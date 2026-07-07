//! Microphone capture — the robust half of the migration (Decision 2).
//!
//! The old Python daemon died here: PortAudio's `Pa_CloseStream` re-enters the
//! CoreAudio HAL lock and deadlocks after sleep/wake/device-change, and the
//! only escape was `os._exit(1)` + launchd respawn. Two decisions fix that:
//!
//! 1. **cpal owns the stream.** Its `drop()` is a clean stop→uninit→dispose
//!    that never re-enters a lock we don't control. The stream lives on a
//!    dedicated worker thread (cpal streams are `!Send`) and is dropped there.
//! 2. **Teardown is bounded.** `stop()` waits on the worker with a
//!    `recv_timeout`, so the caller (the fn event loop) always makes forward
//!    progress. Worst case is a leaked worker thread — never a hung process.
//!
//! Capture is at the device-native rate; `resample.rs` converts to 16 kHz at
//! stop time. Downmixed to mono in the audio callback.

use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use anyhow::{anyhow, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::SampleFormat;

/// Upper bound on how long `stop()` will wait for the worker to hand back the
/// captured samples. cpal teardown is near-instant; this only fires if the
/// audio thread has genuinely wedged, in which case we return empty and move on
/// rather than blocking dictation forever.
const STOP_TIMEOUT: Duration = Duration::from_secs(2);

/// A captured clip: native-rate mono f32 plus the rate it was captured at.
pub struct Clip {
    pub samples: Vec<f32>,
    pub sample_rate: u32,
}

enum Cmd {
    Start,
    Stop(Sender<Clip>),
    Shutdown,
}

/// Handle to the capture worker thread. Cheap to `start()`/`stop()` repeatedly.
pub struct Recorder {
    cmd_tx: Sender<Cmd>,
    worker: Option<JoinHandle<()>>,
}

impl Recorder {
    pub fn new() -> Self {
        let (cmd_tx, cmd_rx) = mpsc::channel();
        let worker = thread::spawn(move || worker_loop(cmd_rx));
        Self {
            cmd_tx,
            worker: Some(worker),
        }
    }

    /// Begin capturing. Idempotent-ish: a Start while already recording just
    /// rebuilds the stream (the worker drops the old one first).
    pub fn start(&self) {
        if self.cmd_tx.send(Cmd::Start).is_err() {
            log::error!("recorder worker is gone; can't start");
        }
    }

    /// Stop capturing and return the clip. Bounded by `STOP_TIMEOUT`: on a
    /// wedged audio thread it returns an empty clip instead of blocking.
    pub fn stop(&self) -> Clip {
        let (resp_tx, resp_rx) = mpsc::channel();
        if self.cmd_tx.send(Cmd::Stop(resp_tx)).is_err() {
            log::error!("recorder worker is gone; can't stop");
            return Clip {
                samples: Vec::new(),
                sample_rate: 16_000,
            };
        }
        match resp_rx.recv_timeout(STOP_TIMEOUT) {
            Ok(clip) => clip,
            Err(RecvTimeoutError::Timeout) => {
                log::error!("recorder stop timed out ({STOP_TIMEOUT:?}) — audio thread wedged; leaking it");
                Clip {
                    samples: Vec::new(),
                    sample_rate: 16_000,
                }
            }
            Err(RecvTimeoutError::Disconnected) => Clip {
                samples: Vec::new(),
                sample_rate: 16_000,
            },
        }
    }
}

impl Drop for Recorder {
    fn drop(&mut self) {
        let _ = self.cmd_tx.send(Cmd::Shutdown);
        if let Some(w) = self.worker.take() {
            let _ = w.join();
        }
    }
}

/// The live capture stream plus its shared sample buffer and rate. Held only on
/// the worker thread; dropping it stops and disposes the cpal stream.
struct Active {
    stream: cpal::Stream,
    buffer: Arc<Mutex<Vec<f32>>>,
    sample_rate: u32,
}

impl Active {
    /// Stop and dispose the stream, then drain the buffer.
    fn finish(self) -> Clip {
        let Active {
            stream,
            buffer,
            sample_rate,
        } = self;
        drop(stream); // clean stop→uninit→dispose; callback can't fire after this
        // Callback is gone, so the lock is uncontended; recover from poison
        // (a panicked callback) rather than panicking teardown too.
        let mut guard = buffer.lock().unwrap_or_else(|e| e.into_inner());
        let samples = std::mem::take(&mut *guard);
        Clip {
            samples,
            sample_rate,
        }
    }
}

fn worker_loop(cmd_rx: Receiver<Cmd>) {
    let mut active: Option<Active> = None;
    while let Ok(cmd) = cmd_rx.recv() {
        match cmd {
            Cmd::Start => {
                if let Some(old) = active.take() {
                    drop(old); // discard any in-flight stream first
                }
                match build_stream() {
                    Ok(a) => active = Some(a),
                    Err(e) => log::error!("failed to start microphone: {e:#}"),
                }
            }
            Cmd::Stop(resp) => {
                let clip = match active.take() {
                    Some(a) => a.finish(),
                    None => Clip {
                        samples: Vec::new(),
                        sample_rate: 16_000,
                    },
                };
                let _ = resp.send(clip);
            }
            Cmd::Shutdown => break,
        }
    }
}

/// Open the default input device at its native config and start pushing
/// downmixed mono f32 into a shared buffer.
fn build_stream() -> Result<Active> {
    let host = cpal::default_host();
    let device = host
        .default_input_device()
        .ok_or_else(|| anyhow!("no default input device"))?;
    let supported = device
        .default_input_config()
        .map_err(|e| anyhow!("default input config: {e}"))?;

    let sample_rate = supported.sample_rate().0;
    let channels = supported.channels() as usize;
    let format = supported.sample_format();
    let config: cpal::StreamConfig = supported.into();

    log::info!(
        "capturing: {} @ {sample_rate} Hz, {channels} ch, {format:?}",
        device.name().unwrap_or_else(|_| "?".into())
    );

    let buffer = Arc::new(Mutex::new(Vec::<f32>::new()));
    let err_fn = |e| log::error!("audio stream error: {e}");

    // Each format arm downmixes N interleaved channels to one mono sample per
    // frame (simple average) and appends to the buffer.
    let stream = match format {
        SampleFormat::F32 => {
            let buf = Arc::clone(&buffer);
            device.build_input_stream(
                &config,
                move |data: &[f32], _| push_mono(data, channels, &buf, |s| s),
                err_fn,
                None,
            )
        }
        SampleFormat::I16 => {
            let buf = Arc::clone(&buffer);
            device.build_input_stream(
                &config,
                move |data: &[i16], _| push_mono(data, channels, &buf, |s| s as f32 / 32768.0),
                err_fn,
                None,
            )
        }
        SampleFormat::U16 => {
            let buf = Arc::clone(&buffer);
            device.build_input_stream(
                &config,
                move |data: &[u16], _| {
                    push_mono(data, channels, &buf, |s| (s as f32 - 32768.0) / 32768.0)
                },
                err_fn,
                None,
            )
        }
        other => return Err(anyhow!("unsupported sample format: {other:?}")),
    }
    .map_err(|e| anyhow!("build input stream: {e}"))?;

    stream.play().map_err(|e| anyhow!("stream play: {e}"))?;
    Ok(Active {
        stream,
        buffer,
        sample_rate,
    })
}

/// Downmix interleaved frames to mono f32 (via `to_f32`) and append.
fn push_mono<T: Copy>(
    data: &[T],
    channels: usize,
    buffer: &Arc<Mutex<Vec<f32>>>,
    to_f32: impl Fn(T) -> f32,
) {
    if channels == 0 {
        return;
    }
    let mut buf = match buffer.lock() {
        Ok(b) => b,
        Err(_) => return,
    };
    for frame in data.chunks(channels) {
        let sum: f32 = frame.iter().map(|&s| to_f32(s)).sum();
        buf.push(sum / channels as f32);
    }
}

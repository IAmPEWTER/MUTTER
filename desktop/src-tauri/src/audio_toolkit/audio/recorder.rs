use std::{
    io::Error,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Mutex,
    },
    time::Duration,
};

use cpal::{
    traits::{DeviceTrait, HostTrait, StreamTrait},
    Device, Sample, SizedSample,
};

use crate::audio_toolkit::{
    audio::{AudioVisualiser, FrameResampler},
    constants,
    vad::{self, VadFrame},
    VoiceActivityDetector,
};

enum Cmd {
    Start(VadPolicy),
    Stop(mpsc::Sender<Vec<f32>>),
    Shutdown,
}

enum AudioChunk {
    Samples(Vec<f32>),
    EndOfStream,
}

/// How 16 kHz mono frames should be filtered for one recording session.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VadPolicy {
    /// Bypass VAD and forward every frame.
    Disabled,
    /// Current offline-tuned VAD profile.
    Offline,
    /// VAD profile with a longer post-speech tail for streaming-capable models.
    Streaming,
}

/// A single VAD engine plus the two hangover-tail lengths its smoothing wrapper
/// should use. The offline and streaming policies are never active
/// concurrently, so one detector is reconfigured per session (see `Cmd::Start`)
/// rather than kept as two resident engines.
#[derive(Clone)]
struct VadConfig {
    detector: Arc<Mutex<Box<dyn vad::VoiceActivityDetector>>>,
    offline_hangover_frames: usize,
    streaming_hangover_frames: usize,
}

impl VadConfig {
    /// Post-speech hangover tail (in 30 ms frames) for the given policy.
    /// `Disabled` never reaches the detector, so it maps to the offline value.
    fn hangover_for(&self, policy: VadPolicy) -> usize {
        match policy {
            VadPolicy::Streaming => self.streaming_hangover_frames,
            VadPolicy::Offline | VadPolicy::Disabled => self.offline_hangover_frames,
        }
    }
}

/// Callback invoked with each 16 kHz mono frame that passes the active capture
/// policy while recording. Used to feed a live streaming transcription as audio arrives.
pub type AudioFrameCallback = Arc<dyn Fn(&[f32]) + Send + Sync + 'static>;

/// Upper bound on how long `stop()`/`close()` will wait on the recorder's
/// consumer/worker thread before giving up. Guards against a permanently
/// silent cpal input callback (e.g. an external mic unplugged mid-recording
/// and never restored) wedging the caller forever while holding the
/// recorder lock (see docs/handy-migration-spec.md §4/§6). `stop()`/`close()`
/// must always return within this bound: on timeout we log loudly and give
/// up rather than hang, regardless of what the audio callback does.
const RECORDER_STOP_TIMEOUT: Duration = Duration::from_secs(5);

pub struct AudioRecorder {
    device: Option<Device>,
    cmd_tx: Option<mpsc::Sender<Cmd>>,
    worker_handle: Option<std::thread::JoinHandle<()>>,
    vad: Option<VadConfig>,
    level_cb: Option<Arc<dyn Fn(Vec<f32>) + Send + Sync + 'static>>,
    audio_cb: Option<AudioFrameCallback>,
}

impl AudioRecorder {
    pub fn new() -> Result<Self, Box<dyn std::error::Error>> {
        Ok(AudioRecorder {
            device: None,
            cmd_tx: None,
            worker_handle: None,
            vad: None,
            level_cb: None,
            audio_cb: None,
        })
    }

    /// Attach a single VAD engine, reconfigured per session for the offline vs
    /// streaming hangover tail. The two policies are mutually exclusive within a
    /// recording, so one engine covers both instead of two resident instances.
    pub fn with_vad(
        mut self,
        detector: Box<dyn VoiceActivityDetector>,
        offline_hangover_frames: usize,
        streaming_hangover_frames: usize,
    ) -> Self {
        self.vad = Some(VadConfig {
            detector: Arc::new(Mutex::new(detector)),
            offline_hangover_frames,
            streaming_hangover_frames,
        });
        self
    }

    pub fn with_level_callback<F>(mut self, cb: F) -> Self
    where
        F: Fn(Vec<f32>) + Send + Sync + 'static,
    {
        self.level_cb = Some(Arc::new(cb));
        self
    }

    /// Register a callback that receives real-time 16 kHz frames after the active
    /// VAD policy has been applied. Frames arrive in real time, in order, on the
    /// recorder's consumer thread — keep the callback cheap (e.g. forward to a
    /// channel) so it never stalls capture.
    pub fn with_audio_callback<F>(mut self, cb: F) -> Self
    where
        F: Fn(&[f32]) + Send + Sync + 'static,
    {
        self.audio_cb = Some(Arc::new(cb));
        self
    }

    pub fn open(&mut self, device: Option<Device>) -> Result<(), Box<dyn std::error::Error>> {
        if self.worker_handle.is_some() {
            return Ok(()); // already open
        }

        let (sample_tx, sample_rx) = mpsc::channel::<AudioChunk>();
        let (cmd_tx, cmd_rx) = mpsc::channel::<Cmd>();
        let (init_tx, init_rx) = mpsc::sync_channel::<Result<(), String>>(1);

        let host = crate::audio_toolkit::get_cpal_host();
        let device = match device {
            Some(dev) => dev,
            None => host
                .default_input_device()
                .ok_or_else(|| Error::new(std::io::ErrorKind::NotFound, "No input device found"))?,
        };

        let thread_device = device.clone();
        let vad = self.vad.clone();
        // Move the optional level callback into the worker thread
        let level_cb = self.level_cb.clone();
        // Move the optional real-time audio frame callback into the worker thread
        let audio_cb = self.audio_cb.clone();

        let worker = std::thread::spawn(move || {
            let stop_flag = Arc::new(AtomicBool::new(false));
            let stop_flag_for_stream = stop_flag.clone();
            let init_result = (|| -> Result<(cpal::Stream, u32), String> {
                let config = AudioRecorder::get_preferred_config(&thread_device)
                    .map_err(|e| format!("Failed to fetch preferred config: {e}"))?;

                let sample_rate = config.sample_rate().0;
                let channels = config.channels() as usize;

                log::info!(
                    "Using device: {:?}\nSample rate: {}\nChannels: {}\nFormat: {:?}",
                    thread_device.name(),
                    sample_rate,
                    channels,
                    config.sample_format()
                );

                let stream = match config.sample_format() {
                    cpal::SampleFormat::U8 => AudioRecorder::build_stream::<u8>(
                        &thread_device,
                        &config,
                        sample_tx,
                        channels,
                        stop_flag_for_stream,
                    )
                    .map_err(|e| format!("Failed to build input stream: {e}"))?,
                    cpal::SampleFormat::I8 => AudioRecorder::build_stream::<i8>(
                        &thread_device,
                        &config,
                        sample_tx,
                        channels,
                        stop_flag_for_stream,
                    )
                    .map_err(|e| format!("Failed to build input stream: {e}"))?,
                    cpal::SampleFormat::I16 => AudioRecorder::build_stream::<i16>(
                        &thread_device,
                        &config,
                        sample_tx,
                        channels,
                        stop_flag_for_stream,
                    )
                    .map_err(|e| format!("Failed to build input stream: {e}"))?,
                    cpal::SampleFormat::I32 => AudioRecorder::build_stream::<i32>(
                        &thread_device,
                        &config,
                        sample_tx,
                        channels,
                        stop_flag_for_stream,
                    )
                    .map_err(|e| format!("Failed to build input stream: {e}"))?,
                    cpal::SampleFormat::F32 => AudioRecorder::build_stream::<f32>(
                        &thread_device,
                        &config,
                        sample_tx,
                        channels,
                        stop_flag_for_stream,
                    )
                    .map_err(|e| format!("Failed to build input stream: {e}"))?,
                    sample_format => {
                        return Err(format!("Unsupported sample format: {sample_format:?}"));
                    }
                };

                stream
                    .play()
                    .map_err(|e| format!("Failed to start microphone stream: {e}"))?;

                Ok((stream, sample_rate))
            })();

            match init_result {
                Ok((stream, sample_rate)) => {
                    let _ = init_tx.send(Ok(()));
                    // Keep the stream alive while we process samples.
                    run_consumer(
                        sample_rate,
                        vad,
                        sample_rx,
                        cmd_rx,
                        level_cb,
                        audio_cb,
                        stop_flag,
                    );
                    drop(stream);
                }
                Err(error_message) => {
                    log::error!("{error_message}");
                    let _ = init_tx.send(Err(error_message));
                }
            }
        });

        match init_rx.recv() {
            Ok(Ok(())) => {
                self.device = Some(device);
                self.cmd_tx = Some(cmd_tx);
                self.worker_handle = Some(worker);
                Ok(())
            }
            Ok(Err(error_message)) => {
                let _ = worker.join();
                let kind = if is_microphone_access_denied(&error_message) {
                    std::io::ErrorKind::PermissionDenied
                } else {
                    std::io::ErrorKind::Other
                };
                Err(Box::new(Error::new(kind, error_message)))
            }
            Err(recv_error) => {
                let _ = worker.join();
                Err(Box::new(Error::other(format!(
                    "Failed to initialize microphone worker: {recv_error}"
                ))))
            }
        }
    }

    pub fn start(&self, vad_policy: VadPolicy) -> Result<(), Box<dyn std::error::Error>> {
        if let Some(tx) = &self.cmd_tx {
            tx.send(Cmd::Start(vad_policy))?;
        }
        Ok(())
    }

    pub fn stop(&self) -> Result<Vec<f32>, Box<dyn std::error::Error>> {
        let (resp_tx, resp_rx) = mpsc::channel();
        if let Some(tx) = &self.cmd_tx {
            tx.send(Cmd::Stop(resp_tx))?;
        }
        // Bounded wait for the samples: a permanently silent cpal callback
        // (e.g. an unplugged/stalled input device) must never wedge the
        // caller forever.
        match resp_rx.recv_timeout(RECORDER_STOP_TIMEOUT) {
            Ok(samples) => Ok(samples),
            Err(_) => {
                log::error!(
                    "Recorder did not respond to stop() within {RECORDER_STOP_TIMEOUT:?}; \
                     giving up rather than hang (likely a stalled/unplugged input device)"
                );
                Err(Box::new(Error::other(
                    "Timed out waiting for recorder to stop",
                )))
            }
        }
    }

    pub fn close(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        if let Some(tx) = self.cmd_tx.take() {
            let _ = tx.send(Cmd::Shutdown);
        }
        if let Some(h) = self.worker_handle.take() {
            join_with_timeout(h, RECORDER_STOP_TIMEOUT)?;
        }
        self.device = None;
        Ok(())
    }

    fn build_stream<T>(
        device: &cpal::Device,
        config: &cpal::SupportedStreamConfig,
        sample_tx: mpsc::Sender<AudioChunk>,
        channels: usize,
        stop_flag: Arc<AtomicBool>,
    ) -> Result<cpal::Stream, cpal::BuildStreamError>
    where
        T: Sample + SizedSample + Send + 'static,
        f32: cpal::FromSample<T>,
    {
        let mut output_buffer = Vec::new();
        let mut eos_sent = false;

        let stream_cb = move |data: &[T], _: &cpal::InputCallbackInfo| {
            if stop_flag.load(Ordering::Relaxed) {
                if !eos_sent {
                    let _ = sample_tx.send(AudioChunk::EndOfStream);
                    eos_sent = true;
                }
                return;
            }
            eos_sent = false;

            output_buffer.clear();

            if channels == 1 {
                output_buffer.extend(data.iter().map(|&sample| sample.to_sample::<f32>()));
            } else {
                let frame_count = data.len() / channels;
                output_buffer.reserve(frame_count);

                for frame in data.chunks_exact(channels) {
                    let mono_sample = frame
                        .iter()
                        .map(|&sample| sample.to_sample::<f32>())
                        .sum::<f32>()
                        / channels as f32;
                    output_buffer.push(mono_sample);
                }
            }

            if sample_tx
                .send(AudioChunk::Samples(output_buffer.clone()))
                .is_err()
            {
                log::error!("Failed to send samples");
            }
        };

        device.build_input_stream(
            &config.clone().into(),
            stream_cb,
            |err| log::error!("Stream error: {}", err),
            None,
        )
    }

    fn get_preferred_config(
        device: &cpal::Device,
    ) -> Result<cpal::SupportedStreamConfig, Box<dyn std::error::Error>> {
        // Use the device's native/default sample rate and let the FrameResampler
        // in run_consumer() downsample to 16kHz. This avoids forcing hardware into
        // a non-native rate which can cause issues on some devices (Bluetooth
        // codecs, certain ALSA drivers, etc.).
        let default_config = device.default_input_config()?;
        let target_rate = default_config.sample_rate();

        // Try to find the best sample format at the device's default rate
        let supported_configs = match device.supported_input_configs() {
            Ok(configs) => configs,
            Err(e) => {
                log::warn!("Could not enumerate input configs ({e}), using device default");
                return Ok(default_config);
            }
        };
        let mut best_config: Option<cpal::SupportedStreamConfigRange> = None;

        for config_range in supported_configs {
            if config_range.min_sample_rate() <= target_rate
                && config_range.max_sample_rate() >= target_rate
            {
                match best_config {
                    None => best_config = Some(config_range),
                    Some(ref current) => {
                        // Prioritize F32 > I16 > I32 > others
                        let score = |fmt: cpal::SampleFormat| match fmt {
                            cpal::SampleFormat::F32 => 4,
                            cpal::SampleFormat::I16 => 3,
                            cpal::SampleFormat::I32 => 2,
                            _ => 1,
                        };

                        if score(config_range.sample_format()) > score(current.sample_format()) {
                            best_config = Some(config_range);
                        }
                    }
                }
            }
        }

        if let Some(config) = best_config {
            return Ok(config.with_sample_rate(target_rate));
        }

        // Fall back to device default if no config matched (exotic/virtual devices)
        log::warn!(
            "No supported config matched device default rate {:?}, using default config",
            target_rate
        );
        Ok(default_config)
    }
}

/// Joins a worker thread, but never blocks longer than `timeout`. If the
/// thread hasn't exited by then, the handle is abandoned (the underlying OS
/// thread keeps running to completion in the background) instead of blocking
/// the caller — a stalled worker can never wedge `close()`.
fn join_with_timeout(
    handle: std::thread::JoinHandle<()>,
    timeout: Duration,
) -> Result<(), Box<dyn std::error::Error>> {
    let (done_tx, done_rx) = mpsc::channel::<()>();
    std::thread::spawn(move || {
        let _ = handle.join();
        let _ = done_tx.send(());
    });

    match done_rx.recv_timeout(timeout) {
        Ok(()) => Ok(()),
        Err(_) => {
            log::error!(
                "Recorder worker thread did not exit within {timeout:?}; abandoning it \
                 rather than hang (likely a stalled/unplugged input device)"
            );
            Err(Box::new(Error::other(
                "Timed out waiting for recorder worker thread to exit",
            )))
        }
    }
}

pub fn is_microphone_access_denied(error_message: &str) -> bool {
    let normalized = error_message.to_lowercase();
    normalized.contains("access is denied")
        || normalized.contains("permission denied")
        || normalized.contains("0x80070005")
}

pub fn is_no_input_device_error(error_message: &str) -> bool {
    let normalized = error_message.to_lowercase();
    normalized.contains("no input device found")
        || (normalized.contains("failed to fetch preferred config")
            && normalized.contains("coreaudio"))
}

#[cfg(test)]
mod tests {
    use super::{
        is_microphone_access_denied, is_no_input_device_error, join_with_timeout, run_consumer,
        AudioChunk, Cmd,
    };
    use std::sync::{atomic::AtomicBool, mpsc, Arc};
    use std::time::{Duration, Instant};

    #[test]
    fn detects_access_is_denied() {
        assert!(is_microphone_access_denied("Access is denied"));
    }

    #[test]
    fn detects_permission_denied() {
        assert!(is_microphone_access_denied("permission denied"));
    }

    #[test]
    fn detects_windows_error_code() {
        assert!(is_microphone_access_denied("WASAPI error: 0x80070005"));
    }

    #[test]
    fn does_not_match_unrelated_errors() {
        assert!(!is_microphone_access_denied("device not found"));
    }

    #[test]
    fn detects_no_input_device() {
        assert!(is_no_input_device_error("No input device found"));
    }

    #[test]
    fn detects_coreaudio_config_error() {
        assert!(is_no_input_device_error(
            "Failed to fetch preferred config: A backend-specific error has occurred: An unknown error unknown to the coreaudio-rs API occurred"
        ));
    }

    #[test]
    fn does_not_match_other_errors_for_no_device() {
        assert!(!is_no_input_device_error("permission denied"));
        assert!(!is_no_input_device_error("device not found"));
    }

    /// The actual wedge (see docs/handy-migration-spec.md §4/§6): a permanently
    /// silent cpal input callback (mic unplugged mid-recording) means
    /// `sample_tx` never sends another `AudioChunk`, so `run_consumer`'s main
    /// loop must not block on `sample_rx` forever — it has to keep servicing
    /// `cmd_rx` on a bounded cadence, or a pending `Stop`/`Shutdown` can never
    /// be processed. No real audio device needed: `run_consumer` only depends
    /// on channels.
    #[test]
    fn run_consumer_services_stop_when_producer_goes_permanently_silent() {
        let (sample_tx, sample_rx) = mpsc::channel::<AudioChunk>();
        let (cmd_tx, cmd_rx) = mpsc::channel::<Cmd>();
        let stop_flag = Arc::new(AtomicBool::new(false));

        let worker = std::thread::spawn(move || {
            run_consumer(16000, None, sample_rx, cmd_rx, None, None, stop_flag);
        });

        // Keep the producer's sender alive (as cpal would, mid-stream) but
        // never send on it — simulating a stalled/unplugged input device.
        let _silent_producer = sample_tx;

        let (resp_tx, resp_rx) = mpsc::channel();
        cmd_tx.send(Cmd::Stop(resp_tx)).unwrap();

        let start = Instant::now();
        let result = resp_rx.recv_timeout(Duration::from_secs(3));
        assert!(
            result.is_ok(),
            "stop() was not serviced within bounded time while the producer was silent"
        );
        assert!(
            start.elapsed() < Duration::from_secs(3),
            "servicing Stop took unexpectedly long: {:?}",
            start.elapsed()
        );

        cmd_tx.send(Cmd::Shutdown).unwrap();
        worker.join().expect("consumer thread should exit cleanly");
    }

    /// Defense-in-depth check on the `close()` call site: even if a worker
    /// thread never exits, `join_with_timeout` must still bound the wait
    /// rather than hang, and it must return promptly on the happy path.
    #[test]
    fn join_with_timeout_returns_promptly_when_thread_exits_quickly() {
        let handle = std::thread::spawn(|| {
            std::thread::sleep(Duration::from_millis(10));
        });
        assert!(join_with_timeout(handle, Duration::from_secs(2)).is_ok());
    }

    #[test]
    fn join_with_timeout_bounds_wait_when_thread_never_exits() {
        // Simulates a permanently wedged worker thread — the exact shape of
        // the real wedge this guards against.
        let handle = std::thread::spawn(|| {
            std::thread::sleep(Duration::from_secs(3600));
        });

        let start = Instant::now();
        let result = join_with_timeout(handle, Duration::from_millis(200));
        assert!(result.is_err());
        assert!(
            start.elapsed() < Duration::from_secs(2),
            "join_with_timeout did not bound the wait: took {:?}",
            start.elapsed()
        );
    }
}

#[allow(clippy::too_many_arguments)]
fn run_consumer(
    in_sample_rate: u32,
    vad: Option<VadConfig>,
    sample_rx: mpsc::Receiver<AudioChunk>,
    cmd_rx: mpsc::Receiver<Cmd>,
    level_cb: Option<Arc<dyn Fn(Vec<f32>) + Send + Sync + 'static>>,
    audio_cb: Option<AudioFrameCallback>,
    stop_flag: Arc<AtomicBool>,
) {
    let mut frame_resampler = FrameResampler::new(
        in_sample_rate as usize,
        constants::WHISPER_SAMPLE_RATE as usize,
        Duration::from_millis(30),
    );

    let mut processed_samples = Vec::<f32>::new();
    let mut recording = false;
    let mut vad_policy = VadPolicy::Offline;

    // ---------- spectrum visualisation setup ---------------------------- //
    const BUCKETS: usize = 16;
    // Scale the FFT window to the device sample rate so the analysis window
    // (~33 ms) and frequency resolution (~30 Hz/bin) stay roughly constant
    // across devices. A fixed 512-sample window collapses the low vocal
    // buckets onto a single bin at 48 kHz (e.g. built-in laptop mics), and
    // would stutter at ~4-8 updates/sec on an 8-16 kHz Bluetooth headset.
    // Targets: 48 kHz -> 2048, 16 kHz -> 512, 8 kHz -> 256.
    let target_window = (f64::from(in_sample_rate) / 30.0).round() as usize;
    let window_size = [256usize, 512, 1024, 2048]
        .into_iter()
        .min_by_key(|w| w.abs_diff(target_window))
        .unwrap();
    let mut visualizer = AudioVisualiser::new(
        in_sample_rate,
        window_size,
        BUCKETS,
        400.0,  // vocal_min_hz
        4000.0, // vocal_max_hz
    );

    fn handle_frame(
        samples: &[f32],
        recording: bool,
        vad_policy: VadPolicy,
        vad: &Option<VadConfig>,
        audio_cb: &Option<AudioFrameCallback>,
        out_buf: &mut Vec<f32>,
    ) {
        if !recording {
            return;
        }

        let mut emit = |buf: &[f32]| {
            out_buf.extend_from_slice(buf);
            if let Some(cb) = audio_cb {
                cb(buf);
            }
        };

        if vad_policy == VadPolicy::Disabled {
            emit(samples);
            return;
        }

        if let Some(cfg) = vad {
            let mut det = cfg.detector.lock().unwrap();
            match det.push_frame(samples).unwrap_or(VadFrame::Speech(samples)) {
                VadFrame::Speech(buf) => emit(buf),
                VadFrame::Noise => {}
            }
        } else {
            emit(samples);
        }
    }

    // Poll interval for the outer loop when no audio is arriving. A
    // permanently silent producer (e.g. an unplugged/stalled input device)
    // must not stop a pending Stop/Shutdown from being serviced, so the loop
    // wakes on this cadence — even with nothing in `sample_rx` — to check
    // `cmd_rx` below. This is the fix for the stop()/close() wedge: on the
    // happy path (chunks arriving normally) behavior is unchanged, since
    // `recv_timeout` returns immediately once a chunk is available.
    const IDLE_POLL_INTERVAL: Duration = Duration::from_millis(250);

    // Runs until the stream closes and the channel disconnects.
    loop {
        match sample_rx.recv_timeout(IDLE_POLL_INTERVAL) {
            Ok(chunk) => {
                let raw = match chunk {
                    AudioChunk::Samples(s) => s,
                    AudioChunk::EndOfStream => continue,
                };

                // ---------- spectrum processing ---------------------------------- //
                if let Some(buckets) = visualizer.feed(&raw) {
                    if let Some(cb) = &level_cb {
                        cb(buckets);
                    }
                }

                // ---------- existing pipeline ------------------------------------ //
                frame_resampler.push(&raw, &mut |frame: &[f32]| {
                    handle_frame(
                        frame,
                        recording,
                        vad_policy,
                        &vad,
                        &audio_cb,
                        &mut processed_samples,
                    )
                });
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {
                // No audio arrived this interval — fall through to the
                // command check below anyway.
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }

        // non-blocking check for a command
        while let Ok(cmd) = cmd_rx.try_recv() {
            match cmd {
                Cmd::Start(policy) => {
                    stop_flag.store(false, Ordering::Relaxed);
                    vad_policy = policy;
                    processed_samples.clear();
                    recording = true;
                    visualizer.reset();
                    // Reconfigure the single VAD engine for this session's policy
                    // and clear its smoothing + recurrent state before it sees
                    // any frames.
                    if vad_policy != VadPolicy::Disabled {
                        if let Some(cfg) = &vad {
                            let mut det = cfg.detector.lock().unwrap();
                            det.set_hangover_frames(cfg.hangover_for(vad_policy));
                            det.reset();
                        }
                    }
                }
                Cmd::Stop(reply_tx) => {
                    recording = false;
                    stop_flag.store(true, Ordering::Relaxed);

                    // Drain all remaining audio until the producer confirms end-of-stream.
                    // The cpal callback sees the stop flag, sends EndOfStream, and goes
                    // silent — guaranteeing every captured sample is in the channel
                    // ahead of the sentinel.
                    loop {
                        match sample_rx.recv_timeout(Duration::from_secs(2)) {
                            Ok(AudioChunk::Samples(remaining)) => {
                                frame_resampler.push(&remaining, &mut |frame: &[f32]| {
                                    handle_frame(
                                        frame,
                                        true,
                                        vad_policy,
                                        &vad,
                                        &audio_cb,
                                        &mut processed_samples,
                                    )
                                });
                            }
                            Ok(AudioChunk::EndOfStream) => break,
                            Err(_) => {
                                log::warn!("Timed out waiting for EndOfStream from audio callback");
                                break;
                            }
                        }
                    }

                    frame_resampler.finish(&mut |frame: &[f32]| {
                        handle_frame(
                            frame,
                            true,
                            vad_policy,
                            &vad,
                            &audio_cb,
                            &mut processed_samples,
                        )
                    });

                    let _ = reply_tx.send(std::mem::take(&mut processed_samples));

                    // Resume the audio callback so the consumer loop can continue
                    // receiving chunks (important for always-on microphone mode).
                    stop_flag.store(false, Ordering::Relaxed);
                }
                Cmd::Shutdown => {
                    stop_flag.store(true, Ordering::Relaxed);
                    return;
                }
            }
        }
    }
}

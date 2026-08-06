//! MUTTER — headless push-to-talk dictation daemon for macOS.
//!
//! Hold fn, speak, release; the words are typed at the cursor. No window, no
//! tray, no dock icon — a background agent, exactly like the old Python daemon,
//! but built on the two architectural decisions that made the vendored Rust
//! build robust where the Python one wasn't:
//!
//!   Decision 1 — the trigger (`handy-keys`): the CGEventTap is re-enabled every
//!   100 ms and fn state is re-derived from the OS flags on every event, so a
//!   tap macOS disables under load/sleep heals instead of silently dropping
//!   fn-up. (Old daemon armed the tap once and blocked in CFRunLoopRun — nothing
//!   re-armed it.)
//!
//!   Decision 2 — the audio (`recorder.rs` + cpal): the capture stream's drop is
//!   a clean stop→dispose that never re-enters the CoreAudio HAL lock, and
//!   teardown is bounded by a timeout. (Old daemon's PortAudio Pa_CloseStream
//!   re-entered the HAL lock and deadlocked after sleep/wake — only cure was
//!   os._exit + launchd respawn.)
//!
//! Everything slow (resample + transcribe + inject) runs on a single FIFO
//! worker thread so the fn event loop is always free to catch the next press.

mod frontmost;
mod hallucination;
mod hardware;
mod inject;
mod keycodes;
mod recorder;
mod resample;
mod speech;
mod whisper;

use std::sync::mpsc::{self, Sender};
use std::thread;
use std::time::{Duration, Instant};

use handy_keys::{Hotkey, HotkeyManager, HotkeyState, Modifiers};

use recorder::{Clip, Recorder};
use whisper::Client;

/// Language hint sent to whisper. `"en"` skips ~50 ms of language detection —
/// same choice the old daemon made (`stt.py` default `language="en"`).
const LANGUAGE: &str = "en";

/// How long to wait for the shared whisper service at startup before giving up
/// and letting launchd respawn us. Matches the old daemon's 180 s ceiling.
const WHISPER_WAIT: Duration = Duration::from_secs(180);

/// Clips shorter than this are "the user didn't say anything" — dropped before
/// they reach whisper (an accidental fn tap must never type). 0.3 s at the
/// 16 kHz service rate, mirroring `stt.py`'s `MIN_CAPTURED_SEC`.
const MIN_CLIP_SAMPLES: usize = 16_000 * 3 / 10;

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    if !handy_keys::check_accessibility() {
        log::warn!(
            "Accessibility not granted — the fn tap won't arm. Grant it in \
             System Settings → Privacy & Security → Accessibility, then relaunch."
        );
    }

    // Fail fast (for launchd to respawn) if the ASR brain is unreachable — a
    // dead service should surface at startup, not mid-dictation.
    let client = Client::new();
    if let Err(e) = wait_for_whisper(&client) {
        log::error!("{e:#}");
        std::process::exit(1);
    }

    let recorder = Recorder::new();
    let tx = spawn_transcribe_worker(client);

    let manager = match HotkeyManager::new() {
        Ok(m) => m,
        Err(e) => {
            log::error!("failed to start the fn key listener: {e}. Is Accessibility granted?");
            std::process::exit(1);
        }
    };
    let fn_key = Hotkey::new(Modifiers::FN, None).expect("FN is a valid modifier-only hotkey");
    if let Err(e) = manager.register(fn_key) {
        log::error!("failed to register the fn hotkey: {e}");
        std::process::exit(1);
    }

    log::info!("mutter: ready — hold fn to dictate  pid={}", std::process::id());

    // The fn event loop. Never does slow work: press starts capture, release
    // stops it (bounded) and hands the clip to the worker.
    //
    // `hardware::Watch` runs alongside the hold to answer "did this press come
    // from this Mac's own keyboard?" — a Screen-Sharing-forwarded fn is
    // identical by keycode but never moves the hardware flag state. Recording
    // still starts immediately, so the check costs a real dictation nothing.
    let mut fn_watch: Option<hardware::Watch> = None;
    loop {
        match manager.recv() {
            Ok(event) => match event.state {
                HotkeyState::Pressed => {
                    fn_watch = Some(hardware::Watch::start());
                    inject::set_system_muted(true);
                    recorder.start();
                }
                HotkeyState::Released => {
                    inject::set_system_muted(false);
                    let clip = recorder.stop();
                    // `None` means a release with no matching press we saw;
                    // treat it as local so a state glitch can't eat dictation.
                    let from_this_keyboard = fn_watch.take().is_none_or(hardware::Watch::finish);
                    if !from_this_keyboard {
                        log::info!(
                            "fn was not physically down — forwarded press (Screen Sharing); \
                             dropping {} samples without transcribing",
                            clip.samples.len()
                        );
                    } else if !clip.samples.is_empty() {
                        if let Err(e) = tx.send(clip) {
                            log::error!("transcribe worker gone; dropping clip: {e}");
                        }
                    }
                }
            },
            Err(e) => {
                // The listener thread died — without it there is no trigger.
                // Exit so launchd respawns a fresh tap.
                log::error!("fn listener stopped: {e}; exiting for respawn");
                std::process::exit(1);
            }
        }
    }
}

/// Single FIFO consumer: resample → transcribe → inject, in strict spoken
/// order. Never dies — a failed clip logs and the loop continues.
fn spawn_transcribe_worker(client: Client) -> Sender<Clip> {
    let (tx, rx) = mpsc::channel::<Clip>();
    thread::spawn(move || {
        while let Ok(clip) = rx.recv() {
            let pcm16k = resample::to_16k(&clip.samples, clip.sample_rate);
            if pcm16k.len() < MIN_CLIP_SAMPLES {
                log::info!("clip too short ({} ms); ignoring", pcm16k.len() / 16);
                continue;
            }
            // R4: silence must never reach whisper — it answers with boilerplate.
            let speech = speech::detect(&pcm16k, 16_000);
            if !speech.is_speech {
                log::info!(
                    "no speech in {} ms (threshold {:.0}, {} loud blocks, longest run {}); \
                     not transcribing",
                    pcm16k.len() / 16,
                    speech.threshold,
                    speech.loud_blocks,
                    speech.longest_run_blocks
                );
                continue;
            }
            match client.transcribe(&pcm16k, 16_000, Some(LANGUAGE)) {
                Ok(text) if hallucination::is_hallucination(&text) => {
                    log::info!("dropped hallucination/empty transcript: {:?}", text.trim());
                }
                Ok(text) => inject::inject(&text),
                Err(e) => log::error!("transcription failed: {e:#}"),
            }
        }
    });
    tx
}

/// Ping the whisper service, retrying until it answers or `WHISPER_WAIT`
/// elapses. Returns `Err` only if it never came up.
fn wait_for_whisper(client: &Client) -> anyhow::Result<()> {
    if client.ping().is_ok() {
        return Ok(());
    }
    log::info!("waiting for whisper service (up to {}s)...", WHISPER_WAIT.as_secs());
    let deadline = Instant::now() + WHISPER_WAIT;
    while Instant::now() < deadline {
        thread::sleep(Duration::from_secs(2));
        if client.ping().is_ok() {
            return Ok(());
        }
    }
    Err(anyhow::anyhow!(
        "whisper service unreachable at {} after {}s — is it loaded? \
         (launchctl list | grep whisper)",
        whisper::sock_path().display(),
        WHISPER_WAIT.as_secs()
    ))
}

#[cfg(test)]
mod smoke {
    //! Offline end-to-end check of the audio→ASR path against the LIVE whisper
    //! service. `#[ignore]` by default (needs the service running + a WAV), run
    //! with:
    //!
    //! ```sh
    //! say --file-format=WAVE --data-format=LEF32@48000 -o /tmp/k.wav \
    //!   "the quick brown fox jumps over the lazy dog"
    //! MUTTER_SMOKE_WAV=/tmp/k.wav cargo test --release -- --ignored smoke
    //! ```
    //!
    //! Reads a float/int WAV at any rate, resamples to 16 kHz exactly as the
    //! daemon does, and asserts the transcript comes back non-empty.
    use crate::{resample, whisper};

    #[test]
    #[ignore = "needs the live whisper service and MUTTER_SMOKE_WAV set"]
    fn smoke_transcribe_wav() {
        let path = std::env::var("MUTTER_SMOKE_WAV")
            .expect("set MUTTER_SMOKE_WAV to a WAV path");
        let mut reader = hound::WavReader::open(&path).expect("open wav");
        let spec = reader.spec();
        let samples: Vec<f32> = match spec.sample_format {
            hound::SampleFormat::Float => {
                reader.samples::<f32>().map(|s| s.unwrap()).collect()
            }
            hound::SampleFormat::Int => {
                let max = (1i64 << (spec.bits_per_sample - 1)) as f32;
                reader.samples::<i32>().map(|s| s.unwrap() as f32 / max).collect()
            }
        };
        // Downmix to mono if needed (average interleaved channels).
        let ch = spec.channels as usize;
        let mono: Vec<f32> = if ch <= 1 {
            samples
        } else {
            samples.chunks(ch).map(|f| f.iter().sum::<f32>() / ch as f32).collect()
        };

        let pcm16k = resample::to_16k(&mono, spec.sample_rate);
        let text = whisper::Client::new()
            .transcribe(&pcm16k, 16_000, Some("en"))
            .expect("transcribe");
        eprintln!("smoke transcript: {text:?}");
        assert!(!text.trim().is_empty(), "expected non-empty transcript");
    }
}

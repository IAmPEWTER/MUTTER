//! Tier A — the authoritative "real audio -> text" proof.
//!
//! Migration definition-of-done (docs/handy-migration-spec.md §0 goal 2, §6)
//! requires an automated test that exercises the *real* capture DSP on *real*
//! speech, not unit fakes. This test does exactly that, deterministically and
//! without any OS mic / CoreAudio flakiness:
//!
//!   checked-in speech WAV (16 kHz mono s16le)
//!     -> real WAV decode (`read_wav_samples`)
//!     -> real Silero VAD stack (`SmoothedVad<SileroVad>` + the exact constants
//!        and threshold `managers::audio::create_audio_recorder` builds), driven
//!        through the real `FrameResampler` the recorder consumer uses
//!     -> real `RemoteSocket` engine client -> live MLX whisper daemon
//!     -> assert the transcript contains the fixture's key content words.
//!
//! The framing/VAD segmentation here is the app's own code: the only thing
//! reconstructed is `run_consumer::handle_frame`'s three-line Speech/Noise match
//! (`run_consumer` itself is private and takes private channel types). The VAD
//! engine, the smoothing wrapper, its constants/threshold, and the resampler are
//! all the real recorder pipeline — cpal is the only thing bypassed, which is the
//! whole point (it's the flaky OS layer this test deliberately removes).
//!
//! Gating: the VAD assertions always run (no daemon needed). The daemon
//! round-trip only asserts when the whisper socket is present; when it is absent
//! (CI, or any machine without the service) the test prints a skip notice and
//! passes. When the daemon *is* present it MUST assert — see the run in
//! logs/2026-07-06-handy-vendor-execution.md.
//!
//! Regenerate the fixture with `scripts/gen_test_fixture.sh`.

use std::path::PathBuf;
use std::time::Duration;

use handy_app_lib::audio_toolkit::audio::FrameResampler;
use handy_app_lib::audio_toolkit::read_wav_samples;
use handy_app_lib::audio_toolkit::vad::{
    SileroVad, SmoothedVad, VadFrame, VoiceActivityDetector, VAD_OFFLINE_HANGOVER_FRAMES,
    VAD_ONSET_FRAMES, VAD_PREFILL_FRAMES,
};
use handy_app_lib::remote_socket::{sock_path, RemoteSocketClient};

/// Must match `managers::audio::VAD_THRESHOLD` — the live recording profile.
const VAD_THRESHOLD: f32 = 0.3;
/// Must match `audio_toolkit::constants::WHISPER_SAMPLE_RATE`.
const WHISPER_SAMPLE_RATE: usize = 16_000;
/// 30 ms frame, as the recorder consumer's `FrameResampler` uses.
const VAD_FRAME_MS: u64 = 30;

fn fixtures_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("fixtures")
}

fn vad_model_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("resources")
        .join("models")
        .join("silero_vad_v4.onnx")
}

/// Run 16 kHz mono `samples` through the app's real offline VAD segmentation and
/// return the concatenated voiced samples. This mirrors, byte-for-byte, what the
/// recorder consumer does per frame: the fixture is already 16 kHz, so the real
/// `FrameResampler` acts as a passthrough that slices the stream into the exact
/// 480-sample (30 ms) frames Silero requires, and each frame is pushed through
/// the real `SmoothedVad<SileroVad>`; `Speech` frames are kept, `Noise` dropped
/// — identical to `run_consumer::handle_frame` under the `Offline` policy.
fn segment_voiced_like_recorder(samples: &[f32]) -> Vec<f32> {
    let silero = SileroVad::new(vad_model_path(), VAD_THRESHOLD).expect("load Silero VAD model");
    let mut vad = SmoothedVad::new(
        Box::new(silero),
        VAD_PREFILL_FRAMES,
        VAD_OFFLINE_HANGOVER_FRAMES,
        VAD_ONSET_FRAMES,
    );
    // The recorder resets the detector at `Cmd::Start`, before any frame.
    vad.reset();

    let mut voiced = Vec::<f32>::new();
    let mut resampler = FrameResampler::new(
        WHISPER_SAMPLE_RATE,
        WHISPER_SAMPLE_RATE,
        Duration::from_millis(VAD_FRAME_MS),
    );

    resampler.push(samples, |frame: &[f32]| {
        match vad.push_frame(frame).unwrap_or(VadFrame::Speech(frame)) {
            VadFrame::Speech(buf) => voiced.extend_from_slice(buf),
            VadFrame::Noise => {}
        }
    });
    // Flush the final partial frame (zero-padded to 480), as the recorder does
    // on stop via `FrameResampler::finish`.
    resampler.finish(|frame: &[f32]| {
        match vad.push_frame(frame).unwrap_or(VadFrame::Speech(frame)) {
            VadFrame::Speech(buf) => voiced.extend_from_slice(buf),
            VadFrame::Noise => {}
        }
    });

    voiced
}

/// Lowercase, keep only alphanumerics and spaces — so the assertion is immune to
/// whisper's punctuation and casing ("The quick brown fox ... dog." matches).
fn normalize(s: &str) -> String {
    s.to_lowercase()
        .chars()
        .map(|c| if c.is_alphanumeric() { c } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

#[test]
fn real_speech_wav_through_real_vad_and_daemon() {
    let wav = fixtures_dir().join("fox_16khz_mono.wav");
    let expected_path = fixtures_dir().join("fox_16khz_mono.expected.txt");

    let samples =
        read_wav_samples(&wav).unwrap_or_else(|e| panic!("decode fixture {}: {e}", wav.display()));
    let total_secs = samples.len() as f32 / WHISPER_SAMPLE_RATE as f32;
    assert!(
        !samples.is_empty(),
        "fixture {} decoded to zero samples",
        wav.display()
    );

    // ── VAD regression guard (spec §4) ──────────────────────────────────────
    // The exact failure hit manually: the VAD silently drops (near-)everything
    // and the clip transcribes to nothing. Pin that here: the real Silero VAD
    // MUST find speech in a real speech clip. This runs with or without the
    // daemon, so the guard is always exercised.
    let voiced = segment_voiced_like_recorder(&samples);
    let voiced_secs = voiced.len() as f32 / WHISPER_SAMPLE_RATE as f32;
    eprintln!("VAD kept {voiced_secs:.3}s voiced of {total_secs:.3}s total");
    assert!(
        !voiced.is_empty(),
        "VAD produced ZERO voiced samples from a real speech clip — the silent-drop \
         regression the migration must prevent (docs/handy-migration-spec.md §4)"
    );
    assert!(
        voiced_secs > 0.3,
        "VAD kept only {voiced_secs:.3}s of a {total_secs:.3}s speech clip — \
         suspiciously little; VAD may be over-trimming"
    );

    // ── Full round-trip: real engine client -> live daemon ─────────────────
    let sock = sock_path();
    if !sock.exists() {
        eprintln!(
            "real_audio_pipeline: no whisper socket at {} — skipping daemon assertion \
             (VAD guard above still ran and passed)",
            sock.display()
        );
        return;
    }

    let client = RemoteSocketClient::new();
    // Transcribe the VAD-segmented voiced audio — exactly what the app hands to
    // the engine after capture.
    let text = client
        .transcribe(&voiced, WHISPER_SAMPLE_RATE as u32, Some("en"))
        .expect("daemon transcribe of VAD-segmented voiced audio");
    eprintln!("daemon transcript: {text:?}");

    let expected = std::fs::read_to_string(&expected_path).unwrap_or_default();
    let got = normalize(&text);
    assert!(
        !got.is_empty(),
        "daemon returned empty transcript for real speech (expected ~{:?})",
        expected.trim()
    );
    // Assert on content words, not the exact string: whisper adds casing and
    // punctuation, and word choice on function words can vary.
    for phrase in ["quick brown fox", "lazy dog"] {
        assert!(
            got.contains(phrase),
            "transcript {text:?} (normalized {got:?}) missing key content {phrase:?}; \
             expected ~{:?}",
            expected.trim()
        );
    }
}

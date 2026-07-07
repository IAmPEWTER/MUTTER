//! One-shot resample of a captured mono clip to 16 kHz for the whisper service.
//!
//! Capture happens at the device-native rate (48 kHz on the built-in mic, but
//! anything on Bluetooth) and we resample in software here — the deliberate
//! opposite of the old daemon forcing the hardware to 16 kHz, which was its
//! Bluetooth-codec-negotiation fragility. Uses the same `rubato::FftFixedIn`
//! the vendored Handy build used, driven whole-buffer at stop time.

use rubato::{FftFixedIn, Resampler};

const TARGET_HZ: usize = 16_000;
const CHUNK: usize = 1024;

/// Resample `input` (mono f32) from `src_hz` to 16 kHz. Returns `input`
/// unchanged when it's already 16 kHz. On resampler failure, logs and returns
/// the input as-is — a slightly-wrong-rate clip still transcribes far better
/// than dropping the dictation.
pub fn to_16k(input: &[f32], src_hz: u32) -> Vec<f32> {
    if src_hz as usize == TARGET_HZ || input.is_empty() {
        return input.to_vec();
    }

    let mut resampler = match FftFixedIn::<f32>::new(src_hz as usize, TARGET_HZ, CHUNK, 1, 1) {
        Ok(r) => r,
        Err(e) => {
            log::error!("resampler init ({src_hz} -> {TARGET_HZ}) failed: {e}; sending raw");
            return input.to_vec();
        }
    };

    let mut out = Vec::with_capacity(input.len() * TARGET_HZ / src_hz as usize + CHUNK);
    let mut pos = 0;
    while pos + CHUNK <= input.len() {
        match resampler.process(&[&input[pos..pos + CHUNK]], None) {
            Ok(chunk) => out.extend_from_slice(&chunk[0]),
            Err(e) => {
                log::error!("resample chunk failed: {e}; sending partial");
                return out;
            }
        }
        pos += CHUNK;
    }
    // Final partial chunk: pad with silence to a full chunk so FFT is happy.
    if pos < input.len() {
        let mut tail = input[pos..].to_vec();
        tail.resize(CHUNK, 0.0);
        if let Ok(chunk) = resampler.process(&[&tail], None) {
            out.extend_from_slice(&chunk[0]);
        }
    }
    out
}

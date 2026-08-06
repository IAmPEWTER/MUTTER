//! R4 — the acoustic speech gate, ported from the Python daemon (`stt.py`
//! `capture()` + `_VadState` + `_compute_rms_int16`).
//!
//! The Python daemon returned audio only when there was acoustic evidence of
//! speech, so a silent fn-hold never reached whisper at all. The `mac-native`
//! rewrite dropped this and kept only the 0.3 s length check, which is why a
//! silent hold now types whisper's boilerplate at the cursor. This restores it.
//!
//! Structure and constants are `stt.py`'s, unchanged: 50 ms blocks,
//! `MIN_SPEECH_FRAMES` non-contiguous loud blocks, or `MIN_SPEECH_SEC`
//! contiguous, either one passes.
//!
//! One thing had to change. `stt.py` compared against an absolute
//! `DEFAULT_SILENCE_RMS = 300`, which assumes the room reads quieter than that.
//! Measured on code-mac (2026-08-05): empty-room floor is int16 RMS ~1030, so
//! every 50 ms block clears 300 and the gate passes 12/12 silent clips — inert.
//! The threshold is therefore `max(300, clip_floor * 2)`: never *looser* than
//! the original, so a quiet machine behaves exactly as the Python daemon did,
//! and a loud one stops being a no-op. The high pass is for the same reason —
//! this room's floor is low-frequency (fan / mic self-noise at 100 % gain),
//! and removing it is what separates ambience from quiet speech.

/// `stt.py` `DEFAULT_BLOCK_SECONDS` — the audio callback block size, and the
/// resolution the gate counts in.
const BLOCK_SEC: f32 = 0.05;

/// `stt.py` `DEFAULT_SILENCE_RMS`. Kept as the floor of the threshold, never
/// the whole of it.
const SILENCE_RMS: f32 = 300.0;

/// `stt.py` `MIN_SPEECH_FRAMES` — loud blocks, not necessarily contiguous.
const MIN_SPEECH_FRAMES: usize = 3;

/// `stt.py` `DEFAULT_MIN_SPEECH_SEC` — contiguous loud audio that flips
/// `_VadState.has_spoken`.
const MIN_SPEECH_SEC: f32 = 0.25;

/// High-pass cutoff. Chosen from measurement, not taste: at 250 Hz the
/// empty-room dynamic range collapses to 3.3–4.6 dB while speech holds
/// 8.7–45 dB.
const HPF_HZ: f32 = 250.0;

/// Multiple of the clip's own noise floor a block must clear to count as
/// speech (+6 dB).
const FLOOR_MARGIN: f32 = 2.0;

/// What the gate decided, and the numbers behind it — logged on every drop so a
/// wrongly-rejected dictation is diagnosable instead of silent.
pub struct Decision {
    pub is_speech: bool,
    pub threshold: f32,
    pub loud_blocks: usize,
    pub longest_run_blocks: usize,
}

/// True when `pcm` (mono f32 at `sample_rate`, nominally ±1.0) carries acoustic
/// evidence of speech. Empty or sub-block clips are not speech.
pub fn detect(pcm: &[f32], sample_rate: u32) -> Decision {
    let block = (sample_rate as f32 * BLOCK_SEC) as usize;
    if block == 0 || pcm.len() < block {
        return Decision {
            is_speech: false,
            threshold: SILENCE_RMS,
            loud_blocks: 0,
            longest_run_blocks: 0,
        };
    }

    let filtered = high_pass(pcm, sample_rate, HPF_HZ);
    let rms: Vec<f32> = filtered.chunks_exact(block).map(block_rms_int16).collect();

    let threshold = SILENCE_RMS.max(percentile(&rms, 10) * FLOOR_MARGIN);

    let mut loud_blocks = 0usize;
    let mut run = 0usize;
    let mut longest_run_blocks = 0usize;
    for &r in &rms {
        if r > threshold {
            loud_blocks += 1;
            run += 1;
            longest_run_blocks = longest_run_blocks.max(run);
        } else {
            run = 0;
        }
    }

    // stt.py: `final_vad.has_spoken or speech_frames >= MIN_SPEECH_FRAMES`.
    let has_spoken = longest_run_blocks as f32 * BLOCK_SEC >= MIN_SPEECH_SEC;
    Decision {
        is_speech: has_spoken || loud_blocks >= MIN_SPEECH_FRAMES,
        threshold,
        loud_blocks,
        longest_run_blocks,
    }
}

/// RMS of one block, expressed on `stt.py`'s int16 scale so the inherited
/// constants keep their meaning.
fn block_rms_int16(block: &[f32]) -> f32 {
    let sum_sq: f32 = block.iter().map(|s| s * s).sum();
    (sum_sq / block.len() as f32).sqrt() * 32768.0
}

/// One-pole high pass: `y[n] = a * (y[n-1] + x[n] - x[n-1])`.
fn high_pass(x: &[f32], sample_rate: u32, cutoff_hz: f32) -> Vec<f32> {
    let a = 1.0 / (1.0 + 2.0 * std::f32::consts::PI * cutoff_hz / sample_rate as f32);
    let mut out = Vec::with_capacity(x.len());
    let mut prev_in = 0.0f32;
    let mut acc = 0.0f32;
    for (i, &s) in x.iter().enumerate() {
        let dx = if i == 0 { s } else { s - prev_in };
        acc = a * (acc + dx);
        prev_in = s;
        out.push(acc);
    }
    out
}

/// Nearest-rank percentile of `values` (0..=100). Used for the clip's own noise
/// floor, which is what makes the gate survive a room the inherited constant
/// was never calibrated for.
fn percentile(values: &[f32], pct: usize) -> f32 {
    if values.is_empty() {
        return 0.0;
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let idx = (sorted.len() - 1) * pct / 100;
    sorted[idx]
}

#[cfg(test)]
mod tests {
    use super::*;

    const SR: u32 = 16_000;

    fn sine(freq: f32, secs: f32, amp: f32) -> Vec<f32> {
        (0..(SR as f32 * secs) as usize)
            .map(|i| amp * (2.0 * std::f32::consts::PI * freq * i as f32 / SR as f32).sin())
            .collect()
    }

    /// Deterministic pseudo-noise — no rand dependency for a unit test.
    fn noise(secs: f32, amp: f32) -> Vec<f32> {
        let mut state = 0x2545_F491_4F6C_DD1Du64;
        (0..(SR as f32 * secs) as usize)
            .map(|_| {
                state ^= state << 13;
                state ^= state >> 7;
                state ^= state << 17;
                ((state >> 40) as f32 / 8388608.0 - 1.0) * amp
            })
            .collect()
    }

    #[test]
    fn digital_silence_is_not_speech() {
        assert!(!detect(&vec![0.0; SR as usize * 2], SR).is_speech);
    }

    #[test]
    fn empty_and_subblock_clips_are_not_speech() {
        assert!(!detect(&[], SR).is_speech);
        assert!(!detect(&vec![0.0; 100], SR).is_speech);
    }

    /// The failure R4 exists to stop, and the one an absolute threshold misses:
    /// stationary noise far louder than the inherited 300 constant.
    #[test]
    fn loud_stationary_noise_is_not_speech() {
        let d = detect(&noise(3.0, 0.1), SR);
        assert!(
            !d.is_speech,
            "loud stationary noise passed: {} loud blocks, threshold {}",
            d.loud_blocks, d.threshold
        );
    }

    #[test]
    fn steady_hum_is_not_speech() {
        assert!(!detect(&sine(60.0, 3.0, 0.25), SR).is_speech);
    }

    /// A single impulse (key click, desk bump) must not clear MIN_SPEECH_FRAMES.
    #[test]
    fn single_click_is_not_speech() {
        let mut clip = vec![0.0f32; SR as usize];
        for s in clip.iter_mut().skip(8000).take(160) {
            *s = 0.8;
        }
        clip.extend(vec![0.0f32; SR as usize]);
        assert!(!detect(&clip, SR).is_speech);
    }

    /// Modulated tone burst over a noise floor — the shape of speech.
    #[test]
    fn modulated_bursts_over_a_noise_floor_are_speech() {
        let mut clip = noise(3.0, 0.02);
        for burst in 0..4 {
            let start = SR as usize / 2 + burst * SR as usize / 2;
            let tone = sine(900.0, 0.3, 0.35);
            for (i, s) in tone.iter().enumerate() {
                if let Some(dst) = clip.get_mut(start + i) {
                    *dst += s;
                }
            }
        }
        let d = detect(&clip, SR);
        assert!(d.is_speech, "speech-shaped clip rejected: {:?}", d.loud_blocks);
    }

    /// On a quiet clip the inherited constant must still dominate, so the gate
    /// behaves exactly as `stt.py` did where `stt.py` worked.
    #[test]
    fn threshold_falls_back_to_the_python_constant_when_the_room_is_quiet() {
        let clip = noise(2.0, 0.001);
        assert_eq!(detect(&clip, SR).threshold, SILENCE_RMS);
    }

    /// ...and rises above it only when the floor demands (code-mac's room).
    #[test]
    fn threshold_rises_above_the_constant_in_a_loud_room() {
        let clip = noise(2.0, 0.1);
        assert!(detect(&clip, SR).threshold > SILENCE_RMS);
    }

    /// Regression against real recordings, so the gate is pinned to measured
    /// audio and not only to synthetic signals. Point `MUTTER_CLIPS` at a
    /// directory of 16 kHz mono WAVs; anything named `amb*` must be rejected,
    /// everything else must pass:
    ///
    /// ```sh
    /// MUTTER_CLIPS=/path/to/clips cargo test --release -- --ignored fixtures
    /// ```
    #[test]
    #[ignore = "needs MUTTER_CLIPS pointing at recorded WAV fixtures"]
    fn fixtures_match_measured_behavior() {
        let dir = std::env::var("MUTTER_CLIPS").expect("set MUTTER_CLIPS");
        let mut checked = 0;
        let mut wrong = Vec::new();
        for entry in std::fs::read_dir(&dir).expect("read clip dir") {
            let path = entry.expect("dir entry").path();
            if path.extension().and_then(|e| e.to_str()) != Some("wav") {
                continue;
            }
            let name = path.file_name().unwrap().to_string_lossy().to_string();
            let mut reader = hound::WavReader::open(&path).expect("open wav");
            let spec = reader.spec();
            let pcm: Vec<f32> = reader
                .samples::<i16>()
                .map(|s| s.expect("sample") as f32 / 32768.0)
                .collect();
            let d = detect(&pcm, spec.sample_rate);
            let expect_silence = name.starts_with("amb");
            checked += 1;
            if d.is_speech == expect_silence {
                wrong.push(format!(
                    "{name}: got {}, threshold {:.0}, {} loud blocks",
                    if d.is_speech { "SPEECH" } else { "silence" },
                    d.threshold,
                    d.loud_blocks
                ));
            }
        }
        assert!(checked > 0, "no WAVs found in {dir}");
        assert!(wrong.is_empty(), "{checked} clips checked, wrong: {wrong:#?}");
    }
}

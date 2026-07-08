//! Whisper silence-hallucination filter. Ported from the old Python daemon
//! (`stt.py`, `is_hallucination` + `_HALLUCINATIONS` + the repeat-pattern
//! regex).
//!
//! Whisper has a well-known habit of emitting boilerplate on silent or
//! near-silent audio ("You", "Thank you.", "Thanks for watching."). The old
//! daemon dropped these between transcription and injection; without the
//! filter every silent fn-hold types " You" at the cursor — including through
//! Screen Sharing, into someone's live text field.
//!
//! Conservative, like the original: only exact known phrases, or strings made
//! entirely of filler tokens, are dropped. A real transcript with a "thanks"
//! in the middle passes through.

/// Exact transcripts to drop — compared lowercase, trailing `.!?,` and
/// whitespace stripped. stt.py's `_HALLUCINATIONS`, verbatim.
const EXACT: [&str; 21] = [
    "thank you",
    "thanks",
    "thanks for watching",
    "thank you for watching",
    "thank you so much",
    "subscribe",
    "please subscribe",
    "like and subscribe",
    "subscribe to my channel",
    "bye",
    "goodbye",
    "you",
    "the end",
    "so",
    "yeah",
    "okay",
    "ok",
    "um",
    "uh",
    "mm",
    "hmm",
];

/// True if `text` looks like a Whisper silence hallucination (or is empty —
/// nothing to type either way).
pub fn is_hallucination(text: &str) -> bool {
    let cleaned = text.trim();
    if cleaned.is_empty() {
        return true;
    }
    let lowered = cleaned.to_lowercase();
    if EXACT.contains(&lowered.trim_end_matches(['.', '!', '?', ',']).trim_end()) {
        return true;
    }
    only_fillers(&lowered)
}

/// Whisper loops on silence: "Thank you. Thank you. Thank you." — drop any
/// transcript made only of filler tokens separated by whitespace/`.!?,`.
/// Same token list as stt.py's `_HALLUCINATION_REPEAT_RE`.
fn only_fillers(lowered: &str) -> bool {
    let words: Vec<&str> = lowered
        .split(|c: char| c.is_whitespace() || matches!(c, '.' | '!' | '?' | ','))
        .filter(|w| !w.is_empty())
        .collect();
    if words.is_empty() {
        return false;
    }
    let mut i = 0;
    while i < words.len() {
        match words[i] {
            "thank" if words.get(i + 1) == Some(&"you") => i += 2,
            "the" if words.get(i + 1) == Some(&"end") => i += 2,
            "thanks" | "bye" | "you" | "ok" | "okay" | "so" | "yeah" | "mm" | "hmm" | "uh"
            | "um" => i += 1,
            _ => return false,
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::is_hallucination;

    #[test]
    fn drops_silence_boilerplate() {
        for t in [
            "You",
            " you.",
            "Thank you.",
            "Thanks for watching!",
            "Thank you. Thank you. Thank you.",
            "Hmm.",
            "",
            "   ",
        ] {
            assert!(is_hallucination(t), "should drop {t:?}");
        }
    }

    #[test]
    fn passes_real_transcripts() {
        for t in [
            "Does this work at all?",
            "Nope, it's all fucked.",
            "thanks for the review comments",
            "You should see this",
            "okay so the next step is the resampler",
        ] {
            assert!(!is_hallucination(t), "should pass {t:?}");
        }
    }
}

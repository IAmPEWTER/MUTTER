# 2026-08-05 — hallucinated words on code-mac: root cause + fix

Closes the OPEN item in `2026-07-26-dictation-dead-triage.md` (R4 gap).
Shipped and confirmed live.

## Mechanism (measured, not inferred)

Peter dictates on his own Mac while screen-shared into code-mac. Screen Sharing
forwards the fn press; code-mac's daemon records its own empty room and injects
whisper's boilerplate into the session — interleaved with the real dictation his
local daemon is typing over the keycode path.

Captured live with a listen-only tap during a 3.2 s hold:

```
15.87s flagsChanged keycode=63 fn_flag=1 src_pid=90646   <- fn down
19.05s flagsChanged keycode=63 fn_flag=0 src_pid=90646   <- fn up
```

`src_pid 90646` = `ScreensharingAgent`. Hardware events post `src_pid 0`.

**The forwarded press is indistinguishable by keycode.** It arrives as a real
`keycode=63` through the same `kCGSessionEventTap` the Python daemon used, so
`daemon.py`'s `keycode != _KEYCODE_FN` filter passed it too. The Python daemon
captured on every remote dictation as well — R4 threw the audio away, which is
the only reason it was never visible. Log before the fix: 1052 captures, 1007
injections.

## Two dead ends, so nobody re-runs them

- **`no_speech_prob` / `avg_logprob` cannot detect this.** Measured on
  empty-room audio: `no_speech_prob` 1.1e-10 (silence) vs 5.0e-13 (speech);
  `avg_logprob` −0.153 vs −0.159. No separation. The shared whisper service is
  deliberately untouched — exposing these fields would buy nothing.
  `compression_ratio` separates the repeat-loop class only (39.2 vs 1.04) and
  misses plain escapes like `'Here we go.'`.
- **The transcript filter cannot be the primary defense.** 13 empty-room clips,
  4 escaped `is_hallucination`: `'Here are some advertisements here. You'` ×2,
  `"Here's your key toставставстав You"`, `'Here we go.'`. Open class.

## Fix

**`hardware.rs` — a forwarded press never reaches the microphone.**
`CGEventSourceFlagsState(kCGEventSourceStateHIDSystemState)` reports physical
keyboard state; over the 3.2 s forwarded hold above it showed **zero**
transitions. `daemon.py:534` already trusted this API to heal a missed fn-up.
Sampled across the whole hold, not once at key-down, so a lagging read cannot
misclassify a genuine local press; recording still starts immediately, so a real
dictation pays no latency.

**`speech.rs` — R4 restored.** `stt.py`'s structure and constants unchanged:
50 ms blocks, `MIN_SPEECH_FRAMES` non-contiguous or `MIN_SPEECH_SEC` contiguous.

Threshold is the one deviation: `max(300, clip_floor × 2)`, never looser than
`DEFAULT_SILENCE_RMS`. Verbatim 300 is **inert on code-mac** — empty-room floor
reads int16 RMS ~1030 at 100 % input gain, so all 12/12 silent clips pass as
speech. On a quiet clip the threshold resolves to exactly 300 and behaves as
`stt.py` did. High-passed at 250 Hz first: this room's floor is low-frequency
(fan / mic self-noise), and removing it is what separates ambience (dynamic
range 3.3–4.6 dB) from quiet speech (8.7–45 dB).

Gain-reduction was tested as the alternative that would let verbatim 300 work:
viable only at ≤25 % input gain, where the entire margin is 4.1 dB — a fan
spin-up erases it and the gate silently reverts to a no-op. Rejected.

## Verification

23/23 real recordings correct through the Rust gate (13 empty-room rejected;
10 speech accepted, incl. a barely-audible clip and single-word "yes"). Kept as
`speech::tests::fixtures_match_measured_behavior`, `--ignored`, driven by
`MUTTER_CLIPS`. Synthetics in-tree cover louder-than-speech stationary noise,
60 Hz hum, single click, digital silence.

Live after install: 5 forwarded presses, 5 dropped, **0 injections**.

## Gotcha

Synthetic fn events are useless for testing this. Posting to `kCGHIDEventTap`
**does** move HID state, so a synthetic press passes the hardware check and
cannot reproduce the forwarded case; it also desyncs `handy-keys`' fn state and
inverts the trigger until the daemon is restarted (`mac-native/README.md` warns
of this). Only a real forwarded press exercises the path.

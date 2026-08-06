# 2026-07-26 — "MUTTER not working" triage

## Root cause: R3 was never ported to mac-native
`recorder.rs` used `default_input_device()`. R3 ("force built-in mic, never
system default") was live in the Python daemon (`stt._builtin_input_device`) and
in the vendored Handy config, and was lost in the `mac-native` rewrite.

Headphones in the jack had made `External Microphone` the default; those
headphones have no mic, so capture was silence (mean **-78.3 dB**; built-in
reads **-30.7 dB** same room). Whisper answered silence with boilerplate,
`hallucination.rs` dropped it → nothing typed, no error.

Symptom shape: `capturing:` present every hold (tap + mic fine), every hold
ending in `dropped hallucination/empty transcript`.

Fixed in code — `input_device()` pins the built-in by name, falls back to the
default only when no built-in exists. Proven against real hardware with the
system default deliberately set to the dead device
(`picks_builtin_over_system_default`, `--ignored`).

## The parity checklist covers the *retired* substrate
`docs/parity-R1-R18.md` verified R1–R18 against vendored Handy (`desktop/`),
which the 2026-07-06 decision replaced with `mac-native`. Nothing re-audited
mac-native against it. R3 was not the only casualty — also absent there:

| Req | mac-native state |
|---|---|
| R4 VAD detect/segment/trim | absent; `whisper.rs` sends `vad_filter: false`, no acoustic gate |
| R7 max-recording cap (120 s) | absent (the 120 s in `whisper.rs` is a socket timeout) |
| R9 repeat-collapse word+phrase | absent; `inject.rs::sanitize` does newlines + double spaces only |
| R16 single instance | not enforced — two daemons ran here for 12 days |

Unaudited, not confirmed-broken. R16's absence is what let the duplicate agent
below go unnoticed.

## Duplicate LaunchAgent
`~/Library/LaunchAgents/MUTTER.plist` (label `MUTTER`, created Jul 6, not from
`install.sh`, no repo reference) ran a second copy of the same binary — two fn
taps, two mic opens. Live since Jul 14 (pids 818 + 822). Booted out + trashed,
along with dead symlink `com.peter.mutter.plist` → `mac/com.peter.mutter.plist`.

`install.sh` only ever writes `com.peter.mutter.app.plist`; anything else under
that label prefix is stray.

## install.sh bootout/bootstrap race
`launchctl bootout` returns before launchd releases the label, so the immediate
`bootstrap` failed with `Bootstrap failed: 5: Input/output error` and left the
daemon **not running**. Fixed with a wait-until-gone loop.

## Verifying the installed binary is current
Strip signatures before comparing — `install.sh` signs the *bundle*, so
Info.plist enters the code directory and CDHash differs from a signed bare
binary by design. A false mismatch costs a needless reinstall, and a new binary
hash drops the pinned Accessibility grant.

Also measured: the Accessibility grant **survived** both rebuilds today (TCC row
has `csreq = NULL`, `auth_value = 2`; the new binary logged `capturing:` on a
real fn-hold). The README's "every build needs an off/on toggle" did not hold
here — worth re-checking before repeating that claim.

## ~~OPEN~~ RESOLVED 2026-08-05 — hallucinations escape the filter

Root cause was not the filter and not this machine's noise floor: Screen Sharing
forwards fn as a real `keycode=63`, so code-mac recorded its empty room on every
remote dictation. Fixed by `hardware.rs` (physical-fn check) + `speech.rs` (R4
restored). The "R4 verbatim would not fix this machine" note below still holds
and is why the threshold is now relative. See
`2026-08-05-hallucination-root-fix.md`.

## Original OPEN note
Whisper boilerplate reaching the cursor is **not** a filter that is off. It is
compiled in and unconditional (`main.rs:130`). It has holes, and nothing
upstream of it stops silence.

Probe: 12 × 5 s of empty-room audio, built-in mic, daemon's exact whisper params.
**4/12 escaped** `is_hallucination`:

```
'so Whoa you'  ·  'Whoa you you'  ·  'so Whoa Thank you.'  ·  'Copyright Justin Lavigne'
```

"Whoa" is not in the filler list; subtitle-credit artifacts ("Copyright …") are
a whole class the 21-phrase list does not model.

The real gap is R4: the Python daemon gated on acoustics so *silence never
reached whisper at all* ("strict enough that silence and fan noise never reach
whisper"). mac-native has only the 0.3 s length guard.

**Porting R4 verbatim would not fix this machine.** Measured: input gain 100 %,
empty-room ambience int16 **RMS 702** vs `DEFAULT_SILENCE_RMS = 300` — the
Python gate would classify this room as speech. Any acoustic gate here needs a
threshold set against measured ambience, or lower input gain, not the inherited
constant. Probe harness: scratchpad `silence_probe.py` (rebuild if cleared).

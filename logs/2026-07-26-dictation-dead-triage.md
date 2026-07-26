# 2026-07-26 — "MUTTER not working" triage

## Cause: dead default input device
Headphones in the jack → macOS made `External Microphone` the default input.
Those headphones have no mic → capture was silence (mean **-78.3 dB**; built-in
reads **-30.7 dB** in the same room). Whisper answered silence with boilerplate
("Thank you.", "you"), `hallucination.rs` dropped it → nothing typed, no error.

Symptom shape: `capturing:` lines present in `app.err.log` (tap + mic fine),
every hold ending in `dropped hallucination/empty transcript`.

Fix: `SwitchAudioSource -t input -s "MacBook Air Microphone"`.

## Second defect: duplicate LaunchAgent
`~/Library/LaunchAgents/MUTTER.plist` (label `MUTTER`, created Jul 6, not from
`install.sh`, no repo reference) ran a **second** copy of the same binary — two
fn event taps, two mic opens, both typing. Both were live since Jul 14 (pids
818 + 822). Booted out + trashed. Also trashed dead symlink
`com.peter.mutter.plist` → `mac/com.peter.mutter.plist` (Python daemon,
gitignored away; not loaded).

`install.sh` only ever writes `com.peter.mutter.app.plist` — anything else under
that label prefix is stray.

## Version state — current, no reinstall needed
`main` at c852848, 0 ahead / 0 behind `origin/main`, tree clean.
Installed binary (signature stripped) is byte-identical to
`mac-native/target/release/mutter` from this source: `ad2216cd…791c1`.

Do **not** compare `shasum` of the installed binary against a freshly signed
bare binary — `install.sh` signs the *bundle*, so Info.plist enters the code
directory and the CDHash legitimately differs. Strip signatures and compare, or
the check reports a false mismatch (and a needless reinstall costs the pinned
Accessibility grant).

## Verified after fix
Single instance (pid 36265, "ready — hold fn to dictate"); default input live at
-30.5 dB; whisper socket up; offline smoke test `smoke_transcribe_wav` passed.

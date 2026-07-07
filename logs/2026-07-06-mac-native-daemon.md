# mac-native daemon — build + ship

Date: 2026-07-06. Pivot from the vendored Handy/Tauri app (`desktop/`) to a minimal headless Rust daemon (`mac-native/`). Peter: "get rid of EVERYTHING from the rust version we dont need... max simple, max robust."

## Done
- Built `mac-native/` daemon (6 src files, ~1.9 MB vs Tauri's 36 MB). Behaves like the old Python daemon; robustness = the two Handy choices only (self-healing fn tap via `handy-keys`; non-deadlocking cpal teardown). See `mac-native/DECISIONS.md`.
- Deployed: daemon binary swapped into `/Applications/MUTTER.app` (re-signed ad-hoc, identifier `com.peter.mutter.app`) → inherits existing Accessibility + Microphone TCC grants, no re-prompt. LaunchAgent `~/Library/LaunchAgents/com.peter.mutter.app.plist` (KeepAlive/SuccessfulExit:false).
- `install.sh` assembles a minimal `LSUIElement` bundle from scratch (no Tauri) — shippable to other Macs. Verified working end-to-end.
- Verified: unit tests (keycode map) + offline smoke (`say` "quick brown fox" → resample 48→16k → whisper client → exact transcript). fn tap fires + mic opens + enigo posts (all proven live). Full record→transcribe→inject chain fired.
- Fixed 2 bugs pre-ship: zombie leak in `set_system_muted` (spawn without wait → reap on detached thread); mutex-poison panic in recorder teardown.
- Committed + pushed (55bb379, c253802, 93780f8).

## Empirical gotcha
- **Never test the trigger with synthetic CGEvents.** Fake fn events carry fake modifier flags and desync `handy-keys`' fn state (inverts trigger: fn-down reads as release). Real hardware fn self-corrects every event. Restart daemon to clear a desync. This cost real debugging time; also in `mac-native/README.md` troubleshooting.

## Open (need Peter)
1. **Real spoken test** — hold real fn + speak → words typed at cursor. Only the physical gesture is unverified (everything up to it is). Watch `~/Library/Logs/mutter/app.err.log`.
2. **Retire `desktop/`** (9.2 GB, has uncommitted seams, is the fallback source). Deferred pending #1 + explicit go-ahead — hard to reverse. Root README/DECISIONS say "superseded (retire after soak)". Tauri exe backed up in this session's scratchpad.

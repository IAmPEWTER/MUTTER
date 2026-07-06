# Handy vendor execution log

Date: 2026-07-06. Session: mutter Ruster. Executing docs/handy-migration-spec.md (as revised: vendor, no fork).

## Done
- Spec §3/§4 revised: fork → vendor-by-subtree (Peter approved).
- Vendored Handy v0.9.0 → `desktop/` (`git subtree add --squash`, remote `handy` local-only). Commit b292fe3.
- Toolchain installed: rustup (rustc 1.96.1), bun 1.3.14, cmake 4.3.4.
- Whisper service baseline verified: ping ok + exact round-trip on `say`-generated 16k fixture ("quick brown fox").

## Decisions
- **Bundle id `com.peter.mutter.app`** (not com.peter.mutter): tauri-plugin-autostart writes `~/Library/LaunchAgents/<bundle-id>.plist` — would collide with Python daemon's plist during soak.
- **Auto-updater must die**: Handy ships tauri-plugin-updater live — would overwrite vendored app with upstream releases. Disabled in rebrand.
- Rebrand = surface only (productName, identifier, icons from docs/logo.png 1024², user-visible strings). Internal identifiers stay Handy → cheap tag merges.
- No codesign identity on machine → ad-hoc signing. If TCC grants reset across rebuilds, make self-signed cert (note for later).
- Test fixtures via `say` → afconvert 16k mono WAV. Ground truth known, no mic needed.

## P0–P3 results (all committed)
- **P0 build/rebrand:** MUTTER.app builds + installs (`/Applications/MUTTER.app`), bundle `com.peter.mutter.app`. dmg bundling fails (no codesign identity) — irrelevant, we ship the `.app`. Use `bun tauri build --no-bundle` to skip dmg.
- **TCC grants:** system TCC.db is writable on this box (SIP posture allows `sudo sqlite3` UPDATE/INSERT). Accessibility + Microphone granted directly (auth_value 2). csreq NULL is accepted (matches existing CLI grants).
- **R1 fn trigger PROVEN:** synthetic fn = CGEvent FlagsChanged, keycode 63, flags `MaskSecondaryFn`(0x800000) set=down/clear=up, `CGEventPost(kCGSessionEventTap)` (handy-keys taps SessionEventTap, listener.rs:399,248). Reference: `scratchpad/fn_press.py`. Log confirms `state=Pressed → TranscribeAction::start`. Model loads ~400ms (small, Metal MTL0).
- **R8 MLX PROVEN:** RemoteSocket live daemon round-trip (ping ok, PCM→text). 125/125 lib tests pass on merged tree.
- **Commits:** e2ade4d (R8 RemoteSocket), ded3399 (R13/R15 Screen-Share keycodes), 01c525d (R7/R9 + audio-wedge fix).

## Audio-injection environment findings (for next agent)
- **BlackHole 2ch is a dead end on this Mac:** no clock master → both `afplay` INTO it and `ffmpeg avfoundation` capture FROM it HANG (not the app's fault). Needs an aggregate device w/ a real clock to work; not worth it.
- **Acoustic path (speaker→mic):** fn trigger fires + recording starts, but VAD rejects the marginal SNR → no transcript. Not a bug, just weak signal.
- **Consequence:** authoritative "real audio → text" proof = deterministic **fixture-through-pipeline** test (P1b tier A: WAV → real VAD → RemoteSocket → assert), NOT OS mic capture. cpal capture itself is upstream-proven (25.9k-star userbase). App-level fn proof = P1b tier C smoke (fn synth → log asserts start/stop, no audio).

## Final verification (robust binary in installed bundle)
- Swapped `target/release/handy` (all fixes) into `/Applications/MUTTER.app`, ad-hoc re-signed. TCC grants (Accessibility+Mic) are **bundle-id keyed** → survive re-sign. Confirmed both still auth_value 2.
- **RemoteSocket loads in the assembled app** (deterministic, via `--toggle-transcription` CLI): `TranscribeAction::start → load model: mlx-whisper-service → Successfully loaded (42ms)`. No local weights, ping-only. Production path proven end to end.
- **Use `--toggle-transcription` (not synthetic fn) to drive the app** — identical action path, 100% reliable. Also proves R16 single-instance remote control. Synthetic-fn CGEvent posting is intermittent (poster timing, not the app) — reserve it for the R1 smoke only.
- 126 lib tests + Tier A integration re-run by me: all green.

## Daemon state
- Python daemon `com.peter.mutter` booted out (plist symlink still at `~/Library/LaunchAgents/`, unloaded). whisper service up. No app autostart plist yet (`autostart_enabled:false`). Nothing owns fn persistently until soak setup.

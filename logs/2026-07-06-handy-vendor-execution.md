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

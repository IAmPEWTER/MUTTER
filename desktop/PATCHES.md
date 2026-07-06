# PATCHES — MUTTER's deltas over vendored Handy

Base: **Handy v0.9.0** (`github.com/cjpais/Handy`, MIT), vendored at `desktop/` via `git subtree --squash`.
Update rule: adopt by **release tag only** (never per-commit). `git fetch handy tag vX.Y.Z && git subtree pull --prefix=desktop handy vX.Y.Z --squash`. Re-apply the seams below on conflict; logic lives in our own new files, so a conflict is "re-place one line," not a rewrite.

Discipline (spec §5): custom logic in NEW files upstream never touches; edits to THEIR files are 1-line delegating seams.

## New files (immune to upstream churn)
| File | Purpose | Req |
|---|---|---|
| `src-tauri/src/managers/remote_socket.rs` | ASR client → shared MLX whisper daemon (unix socket) | R8 |
| `src-tauri/src/frontmost.rs` | NSWorkspace frontmost-app / is_screen_sharing | R15 |
| `src-tauri/src/paste_keycodes.rs` | current-layout char→keycode typing, 8ms delay | R13 |

## Seams in upstream files (re-apply on merge conflict)
| File | Seam | Notes |
|---|---|---|
| `managers/mod.rs` | `pub mod remote_socket;` | |
| `managers/model.rs` | `EngineType::RemoteSocket` variant; pseudo-model insert; skip in `update_download_status` + `get_model_path` | pseudo-model = `ModelSource::Local, is_downloaded:true` bypasses disk gates |
| `managers/transcription.rs` | `LoadedEngine::RemoteSocket` variant; load arm (ping on load); transcribe arm | delegates to remote_socket.rs |
| `clipboard.rs` | one branch in `paste()`: Screen-Sharing frontmost → `paste_keycodes::type_text` | else path untouched |
| `lib.rs` | `mod frontmost; mod paste_keycodes;`; updater plugin registration REMOVED; window title MUTTER | |
| `Cargo.toml` | `objc2-app-kit` promoted to direct dep | already transitive |
| `settings.rs` | `update_checks_enabled` default false; `max_recording_secs` default 120 (backend-only) | R7 |
| `transcription_coordinator.rs` | 120s max-recording watchdog (additive, generation-guarded) | R7 |
| `audio_toolkit/audio/recorder.rs` | `recv()`→`recv_timeout` + join-with-timeout in stop/close | audio-wedge fix (spec §4). fix-shaped → optionally upstream via throwaway fork |
| `audio_toolkit/text.rs` | `collapse_phrase_repeats` after `collapse_stutters` | R9 |

## Rebrand (surface only — re-apply on merge)
- `tauri.conf.json`: productName MUTTER, identifier `com.peter.mutter.app`, `createUpdaterArtifacts:false`, no `updater` plugin block.
- `capabilities/*.json`: `updater:default` permission removed.
- Icons regenerated from `../docs/logo.png`; `index.html` title; `tray.rs` label; i18n "Handy"→"MUTTER" brand strings.
- `MutterLogo.tsx` (new file, traced stacked wordmark) replaces `HandyTextLogo`/`HandyHand` in `Sidebar.tsx`, `Onboarding.tsx`, `AccessibilityOnboarding.tsx`; sidebar nav icon for the dictation section is lucide `Mic` (stacked mark illegible at nav-icon size).
- `theme.css`: MUTTER navy/white monochrome (`#080514` / `#fefefe`) replaces Handy's pink palette, same variable names. `--color-logo-primary`/`--color-background-ui` flip light↔dark (navy-on-white in light mode, white-on-navy in dark). Two spots needed an explicit text-color flip because they render the token as a solid full-opacity fill with plain ambient/hardcoded text: `Sidebar.tsx` active-item pill (`text-white dark:text-[#080514]`) and `Badge.tsx` `primary` variant (same pair). `AccessibilityOnboarding.tsx` grant buttons and `UpdateChecker.tsx`'s install button were switched from `bg-logo-primary` to `bg-background-ui` (always dark in both themes) since they hardcode `text-white`.
- `AboutSettings.tsx`: donate link + `github.com/cjpais/Handy` source link removed (MUTTER is private, not soliciting).
- `DebugPaths.tsx`: example paths now real macOS paths (`~/Library/Application Support/com.peter.mutter.app/...`), not Windows `%APPDATA%/handy`.
- `release-notes/0.9.0.md`: rewritten, no Handy references or cjpais/Handy issue links.
- Internal identifiers (crate `handy_app_lib`, `handy` binary, `handy-keys`, `handy_keys` enum value/bindings, URLs, HF cache paths) left as Handy — deep rename = merge pain for zero user benefit.
- **Auto-updater is disabled on purpose** — it would overwrite this vendored tree with upstream releases. Keep it dead across merges.

## Known cosmetic gaps (deferred, non-blocking)
- `resources/handy.png` (Linux tray icon) still says "handy" — Linux-only, not built/shipped for macOS.
- `cli.rs` binary/program name (`#[command(name = "handy", ...)]`) intentionally left as `handy` — only the `about` help text was changed to MUTTER; renaming risks the single-instance/CLI plumbing for no user-visible benefit (never seen outside `--help`).

## Smoke test after every merge (spec §5)
1. fn-hold triggers recording (`scripts/smoke/fn_trigger_smoke.sh`).
2. MLX round-trip (RemoteSocket integration test).
3. Screen-Share keycode paste.

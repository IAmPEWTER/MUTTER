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
| `lib.rs` | `mod frontmost; mod paste_keycodes;`; updater plugin registration REMOVED; window title MUTTER; `--headless` handling (accessory + hide tray + skip the no-tray→show-window fallback); `RunEvent::Ready` re-asserts Accessory when headless | headless = edits in `initialize_core_logic`, setup closure, and the `.run()` Ready arm. Ready arm is load-bearing: launchd re-foregrounds the app and AppKit resets the policy after `setup`, so the dock icon needs re-hiding once the loop is up |
| `cli.rs` | `--headless` flag (new field) | invisible background-agent mode |
| `tauri.conf.json` | `mainBinaryName: "mutter"` | renames only the bundled executable `handy`→`mutter` (crate/lib untouched) so macOS system notifications ("… can run in the background", Accessibility prompt) say "mutter", not "handy" |
| `llm_client.rs` | outbound HTTP headers rebranded (Referer/User-Agent/X-Title Handy→MUTTER) | X-Title shows on OpenRouter's public rankings; dropped the cjpais/Handy URL |
| `Cargo.toml` | `objc2-app-kit` promoted to direct dep | already transitive |
| `settings.rs` | `update_checks_enabled` default false; `max_recording_secs` default 120 (backend-only) | R7 |
| `transcription_coordinator.rs` | 120s max-recording watchdog (additive, generation-guarded) | R7 |
| `audio_toolkit/audio/recorder.rs` | `recv()`→`recv_timeout` + join-with-timeout in stop/close | audio-wedge fix (spec §4). fix-shaped → optionally upstream via throwaway fork |
| `audio_toolkit/text.rs` | `collapse_phrase_repeats` after `collapse_stutters` | R9 |
| `src/App.tsx` | `checkOnboardingStatus`: skip the macOS/Windows permission-reveal gate when `start_hidden` | a hidden agent must never force a window; the mic check (`AVCaptureDevice.authorizationStatus`) false-negatives on our `csreq=NULL` grant even though cpal records fine, and that was force-opening the onboarding window (→ dock icon). Gated on the `start_hidden` setting (frontend already has it; no new command / no bindings regen, which only happens on debug builds) |

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
- **Headless is the shipped mode.** The login item (`~/Library/LaunchAgents/com.peter.mutter.app.plist`) launches `MacOS/mutter --headless`: no dock icon, no window, no tray — an invisible background agent, matching the old Python daemon. It records/transcribes on fn-hold with zero visible chrome. To open settings, launch the app plainly (`open -a MUTTER`) — single-instance forwards to the running agent and shows the window (dock icon appears only while it's open, gone on close). Handy blocks the all-hidden combo by design (a hidden-tray start force-shows the window so there's always a way back in); `--headless` opts out on three axes: hides the tray, skips the window-show, and (via `App.tsx`) skips the permission-onboarding gate. **The no-dock guarantee is `LSUIElement=true`** in `src-tauri/Info.plist` (Tauri auto-merges it) — deterministic, not dependent on runtime activation-policy timing, which loses a race with launchd re-foregrounding the app. `show_main_window` flips to `Regular` when a window is actually shown, so relaunch-to-settings still gets a normal window + dock.
  - **Dock "MUTTER" while testing = recents ghost, not a running tile.** macOS "Show recent applications in Dock" lists recently-launched apps; repeated relaunches populate it. It is NOT the app owning a dock tile (verify: quit the app — if the tile survives, it's the recents ghost; clear with the `recent-apps` filter, not by touching the app).

## "handy" left in place — deep + invisible (renaming = merge pain, zero user benefit)
Nothing here is ever rendered to the user. The **shipped executable is `mutter`** (`mainBinaryName`), so macOS notifications, the CLI `--help` name, and window/tray all say MUTTER.
- Cargo **package** name `handy` + **lib** name `handy_app_lib` (`Cargo.toml`) — compile-time identifiers only; `main.rs` calls `handy_app_lib::run`. Output binary is renamed by `mainBinaryName`, so these never surface.
- `handy-keys` crate (external dependency, the fn-key event tap) and the `handy_keys` module/enum — internal symbols; the crate is published under that name, can't rename it.
- HF model-cache path segment, debug-dump wav filenames (`handy-*.wav`), `HANDY_NO_GTK_LAYER_SHELL` env (Linux), `resources/handy.png` (Linux tray icon) — off-screen / non-macOS / debug-only.

## Smoke test after every merge (spec §5)
1. fn-hold triggers recording (`scripts/smoke/fn_trigger_smoke.sh`).
2. MLX round-trip (RemoteSocket integration test).
3. Screen-Share keycode paste.

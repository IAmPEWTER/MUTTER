# MUTTER → Handy migration spec

Status: proposed. Source research: `logs/2026-07-06-handy-migration-feasibility.md` (5-agent read of Handy `dad37ba`, MIT). Telegraphic by design.

## 0. Goals — definition of done
The new MUTTER ships only when all three hold:
1. **Completely robust.** Zero process-suicide recovery; no restart loop. Survives sleep/wake, mic device changes, and CGEventTap-disable with no user-visible failure — the two MUTTER root failures (§6) are structurally gone or healed in-process. The one latent audio wedge (§4) is closed with a `stop()/close()` timeout before ship.
2. **End-to-end tested.** Automated tests exercise the *real* flow on *real* audio — fn-hold → capture → VAD → MLX socket round-trip → text injection — plus the Screen-Sharing keycode path, run in CI/pre-commit. Not unit fakes alone: MUTTER's fatal gap was that the two things that actually break (PortAudio teardown, missed fn-up) were untested. Those exact failure modes get regression coverage.
3. **Full feature parity.** Every requirement R1–R18 (§2) working and verified. **No feature regressions** vs the current version, and the Python Mac daemon is not retired until parity is proven.

**Platform scope:** this migration covers the **Mac** app (Handy also yields Windows/Linux for free if ever wanted). It does **not** cover mobile — Handy is desktop-only; the existing Android (Kotlin) app and future iOS stay separate by necessity (iOS/Android sandbox forbids system-wide text injection; mobile dictation = custom keyboard / share sheet, a different app).

## 1. Why
MUTTER's fragility is 100% in the **Python front-end substrate**, not the ASR. Two root failures (see §6): PortAudio teardown deadlock → process-suicide restart loop; naive CGEventTap drops fn-up. The MLX whisper service (`~/Documents/services/whisper/`) is the robust part and stays.

Move the substrate to **Handy** — a native Rust (Tauri) dictation app that already solves both root failures and ships most of a dictation product. Keep our MLX service via a thin socket client. Handy is MIT (fork-friendly), pushed daily, 58 releases, 100+ contributors — not a dead end.

**Thesis:** replace the fragile hand-rolled Python audio+eventtap layer with Handy's hardened native one; keep our ASR brain and our custom behaviors.

## 2. What the new app must do — full parity with MUTTER
Every MUTTER behavior, with Handy status (✅ built-in/config · 🟡 small graft · 🟠 real work · 🔴 build).

| # | Requirement | Status | How |
|---|---|---|---|
| R1 | Trigger: **hold fn/🌐 (keycode 63)** = record; release = stop+transcribe | ✅ config | `handy-keys` backend treats fn as modifier (`MaskSecondaryFn`, `0x3F`); `push_to_talk:true`; set binding `"fn"` |
| R2 | 3-state machine idle→recording→transcribing | ✅ | `transcription_coordinator.rs:72-79` |
| R3 | **Force built-in mic**, never system default (anti-Bluetooth A2DP→HFP) | ✅ config | Named pin `commands/audio.rs:200-217`; bonus clamshell pin |
| R4 | VAD: detect speech, segment, trim | ✅ upgrade | Silero ONNX + smoothing (onset/pre-roll/hangover) replaces RMS |
| R5 | Capture 16 kHz mono | ✅ | Native rate → resample to 16 kHz f32 (rubato) |
| R6 | **Mute system output while recording** | ✅ config | `mute_while_recording`, `managers/audio.rs:23-110` |
| R7 | Max-recording cap (MUTTER 120 s) | 🟡 | Add timeout in coordinator |
| R8 | **ASR via shared MLX unix-socket service; keep the service** | 🟡 graft | New `RemoteSocket` engine variant; transcribe() gets `Vec<f32>` 16 kHz mono = what MLX eats |
| R9 | Repeat-collapse (dedupe hallucinated repeats) | ✅ half | `collapse_stutters` (`text.rs:236`) does single-word; extend to phrases |
| R10 | Sanitizer / filler removal | ✅ | `filter_transcription_output` built-in |
| R11 | Optional LLM cleanup | ✅ hook | `llm_client.rs` (local/OpenAI-compat/Apple Intelligence) |
| R12 | Inject text into focused app (default) | ✅ | `PasteMethod::CtrlV` clipboard-paste, enigo |
| R13 | **Screen-Sharing: real keycodes char-by-char, 8 ms delay** | 🟠 build | New `frontmost.rs` (NSWorkspace) + `paste_keycodes.rs`; enigo already has the hardware-keycode primitive |
| R14 | Clipboard fallback + **preserve user clipboard** | ✅ | `clipboard.rs:16-79` save/restore, default `DontModify` |
| R15 | Frontmost-app detection | 🔴 build | ~1 objc2/Swift fn (none in Handy) — feeds R13 |
| R16 | Single instance | ✅ | Tauri single-instance + `cli.rs` remote-control |
| R17 | Auto-restart on crash / run at login | ✅ | Autostart plugin; app is resident (no restart loop by design) |
| R18 | Failure persistence (retry queue) | 🟡 | MUTTER's `~/.mutter/pending/` never fired; add only if needed |

## 3. How — probable migration path
No fork. Vendor Handy release tag into the MUTTER repo at `desktop/` via `git subtree add --squash` (local-only `handy` fetch-remote). Updates: fetch new tag → `git subtree pull --prefix=desktop --squash` → resolve seam+rebrand conflicts → smoke test. Rebrand = surface only (name, tray, icons, bundle id `com.peter.mutter.app`); internal identifiers stay Handy; upstream `LICENSE` retained in `desktop/` (MIT). Discipline: **new files + thin 1-line seams** (see §5), never rewrite an upstream fn.

- **P0 spike (proof-of-life):** build on this Mac; set binding `"fn"`, grant Accessibility; confirm fn-hold dictation + default paste with a stock model. Validates R1/R3/R12.
- **P1 keep-the-service:** add `RemoteSocket` transcriber → new `remote_socket.rs` + variant in `EngineType`/`LoadedEngine` + 1-line `transcribe` arm + register `ModelSource::Local` pseudo-model. Point at `~/Documents/services/whisper/*.sock`; confirm MLX round-trip on real audio. Validates R8.
- **P2 injection:** `frontmost.rs` + `paste_keycodes.rs` + 1 branch line in `paste()`. Validates R13/R15.
- **P3 robustness:** audio `stop()/close()` timeout (§6-gap) — **PR upstream** (fix-shaped). R7 cap.
- **P4 finish:** repeat-collapse phrase extension (R9); retire Python Mac daemon; `android/` untouched.

Config to set: HandyKeys backend + Accessibility granted, mic=built-in, `push_to_talk:true`, `mute_while_recording`, `PasteMethod`, VAD thresholds (lift consts→settings if runtime tuning wanted).

## 4. Gotchas
- **fn needs HandyKeys backend + Accessibility.** Missing perm → silent fallback to Tauri backend, which *rejects* fn and resets binding to `option+space` (`tauri_impl.rs:54-60`). Grant at first run; pin backend.
- **RemoteSocket must bypass "model on disk" gates** (`transcription.rs:478,492`; `model.rs:2270,1305`) — register pseudo-model `ModelSource::Local, is_downloaded:true`; special-case `update_download_status`.
- **Default Cmd+V into Screen Sharing pastes the *remote's* clipboard, not our text** → keycode path (R13) stays mandatory.
- **cpal matches mic by name string, not stable UID** — duplicate display names → first match. Built-in name stable in practice.
- **Latent audio wedge:** permanently-silent cpal callback hangs `stop()/close()` under `recorder.lock()` — close with `recv_timeout` (P3). Smaller + fixable vs MUTTER's C deadlock.
- **Not whisper-rs.** Engines are cjpais forks `transcribe-cpp` + `transcribe-rs`; RemoteSocket sidesteps both.
- **Build weight:** Rust+Bun+Xcode CLT+cmake+ONNX; slow first build; fetch Silero model manually first run; Apple-Silicon = smooth path.
- **Vendor upkeep:** feature freeze upstream → carry *features* in our tree, adopt by **release tag** only, keep `PATCHES.md`, smoke-test 3 features per merge. Upstreaming fixes = optional, via throwaway fork. (detail in feasibility log)

## 5. Seam discipline (keeps updates cheap)
Custom code in NEW files upstream never touches: `remote_socket.rs`, `frontmost.rs`, `paste_keycodes.rs`. Edits in *their* files = 1-line delegations (`RemoteSocket(c) => c.transcribe(audio)`). Then a conflict = re-apply one line, not a rewrite.

## 6. Why Handy is less fragile — exact decisions vs our version
| Root failure | MUTTER (fragile) | Handy (robust) | The different decision |
|---|---|---|---|
| **Audio teardown** | PortAudio `Pa_CloseStream` re-enters CoreAudio HAL lock → deadlock after sleep/wake/device-change; only cure = 180 s watchdog `os._exit(1)` + launchd respawn (`daemon.py:664-675`) | cpal `Stream::Drop` (stop→uninit→dispose), no lock reentrancy, no panic; open-path self-heals stale device (cache-invalidate + retry, `managers/audio.rs:400-409`) | **Own the audio loop in native Rust** vs depend on a C lib whose teardown you can't fix → recovery becomes a code change, not a process kill |
| **Trigger tap** | CGEventTap, naive listen-only; macOS disables it under load/sleep → **silently drops fn-up**; 0.5 s poller patches some (`daemon.py:544`, 10× "missed fn-up") | Same CGEventTap tech, but **re-enables the tap every 100 ms** when disabled (`listener.rs:463-475`), **reconciles modifiers vs real OS flags** each event (`:80-110`), derives fn fresh from flags never latched (`:138-142`) | **Assume the tap WILL be disabled** and engineer re-enable+reconcile as a first-class path, vs assume it's reliable and bolt on a partial poller |
| **Recovery model** | Process suicide is the *primary* mechanism → restart loop (4 PIDs/session), up-to-180 s startup block on whisper | Resident app; failures self-heal in-process; model warm out-of-process; watchdog not load-bearing | Robustness lives in the **happy-path code**, not the safety net |
| **Runtime/deps** | Python driving CoreAudio via PortAudio + Quartz via pyobjc/pynput; unpinned `>=` deps + gitignored venv → reinstall pulls latest fragile keystroke libs | Compiled Rust: cpal(coreaudio-rs)+enigo, `Cargo.lock`-pinned graph | Compile against OS APIs with a locked graph vs stack of glue libs that each break across macOS versions |
| **Capture rate** | Forces hardware to 16 kHz → fragile on Bluetooth/ALSA codec negotiation | Captures device-native rate, resamples in software (rubato, `recorder.rs:403-456`) | Don't fight the hardware clock; resample in code |
| **VAD** | RMS energy gate → misfires on noise/music | Silero neural VAD + smoothing | Correctness robustness, not just uptime |

**One-line answer:** MUTTER bet that a Python process could reliably drive CoreAudio (through PortAudio) and the event tap, with a process-suicide watchdog as the net. Handy makes the opposite bets — own the audio loop in Rust so teardown *can't* deadlock, and treat tap-disable as expected so re-enable+reconcile is designed in. The failures that force MUTTER to kill itself are, in Handy, either structurally impossible or healed in-process.

# R1–R18 parity checklist — MUTTER (vendored Handy) vs legacy

Gate for retiring the python Mac daemon (spec §0 goal 3). Status: ✅ verified · 🧪 covered by automated test · ⏳ pending soak · ⚠ gap.

| # | Requirement | Status | Evidence |
|---|---|---|---|
| R1 | Hold fn (kc 63) = record; release = stop+transcribe | ✅ | Log: synthetic fn `state=Pressed → TranscribeAction::start`; smoke `scripts/smoke/fn_trigger_smoke.sh` |
| R2 | 3-state machine idle→recording→transcribing | ✅ | `transcription_coordinator.rs`; coordinator watchdog tests |
| R3 | Force built-in mic, never system default | ✅ | `selected_microphone` pin honored (log shows selected device used) |
| R4 | VAD detect/segment/trim | 🧪 | Silero ONNX; P1b tier-A asserts non-empty voiced segments on fixture |
| R5 | Capture 16 kHz mono | ✅ | native rate → rubato resample → 16k f32 (upstream) |
| R6 | Mute system output while recording | ✅ | `mute_while_recording` (upstream `managers/audio.rs`) |
| R7 | Max-recording cap (120 s) | 🧪 | `max_recording_secs` watchdog; coordinator tests |
| R8 | ASR via shared MLX unix-socket service | ✅🧪 | RemoteSocket live daemon round-trip; P1b tier-A fixture→text |
| R9 | Repeat-collapse (word + phrase) | 🧪 | `collapse_stutters` + `collapse_phrase_repeats`; 13 tests |
| R10 | Sanitizer / filler removal | ✅ | `filter_transcription_output` (upstream) |
| R11 | Optional LLM cleanup | ✅ | `llm_client.rs` opt-in (upstream, off by default) |
| R12 | Inject text into focused app (default) | ⏳ | `PasteMethod::CtrlV` clipboard-paste; confirm in soak on real apps |
| R13 | Screen-Sharing real keycodes, 8 ms | 🧪⏳ | `paste_keycodes.rs` + 6 map tests; live Screen-Sharing confirm in soak |
| R14 | Clipboard fallback + preserve user clipboard | ✅ | `clipboard.rs` DontModify save/restore (upstream) |
| R15 | Frontmost-app detection | 🧪 | `frontmost.rs` NSWorkspace |
| R16 | Single instance | ✅ | Tauri single-instance + cli.rs (upstream) |
| R17 | Auto-restart on crash / run at login | ⏳ | Autostart plugin (`autostart_enabled`); resident app, no restart loop. Enable for soak |
| R18 | Failure persistence (retry queue) | ⚠ n/a | MUTTER's `~/.mutter/pending/` never fired; not ported. Add only if a real drop appears |

## Not-regressions vs legacy (spec §6 root failures)
- **Audio teardown deadlock** → gone: cpal `Stream::Drop`, no PortAudio HAL reentrancy. Latent silent-callback wedge closed with recv_timeout/join-timeout (`recorder.rs`; regression tests).
- **CGEventTap drops fn-up** → handled upstream: tap re-enabled every 100 ms, modifiers reconciled vs OS flags each event, fn derived fresh from flags.
- **Process-suicide restart loop** → gone: resident app, no `os._exit` watchdog, model warm out-of-process (MLX service).

## Retirement gate (do not remove python until ALL hold)
1. Every R above ✅ or 🧪 (R18 waived — never used). ⏳ items closed during soak.
2. ~1 week daily-driver soak on the native app with no user-visible failure.
3. `mutter-switch python` fallback verified working (instant revert path).
Then: `mutter-switch app` permanent + enable autostart; python daemon `launchctl bootout` (plist/code KEPT until Peter approves deletion).

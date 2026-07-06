# Handy-as-base feasibility — MUTTER migration research

Date: 2026-07-06. Method: read-only source analysis of Handy `dad37ba` (5 parallel agents), cloned to scratchpad. Repo: github.com/cjpais/Handy (MIT).

**Verdict: GO.** Every MUTTER feature ports. MLX socket service is kept. Not a dead end. Only one genuinely from-scratch piece (Screen-Sharing detection), and that's MUTTER code being re-homed.

## Why Handy at all
MUTTER's fragility is 100% in the Python front-end substrate (PortAudio teardown deadlock → process-suicide loop; CGEventTap dropping fn-up). The MLX whisper service is the robust part. Handy replaces the fragile substrate (native Rust: cpal + a hardened CGEventTap crate) while letting us keep the MLX service. See 2026-07-06 diagnostic (prior log / chat).

## Per-feature verdicts (✅ works / 🟡 small graft / 🟠 real work / 🔴 build-from-scratch)

| MUTTER feature | Verdict | Evidence / notes |
|---|---|---|
| fn/🌐 hold-to-talk trigger | ✅ config only | `handy-keys` is default macOS backend, treats fn as modifier via CGEventTap+`MaskSecondaryFn`, keycode `0x3F`=63 (`handy-keys .../macos/keycode.rs:71,253`). `push_to_talk:true` default (`settings.rs:804`). down=record/up=stop machine at `transcription_coordinator.rs:72-79`. Set transcribe binding = `"fn"`. **Gotcha:** grant Accessibility at first run or it falls back to the Tauri backend, which hard-rejects fn (`tauri_impl.rs:54-60`). |
| Force built-in mic (anti-Bluetooth) | ✅ superset | Name-based pin `commands/audio.rs:200-217`, resolved `managers/audio.rs:260-299`; used regardless of system default. Bonus: 2nd clamshell/docked pin MUTTER lacks. Caveat: cpal matches by name string, not stable UID. |
| PortAudio teardown deadlock (the crash) | ✅ root-fixed | cpal `Stream::Drop` (`recorder.rs:281`) replaces PortAudio's HAL-locking close. No panic/unwrap on teardown. Open path self-heals stale device (cache-invalidate + 1 retry, `managers/audio.rs:400-409`). |
| Audio robustness residual | 🟡 small graft | One latent hang: a permanently-silent cpal callback (yank active external mic, never restore) can wedge `stop()`/`close()` under `recorder.lock()` (`recorder.rs:601-607,326-343`). Fix = `recv_timeout`/join-timeout + optional periodic consumer tick + CoreAudio device-change listener. **In our Rust, fixable — not the unfixable C deadlock.** Pinning built-in mic makes the window tiny. |
| VAD | ✅ upgrade | Silero ONNX + smoothing state machine (onset debounce, pre-roll, hangover) — `vad/silero.rs`, `vad/smoothed.rs`. Strict upgrade over RMS. Knobs are compile-time consts (`vad/mod.rs:3-6`); lift to settings if runtime tuning wanted. |
| Mute system output while recording | ✅ built-in | `managers/audio.rs:23-110`, `set volume output muted` via osascript, gated by `mute_while_recording`, un-mutes once. |
| Sample format | ✅ | Captures native rate, downmixes mono, resamples to 16 kHz f32 (`recorder.rs:403-456,527-531`, rubato). MLX-friendly. |
| **Keep MLX socket ASR service** | ✅ small graft | Engine dispatch is `enum LoadedEngine` + `match` (NOT a trait) — `managers/transcription.rs:159,1094,1213`. Add `RemoteSocket` variant across ~4 sites: `EngineType`(`model.rs:26`), `LoadedEngine`(`:159`), `load_model` arm(`:520`), `transcribe` arm(`:1213`). Bypass the "model must exist on disk" gates (`transcription.rs:478,492` + `model.rs:2270,1305`) by registering a `ModelSource::Local, is_downloaded:true` pseudo-model. transcribe() receives `Vec<f32>` 16 kHz mono — **exactly what the MLX daemon eats**. Streaming/idle-unload/history all engine-agnostic. ~15-line socket-client arm. |
| Repeat-collapse (hallucinated repeats) | ✅ half-built | `collapse_stutters` already collapses 3+ consecutive identical *words* (`audio_toolkit/text.rs:236`), runs on every transcription via `post_process_transcription_text` (`transcription.rs:1598`). Multi-word phrase repeats = extend that one fn. |
| LLM cleanup (optional) | ✅ hook exists | `llm_client.rs` (OpenAI-compat incl. custom local base_url, Anthropic) + `apple_intelligence.rs` (on-device). Opt-in via `transcribe_with_post_process`. Point at a local endpoint. |
| Default text injection | ✅ | `PasteMethod` enum, macOS default `CtrlV` clipboard-paste (`settings.rs:201-208`, `clipboard.rs:618-638`), enigo 0.6.1. |
| Clipboard save/restore | ✅ built-in | `clipboard.rs:16-79`, default `DontModify` restores original after paste. |
| **Screen-Sharing keycode injection** | 🟠 real work | (1) frontmost-app detection **does not exist** — no NSWorkspace/objc2 anywhere; build ~1 fn (objc2 `NSWorkspace.frontmostApplication.bundleIdentifier` or Swift shim). (2) graft `paste_via_keycodes()` alongside existing strategies, hook in `paste()`'s match (`clipboard.rs:618-647`). enigo already exposes the hardware-keycode primitive that crosses into the remote (`Key::Other`/`Key::Unicode`→`CGEventCreateKeyboardEvent`), so no new native code for injection itself. **Still necessary:** Handy's default Cmd+V reaches the remote but pastes the *remote's* clipboard, not our text. This is MUTTER code being re-homed. |
| Per-app config | 🔴 none | Settings are global; no bundle-id branching. Single `paste()` chokepoint is a clean place to add the map ourselves. |

## Health / dead-end (GitHub API, not the shallow clone)
- 25.9k stars, 2.2k forks, **100+ contributors**, pushed **today**, **58 releases**, latest v0.9.0 (2026-07-01), weekly-biweekly cadence over 11 months. **Not a dead-end.**
- MIT (`LICENSE`, `Cargo.toml:7`) — fork + private + proprietary additions all permitted.
- Clean extend seams for a Rust-backend dev: manager pattern, 106 tauri commands, `AppSettings` struct with `#[serde(default)]` per field (backend-only setting = field + default fn, no frontend change), auto-generated TS bindings (`lib.rs:647-653`). Excellent `AGENTS.md` (good for AI-assisted work).

## Risks / costs (eyes open)
1. **Founder concentration.** cjpais = 478 commits vs #2 at 22. Active contributor tail + 25.9k stars means it won't vanish, but momentum rides on him.
2. **Inherited forked deps.** Core stack pins cjpais forks of tauri-runtime/-wry/-utils, rodio, vad-rs, hf-hub, rdev, plus his own `transcribe-cpp`/`transcribe-rs` engines (`Cargo.toml:149-152` patch section). All MIT/public — a maintenance liability if they stall, not lock-in. (Note: if we go RemoteSocket for ASR we barely touch the engine crates.)
3. **Upstream feature freeze** (`AGENTS.md:213`) — our custom trigger/remote-client/injection won't be upstreamed. **We carry a permanent fork and merge upstream fixes ourselves.** Fine for a private on-the-service base.
4. **Build friction (moderate).** Rust + Bun + Xcode CLT + cmake; transcribe-cpp compiles C++/Metal, transcribe-rs pulls ONNX Runtime (smooth on Apple Silicon, fiddlier on Intel), Silero model fetched manually first run. Slow first `tauri build` (lto=true). Incremental dev builds fine.
5. **A few dense core files** (`model.rs` 2704, `transcription.rs` 1953 LOC) — reading cost before modifying, not spaghetti.

## Effort to full parity (rough)
- Config-only / built-in: fn trigger, mic-pin, mute, VAD, clipboard save/restore, default paste. → **~0 code.**
- Small grafts: RemoteSocket ASR client (~1 file), audio stop/close timeout hardening, repeat-collapse phrase extension. → **contained Rust.**
- Real work: Screen-Sharing frontmost detection + keycode branch. → **the one from-scratch piece; MUTTER already has the logic to port.**

## Recommended path
1. Spike first: fork → build on this Mac → set binding to `"fn"`, grant Accessibility → confirm fn-hold PTT + default paste works end-to-end with a stock model.
2. Add `RemoteSocket` transcriber → point at `~/Documents/services/whisper/*.sock` → confirm MLX round-trip. This validates the "keep it on the service" thesis with real audio.
3. Port Screen-Sharing injection + audio-timeout hardening.
4. Retire MUTTER Python Mac daemon; keep android/ separate.

## Can we take upstream updates while keeping our features? YES, with one rule
Empirical churn (full 693-commit history unshallowed):
- Upstream **adds engines additively** — the exact move RemoteSocket makes. `add cohere (#1200)` = +28 lines, 1 file. SenseVoice/Moonshine/GigaAM/Canary/Parakeet all landed the same way. So our variant merges like one of their own engine adds.
- **The rule:** put ~all custom code in NEW files (immune to their changes) — `remote_socket.rs`, `frontmost.rs`, `paste_keycodes.rs` — and reduce edits in their files to 1-line seams that delegate (`RemoteSocket(c) => c.transcribe(audio)`). Never rewrite an upstream fn; branch out of it. Then a conflict = "re-apply one line," not a debug session.
- **Bounded real cost:** ~1-2×/yr a hot file gets reshaped. Proof: `introduce transcribe.cpp (#1529)` = **+1288/-289 in transcription.rs** (whisper-rs→transcribe-cpp swap). When that hits, re-place the thin seam (~1hr) — logic stays safe in our own file.

Churn on files we touch (all-time / last-90d) + exposure:
| File | all/90d | our change | exposure |
|---|---|---|---|
| managers/transcription.rs | 56/4 | 1-line RemoteSocket seam → own module | Low (re-place on reshapes like #1529) |
| managers/model.rs | 28/2 | enum variant + pseudo-model reg | Low |
| clipboard.rs | 21/0 | 1 branch line in `paste()` | Very low (cold) |
| input.rs | 1/0 | reuse, no edit | None |
| settings.rs | 75/2 | additive fields | Low (additive merges clean) |
| recorder.rs | 17/3 | audio stop/close timeout | **offload → PR upstream** |
| managers/audio.rs | 42/2 | (timeout fix) | **offload → PR upstream** |
| shortcut/mod.rs | 17/4 | none (fn is config) | None |

**Force multiplier — split the changes:** carry *features* (fn config, RemoteSocket, Screen-Sharing) in the fork; **PR *fixes* upstream** (audio stop/close timeout, repeat-collapse phrase extension). They're bug-shaped, and history shows `fix:` PRs merge even under the feature freeze — once accepted, upstream maintains them and our fork delta shrinks.

**Workflow:** fork → `upstream` remote → adopt by **release tag** (not every commit) → `PATCHES.md` ledger of every seam+why → 3-feature smoke test (fn-hold, MLX round-trip, Screen-Share paste) after each merge.
Tailwind: feature freeze → near-term updates are mostly fixes+model-adds (merge easy). Caveat/speculation: a freeze can precede a rewrite; #1529 proves big reshapes happen — budget the occasional hour, don't expect zero.

Crate source for handy-keys read at scratchpad `handy-keys-src/handy-keys-0.2.4/`. Handy clone at scratchpad `Handy/`.

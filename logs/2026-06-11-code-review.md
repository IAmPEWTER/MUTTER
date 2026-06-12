# 2026-06-11 — Full code review (both platforms), pre-fix findings

User symptoms: (1) Android hold→talk→buzz→nothing written; (2) Mac spews hallucinated text without fn press.

## Android root causes
- **Restore clobbers failed injection**: inject fail → haptic(80) → text "left on clipboard" → end-of-hold `injector.finish()` restores old clipboard 200 ms later, destroying transcript. MutterAccessibilityService.kt:225 + TextInjector.kt:74.
- **Press swallowed during TRANSCRIBING**: `handleDown` returns true when state != IDLE — next hold while last chunk drains records nothing, zero feedback. :147
- **Engine load/transcribe failure drops chunk audio forever** (haptic 100). :200-207
- **Filter eats real words**: chunks are VAD-confirmed speech, yet HallucinationFilter drops "okay"/"thank you"/"yeah"/"you" as standalone dictations. EnergyGate double-drops quiet <1 s chunks.
- Races: `engine.release()` (onUnbind) can free native recognizer mid-`transcribe()` (transcribe not @Synchronized during decode); `abortToIdle` restores clipboard off-FIFO (can paste stale clipboard mid-chunk); `injectedThisHold` reset races prior hold's draining chunks.

## Mac root causes
- **Phantom fn**: tap edge-detects the fn *flag*; macOS sets kCGEventFlagMaskSecondaryFn for arrow/Home/End/PgUp/PgDn/fwd-delete → phantom fn-down records ambient. Real fn key = keycode 63 (kVK_Function); no keycode filter exists. daemon.py:437-448.
- **Forced transcription of silence**: `finish()` sets `has_spoken=True` unconditionally (stt.py:450) → ambient/silence reaches whisper → hallucination → typed.
- **Missed fn-up** (tap disabled by timeout mid-hold) → records to 120 s cap → auto-transcribes room audio → "bunch of text" spew. No flag-state reconciliation.
- **Quick-tap race**: fn-up before listen() initializes → finish() no-ops (vad None) → listen() sets _recording=True after → deaf up to 120 s + watchdog os._exit. daemon.py:468/stt.py:377.
- **>120 s hold**: capture caps, transcribes mid-hold, remainder of speech lost.
- Inject exception → text lost silently (no clipboard fallback). Phrase blacklist eats deliberate "okay"/"thank you" (same as Android).

## Fix philosophy (decided)
Acoustic evidence gates transcription (VAD/RMS-confirmed speech), then TRUST the transcript — blacklist shrinks to decoder repeat-pathology only. PTT = explicit intent; false drops worse than rare hallucination. Never-drop ladder on injection: primary path → fallback → clipboard preserved (skip restore) + notification. Audio never discarded on engine failure (retry, then persist WAV).

## Outcome (same day)
All fixes shipped. Commits 8c2c8cc, c0f5719 (mac), c6c8e6c, 1e94fd0, 0df0eff, e2a39f8 (android). Mac daemon restarted live (pid 76870, ready). Android v0.5.0 released to IAmPEWTER/mutter-releases (self-updater path); user installs via Settings → Check for updates. Tests: mac 23 pass, android 35 pass. Docs synced (SDD, android design, DECISIONS ×2). User confirmed "500 thank yous" symptom pre-fix — matches missed-fn-up + forced-transcription chain; defense in depth: keycode filter + reconciler + acoustic gate + collapse_repeats.

## Cleanup pass (same day)
- Bug found in own work: mac pending-WAV name was second-resolution — fast-failing queued segments overwrote each other. Fixed with per-process sequence suffix.
- Live phantom test against running daemon: posted flagsChanged (keycode 126 + fn flag) → daemon correctly inert (no mute, no turn). Keycode filter verified in vivo.
- Empirical quirk: synthetic posted flag events do NOT register in `CGEventSourceFlagsState` — reconciler unspoofable, but physical-hold reading unverifiable without a finger. Hardened with 2-poll debounce (heal at ≤1 s) so one transient misread can't cut a dictation. If a real dictation ever cuts at ~1 s, err.log will show "missed fn-up healed by reconciler" — that's the diagnostic.
- android/README.md de-staled (file tree, test counts, removed pre-phone-arrival sections).

## Notes
- Mute logic (mac): 3 commits of regression history (3f2784a→af342bc revert). DO NOT touch.
- Live daemon: pid in /tmp/mutter.pid, logs /tmp/mutter.{out,err}.log, mic = 'MacBook Air Microphone'.
- Tests: mac pytest 14 pass-claimed; android ./gradlew test 41 pass-claimed.

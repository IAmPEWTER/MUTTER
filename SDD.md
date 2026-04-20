# MUTTER — Software Design Document

Mic → whisper → types into focused app. Hold fn to dictate, anywhere on macOS.

## Goal

Replace macOS built-in dictation. System-wide push-to-talk with a local
Whisper model. Never touches the clipboard. Zero perceptible latency
after the one-time boot.

## Non-goals

- Not a Claude wrapper. Doesn't know Claude exists.
- Not a streaming-live-transcription tool (v1). Types on release.
- Not a voice-assistant. No TTS output. No intent-parsing.

## Architecture

One resident Python process. No Karabiner, no shell bridges.

```
  fn-down / fn-up (system-wide keyboard event)
        │
        ▼
  ┌────────────────────────────────────────────┐
  │ mutter daemon (single process)             │
  │                                            │
  │   CGEventTap on flagsChanged events        │
  │   └─ edge-detects fn bit in CGEventFlags   │
  │       │                                    │
  │       ▼                                    │
  │   state machine (IDLE / LISTENING /        │
  │   TRANSCRIBING) + Listener from stt.py     │
  │       │                                    │
  │       ▼                                    │
  │   pynput Controller.type(transcript)       │
  └────────────────────────────────────────────┘
```

### 1. mutter/daemon.py

One persistent Python process. At boot:
- `_start_event_tap()` creates a `kCGSessionEventTap` for
  `kCGEventFlagsChanged` events on a dedicated background thread with
  its own `CFRunLoop`. Returns `False` (and the daemon exits 1) if
  Accessibility isn't granted — `launchctl` then restarts us until
  the user grants it once.
- `Listener.start()` loads mlx-whisper and pre-warms the model with a
  zero buffer (the warmup lives inside `_MlxBackend.__init__`).
- Installs `SIGTERM` / `SIGINT` handlers for clean `launchctl unload`.
- Acquires `/tmp/mutter.pid` (single-instance lock).
- Main thread idles on `time.sleep(0.5)` forever.

State machine (3 states, protected by a lock — the tap callback runs
on a different thread from the worker):

```
IDLE ── fn-down ──→ LISTENING ── fn-up ──→ TRANSCRIBING ──→ IDLE
                       │                                     ▲
                       └── listener.listen() polling ─ finish ┘
```

- On **fn-down** (flagsChanged with fn bit flipping 0→1): if `IDLE`,
  flip to `LISTENING`, spawn a worker that calls
  `listener.listen(silence_duration=3600)`. The big silence window is
  intentional — we never want VAD to auto-stop; the caller decides
  when to stop.
- On **fn-up** (flagsChanged with fn bit flipping 1→0): if
  `LISTENING`, flip to `TRANSCRIBING`, call `listener.finish()`. The
  worker's poll loop exits, audio is transcribed, hallucinations are
  filtered, text is injected.
- Worker drops back to `IDLE` when done.

Re-entrancy rules:
- fn-down in LISTENING/TRANSCRIBING: no-op.
- fn-up in IDLE/TRANSCRIBING: no-op.
- The callback only acts on **edges** (`fn_on != _fn_was_on`), so
  flagsChanged events from other modifiers (shift, ctrl, option) don't
  trigger anything.

Tap self-healing: if macOS disables the tap (slow callback, or a user-
input flood), the callback receives `kCGEventTapDisabledByTimeout` /
`kCGEventTapDisabledByUserInput` and re-enables the tap in place.

### 2. mutter/stt.py

Copied verbatim from CLAWS (`Code/claws/claws/stt.py`). Zero changes.
Reused, not forked. Contains:
- `Listener` — mic → whisper pipeline with `listen()`, `finish()`,
  `cancel()`.
- `_VadState` — pure time-parameterised VAD.
- `_MlxBackend`, `_FasterWhisperBackend` — pluggable whisper wrappers.
- `is_hallucination()` — filters "thank you", "subscribe", "you", etc.
- `MIN_CAPTURED_SEC` — drops clips shorter than 0.3 s.
- `is_model_cached()` — for readable "downloading..." vs "loading..."
  startup messages.

### 3. Injection — pynput

`pynput.keyboard.Controller().type(text)` wraps macOS
`CGEventKeyboardSetUnicodeString`. Types characters into whatever app
has focus. Clipboard is NEVER read or written. Handles unicode, emoji,
quotes, newlines, arbitrary length.

Text is sanitized first: `\n` and `\r` → space, runs of spaces
collapsed, trimmed. A dictation transcript cannot accidentally submit a
CLI prompt by containing a stray newline.

### 4. LaunchAgent (com.peter.mutter.plist)

- `RunAtLoad: true` — daemon starts at login.
- `KeepAlive` only on crash (`SuccessfulExit: false`) — restart on
  crash, but not if the user kills it manually. This is also what
  brings the daemon back up after the one-time Accessibility grant.
- `ThrottleInterval: 10` — if the daemon crash-loops, at least 10 s
  between restarts.
- Stdout/stderr go to `/tmp/mutter.{out,err}.log` for post-mortem.

## Latency budget

| Stage | Cost | Notes |
|---|---|---|
| fn-down → CGEventTap callback | ~3–5 ms | kernel → tap thread |
| callback → worker thread starts listening | ~5 ms | thread spawn + one lock acquire |
| **Total fn-down → mic hot** | **~10 ms** | imperceptible |
| fn-up → finish() | ~5 ms | same path, reversed |
| Whisper transcribe (short clip) | ~100–300 ms | mlx Metal on M-series |
| pynput types 40-char sentence | ~20–80 ms | one CGEvent per char |
| **Total fn-up → text appears** | **~150–400 ms** | feels instant |

Cold start (login): ~10 s mlx load. Paid once per login, not per press.

## Edge cases handled

- **Multiple daemons**: pidfile lock.
- **Stale pidfile** (old daemon crashed without cleanup): daemon
  detects dead PID via `kill(pid, 0)` and overwrites.
- **Accessibility not granted**: `CGEventTapCreate` returns NULL,
  daemon exits 1 with a clear log message; launchd restarts every
  `ThrottleInterval` seconds until the user grants it once.
- **Tap disabled by OS** (slow callback / input flood): the callback
  re-enables the tap in place.
- **Stray fn-up** (release without prior press): state-machine no-op.
- **Double fn-down** (while already LISTENING): state-machine no-op.
- **Non-fn flagsChanged events** (shift, ctrl, option, caps): ignored
  because the fn bit didn't change.
- **Short tap of fn** (<300 ms): `MIN_CAPTURED_SEC` drops clip.
- **Silent hold** (fn held but no speech): `is_hallucination` catches
  "thank you", "you", etc.
- **Transcript contains `\n`**: sanitizer replaces with space.
- **Long transcript**: pynput types chars one-by-one; no length limit.
- **Special chars / unicode / emoji**: CGEventKeyboardSetUnicodeString
  handles all.
- **Focus change mid-hold**: text injects into whatever's focused at
  release. User's responsibility not to switch windows.

## Known caveats (document, don't fix)

1. **macOS built-in dictation must be disabled** (System Settings →
   Keyboard → Dictation → Off). Otherwise fn can fire Apple's
   dictation simultaneously.
2. **fn+F-key combos record a brief empty clip**. Harmless (filtered
   out) but does mean the mic turns on for ~100 ms during, e.g., a
   brightness-up press.
3. **Transcribe blocks the worker thread** (100–300 ms). A second
   fn-tap during transcribe is ignored. Fine for human-scale typing.
4. **Release-to-text gap**. If you release fn and start typing on the
   keyboard immediately, typed chars and dictated chars interleave.
   Wait ~250 ms for the dictation to land before touching the
   keyboard again.

## File tree

```
MUTTER/
├── SDD.md                       — this file
├── NOTES.md                     — build log + decisions
├── README.md                    — setup instructions for the user
├── mutter/
│   ├── __init__.py
│   ├── stt.py                   — verbatim copy from CLAWS
│   └── daemon.py                — CGEventTap + state machine + inject
├── tests/
│   └── test_daemon_refactor.py  — pidfile, state machine, fn transitions
├── com.peter.mutter.plist       — LaunchAgent for login autostart
├── requirements.txt
└── .venv/                       — Python 3.11 + deps (gitignored)
```

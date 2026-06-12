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
  │   └─ keycode 63 (real fn key) only, then   │
  │      edge-detects fn bit in CGEventFlags   │
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
- `WhisperClient.ping()` confirms the whisper service is up; if it's
  not (e.g. cold boot, service still loading its model), the daemon
  blocks in `wait_for_service(timeout=180)` before proceeding.
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

- On **fn-down** (flagsChanged, keycode 63, fn bit flipping 0→1): if
  `IDLE`, flip to `LISTENING`, `begin_turn()`, spawn a capture worker
  that calls `listener.capture(silence_duration=3600)`. The big
  silence window is intentional — VAD never auto-stops; the caller
  decides when to stop.
- On **fn-up** (keycode 63, fn bit 1→0): if `LISTENING`, flip to
  `TRANSCRIBING`, call `listener.finish()`.
- The capture worker queues the PCM on a FIFO **tx queue** and drops
  state back to `IDLE` immediately — a single consumer thread
  transcribes + injects in spoken order, so a slow transcription can
  never swallow the next press. If capture hit its 120 s segment cap
  while fn is still held, the worker loops straight into a fresh
  capture: marathon holds stream out in segments, nothing lost.

The keycode-63 filter matters: arrow/Home/End/PgUp/PgDn/forward-delete
and F-keys ALSO toggle the fn *flag* in flagsChanged events on Apple
keyboards. Without the filter, holding an arrow key phantom-recorded
ambient audio and whisper's hallucination of it got typed.

Self-healing: the main loop polls `CGEventSourceFlagsState` every
0.5 s; fn physically up on two consecutive polls while LISTENING (the
tap missed the release — e.g. disabled mid-hold) finishes the turn
≤1 s late instead of recording to the cap and typing hallucinated
ambient. The debounce keeps one transient misread from cutting a live
dictation. Note: synthetic (posted) flag events don't register in
`CGEventSourceFlagsState`, so the reconciler can't be spoofed.

Re-entrancy rules:
- fn-down in LISTENING/TRANSCRIBING: no-op.
- fn-up in IDLE/TRANSCRIBING: no-op.
- Quick tap (fn-up delivered before the worker reaches capture): a
  finish-requested flag aborts that capture instantly; `begin_turn()`
  at fn-down keeps the flag from leaking into the next turn.
- The callback only acts on **edges** (`fn_on != _fn_was_on`), so
  flagsChanged events from other modifiers (shift, ctrl, option) don't
  trigger anything.

Tap self-healing: if macOS disables the tap (slow callback, or a user-
input flood), the callback receives `kCGEventTapDisabledByTimeout` /
`kCGEventTapDisabledByUserInput` and re-enables the tap in place.

### 2. mutter/stt.py

Mic → VAD → whisper-service IPC. Contains:
- `Listener` — `capture()` records frames until VAD, the 120 s cap or
  `finish()` stops it and returns int16 PCM; `transcribe()` hands PCM
  to `WhisperClient.transcribe()` and never raises (a failed clip is
  persisted to `~/.mutter/pending/*.wav` + user notification).
- Acoustic speech gate — a capture is transcribed only if the VAD
  confirmed speech OR ≥3 50 ms blocks cleared the RMS threshold.
  True silence never reaches whisper (whisper hallucinates on it).
- `collapse_repeats()` — bounds whisper's repeat-loop pathology
  ("Thank you." ×500) to one instance, any phrase, any period ≤8
  words. Replaces the old phrase blacklist, which ate deliberate
  short dictations ("okay", "thank you").
- `_VadState` — pure time-parameterised VAD. With `silence_duration`
  set very high (3600 s) the daemon never relies on auto-stop; `finish()`
  on fn-release is what ends the turn.
- `MIN_CAPTURED_SEC` — drops clips shorter than 0.3 s.

### 2b. mutter/whisper_client.py

Thin Python client for the whisper-service unix socket. Wraps the
length-prefixed-JSON wire protocol. `transcribe(pcm)` is the only
hot-path method mutter calls; `wait_for_service()` is called once at
daemon startup.

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
| Whisper transcribe (short clip) | ~100–300 ms | over unix socket to the warm whisper service |
| Unix-socket round-trip overhead | ~1 ms | length-prefixed JSON + raw PCM |
| pynput types 40-char sentence | ~20–80 ms | one CGEvent per char |
| **Total fn-up → text appears** | **~150–400 ms** | feels instant |

The whisper model is loaded by the separate whisper service, not by mutter.
Mutter's only boot cost is starting the CGEventTap (~1 s), then pinging
the service (~few ms if it's up; up to 180 s wait if it's still warming).

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
- **Short tap of fn** (<300 ms): `MIN_CAPTURED_SEC` drops clip; if the
  tap beats the worker to `capture()`, the finish-requested flag aborts
  cleanly (used to wedge the daemon for up to 120 s).
- **Silent hold** (fn held but no speech): zero speech evidence → the
  clip never reaches whisper at all.
- **Whisper service down mid-dictation**: clip saved to
  `~/.mutter/pending/`, notification shown — speech never lost.
- **Typing fails** (injection error): transcript lands on the
  clipboard via pbcopy + notification.
- **Hold >120 s**: capture loops in segments; text streams out while
  the hold continues.
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
2. **fn+F-key combos record a brief empty clip**. Harmless (no speech
   evidence → dropped before whisper) but the mic turns on for ~100 ms
   during, e.g., a brightness-up press.
3. **Release-to-text gap**. If you release fn and start typing on the
   keyboard immediately, typed chars and dictated chars interleave.
   Wait ~250 ms for the dictation to land before touching the
   keyboard again.

## File tree

```
MUTTER/mac/
├── SDD.md                       — this file
├── README.md                    — setup instructions for the user
├── mutter/
│   ├── __init__.py
│   ├── daemon.py                — CGEventTap + state machine + inject
│   ├── stt.py                   — mic + VAD + hallucination filter
│   └── whisper_client.py        — unix-socket client for the whisper service
├── tests/
│   └── test_daemon_refactor.py  — pidfile, state machine, fn transitions
├── com.peter.mutter.plist       — LaunchAgent for login autostart
├── requirements.txt
└── .venv/                       — Python 3.11 + deps (gitignored)
```

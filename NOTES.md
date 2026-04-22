# MUTTER build log

## 2026-04-21 — mute system audio while fn held

Two `os.system("osascript -e 'set volume output muted ...' &")` lines in `_on_fn_down` / `_on_fn_up` so the mic doesn't pick up music during dictation. Fire-and-forget; prior mute state not preserved; measured overhead +9 ms/turn.

## 2026-04-19 — initial build

**Decision: use pynput, not pbcopy+Cmd+V.**
- Peter wants his clipboard preserved (copy/paste workflow untouched).
- pynput wraps `CGEventKeyboardSetUnicodeString`. Clipboard never read
  or written.
- Cost: ~20–80 ms to type a 40-char sentence vs ~5 ms to paste. Still
  imperceptible.

**Decision: copy `stt.py` verbatim from CLAWS.**
- Zero CLAWS deps in that file; it's a standalone library already.
- MUTTER is a separate project and will not import from CLAWS. If
  CLAWS changes `stt.py`, we either hand-copy updates over or let
  MUTTER drift. That's fine — the file is stable.

**Decision: 3-state machine, not 6-state like CLAWS voice_cli.**
- CLAWS has idle / voice-listen / voice-busy / convo-listen /
  convo-busy / idle-busy. We have idle / listening / transcribing.
- Reason: dictation has exactly one mode. No TTS response, no Claude
  turn, no "convo vs voice" split. Three states cover everything.

**Decision: SIGUSR1/SIGUSR2 for start/stop, not a single toggle.**
- Karabiner gives us real down/up events (unlike Claude Code's
  terminal repeat-event hack). Use them.
- Single-toggle signal would require the launcher to track state —
  more fragile.

**Decision: silence_duration = 3600s during listen.**
- We never want VAD to end the turn. The user's fn-release is the
  signal. Huge silence_duration means "don't auto-stop."
- `finish()` is what ends the turn — Listener already supports this
  from when CLAWS added it for voice-gate mode.

**Decision: release tone inlined, `_Chime` class not imported.**
- Peter said "no Chime." 20 lines of inline sine-with-fade covers it.

**Decision: LaunchAgent `KeepAlive.SuccessfulExit = false`.**
- Restart on crash, don't restart on `SIGTERM` / clean exit. Lets Peter
  `launchctl unload` without the daemon resurrecting.

**Decision: pidfile, not pgrep.**
- pgrep by process name was CLAWS's approach. Brittle — matches any
  Python that happens to have "mutter.daemon" in argv.
- Pidfile at `/tmp/mutter.pid`. Checked via `kill -0 pid` to detect
  stale. Cleaner.

**Decision: fn+other-key combos not specially handled.**
- fn+arrow / fn+F-key will trigger a brief (start + ~100 ms +
  stop) cycle on the daemon. Captured audio is sub-MIN_CAPTURED_SEC
  or filtered as hallucination. Net: nothing typed. No harm.
- Could intercept with a `to_if_alone` clause in Karabiner but it
  complicates the rule. Ship simple, revisit if it misbehaves.

## 2026-04-19 — updated: tone removed, full install done

**Tone removed.** Misread user's reply earlier as "release tone: yes."
They clarified: no auditory component at all. `_prime_release_tone`,
`_play_release_tone`, and the numpy/sounddevice imports for tone are
gone. Daemon is silent.

**Full install performed in-session:**
- Karabiner-Elements installed via `brew install --cask karabiner-elements`.
- `karabiner.json` written with the fn→launcher rule embedded (Karabiner
  rewrote the file into its canonical form on first load; rule survived).
- macOS built-in Dictation disabled via
  `defaults write com.apple.HIToolbox AppleDictationAutoEnable -bool false`
  and `com.apple.assistant.support "Dictation Enabled" -bool false`.
- LaunchAgent plist copied to `~/Library/LaunchAgents/` and loaded.
- Daemon running under launchd, whisper pre-warmed (`ready in 12.7s`).
- Signal round-trip via launcher.sh verified (SIGUSR1→SIGUSR2 clean, no
  crash, short press correctly dropped by MIN_CAPTURED_SEC filter).

**Hard constraint: two permission toggles require a human click.**
TCC.db is SIP-protected; cannot be written outside Recovery Mode.
macOS requires the user to click once for each of:

1. **Accessibility** for `/opt/homebrew/Cellar/python@3.11/.../bin/python3.11`
   — pynput needs this to post CGEvents.
2. **Input Monitoring** for Karabiner-Elements — Karabiner needs this
   to observe key events.

pynput's `Controller.type()` silently drops events when Accessibility
is missing — it does not raise. Verified: a shift tap from python3.11
issued no exception but left TCC.db unchanged. So the first real fn-
press will trigger macOS's Accessibility prompt automatically; user
clicks Allow once, and it's done forever.

**Tested in-session (this run):**
- Sanitizer unit test (newline stripping, spacing collapse).
- Pidfile lock (accept-then-reject, stale-pid recovery).
- State machine with fake Listener + fake keyboard (all transitions,
  re-entrancy, empty/None transcript paths).
- Real daemon boots under launchctl, whisper loads from HF cache.
- Signal round-trip via launcher.sh (SIGUSR1/SIGUSR2/SIGTERM).
- Stray-signal safety (SIGUSR2 in IDLE is no-op, daemon survives).
- Short press silently dropped (clip under MIN_CAPTURED_SEC).

**Cannot test in-session (needs human at the Mac):**
- Actual pynput typing into a focused app (requires Accessibility
  grant, which Peter must click once).
- Karabiner intercepting fn-down/up (requires Input Monitoring grant,
  which Peter must click once).
- First-press of fn triggering both prompts in sequence.

## 2026-04-19 — refactor: CGEventTap replaces Karabiner

**Decision: replace Karabiner-Elements with an in-process CGEventTap.**
- Karabiner was the most invasive piece (brew cask, 4 background
  agents, DriverKit system extension, 4 permission grants total).
- pyobjc's `Quartz.CGEventTapCreate` gives us the same fn-down/fn-up
  visibility natively. fn is delivered as a `kCGEventFlagsChanged`
  event with `kCGEventFlagMaskSecondaryFn` (0x800000) in the flags.
  Edge-detect 0→1 = fn-down, 1→0 = fn-up.
- Net: -1 brew cask, -1 kernel extension, -3 permission grants, -1
  shell hop. Latency drops from ~30 ms to ~10 ms.

**Decision: tap runs on a dedicated CFRunLoop thread.**
- Main thread must stay free for `time.sleep(0.5)` polling and signal
  delivery (SIGTERM under `launchctl unload`). `CFRunLoopRun()` is
  blocking, so it gets its own thread. Shutdown calls
  `CFRunLoopStop(loop)` and joins the thread.

**Decision: callback is listen-only (`kCGEventTapOptionListenOnly`).**
- We never modify or drop events — fn still reaches the OS and other
  apps normally. Listen-only taps are cheaper and don't block the
  event stream if we ever miss a re-enable call.

**Decision: re-enable tap in-place on `kCGEventTapDisabledByTimeout` /
`kCGEventTapDisabledByUserInput`.**
- macOS will disable a tap whose callback is slow. Our callback is
  fast (state flip + worker spawn) but defensive anyway: the same
  callback receives the disable event and calls `CGEventTapEnable`
  to turn itself back on.

**Decision: tap creation is blocking on Accessibility grant.**
- `CGEventTapCreate` returns NULL if the process lacks Accessibility.
  We exit 1 with a clear stderr message; launchd's KeepAlive restarts
  every `ThrottleInterval` (10 s). Once the user clicks Allow once,
  the next restart picks it up and stays up.

**Removed:**
- `launcher.sh` (shell signal bridge — gone, tap fires directly).
- `karabiner/mutter.json` (rule — gone, tap replaces it).
- SIGUSR1 / SIGUSR2 handlers in daemon.py (no external signaller
  anymore). SIGTERM/SIGINT kept for clean shutdown.
- `CLAWS_WHISPER_BACKEND=mlx` env var from the plist — redundant,
  `DEFAULT_BACKEND` in `stt.py` is already `BACKEND_MLX`.
- `_HERE` / `_PKG_ROOT` sys.path dance in daemon.py — dead code,
  launch is always `python -m mutter.daemon`.
- `logger = logging.getLogger("mutter")` import that was never used.

**System-level cleanup performed:**
- `brew uninstall --cask karabiner-elements` (removed app, driver
  extension, and `karabiner_cli` binary).
- `launchctl bootout` on the three stale `org.pqrs.service.agent.*`
  user agents from the prior session.
- Trashed `~/.config/karabiner/`.
- Reloaded `com.peter.mutter.plist` with the new code.

**Tested in-session:**
- Unit tests: sanitizer, pidfile (acquire / reject-live / stale / release),
  state machine (fn-down → fn-up cycle, double-fn-down no-op, stray
  fn-up no-op, empty and whitespace transcripts don't type), fn-flag
  edge-detection logic. All pass.
- Integration: daemon loads whisper in ~10 s, tap creates cleanly.
  Posted synthetic `kCGEventFlagsChanged` events with and without
  the fn bit; with a temporary debug print, confirmed `fn=down` and
  `fn=up` log lines appeared in order. Removed the print, reloaded.
- Post-Karabiner-uninstall: synthetic events still reach the daemon,
  no errors, daemon still up.

**Still can't test in-session (needs human at the Mac):**
- Actual fn-key press from the physical keyboard feeding a real voice
  clip. Synthetic events prove the tap is wired; only Peter speaking
  into the mic proves the end-to-end voice path.
- On a completely fresh install with no existing Accessibility grant,
  the first-press TCC prompt flow.

"""MUTTER daemon — resident push-to-talk dictation.

One long-lived process. Loads whisper at boot (pays the ~10 s warmup
once), then watches the fn key via a CGEventTap.

    fn-down  → start recording
    fn-up    → finish + inject

Injection: pynput's ``Controller.type`` in normal apps; real Quartz
keycode events when the focused app is macOS Screen Sharing (see
``_type_via_quartz_keycodes`` for why pynput's typer mangles chars
across the screen-share keystroke channel).

System output is muted while fn is held so music or video on this
machine can't bleed into the mic.

State machine (3 states)::

    IDLE ── fn-down ──→ LISTENING ── fn-up ──→ TRANSCRIBING ──→ IDLE

Re-entrancy: a second fn-down while LISTENING is a no-op; an fn-up
in IDLE is a no-op. Stray half-events cannot corrupt state.
"""

from __future__ import annotations

import os
import signal
import sys
import threading
import time
from pathlib import Path
from typing import Optional

from mutter.stt import Listener, is_available
from mutter.whisper_client import WhisperClient, wait_for_service

try:
    from pynput.keyboard import Controller as KeyboardController
except ImportError:
    print(
        "mutter: pynput is required. Install with: pip install pynput",
        file=sys.stderr,
    )
    sys.exit(1)

try:
    import Quartz
except ImportError:
    print(
        "mutter: pyobjc-framework-quartz is required. "
        "Install with: pip install pyobjc-framework-quartz",
        file=sys.stderr,
    )
    sys.exit(1)


STATE_IDLE = "idle"
STATE_LISTENING = "listening"
STATE_TRANSCRIBING = "transcribing"

# Hard ceiling on one capture segment (120 s cap from capture() + 60 s
# grace). Overrun = wedged audio worker (PortAudio teardown deadlock);
# main loop os._exit(1)s for launchd to respawn. Re-armed per segment
# for marathon holds.
_TURN_DEADLINE_SEC = 180.0

# kVK_Function — the physical fn/🌐 key. flagsChanged events for
# arrow/Home/End/PgUp/PgDn/forward-delete/F-keys ALSO toggle the fn
# *flag* on Apple keyboards; only events carrying this keycode are a
# real fn press. Without this filter, holding an arrow key phantom-
# records ambient audio and whisper hallucinates text from it.
_KEYCODE_FN = 63


# ---------------------------------------------------------------------------
# Pidfile — single-instance lock.
# ---------------------------------------------------------------------------


def _pidfile_path() -> Path:
    return Path(os.environ.get("MUTTER_PIDFILE", "/tmp/mutter.pid"))


def _acquire_pidfile() -> bool:
    """Return True if this process is the only mutter daemon.

    Treats a pidfile whose PID is dead as stale and overwrites it.
    """
    p = _pidfile_path()
    if p.exists():
        try:
            old = int(p.read_text().strip())
            os.kill(old, 0)
            return False
        except (ValueError, ProcessLookupError, PermissionError):
            pass
    try:
        p.write_text(str(os.getpid()))
    except OSError as e:
        print(f"mutter: can't write pidfile {p}: {e}", file=sys.stderr)
        return False
    return True


def _release_pidfile() -> None:
    p = _pidfile_path()
    try:
        if p.exists() and p.read_text().strip() == str(os.getpid()):
            p.unlink()
    except OSError:
        pass


# ---------------------------------------------------------------------------
# Text sanitizer — strip characters that would misbehave when typed
# straight into a CLI prompt.
# ---------------------------------------------------------------------------


def _sanitize(text: str) -> str:
    """Remove newlines so a dictation doesn't accidentally submit a prompt."""
    if not text:
        return ""
    cleaned = text.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
    while "  " in cleaned:
        cleaned = cleaned.replace("  ", " ")
    return cleaned.strip()


def _set_system_muted(muted: bool) -> None:
    """Toggle macOS output mute via osascript, fire-and-forget.

    Used to silence music/video on this machine while fn is held so
    it can't bleed into the mic. Prior mute state isn't preserved —
    every fn-up turns audio back on regardless. ~9 ms per call.
    """
    state = "true" if muted else "false"
    os.system(f"osascript -e 'set volume output muted {state}' &")


# 8 ms inter-keystroke delay through Screen Sharing — measured
# floor for reliable delivery; 5 ms drops events under burst.
_TYPE_INTER_CHAR_DELAY = 0.008

# kVK_Shift. Posting an explicit shift-down before a shifted char
# (and shift-up after) is required: just setting the shift *flag*
# on the key event isn't enough — Screen Sharing's keystroke
# forwarder drops the modifier and '?' arrives as '/'.
_KEYCODE_SHIFT = 56


# ---------------------------------------------------------------------------
# Focused-app detection.
#
# AppKit's NSWorkspace.frontmostApplication / NSRunningApplication lookups
# only update when the main run loop pumps events in a common mode — and
# this daemon's main thread just sleeps. Their caches stay frozen at startup
# state, so we go straight to the Accessibility API + libproc, both of which
# are real-time and don't need a run loop.
# ---------------------------------------------------------------------------


def _focused_app_pid() -> Optional[int]:
    """PID of the currently focused app, or None."""
    try:
        from ApplicationServices import (
            AXUIElementCreateSystemWide,
            AXUIElementCopyAttributeValue,
            AXUIElementGetPid,
        )
    except ImportError:
        return None
    try:
        err, focused = AXUIElementCopyAttributeValue(
            AXUIElementCreateSystemWide(), "AXFocusedApplication", None
        )
        if err != 0 or focused is None:
            return None
        err, pid = AXUIElementGetPid(focused, None)
        return int(pid) if err == 0 else None
    except Exception:
        return None


def _process_executable_path(pid: int) -> Optional[str]:
    """Resolve a pid to its executable path via libproc."""
    try:
        import ctypes
        import ctypes.util
        libproc = ctypes.CDLL(ctypes.util.find_library("proc"))
        libproc.proc_pidpath.argtypes = [
            ctypes.c_int, ctypes.c_void_p, ctypes.c_uint32
        ]
        libproc.proc_pidpath.restype = ctypes.c_int
        buf = ctypes.create_string_buffer(4096)
        n = libproc.proc_pidpath(pid, buf, 4096)
        if n <= 0:
            return None
        return buf.value.decode("utf-8", errors="replace")
    except Exception:
        return None


def _frontmost_is_screen_sharing() -> bool:
    pid = _focused_app_pid()
    if pid is None:
        return False
    path = _process_executable_path(pid)
    return path is not None and "Screen Sharing.app" in path


# Common Whisper-produced non-ANSI chars normalized to ASCII so the
# layout's keycode map can type them. Anything outside this set and
# the keycode map is silently skipped (rare for English dictation).
_ASCII_FALLBACK = {
    "‘": "'", "’": "'",   # smart single quotes
    "“": '"', "”": '"',   # smart double quotes
    "–": "-", "—": "-",   # en-dash, em-dash
    "…": "...",                # ellipsis
}


def _normalize_for_typing(text: str) -> str:
    return "".join(_ASCII_FALLBACK.get(ch, ch) for ch in text)


_KEYCODE_MAP: Optional[dict] = None


def _ensure_keycode_map() -> dict:
    """Build (and cache) a ``char -> (keycode, modifier_flag)`` map
    for the current keyboard layout, by asking Apple's UCKeyTranslate
    what each (keycode, modifier) combination produces. Covers a-z,
    A-Z, 0-9, and the shifted/unshifted symbols on the layout."""
    global _KEYCODE_MAP
    if _KEYCODE_MAP is not None:
        return _KEYCODE_MAP
    from pynput._util.darwin import keycode_context, keycode_to_string
    # UCKeyTranslate's modifier_state is the high byte of the classic
    # EventRecord.modifiers word: shiftKey = 0x200, top byte = 2.
    UC_NO_MOD, UC_SHIFT = 0, 2
    m: dict = {}
    with keycode_context() as ctx:
        for kc in range(128):
            for ucstate, flag in (
                (UC_NO_MOD, 0),
                (UC_SHIFT, Quartz.kCGEventFlagMaskShift),
            ):
                ch = keycode_to_string(ctx, kc, ucstate)
                if not ch or len(ch) != 1:
                    continue
                # No-mod entries take priority (already inserted in outer loop).
                m.setdefault(ch, (kc, flag))
    _KEYCODE_MAP = m
    return m


def _type_via_quartz_keycodes(text: str) -> None:
    """Type each char as a real Quartz keycode event. Brackets runs
    of shifted chars with explicit shift-key down/up events (just
    setting the flag isn't enough through Screen Sharing — the
    remote drops the modifier). Reliable through Screen Sharing
    because keycode+modifier-key events are the same channel that
    already forwards Cmd+Tab, arrow keys, fn itself."""
    keymap = _ensure_keycode_map()
    src = Quartz.CGEventSourceCreate(Quartz.kCGEventSourceStateHIDSystemState)

    def post(kc: int, is_down: bool, with_shift: bool = False) -> None:
        ev = Quartz.CGEventCreateKeyboardEvent(src, kc, is_down)
        if with_shift:
            Quartz.CGEventSetFlags(ev, Quartz.kCGEventFlagMaskShift)
        Quartz.CGEventPost(Quartz.kCGHIDEventTap, ev)

    shift_held = False
    for ch in _normalize_for_typing(text):
        info = keymap.get(ch)
        if info is None:
            continue
        kc, flag = info
        needs_shift = bool(flag)

        if needs_shift and not shift_held:
            post(_KEYCODE_SHIFT, True)
            shift_held = True
            time.sleep(_TYPE_INTER_CHAR_DELAY)
        elif not needs_shift and shift_held:
            post(_KEYCODE_SHIFT, False)
            shift_held = False
            time.sleep(_TYPE_INTER_CHAR_DELAY)

        post(kc, True, with_shift=shift_held)
        post(kc, False, with_shift=shift_held)
        time.sleep(_TYPE_INTER_CHAR_DELAY)

    if shift_held:
        post(_KEYCODE_SHIFT, False)


# ---------------------------------------------------------------------------
# Daemon
# ---------------------------------------------------------------------------


class MutterDaemon:
    """CGEventTap-driven PTT dictation."""

    def __init__(self) -> None:
        self.listener: Optional[Listener] = None
        self.keyboard = KeyboardController()
        self.state = STATE_IDLE
        self._state_lock = threading.Lock()
        self.listen_thread: Optional[threading.Thread] = None
        self.should_exit = False
        self._turn_deadline: Optional[float] = None

        # Event-tap state. All touched on the tap's CFRunLoop thread.
        self._tap = None
        self._tap_source = None
        self._tap_loop = None
        self._tap_thread: Optional[threading.Thread] = None
        self._fn_was_on = False

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def start_listener(self) -> int:
        if not is_available():
            print(
                "mutter: audio deps missing (sounddevice, numpy)",
                file=sys.stderr,
            )
            return 1

        try:
            WhisperClient(timeout=2.0).ping()
        except Exception:
            print("mutter: waiting for whisper service (up to 180 s)...", flush=True)
            if not wait_for_service(timeout=180.0):
                print(
                    "mutter: whisper service unreachable. Is ~/.whisper-service "
                    "loaded? Try: launchctl list | grep whisper",
                    file=sys.stderr,
                )
                return 1

        self.listener = Listener(
            silence_duration=3600.0,  # never VAD-stop; finish() gates the turn
            max_duration=120.0,
        )
        print(f"mutter: ready  pid={os.getpid()}", flush=True)
        return 0

    def shutdown(self) -> None:
        if self.listener is not None:
            try:
                self.listener.stop()
            except Exception:
                pass
        _release_pidfile()

    # ------------------------------------------------------------------
    # Signals — only SIGTERM / SIGINT for clean exit under launchctl.
    # ------------------------------------------------------------------

    def install_signals(self) -> None:
        signal.signal(signal.SIGTERM, self._on_sigterm)
        signal.signal(signal.SIGINT, self._on_sigterm)

    def _on_sigterm(self, _signum, _frame) -> None:
        self.should_exit = True

    # ------------------------------------------------------------------
    # CGEventTap — watches fn key via flagsChanged events.
    # ------------------------------------------------------------------

    def _start_event_tap(self) -> bool:
        """Create the tap. Returns False if Accessibility isn't granted.

        CGEventTapCreate returns NULL when the process lacks the
        Accessibility TCC grant. On first run macOS shows the
        permission prompt; after the user clicks Allow, launchd
        restarts us (KeepAlive) and the retry succeeds.
        """
        mask = 1 << Quartz.kCGEventFlagsChanged
        self._tap = Quartz.CGEventTapCreate(
            Quartz.kCGSessionEventTap,
            Quartz.kCGHeadInsertEventTap,
            Quartz.kCGEventTapOptionListenOnly,
            mask,
            self._tap_callback,
            None,
        )
        if not self._tap:
            return False
        self._tap_source = Quartz.CFMachPortCreateRunLoopSource(
            None, self._tap, 0
        )
        self._tap_thread = threading.Thread(
            target=self._tap_run_loop, daemon=True, name="mutter-tap"
        )
        self._tap_thread.start()
        return True

    def _tap_run_loop(self) -> None:
        self._tap_loop = Quartz.CFRunLoopGetCurrent()
        Quartz.CFRunLoopAddSource(
            self._tap_loop, self._tap_source, Quartz.kCFRunLoopCommonModes
        )
        Quartz.CGEventTapEnable(self._tap, True)
        Quartz.CFRunLoopRun()

    def _stop_event_tap(self) -> None:
        if self._tap is not None:
            try:
                Quartz.CGEventTapEnable(self._tap, False)
            except Exception:
                pass
        if self._tap_loop is not None:
            try:
                Quartz.CFRunLoopStop(self._tap_loop)
            except Exception:
                pass
        if self._tap_thread is not None:
            self._tap_thread.join(timeout=2.0)

    def _tap_callback(self, _proxy, event_type, event, _refcon):
        """Runs on the tap's CFRunLoop thread. MUST return fast.

        Returning the event unmodified is a no-op for listen-only taps.
        Re-enables the tap if macOS disabled it (timeout or user-input
        flood).
        """
        if event_type in (
            Quartz.kCGEventTapDisabledByTimeout,
            Quartz.kCGEventTapDisabledByUserInput,
        ):
            try:
                Quartz.CGEventTapEnable(self._tap, True)
            except Exception:
                pass
            return event
        if event_type == Quartz.kCGEventFlagsChanged:
            try:
                keycode = Quartz.CGEventGetIntegerValueField(
                    event, Quartz.kCGKeyboardEventKeycode
                )
                flags = Quartz.CGEventGetFlags(event)
            except Exception:
                return event
            if keycode != _KEYCODE_FN:
                return event
            fn_on = bool(flags & Quartz.kCGEventFlagMaskSecondaryFn)
            if fn_on != self._fn_was_on:
                self._fn_was_on = fn_on
                if fn_on:
                    self._on_fn_down()
                else:
                    self._on_fn_up()
        return event

    # ------------------------------------------------------------------
    # State transitions — identical logic to the old SIGUSR1/2 path,
    # just triggered by the tap instead of signals.
    # ------------------------------------------------------------------

    def _on_fn_down(self) -> None:
        with self._state_lock:
            if self.state != STATE_IDLE:
                return
            self.state = STATE_LISTENING
        _set_system_muted(True)
        self._turn_deadline = time.monotonic() + _TURN_DEADLINE_SEC
        self.listen_thread = threading.Thread(
            target=self._listen_worker, daemon=True
        )
        self.listen_thread.start()

    def _on_fn_up(self) -> None:
        with self._state_lock:
            if self.state != STATE_LISTENING or self.listener is None:
                return
            self.state = STATE_TRANSCRIBING
        _set_system_muted(False)
        try:
            self.listener.finish()
        except Exception as e:
            print(f"mutter: finish error: {e}", file=sys.stderr)

    def _reconcile_fn_state(self) -> None:
        """Self-heal a missed fn-up. If macOS disables the tap mid-hold
        (timeout / user-input flood) the release event is lost; without
        this we'd record ambient audio until the 120 s cap and then type
        whisper's hallucination of it ("Thank you." x500). Poll the real
        hardware flag state; fn physically up while we think we're
        LISTENING → finish the turn now (≤0.5 s late)."""
        try:
            flags = Quartz.CGEventSourceFlagsState(
                Quartz.kCGEventSourceStateHIDSystemState
            )
        except Exception:
            return
        if flags & Quartz.kCGEventFlagMaskSecondaryFn:
            return
        self._fn_was_on = False  # heal edge desync even when IDLE
        if self.state == STATE_LISTENING:
            print("mutter: missed fn-up healed by reconciler", file=sys.stderr)
            self._on_fn_up()

    # ------------------------------------------------------------------
    # Worker — runs the listen/transcribe/inject pipeline so the tap
    # callback returns immediately.
    # ------------------------------------------------------------------

    def _listen_worker(self) -> None:
        assert self.listener is not None
        transcript: Optional[str] = None
        try:
            transcript = self.listener.listen(
                silence_duration=3600.0,
                max_duration=120.0,
            )
        except RuntimeError as e:
            print(f"mutter: mic error: {e}", file=sys.stderr)
        except Exception as e:
            print(f"mutter: listen error: {e}", file=sys.stderr)
        finally:
            if transcript:
                self._inject(transcript)
            with self._state_lock:
                self.state = STATE_IDLE
            self._turn_deadline = None

    def _inject(self, text: str) -> None:
        cleaned = _sanitize(text)
        if not cleaned:
            return
        # Leading space so dictation doesn't crash into prior text.
        payload = " " + cleaned
        try:
            if _frontmost_is_screen_sharing():
                _type_via_quartz_keycodes(payload)
            else:
                self.keyboard.type(payload)
        except Exception as e:
            print(f"mutter: inject error: {e}", file=sys.stderr)

    # ------------------------------------------------------------------
    # Main loop.
    # ------------------------------------------------------------------

    def run(self) -> int:
        if not _acquire_pidfile():
            print(
                f"mutter: another daemon is already running "
                f"(pidfile {_pidfile_path()})",
                file=sys.stderr,
            )
            return 1
        try:
            self.install_signals()
            # Listener must be ready BEFORE the tap goes live: an fn
            # press during the ~10 s whisper-load would otherwise hit
            # _listen_worker's "assert self.listener is not None",
            # kill the worker before its finally ran, and leave state
            # stuck in LISTENING forever — every later press a no-op.
            rc = self.start_listener()
            if rc != 0:
                return rc
            if not self._start_event_tap():
                print(
                    "mutter: CGEventTapCreate returned NULL — grant "
                    "Accessibility to this Python binary in System "
                    "Settings → Privacy & Security → Accessibility. "
                    "launchd will restart this daemon automatically.",
                    file=sys.stderr,
                )
                return 1
            while not self.should_exit:
                deadline = self._turn_deadline
                if deadline is not None and time.monotonic() > deadline:
                    # Wedged worker — clean shutdown would hang on the
                    # same mutex. Die hard; launchd respawns.
                    print(
                        "mutter: turn deadline exceeded; exiting for "
                        "launchd to respawn",
                        file=sys.stderr,
                        flush=True,
                    )
                    os._exit(1)
                self._reconcile_fn_state()
                time.sleep(0.5)
        finally:
            self._stop_event_tap()
            self.shutdown()
        return 0


def main() -> int:
    return MutterDaemon().run()


if __name__ == "__main__":
    sys.exit(main())

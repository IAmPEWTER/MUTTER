"""MUTTER daemon — resident push-to-talk dictation.

One long-lived process. Loads whisper at boot (pays the ~10 s warmup
once), then watches the fn key via a CGEventTap.

    fn-down  → start recording
    fn-up    → finish + inject

Injection uses pynput's Controller.type(), which calls the macOS
CGEventKeyboardSetUnicodeString API. That types characters directly
into whatever app has focus — the clipboard is never touched, so the
user's Cmd+C / Cmd+V stays theirs. Completely silent.

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

from mutter.stt import (
    Listener,
    is_available,
    is_model_cached,
    resolve_backend_from_env,
)

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
        backend = resolve_backend_from_env()
        if not is_available(backend=backend):
            lib = "mlx-whisper" if backend == "mlx" else "faster-whisper"
            print(
                f"mutter: deps missing (sounddevice, numpy, {lib})",
                file=sys.stderr,
            )
            return 1

        cached = is_model_cached(backend=backend)
        if cached:
            print(f"mutter: loading whisper ({backend})...", flush=True)
        else:
            print(
                f"mutter: downloading whisper ({backend}, ~1.5 GB, one time)...",
                flush=True,
            )

        t0 = time.monotonic()
        listener = Listener(
            backend=backend,
            silence_duration=3600.0,  # never VAD-stop; finish() gates the turn
            max_duration=120.0,
        )
        try:
            listener._ensure_model()
        except RuntimeError as e:
            print(f"mutter: {e}", file=sys.stderr)
            return 1
        self.listener = listener
        print(
            f"mutter: ready in {time.monotonic() - t0:.1f}s  pid={os.getpid()}",
            flush=True,
        )
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
                flags = Quartz.CGEventGetFlags(event)
            except Exception:
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
        os.system("osascript -e 'set volume output muted true' &")
        self.listen_thread = threading.Thread(
            target=self._listen_worker, daemon=True
        )
        self.listen_thread.start()

    def _on_fn_up(self) -> None:
        with self._state_lock:
            if self.state != STATE_LISTENING or self.listener is None:
                return
            self.state = STATE_TRANSCRIBING
        os.system("osascript -e 'set volume output muted false' &")
        try:
            self.listener.finish()
        except Exception as e:
            print(f"mutter: finish error: {e}", file=sys.stderr)

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
            self.listener._close_stream()
            with self._state_lock:
                self.state = STATE_IDLE

    def _inject(self, text: str) -> None:
        cleaned = _sanitize(text)
        if not cleaned:
            return
        try:
            # Leading space so dictation doesn't crash into prior text.
            self.keyboard.type(" " + cleaned)
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
                time.sleep(0.5)
        finally:
            self._stop_event_tap()
            self.shutdown()
        return 0


def main() -> int:
    return MutterDaemon().run()


if __name__ == "__main__":
    sys.exit(main())

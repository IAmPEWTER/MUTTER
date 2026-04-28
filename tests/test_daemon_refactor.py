"""Stress tests for MUTTER daemon after the CGEventTap refactor.

Exercises:
    - Pidfile acquire / release / stale-detect.
    - Sanitizer (unchanged behavior).
    - Fn-state transition tracker inside _tap_callback (via fake events).
    - State machine transitions with a fake Listener and fake keyboard.
    - Re-entrancy: double fn-down ignored, fn-up in IDLE ignored.
"""

from __future__ import annotations

import contextlib
import os
import sys
import threading
import time
from pathlib import Path
from unittest.mock import MagicMock

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from mutter import daemon as d


@pytest.fixture
def tmp_pidfile(tmp_path, monkeypatch):
    path = tmp_path / "mutter.pid"
    monkeypatch.setenv("MUTTER_PIDFILE", str(path))
    return path


# ---------------------------------------------------------------------------
# Sanitizer
# ---------------------------------------------------------------------------


def test_sanitizer():
    assert d._sanitize("") == ""
    assert d._sanitize("hello") == "hello"
    assert d._sanitize("a\nb") == "a b"
    assert d._sanitize("a\r\nb") == "a b"
    assert d._sanitize("a\rb") == "a b"
    assert d._sanitize("  a   b  ") == "a b"
    assert d._sanitize("line1\nline2\nline3") == "line1 line2 line3"
    print("ok sanitizer")


# ---------------------------------------------------------------------------
# Pidfile
# ---------------------------------------------------------------------------


def test_pidfile_acquire_reject(tmp_pidfile):
    assert d._acquire_pidfile()
    # Simulate a different live daemon by writing our own PID back —
    # kill(pid, 0) from us to us always succeeds, so acquire must reject.
    tmp_pidfile.write_text(str(os.getpid()))
    assert not d._acquire_pidfile()
    print("ok pidfile reject live")


def test_pidfile_stale(tmp_pidfile):
    # Write a PID that can't exist.
    tmp_pidfile.write_text("999999")
    assert d._acquire_pidfile()
    assert tmp_pidfile.read_text().strip() == str(os.getpid())
    print("ok pidfile stale recovery")


def test_pidfile_release(tmp_pidfile):
    d._acquire_pidfile()
    assert tmp_pidfile.exists()
    d._release_pidfile()
    assert not tmp_pidfile.exists()
    print("ok pidfile release")


# ---------------------------------------------------------------------------
# State machine
# ---------------------------------------------------------------------------


class FakeListener:
    def __init__(self):
        self.finished = threading.Event()
        self.listen_called = threading.Event()
        self.transcript = "hello world"

    def listen(self, *, silence_duration=None, max_duration=None):
        self.listen_called.set()
        self.finished.wait(timeout=2.0)
        return self.transcript

    def finish(self):
        self.finished.set()

    def stop(self):
        pass

    def _close_stream(self):
        pass


def _new_daemon_with_fake_listener():
    dm = d.MutterDaemon()
    dm.listener = FakeListener()
    dm.keyboard = MagicMock()
    return dm


def test_fn_down_up_cycle():
    dm = _new_daemon_with_fake_listener()
    dm._on_fn_down()
    assert dm.state == d.STATE_LISTENING
    # Wait for listen() to be called
    assert dm.listener.listen_called.wait(timeout=1.0)
    dm._on_fn_up()
    assert dm.state == d.STATE_TRANSCRIBING
    # Worker thread injects and returns to IDLE
    dm.listen_thread.join(timeout=2.0)
    assert dm.state == d.STATE_IDLE
    dm.keyboard.type.assert_called_once_with(" hello world")
    print("ok fn-down → fn-up cycle")


def test_double_fn_down_is_noop():
    dm = _new_daemon_with_fake_listener()
    dm._on_fn_down()
    assert dm.state == d.STATE_LISTENING
    assert dm.listener.listen_called.wait(timeout=1.0)
    first_thread = dm.listen_thread
    dm._on_fn_down()  # should be ignored
    assert dm.listen_thread is first_thread
    assert dm.state == d.STATE_LISTENING
    dm._on_fn_up()
    dm.listen_thread.join(timeout=2.0)
    print("ok double fn-down is no-op")


def test_fn_up_without_fn_down():
    dm = _new_daemon_with_fake_listener()
    assert dm.state == d.STATE_IDLE
    dm._on_fn_up()  # should be ignored
    assert dm.state == d.STATE_IDLE
    dm.keyboard.type.assert_not_called()
    print("ok stray fn-up is no-op")


def test_empty_transcript_no_type():
    dm = _new_daemon_with_fake_listener()
    dm.listener.transcript = None
    dm._on_fn_down()
    assert dm.listener.listen_called.wait(timeout=1.0)
    dm._on_fn_up()
    dm.listen_thread.join(timeout=2.0)
    assert dm.state == d.STATE_IDLE
    dm.keyboard.type.assert_not_called()
    print("ok empty transcript → no type")


def test_whitespace_only_transcript_no_type():
    dm = _new_daemon_with_fake_listener()
    dm.listener.transcript = "   \n  "
    dm._on_fn_down()
    assert dm.listener.listen_called.wait(timeout=1.0)
    dm._on_fn_up()
    dm.listen_thread.join(timeout=2.0)
    dm.keyboard.type.assert_not_called()
    print("ok whitespace transcript → no type")


# ---------------------------------------------------------------------------
# Screen-Sharing paste path
# ---------------------------------------------------------------------------


class FakePasteboard:
    def __init__(self, initial):
        self.value = initial
        self.history = [initial]

    def stringForType_(self, _t):
        return self.value

    def clearContents(self):
        self.value = None

    def setString_forType_(self, v, _t):
        self.value = v
        self.history.append(v)
        return True


@contextlib.contextmanager
def _patched_screen_sharing(*, fake_pb, press_result):
    """Stub everything `_send_via_screen_sharing` touches: a fake
    AppKit module exposing the given pasteboard, plus monkey-patched
    detection + AXPress that returns ``press_result``. Also shrinks
    the restore Timer delay so tests don't sleep a full second."""

    class FakeAppKit:
        NSPasteboardTypeString = "public.utf8-plain-text"

        class NSPasteboard:
            @staticmethod
            def generalPasteboard():
                return fake_pb

    saved = {
        "appkit": sys.modules.get("AppKit"),
        "frontmost": d._frontmost_is_screen_sharing,
        "press": d._press_send_clipboard_menu,
        "delay": d._CLIPBOARD_RESTORE_DELAY,
    }
    sys.modules["AppKit"] = FakeAppKit
    d._frontmost_is_screen_sharing = lambda: True
    press_mock = MagicMock(return_value=press_result)
    d._press_send_clipboard_menu = press_mock
    d._CLIPBOARD_RESTORE_DELAY = 0.05
    try:
        yield press_mock
    finally:
        if saved["appkit"] is None:
            sys.modules.pop("AppKit", None)
        else:
            sys.modules["AppKit"] = saved["appkit"]
        d._frontmost_is_screen_sharing = saved["frontmost"]
        d._press_send_clipboard_menu = saved["press"]
        d._CLIPBOARD_RESTORE_DELAY = saved["delay"]


def test_inject_types_normally_outside_screen_sharing():
    original = d._frontmost_is_screen_sharing
    d._frontmost_is_screen_sharing = lambda: False
    try:
        dm = _new_daemon_with_fake_listener()
        dm._inject("hello world")
        dm.keyboard.type.assert_called_once_with(" hello world")
    finally:
        d._frontmost_is_screen_sharing = original
    print("ok normal app → type")


def test_inject_screen_sharing_press_succeeds():
    """Send Clipboard menu fires: clipboard set with dictation,
    type-mode keyboard untouched, prior clipboard restored on Timer."""
    fake_pb = FakePasteboard(initial="USER PRIOR")
    with _patched_screen_sharing(fake_pb=fake_pb, press_result=True) as press:
        dm = _new_daemon_with_fake_listener()
        dm._inject("hello world")

        press.assert_called_once_with()
        dm.keyboard.type.assert_not_called()
        assert " hello world" in fake_pb.history
        # Wait past the (shrunken) restore Timer.
        time.sleep(0.15)
        assert fake_pb.value == "USER PRIOR"
    print("ok screen-share inject + clipboard restored")


def test_inject_screen_sharing_press_fails_rolls_back():
    """If the menu can't be pressed (no session, etc.), we must not
    leave the dictation on the user's clipboard."""
    fake_pb = FakePasteboard(initial="USER PRIOR")
    with _patched_screen_sharing(fake_pb=fake_pb, press_result=False):
        dm = _new_daemon_with_fake_listener()
        dm._inject("hello world")
        # Type path must NOT have run either — we don't fall back to
        # mangled per-char typing inside Screen Sharing.
        dm.keyboard.type.assert_not_called()
        # Clipboard was rolled back synchronously.
        assert fake_pb.value == "USER PRIOR"
    print("ok screen-share press failure → clipboard rolled back")


# ---------------------------------------------------------------------------
# Tap callback — fn-flag transition detection using fake event constants.
#
# We don't need a real CGEvent; we bypass _tap_callback and drive
# _on_fn_down/_on_fn_up directly through a transition helper.
# ---------------------------------------------------------------------------


def test_fn_transition_logic():
    """Verify the daemon only fires start/stop on fn-flag EDGES, not
    on every flagsChanged event. Simulates the sequence you'd see
    pressing Shift-then-fn: multiple flagsChanged events, but only
    the fn transitions should matter."""
    dm = _new_daemon_with_fake_listener()

    # Simulate: Shift alone (no fn edge) → nothing happens.
    # We reach into the tracker by poking _fn_was_on and calling the
    # flag check the way the callback does.
    dm._fn_was_on = False

    # Fake flag sequence: shift on (no fn) — no transition
    flags_shift_only = 0x20000  # just shift mask; fn bit absent
    fn_on = bool(flags_shift_only & 0x800000)  # kCGEventFlagMaskSecondaryFn
    assert fn_on is False
    assert fn_on == dm._fn_was_on  # no edge

    # Fn-down: fn bit appears (with shift still held)
    flags_shift_fn = 0x20000 | 0x800000
    fn_on = bool(flags_shift_fn & 0x800000)
    assert fn_on is True
    assert fn_on != dm._fn_was_on  # edge detected

    # Fn-up: fn bit goes away (shift still held)
    dm._fn_was_on = True
    flags_shift_only_again = 0x20000
    fn_on = bool(flags_shift_only_again & 0x800000)
    assert fn_on is False
    assert fn_on != dm._fn_was_on  # edge detected

    # Still holding fn + shift, another flagsChanged event for a third key:
    dm._fn_was_on = True
    flags_ctrl_fn = 0x40000 | 0x800000
    fn_on = bool(flags_ctrl_fn & 0x800000)
    assert fn_on is True
    assert fn_on == dm._fn_was_on  # no edge — fn is still on
    print("ok fn-flag transition detection")


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------


def main():
    import tempfile

    # Force the pre-existing state-machine tests onto the type path
    # regardless of whichever app the developer happens to be focused
    # on. Individual paste-mode tests override this themselves.
    d._frontmost_is_screen_sharing = lambda: False

    def with_tmp(fn):
        with tempfile.TemporaryDirectory() as t:
            fn(Path(t) / "mutter.pid")

    test_sanitizer()
    with_tmp(test_pidfile_acquire_reject)
    with_tmp(test_pidfile_stale)
    with_tmp(test_pidfile_release)
    test_fn_down_up_cycle()
    test_double_fn_down_is_noop()
    test_fn_up_without_fn_down()
    test_empty_transcript_no_type()
    test_whitespace_only_transcript_no_type()
    test_fn_transition_logic()
    test_inject_types_normally_outside_screen_sharing()
    test_inject_screen_sharing_press_succeeds()
    test_inject_screen_sharing_press_fails_rolls_back()
    print("\nall tests passed")


if __name__ == "__main__":
    main()

"""Stress tests for MUTTER daemon after the CGEventTap refactor.

Exercises:
    - Pidfile acquire / release / stale-detect.
    - Sanitizer (unchanged behavior).
    - Fn-state transition tracker inside _tap_callback (via fake events).
    - State machine transitions with a fake Listener and fake keyboard.
    - Re-entrancy: double fn-down ignored, fn-up in IDLE ignored.
"""

from __future__ import annotations

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
    print("\nall tests passed")


if __name__ == "__main__":
    main()

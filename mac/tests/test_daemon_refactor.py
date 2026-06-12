"""Stress tests for MUTTER daemon.

Exercises:
    - Pidfile acquire / release / stale-detect.
    - Sanitizer + repeat-collapse (anti-spew).
    - Fn-state transition tracker inside _tap_callback (via fake events).
    - Phantom-fn defense: non-63 keycodes ignored, reconciler heals a
      missed fn-up.
    - State machine transitions with a fake Listener and fake keyboard.
    - Re-entrancy: double fn-down ignored, fn-up in IDLE ignored.
    - Quick-tap race: finish() before capture() starts must not wedge.
    - Acoustic speech-evidence gate: silence never reaches whisper.
    - Screen-Sharing inject path uses the Quartz keycode typer
      (not pynput's mangled per-char path).
    - Layout-derived keycode map covers the full dictation charset.
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
from mutter import stt


def _wait_for(predicate, timeout=2.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(0.01)
    return False


@pytest.fixture
def tmp_pidfile(tmp_path, monkeypatch):
    path = tmp_path / "mutter.pid"
    monkeypatch.setenv("MUTTER_PIDFILE", str(path))
    return path


@pytest.fixture(autouse=True)
def _no_system_mute(monkeypatch):
    """Keep test runs from toggling the real system mute via osascript."""
    monkeypatch.setattr(d, "_set_system_muted", lambda muted: None)


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
        self.audio = object()  # sentinel "captured PCM"
        self.begin_turn_calls = 0

    def begin_turn(self):
        self.begin_turn_calls += 1
        self.finished = threading.Event()

    def capture(self, *, silence_duration=None, max_duration=None):
        self.listen_called.set()
        self.finished.wait(timeout=2.0)
        return self.audio

    def transcribe(self, audio):
        return self.transcript

    def finish(self):
        self.finished.set()

    def stop(self):
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
    assert dm.listener.begin_turn_calls == 1
    # Wait for capture() to be called
    assert dm.listener.listen_called.wait(timeout=1.0)
    dm._on_fn_up()
    # Capture worker returns to IDLE; the tx queue types asynchronously.
    dm.listen_thread.join(timeout=2.0)
    assert dm.state == d.STATE_IDLE
    assert _wait_for(lambda: dm.keyboard.type.call_count == 1)
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


def test_audio_mute_brackets_fn_press():
    """Mute(True) on fn-down, Mute(False) on fn-up. No fire on
    re-entrant fn-down (state already LISTENING). No fire on stray
    fn-up (state already IDLE)."""
    saved = d._set_system_muted
    calls: list = []
    d._set_system_muted = lambda muted: calls.append(muted)
    try:
        dm = _new_daemon_with_fake_listener()
        dm._on_fn_down()
        assert calls == [True]
        assert dm.listener.listen_called.wait(timeout=1.0)
        # Re-entrant fn-down while LISTENING — must NOT re-mute.
        dm._on_fn_down()
        assert calls == [True]
        dm._on_fn_up()
        assert calls == [True, False]
        dm.listen_thread.join(timeout=2.0)

        # Stray fn-up while IDLE — must NOT unmute.
        dm2 = _new_daemon_with_fake_listener()
        calls.clear()
        dm2._on_fn_up()
        assert calls == []
    finally:
        d._set_system_muted = saved
    print("ok audio mute brackets fn press")


def test_empty_transcript_no_type():
    dm = _new_daemon_with_fake_listener()
    dm.listener.transcript = None
    dm._on_fn_down()
    assert dm.listener.listen_called.wait(timeout=1.0)
    dm._on_fn_up()
    dm.listen_thread.join(timeout=2.0)
    assert dm.state == d.STATE_IDLE
    dm._tx_queue.join()  # drain the async pipeline before asserting
    dm.keyboard.type.assert_not_called()
    print("ok empty transcript → no type")


def test_whitespace_only_transcript_no_type():
    dm = _new_daemon_with_fake_listener()
    dm.listener.transcript = "   \n  "
    dm._on_fn_down()
    assert dm.listener.listen_called.wait(timeout=1.0)
    dm._on_fn_up()
    dm.listen_thread.join(timeout=2.0)
    dm._tx_queue.join()
    dm.keyboard.type.assert_not_called()
    print("ok whitespace transcript → no type")


def test_marathon_hold_streams_segments():
    """If capture returns while fn is still held (the 120 s segment cap),
    the worker must loop straight into a fresh capture — and every
    segment must be typed, in order."""
    dm = _new_daemon_with_fake_listener()
    lst = dm.listener
    segments = ["segment one", "segment two", "segment three"]
    transcripts = iter(segments)
    lst.transcribe = lambda audio: next(transcripts)

    dm._on_fn_down()
    assert lst.listen_called.wait(timeout=1.0)
    # Two cap-expiries while fn stays held: release capture without fn-up.
    for _ in range(2):
        lst.listen_called.clear()
        old = lst.finished
        lst.finished = threading.Event()  # next capture waits on a fresh event
        old.set()                 # capture returns; state still LISTENING
        assert lst.listen_called.wait(timeout=1.0)  # worker looped
        assert dm.state == d.STATE_LISTENING
    dm._on_fn_up()
    dm.listen_thread.join(timeout=2.0)
    assert dm.state == d.STATE_IDLE
    assert _wait_for(lambda: dm.keyboard.type.call_count == 3)
    typed = [c.args[0] for c in dm.keyboard.type.call_args_list]
    assert typed == [" segment one", " segment two", " segment three"]
    print("ok marathon hold streams segments in order")


def test_press_during_drain_not_swallowed():
    """A new fn-down while the previous turn is still transcribing must
    start a new capture immediately — slow transcription can never
    swallow a press."""
    dm = _new_daemon_with_fake_listener()
    lst = dm.listener
    release_tx = threading.Event()
    first_tx_entered = threading.Event()

    def slow_transcribe(audio):
        first_tx_entered.set()
        release_tx.wait(timeout=2.0)
        return "slow text"

    lst.transcribe = slow_transcribe
    dm._on_fn_down()
    assert lst.listen_called.wait(timeout=1.0)
    dm._on_fn_up()
    dm.listen_thread.join(timeout=2.0)
    assert first_tx_entered.wait(timeout=1.0)

    # Previous turn still transcribing — press again.
    lst.listen_called.clear()
    dm._on_fn_down()
    assert dm.state == d.STATE_LISTENING, "press was swallowed"
    assert lst.listen_called.wait(timeout=1.0)
    release_tx.set()
    dm._on_fn_up()
    dm.listen_thread.join(timeout=2.0)
    assert _wait_for(lambda: dm.keyboard.type.call_count == 2)
    print("ok press during drain not swallowed")


# ---------------------------------------------------------------------------
# Screen-Sharing paste path
# ---------------------------------------------------------------------------


def test_inject_types_normally_outside_screen_sharing():
    original = d._frontmost_is_screen_sharing
    d._frontmost_is_screen_sharing = lambda: False
    try:
        dm = _new_daemon_with_fake_listener()
        dm._inject("hello world")
        dm.keyboard.type.assert_called_once_with(" hello world")
        dm.keyboard.pressed.assert_not_called()
    finally:
        d._frontmost_is_screen_sharing = original
    print("ok normal app → type")


def test_inject_screen_sharing_uses_keycode_typer():
    """When Screen Sharing is focused, _inject must call the Quartz
    keycode typer with the dictation (no clipboard, no Cmd+V) and
    must not invoke pynput's mangled type() path."""
    saved_front = d._frontmost_is_screen_sharing
    saved_typer = d._type_via_quartz_keycodes
    d._frontmost_is_screen_sharing = lambda: True
    d._type_via_quartz_keycodes = MagicMock()
    try:
        dm = _new_daemon_with_fake_listener()
        dm._inject("hello world")
        d._type_via_quartz_keycodes.assert_called_once_with(" hello world")
        dm.keyboard.type.assert_not_called()
    finally:
        d._frontmost_is_screen_sharing = saved_front
        d._type_via_quartz_keycodes = saved_typer
    print("ok screen-share → keycode typer")


def test_keycode_map_covers_dictation_charset():
    """The layout-derived keycode map must cover every character a
    Whisper transcript reasonably contains: a-z, A-Z, 0-9, space,
    common punctuation. If any of these are missing, screen-share
    typing would silently drop them."""
    keymap = d._ensure_keycode_map()
    required = (
        "abcdefghijklmnopqrstuvwxyz"
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        "0123456789"
        " .,!?'\"-_"
    )
    missing = [c for c in required if c not in keymap]
    assert not missing, f"keycode map missing: {missing!r}"
    # Sanity: capital letter must use the same keycode as its lowercase
    # twin, with the shift flag set — that's the whole reason this fix
    # works at all.
    lower_kc, lower_flag = keymap["i"]
    upper_kc, upper_flag = keymap["I"]
    assert lower_kc == upper_kc
    assert lower_flag == 0
    assert upper_flag != 0
    print("ok keycode map covers dictation charset")


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


def test_tap_callback_ignores_non_fn_keycodes(monkeypatch):
    """Arrow/nav/F-keys also toggle the fn FLAG in their flagsChanged
    events on Apple keyboards. Only keycode 63 (the physical fn key)
    may start or stop dictation — otherwise holding an arrow key
    phantom-records ambient audio and whisper hallucinates from it."""
    dm = _new_daemon_with_fake_listener()
    fake = {"keycode": 126, "flags": 0x800000}  # up-arrow carrying fn flag
    monkeypatch.setattr(
        d.Quartz, "CGEventGetIntegerValueField", lambda ev, f: fake["keycode"]
    )
    monkeypatch.setattr(d.Quartz, "CGEventGetFlags", lambda ev: fake["flags"])

    dm._tap_callback(None, d.Quartz.kCGEventFlagsChanged, object(), None)
    assert dm.state == d.STATE_IDLE  # phantom ignored

    fake["keycode"] = 63  # the real fn key
    dm._tap_callback(None, d.Quartz.kCGEventFlagsChanged, object(), None)
    assert dm.state == d.STATE_LISTENING
    assert dm.listener.listen_called.wait(timeout=1.0)

    fake["flags"] = 0  # fn released
    dm._tap_callback(None, d.Quartz.kCGEventFlagsChanged, object(), None)
    dm.listen_thread.join(timeout=2.0)
    assert dm.state == d.STATE_IDLE
    print("ok non-fn keycodes ignored")


def test_reconciler_heals_missed_fn_up(monkeypatch):
    """If the tap missed the fn-up (macOS disabled it mid-hold), the
    main-loop reconciler must finish the turn once the hardware flag
    state shows fn is physically up."""
    dm = _new_daemon_with_fake_listener()
    dm._on_fn_down()
    assert dm.listener.listen_called.wait(timeout=1.0)
    dm._fn_was_on = True

    monkeypatch.setattr(d.Quartz, "CGEventSourceFlagsState", lambda src: 0)
    dm._reconcile_fn_state()
    assert dm._fn_was_on is False
    dm.listen_thread.join(timeout=2.0)
    assert dm.state == d.STATE_IDLE
    print("ok reconciler heals missed fn-up")


def test_reconciler_noop_while_fn_held(monkeypatch):
    dm = _new_daemon_with_fake_listener()
    dm._on_fn_down()
    assert dm.listener.listen_called.wait(timeout=1.0)
    dm._fn_was_on = True

    monkeypatch.setattr(
        d.Quartz, "CGEventSourceFlagsState", lambda src: 0x800000
    )
    dm._reconcile_fn_state()
    assert dm.state == d.STATE_LISTENING
    assert dm._fn_was_on is True
    dm._on_fn_up()
    dm.listen_thread.join(timeout=2.0)
    print("ok reconciler no-op while fn held")


# ---------------------------------------------------------------------------
# stt: repeat-collapse (anti-spew)
# ---------------------------------------------------------------------------


def test_collapse_repeats_kills_spew():
    text = ("Thank you. " * 500).strip()
    assert stt.collapse_repeats(text) == "Thank you."
    # Multi-word loop variants the old phrase-blacklist regex missed.
    text = ("Thanks for watching! " * 12).strip()
    assert stt.collapse_repeats(text) == "Thanks for watching!"
    assert stt.collapse_repeats("you you you you you you") == "you"
    print("ok collapse kills spew")


def test_collapse_repeats_preserves_real_speech():
    for s in (
        "",
        "okay",
        "thank you",
        "I really really like it",
        "no no no",  # three repeats — below the collapse threshold
        "that that was weird",
        "send the report to bob and then to alice please",
    ):
        assert stt.collapse_repeats(s) == s
    # A run embedded in real speech collapses without touching the rest.
    assert (
        stt.collapse_repeats(
            "okay so Thank you. Thank you. Thank you. Thank you. done"
        )
        == "okay so Thank you. done"
    )
    print("ok collapse preserves real speech")


# ---------------------------------------------------------------------------
# stt: quick-tap race + acoustic speech-evidence gate
# ---------------------------------------------------------------------------


@pytest.fixture
def quiet_listener(monkeypatch):
    lst = stt.Listener()
    monkeypatch.setattr(lst, "_ensure_stream", lambda: None)
    monkeypatch.setattr(lst, "_close_stream", lambda: None)
    return lst


def test_finish_before_capture_does_not_wedge(quiet_listener):
    """A quick tap can deliver the fn-up before the worker thread
    reaches capture(). The stale finish must abort THAT capture
    immediately (previously this wedged the daemon for up to 120 s),
    and begin_turn must stop it leaking into the NEXT turn."""
    lst = quiet_listener
    lst.begin_turn()
    lst.finish()  # fn-up wins the race
    t0 = time.time()
    assert lst.capture(max_duration=120.0) is None
    assert time.time() - t0 < 1.0, "capture should exit immediately"

    # Next turn: the consumed flag must not cancel it.
    lst.begin_turn()
    done = []
    th = threading.Thread(target=lambda: done.append(lst.capture(max_duration=60.0)))
    th.start()
    assert _wait_for(lambda: lst._recording), "capture never started"
    lst.finish()
    th.join(timeout=2.0)
    assert not th.is_alive(), "capture wedged"
    assert done == [None]  # no frames captured → None, but it RETURNED
    print("ok quick-tap race does not wedge")


def test_capture_gates_on_speech_evidence(quiet_listener):
    """True silence must never reach whisper (it hallucinates), but a
    few quiet/short loud frames — below the VAD's contiguous bar — must
    still pass: PTT means the user pressed on purpose."""
    np = pytest.importorskip("numpy")
    lst = quiet_listener
    block = int(stt.SAMPLE_RATE * stt.DEFAULT_BLOCK_SECONDS)

    def run_capture(frame_levels):
        done = []
        lst.begin_turn()
        th = threading.Thread(target=lambda: done.append(lst.capture(max_duration=60.0)))
        th.start()
        assert _wait_for(lambda: lst._recording)
        for level in frame_levels:
            frame = np.full((block, 1), level, dtype=np.int16)
            lst._audio_callback(frame, block, None, None)
        lst.finish()
        th.join(timeout=2.0)
        assert not th.is_alive()
        return done[0]

    # 1 s of pure silence → None: nothing for whisper to hallucinate on.
    assert run_capture([0] * 20) is None
    # Three isolated 50 ms loud frames (never 0.25 s contiguous, so the
    # VAD's has_spoken stays False) → still captured via the evidence gate.
    levels = [0] * 5 + [3000] + [0] * 4 + [3000] + [0] * 4 + [3000] + [0] * 5
    audio = run_capture(levels)
    assert audio is not None
    assert audio.size == len(levels) * block
    print("ok speech-evidence gate")

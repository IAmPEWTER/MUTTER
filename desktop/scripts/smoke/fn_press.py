#!/usr/bin/env python3
"""Synthesize an fn (globe) key hold/release the way handy-keys reads it:
a FlagsChanged event, keycode 63, MaskSecondaryFn set = down, cleared = up.
Posted to the session tap (CGEventPost) so handy-keys' SessionEventTap sees it.

Needs a Python with pyobjc (Quartz). On this machine /opt/homebrew/bin/python3
has it; /usr/bin/python3 does not. The posting process must be trusted for
Accessibility for the event to reach the tap.

Usage: fn_press.py down | up
"""
import sys, Quartz

KC_FN = 63
FN_MASK = Quartz.kCGEventFlagMaskSecondaryFn  # 0x800000


def flags_changed(down: bool):
    e = Quartz.CGEventCreateKeyboardEvent(None, KC_FN, True)
    Quartz.CGEventSetType(e, Quartz.kCGEventFlagsChanged)
    Quartz.CGEventSetIntegerValueField(e, Quartz.kCGKeyboardEventKeycode, KC_FN)
    Quartz.CGEventSetFlags(e, FN_MASK if down else 0)
    Quartz.CGEventPost(Quartz.kCGSessionEventTap, e)


action = sys.argv[1] if len(sys.argv) > 1 else ""
if action == "down":
    flags_changed(True)
elif action == "up":
    flags_changed(False)
else:
    sys.exit("usage: fn_press.py down|up")
print(f"fn {action}")

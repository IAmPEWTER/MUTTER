#!/usr/bin/env bash
# Tier C — live fn-trigger smoke test. Proves R1 (hold fn/globe = record) end to
# end at the app level, WITHOUT audio injection.
#
# Why no audio in the loop: this machine's BlackHole virtual device has no clock
# master, so acoustic capture into the running app can't be driven
# deterministically. The Tier A Rust integration test
# (src-tauri/tests/real_audio_pipeline.rs) covers audio->text deterministically
# instead. This script proves ONLY the fn tap + trigger path, which is the other
# historical root failure (missed fn-up / naive event tap; spec §6): a synthetic
# fn keypress must make the app START recording on fn-down and SEE the fn-up.
#
# It posts a synthetic fn event exactly the way handy-keys reads it (FlagsChanged,
# keycode 63, MaskSecondaryFn set/cleared — see fn_press.py) to the session event
# tap, then asserts the app's log gained BOTH:
#   - "TranscribeAction::start"  (fn-down started recording)
#   - "state=Released"           (fn-up was seen by the tap)
# These are DEBUG lines; the app logs at Debug to file by default, so no --debug
# flag is required.
#
# Does NOT depend on BlackHole or any acoustic capture. Exit 0 on success;
# non-zero with a clear message otherwise.
set -euo pipefail

LOG="$HOME/Library/Logs/com.peter.mutter.app/handy.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FN_PRESS="$SCRIPT_DIR/fn_press.py"

fail() { echo "FAIL: $*" >&2; exit 1; }

# ── pick a python with pyobjc/Quartz ───────────────────────────────────────
PY=""
for cand in /opt/homebrew/bin/python3 python3 /usr/bin/python3; do
  if command -v "$cand" >/dev/null 2>&1 && "$cand" -c "import Quartz" >/dev/null 2>&1; then
    PY="$cand"; break
  fi
done
[ -n "$PY" ] || fail "no python3 with pyobjc/Quartz found (needed to post the synthetic fn event)"

# ── locate the app (installed preferred; fall back to a built bundle) ───────
APP=""
for cand in \
  "/Applications/MUTTER.app" \
  "$SCRIPT_DIR/../../src-tauri/target/release/bundle/macos/MUTTER.app" \
  "$SCRIPT_DIR/../../src-tauri/target/debug/bundle/macos/MUTTER.app"; do
  [ -d "$cand" ] && { APP="$cand"; break; }
done
[ -n "$APP" ] || fail "MUTTER.app not found (looked in /Applications and target/*/bundle/macos)"

# ── ensure the app is running (background launch, no focus steal) ───────────
is_running() { pgrep -f "MUTTER.app/Contents/MacOS" >/dev/null 2>&1; }
if ! is_running; then
  echo "MUTTER not running; launching $APP"
  open -ga "$APP"
  for _ in $(seq 1 20); do is_running && break; sleep 0.5; done
  # Give the handy-keys CGEventTap time to register after process start.
  sleep 4
fi
is_running || fail "MUTTER did not start"
[ -f "$LOG" ] || fail "log file not found at $LOG"

# ── record the current end of the log (byte offset) ────────────────────────
START=$(wc -c < "$LOG" | tr -d ' ')
echo "log baseline at byte $START; posting synthetic fn press (down, hold ~1s, up)..."

# ── post fn-down, hold ~1s, fn-up ──────────────────────────────────────────
"$PY" "$FN_PRESS" down
sleep 1
"$PY" "$FN_PRESS" up

# ── wait for the app to flush the expected log lines ───────────────────────
NEW=""
for _ in $(seq 1 20); do
  NEW="$(tail -c +"$((START + 1))" "$LOG")"
  if echo "$NEW" | grep -q "TranscribeAction::start" && echo "$NEW" | grep -q "state=Released"; then
    break
  fi
  sleep 0.5
done

echo "---- new log lines ----"
echo "$NEW" | grep -E "handy-keys event|TranscribeAction" || echo "(no matching lines)"
echo "-----------------------"

echo "$NEW" | grep -q "TranscribeAction::start" \
  || fail "app did not log TranscribeAction::start after synthetic fn-down (recording never started)"
echo "$NEW" | grep -q "state=Released" \
  || fail "app did not log state=Released after synthetic fn-up (fn-up not seen by the tap)"

echo "PASS: fn-down started recording (TranscribeAction::start) and fn-up was seen (state=Released)"
exit 0

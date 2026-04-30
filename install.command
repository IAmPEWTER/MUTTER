#!/bin/bash
# MUTTER installer. Double-click from Finder to run.
#
# What this does (idempotent — safe to re-run):
#   1. Creates .venv inside this folder using the best python3.11 we find.
#   2. pip-installs requirements.txt into that venv.
#   3. Writes a LaunchAgent plist to ~/Library/LaunchAgents/ pointing at
#      this folder's venv.
#   4. Loads the agent. The daemon starts immediately and at every login.
#
# Stops with a clear error if:
#   - python3.11 isn't installed.
#   - This folder isn't writable.
#   - pip install fails.

set -euo pipefail

PKG_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PKG_DIR"

echo ""
echo "== MUTTER installer =="
echo "   folder: $PKG_DIR"
echo ""

# --- Python ---
if ! command -v python3.11 >/dev/null 2>&1; then
    echo "ERROR: python3.11 not found on PATH."
    echo ""
    echo "Install it with Homebrew:"
    echo "   brew install python@3.11"
    echo ""
    read -rp "Press return to close..."
    exit 1
fi
PY="$(command -v python3.11)"
echo "Using Python: $PY"

# --- Venv ---
if [ ! -x "$PKG_DIR/.venv/bin/python" ]; then
    echo "Creating venv at $PKG_DIR/.venv ..."
    "$PY" -m venv "$PKG_DIR/.venv"
else
    echo "Venv already exists."
fi

# --- Deps ---
echo "Installing Python dependencies (this downloads mlx + pynput; ~2 min first time)..."
"$PKG_DIR/.venv/bin/pip" install --upgrade pip --quiet
"$PKG_DIR/.venv/bin/pip" install -r "$PKG_DIR/requirements.txt" --quiet

# --- LaunchAgent plist ---
PLIST_DEST="$HOME/Library/LaunchAgents/com.peter.mutter.plist"
mkdir -p "$(dirname "$PLIST_DEST")"

# Unload the old one if present — avoids "already loaded" warnings.
if launchctl list | grep -q "com.peter.mutter"; then
    echo "Unloading existing com.peter.mutter agent ..."
    launchctl unload "$PLIST_DEST" 2>/dev/null || true
fi

echo "Writing LaunchAgent: $PLIST_DEST"
sed "s|__PKG_DIR__|$PKG_DIR|g" \
    "$PKG_DIR/com.peter.mutter.plist.template" > "$PLIST_DEST"

echo "Loading LaunchAgent ..."
launchctl load "$PLIST_DEST"

echo ""
echo "== Install complete =="
echo ""
echo "Daemon is starting. First launch downloads the Whisper model"
echo "(~1.5 GB FP16 by default) and compiles it — takes ~60 s one time."
echo ""
echo "To use the smaller q4 build (~440 MB) on a tighter machine, edit"
echo "$PLIST_DEST and add an EnvironmentVariables block setting"
echo "MUTTER_WHISPER_MODEL=large-v3-turbo-q4 — see README.md for details."
echo ""
echo "Watch the log for 'mutter: ready':"
echo "   tail -f /tmp/mutter.out.log"
echo ""
echo "You need to grant two permissions at first use:"
echo "  1. Microphone — prompt appears on first fn-hold."
echo "  2. Accessibility — prompt appears on first daemon start."
echo "     If missed, open System Settings → Privacy & Security →"
echo "     Accessibility and enable the python binary at:"
echo "     $PKG_DIR/.venv/bin/python"
echo ""
echo "Test it: open Notes, hold fn, speak, release. Text appears."
echo ""
read -rp "Press return to close..."

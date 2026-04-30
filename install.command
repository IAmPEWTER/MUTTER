#!/bin/bash
# MUTTER installer. Double-click from Finder or run with: bash install.command
#
# Checks system requirements, sets up Python 3.11 (no admin password),
# installs deps, registers the LaunchAgent, and walks you through the
# permission clicks at the end.

set -euo pipefail

PKG_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PKG_DIR"
xattr -dr com.apple.quarantine "$PKG_DIR" 2>/dev/null || true

bail() { echo ""; echo "ERROR: $*"; echo ""; read -rp "Press return to close..."; exit 1; }

echo ""
echo "== MUTTER installer =="
echo ""

# ---------------------------------------------------------------------------
# System requirements
# ---------------------------------------------------------------------------
MAC_VER="$(sw_vers -productVersion)"
MAC_MAJOR="${MAC_VER%%.*}"
ARCH="$(uname -m)"

if [ "$ARCH" != "arm64" ]; then
    bail "MUTTER needs an Apple Silicon Mac (M1 or newer). This Mac is $ARCH."
fi
if [ "$MAC_MAJOR" -lt 15 ]; then
    bail "MUTTER needs macOS 15 (Sequoia) or newer. This Mac is macOS $MAC_VER."
fi
echo "macOS $MAC_VER on $ARCH — supported."

# ---------------------------------------------------------------------------
# Python 3.11
# ---------------------------------------------------------------------------
if command -v python3.11 >/dev/null 2>&1; then
    PY="$(command -v python3.11)"
else
    echo "Installing Python 3.11 (no admin password needed)..."
    if ! command -v uv >/dev/null 2>&1; then
        curl -LsSf https://astral.sh/uv/install.sh | sh
    fi
    export PATH="$HOME/.local/bin:$PATH"
    uv python install 3.11
    PY="$(uv python find 3.11)"
fi

# ---------------------------------------------------------------------------
# Virtualenv + dependencies
# ---------------------------------------------------------------------------
if [ ! -x "$PKG_DIR/.venv/bin/python" ]; then
    "$PY" -m venv "$PKG_DIR/.venv"
fi
echo "Installing dependencies (~2 min the first time)..."
"$PKG_DIR/.venv/bin/pip" install --upgrade pip --quiet
"$PKG_DIR/.venv/bin/pip" install -r "$PKG_DIR/requirements.txt" --quiet

# ---------------------------------------------------------------------------
# LaunchAgent (preserve any custom EnvironmentVariables on re-install)
# ---------------------------------------------------------------------------
PLIST_DEST="$HOME/Library/LaunchAgents/com.peter.mutter.plist"
mkdir -p "$(dirname "$PLIST_DEST")"

if [ -f "$PLIST_DEST" ] && grep -q "$PKG_DIR" "$PLIST_DEST"; then
    launchctl unload "$PLIST_DEST" 2>/dev/null || true
else
    [ -f "$PLIST_DEST" ] && launchctl unload "$PLIST_DEST" 2>/dev/null || true
    sed "s|__PKG_DIR__|$PKG_DIR|g" \
        "$PKG_DIR/com.peter.mutter.plist.template" > "$PLIST_DEST"
fi
launchctl load "$PLIST_DEST"
echo "Daemon installed and started."

# ---------------------------------------------------------------------------
# Final user steps — only what's actually needed
# ---------------------------------------------------------------------------
DICT_AUTO="$(defaults read com.apple.HIToolbox AppleDictationAutoEnable 2>/dev/null || echo 0)"
DICT_SUPP="$(defaults read com.apple.assistant.support "Dictation Enabled" 2>/dev/null || echo 0)"
DICT_ON=0
[ "$DICT_AUTO" = "1" ] && DICT_ON=1
[ "$DICT_SUPP" = "1" ] && DICT_ON=1

echo ""
echo "===================================="
echo "  Almost done — last permission step"
echo "===================================="
echo ""

STEP=1
if [ "$DICT_ON" = "1" ]; then
    cat <<EOF
  $STEP. Turn OFF macOS Dictation (otherwise fn fires Apple's at the same time):
       System Settings  →  Keyboard  →  Dictation  →  toggle OFF
EOF
    STEP=$((STEP + 1))
    echo ""
fi

cat <<EOF
  $STEP. Grant Accessibility (so MUTTER can read your fn key).
     The pane will OPEN AUTOMATICALLY in 5 seconds.
     Find "python" in the list and toggle it ON. If it isn't listed,
     click "+" and pick:
       $PKG_DIR/.venv/bin/python

After that, just hold fn anywhere, speak, release. Text appears.
The Microphone prompt (one-time "Allow") shows on your first fn-hold.

Whisper model (~1.5 GB) auto-downloads on first daemon launch.
Watch:    tail -f /tmp/mutter.out.log
You'll see "mutter: ready" when it's done.
EOF

sleep 5
open "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility" 2>/dev/null || true

echo ""
read -rp "Press return when finished to close this window..."

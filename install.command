#!/bin/bash
# MUTTER installer. Double-click from Finder or run with: bash install.command
#
# Verifies the Mac, installs Python 3.11 (no admin password), sets up the
# venv + deps, registers the LaunchAgent, then walks you through the
# permission and Settings clicks at the end. Idempotent — safe to re-run.

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
    bail "MUTTER needs a Mac with an Apple chip (M1 or newer — any Mac from late 2020 onwards). This Mac has an Intel chip ($ARCH)."
fi
if [ "$MAC_MAJOR" -lt 15 ]; then
    bail "MUTTER needs macOS 15 (Sequoia) or newer. This Mac is on macOS $MAC_VER. Update it from System Settings → General → Software Update."
fi
echo "macOS $MAC_VER on Apple Silicon — supported."

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
# Detect Settings that need changing
# ---------------------------------------------------------------------------
# fn / 🌐 key behavior. 0 = Do Nothing (what we want).
# 1 = Change Input Source. 2 = Show Emoji & Symbols. 3 = Start Dictation.
FN_USAGE="$(defaults read com.apple.HIToolbox AppleFnUsageType 2>/dev/null || echo 0)"

# Old-style Dictation toggle (one of two flags depending on macOS version).
DICT_AUTO="$(defaults read com.apple.HIToolbox AppleDictationAutoEnable 2>/dev/null || echo 0)"
DICT_SUPP="$(defaults read com.apple.assistant.support "Dictation Enabled" 2>/dev/null || echo 0)"
DICT_ON=0
[ "$DICT_AUTO" = "1" ] && DICT_ON=1
[ "$DICT_SUPP" = "1" ] && DICT_ON=1

NEEDS_KEYBOARD=0
[ "$FN_USAGE" != "0" ] && NEEDS_KEYBOARD=1
[ "$DICT_ON" = "1" ] && NEEDS_KEYBOARD=1

# ---------------------------------------------------------------------------
# Walk the user through what's left
# ---------------------------------------------------------------------------
cat <<'EOF'

==================================================
  Almost done — a couple of one-time clicks left
==================================================

About the "fn" key: on newer Macs it has a globe icon 🌐 printed on it
instead of the letters "fn". Same key — bottom-left of the keyboard.

EOF

STEP=1

if [ "$NEEDS_KEYBOARD" = "1" ]; then
    echo "  $STEP. Open System Settings → Keyboard. (We'll open it for you.)"
    if [ "$FN_USAGE" != "0" ]; then
        echo "       • Set \"Press 🌐 key to\" → Do Nothing"
        echo "         (otherwise fn will pop up the emoji picker every release)"
    fi
    if [ "$DICT_ON" = "1" ]; then
        echo "       • Scroll down to Dictation, toggle it OFF"
        echo "         (otherwise fn fires Apple's dictation alongside MUTTER)"
    fi
    echo ""
    STEP=$((STEP + 1))
fi

cat <<EOF
  $STEP. Grant Accessibility (lets MUTTER read your fn / 🌐 key).
       Find "python" in the list and toggle it ON. If it isn't listed,
       click the "+" button and pick this file:
         $PKG_DIR/.venv/bin/python

After that, just hold fn / 🌐, speak, release. Text appears at the cursor.
The Microphone prompt (one-time "Allow") shows on your first hold.

The Whisper speech-recognition model (~1.5 GB) downloads on the daemon's
first launch. If you'd like to watch progress:
   tail -f /tmp/mutter.out.log
You'll see "mutter: ready" when it's done.
EOF

# ---------------------------------------------------------------------------
# Open the panes for them
# ---------------------------------------------------------------------------
echo ""
sleep 5
if [ "$NEEDS_KEYBOARD" = "1" ]; then
    open "x-apple.systempreferences:com.apple.preference.keyboard" 2>/dev/null || true
    sleep 8
fi
open "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility" 2>/dev/null || true

echo ""
read -rp "Press return when finished to close this window..."

#!/bin/bash
# MUTTER installer. Double-click from Finder, or run with:
#   bash install.command
#
# Idempotent — safe to re-run. Preserves any custom EnvironmentVariables
# you've added to the LaunchAgent.
#
# What this does, with no admin password required:
#   1. Finds Python 3.11. If missing, installs uv (a ~10 MB single-binary
#      Python manager from Astral) and uses it to install Python 3.11.
#   2. Creates .venv in this folder, installs requirements.txt into it.
#   3. Writes a LaunchAgent plist to ~/Library/LaunchAgents/ that runs
#      the daemon at every login.
#   4. Opens System Settings to the Accessibility pane so you can grant
#      the one permission MUTTER can't grant itself.

set -euo pipefail

PKG_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PKG_DIR"

# Clear macOS quarantine on the install scripts so re-runs don't trigger
# "cannot verify developer" again. No-op if the user already opened.
xattr -dr com.apple.quarantine "$PKG_DIR" 2>/dev/null || true

echo ""
echo "== MUTTER installer =="
echo "   folder: $PKG_DIR"
echo ""

# ---------------------------------------------------------------------------
# Python 3.11
# ---------------------------------------------------------------------------
if command -v python3.11 >/dev/null 2>&1; then
    PY="$(command -v python3.11)"
    echo "Found Python 3.11: $PY"
else
    echo "Python 3.11 not found. Installing it via uv (no admin password)..."
    if ! command -v uv >/dev/null 2>&1; then
        # uv is a single binary, installs to ~/.local/bin
        curl -LsSf https://astral.sh/uv/install.sh | sh
    fi
    export PATH="$HOME/.local/bin:$PATH"
    uv python install 3.11
    PY="$(uv python find 3.11)"
    echo "Installed Python 3.11: $PY"
fi

# ---------------------------------------------------------------------------
# Virtualenv + dependencies
# ---------------------------------------------------------------------------
if [ ! -x "$PKG_DIR/.venv/bin/python" ]; then
    echo "Creating venv ..."
    "$PY" -m venv "$PKG_DIR/.venv"
else
    echo "Reusing existing venv."
fi

echo "Installing Python dependencies (~2 min the first time)..."
"$PKG_DIR/.venv/bin/pip" install --upgrade pip --quiet
"$PKG_DIR/.venv/bin/pip" install -r "$PKG_DIR/requirements.txt" --quiet

# ---------------------------------------------------------------------------
# LaunchAgent
# ---------------------------------------------------------------------------
PLIST_DEST="$HOME/Library/LaunchAgents/com.peter.mutter.plist"
mkdir -p "$(dirname "$PLIST_DEST")"

if [ -f "$PLIST_DEST" ] && grep -q "$PKG_DIR" "$PLIST_DEST"; then
    # Existing plist already points at this folder — preserve it. This
    # keeps any user-added EnvironmentVariables (e.g. MUTTER_WHISPER_MODEL).
    echo "LaunchAgent already configured for this folder — preserving custom settings."
    launchctl unload "$PLIST_DEST" 2>/dev/null || true
else
    if [ -f "$PLIST_DEST" ]; then
        echo "Replacing LaunchAgent (existing one points to a different folder)."
        launchctl unload "$PLIST_DEST" 2>/dev/null || true
    fi
    sed "s|__PKG_DIR__|$PKG_DIR|g" \
        "$PKG_DIR/com.peter.mutter.plist.template" > "$PLIST_DEST"
    echo "Wrote LaunchAgent: $PLIST_DEST"
fi

launchctl load "$PLIST_DEST"
echo "LaunchAgent loaded."

# ---------------------------------------------------------------------------
# What the user has to do (3 clicks total)
# ---------------------------------------------------------------------------
cat <<EOF

==========================================
  Install done — three quick clicks left
==========================================

  1. Turn OFF macOS Dictation so it doesn't fire on fn alongside MUTTER.
       System Settings → Keyboard → Dictation → toggle OFF.

  2. Grant Accessibility to MUTTER. The pane will OPEN AUTOMATICALLY
     in 5 seconds. Find the python entry and toggle it ON. If it's
     not in the list, click '+' and pick:
       $PKG_DIR/.venv/bin/python

  3. Click "Allow" on the Microphone prompt the first time you hold fn.

That's all. Then: hold fn anywhere, speak, release. Text appears.

Whisper model (~1.5 GB) auto-downloads on first daemon launch. Watch:
   tail -f /tmp/mutter.out.log
You'll see "mutter: ready in ~10s" when it's done loading.

Want the smaller q4 model (~440 MB) instead? See README.md → "Choosing
a Whisper model".
EOF

sleep 5
open "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility" 2>/dev/null || true

echo ""
read -rp "Press return when finished to close this window..."

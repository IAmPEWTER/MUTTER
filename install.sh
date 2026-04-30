#!/bin/bash
# MUTTER one-line bootstrap.
#
# Paste in Terminal:
#   curl -fsSL https://raw.githubusercontent.com/IAmPEWTER/MUTTER/main/install.sh | bash
#
# What it does:
#   - Clones (or updates) the repo into ~/Desktop/MUTTER.
#   - Runs install.command from that folder.
# Everything else (Python, venv, deps, LaunchAgent) is handled there.

set -euo pipefail

DEST="$HOME/Desktop/MUTTER"
REPO="https://github.com/IAmPEWTER/MUTTER.git"

echo ""
echo "== MUTTER bootstrap =="
echo ""

if [ -d "$DEST/.git" ]; then
    echo "Updating existing checkout at $DEST ..."
    git -C "$DEST" pull --rebase --quiet
elif [ -e "$DEST" ]; then
    echo "ERROR: $DEST exists but isn't a git checkout."
    echo "Move or rename it, then re-run."
    exit 1
else
    echo "Cloning $REPO -> $DEST ..."
    git clone --quiet "$REPO" "$DEST"
fi

bash "$DEST/install.command"

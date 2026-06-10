#!/bin/bash
# MUTTER uninstaller. Double-click to run.
# Unloads and removes the LaunchAgent. Leaves this folder and venv alone
# so you can reinstall with install.command without re-downloading whisper.

set -euo pipefail

PLIST_DEST="$HOME/Library/LaunchAgents/com.peter.mutter.plist"

echo ""
echo "== MUTTER uninstaller =="
echo ""

if [ -f "$PLIST_DEST" ]; then
    launchctl unload "$PLIST_DEST" 2>/dev/null || true
    rm -f "$PLIST_DEST"
    echo "Removed LaunchAgent: $PLIST_DEST"
else
    echo "No LaunchAgent found at $PLIST_DEST — already uninstalled."
fi

rm -f /tmp/mutter.pid /tmp/mutter.out.log /tmp/mutter.err.log

echo ""
echo "To fully remove, also delete this folder and the whisper cache:"
echo "   ~/.cache/huggingface/hub/models--mlx-community--whisper-large-v3-turbo*"
echo ""
read -rp "Press return to close..."

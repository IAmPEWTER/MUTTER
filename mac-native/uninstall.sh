#!/bin/bash
# Remove the MUTTER daemon: stop it, delete the LaunchAgent + app bundle.
# Leaves logs and the macOS TCC permission grants in place (harmless if you
# reinstall later). Run with --purge to also remove logs.
set -euo pipefail

IDENT="com.peter.mutter.app"
PLIST="$HOME/Library/LaunchAgents/${IDENT}.plist"
BUNDLE="/Applications/MUTTER.app"
LOGDIR="$HOME/Library/Logs/mutter"

echo "== MUTTER daemon uninstall =="
launchctl bootout "gui/$(id -u)/${IDENT}" 2>/dev/null || true
rm -f "$PLIST"
# Send the app bundle to Trash rather than hard-deleting.
if [ -d "$BUNDLE" ]; then
    osascript -e "tell application \"Finder\" to delete POSIX file \"$BUNDLE\"" >/dev/null 2>&1 \
        || rm -rf "$BUNDLE"
fi

if [ "${1:-}" = "--purge" ]; then
    rm -rf "$LOGDIR"
    echo "removed logs."
fi

echo "done. (Accessibility/Microphone grants left in System Settings; remove"
echo "manually if you want them gone.)"

#!/bin/bash
# Build + install the MUTTER dictation daemon on this Mac.
#
#   ./install.sh
#
# Self-contained: assembles /Applications/MUTTER.app around the compiled binary
# (no Tauri/Xcode toolchain needed), installs the LaunchAgent, and loads it.
# Idempotent — safe to re-run to update after a `git pull`.
#
# Requires: Rust (rustup), and the shared STT service running.
set -euo pipefail

BUNDLE="/Applications/MUTTER.app"
IDENT="com.peter.mutter.app"
PLIST="$HOME/Library/LaunchAgents/${IDENT}.plist"
LOGDIR="$HOME/Library/Logs/mutter"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "== MUTTER daemon install =="

# --- cargo (rustup installs to the toolchain dir; PATH may lack the shim) ----
if command -v cargo >/dev/null 2>&1; then
    CARGO="$(command -v cargo)"
elif command -v rustup >/dev/null 2>&1; then
    CARGO="$(rustup which cargo)"
    # Put the toolchain's bin on PATH so cargo can find its sibling rustc.
    export PATH="$(dirname "$CARGO"):$PATH"
else
    echo "ERROR: Rust not found. Install it: https://rustup.rs" >&2
    exit 1
fi

echo "-- building (release) --"
( cd "$HERE" && "$CARGO" build --release )
BIN="$HERE/target/release/mutter"
[ -x "$BIN" ] || { echo "ERROR: build produced no binary at $BIN" >&2; exit 1; }

echo "-- assembling $BUNDLE --"
mkdir -p "$BUNDLE/Contents/MacOS"
cp "$HERE/Info.plist" "$BUNDLE/Contents/Info.plist"
cp "$BIN" "$BUNDLE/Contents/MacOS/mutter"

echo "-- signing (ad-hoc, stable identifier so TCC grants persist) --"
codesign --force --sign - --identifier "$IDENT" "$BUNDLE"

echo "-- installing LaunchAgent --"
mkdir -p "$LOGDIR" "$(dirname "$PLIST")"
sed "s|__HOME__|$HOME|g" "$HERE/com.peter.mutter.app.plist" > "$PLIST"

echo "-- (re)loading daemon --"
# bootout returns before launchd has actually released the label; bootstrapping
# into the gap fails with "Bootstrap failed: 5: Input/output error". Wait for
# the service to really be gone.
launchctl bootout "gui/$(id -u)/${IDENT}" 2>/dev/null || true
for _ in $(seq 1 50); do
    launchctl print "gui/$(id -u)/${IDENT}" >/dev/null 2>&1 || break
    sleep 0.1
done
launchctl bootstrap "gui/$(id -u)" "$PLIST"
sleep 2

# --- STT service sanity (check the socket the daemon actually uses) --------
# Prefer stt.sock; fall back to the pre-2026-08-20 whisper.sock so this
# installer is happy against a daemon that has not been migrated yet.
WSOCK="${STT_SOCK:-${WHISPER_SOCK:-}}"
if [[ -z "$WSOCK" ]]; then
    WSOCK="${TMPDIR:-/tmp/}stt.sock"
    [[ -S "$WSOCK" ]] || WSOCK="${TMPDIR:-/tmp/}whisper.sock"
fi
if [ ! -S "$WSOCK" ]; then
    echo ""
    echo "WARNING: STT socket not found at $WSOCK."
    echo "         MUTTER needs the shared STT service running"
    echo "         (see ~/Documents/services/stt/)."
fi

echo ""
echo "Installed. First run needs two macOS permissions for MUTTER.app:"
echo "  1. Accessibility  (to read the fn key + type at the cursor)"
echo "  2. Microphone     (prompts automatically on your first fn-hold)"
echo "Opening the Accessibility pane — enable MUTTER there if it isn't already."
open "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility" || true

echo ""
echo "Then: click into any text field, hold fn, speak, release."
echo "Logs: $LOGDIR/app.err.log"

#!/usr/bin/env bash
# Regenerate the Tier A real-audio test fixture (docs/handy-migration-spec.md §0
# goal 2). Synthesizes a known phrase with macOS `say`, then converts it to the
# 16 kHz mono s16le WAV the recorder pipeline and the MLX daemon both consume —
# deterministic ground truth, no microphone or acoustic capture needed.
#
# The resulting WAV is committed so CI never needs `say`/`afconvert`; this script
# only exists to reproduce or refresh it. Safe to run from anywhere.
set -euo pipefail

PHRASE="the quick brown fox jumps over the lazy dog"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_DIR="$SCRIPT_DIR/../src-tauri/tests/fixtures"
mkdir -p "$FIXTURE_DIR"

WAV="$FIXTURE_DIR/fox_16khz_mono.wav"
EXPECTED="$FIXTURE_DIR/fox_16khz_mono.expected.txt"

TMP_AIFF="$(mktemp -t fox_fixture).aiff"
trap 'rm -f "$TMP_AIFF"' EXIT

say -o "$TMP_AIFF" "$PHRASE"
# WAVE container, little-endian int16, 16 kHz, 1 channel = 16 kHz mono s16le.
afconvert -f WAVE -d LEI16@16000 -c 1 "$TMP_AIFF" "$WAV"
printf '%s\n' "$PHRASE" > "$EXPECTED"

echo "Wrote $WAV"
afinfo "$WAV" | sed -n '3,6p'
echo "Wrote $EXPECTED ($PHRASE)"

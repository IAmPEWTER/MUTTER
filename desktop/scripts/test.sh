#!/usr/bin/env bash
# Desktop (Tauri) test entry point for local dev / CI.
#
#   cargo test --lib              -> fast unit tests, no daemon needed
#   Tier A real_audio_pipeline    -> real WAV -> real VAD -> live MLX daemon;
#                                    skips gracefully when the whisper socket is
#                                    absent (see the test's own gating).
#
# The git pre-commit hook (../../.git/hooks/pre-commit) runs ONLY `cargo test
# --lib` to stay fast and daemon-independent. Run this script for the full pass
# (lib + Tier A integration) locally or in CI.
set -euo pipefail

export PATH="$HOME/.rustup/toolchains/stable-aarch64-apple-darwin/bin:/opt/homebrew/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../src-tauri"

echo "== cargo test --lib =="
cargo test --lib "$@"

echo
echo "== Tier A: real_audio_pipeline (real audio -> VAD -> daemon) =="
cargo test --test real_audio_pipeline -- --nocapture

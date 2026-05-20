#!/usr/bin/env bash
# Download the sherpa-onnx Android AAR (~55 MB) from GitHub Releases into
# app/libs/. Idempotent: skips download if the file already exists with the
# right SHA-256.
#
# Called automatically by Gradle (see app/build.gradle.kts), so a manual
# invocation is normally unnecessary.

set -euo pipefail

VERSION="1.13.2"
AAR_NAME="sherpa-onnx-${VERSION}.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/${AAR_NAME}"
EXPECTED_SHA256="aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBS_DIR="$(cd "$SCRIPT_DIR/../app/libs" 2>/dev/null && pwd || true)"
if [ -z "$LIBS_DIR" ]; then
    LIBS_DIR="$SCRIPT_DIR/../app/libs"
    mkdir -p "$LIBS_DIR"
    LIBS_DIR="$(cd "$LIBS_DIR" && pwd)"
fi
TARGET="$LIBS_DIR/$AAR_NAME"

verify() {
    local file="$1"
    if [ ! -f "$file" ]; then return 1; fi
    local got
    if command -v sha256sum >/dev/null 2>&1; then
        got=$(sha256sum "$file" | cut -d' ' -f1)
    else
        got=$(shasum -a 256 "$file" | cut -d' ' -f1)
    fi
    [ "$got" = "$EXPECTED_SHA256" ]
}

if verify "$TARGET"; then
    echo "sherpa-onnx AAR already present and verified."
    exit 0
fi

echo "Downloading $AAR_NAME (~55 MB) from k2-fsa/sherpa-onnx releases..."
curl --fail --location --output "$TARGET.tmp" "$URL"
if ! verify "$TARGET.tmp"; then
    rm -f "$TARGET.tmp"
    echo "ERROR: SHA-256 mismatch after download." >&2
    exit 1
fi
mv "$TARGET.tmp" "$TARGET"
echo "Done: $TARGET"

#!/usr/bin/env bash
# Put the speech model on a connected device/emulator without making the app
# download it, so `./gradlew :app:connectedDebugAndroidTest` can run.
#
# Files are cached under build/model-cache/ and SHA-256 verified against the
# canonical k2-fsa release — the same hashes SttModel.kt carries.
set -euo pipefail

PKG="${PKG:-com.peter.mutter.debug}"
DIR="parakeet-tdt-0.6b-v2-int8"
HF="https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"
GH="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

# filename<TAB>url<TAB>sha256
ASSETS=$(cat <<EOF
encoder.int8.onnx	$HF/encoder.int8.onnx	a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab
decoder.int8.onnx	$HF/decoder.int8.onnx	b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e
joiner.int8.onnx	$HF/joiner.int8.onnx	7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2
tokens.txt	$HF/tokens.txt	ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d
silero_vad.onnx	$GH/silero_vad.onnx	9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6
EOF
)

CACHE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/model-cache"
mkdir -p "$CACHE"

while IFS=$'\t' read -r name url want; do
    [ -n "$name" ] || continue
    got=$(shasum -a 256 "$CACHE/$name" 2>/dev/null | cut -d' ' -f1 || true)
    if [ "$got" != "$want" ]; then
        echo "downloading $name"
        curl --fail --location --progress-bar --output "$CACHE/$name.tmp" "$url"
        got=$(shasum -a 256 "$CACHE/$name.tmp" | cut -d' ' -f1)
        [ "$got" = "$want" ] || { echo "ERROR: $name checksum mismatch" >&2; exit 1; }
        mv "$CACHE/$name.tmp" "$CACHE/$name"
    fi
done <<< "$ASSETS"

# adb shell reads stdin, which would swallow the rest of the here-string.
adb shell "run-as $PKG mkdir -p files/models/$DIR" </dev/null 2>/dev/null || true
while IFS=$'\t' read -r name url want; do
    [ -n "$name" ] || continue
    echo "pushing $name"
    adb push "$CACHE/$name" "/data/local/tmp/$name" </dev/null >/dev/null
    adb shell "cat /data/local/tmp/$name | run-as $PKG sh -c 'cat > files/models/$DIR/$name'" </dev/null
    adb shell "rm /data/local/tmp/$name" </dev/null
done <<< "$ASSETS"

echo "on device:"
adb shell "run-as $PKG ls -la files/models/$DIR" </dev/null

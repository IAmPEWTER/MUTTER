#!/usr/bin/env bash
# Put the speech model on a connected device/emulator without making the app
# download it, so `./gradlew :app:connectedDebugAndroidTest` can run.
#
# Files are cached under build/model-cache/ and SHA-256 verified. The file list,
# URLs and hashes are read out of SttModel.kt — there is no second copy to drift.
set -euo pipefail

PKG="${PKG:-com.peter.mutter.debug}"
# Everything below is derived from SttModel.kt so the two can never disagree
# about which files, from where, at which hash.
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/src/main/kotlin/com/peter/mutter/SttModel.kt"
read -r DIR ASSETS <<<"$(python3 - "$SRC" <<'PY'
import re, sys
src = open(sys.argv[1]).read()
const = dict(re.findall(r'const val (\w+)\s*=\s*"([^"]*)"', src))
rows = []
for url, name, size, sha in re.findall(
        r'Asset\(\s*"([^"]+)",\s*(\w+),\s*([\d_]+)L,\s*\n?\s*"([0-9a-f]{64})"', src):
    url = re.sub(r'\$(\w+)', lambda m: const[m.group(1)], url)
    rows.append(f"{const[name]}|{url}|{sha}")
print(const["DIR"], ";".join(rows))
PY
)"
[ -n "$DIR" ] && [ -n "$ASSETS" ] || { echo "ERROR: could not parse SttModel.kt" >&2; exit 1; }
ASSETS=$(echo "$ASSETS" | tr ';' '\n' | tr '|' '\t')

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

# Ship-an-update playbook (Android)

The MUTTER Android app self-updates via GitHub Releases. On launch it fetches `latest.json`, compares `versionCode`, and offers an in-app install if higher.

## One-time setup (already done = skip)

- Public repo `IAmPEWTER/mutter-releases` exists.
- Phone has a build with `com.peter.mutter.updater` installed (i.e. ≥ versionCode 2).
- Phone has "Install unknown apps" toggled on for MUTTER.

If creating from scratch:

```
gh repo create IAmPEWTER/mutter-releases --public --description "MUTTER Android release artifacts" --homepage "https://github.com/IAmPEWTER/mutter-releases"
```

## Cut a release

```
cd ~/Desktop/MUTTER/android

# 1. Bump version in app/build.gradle.kts (both versionCode and versionName)
#    Edit:  versionCode = N+1    versionName = "X.Y.Z"

# 2. Build
./gradlew :app:assembleDebug

# 3. Stage artifacts
APK=app/build/outputs/apk/debug/app-debug.apk
SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
SIZE=$(stat -f%z "$APK")
VC=$(grep 'versionCode =' app/build.gradle.kts | head -1 | awk '{print $NF}')
VN=$(grep 'versionName =' app/build.gradle.kts | head -1 | awk -F'"' '{print $2}')

cat > /tmp/latest.json <<JSON
{
  "versionCode": $VC,
  "versionName": "$VN",
  "apkUrl": "https://github.com/IAmPEWTER/mutter-releases/releases/download/v$VN/app-debug.apk",
  "apkSha256": "$SHA",
  "apkSize": $SIZE,
  "notes": "<release notes here>"
}
JSON

# 4. Push the release
gh release create "v$VN" --repo IAmPEWTER/mutter-releases \
  --title "v$VN" \
  --notes "<release notes>" \
  "$APK" /tmp/latest.json
```

Phone picks it up at next app launch (or via Settings → Check for updates).

**If the release changes `SttModel.DIR`** the new build has no model until the user
downloads it: the service posts a tappable notification on connect, and the old
model directory is deleted once the new one lands. Say so in the release notes.

## Schema

`latest.json` lives at `https://github.com/IAmPEWTER/mutter-releases/releases/latest/download/latest.json` (stable URL — GitHub resolves `latest/download/<asset>` to the most recent release).

| Field | Type | Required | Notes |
|---|---|---|---|
| `versionCode` | int | yes | App-side `BuildConfig.VERSION_CODE` compares against this |
| `versionName` | string | yes | Displayed in UI |
| `apkUrl` | string (https) | yes | Direct download URL |
| `apkSha256` | string | no | If present, verified after download |
| `apkSize` | int | no | If present, verified after download |
| `notes` | string | no | Shown in update prompt |

## Constraints

- **Same signing key** — the new APK must be signed by `~/.android/debug.keystore` (back this up to `~/.secrets/mutter/debug.keystore`). A different key = Android refuses the update, falls back to "uninstall first" which wipes the model cache.
- **Monotonic versionCode** — Android won't install an APK whose versionCode ≤ installed. Bump every release.
- **Phone must have permission** — "Install unknown apps" toggled on for MUTTER. The app routes the user to the right settings screen the first time install is attempted.
- **First update from a pre-updater install** must be done manually (the old build doesn't know to check). Subsequent updates auto-flow.

## Where the code lives

- `app/src/main/kotlin/com/peter/mutter/updater/`
  - `UpdateConstants.kt` — manifest URL, throttle interval
  - `UpdateManifest.kt` — JSON schema + parser
  - `UpdateChecker.kt` — fetch + version compare (throttled to once per 6 h unless `force=true`)
  - `UpdateInstaller.kt` — download to cache, SHA-256 verify, open PackageInstaller session
  - `UpdateInstallReceiver.kt` — handles `STATUS_PENDING_USER_ACTION` → launches the system installer UI
- `SetupActivity.kt` does a silent check on launch and badges the Settings button if an update is found.
- `SettingsActivity.kt` exposes a manual "Check for updates" button.

## Testing manifest changes locally

Override the manifest URL via SharedPreferences:

```
adb shell run-as com.peter.mutter.debug sh -c 'mkdir -p shared_prefs && cat > shared_prefs/mutter_prefs.xml' <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="updater_manifest_url">http://10.0.2.2:8000/latest.json</string>
</map>
XML
adb shell am force-stop com.peter.mutter.debug
```

Then `python3 -m http.server 8000` from a dir containing a sample `latest.json` + APK. `10.0.2.2` is the AVD's host-loopback gateway. On a physical device over WiFi, substitute the host's LAN IP.

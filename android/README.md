# MUTTER Android

Hold **volume-down** inside any text field on Android → speak → release → text appears at the cursor.

Local Whisper (sherpa-onnx, distil-small.en INT8). CPU only. Clipboard restored within ~200 ms.

## Get the APK

Prebuilt debug APK (45 MB, anyone with the link can download):

**https://github.com/IAmPEWTER/mutter-releases/releases/latest/download/app-debug.apk**

(Old Drive mirror: https://drive.google.com/file/d/13oiCOU95ENVV6AQ6Pu_7tzvlnZ7AYaqD/view — kept for the first install if you can't reach GitHub.)

Source build instructions are at the bottom for anyone who wants to compile from scratch.

## In-app updates

From v0.2.0 the app self-updates. On launch it fetches `latest.json` from the releases repo, compares `versionCode`, and offers an in-app install if higher. The model cache and prefs survive updates (same package + same signing key). See `docs/release-process.md` for the cut-a-release procedure.

First-time setup: Settings → tap Check for updates → Install. Android will once ask "Install unknown apps for MUTTER" — toggle on, return, tap Install again. After that all updates run with a single confirm tap per install.

## What works out of the box

- Sideload-only. No Play Store.
- Galaxy S23 / Snapdragon 8 Gen 2 verified. minSdk 34 (Android 14+). Built for arm64-v8a.
- Volume-down PTT whenever the soft keyboard is up (or an editable text field has focus). Outside, volume keys behave normally.
- Paste-and-restore text injection (universal). Password fields skipped automatically.

## One-time setup on the phone

### Path A — tap-to-install (no cable needed)

1. On the phone, open the Drive link above. Tap **Download**.
2. Open the downloaded APK from your Files / Downloads notification.
3. Android will ask to *allow install from this source* — toggle it on for whichever app opened the APK (Files / Chrome / Drive). Tap **Install**.

### Path B — adb sideload (cable)

1. **Enable Developer Options + USB Debugging.**
   - Settings → About phone → Software information → tap *Build number* 7 times.
   - Settings → Developer options → toggle *USB debugging* on.
2. **Plug phone into a computer via USB.** Tap *Allow* on the RSA-fingerprint prompt.
3. **Install the APK:**
   ```
   adb install -r mutter-0.1.0-debug.apk
   ```

### After install, either path

4. **Open *MUTTER* on the phone.** Setup wizard walks you through:
   - Grant Microphone permission.
   - Enable accessibility service (Settings → Accessibility → Installed apps → MUTTER → on).
     - You will be asked to confirm key-event filtering. Allow.
   - Allow battery optimization exemption (prevents Android from killing the service).
   - Download model (~280 MB, one time, cached on device).
   - In-wizard test field: hold volume-down and say something. Release.

## Daily use

- Tap into any text field (Messages, Gmail, Notes, Slack, Chrome address bar, etc.).
- Press and hold volume-down.
- Speak.
- Release.
- Text appears at the cursor ~200–350 ms later.

When MUTTER is recording, a low-priority notification shows in the shade and the volume slider does **not** appear (the press is consumed). When idle, volume-down does the normal thing.

To disable temporarily: open MUTTER → Settings → toggle *Volume-down intercept* off. Re-enable from the same place.

## What's where

```
android/
├── app/
│   ├── build.gradle.kts          — AGP 8.7, Kotlin 2.0, compileSdk 35
│   ├── libs/sherpa-onnx-1.13.2.aar
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/peter/mutter/
│       │   ├── MutterAccessibilityService.kt   — key intercept, hold sessions, FG promotion
│       │   ├── AudioRecorder.kt                — 16 kHz mono PCM capture
│       │   ├── VadSegmenter.kt                 — Silero VAD + chunk buffering
│       │   ├── AdaptiveEndpointer.kt           — pure chunk-cut logic
│       │   ├── WhisperEngine.kt                — sherpa-onnx OfflineRecognizer wrapper
│       │   ├── Sanitizer.kt                    — text cleanup + repeat-loop collapse
│       │   ├── EnergyGate.kt                   — RMS (degraded-VAD stand-in)
│       │   ├── PendingAudio.kt                 — failed-chunk WAV persistence
│       │   ├── TextInjector.kt                 — paste → SET_TEXT splice → clipboard+notif
│       │   ├── ModelDownloader.kt              — first-launch HF fetch
│       │   ├── NotificationHelper.kt           — FG + error channels
│       │   ├── DailyRecycler.kt                — ~5am recognizer recycle
│       │   ├── Prefs.kt                        — intercept on/off
│       │   ├── updater/                        — GitHub Releases self-update
│       │   ├── setup/SetupActivity.kt          — wizard
│       │   └── settings/SettingsActivity.kt
│       └── res/
│           ├── xml/accessibility_service_config.xml
│           └── values, layout, drawable, color, font, mipmap-anydpi-v26
├── art/ic_launcher.svg            — icon design source (vector drawable mirrors it)
└── (gradle wrapper, settings, etc.)
```

UI follows the Margo design tokens (`~/Desktop/Margo/Website and design/design/DESIGN_TOKENS.md`): Graphite palette, Geist type, pill controls.

## Build from source

Requires JDK 17 (`brew install openjdk@17` on macOS) and Android SDK cmdline-tools (`brew install --cask android-commandlinetools`). Set `JAVA_HOME` and `ANDROID_HOME`, then accept SDK licenses with `sdkmanager --licenses`.

```
cd android/
./gradlew :app:assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM unit tests for the pure-logic units
./gradlew :app:lintDebug              # static analysis (warnings only)
```

The sherpa-onnx Android AAR (~55 MB) is **not** committed to the repo. The first `./gradlew assembleDebug` runs `scripts/fetch-libs.sh` automatically, which downloads it from `k2-fsa/sherpa-onnx` releases into `app/libs/` and SHA-256-verifies. Idempotent — subsequent builds skip the download.

Debug build is signed automatically with `~/.android/debug.keystore`. Sideloadable as-is; no Play Store, no keystore management.

## Verified

- 35 JVM unit tests pass (endpointer, sanitizer + repeat-collapse, RMS, recycler, updater).
- Daily driver on Galaxy S23 (One UI): vol-down intercept, paste injection, streaming chunks.

## Uninstall

- Phone: Settings → Apps → MUTTER → Uninstall.
- Or via adb: `adb uninstall com.peter.mutter.debug`.

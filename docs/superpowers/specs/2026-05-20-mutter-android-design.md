# MUTTER Android — Design

Android port of MUTTER. Hold **volume-down** while a text field is focused → speak → release → text appears at the cursor.

## Goal

Replace Samsung's on-device STT with local Whisper, system-wide. Press-to-talk via vol-down hardware key. Briefly uses clipboard (saved + restored within ~200 ms) for the universal paste path.

## Non-goals

- Not modifying Samsung Keyboard.
- Not a custom IME.
- Not streaming. Transcribes on release.

## Trigger

Vol-down intercept is **scoped to the focused text input field**. Outside text inputs, vol-down lowers media volume normally. Inside one, holding vol-down dictates.

Gated by `findFocus(FOCUS_INPUT).isEditable()` on key DOWN. False → pass through. True → consume + record.

## Architecture

```
  vol-down DOWN
        │
        ▼
  AccessibilityService.onKeyEvent
    ├─ not editable → pass through
    └─ editable → consume, startForeground(MIC), open AudioRecord
                          │
                          ▼
                   ring buffer (16k mono int16)
                          │
                          ▼
  vol-down UP
        │
        ▼
  consume, stop AudioRecord, stopForeground, transcribe
                          │
                          ▼
                sherpa-onnx OfflineRecognizer.decode
                  + hallucination filter (port of mutter/stt.py)
                  + energy gate
                          │
                          ▼
  set clipboard → ACTION_PASTE → restore clipboard ~200 ms later
       (ACTION_SET_TEXT as optional fast-path)
```

One AccessibilityService process owns everything. Promotes to FG (type=microphone) only during recording.

### MutterAccessibilityService

Non-default manifest attributes:
- `canRequestFilterKeyEvents="true"`
- `foregroundServiceType="microphone"`
- accessibility config: `flagRequestFilterKeyEvents`
- permission: `FOREGROUND_SERVICE_MICROPHONE`

State machine (mirrors mutter/daemon.py):

```
IDLE ── vol-down DOWN + editable focus ──→ LISTENING
LISTENING ── vol-down UP ──→ TRANSCRIBING
TRANSCRIBING ── result/error ──→ IDLE
```

Only edges drive transitions. Auto-repeat at ~50 ms while held — first DOWN and matching UP only. Galaxy firmware may flash the volume slider for ~50 ms before consumption; cosmetic.

Model load in `onServiceConnected()`:
1. `OfflineRecognizer` from distil-small.en INT8 in internal storage.
2. Pre-warm with 0.1 s zero buffer (matches `_MlxBackend.__init__`).

FG promotion: `startForeground(MICROPHONE)` on DOWN, `stopForeground(REMOVE)` on UP. Notification visible only while recording.

AudioRecord: 16 kHz mono PCM int16, 50 ms blocks (matches `DEFAULT_BLOCK_SECONDS`). Opened per LISTENING entry.

### sherpa-onnx

JitPack: `com.github.k2-fsa:sherpa-onnx:v1.13.2`.

Model: `distil-small.en` INT8 from `csukuangfj/sherpa-onnx-whisper-distil-small.en`:
- encoder.int8.onnx ~103 MB
- decoder.int8.onnx ~195 MB
- tokens.txt
- Total ~298 MB.

CPU only. NNAPI falls back for transformer ops, deprecated in Android 15. QNN doesn't fit Whisper's dynamic decoder, and sherpa-onnx ships no Whisper-QNN binary. CPU latency on SD8G2: ~150–300 ms per 5 s clip.

### Model download (first launch)

1. Check `getFilesDir()/models/distil-small.en/`.
2. If missing, download `.tar.bz2` from sherpa-onnx GitHub releases (~150 MB compressed).
3. Extract.
4. SHA-256 verify against baked-in hash.

### Hallucination filter

Port from `mutter/stt.py`:
- `_HALLUCINATIONS` frozenset
- `_HALLUCINATION_REPEAT_RE`
- `is_hallucination(text)`

Plus energy gate: RMS < threshold AND duration < 1.0 s → drop regardless of transcript.

### Text injection

Primary: **paste-and-restore.** Save clipboard, set to transcript, `ACTION_PASTE` on focused node, restore ~200 ms later.

Optional fast-path: `ACTION_SET_TEXT` first; on `false`, fall to paste.

Sanitize (port from `mutter/daemon.py`):
- `\n`, `\r` → space
- Runs of spaces collapsed
- Trim

## Setup wizard

Deep-linked sequential steps:
1. RECORD_AUDIO grant.
2. Accessibility enable (`Settings.ACTION_ACCESSIBILITY_SETTINGS`).
3. Battery optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
4. Model download.
5. Smoke test: in-wizard text field, "hold vol-down and say 'hello'."

## Latency

| Stage | Cost |
|---|---|
| vol-down DOWN → onKeyEvent | ~2–5 ms |
| Focus check | ~3–10 ms |
| AudioRecord open + startForeground | ~10 ms |
| **DOWN → mic hot** | **~15–25 ms** |
| sherpa-onnx transcribe (5 s clip) | ~150–300 ms |
| paste-and-restore | ~20–50 ms |
| **UP → text visible** | **~175–355 ms** |

Cold start (post-reboot, first press): ~2 s warmup in `onServiceConnected()`, paid in background.

## Resource usage

- **Battery**: idle negligible. Per dictation (5 s record + 300 ms transcribe): ~3.4 J. 100/day ≈ 0.1 Wh ≈ 0.7% of S23 battery.
- **RAM**: ~330–430 MB resident (model + ORT + baseline). LPDDR5 refresh is content-independent — no continuous power penalty.
- **CPU**: idle 0%. Recording ~5–10% of one core. Transcribing: all 8 cores for ~150–300 ms per session.

## Edge cases

- Re-press during LISTENING/TRANSCRIBING, stale UP during IDLE/TRANSCRIBING: consumed no-op.
- Focus disappears mid-hold: keep recording until UP, re-resolve focus at injection.
- Focus moves mid-hold: inject wherever focus lands at UP.
- Vol-up while vol-down held: passes through.
- Vol-down in password field (TYPE_TEXT_VARIATION_PASSWORD): not consumed. Privacy guard.
- Apps that block AccessibilityService: vol-down passes through; PTT silently inactive.
- Short tap (<0.3 s): clip drops on `MIN_CAPTURED_SEC` (port).
- Max duration: hard 60 s, soft 30 s with warning vibration. Matches `DEFAULT_MAX_DURATION_SEC`.
- Service killed by OS: Android auto-restarts accessibility services; battery exemption further reduces kill rate.
- Model download interrupted: resumable HTTP range; SHA-256 re-verified.
- Transcription failure (sherpa-onnx throws / OOM / model corruption): caught, logged, haptic. No text. Next press retries.
- Both paste and ACTION_SET_TEXT fail: transcript left on clipboard (no restore), haptic.

## Caveats

1. Some apps refuse normal operation when an accessibility service is enabled (banking, anti-fraud). PTT inactive there.
2. Volume keys lose normal function in text fields. Settings toggle disables intercept.
3. Cold start after reboot: ~2 s, paid in background.
4. Volume slider may briefly flash on Galaxy firmware (~50 ms). Cosmetic.

## File tree

```
MUTTER/
├── android/
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/peter/mutter/
│   │   │   ├── MutterAccessibilityService.kt   — key intercept, audio, model, injection
│   │   │   ├── WhisperEngine.kt                — sherpa-onnx wrapper
│   │   │   ├── HallucinationFilter.kt          — port of mutter/stt.py
│   │   │   ├── EnergyGate.kt                   — RMS silence guard
│   │   │   ├── TextInjector.kt                 — paste-and-restore + ACTION_SET_TEXT
│   │   │   ├── ModelDownloader.kt              — first-launch fetch + SHA-256
│   │   │   ├── setup/                          — wizard activities
│   │   │   └── settings/SettingsActivity.kt
│   │   └── res/xml/accessibility_service_config.xml
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── (existing macOS MUTTER files unchanged)
```

## Sources

- sherpa-onnx [GitHub](https://github.com/k2-fsa/sherpa-onnx) · [distil-small.en on HF](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-distil-small.en)
- Samsung Keyboard mic hardcoded: [FUTO](https://github.com/futo-org/voice-input), [Samsung Community](https://us.community.samsung.com/t5/Galaxy-S24/Samsung-keyboard-microphone-input/td-p/2789075/page/13)
- NNAPI/QNN not viable for Whisper: [ORT NNAPI](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html), [ORT QNN](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html), [NNAPI deprecation](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)
- Volume-key system-wide intercept proof: [Key Mapper](https://f-droid.org/packages/io.github.sds100.keymapper/)
- [Android 15 FGS rules](https://developer.android.com/about/versions/15/changes/foreground-service-types)

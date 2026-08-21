# MUTTER Android — Design

Android port of MUTTER. Hold **volume-down** while a text field is focused → speak → release → text appears at the cursor.

## Goal

Replace Samsung's on-device STT with a local model, system-wide. Press-to-talk via vol-down hardware key. Briefly uses clipboard (saved + restored within ~200 ms) for the universal paste path.

## Non-goals

- Not modifying Samsung Keyboard.
- Not a custom IME.

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
    └─ editable → consume, startForeground(MIC), THEN stream AudioRecord
                          │
                          ▼
               512-sample (32 ms) windows
                          │
                          ▼
        VadSegmenter: Silero VAD per window →
        AdaptiveEndpointer cuts ≤25 s chunks (no length cap)
                          │
            each cut ─────┤  ← streaming: fires mid-hold
                          ▼
                sherpa-onnx OfflineRecognizer.decode (per chunk)
                  + collapseRepeats (bounds decoder repeat-loops)
                          │
                          ▼
  set clipboard → ACTION_PASTE → (refused? ACTION_SET_TEXT splice)
       (chunks injected in spoken order, space-separated;
        end of hold: failed chunks → clipboard + notification,
        else saved clipboard restored ~200 ms later)
                          ▲
  vol-down UP ──── stop AudioRecord, flush final chunk, stopForeground
```

One AccessibilityService process owns everything. Promotes to FG (type=microphone) only during recording. Chunks transcribe on a single FIFO worker, so text lands in spoken order while the user is still holding.

### MutterAccessibilityService

Non-default manifest attributes:
- `canRequestFilterKeyEvents="true"`
- `foregroundServiceType="microphone"`
- accessibility config: `flagRequestFilterKeyEvents`
- permission: `FOREGROUND_SERVICE_MICROPHONE`

Capture state is a single `capturing` flag (true between accepted DOWN and
its UP). Transcription/injection continue on the FIFO worker after UP, so a
NEW hold starts immediately even while the previous one is still draining — a
slow transcription can never swallow a press. Each hold carries a `Hold`
session (target node, spacing flag, failed-chunk list) through the queue, so
overlapping holds can't cross-talk. Auto-repeat while held is consumed; first
DOWN and matching UP only. Galaxy firmware may flash the volume slider for
~50 ms before consumption; cosmetic.

Model load in `onServiceConnected()`:
1. `OfflineRecognizer` from parakeet-tdt-0.6b-v2 INT8 in internal storage.
2. `VadSegmenter` loads `silero_vad.onnx` from file (`Vad(config=…)`, null AssetManager → `newFromFile`).
3. Pre-warm recognizer with 0.1 s zero buffer.
4. `AudioRecorder.prepare()` opens the HAL input so the first key-down only leaves standby.

`DailyRecycler` arms one inexact ~5 a.m. alarm to `release()`+reload the recognizer when idle — bounds native-heap fragmentation over long uptime while staying hot; re-armed in `onServiceConnected` so it survives reboots without a BOOT receiver.

FG promotion: `startForeground(MICROPHONE)` on DOWN, `stopForeground(REMOVE)` on UP. Notification visible only while recording.

**Order matters and is not cosmetic.** Android does not error when a background
app records — it hands back silence
([docs](https://developer.android.com/media/platform/sharing-audio-input)), and an
accessibility service with no UI on top is background for this purpose. Starting
`AudioRecord` before the FGS registers therefore ate the head of every utterance.
Promotion failure (usually a revoked battery-optimisation exemption) aborts the
hold and notifies, because the alternative is a dictation that records nothing
and says nothing. `MutterAudio` logs the head latency and `isClientSilenced()`.

The volume-down path cannot be exercised with `adb shell input` — injected key
events bypass accessibility key filtering.

AudioRecord: 16 kHz mono PCM int16, streamed in fixed **512-sample (32 ms) windows** (the Silero VAD window) via an `onWindow` callback. No length cap — a hold runs as long as the user talks. Source is `VOICE_RECOGNITION` (no AGC, no call-tuned noise suppressor), falling back to `MIC` if a device refuses it. One instance is reused for the life of the service; `prepare()` opens the input, `start()`/`stop()` bracket each hold.

### sherpa-onnx

AAR 1.13.6, fetched from k2-fsa GitHub releases by `scripts/fetch-libs.sh` (not JitPack).

Model: NVIDIA `parakeet-tdt-0.6b-v2` INT8, a NeMo transducer (`modelType = "nemo_transducer"`) — encoder 652 MB, decoder 7 MB, joiner 2 MB, tokens. Identity, sizes and SHA-256s live in `SttModel.kt`.

Measured on 250 LibriSpeech test-clean utterances cut to 3–8 s (one sentence at a time, as used), decode and PSS on an arm64 Android 15 device:

| model | WER | clean sentences | decode (7.4 s clip) | peak PSS |
|---|---|---|---|---|
| whisper distil-small.en int8 (until v0.6.0) | 4.06% | 67.6% | 612 ms | 657 MB |
| moonshine-base-en int8 | 3.28% | 71.2% | — | — |
| parakeet-tdt-110m int8 | 2.39% | 74.8% | 82 ms | 331 MB |
| **parakeet-tdt-0.6b-v2 int8** | **1.64%** | **83.2%** | **186 ms** | **819 MB** |

A transducer has no fixed encoder window, so a 4 s chunk costs 4 s of work — Whisper padded every chunk to 30 s, which is most of the speed difference.

CPU only. NNAPI is deprecated in Android 15 and falls back for these ops anyway; sherpa-onnx's QNN (Hexagon NPU) support ships no Android AAR — only Rockchip — and its QNN models are Moonshine and a streaming Nemotron, so the Snapdragon NPU is not reachable from here.

parakeet-tdt-110m is the fallback if 819 MB resident ever becomes a problem: still better than distil-small.en on every axis at 331 MB. Its int8 files are not on HuggingFace (the canonical repo is empty), so it would need the GitHub tarball or a hash-verified mirror.

### Segmentation (`VadSegmenter` + `AdaptiveEndpointer`)

Cuts exist for streaming — each one transcribes immediately, so text appears mid-hold. They were also a hard requirement under Whisper's fixed 30 s encoder window; the transducer removed that, and the ceiling now just bounds per-chunk latency. Each 512-sample window is scored by Silero VAD (`Vad.compute()` ≥ 0.5 = speech); a pure, unit-tested endpointer decides cuts:

- No cut until **4 s of speech** in the chunk (avoids fragments).
- Then cut on a silence gap ≥ a threshold that **ramps with total chunk length**: 500 ms (< 7 s) → 300 ms (< 15 s) → 200 ms floor.
- **Emergency cut at 25 s** of total chunk duration, unconditional — bounds chunk latency even with zero pauses.
- Counters reset after each cut; the next chunk re-earns the 4 s gate.

Buffering is ours (`compute()` never queues audio), so memory stays flat. If the VAD model is missing or a window is the wrong size, per-window RMS stands in and the 25 s emergency cut still bounds length — degraded, never a failure. `silero_vad.onnx` (643854 B) is fetched by `ModelDownloader` alongside the recognizer.

### Model download (first launch)

1. Check `getFilesDir()/models/<SttModel.DIR>/` — names and sizes only, so service connect stays fast.
2. If missing, fetch each file listed in `SttModel.ASSETS`, resuming via `Range`.
3. SHA-256 verify against the canonical k2-fsa release before putting it in place. A `200` answer to a `Range` request is never appended — that produced a size-correct, corrupt model.
4. Delete model directories that are no longer active.

Files come from the author's HuggingFace copy because GitHub ships one `.tar.bz2` and Android has no bzip2 decoder. The hash check is what makes that indirection safe.

Missing model → tappable notification on service connect. Silence there meant an update that changed models looked like dictation simply breaking.

### Transcript hygiene (no blacklist)

Every chunk reaching the engine carries VAD-confirmed speech, so the
transcript is trusted: deliberate short dictations ("okay", "thank you")
always type. `Sanitizer.collapseRepeats` bounds whisper's repeat-loop
pathology (same phrase ×N, any phrase, period ≤8 words) to one instance.
Degraded mode (VAD model missing): per-window RMS stands in for Silero, so a
silent hold still emits no chunks.

Never-drop: engine load/decode failure persists the chunk to
`filesDir/pending/*.wav` (`PendingAudio`) + notification. `SttEngine`
serializes transcribe/release on its monitor so the daily recycle or unbind
can't free the native recognizer mid-decode. `pending/` is pruned to the
newest 20 / 14 days on every save — it used to grow without bound.

### Text injection

Primary: **paste-and-restore.** Save clipboard once per hold, set to chunk,
`ACTION_PASTE` on the target (focused editable → refresh()-validated
DOWN-node → paste-action fallback). Paste refused → `ACTION_SET_TEXT` splice
at the cursor. Both fail → the chunk joins the hold's failed list; at end of
hold the failed text goes ON the clipboard (the saved clipboard is NOT
restored over it — that used to destroy the transcript) + a notification
shows it. If a new hold begins inside the 200 ms restore window, begin()
adopts the pending payload and cancels the late write.

Sanitize:
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

- Re-press while capturing, stale UP while idle: consumed no-op. A press
  while the previous hold is still *transcribing* starts a new hold
  immediately (FIFO keeps text in spoken order).
- Focus disappears mid-hold: keep recording until UP, re-resolve focus at injection.
- Focus moves mid-hold: inject wherever focus lands at UP.
- Vol-up while vol-down held: passes through.
- Vol-down in password field (TYPE_TEXT_VARIATION_PASSWORD): not consumed. Privacy guard.
- Apps that block AccessibilityService: vol-down passes through; PTT silently inactive.
- Short tap / brief utterance: flushed as the final chunk (no minimum); dropped only if it has no speech or the energy gate marks it silent.
- No max duration. Holds of any length stream in as ≤25 s chunks; the old 60 s cap and 30 s warning vibration are removed.
- Held in silence: no speech → no chunk emitted (emergency cuts produce silent chunks that the energy gate drops). No buzz, no text.
- Service killed by OS: Android auto-restarts accessibility services; battery exemption further reduces kill rate.
- Model download interrupted: resumable HTTP range; SHA-256 re-verified.
- Transcription failure (sherpa-onnx throws / OOM / model corruption): caught, chunk saved to `pending/*.wav`, notification, haptic. Audio never discarded.
- Both paste and ACTION_SET_TEXT fail: failed text placed on clipboard at end of hold (restore skipped) + notification, haptic.

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
│   │   │   ├── MutterAccessibilityService.kt   — key intercept, streaming pipeline, injection
│   │   │   ├── AudioRecorder.kt                — mic → 512-sample window callback (no cap)
│   │   │   ├── VadSegmenter.kt                 — Silero VAD + chunk buffering
│   │   │   ├── AdaptiveEndpointer.kt           — pure cut logic (4s gate, 500→300→200ms, 25s)
│   │   │   ├── DailyRecycler.kt                — ~5am alarm: recycle recognizer when idle
│   │   │   ├── WhisperEngine.kt                — sherpa-onnx wrapper
│   │   │   ├── EnergyGate.kt                   — RMS (degraded-VAD stand-in)
│   │   │   ├── PendingAudio.kt                 — failed-chunk WAV persistence
│   │   │   ├── TextInjector.kt                 — paste → SET_TEXT splice → clipboard+notif
│   │   │   ├── ModelDownloader.kt              — first-launch fetch (recognizer + VAD) + SHA-256
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

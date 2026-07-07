# Decisions — mac-native

Architectural only. Newest at top.

- **2026-07-06 — Minimal native daemon, not the vendored Handy/Tauri app (Peter).** Supersedes the root 2026-07-06 "Mac substrate = vendored Handy" decision. Handy's robustness lived entirely in two low-level choices, not in Rust or its GUI. So instead of carrying a 36 MB Tauri app + webview + overlay + settings UI to run invisibly, extract just those two choices into a ~1.9 MB headless daemon that behaves exactly like the old Python daemon. The GUI added latency (transcription overhead is unchanged — the ~1.2 s MLX floor is shared) and a recording overlay the user never wanted. Vendored `desktop/` superseded (retire after soak).

- **The two robustness choices copied (grounded in code, not vibes).**
  1. *fn tap:* `handy-keys` re-enables the CGEventTap every 100 ms and re-derives fn from OS flags every event. The Python daemon armed the tap once + blocked in `CFRunLoopRun`; nothing re-armed a tap macOS disabled → dropped fn-up.
  2. *audio teardown:* cpal `Stream::drop` is a clean stop→dispose that never re-enters the CoreAudio HAL lock; teardown is timeout-bounded regardless. PortAudio `Pa_CloseStream` re-entered the HAL lock → deadlock after sleep/wake → Python's only cure was `os._exit(1)` + respawn (unfixable in Python: leaking the wedged close-thread poisons the held HAL lock).

- **Reuse the `com.peter.mutter.app` bundle identity for TCC.** Accessibility can't be granted programmatically and TCC keys on bundle identity, not the binary. A minimal `LSUIElement` bundle + stable ad-hoc identifier lets Accessibility + Microphone grants survive every rebuild — no re-prompting on update.

- **Capture at device-native rate, resample to 16 kHz in software (`resample.rs`).** Deliberately the opposite of the Python daemon forcing the hardware clock to 16 kHz, which was its Bluetooth-codec-negotiation fragility. The whisper server assumes 16 kHz (`len/16000`), so the daemon owns the conversion.

- **ASR stays out-of-process (shared MLX whisper service).** The daemon decodes + resamples audio and hands 16 kHz mono PCM over a unix socket. One always-warm model shared by every consumer; the daemon never loads weights. Wire protocol is byte-for-byte the Python reference's.

# MUTTER — mac-native

Headless push-to-talk dictation daemon. Hold **fn**, speak, release → words typed at the cursor. Local Whisper via the shared MLX service. No window, no tray, no dock icon.

Replaces both the old Python daemon (`../mac/`) and the vendored Handy/Tauri app: same behavior as the Python daemon, but with the two architectural fixes that made it robust — and none of the GUI.

## Why this exists — the two decisions

The Python daemon's fragility was 100% its audio + event-tap layer. Two root causes, two fixes copied here (it is NOT "Rust made it robust" — it's these specific choices):

1. **fn trigger — assume the tap dies, heal it.** The `handy-keys` crate re-enables the CGEventTap every 100 ms and re-derives fn from the live OS flags on *every* event. The Python daemon armed the tap once and blocked in `CFRunLoopRun` — nothing re-armed it, so a tap macOS disabled under load/sleep silently dropped fn-up. → `Cargo.toml` (`handy-keys`), driven in `main.rs`.
2. **audio teardown — can't deadlock, and bounded anyway.** cpal's `Stream` drop is a clean stop→dispose that never re-enters the CoreAudio HAL lock; `recorder.rs` also bounds every teardown with a timeout. PortAudio's `Pa_CloseStream` re-entered the HAL lock and deadlocked after sleep/wake/device-change — the Python daemon's only escape was `os._exit(1)` + launchd respawn. → `recorder.rs`.

## Layout

| File | Role |
|---|---|
| `main.rs` | fn event loop + FIFO transcribe worker; wires it all together |
| `recorder.rs` | cpal capture on a worker thread; bounded teardown (**decision 2**) |
| `whisper.rs` | unix-socket client for the shared MLX whisper service |
| `resample.rs` | device-native rate → 16 kHz (whisper hardcodes /16000) |
| `frontmost.rs` | "is Screen Sharing frontmost?" via LaunchServices (`lsappinfo`) |
| `hallucination.rs` | drops whisper silence-boilerplate ("You", "Thank you.") before typing |
| `keycodes.rs` | Screen-Sharing keycode typing (Cmd+V crosses to the remote) |
| `inject.rs` | normal Unicode typing + clipboard fallback + mute-while-recording |

## Install

```sh
./install.sh          # build, assemble /Applications/MUTTER.app, load the LaunchAgent
./uninstall.sh        # stop + remove (add --purge to also drop logs)
```

Requires Rust (`rustup`) and the shared MLX whisper service running (`~/Documents/services/whisper/`).

### Permissions (first run, per machine)
- **Accessibility** — read the fn key + type. Grant manually: System Settings → Privacy & Security → Accessibility → enable **MUTTER**. `install.sh` opens the pane.
- **Microphone** — prompts automatically on the first fn-hold.

Both grants key on the bundle identifier `com.peter.mutter.app`. Microphone survives updates. Accessibility is additionally pinned to the exact binary hash (ad-hoc signing), so **every build from changed source needs one off/on toggle** of MUTTER in that pane. If the toggle doesn't take (row wedged after several binaries cycled): `tccutil reset Accessibility com.peter.mutter.app`, kickstart, toggle the fresh row.

## Why it lives in an .app bundle

macOS TCC grants attach to a bundle identity, not a bare binary, and Accessibility can't be granted programmatically. Wrapping the daemon in a minimal `LSUIElement` bundle (`Info.plist` + the binary — no GUI) is what lets the grants persist. launchd runs it with `KeepAlive/SuccessfulExit:false` = respawn on any crash.

## Troubleshooting

- **Nothing types on fn-hold** — check `~/Library/Logs/mutter/app.err.log`. `capturing:` on fn-down means the tap works. No line = Accessibility not granted (or the tap needs a reload: `launchctl kickstart -k gui/$(id -u)/com.peter.mutter.app`).
- **Don't test with synthetic fn events.** Fake CGEvents carry fake modifier flags and desync `handy-keys`' fn state (inverts the trigger). Real hardware fn self-corrects every event. Restart the daemon to clear a desync.
- **Offline smoke test** (verifies whisper client + resample on known speech):
  ```sh
  say --file-format=WAVE --data-format=LEF32@48000 -o /tmp/k.wav \
    "the quick brown fox jumps over the lazy dog"
  MUTTER_SMOKE_WAV=/tmp/k.wav cargo test --release -- --ignored smoke
  ```

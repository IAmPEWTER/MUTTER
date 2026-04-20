# MUTTER

Hold **fn** anywhere on macOS → speak → release → text appears at the
cursor. Local Whisper (mlx Metal). Clipboard untouched.

## One-time setup

### 1. Install Python deps

```
cd ~/Desktop/MUTTER
python3.11 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

### 2. Disable macOS built-in Dictation

System Settings → Keyboard → Dictation → **Off**.
(Otherwise fn fires Apple's built-in dictation at the same time.)

### 3. Install the LaunchAgent

```
cp com.peter.mutter.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.peter.mutter.plist
```

The daemon starts immediately and again at every login.

### 4. Grant permissions (macOS asks you twice, once)

The first time the daemon runs, macOS prompts for **Microphone** and
**Accessibility** access for the Python binary. Click Allow on both.

- Microphone prompt appears when the daemon opens the input stream.
- Accessibility prompt appears when the daemon creates its keyboard
  event tap. Until granted, `CGEventTapCreate` returns NULL and the
  daemon exits; launchd then auto-restarts it every 10 s, so the
  moment you click Allow, it comes back up for good.

Verify it's up:

```
cat /tmp/mutter.pid
tail /tmp/mutter.out.log
```

You should see `mutter: ready in ~10s  pid=...`.

## Test

1. Open Notes (or any text field).
2. Hold **fn**.
3. Say: "hello from mutter."
4. Release **fn**.
5. ~200 ms later the text appears.

## Controls

- **Hold fn** — record.
- **Release fn** — transcribe + type.
- Nothing else.

## Uninstall

```
launchctl unload ~/Library/LaunchAgents/com.peter.mutter.plist
rm ~/Library/LaunchAgents/com.peter.mutter.plist
rm -rf ~/Desktop/MUTTER
```

## Troubleshooting

- **Nothing happens on fn-down**: check `cat /tmp/mutter.pid` shows a
  live PID. If not, `tail /tmp/mutter.err.log` — most common cause is
  a missing Accessibility grant (log line: *"CGEventTapCreate
  returned NULL"*).
- **Press works but nothing typed**: pynput doesn't have
  Accessibility access. System Settings → Privacy & Security →
  Accessibility → check the Python binary.
- **Crash loop**: `tail /tmp/mutter.err.log`.

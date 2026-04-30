# MUTTER

Hold **fn** anywhere on macOS → speak → release → text appears at the
cursor. Local Whisper (mlx Metal). Clipboard untouched. Works through
macOS Screen Sharing.app too.

## Install

1. Put this folder somewhere persistent — `~/Desktop/MUTTER` is fine.
2. Double-click **install.command**. Terminal opens, runs the installer.
3. First launch downloads the Whisper model (~1.5 GB FP16 default) and
   compiles — takes ~60 s.
4. On first fn-hold, macOS prompts for **Microphone** and
   **Accessibility**. Click Allow on both.

Manual setup is also available — see the [legacy steps](#manual-install)
below.

## Prerequisites

- macOS 15+ (Sequoia or later) on Apple Silicon (M-series).
- `python3.11` on PATH. If missing: `brew install python@3.11`.

## Use

- **Hold fn** — record. System output is muted while held so music or
  video on this Mac can't bleed into the mic.
- **Release fn** — transcribe + type at cursor. Audio comes back on.
- That's all.

### Screen Sharing

Works through macOS Screen Sharing.app — hold fn while focused on the
screen-share window, dictation appears at the remote cursor. No
clipboard hijacking. Internally MUTTER swaps to real Quartz keycode
events for this path because pynput's typer mangles characters across
Apple's screen-share keystroke forwarder; see `mutter/daemon.py` for
the gory details.

## Choosing a Whisper model

The default is `large-v3-turbo` (FP16) — best accuracy. To override
per-machine, set `MUTTER_WHISPER_MODEL` in the LaunchAgent's
`EnvironmentVariables` block:

```xml
<key>EnvironmentVariables</key>
<dict>
    <key>MUTTER_WHISPER_MODEL</key>
    <string>large-v3-turbo-q4</string>
</dict>
```

Then `launchctl unload` + `launchctl load` the plist (or just reboot).

| Model | Disk | RSS | Per-call (7.5 s clip) | Notes |
|---|---|---|---|---|
| `large-v3-turbo` *(default)* | 1.5 GB | 1.78 GB | 1317 ms | Best accuracy. |
| `large-v3-turbo-q4` | 442 MB | 0.78 GB | 1266 ms | 4-bit quant. Indistinguishable on English speech. Use on RAM-tight machines. |

Any model tag valid for the active backend works — `mlx-community` repos
for mlx (default), `Systran`/`openai` repos for `faster-whisper`.

## Verify it's running

```
cat /tmp/mutter.pid          # live pid
tail /tmp/mutter.out.log     # should show "mutter: ready in ~10s"
```

## Troubleshooting

**Nothing happens on fn-hold.** Check `/tmp/mutter.err.log`. Most
common cause: Accessibility not granted. System Settings → Privacy &
Security → Accessibility → enable the python binary at
`<this-folder>/.venv/bin/python`.

**Log says "CGEventTapCreate returned NULL"** — same thing,
Accessibility is missing. Click Allow and launchd will restart the
daemon within 10 s.

**macOS Dictation conflicts.** If macOS's built-in Dictation is on,
it'll fire on fn alongside MUTTER. Turn it off: System Settings →
Keyboard → Dictation.

## Uninstall

Double-click **uninstall.command**. Removes the LaunchAgent but keeps
this folder and the model cache. Delete the folder and
`~/.cache/huggingface/hub/models--mlx-community--whisper-large-v3-turbo*`
to remove everything.

## Manual install

If you'd rather not run the installer script:

```
cd ~/Desktop/MUTTER
python3.11 -m venv .venv
.venv/bin/pip install -r requirements.txt
sed "s|__PKG_DIR__|$PWD|g" com.peter.mutter.plist.template \
    > ~/Library/LaunchAgents/com.peter.mutter.plist
launchctl load ~/Library/LaunchAgents/com.peter.mutter.plist
```

## Tests

```
.venv/bin/python -m pytest tests/
```

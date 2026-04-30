# MUTTER

Hold **fn** → speak → release → your words appear at the cursor.
Local Whisper. Private. Works through Screen Sharing.

**Requirements:** Apple Silicon Mac, macOS 15 (Sequoia) or newer.

## Install

Paste in Terminal:

```
curl -fsSL https://raw.githubusercontent.com/IAmPEWTER/MUTTER/main/install.sh | bash
```

The installer handles Python, dependencies, and the background service.
At the end it walks you through one or two permission clicks.

> Don't want to use Terminal? Download the
> [zip](https://github.com/IAmPEWTER/MUTTER/archive/refs/heads/main.zip),
> unzip to your Desktop, then **right-click `install.command` → Open**.

## Use

- **Hold fn** to record. System audio mutes so it can't bleed into the mic.
- **Release fn** to transcribe and type at the cursor.

## Uninstall

Double-click `uninstall.command` in the MUTTER folder, or run:

```
bash ~/Desktop/MUTTER/uninstall.command
```

## Trouble?

Almost always one of these:

- **Nothing happens on fn.** Re-run `install.command` and complete the
  Accessibility step it walks you through.
- **fn fires Apple's dictation too.** Turn off System Settings → Keyboard
  → Dictation.
- **Anything else:** check `tail /tmp/mutter.err.log`.

## Choosing a Whisper model

Default is `large-v3-turbo` (1.5 GB FP16, best accuracy). To use the
4-bit quant (440 MB, indistinguishable on English speech) on a tighter
machine, edit `~/Library/LaunchAgents/com.peter.mutter.plist` and add:

```xml
<key>EnvironmentVariables</key>
<dict>
    <key>MUTTER_WHISPER_MODEL</key>
    <string>large-v3-turbo-q4</string>
</dict>
```

Then `launchctl unload` + `launchctl load` the plist (or reboot).

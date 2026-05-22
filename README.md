<p align="center">
  <img src="docs/logo.png" alt="MUTTER" width="180" />
</p>

# MUTTER

Hold the **fn** (or **🌐**) key on your Mac, speak, release — your
words appear typed at the cursor. Whisper speech-recognition running
on your Mac, not in the cloud. Works through Screen Sharing.

> **STT runs in the whisper service** (`~/.whisper-service`, separate
> LaunchAgent). MUTTER's venv no longer carries whisper deps; the
> daemon talks to the service over a unix socket and the model is
> warm-loaded once machine-wide.
>
> **Android port:** hold **volume-down** in any text field on Android.
> Prebuilt APK + setup: [android/README.md](android/README.md).

## Will this work on my Mac?

You need:

- A Mac with an **Apple chip** (M1 or newer — any Mac sold from late
  2020 onwards). Click the Apple menu → "About This Mac." If "Chip"
  says "Apple M…" something, you're good.
- **macOS 15 (Sequoia) or newer.** Same screen, "macOS" line.

The installer also checks both and stops with a clear message if
either is missing.

## Install

1. Open **Terminal**: press `⌘ Space` to open Spotlight, type
   `terminal`, hit return. A black-or-white text window opens.
2. Click into the Terminal window, paste this line, hit return:

   ```
   curl -fsSL https://raw.githubusercontent.com/IAmPEWTER/MUTTER/main/install.sh | bash
   ```

3. Watch the Terminal output. The installer will tell you exactly
   what permission clicks to do — usually **one** (grant
   Accessibility), occasionally **two or three** if your Mac's
   Keyboard settings need a tweak first. It opens System Settings to
   the right pane for you each time.
4. Click **Allow** the first time you hold fn / 🌐 — that's the
   one-time Microphone prompt.

That's the whole install.

> Prefer not to use Terminal? Download the
> [zip](https://github.com/IAmPEWTER/MUTTER/archive/refs/heads/main.zip),
> double-click to unzip, drag the **MUTTER** folder to your Desktop,
> then **right-click `install.command` → Open**. Click "Open" again in
> the warning dialog. Same as above from there.

## Use

The fn / 🌐 key is bottom-left of the keyboard. Newer Macs print 🌐
on it; older ones say "fn." Same key.

- **Hold it** to record. Your Mac mutes its own audio so music or
  videos can't bleed into the mic.
- **Release it** to transcribe and type at the cursor.

That's all. No app to keep open — it runs quietly in the background
and starts again every time you log in.

## Uninstall

Double-click `uninstall.command` in the MUTTER folder. Or:

```
bash ~/Desktop/MUTTER/uninstall.command
```

## Trouble?

- **Holding fn does nothing.** Re-run `install.command` and complete
  the Accessibility step it walks you through.
- **fn pops up the emoji picker.** System Settings → Keyboard → set
  "Press 🌐 key to" to **Do Nothing**.
- **fn also fires Apple's dictation.** System Settings → Keyboard →
  Dictation → toggle off.
- **Anything else:** check `tail /tmp/mutter.err.log` for clues.

## For tinkerers: switching the Whisper model

The model is owned by the whisper service, not MUTTER. Edit the
primary-model env var in the service's plist at
`~/.whisper-service/` (look for the `*_WHISPER_MODEL` key — value is
a full HF repo id like `mlx-community/whisper-large-v3-turbo-q4`),
then reload that plist. MUTTER picks up whatever the service has
warm.

To pin MUTTER to a specific warm model regardless of the service
primary, set `MUTTER_WHISPER_MODEL` (also a full HF repo id) in
MUTTER's plist. Must be one the service has loaded.

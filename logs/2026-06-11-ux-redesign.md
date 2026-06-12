# 2026-06-11 — Android UX redesign (v0.6.0)

Both screens (setup wizard + settings) ported to Margo design language; new vector launcher icon (Opus subagent drew it, 2 iterations — v1 was illegible mush at 26 units wide, rejected on render review).

## Empirical findings
- `fitsSystemWindows="true"` REPLACES the view's own padding with inset values — 22dp gutters silently became 0. Symptom: text hugging screen edge, switch pill "clipped" (its right cap was past the screen edge). Padding must live one level below the inset-consuming root.
- SwitchCompat with custom track/thumb drawables kept clipping the thumb past the track's right cap even with track=minWidth=2×thumb all agreeing. Gave up fighting its metrics; ToggleButton text pill (Margo ConvoToggle spec) is deterministic and more on-language.
- Release pipeline fact: shipped asset is the *debug-signed* `app-debug.apk` (package `com.peter.mutter.debug`) — verified v0.6.0 cert SHA-256 == v0.5.0 cert before uploading, so the self-updater accepts it.

## Verified
- Emulator (mutter_test, API 35, 420dpi): both screens screenshot-reviewed at every iteration; toggle flips ON↔OFF and persists; launcher icon whole + centered in masked circle in app drawer.
- Icon legibility gate: 96px render reads U-T-T-E-R letter-by-letter.
- 35 unit tests pass. Released v0.6.0 (versionCode 6) to IAmPEWTER/mutter-releases with latest.json.

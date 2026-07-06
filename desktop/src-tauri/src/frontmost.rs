//! Frontmost-application detection (macOS only).
//!
//! Feeds the Screen Sharing keycode-injection path in `paste_keycodes.rs`
//! (see that module's doc comment for the full why): Cmd+V inside macOS
//! Screen Sharing reaches the REMOTE machine's clipboard, so paste has to
//! switch to typed keycodes whenever Screen Sharing is frontmost.
//!
//! Ported from `mac/mutter/daemon.py`'s `_frontmost_is_screen_sharing`,
//! which deliberately avoids `NSWorkspace` there: that daemon's main
//! thread only sleeps and never pumps a `CFRunLoop`, so `NSWorkspace`'s
//! frontmost-app cache goes stale and it falls back to the Accessibility
//! API + libproc instead. Handy's Tauri main thread already runs a live
//! Cocoa run loop continuously, so that staleness problem doesn't apply
//! here — `NSWorkspace.frontmostApplication` stays fresh, and is the
//! simpler primitive `objc2-app-kit` already gives us for free (it's
//! already in the dependency graph via enigo/arboard/global-hotkey).

use objc2_app_kit::NSWorkspace;

/// Bundle directory path of the frontmost app (e.g.
/// `/System/Applications/Utilities/Screen Sharing.app`), or `None` if the
/// frontmost app or its bundle URL can't be resolved.
pub fn frontmost_app_bundle_path() -> Option<String> {
    let app = NSWorkspace::sharedWorkspace().frontmostApplication()?;
    let url = app.bundleURL()?;
    Some(url.path()?.to_string())
}

/// Bundle identifier of the frontmost app (e.g. `com.apple.ScreenSharing`),
/// or `None` if unavailable. Not currently consumed by `is_screen_sharing()`
/// (which matches on bundle path, like daemon.py) — exposed as a companion
/// accessor per the frontmost-detection API surface (spec R15).
#[allow(dead_code)]
pub fn frontmost_app_bundle_id() -> Option<String> {
    let app = NSWorkspace::sharedWorkspace().frontmostApplication()?;
    Some(app.bundleIdentifier()?.to_string())
}

/// True when macOS Screen Sharing is the frontmost app.
///
/// Matches daemon.py's exact check: a substring match on the bundle path
/// (`"Screen Sharing.app" in path`) rather than a hardcoded bundle
/// identifier — robust to Apple relocating the app between OS versions.
pub fn is_screen_sharing() -> bool {
    frontmost_app_bundle_path()
        .map(|path| path.contains("Screen Sharing.app"))
        .unwrap_or(false)
}

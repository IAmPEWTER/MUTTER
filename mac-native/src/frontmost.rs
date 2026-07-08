//! Which app currently has focus — needed only to answer "is Screen Sharing
//! frontmost?", which selects the injection path (keycodes vs Unicode text).
//!
//! The old Python daemon answered this with the Accessibility API's
//! system-wide "AXFocusedApplication". On this macOS (Darwin 25) that query
//! fails unconditionally from this process shape — kAXErrorCannotComplete
//! (-25204) on every call, AX-trusted or not, with or without a pumped
//! CFRunLoop (probed empirically 2026-07-08). NSWorkspace is no alternative:
//! its `frontmostApplication` cache only refreshes when the main run loop
//! pumps, and this daemon's main thread blocks in the hotkey loop.
//!
//! LaunchServices still answers in real time, so we ask it via `lsappinfo` —
//! one short-lived subprocess per dictation, the same idiom as the osascript
//! mute toggle. It also measures app *activation* (which decides where
//! keystrokes land), not window z-order, which can disagree.

use std::process::Command;

/// True when the frontmost (active) app is Screen Sharing. Any failure →
/// `false`, so the caller falls back to the normal Unicode-text path.
pub fn is_screen_sharing() -> bool {
    frontmost_bundle_path().is_some_and(|p| p.contains("Screen Sharing.app"))
}

/// Bundle path of the active app per LaunchServices. Output looks like
/// `"LSBundlePath"="/System/Applications/Utilities/Screen Sharing.app"`;
/// a substring check is all the caller needs, so it is not parsed further.
fn frontmost_bundle_path() -> Option<String> {
    let asn = lsappinfo(&["front"])?;
    let asn = asn.trim();
    if asn.is_empty() {
        return None;
    }
    lsappinfo(&["info", "-only", "bundlepath", asn])
}

fn lsappinfo(args: &[&str]) -> Option<String> {
    let out = Command::new("lsappinfo").args(args).output().ok()?;
    if !out.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&out.stdout).into_owned())
}

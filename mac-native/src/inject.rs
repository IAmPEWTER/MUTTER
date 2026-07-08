//! Deliver transcribed text to the focused app, plus the mute-while-recording
//! helper. Ported from the old Python daemon's `_inject` / `_sanitize` /
//! `_copy_to_clipboard` / `_set_system_muted`.
//!
//! Two typing paths, same as the old daemon:
//!   - Normal: enigo's `text()` posts Unicode key events (NOT a clipboard
//!     paste) — Cmd+V would clobber the user's clipboard and, inside Screen
//!     Sharing, paste the *remote* clipboard.
//!   - Screen Sharing frontmost: real hardware keycodes (`keycodes::type_text`)
//!     — the only channel Screen Sharing's keystroke forwarder delivers
//!     reliably.
//!
//! On any typing failure the text goes to the clipboard as a last resort so a
//! dictation is never lost.

use std::process::Command;

use enigo::{Enigo, Keyboard, Settings};

use crate::frontmost;
use crate::keycodes;

/// Sanitize, prepend a leading space, and type `text` at the cursor. Never
/// panics; on failure the text lands on the clipboard.
pub fn inject(text: &str) {
    let cleaned = sanitize(text);
    if cleaned.is_empty() {
        return;
    }
    // Leading space so dictation doesn't crash into whatever precedes it.
    let payload = format!(" {cleaned}");

    let result = if frontmost::is_screen_sharing() {
        // Logged so a garbled-injection report can be pinned to a path.
        log::info!("inject: Screen Sharing frontmost — keycode path");
        keycodes::type_text(&payload)
    } else {
        type_unicode(&payload)
    };

    if let Err(e) = result {
        log::error!("inject failed ({e}); copying to clipboard instead");
        copy_to_clipboard(&cleaned);
    }
}

/// Normal path: post Unicode key events for the whole string via enigo.
fn type_unicode(text: &str) -> Result<(), String> {
    let mut enigo =
        Enigo::new(&Settings::default()).map_err(|e| format!("init enigo: {e}"))?;
    enigo.text(text).map_err(|e| format!("enigo text: {e}"))
}

/// Remove newlines (so a dictation can't accidentally submit a prompt) and
/// collapse runs of spaces. Mirrors the old daemon's `_sanitize`.
fn sanitize(text: &str) -> String {
    if text.is_empty() {
        return String::new();
    }
    let mut cleaned = text.replace("\r\n", " ").replace(['\n', '\r'], " ");
    while cleaned.contains("  ") {
        cleaned = cleaned.replace("  ", " ");
    }
    cleaned.trim().to_string()
}

/// Last-resort delivery when typing fails: never lose a dictation.
fn copy_to_clipboard(text: &str) {
    use std::io::Write;
    let child = Command::new("pbcopy")
        .stdin(std::process::Stdio::piped())
        .spawn();
    match child {
        Ok(mut c) => {
            if let Some(stdin) = c.stdin.take() {
                let mut stdin = stdin;
                let _ = stdin.write_all(text.as_bytes());
            }
            let _ = c.wait();
        }
        Err(e) => log::error!("pbcopy failed: {e}"),
    }
}

/// Toggle macOS output mute via osascript. Silences music/video on this machine
/// while fn is held so it can't bleed into the mic. Prior mute state is not
/// preserved — every release unmutes, matching the old daemon.
///
/// Non-blocking (the ~9 ms osascript runs on a detached thread so it never
/// delays record start), but the thread `wait()`s the child so we don't leak a
/// zombie process on every fn press/release across the daemon's long lifetime.
pub fn set_system_muted(muted: bool) {
    let state = if muted { "true" } else { "false" };
    let arg = format!("set volume output muted {state}");
    std::thread::spawn(move || {
        let _ = Command::new("osascript").arg("-e").arg(arg).status();
    });
}

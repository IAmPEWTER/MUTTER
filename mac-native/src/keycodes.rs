//! Real hardware-keycode text injection for macOS Screen Sharing.
//!
//! Handy's normal paste path (`clipboard.rs`) writes the clipboard and
//! sends Cmd+V. Inside macOS Screen Sharing, Cmd+V is forwarded to the
//! REMOTE machine and pastes the REMOTE's clipboard — our text never
//! arrives. `CGEventKeyboardSetUnicodeString`-style events (what enigo's
//! `text()`/`fast_text()` posts for `PasteMethod::Direct`) don't cross
//! correctly either. The only channel proven reliable through Screen
//! Sharing's keystroke forwarder is real keycode+modifier `CGEvent`s — the
//! same channel that already forwards Cmd+Tab, arrow keys, and fn itself.
//!
//! Ported from the working Python daemon (`mac/mutter/daemon.py`,
//! `_ensure_keycode_map` + `_type_via_quartz_keycodes`, roughly lines
//! 240-315). Same algorithm: build a `char -> (keycode, needs_shift)` map
//! by asking `UCKeyTranslate` what every (keycode, modifier) pair on the
//! *current* keyboard layout produces, then type each character as a real
//! keycode down/up pair, bracketing runs of shifted characters with an
//! explicit physical Shift key down/up (not just the event's shift flag —
//! Screen Sharing's forwarder drops a flag-only modifier and `?` arrives
//! as `/`), at an 8 ms floor between events.
//!
//! Posting: enigo's macOS backend already posts real `CGEventCreateKeyboardEvent`
//! keycode events at `CGEventTapLocation::HID` for `Key::Other(keycode)`/
//! `Key::Shift` (verified by reading `enigo-0.6.1`'s `macos_impl.rs`,
//! `raw()`); that's the exact primitive daemon.py hand-rolls via Quartz.
//! Reusing it means zero new CGEvent-posting code and zero new
//! dependencies for that half of this file — enigo 0.6.1 is already a
//! project dependency.
//!
//! The layout scan itself (`UCKeyTranslate`/`TISCopyCurrentKeyboardInputSource`)
//! isn't exposed by enigo (it's a private helper used only for its
//! `Key::Unicode`, which doesn't handle shift), so this file talks to
//! Carbon/CoreFoundation directly via `extern "C"` + framework linking —
//! the same symbols pynput's ctypes loads from `Carbon.framework` in
//! daemon.py. This needs no new crate dependency either: framework
//! linking is a native `rustc`/linker feature.

use enigo::{Direction, Enigo, Key, Keyboard, Settings};
use std::collections::HashMap;
use std::ffi::c_void;
use std::sync::OnceLock;
use std::time::Duration;

// ---------------------------------------------------------------------
// Carbon / CoreFoundation FFI — current-keyboard-layout keycode lookup.
// ---------------------------------------------------------------------

#[link(name = "Carbon", kind = "framework")]
extern "C" {
    fn TISCopyCurrentKeyboardInputSource() -> *const c_void;
    fn TISCopyCurrentASCIICapableKeyboardLayoutInputSource() -> *const c_void;
    fn TISGetInputSourceProperty(
        input_source: *const c_void,
        property_key: *const c_void,
    ) -> *const c_void;
    fn LMGetKbdType() -> u32;
    #[allow(non_snake_case)]
    fn UCKeyTranslate(
        key_layout_ptr: *const c_void,
        virtual_key_code: u16,
        key_action: u16,
        modifier_key_state: u32,
        keyboard_type: u32,
        key_translate_options: u32,
        dead_key_state: *mut u32,
        max_string_length: u32,
        actual_string_length: *mut u32,
        unicode_string: *mut u16,
    ) -> i32;

    static kTISPropertyUnicodeKeyLayoutData: *const c_void;
}

#[link(name = "CoreFoundation", kind = "framework")]
extern "C" {
    fn CFRelease(cf: *const c_void);
    fn CFDataGetBytePtr(the_data: *const c_void) -> *const u8;
}

/// kUCKeyActionDisplay — translate for on-screen display, not full event
/// simulation semantics (matches daemon.py / pynput).
const UC_KEY_ACTION_DISPLAY: u16 = 3;
/// kUCKeyTranslateNoDeadKeysBit, passed as a literal options value (not
/// shifted into a bit mask) — matches daemon.py/pynput exactly.
const UC_KEY_TRANSLATE_NO_DEAD_KEYS_BIT: u32 = 0;
/// UCKeyTranslate's modifier_state is the high byte of the classic
/// EventRecord.modifiers word: shiftKey = 0x200, top byte = 2.
const UC_NO_MOD: u32 = 0;
const UC_SHIFT: u32 = 2;

/// 8 ms inter-keystroke delay through Screen Sharing — measured floor for
/// reliable delivery; daemon.py found 5 ms drops events under burst.
const TYPE_INTER_CHAR_DELAY: Duration = Duration::from_millis(8);

/// `char -> (keycode, needs_shift)` for the current keyboard layout.
///
/// Cached for the process lifetime via `OnceLock` (same pattern as
/// `managers/transcription.rs`'s `GPU_DEVICES` / `portable.rs`'s
/// `PORTABLE_DATA_DIR`): built once, on first use, never invalidated.
/// daemon.py does the same (`_KEYCODE_MAP` is a plain module-global,
/// never rebuilt). Tradeoff: a keyboard-layout switch mid-run would need
/// a live `TISNotification` listener to invalidate this and isn't worth
/// the complexity for "user swaps keyboard layout while mid-dictation" —
/// worst case is a rebuild-on-next-launch, same ceiling daemon.py accepts.
static KEYCODE_MAP: OnceLock<HashMap<char, (u16, bool)>> = OnceLock::new();

fn keycode_map() -> &'static HashMap<char, (u16, bool)> {
    KEYCODE_MAP.get_or_init(build_keycode_map)
}

/// Resolve `TISGetInputSourceProperty(source, kTISPropertyUnicodeKeyLayoutData)`
/// to a raw pointer to the source's `UCKeyboardLayout` bytes, or `None` if
/// `source` is null or carries no Unicode layout data (e.g. some IMEs).
///
/// # Safety
/// `source` must be a valid `TISInputSourceRef` (or null).
unsafe fn layout_ptr_from_source(source: *const c_void) -> Option<*const c_void> {
    if source.is_null() {
        return None;
    }
    // TISGetInputSourceProperty is a "Get" accessor: unretained, don't release.
    let layout_data = TISGetInputSourceProperty(source, kTISPropertyUnicodeKeyLayoutData);
    if layout_data.is_null() {
        return None;
    }
    let bytes = CFDataGetBytePtr(layout_data);
    if bytes.is_null() {
        None
    } else {
        Some(bytes as *const c_void)
    }
}

/// `UCKeyTranslate` one (keycode, modifier) pair to the single `char` it
/// produces on the given layout, or `None` if it fails or yields anything
/// other than exactly one character (matches daemon.py's `len(ch) != 1`
/// skip).
///
/// # Safety
/// `layout_ptr` must point at a live `UCKeyboardLayout` (i.e. the
/// underlying `TISInputSourceRef` it came from must still be alive).
unsafe fn translate(layout_ptr: *const c_void, keycode: u16, modifier_state: u32, keyboard_type: u32) -> Option<char> {
    let mut dead_key_state: u32 = 0;
    let mut actual_length: u32 = 0;
    let mut buf = [0u16; 4];
    let status = UCKeyTranslate(
        layout_ptr,
        keycode,
        UC_KEY_ACTION_DISPLAY,
        modifier_state,
        keyboard_type,
        UC_KEY_TRANSLATE_NO_DEAD_KEYS_BIT,
        &mut dead_key_state,
        buf.len() as u32,
        &mut actual_length,
        buf.as_mut_ptr(),
    );
    if status != 0 || actual_length == 0 {
        return None;
    }
    let s = String::from_utf16(&buf[..actual_length as usize]).ok()?;
    let mut chars = s.chars();
    let ch = chars.next()?;
    if chars.next().is_some() {
        return None;
    }
    Some(ch)
}

/// Build the `char -> (keycode, needs_shift)` map for the layout that's
/// current right now. Mirrors daemon.py's `_ensure_keycode_map`: try
/// `TISCopyCurrentKeyboardInputSource` first, fall back to
/// `TISCopyCurrentASCIICapableKeyboardLayoutInputSource` if that source
/// carries no Unicode layout data (e.g. a non-Latin IME is active);
/// iterate keycodes 0..127 under no-modifier then shift, first-writer-wins
/// per char so the lowest keycode / unshifted form is preferred.
fn build_keycode_map() -> HashMap<char, (u16, bool)> {
    let mut map = HashMap::new();

    unsafe {
        let mut source = TISCopyCurrentKeyboardInputSource();
        let mut layout_ptr = layout_ptr_from_source(source);
        if layout_ptr.is_none() {
            if !source.is_null() {
                CFRelease(source);
            }
            source = TISCopyCurrentASCIICapableKeyboardLayoutInputSource();
            layout_ptr = layout_ptr_from_source(source);
        }

        let Some(layout_ptr) = layout_ptr else {
            if !source.is_null() {
                CFRelease(source);
            }
            log::warn!("mutter: no keyboard layout data available — Screen Sharing keycode typing will type nothing");
            return map;
        };

        let keyboard_type = LMGetKbdType();
        for keycode in 0u16..128 {
            for (modifier_state, needs_shift) in [(UC_NO_MOD, false), (UC_SHIFT, true)] {
                if let Some(ch) = translate(layout_ptr, keycode, modifier_state, keyboard_type) {
                    map.entry(ch).or_insert((keycode, needs_shift));
                }
            }
        }

        if !source.is_null() {
            CFRelease(source);
        }
    }

    // The physical Return/Enter key translates to carriage return ('\r')
    // via UCKeyTranslate, not '\n'. daemon.py never needs a '\n' entry
    // because its sanitizer strips newlines before any typing path runs;
    // this port has no such upstream guarantee, so alias '\n' to the same
    // key as '\r' when present. Intentional addition beyond daemon.py.
    if let Some(&ret) = map.get(&'\r') {
        map.entry('\n').or_insert(ret);
    }

    map
}

/// Common Whisper-produced non-ASCII punctuation normalized to ASCII so
/// the layout's keycode map can type it. Anything else that isn't in the
/// map is silently skipped (rare for English dictation). Mirrors
/// daemon.py's `_ASCII_FALLBACK` / `_normalize_for_typing`.
fn normalize_for_typing(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for ch in text.chars() {
        match ch {
            '\u{2018}' | '\u{2019}' => out.push('\''), // smart single quotes
            '\u{201C}' | '\u{201D}' => out.push('"'),  // smart double quotes
            '\u{2013}' | '\u{2014}' => out.push('-'),  // en-dash, em-dash
            '\u{2026}' => out.push_str("..."),         // ellipsis
            other => out.push(other),
        }
    }
    out
}

/// Type `text` as real hardware keycode events, char by char, at an 8 ms
/// floor. Brackets runs of shifted characters with explicit physical
/// Shift key down/up (via enigo's `Key::Shift`) rather than only setting
/// the event's shift flag — Screen Sharing's keystroke forwarder drops a
/// flag-only modifier. Unmappable characters are silently skipped.
///
/// Mirrors daemon.py's `_type_via_quartz_keycodes` exactly, including
/// event ordering: no delay between a character's down/up pair, only
/// after the pair (and after a shift press/release) completes.
pub fn type_text(text: &str) -> Result<(), String> {
    let map = keycode_map();
    if map.is_empty() {
        return Err("mutter: keyboard layout keycode map unavailable".to_string());
    }

    let mut enigo =
        Enigo::new(&Settings::default()).map_err(|e| format!("Failed to init Enigo for keycode typing: {e}"))?;

    let mut shift_held = false;
    for ch in normalize_for_typing(text).chars() {
        let Some(&(keycode, needs_shift)) = map.get(&ch) else {
            continue; // silent skip — matches daemon.py
        };

        if needs_shift && !shift_held {
            enigo
                .key(Key::Shift, Direction::Press)
                .map_err(|e| format!("Failed to press Shift: {e}"))?;
            shift_held = true;
            std::thread::sleep(TYPE_INTER_CHAR_DELAY);
        } else if !needs_shift && shift_held {
            enigo
                .key(Key::Shift, Direction::Release)
                .map_err(|e| format!("Failed to release Shift: {e}"))?;
            shift_held = false;
            std::thread::sleep(TYPE_INTER_CHAR_DELAY);
        }

        enigo
            .key(Key::Other(keycode as u32), Direction::Click)
            .map_err(|e| format!("Failed to type char {ch:?} (keycode {keycode}): {e}"))?;
        std::thread::sleep(TYPE_INTER_CHAR_DELAY);
    }

    if shift_held {
        enigo
            .key(Key::Shift, Direction::Release)
            .map_err(|e| format!("Failed to release Shift: {e}"))?;
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn map_covers_lowercase_letters() {
        let map = keycode_map();
        for c in 'a'..='z' {
            assert!(map.contains_key(&c), "missing keycode for {c:?}");
            assert!(!map[&c].1, "{c:?} should not need shift");
        }
    }

    #[test]
    fn map_covers_digits() {
        let map = keycode_map();
        for c in '0'..='9' {
            assert!(map.contains_key(&c), "missing keycode for {c:?}");
        }
    }

    #[test]
    fn map_covers_space_and_newline() {
        let map = keycode_map();
        assert!(map.contains_key(&' '), "missing keycode for space");
        assert!(map.contains_key(&'\n'), "missing keycode for newline");
    }

    #[test]
    fn map_covers_common_punctuation() {
        let map = keycode_map();
        for c in ['.', ',', '/', '-', '\'', ';', '=', '[', ']'] {
            assert!(map.contains_key(&c), "missing keycode for {c:?}");
        }
    }

    #[test]
    fn uppercase_maps_to_shift_plus_same_physical_key() {
        let map = keycode_map();
        let (lower_kc, lower_shift) = map[&'a'];
        let (upper_kc, upper_shift) = map[&'A'];
        assert!(!lower_shift);
        assert!(upper_shift, "uppercase letter should need shift");
        assert_eq!(
            lower_kc, upper_kc,
            "uppercase and lowercase should share the same physical key"
        );
    }

    #[test]
    fn normalize_replaces_smart_punctuation() {
        assert_eq!(normalize_for_typing("\u{2018}hi\u{2019}"), "'hi'");
        assert_eq!(normalize_for_typing("\u{201C}hi\u{201D}"), "\"hi\"");
        assert_eq!(normalize_for_typing("a\u{2013}b\u{2014}c"), "a-b-c");
        assert_eq!(normalize_for_typing("wait\u{2026}"), "wait...");
    }
}

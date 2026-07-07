//! Which app currently has focus — needed only to answer "is Screen Sharing
//! frontmost?", which selects the injection path (keycodes vs Unicode text).
//!
//! Ported from the old Python daemon's `_frontmost_is_screen_sharing`. The
//! subtlety it documents: AppKit's `NSWorkspace.frontmostApplication` only
//! refreshes when the main run loop pumps events in a common mode, and this
//! daemon's main thread blocks in the hotkey loop — it never pumps one. So its
//! cache would freeze at startup state. The Accessibility API + libproc are
//! both real-time and run-loop-free, so we use them directly.
//!
//! Requires the Accessibility permission the daemon already holds for enigo.

use std::ffi::CString;
use std::os::raw::{c_char, c_int};
use std::ptr;

type CFTypeRef = *const std::ffi::c_void;
type CFStringRef = *const std::ffi::c_void;
type AXUIElementRef = *const std::ffi::c_void;
type AXError = i32;

#[allow(non_upper_case_globals)]
const kCFStringEncodingUTF8: u32 = 0x0800_0100;

#[link(name = "CoreFoundation", kind = "framework")]
extern "C" {
    fn CFStringCreateWithCString(
        alloc: CFTypeRef,
        c_str: *const c_char,
        encoding: u32,
    ) -> CFStringRef;
    fn CFRelease(cf: CFTypeRef);
}

#[link(name = "ApplicationServices", kind = "framework")]
extern "C" {
    fn AXUIElementCreateSystemWide() -> AXUIElementRef;
    fn AXUIElementCopyAttributeValue(
        element: AXUIElementRef,
        attribute: CFStringRef,
        value: *mut CFTypeRef,
    ) -> AXError;
    fn AXUIElementGetPid(element: AXUIElementRef, pid: *mut i32) -> AXError;
}

extern "C" {
    // libproc, part of libSystem — no explicit link needed.
    fn proc_pidpath(pid: c_int, buffer: *mut std::ffi::c_void, buffersize: u32) -> c_int;
}

/// True when the focused app's executable lives inside `Screen Sharing.app`.
/// Any failure (no focus, permission denied, path unreadable) → `false`, so the
/// caller falls back to the normal Unicode-text injection path.
pub fn is_screen_sharing() -> bool {
    match focused_app_path() {
        Some(path) => path.contains("Screen Sharing.app"),
        None => false,
    }
}

fn focused_app_path() -> Option<String> {
    let pid = focused_app_pid()?;
    process_path(pid)
}

fn focused_app_pid() -> Option<i32> {
    unsafe {
        let system_wide = AXUIElementCreateSystemWide();
        if system_wide.is_null() {
            return None;
        }
        let attr = cfstring("AXFocusedApplication");
        if attr.is_null() {
            CFRelease(system_wide);
            return None;
        }
        let mut focused: CFTypeRef = ptr::null();
        let err = AXUIElementCopyAttributeValue(system_wide, attr, &mut focused);
        CFRelease(attr);
        CFRelease(system_wide);
        if err != 0 || focused.is_null() {
            return None;
        }
        let mut pid: i32 = 0;
        let err = AXUIElementGetPid(focused, &mut pid);
        CFRelease(focused);
        if err != 0 {
            return None;
        }
        Some(pid)
    }
}

fn process_path(pid: i32) -> Option<String> {
    let mut buf = vec![0u8; 4096];
    let n = unsafe { proc_pidpath(pid, buf.as_mut_ptr() as *mut std::ffi::c_void, buf.len() as u32) };
    if n <= 0 {
        return None;
    }
    buf.truncate(n as usize);
    Some(String::from_utf8_lossy(&buf).into_owned())
}

/// Create a +1-retained CFString from a Rust &str (caller must `CFRelease`).
unsafe fn cfstring(s: &str) -> CFStringRef {
    let c = match CString::new(s) {
        Ok(c) => c,
        Err(_) => return ptr::null(),
    };
    CFStringCreateWithCString(ptr::null(), c.as_ptr(), kCFStringEncodingUTF8)
}

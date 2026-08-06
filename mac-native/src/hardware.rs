//! Is the fn key *physically* down on this Mac's own keyboard?
//!
//! Screen Sharing forwards the remote user's keystrokes as CGEvents posted by
//! `ScreensharingAgent`. They are indistinguishable from a local press by
//! keycode — measured live 2026-08-05, a forwarded fn arrives as
//! `keycode=63, fn_flag=1`, through the same `kCGSessionEventTap` the Python
//! daemon used, which is why its `keycode != 63` filter never blocked them
//! either. Both daemons record on every remote fn-hold; only the Python one
//! threw the audio away (R4), which is what kept it invisible.
//!
//! `CGEventSourceFlagsState(kCGEventSourceStateHIDSystemState)` reports the
//! *hardware* modifier state, and a forwarded press does not move it: over a
//! 3.2 s forwarded fn-hold the HID fn bit showed **zero** transitions while the
//! tap saw a clean down/up pair. `daemon.py:534` already trusted this same API
//! to heal a missed fn-up.
//!
//! So a Mac being driven remotely stops dictating from its own microphone,
//! which is correct — nobody is at that microphone. The user's own machine
//! keeps typing into the session over the Screen Sharing keycode path
//! (`keycodes.rs`), exactly as before.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

/// `kCGEventSourceStateHIDSystemState`.
const HID_SYSTEM_STATE: u32 = 1;

/// `kCGEventFlagMaskSecondaryFn`.
const MASK_SECONDARY_FN: u64 = 0x0080_0000;

/// How often the watcher samples hardware state during a hold. A real hold is
/// at least `MIN_CLIP_SAMPLES` (0.3 s) long, so this gets ~30 samples — the
/// decision never rests on catching a single instant.
const POLL: Duration = Duration::from_millis(10);

#[link(name = "CoreGraphics", kind = "framework")]
extern "C" {
    fn CGEventSourceFlagsState(state_id: u32) -> u64;
}

/// True when the fn key is physically held on this Mac's keyboard right now.
pub fn fn_is_physically_down() -> bool {
    // Safe: a plain FFI read of global HID state, no arguments to invalidate.
    unsafe { CGEventSourceFlagsState(HID_SYSTEM_STATE) & MASK_SECONDARY_FN != 0 }
}

/// Watches hardware fn state for the duration of one hold.
///
/// Sampling across the whole hold rather than once at key-down means a lagging
/// or momentarily-missed HID read can't misclassify a genuine local press — the
/// answer is "was fn *ever* physically down", which for a real hold is true for
/// its entire duration and for a forwarded one is never true.
pub struct Watch {
    seen: Arc<AtomicBool>,
    stop: Arc<AtomicBool>,
}

impl Watch {
    /// Begin watching. Cheap: one thread that exits as soon as it has its
    /// answer, or when `finish()` is called.
    pub fn start() -> Self {
        let seen = Arc::new(AtomicBool::new(fn_is_physically_down()));
        let stop = Arc::new(AtomicBool::new(false));
        if !seen.load(Ordering::Relaxed) {
            let seen_w = Arc::clone(&seen);
            let stop_w = Arc::clone(&stop);
            thread::spawn(move || {
                while !stop_w.load(Ordering::Relaxed) {
                    if fn_is_physically_down() {
                        seen_w.store(true, Ordering::Relaxed);
                        return;
                    }
                    thread::sleep(POLL);
                }
            });
        }
        Self { seen, stop }
    }

    /// Stop watching. True if fn was physically down at any point during the
    /// hold — i.e. this dictation came from this Mac's own keyboard.
    pub fn finish(self) -> bool {
        self.stop.store(true, Ordering::Relaxed);
        self.seen.load(Ordering::Relaxed)
    }
}

impl Drop for Watch {
    /// A press whose release never arrives (or is superseded by the next press)
    /// would otherwise leave its poller running for the life of the daemon.
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The FFI must not trap, and with no key held it must read false. (CI and
    /// dev machines both run this with fn up.)
    #[test]
    fn reads_hardware_state_without_trapping() {
        assert!(!fn_is_physically_down());
    }

    /// A watch over an idle period sees no physical fn — the forwarded-press
    /// case, which is exactly what must be dropped.
    #[test]
    fn watch_reports_no_physical_fn_when_none_is_held() {
        let w = Watch::start();
        thread::sleep(Duration::from_millis(50));
        assert!(!w.finish());
    }

    /// Dropping a watch without finishing it must stop its poller, so a press
    /// superseded by the next one can't leak a thread.
    #[test]
    fn dropping_a_watch_stops_its_poller() {
        let w = Watch::start();
        let stop = Arc::clone(&w.stop);
        drop(w);
        assert!(stop.load(Ordering::Relaxed));
    }
}

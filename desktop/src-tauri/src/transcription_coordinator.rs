use crate::actions::ACTION_MAP;
use crate::managers::audio::AudioRecordingManager;
use crate::settings::get_settings;
use log::{debug, error, warn};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Sender};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Manager};

const DEBOUNCE: Duration = Duration::from_millis(30);

/// Commands processed sequentially by the coordinator thread.
enum Command {
    Input {
        binding_id: String,
        hotkey_string: String,
        is_pressed: bool,
        push_to_talk: bool,
    },
    Cancel {
        recording_was_active: bool,
    },
    ProcessingFinished,
    /// Sent by a per-recording watchdog thread (see `start()`) after
    /// `max_recording_secs` elapses. `generation` pins it to the recording
    /// session that spawned it, so a watchdog for an already-finished (or
    /// restarted) recording is a harmless no-op — see `should_stop_for_max_duration`.
    MaxDurationReached {
        binding_id: String,
        hotkey_string: String,
        generation: u64,
    },
}

/// Pipeline lifecycle, owned exclusively by the coordinator thread.
enum Stage {
    Idle,
    Recording(String), // binding_id
    Processing,
}

/// Serialises all transcription lifecycle events through a single thread
/// to eliminate race conditions between keyboard shortcuts, signals, and
/// the async transcribe-paste pipeline.
pub struct TranscriptionCoordinator {
    tx: Sender<Command>,
}

pub fn is_transcribe_binding(id: &str) -> bool {
    id == "transcribe" || id == "transcribe_with_post_process"
}

impl TranscriptionCoordinator {
    pub fn new(app: AppHandle) -> Self {
        let (tx, rx) = mpsc::channel();
        // Bumped every time a recording starts; lets a stale max-duration
        // watchdog (spawned by a prior recording of the same binding) tell
        // it's no longer the active session — see `should_stop_for_max_duration`.
        let generation = Arc::new(AtomicU64::new(0));

        let watchdog_tx = tx.clone();
        thread::spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                let mut stage = Stage::Idle;
                let mut last_press: Option<Instant> = None;

                while let Ok(cmd) = rx.recv() {
                    match cmd {
                        Command::Input {
                            binding_id,
                            hotkey_string,
                            is_pressed,
                            push_to_talk,
                        } => {
                            // Debounce rapid-fire press events (key repeat / double-tap).
                            // Releases always pass through for push-to-talk.
                            if is_pressed {
                                let now = Instant::now();
                                if last_press.is_some_and(|t| now.duration_since(t) < DEBOUNCE) {
                                    debug!("Debounced press for '{binding_id}'");
                                    continue;
                                }
                                last_press = Some(now);
                            }

                            if push_to_talk {
                                if is_pressed && matches!(stage, Stage::Idle) {
                                    start(
                                        &app,
                                        &mut stage,
                                        &binding_id,
                                        &hotkey_string,
                                        &watchdog_tx,
                                        &generation,
                                    );
                                } else if !is_pressed
                                    && matches!(&stage, Stage::Recording(id) if id == &binding_id)
                                {
                                    stop(&app, &mut stage, &binding_id, &hotkey_string);
                                }
                            } else if is_pressed {
                                match &stage {
                                    Stage::Idle => {
                                        start(
                                            &app,
                                            &mut stage,
                                            &binding_id,
                                            &hotkey_string,
                                            &watchdog_tx,
                                            &generation,
                                        );
                                    }
                                    Stage::Recording(id) if id == &binding_id => {
                                        stop(&app, &mut stage, &binding_id, &hotkey_string);
                                    }
                                    _ => {
                                        debug!("Ignoring press for '{binding_id}': pipeline busy")
                                    }
                                }
                            }
                        }
                        Command::Cancel {
                            recording_was_active,
                        } => {
                            // Don't reset during processing — wait for the pipeline to finish.
                            if !matches!(stage, Stage::Processing)
                                && (recording_was_active || matches!(stage, Stage::Recording(_)))
                            {
                                stage = Stage::Idle;
                            }
                        }
                        Command::ProcessingFinished => {
                            stage = Stage::Idle;
                        }
                        Command::MaxDurationReached {
                            binding_id,
                            hotkey_string,
                            generation: fired_generation,
                        } => {
                            if should_stop_for_max_duration(
                                &stage,
                                &binding_id,
                                generation.load(Ordering::SeqCst),
                                fired_generation,
                            ) {
                                warn!(
                                    "Recording for '{binding_id}' exceeded max_recording_secs; \
                                     stopping as if the key were released"
                                );
                                stop(&app, &mut stage, &binding_id, &hotkey_string);
                            }
                        }
                    }
                }
                debug!("Transcription coordinator exited");
            }));
            if let Err(e) = result {
                error!("Transcription coordinator panicked: {e:?}");
            }
        });

        Self { tx }
    }

    /// Send a keyboard/signal input event for a transcribe binding.
    /// For signal-based toggles, use `is_pressed: true` and `push_to_talk: false`.
    pub fn send_input(
        &self,
        binding_id: &str,
        hotkey_string: &str,
        is_pressed: bool,
        push_to_talk: bool,
    ) {
        if self
            .tx
            .send(Command::Input {
                binding_id: binding_id.to_string(),
                hotkey_string: hotkey_string.to_string(),
                is_pressed,
                push_to_talk,
            })
            .is_err()
        {
            warn!("Transcription coordinator channel closed");
        }
    }

    pub fn notify_cancel(&self, recording_was_active: bool) {
        if self
            .tx
            .send(Command::Cancel {
                recording_was_active,
            })
            .is_err()
        {
            warn!("Transcription coordinator channel closed");
        }
    }

    pub fn notify_processing_finished(&self) {
        if self.tx.send(Command::ProcessingFinished).is_err() {
            warn!("Transcription coordinator channel closed");
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn start(
    app: &AppHandle,
    stage: &mut Stage,
    binding_id: &str,
    hotkey_string: &str,
    watchdog_tx: &Sender<Command>,
    generation: &Arc<AtomicU64>,
) {
    let Some(action) = ACTION_MAP.get(binding_id) else {
        warn!("No action in ACTION_MAP for '{binding_id}'");
        return;
    };
    action.start(app, binding_id, hotkey_string);
    if app
        .try_state::<Arc<AudioRecordingManager>>()
        .is_some_and(|a| a.is_recording())
    {
        *stage = Stage::Recording(binding_id.to_string());

        // Max-recording-duration watchdog: guards against a missed key-up
        // (e.g. a dropped keyboard event) leaving the mic recording forever.
        // Additive only — a plain timer thread, no state-machine restructuring.
        // `generation` lets a later, unrelated recording of the same binding
        // invalidate this timer instead of being cut short by it.
        let this_generation = generation.fetch_add(1, Ordering::SeqCst) + 1;
        let max_recording_secs = get_settings(app).max_recording_secs;
        let watchdog_tx = watchdog_tx.clone();
        let watchdog_binding_id = binding_id.to_string();
        let watchdog_hotkey_string = hotkey_string.to_string();
        thread::spawn(move || {
            thread::sleep(Duration::from_secs(max_recording_secs));
            let _ = watchdog_tx.send(Command::MaxDurationReached {
                binding_id: watchdog_binding_id,
                hotkey_string: watchdog_hotkey_string,
                generation: this_generation,
            });
        });
    } else {
        debug!("Start for '{binding_id}' did not begin recording; staying idle");
    }
}

fn stop(app: &AppHandle, stage: &mut Stage, binding_id: &str, hotkey_string: &str) {
    let Some(action) = ACTION_MAP.get(binding_id) else {
        warn!("No action in ACTION_MAP for '{binding_id}'");
        return;
    };
    action.stop(app, binding_id, hotkey_string);
    *stage = Stage::Processing;
}

/// Pure predicate for whether a fired `MaxDurationReached` watchdog should
/// actually stop the recording. False when the recording already ended
/// (stage no longer `Recording(binding_id)`) or when a newer recording of the
/// same binding has started since this watchdog was spawned (generation
/// mismatch) — both are normal races, not bugs, since the watchdog is a plain
/// timer with no cancellation.
fn should_stop_for_max_duration(
    stage: &Stage,
    binding_id: &str,
    current_generation: u64,
    fired_generation: u64,
) -> bool {
    current_generation == fired_generation
        && matches!(stage, Stage::Recording(id) if id == binding_id)
}

#[cfg(test)]
mod tests {
    use super::{should_stop_for_max_duration, Stage};

    #[test]
    fn stops_the_still_active_recording_it_was_spawned_for() {
        let stage = Stage::Recording("transcribe".to_string());
        assert!(should_stop_for_max_duration(&stage, "transcribe", 1, 1));
    }

    #[test]
    fn ignores_a_watchdog_for_a_recording_that_already_stopped() {
        // User released the key (or cancelled) before the watchdog fired —
        // stage moved on, but the generation counter didn't change.
        let stage = Stage::Idle;
        assert!(!should_stop_for_max_duration(&stage, "transcribe", 1, 1));

        let stage = Stage::Processing;
        assert!(!should_stop_for_max_duration(&stage, "transcribe", 1, 1));
    }

    #[test]
    fn ignores_a_stale_watchdog_from_a_prior_recording_of_the_same_binding() {
        // A new recording of the same binding started (bumping the
        // generation) before the old watchdog fired — must not cut the new
        // recording short.
        let stage = Stage::Recording("transcribe".to_string());
        assert!(!should_stop_for_max_duration(&stage, "transcribe", 2, 1));
    }

    #[test]
    fn ignores_a_watchdog_for_a_different_binding() {
        let stage = Stage::Recording("transcribe_with_post_process".to_string());
        assert!(!should_stop_for_max_duration(&stage, "transcribe", 1, 1));
    }
}

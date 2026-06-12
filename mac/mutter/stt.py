"""Microphone → whisper service.

Captures audio from the built-in microphone (never the system-default
input — see :func:`_builtin_input_device`) and transcribes via the
machine-wide whisper unix-socket service at ``~/.whisper-service``.

Capture and transcription are separate calls so the daemon can queue
slow transcriptions without blocking (or losing) the next capture::

    from mutter.stt import Listener

    ears = Listener()
    ears.begin_turn()
    audio = ears.capture()          # stopped by VAD or ears.finish()
    if audio is not None:
        text = ears.transcribe(audio)

Never-drop guarantees:
    - ``capture`` returns audio only when there is acoustic evidence of
      speech — true silence never reaches whisper (whisper hallucinates
      on silence).
    - ``transcribe`` never raises; if the whisper service fails, the
      clip is persisted to ``~/.mutter/pending/`` and the user notified.
    - ``collapse_repeats`` bounds whisper's repeat-loop pathology to a
      single phrase instead of a 500-line spew.

The VAD is a pure dataclass (:class:`_VadState`) so tests can drive
its transitions with synthetic timestamps.
"""

from __future__ import annotations

import logging
import os
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, List, Optional

from mutter.whisper_client import WhisperClient

logger = logging.getLogger(__name__)


def _builtin_input_device() -> Optional[int]:
    """Index of the Mac's built-in microphone, or ``None`` for the default.

    MUTTER records from the built-in mic, never the system-default input.
    When a Bluetooth headset/speaker is the default input, opening it forces
    an A2DP->HFP profile switch that fails with CoreAudio ``-10851`` while
    audio is playing, and otherwise hands back a silent (rms=0) HFP stream —
    either way dictation gets nothing. The built-in mic has neither failure
    mode and leaves Bluetooth music playing in A2DP untouched; the
    output-mute-while-fn path already stops local bleed into it.

    Returns ``None`` (PortAudio default) on Macs with no built-in mic.
    """
    try:
        import sounddevice as sd
        hints = ("MacBook", "iMac", "Mac mini", "Mac Studio", "Mac Pro", "Built-in")
        for i, d in enumerate(sd.query_devices()):
            if d.get("max_input_channels", 0) > 0:
                name = d.get("name", "")
                if "Microphone" in name and any(h in name for h in hints):
                    return i
    except Exception:
        return None
    return None


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

#: Whisper's native sampling rate. Resampling from another rate wastes CPU.
SAMPLE_RATE = 16000

#: Mono audio. Whisper trains on mono; stereo just doubles CPU for nothing.
CHANNELS = 1

#: 16-bit PCM. 2 bytes per sample.
SAMPLE_WIDTH = 2

#: RMS level (int16 range 0-32767) below which a frame counts as silence.
#: Slightly louder than hermes' 200 default to reduce false positives from
#: fan noise on laptops.
DEFAULT_SILENCE_RMS = 300

#: Seconds of continuous audio above the RMS threshold required before we
#: decide the user is actually speaking (vs. a cough / click / thump).
DEFAULT_MIN_SPEECH_SEC = 0.25

#: Seconds of continuous silence AFTER confirmed speech before we stop
#: recording. Short enough to feel snappy, long enough to survive the
#: natural pauses mid-sentence.
DEFAULT_SILENCE_STOP_SEC = 1.5

#: Hard ceiling on one utterance. Safety net — VAD should stop us first.
DEFAULT_MAX_DURATION_SEC = 60.0

#: Clips shorter than this are treated as "user didn't say anything".
MIN_CAPTURED_SEC = 0.3

#: Audio callback block size (seconds). 50 ms gives the VAD enough
#: resolution to notice a gap between words without flooding the lock.
DEFAULT_BLOCK_SECONDS = 0.05

#: Lenient speech-evidence gate for push-to-talk: at least this many
#: 50 ms callback blocks above the RMS threshold (not necessarily
#: contiguous) before a capture is worth transcribing. Looser than the
#: VAD's ``has_spoken`` (0.25 s contiguous) so quiet/short utterances
#: pass; strict enough that silence and fan noise never reach whisper.
MIN_SPEECH_FRAMES = 3


# ---------------------------------------------------------------------------
# Repeat-collapse
#
# Whisper's decoder can lock into a loop on noisy or marginal audio and
# emit the same phrase hundreds of times ("Thank you. Thank you. ...").
# The acoustic gate in capture() keeps true silence away from whisper
# entirely; this bounds the damage when real-but-noisy audio still
# triggers a loop: the run collapses to one instance, so the worst case
# types one phrase, never a spew. A deliberate short dictation ("okay",
# "thank you") is never dropped — PTT means the user pressed on purpose.
# ---------------------------------------------------------------------------

_WORD_EDGE_PUNCT = ".,!?;:"


def _collapse_repeats_once(words: List[str], norm: List[str],
                           min_repeats: int, max_period: int) -> List[str]:
    n = len(words)
    out: List[str] = []
    i = 0
    while i < n:
        for period in range(1, min(max_period, (n - i) // min_repeats) + 1):
            reps = 1
            while (i + (reps + 1) * period <= n
                   and norm[i + reps * period: i + (reps + 1) * period]
                   == norm[i: i + period]):
                reps += 1
            if reps >= min_repeats:
                out.extend(words[i: i + period])
                i += reps * period
                break
        else:
            out.append(words[i])
            i += 1
    return out


def collapse_repeats(text: str, min_repeats: int = 4, max_period: int = 8) -> str:
    """Collapse runs of ``min_repeats``+ consecutive identical word
    groups (1..``max_period`` words, compared case- and punctuation-
    insensitively) down to a single instance, repeating until stable.

    Real dictation is untouched — nobody says the same phrase four
    times in a row; if they truly do, one instance still types.
    """
    while True:
        words = text.split()
        if len(words) < min_repeats:
            return text
        norm = [w.lower().strip(_WORD_EDGE_PUNCT) for w in words]
        out = _collapse_repeats_once(words, norm, min_repeats, max_period)
        collapsed = " ".join(out)
        if collapsed == text:
            return text
        text = collapsed


# ---------------------------------------------------------------------------
# Never-drop plumbing — user notification + failed-clip persistence.
# ---------------------------------------------------------------------------

_PENDING_DIR = Path.home() / ".mutter" / "pending"

# Uniqueness suffix: queued segments can fail within the same second
# (service down -> instant connection errors), and a timestamp-only
# name would overwrite earlier clips.
_pending_seq = iter(range(1, 1_000_000))


def notify_user(message: str) -> None:
    """Best-effort macOS notification banner. Never raises, never blocks."""
    try:
        import json
        import subprocess
        script = (
            f"display notification {json.dumps(message)} "
            f'with title "MUTTER"'
        )
        subprocess.Popen(
            ["osascript", "-e", script],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        pass


def _persist_pending_audio(audio: Any, sample_rate: int) -> Optional[Path]:
    """Save a clip that failed to transcribe as a WAV under
    ``~/.mutter/pending/`` so a dictation is never silently lost."""
    try:
        import wave
        _PENDING_DIR.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        path = _PENDING_DIR / f"{stamp}-{next(_pending_seq):06d}.wav"
        with wave.open(str(path), "wb") as w:
            w.setnchannels(CHANNELS)
            w.setsampwidth(SAMPLE_WIDTH)
            w.setframerate(sample_rate)
            w.writeframes(audio.tobytes())
        return path
    except Exception as e:
        print(f"mutter: failed to persist audio: {e}", file=sys.stderr)
        return None


# ---------------------------------------------------------------------------
# Pure helpers
# ---------------------------------------------------------------------------


def is_available() -> bool:
    """True iff the audio stack (sounddevice + numpy) is importable.

    Does NOT check microphone hardware. Mic failures surface as
    :class:`RuntimeError` from :meth:`Listener.start`. Service
    reachability is :func:`whisper_client.wait_for_service`.
    """
    try:
        import sounddevice  # noqa: F401
        import numpy  # noqa: F401
    except (ImportError, OSError):
        return False
    return True


def _compute_rms_int16(samples: Any) -> int:
    """Root-mean-square of an int16 sample buffer as an int in [0, 32767].

    Handles numpy arrays and empty buffers gracefully. Returns 0 on any
    error rather than crashing the audio callback.
    """
    try:
        import numpy as np
    except ImportError:
        return 0
    if samples is None:
        return 0
    size = getattr(samples, "size", None)
    if size is None or size == 0:
        return 0
    try:
        squared = samples.astype(np.float64) ** 2
        mean = float(squared.mean())
    except Exception:
        return 0
    if mean <= 0:
        return 0
    return int(mean ** 0.5)


# ---------------------------------------------------------------------------
# Voice activity detector (pure state machine)
# ---------------------------------------------------------------------------


@dataclass
class _VadState:
    """Simple two-stage VAD: wait-for-speech → wait-for-silence.

    Fully time-parameterized: the caller passes ``now`` into every
    :meth:`update` call, so the state machine has zero hidden clock
    dependencies. Tests drive it with synthetic timestamps.

    Stages:
        1. Waiting for the user to speak. RMS above threshold starts a
           "maybe speaking" timer; if it stays loud for
           ``min_speech_duration`` we flip to ``has_spoken = True``.
        2. User is speaking, waiting for them to stop. RMS drops below
           threshold → silence timer starts; after ``silence_duration``
           we set ``finished = True``.

    Max duration is an absolute wall-clock cap from the ``begin`` time,
    enforced at every update as a safety net.

    Note on sentinels: ``speech_start`` and ``silence_start`` use ``None``
    to mean "not tracking" — we can't use 0.0 because the VAD may
    legitimately begin at t=0.0 (tests do this; a monotonic clock that
    happens to start near zero could too).
    """

    silence_threshold: int
    min_speech_duration: float
    silence_duration: float
    max_duration: float

    started_at: float = 0.0
    speech_start: Optional[float] = None
    silence_start: Optional[float] = None
    has_spoken: bool = False
    finished: bool = False

    def begin(self, now: float) -> None:
        """Reset the state machine for a new recording."""
        self.started_at = now
        self.speech_start = None
        self.silence_start = None
        self.has_spoken = False
        self.finished = False

    def update(self, rms: int, now: float) -> bool:
        """Feed a new RMS reading. Returns True if recording should stop."""
        if self.finished:
            return True
        if (now - self.started_at) >= self.max_duration:
            self.finished = True
            return True
        if rms > self.silence_threshold:
            # Frame is loud enough to be speech.
            if self.speech_start is None:
                self.speech_start = now
            elif not self.has_spoken and (now - self.speech_start) >= self.min_speech_duration:
                self.has_spoken = True
            # Any loud frame resets the silence timer.
            self.silence_start = None
        elif self.has_spoken:
            # Quiet, and we've already confirmed speech → start silence timer.
            if self.silence_start is None:
                self.silence_start = now
            elif (now - self.silence_start) >= self.silence_duration:
                self.finished = True
                return True
        # else: still waiting for the user to start talking.
        return False


# ---------------------------------------------------------------------------
# Listener
# ---------------------------------------------------------------------------


@dataclass
class Listener:
    """Microphone capture + local Whisper transcription, as two steps.

    Per turn: :meth:`begin_turn`, then :meth:`capture` on a worker
    thread (stopped by VAD, the duration cap, or :meth:`finish` from
    another thread), then :meth:`transcribe` on the captured audio —
    typically from a separate queue so a slow transcription can't
    block the next capture.

    Thread-safety: :meth:`capture`, :meth:`finish`, :meth:`begin_turn`
    and :meth:`stop` may be called from any thread. Only one ``capture``
    at a time — the audio stream is a shared resource.

    Parameters (all optional):
        model: HF repo id of the warm model the whisper service should
            use. Must be in the service's warm cache. ``None`` =
            service's primary.
        sample_rate: input sample rate (Hz). Whisper wants 16000.
        silence_threshold: RMS cutoff for silence detection.
        silence_duration: seconds of silence after speech = stop.
        max_duration: hard ceiling on one utterance (seconds).
        min_speech_duration: minimum speech before we count a "start".
        language: ``"en"`` skips ~50 ms of Whisper language detection
            per call. ``None`` = auto-detect.
    """

    model: Optional[str] = field(
        default_factory=lambda: os.environ.get("MUTTER_WHISPER_MODEL")
    )
    sample_rate: int = SAMPLE_RATE
    silence_threshold: int = DEFAULT_SILENCE_RMS
    silence_duration: float = DEFAULT_SILENCE_STOP_SEC
    max_duration: float = DEFAULT_MAX_DURATION_SEC
    min_speech_duration: float = DEFAULT_MIN_SPEECH_SEC
    language: Optional[str] = "en"

    _client: WhisperClient = field(default_factory=WhisperClient, repr=False)
    _stream: Any = field(default=None, repr=False)
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)
    _frames: List[Any] = field(default_factory=list, repr=False)
    _recording: bool = field(default=False, repr=False)
    _vad: Optional[_VadState] = field(default=None, repr=False)
    _current_rms: int = field(default=0, repr=False)
    _speech_frames: int = field(default=0, repr=False)
    _finish_requested: bool = field(default=False, repr=False)
    _logged_device: Any = field(default="?", repr=False)

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def stop(self) -> None:
        """Close the audio stream. Idempotent."""
        with self._lock:
            self._recording = False
            self._frames = []
            self._vad = None
            self._finish_requested = False
        self._close_stream()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def begin_turn(self) -> None:
        """Called at key-down, before the capture worker spawns. Clears
        any stale finish flag from a previous turn so it can't cancel
        this one."""
        with self._lock:
            self._finish_requested = False

    def capture(
        self,
        *,
        silence_duration: Optional[float] = None,
        max_duration: Optional[float] = None,
        poll_interval: float = 0.01,
    ) -> Optional[Any]:
        """Record one utterance; return int16 mono PCM, or ``None`` when:

            - :meth:`finish` raced ahead of this call (quick tap — the
              fn-up arrived before the worker thread got here)
            - there is no acoustic evidence of speech (true silence
              must never reach whisper: it hallucinates)
            - the clip is shorter than :data:`MIN_CAPTURED_SEC`

        Parameters are one-shot overrides of the defaults on ``self``.
        """
        sd_dur = silence_duration if silence_duration is not None else self.silence_duration
        md_dur = max_duration if max_duration is not None else self.max_duration

        self._ensure_stream()

        with self._lock:
            fast_finish = self._finish_requested
            self._finish_requested = False
            if not fast_finish:
                self._frames = []
                vad = _VadState(
                    silence_threshold=self.silence_threshold,
                    min_speech_duration=self.min_speech_duration,
                    silence_duration=sd_dur,
                    max_duration=md_dur,
                )
                vad.begin(time.monotonic())
                self._vad = vad
                self._recording = True
                self._current_rms = 0
                self._speech_frames = 0
        if fast_finish:
            self._close_stream()
            return None

        # Poll until the callback decides we're done.
        try:
            while True:
                with self._lock:
                    recording = self._recording
                if not recording:
                    break
                time.sleep(poll_interval)
        finally:
            with self._lock:
                self._recording = False
                frames = list(self._frames)
                final_vad = self._vad
                speech_frames = self._speech_frames
                self._frames = []
                self._vad = None
            # Close before whisper runs so the mic indicator is only
            # on while the user is holding the key.
            self._close_stream()

        if not frames or final_vad is None:
            return None
        if not (final_vad.has_spoken or speech_frames >= MIN_SPEECH_FRAMES):
            # Held in silence / pure ambient: zero speech evidence.
            # Transcribing this is how "Thank you." x500 happens.
            return None

        try:
            import numpy as np
        except ImportError:
            return None

        audio = np.concatenate(frames, axis=0).reshape(-1)
        if audio.size < int(self.sample_rate * MIN_CAPTURED_SEC):
            return None
        return audio

    def transcribe(self, audio: Any) -> Optional[str]:
        """Captured audio → clean transcript, or ``None`` for
        nothing-to-type.

        Never raises and never silently loses speech: if the whisper
        service fails, the clip is persisted to ``~/.mutter/pending/``
        and the user is notified.
        """
        try:
            text = self._do_transcribe(audio)
        except Exception as e:
            saved = _persist_pending_audio(audio, self.sample_rate)
            where = f" — saved to {saved}" if saved else ""
            print(
                f"mutter: transcribe failed: {e}{where}",
                file=sys.stderr,
                flush=True,
            )
            notify_user(
                "Transcription failed — audio saved to ~/.mutter/pending"
            )
            return None
        text = (text or "").strip()
        if not text:
            return None
        return collapse_repeats(text)

    def finish(self) -> None:
        """Stop recording NOW; the in-flight :meth:`capture` returns
        whatever was gathered so far.

        Used by push-to-talk clients: the user says "I'm done" by
        releasing the key instead of waiting for VAD silence.

        If :meth:`capture` hasn't initialized yet (a quick tap delivers
        the key-up before the worker thread gets there), leaves a flag
        so it exits immediately instead of starting a recording that
        nothing would ever stop.

        Idempotent: calling on an already-stopped listener is a no-op.
        """
        with self._lock:
            if self._vad is not None:
                self._vad.finished = True
            else:
                self._finish_requested = True
            self._recording = False

    # ------------------------------------------------------------------
    # Audio stream
    # ------------------------------------------------------------------

    def _ensure_stream(self) -> None:
        if self._stream is not None:
            return
        try:
            import sounddevice as sd
        except (ImportError, OSError) as e:  # pragma: no cover - env dep
            raise RuntimeError(
                "sounddevice is required for microphone input. "
                "Install: pip install sounddevice numpy. "
                f"(underlying error: {e})"
            ) from e
        device = _builtin_input_device()
        try:
            stream = sd.InputStream(
                samplerate=self.sample_rate,
                channels=CHANNELS,
                dtype="int16",
                device=device,
                callback=self._audio_callback,
                blocksize=int(self.sample_rate * DEFAULT_BLOCK_SECONDS),
            )
            stream.start()
        except Exception as e:  # pragma: no cover - hw dep
            raise RuntimeError(
                "Failed to open microphone. On macOS, grant Terminal (or "
                "your shell) microphone access in System Settings → Privacy "
                f"& Security → Microphone. (underlying error: {e})"
            ) from e
        self._stream = stream
        # Log the input device once, and again only if it changes (e.g. the
        # built-in mic disappears and we fall through to default) — keeps the
        # error log free of one line per dictation.
        if device != self._logged_device:
            self._logged_device = device
            try:
                dev_name = sd.query_devices(device)["name"] if device is not None \
                    else "system default"
            except Exception:
                dev_name = repr(device)
            print(f"mutter: mic open on {dev_name!r}", file=sys.stderr, flush=True)

    def _close_stream(self) -> None:
        stream, self._stream = self._stream, None
        if stream is None:
            return
        # abort, not stop: Pa_StopStream drains the input buffer and can
        # deadlock after CoreAudio HAL disruption (sleep/wake, device
        # change). Frames are already callback-copied; nothing to drain.
        for op in ("abort", "close"):
            fn = getattr(stream, op, None)
            if fn is None:
                continue
            try:
                fn()
            except Exception:
                pass

    def _audio_callback(self, indata, frames, time_info, status) -> None:
        """sounddevice audio-thread callback. MUST be fast.

        Appends audio to the frame buffer and ticks the VAD. When the VAD
        decides we're done, sets ``_recording = False`` so the main poll
        loop in :meth:`listen` exits.
        """
        if status:
            logger.debug("audio status: %s", status)
        with self._lock:
            if not self._recording or self._vad is None:
                return
            # Copy because sounddevice reuses its own buffer across callbacks.
            if hasattr(indata, "copy"):
                self._frames.append(indata.copy())
            else:
                self._frames.append(indata)
            rms = _compute_rms_int16(indata)
            self._current_rms = rms
            if rms > self._vad.silence_threshold:
                self._speech_frames += 1
            if self._vad.update(rms, time.monotonic()):
                self._recording = False

    # ------------------------------------------------------------------
    # Whisper
    # ------------------------------------------------------------------

    def _do_transcribe(self, audio_int16: Any) -> str:
        """Transcribe a captured int16 clip via the whisper service."""
        result = self._client.transcribe(
            audio_int16,
            sample_rate=SAMPLE_RATE,
            language=self.language,
            model=self.model,
            condition_on_previous_text=False,
        )
        return result.get("text", "") or ""

"""Microphone → whisper service.

Captures audio from the built-in microphone (never the system-default
input — see :func:`_builtin_input_device`), auto-stops when the user
falls silent (simple RMS VAD), and transcribes via the machine-wide
whisper unix-socket service at ``~/.whisper-service``.

Usage::

    from mutter.stt import Listener

    ears = Listener()
    ears.start()
    transcript = ears.listen()
    if transcript:
        print(f"You said: {transcript}")

The VAD is a pure dataclass (:class:`_VadState`) so tests can drive
its transitions with synthetic timestamps.
"""

from __future__ import annotations

import logging
import os
import re
import sys
import threading
import time
from dataclasses import dataclass, field
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


# ---------------------------------------------------------------------------
# Hallucination filter
#
# Whisper has a well-known habit of emitting boilerplate strings on silent
# or near-silent audio ("Thanks for watching.", "Subscribe."). These tank
# UX because the user hears the system react to nothing. We filter them.
# ---------------------------------------------------------------------------

_HALLUCINATIONS = frozenset(
    {
        "thank you",
        "thanks",
        "thanks for watching",
        "thank you for watching",
        "thank you so much",
        "subscribe",
        "please subscribe",
        "like and subscribe",
        "subscribe to my channel",
        "bye",
        "goodbye",
        "you",
        "the end",
        "so",
        "yeah",
        "okay",
        "ok",
        "um",
        "uh",
        "mm",
        "hmm",
    }
)

# Repeat pattern: "thank you. thank you. thank you." or ". . .". Whisper
# loops on silence. Anchored.
_HALLUCINATION_REPEAT_RE = re.compile(
    r"^\s*(?:(?:thank you|thanks|bye|you|ok|okay|the end|so|yeah|mm|hmm|uh|um)"
    r"[\s.!?,]*){1,}$",
    re.IGNORECASE,
)


def is_hallucination(text: str) -> bool:
    """Return True if ``text`` looks like a Whisper silence hallucination.

    Conservative: only returns True for the exact known phrases or for
    strings that are *only* made of filler tokens. Real transcripts with
    a "thanks" in the middle pass through.
    """
    if text is None:
        return True
    cleaned = text.strip()
    if not cleaned:
        return True
    lowered = cleaned.lower().rstrip(".!?,")
    if lowered in _HALLUCINATIONS:
        return True
    if _HALLUCINATION_REPEAT_RE.match(cleaned):
        return True
    return False


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
    """Microphone → local Whisper transcriber.

    Call :meth:`listen` to record one utterance and get back a clean
    transcript string (or ``None`` if nothing meaningful was captured).
    Use as a context manager to ensure the audio stream is closed on exit.

    Thread-safety: :meth:`listen`, :meth:`cancel`, and :meth:`current_rms`
    may be called from any thread. Only one ``listen`` at a time — the
    audio stream is a shared resource.

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
    _logged_device: Any = field(default="?", repr=False)

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def start(self) -> None:
        """No-op kept for context-manager parity. The client is built
        in :meth:`__post_init__`; the mic stream is opened by :meth:`listen`."""

    def stop(self) -> None:
        """Close the audio stream. Idempotent."""
        with self._lock:
            self._recording = False
            self._frames = []
            self._vad = None
        self._close_stream()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def listen(
        self,
        *,
        silence_duration: Optional[float] = None,
        max_duration: Optional[float] = None,
        poll_interval: float = 0.01,
    ) -> Optional[str]:
        """Record one utterance and return the transcript (or None).

        Returns ``None`` in any of these cases:
            - User never spoke (RMS stayed below threshold)
            - Captured clip was shorter than :data:`MIN_CAPTURED_SEC`
            - Transcript was empty
            - Transcript matched a known Whisper hallucination
            - A concurrent :meth:`cancel` aborted the recording

        Parameters are one-shot overrides of the defaults on ``self``.
        """
        sd_dur = silence_duration if silence_duration is not None else self.silence_duration
        md_dur = max_duration if max_duration is not None else self.max_duration

        self._ensure_stream()

        with self._lock:
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
                self._frames = []
                self._vad = None
            # Close before whisper runs so the mic indicator is only
            # on while the user is holding the key.
            self._close_stream()

        if not frames or final_vad is None or not final_vad.has_spoken:
            return None

        try:
            import numpy as np
        except ImportError:
            return None

        audio = np.concatenate(frames, axis=0).reshape(-1)
        if audio.size < int(self.sample_rate * MIN_CAPTURED_SEC):
            return None

        text = self._do_transcribe(audio)
        if not text:
            return None
        text = text.strip()
        if is_hallucination(text):
            return None
        return text

    def finish(self) -> None:
        """Stop recording NOW and transcribe what we have.

        Unlike :meth:`cancel`, preserves the captured frames so the
        in-flight :meth:`listen` call will flip out of its poll loop
        and run Whisper on the audio gathered so far.

        Used by push-to-talk clients: the user says "I'm done" by
        pressing a key instead of waiting for VAD silence, and we
        want the transcript of whatever they said up to that point —
        even if the VAD never confirmed "has_spoken" (short
        utterances, quiet voice, etc).

        Idempotent: calling on an already-stopped listener is a no-op.
        """
        with self._lock:
            if self._vad is not None:
                # Force the VAD out of both "still waiting for speech"
                # and "still waiting for silence" at the same time, so
                # the listen() post-check passes the has_spoken gate
                # and transcribes whatever frames we have.
                self._vad.has_spoken = True
                self._vad.finished = True
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

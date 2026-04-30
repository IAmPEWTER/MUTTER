"""Speech-to-text for CLAWS — microphone → local Whisper.

Captures audio from the default input device, auto-stops when the user
falls silent (simple RMS VAD), and transcribes on-device. No API keys,
no cloud round-trips.

Two Whisper backends are supported, selectable via the
``CLAWS_WHISPER_BACKEND`` environment variable or the ``backend=``
constructor argument on :class:`Listener`:

- ``"faster-whisper"`` *(default)* — CPU int8 via the ``faster-whisper``
  library. Works on any Mac (Apple Silicon or Intel); ~180 ms for a 5 s
  clip on an M1 Air tiny.en.
- ``"mlx"`` — Apple MLX Metal GPU via ``mlx-whisper``. Apple Silicon
  only; ~65 ms for the same clip on the same machine (≈2.9× faster).
  Identical transcripts to faster-whisper in stress testing.

Usage::

    from claws.stt import Listener

    with Listener() as ears:            # backend from env / default
        print("Speak now...")
        transcript = ears.listen()
        if transcript:
            print(f"You said: {transcript}")

    with Listener(backend="mlx") as ears:  # explicit
        transcript = ears.listen()

Design:
    - Lazy imports for ``sounddevice``, ``numpy``, and the chosen whisper
      library so this module loads (and raises readable errors) on
      machines missing any of them. Nothing at import time blocks us
      from unit-testing pure logic.
    - The VAD lives in :class:`_VadState`, a pure dataclass whose
      ``update`` takes a wall-clock time as a parameter. This makes the
      state machine 100% deterministic in tests.
    - :class:`Listener` plumbs audio callbacks into the VAD, records
      frames while the VAD says "still going", then hands the int16 PCM
      buffer to the chosen backend wrapper for transcription.
    - Each backend wrapper (``_FasterWhisperBackend``, ``_MlxBackend``)
      owns its library import and exposes a uniform
      ``.transcribe(audio_f32) -> str`` method. :class:`Listener` treats
      them interchangeably via duck-typing; adding a third backend is a
      ~20-line class.
    - :class:`Listener` also exposes two injection seams used by tests:
      ``stream_factory`` (replace the real ``sd.InputStream``) and
      ``transcribe_fn`` (replace the real Whisper call). Injections let
      us exercise the full listen/transcribe/hallucination path with
      zero hardware and zero model download.
    - Whisper hallucinations (``"thank you."``, ``"thanks for watching"``,
      etc.) are filtered out of the returned transcript. Pattern list
      inspired by ``~/.hermes/hermes-agent/tools/voice_mode.py:533-581``.
"""

from __future__ import annotations

import logging
import os
import re
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable, List, Optional

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

#: Whisper's native sampling rate. Resampling from another rate wastes CPU.
SAMPLE_RATE = 16000

#: Mono audio. Whisper trains on mono; stereo just doubles CPU for nothing.
CHANNELS = 1

#: 16-bit PCM. 2 bytes per sample.
SAMPLE_WIDTH = 2

#: Whisper model tag. Override per-machine via ``MUTTER_WHISPER_MODEL``
#: (e.g. set in the LaunchAgent's ``EnvironmentVariables`` block).
#:
#: Recommended values for the mlx backend:
#:
#: - ``large-v3-turbo``     — FP16. 1.5 GB on disk, ~1.8 GB RSS. Best
#:   accuracy. Default.
#: - ``large-v3-turbo-q4``  — 4-bit quant. 440 MB on disk, ~780 MB RSS,
#:   ~4 % faster. Accuracy effectively identical on English speech.
#:   Worth it on RAM-constrained machines.
DEFAULT_MODEL_SIZE = os.environ.get("MUTTER_WHISPER_MODEL", "large-v3-turbo")

#: Backend identifiers for the pluggable whisper dispatch. ``mlx`` is
#: Apple-Silicon-only (and requires macOS 15+ for current mlx builds);
#: ``faster-whisper`` is the portable CPU fallback.
BACKEND_FASTER_WHISPER = "faster-whisper"
BACKEND_MLX = "mlx"
DEFAULT_BACKEND = BACKEND_MLX


def resolve_backend_from_env() -> str:
    """Return the backend tag from ``CLAWS_WHISPER_BACKEND`` or the default.

    Unknown values fall back to :data:`DEFAULT_BACKEND` with a logger
    warning — misspellings shouldn't crash the voice loop at startup.
    """
    raw = os.environ.get("CLAWS_WHISPER_BACKEND", "").strip().lower()
    if not raw:
        return DEFAULT_BACKEND
    if raw in (BACKEND_FASTER_WHISPER, "fw", "cpu"):
        return BACKEND_FASTER_WHISPER
    if raw in (BACKEND_MLX, "metal", "gpu"):
        return BACKEND_MLX
    logger.warning(
        "unknown CLAWS_WHISPER_BACKEND=%r, falling back to %s",
        raw, DEFAULT_BACKEND,
    )
    return DEFAULT_BACKEND

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


def is_available(backend: str = DEFAULT_BACKEND) -> bool:
    """True iff the native audio + whisper stack for ``backend`` is importable.

    Does NOT check microphone hardware or model download state. Those
    surface as readable :class:`RuntimeError` from :meth:`Listener.start`.
    """
    try:
        import sounddevice  # noqa: F401
        import numpy  # noqa: F401
    except (ImportError, OSError):
        return False
    if backend == BACKEND_MLX:
        try:
            import mlx_whisper  # noqa: F401
        except (ImportError, OSError):
            return False
    else:
        try:
            import faster_whisper  # noqa: F401
        except (ImportError, OSError):
            return False
    return True


def is_model_cached(
    model_size: str = DEFAULT_MODEL_SIZE,
    backend: str = DEFAULT_BACKEND,
) -> bool:
    """Return True if the Whisper model for ``backend`` is already on disk.

    Lets callers tell the user whether the next :meth:`Listener.start`
    will be instant (~1 s) or a slow one-time download (~2–4 minutes
    for tiny.en on a typical home connection).

    Both supported backends pull from Hugging Face Hub into
    ``~/.cache/huggingface/hub``, but under different repo names and
    with different "done" markers:

    - ``faster-whisper``: ``Systran/faster-whisper-<size>`` with a
      ``model.bin`` weight file.
    - ``mlx``: ``mlx-community/whisper-<size>`` with a ``weights.npz``
      weight file (MLX's native NumPy-zip format).

    The presence of a ``snapshots/`` directory containing at least one
    snapshot with the expected weight file is the cleanest "cached and
    usable" signal short of actually loading the model.
    """
    from pathlib import Path

    cache_root = (
        Path(os.environ.get("HF_HOME", Path.home() / ".cache" / "huggingface"))
        / "hub"
    )
    if backend == BACKEND_MLX:
        repo = f"models--mlx-community--whisper-{model_size}"
        # mlx-community ships either weights.npz (older) or weights.safetensors
        # (newer, e.g. large-v3-turbo). Accept either.
        markers = ("weights.npz", "weights.safetensors")
    else:
        repo = f"models--Systran--faster-whisper-{model_size}"
        markers = ("model.bin",)
    snapshots = cache_root / repo / "snapshots"
    if not snapshots.is_dir():
        return False
    for snap in snapshots.iterdir():
        if any((snap / m).exists() for m in markers):
            return True
    return False


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
# Whisper backends (pluggable)
#
# Each backend wrapper owns its library import, loads the model in
# __init__, and exposes a single ``.transcribe(audio_f32) -> str`` method.
# Listener stores the chosen wrapper in ``self._model`` and calls that
# method — no if-else cascades in the hot path. Adding a third backend
# (Apple SFSpeechRecognizer, Groq API, etc) is a ~20-line new class.
# ---------------------------------------------------------------------------


class _FasterWhisperBackend:
    """Reference backend: faster-whisper int8 on CPU.

    Works on any Mac (Apple Silicon or Intel) as well as Linux/Windows.
    Median ≈180 ms for a 5-second English clip on an M1 with tiny.en.
    This is the baseline every other backend is compared against.
    """

    def __init__(
        self,
        model_size: str,
        language: Optional[str],
        beam_size: int,
    ) -> None:
        try:
            from faster_whisper import WhisperModel
        except ImportError as e:  # pragma: no cover - env dep
            raise RuntimeError(
                "faster-whisper is required for the 'faster-whisper' "
                "backend. Install: pip install faster-whisper. "
                f"(underlying error: {e})"
            ) from e
        # int8 on CPU: fastest honest quantization. ~5-10x realtime for
        # tiny.en on an M1. First use triggers a ~40 MB HF download.
        self._model = WhisperModel(
            model_size,
            device="cpu",
            compute_type="int8",
        )
        self._language = language
        self._beam_size = beam_size

    def transcribe(self, audio_f32: Any) -> str:
        segments, _info = self._model.transcribe(
            audio_f32,
            language=self._language,
            beam_size=self._beam_size,
            vad_filter=False,  # our VAD already trimmed the clip
            condition_on_previous_text=False,
        )
        return "".join(s.text for s in segments)


class _MlxBackend:
    """Apple MLX Metal-GPU backend via ``mlx-whisper``.

    Apple Silicon only (requires an M-series NPU/GPU). On an M1 Air
    with tiny.en, median transcribe latency for a 5-second English
    clip is ≈65 ms — roughly 2.9× faster than faster-whisper on the
    same hardware, with identical transcript accuracy in stress tests.

    Model repos live at ``mlx-community/whisper-<size>-mlx`` on HF. The
    first :meth:`transcribe` call triggers a one-time download (~40 MB
    for tiny.en) and a model-graph compile that takes a few seconds.
    :meth:`__init__` pre-warms the model with a zero buffer so that
    compile cost is paid at Listener start time instead of on the
    user's first spoken turn.
    """

    #: HF repos live under the mlx-community org. Default tag
    #: ``large-v3-turbo`` uses the canonical repo; older ``{size}-mlx``
    #: aliases (tiny.en-mlx etc.) still work if passed as model_size.
    _REPO_TEMPLATE = "mlx-community/whisper-{size}"

    def __init__(
        self,
        model_size: str,
        language: Optional[str],
    ) -> None:
        try:
            import mlx_whisper  # noqa: F401
        except ImportError as e:  # pragma: no cover - env dep
            raise RuntimeError(
                "mlx-whisper is required for the 'mlx' backend. "
                "Install: pip install mlx-whisper. "
                f"(underlying error: {e})"
            ) from e
        self._mlx_whisper = mlx_whisper
        self._repo = self._REPO_TEMPLATE.format(size=model_size)
        self._language = language
        # Pre-warm so the first user turn doesn't eat the model-compile
        # cost. A 0.1-second zero buffer is enough to trigger the full
        # load-and-compile path without transcribing anything real.
        try:
            import numpy as np
            warmup = np.zeros(int(SAMPLE_RATE * 0.1), dtype=np.float32)
            self._mlx_whisper.transcribe(
                warmup,
                path_or_hf_repo=self._repo,
                language=self._language,
                condition_on_previous_text=False,
                verbose=None,
            )
        except Exception as e:  # pragma: no cover - warmup is best effort
            logger.debug("mlx warmup failed (non-fatal): %s", e)

    def transcribe(self, audio_f32: Any) -> str:
        result = self._mlx_whisper.transcribe(
            audio_f32,
            path_or_hf_repo=self._repo,
            language=self._language,
            condition_on_previous_text=False,
            verbose=None,
        )
        text = result.get("text", "") if isinstance(result, dict) else ""
        return text or ""


def _make_backend(
    backend: str,
    model_size: str,
    language: Optional[str],
    beam_size: int,
) -> Any:
    """Factory: return a backend wrapper matching ``backend``.

    Split out so tests can patch it without touching :class:`Listener`
    state. Unknown backend tags raise :class:`ValueError` — the env
    resolver (:func:`resolve_backend_from_env`) already normalises the
    common misspellings to valid tags.
    """
    if backend == BACKEND_MLX:
        return _MlxBackend(model_size=model_size, language=language)
    if backend == BACKEND_FASTER_WHISPER:
        return _FasterWhisperBackend(
            model_size=model_size,
            language=language,
            beam_size=beam_size,
        )
    raise ValueError(f"unknown whisper backend: {backend!r}")


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
        model_size: Whisper model tag. ``tiny.en`` is default.
        backend: ``"faster-whisper"`` or ``"mlx"``. Defaults to the
            value of ``CLAWS_WHISPER_BACKEND`` or ``"faster-whisper"``
            if unset. See module docstring for trade-offs.
        sample_rate: input sample rate (Hz). Whisper wants 16000.
        silence_threshold: RMS cutoff for silence detection.
        silence_duration: seconds of silence after speech = stop.
        max_duration: hard ceiling on one utterance (seconds).
        min_speech_duration: minimum speech before we count a "start".
        language: ``"en"`` passes to Whisper for speed. None = auto-detect.
        beam_size: Whisper beam search width (faster-whisper only).
            1 = greedy = fast. Ignored by the mlx backend.

    Test-injection parameters:
        transcribe_fn: ``(audio_int16_ndarray) -> str``. Bypass Whisper.
        stream_factory: ``(callback) -> stream``. Bypass sounddevice.
        time_fn: ``() -> float``. Clock source (default ``time.monotonic``).
    """

    model_size: str = DEFAULT_MODEL_SIZE
    backend: str = field(default_factory=resolve_backend_from_env)
    sample_rate: int = SAMPLE_RATE
    silence_threshold: int = DEFAULT_SILENCE_RMS
    silence_duration: float = DEFAULT_SILENCE_STOP_SEC
    max_duration: float = DEFAULT_MAX_DURATION_SEC
    min_speech_duration: float = DEFAULT_MIN_SPEECH_SEC
    language: Optional[str] = "en"
    beam_size: int = 1

    # Test-injection seams. None in production.
    transcribe_fn: Optional[Callable[[Any], str]] = None
    stream_factory: Optional[Callable[[Callable], Any]] = None
    time_fn: Callable[[], float] = field(default=time.monotonic)

    # Runtime state (not part of the public API)
    # ``_model`` holds a backend wrapper instance (``_FasterWhisperBackend``
    # or ``_MlxBackend``), each with a uniform ``.transcribe(audio_f32)``
    # method. ``None`` when transcribe_fn is injected (tests).
    _model: Any = field(default=None, repr=False)
    _stream: Any = field(default=None, repr=False)
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)
    _frames: List[Any] = field(default_factory=list, repr=False)
    _recording: bool = field(default=False, repr=False)
    _vad: Optional[_VadState] = field(default=None, repr=False)
    _current_rms: int = field(default=0, repr=False)

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def start(self) -> None:
        """Load the Whisper model. Idempotent.

        Does NOT open the mic — :meth:`listen` opens it on entry and
        closes it on exit, so the macOS mic indicator stays off at rest.
        """
        self._ensure_model()

    def stop(self) -> None:
        """Close the audio stream and drop the model. Idempotent."""
        with self._lock:
            self._recording = False
            self._frames = []
            self._vad = None
        self._close_stream()
        # Don't forcibly free the model — Python GC will.
        self._model = None

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
        self._ensure_model()

        with self._lock:
            self._frames = []
            vad = _VadState(
                silence_threshold=self.silence_threshold,
                min_speech_duration=self.min_speech_duration,
                silence_duration=sd_dur,
                max_duration=md_dur,
            )
            vad.begin(self.time_fn())
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

        audio = np.concatenate(frames, axis=0)
        if audio.size < int(self.sample_rate * MIN_CAPTURED_SEC):
            return None

        text = self._do_transcribe(audio)
        if not text:
            return None
        text = text.strip()
        if is_hallucination(text):
            return None
        return text

    def cancel(self) -> None:
        """Abort current recording without transcribing. Idempotent."""
        with self._lock:
            self._recording = False
            self._frames = []
            self._vad = None

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

    def current_rms(self) -> int:
        """Last-known RMS reading. Useful for live level meters."""
        with self._lock:
            return self._current_rms

    def is_recording(self) -> bool:
        with self._lock:
            return self._recording

    # ------------------------------------------------------------------
    # Context manager
    # ------------------------------------------------------------------

    def __enter__(self) -> "Listener":
        self.start()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.stop()

    # ------------------------------------------------------------------
    # Audio stream
    # ------------------------------------------------------------------

    def _ensure_stream(self) -> None:
        if self._stream is not None:
            return
        if self.stream_factory is not None:
            self._stream = self.stream_factory(self._audio_callback)
            return
        try:
            import sounddevice as sd
        except (ImportError, OSError) as e:  # pragma: no cover - env dep
            raise RuntimeError(
                "sounddevice is required for microphone input. "
                "Install: pip install sounddevice numpy. "
                f"(underlying error: {e})"
            ) from e
        try:
            stream = sd.InputStream(
                samplerate=self.sample_rate,
                channels=CHANNELS,
                dtype="int16",
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

    def _close_stream(self) -> None:
        stream, self._stream = self._stream, None
        if stream is None:
            return
        for op in ("stop", "close"):
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
            if self._vad.update(rms, self.time_fn()):
                self._recording = False

    # ------------------------------------------------------------------
    # Whisper
    # ------------------------------------------------------------------

    def _ensure_model(self) -> None:
        """Lazy-load the chosen backend. No-op once loaded.

        Skipped entirely when ``transcribe_fn`` is injected (tests) —
        the injection seam takes precedence over any real backend.
        """
        if self._model is not None or self.transcribe_fn is not None:
            return
        self._model = _make_backend(
            backend=self.backend,
            model_size=self.model_size,
            language=self.language,
            beam_size=self.beam_size,
        )

    def _do_transcribe(self, audio_int16: Any) -> str:
        """Run the backend wrapper against a captured clip.

        ``transcribe_fn`` still takes int16 audio (backward compat with
        existing tests and injected fakes). The real backend path
        converts to float32 in [-1, 1] before dispatching.
        """
        if self.transcribe_fn is not None:
            result = self.transcribe_fn(audio_int16)
            return result or ""
        if self._model is None:
            return ""
        try:
            import numpy as np
        except ImportError:
            return ""
        # Both real backends expect mono float32 in [-1, 1].
        audio_f32 = (audio_int16.astype(np.float32) / 32768.0).flatten()
        return self._model.transcribe(audio_f32) or ""

"""Thin Python client for the whisper service.

Synchronous API. One unix-socket round-trip per call. The service
serializes transcribe requests internally, so concurrent callers
queue cleanly.

Usage:

    from mutter.whisper_client import WhisperClient
    import numpy as np

    pcm = np.zeros(16000, dtype=np.int16)        # 1 s of int16 PCM @ 16 kHz
    c = WhisperClient()
    print(c.ping())
    result = c.transcribe(pcm, sample_rate=16000, language="en")
    print(result["text"])

Wire protocol — see the whisper service's server.py.
"""
from __future__ import annotations

import json
import socket
import struct
import tempfile
import time
from pathlib import Path
from typing import Optional

import numpy as np


# Socket path. Whisper service binds this same default. To target a
# service installed elsewhere, pass ``sock_path=`` to WhisperClient.
DEFAULT_SOCK_PATH = Path(tempfile.gettempdir()) / "whisper.sock"


class WhisperClientError(RuntimeError):
    """Server returned ok=false."""

    def __init__(self, error: str, type_: str):
        super().__init__(f"{type_}: {error}")
        self.type = type_
        self.error = error


class WhisperClient:
    """Sync client for the whisper unix-socket service.

    Each method opens a fresh connection, sends, reads, closes. The
    socket is cheap; this keeps the API stateless and removes any
    keep-alive bookkeeping.

    Parameters
    ----------
    sock_path
        Path to the server's unix socket. Defaults to
        ``$TMPDIR/whisper.sock`` (per-user Darwin temp dir).
    timeout
        Per-call socket timeout in seconds. The transcribe path
        intentionally allows generous time for long clips.
    """

    def __init__(self, sock_path: Path | str = DEFAULT_SOCK_PATH, timeout: float = 120.0):
        self.sock_path = Path(sock_path)
        self.timeout = timeout

    # ---- internals ------------------------------------------------------

    def _connect(self) -> socket.socket:
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(self.timeout)
        s.connect(str(self.sock_path))
        return s

    @staticmethod
    def _send_json(s: socket.socket, obj: dict) -> None:
        payload = json.dumps(obj).encode("utf-8")
        s.sendall(struct.pack(">I", len(payload)) + payload)

    @staticmethod
    def _recv_exact(s: socket.socket, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = s.recv(n - len(buf))
            if not chunk:
                raise ConnectionError(f"server disconnected after {len(buf)}/{n} bytes")
            buf.extend(chunk)
        return bytes(buf)

    @classmethod
    def _recv_json(cls, s: socket.socket) -> dict:
        raw_len = cls._recv_exact(s, 4)
        (length,) = struct.unpack(">I", raw_len)
        return json.loads(cls._recv_exact(s, length).decode("utf-8"))

    @staticmethod
    def _check(resp: dict) -> dict:
        if not resp.get("ok"):
            raise WhisperClientError(
                resp.get("error", "unknown error"),
                resp.get("type", "RuntimeError"),
            )
        return resp

    # ---- public API -----------------------------------------------------

    def ping(self) -> dict:
        """Round-trip health check. Returns the server's status dict."""
        with self._connect() as s:
            self._send_json(s, {"op": "ping"})
            return self._check(self._recv_json(s))

    def transcribe(
        self,
        pcm: np.ndarray,
        sample_rate: int = 16000,
        language: Optional[str] = None,
        beam_size: int = 5,
        vad_filter: bool = False,
        word_timestamps: bool = False,
        condition_on_previous_text: bool = False,
        model: Optional[str] = None,
    ) -> dict:
        """Transcribe int16 mono PCM.

        Parameters
        ----------
        pcm
            1-D numpy array of dtype int16. Mono only — pre-mix stereo
            at the caller.
        sample_rate
            Source sample rate. faster-whisper resamples internally.
            Pass the actual rate of your PCM, not always 16000.
        language
            Two-letter ISO code (``"en"``, ``"es"``, ...) or ``None`` for
            auto-detect. Consumers should pass ``"en"`` to skip
            detection cost.
        beam_size
            Whisper beam search width. 5 is a good default; lower (1-2)
            for fastest, higher (8-10) for quality.
        vad_filter
            If True, faster-whisper's Silero VAD prunes silence before
            decoding. Use when input may have long lead-in/lead-out.
        word_timestamps
            Adds per-word timestamps in segments[].words. Slower.
        condition_on_previous_text
            If True, each 30 s decode chunk gets the previous chunk's
            transcript as a prefix prompt — Whisper's library default
            behavior. Helps continuity but causes runaway repeat-loop
            hallucinations ("Thank you. Thank you. Thank you...") when
            the model gets stuck. **Default False** — matches what
            voice/mutter/whatsapp in-process code passes today, and is
            strictly safer for long clips that span chunks. Only flip
            to True if you specifically want cross-chunk cohesion and
            have validated your audio doesn't trigger the hallucination
            cascade.
        model
            HF repo id of the model to use. Must be in the service's
            warm cache (loaded at service startup). ``None`` = use the
            service's primary. Run ``ping()`` to see the live list.
            Requesting a model not in the warm cache returns
            ``WhisperClientError``.

        Returns
        -------
        dict with keys: ok, text, language, language_probability,
        duration_s, segments, elapsed_s.

        Raises
        ------
        WhisperClientError
            If the server returns ok=false.
        ConnectionError
            If the socket can't connect or peer disconnects.
        """
        if not isinstance(pcm, np.ndarray):
            raise TypeError(f"pcm must be np.ndarray, got {type(pcm).__name__}")
        if pcm.dtype != np.int16:
            raise ValueError(f"pcm.dtype must be int16, got {pcm.dtype}")
        if pcm.ndim != 1:
            raise ValueError(f"pcm must be 1-D, got shape {pcm.shape}")

        pcm_bytes = pcm.tobytes()
        header = {
            "op": "transcribe",
            "sample_rate": int(sample_rate),
            "channels": 1,
            "format": "int16",
            "pcm_bytes": len(pcm_bytes),
            "language": language,
            "beam_size": int(beam_size),
            "vad_filter": bool(vad_filter),
            "word_timestamps": bool(word_timestamps),
            "condition_on_previous_text": bool(condition_on_previous_text),
            "model": model,
        }
        with self._connect() as s:
            self._send_json(s, header)
            s.sendall(pcm_bytes)
            return self._check(self._recv_json(s))


def wait_for_service(
    sock_path: Path | str = DEFAULT_SOCK_PATH,
    timeout: float = 180.0,
    poll_interval: float = 0.5,
) -> bool:
    """Block until the service answers a ping, or return False on timeout.

    Useful at consumer startup if there's a chance the service is
    still loading the model.
    """
    sock_path = Path(sock_path)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if sock_path.exists():
            try:
                WhisperClient(sock_path=sock_path, timeout=2.0).ping()
                return True
            except (ConnectionError, OSError, socket.timeout, WhisperClientError):
                pass
        time.sleep(poll_interval)
    return False

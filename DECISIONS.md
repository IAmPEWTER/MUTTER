# Decisions

- **2026-05-17** — Trimmed `mutter/stt.py` and `mutter/whisper_client.py` to drop API surface mutter never uses (Listener.cancel/current_rms/is_recording/`__enter__`/`__exit__`/test seams, WhisperClient.transcribe_file). Why: ~60% of mutter's `.py` lines were vendored verbatim from CLAWS (stt.py) and whisper-service (whisper_client.py), bringing capabilities mutter doesn't call. ~100 LoC removed; all 14 tests pass; fn → text verified. Commit `3d1950b`.
- **2026-05-17** — Deleted `NOTES.md`. Why: 183 lines, 100% historical narrative about Karabiner (removed), tone (never built), SIGUSR1/2 (replaced by CGEventTap), pidfile-vs-pgrep (CLAWS legacy). Nothing in it described current code.

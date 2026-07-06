# desktop/scripts

## Regression test harness

Covers the migration definition-of-done (docs/handy-migration-spec.md §0 goal 2,
§6): real audio -> text, plus the two historical root failures (audio teardown
wedge, missed fn-up).

| Tier | What it proves | Where | Needs |
|------|----------------|-------|-------|
| A | real WAV -> real Silero VAD -> real RemoteSocket engine -> live MLX daemon -> transcript | `src-tauri/tests/real_audio_pipeline.rs` | whisper daemon (skips if socket absent) |
| B | `stop()`/`close()` return in bounded time under a permanently silent producer (audio teardown wedge) | `src-tauri/src/audio_toolkit/audio/recorder.rs` `#[cfg(test)]` | nothing (pure channels) |
| C | fn/globe tap: fn-down starts recording, fn-up is seen (missed fn-up regression) | `smoke/fn_trigger_smoke.sh` | installed/built MUTTER.app + pyobjc |

### Run

```bash
# Fast unit/lib tests (Tier B lives here):
cd desktop/src-tauri && cargo test --lib

# Full pass — lib tests + Tier A real-audio integration:
desktop/scripts/test.sh

# Tier C live fn-trigger smoke (launches MUTTER.app if not running):
desktop/scripts/smoke/fn_trigger_smoke.sh
```

Tier A gates on the whisper socket (`$WHISPER_SOCK` or `$TMPDIR/whisper.sock`):
with the daemon up it asserts the transcript; without it, it prints a skip notice
and passes. The VAD guard (real Silero VAD must find speech in the fixture) always
runs — that is the regression guard for the manual "near-silence -> no transcript"
failure.

### Fixture

`src-tauri/tests/fixtures/fox_16khz_mono.wav` (+ `.expected.txt`) is committed so
CI needs no `say`. Regenerate with `gen_test_fixture.sh` (macOS `say` +
`afconvert`, 16 kHz mono s16le).

## Pre-commit hook

`../../.git/hooks/pre-commit` (repo root is `MUTTER/`, app is the `desktop/`
subtree). It runs `cargo test --lib` **only when `desktop/` files are staged**,
and only the lib tests (fast, daemon-independent). The Tier A integration test is
left to `scripts/test.sh` / CI because it needs the live daemon. The hook is not
auto-installed by anything — it is a plain checked-behavior file at
`.git/hooks/pre-commit` (git hooks live outside the tree and are not committed);
`scripts/test.sh` is the portable entry point CI should call.

## One-line visibility change (noted for merge upkeep)

Tier A needs the `RemoteSocket` client reachable from an external integration
test. `src-tauri/src/lib.rs` re-exports it additively:

```rust
pub use managers::remote_socket;
```

No app code path changed; `remote_socket` and its items were already `pub` within
the (private) `managers` module. Re-apply this one line if a vendor tag merge
drops it.

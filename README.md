<p align="center">
  <img src="docs/logo.png" alt="MUTTER" width="180" />
</p>

# MUTTER

Hold a key, speak, release — your words appear typed at the cursor. Local Whisper, no cloud.

| Platform | Where | Status |
|---|---|---|
| **Mac** — hold **fn / 🌐** | [desktop/](desktop/) | native app (Rust/Tauri), in soak — supersedes the Python daemon in [mac/](mac/) |
| **Android** — hold **volume-down** | [android/](android/) | live (APK) |
| **iOS** | [ios/](ios/) | not started |

The Mac app is a vendored, MUTTER-branded build of [Handy](https://github.com/cjpais/Handy) (MIT) that keeps our shared MLX whisper service as the ASR brain. It replaces the fragile Python audio/event-tap layer with a hardened native one. Seam ledger + upstream-merge rules: [desktop/PATCHES.md](desktop/PATCHES.md). Migration story: [docs/handy-migration-spec.md](docs/handy-migration-spec.md). The Python daemon ([mac/](mac/)) stays installed until the native app clears its soak + parity gate ([docs/parity-R1-R18.md](docs/parity-R1-R18.md)).

Install (Mac, legacy Python daemon): `curl -fsSL https://raw.githubusercontent.com/IAmPEWTER/MUTTER/main/install.sh | bash`

Decisions: cross-platform in [DECISIONS.md](DECISIONS.md), per-platform in each subproject.

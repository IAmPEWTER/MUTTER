# MUTTER

Dictation: hold a key, speak, release — words typed at the cursor. Local STT via the shared service. Map: README.md (platforms: mac/, android/, ios/). Decisions: DECISIONS.md per platform + root.

## Git
- Granular commits — every coherent change its own commit, never end-of-session batches.
- Every commit ends with trailers:
  ```
  Model: <exact model id>
  Session: <session name>
  ```

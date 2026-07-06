//! Client for the shared MLX-whisper daemon (`~/Documents/services/whisper/`).
//!
//! Backs the `RemoteSocket` [`EngineType`](super::model::EngineType): instead
//! of loading local model weights, transcription is delegated to an
//! already-warm out-of-process daemon over a unix socket. See
//! `docs/handy-migration-spec.md` §R8/§4/§5 for why this exists as a
//! standalone, never-restructured file.
//!
//! Wire protocol — MUST stay byte-for-byte compatible with the Python
//! reference client/server (`client.py`/`server.py` in the whisper-service
//! repo), not reimplemented from memory:
//!
//! ```text
//! Request:  [4-byte BE length][JSON header bytes][raw PCM bytes, iff header.pcm_bytes > 0]
//! Response: [4-byte BE length][JSON body bytes]
//! ```
//!
//! PCM mode header: `{"op":"transcribe","sample_rate":u32,"channels":1,
//! "format":"int16","pcm_bytes":usize,"language":string|null,"beam_size":5,
//! "vad_filter":false,"word_timestamps":false,"condition_on_previous_text":false}`.
//! PCM payload is little-endian int16 mono, drained by the server before any
//! header-level validation error (protocol requires this ordering — see
//! `server.py:_handle_transcribe` — otherwise the client's `sendall()` of the
//! PCM body would hit a broken pipe).
//!
//! Ping: `{"op":"ping"}` -> `{"ok":true,"model":...,"uptime_s":...,"version":1}`.
//!
//! Response envelope: `{"ok":true, ...}` on success, `{"ok":false,"error":...,
//! "type":...}` on failure.
//!
//! Stateless by design, matching `WhisperClient`: every call opens a fresh
//! connection, sends, reads, closes. No keep-alive/reconnect bookkeeping.

use anyhow::{anyhow, Context, Result};
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::time::Duration;

use super::model::{EngineType, ModelInfo, ModelSource};

/// Id of the pseudo-model registered for this engine in
/// [`super::model::ModelManager::new`]. Shared by the registration site and
/// every disk-gate special case below, so they can't drift apart.
pub const MODEL_ID: &str = "mlx-whisper-service";
pub const MODEL_NAME: &str = "MLX Whisper (service)";

/// Per-call socket timeout. Generous on purpose — a long dictation clip on a
/// cold GPU (or a daemon mid-model-swap) should not spuriously time out.
/// Matches the Python client's own default.
const TIMEOUT: Duration = Duration::from_secs(120);

/// Fallback language when the caller has no language intent to plumb through
/// at all. In practice `transcription.rs` always has `validated_language` in
/// scope and passes it via [`resolve_language`], so this only fires if some
/// future caller invokes [`RemoteSocketClient::transcribe`] directly with no
/// language plumbing.
const DEFAULT_LANGUAGE: &str = "en";

/// Resolve the daemon's socket path exactly like `client.py`'s
/// `DEFAULT_SOCK_PATH`: `$WHISPER_SOCK` if set and non-empty, else
/// `$TMPDIR/whisper.sock` (`std::env::temp_dir()` falls back to `/tmp` the
/// same way Python's `tempfile.gettempdir()` does when `TMPDIR` is unset).
pub fn sock_path() -> PathBuf {
    match std::env::var("WHISPER_SOCK") {
        Ok(p) if !p.is_empty() => PathBuf::from(p),
        _ => std::env::temp_dir().join("whisper.sock"),
    }
}

/// Thin, stateless client for the whisper unix-socket daemon.
pub struct RemoteSocketClient {
    sock_path: PathBuf,
}

impl RemoteSocketClient {
    pub fn new() -> Self {
        Self {
            sock_path: sock_path(),
        }
    }

    fn connect(&self) -> Result<UnixStream> {
        let stream = UnixStream::connect(&self.sock_path).with_context(|| {
            format!("connect to whisper service at {}", self.sock_path.display())
        })?;
        stream.set_read_timeout(Some(TIMEOUT))?;
        stream.set_write_timeout(Some(TIMEOUT))?;
        Ok(stream)
    }

    fn send_json(stream: &mut UnixStream, value: &Value) -> Result<()> {
        let payload = serde_json::to_vec(value).context("serializing request JSON")?;
        let len = u32::try_from(payload.len())
            .map_err(|_| anyhow!("request payload too large ({} bytes)", payload.len()))?;
        stream
            .write_all(&len.to_be_bytes())
            .context("writing request length prefix")?;
        stream
            .write_all(&payload)
            .context("writing request JSON body")?;
        Ok(())
    }

    fn recv_exact(stream: &mut UnixStream, n: usize) -> Result<Vec<u8>> {
        let mut buf = vec![0u8; n];
        stream
            .read_exact(&mut buf)
            .with_context(|| format!("reading {} bytes from whisper service", n))?;
        Ok(buf)
    }

    fn recv_json(stream: &mut UnixStream) -> Result<Value> {
        let len_bytes = Self::recv_exact(stream, 4)?;
        let len = u32::from_be_bytes(len_bytes.try_into().unwrap()) as usize;
        let body = Self::recv_exact(stream, len)?;
        serde_json::from_slice(&body).context("parsing whisper service response JSON")
    }

    /// Raise a `{"ok": false, ...}` response as an error, mirroring
    /// `WhisperClient._check` / `WhisperClientError` in client.py.
    fn check(resp: Value) -> Result<Value> {
        let ok = resp.get("ok").and_then(Value::as_bool).unwrap_or(false);
        if !ok {
            let error = resp
                .get("error")
                .and_then(Value::as_str)
                .unwrap_or("unknown error");
            let type_ = resp
                .get("type")
                .and_then(Value::as_str)
                .unwrap_or("RuntimeError");
            return Err(anyhow!("whisper service error ({type_}): {error}"));
        }
        Ok(resp)
    }

    /// Round-trip health check (`{"op": "ping"}`). Called at model-load time
    /// so a dead/unreachable daemon surfaces as a load error, never a
    /// mid-dictation failure.
    pub fn ping(&self) -> Result<Value> {
        let mut stream = self.connect()?;
        Self::send_json(&mut stream, &json!({"op": "ping"}))?;
        let resp = Self::recv_json(&mut stream)?;
        Self::check(resp)
    }

    /// Transcribe mono PCM at `sample_rate`. `pcm_f32` is expected in
    /// [-1.0, 1.0] range (standard float audio) and is converted to int16
    /// here — the daemon only speaks int16 PCM (see [`f32_to_i16_pcm`]).
    /// `language` is a two-letter ISO code, or `None` for auto-detect.
    pub fn transcribe(
        &self,
        pcm_f32: &[f32],
        sample_rate: u32,
        language: Option<&str>,
    ) -> Result<String> {
        let pcm = f32_to_i16_pcm(pcm_f32);
        let pcm_bytes = i16_pcm_to_le_bytes(&pcm);

        let header = json!({
            "op": "transcribe",
            "sample_rate": sample_rate,
            "channels": 1,
            "format": "int16",
            "pcm_bytes": pcm_bytes.len(),
            "language": language,
            "beam_size": 5,
            "vad_filter": false,
            "word_timestamps": false,
            "condition_on_previous_text": false,
        });

        let mut stream = self.connect()?;
        Self::send_json(&mut stream, &header)?;
        stream
            .write_all(&pcm_bytes)
            .context("sending PCM payload to whisper service")?;
        let resp = Self::recv_json(&mut stream)?;
        let resp = Self::check(resp)?;
        Ok(resp
            .get("text")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string())
    }
}

impl Default for RemoteSocketClient {
    fn default() -> Self {
        Self::new()
    }
}

/// Convert mono f32 PCM to int16, matching the scale the daemon expects on
/// the way in (it divides by 32768.0 — see `server.py:_handle_transcribe`).
/// Clamped so an out-of-range sample (e.g. a hot mic) saturates rather than
/// wrapping.
pub fn f32_to_i16_pcm(samples: &[f32]) -> Vec<i16> {
    samples
        .iter()
        .map(|&s| {
            (s * 32768.0)
                .round()
                .clamp(i16::MIN as f32, i16::MAX as f32) as i16
        })
        .collect()
}

/// Serialize int16 samples to little-endian bytes. Explicit (rather than
/// native-endian) so the wire format doesn't silently depend on the host's
/// endianness — it happens to match native order on the Apple Silicon boxes
/// both ends of this protocol run on, since that's also what `numpy`'s
/// `ndarray.tobytes()` produces there.
fn i16_pcm_to_le_bytes(pcm: &[i16]) -> Vec<u8> {
    let mut out = Vec::with_capacity(pcm.len() * 2);
    for sample in pcm {
        out.extend_from_slice(&sample.to_le_bytes());
    }
    out
}

/// Build the `language` argument for [`RemoteSocketClient::transcribe`] from
/// Handy's language *intent* (`"auto"` or a language code, as already
/// resolved by `effective_language_for_model`). `"auto"` maps to `None` (the
/// daemon auto-detects); any concrete code passes through unchanged since the
/// daemon/mlx-whisper accepts arbitrary ISO codes, not just Handy's
/// static per-model list. Falls back to [`DEFAULT_LANGUAGE`] only for an
/// empty intent, which the normal settings path never produces.
pub fn resolve_language(intent: &str) -> Option<String> {
    match intent {
        "" => Some(DEFAULT_LANGUAGE.to_string()),
        "auto" => None,
        other => Some(other.to_string()),
    }
}

/// The pseudo-model registered so `RemoteSocket` shows up in the model list
/// like any downloadable engine, but with nothing to download or delete:
/// `ModelSource::Local` + `is_downloaded: true` permanently (see the
/// `update_download_status`/`get_model_path` special cases in `model.rs`
/// that keep it that way — this engine has no on-disk weights at all).
/// `supported_languages` is deliberately left empty: `effective_language`
/// (model.rs) passes an empty-list model's language intent through
/// unchanged, so the daemon (which accepts any code) sees exactly what the
/// user picked instead of Handy's static whisper-language allowlist.
pub fn pseudo_model_info() -> ModelInfo {
    ModelInfo {
        id: MODEL_ID.to_string(),
        name: MODEL_NAME.to_string(),
        description: "Transcribes via the shared, always-warm MLX whisper daemon. No local weights — nothing to download or delete.".to_string(),
        filename: MODEL_ID.to_string(),
        source: ModelSource::Local,
        size_mb: 0,
        is_downloaded: true,
        is_downloading: false,
        partial_size: 0,
        is_directory: false,
        engine_type: EngineType::RemoteSocket,
        accuracy_score: 0.90,
        speed_score: 0.95,
        supports_translation: false,
        is_recommended: false,
        supported_languages: Vec::new(),
        supports_language_selection: true,
        is_custom: false,
        supports_streaming: false,
        supports_language_detection: true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Protocol smoke test against a real running daemon. Sends 1s of
    /// silence and asserts a well-formed ok response. Skips (passes) when no
    /// socket is present so this stays green in CI/other machines where the
    /// whisper service isn't running — the daemon being absent is not a bug
    /// in this client.
    #[test]
    fn ping_and_transcribe_silence_against_live_daemon() {
        let path = sock_path();
        if !path.exists() {
            eprintln!(
                "remote_socket smoke test: no socket at {} — skipping",
                path.display()
            );
            return;
        }

        let client = RemoteSocketClient::new();

        let ping = client
            .ping()
            .expect("ping should succeed against a live daemon");
        assert_eq!(
            ping.get("ok").and_then(Value::as_bool),
            Some(true),
            "ping response missing ok:true: {ping:?}"
        );

        let one_second_silence = vec![0.0f32; 16_000];
        let result = client
            .transcribe(&one_second_silence, 16_000, Some("en"))
            .expect("transcribe should succeed against a live daemon");
        // Silence should transcribe to empty (or near-empty) text, but the
        // real assertion is that we got a well-formed, non-error response at
        // all — text content on silence is not part of the wire contract.
        eprintln!("remote_socket smoke test: transcribed silence -> {result:?}");
    }

    #[test]
    fn f32_to_i16_round_trip_and_clamp() {
        let samples = [0.0f32, 1.0, -1.0, 2.0, -2.0, 0.5];
        let pcm = f32_to_i16_pcm(&samples);
        assert_eq!(pcm[0], 0);
        assert_eq!(pcm[1], i16::MAX);
        assert_eq!(pcm[2], i16::MIN);
        assert_eq!(pcm[3], i16::MAX); // clamps rather than wrapping
        assert_eq!(pcm[4], i16::MIN);
        assert_eq!(pcm[5], 16384);
    }

    #[test]
    fn resolve_language_maps_auto_to_none() {
        assert_eq!(resolve_language("auto"), None);
        assert_eq!(resolve_language("en"), Some("en".to_string()));
        assert_eq!(resolve_language(""), Some(DEFAULT_LANGUAGE.to_string()));
    }
}

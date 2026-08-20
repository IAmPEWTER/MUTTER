//! Client for the shared MLX-whisper daemon (`~/Documents/services/whisper/`).
//!
//! MUTTER keeps its ASR brain out-of-process: a single always-warm model,
//! shared by every consumer, reached over a unix socket. This daemon never
//! loads model weights itself — it decodes + resamples audio to 16 kHz mono
//! and hands it off.
//!
//! Ported verbatim from the vendored Handy build's `remote_socket.rs` (the
//! Handy model-registry glue dropped — only the wire client remains). The wire
//! protocol MUST stay byte-for-byte compatible with the Python reference
//! `client.py`/`server.py`:
//!
//! ```text
//! Request:  [4-byte BE length][JSON header][raw PCM bytes, iff header.pcm_bytes > 0]
//! Response: [4-byte BE length][JSON body]
//! ```
//!
//! PCM payload is little-endian int16 mono at `sample_rate` (the server assumes
//! 16 kHz — `server.py` computes duration as `len/16000`, so the caller must
//! resample). Stateless: every call opens a fresh connection, sends, reads,
//! closes.

use anyhow::{anyhow, Context, Result};
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::time::Duration;

/// Per-call socket timeout. Generous on purpose — a long dictation clip on a
/// cold GPU (or a daemon mid-model-swap) should not spuriously time out.
const TIMEOUT: Duration = Duration::from_secs(120);

/// Resolve the daemon's socket path exactly like `client.py`'s
/// `_default_sock()`: `$STT_SOCK`, else `$WHISPER_SOCK`, else whichever
/// socket is actually present.
///
/// The service was named "whisper" until 2026-08-20. Probing the legacy
/// path keeps this binary working against a daemon that has not been
/// updated yet, so the two can be upgraded in either order.
pub fn sock_path() -> PathBuf {
    for var in ["STT_SOCK", "WHISPER_SOCK"] {
        if let Ok(p) = std::env::var(var) {
            if !p.is_empty() {
                return PathBuf::from(p);
            }
        }
    }
    let tmp = std::env::temp_dir();
    let current = tmp.join("stt.sock");
    if !current.exists() {
        let legacy = tmp.join("whisper.sock");
        if legacy.exists() {
            return legacy;
        }
    }
    current
}

/// Thin, stateless client for the STT unix-socket daemon.
pub struct Client {
    sock_path: PathBuf,
}

impl Client {
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

    /// Raise a `{"ok": false, ...}` response as an error.
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

    /// Round-trip health check (`{"op": "ping"}`). Called at startup so a
    /// dead/unreachable daemon surfaces immediately, not mid-dictation.
    pub fn ping(&self) -> Result<Value> {
        let mut stream = self.connect()?;
        Self::send_json(&mut stream, &json!({"op": "ping"}))?;
        let resp = Self::recv_json(&mut stream)?;
        Self::check(resp)
    }

    /// Transcribe mono f32 PCM (expected in [-1.0, 1.0]) at `sample_rate`.
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

impl Default for Client {
    fn default() -> Self {
        Self::new()
    }
}

/// Convert mono f32 PCM to int16, matching the scale the daemon expects
/// (`server.py` divides by 32768.0). Clamped so a hot mic saturates rather
/// than wrapping.
fn f32_to_i16_pcm(samples: &[f32]) -> Vec<i16> {
    samples
        .iter()
        .map(|&s| {
            (s * 32768.0)
                .round()
                .clamp(i16::MIN as f32, i16::MAX as f32) as i16
        })
        .collect()
}

/// Serialize int16 samples to explicit little-endian bytes (matches numpy's
/// `tobytes()` on the Apple Silicon boxes both ends run on).
fn i16_pcm_to_le_bytes(pcm: &[i16]) -> Vec<u8> {
    let mut out = Vec::with_capacity(pcm.len() * 2);
    for sample in pcm {
        out.extend_from_slice(&sample.to_le_bytes());
    }
    out
}

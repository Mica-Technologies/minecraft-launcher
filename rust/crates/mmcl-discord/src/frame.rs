// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Discord IPC frame: `u32 LE opcode`, `u32 LE payload length`, then the UTF-8 JSON
//! payload.

use serde_json::Value;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::error::IpcError;

/// Payloads larger than this are refused (Discord's own frames are a few KiB at most), so a
/// corrupt length cannot make the reader allocate gigabytes.
pub const MAX_PAYLOAD_LEN: u32 = 1024 * 1024;

/// The frame header size in bytes.
pub const HEADER_LEN: usize = 8;

/// The IPC opcodes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Opcode {
    /// The first frame the client sends: `{"v":1,"client_id":..}`.
    Handshake = 0,
    /// Commands, responses and event dispatches.
    Frame = 1,
    /// Either side closes the connection.
    Close = 2,
    /// Keep-alive from Discord; answered with [`Opcode::Pong`] and the same payload.
    Ping = 3,
    /// The answer to a ping.
    Pong = 4,
}

impl Opcode {
    /// The opcode for a raw value.
    pub fn from_u32(v: u32) -> Result<Opcode, IpcError> {
        Ok(match v {
            0 => Opcode::Handshake,
            1 => Opcode::Frame,
            2 => Opcode::Close,
            3 => Opcode::Ping,
            4 => Opcode::Pong,
            other => return Err(IpcError::UnknownOpcode(other)),
        })
    }
}

/// One decoded frame.
#[derive(Debug, Clone, PartialEq)]
pub struct Frame {
    /// The opcode.
    pub op: Opcode,
    /// The JSON payload (`Value::Null` for an empty payload).
    pub payload: Value,
}

/// Encodes a frame.
pub fn encode(op: Opcode, payload: &Value) -> Vec<u8> {
    let body = payload.to_string().into_bytes();
    let mut out = Vec::with_capacity(HEADER_LEN + body.len());
    out.extend_from_slice(&(op as u32).to_le_bytes());
    out.extend_from_slice(&(body.len() as u32).to_le_bytes());
    out.extend_from_slice(&body);
    out
}

/// Decodes one frame from the front of `buf`. Returns the frame and the number of bytes it
/// used, or `None` when `buf` does not hold a whole frame yet.
pub fn decode(buf: &[u8]) -> Result<Option<(Frame, usize)>, IpcError> {
    if buf.len() < HEADER_LEN {
        return Ok(None);
    }
    let op = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
    let len = u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]);
    let op = Opcode::from_u32(op)?;
    if len > MAX_PAYLOAD_LEN {
        return Err(IpcError::FrameTooLarge(len));
    }
    let total = HEADER_LEN + len as usize;
    if buf.len() < total {
        return Ok(None);
    }
    let payload = parse_payload(&buf[HEADER_LEN..total])?;
    Ok(Some((Frame { op, payload }, total)))
}

fn parse_payload(body: &[u8]) -> Result<Value, IpcError> {
    if body.iter().all(u8::is_ascii_whitespace) {
        return Ok(Value::Null);
    }
    Ok(serde_json::from_slice(body)?)
}

/// Reads one frame. Not cancel-safe: run it in a task of its own rather than in `select!`.
pub async fn read_frame<R: AsyncRead + Unpin + ?Sized>(r: &mut R) -> Result<Frame, IpcError> {
    let mut header = [0u8; HEADER_LEN];
    r.read_exact(&mut header).await?;
    let op = Opcode::from_u32(u32::from_le_bytes([
        header[0], header[1], header[2], header[3],
    ]))?;
    let len = u32::from_le_bytes([header[4], header[5], header[6], header[7]]);
    if len > MAX_PAYLOAD_LEN {
        return Err(IpcError::FrameTooLarge(len));
    }
    let mut body = vec![0u8; len as usize];
    r.read_exact(&mut body).await?;
    Ok(Frame {
        op,
        payload: parse_payload(&body)?,
    })
}

/// Writes one frame and flushes it.
pub async fn write_frame<W: AsyncWrite + Unpin + ?Sized>(
    w: &mut W,
    op: Opcode,
    payload: &Value,
) -> Result<(), IpcError> {
    w.write_all(&encode(op, payload)).await?;
    w.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn encode_writes_little_endian_header_then_json() {
        let bytes = encode(Opcode::Frame, &json!({"a":1}));
        assert_eq!(&bytes[..4], &[1, 0, 0, 0]);
        assert_eq!(&bytes[4..8], &[7, 0, 0, 0]);
        assert_eq!(&bytes[8..], br#"{"a":1}"#);
    }

    #[test]
    fn decode_round_trips_every_opcode() {
        for op in [
            Opcode::Handshake,
            Opcode::Frame,
            Opcode::Close,
            Opcode::Ping,
            Opcode::Pong,
        ] {
            let payload = json!({"v":1,"client_id":"841860482029846528"});
            let bytes = encode(op, &payload);
            let (frame, used) = decode(&bytes).unwrap().unwrap();
            assert_eq!(used, bytes.len());
            assert_eq!(frame, Frame { op, payload });
        }
    }

    #[test]
    fn decode_waits_for_a_whole_frame() {
        let bytes = encode(Opcode::Ping, &json!({"nonce":"x"}));
        for cut in 0..bytes.len() {
            assert!(decode(&bytes[..cut]).unwrap().is_none(), "cut {cut}");
        }
    }

    #[test]
    fn decode_leaves_the_next_frame_in_the_buffer() {
        let mut bytes = encode(Opcode::Frame, &json!({"n":1}));
        let first = bytes.len();
        bytes.extend(encode(Opcode::Frame, &json!({"n":2})));
        let (a, used) = decode(&bytes).unwrap().unwrap();
        assert_eq!(used, first);
        assert_eq!(a.payload["n"], 1);
        let (b, _) = decode(&bytes[used..]).unwrap().unwrap();
        assert_eq!(b.payload["n"], 2);
    }

    #[test]
    fn decode_rejects_unknown_opcodes_and_huge_lengths() {
        let mut bad_op = encode(Opcode::Frame, &json!({}));
        bad_op[0] = 9;
        assert!(matches!(decode(&bad_op), Err(IpcError::UnknownOpcode(9))));

        let mut huge = vec![1, 0, 0, 0];
        huge.extend_from_slice(&(MAX_PAYLOAD_LEN + 1).to_le_bytes());
        assert!(matches!(decode(&huge), Err(IpcError::FrameTooLarge(_))));
    }

    #[test]
    fn empty_payload_is_null() {
        let bytes = [2u8, 0, 0, 0, 0, 0, 0, 0];
        let (frame, _) = decode(&bytes).unwrap().unwrap();
        assert_eq!(frame.op, Opcode::Close);
        assert_eq!(frame.payload, Value::Null);
    }

    #[test]
    fn invalid_json_is_an_error() {
        let mut bytes = vec![1, 0, 0, 0, 3, 0, 0, 0];
        bytes.extend_from_slice(b"{x}");
        assert!(matches!(decode(&bytes), Err(IpcError::Json(_))));
    }

    #[tokio::test]
    async fn async_read_and_write_round_trip() {
        let (mut a, mut b) = tokio::io::duplex(256);
        write_frame(&mut a, Opcode::Frame, &json!({"cmd":"DISPATCH"}))
            .await
            .unwrap();
        write_frame(&mut a, Opcode::Ping, &json!(123))
            .await
            .unwrap();
        let f1 = read_frame(&mut b).await.unwrap();
        assert_eq!(f1.op, Opcode::Frame);
        assert_eq!(f1.payload["cmd"], "DISPATCH");
        let f2 = read_frame(&mut b).await.unwrap();
        assert_eq!(
            f2,
            Frame {
                op: Opcode::Ping,
                payload: json!(123)
            }
        );
    }

    #[tokio::test]
    async fn read_frame_reports_eof() {
        let (a, mut b) = tokio::io::duplex(64);
        drop(a);
        assert!(matches!(read_frame(&mut b).await, Err(IpcError::Io(_))));
    }
}

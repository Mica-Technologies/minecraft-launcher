// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Errors of the Discord IPC client.

use std::io;

/// What went wrong talking to Discord.
#[derive(Debug, thiserror::Error)]
pub enum IpcError {
    /// The pipe or socket failed (including Discord closing it).
    #[error("Discord IPC I/O error: {0}")]
    Io(#[from] io::Error),
    /// A frame announced a payload larger than [`crate::frame::MAX_PAYLOAD_LEN`].
    #[error("Discord IPC frame of {0} bytes is too large")]
    FrameTooLarge(u32),
    /// A frame carried an opcode outside 0..=4.
    #[error("unknown Discord IPC opcode {0}")]
    UnknownOpcode(u32),
    /// A payload was not valid JSON.
    #[error("Discord IPC payload is not valid JSON: {0}")]
    Json(#[from] serde_json::Error),
    /// Discord sent a CLOSE frame.
    #[error("Discord closed the IPC connection: {0}")]
    Closed(String),
    /// Discord answered the handshake with an error.
    #[error("Discord rejected the handshake: {0}")]
    Rejected(String),
    /// No `READY` (or a write did not complete) within the time limit.
    #[error("Discord IPC timed out during {0}")]
    Timeout(&'static str),
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The crate's error type.

use std::io;

/// Everything that can go wrong in the RGB subsystem. Backend failures never reach the user
/// directly: the controller logs them (silently) and feeds them to the circuit breaker.
#[derive(Debug, thiserror::Error)]
pub enum RgbError {
    /// A colour value or hex string that is not a valid 24-bit colour.
    #[error("invalid RGB colour: {0}")]
    InvalidColor(String),
    /// An effect constructor got an invalid argument (period <= 0, empty palette).
    #[error("invalid effect argument: {0}")]
    InvalidEffect(String),
    /// Socket or pipe I/O failed.
    #[error(transparent)]
    Io(#[from] io::Error),
    /// A wire protocol (OpenRGB, Chroma REST, the helper pipe) got something unexpected.
    #[error("protocol error: {0}")]
    Protocol(String),
    /// A vendor SDK or service rejected a call.
    #[error("{0}")]
    Backend(String),
    /// The backend is not usable on this system (no DLL, wrong OS).
    #[error("backend unavailable: {0}")]
    Unavailable(String),
}

impl RgbError {
    /// A [`RgbError::Backend`] from any message.
    pub fn backend(msg: impl Into<String>) -> RgbError {
        RgbError::Backend(msg.into())
    }

    /// A [`RgbError::Protocol`] from any message.
    pub fn protocol(msg: impl Into<String>) -> RgbError {
        RgbError::Protocol(msg.into())
    }
}

/// Result alias for the crate.
pub type RgbResult<T> = Result<T, RgbError>;

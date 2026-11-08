// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The crate's error type.

use std::io;

/// A failed native step.
#[derive(Debug, thiserror::Error)]
pub enum PlatformError {
    /// File or socket I/O failed.
    #[error("{0}")]
    Io(#[from] io::Error),
    /// A Windows API returned a failing HRESULT.
    #[error("{what} HRESULT=0x{code:08X}")]
    Hresult { what: &'static str, code: u32 },
    /// The window has no native handle of the kind this step needs.
    #[error("no native window handle")]
    NoWindowHandle,
    /// The step must run on the main (UI) thread and was called elsewhere.
    #[error("not on the main thread")]
    NotMainThread,
    /// The feature does not exist on this platform or OS version.
    #[error("unsupported on this platform")]
    Unsupported,
    /// A D-Bus call failed (Linux).
    #[error("D-Bus: {0}")]
    DBus(String),
    /// Anything else, already described.
    #[error("{0}")]
    Other(String),
}

#[cfg(windows)]
impl From<windows::core::Error> for PlatformError {
    fn from(e: windows::core::Error) -> Self {
        PlatformError::Hresult {
            what: "Win32",
            code: e.code().0 as u32,
        }
    }
}

#[cfg(windows)]
impl PlatformError {
    /// An HRESULT failure attributed to `what` (the API name).
    pub(crate) fn hr(what: &'static str, e: windows::core::Error) -> Self {
        PlatformError::Hresult {
            what,
            code: e.code().0 as u32,
        }
    }
}

/// Result alias for this crate.
pub type Result<T, E = PlatformError> = std::result::Result<T, E>;

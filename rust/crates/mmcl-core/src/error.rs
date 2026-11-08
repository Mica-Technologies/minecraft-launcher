// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launcher's base error (`LauncherException`).

use std::error::Error as StdError;

/// A boxed underlying cause.
pub type BoxError = Box<dyn StdError + Send + Sync + 'static>;

/// Base error. Higher crates define their own `thiserror` enums and convert into this one where
/// a launcher-level error is expected.
#[derive(thiserror::Error, Debug)]
pub enum Error {
    /// A failure with a message meant for the user (already localized by whoever raised it).
    #[error("{msg}")]
    Launcher {
        msg: String,
        #[source]
        source: Option<BoxError>,
    },
    /// An I/O failure.
    #[error(transparent)]
    Io(#[from] std::io::Error),
    /// A mode-dependent path or setting was requested before the game mode was set.
    #[error("the game mode has not been set yet")]
    ModeUnset,
}

impl Error {
    /// A user-facing error without a cause.
    pub fn launcher(msg: impl Into<String>) -> Error {
        Error::Launcher {
            msg: msg.into(),
            source: None,
        }
    }

    /// A user-facing error with a cause.
    pub fn launcher_with(msg: impl Into<String>, source: impl Into<BoxError>) -> Error {
        Error::Launcher {
            msg: msg.into(),
            source: Some(source.into()),
        }
    }
}

/// `Result` with [`Error`].
pub type Result<T, E = Error> = std::result::Result<T, E>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_and_source() {
        let e = Error::launcher("Could not start");
        assert_eq!(e.to_string(), "Could not start");
        assert!(e.source().is_none());
        let io = std::io::Error::other("disk");
        let e = Error::launcher_with("Could not save", io);
        assert_eq!(e.source().unwrap().to_string(), "disk");
        let e: Error = std::io::Error::other("x").into();
        assert!(matches!(e, Error::Io(_)));
    }
}

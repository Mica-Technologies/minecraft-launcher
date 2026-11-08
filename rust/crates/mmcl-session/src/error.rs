// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Errors of the session crate.

use thiserror::Error;

/// Something a session operation could not do.
#[derive(Debug, Error)]
pub enum SessionError {
    /// A `tokio::process::Child` was handed over outside a tokio runtime, so nothing could wait
    /// on it or read its pipes.
    #[error("{}", mmcl_i18n::t!("session.error.noAsyncRuntime"))]
    NoAsyncRuntime,
}

impl SessionError {
    /// The localization key of the message a user sees.
    pub fn l10n_key(&self) -> &'static str {
        match self {
            SessionError::NoAsyncRuntime => "session.error.noAsyncRuntime",
        }
    }
}

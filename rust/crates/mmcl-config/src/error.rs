// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Errors a caller of this crate can see.

use crate::store::ParseError;

/// A config operation that failed.
#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    /// Exporting the settings to a file failed.
    #[error("{0}")]
    Export(#[source] std::io::Error),
    /// The file to import could not be read as a settings document.
    #[error("{0}")]
    Import(#[source] ParseError),
}

impl ConfigError {
    /// The localization key of the user-facing message (`{0}` is the cause).
    pub fn key(&self) -> &'static str {
        match self {
            ConfigError::Export(_) => "config.error.exportFailed",
            ConfigError::Import(_) => "config.error.importFailed",
        }
    }

    /// The localized message.
    pub fn localized(&self) -> String {
        let cause = match self {
            ConfigError::Export(e) => e.to_string(),
            ConfigError::Import(e) => e.localized(),
        };
        mmcl_i18n::tf!(self.key(), cause)
    }
}

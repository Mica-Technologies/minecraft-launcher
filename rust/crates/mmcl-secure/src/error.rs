// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The cipher's error type.

use std::fmt;

/// Why an encryption or decryption failed.
///
/// Callers that only need "readable or not" treat every error like a missing value. Logs should
/// carry [`CipherError::kind_name`] only (the Java logs the exception's simple class name), never
/// any data.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CipherError {
    /// The text envelope is not valid Base64 (Java: `IllegalArgumentException`).
    InvalidBase64,
    /// The GCM tag did not match: the data was written on another machine or install, or it was
    /// tampered with (Java: `AEADBadTagException`).
    Authentication,
    /// The decrypted bytes are not UTF-8 (text API only).
    InvalidUtf8,
    /// The operating system's random generator failed.
    Random,
    /// The cipher itself failed (a key or nonce of the wrong size; not expected in practice).
    Crypto,
    /// The process-wide cipher was used before [`crate::cipher::init_global`].
    NotInitialized,
}

impl CipherError {
    /// A stable, data-free name for logs, like the Java exception's simple class name.
    pub fn kind_name(&self) -> &'static str {
        match self {
            CipherError::InvalidBase64 => "InvalidBase64",
            CipherError::Authentication => "AuthenticationFailed",
            CipherError::InvalidUtf8 => "InvalidUtf8",
            CipherError::Random => "RandomUnavailable",
            CipherError::Crypto => "CryptoFailure",
            CipherError::NotInitialized => "NotInitialized",
        }
    }

    /// The localization key of the user-facing message.
    pub fn l10n_key(&self) -> &'static str {
        match self {
            CipherError::InvalidBase64 => "secure.cipher.error.invalidBase64",
            CipherError::Authentication => "secure.cipher.error.authentication",
            CipherError::InvalidUtf8 => "secure.cipher.error.invalidUtf8",
            CipherError::Random => "secure.cipher.error.random",
            CipherError::Crypto => "secure.cipher.error.crypto",
            CipherError::NotInitialized => "secure.cipher.error.notInitialized",
        }
    }
}

impl fmt::Display for CipherError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&mmcl_i18n::get(self.l10n_key()))
    }
}

impl std::error::Error for CipherError {}

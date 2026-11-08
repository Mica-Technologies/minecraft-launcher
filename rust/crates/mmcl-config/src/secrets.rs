// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The secret slot in the config (Java `AuthTokenStore`): the CurseForge API key, stored as a
//! Base64 machine-bound AES-256-GCM envelope. The cipher is injected so this crate does not
//! depend on the crypto crate; `mmcl-engine` adapts `mmcl_secure::MachineCipher`.

use std::error::Error;

/// Encrypts and decrypts config secrets as Base64 envelope strings
/// (`salt[16] | iv[12] | ct+tag`, the Java `MachineSecretCipher` format).
pub trait SecretCipher: Send + Sync {
    /// Encrypts `plaintext` into a Base64 envelope.
    fn encrypt(&self, plaintext: &str) -> Result<String, Box<dyn Error + Send + Sync>>;
    /// Decrypts a Base64 envelope. Errors when it was tampered with or bound to another
    /// machine.
    fn decrypt(&self, encoded: &str) -> Result<String, Box<dyn Error + Send + Sync>>;
}

/// Short type name of an error for the `{0}` slot of the cipher warnings (Java prints
/// `getClass().getSimpleName()`; Rust has no runtime type name, so the message is used).
pub(crate) fn describe(e: &(dyn Error + Send + Sync)) -> String {
    e.to_string()
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The machine-bound AES-256-GCM cipher (`MachineSecretCipher` + `AccountCipher`).
//!
//! Byte-compatible with the Java launcher:
//!
//! - **Envelope** (raw): `salt[16] || iv[12] || ciphertext || tag[16]`, fresh random salt and IV
//!   per encryption. The text form is standard padded Base64 of the envelope.
//! - **Key**: PBKDF2-HMAC-SHA256 over the UTF-8 bytes of the [fingerprint](crate::fingerprint),
//!   with the envelope's salt, 65,536 iterations, 32 bytes.
//! - **Cipher**: AES-256-GCM, no AAD, 128-bit tag appended to the ciphertext.
//! - **Decrypt**: an envelope shorter than 28 bytes is `Ok(None)` ("too short", not an error);
//!   a tag mismatch is `Err(CipherError::Authentication)` (another machine or install, or
//!   tampered). There is deliberately no fallback to older fingerprints.
//!
//! PBKDF2 at 65,536 iterations is slow by design: keep calls off the UI thread. Derived keys are
//! cached per salt and fingerprint for the life of a [`MachineCipher`], so reading back a file
//! this process wrote, or reading one file twice, derives once. The format is unaffected.

use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};

use aes_gcm::aead::Aead;
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use base64::Engine;
use base64::alphabet;
use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
use parking_lot::Mutex;
use rand::TryRngCore;
use rand::rngs::OsRng;
use sha2::Sha256;

use crate::error::CipherError;
use crate::fingerprint::HostKeySource;

/// Salt bytes at the start of an envelope.
pub const SALT_LEN: usize = 16;
/// IV (GCM nonce) bytes after the salt.
pub const IV_LEN: usize = 12;
/// GCM tag bytes at the end of the ciphertext.
pub const TAG_LEN: usize = 16;
/// Shorter envelopes decrypt to `Ok(None)`. Note: 28 (salt + IV), not 44; a 28..43-byte input
/// reaches GCM and fails the tag, as in the Java.
pub const MIN_ENVELOPE_LEN: usize = SALT_LEN + IV_LEN;
/// PBKDF2 iteration count.
pub const PBKDF2_ITERATIONS: u32 = 65_536;
/// Derived key bytes (AES-256).
pub const KEY_LEN: usize = 32;

/// Derived keys kept per [`MachineCipher`].
const KEY_CACHE_CAPACITY: usize = 32;

/// Java's `Base64.getEncoder()` / `getDecoder()`: standard alphabet, padded output, and on input
/// padding optional and non-zero trailing bits ignored, like the JDK decoder.
const JAVA_BASE64: GeneralPurpose = GeneralPurpose::new(
    &alphabet::STANDARD,
    GeneralPurposeConfig::new()
        .with_encode_padding(true)
        .with_decode_padding_mode(DecodePaddingMode::Indifferent)
        .with_decode_allow_trailing_bits(true),
);

/// Encrypts and decrypts byte envelopes. The seam credential storage takes (Java
/// `AccountCipher`), so tests can pass a fake.
pub trait Cipher: Send + Sync {
    /// Encrypts `plaintext` into an envelope.
    fn encrypt(&self, plaintext: &[u8]) -> Result<Vec<u8>, CipherError>;
    /// Decrypts an envelope. `Ok(None)` when it is too short to be one; `Err` when it was
    /// tampered with or bound to another machine.
    fn decrypt(&self, envelope: &[u8]) -> Result<Option<Vec<u8>>, CipherError>;
}

/// Where the key's passphrase comes from (`MachineKeySource`). The host implementation is
/// [`HostKeySource`]; tests use [`FixedKeySource`] to simulate other machines.
pub trait KeySource: Send + Sync {
    /// The PBKDF2 passphrase. It must stay the same for the life of the source.
    fn fingerprint(&self) -> String;
}

/// A fixed fingerprint (tests, compatibility vectors, tools).
#[derive(Clone)]
pub struct FixedKeySource(pub String);

impl KeySource for FixedKeySource {
    fn fingerprint(&self) -> String {
        self.0.clone()
    }
}

impl std::fmt::Debug for FixedKeySource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("FixedKeySource(<redacted>)")
    }
}

/// PBKDF2-HMAC-SHA256 over the UTF-8 fingerprint (SunJCE encodes the `char[]` as UTF-8).
pub fn derive_key(fingerprint: &str, salt: &[u8]) -> [u8; KEY_LEN] {
    let mut key = [0u8; KEY_LEN];
    pbkdf2::pbkdf2_hmac::<Sha256>(fingerprint.as_bytes(), salt, PBKDF2_ITERATIONS, &mut key);
    key
}

/// Seals `plaintext` with `key` and an explicit salt and IV into an envelope. Exposed so
/// compatibility tests can produce exactly what the Java produces for the same inputs.
pub fn seal_with(
    key: &[u8; KEY_LEN],
    salt: &[u8; SALT_LEN],
    iv: &[u8; IV_LEN],
    plaintext: &[u8],
) -> Result<Vec<u8>, CipherError> {
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| CipherError::Crypto)?;
    let sealed = cipher
        .encrypt(Nonce::from_slice(iv), plaintext)
        .map_err(|_| CipherError::Crypto)?;
    let mut out = Vec::with_capacity(SALT_LEN + IV_LEN + sealed.len());
    out.extend_from_slice(salt);
    out.extend_from_slice(iv);
    out.extend_from_slice(&sealed);
    Ok(out)
}

/// Splits an envelope and opens it with `key_for(salt)`. `Ok(None)` below 28 bytes.
fn open_with(
    envelope: &[u8],
    key_for: impl FnOnce(&[u8; SALT_LEN]) -> [u8; KEY_LEN],
) -> Result<Option<Vec<u8>>, CipherError> {
    if envelope.len() < MIN_ENVELOPE_LEN {
        return Ok(None);
    }
    let (salt, rest) = envelope.split_at(SALT_LEN);
    let (iv, sealed) = rest.split_at(IV_LEN);
    let salt: &[u8; SALT_LEN] = salt.try_into().map_err(|_| CipherError::Crypto)?;
    let key = key_for(salt);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| CipherError::Crypto)?;
    cipher
        .decrypt(Nonce::from_slice(iv), sealed)
        .map(Some)
        .map_err(|_| CipherError::Authentication)
}

/// Encodes an envelope as the Java text form (standard, padded Base64).
pub fn encode_base64(envelope: &[u8]) -> String {
    JAVA_BASE64.encode(envelope)
}

/// Decodes the Java text form, accepting what `Base64.getDecoder()` accepts.
pub fn decode_base64(text: &str) -> Result<Vec<u8>, CipherError> {
    JAVA_BASE64
        .decode(text)
        .map_err(|_| CipherError::InvalidBase64)
}

fn random_array<const N: usize>() -> Result<[u8; N], CipherError> {
    let mut out = [0u8; N];
    OsRng
        .try_fill_bytes(&mut out)
        .map_err(|_| CipherError::Random)?;
    Ok(out)
}

struct CachedKey {
    salt: [u8; SALT_LEN],
    fingerprint: String,
    key: [u8; KEY_LEN],
}

/// The machine-bound cipher (Java `MachineSecretCipher` / `AccountCipher.machineBound()`).
pub struct MachineCipher {
    source: Arc<dyn KeySource>,
    cache: Mutex<Vec<CachedKey>>,
}

impl MachineCipher {
    /// The host cipher for one launcher config folder (`DataPaths::config_dir`); its install
    /// secret is `config_dir/machine-key.bin`. Nothing is read until the first use.
    pub fn new(config_dir: impl Into<PathBuf>) -> Self {
        let dir: PathBuf = config_dir.into();
        Self::with_source(Arc::new(HostKeySource::new(&dir)))
    }

    /// The host cipher for the data folder in `paths`.
    pub fn for_paths(paths: &mmcl_core::DataPaths) -> Self {
        Self::new(paths.config_dir())
    }

    /// A cipher over any key source (tests: a [`FixedKeySource`] per simulated machine).
    pub fn with_source(source: Arc<dyn KeySource>) -> Self {
        Self {
            source,
            cache: Mutex::new(Vec::new()),
        }
    }

    fn key_for(&self, salt: &[u8; SALT_LEN]) -> [u8; KEY_LEN] {
        let fingerprint = self.source.fingerprint();
        if let Some(hit) = self
            .cache
            .lock()
            .iter()
            .find(|c| &c.salt == salt && c.fingerprint == fingerprint)
        {
            return hit.key;
        }
        let key = derive_key(&fingerprint, salt);
        let mut cache = self.cache.lock();
        if cache.len() >= KEY_CACHE_CAPACITY {
            cache.remove(0);
        }
        cache.push(CachedKey {
            salt: *salt,
            fingerprint,
            key,
        });
        key
    }

    /// Encrypts bytes into a raw envelope (`encryptBytes`).
    pub fn encrypt_bytes(&self, plaintext: &[u8]) -> Result<Vec<u8>, CipherError> {
        let salt = random_array::<SALT_LEN>()?;
        let iv = random_array::<IV_LEN>()?;
        let key = self.key_for(&salt);
        seal_with(&key, &salt, &iv, plaintext)
    }

    /// Decrypts a raw envelope (`decryptBytes`).
    pub fn decrypt_bytes(&self, envelope: &[u8]) -> Result<Option<Vec<u8>>, CipherError> {
        open_with(envelope, |salt| self.key_for(salt))
    }

    /// Encrypts text into a Base64 envelope (`encrypt`).
    pub fn encrypt_str(&self, plaintext: &str) -> Result<String, CipherError> {
        self.encrypt_bytes(plaintext.as_bytes())
            .map(|e| encode_base64(&e))
    }

    /// Decrypts a Base64 envelope (`decrypt`). Empty or blank input, and envelopes too short to
    /// be valid, give `Ok(None)`; invalid Base64 gives `Err(InvalidBase64)`.
    pub fn decrypt_str(&self, encoded: &str) -> Result<Option<String>, CipherError> {
        if crate::javastr::is_blank(encoded) {
            return Ok(None);
        }
        let envelope = decode_base64(encoded)?;
        match self.decrypt_bytes(&envelope)? {
            Some(plain) => String::from_utf8(plain)
                .map(Some)
                .map_err(|_| CipherError::InvalidUtf8),
            None => Ok(None),
        }
    }
}

impl Cipher for MachineCipher {
    fn encrypt(&self, plaintext: &[u8]) -> Result<Vec<u8>, CipherError> {
        self.encrypt_bytes(plaintext)
    }

    fn decrypt(&self, envelope: &[u8]) -> Result<Option<Vec<u8>>, CipherError> {
        self.decrypt_bytes(envelope)
    }
}

impl std::fmt::Debug for MachineCipher {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("MachineCipher").finish_non_exhaustive()
    }
}

// ---- process-wide cipher -------------------------------------------------------------------

static GLOBAL: OnceLock<Arc<MachineCipher>> = OnceLock::new();

/// Installs the process-wide host cipher for `config_dir`, once the data root is known (never
/// before mode detection). Returns `false` when one was already installed; the first stays.
pub fn init_global(config_dir: &Path) -> bool {
    install_global(Arc::new(MachineCipher::new(config_dir)))
}

/// Installs a specific process-wide cipher. Returns `false` when one was already installed.
pub fn install_global(cipher: Arc<MachineCipher>) -> bool {
    GLOBAL.set(cipher).is_ok()
}

/// The process-wide cipher, if installed.
pub fn global() -> Option<Arc<MachineCipher>> {
    GLOBAL.get().cloned()
}

fn require_global() -> Result<&'static MachineCipher, CipherError> {
    GLOBAL
        .get()
        .map(|c| c.as_ref())
        .ok_or(CipherError::NotInitialized)
}

/// [`MachineCipher::encrypt_str`] on the process-wide cipher.
pub fn encrypt(plaintext: &str) -> Result<String, CipherError> {
    require_global()?.encrypt_str(plaintext)
}

/// [`MachineCipher::decrypt_str`] on the process-wide cipher. Blank input is `Ok(None)` even
/// before initialisation, as it never reaches the cipher.
pub fn decrypt(encoded: &str) -> Result<Option<String>, CipherError> {
    if crate::javastr::is_blank(encoded) {
        return Ok(None);
    }
    require_global()?.decrypt_str(encoded)
}

/// [`MachineCipher::encrypt_bytes`] on the process-wide cipher.
pub fn encrypt_bytes(plaintext: &[u8]) -> Result<Vec<u8>, CipherError> {
    require_global()?.encrypt_bytes(plaintext)
}

/// [`MachineCipher::decrypt_bytes`] on the process-wide cipher. Short input is `Ok(None)` even
/// before initialisation.
pub fn decrypt_bytes(envelope: &[u8]) -> Result<Option<Vec<u8>>, CipherError> {
    if envelope.len() < MIN_ENVELOPE_LEN {
        return Ok(None);
    }
    require_global()?.decrypt_bytes(envelope)
}

#[cfg(test)]
mod tests;

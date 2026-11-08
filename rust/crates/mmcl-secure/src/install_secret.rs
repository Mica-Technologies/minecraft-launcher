// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The per-install random secret, `<config>/machine-key.bin` (32 raw bytes, owner-only).
//!
//! It is always mixed into the cipher fingerprint, so a sibling local user who can read the
//! user name, `os.name` and hardware UUID still cannot rebuild the key without also reading this
//! owner-only file.

use std::collections::HashMap;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use parking_lot::Mutex;
use rand::TryRngCore;
use rand::rngs::OsRng;

/// The secret's file name inside the launcher config folder.
pub const FILE_NAME: &str = "machine-key.bin";

/// Bytes in a freshly generated secret (and the minimum an existing file must hold).
pub const SECRET_BYTES: usize = 32;

/// `<config_dir>/machine-key.bin`.
pub fn path_in(config_dir: &Path) -> PathBuf {
    config_dir.join(FILE_NAME)
}

/// Lower-case hex of every byte (`%02x` per byte, as the Java writes it).
pub fn to_hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        let _ = write!(out, "{b:02x}");
    }
    out
}

/// The secret as the fingerprint uses it.
#[derive(Clone, PartialEq, Eq)]
pub struct InstallSecret {
    /// Lower-case hex of all the bytes.
    pub hex: String,
    /// `false` when the secret could not be persisted and only lives in this process.
    pub persisted: bool,
}

impl std::fmt::Debug for InstallSecret {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Never print the secret.
        f.debug_struct("InstallSecret")
            .field("hex", &"<redacted>")
            .field("persisted", &self.persisted)
            .finish()
    }
}

/// Reads the secret at `path`, creating it when it is missing or shorter than 32 bytes.
///
/// - A regular file of at least 32 bytes: the hex of **all** its bytes (even past 32).
/// - Otherwise 32 fresh CSPRNG bytes are written (parent folders created, owner-only before
///   the file appears under its real name) and used.
/// - When anything fails, a warning (`log.machineCipher.persistSecretFailed`) is logged and a
///   fresh in-memory secret is used: values encrypted by this process then cannot be decrypted
///   after a restart. The Java launcher accepts the same.
pub fn load_or_create(path: &Path) -> InstallSecret {
    match try_load_or_create(path) {
        Ok(hex) => InstallSecret {
            hex,
            persisted: true,
        },
        Err(e) => {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!(
                    "log.machineCipher.persistSecretFailed",
                    format!("{:?}", e.kind())
                )
            );
            InstallSecret {
                hex: to_hex(&ephemeral_bytes()),
                persisted: false,
            }
        }
    }
}

/// [`load_or_create`], resolved once per path for the whole process (the Java caches the secret
/// in a static under a class-wide lock). Every cipher over the same config folder therefore uses
/// the same secret, even an ephemeral one, and two ciphers starting at once cannot both create
/// the file and each keep a different secret (which would make one of them write values that
/// no later process can read).
pub fn cached(path: &Path) -> InstallSecret {
    static SECRETS: OnceLock<Mutex<HashMap<PathBuf, InstallSecret>>> = OnceLock::new();
    let mut secrets = SECRETS.get_or_init(|| Mutex::new(HashMap::new())).lock();
    secrets
        .entry(path.to_path_buf())
        .or_insert_with(|| load_or_create(path))
        .clone()
}

fn try_load_or_create(path: &Path) -> io::Result<String> {
    if std::fs::metadata(path)
        .map(|m| m.is_file())
        .unwrap_or(false)
    {
        let existing = std::fs::read(path)?;
        if existing.len() >= SECRET_BYTES {
            return Ok(to_hex(&existing));
        }
        // Corrupt or truncated: regenerate below.
    }
    let mut fresh = [0u8; SECRET_BYTES];
    OsRng
        .try_fill_bytes(&mut fresh)
        .map_err(|e| io::Error::other(e.to_string()))?;
    let outcome = mmcl_core::fsx::atomic_write(path, &fresh, true)?;
    if let Some(e) = outcome.permission_error {
        crate::perms::log_tighten_failure(path, &e);
    }
    Ok(to_hex(&fresh))
}

fn ephemeral_bytes() -> [u8; SECRET_BYTES] {
    let mut fresh = [0u8; SECRET_BYTES];
    if OsRng.try_fill_bytes(&mut fresh).is_err() {
        // The OS generator is gone; the thread generator is seeded from it at startup and is
        // still a CSPRNG.
        rand::RngCore::fill_bytes(&mut rand::rng(), &mut fresh);
    }
    fresh
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_is_lower_case_two_digits_per_byte() {
        assert_eq!(to_hex(&[0x00, 0x0a, 0xff, 0x7f]), "000aff7f");
    }

    #[test]
    fn a_missing_secret_is_created_and_then_reused() {
        let dir = tempfile::tempdir().unwrap();
        let path = path_in(&dir.path().join("config"));
        let first = load_or_create(&path);
        assert!(first.persisted);
        assert_eq!(first.hex.len(), 64);
        let bytes = std::fs::read(&path).unwrap();
        assert_eq!(bytes.len(), 32);
        assert_eq!(to_hex(&bytes), first.hex);
        assert_eq!(load_or_create(&path), first);
        assert!(!mmcl_core::fsx::tmp_path_for(&path).exists());
    }

    #[test]
    fn every_byte_of_a_long_secret_is_used() {
        let dir = tempfile::tempdir().unwrap();
        let path = path_in(dir.path());
        let long: Vec<u8> = (0..40u8).collect();
        std::fs::write(&path, &long).unwrap();
        assert_eq!(load_or_create(&path).hex, to_hex(&long));
    }

    #[test]
    fn a_truncated_secret_is_replaced() {
        let dir = tempfile::tempdir().unwrap();
        let path = path_in(dir.path());
        std::fs::write(&path, [1u8; 31]).unwrap();
        let secret = load_or_create(&path);
        assert!(secret.persisted);
        let bytes = std::fs::read(&path).unwrap();
        assert_eq!(bytes.len(), 32);
        assert_ne!(bytes, vec![1u8; 32]);
        assert_eq!(secret.hex, to_hex(&bytes));
    }

    #[test]
    fn an_unwritable_location_falls_back_to_an_ephemeral_secret() {
        let dir = tempfile::tempdir().unwrap();
        // A directory where the file should be: not a regular file, and it cannot be replaced.
        let path = path_in(dir.path());
        std::fs::create_dir(&path).unwrap();
        std::fs::write(path.join("keep"), b"x").unwrap();
        let a = load_or_create(&path);
        let b = load_or_create(&path);
        assert!(!a.persisted);
        assert_eq!(a.hex.len(), 64);
        assert_ne!(a.hex, b.hex);
    }

    #[test]
    fn the_cached_secret_is_shared_per_path_even_when_ephemeral() {
        let dir = tempfile::tempdir().unwrap();
        // Unwritable: each uncached call would make a different ephemeral secret.
        let blocked = path_in(dir.path());
        std::fs::create_dir(&blocked).unwrap();
        std::fs::write(blocked.join("keep"), b"x").unwrap();
        let a = cached(&blocked);
        assert!(!a.persisted);
        assert_eq!(cached(&blocked), a);

        let other = path_in(&dir.path().join("other"));
        let b = cached(&other);
        assert!(b.persisted);
        assert_ne!(a.hex, b.hex);
        // Replacing the file later does not change this process's secret (Java: same).
        std::fs::write(&other, [9u8; 32]).unwrap();
        assert_eq!(cached(&other), b);
    }

    #[test]
    fn concurrent_first_uses_agree_on_one_secret() {
        let dir = tempfile::tempdir().unwrap();
        let path = path_in(dir.path());
        let secrets: Vec<InstallSecret> = std::thread::scope(|scope| {
            let handles: Vec<_> = (0..8).map(|_| scope.spawn(|| cached(&path))).collect();
            handles.into_iter().map(|h| h.join().unwrap()).collect()
        });
        assert!(secrets.iter().all(|s| s == &secrets[0]));
        assert_eq!(to_hex(&std::fs::read(&path).unwrap()), secrets[0].hex);
    }

    #[test]
    fn debug_never_prints_the_secret() {
        let secret = InstallSecret {
            hex: "deadbeef".into(),
            persisted: true,
        };
        assert!(!format!("{secret:?}").contains("deadbeef"));
    }

    #[cfg(unix)]
    #[test]
    fn a_new_secret_is_owner_only() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let path = path_in(dir.path());
        load_or_create(&path);
        let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
    }
}

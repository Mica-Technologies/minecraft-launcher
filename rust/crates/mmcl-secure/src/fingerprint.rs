// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The PBKDF2 passphrase the machine key is derived from:
//!
//! ```text
//! <user> "|" <os.name> "|" <hardware uuid> "|" "install:" <install secret hex>
//! ```
//!
//! - `user`: env `USER` if non-empty, else env `USERNAME` if non-empty, else Java's `user.name`
//!   (the account name from `GetUserNameW` on Windows, the `getpwuid(getuid())` name on Unix).
//! - `os.name`: [`crate::os_name::java_os_name`].
//! - hardware uuid: [`crate::hwuuid::hardware_uuid`].
//! - install secret: [`crate::install_secret`].
//!
//! Never add volatile inputs. A network MAC address used to be part of it; macOS randomizes
//! MACs, so the key rotated and about 90% of macOS launches failed to renew their tokens.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use crate::cipher::KeySource;
use crate::install_secret;

/// Assembles the fingerprint. `None` components become `""`. Pure, so the exact format is
/// tested without hardware.
pub fn assemble_fingerprint(
    user: Option<&str>,
    os_name: Option<&str>,
    hw_uuid: Option<&str>,
    install_secret_hex: &str,
) -> String {
    format!(
        "{}|{}|{}|install:{}",
        user.unwrap_or(""),
        os_name.unwrap_or(""),
        hw_uuid.unwrap_or(""),
        install_secret_hex
    )
}

/// `MachineSecretCipher.resolveOsUsername` over explicit values (pure).
pub fn pick_username(
    env_user: Option<String>,
    env_username: Option<String>,
    java_user_name: impl FnOnce() -> String,
) -> String {
    env_user
        .filter(|u| !u.is_empty())
        .or_else(|| env_username.filter(|u| !u.is_empty()))
        .unwrap_or_else(java_user_name)
}

/// The user name the Java launcher puts into the fingerprint on this machine.
pub fn os_username() -> String {
    let env = |name: &str| std::env::var_os(name).map(|v| v.to_string_lossy().into_owned());
    pick_username(env("USER"), env("USERNAME"), java_user_name)
}

/// Java's `user.name` system property.
pub fn java_user_name() -> String {
    imp::user_name()
}

#[cfg(windows)]
mod imp {
    use windows::Win32::System::WindowsProgramming::GetUserNameW;
    use windows::core::PWSTR;

    pub fn user_name() -> String {
        // UNLEN + 1.
        let mut buffer = [0u16; 257];
        let mut len = buffer.len() as u32;
        // SAFETY: `buffer` holds `len` u16s; GetUserNameW writes at most that many.
        let ok = unsafe { GetUserNameW(Some(PWSTR(buffer.as_mut_ptr())), &mut len) };
        if ok.is_err() || len == 0 {
            // The JDK's Windows java_props_md.c falls back to user.name "unknown" when
            // GetUserNameW fails.
            return "unknown".to_string();
        }
        // `len` includes the terminating NUL.
        String::from_utf16_lossy(&buffer[..(len as usize).saturating_sub(1)])
    }
}

#[cfg(unix)]
mod imp {
    use std::ffi::CStr;

    pub fn user_name() -> String {
        // SAFETY: getpwuid_r writes into `pwd` and `buffer`, both owned here; `result` is either
        // null or points at `pwd`, whose name points into `buffer`.
        unsafe {
            let mut buffer = vec![0 as libc::c_char; 16 * 1024];
            let mut pwd: libc::passwd = std::mem::zeroed();
            let mut result: *mut libc::passwd = std::ptr::null_mut();
            let rc = libc::getpwuid_r(
                libc::getuid(),
                &mut pwd,
                buffer.as_mut_ptr(),
                buffer.len(),
                &mut result,
            );
            if rc != 0 || result.is_null() || pwd.pw_name.is_null() {
                // The JDK reports "?" when the uid has no passwd entry.
                return "?".to_string();
            }
            CStr::from_ptr(pwd.pw_name).to_string_lossy().into_owned()
        }
    }
}

#[cfg(not(any(windows, unix)))]
mod imp {
    pub fn user_name() -> String {
        String::new()
    }
}

/// The host's real fingerprint, for one launcher config folder.
///
/// Every part is resolved once, on first use, and then reused for the life of the value (oshi
/// memoizes the hardware UUID and the Java caches the install secret the same way). Creating
/// it touches nothing; the first [`KeySource::fingerprint`] call reads (or creates)
/// `machine-key.bin`.
pub struct HostKeySource {
    secret_path: PathBuf,
    fingerprint: OnceLock<String>,
}

impl HostKeySource {
    /// A source whose install secret lives in `config_dir/machine-key.bin`.
    pub fn new(config_dir: &Path) -> Self {
        Self {
            secret_path: install_secret::path_in(config_dir),
            fingerprint: OnceLock::new(),
        }
    }

    /// Where the install secret is read from.
    pub fn secret_path(&self) -> &Path {
        &self.secret_path
    }
}

impl KeySource for HostKeySource {
    fn fingerprint(&self) -> String {
        self.fingerprint
            .get_or_init(|| {
                let user = os_username();
                let os = crate::os_name::java_os_name();
                let hw = crate::hwuuid::hardware_uuid();
                let secret = install_secret::cached(&self.secret_path);
                assemble_fingerprint(Some(&user), Some(&os), Some(&hw), &secret.hex)
            })
            .clone()
    }
}

impl std::fmt::Debug for HostKeySource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HostKeySource")
            .field("secret_path", &self.secret_path)
            .finish_non_exhaustive()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fingerprint_always_appends_the_install_secret() {
        assert_eq!(
            assemble_fingerprint(
                Some("alice"),
                Some("Windows 11"),
                Some("UUID-1234"),
                "SECRET"
            ),
            "alice|Windows 11|UUID-1234|install:SECRET"
        );
        // No UUID: empty UUID slot, but the secret is still mixed in.
        assert_eq!(
            assemble_fingerprint(Some("alice"), Some("Linux"), Some(""), "SECRET"),
            "alice|Linux||install:SECRET"
        );
    }

    #[test]
    fn fingerprint_includes_the_install_secret() {
        let fp = assemble_fingerprint(
            Some("alice"),
            Some("Windows 11"),
            Some("UUID-1234"),
            "SECRET",
        );
        assert!(fp.contains("install:SECRET"));
    }

    #[test]
    fn null_components_are_treated_as_empty() {
        assert_eq!(
            assemble_fingerprint(None, None, None, "SECRET"),
            "|||install:SECRET"
        );
    }

    #[test]
    fn username_prefers_user_then_username_then_the_account_name() {
        let never = || panic!("account name should not be consulted");
        assert_eq!(
            pick_username(Some("a".into()), Some("b".into()), never),
            "a"
        );
        assert_eq!(
            pick_username(Some(String::new()), Some("b".into()), never),
            "b"
        );
        assert_eq!(pick_username(None, Some("b".into()), never), "b");
        assert_eq!(pick_username(None, Some(String::new()), || "c".into()), "c");
        assert_eq!(pick_username(None, None, || "c".into()), "c");
    }

    #[test]
    fn the_account_name_is_known() {
        assert!(!java_user_name().is_empty());
    }

    #[test]
    fn the_host_fingerprint_is_stable_and_creates_the_secret_lazily() {
        let dir = tempfile::tempdir().unwrap();
        let source = HostKeySource::new(dir.path());
        assert!(!source.secret_path().exists());
        let fp = source.fingerprint();
        assert!(source.secret_path().exists());
        assert_eq!(fp, source.fingerprint());
        let secret = install_secret::cached(source.secret_path());
        assert!(fp.ends_with(&format!("|install:{}", secret.hex)));
        assert_eq!(
            fp.matches('|').count(),
            3 + os_username().matches('|').count()
        );
        assert!(fp.starts_with(&format!(
            "{}|{}|{}|",
            os_username(),
            crate::os_name::java_os_name(),
            crate::hwuuid::hardware_uuid()
        )));
    }

    /// Cross-checks against a JDK: set `MMCL_PARITY_USER` to the user value the Java resolves
    /// (`USER`, else `USERNAME`, else `user.name`).
    #[test]
    fn username_matches_java_when_told_its_value() {
        if let Ok(expected) = std::env::var("MMCL_PARITY_USER") {
            assert_eq!(os_username(), expected);
        }
    }
}

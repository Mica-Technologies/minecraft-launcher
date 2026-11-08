// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Owner-only permissions for credential files and folders (`FilePermissions`).
//!
//! Best effort: a failure is logged (`log.filePermissions.posixTightenFailed` on Unix,
//! `log.filePermissions.aclTightenFailed` on Windows) and never fails the caller's write.
//! Callers must not assume the tightening worked. Both calls are idempotent.
//!
//! - Unix: files `0600`, folders `0700`. The two are separate calls on purpose: a folder at
//!   `0600` has no execute bit and locks out its own owner.
//! - Windows: the DACL is replaced by one protected ALLOW entry giving the owner full control
//!   (folders' entry is inherited by new children), so inherited entries cannot come back.

use std::io;
use std::path::Path;

/// Restricts a file to its owner (`applyOwnerOnly`). Returns whether it worked.
pub fn owner_only(path: &Path) -> bool {
    apply(path, false)
}

/// Restricts a folder to its owner, keeping it traversable (`applyOwnerOnlyDirectory`).
/// Returns whether it worked.
pub fn owner_only_dir(path: &Path) -> bool {
    apply(path, true)
}

fn apply(path: &Path, is_dir: bool) -> bool {
    match mmcl_core::fsx::owner_only(path, is_dir) {
        Ok(()) => true,
        Err(e) => {
            log_tighten_failure(path, &e);
            false
        }
    }
}

/// Logs a failed tightening of `path` with the platform's message.
pub(crate) fn log_tighten_failure(path: &Path, error: &io::Error) {
    let key = if cfg!(windows) {
        "log.filePermissions.aclTightenFailed"
    } else {
        "log.filePermissions.posixTightenFailed"
    };
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    tracing::warn!(
        "{}",
        mmcl_i18n::tf!(key, name, format!("{:?}", error.kind()))
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_missing_path_fails_without_panicking() {
        let dir = tempfile::tempdir().unwrap();
        assert!(!owner_only(&dir.path().join("missing")));
        assert!(!owner_only_dir(&dir.path().join("missing-dir")));
    }

    #[cfg(unix)]
    mod unix {
        use super::super::*;
        use std::os::unix::fs::PermissionsExt;

        fn mode(path: &Path) -> u32 {
            std::fs::metadata(path).unwrap().permissions().mode() & 0o777
        }

        #[test]
        fn apply_owner_only_restricts_file_to_owner_read_write() {
            let dir = tempfile::tempdir().unwrap();
            let file = dir.path().join("secret.txt");
            std::fs::write(&file, "token").unwrap();
            assert!(owner_only(&file));
            assert_eq!(mode(&file), 0o600);
        }

        #[test]
        fn apply_owner_only_directory_restricts_directory_to_owner_rwx() {
            let dir = tempfile::tempdir().unwrap();
            let sub = dir.path().join("profile-dir");
            std::fs::create_dir(&sub).unwrap();
            assert!(owner_only_dir(&sub));
            assert_eq!(mode(&sub), 0o700);
            // Still usable by its owner.
            std::fs::write(sub.join("inner"), b"x").unwrap();
        }

        #[test]
        fn apply_owner_only_is_idempotent() {
            let dir = tempfile::tempdir().unwrap();
            let file = dir.path().join("secret.txt");
            std::fs::write(&file, "token").unwrap();
            owner_only(&file);
            owner_only(&file);
            assert_eq!(mode(&file), 0o600);
        }

        #[test]
        fn apply_owner_only_tightens_already_permissive_file() {
            let dir = tempfile::tempdir().unwrap();
            let file = dir.path().join("secret.txt");
            std::fs::write(&file, "token").unwrap();
            std::fs::set_permissions(&file, std::fs::Permissions::from_mode(0o644)).unwrap();
            owner_only(&file);
            assert_eq!(mode(&file), 0o600);
        }
    }

    /// The Windows variant reads the DACL back: one ALLOW entry, for the owner, protected.
    #[cfg(windows)]
    mod windows_acl {
        use super::super::*;
        use std::os::windows::ffi::OsStrExt;
        use std::ptr::null_mut;
        use windows::Win32::Foundation::{HLOCAL, LocalFree};
        use windows::Win32::Security::Authorization::{GetNamedSecurityInfoW, SE_FILE_OBJECT};
        use windows::Win32::Security::{
            ACCESS_ALLOWED_ACE, ACE_HEADER, ACL, ACL_SIZE_INFORMATION, AclSizeInformation,
            DACL_SECURITY_INFORMATION, EqualSid, GetAce, GetAclInformation,
            GetSecurityDescriptorControl, OWNER_SECURITY_INFORMATION, PSECURITY_DESCRIPTOR, PSID,
            SE_DACL_PROTECTED,
        };
        use windows::core::PCWSTR;

        struct Dacl {
            entries: u32,
            allow_for_owner: bool,
            protected: bool,
        }

        fn read_dacl(path: &Path) -> Dacl {
            let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
            // SAFETY: out-pointers are locals; the descriptor is freed with LocalFree.
            unsafe {
                let mut owner = PSID(null_mut());
                let mut dacl: *mut ACL = null_mut();
                let mut sd = PSECURITY_DESCRIPTOR(null_mut());
                let rc = GetNamedSecurityInfoW(
                    PCWSTR(wide.as_ptr()),
                    SE_FILE_OBJECT,
                    OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
                    Some(&mut owner),
                    None,
                    Some(&mut dacl),
                    None,
                    &mut sd,
                );
                assert!(rc.is_ok(), "GetNamedSecurityInfoW: {rc:?}");
                let mut info = ACL_SIZE_INFORMATION::default();
                GetAclInformation(
                    dacl,
                    &mut info as *mut _ as *mut _,
                    std::mem::size_of::<ACL_SIZE_INFORMATION>() as u32,
                    AclSizeInformation,
                )
                .unwrap();
                // Every entry must be an ALLOW entry for the owner. (A folder's inheritable
                // GENERIC_ALL entry is stored as an effective entry plus an inherit-only one.)
                let mut allow_for_owner = info.AceCount > 0;
                for index in 0..info.AceCount {
                    let mut ace: *mut core::ffi::c_void = null_mut();
                    GetAce(dacl, index, &mut ace).unwrap();
                    let header = &*(ace as *const ACE_HEADER);
                    let allowed = ace as *const ACCESS_ALLOWED_ACE;
                    let sid = PSID(std::ptr::addr_of!((*allowed).SidStart) as *mut _);
                    allow_for_owner &= header.AceType == 0 && EqualSid(sid, owner).is_ok();
                }
                let mut control = 0u16;
                let mut revision = 0u32;
                GetSecurityDescriptorControl(sd, &mut control, &mut revision).unwrap();
                let _ = LocalFree(Some(HLOCAL(sd.0)));
                Dacl {
                    entries: info.AceCount,
                    allow_for_owner,
                    protected: control & SE_DACL_PROTECTED.0 != 0,
                }
            }
        }

        #[test]
        fn a_file_gets_a_single_protected_owner_entry() {
            let dir = tempfile::tempdir().unwrap();
            let file = dir.path().join("secret.txt");
            std::fs::write(&file, "token").unwrap();
            assert!(owner_only(&file));
            let dacl = read_dacl(&file);
            assert_eq!(dacl.entries, 1);
            assert!(dacl.allow_for_owner);
            assert!(dacl.protected);
            // The owner can still read and rewrite it.
            assert_eq!(std::fs::read_to_string(&file).unwrap(), "token");
            std::fs::write(&file, "again").unwrap();
        }

        #[test]
        fn a_folder_stays_usable_by_its_owner() {
            let dir = tempfile::tempdir().unwrap();
            let sub = dir.path().join("profile-dir");
            std::fs::create_dir(&sub).unwrap();
            assert!(owner_only_dir(&sub));
            assert!(owner_only_dir(&sub));
            let dacl = read_dacl(&sub);
            assert!(dacl.entries <= 2, "{} entries", dacl.entries);
            assert!(dacl.allow_for_owner);
            assert!(dacl.protected);
            std::fs::write(sub.join("inner"), b"x").unwrap();
            assert_eq!(std::fs::read(sub.join("inner")).unwrap(), b"x");
        }
    }
}

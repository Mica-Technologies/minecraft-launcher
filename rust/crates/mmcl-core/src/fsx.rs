// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Small file-system helpers with launcher-specific guarantees: owner-only permissions
//! (`FilePermissions`), atomic replace-by-rename, and the "move the data folder aside" step of
//! a launcher reset.
//!
//! Nothing here logs: results say what happened so the caller can log a localized line.

use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

/// Restricts a file or folder to its owner.
///
/// - Unix: mode `0600` (file) or `0700` (folder).
/// - Windows: a protected DACL with one ACE granting the owner full control, replacing the
///   inherited entries. Folders' ACE is inherited by new children, so files created inside stay
///   owner-only.
/// - Elsewhere: nothing.
pub fn owner_only(path: &Path, is_dir: bool) -> io::Result<()> {
    imp::owner_only(path, is_dir)
}

#[cfg(unix)]
mod imp {
    use std::io;
    use std::os::unix::fs::PermissionsExt;
    use std::path::Path;

    pub fn owner_only(path: &Path, is_dir: bool) -> io::Result<()> {
        let mode = if is_dir { 0o700 } else { 0o600 };
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))
    }
}

#[cfg(windows)]
mod imp {
    use std::io;
    use std::os::windows::ffi::OsStrExt;
    use std::path::Path;
    use std::ptr::{null, null_mut};

    use windows_sys::Win32::Foundation::{GENERIC_ALL, LocalFree};
    use windows_sys::Win32::Security::Authorization::{
        EXPLICIT_ACCESS_W, GetNamedSecurityInfoW, NO_MULTIPLE_TRUSTEE, SE_FILE_OBJECT, SET_ACCESS,
        SetEntriesInAclW, SetNamedSecurityInfoW, TRUSTEE_IS_SID, TRUSTEE_IS_USER, TRUSTEE_W,
    };
    use windows_sys::Win32::Security::{
        ACL, DACL_SECURITY_INFORMATION, NO_INHERITANCE, OWNER_SECURITY_INFORMATION,
        PROTECTED_DACL_SECURITY_INFORMATION, PSECURITY_DESCRIPTOR, PSID,
        SUB_CONTAINERS_AND_OBJECTS_INHERIT,
    };

    fn check(code: u32) -> io::Result<()> {
        if code == 0 {
            Ok(())
        } else {
            Err(io::Error::from_raw_os_error(code as i32))
        }
    }

    pub fn owner_only(path: &Path, is_dir: bool) -> io::Result<()> {
        let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        // SAFETY: every pointer handed to the Win32 calls is either a valid out-pointer to a
        // local or a buffer owned by this frame; the two LocalAlloc'd results are freed below.
        unsafe {
            let mut owner: PSID = null_mut();
            let mut descriptor: PSECURITY_DESCRIPTOR = null_mut();
            check(GetNamedSecurityInfoW(
                wide.as_ptr(),
                SE_FILE_OBJECT,
                OWNER_SECURITY_INFORMATION,
                &mut owner,
                null_mut(),
                null_mut(),
                null_mut(),
                &mut descriptor,
            ))?;

            let access = EXPLICIT_ACCESS_W {
                grfAccessPermissions: GENERIC_ALL,
                grfAccessMode: SET_ACCESS,
                grfInheritance: if is_dir {
                    SUB_CONTAINERS_AND_OBJECTS_INHERIT
                } else {
                    NO_INHERITANCE
                },
                Trustee: TRUSTEE_W {
                    pMultipleTrustee: null_mut(),
                    MultipleTrusteeOperation: NO_MULTIPLE_TRUSTEE,
                    TrusteeForm: TRUSTEE_IS_SID,
                    TrusteeType: TRUSTEE_IS_USER,
                    ptstrName: owner as *mut u16,
                },
            };
            let mut acl: *mut ACL = null_mut();
            let result = check(SetEntriesInAclW(1, &access, null(), &mut acl)).and_then(|()| {
                check(SetNamedSecurityInfoW(
                    wide.as_ptr(),
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                    null_mut(),
                    null_mut(),
                    acl,
                    null(),
                ))
            });
            if !acl.is_null() {
                LocalFree(acl as _);
            }
            if !descriptor.is_null() {
                LocalFree(descriptor as _);
            }
            result
        }
    }
}

#[cfg(not(any(unix, windows)))]
mod imp {
    use std::io;
    use std::path::Path;

    pub fn owner_only(_path: &Path, _is_dir: bool) -> io::Result<()> {
        Ok(())
    }
}

/// What [`atomic_write`] did besides writing the bytes.
#[derive(Debug, Default)]
pub struct AtomicWriteOutcome {
    /// Set when owner-only permissions were requested but could not be applied; the write
    /// itself still happened. Log `log.filePermissions.*TightenFailed`.
    pub permission_error: Option<io::Error>,
    /// `true` when the atomic rename failed and the target was replaced by delete-then-rename
    /// instead. Log `log.configStore.atomicMoveUnsupported`.
    pub fell_back_to_plain_replace: bool,
}

/// The temporary sibling [`atomic_write`] uses: `<file name>.tmp`.
pub fn tmp_path_for(target: &Path) -> PathBuf {
    let mut name = target
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_default();
    name.push(".tmp");
    target.with_file_name(name)
}

/// Writes `bytes` to `target` so a reader sees either the old or the new file, never a torn one:
/// create the parent folder, write `<name>.tmp`, fsync it, tighten its permissions (before the
/// rename, so the real path is never world-readable), then rename it over `target`.
///
/// On error the temporary file is removed and `target` is left as it was.
pub fn atomic_write(
    target: &Path,
    bytes: &[u8],
    owner_only_perms: bool,
) -> io::Result<AtomicWriteOutcome> {
    let tmp = tmp_path_for(target);
    let result = write_then_rename(target, &tmp, bytes, owner_only_perms);
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}

fn write_then_rename(
    target: &Path,
    tmp: &Path,
    bytes: &[u8],
    owner_only_perms: bool,
) -> io::Result<AtomicWriteOutcome> {
    let mut outcome = AtomicWriteOutcome::default();
    if let Some(parent) = target.parent().filter(|p| !p.as_os_str().is_empty()) {
        fs::create_dir_all(parent)?;
    }
    {
        let mut file = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
    }
    if owner_only_perms && let Err(e) = owner_only(tmp, false) {
        outcome.permission_error = Some(e);
    }
    if let Err(first) = fs::rename(tmp, target) {
        // Some file systems cannot replace atomically; fall back to delete-then-rename.
        if target.exists() && fs::remove_file(target).is_ok() {
            fs::rename(tmp, target)?;
            outcome.fell_back_to_plain_replace = true;
        } else {
            return Err(first);
        }
    }
    sync_parent(target);
    Ok(outcome)
}

/// Best-effort fsync of the folder holding `path`, so the rename itself is durable (Unix only;
/// Windows has no folder handles for this).
fn sync_parent(path: &Path) {
    #[cfg(unix)]
    if let Some(parent) = path.parent()
        && let Ok(dir) = fs::File::open(parent)
    {
        let _ = dir.sync_all();
    }
    #[cfg(not(unix))]
    let _ = path;
}

/// The sibling a reset moves the data folder to: `<name>.reset-<stamp>`.
pub fn reset_aside_path(root: &Path, stamp_ms: u128) -> PathBuf {
    let mut name = root
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_default();
    name.push(format!(".reset-{stamp_ms}"));
    root.with_file_name(name)
}

/// Moves the launcher data folder to `<name>.reset-<stamp>` in one rename, so a file still in
/// use makes the whole step fail with nothing touched. `Ok(None)` when the folder does not
/// exist. Fails (leaving the folder in place) when the sibling name is already taken.
pub fn move_aside_for_reset(root: &Path, stamp_ms: u128) -> io::Result<Option<PathBuf>> {
    if fs::symlink_metadata(root).is_err() {
        return Ok(None);
    }
    let aside = reset_aside_path(root, stamp_ms);
    if fs::symlink_metadata(&aside).is_ok() {
        return Err(io::Error::new(
            io::ErrorKind::AlreadyExists,
            format!("{} already exists", aside.display()),
        ));
    }
    fs::rename(root, &aside)?;
    Ok(Some(aside))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tmp_name_appends_suffix() {
        let p = Path::new("dir").join("configuration.json");
        assert_eq!(
            tmp_path_for(&p),
            Path::new("dir").join("configuration.json.tmp")
        );
    }

    #[test]
    fn atomic_write_replaces_and_leaves_no_tmp() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("nested").join("configuration.json");
        atomic_write(&target, b"{\"a\":1}", true).unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"{\"a\":1}");
        let outcome = atomic_write(&target, b"{\"a\":2}", true).unwrap();
        assert!(!outcome.fell_back_to_plain_replace);
        assert_eq!(fs::read(&target).unwrap(), b"{\"a\":2}");
        assert!(!tmp_path_for(&target).exists());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(&target).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o600);
        }
        #[cfg(windows)]
        assert!(
            outcome.permission_error.is_none(),
            "{:?}",
            outcome.permission_error
        );
    }

    #[test]
    fn owner_only_on_a_folder_keeps_it_usable() {
        let dir = tempfile::tempdir().unwrap();
        let sub = dir.path().join("secret");
        fs::create_dir(&sub).unwrap();
        owner_only(&sub, true).unwrap();
        fs::write(sub.join("x"), b"1").unwrap();
        assert_eq!(fs::read(sub.join("x")).unwrap(), b"1");
    }

    #[test]
    fn failed_write_leaves_target_untouched() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("cfg.json");
        fs::write(&target, b"old").unwrap();
        // A folder squatting on the tmp name makes the tmp open fail.
        fs::create_dir(tmp_path_for(&target)).unwrap();
        assert!(atomic_write(&target, b"new", false).is_err());
        assert_eq!(fs::read(&target).unwrap(), b"old");
    }
}

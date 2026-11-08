// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Small filesystem helpers the front ends share when carrying out a [`CrashAction`]
//! (`crate::CrashAction`). Opening a folder in the file manager or a URL in the browser stays
//! with the front end.

use std::ffi::OsString;
use std::io;
use std::path::{Path, PathBuf};

use crate::diagnosis::PackFolder;

/// The path a disabled jar is renamed to: `x.jar` → `x.jar.disabled`. Forge, NeoForge and
/// Fabric all skip `.jar.disabled`, and a rename (not a delete) can be undone.
pub fn disabled_path(jar: &Path) -> PathBuf {
    let mut name: OsString = jar.file_name().map(OsString::from).unwrap_or_default();
    name.push(".disabled");
    jar.with_file_name(name)
}

/// Disables a mod by renaming `jar` to [`disabled_path`], replacing an existing file of that
/// name. Returns the new path.
pub fn disable_mod(jar: &Path) -> io::Result<PathBuf> {
    let target = disabled_path(jar);
    if target.is_file() {
        // `rename` replaces on Windows and Unix alike; removing first keeps the result the
        // same on file systems where it would not.
        std::fs::remove_file(&target)?;
    }
    std::fs::rename(jar, &target)?;
    Ok(target)
}

/// Resolves `folder` under the pack `root` and creates it if missing, ready to be opened.
pub fn ensure_pack_folder(root: &Path, folder: PackFolder) -> io::Result<PathBuf> {
    let path = folder.path_in(root);
    std::fs::create_dir_all(&path)?;
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disable_renames_and_replaces() {
        let dir = tempfile::tempdir().unwrap();
        let jar = dir.path().join("OptiFine_1.20.1.jar");
        std::fs::write(&jar, b"new").unwrap();
        std::fs::write(dir.path().join("OptiFine_1.20.1.jar.disabled"), b"old").unwrap();
        let out = disable_mod(&jar).unwrap();
        assert_eq!(out, dir.path().join("OptiFine_1.20.1.jar.disabled"));
        assert!(!jar.exists());
        assert_eq!(std::fs::read(&out).unwrap(), b"new");
    }

    #[test]
    fn ensure_creates_sub_folders() {
        let dir = tempfile::tempdir().unwrap();
        let p = ensure_pack_folder(dir.path(), PackFolder::CrashReports).unwrap();
        assert!(p.is_dir());
        assert_eq!(p, dir.path().join("crash-reports"));
        assert_eq!(
            ensure_pack_folder(dir.path(), PackFolder::Root).unwrap(),
            dir.path()
        );
    }
}

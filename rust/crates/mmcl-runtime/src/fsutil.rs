// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! File helpers of the runtime manager (Java `findJavaExecutable`, `searchForFile`,
//! `markJavaBinariesExecutable`).

use std::fs;
use std::path::{Path, PathBuf};

use mmcl_i18n::tf;
use mmcl_mojang::HostInfo;

/// Depth-first search under `dir` for the first regular file named exactly `name`.
///
/// Children are visited in name order, files and folders together, so the result does not
/// depend on the file system's listing order (Java's did, bug B-21). A missing or unreadable
/// `dir` yields `None`. Returns an absolute path when `dir` is absolute.
pub fn search_for_file(dir: &Path, name: &str) -> Option<PathBuf> {
    let mut children: Vec<_> = fs::read_dir(dir).ok()?.filter_map(Result::ok).collect();
    children.sort_by_key(|e| e.file_name());
    for child in children {
        let path = child.path();
        // `metadata` follows symlinks, like Java's `isFile` / `isDirectory`.
        let Ok(meta) = fs::metadata(&path) else {
            continue;
        };
        if meta.is_file() && child.file_name() == name {
            return Some(path);
        }
        if meta.is_dir()
            && !is_symlink(&path)
            && let Some(found) = search_for_file(&path, name)
        {
            return Some(found);
        }
    }
    None
}

fn is_symlink(p: &Path) -> bool {
    fs::symlink_metadata(p)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
}

/// The first `java` (`java.exe` on Windows) under `dir`.
pub fn find_java_executable(dir: &Path, host: &HostInfo) -> Option<PathBuf> {
    search_for_file(dir, host.java_exec_name())
}

/// Restores the execute bits on every regular file in `java_exec`'s folder (`bin/`), and on
/// `<bin>/../lib/jspawnhelper` when present. The hardened extractor drops POSIX modes, so a
/// freshly extracted JRE would otherwise not run. No-op on Windows.
pub fn mark_java_binaries_executable(java_exec: &Path) {
    let Some(bin) = java_exec.parent() else {
        return;
    };
    if bin.as_os_str().is_empty() || !bin.is_dir() {
        return;
    }
    if let Ok(entries) = fs::read_dir(bin) {
        for e in entries.flatten() {
            let p = e.path();
            if p.is_file()
                && !is_executable(&p)
                && let Err(_e) = set_executable(&p)
            {
                mmcl_log::warn_silent(&tf!(
                    "log.runtimeManager.markExecutableFailed",
                    p.display().to_string()
                ));
            }
        }
    }
    if let Some(root) = bin.parent() {
        let helper = root.join("lib").join("jspawnhelper");
        if helper.is_file() && !is_executable(&helper) {
            let _ = set_executable(&helper);
        }
    }
}

/// `true` when the owner may execute `p` (always `true` on Windows, where the extension decides).
pub fn is_executable(p: &Path) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::metadata(p)
            .map(|m| m.permissions().mode() & 0o100 != 0)
            .unwrap_or(false)
    }
    #[cfg(not(unix))]
    {
        p.exists()
    }
}

/// Adds the execute bit wherever the read bit is set (owner at least). No-op on Windows.
pub fn set_executable(p: &Path) -> std::io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = fs::metadata(p)?.permissions();
        let mode = perms.mode();
        perms.set_mode(mode | 0o100 | ((mode & 0o044) >> 2));
        fs::set_permissions(p, perms)
    }
    #[cfg(not(unix))]
    {
        let _ = p;
        Ok(())
    }
}

/// Total size in bytes of the regular files under `dir` (symlinks not followed).
pub fn dir_size(dir: &Path) -> u64 {
    walkdir::WalkDir::new(dir)
        .follow_links(false)
        .into_iter()
        .filter_map(Result::ok)
        .filter(|e| e.file_type().is_file())
        .filter_map(|e| e.metadata().ok())
        .map(|m| m.len())
        .sum()
}

/// Reads a version marker, trimmed. `None` when it cannot be read.
pub fn read_marker(path: &Path) -> Option<String> {
    fs::read_to_string(path).ok().map(|s| s.trim().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_mojang::host::presets;

    fn touch(p: &Path) {
        if let Some(parent) = p.parent() {
            fs::create_dir_all(parent).unwrap();
        }
        fs::write(p, b"").unwrap();
    }

    #[test]
    fn finds_a_file_directly_in_the_directory() {
        let d = tempfile::tempdir().unwrap();
        let target = d.path().join("java");
        touch(&target);
        assert_eq!(search_for_file(d.path(), "java"), Some(target));
    }

    #[test]
    fn finds_a_file_nested_several_levels_deep() {
        let d = tempfile::tempdir().unwrap();
        let target = d.path().join("a").join("b").join("c").join("java");
        touch(&target);
        assert_eq!(search_for_file(d.path(), "java"), Some(target));
    }

    #[test]
    fn none_when_no_match() {
        let d = tempfile::tempdir().unwrap();
        touch(&d.path().join("not-java"));
        assert_eq!(search_for_file(d.path(), "java"), None);
    }

    #[test]
    fn none_for_a_missing_directory() {
        let d = tempfile::tempdir().unwrap();
        assert_eq!(search_for_file(&d.path().join("missing"), "java"), None);
    }

    #[test]
    fn match_is_exact_not_partial() {
        let d = tempfile::tempdir().unwrap();
        touch(&d.path().join("javac"));
        touch(&d.path().join("java.exe.bak"));
        assert_eq!(search_for_file(d.path(), "java"), None);
    }

    #[test]
    fn a_directory_with_the_name_is_not_a_match() {
        let d = tempfile::tempdir().unwrap();
        fs::create_dir_all(d.path().join("java")).unwrap();
        assert_eq!(search_for_file(d.path(), "java"), None);
        let inner = d.path().join("java").join("java");
        touch(&inner);
        assert_eq!(search_for_file(d.path(), "java"), Some(inner));
    }

    #[test]
    fn search_order_is_by_name() {
        let d = tempfile::tempdir().unwrap();
        touch(&d.path().join("b").join("java"));
        touch(&d.path().join("a").join("java"));
        assert_eq!(
            search_for_file(d.path(), "java"),
            Some(d.path().join("a").join("java"))
        );
    }

    #[test]
    fn find_java_uses_the_platform_exe_name() {
        let d = tempfile::tempdir().unwrap();
        let bin = d.path().join("bin");
        touch(&bin.join("java.exe"));
        touch(&bin.join("java"));
        assert_eq!(
            find_java_executable(d.path(), &presets::windows_x64()),
            Some(bin.join("java.exe"))
        );
        assert_eq!(
            find_java_executable(d.path(), &presets::linux_x64()),
            Some(bin.join("java"))
        );
    }

    #[test]
    fn find_java_none_when_absent() {
        let d = tempfile::tempdir().unwrap();
        assert_eq!(find_java_executable(d.path(), &presets::mac_arm()), None);
    }

    #[test]
    fn mark_executable_no_op_cases() {
        mark_java_binaries_executable(Path::new(""));
        mark_java_binaries_executable(Path::new("java"));
        let d = tempfile::tempdir().unwrap();
        mark_java_binaries_executable(&d.path().join("missing").join("java"));
    }

    #[cfg(unix)]
    #[test]
    fn restores_execute_bits_in_bin_and_jspawnhelper() {
        use std::os::unix::fs::PermissionsExt;
        let d = tempfile::tempdir().unwrap();
        let java = d.path().join("bin").join("java");
        let other = d.path().join("bin").join("keytool");
        let helper = d.path().join("lib").join("jspawnhelper");
        for p in [&java, &other, &helper] {
            touch(p);
            fs::set_permissions(p, fs::Permissions::from_mode(0o644)).unwrap();
            assert!(!is_executable(p));
        }
        mark_java_binaries_executable(&java);
        assert!(is_executable(&java));
        assert!(is_executable(&other), "every regular file in bin/");
        assert!(is_executable(&helper));
        let mode = fs::metadata(&java).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o755);
    }

    #[test]
    fn sizes_and_markers() {
        let d = tempfile::tempdir().unwrap();
        fs::write(d.path().join("a"), vec![0u8; 1000]).unwrap();
        fs::create_dir_all(d.path().join("x")).unwrap();
        fs::write(d.path().join("x").join("b"), vec![0u8; 24]).unwrap();
        assert_eq!(dir_size(d.path()), 1024);
        fs::write(d.path().join(".version"), "  21.0.1 \n").unwrap();
        assert_eq!(
            read_marker(&d.path().join(".version")).as_deref(),
            Some("21.0.1")
        );
        assert_eq!(read_marker(&d.path().join("nope")), None);
    }
}

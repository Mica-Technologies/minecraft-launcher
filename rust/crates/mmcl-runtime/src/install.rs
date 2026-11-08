// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Containment rules for Mojang runtime manifest entries, and the installed-runtime scan.
//!
//! The manifest is published by Mojang, but every entry name is still external data: a
//! `../../launcher/config.json` would escape the runtime folder. Names are first rejected by
//! shape ([`is_unsafe_entry_name`]), then resolved lexically and checked to stay under the base
//! ([`resolve_within`]), comparing whole path components (so `/base-evil` is not under `/base`).

use std::fs;
use std::path::{Path, PathBuf};

use mmcl_core::consts::runtime::RUNTIME_VERSION_FILE_NAME;
use mmcl_core::paths::normalize_lexical;

use crate::fsutil;

/// `true` for names that must not even be resolved: an embedded NUL, a leading `/` or `\`, or
/// a Windows drive (`C:`). `..` is deliberately not caught here; [`resolve_within`] catches it.
pub fn is_unsafe_entry_name(relative_path: &str) -> bool {
    relative_path.contains('\0')
        || relative_path.starts_with('/')
        || relative_path.starts_with('\\')
        || relative_path.as_bytes().get(1) == Some(&b':')
}

/// `base/relative_path`, lexically normalised, or `None` when it lands outside `base`. `base`
/// should already be absolute and normalised.
pub fn resolve_within(base: &Path, relative_path: &str) -> Option<PathBuf> {
    let resolved = normalize_lexical(&base.join(relative_path));
    resolved.starts_with(base).then_some(resolved)
}

/// Where a `link` entry at `link_path` pointing at `target` resolves, or `None` when the
/// target is absolute, contains a NUL, or leaves `base`. Both paths must already be inside
/// `base` and normalised.
pub fn resolve_link_target(base: &Path, link_path: &Path, target: &str) -> Option<PathBuf> {
    if target.is_empty() || is_unsafe_entry_name(target) {
        return None;
    }
    let parent = link_path.parent()?;
    let resolved = normalize_lexical(&parent.join(target));
    (resolved.starts_with(base) && resolved != link_path).then_some(resolved)
}

/// `true` when `path`'s parent stays inside `base_canon` on disk, with symlinks resolved.
///
/// The lexical check in [`resolve_within`] cannot see symlinks. Once `link` entries exist as
/// real symlinks (macOS and Linux), a chain of individually contained links can make a
/// lexically contained path land outside the runtime folder, and a later manifest's file or
/// directory entry would then be written there. This canonicalises the nearest existing
/// ancestor of `path`'s parent and requires it under `base_canon`, which must itself be
/// canonical. The components below that ancestor do not exist yet, so they cannot be links.
/// A dangling link on the way fails closed (`false`).
pub fn parent_physically_within(base_canon: &Path, path: &Path) -> bool {
    let Some(mut cur) = path.parent() else {
        return false;
    };
    while fs::symlink_metadata(cur).is_err() {
        match cur.parent() {
            Some(p) => cur = p,
            None => return false,
        }
    }
    fs::canonicalize(cur).is_ok_and(|c| c.starts_with(base_canon))
}

/// One installed runtime, as the runtime-management screens list it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct InstalledRuntime {
    /// The folder name, which is the component name (`jre-legacy`, `java-runtime-gamma`, ...).
    pub component: String,
    /// The trimmed `.version` marker; empty when it could not be read (callers localise the
    /// "version unknown" case).
    pub version: String,
    /// The absolute folder path.
    pub path: PathBuf,
    /// Folder size in whole MiB (rounded down).
    pub size_mb: u64,
}

/// Every child folder of `runtime_root` holding a `.version` marker. A missing root, or a root
/// that is a file, gives an empty list; plain files under the root are ignored. Sorted by
/// component name.
pub fn scan_installed(runtime_root: &Path) -> Vec<InstalledRuntime> {
    let Ok(entries) = fs::read_dir(runtime_root) else {
        return Vec::new();
    };
    let mut out: Vec<InstalledRuntime> = entries
        .filter_map(Result::ok)
        .filter(|e| e.path().is_dir())
        .filter_map(|e| {
            let dir = e.path();
            let marker = dir.join(RUNTIME_VERSION_FILE_NAME);
            if !marker.exists() {
                return None;
            }
            let path = std::path::absolute(&dir).unwrap_or(dir.clone());
            Some(InstalledRuntime {
                component: e.file_name().to_string_lossy().into_owned(),
                version: fsutil::read_marker(&marker).unwrap_or_default(),
                size_mb: fsutil::dir_size(&dir) / (1024 * 1024),
                path,
            })
        })
        .collect();
    out.sort_by(|a, b| a.component.cmp(&b.component));
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_core::{DataPaths, GameMode};

    fn base() -> (tempfile::TempDir, PathBuf) {
        let d = tempfile::tempdir().unwrap();
        let b = normalize_lexical(&std::path::absolute(d.path().join("runtime")).unwrap());
        (d, b)
    }

    #[test]
    fn ordinary_relative_paths_are_safe() {
        for p in ["bin/java", "lib/modules", "release"] {
            assert!(!is_unsafe_entry_name(p), "{p}");
        }
    }

    #[test]
    fn unsafe_shapes() {
        assert!(is_unsafe_entry_name("bin/java\0.exe"));
        assert!(is_unsafe_entry_name("/etc/passwd"));
        assert!(is_unsafe_entry_name("\\Windows\\System32\\evil.dll"));
        assert!(is_unsafe_entry_name("C:\\Windows\\System32\\evil.dll"));
        assert!(is_unsafe_entry_name("C:relative"));
    }

    #[test]
    fn parent_traversal_alone_is_not_caught_by_the_name_check() {
        assert!(!is_unsafe_entry_name("../../etc/passwd"));
    }

    #[test]
    fn resolves_an_ordinary_entry() {
        let (_d, b) = base();
        assert_eq!(
            resolve_within(&b, "bin/java"),
            Some(normalize_lexical(&b.join("bin").join("java")))
        );
        assert_eq!(
            resolve_within(&b, "bin/../lib/x"),
            Some(b.join("lib").join("x"))
        );
    }

    #[test]
    fn rejects_a_parent_escape() {
        let (_d, b) = base();
        assert_eq!(resolve_within(&b, "../../../../etc/passwd"), None);
        assert_eq!(resolve_within(&b, "bin/../../x"), None);
    }

    #[test]
    fn rejects_an_escape_through_a_sibling_with_a_shared_prefix() {
        let (_d, b) = base();
        assert_eq!(resolve_within(&b, "../runtime-evil/payload"), None);
    }

    #[test]
    fn a_bare_dot_resolves_to_the_base() {
        let (_d, b) = base();
        assert_eq!(resolve_within(&b, "."), Some(b.clone()));
    }

    #[test]
    fn link_targets_stay_inside() {
        let (_d, b) = base();
        let link = b.join("legal").join("java.desktop").join("LICENSE");
        assert_eq!(
            resolve_link_target(&b, &link, "../java.base/LICENSE"),
            Some(b.join("legal").join("java.base").join("LICENSE"))
        );
        assert_eq!(
            resolve_link_target(&b, &link, "../../../../etc/passwd"),
            None
        );
        assert_eq!(resolve_link_target(&b, &link, "/etc/passwd"), None);
        assert_eq!(resolve_link_target(&b, &link, ""), None);
        assert_eq!(resolve_link_target(&b, &link, "LICENSE"), None, "self-link");
    }

    #[test]
    fn physical_containment_of_plain_folders() {
        let d = tempfile::tempdir().unwrap();
        let base = d.path().join("rt");
        fs::create_dir_all(base.join("bin")).unwrap();
        let canon = fs::canonicalize(&base).unwrap();
        assert!(parent_physically_within(
            &canon,
            &base.join("bin").join("java")
        ));
        assert!(parent_physically_within(
            &canon,
            &base.join("not").join("yet").join("there")
        ));
        assert!(!parent_physically_within(&canon, &d.path().join("x")));
    }

    #[cfg(unix)]
    #[test]
    fn a_symlink_chain_that_escapes_is_caught() {
        // a/b -> ..   (lexically inside: base/a/.. == base)
        // a/b/c -> ../..  (lexically inside: base/a/b/../.. == base, but on disk a/b is base,
        // so c lands at base/c pointing two levels above base)
        let d = tempfile::tempdir().unwrap();
        let base = d.path().join("root").join("rt");
        fs::create_dir_all(base.join("a")).unwrap();
        std::os::unix::fs::symlink("..", base.join("a").join("b")).unwrap();
        std::os::unix::fs::symlink("../..", base.join("c")).unwrap();
        let canon = fs::canonicalize(&base).unwrap();
        let lexical = normalize_lexical(&std::path::absolute(&base).unwrap());
        let target = resolve_within(&lexical, "a/b/c/evil").expect("lexically inside");
        assert!(!parent_physically_within(&canon, &target));
        assert!(parent_physically_within(
            &canon,
            &base.join("a").join("b").join("ok")
        ));
    }

    #[test]
    fn component_folder_is_under_the_runtime_root() {
        let paths = DataPaths::at_root("/data", GameMode::Client);
        let p = paths.runtime_component_dir("java-runtime-gamma");
        assert!(p.starts_with(paths.runtime_dir()));
        assert!(p.ends_with("java-runtime-gamma"));
        assert_ne!(
            paths.runtime_component_dir("jre-legacy"),
            paths.runtime_component_dir("java-runtime-gamma")
        );
    }

    #[test]
    fn missing_root_is_empty() {
        let d = tempfile::tempdir().unwrap();
        assert!(scan_installed(&d.path().join("missing")).is_empty());
    }

    #[test]
    fn a_file_as_root_is_empty() {
        let d = tempfile::tempdir().unwrap();
        let f = d.path().join("not-a-dir");
        fs::write(&f, b"").unwrap();
        assert!(scan_installed(&f).is_empty());
    }

    #[test]
    fn skips_a_folder_without_marker() {
        let d = tempfile::tempdir().unwrap();
        fs::create_dir_all(d.path().join("java-runtime-gamma").join("bin")).unwrap();
        assert!(scan_installed(d.path()).is_empty());
    }

    #[test]
    fn reports_an_installed_component() {
        let d = tempfile::tempdir().unwrap();
        let c = d.path().join("jre-legacy");
        fs::create_dir_all(&c).unwrap();
        fs::write(c.join(RUNTIME_VERSION_FILE_NAME), "8u392").unwrap();
        fs::write(c.join("blob"), vec![0u8; 3 * 1024 * 1024 + 5]).unwrap();
        let list = scan_installed(d.path());
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].component, "jre-legacy");
        assert_eq!(list[0].version, "8u392");
        assert_eq!(list[0].path, std::path::absolute(&c).unwrap());
        assert_eq!(list[0].size_mb, 3);
    }

    #[test]
    fn ignores_plain_files_under_the_root() {
        let d = tempfile::tempdir().unwrap();
        fs::write(d.path().join("stray-file.txt"), b"").unwrap();
        fs::write(d.path().join("mojang-runtime-index.json"), b"{}").unwrap();
        assert!(scan_installed(d.path()).is_empty());
    }

    #[test]
    fn marker_is_trimmed() {
        let d = tempfile::tempdir().unwrap();
        let c = d.path().join("java-runtime-delta");
        fs::create_dir_all(&c).unwrap();
        fs::write(c.join(RUNTIME_VERSION_FILE_NAME), "  21.0.1\n").unwrap();
        assert_eq!(scan_installed(d.path())[0].version, "21.0.1");
    }
}

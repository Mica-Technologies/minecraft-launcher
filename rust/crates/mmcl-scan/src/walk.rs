// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pack-folder walk both scanners share: excluded directories are skipped as whole subtrees,
//! excluded files are skipped, and links are not followed (as Java's `walkFileTree`).

use std::path::{Path, PathBuf};

use walkdir::WalkDir;

use crate::supplemental::is_excluded_rel;

/// One walk event.
pub(crate) enum WalkItem {
    /// A non-directory entry that is not excluded (regular file, or a link to anything).
    File(PathBuf),
    /// An entry that could not be read. `is_root` is set when the root itself failed.
    Error {
        path: Option<PathBuf>,
        is_dir: bool,
        is_root: bool,
        error: walkdir::Error,
    },
}

/// The pack-relative path of `path`, lowercased with `/` separators (Java's
/// `relativize(...).toString().toLowerCase(ROOT).replace('\\', '/')`).
pub(crate) fn rel_string(root: &Path, path: &Path) -> Option<String> {
    let rel = path.strip_prefix(root).ok()?;
    let mut out = String::new();
    for c in rel.components() {
        if !out.is_empty() {
            out.push('/');
        }
        out.push_str(&c.as_os_str().to_string_lossy());
    }
    Some(out.to_lowercase().replace('\\', "/"))
}

/// Walks `root`, calling `visit` for each item. `exclusions` must already be normalised
/// ([`crate::supplemental::normalize_exclusions`]).
pub(crate) fn walk(root: &Path, exclusions: &[String], mut visit: impl FnMut(WalkItem)) {
    let it = WalkDir::new(root)
        .follow_links(false)
        .into_iter()
        .filter_entry(|e| {
            if e.depth() == 0 || exclusions.is_empty() {
                return true;
            }
            match rel_string(root, e.path()) {
                Some(rel) => !is_excluded_rel(&rel, exclusions),
                None => true,
            }
        });
    for entry in it {
        match entry {
            Ok(e) => {
                if !e.file_type().is_dir() {
                    visit(WalkItem::File(e.into_path()));
                }
            }
            Err(error) => {
                let path = error.path().map(Path::to_path_buf);
                let is_dir = path.as_deref().is_some_and(Path::is_dir);
                let is_root = error.depth() == 0;
                visit(WalkItem::Error {
                    path,
                    is_dir,
                    is_root,
                    error,
                });
            }
        }
    }
}

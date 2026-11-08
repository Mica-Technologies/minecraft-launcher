// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Which installed packs the launcher authored itself (Java
//! `LiveMcpLauncherActions.resolveAuthoredManifest`). Only those may be edited through MCP: an
//! imported `.mmcjson` is local but belongs to the user, and a remote manifest is not ours.

use std::path::{Path, PathBuf};

/// The folder (under the launcher config folder) authored manifests live in.
pub const AUTHORED_MANIFESTS_DIR: &str = "mcp-manifests";

fn absolute_normalized(p: &Path) -> Option<PathBuf> {
    let abs = std::path::absolute(p).ok()?;
    Some(mmcl_core::paths::normalize_lexical(&abs))
}

/// The manifest's path when `manifest_url` is a `file:` URL whose path lies (lexically,
/// component-wise) strictly inside `authored_dir`; otherwise `None`.
pub fn resolve_authored_manifest(manifest_url: &str, authored_dir: &Path) -> Option<PathBuf> {
    let trimmed = manifest_url.trim();
    if !trimmed
        .get(..5)
        .is_some_and(|s| s.eq_ignore_ascii_case("file:"))
    {
        return None;
    }
    let url = url::Url::parse(trimmed).ok()?;
    if url.scheme() != "file" {
        return None;
    }
    let path = url.to_file_path().ok()?;
    let path = absolute_normalized(&path)?;
    let dir = absolute_normalized(authored_dir)?;
    if path != dir && path.starts_with(&dir) {
        Some(path)
    } else {
        None
    }
}

/// [`resolve_authored_manifest`], also requiring an existing regular file.
pub fn local_manifest_path_of(manifest_url: &str, authored_dir: &Path) -> Option<PathBuf> {
    resolve_authored_manifest(manifest_url, authored_dir).filter(|p| p.is_file())
}

#[cfg(test)]
mod tests {
    //! Port of `LiveMcpLauncherActionsTest`.
    use super::*;

    fn dir() -> (tempfile::TempDir, PathBuf) {
        let t = tempfile::tempdir().unwrap();
        let d = t.path().join(AUTHORED_MANIFESTS_DIR);
        std::fs::create_dir_all(&d).unwrap();
        (t, d)
    }

    fn url_of(p: &Path) -> String {
        url::Url::from_file_path(p).unwrap().to_string()
    }

    #[test]
    fn a_manifest_inside_resolves() {
        let (_t, d) = dir();
        let f = d.join("created-alto.json");
        assert_eq!(resolve_authored_manifest(&url_of(&f), &d), Some(f.clone()));
        assert_eq!(
            local_manifest_path_of(&url_of(&f), &d),
            None,
            "missing file"
        );
        std::fs::write(&f, "{}").unwrap();
        assert_eq!(local_manifest_path_of(&url_of(&f), &d), Some(f));
    }

    #[test]
    fn a_nested_manifest_resolves() {
        let (_t, d) = dir();
        let f = d.join("sub").join("x.json");
        assert_eq!(resolve_authored_manifest(&url_of(&f), &d), Some(f));
    }

    #[test]
    fn outside_and_sibling_prefix_do_not() {
        let (t, d) = dir();
        let outside = t.path().join("x.json");
        assert_eq!(resolve_authored_manifest(&url_of(&outside), &d), None);
        let sibling = t.path().join("mcp-manifests-evil").join("x.json");
        assert_eq!(resolve_authored_manifest(&url_of(&sibling), &d), None);
    }

    #[test]
    fn dot_dot_escape_does_not() {
        let (_t, d) = dir();
        let base = url_of(&d.join("x.json"));
        let escaped = base.replace("/x.json", "/../escape.json");
        assert_eq!(resolve_authored_manifest(&escaped, &d), None);
    }

    #[test]
    fn the_dir_itself_does_not() {
        let (_t, d) = dir();
        assert_eq!(resolve_authored_manifest(&url_of(&d), &d), None);
    }

    #[test]
    fn remote_and_malformed_inputs_do_not() {
        let (_t, d) = dir();
        for u in [
            "https://example.com/created-x.json",
            "",
            "file:",
            "file://%zz",
            "not a url",
            "jar:file:/x!/y",
        ] {
            assert_eq!(resolve_authored_manifest(u, &d), None, "{u}");
        }
    }
}

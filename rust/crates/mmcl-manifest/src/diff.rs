// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pure core of the pending-update diff: which mods an update would add, remove or
//! change, matching installed jars to manifest entries by a version-stripped name key.
//! `mmcl-modpack` lists `mods/` and supplies the hash check.

use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

use regex::Regex;

use crate::files::ManifestFile;

static VERSION_TAIL: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"[-_]v?[0-9].*$").expect("valid regex"));

/// Mod changes an update would make, by display base name.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct UpdateDiff {
    pub added: Vec<String>,
    pub removed: Vec<String>,
    pub updated: Vec<String>,
}

impl UpdateDiff {
    /// True when nothing would change.
    pub fn is_empty(&self) -> bool {
        self.added.is_empty() && self.removed.is_empty() && self.updated.is_empty()
    }
}

/// Strips a trailing `.jar.disabled` or `.jar` (any case), then the version tail (`[-_]v?\d`
/// to the end); if that leaves nothing, the name without its extension.
pub fn base_name(file: &str) -> String {
    let n = strip_suffix_ignore_case(file, ".jar.disabled")
        .or_else(|| strip_suffix_ignore_case(file, ".jar"))
        .unwrap_or(file);
    let stripped = VERSION_TAIL.replace(n, "");
    if stripped.trim().is_empty() {
        n.to_string()
    } else {
        stripped.into_owned()
    }
}

/// `s` without an ASCII `suffix`, compared ignoring ASCII case.
fn strip_suffix_ignore_case<'a>(s: &'a str, suffix: &str) -> Option<&'a str> {
    let cut = s.len().checked_sub(suffix.len())?;
    let tail = s.get(cut..)?;
    tail.eq_ignore_ascii_case(suffix).then(|| &s[..cut])
}

/// The grouping key: [`base_name`] lower-cased.
pub fn mod_key(file: &str) -> String {
    base_name(file).to_lowercase()
}

/// True for an installed file the diff considers (`.jar` or `.jar.disabled`, any case).
pub fn is_installed_mod_file(name: &str) -> bool {
    strip_suffix_ignore_case(name, ".jar").is_some()
        || strip_suffix_ignore_case(name, ".jar.disabled").is_some()
}

/// Diffs a manifest's mods against installed file names (directory order).
///
/// `installed_differs(mod, installed_file_name)` reports whether the installed file's bytes
/// do not match the mod's declared hash; it should return `false` when the mod declares no
/// usable hash or the check fails, so nothing is reported as changed by mistake.
///
/// Server-only mods (`clientReq` false) never count as added or updated, since they never
/// install on a client, but they still claim their key so a matching jar is not "removed".
pub fn diff_mods<S, F>(
    manifest: &[ManifestFile],
    installed: &[S],
    installed_differs: F,
) -> UpdateDiff
where
    S: AsRef<str>,
    F: Fn(&ManifestFile, &str) -> bool,
{
    let mut installed_by_key: Vec<(String, String, String)> = Vec::new(); // key, file, display
    let mut seen = HashSet::new();
    for file in installed.iter().map(AsRef::as_ref) {
        if !is_installed_mod_file(file) {
            continue;
        }
        let key = mod_key(file);
        if seen.insert(key.clone()) {
            installed_by_key.push((key, file.to_string(), base_name(file)));
        }
    }
    let lookup: HashMap<&str, &str> = installed_by_key
        .iter()
        .map(|(k, f, _)| (k.as_str(), f.as_str()))
        .collect();

    let mut diff = UpdateDiff::default();
    let mut manifest_keys = HashSet::new();
    for m in manifest {
        let Some(file_name) = m.file_name() else {
            continue;
        };
        let key = mod_key(file_name);
        manifest_keys.insert(key.clone());
        if !m.client_req {
            continue;
        }
        match lookup.get(key.as_str()) {
            None => diff.added.push(base_name(file_name)),
            Some(installed_file) => {
                if installed_differs(m, installed_file) {
                    diff.updated.push(base_name(file_name));
                }
            }
        }
    }
    diff.removed = installed_by_key
        .into_iter()
        .filter(|(k, _, _)| !manifest_keys.contains(k))
        .map(|(_, _, display)| display)
        .collect();
    diff
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m(local: &str, client: bool) -> ManifestFile {
        ManifestFile {
            local: Some(local.into()),
            client_req: client,
            server_req: true,
            ..Default::default()
        }
    }

    #[test]
    fn base_names() {
        assert_eq!(base_name("jei-1.20.1-15.2.0.jar"), "jei");
        assert_eq!(base_name("Create_v0.5.1.JAR"), "Create");
        assert_eq!(base_name("journeymap-1.20.1.jar.disabled"), "journeymap");
        assert_eq!(base_name("3d-skin-layers.jar"), "3d-skin-layers");
        assert_eq!(base_name("-1.2.jar"), "-1.2");
        assert_eq!(base_name("plain.txt"), "plain.txt");
        assert_eq!(mod_key("JEI-1.0.jar"), "jei");
    }

    #[test]
    fn added_removed_updated() {
        let manifest = vec![
            m("mods/jei-2.0.jar", true),
            m("create-0.6.jar", true),
            m("new-mod-1.0.jar", true),
            m("server-only-1.0.jar", false),
        ];
        let installed = [
            "jei-1.0.jar",
            "create-0.5.jar",
            "old-mod-1.0.jar",
            "server-only-1.0.jar",
            "readme.txt",
            "old-mod-0.9.jar",
        ];
        let diff = diff_mods(&manifest, &installed, |_, file| file == "create-0.5.jar");
        assert_eq!(diff.added, ["new-mod"]);
        assert_eq!(diff.updated, ["create"]);
        assert_eq!(diff.removed, ["old-mod"]);
        assert!(!diff.is_empty());
    }

    #[test]
    fn server_only_mods_are_never_added() {
        let diff = diff_mods(&[m("srv-1.0.jar", false)], &[] as &[&str], |_, _| true);
        assert!(diff.is_empty());
    }

    #[test]
    fn disabled_jars_count_as_installed() {
        let diff = diff_mods(
            &[m("jei-2.0.jar", true)],
            &["jei-1.0.jar.disabled"],
            |_, _| false,
        );
        assert!(diff.is_empty());
    }

    #[test]
    fn entries_without_local_are_skipped() {
        let diff = diff_mods(&[ManifestFile::default()], &["a-1.jar"], |_, _| false);
        assert_eq!(diff.removed, ["a"]);
        assert!(diff.added.is_empty());
    }
}

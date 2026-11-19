// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pure core of the pending-update diff: which mods an update would add, remove or
//! change, matching installed jars to manifest entries by a version-stripped name key.
//! `mmcl-modpack` lists `mods/` and supplies the hash check.

use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

use regex::Regex;

use crate::files::ManifestFile;
use crate::optional::OptionalMod;

static VERSION_TAIL: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"[-_]v?[0-9].*$").expect("valid regex"));

/// Mod changes an update would make, by display base name.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct UpdateDiff {
    pub added: Vec<String>,
    pub removed: Vec<String>,
    pub updated: Vec<String>,
    /// The names in `added` / `updated` that belong to optional mods the player has on, for
    /// an "(optional)" tag. No duplicates, first-seen order.
    pub optional_names: Vec<String>,
    /// Display names of client-side optional mods that are new since the last sync and off:
    /// available for the player to switch on.
    pub new_optional_names: Vec<String>,
}

impl UpdateDiff {
    /// True when nothing would change and no new optional mod is available.
    pub fn is_empty(&self) -> bool {
        self.added.is_empty()
            && self.removed.is_empty()
            && self.updated.is_empty()
            && self.new_optional_names.is_empty()
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
    diff_mods_with_optional(
        manifest,
        &[],
        &HashSet::new(),
        &HashSet::new(),
        installed,
        installed_differs,
    )
}

/// [`diff_mods`] with the pack's optional mods (Java `PendingUpdateDiff.compute`).
///
/// Changes to optional mods that are on (`enabled_keys`, identity keys of the optional mods
/// installed on this client) are listed like any other mod change and named in
/// `optional_names`. An optional mod that is off is never on disk, so its changes are hidden;
/// a client-side one whose key is not in `known_keys` (the optional mods the manifest had at
/// the last successful sync) is listed in `new_optional_names`. An optional jar never counts
/// as removed: when it is off, the player chose that.
pub fn diff_mods_with_optional<S, F>(
    manifest: &[ManifestFile],
    optional: &[OptionalMod],
    enabled_keys: &HashSet<String>,
    known_keys: &HashSet<String>,
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
        classify(m, file_name, &lookup, &installed_differs, &mut diff);
    }
    for o in optional {
        let file = o.to_manifest_file();
        let Some(file_name) = file.file_name() else {
            continue;
        };
        manifest_keys.insert(mod_key(file_name));
        if enabled_keys.contains(&o.key()) {
            if let Some(name) = classify(&file, file_name, &lookup, &installed_differs, &mut diff)
                && !diff.optional_names.contains(&name)
            {
                diff.optional_names.push(name);
            }
        } else if o.is_client_side() && !known_keys.contains(&o.key()) {
            // A server-only optional mod is never offered on a client, so never "available".
            diff.new_optional_names.push(o.display_name());
        }
    }
    diff.removed = installed_by_key
        .into_iter()
        .filter(|(k, _, _)| !manifest_keys.contains(k))
        .map(|(_, _, display)| display)
        .collect();
    diff
}

/// Files one manifest mod as added or updated; returns the name it was filed under, or `None`
/// when it is unchanged.
fn classify<F>(
    m: &ManifestFile,
    file_name: &str,
    lookup: &HashMap<&str, &str>,
    installed_differs: &F,
    diff: &mut UpdateDiff,
) -> Option<String>
where
    F: Fn(&ManifestFile, &str) -> bool,
{
    let name = base_name(file_name);
    match lookup.get(mod_key(file_name).as_str()) {
        None => diff.added.push(name.clone()),
        Some(installed_file) if installed_differs(m, installed_file) => {
            diff.updated.push(name.clone())
        }
        Some(_) => return None,
    }
    Some(name)
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

    // ---- ports of PendingUpdateDiffOptionalTest ----

    fn opt(name: &str, local: &str, client: bool, server: bool) -> OptionalMod {
        OptionalMod {
            name: Some(name.into()),
            local: Some(local.into()),
            client_req: client,
            server_req: server,
            ..Default::default()
        }
    }

    fn keys(k: &[&str]) -> HashSet<String> {
        k.iter().map(|s| s.to_string()).collect()
    }

    /// Every installed file whose name is in `changed` reads as differing from the manifest.
    fn diff_opt(
        optional: &[OptionalMod],
        enabled: &[&str],
        known: &[&str],
        installed: &[&str],
        changed: &[&str],
    ) -> UpdateDiff {
        diff_mods_with_optional(
            &[m("jei.jar", true)],
            optional,
            &keys(enabled),
            &keys(known),
            installed,
            |_, f| changed.contains(&f),
        )
    }

    #[test]
    fn changes_to_enabled_optional_mods_are_listed_and_tagged() {
        let optional = [
            opt("AUSM", "ausm.jar", true, false),
            opt("Lib", "lib.jar", true, false),
        ];
        let d = diff_opt(
            &optional,
            &["ausm.jar", "lib.jar"],
            &["ausm.jar", "lib.jar"],
            &["jei.jar", "ausm.jar"],
            &["ausm.jar"],
        );
        assert_eq!(d.updated, ["ausm"]);
        assert_eq!(d.added, ["lib"]);
        assert_eq!(d.optional_names, ["ausm", "lib"]);
        assert!(d.removed.is_empty() && d.new_optional_names.is_empty());
    }

    #[test]
    fn a_new_optional_mod_that_is_off_is_available_not_added() {
        let optional = [
            opt("AUSM", "ausm.jar", true, false),
            opt("Old", "old.jar", true, false),
        ];
        let d = diff_opt(
            &optional,
            &[],
            &["old.jar"],
            &["jei.jar"],
            &["ausm.jar", "old.jar"],
        );
        assert_eq!(
            d.new_optional_names,
            ["AUSM"],
            "only the one the last sync did not know about is new"
        );
        assert!(d.added.is_empty());
        assert!(
            d.updated.is_empty(),
            "changes to optional mods that are off are hidden"
        );
        assert!(!d.is_empty());
    }

    #[test]
    fn a_server_only_optional_mod_is_never_offered_to_a_client() {
        let d = diff_opt(
            &[opt("Srv", "srv.jar", false, true)],
            &[],
            &[],
            &["jei.jar"],
            &[],
        );
        assert!(d.new_optional_names.is_empty());
        assert!(d.is_empty());
    }

    #[test]
    fn an_optional_jar_on_disk_is_never_reported_removed() {
        let d = diff_opt(
            &[opt("AUSM", "ausm.jar", true, false)],
            &[],
            &["ausm.jar"],
            &["jei.jar", "ausm.jar", "handmade.jar"],
            &[],
        );
        assert_eq!(d.removed, ["handmade"]);
        assert!(d.new_optional_names.is_empty());
    }

    #[test]
    fn without_a_record_every_optional_mod_that_is_off_reads_as_new() {
        let d = diff_opt(
            &[opt("AUSM", "ausm.jar", true, false)],
            &[],
            &[],
            &["jei.jar"],
            &[],
        );
        assert_eq!(d.new_optional_names, ["AUSM"]);
    }

    #[test]
    fn a_pack_without_optional_mods_diffs_as_before() {
        let d = diff_mods_with_optional(
            &[m("jei.jar", true), m("new.jar", true)],
            &[],
            &HashSet::new(),
            &HashSet::new(),
            &["jei.jar", "gone.jar"],
            |_, _| false,
        );
        assert_eq!(d.added, ["new"]);
        assert_eq!(d.removed, ["gone"]);
        assert!(d.optional_names.is_empty());
    }

    #[test]
    fn entries_without_local_are_skipped() {
        let diff = diff_mods(&[ManifestFile::default()], &["a-1.jar"], |_, _| false);
        assert_eq!(diff.removed, ["a"]);
        assert!(diff.added.is_empty());
    }
}

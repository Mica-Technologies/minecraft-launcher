// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pending-update diff (Java `PendingUpdateDiff`): which mods an update would add, remove
//! or change, from `mods/` against the manifest. A hint shown only when an update is pending.

use std::fs;
use std::path::Path;

pub use mmcl_manifest::diff::{UpdateDiff, base_name, mod_key};
use mmcl_manifest::{ManifestFile, Side};

use crate::managed::ManagedFile;
use crate::pack::GameModPack;

/// Installed mod file names in `mods_dir`, directory order (sorted by name for determinism).
pub fn installed_mod_names(mods_dir: &Path) -> Vec<String> {
    let Ok(rd) = fs::read_dir(mods_dir) else {
        return Vec::new();
    };
    let mut v: Vec<String> = rd
        .flatten()
        .filter(|e| e.file_type().is_ok_and(|t| t.is_file()))
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| mmcl_manifest::diff::is_installed_mod_file(n))
        .collect();
    v.sort();
    v
}

/// The diff for `pack` (empty for vanilla packs and a missing mod list).
pub fn pending_update_diff(pack: &GameModPack) -> UpdateDiff {
    if pack.is_vanilla() {
        return UpdateDiff::default();
    }
    let Some(mods) = pack.manifest().pack_mods.as_ref() else {
        return UpdateDiff::default();
    };
    let mods_dir = pack.mods_dir();
    let names = installed_mod_names(&mods_dir);
    let root = pack.paths().root().to_path_buf();
    mmcl_manifest::diff::diff_mods(mods, &names, |m: &ManifestFile, installed: &str| {
        let f = ManagedFile::new(m.clone(), &mods_dir, &root);
        // Any error reads as "matches": nothing is reported changed by mistake.
        !f.matches_declared_hash(&mods_dir.join(installed))
            .unwrap_or(true)
    })
}

/// `true` when `m` would be installed on a client (used by front ends that render the diff).
pub fn shows_on_client(m: &ManifestFile) -> bool {
    m.is_required_for(Side::Client)
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_core::{DataPaths, GameMode};
    use mmcl_manifest::PackManifest;
    use std::sync::Arc;

    fn m(local: &str, sha1: Option<&str>, client: bool) -> ManifestFile {
        ManifestFile {
            local: Some(local.into()),
            remote: Some("https://x/y".into()),
            sha1: sha1.map(str::to_string),
            client_req: client,
            ..Default::default()
        }
    }

    #[test]
    fn diff_against_installed_mods() {
        let d = tempfile::tempdir().unwrap();
        let paths = Arc::new(DataPaths::at_root(d.path(), GameMode::Client));
        let manifest = PackManifest {
            pack_name: Some("D".into()),
            pack_mods: Some(vec![
                m(
                    "jei-1.2.jar",
                    Some(&mmcl_util::hash::sha1_hex(b"new")),
                    true,
                ),
                m("newmod-1.0.jar", None, true),
                m("serveronly-1.0.jar", None, false),
                m(
                    "same-2.0.jar",
                    Some(&mmcl_util::hash::sha1_hex(b"same")),
                    true,
                ),
            ]),
            ..Default::default()
        };
        let p = GameModPack::from_manifest(paths, manifest, None, None);
        let mods = p.mods_dir();
        fs::create_dir_all(&mods).unwrap();
        fs::write(mods.join("jei-1.1.jar"), "old").unwrap();
        fs::write(mods.join("same-2.0.jar"), "same").unwrap();
        fs::write(mods.join("handadded_v3.jar.disabled"), "x").unwrap();
        fs::write(mods.join("readme.txt"), "x").unwrap();
        let diff = pending_update_diff(&p);
        assert_eq!(diff.added, ["newmod"]);
        assert_eq!(diff.updated, ["jei"]);
        assert_eq!(diff.removed, ["handadded"]);
    }

    #[test]
    fn empty_for_vanilla_and_null_mods() {
        let d = tempfile::tempdir().unwrap();
        let paths = Arc::new(DataPaths::at_root(d.path(), GameMode::Client));
        assert!(pending_update_diff(&GameModPack::vanilla(paths.clone(), "1.20.4")).is_empty());
        let p = GameModPack::from_manifest(
            paths,
            PackManifest {
                pack_name: Some("N".into()),
                ..Default::default()
            },
            None,
            None,
        );
        assert!(pending_update_diff(&p).is_empty());
    }
}

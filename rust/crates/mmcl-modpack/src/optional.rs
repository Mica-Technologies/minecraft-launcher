// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Optional mods at the pack level (the sync-facing half of Java `GameModPackFileSync`,
//! `GameModPack.getVerifyFingerprint` and `OptionalMods`). The rules themselves are in
//! [`mmcl_manifest::optional`].
//!
//! * [`mods_for_side`]: what a sync installs and keeps: every `packMods` entry plus the
//!   optional mods that are on for the side. An optional mod that is off is not in the set, so
//!   the sweep removes its jar.
//! * [`verify_fingerprint`]: the manifest hash extended with the optional mods that are on, so
//!   switching one forces a full verify and a scan on the next launch.
//! * [`KNOWN_OPTIONAL_MODS_FILE`]: the sidecar the pending-update diff reads to tell an
//!   optional mod that is new in an update from one the player left off.

use std::collections::{BTreeSet, HashSet};
use std::path::Path;

use mmcl_i18n::tf;
use mmcl_manifest::optional::{self, Choices};
use mmcl_manifest::{ManifestFile, PackManifest, Side};

use crate::sidecar::PackDotFile;
use crate::verify_state::compute_manifest_sha256;

/// Sidecar in the pack root listing the identity keys of every optional mod the manifest
/// declared at the last successful mod sync, one per line (same file and format as Java).
pub const KNOWN_OPTIONAL_MODS_FILE: &str = ".known_optional_mods";

/// The mods a sync installs and keeps on `side`: every `packMods` entry, then the optional
/// mods [`optional::select_for_side`] picks. An optional mod whose file is also a `packMods`
/// entry is skipped (the required entry wins), and so is one whose `local` is not a plain file
/// name: joined to the `mods` folder it would land where the game does not load it. Side
/// filtering of the `packMods` entries themselves stays with
/// [`ManifestFile::is_required_for`].
///
/// `None` exactly when `packMods` is absent: there is no authoritative mod list.
pub fn mods_for_side(
    manifest: &PackManifest,
    side: Side,
    choices: &Choices,
) -> Option<Vec<ManifestFile>> {
    let required = manifest.pack_mods.as_ref()?;
    let mut mods = required.clone();
    let all = manifest.optional_mods();
    if all.is_empty() {
        return Some(mods);
    }
    let required_keys: HashSet<String> = required
        .iter()
        .map(|m| optional::key_of(m.local.as_deref().unwrap_or("")))
        .collect();
    let selected = optional::select_for_side(all, side, choices);
    let pack_name = manifest.pack_name.clone().unwrap_or_default();
    for m in &selected {
        if !m.is_plain_file_name() {
            mmcl_log::warn_silent(&tf!(
                "log.gameModPackFileSync.optionalInFolderSkipped",
                m.local.clone().unwrap_or_default(),
                pack_name.as_str()
            ));
            continue;
        }
        if !required_keys.contains(&m.key()) {
            mods.push(m.to_manifest_file());
        }
    }
    mmcl_log::debug(&tf!(
        "log.gameModPackFileSync.optionalSelected",
        pack_name.as_str(),
        selected.len().to_string(),
        all.len().to_string()
    ));
    Some(mods)
}

/// The identity keys of the optional mods that are on for `side` (choices are ignored on a
/// server), the input of [`verify_fingerprint`].
pub fn enabled_keys_for_side(
    manifest: &PackManifest,
    side: Side,
    choices: &Choices,
) -> Vec<String> {
    optional::select_for_side(manifest.optional_mods(), side, choices)
        .iter()
        .map(|m| m.key())
        .collect()
}

/// The fingerprint the verify fast path and the scan schedule compare between launches: the
/// manifest hash unchanged when it is absent or no optional mod is on, else a SHA-256 over the
/// hash and the sorted keys (`<sha>|optional:<k1>,<k2>`), the same value Java computes.
pub fn verify_fingerprint(
    manifest_sha256: Option<&str>,
    enabled_keys: &[String],
) -> Option<String> {
    let sha = manifest_sha256?;
    if enabled_keys.is_empty() {
        return Some(sha.to_string());
    }
    let sorted: BTreeSet<&str> = enabled_keys.iter().map(String::as_str).collect();
    let joined = sorted.into_iter().collect::<Vec<_>>().join(",");
    Some(compute_manifest_sha256(&format!("{sha}|optional:{joined}")))
}

/// The text of [`KNOWN_OPTIONAL_MODS_FILE`] for `manifest`, or `None` when nothing should be
/// written: a pack that never had optional mods gets no sidecar, while one that had them keeps
/// its file up to date even when the list is now empty.
pub fn known_optional_mods_text(manifest: &PackManifest, sidecar_exists: bool) -> Option<String> {
    let keys: Vec<String> = manifest
        .optional_mods()
        .iter()
        .map(|m| m.key())
        .filter(|k| !k.is_empty())
        .collect();
    if keys.is_empty() && !sidecar_exists {
        return None;
    }
    Some(keys.join("\n"))
}

/// Records which optional mods the manifest declared at a successful sync. Best effort: a
/// failed write only makes the next update summary list existing optional mods as new.
pub fn remember_optional_mods(manifest: &PackManifest, pack_root: &Path, pack_name: &str) {
    let exists = PackDotFile::read(pack_root, KNOWN_OPTIONAL_MODS_FILE).is_some();
    let Some(text) = known_optional_mods_text(manifest, exists) else {
        return;
    };
    if PackDotFile::write(pack_root, KNOWN_OPTIONAL_MODS_FILE, &text).is_err() {
        mmcl_log::warn_silent(&tf!(
            "log.gameModPackFileSync.knownOptionalWriteFailed",
            pack_name
        ));
    }
}

/// The keys [`KNOWN_OPTIONAL_MODS_FILE`] lists (empty when it is absent).
pub fn known_optional_keys(pack_root: &Path) -> HashSet<String> {
    PackDotFile::read_lines(pack_root, KNOWN_OPTIONAL_MODS_FILE)
        .into_iter()
        .collect()
}

#[cfg(test)]
mod tests {
    //! Ports of the sync half of `OptionalModSyncTest`.
    use super::*;

    const MANIFEST: &str = r#"{
      "packName": "Alto",
      "packVersion": "1.0.0",
      "packMods": [
        { "name": "JEI", "remote": "https://example.invalid/jei.jar", "local": "jei.jar",
          "sha1": "-1", "md5": "-1", "sha256": "-1", "clientReq": true, "serverReq": true }
      ],
      "packOptionalMods": [
        { "name": "AUSM", "remote": "https://example.invalid/ausm.jar", "local": "ausm.jar",
          "sha256": "abc", "description": "Shader support", "requires": ["lib.jar"],
          "conflictsWith": ["celeritas-extra.jar"] },
        { "name": "Lib", "remote": "https://example.invalid/lib.jar", "local": "lib.jar",
          "serverReq": true, "defaultEnabled": true },
        { "name": "Extra", "remote": "https://example.invalid/x.jar", "local": "celeritas-extra.jar",
          "clientReq": false, "serverReq": true },
        { "name": "Dup", "remote": "https://example.invalid/jei2.jar", "local": "JEI.jar",
          "defaultEnabled": true },
        { "name": "Nested", "remote": "https://example.invalid/n.jar", "local": "mods/nested.jar",
          "defaultEnabled": true, "serverReq": true }
      ]
    }"#;

    fn pack() -> PackManifest {
        PackManifest::parse(MANIFEST).unwrap()
    }

    fn choices(pairs: &[(&str, bool)]) -> Choices {
        pairs.iter().map(|(k, v)| (k.to_string(), *v)).collect()
    }

    fn keys(mods: Option<Vec<ManifestFile>>) -> Vec<String> {
        mods.unwrap()
            .iter()
            .map(|m| optional::key_of(m.local.as_deref().unwrap_or("")))
            .collect()
    }

    #[test]
    fn a_manifest_without_the_key_has_no_optional_mods() {
        let plain = PackManifest::parse(r#"{"packName":"P","packMods":[]}"#).unwrap();
        assert!(plain.optional_mods().is_empty());
        assert_eq!(
            mods_for_side(&plain, Side::Client, &Choices::new()),
            Some(vec![])
        );
    }

    #[test]
    fn a_client_syncs_required_mods_plus_the_optional_mods_that_are_on() {
        // Defaults: only lib.jar (the duplicate of jei.jar is shadowed by the required entry).
        assert_eq!(
            keys(mods_for_side(&pack(), Side::Client, &Choices::new())),
            ["jei.jar", "lib.jar"]
        );
        // Turning AUSM on pulls in lib.jar; the duplicate stays out.
        assert_eq!(
            keys(mods_for_side(
                &pack(),
                Side::Client,
                &choices(&[("ausm.jar", true)])
            )),
            ["jei.jar", "ausm.jar", "lib.jar"]
        );
    }

    #[test]
    fn selected_optional_mods_carry_their_side_flags() {
        let mods = mods_for_side(&pack(), Side::Client, &Choices::new()).unwrap();
        let lib = mods
            .iter()
            .find(|m| m.local.as_deref() == Some("lib.jar"))
            .unwrap();
        assert!(lib.is_required_for(Side::Client));
        assert_eq!(lib.name.as_deref(), Some("Lib"));
    }

    #[test]
    fn a_disabled_optional_mod_is_not_in_the_expected_set_so_the_sweep_removes_it() {
        let expected = keys(mods_for_side(
            &pack(),
            Side::Client,
            &choices(&[("lib.jar", false)]),
        ));
        assert!(!expected.contains(&"lib.jar".to_string()));
        assert!(!expected.contains(&"ausm.jar".to_string()));
    }

    #[test]
    fn a_server_ignores_player_choices_and_uses_server_side_defaults() {
        assert_eq!(
            keys(mods_for_side(
                &pack(),
                Side::Server,
                &choices(&[("celeritas-extra.jar", true), ("lib.jar", false)])
            )),
            ["jei.jar", "lib.jar"]
        );
    }

    #[test]
    fn an_optional_mod_whose_file_name_holds_a_folder_is_never_installed() {
        for side in [Side::Client, Side::Server] {
            assert!(
                !keys(mods_for_side(&pack(), side, &Choices::new()))
                    .contains(&"nested.jar".to_string())
            );
        }
    }

    #[test]
    fn no_authoritative_mod_list_means_no_expected_set() {
        let stub = PackManifest::parse(r#"{"packOptionalMods":[{"local":"a.jar"}]}"#).unwrap();
        assert_eq!(mods_for_side(&stub, Side::Client, &Choices::new()), None);
    }

    #[test]
    fn the_fingerprint_is_the_manifest_hash_until_an_optional_mod_is_on() {
        assert_eq!(verify_fingerprint(Some("abc"), &[]).as_deref(), Some("abc"));
        assert_eq!(verify_fingerprint(None, &["a.jar".into()]), None);
        let on = verify_fingerprint(Some("abc"), &["a.jar".into(), "b.jar".into()]).unwrap();
        assert_ne!(on, "abc");
        assert_eq!(
            verify_fingerprint(Some("abc"), &["b.jar".into(), "a.jar".into()]).unwrap(),
            on,
            "order must not matter"
        );
        assert_ne!(
            verify_fingerprint(Some("abc"), &["a.jar".into()]).unwrap(),
            on
        );
        // Java: VerifyState.computeManifestSha256("abc|optional:a.jar,b.jar").
        assert_eq!(on, compute_manifest_sha256("abc|optional:a.jar,b.jar"));
    }

    #[test]
    fn the_fingerprint_follows_what_is_on_for_the_side() {
        let p = pack();
        assert_eq!(
            enabled_keys_for_side(&p, Side::Client, &Choices::new()),
            ["lib.jar", "jei.jar", "nested.jar"]
        );
        assert_eq!(
            enabled_keys_for_side(&p, Side::Server, &choices(&[("lib.jar", false)])),
            ["lib.jar", "nested.jar"]
        );
    }

    #[test]
    fn the_sidecar_lists_every_optional_mod_and_only_for_packs_that_had_them() {
        assert_eq!(
            known_optional_mods_text(&pack(), false).as_deref(),
            Some("ausm.jar\nlib.jar\nceleritas-extra.jar\njei.jar\nnested.jar")
        );
        let plain = PackManifest::parse(r#"{"packMods":[]}"#).unwrap();
        assert_eq!(known_optional_mods_text(&plain, false), None);
        assert_eq!(known_optional_mods_text(&plain, true).as_deref(), Some(""));

        let dir = tempfile::tempdir().unwrap();
        remember_optional_mods(&plain, dir.path(), "P");
        assert!(!dir.path().join(KNOWN_OPTIONAL_MODS_FILE).exists());
        remember_optional_mods(&pack(), dir.path(), "Alto");
        assert_eq!(known_optional_keys(dir.path()).len(), 5);
        remember_optional_mods(&plain, dir.path(), "P");
        assert_eq!(
            std::fs::read_to_string(dir.path().join(KNOWN_OPTIONAL_MODS_FILE)).unwrap(),
            ""
        );
        assert!(known_optional_keys(dir.path()).is_empty());
    }
}

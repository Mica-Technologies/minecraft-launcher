// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Every localization key this crate uses must exist in English (Java bundle or Rust overlay).

use std::collections::BTreeSet;

/// Keys passed indirectly (through a variable) rather than to `t!` / `tf!` as a literal.
const INDIRECT: &[&str] = &[
    "log.gameModPackEnvironment.logoSourceFailed",
    "log.gameModPackEnvironment.backgroundSourceFailed",
    "fileSync.progress.noConfigs",
    "fileSync.progress.noResourcePacks",
    "fileSync.progress.noShaderPacks",
    "fileSync.progress.noInitialFiles",
    "log.gameModPack.supplementalInfection",
    "log.gameModPack.supplementalWarning",
    "log.gameModPack.scanBlockedLaunchOne",
    "log.gameModPack.scanBlockedLaunchMany",
    "modpack.error.generic",
    "mmcjsonImporter.error.pickFile",
    "prismImporter.error.pickFolder",
    "prismImporter.error.notInstance",
    "prismImporter.error.noMinecraftFolder",
    "prismImporter.error.noComponents",
    "prismImporter.error.noMcVersion",
];

#[test]
fn every_key_exists_in_english() {
    let re = regex::Regex::new(r#"\bt[f]?!\(\s*"([A-Za-z0-9_.]+)""#).unwrap();
    let mut keys: BTreeSet<String> = INDIRECT.iter().map(|s| s.to_string()).collect();
    let src = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    for e in walkdir::WalkDir::new(&src) {
        let e = e.unwrap();
        if e.path().extension().is_some_and(|x| x == "rs") {
            let text = std::fs::read_to_string(e.path()).unwrap();
            for c in re.captures_iter(&text) {
                keys.insert(c[1].to_string());
            }
        }
    }
    let missing: Vec<&String> = keys
        .iter()
        .filter(|k| mmcl_i18n::english(k).is_none())
        .collect();
    assert!(missing.is_empty(), "missing English keys: {missing:#?}");
}

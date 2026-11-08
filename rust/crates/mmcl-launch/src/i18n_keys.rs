// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Every localization key this crate uses must exist in English (Java bundle or Rust overlay).

use std::collections::BTreeSet;

#[test]
fn every_key_exists_in_english() {
    // Keys passed to t!/tf!/lerr!, or as string literals to error and batch-key fields.
    let macro_re = regex::Regex::new(r#"\b(?:t|tf|lerr)!\(\s*"([A-Za-z0-9_.]+)""#).unwrap();
    let literal_re = regex::Regex::new(
        r#""((?:launch|minecraft|forge|forgeLoader|libraryManifest|gameModPackLauncher|officialExport|log)\.[A-Za-z0-9_.]+[A-Za-z0-9])""#,
    )
    .unwrap();
    let mut keys: BTreeSet<String> = [
        "VERIFIED_ASSET_PROGRESS_TEXT",
        "UNABLE_ACCESS_FORGE_JAR_TEXT",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    let src = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    for e in walkdir::WalkDir::new(&src) {
        let e = e.unwrap();
        if e.path().extension().is_some_and(|x| x == "rs") {
            let text = std::fs::read_to_string(e.path()).unwrap();
            for c in macro_re.captures_iter(&text) {
                keys.insert(c[1].to_string());
            }
            for c in literal_re.captures_iter(&text) {
                // File names such as `minecraft.jar` are not keys.
                if !c[1].contains(".jar") {
                    keys.insert(c[1].to_string());
                }
            }
        }
    }
    let missing: Vec<&String> = keys
        .iter()
        .filter(|k| mmcl_i18n::english(k).is_none())
        .collect();
    assert!(missing.is_empty(), "missing English keys: {missing:#?}");
}

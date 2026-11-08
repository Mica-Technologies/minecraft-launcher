// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Golden tests for the `.properties` parser against Java's `Properties.load`.
//!
//! `fixtures/edge_cases.properties` exercises every grammar rule (mixed line endings,
//! continuations, comments, escapes, separators, surrogates). `fixtures/edge_cases.java.json` is
//! what JDK 26 produced for it (`Properties.load` over a UTF-8 reader, as `PropertyResourceBundle`
//! reads bundles), generated once and committed. A synthetic fixture keeps the test stable when the
//! real bundles' text changes.
//!
//! For the real bundles, set `MMCL_I18N_JAVA_DUMPS` to a folder of `DisplayStrings*.json` dumps
//! made the same way, and `java_dumps_match_real_bundles` compares every file.

use std::collections::BTreeMap;
use std::path::PathBuf;

use mmcl_i18n::properties;

fn fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("fixtures")
}

fn load_json(path: &std::path::Path) -> BTreeMap<String, String> {
    let text = std::fs::read_to_string(path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    serde_json::from_str(&text).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

#[test]
fn edge_cases_match_java_output() {
    let bytes = std::fs::read(fixtures().join("edge_cases.properties")).unwrap();
    let (parsed, diags) = properties::parse_with_diagnostics(&properties::decode(&bytes));
    assert!(diags.is_empty(), "{diags:?}");
    let ours: BTreeMap<String, String> = parsed.into_iter().collect();
    let java = load_json(&fixtures().join("edge_cases.java.json"));
    for (k, v) in &java {
        assert_eq!(ours.get(k), Some(v), "key {k:?}");
    }
    let extra: Vec<_> = ours.keys().filter(|k| !java.contains_key(*k)).collect();
    assert!(extra.is_empty(), "keys Java does not produce: {extra:?}");
}

#[test]
fn java_dumps_match_real_bundles() {
    let Some(dir) = std::env::var_os("MMCL_I18N_JAVA_DUMPS") else {
        eprintln!("MMCL_I18N_JAVA_DUMPS not set; skipping the full-bundle Java comparison");
        return;
    };
    let dir = PathBuf::from(dir);
    let mut compared = 0;
    for suffix in mmcl_i18n::bundle::java_suffixes() {
        let name = if suffix.is_empty() {
            "DisplayStrings.json".to_string()
        } else {
            format!("DisplayStrings_{suffix}.json")
        };
        let path = dir.join(&name);
        if !path.exists() {
            continue;
        }
        let java = load_json(&path);
        let ours: BTreeMap<String, String> = mmcl_i18n::bundle::java_properties(suffix)
            .unwrap()
            .into_iter()
            .collect();
        assert_eq!(ours.len(), java.len(), "{name}: entry count");
        for (k, v) in &java {
            assert_eq!(ours.get(k), Some(v), "{name}: key {k:?}");
        }
        compared += 1;
    }
    assert!(compared > 0, "no dumps found in {}", dir.display());
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `LocalizationKeyReferencesTest`: every literal key used through `t!`, `tf!` or the
//! `mmcl_i18n::{get, get_or, format, lookup}` functions anywhere in `rust/crates` must exist in
//! the English bundle (Java + overlay). This crate's own sources and tests are skipped: they use
//! missing keys on purpose.

use std::path::PathBuf;

use regex::Regex;
use walkdir::WalkDir;

#[test]
fn every_literal_key_reference_exists_in_english() {
    let crates = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("crates dir")
        .to_path_buf();
    let patterns = [
        Regex::new(r#"\bt!\s*\(\s*"([^"\\]+)"\s*[,)]"#).unwrap(),
        Regex::new(r#"\btf!\s*\(\s*"([^"\\]+)"\s*[,)]"#).unwrap(),
        Regex::new(r#"\bi18n::(?:get|get_or|format|lookup|contains)\s*\(\s*"([^"\\]+)"\s*[,)]"#)
            .unwrap(),
    ];
    let mut missing = Vec::new();
    let mut checked = 0;
    for entry in WalkDir::new(&crates)
        .into_iter()
        .filter_entry(|e| {
            let name = e.file_name().to_string_lossy();
            !(e.file_type().is_dir()
                && (name == "target" || name == "mmcl-i18n" || name.starts_with('.')))
        })
        .filter_map(Result::ok)
    {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("rs") {
            continue;
        }
        let Ok(source) = std::fs::read_to_string(path) else {
            continue;
        };
        for re in &patterns {
            for cap in re.captures_iter(&source) {
                checked += 1;
                let key = &cap[1];
                if mmcl_i18n::english(key).is_none() {
                    let m = cap.get(0).unwrap();
                    let line = source[..m.start()].matches('\n').count() + 1;
                    missing.push(format!("{}:{line} {key}", path.display()));
                }
            }
        }
    }
    eprintln!("checked {checked} literal key references");
    assert!(
        missing.is_empty(),
        "keys referenced in code but absent from DisplayStrings.properties (Java or rust/i18n overlay): {missing:#?}"
    );
}

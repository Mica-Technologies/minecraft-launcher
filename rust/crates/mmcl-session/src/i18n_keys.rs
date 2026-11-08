// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Every localization key this crate uses must exist in English (Java bundle or Rust overlay).

use std::collections::BTreeSet;
use std::path::Path;

fn rust_files(dir: &Path, out: &mut Vec<std::path::PathBuf>) {
    for e in std::fs::read_dir(dir).unwrap() {
        let p = e.unwrap().path();
        if p.is_dir() {
            rust_files(&p, out);
        } else if p.extension().is_some_and(|x| x == "rs") {
            out.push(p);
        }
    }
}

#[test]
fn every_key_exists_in_english() {
    let macro_re = regex::Regex::new(r#"\b(?:t|tf)!\(\s*"([A-Za-z0-9_.]+)""#).unwrap();
    let literal_re =
        regex::Regex::new(r#""((?:session|launch|log\.session)\.[A-Za-z0-9_.]+[A-Za-z0-9])""#)
            .unwrap();
    let mut keys = BTreeSet::new();
    let mut files = Vec::new();
    rust_files(
        &Path::new(env!("CARGO_MANIFEST_DIR")).join("src"),
        &mut files,
    );
    for f in files {
        let text = std::fs::read_to_string(&f).unwrap();
        for c in macro_re.captures_iter(&text) {
            keys.insert(c[1].to_string());
        }
        for c in literal_re.captures_iter(&text) {
            keys.insert(c[1].to_string());
        }
    }
    assert!(keys.contains("log.session.listenerPanicked"));
    let missing: Vec<&String> = keys
        .iter()
        .filter(|k| mmcl_i18n::english(k).is_none())
        .collect();
    assert!(missing.is_empty(), "missing English keys: {missing:#?}");
}

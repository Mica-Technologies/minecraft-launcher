// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `OptionalModChoicesConfigTest`, plus the setter and the round trip with a
//! configuration file the Java launcher wrote.

use indexmap::IndexMap;
use mmcl_config::keys::OPTIONAL_MODS_BY_PACK_KEY;
use mmcl_config::store::{parse_document, serialize_document};
use mmcl_config::{Config, ConfigStore, Document};
use serde_json::{Value, json};

fn inject(by_pack: Option<Value>) -> Config {
    let mut doc = Document::new();
    if let Some(v) = by_pack {
        doc.insert(OPTIONAL_MODS_BY_PACK_KEY.into(), v);
    }
    Config::new(ConfigStore::in_memory(doc))
}

fn choices(pairs: &[(&str, bool)]) -> IndexMap<String, bool> {
    pairs.iter().map(|(k, v)| (k.to_string(), *v)).collect()
}

#[test]
fn the_key_matches_the_documented_name() {
    assert_eq!(OPTIONAL_MODS_BY_PACK_KEY, "optionalModsByPack");
}

#[test]
fn choices_are_read_by_pack_key() {
    let c = inject(Some(json!({
        "https://example.com/pack.json": {"ausm.jar": true, "lib.jar": false}
    })));
    assert_eq!(
        c.optional_mod_choices("https://example.com/pack.json"),
        choices(&[("ausm.jar", true), ("lib.jar", false)])
    );
    assert!(
        c.optional_mod_choices("https://example.com/other.json")
            .is_empty()
    );
}

#[test]
fn non_boolean_values_are_skipped() {
    let c = inject(Some(
        json!({"p": {"ausm.jar": "yes", "lib.jar": true, "n": 1}}),
    ));
    assert_eq!(c.optional_mod_choices("p"), choices(&[("lib.jar", true)]));
}

#[test]
fn absent_blank_or_malformed_means_no_choices() {
    let c = inject(None);
    assert!(c.optional_mod_choices("p").is_empty());
    assert!(c.optional_mod_choices(" ").is_empty());
    assert!(
        inject(Some(json!({"p": "not an object"})))
            .optional_mod_choices("p")
            .is_empty()
    );
    assert!(inject(Some(json!(42))).optional_mod_choices("p").is_empty());
}

#[test]
fn setting_replaces_the_packs_choices_and_empty_removes_them() {
    let c = inject(Some(json!({"other": {"x.jar": true}})));
    c.set_optional_mod_choices("p", &choices(&[("AUSM.jar", true), (" ", false)]));
    assert_eq!(
        c.store().get_value(OPTIONAL_MODS_BY_PACK_KEY),
        Some(json!({"other": {"x.jar": true}, "p": {"AUSM.jar": true}}))
    );
    c.set_optional_mod_choices("p", &choices(&[("lib.jar", false)]));
    assert_eq!(c.optional_mod_choices("p"), choices(&[("lib.jar", false)]));
    c.set_optional_mod_choices("p", &IndexMap::new());
    c.set_optional_mod_choices("other", &IndexMap::new());
    // Java leaves the emptied section in place.
    assert_eq!(
        c.store().get_value(OPTIONAL_MODS_BY_PACK_KEY),
        Some(json!({}))
    );
    c.set_optional_mod_choices("", &choices(&[("a.jar", true)]));
    assert_eq!(
        c.store().get_value(OPTIONAL_MODS_BY_PACK_KEY),
        Some(json!({}))
    );
}

#[test]
fn a_malformed_section_is_replaced_on_write() {
    let c = inject(Some(json!("broken")));
    c.set_optional_mod_choices("p", &choices(&[("a.jar", true)]));
    assert_eq!(c.optional_mod_choices("p"), choices(&[("a.jar", true)]));
}

/// What `ModPackConfig.setOptionalModChoices` leaves in `configuration.json` (Gson, compact).
const JAVA_WRITTEN: &str = r#"{"minRAM":2048,"accountOverrideByPack":{"https://example.com/alto.json":"uuid-a"},"optionalModsByPack":{"https://example.com/alto.json":{"AUSM.jar":true,"lib.jar":false},"vanilla:1.20.4":{"x.jar":true}},"theme":"dark"}"#;

#[test]
fn a_java_written_file_reads_and_writes_back_byte_for_byte() {
    let doc = parse_document(JAVA_WRITTEN.as_bytes()).unwrap();
    let c = Config::new(ConfigStore::in_memory(doc));
    let alto = c.optional_mod_choices("https://example.com/alto.json");
    assert_eq!(alto, choices(&[("AUSM.jar", true), ("lib.jar", false)]));
    assert_eq!(alto.keys().next().map(String::as_str), Some("AUSM.jar"));

    // Writing the same choices back changes nothing, so Java reads exactly what it wrote.
    c.set_optional_mod_choices("https://example.com/alto.json", &alto);
    assert_eq!(
        String::from_utf8(serialize_document(&c.store().snapshot())).unwrap(),
        JAVA_WRITTEN
    );

    // A Rust toggle writes the shape Java reads: an object of booleans under the pack key.
    c.set_optional_mod_choices(
        "https://example.com/alto.json",
        &choices(&[("AUSM.jar", false), ("lib.jar", false)]),
    );
    let text = String::from_utf8(serialize_document(&c.store().snapshot())).unwrap();
    assert!(text.contains(
        r#""optionalModsByPack":{"https://example.com/alto.json":{"AUSM.jar":false,"lib.jar":false},"vanilla:1.20.4":{"x.jar":true}}"#
    ));
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `AccountOverrideConfigTest`, plus the setter behaviour.

use mmcl_config::keys::ACCOUNT_OVERRIDE_BY_PACK_KEY;
use mmcl_config::{Config, ConfigStore, Document};
use serde_json::{Value, json};

fn inject(overrides: Option<Value>) -> Config {
    let mut doc = Document::new();
    if let Some(o) = overrides {
        doc.insert(ACCOUNT_OVERRIDE_BY_PACK_KEY.into(), o);
    }
    Config::new(ConfigStore::in_memory(doc))
}

#[test]
fn an_override_is_read_by_pack_key() {
    let c = inject(Some(json!({
        "https://example.com/pack.json": "uuid-b",
        "vanilla:1.20.4": "uuid-c",
    })));
    assert_eq!(
        c.account_override_for_pack("https://example.com/pack.json")
            .as_deref(),
        Some("uuid-b")
    );
    assert_eq!(
        c.account_override_for_pack("vanilla:1.20.4").as_deref(),
        Some("uuid-c")
    );
}

#[test]
fn no_entry_means_the_default_account() {
    assert_eq!(
        inject(None).account_override_for_pack("https://example.com/pack.json"),
        None
    );
    assert_eq!(
        inject(Some(json!({}))).account_override_for_pack("https://example.com/pack.json"),
        None
    );
}

#[test]
fn blank_keys_and_values_mean_the_default_account() {
    let c = inject(Some(json!({"p": "  ", "n": null})));
    assert_eq!(c.account_override_for_pack("p"), None);
    assert_eq!(c.account_override_for_pack("n"), None);
    assert_eq!(c.account_override_for_pack(""), None);
    assert_eq!(c.account_override_for_pack("   "), None);
}

#[test]
fn a_malformed_section_means_the_default_account() {
    let c = inject(Some(json!("not an object")));
    assert_eq!(c.account_override_for_pack("p"), None);
}

#[test]
fn setting_over_a_malformed_section_replaces_it_and_blank_removes() {
    let c = inject(Some(json!("not an object")));
    c.set_account_override_for_pack("p", Some("uuid-a"));
    assert_eq!(c.account_override_for_pack("p").as_deref(), Some("uuid-a"));
    c.set_account_override_for_pack("p", Some(" "));
    assert_eq!(c.account_override_for_pack("p"), None);
    assert_eq!(
        c.store().get_value(ACCOUNT_OVERRIDE_BY_PACK_KEY),
        Some(json!({}))
    );
    c.set_account_override_for_pack("", Some("uuid-x"));
    assert_eq!(
        c.store().get_value(ACCOUNT_OVERRIDE_BY_PACK_KEY),
        Some(json!({}))
    );
}

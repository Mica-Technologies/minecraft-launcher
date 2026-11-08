// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `ConfigStoreAccessorTest`.

use mmcl_config::{ConfigStore, Document};
use serde_json::{Value, json};

fn fresh() -> ConfigStore {
    let doc: Document = json!({
        "presentString": "hello",
        "presentBoolean": true,
        "presentInt": 42,
        "presentLong": 9_000_000_000i64,
        "presentDouble": 2.5,
        "wrongTypeForInt": "not-a-number",
    })
    .as_object()
    .unwrap()
    .clone();
    ConfigStore::in_memory(doc)
}

#[test]
fn reads_present_values_of_every_type() {
    let s = fresh();
    assert_eq!(s.get_string("presentString", "fallback"), "hello");
    assert!(s.get_bool("presentBoolean", false));
    assert_eq!(s.get_i32("presentInt", -1), 42);
    assert_eq!(s.get_i64("presentLong", -1), 9_000_000_000);
    assert_eq!(s.get_f64("presentDouble", -1.0), 2.5);
}

#[test]
fn absent_keys_fall_back_to_defaults() {
    let s = fresh();
    assert_eq!(s.get_string("missing", "fallback"), "fallback");
    assert!(s.get_bool("missing", true));
    assert!(!s.get_bool("missing", false));
    assert_eq!(s.get_i32("missing", 7), 7);
    assert_eq!(s.get_i64("missing", 7), 7);
    assert_eq!(s.get_f64("missing", 7.5), 7.5);
}

#[test]
fn null_reads_as_absent() {
    let s = fresh();
    s.set_value("nullKey", Value::Null);
    assert_eq!(s.get_i32("nullKey", 3), 3);
    // get-or-init does not replace a present null.
    assert_eq!(s.get_or_init_i32("nullKey", 4), 4);
    assert_eq!(s.get_value("nullKey"), Some(Value::Null));
}

#[test]
fn plain_read_of_absent_key_does_not_mutate_the_document() {
    let s = fresh();
    s.get_i32("missing", 7);
    assert!(!s.contains("missing"));
}

#[test]
fn wrong_typed_value_falls_back_to_the_default() {
    let s = fresh();
    assert_eq!(s.get_i32("wrongTypeForInt", 99), 99);
}

#[test]
fn wrong_typed_value_falls_back_for_every_numeric_accessor() {
    let s = fresh();
    assert_eq!(s.get_i64("wrongTypeForInt", 7), 7);
    assert_eq!(s.get_f64("wrongTypeForInt", 7.5), 7.5);
}

#[test]
fn structured_value_where_a_scalar_is_expected_falls_back() {
    let s = fresh();
    s.set_value("objectWhereScalarExpected", json!({}));
    s.set_value("multiElementArray", json!([1, 2]));
    assert_eq!(s.get_i32("objectWhereScalarExpected", 5), 5);
    assert_eq!(
        s.get_string("objectWhereScalarExpected", "fallback"),
        "fallback"
    );
    assert!(s.get_bool("objectWhereScalarExpected", true));
    assert_eq!(s.get_i32("multiElementArray", 5), 5);
}

#[test]
fn single_element_array_unwraps_to_its_element_per_gson_semantics() {
    let s = fresh();
    s.set_value("singleElementArray", json!([42]));
    assert_eq!(s.get_i32("singleElementArray", 5), 42);
}

#[test]
fn falling_back_does_not_overwrite_the_stored_value() {
    let s = fresh();
    s.get_i32("wrongTypeForInt", 99);
    assert_eq!(s.get_value("wrongTypeForInt"), Some(json!("not-a-number")));
}

#[test]
fn get_or_init_returns_existing_value_without_overwriting_it() {
    let s = fresh();
    assert_eq!(s.get_or_init_i32("presentInt", 5), 42);
    assert_eq!(s.get_value("presentInt"), Some(json!(42)));
}

#[test]
fn get_or_init_boolean_returns_existing_value_without_overwriting_it() {
    let s = fresh();
    assert!(s.get_or_init_bool("presentBoolean", false));
    assert_eq!(s.get_value("presentBoolean"), Some(json!(true)));
}

#[test]
fn get_or_init_persists_an_absent_default() {
    let s = fresh();
    assert_eq!(s.get_or_init_string("newKey", "d"), "d");
    assert_eq!(s.get_value("newKey"), Some(json!("d")));
}

#[test]
fn snapshot_returns_an_independent_copy() {
    let s = fresh();
    let mut snap = s.snapshot();
    snap.insert("presentString".into(), json!("mutated"));
    assert_eq!(s.get_string("presentString", "fallback"), "hello");
}

#[test]
fn mutate_applies_changes_to_the_live_document() {
    let s = fresh();
    s.mutate(|doc| {
        doc.insert("addedByMutate".into(), json!("yes"));
    });
    assert_eq!(s.get_string("addedByMutate", "fallback"), "yes");
}

#[test]
fn coercion_table_rows() {
    let s = fresh();
    s.set_value("str42", json!("42"));
    s.set_value("num", json!(2.5));
    s.set_value("boolT", json!(true));
    assert_eq!(s.get_i32("str42", 0), 42);
    assert_eq!(s.get_f64("str42", 0.0), 42.0);
    assert!(!s.get_bool("str42", true));
    assert_eq!(s.get_string("num", ""), "2.5");
    assert_eq!(s.get_i32("num", 0), 2);
    assert!(!s.get_bool("num", true));
    assert_eq!(s.get_string("boolT", ""), "true");
    assert_eq!(s.get_i32("boolT", 9), 9);
    assert_eq!(s.get_f64("boolT", 9.0), 9.0);
    assert!(
        !s.get_bool("presentString", true),
        "any other string is false"
    );
    // Out of int range: refused instead of truncated.
    assert_eq!(s.get_i32("presentLong", 1), 1);
}

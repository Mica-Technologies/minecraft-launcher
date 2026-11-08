// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `ConfigManagerJvmArgsMigrationTest`.

use mmcl_config::keys::*;
use mmcl_config::migrate::migrate_default_jvm_args;
use serde_json::{Map, Value, json};

fn config_with_args(args: &str) -> Map<String, Value> {
    let mut m = Map::new();
    m.insert(JVM_ARGS_KEY.into(), Value::from(args));
    m
}

fn saved_args(m: &Map<String, Value>) -> &str {
    m[JVM_ARGS_KEY].as_str().unwrap()
}

#[test]
fn the_new_default_uses_concurrent_explicit_gc() {
    assert!(JVM_ARGS_VALUE_DEFAULT.contains("-XX:+ExplicitGCInvokesConcurrent"));
    assert!(!JVM_ARGS_VALUE_DEFAULT.contains("DisableExplicitGC"));
    assert_eq!(JVM_ARGS_VALUE_DEFAULT, JVM_PRESET_PERFORMANCE_ARGS);
    assert!(JVM_ARGS_VALUE_DEFAULT_PRE_2026_10.contains("-XX:+DisableExplicitGC"));
    assert!(mmcl_util::jvm_args::is_clean(Some(JVM_ARGS_VALUE_DEFAULT)));
}

#[test]
fn the_old_default_is_replaced_with_the_new_default() {
    let mut c = config_with_args(JVM_ARGS_VALUE_DEFAULT_PRE_2026_10);
    assert!(migrate_default_jvm_args(&mut c));
    assert_eq!(saved_args(&c), JVM_ARGS_VALUE_DEFAULT);
}

#[test]
fn the_old_default_with_surrounding_whitespace_is_replaced() {
    let mut c = config_with_args(&format!("  {JVM_ARGS_VALUE_DEFAULT_PRE_2026_10} \n"));
    assert!(migrate_default_jvm_args(&mut c));
    assert_eq!(saved_args(&c), JVM_ARGS_VALUE_DEFAULT);
}

#[test]
fn a_customised_value_is_left_untouched() {
    let old = JVM_ARGS_VALUE_DEFAULT_PRE_2026_10;
    let customised = [
        format!("{old} -Xss2m"),
        old.replace("MaxGCPauseMillis=200", "MaxGCPauseMillis=100"),
        old.replace(" -XX:+AlwaysPreTouch", ""),
        old.replace(' ', "  "),
        "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:MaxGCPauseMillis=200 \
         -XX:G1HeapRegionSize=4M -XX:+AlwaysPreTouch -XX:+DisableExplicitGC"
            .to_string(),
        JVM_PRESET_LOW_MEMORY_ARGS.to_string(),
        String::new(),
    ];
    for value in &customised {
        let mut c = config_with_args(value);
        assert!(!migrate_default_jvm_args(&mut c), "{value}");
        assert_eq!(saved_args(&c), value);
    }
}

#[test]
fn the_new_default_is_left_untouched() {
    let mut c = config_with_args(JVM_ARGS_VALUE_DEFAULT);
    assert!(!migrate_default_jvm_args(&mut c));
    assert_eq!(saved_args(&c), JVM_ARGS_VALUE_DEFAULT);
}

#[test]
fn the_migration_is_idempotent() {
    let mut c = config_with_args(JVM_ARGS_VALUE_DEFAULT_PRE_2026_10);
    assert!(migrate_default_jvm_args(&mut c));
    let after_first = c.clone();
    assert!(!migrate_default_jvm_args(&mut c));
    assert_eq!(after_first, c);
}

#[test]
fn an_absent_or_non_string_value_is_left_alone() {
    let mut empty = Map::new();
    assert!(!migrate_default_jvm_args(&mut empty));
    assert!(!empty.contains_key(JVM_ARGS_KEY));

    let mut numeric = Map::new();
    numeric.insert(JVM_ARGS_KEY.into(), json!(42));
    assert!(!migrate_default_jvm_args(&mut numeric));
    assert_eq!(numeric[JVM_ARGS_KEY], json!(42));

    let mut nested = Map::new();
    nested.insert(JVM_ARGS_KEY.into(), json!({}));
    assert!(!migrate_default_jvm_args(&mut nested));
}

#[test]
fn the_old_and_new_defaults_differ_only_in_the_explicit_gc_flag() {
    assert_ne!(JVM_ARGS_VALUE_DEFAULT_PRE_2026_10, JVM_ARGS_VALUE_DEFAULT);
    assert_eq!(
        JVM_ARGS_VALUE_DEFAULT,
        JVM_ARGS_VALUE_DEFAULT_PRE_2026_10
            .replace("-XX:+DisableExplicitGC", "-XX:+ExplicitGCInvokesConcurrent")
    );
}

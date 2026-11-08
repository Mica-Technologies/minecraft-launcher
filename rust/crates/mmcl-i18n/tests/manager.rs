// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `LocalizationManagerTest`, plus live-switch checks.
//!
//! Tests here that touch the global localizer only ever pin it to `en-US`, so they can run in
//! parallel; anything that changes language uses its own `Localizer`.

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use mmcl_i18n::{FmtArg, Locale, Localizer, t, tf};

fn pin_english() {
    mmcl_i18n::set_locale(Locale::en_us());
}

fn english(key: &str) -> String {
    mmcl_i18n::bundle::java_properties("")
        .expect("English bundle")
        .get(key)
        .cloned()
        .unwrap_or_else(|| panic!("English bundle lacks {key}"))
}

const MISSING: &str = "this.key.definitely.does.not.exist.anywhere";

#[test]
fn get_known_legacy_key_returns_english_value() {
    pin_english();
    assert_eq!(t!("COMPLETED_TEXT"), english("COMPLETED_TEXT"));
}

#[test]
fn get_known_dot_namespaced_key_returns_raw_template() {
    pin_english();
    assert_eq!(t!("main.card.lastPlayed"), english("main.card.lastPlayed"));
    assert!(t!("main.card.lastPlayed").contains("{0}"));
}

#[test]
fn get_missing_key_returns_the_key_itself() {
    pin_english();
    assert_eq!(mmcl_i18n::get(MISSING), MISSING);
    assert_eq!(mmcl_i18n::lookup(MISSING), None);
    assert!(!mmcl_i18n::contains(MISSING));
}

#[test]
fn get_empty_key_returns_empty_string() {
    // Java's null key gives ""; Rust has no null, and an empty key (absent from every bundle)
    // gives "" through the missing-key rule.
    pin_english();
    assert_eq!(mmcl_i18n::get(""), "");
}

#[test]
fn get_or_missing_key_returns_fallback() {
    pin_english();
    assert_eq!(
        mmcl_i18n::get_or(MISSING, "fallback-value-should-appear"),
        "fallback-value-should-appear"
    );
}

#[test]
fn get_or_known_key_ignores_fallback() {
    pin_english();
    assert_eq!(
        mmcl_i18n::get_or("COMPLETED_TEXT", "SHOULD_NOT_APPEAR"),
        english("COMPLETED_TEXT")
    );
}

#[test]
fn format_single_placeholder_substitutes_arg() {
    pin_english();
    let expected = english("main.card.lastPlayed").replace("{0}", "Yesterday");
    assert_eq!(tf!("main.card.lastPlayed", "Yesterday"), expected);
}

#[test]
fn format_multiple_placeholders_substitute_in_order() {
    pin_english();
    assert_eq!(tf!("main.pagination.pageOfPages", 2, 5), "Page 2 of 5");
}

#[test]
fn format_three_placeholders() {
    pin_english();
    assert_eq!(
        tf!("log.rgb.controller.renderFrameThrew", "OpenRGB", 42, 3),
        "RGB renderFrame on OpenRGB threw (health=42, failures=3)"
    );
}

#[test]
fn format_no_args_returns_raw_template() {
    pin_english();
    assert_eq!(tf!("main.card.lastPlayed"), english("main.card.lastPlayed"));
    assert_eq!(
        mmcl_i18n::format("main.card.lastPlayed", &[]),
        english("main.card.lastPlayed")
    );
}

#[test]
fn format_too_few_args_leaves_literal_braces() {
    pin_english();
    assert_eq!(tf!("main.pagination.pageOfPages", 2), "Page 2 of {1}");
}

#[test]
fn format_too_many_args_ignores_extras() {
    pin_english();
    assert_eq!(
        tf!("main.card.lastPlayed", "Yesterday", "unused-extra-arg"),
        "Last played Yesterday"
    );
}

#[test]
fn format_missing_key_without_braces_returns_key_and_drops_args() {
    pin_english();
    assert_eq!(tf!(MISSING, "unused-arg"), MISSING);
}

#[test]
fn format_missing_key_with_malformed_syntax_returns_debug_string() {
    pin_english();
    let malformed = "missing.key.{0,badtype}";
    assert_eq!(tf!(malformed, "arg1"), format!("{malformed} [arg1]"));
    // Debug-string args are Java toString, not locale-formatted.
    assert_eq!(tf!("x {0", 1234, 2.0, true), "x {0 [1234, 2.0, true]");
}

#[test]
fn numbers_get_locale_grouping() {
    pin_english();
    assert_eq!(
        tf!("main.pagination.pageOfPages", 1000, 20000),
        "Page 1,000 of 20,000"
    );
    assert_eq!(
        tf!("main.pagination.pageOfPages", 1000.to_string(), 2),
        "Page 1000 of 2"
    );
}

#[test]
fn apostrophes_survive_formatting() {
    let out = mmcl_i18n::format_template(
        "l'outil {0} n'a pas démarré",
        &[FmtArg::from("X")],
        &Locale::parse("fr"),
    );
    assert_eq!(out, "l'outil X n'a pas démarré");
}

#[test]
fn templates_without_apostrophes_are_untouched() {
    let out = mmcl_i18n::format_template("Page {0} of {1}", &[], &Locale::en_us());
    assert_eq!(out, "Page {0} of {1}");
}

#[test]
fn current_locale_after_pinning_english_is_root() {
    pin_english();
    // en-US has no bundle of its own: the root bundle is in use, as in Java.
    assert!(mmcl_i18n::current_locale().is_root());
    assert_eq!(mmcl_i18n::requested_locale(), Locale::en_us());
}

#[test]
fn live_switch_changes_lookups_without_restart() {
    let loc = Localizer::new(Locale::en_us());
    let en = loc.get("COMPLETED_TEXT").into_owned();
    let calls = Arc::new(AtomicUsize::new(0));
    let seen = calls.clone();
    let id = loc.on_locale_changed(move |l| {
        assert_eq!(l.to_tag(), "de");
        seen.fetch_add(1, Ordering::SeqCst);
    });
    let before = loc.revision();
    loc.set_locale(Locale::parse("de-DE"));
    assert_eq!(loc.revision(), before + 1);
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    assert_eq!(loc.current_locale().to_tag(), "de");
    assert_eq!(loc.requested_locale().to_tag(), "de-DE");
    let de = loc.get("COMPLETED_TEXT").into_owned();
    let de_file = mmcl_i18n::bundle::java_properties("de").unwrap();
    assert_eq!(de, de_file["COMPLETED_TEXT"]);
    assert_ne!(
        de, en,
        "German bundle should differ from English for COMPLETED_TEXT"
    );
    // German numbers once switched.
    let page = loc.format("main.pagination.pageOfPages", &[1000.into(), 2.into()]);
    assert!(page.contains("1.000"), "{page}");

    loc.remove_listener(id);
    loc.set_locale(Locale::en_us());
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    assert_eq!(loc.get("COMPLETED_TEXT"), en);
}

#[test]
fn child_bundle_inherits_from_root() {
    // Every key resolves in every supported locale (the chain ends at the root).
    for (locale, _) in mmcl_i18n::supported_locales() {
        let loc = Localizer::new(locale.clone());
        for key in mmcl_i18n::english_keys().take(200) {
            assert!(loc.contains(key), "{locale}: {key}");
        }
    }
}

#[test]
fn concurrent_readers_during_switches() {
    let loc = Arc::new(Localizer::new(Locale::en_us()));
    let readers: Vec<_> = (0..4)
        .map(|_| {
            let loc = loc.clone();
            std::thread::spawn(move || {
                for _ in 0..2000 {
                    let v = loc.get("COMPLETED_TEXT");
                    assert!(!v.is_empty());
                    assert_ne!(v, "COMPLETED_TEXT");
                }
            })
        })
        .collect();
    for i in 0..50 {
        let tag = mmcl_i18n::SUPPORTED[i % mmcl_i18n::SUPPORTED.len()].0;
        loc.set_locale(Locale::parse(tag));
    }
    for r in readers {
        r.join().unwrap();
    }
}

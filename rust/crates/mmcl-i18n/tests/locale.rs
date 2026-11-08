// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Startup locale resolution (`LocaleBootstrap`) and bundle candidate chains.

use mmcl_i18n::bundle::{candidates, resolve_suffixes};
use mmcl_i18n::locale::{detect_from_raw, detect_os, os_locale, resolve, resolve_with};
use mmcl_i18n::{Locale, Localizer, SUPPORTED};

#[test]
fn blank_override_gives_the_os_locale() {
    assert_eq!(resolve(""), *os_locale());
    assert_eq!(resolve("  "), detect_os());
}

#[test]
fn und_and_garbage_give_the_os_locale() {
    let os = Locale::parse("sv-SE");
    assert_eq!(resolve_with("und", &os), os);
    assert_eq!(resolve_with("%%%", &os), os);
    assert_eq!(resolve_with("en_US", &os), os);
    assert_eq!(resolve_with("de", &os).to_tag(), "de");
}

#[test]
fn c_and_posix_give_en_us() {
    for raw in ["C", "POSIX", "C.UTF-8", "posix", "c.utf8"] {
        assert_eq!(detect_from_raw(Some(raw)), Locale::en_us(), "{raw}");
    }
}

#[test]
fn os_locale_is_a_stable_snapshot() {
    let first = os_locale().clone();
    mmcl_i18n::set_locale(Locale::parse("ja"));
    assert_eq!(
        *os_locale(),
        first,
        "applying a locale must not change the OS snapshot"
    );
    assert!(!os_locale().is_root());
}

#[test]
fn candidate_chains() {
    let c = |t: &str| candidates(&Locale::parse(t));
    assert_eq!(c("pt-BR"), ["pt_BR", "pt"]);
    assert_eq!(c("zh-Hans-CN"), ["zh_Hans_CN", "zh_Hans", "zh_CN", "zh"]);
    assert_eq!(c("zh-TW"), ["zh_Hant_TW", "zh_Hant", "zh_TW", "zh"]);
    assert_eq!(c("zh"), ["zh"]);
    assert_eq!(c("pt-PT"), ["pt_PT", "pt"]);
    assert_eq!(c("fr-CA"), ["fr_CA", "fr"]);
}

#[test]
fn resolved_bundles() {
    let r = |t: &str| resolve_suffixes(&Locale::parse(t))[0].clone();
    assert_eq!(r("pt-BR"), "pt_BR");
    assert_eq!(r("zh-Hans-CN"), "zh_CN");
    assert_eq!(r("zh-Hant-TW"), "zh_TW");
    assert_eq!(r("zh-TW"), "zh_TW");
    assert_eq!(r("zh"), "", "plain zh falls back to English (Java parity)");
    assert_eq!(
        r("pt-PT"),
        "",
        "pt-PT finds no DisplayStrings_pt (Java parity)"
    );
    assert_eq!(r("fr-CA"), "fr");
}

#[test]
fn every_supported_tag_round_trips() {
    for (tag, name) in SUPPORTED {
        let l = Locale::parse(tag);
        assert_eq!(l.to_tag(), *tag);
        assert_eq!(mmcl_i18n::display_name(tag), Some(*name));
        let loc = Localizer::new(l.clone());
        assert_eq!(loc.current_locale(), l, "{tag} resolves its own bundle");
    }
    assert_eq!(SUPPORTED.len(), 16);
    assert!(mmcl_i18n::display_name("en").is_none());
}

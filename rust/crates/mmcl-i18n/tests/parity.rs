// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `DisplayStringsBundleParityTest`, run over the same Java files the Rust build embeds,
//! plus checks that every bundle parses cleanly and the overlay stays consistent.

use std::collections::{BTreeMap, BTreeSet};

use indexmap::IndexMap;
use mmcl_i18n::bundle;
use mmcl_i18n::properties;
use mmcl_i18n::{FmtArg, Locale, Localizer, SUPPORTED};

type Props = IndexMap<String, String>;

fn english() -> Props {
    bundle::java_properties("").expect("Java English bundle is embedded")
}

fn suffix_for(tag: &str) -> String {
    tag.replace('-', "_")
}

fn locale_file(tag: &str) -> Props {
    bundle::java_properties(&suffix_for(tag)).unwrap_or_else(|| {
        panic!(
            "no Java bundle DisplayStrings_{}.properties",
            suffix_for(tag)
        )
    })
}

fn placeholder_indices(value: &str) -> BTreeSet<u32> {
    let re = regex::Regex::new(r"\{(\d+)").unwrap();
    re.captures_iter(value)
        .filter_map(|c| c[1].parse().ok())
        .collect()
}

#[test]
fn every_java_bundle_parses_without_diagnostics() {
    let mut problems = Vec::new();
    let mut count = 0;
    for suffix in bundle::java_suffixes() {
        count += 1;
        let bytes = bundle::java_source(suffix).unwrap();
        let text = std::str::from_utf8(bytes)
            .unwrap_or_else(|e| panic!("DisplayStrings_{suffix} is not UTF-8: {e}"));
        let (map, diags) = properties::parse_with_diagnostics(text);
        if !diags.is_empty() {
            problems.push(format!("{suffix:?}: {diags:?}"));
        }
        assert!(
            map.len() > 1000,
            "{suffix:?} parsed to only {} entries",
            map.len()
        );
    }
    assert_eq!(
        count,
        SUPPORTED.len() + 1,
        "expected English plus every supported locale"
    );
    assert!(problems.is_empty(), "malformed escapes: {problems:?}");
}

#[test]
fn every_supported_locale_resolves_its_own_bundle_at_runtime() {
    let mut fell_back = Vec::new();
    for (tag, _) in SUPPORTED {
        let loc = Localizer::new(Locale::parse(tag));
        if loc.current_locale().is_root() {
            fell_back.push(*tag);
        }
        // And the bundle found is the one named after the tag.
        assert_eq!(
            bundle::resolve_suffixes(&Locale::parse(tag))[0],
            suffix_for(tag),
            "{tag}"
        );
    }
    assert!(
        fell_back.is_empty(),
        "locales that fell back to English: {fell_back:?}"
    );
}

#[test]
fn no_shipped_locale_is_missing_english_keys() {
    let en = english();
    let mut missing = BTreeMap::new();
    for (tag, _) in SUPPORTED {
        let file = locale_file(tag);
        let m: BTreeSet<_> = en
            .keys()
            .filter(|k| !file.contains_key(*k))
            .cloned()
            .collect();
        if !m.is_empty() {
            missing.insert(*tag, m);
        }
    }
    assert!(missing.is_empty(), "missing keys: {missing:?}");
}

#[test]
fn no_shipped_locale_has_orphan_keys() {
    let en = english();
    let mut orphans = BTreeMap::new();
    for (tag, _) in SUPPORTED {
        let file = locale_file(tag);
        let o: BTreeSet<_> = file
            .keys()
            .filter(|k| !en.contains_key(*k))
            .cloned()
            .collect();
        if !o.is_empty() {
            orphans.insert(*tag, o);
        }
    }
    assert!(orphans.is_empty(), "orphan keys: {orphans:?}");
}

#[test]
fn message_format_placeholders_match_across_translations() {
    let en = english();
    let mut mismatches = BTreeMap::new();
    for (tag, _) in SUPPORTED {
        let file = locale_file(tag);
        let mut list = Vec::new();
        for (key, value) in &en {
            let en_slots = placeholder_indices(value);
            if en_slots.is_empty() {
                continue;
            }
            let Some(tr) = file.get(key) else { continue };
            let tr_slots = placeholder_indices(tr);
            if en_slots != tr_slots {
                list.push(format!("{key} en={en_slots:?} tr={tr_slots:?}"));
            }
        }
        if !list.is_empty() {
            mismatches.insert(*tag, list);
        }
    }
    assert!(mismatches.is_empty(), "slot mismatches: {mismatches:?}");
}

#[test]
fn translations_keep_english_line_breaks_and_add_no_backslashes() {
    let en = english();
    let mut problems = BTreeMap::new();
    for (tag, _) in SUPPORTED {
        let file = locale_file(tag);
        let mut list = Vec::new();
        for (key, ev) in &en {
            let Some(tv) = file.get(key) else { continue };
            if tv.contains('\\') && !ev.contains('\\') {
                list.push(format!("{key} has a stray backslash"));
            }
            let eb = ev.matches('\n').count();
            let tb = tv.matches('\n').count();
            if eb != tb {
                list.push(format!("{key} has {tb} line breaks, English has {eb}"));
            }
        }
        if !list.is_empty() {
            problems.insert(*tag, list);
        }
    }
    assert!(problems.is_empty(), "broken escapes: {problems:?}");
}

#[test]
fn no_bundle_uses_doubled_apostrophes() {
    let mut doubled = Vec::new();
    let mut check = |name: &str, p: &Props| {
        for (k, v) in p {
            if v.contains("''") {
                doubled.push(format!("{name}:{k}"));
            }
        }
    };
    check("en", &english());
    for (tag, _) in SUPPORTED {
        check(tag, &locale_file(tag));
    }
    for suffix in bundle::overlay_suffixes() {
        check(
            &format!("overlay {suffix:?}"),
            &bundle::overlay_properties(suffix).unwrap(),
        );
    }
    assert!(doubled.is_empty(), "doubled apostrophes: {doubled:?}");
}

#[test]
fn leading_spaces_survive_loading_in_every_locale() {
    let en = english();
    let joins_without_spaces = ["ja", "zh-CN", "zh-TW"];
    let mut lost = Vec::new();
    for (tag, _) in SUPPORTED {
        if joins_without_spaces.contains(tag) {
            continue;
        }
        let file = locale_file(tag);
        for (k, ev) in &en {
            if let Some(tv) = file.get(k)
                && ev.starts_with(' ')
                && !tv.starts_with(' ')
            {
                lost.push(format!("{tag}:{k}"));
            }
        }
    }
    assert!(lost.is_empty(), "leading space lost: {lost:?}");
}

#[test]
fn no_bundle_contains_xml_entities() {
    let entity = regex::Regex::new(r"&(quot|amp|lt|gt|apos|#\d+);").unwrap();
    let mut found = Vec::new();
    let mut check = |name: &str, p: &Props| {
        for (k, v) in p {
            if entity.is_match(v) {
                found.push(format!("{name}:{k}"));
            }
        }
    };
    check("en", &english());
    for (tag, _) in SUPPORTED {
        check(tag, &locale_file(tag));
    }
    assert!(found.is_empty(), "XML entities: {found:?}");
}

#[test]
fn choice_patterns_format_in_every_bundle() {
    let mut bundles: Vec<(String, Props)> = vec![("en".into(), english())];
    for (tag, _) in SUPPORTED {
        bundles.push(((*tag).into(), locale_file(tag)));
    }
    let mut broken = Vec::new();
    let mut seen = 0;
    for (name, props) in &bundles {
        for (key, value) in props {
            if !value.contains(",choice,") {
                continue;
            }
            seen += 1;
            for n in [0i64, 1, 5] {
                match mmcl_i18n::msgfmt::format(value, &[FmtArg::Int(n)], &Locale::ROOT) {
                    Ok(out) => {
                        if out.contains('{') || out.contains('|') || !out.contains(&n.to_string()) {
                            broken.push(format!("{name}:{key} n={n} -> {out}"));
                        }
                    }
                    Err(e) => broken.push(format!("{name}:{key} n={n} -> {e}")),
                }
            }
        }
    }
    assert!(seen > 0, "no choice patterns found; the scan is broken");
    assert!(
        broken.is_empty(),
        "choice patterns that don't format: {broken:?}"
    );
}

#[test]
fn every_template_formats_without_falling_back() {
    // Every bundle value with a numbered slot must be a valid pattern for our formatter, so the
    // `template [args]` debug string never reaches users. Ten numeric args cover every slot.
    let args: Vec<FmtArg> = (0..10).map(|i| FmtArg::Int(i + 2)).collect();
    let mut bad = Vec::new();
    let mut all: Vec<(String, Props)> = vec![("en".into(), english())];
    for (tag, _) in SUPPORTED {
        all.push(((*tag).into(), locale_file(tag)));
    }
    for (name, props) in &all {
        for (key, value) in props {
            // Only templates with numbered slots are format() templates; values such as
            // `{name}.jar.disabled` are plain text read with get().
            if placeholder_indices(value).is_empty() {
                continue;
            }
            if let Err(e) = mmcl_i18n::msgfmt::format(value, &args, &Locale::ROOT) {
                bad.push(format!("{name}:{key}: {e}"));
            }
        }
    }
    assert!(bad.is_empty(), "patterns that fail to format: {bad:?}");
}

#[test]
fn overlay_bundles_parse_and_translate_only_known_keys() {
    let en_overlay = bundle::overlay_properties("").unwrap_or_default();
    let en_java = english();
    let mut problems = Vec::new();
    for suffix in bundle::overlay_suffixes() {
        let bytes = bundle::overlay_source(suffix).unwrap();
        let text = properties::decode(bytes);
        let (map, diags) = properties::parse_with_diagnostics(&text);
        if !diags.is_empty() {
            problems.push(format!("overlay {suffix:?}: {diags:?}"));
        }
        if suffix.is_empty() {
            continue;
        }
        assert!(
            bundle::java_source(suffix).is_some(),
            "overlay locale {suffix:?} has no Java bundle; use the Java filename form (pt_BR)"
        );
        for key in map.keys() {
            if !en_overlay.contains_key(key) && !en_java.contains_key(key) {
                problems.push(format!("overlay {suffix:?} has orphan key {key}"));
            }
        }
    }
    assert!(problems.is_empty(), "{problems:?}");
}

#[test]
fn overlay_wins_over_java() {
    // Whatever the overlay defines in English is what lookups return.
    if let Some(overlay) = bundle::overlay_properties("") {
        for (k, v) in &overlay {
            assert_eq!(mmcl_i18n::english(k), Some(v.as_str()), "{k}");
        }
    }
}

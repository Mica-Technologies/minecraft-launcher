// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The embedded bundles and Java's `ResourceBundle` resolution rules.
//!
//! Each bundle *suffix* (`""` for the English root, `"de"`, `"pt_BR"`, `"zh_CN"`, ...) has a Java
//! file, a Rust overlay file, or both. Its effective content is the Java file with the overlay's
//! entries laid over it (overlay wins). A locale resolves to a *chain* of suffixes, most specific
//! first and always ending at the root, and a lookup walks the chain: a child bundle inherits any
//! key it lacks from its parents, as in Java.

use std::collections::HashMap;
use std::sync::OnceLock;

use indexmap::IndexMap;

use crate::locale::Locale;
use crate::properties;

mod generated {
    include!(concat!(env!("OUT_DIR"), "/bundles.rs"));
}

use generated::{JAVA_FILES, OVERLAY_FILES, SUFFIX_COUNT, SUFFIXES};

/// One suffix's merged entries.
#[derive(Debug)]
pub(crate) struct FileBundle {
    map: HashMap<String, String>,
}

impl FileBundle {
    pub(crate) fn get(&'static self, key: &str) -> Option<&'static str> {
        self.map.get(key).map(String::as_str)
    }

    pub(crate) fn keys(&'static self) -> impl Iterator<Item = &'static str> {
        self.map.keys().map(String::as_str)
    }
}

static LOADED: [OnceLock<FileBundle>; SUFFIX_COUNT] = [const { OnceLock::new() }; SUFFIX_COUNT];

fn index_of(suffix: &str) -> Option<usize> {
    SUFFIXES.iter().position(|s| *s == suffix)
}

fn source(files: &'static [(&'static str, &'static [u8])], suffix: &str) -> Option<&'static [u8]> {
    files.iter().find(|(s, _)| *s == suffix).map(|(_, b)| *b)
}

fn load(suffix: &str) -> FileBundle {
    let mut map = HashMap::new();
    for bytes in [source(JAVA_FILES, suffix), source(OVERLAY_FILES, suffix)]
        .into_iter()
        .flatten()
    {
        for (k, v) in properties::parse(&properties::decode(bytes)) {
            map.insert(k, v);
        }
    }
    FileBundle { map }
}

/// The merged bundle for `suffix`, parsed on first use.
pub(crate) fn file(suffix: &str) -> Option<&'static FileBundle> {
    let idx = index_of(suffix)?;
    Some(LOADED[idx].get_or_init(|| load(SUFFIXES[idx])))
}

/// The root (English) bundle; always present (the build fails without it).
pub(crate) fn root() -> &'static FileBundle {
    file("").expect("the root bundle is embedded at build time")
}

/// Every embedded suffix (Java or overlay), sorted; `""` is the root.
pub fn suffixes() -> &'static [&'static str] {
    SUFFIXES
}

/// True when a Java or overlay file exists for `suffix`.
pub fn has_bundle(suffix: &str) -> bool {
    index_of(suffix).is_some()
}

/// The raw bytes of the Java bundle `DisplayStrings[_suffix].properties`, if embedded.
pub fn java_source(suffix: &str) -> Option<&'static [u8]> {
    source(JAVA_FILES, suffix)
}

/// The raw bytes of the Rust overlay bundle for `suffix`, if one exists.
pub fn overlay_source(suffix: &str) -> Option<&'static [u8]> {
    source(OVERLAY_FILES, suffix)
}

/// The suffixes that have a Java file.
pub fn java_suffixes() -> impl Iterator<Item = &'static str> {
    JAVA_FILES.iter().map(|(s, _)| *s)
}

/// The suffixes that have an overlay file.
pub fn overlay_suffixes() -> impl Iterator<Item = &'static str> {
    OVERLAY_FILES.iter().map(|(s, _)| *s)
}

/// Parses the Java bundle for `suffix` alone (no overlay), for parity checks.
pub fn java_properties(suffix: &str) -> Option<IndexMap<String, String>> {
    java_source(suffix).map(|b| properties::parse(&properties::decode(b)))
}

/// Parses the overlay bundle for `suffix` alone, for parity checks.
pub fn overlay_properties(suffix: &str) -> Option<IndexMap<String, String>> {
    overlay_source(suffix).map(|b| properties::parse(&properties::decode(b)))
}

/// Java's `Control.toBundleName` suffix for (language, script, region).
fn bundle_name(language: &str, script: &str, region: &str) -> String {
    let mut s = language.to_string();
    if !script.is_empty() {
        s.push('_');
        s.push_str(script);
    }
    if !region.is_empty() {
        s.push('_');
        s.push_str(region);
    }
    s
}

fn default_list(language: &str, script: &str, region: &str) -> Vec<String> {
    let mut script = script.to_string();
    let mut region = region.to_string();
    // Java infers the Chinese script from the region.
    if language == "zh" && script.is_empty() && !region.is_empty() {
        script = match region.as_str() {
            "TW" | "HK" | "MO" => "Hant",
            "CN" | "SG" => "Hans",
            _ => "",
        }
        .to_string();
    }
    let mut list = Vec::with_capacity(4);
    if !region.is_empty() {
        list.push(bundle_name(language, &script, &region));
    }
    if !script.is_empty() {
        list.push(bundle_name(language, &script, ""));
        // Chinese with a script only: supply the region users expect.
        if language == "zh" && region.is_empty() {
            region = match script.as_str() {
                "Hans" => "CN",
                "Hant" => "TW",
                _ => "",
            }
            .to_string();
        }
        if !region.is_empty() {
            list.push(bundle_name(language, "", &region));
        }
    }
    if !language.is_empty() {
        list.push(language.to_string());
    }
    list
}

/// The candidate bundle suffixes for `locale`, most specific first, as Java's default
/// `ResourceBundle.Control.getCandidateLocales` produces them. The root bundle (`""`) is implied
/// after the last candidate and not listed.
///
/// `pt-BR` gives `["pt_BR", "pt"]`; `zh-TW` gives `["zh_Hant_TW", "zh_Hant", "zh_TW", "zh"]`;
/// `zh` gives `["zh"]`, which has no file, so plain Chinese shows English (Java parity).
pub fn candidates(locale: &Locale) -> Vec<String> {
    let (lang, script, region) = (locale.language(), locale.script(), locale.region());
    if lang == "nb" {
        // Java interleaves the Norwegian Bokmål candidates with their `no` equivalents.
        let mut out = Vec::new();
        for c in default_list("nb", script, region) {
            let no = format!("no{}", &c[2..]);
            out.push(c);
            out.push(no);
        }
        return out;
    }
    default_list(lang, script, region)
}

/// The locale a bundle suffix stands for (`"pt_BR"` gives `pt-BR`, `""` the root).
pub fn locale_of_suffix(suffix: &str) -> Locale {
    let mut parts = suffix.split('_');
    let language = parts.next().unwrap_or("");
    let (mut script, mut region) = ("", "");
    for p in parts {
        if p.len() == 4 && script.is_empty() && region.is_empty() {
            script = p;
        } else if region.is_empty() {
            region = p;
        }
    }
    Locale::new(language, script, region)
}

/// A resolved chain for one requested locale.
pub(crate) struct Resolved {
    pub(crate) chain: Vec<&'static FileBundle>,
    /// The locale of the most specific bundle found (Java's `bundle.getLocale()`).
    pub(crate) locale: Locale,
}

/// The existing-bundle suffixes for `locale`, most specific first, ending with `""`.
pub fn resolve_suffixes(locale: &Locale) -> Vec<String> {
    let mut found: Vec<String> = candidates(locale)
        .into_iter()
        .filter(|s| has_bundle(s))
        .collect();
    found.push(String::new());
    found
}

pub(crate) fn resolve(locale: &Locale) -> Resolved {
    let suffixes = resolve_suffixes(locale);
    let locale = locale_of_suffix(&suffixes[0]);
    let chain = suffixes.iter().filter_map(|s| file(s)).collect::<Vec<_>>();
    Resolved { chain, locale }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c(tag: &str) -> Vec<String> {
        candidates(&Locale::parse(tag))
    }

    /// Expected lists are JDK 26 `Control.getCandidateLocales` output, root removed.
    #[test]
    fn candidate_chains_match_java() {
        assert_eq!(c("pt-BR"), ["pt_BR", "pt"]);
        assert_eq!(c("zh-Hans-CN"), ["zh_Hans_CN", "zh_Hans", "zh_CN", "zh"]);
        assert_eq!(c("zh-TW"), ["zh_Hant_TW", "zh_Hant", "zh_TW", "zh"]);
        assert_eq!(c("zh-HK"), ["zh_Hant_HK", "zh_Hant", "zh_HK", "zh"]);
        assert_eq!(c("zh-SG"), ["zh_Hans_SG", "zh_Hans", "zh_SG", "zh"]);
        assert_eq!(c("zh-Hans"), ["zh_Hans", "zh_CN", "zh"]);
        assert_eq!(c("zh-Hant"), ["zh_Hant", "zh_TW", "zh"]);
        assert_eq!(c("zh"), ["zh"]);
        assert_eq!(c("zh-Hant-CN"), ["zh_Hant_CN", "zh_Hant", "zh_CN", "zh"]);
        assert_eq!(c("pt-PT"), ["pt_PT", "pt"]);
        assert_eq!(c("fr-CA"), ["fr_CA", "fr"]);
        assert_eq!(c("sr-Latn-RS"), ["sr_Latn_RS", "sr_Latn", "sr_RS", "sr"]);
        assert_eq!(c("es-419"), ["es_419", "es"]);
        assert_eq!(c("nb-NO"), ["nb_NO", "no_NO", "nb", "no"]);
        assert!(c("und").is_empty());
    }

    #[test]
    fn resolution_picks_existing_files() {
        assert_eq!(resolve_suffixes(&Locale::parse("pt-BR")), ["pt_BR", ""]);
        assert_eq!(
            resolve_suffixes(&Locale::parse("zh-Hans-CN")),
            ["zh_CN", ""]
        );
        assert_eq!(
            resolve_suffixes(&Locale::parse("zh-Hant-TW")),
            ["zh_TW", ""]
        );
        assert_eq!(resolve_suffixes(&Locale::parse("zh-TW")), ["zh_TW", ""]);
        assert_eq!(resolve_suffixes(&Locale::parse("zh")), [""]);
        assert_eq!(resolve_suffixes(&Locale::parse("pt-PT")), [""]);
        assert_eq!(resolve_suffixes(&Locale::parse("fr-CA")), ["fr", ""]);
        assert_eq!(resolve_suffixes(&Locale::parse("en-US")), [""]);
        assert_eq!(resolve(&Locale::parse("fr-CA")).locale.to_tag(), "fr");
        assert!(resolve(&Locale::parse("en-US")).locale.is_root());
    }

    #[test]
    fn suffix_locales() {
        assert_eq!(locale_of_suffix("pt_BR").to_tag(), "pt-BR");
        assert_eq!(locale_of_suffix("zh_Hans_CN").to_tag(), "zh-Hans-CN");
        assert_eq!(locale_of_suffix("zh_Hant").to_tag(), "zh-Hant");
        assert!(locale_of_suffix("").is_root());
    }
}

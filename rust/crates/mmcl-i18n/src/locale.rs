// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Locales: a small BCP-47 language-tag type with Java `Locale.forLanguageTag` leniency, the OS
//! locale snapshot, and the startup resolution rules of the Java `LocaleBootstrap`.

use std::fmt;
use std::str::FromStr;
use std::sync::OnceLock;

/// A language identifier: language, optional script and optional region.
///
/// Fields are normalised the way Java's `Locale` does it: language lowercase, script title-case,
/// region uppercase. The root locale (`und`) has an empty language. Variants and extensions are
/// accepted when parsing but not kept; no bundle or number format uses them.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Default)]
pub struct Locale {
    language: String,
    script: String,
    region: String,
}

/// Alias matching the spec's API sketch.
pub type LanguageIdentifier = Locale;

impl Locale {
    /// The root locale (`und`): what Java calls `Locale.ROOT`.
    pub const ROOT: Locale = Locale {
        language: String::new(),
        script: String::new(),
        region: String::new(),
    };

    /// Builds a locale from parts, normalising case. Empty parts are allowed.
    pub fn new(language: &str, script: &str, region: &str) -> Self {
        let language = canonical_language(&language.to_ascii_lowercase());
        let script = title_case(script);
        let region = region.to_ascii_uppercase();
        Locale {
            language: if language == "und" {
                String::new()
            } else {
                language
            },
            script,
            region,
        }
    }

    /// `en-US`, the fallback for unknown or C/POSIX OS locales (Java's `Locale.US`).
    pub fn en_us() -> Self {
        Locale::new("en", "", "US")
    }

    /// Parses a BCP-47 tag leniently, like Java's `Locale.forLanguageTag`: parsing stops at the
    /// first ill-formed subtag, and a tag whose language is ill-formed gives [`Locale::ROOT`].
    /// Underscores are not separators (`en_US` gives the root), matching Java.
    pub fn parse(tag: &str) -> Locale {
        let mut parts = tag.split('-').peekable();
        let Some(first) = parts.next() else {
            return Locale::ROOT;
        };
        let first_l = first.to_ascii_lowercase();
        if !is_alpha(&first_l) || !(2..=8).contains(&first_l.len()) {
            return Locale::ROOT;
        }
        let mut language = first_l;
        // extlang: up to three 3-letter subtags after a 2-3 letter language; the first replaces it.
        if language.len() <= 3 {
            let mut ext = 0;
            while ext < 3 {
                match parts.peek() {
                    Some(p) if p.len() == 3 && is_alpha(p) => {
                        if ext == 0 {
                            language = p.to_ascii_lowercase();
                        }
                        parts.next();
                        ext += 1;
                    }
                    _ => break,
                }
            }
        }
        let mut script = String::new();
        if let Some(p) = parts.peek()
            && p.len() == 4
            && is_alpha(p)
        {
            script = title_case(p);
            parts.next();
        }
        let mut region = String::new();
        if let Some(p) = parts.peek()
            && ((p.len() == 2 && is_alpha(p))
                || (p.len() == 3 && p.bytes().all(|b| b.is_ascii_digit())))
        {
            region = p.to_ascii_uppercase();
            parts.next();
        }
        Locale::new(&language, &script, &region)
    }

    /// Parses a tag, returning `None` for the root/`und` result (an empty, `und` or ill-formed tag).
    pub fn try_parse(tag: &str) -> Option<Locale> {
        let l = Locale::parse(tag);
        if l.is_root() { None } else { Some(l) }
    }

    /// Language subtag, lowercase; empty for the root locale.
    pub fn language(&self) -> &str {
        &self.language
    }

    /// Script subtag in title case (`Hans`), or empty.
    pub fn script(&self) -> &str {
        &self.script
    }

    /// Region subtag in uppercase (`BR`, `419`), or empty.
    pub fn region(&self) -> &str {
        &self.region
    }

    /// True for the root locale (no language). Java's `getLanguage().isEmpty()`.
    pub fn is_root(&self) -> bool {
        self.language.is_empty()
    }

    /// The BCP-47 tag: `pt-BR`, `zh-Hans-CN`, or `und` for the root.
    pub fn to_tag(&self) -> String {
        let mut s = if self.language.is_empty() {
            "und".to_string()
        } else {
            self.language.clone()
        };
        for part in [&self.script, &self.region] {
            if !part.is_empty() {
                s.push('-');
                s.push_str(part);
            }
        }
        s
    }

    /// True when the language is written right to left (Arabic, Hebrew, Persian, Urdu, ...).
    pub fn is_right_to_left(&self) -> bool {
        matches!(
            self.language.as_str(),
            "ar" | "he" | "fa" | "ur" | "yi" | "ps" | "sd" | "ug" | "dv" | "ckb"
        )
    }
}

impl fmt::Display for Locale {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.to_tag())
    }
}

impl FromStr for Locale {
    type Err = std::convert::Infallible;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(Locale::parse(s))
    }
}

fn is_alpha(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_alphabetic())
}

fn title_case(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for (i, c) in s.chars().enumerate() {
        if i == 0 {
            out.push(c.to_ascii_uppercase());
        } else {
            out.push(c.to_ascii_lowercase());
        }
    }
    out
}

/// Java maps the deprecated ISO 639 codes to their current forms.
fn canonical_language(lang: &str) -> String {
    match lang {
        "iw" => "he",
        "ji" => "yi",
        "in" => "id",
        other => other,
    }
    .to_string()
}

/// Normalises a raw OS locale string: strips `.codeset` and `@modifier` and turns `_` into `-`
/// (`en_US.UTF-8` gives `en-US`). Returns `None` for an empty, `C` or `POSIX` locale.
pub fn normalize_os_tag(raw: &str) -> Option<String> {
    let raw = raw.trim();
    let base = raw.split(['.', '@']).next().unwrap_or("");
    if base.is_empty() || base.eq_ignore_ascii_case("C") || base.eq_ignore_ascii_case("POSIX") {
        return None;
    }
    Some(base.replace('_', "-"))
}

/// Java's `detectOsLocale` over a raw OS string: `None`, blank, `und`, `C` or `POSIX` (with any
/// codeset) give `en-US`; anything else is parsed.
pub fn detect_from_raw(raw: Option<&str>) -> Locale {
    raw.and_then(normalize_os_tag)
        .and_then(|t| Locale::try_parse(&t))
        .unwrap_or_else(Locale::en_us)
}

static OS_LOCALE: OnceLock<Locale> = OnceLock::new();

/// The OS locale, captured once on first call (before any override is applied), so "Use OS
/// Language" re-detects the real OS locale rather than the last one applied.
pub fn os_locale() -> &'static Locale {
    OS_LOCALE.get_or_init(|| detect_from_raw(sys_locale::get_locale().as_deref()))
}

/// The OS locale with the C/POSIX/`und` cases mapped to `en-US`.
pub fn detect_os() -> Locale {
    os_locale().clone()
}

/// Java's `LocaleBootstrap.resolve`: a blank override gives the OS locale; otherwise the parsed
/// tag, unless it parses to nothing (`und`, garbage), which also gives the OS locale.
pub fn resolve(override_tag: &str) -> Locale {
    resolve_with(override_tag, os_locale())
}

/// [`resolve`] with the OS locale supplied, for tests.
pub fn resolve_with(override_tag: &str, os: &Locale) -> Locale {
    let tag = override_tag.trim();
    if tag.is_empty() {
        return os.clone();
    }
    Locale::try_parse(tag).unwrap_or_else(|| os.clone())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_matches_java_for_language_tag() {
        // Expected values were produced by Java 26 Locale.forLanguageTag(..).toLanguageTag().
        let cases = [
            ("pt-BR", "pt-BR"),
            ("zh-hans-cn", "zh-Hans-CN"),
            ("ZH-hant-tw", "zh-Hant-TW"),
            ("EN-us", "en-US"),
            ("en_US", "und"),
            ("C", "und"),
            ("123", "und"),
            ("", "und"),
            ("und", "und"),
            ("x-foo", "und"),
            ("es-419", "es-419"),
            ("en-123", "en-123"),
            ("en-Latn-US-foo_bar", "en-Latn-US"),
            ("en-US-", "en-US"),
            ("en--US", "en"),
            ("iw", "he"),
            ("in", "id"),
            ("ji", "yi"),
            ("eng-US", "eng-US"),
            ("ja-JP-u-ca-japanese", "ja-JP"),
        ];
        for (input, expected) in cases {
            assert_eq!(Locale::parse(input).to_tag(), expected, "input {input:?}");
        }
    }

    #[test]
    fn os_normalisation() {
        assert_eq!(detect_from_raw(Some("C")), Locale::en_us());
        assert_eq!(detect_from_raw(Some("POSIX")), Locale::en_us());
        assert_eq!(detect_from_raw(Some("C.UTF-8")), Locale::en_us());
        assert_eq!(detect_from_raw(Some("")), Locale::en_us());
        assert_eq!(detect_from_raw(None), Locale::en_us());
        assert_eq!(detect_from_raw(Some("und")), Locale::en_us());
        assert_eq!(detect_from_raw(Some("en_US.UTF-8")).to_tag(), "en-US");
        assert_eq!(detect_from_raw(Some("de_DE@euro")).to_tag(), "de-DE");
        assert_eq!(detect_from_raw(Some("zh-Hans-CN")).to_tag(), "zh-Hans-CN");
    }

    #[test]
    fn resolve_rules() {
        let os = Locale::parse("fr-CA");
        assert_eq!(resolve_with("", &os), os);
        assert_eq!(resolve_with("   ", &os), os);
        assert_eq!(resolve_with("und", &os), os);
        assert_eq!(resolve_with("!!garbage!!", &os), os);
        assert_eq!(resolve_with("pt-BR", &os).to_tag(), "pt-BR");
        assert_eq!(resolve_with("zh-TW", &os).to_tag(), "zh-TW");
    }

    #[test]
    fn rtl() {
        assert!(Locale::parse("ar").is_right_to_left());
        assert!(!Locale::parse("fr").is_right_to_left());
    }
}

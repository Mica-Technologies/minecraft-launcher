// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The UI languages offered in Settings (Java `SupportedLocales.ENTRIES`).

use crate::locale::Locale;

/// `(BCP-47 tag, display name in its own language)`, in dropdown order. English is not listed:
/// the UI offers "Use OS Language" (an empty override) plus these. Keep in sync with
/// `TARGET_LOCALES` in `tools/i18n/translate-locales.js`.
///
/// Tags are BCP-47 (`pt-BR`); bundle filenames use the underscore form (`pt_BR`).
pub const SUPPORTED: &[(&str, &str)] = &[
    ("es", "Español"),
    ("fr", "Français"),
    ("de", "Deutsch"),
    ("pt-BR", "Português (Brasil)"),
    ("it", "Italiano"),
    ("ru", "Русский"),
    ("ja", "日本語"),
    ("ko", "한국어"),
    ("zh-CN", "简体中文"),
    ("zh-TW", "繁體中文"),
    ("ar", "العربية"),
    ("hi", "हिन्दी"),
    ("nl", "Nederlands"),
    ("pl", "Polski"),
    ("tr", "Türkçe"),
    ("sv", "Svenska"),
];

/// The supported languages as parsed locales with their native names.
pub fn supported_locales() -> impl Iterator<Item = (Locale, &'static str)> {
    SUPPORTED
        .iter()
        .map(|(tag, name)| (Locale::parse(tag), *name))
}

/// The native display name for a supported tag (compared case-insensitively), if listed.
pub fn display_name(tag: &str) -> Option<&'static str> {
    SUPPORTED
        .iter()
        .find(|(t, _)| t.eq_ignore_ascii_case(tag))
        .map(|(_, n)| *n)
}

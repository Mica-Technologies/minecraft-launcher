// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The bundled help pages and the page lookup rules (`HelpTopic.getResourcePath`,
//! `MCLauncherHelpWindow.localizedPagePath` / `isRightToLeft`).
//!
//! The pages are the Java launcher's own resources (`src/main/resources/help/`), compiled in so
//! the two launchers always show the same text. Translated pages live in `help/<lang>_<COUNTRY>/`
//! or `help/<lang>/`; anything missing falls back to the English page.

use mmcl_ui_kit::HelpTopic;

/// The resource paths of the bundled pages, `(path, html)`.
macro_rules! page {
    ($folder:literal, $file:literal) => {
        (
            concat!("help/", $folder, $file),
            include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../../../src/main/resources/help/",
                $folder,
                $file
            )),
        )
    };
}

/// One folder's 14 pages.
macro_rules! folder {
    ($folder:literal) => {
        [
            page!($folder, "getting-started.html"),
            page!($folder, "main-screen.html"),
            page!($folder, "login.html"),
            page!($folder, "settings.html"),
            page!($folder, "browse-library.html"),
            page!($folder, "modpack-editor.html"),
            page!($folder, "game-console.html"),
            page!($folder, "vanilla-versions.html"),
            page!($folder, "runtime-management.html"),
            page!($folder, "keyboard-shortcuts.html"),
            page!($folder, "jvm-presets.html"),
            page!($folder, "action-reference.html"),
            page!($folder, "language.html"),
            page!($folder, "troubleshooting.html"),
        ]
    };
}

type Folder = [(&'static str, &'static str); 14];

/// The English pages and every translated folder the Java launcher ships.
static FOLDERS: [Folder; 17] = [
    folder!(""),
    folder!("ar/"),
    folder!("de/"),
    folder!("es/"),
    folder!("fr/"),
    folder!("hi/"),
    folder!("it/"),
    folder!("ja/"),
    folder!("ko/"),
    folder!("nl/"),
    folder!("pl/"),
    folder!("pt_BR/"),
    folder!("ru/"),
    folder!("sv/"),
    folder!("tr/"),
    folder!("zh_CN/"),
    folder!("zh_TW/"),
];

/// The translated folders (without the English root), in the order above.
pub const TRANSLATED_FOLDERS: [&str; 16] = [
    "ar", "de", "es", "fr", "hi", "it", "ja", "ko", "nl", "pl", "pt_BR", "ru", "sv", "tr", "zh_CN",
    "zh_TW",
];

/// Every bundled page, `(resource path, html)`.
pub fn all_pages() -> impl Iterator<Item = (&'static str, &'static str)> {
    FOLDERS.iter().flat_map(|f| f.iter().copied())
}

/// The bundled page at `path` (`help/de/login.html`), if any.
pub fn page(path: &str) -> Option<&'static str> {
    all_pages().find(|(p, _)| *p == path).map(|(_, html)| html)
}

/// `localizedPagePath`: `help/<lang>_<COUNTRY>/<file>`, then `help/<lang>/<file>`, else the
/// English path. An empty language means English.
pub fn localized_page_path(
    english: &str,
    language: &str,
    country: &str,
    exists: impl Fn(&str) -> bool,
) -> String {
    let Some(slash) = english.rfind('/') else {
        return english.to_string();
    };
    if language.is_empty() {
        return english.to_string();
    }
    let (folder, file) = (&english[..=slash], &english[slash + 1..]);
    let mut candidates = Vec::with_capacity(2);
    if !country.is_empty() {
        candidates.push(format!("{folder}{language}_{country}/{file}"));
    }
    candidates.push(format!("{folder}{language}/{file}"));
    candidates
        .into_iter()
        .find(|c| exists(c))
        .unwrap_or_else(|| english.to_string())
}

/// `isRightToLeft`: only Arabic, Hebrew (both codes), Persian and Urdu.
pub fn is_right_to_left(language: &str) -> bool {
    matches!(language, "ar" | "he" | "iw" | "fa" | "ur")
}

/// A resolved page for a topic in a locale.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedPage {
    /// The resource path used.
    pub path: String,
    /// The page HTML (`None` when nothing is bundled for the topic).
    pub html: Option<&'static str>,
    /// Right-to-left: only when a translated page was actually found for an RTL language.
    pub rtl: bool,
}

/// Resolves `topic` for `language` / `country` against the bundled pages.
pub fn resolve(topic: HelpTopic, language: &str, country: &str) -> ResolvedPage {
    let english = topic.resource_path();
    let path = localized_page_path(english, language, country, |p| page(p).is_some());
    let rtl = path != english && is_right_to_left(language);
    ResolvedPage {
        html: page(&path),
        path,
        rtl,
    }
}

/// `help://topic/<NAME>` -> the topic: name upper-cased, `-` -> `_`, matched to the Java enum
/// constant name. `None` for anything else.
pub fn topic_from_link(href: &str) -> Option<Result<HelpTopic, String>> {
    let name = href.trim().strip_prefix("help://topic/")?;
    let normalized = name
        .trim_end_matches('/')
        .to_ascii_uppercase()
        .replace('-', "_");
    Some(topic_by_name(&normalized).ok_or_else(|| name.to_string()))
}

/// The Java enum constant name of a topic.
pub const fn java_name(topic: HelpTopic) -> &'static str {
    match topic {
        HelpTopic::GettingStarted => "GETTING_STARTED",
        HelpTopic::MainScreen => "MAIN_SCREEN",
        HelpTopic::Login => "LOGIN",
        HelpTopic::Settings => "SETTINGS",
        HelpTopic::BrowseLibrary => "BROWSE_LIBRARY",
        HelpTopic::ModpackEditor => "MODPACK_EDITOR",
        HelpTopic::GameConsole => "GAME_CONSOLE",
        HelpTopic::VanillaVersions => "VANILLA_VERSIONS",
        HelpTopic::RuntimeManagement => "RUNTIME_MANAGEMENT",
        HelpTopic::KeyboardShortcuts => "KEYBOARD_SHORTCUTS",
        HelpTopic::JvmPresets => "JVM_PRESETS",
        HelpTopic::ActionReference => "ACTION_REFERENCE",
        HelpTopic::Language => "LANGUAGE",
        HelpTopic::Troubleshooting => "TROUBLESHOOTING",
    }
}

/// The topic for a Java enum constant name.
pub fn topic_by_name(name: &str) -> Option<HelpTopic> {
    HelpTopic::ALL.into_iter().find(|t| java_name(*t) == name)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    fn resources() -> std::path::PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../src/main/resources")
    }

    /// `HelpPageLocalizationTest.prefersLanguageCountryFolder`.
    #[test]
    fn prefers_the_language_country_folder() {
        let exists = |p: &str| p == "help/pt_BR/login.html" || p == "help/pt/login.html";
        assert_eq!(
            localized_page_path("help/login.html", "pt", "BR", exists),
            "help/pt_BR/login.html"
        );
    }

    /// `HelpPageLocalizationTest.fallsBackToLanguageThenEnglish`.
    #[test]
    fn falls_back_to_language_then_english() {
        let exists = |p: &str| p == "help/de/login.html";
        assert_eq!(
            localized_page_path("help/login.html", "de", "AT", exists),
            "help/de/login.html"
        );
        assert_eq!(
            localized_page_path("help/login.html", "xx", "YY", exists),
            "help/login.html"
        );
        assert_eq!(
            localized_page_path("help/login.html", "", "", |_| true),
            "help/login.html"
        );
        assert_eq!(
            localized_page_path("login.html", "de", "", |_| true),
            "login.html"
        );
    }

    /// `HelpPageLocalizationTest.onlyRtlLanguagesFlip`.
    #[test]
    fn only_rtl_languages_flip() {
        for l in ["ar", "he", "iw", "fa", "ur"] {
            assert!(is_right_to_left(l), "{l}");
        }
        for l in ["en", "de", "ja", "zh", ""] {
            assert!(!is_right_to_left(l), "{l}");
        }
        // RTL only when the translated page was found.
        assert!(resolve(HelpTopic::Login, "ar", "").rtl);
        assert!(
            !resolve(HelpTopic::Login, "fa", "").rtl,
            "no Persian pages ship"
        );
    }

    /// `HelpPageLocalizationTest.everyTranslatedPageHasAnEnglishOriginal`, plus: every page on
    /// disk is bundled.
    #[test]
    fn every_translated_page_has_an_english_original_and_is_bundled() {
        let root = resources().join("help");
        let mut seen = 0;
        for entry in std::fs::read_dir(&root).expect("help folder") {
            let entry = entry.unwrap();
            if !entry.file_type().unwrap().is_dir() {
                continue;
            }
            let lang = entry.file_name().to_string_lossy().to_string();
            assert!(
                TRANSLATED_FOLDERS.contains(&lang.as_str()),
                "folder {lang} is not bundled"
            );
            for page_entry in std::fs::read_dir(entry.path()).unwrap() {
                let name = page_entry
                    .unwrap()
                    .file_name()
                    .to_string_lossy()
                    .to_string();
                if !name.ends_with(".html") {
                    continue;
                }
                assert!(
                    root.join(&name).exists(),
                    "{lang}/{name} has no English original"
                );
                assert!(
                    page(&format!("help/{lang}/{name}")).is_some(),
                    "{lang}/{name}"
                );
                seen += 1;
            }
        }
        assert_eq!(seen, 16 * 14);
        for topic in HelpTopic::ALL {
            assert!(page(topic.resource_path()).is_some(), "{topic:?}");
        }
    }

    #[test]
    fn resolve_uses_the_bundled_translations() {
        let de = resolve(HelpTopic::Settings, "de", "DE");
        assert_eq!(de.path, "help/de/settings.html");
        assert!(de.html.is_some());
        let br = resolve(HelpTopic::Settings, "pt", "BR");
        assert_eq!(br.path, "help/pt_BR/settings.html");
        let pt = resolve(HelpTopic::Settings, "pt", "PT");
        assert_eq!(pt.path, "help/settings.html", "no plain pt folder ships");
        let en = resolve(HelpTopic::Settings, "en", "US");
        assert_eq!(en.path, "help/settings.html");
        assert!(!en.rtl);
    }

    #[test]
    fn help_links_resolve_like_the_java_bridge() {
        assert_eq!(
            topic_from_link("help://topic/LOGIN"),
            Some(Ok(HelpTopic::Login))
        );
        assert_eq!(
            topic_from_link("help://topic/browse-library"),
            Some(Ok(HelpTopic::BrowseLibrary))
        );
        assert_eq!(
            topic_from_link("help://topic/NOPE"),
            Some(Err("NOPE".to_string()))
        );
        assert_eq!(topic_from_link("https://example.com"), None);
        for t in HelpTopic::ALL {
            assert_eq!(topic_by_name(java_name(t)), Some(t));
        }
    }

    #[test]
    fn every_link_in_every_page_names_a_topic() {
        for (path, html) in all_pages() {
            let mut rest = html;
            while let Some(p) = rest.find("help://topic/") {
                let tail = &rest[p..];
                let end = tail.find(['"', '\'']).unwrap_or(tail.len());
                let link = &tail[..end];
                assert!(
                    matches!(topic_from_link(link), Some(Ok(_))),
                    "{path}: {link}"
                );
                rest = &tail[end..];
            }
        }
    }
}

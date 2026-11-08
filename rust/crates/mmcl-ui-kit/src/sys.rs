// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! OS helpers from `GUIUtilities`: open a URL in the browser, open a folder, light/dark chrome,
//! and the redaction applied to anything a dialog shows.

use std::path::Path;
use std::sync::LazyLock;

use gpui::App;
use mmcl_i18n::tf;
use parking_lot::RwLock;
use regex::Regex;

/// The `[REDACTED]` marker.
pub const REDACTED: &str = "[REDACTED]";

static ACCESS_TOKEN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(--accessToken)([=\s]+)\S+").expect("regex"));
static CLIENT_TOKEN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(--clientToken)([=\s]+)\S+").expect("regex"));
static LEGACY_SESSION: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"token:[A-Za-z0-9._-]+:([0-9a-fA-F-]{32,36})").expect("regex"));

/// The Java `SensitiveDataRedactor.redact`: masks `--accessToken` / `--clientToken` values and
/// the token in a legacy `token:<token>:<uuid>` session id. The default dialog redactor.
pub fn redact_tokens(input: &str) -> String {
    if input.is_empty() {
        return String::new();
    }
    let out = ACCESS_TOKEN.replace_all(input, format!("${{1}}${{2}}{REDACTED}").as_str());
    let out = CLIENT_TOKEN.replace_all(&out, format!("${{1}}${{2}}{REDACTED}").as_str());
    let out = LEGACY_SESSION.replace_all(&out, format!("token:{REDACTED}:${{1}}").as_str());
    out.into_owned()
}

type Redactor = fn(&str) -> String;

static REDACTOR: RwLock<Redactor> = RwLock::new(redact_tokens);

/// Replaces the redactor dialogs run their text through (the app installs `mmcl_log::redact`
/// so every surface masks the same things).
pub fn set_redactor(redactor: fn(&str) -> String) {
    *REDACTOR.write() = redactor;
}

/// Runs the installed redactor.
pub fn redact(input: &str) -> String {
    (REDACTOR.read())(input)
}

/// `UrlValidation.sanitizedHttpUrl`: the trimmed URL when it parses with an `http`/`https`
/// scheme, else `None` (blank, malformed, `file:`, `javascript:`, ...).
pub fn sanitized_http_url(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let parsed = url::Url::parse(trimmed).ok()?;
    matches!(parsed.scheme(), "http" | "https").then(|| parsed.to_string())
}

/// Opens an http(s) URL in the browser; anything else is refused and logged.
pub fn open_external_url(cx: &App, url: &str) {
    match sanitized_http_url(url) {
        None => tracing::warn!("{}", tf!("log.modpack.refuseNonHttpUrl", url)),
        Some(safe) => cx.open_url(&safe),
    }
}

/// Opens a folder in the system file browser; a missing folder is ignored.
pub fn open_folder(cx: &App, dir: &Path) {
    if dir.as_os_str().is_empty() || !dir.exists() {
        return;
    }
    cx.open_with_system(dir);
}

/// `GUIUtilities.isLightChrome` over the configured theme string (matched exactly, like Java).
///
/// Java's rule was `theme == "Light" || (theme == "Native (Mica)" && !os_dark)`; this follows
/// [`mmcl_theme::is_light_chrome`], which derives it from the resolved sheet and so also gives
/// Automatic-on-a-light-OS the light title bar its light window needs.
pub fn is_light_chrome(theme: &str, os_dark: bool) -> bool {
    match mmcl_theme::ThemeId::from_config(theme) {
        Some(id) => mmcl_theme::is_light_chrome(id, os_dark),
        None => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Port of `SensitiveDataRedactorTest` (the `redact` half).
    #[test]
    fn redacts_tokens_but_keeps_the_rest() {
        assert_eq!(
            redact_tokens("java --accessToken eyJhbGciOi.abc.def --username Steve"),
            "java --accessToken [REDACTED] --username Steve"
        );
        assert_eq!(
            redact_tokens("--clientToken=abc123 next"),
            "--clientToken=[REDACTED] next"
        );
        assert_eq!(
            redact_tokens("token:secretvalue:0123456789abcdef0123456789abcdef"),
            "token:[REDACTED]:0123456789abcdef0123456789abcdef"
        );
        assert_eq!(redact_tokens("nothing secret"), "nothing secret");
        assert_eq!(redact_tokens(""), "");
    }

    #[test]
    fn only_http_urls_pass() {
        assert_eq!(
            sanitized_http_url("  https://example.com/pack  ").as_deref(),
            Some("https://example.com/pack")
        );
        assert!(sanitized_http_url("HTTP://example.com").is_some());
        assert_eq!(sanitized_http_url("file:///etc/passwd"), None);
        assert_eq!(sanitized_http_url("javascript:alert(1)"), None);
        assert_eq!(sanitized_http_url("not a url"), None);
        assert_eq!(sanitized_http_url(""), None);
    }

    #[test]
    fn light_chrome_rule() {
        assert!(is_light_chrome("Light", true));
        assert!(is_light_chrome("Native (Mica)", false));
        assert!(!is_light_chrome("Native (Mica)", true));
        assert!(!is_light_chrome("Dark", false));
        // Fixed rule (mmcl-theme): Automatic on a light OS gets light chrome to match its window.
        assert!(is_light_chrome("Automatic", false));
        assert!(!is_light_chrome("Automatic", true));
        assert!(!is_light_chrome("bogus", false));
    }
}

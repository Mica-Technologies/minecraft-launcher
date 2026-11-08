// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `mmcl://` deep links (`LauncherUriHandler`): recognition, parsing, and the install-URL
//! safety classifier. Dispatch (dialogs, installs, launches) lives in the app crate behind
//! its `DeepLinkHost`; this module only decides what a link asks for.
//!
//! ```text
//! mmcl://add?url=<urlenc manifest>     install a modpack
//! mmcl://play?name=<pack or friendly name>
//! mmcl://join?url=<urlenc manifest>    install if needed, then play (Discord "Join")
//! mmcl://join?vanilla=<version id>     install the vanilla version if needed, then play
//! mmcl://open                          bring the window forward
//! ```

use std::collections::HashMap;

pub use mmcl_core::args::is_launcher_uri as is_launcher_uri_str;
pub use mmcl_core::identity::{URL_SCHEME as SCHEME, URL_SCHEME_PREFIX as SCHEME_PREFIX};

use crate::uri;

/// Hosts whose manifests install without a confirmation prompt. Exact match only (after
/// lowercasing): `evil.<trusted>.attacker.com` still asks.
pub const TRUSTED_INSTALL_HOSTS: &[&str] = &["micauseaststorage.blob.core.windows.net"];

/// `true` when `s` starts with `mmcl://` (any case). `mmcl:play` does not match.
pub fn is_launcher_uri(s: Option<&str>) -> bool {
    s.is_some_and(is_launcher_uri_str)
}

/// What a deep link asks for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeepLink {
    /// Install the modpack whose manifest is at `url`.
    Add { url: String },
    /// Launch the installed pack with this pack name or (case-insensitive) friendly name.
    Play { name: String },
    /// Install the pack at `url` if needed, then launch it.
    JoinUrl { url: String },
    /// Install vanilla `version` if needed, then launch it.
    JoinVanilla { version: String },
    /// Bring the launcher window forward.
    Open,
}

impl DeepLink {
    /// The lowercase action name (`add`, `play`, `join`, `open`).
    pub fn action(&self) -> &'static str {
        match self {
            DeepLink::Add { .. } => "add",
            DeepLink::Play { .. } => "play",
            DeepLink::JoinUrl { .. } | DeepLink::JoinVanilla { .. } => "join",
            DeepLink::Open => "open",
        }
    }
}

/// Why a string is not a usable deep link. Each maps to the Java handler's log key.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum DeepLinkError {
    /// Not an `mmcl://` URI at all (the handler ignores it silently).
    #[error("not a launcher URI")]
    NotLauncherUri,
    /// `URI.create` would reject it.
    #[error("malformed launcher URI: {0}")]
    Malformed(String),
    /// An action other than add/play/join/open.
    #[error("unknown mmcl:// action: {0}")]
    UnknownAction(String),
    /// `add` without `url`.
    #[error("mmcl://add missing required url parameter")]
    AddMissingUrl,
    /// `play` without `name`.
    #[error("mmcl://play missing required name parameter")]
    PlayMissingName,
    /// `join` with neither `url` nor `vanilla`.
    #[error("mmcl://join missing required url or vanilla parameter")]
    JoinMissingParam,
}

impl DeepLinkError {
    /// The localization key the handler logs for this error (`None` for
    /// [`DeepLinkError::NotLauncherUri`], which is not logged).
    pub fn log_key(&self) -> Option<&'static str> {
        Some(match self {
            DeepLinkError::NotLauncherUri => return None,
            DeepLinkError::Malformed(_) => "log.uriHandler.malformedUri",
            DeepLinkError::UnknownAction(_) => "log.uriHandler.unknownAction",
            DeepLinkError::AddMissingUrl => "log.uriHandler.addMissingUrl",
            DeepLinkError::PlayMissingName => "log.uriHandler.playMissingName",
            DeepLinkError::JoinMissingParam => "log.uriHandler.joinMissingParam",
        })
    }

    /// The localized log line.
    pub fn localized(&self) -> String {
        match (self, self.log_key()) {
            (_, None) => String::new(),
            (DeepLinkError::Malformed(s) | DeepLinkError::UnknownAction(s), Some(k)) => {
                mmcl_i18n::tf!(k, s)
            }
            (_, Some(k)) => mmcl_i18n::get(k).into_owned(),
        }
    }
}

/// The action and parameters of a launcher URI before they are interpreted.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawDeepLink {
    /// The action as written (the authority host, or the text after `mmcl://` up to `?`).
    pub action: String,
    /// The decoded query parameters (a later duplicate wins).
    pub params: HashMap<String, String>,
}

/// Parses the action and query of a launcher URI (steps 1, 3, 4 and 5 of `handle`).
pub fn parse_raw(s: &str) -> Result<RawDeepLink, DeepLinkError> {
    if !is_launcher_uri_str(s) {
        return Err(DeepLinkError::NotLauncherUri);
    }
    let parsed = uri::parse(s).map_err(|_| DeepLinkError::Malformed(s.to_string()))?;
    let action = match parsed.host {
        Some(h) => h.to_string(),
        None => {
            let body = &s[SCHEME_PREFIX.len()..];
            body.split('?').next().unwrap_or("").to_string()
        }
    };
    Ok(RawDeepLink {
        action,
        params: parse_query(parsed.query),
    })
}

/// Parses and interprets a launcher URI.
pub fn parse(s: &str) -> Result<DeepLink, DeepLinkError> {
    let raw = parse_raw(s)?;
    let param = |k: &str| raw.params.get(k).filter(|v| !v.trim().is_empty()).cloned();
    match raw.action.to_lowercase().as_str() {
        "add" => param("url")
            .map(|url| DeepLink::Add { url })
            .ok_or(DeepLinkError::AddMissingUrl),
        "play" => param("name")
            .map(|name| DeepLink::Play { name })
            .ok_or(DeepLinkError::PlayMissingName),
        "join" => {
            if let Some(version) = param("vanilla") {
                Ok(DeepLink::JoinVanilla { version })
            } else if let Some(url) = param("url") {
                Ok(DeepLink::JoinUrl { url })
            } else {
                Err(DeepLinkError::JoinMissingParam)
            }
        }
        "open" => Ok(DeepLink::Open),
        _ => Err(DeepLinkError::UnknownAction(raw.action)),
    }
}

/// Decodes `key1=value1&key2=value2` (`parseQuery`): empty segments are skipped, a segment
/// without `=` has the value `""`, both sides are form-decoded (`+` is a space, `%XX` is
/// UTF-8), a pair with a malformed `%` is skipped, and a later duplicate wins.
pub fn parse_query(query: Option<&str>) -> HashMap<String, String> {
    let mut out = HashMap::new();
    let Some(q) = query.filter(|q| !q.is_empty()) else {
        return out;
    };
    for part in q.split('&').filter(|p| !p.is_empty()) {
        let (k, v) = part.split_once('=').unwrap_or((part, ""));
        if let (Some(k), Some(v)) = (form_decode(k), form_decode(v)) {
            out.insert(k, v);
        }
    }
    out
}

/// `URLDecoder.decode(s, UTF_8)`: `+` becomes a space and `%XX` sequences are decoded as
/// UTF-8 (invalid UTF-8 becomes U+FFFD). `None` for a malformed escape.
pub fn form_decode(s: &str) -> Option<String> {
    let b = s.as_bytes();
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'+' => {
                out.push(' ');
                i += 1;
            }
            b'%' => {
                let mut bytes = Vec::new();
                while i < b.len() && b[i] == b'%' {
                    let hex = s.get(i + 1..i + 3)?;
                    // Checked first: from_str_radix would accept a sign ("%+1").
                    if !hex.bytes().all(|c| c.is_ascii_hexdigit()) {
                        return None;
                    }
                    bytes.push(u8::from_str_radix(hex, 16).ok()?);
                    i += 3;
                }
                out.push_str(&String::from_utf8_lossy(&bytes));
            }
            _ => {
                let ch = s[i..].chars().next()?;
                out.push(ch);
                i += ch.len_utf8();
            }
        }
    }
    Some(out)
}

/// How the launcher treats a manifest URL that arrived over `mmcl://` (or the MCP install
/// tool).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InstallUrlVerdict {
    /// Well-formed https on a trusted host: install without asking.
    AcceptTrusted,
    /// Well-formed https on any other host: ask the user first.
    RequireConfirmation,
    /// Missing, malformed, not https, control characters, no host: refuse.
    Reject,
}

/// Classifies an install URL (`classifyInstallUrl`).
pub fn classify_install_url(url: Option<&str>) -> InstallUrlVerdict {
    let Some(url) = url.filter(|u| !u.trim().is_empty()) else {
        return InstallUrlVerdict::Reject;
    };
    if url.chars().any(|c| (c as u32) < 0x20 || c as u32 == 0x7F) {
        return InstallUrlVerdict::Reject;
    }
    let Ok(parsed) = uri::parse(url) else {
        return InstallUrlVerdict::Reject;
    };
    if !parsed
        .scheme
        .is_some_and(|s| s.eq_ignore_ascii_case("https"))
    {
        return InstallUrlVerdict::Reject;
    }
    let Some(host) = parsed.non_blank_host() else {
        return InstallUrlVerdict::Reject;
    };
    let host = host.to_lowercase();
    if TRUSTED_INSTALL_HOSTS.contains(&host.as_str()) {
        InstallUrlVerdict::AcceptTrusted
    } else {
        InstallUrlVerdict::RequireConfirmation
    }
}

/// The URL's host for display in the confirmation prompt, or the raw URL when it does not
/// parse or has no host.
pub fn host_of(url: &str) -> String {
    match uri::parse(url) {
        Ok(p) => p.non_blank_host().unwrap_or(url).to_string(),
        Err(_) => url.to_string(),
    }
}

/// Percent-encodes everything except RFC 3986 unreserved characters (`A-Z a-z 0-9 - . _ ~`),
/// as UTF-8 with uppercase hex. For deep links placed in `.desktop` actions and jump lists.
pub fn pct_encode_unreserved(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for &b in s.as_bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// `mmcl://play?name=<name>`.
pub fn play_link(pack_name: &str) -> String {
    format!(
        "{SCHEME_PREFIX}play?name={}",
        pct_encode_unreserved(pack_name)
    )
}

/// `mmcl://join?url=<manifest url>`.
pub fn join_link(manifest_url: &str) -> String {
    format!(
        "{SCHEME_PREFIX}join?url={}",
        pct_encode_unreserved(manifest_url)
    )
}

#[cfg(test)]
mod tests {
    use super::InstallUrlVerdict::*;
    use super::*;

    fn map(pairs: &[(&str, &str)]) -> HashMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    // ---- LauncherUriHandlerClassifyInstallUrlTest ----

    #[test]
    fn null_url_is_rejected() {
        assert_eq!(classify_install_url(None), Reject);
    }

    #[test]
    fn empty_url_is_rejected() {
        assert_eq!(classify_install_url(Some("")), Reject);
    }

    #[test]
    fn blank_url_is_rejected() {
        assert_eq!(classify_install_url(Some("   ")), Reject);
    }

    #[test]
    fn embedded_control_character_below_space_is_rejected() {
        assert_eq!(
            classify_install_url(Some(
                "https://micauseaststorage.blob.core.windows.net/x\n.json"
            )),
            Reject
        );
    }

    #[test]
    fn embedded_carriage_return_is_rejected() {
        assert_eq!(
            classify_install_url(Some("https://example.com/x\r.json")),
            Reject
        );
    }

    #[test]
    fn embedded_nul_byte_is_rejected() {
        assert_eq!(
            classify_install_url(Some("https://example.com/x\0.json")),
            Reject
        );
    }

    #[test]
    fn embedded_del_character_is_rejected() {
        assert_eq!(
            classify_install_url(Some("https://example.com/x\u{7f}.json")),
            Reject
        );
    }

    #[test]
    fn malformed_uri_is_rejected() {
        assert_eq!(
            classify_install_url(Some("https://example.com/not a valid uri")),
            Reject
        );
    }

    #[test]
    fn http_scheme_is_rejected() {
        assert_eq!(
            classify_install_url(Some("http://example.com/manifest.json")),
            Reject
        );
    }

    #[test]
    fn schemeless_url_is_rejected() {
        assert_eq!(
            classify_install_url(Some("example.com/manifest.json")),
            Reject
        );
    }

    #[test]
    fn non_http_scheme_is_rejected() {
        assert_eq!(
            classify_install_url(Some("ftp://example.com/manifest.json")),
            Reject
        );
    }

    #[test]
    fn scheme_case_is_ignored_for_acceptance() {
        assert_eq!(
            classify_install_url(Some("HTTPS://example.com/manifest.json")),
            RequireConfirmation
        );
    }

    #[test]
    fn missing_host_is_rejected() {
        assert_eq!(classify_install_url(Some("https:///manifest.json")), Reject);
    }

    #[test]
    fn trusted_host_is_accepted_silently() {
        assert_eq!(
            classify_install_url(Some(
                "https://micauseaststorage.blob.core.windows.net/packs/manifest.json"
            )),
            AcceptTrusted
        );
    }

    #[test]
    fn trusted_host_match_is_case_insensitive() {
        assert_eq!(
            classify_install_url(Some(
                "https://MICAUSEASTSTORAGE.BLOB.CORE.WINDOWS.NET/packs/manifest.json"
            )),
            AcceptTrusted
        );
    }

    #[test]
    fn ordinary_unknown_https_host_requires_confirmation() {
        assert_eq!(
            classify_install_url(Some("https://example.com/manifest.json")),
            RequireConfirmation
        );
    }

    #[test]
    fn similar_but_not_exact_host_requires_confirmation() {
        assert_eq!(
            classify_install_url(Some(
                "https://evil.micauseaststorage.blob.core.windows.net.attacker.com/x.json"
            )),
            RequireConfirmation
        );
    }

    #[test]
    fn registry_style_host_is_rejected() {
        assert_eq!(
            classify_install_url(Some("https://bad_host.example.com/x.json")),
            Reject
        );
    }

    // ---- LauncherUriHandlerQueryAndSchemeTest ----

    #[test]
    fn recognizes_lowercase_scheme() {
        assert!(is_launcher_uri(Some("mmcl://play?name=x")));
    }

    #[test]
    fn scheme_match_is_case_insensitive() {
        assert!(is_launcher_uri(Some("MMCL://play?name=x")));
        assert!(is_launcher_uri(Some("Mmcl://Play")));
    }

    #[test]
    fn rejects_other_schemes() {
        assert!(!is_launcher_uri(Some("https://example.com")));
        assert!(!is_launcher_uri(Some("mmclx://play")));
    }

    #[test]
    fn rejects_null_and_blank() {
        assert!(!is_launcher_uri(None));
        assert!(!is_launcher_uri(Some("")));
    }

    #[test]
    fn rejects_scheme_without_slash_slash() {
        assert!(!is_launcher_uri(Some("mmcl:play")));
    }

    #[test]
    fn parses_single_key_value_pair() {
        assert_eq!(parse_query(Some("name=MyPack")), map(&[("name", "MyPack")]));
    }

    #[test]
    fn parses_multiple_pairs_separated_by_ampersand() {
        assert_eq!(
            parse_query(Some("name=MyPack&version=2")),
            map(&[("name", "MyPack"), ("version", "2")])
        );
    }

    #[test]
    fn url_decodes_both_keys_and_values() {
        assert_eq!(
            parse_query(Some("url=https%3A%2F%2Fexample.com%2Fa%20b")),
            map(&[("url", "https://example.com/a b")])
        );
        assert_eq!(parse_query(Some("a+b=c+d")), map(&[("a b", "c d")]));
        assert_eq!(parse_query(Some("n=caf%C3%A9")), map(&[("n", "caf\u{e9}")]));
    }

    #[test]
    fn treats_key_with_no_equals_sign_as_empty_value() {
        assert_eq!(parse_query(Some("flag")), map(&[("flag", "")]));
    }

    #[test]
    fn empty_and_null_query_return_empty_map() {
        assert!(parse_query(Some("")).is_empty());
        assert!(parse_query(None).is_empty());
    }

    #[test]
    fn skips_empty_segments_from_double_ampersand() {
        assert_eq!(
            parse_query(Some("a=1&&b=2")),
            map(&[("a", "1"), ("b", "2")])
        );
    }

    #[test]
    fn later_duplicate_key_overwrites_earlier_one() {
        assert_eq!(
            parse_query(Some("name=first&name=second")),
            map(&[("name", "second")])
        );
    }

    #[test]
    fn malformed_escape_skips_only_that_pair() {
        assert_eq!(parse_query(Some("a=%zz&b=2")), map(&[("b", "2")]));
        assert_eq!(parse_query(Some("a=%4&b=2")), map(&[("b", "2")]));
    }

    #[test]
    fn host_of_returns_host_for_well_formed_url() {
        assert_eq!(host_of("https://example.com/pack.json"), "example.com");
    }

    #[test]
    fn host_of_falls_back_to_raw_url_on_parse_failure() {
        let malformed = "https://exa mple.com/x with space";
        assert_eq!(host_of(malformed), malformed);
    }

    #[test]
    fn host_of_falls_back_to_raw_url_when_host_is_absent() {
        let no_host = "mailto:someone@example.com";
        assert_eq!(host_of(no_host), no_host);
    }

    // ---- parse ----

    #[test]
    fn parses_every_action() {
        assert_eq!(
            parse("mmcl://add?url=https%3A%2F%2Fexample.com%2Fp.json"),
            Ok(DeepLink::Add {
                url: "https://example.com/p.json".into()
            })
        );
        assert_eq!(
            parse("MMCL://Play?name=My+Pack"),
            Ok(DeepLink::Play {
                name: "My Pack".into()
            })
        );
        assert_eq!(
            parse("mmcl://join?url=https://example.com/p.json"),
            Ok(DeepLink::JoinUrl {
                url: "https://example.com/p.json".into()
            })
        );
        assert_eq!(
            parse("mmcl://join?url=x&vanilla=1.20.1"),
            Ok(DeepLink::JoinVanilla {
                version: "1.20.1".into()
            })
        );
        assert_eq!(parse("mmcl://open"), Ok(DeepLink::Open));
    }

    #[test]
    fn parse_reports_each_failure() {
        assert_eq!(parse("https://x"), Err(DeepLinkError::NotLauncherUri));
        assert!(matches!(
            parse("mmcl://play?name=a b"),
            Err(DeepLinkError::Malformed(_))
        ));
        assert_eq!(
            parse("mmcl://explode"),
            Err(DeepLinkError::UnknownAction("explode".into()))
        );
        assert_eq!(parse("mmcl://add"), Err(DeepLinkError::AddMissingUrl));
        assert_eq!(
            parse("mmcl://play?name=%20"),
            Err(DeepLinkError::PlayMissingName)
        );
        assert_eq!(parse("mmcl://join"), Err(DeepLinkError::JoinMissingParam));
        assert_eq!(
            DeepLinkError::AddMissingUrl.log_key(),
            Some("log.uriHandler.addMissingUrl")
        );
    }

    #[test]
    fn registry_authority_falls_back_to_the_text_before_the_query() {
        let raw = parse_raw("mmcl://my_action?x=1").unwrap();
        assert_eq!(raw.action, "my_action");
        assert_eq!(raw.params, map(&[("x", "1")]));
    }

    #[test]
    fn links_round_trip() {
        assert_eq!(pct_encode_unreserved("a b/\u{e9}~"), "a%20b%2F%C3%A9~");
        assert_eq!(
            parse(&play_link("All The Mods 9")),
            Ok(DeepLink::Play {
                name: "All The Mods 9".into()
            })
        );
        let url = "https://example.com/p.json?x=1&y=2";
        assert_eq!(
            parse(&join_link(url)),
            Ok(DeepLink::JoinUrl { url: url.into() })
        );
    }
}

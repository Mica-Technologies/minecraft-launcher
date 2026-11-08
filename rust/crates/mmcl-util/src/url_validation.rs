// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `UrlValidation`: the gate every author-supplied link passes before the launcher opens it.
//!
//! Only `http`/`https` URIs that `java.net.URI.create` accepts get through, so a manifest's
//! "website" field cannot point at `file:`, `javascript:` or a registered OS handler such as
//! `mmcl://` or `ms-settings:`.

use crate::uri;

/// The trimmed input, exactly as typed, when it parses strictly as a URI whose scheme is
/// `http` or `https` (any case). Never a normalised form: `https://example.com` comes back
/// without a trailing `/`.
pub fn sanitized_http_url(raw: &str) -> Option<String> {
    let t = raw.trim();
    if t.is_empty() {
        return None;
    }
    let parsed = uri::parse(t).ok()?;
    let scheme = parsed.scheme?;
    if scheme.eq_ignore_ascii_case("http") || scheme.eq_ignore_ascii_case("https") {
        Some(t.to_string())
    } else {
        None
    }
}

/// [`sanitized_http_url`] for an optional value (`None` gives `None`).
pub fn sanitized_http_url_opt(raw: Option<&str>) -> Option<String> {
    raw.and_then(sanitized_http_url)
}

/// `true` when [`sanitized_http_url`] accepts `raw`.
pub fn is_http_url(raw: &str) -> bool {
    sanitized_http_url(raw).is_some()
}

/// [`is_http_url`] for an optional value (`None` is not a URL).
pub fn is_http_url_opt(raw: Option<&str>) -> bool {
    raw.is_some_and(is_http_url)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_https_url() {
        assert!(is_http_url("https://example.com/page"));
        assert_eq!(
            sanitized_http_url("https://example.com/page").as_deref(),
            Some("https://example.com/page")
        );
    }

    #[test]
    fn accepts_http_url() {
        assert!(is_http_url("http://example.com"));
    }

    #[test]
    fn scheme_match_is_case_insensitive() {
        assert!(is_http_url("HTTPS://example.com"));
        assert!(is_http_url("HtTp://example.com"));
    }

    #[test]
    fn trims_surrounding_whitespace_before_parsing() {
        assert_eq!(
            sanitized_http_url("   https://example.com  ").as_deref(),
            Some("https://example.com")
        );
    }

    #[test]
    fn rejects_file_scheme() {
        assert!(!is_http_url("file:///etc/passwd"));
        assert_eq!(sanitized_http_url("file:///etc/passwd"), None);
    }

    #[test]
    fn rejects_javascript_scheme() {
        assert!(!is_http_url("javascript:alert(1)"));
    }

    #[test]
    fn rejects_custom_handler_scheme() {
        assert!(!is_http_url("mmcl://play?name=x"));
    }

    #[test]
    fn rejects_schemeless_url() {
        assert!(!is_http_url("example.com/page"));
        assert!(!is_http_url("//example.com/page"));
    }

    #[test]
    fn rejects_null_and_blank() {
        assert!(!is_http_url_opt(None));
        assert!(!is_http_url(""));
        assert!(!is_http_url("   "));
        assert_eq!(sanitized_http_url_opt(None), None);
        assert_eq!(sanitized_http_url(""), None);
    }

    #[test]
    fn rejects_malformed_uri() {
        assert!(!is_http_url("https://exa mple.com/page with space"));
        assert!(!is_http_url("https://example.com/%zz"));
        assert!(!is_http_url("https://example.com/<script>"));
    }
}

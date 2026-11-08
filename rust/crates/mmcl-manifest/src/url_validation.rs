// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `UrlValidation.sanitizedHttpUrl`: the only links author-supplied manifest text may open.

/// Characters Java's `URI.create` refuses anywhere in a URI.
fn is_illegal_uri_char(c: char) -> bool {
    c.is_control()
        || c.is_whitespace()
        || matches!(c, '"' | '<' | '>' | '\\' | '^' | '`' | '{' | '|' | '}')
}

/// Every `%` is followed by two hex digits.
fn escapes_are_well_formed(s: &str) -> bool {
    let b = s.as_bytes();
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            if i + 2 >= b.len() {
                return false;
            }
            if !(b[i + 1].is_ascii_hexdigit() && b[i + 2].is_ascii_hexdigit()) {
                return false;
            }
            i += 3;
        } else {
            i += 1;
        }
    }
    true
}

/// The trimmed URL when it parses as a URI with an `http` or `https` scheme
/// (case-insensitive), else `None`. The text is returned as written, not normalised.
pub fn sanitized_http_url(raw: Option<&str>) -> Option<String> {
    let t = crate::non_blank(raw)?;
    let (scheme, _) = t.split_once(':')?;
    if !(scheme.eq_ignore_ascii_case("http") || scheme.eq_ignore_ascii_case("https")) {
        return None;
    }
    if t.chars().any(is_illegal_uri_char) || !escapes_are_well_formed(t) {
        return None;
    }
    url::Url::parse(t).ok()?;
    Some(t.to_string())
}

/// True when [`sanitized_http_url`] accepts the value.
pub fn is_http_url(raw: Option<&str>) -> bool {
    sanitized_http_url(raw).is_some()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_http_and_https() {
        assert_eq!(
            sanitized_http_url(Some("https://example.com/page")).as_deref(),
            Some("https://example.com/page")
        );
        assert!(is_http_url(Some("http://example.com")));
    }

    #[test]
    fn scheme_match_is_case_insensitive() {
        assert!(is_http_url(Some("HTTPS://example.com")));
        assert!(is_http_url(Some("HtTp://example.com")));
    }

    #[test]
    fn trims_surrounding_whitespace() {
        assert_eq!(
            sanitized_http_url(Some("   https://example.com  ")).as_deref(),
            Some("https://example.com")
        );
    }

    #[test]
    fn rejects_other_schemes() {
        assert!(!is_http_url(Some("file:///etc/passwd")));
        assert!(!is_http_url(Some("javascript:alert(1)")));
        assert!(!is_http_url(Some("mmcl://play?name=x")));
    }

    #[test]
    fn rejects_schemeless() {
        assert!(!is_http_url(Some("example.com/page")));
        assert!(!is_http_url(Some("//example.com/page")));
    }

    #[test]
    fn rejects_null_and_blank() {
        assert!(!is_http_url(None));
        assert!(!is_http_url(Some("")));
        assert!(!is_http_url(Some("   ")));
    }

    #[test]
    fn rejects_malformed() {
        assert!(!is_http_url(Some("https://exa mple.com/page with space")));
        assert!(!is_http_url(Some("https://example.com/%zz")));
        assert!(!is_http_url(Some("https://example.com/%4")));
        assert!(!is_http_url(Some("https://")));
        assert!(is_http_url(Some("https://example.com/a%20b")));
    }
}

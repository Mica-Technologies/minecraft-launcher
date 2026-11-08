// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Admission control (Java `McpHttpGuard`), decided before a body byte is read.
//!
//! Absolute checks come first (loopback peer, Origin), then authentication, so nothing past
//! the token check can probe an unauthenticated server. A rejected caller learns the status
//! and nothing more.

use std::net::IpAddr;

use subtle::ConstantTimeEq;

/// The largest accepted body (exactly 1 MiB is fine).
pub const MAX_BODY_BYTES: u64 = 1024 * 1024;
/// How much of an oversized body is read and discarded so the client sees the 413.
pub const DRAIN_LIMIT: u64 = 4 * 1024 * 1024;

/// What one request looks like to the guard.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct RequestMeta {
    pub method: String,
    pub authorization: Option<String>,
    pub origin: Option<String>,
    pub content_type: Option<String>,
    /// `-1` when absent or unparsable.
    pub content_length: i64,
    pub remote_is_loopback: bool,
}

/// The guard's answer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict {
    Allow,
    Forbidden,
    Unauthorized,
    MethodNotAllowed,
    PayloadTooLarge,
    UnsupportedMediaType,
}

impl Verdict {
    /// The HTTP status of a rejection; 200 for [`Verdict::Allow`].
    pub fn status(self) -> u16 {
        match self {
            Verdict::Allow => 200,
            Verdict::Forbidden => 403,
            Verdict::Unauthorized => 401,
            Verdict::MethodNotAllowed => 405,
            Verdict::PayloadTooLarge => 413,
            Verdict::UnsupportedMediaType => 415,
        }
    }
}

/// Parses a `Content-Length` header: `-1` when absent or not a non-negative integer.
pub fn parse_content_length(v: Option<&str>) -> i64 {
    v.and_then(|s| s.trim().parse::<i64>().ok())
        .filter(|n| *n >= 0)
        .unwrap_or(-1)
}

/// The admission table.
pub fn admit(req: &RequestMeta, expected_token: &str) -> Verdict {
    if !req.remote_is_loopback {
        return Verdict::Forbidden;
    }
    if !is_allowed_origin(req.origin.as_deref()) {
        return Verdict::Forbidden;
    }
    if !tokens_match(
        Some(expected_token),
        bearer_token_of(req.authorization.as_deref()).as_deref(),
    ) {
        return Verdict::Unauthorized;
    }
    if req.method.eq_ignore_ascii_case("DELETE") {
        return Verdict::Allow;
    }
    if !req.method.eq_ignore_ascii_case("POST") {
        return Verdict::MethodNotAllowed;
    }
    if req.content_length > MAX_BODY_BYTES as i64 {
        return Verdict::PayloadTooLarge;
    }
    if !is_json_content_type(req.content_type.as_deref()) {
        return Verdict::UnsupportedMediaType;
    }
    Verdict::Allow
}

/// No Origin (non-browser clients) or an http(s) origin on `localhost`, `127.0.0.1` or `[::1]`.
pub fn is_allowed_origin(origin: Option<&str>) -> bool {
    let Some(o) = origin.map(|o| o.trim().to_lowercase()) else {
        return true;
    };
    if o.is_empty() {
        return true;
    }
    if o == "null" {
        return false;
    }
    let rest = if let Some(r) = o.strip_prefix("http://") {
        r
    } else if let Some(r) = o.strip_prefix("https://") {
        r
    } else {
        return false;
    };
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    let host = if authority.starts_with('[') {
        match authority.find(']') {
            Some(end) => &authority[..=end],
            None => authority,
        }
    } else {
        authority.split(':').next().unwrap_or("")
    };
    matches!(host, "localhost" | "127.0.0.1" | "[::1]" | "::1")
}

/// The token of an `Authorization: Bearer <token>` header (case-insensitive scheme).
pub fn bearer_token_of(header: Option<&str>) -> Option<String> {
    let h = header?.trim();
    if h.len() < 7 || !h.is_char_boundary(7) || !h[..7].eq_ignore_ascii_case("bearer ") {
        return None;
    }
    let t = h[7..].trim();
    if t.is_empty() {
        None
    } else {
        Some(t.to_string())
    }
}

/// Constant-time in the content (a length difference is distinguishable, as in Java).
pub fn tokens_match(expected: Option<&str>, presented: Option<&str>) -> bool {
    match (expected, presented) {
        (Some(e), Some(p)) if !e.is_empty() => e.as_bytes().ct_eq(p.as_bytes()).into(),
        _ => false,
    }
}

/// `application/json`, with or without parameters, any case.
pub fn is_json_content_type(ct: Option<&str>) -> bool {
    let Some(ct) = ct else {
        return false;
    };
    let lower = ct.trim().to_lowercase();
    let base = lower.split(';').next().unwrap_or("").trim();
    base == "application/json"
}

/// Loopback, counting IPv4-mapped IPv6 addresses.
pub fn is_loopback(addr: Option<IpAddr>) -> bool {
    addr.is_some_and(|a| a.to_canonical().is_loopback())
}

#[cfg(test)]
mod tests {
    //! Port of `McpHttpGuardTest`.
    use super::*;
    use std::net::{Ipv4Addr, Ipv6Addr};

    const TOKEN: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    fn ok() -> RequestMeta {
        RequestMeta {
            method: "POST".into(),
            authorization: Some(format!("Bearer {TOKEN}")),
            origin: None,
            content_type: Some("application/json".into()),
            content_length: 10,
            remote_is_loopback: true,
        }
    }

    #[test]
    fn a_good_request_is_admitted() {
        assert_eq!(admit(&ok(), TOKEN), Verdict::Allow);
    }

    #[test]
    fn a_remote_peer_is_forbidden_first() {
        let mut r = ok();
        r.remote_is_loopback = false;
        r.authorization = None;
        assert_eq!(admit(&r, TOKEN), Verdict::Forbidden);
    }

    #[test]
    fn a_foreign_origin_is_forbidden_even_with_a_valid_token() {
        let mut r = ok();
        r.origin = Some("http://evil.example".into());
        assert_eq!(admit(&r, TOKEN), Verdict::Forbidden);
    }

    #[test]
    fn unauthenticated_is_rejected_before_any_other_check() {
        let mut r = ok();
        r.authorization = None;
        r.method = "GET".into();
        r.content_type = Some("text/plain".into());
        r.content_length = 1 << 30;
        assert_eq!(admit(&r, TOKEN), Verdict::Unauthorized);
        r.authorization = Some("Bearer wrong".into());
        assert_eq!(admit(&r, TOKEN), Verdict::Unauthorized);
        r.authorization = Some(format!("Basic {TOKEN}"));
        assert_eq!(admit(&r, TOKEN), Verdict::Unauthorized);
    }

    #[test]
    fn an_empty_expected_token_admits_no_one() {
        let mut r = ok();
        r.authorization = Some("Bearer ".into());
        assert_eq!(admit(&r, ""), Verdict::Unauthorized);
        r.authorization = Some("Bearer x".into());
        assert_eq!(admit(&r, ""), Verdict::Unauthorized);
    }

    #[test]
    fn an_authenticated_delete_is_admitted_without_body_checks() {
        let mut r = ok();
        r.method = "delete".into();
        r.content_type = None;
        r.content_length = 1 << 30;
        assert_eq!(admit(&r, TOKEN), Verdict::Allow);
    }

    #[test]
    fn other_methods_are_not_allowed() {
        for m in ["GET", "PUT", "OPTIONS", "PATCH"] {
            let mut r = ok();
            r.method = m.into();
            assert_eq!(admit(&r, TOKEN), Verdict::MethodNotAllowed, "{m}");
        }
        let mut r = ok();
        r.method = "post".into();
        assert_eq!(admit(&r, TOKEN), Verdict::Allow);
    }

    #[test]
    fn size_limit() {
        let mut r = ok();
        r.content_length = MAX_BODY_BYTES as i64;
        assert_eq!(admit(&r, TOKEN), Verdict::Allow);
        r.content_length = MAX_BODY_BYTES as i64 + 1;
        assert_eq!(admit(&r, TOKEN), Verdict::PayloadTooLarge);
        r.content_length = -1;
        assert_eq!(admit(&r, TOKEN), Verdict::Allow, "unknown length passes");
    }

    #[test]
    fn media_type() {
        let mut r = ok();
        r.content_type = Some("text/plain".into());
        assert_eq!(admit(&r, TOKEN), Verdict::UnsupportedMediaType);
        r.content_type = None;
        assert_eq!(admit(&r, TOKEN), Verdict::UnsupportedMediaType);
    }

    #[test]
    fn origin_vectors() {
        for o in [
            "http://localhost:3000",
            "http://127.0.0.1:8080",
            "http://localhost",
            "https://127.0.0.1:443",
            "HTTP://LOCALHOST:3000",
            "http://[::1]:3000",
            "http://[::1]",
            "",
            "   ",
        ] {
            assert!(is_allowed_origin(Some(o)), "{o}");
        }
        assert!(is_allowed_origin(None));
        for o in [
            "http://evil.example",
            "https://rebind.attacker.test:8080",
            "null",
            "NULL",
            "http://localhost.evil.example",
            "http://127.0.0.1.evil.example",
            "http://notlocalhost",
            "http://evil.example/?x=localhost",
            "file://localhost",
            "chrome-extension://abcdef",
            "localhost:3000",
        ] {
            assert!(!is_allowed_origin(Some(o)), "{o}");
        }
    }

    #[test]
    fn bearer_vectors() {
        assert_eq!(bearer_token_of(Some("Bearer X")).as_deref(), Some("X"));
        assert_eq!(bearer_token_of(Some("bearer X")).as_deref(), Some("X"));
        assert_eq!(
            bearer_token_of(Some("  Bearer   X  ")).as_deref(),
            Some("X")
        );
        for h in [
            None,
            Some(""),
            Some("Basic abc"),
            Some("Bearer"),
            Some("Bearer "),
            Some("X"),
            Some("Bearerü"),
        ] {
            assert_eq!(bearer_token_of(h), None, "{h:?}");
        }
    }

    #[test]
    fn token_compare() {
        assert!(tokens_match(Some("abc"), Some("abc")));
        assert!(!tokens_match(Some("abc"), Some("abd")));
        assert!(!tokens_match(Some("abc"), Some("abcd")));
        assert!(!tokens_match(Some(""), Some("")));
        assert!(!tokens_match(None, Some("a")));
        assert!(!tokens_match(Some("a"), None));
    }

    #[test]
    fn content_type_vectors() {
        for ct in [
            "application/json",
            "application/json; charset=utf-8",
            "  APPLICATION/JSON  ",
        ] {
            assert!(is_json_content_type(Some(ct)), "{ct}");
        }
        for ct in [
            None,
            Some("text/plain"),
            Some("application/x-www-form-urlencoded"),
            Some("application/jsonx"),
            Some("multipart/form-data"),
        ] {
            assert!(!is_json_content_type(ct), "{ct:?}");
        }
    }

    #[test]
    fn loopback_vectors() {
        assert!(is_loopback(Some(IpAddr::V4(Ipv4Addr::LOCALHOST))));
        assert!(is_loopback(Some(IpAddr::V6(Ipv6Addr::LOCALHOST))));
        assert!(is_loopback(Some(IpAddr::V6(
            Ipv4Addr::LOCALHOST.to_ipv6_mapped()
        ))));
        assert!(!is_loopback(Some(IpAddr::V4(Ipv4Addr::new(
            192, 168, 1, 2
        )))));
        assert!(!is_loopback(None));
    }

    #[test]
    fn content_length_parse() {
        assert_eq!(parse_content_length(Some(" 12 ")), 12);
        assert_eq!(parse_content_length(Some("x")), -1);
        assert_eq!(parse_content_length(Some("-3")), -1);
        assert_eq!(parse_content_length(None), -1);
    }
}

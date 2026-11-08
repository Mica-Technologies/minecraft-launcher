// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! A strict, non-normalising URI parser with the grammar and acceptance rules of Java's
//! `java.net.URI.create` (RFC 2396 plus the RFC 2732 IPv6 literal).
//!
//! The launcher's security gates (`UrlValidation`, the deep-link handler, the install-URL
//! classifier) were written against `URI.create`: what it rejects, the gate rejects, and a
//! registry-style authority (for example a host containing `_`) leaves the host `None`.
//! `url::Url` normalises and is far more permissive, so it cannot stand in for these checks.
//!
//! Every component is returned as a raw slice of the input; nothing is decoded or rewritten.

use std::net::Ipv6Addr;

/// Why [`parse`] refused its input, with the byte index where Java would report it.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("{reason} at index {index}")]
pub struct UriSyntaxError {
    /// Byte index into the input.
    pub index: usize,
    /// English reason, close to Java's `URISyntaxException` text (diagnostic only).
    pub reason: &'static str,
}

/// A parsed URI. All fields borrow from the input.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JavaUri<'a> {
    /// The scheme, as written (not lowercased).
    pub scheme: Option<&'a str>,
    /// `true` for an opaque URI such as `mailto:a@b` (scheme present, no leading `/`).
    pub opaque: bool,
    /// The raw authority (server-based or registry-based), when `//` was present and
    /// followed by a non-empty authority.
    pub authority: Option<&'a str>,
    /// User information of a server-based authority.
    pub user_info: Option<&'a str>,
    /// Host of a server-based authority (`None` for registry-based ones, as in Java). An IPv6
    /// literal keeps its brackets.
    pub host: Option<&'a str>,
    /// Port of a server-based authority, if present and non-empty.
    pub port: Option<u16>,
    /// The raw path (empty for opaque URIs).
    pub path: &'a str,
    /// The raw query, without the `?`.
    pub query: Option<&'a str>,
    /// The raw fragment, without the `#`.
    pub fragment: Option<&'a str>,
    /// The raw scheme-specific part (between the scheme's `:` and the fragment).
    pub scheme_specific_part: &'a str,
}

impl JavaUri<'_> {
    /// The host when present and not blank.
    pub fn non_blank_host(&self) -> Option<&str> {
        self.host.filter(|h| !h.trim().is_empty())
    }
}

/// Parses `input` with `java.net.URI.create` semantics.
pub fn parse(input: &str) -> Result<JavaUri<'_>, UriSyntaxError> {
    Parser::new(input).parse()
}

// ---- character classes (RFC 2396 as implemented by java.net.URI) -----------------------

#[derive(Clone, Copy)]
struct Class {
    ascii: fn(u8) -> bool,
    /// Escapes (`%XX`) and "other" (non-ASCII, non-space, non-control) characters allowed.
    escaped: bool,
}

fn is_alpha(b: u8) -> bool {
    b.is_ascii_alphabetic()
}
fn is_digit(b: u8) -> bool {
    b.is_ascii_digit()
}
fn is_alphanum(b: u8) -> bool {
    b.is_ascii_alphanumeric()
}
fn is_mark(b: u8) -> bool {
    matches!(
        b,
        b'-' | b'_' | b'.' | b'!' | b'~' | b'*' | b'\'' | b'(' | b')'
    )
}
fn is_unreserved(b: u8) -> bool {
    is_alphanum(b) || is_mark(b)
}
fn is_reserved(b: u8) -> bool {
    matches!(
        b,
        b';' | b'/' | b'?' | b':' | b'@' | b'&' | b'=' | b'+' | b'$' | b',' | b'[' | b']'
    )
}
fn is_uric(b: u8) -> bool {
    is_reserved(b) || is_unreserved(b)
}
fn is_pchar(b: u8) -> bool {
    is_unreserved(b) || matches!(b, b':' | b'@' | b'&' | b'=' | b'+' | b'$' | b',')
}
fn is_path(b: u8) -> bool {
    is_pchar(b) || b == b';' || b == b'/'
}
fn is_userinfo(b: u8) -> bool {
    is_unreserved(b) || matches!(b, b';' | b':' | b'&' | b'=' | b'+' | b'$' | b',')
}
fn is_reg_name(b: u8) -> bool {
    is_unreserved(b) || matches!(b, b'$' | b',' | b';' | b':' | b'@' | b'&' | b'=' | b'+')
}
fn is_server(b: u8) -> bool {
    is_userinfo(b) || is_alphanum(b) || matches!(b, b'-' | b'.' | b':' | b'@' | b'[' | b']')
}
fn is_scheme(b: u8) -> bool {
    is_alphanum(b) || matches!(b, b'+' | b'-' | b'.')
}
fn is_scope_id(b: u8) -> bool {
    is_alphanum(b) || matches!(b, b'_' | b'.')
}
fn is_alphanum_dash(b: u8) -> bool {
    is_alphanum(b) || b == b'-'
}

const URIC: Class = Class {
    ascii: is_uric,
    escaped: true,
};
const PATH: Class = Class {
    ascii: is_path,
    escaped: true,
};
const USERINFO: Class = Class {
    ascii: is_userinfo,
    escaped: true,
};
const REG_NAME: Class = Class {
    ascii: is_reg_name,
    escaped: true,
};
const SERVER: Class = Class {
    ascii: is_server,
    escaped: true,
};
const SCHEME: Class = Class {
    ascii: is_scheme,
    escaped: false,
};
const DIGIT: Class = Class {
    ascii: is_digit,
    escaped: false,
};
const ALPHANUM: Class = Class {
    ascii: is_alphanum,
    escaped: false,
};
const ALPHANUM_DASH: Class = Class {
    ascii: is_alphanum_dash,
    escaped: false,
};
const SCOPE_ID: Class = Class {
    ascii: is_scope_id,
    escaped: false,
};

/// Java's "other" category: non-ASCII, not a space character, not an ISO control.
fn is_other(c: char) -> bool {
    !c.is_ascii() && !c.is_control() && !c.is_whitespace()
}

struct Parser<'a> {
    s: &'a str,
    b: &'a [u8],
}

type PResult<T> = Result<T, UriSyntaxError>;

fn fail<T>(index: usize, reason: &'static str) -> PResult<T> {
    Err(UriSyntaxError { index, reason })
}

impl<'a> Parser<'a> {
    fn new(s: &'a str) -> Self {
        Parser { s, b: s.as_bytes() }
    }

    fn at(&self, p: usize, end: usize, c: u8) -> bool {
        p < end && self.b[p] == c
    }

    fn char_at(&self, p: usize) -> char {
        self.s[p..].chars().next().unwrap_or('\0')
    }

    /// Index of the first char in `stop` (or `end`); `None` when a char in `err` comes first.
    fn scan_for(&self, start: usize, end: usize, err: &[u8], stop: &[u8]) -> Option<usize> {
        let mut p = start;
        while p < end {
            let c = self.b[p];
            if err.contains(&c) {
                return None;
            }
            if stop.contains(&c) {
                break;
            }
            p += 1;
        }
        Some(p)
    }

    /// Scans while chars belong to `class`; returns the first index that does not.
    /// A malformed escape where escapes are allowed is an error (as in Java's `scanEscape`).
    fn scan_class(&self, start: usize, end: usize, class: Class) -> PResult<usize> {
        let mut p = start;
        while p < end {
            let c = self.b[p];
            if c.is_ascii() {
                if (class.ascii)(c) {
                    p += 1;
                    continue;
                }
                if class.escaped && c == b'%' {
                    if p + 3 <= end
                        && self.b[p + 1].is_ascii_hexdigit()
                        && self.b[p + 2].is_ascii_hexdigit()
                    {
                        p += 3;
                        continue;
                    }
                    return fail(p, "Malformed escape pair");
                }
                break;
            }
            let ch = self.char_at(p);
            if class.escaped && is_other(ch) {
                p += ch.len_utf8();
                continue;
            }
            break;
        }
        Ok(p)
    }

    fn check_chars(
        &self,
        start: usize,
        end: usize,
        class: Class,
        what: &'static str,
    ) -> PResult<()> {
        let p = self.scan_class(start, end, class)?;
        if p < end {
            return fail(p, what);
        }
        Ok(())
    }

    fn parse(self) -> PResult<JavaUri<'a>> {
        let n = self.b.len();
        let mut uri = JavaUri {
            scheme: None,
            opaque: false,
            authority: None,
            user_info: None,
            host: None,
            port: None,
            path: "",
            query: None,
            fragment: None,
            scheme_specific_part: "",
        };
        let colon = self.scan_for(0, n, b"/?#", b":");
        let mut p;
        let ssp_start;
        match colon {
            Some(c) if self.at(c, n, b':') => {
                if c == 0 {
                    return fail(0, "Expected scheme name");
                }
                if !is_alpha(self.b[0]) {
                    return fail(0, "Illegal character in scheme name");
                }
                self.check_chars(1, c, SCHEME, "Illegal character in scheme name")?;
                uri.scheme = Some(&self.s[..c]);
                p = c + 1;
                ssp_start = p;
                if self.at(p, n, b'/') {
                    p = self.parse_hierarchical(p, n, &mut uri)?;
                } else {
                    let q = self.scan_for(p, n, b"", b"#").unwrap_or(n);
                    if q <= p {
                        return fail(p, "Expected scheme-specific part");
                    }
                    self.check_chars(p, q, URIC, "Illegal character in opaque part")?;
                    uri.opaque = true;
                    p = q;
                }
            }
            _ => {
                ssp_start = 0;
                p = self.parse_hierarchical(0, n, &mut uri)?;
            }
        }
        uri.scheme_specific_part = &self.s[ssp_start..p];
        if self.at(p, n, b'#') {
            self.check_chars(p + 1, n, URIC, "Illegal character in fragment")?;
            uri.fragment = Some(&self.s[p + 1..]);
            p = n;
        }
        if p < n {
            return fail(p, "Expected end of URI");
        }
        Ok(uri)
    }

    fn parse_hierarchical(&self, start: usize, n: usize, uri: &mut JavaUri<'a>) -> PResult<usize> {
        let mut p = start;
        if self.at(p, n, b'/') && self.at(p + 1, n, b'/') {
            p += 2;
            let q = self.scan_for(p, n, b"", b"/?#").unwrap_or(n);
            if q > p {
                p = self.parse_authority(p, q, uri)?;
            } else if q >= n {
                return fail(p, "Expected authority");
            }
        }
        let q = self.scan_for(p, n, b"", b"?#").unwrap_or(n);
        self.check_chars(p, q, PATH, "Illegal character in path")?;
        uri.path = &self.s[p..q];
        p = q;
        if self.at(p, n, b'?') {
            p += 1;
            let q = self.scan_for(p, n, b"", b"#").unwrap_or(n);
            self.check_chars(p, q, URIC, "Illegal character in query")?;
            uri.query = Some(&self.s[p..q]);
            p = q;
        }
        Ok(p)
    }

    fn parse_authority(&self, start: usize, n: usize, uri: &mut JavaUri<'a>) -> PResult<usize> {
        let server_chars = self.scan_class(start, n, SERVER)? == n;
        let reg_chars = self.scan_class(start, n, REG_NAME)? == n;
        if reg_chars && !server_chars {
            uri.authority = Some(&self.s[start..n]);
            return Ok(n);
        }
        let mut q = start;
        let mut server_err = None;
        if server_chars {
            let mut server = ServerParts::default();
            match self.parse_server(start, n, &mut server) {
                Ok(end) if end >= n => {
                    uri.authority = Some(&self.s[start..n]);
                    uri.user_info = server.user_info;
                    uri.host = server.host;
                    uri.port = server.port;
                    q = n;
                }
                Ok(end) => {
                    server_err = Some(UriSyntaxError {
                        index: end,
                        reason: "Expected end of authority",
                    });
                }
                Err(e) => server_err = Some(e),
            }
        }
        if q < n {
            if reg_chars {
                uri.authority = Some(&self.s[start..n]);
            } else if let Some(e) = server_err {
                return Err(e);
            } else {
                return fail(q, "Illegal character in authority");
            }
        }
        Ok(n)
    }

    fn parse_server(&self, start: usize, n: usize, out: &mut ServerParts<'a>) -> PResult<usize> {
        let mut p = start;
        if let Some(q) = self.scan_for(p, n, b"/?#", b"@")
            && self.at(q, n, b'@')
        {
            self.check_chars(p, q, USERINFO, "Illegal character in user info")?;
            out.user_info = Some(&self.s[p..q]);
            p = q + 1;
        }
        if self.at(p, n, b'[') {
            let open = p;
            p += 1;
            let q = self.scan_for(p, n, b"/?#", b"]");
            match q {
                Some(q) if q > p && self.at(q, n, b']') => {
                    let zone = self.scan_for(p, q, b"", b"%").unwrap_or(q);
                    let addr = &self.s[p..zone];
                    if addr.parse::<Ipv6Addr>().is_err() {
                        return fail(p, "Malformed IPv6 address");
                    }
                    if zone < q {
                        if zone + 1 >= q {
                            return fail(zone, "Expected scope id");
                        }
                        self.check_chars(zone + 1, q, SCOPE_ID, "Illegal character in scope id")?;
                    }
                    out.host = Some(&self.s[open..=q]);
                    p = q + 1;
                }
                _ => return fail(p, "Expected closing bracket for IPv6 address"),
            }
        } else {
            let q = self.parse_ipv4(p, n);
            p = match q {
                Some(q) => {
                    out.host = Some(&self.s[p..q]);
                    q
                }
                None => {
                    let q = self.parse_hostname(p, n)?;
                    out.host = Some(&self.s[p..q]);
                    q
                }
            };
        }
        if self.at(p, n, b':') {
            p += 1;
            let q = self.scan_class(p, n, DIGIT)?;
            if q > p {
                match self.s[p..q].parse::<u32>() {
                    Ok(port) if port <= u32::from(u16::MAX) => out.port = Some(port as u16),
                    // Java accepts any int port; values above 65535 are still syntactically valid
                    // there, but no caller here can use them, so they read as "no port".
                    Ok(_) => out.port = None,
                    Err(_) => return fail(p, "Malformed port number"),
                }
                p = q;
            }
        }
        if p < n {
            return fail(p, "Expected port number");
        }
        Ok(p)
    }

    /// A dotted-quad IPv4 address followed by the end or `:`; `None` otherwise.
    fn parse_ipv4(&self, start: usize, n: usize) -> Option<usize> {
        let mut p = start;
        for i in 0..4 {
            if i > 0 {
                if !self.at(p, n, b'.') {
                    return None;
                }
                p += 1;
            }
            let q = self.scan_class(p, n, DIGIT).ok()?;
            if q <= p || q - p > 3 {
                return None;
            }
            let v: u32 = self.s[p..q].parse().ok()?;
            if v > 255 {
                return None;
            }
            p = q;
        }
        if p < n && self.b[p] != b':' {
            return None;
        }
        Some(p)
    }

    fn parse_hostname(&self, start: usize, n: usize) -> PResult<usize> {
        let mut p = start;
        let mut last_label: Option<usize> = None;
        loop {
            let q = self.scan_class(p, n, ALPHANUM)?;
            if q <= p {
                break;
            }
            last_label = Some(p);
            p = q;
            let q = self.scan_class(p, n, ALPHANUM_DASH)?;
            if q > p {
                if self.b[q - 1] == b'-' {
                    return fail(q - 1, "Illegal character in hostname");
                }
                p = q;
            }
            if !self.at(p, n, b'.') {
                break;
            }
            p += 1;
            if p >= n {
                break;
            }
        }
        if p < n && !self.at(p, n, b':') {
            return fail(p, "Illegal character in hostname");
        }
        let Some(l) = last_label else {
            return fail(start, "Expected hostname");
        };
        // A fully qualified name's rightmost label must start with a letter.
        if l > start && !is_alpha(self.b[l]) {
            return fail(l, "Illegal character in hostname");
        }
        Ok(p)
    }
}

#[derive(Default)]
struct ServerParts<'a> {
    user_info: Option<&'a str>,
    host: Option<&'a str>,
    port: Option<u16>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_plain_https_url() {
        let u = parse("https://example.com:8443/a/b?x=1&y=2#frag").unwrap();
        assert_eq!(u.scheme, Some("https"));
        assert_eq!(u.host, Some("example.com"));
        assert_eq!(u.port, Some(8443));
        assert_eq!(u.path, "/a/b");
        assert_eq!(u.query, Some("x=1&y=2"));
        assert_eq!(u.fragment, Some("frag"));
        assert!(!u.opaque);
    }

    #[test]
    fn rejects_what_java_rejects() {
        for bad in [
            "https://exa mple.com/",
            "https://example.com/a b",
            "https://example.com/<x>",
            "https://example.com/%zz",
            "https://example.com/%4",
            "https://example.com/a\\b",
            "https://example.com/{x}",
            "https://example.com/a^b",
            "https://example.com/a|b",
            "https://example.com/`",
            "https://example.com/\"",
            "https://",
            ":nope",
            "1abc:x",
            "mmcl:",
            "https://example.com/#a#b",
        ] {
            assert!(parse(bad).is_err(), "{bad} should be rejected");
        }
    }

    #[test]
    fn registry_authority_has_no_host() {
        let u = parse("https://under_score.example.com/x").unwrap();
        assert_eq!(u.host, None);
        assert_eq!(u.authority, Some("under_score.example.com"));
        let u = parse("https://999.1.1.1/x").unwrap();
        assert_eq!(u.host, None, "rightmost label must start with a letter");
        let u = parse("https://-bad.com/").unwrap();
        assert_eq!(u.host, None);
    }

    #[test]
    fn empty_authority_with_path_is_allowed() {
        let u = parse("https:///manifest.json").unwrap();
        assert_eq!(u.host, None);
        assert_eq!(u.path, "/manifest.json");
    }

    #[test]
    fn opaque_and_relative_forms() {
        let u = parse("mailto:someone@example.com").unwrap();
        assert!(u.opaque);
        assert_eq!(u.host, None);
        let u = parse("//example.com/page").unwrap();
        assert_eq!(u.scheme, None);
        assert_eq!(u.host, Some("example.com"));
        let u = parse("example.com/page").unwrap();
        assert_eq!(u.scheme, None);
        assert_eq!(u.host, None);
    }

    #[test]
    fn ip_literals_and_userinfo() {
        let u = parse("http://user:pw@127.0.0.1:80/").unwrap();
        assert_eq!(u.user_info, Some("user:pw"));
        assert_eq!(u.host, Some("127.0.0.1"));
        let u = parse("http://[::1]:8080/").unwrap();
        assert_eq!(u.host, Some("[::1]"));
        assert!(parse("http://[::g]/").is_err());
        assert!(parse("http://[::1/").is_err());
    }

    #[test]
    fn query_may_hold_brackets_and_non_ascii_is_allowed_outside_the_host() {
        assert!(parse("https://example.com/?q=[1]").is_ok());
        assert!(parse("https://example.com/caf\u{e9}").is_ok());
        assert!(parse("https://example.com/a\u{a0}b").is_err());
        assert!(parse("https://example.com/[x]").is_err());
    }

    #[test]
    fn launcher_deep_links_parse() {
        let u = parse("mmcl://add?url=https%3A%2F%2Fexample.com%2Fa%20b").unwrap();
        assert_eq!(u.host, Some("add"));
        assert_eq!(u.query, Some("url=https%3A%2F%2Fexample.com%2Fa%20b"));
        let u = parse("mmcl://add?url=https://example.com/x.json").unwrap();
        assert_eq!(u.query, Some("url=https://example.com/x.json"));
    }
}

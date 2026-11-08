// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The `mica://` URI scheme and its containment gate (Java `McpResourceUri`).
//!
//! A placeholder segment is decoded **first** and then checked with [`is_safe_segment`], so an
//! encoded separator or `..` (`%2f`, `%2e%2e`) cannot slip through. A malformed escape is left
//! raw, so the checks still see the raw text.

use indexmap::IndexMap;
use percent_encoding::{AsciiSet, NON_ALPHANUMERIC, utf8_percent_encode};

use crate::McpError;

/// The scheme every resource URI starts with.
pub const SCHEME: &str = "mica://";

/// Java `URLEncoder`'s unreserved set: letters, digits, `.`, `-`, `*`, `_`.
const ENCODE_SET: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'.')
    .remove(b'-')
    .remove(b'*')
    .remove(b'_');

/// Contains both `{` and `}`.
pub fn is_template(t: &str) -> bool {
    t.contains('{') && t.contains('}')
}

fn placeholder_name(part: &str) -> Option<&str> {
    if part.len() > 2 && part.starts_with('{') && part.ends_with('}') {
        Some(&part[1..part.len() - 1])
    } else {
        None
    }
}

/// Matches `uri` against `template`; the decoded placeholder values on success.
pub fn match_template(template: &str, uri: &str) -> Option<IndexMap<String, String>> {
    let t: Vec<&str> = template.split('/').collect();
    let u: Vec<&str> = uri.split('/').collect();
    if t.len() != u.len() {
        return None;
    }
    let mut out = IndexMap::new();
    for (tp, up) in t.iter().zip(u.iter()) {
        if let Some(name) = placeholder_name(tp) {
            let decoded = decode(up);
            if !is_safe_segment(&decoded) {
                return None;
            }
            out.insert(name.to_string(), decoded);
        } else if tp != up {
            return None;
        }
    }
    Some(out)
}

/// Java `URLDecoder.decode`: `+` → space, `%XX` → UTF-8 bytes (invalid UTF-8 → U+FFFD). A `%`
/// not followed by two hex digits leaves the whole segment raw.
pub fn decode(segment: &str) -> String {
    let b = segment.as_bytes();
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            if i + 2 >= b.len() {
                return segment.to_string();
            }
            if !(b[i + 1].is_ascii_hexdigit() && b[i + 2].is_ascii_hexdigit()) {
                return segment.to_string();
            }
            i += 3;
        } else {
            i += 1;
        }
    }
    let plus = segment.replace('+', " ");
    percent_encoding::percent_decode_str(&plus)
        .decode_utf8_lossy()
        .into_owned()
}

/// Not blank, not `.` or `..`, and no `/`, `\`, NUL or control character. A leading dot is
/// fine.
pub fn is_safe_segment(s: &str) -> bool {
    if s.trim().is_empty() || s == "." || s == ".." {
        return false;
    }
    !s.chars().any(|c| c == '/' || c == '\\' || c.is_control())
}

/// Java `URLEncoder` with `+` as `%20`, upper-case hex.
pub fn encode(value: &str) -> String {
    utf8_percent_encode(value, ENCODE_SET).to_string()
}

/// Fills every placeholder of `template` with the encoded `value`; refuses an unsafe value.
pub fn build(template: &str, value: &str) -> Result<String, McpError> {
    if !is_safe_segment(value) {
        return Err(McpError::InvalidArgument(format!(
            "Unsafe resource URI segment: {value:?}"
        )));
    }
    let enc = encode(value);
    let mut out = String::new();
    let mut rest = template;
    while let Some(start) = rest.find('{') {
        let after = &rest[start + 1..];
        match after.find('}') {
            Some(end) if end > 0 && !after[..end].contains(['/', '{']) => {
                out.push_str(&rest[..start]);
                out.push_str(&enc);
                rest = &after[end + 1..];
            }
            _ => {
                out.push_str(&rest[..=start]);
                rest = after;
            }
        }
    }
    out.push_str(rest);
    Ok(out)
}

#[cfg(test)]
mod tests {
    //! Port of `McpResourceUriTest`.
    use super::*;

    const T: &str = "mica://modpack/{friendlyName}/manifest";

    fn m(uri: &str) -> Option<String> {
        match_template(T, uri).map(|p| p["friendlyName"].clone())
    }

    #[test]
    fn matches_and_decodes() {
        assert_eq!(m("mica://modpack/Alto/manifest"), Some("Alto".into()));
        assert_eq!(
            m("mica://modpack/Fabric%201.21.5%3A%200.16.10/manifest"),
            Some("Fabric 1.21.5: 0.16.10".into())
        );
        assert_eq!(m("mica://modpack/a+b/manifest"), Some("a b".into()));
        assert_eq!(
            m("mica://modpack/%C3%A9t%C3%A9/manifest"),
            Some("été".into())
        );
    }

    #[test]
    fn structure_must_match() {
        assert_eq!(m("mica://modpack/Alto/crash-report"), None);
        assert_eq!(m("mica://modpack/Alto/manifest/extra"), None);
        assert_eq!(m("mica://modpack/a/b/manifest"), None);
        assert_eq!(m("MICA://modpack/Alto/manifest"), None);
        assert_eq!(
            match_template("mica://packs", "mica://packs"),
            Some(IndexMap::new())
        );
    }

    #[test]
    fn every_unsafe_segment_is_rejected() {
        for seg in [
            "..", ".", "", " ", "%20", "%2e%2e", "%2E%2E", "%2e", "%2f", "%2F", "a%2fb", "%5c",
            "a%5Cb", "%00", "%0a", "%0D", "%7f", "..%2f", "%c2%85",
        ] {
            assert_eq!(m(&format!("mica://modpack/{seg}/manifest")), None, "{seg}");
        }
    }

    #[test]
    fn malformed_percent_is_left_raw_and_still_checked() {
        assert_eq!(m("mica://modpack/%zz/manifest"), Some("%zz".into()));
        assert_eq!(m("mica://modpack/50%/manifest"), Some("50%".into()));
        assert_eq!(m("mica://modpack/a%2/manifest"), Some("a%2".into()));
        assert_eq!(decode("%zz%2e%2e"), "%zz%2e%2e");
        assert!(!is_safe_segment(&decode("%zz\\")));
    }

    #[test]
    fn a_leading_dot_is_safe() {
        assert!(is_safe_segment(".hidden"));
        assert_eq!(m("mica://modpack/.hidden/manifest"), Some(".hidden".into()));
    }

    #[test]
    fn encode_uses_percent_20_and_upper_hex() {
        assert_eq!(
            encode("Fabric 1.21.5: 0.16.10"),
            "Fabric%201.21.5%3A%200.16.10"
        );
        assert_eq!(encode("~"), "%7E");
        assert_eq!(encode("a.b-c*d_e"), "a.b-c*d_e");
        assert_eq!(encode("é"), "%C3%A9");
        assert_eq!(encode("+"), "%2B");
    }

    #[test]
    fn reserved_characters_round_trip() {
        for name in [
            "A+B",
            "x?y#z",
            "a&b=c",
            "100%",
            "Alto: 26.9.3",
            "ünï",
            "[]{}",
        ] {
            let uri = build(T, name).unwrap();
            assert_eq!(m(&uri), Some(name.to_string()), "{name} via {uri}");
        }
    }

    #[test]
    fn build_refuses_unsafe_values() {
        for v in ["..", "a/b", "a\\b", "", " ", "a\0"] {
            assert!(build(T, v).is_err(), "{v:?}");
        }
        assert_eq!(build(T, "Alto").unwrap(), "mica://modpack/Alto/manifest");
    }

    #[test]
    fn is_template_vectors() {
        assert!(is_template(T));
        assert!(!is_template("mica://packs"));
        assert!(!is_template("mica://{x"));
    }
}

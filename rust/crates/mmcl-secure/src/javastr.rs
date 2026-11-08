// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Small emulations of `java.lang.String` behaviour that the fingerprint depends on.

/// `String.trim()`: strips every leading and trailing char `<= U+0020`.
pub(crate) fn trim(s: &str) -> &str {
    s.trim_matches(|c: char| c <= '\u{20}')
}

/// `Character.isWhitespace(cp)`.
pub(crate) fn is_whitespace(c: char) -> bool {
    match c {
        '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r' | '\u{1C}'..='\u{1F}' => true,
        // Unicode space separators, except the three non-breaking ones.
        '\u{00A0}' | '\u{2007}' | '\u{202F}' => false,
        ' ' | '\u{1680}' | '\u{2000}'..='\u{200A}' | '\u{205F}' | '\u{3000}' => true,
        // Line and paragraph separators.
        '\u{2028}' | '\u{2029}' => true,
        _ => false,
    }
}

/// `String.isBlank()`.
pub(crate) fn is_blank(s: &str) -> bool {
    s.chars().all(is_whitespace)
}

/// `line.split(marker)[1]` for a literal marker with no regex metacharacters. Java drops trailing
/// empty pieces, so `None` stands for the `ArrayIndexOutOfBoundsException` Java would throw.
pub(crate) fn split_second<'a>(line: &'a str, marker: &str) -> Option<&'a str> {
    let mut parts: Vec<&str> = line.split(marker).collect();
    while parts.len() > 1 && parts.last() == Some(&"") {
        parts.pop();
    }
    if parts.len() == 1 && parts[0].is_empty() && !line.is_empty() {
        // Java: a line that is only the marker splits to nothing at all.
        return None;
    }
    parts.get(1).copied()
}

/// oshi `ParseUtil.getStringBetween(line, c)`: `line.substring(first + 1, last).trim()`, or `""`
/// when `c` does not occur. `None` stands for the `StringIndexOutOfBoundsException` Java throws
/// when `c` occurs exactly once.
pub(crate) fn string_between(line: &str, c: char) -> Option<String> {
    let Some(first) = line.find(c) else {
        return Some(String::new());
    };
    let last = line.rfind(c).unwrap_or(first);
    let start = first + c.len_utf8();
    if last < start {
        return None;
    }
    Some(trim(&line[start..last]).to_string())
}

/// `BufferedReader.readLine()` over a whole text: lines end at `\n`, `\r` or `\r\n`, and the
/// terminators are dropped. A trailing terminator does not start an extra empty line.
#[cfg_attr(not(unix), allow(dead_code))]
pub(crate) fn lines(text: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let bytes = text.as_bytes();
    let mut start = 0;
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'\n' => {
                out.push(&text[start..i]);
                i += 1;
                start = i;
            }
            b'\r' => {
                out.push(&text[start..i]);
                i += 1;
                if i < bytes.len() && bytes[i] == b'\n' {
                    i += 1;
                }
                start = i;
            }
            _ => i += 1,
        }
    }
    if start < bytes.len() {
        out.push(&text[start..]);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trim_strips_control_chars_but_not_unicode_spaces() {
        assert_eq!(trim("\t\u{1} abc \n"), "abc");
        assert_eq!(trim("\u{3000}abc"), "\u{3000}abc");
    }

    #[test]
    fn blank_follows_character_is_whitespace() {
        assert!(is_blank(""));
        assert!(is_blank(" \t\r\n\u{3000}"));
        assert!(!is_blank("\u{00A0}"));
        assert!(!is_blank(" x "));
    }

    #[test]
    fn split_second_mirrors_java_split() {
        assert_eq!(split_second("\tUUID: abc", "UUID:"), Some(" abc"));
        assert_eq!(split_second("UUID: a UUID: b", "UUID:"), Some(" a "));
        assert_eq!(split_second("\tUUID:", "UUID:"), None);
        assert_eq!(split_second("UUID:", "UUID:"), None);
        assert_eq!(split_second("UUID:x", "UUID:"), Some("x"));
    }

    #[test]
    fn string_between_mirrors_oshi() {
        assert_eq!(
            string_between("system.hardware.uuid = ' ab-cd '  (string)", '\'').as_deref(),
            Some("ab-cd")
        );
        assert_eq!(string_between("no quotes", '\'').as_deref(), Some(""));
        assert_eq!(string_between("one ' quote", '\''), None);
        assert_eq!(string_between("''", '\'').as_deref(), Some(""));
    }

    #[test]
    fn lines_split_like_read_line() {
        assert_eq!(lines("a\nb\r\nc\rd"), vec!["a", "b", "c", "d"]);
        assert_eq!(lines("a\n"), vec!["a"]);
        assert_eq!(lines("\n"), vec![""]);
        assert!(lines("").is_empty());
    }
}

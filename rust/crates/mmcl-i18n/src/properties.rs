// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! A port of Java's `Properties.load(Reader)` grammar.
//!
//! The logical-line reader and the key/value splitter follow `java.util.Properties.LineReader` and
//! `load0` step for step, so the Rust launcher reads the Java bundles exactly as the JVM does:
//!
//! * physical lines end at `\n`, `\r` or `\r\n`;
//! * a logical line continues when it ends in an odd number of backslashes, and the
//!   continuation's leading whitespace is skipped (a blank line ends it);
//! * lines whose first non-blank char is `#` or `!` are comments (and never continue);
//! * the key ends at the first unescaped `=`, `:` or whitespace; then whitespace, one optional
//!   `=`/`:`, and whitespace again are skipped; the rest is the value (trailing whitespace kept);
//! * escapes `\t \n \r \f \uXXXX`, and `\` before any other char yields that char;
//! * a duplicate key: the last one wins.
//!
//! Unlike Java, a malformed `\uXXXX` does not abort the load: the text is kept literally and a
//! [`Diagnostic`] is reported. `\uXXXX` escapes are decoded as UTF-16 code units, so an escaped
//! surrogate pair becomes one `char`; a lone surrogate becomes U+FFFD (and is reported).

use indexmap::IndexMap;

/// A problem found while parsing; parsing itself never fails.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Diagnostic {
    /// 1-based physical line where the logical line containing the problem starts.
    pub line: usize,
    /// What went wrong.
    pub message: String,
}

/// Parses `.properties` text into an insertion-ordered map (Java's `Properties.load`).
pub fn parse(src: &str) -> IndexMap<String, String> {
    parse_with_diagnostics(src).0
}

/// Like [`parse`], and also returns every malformed escape found.
pub fn parse_with_diagnostics(src: &str) -> (IndexMap<String, String>, Vec<Diagnostic>) {
    let src = src.strip_prefix('\u{feff}').unwrap_or(src);
    let mut map = IndexMap::new();
    let mut diags = Vec::new();
    let mut reader = LineReader::new(src);
    while let Some((line, start_line)) = reader.read_line() {
        let (key, value) = split_key_value(&line);
        let key = convert(key, start_line, &mut diags);
        let value = convert(value, start_line, &mut diags);
        map.shift_remove(&key);
        map.insert(key, value);
    }
    (map, diags)
}

/// Decodes a bundle file's bytes the way `PropertyResourceBundle` does: UTF-8, falling back to
/// ISO-8859-1 when the bytes are not valid UTF-8.
pub fn decode(bytes: &[u8]) -> std::borrow::Cow<'_, str> {
    match std::str::from_utf8(bytes) {
        Ok(s) => std::borrow::Cow::Borrowed(s),
        Err(_) => std::borrow::Cow::Owned(bytes.iter().map(|&b| b as char).collect()),
    }
}

fn is_blank(c: char) -> bool {
    c == ' ' || c == '\t' || c == '\u{c}'
}

/// Java's `Properties.LineReader`: yields logical lines (with the continuation backslashes and
/// the continuation lines' leading whitespace removed, but escapes still raw).
struct LineReader<'a> {
    chars: std::iter::Peekable<std::str::Chars<'a>>,
    skip_lf: bool,
    /// 1-based physical line number of the next char.
    line_no: usize,
}

impl<'a> LineReader<'a> {
    fn new(src: &'a str) -> Self {
        Self {
            chars: src.chars().peekable(),
            skip_lf: false,
            line_no: 1,
        }
    }

    fn next_char(&mut self) -> Option<char> {
        let c = self.chars.next()?;
        if c == '\n' || (c == '\r' && self.chars.peek() != Some(&'\n')) {
            self.line_no += 1;
        }
        Some(c)
    }

    fn read_line(&mut self) -> Option<(Vec<char>, usize)> {
        let mut buf: Vec<char> = Vec::new();
        let mut skip_white = true;
        let mut appended_line_begin = false;
        let mut preceding_backslash = false;
        let mut start_line = self.line_no;
        loop {
            let Some(c) = self.next_char() else {
                // EOF
                if buf.is_empty() {
                    return None;
                }
                if preceding_backslash {
                    buf.pop();
                }
                return Some((buf, start_line));
            };
            if self.skip_lf {
                self.skip_lf = false;
                if c == '\n' {
                    continue;
                }
            }
            if skip_white {
                if is_blank(c) {
                    continue;
                }
                if !appended_line_begin && (c == '\r' || c == '\n') {
                    continue;
                }
                skip_white = false;
                appended_line_begin = false;
            }
            if buf.is_empty() {
                // Still at the start of a new logical line.
                if c == '#' || c == '!' {
                    // A comment: consume the rest of the physical line.
                    loop {
                        match self.next_char() {
                            None => return None,
                            Some('\r') => {
                                self.skip_lf = true;
                                break;
                            }
                            Some('\n') => break,
                            Some(_) => {}
                        }
                    }
                    skip_white = true;
                    continue;
                }
            }
            if c != '\n' && c != '\r' {
                if buf.is_empty() {
                    start_line = self.line_no;
                }
                buf.push(c);
                preceding_backslash = if c == '\\' {
                    !preceding_backslash
                } else {
                    false
                };
            } else {
                // End of a physical line.
                if buf.is_empty() {
                    skip_white = true;
                    continue;
                }
                if preceding_backslash {
                    // The backslash is not part of the line; continue on the next one.
                    buf.pop();
                    skip_white = true;
                    appended_line_begin = true;
                    preceding_backslash = false;
                    if c == '\r' {
                        self.skip_lf = true;
                    }
                } else {
                    if c == '\r' {
                        self.skip_lf = true;
                    }
                    return Some((buf, start_line));
                }
            }
        }
    }
}

/// Java's `load0` key/value split on one logical line. Returns raw (still escaped) slices.
fn split_key_value(line: &[char]) -> (&[char], &[char]) {
    let limit = line.len();
    let mut key_len = 0;
    let mut value_start = limit;
    let mut has_sep = false;
    let mut preceding_backslash = false;
    while key_len < limit {
        let c = line[key_len];
        if (c == '=' || c == ':') && !preceding_backslash {
            value_start = key_len + 1;
            has_sep = true;
            break;
        } else if is_blank(c) && !preceding_backslash {
            value_start = key_len + 1;
            break;
        }
        preceding_backslash = if c == '\\' {
            !preceding_backslash
        } else {
            false
        };
        key_len += 1;
    }
    while value_start < limit {
        let c = line[value_start];
        if !is_blank(c) {
            if !has_sep && (c == '=' || c == ':') {
                has_sep = true;
            } else {
                break;
            }
        }
        value_start += 1;
    }
    (&line[..key_len], &line[value_start..])
}

/// Java's `loadConvert`: resolves escapes. Works in UTF-16 code units so escaped surrogate pairs
/// recombine, then decodes.
fn convert(raw: &[char], line: usize, diags: &mut Vec<Diagnostic>) -> String {
    let mut units: Vec<u16> = Vec::with_capacity(raw.len());
    let mut i = 0;
    let mut buf = [0u16; 2];
    while i < raw.len() {
        let c = raw[i];
        i += 1;
        if c != '\\' {
            units.extend_from_slice(c.encode_utf16(&mut buf));
            continue;
        }
        let Some(&e) = raw.get(i) else {
            // A lone trailing backslash cannot survive the line reader; drop it.
            break;
        };
        i += 1;
        match e {
            'u' => {
                let hex: String = raw[i..].iter().take(4).collect();
                if hex.chars().count() == 4 && hex.chars().all(|h| h.is_ascii_hexdigit()) {
                    units.push(u16::from_str_radix(&hex, 16).unwrap_or(0xFFFD));
                    i += 4;
                } else {
                    diags.push(Diagnostic {
                        line,
                        message: format!("malformed \\uxxxx encoding: \\u{hex}"),
                    });
                    // Lenient: keep the text literally.
                    units.push(u16::from(b'\\'));
                    units.push(u16::from(b'u'));
                }
            }
            't' => units.push(u16::from(b'\t')),
            'n' => units.push(u16::from(b'\n')),
            'r' => units.push(u16::from(b'\r')),
            'f' => units.push(0x0C),
            other => units.extend_from_slice(other.encode_utf16(&mut buf)),
        }
    }
    let mut out = String::with_capacity(units.len());
    for r in char::decode_utf16(units.iter().copied()) {
        match r {
            Ok(c) => out.push(c),
            Err(e) => {
                diags.push(Diagnostic {
                    line,
                    message: format!("unpaired surrogate \\u{:04X}", e.unpaired_surrogate()),
                });
                out.push('\u{FFFD}');
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn one(src: &str) -> (String, String) {
        let m = parse(src);
        assert_eq!(m.len(), 1, "{m:?}");
        let (k, v) = m.into_iter().next().unwrap();
        (k, v)
    }

    #[test]
    fn separators() {
        assert_eq!(one("a=b"), ("a".into(), "b".into()));
        assert_eq!(one("a:b"), ("a".into(), "b".into()));
        assert_eq!(one("a b"), ("a".into(), "b".into()));
        assert_eq!(one("a = = b"), ("a".into(), "= b".into()));
        assert_eq!(one("a"), ("a".into(), "".into()));
    }

    #[test]
    fn leading_space_escape_and_trailing_space() {
        assert_eq!(one(r"k=\ (+{0} more)").1, " (+{0} more)");
        assert_eq!(one("k=   x  ").1, "x  ");
    }

    #[test]
    fn unicode_and_surrogates() {
        assert_eq!(one(r"k=café").1, "café");
        assert_eq!(one(r"k=😀").1, "😀");
        let (m, d) = parse_with_diagnostics(r"k=\uD83D x");
        assert_eq!(m["k"], "\u{FFFD} x");
        assert_eq!(d.len(), 1);
    }

    #[test]
    fn malformed_unicode_is_literal_and_reported() {
        let (m, d) = parse_with_diagnostics("a=1\nk=\\u12G4");
        assert_eq!(m["k"], "\\u12G4");
        assert_eq!(d.len(), 1);
        assert_eq!(d[0].line, 2);
    }

    #[test]
    fn continuation() {
        assert_eq!(one("k=a\\\n   b\\\r\n\tc").1, "abc");
        // an even number of backslashes does not continue
        let m = parse("k=a\\\\\nx=y");
        assert_eq!(m["k"], "a\\");
        assert_eq!(m["x"], "y");
    }

    #[test]
    fn comments_and_duplicates() {
        let m = parse("# c \\\nk=1\n ! c2\nk=2\n");
        assert_eq!(m.len(), 1);
        assert_eq!(m["k"], "2");
    }

    #[test]
    fn decode_falls_back_to_latin1() {
        assert_eq!(decode(b"caf\xe9"), "café");
        assert_eq!(decode("café".as_bytes()), "café");
    }

    #[test]
    fn bom_is_stripped() {
        assert_eq!(one("\u{feff}k=v").0, "k");
    }
}

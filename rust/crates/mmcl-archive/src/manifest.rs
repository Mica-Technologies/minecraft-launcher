// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! A small reader and writer for `META-INF/MANIFEST.MF` that follows `java.util.jar.Manifest`.
//!
//! Reading: lines end in CRLF, LF or CR; a line starting with one space continues the previous
//! one (joined as bytes, so a UTF-8 character split across lines survives); a blank line ends
//! a section; each header is `Name: Value`. Attribute names compare case-insensitively and keep
//! the spelling and position they were first seen with.
//!
//! Writing (`Manifest.write`): the main section starts with `Manifest-Version` (or, without
//! one, `Signature-Version`), then the other attributes in order, then a blank line; each
//! section after it starts with `Name:`. Lines end in CRLF and are wrapped at 72 bytes, with a
//! single-space continuation carrying at most 71 more bytes.

use std::io;

/// One section's attributes, in order. Names compare case-insensitively.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Attributes {
    entries: Vec<(String, String)>,
}

impl Attributes {
    /// The value of `name`, compared case-insensitively.
    pub fn get(&self, name: &str) -> Option<&str> {
        self.entries
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    /// Sets `name`. An existing attribute keeps its position and spelling, as a Java
    /// `LinkedHashMap` keyed by `Attributes.Name` would.
    pub fn insert(&mut self, name: &str, value: &str) {
        match self
            .entries
            .iter_mut()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
        {
            Some(slot) => slot.1 = value.to_owned(),
            None => self.entries.push((name.to_owned(), value.to_owned())),
        }
    }

    /// Removes `name` and returns its value.
    pub fn remove(&mut self, name: &str) -> Option<String> {
        let pos = self
            .entries
            .iter()
            .position(|(k, _)| k.eq_ignore_ascii_case(name))?;
        Some(self.entries.remove(pos).1)
    }

    /// The attributes in order.
    pub fn iter(&self) -> impl Iterator<Item = (&str, &str)> {
        self.entries.iter().map(|(k, v)| (k.as_str(), v.as_str()))
    }

    /// Number of attributes.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// `true` when there are none.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

/// A parsed manifest: the main attributes and the per-entry sections, in order.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Manifest {
    /// The main section.
    pub main: Attributes,
    /// Per-entry sections, keyed by their `Name:` value.
    pub entries: Vec<(String, Attributes)>,
}

const MANIFEST_VERSION: &str = "Manifest-Version";
const SIGNATURE_VERSION: &str = "Signature-Version";

impl Manifest {
    /// Parses manifest bytes. An unterminated last line is accepted (Java rejects it).
    pub fn parse(bytes: &[u8]) -> io::Result<Manifest> {
        let mut manifest = Manifest::default();
        // Physical lines joined with their continuations into logical lines; `None` marks a
        // section break.
        let mut logical: Vec<Option<Vec<u8>>> = Vec::new();
        for line in split_lines(bytes) {
            if line.is_empty() {
                logical.push(None);
            } else if line[0] == b' ' {
                match logical.last_mut() {
                    Some(Some(prev)) => prev.extend_from_slice(&line[1..]),
                    _ => return Err(invalid("misplaced continuation line")),
                }
            } else {
                logical.push(Some(line.to_vec()));
            }
        }

        let mut current: Option<(String, Attributes)> = None;
        let mut in_main = true;
        for item in logical {
            let Some(line) = item else {
                if in_main {
                    in_main = false;
                } else if let Some(section) = current.take() {
                    manifest.entries.push(section);
                }
                continue;
            };
            let text = String::from_utf8(line).map_err(|_| invalid("manifest is not UTF-8"))?;
            let (name, value) = split_header(&text)?;
            if in_main {
                manifest.main.insert(name, value);
            } else {
                match current.as_mut() {
                    Some((_, attrs)) => attrs.insert(name, value),
                    None => {
                        if !name.eq_ignore_ascii_case("Name") {
                            return Err(invalid("section does not start with Name"));
                        }
                        current = Some((value.to_owned(), Attributes::default()));
                    }
                }
            }
        }
        if let Some(section) = current.take() {
            manifest.entries.push(section);
        }
        Ok(manifest)
    }

    /// Serialises like `Manifest.write`.
    ///
    /// Java quirk kept on purpose: when the main section has neither `Manifest-Version` nor
    /// `Signature-Version`, none of its attributes are written.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::new();
        write_section_main(&self.main, &mut out);
        for (name, attrs) in &self.entries {
            println72(&mut out, &format!("Name: {name}"));
            for (k, v) in attrs.iter() {
                println72(&mut out, &format!("{k}: {v}"));
            }
            out.extend_from_slice(b"\r\n");
        }
        out
    }
}

fn write_section_main(main: &Attributes, out: &mut Vec<u8>) {
    let version = [MANIFEST_VERSION, SIGNATURE_VERSION]
        .into_iter()
        .find_map(|n| main.get(n).map(|v| (n, v)));
    if let Some((vername, value)) = version {
        println72(out, &format!("{vername}: {value}"));
        for (k, v) in main.iter() {
            if !k.eq_ignore_ascii_case(vername) {
                println72(out, &format!("{k}: {v}"));
            }
        }
    }
    out.extend_from_slice(b"\r\n");
}

/// Writes `line` wrapped at 72 bytes, as Java's `Manifest.println72` does: the first line
/// holds 72 bytes, each continuation a space plus at most 71 bytes. Splits fall on byte
/// boundaries (the reader joins bytes before decoding).
pub(crate) fn println72(out: &mut Vec<u8>, line: &str) {
    let bytes = line.as_bytes();
    if !bytes.is_empty() {
        out.push(bytes[0]);
        let mut pos = 1;
        while bytes.len() - pos > 71 {
            out.extend_from_slice(&bytes[pos..pos + 71]);
            pos += 71;
            out.extend_from_slice(b"\r\n ");
        }
        out.extend_from_slice(&bytes[pos..]);
    }
    out.extend_from_slice(b"\r\n");
}

/// Splits on CRLF, LF or CR. A trailing terminator does not produce an empty last line.
fn split_lines(bytes: &[u8]) -> Vec<&[u8]> {
    let mut lines = Vec::new();
    let mut start = 0;
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'\n' => {
                lines.push(&bytes[start..i]);
                i += 1;
                start = i;
            }
            b'\r' => {
                lines.push(&bytes[start..i]);
                i += 1;
                if bytes.get(i) == Some(&b'\n') {
                    i += 1;
                }
                start = i;
            }
            _ => i += 1,
        }
    }
    if start < bytes.len() {
        lines.push(&bytes[start..]);
    }
    lines
}

/// `Name: Value`: the name runs to the first `:`, which must be followed by a space.
fn split_header(line: &str) -> io::Result<(&str, &str)> {
    let colon = line
        .find(':')
        .ok_or_else(|| invalid("invalid header field"))?;
    let name = &line[..colon];
    let rest = &line[colon + 1..];
    let value = rest
        .strip_prefix(' ')
        .ok_or_else(|| invalid("invalid header field"))?;
    let valid_name = !name.is_empty()
        && name.len() <= 70
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-');
    if !valid_name {
        return Err(invalid("invalid header field name"));
    }
    Ok((name, value))
}

fn invalid(msg: &str) -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidData,
        format!("Invalid manifest: {msg}"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_main_and_entry_sections() {
        let m = Manifest::parse(
            b"Manifest-Version: 1.0\nMain-Class: a.B\n\nName: a/B.class\nSHA-256-Digest: xyz\n\n",
        )
        .unwrap();
        assert_eq!(m.main.get("manifest-version"), Some("1.0"));
        assert_eq!(m.main.get("Main-Class"), Some("a.B"));
        assert_eq!(m.entries.len(), 1);
        assert_eq!(m.entries[0].0, "a/B.class");
        assert_eq!(m.entries[0].1.get("SHA-256-Digest"), Some("xyz"));
    }

    #[test]
    fn joins_continuation_lines_and_accepts_any_line_ending() {
        let m = Manifest::parse(b"Manifest-Version: 1.0\r\nClass-Path: aaa\r\n bbb\rX: y").unwrap();
        assert_eq!(m.main.get("Class-Path"), Some("aaabbb"));
        assert_eq!(m.main.get("X"), Some("y"));
    }

    #[test]
    fn writes_version_first_with_crlf_and_trailing_blank_line() {
        let mut main = Attributes::default();
        main.insert("Created-By", "test");
        main.insert("Manifest-Version", "1.0");
        let bytes = Manifest {
            main,
            entries: Vec::new(),
        }
        .to_bytes();
        assert_eq!(bytes, b"Manifest-Version: 1.0\r\nCreated-By: test\r\n\r\n");
    }

    #[test]
    fn without_a_version_no_main_attributes_are_written() {
        let mut main = Attributes::default();
        main.insert("Created-By", "test");
        assert_eq!(
            Manifest {
                main,
                entries: Vec::new()
            }
            .to_bytes(),
            b"\r\n"
        );
    }

    #[test]
    fn wraps_at_72_bytes_with_71_byte_continuations() {
        let value = "x".repeat(200);
        let mut out = Vec::new();
        println72(&mut out, &format!("Class-Path: {value}"));
        let text = String::from_utf8(out.clone()).unwrap();
        let lines: Vec<&str> = text.split("\r\n").collect();
        assert_eq!(lines[0].len(), 72);
        assert!(lines[1].starts_with(' '));
        assert_eq!(lines[1].len(), 72);
        assert_eq!(lines.last(), Some(&""));
        let reparsed =
            Manifest::parse(format!("Manifest-Version: 1.0\r\n{text}").as_bytes()).unwrap();
        assert_eq!(reparsed.main.get("Class-Path"), Some(value.as_str()));
    }

    #[test]
    fn a_utf8_character_split_by_wrapping_survives_a_round_trip() {
        let value = format!("{}\u{e9}\u{e9}\u{e9}", "a".repeat(49));
        let mut main = Attributes::default();
        main.insert("Manifest-Version", "1.0");
        main.insert("Implementation-Title", &value);
        let bytes = Manifest {
            main,
            entries: Vec::new(),
        }
        .to_bytes();
        let back = Manifest::parse(&bytes).unwrap();
        assert_eq!(back.main.get("Implementation-Title"), Some(value.as_str()));
    }

    #[test]
    fn rejects_a_header_without_colon_space() {
        assert!(Manifest::parse(b"Manifest-Version:1.0\n").is_err());
        assert!(Manifest::parse(b"no colon here\n").is_err());
    }

    #[test]
    fn repeated_attribute_keeps_first_position_and_spelling() {
        let mut a = Attributes::default();
        a.insert("A", "1");
        a.insert("B", "2");
        a.insert("a", "3");
        let items: Vec<_> = a.iter().collect();
        assert_eq!(items, vec![("A", "3"), ("B", "2")]);
        assert_eq!(a.remove("b"), Some("2".to_owned()));
        assert_eq!(a.len(), 1);
    }
}

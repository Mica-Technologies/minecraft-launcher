// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`Source`]: what a download reads from. Java accepted any `URL`; the launcher only ever
//! passes `http(s)`, `file:` and `jar:file:/...!/entry`. Local sources are read straight from
//! disk: no proxy, no stall watchdog, no offline latch.
//!
//! The `jar:` containment gate (which jars may be read) belongs to the modpack layer.

use std::fmt;
use std::path::{Path, PathBuf};

use url::Url;

use crate::error::DownloadError;

/// A download source.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Source {
    /// `http://` or `https://`.
    Http(Url),
    /// A local file (`file:` URL).
    File(PathBuf),
    /// An entry inside a local jar or zip (`jar:file:/path.jar!/entry`).
    JarEntry { jar: PathBuf, entry: String },
}

impl Source {
    /// Parses `http(s)://`, `file:` and `jar:file:...!/entry` URLs (schemes in any case).
    pub fn parse(s: &str) -> Result<Self, DownloadError> {
        let trimmed = s.trim();
        let invalid = || DownloadError::InvalidSource(trimmed.to_string());
        let lower = trimmed.to_ascii_lowercase();
        if lower.starts_with("jar:") {
            let rest = &trimmed[4..];
            let bang = rest.find("!/").ok_or_else(invalid)?;
            let inner = &rest[..bang];
            let entry = percent_decode(&rest[bang + 2..]);
            if entry.is_empty() {
                return Err(invalid());
            }
            let jar = file_url_to_path(inner).ok_or_else(invalid)?;
            return Ok(Source::JarEntry { jar, entry });
        }
        if lower.starts_with("file:") {
            return file_url_to_path(trimmed)
                .map(Source::File)
                .ok_or_else(invalid);
        }
        if lower.starts_with("http://") || lower.starts_with("https://") {
            let url = Url::parse(trimmed).map_err(|_| invalid())?;
            if url.host_str().is_none_or(str::is_empty) {
                return Err(invalid());
            }
            return Ok(Source::Http(url));
        }
        Err(invalid())
    }

    /// A source for a local file.
    pub fn file(path: impl Into<PathBuf>) -> Self {
        Source::File(path.into())
    }

    /// `true` for `http(s)` sources.
    pub fn is_network(&self) -> bool {
        matches!(self, Source::Http(_))
    }

    /// The HTTP URL, when this is a network source.
    pub fn url(&self) -> Option<&Url> {
        match self {
            Source::Http(u) => Some(u),
            _ => None,
        }
    }

    /// The short name shown in retry notices: see [`url_file_name`].
    pub fn file_name(&self) -> String {
        match self {
            Source::Http(u) => url_file_name(Some(u)),
            Source::File(p) => p
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default(),
            Source::JarEntry { entry, .. } => entry
                .rsplit('/')
                .next()
                .unwrap_or(entry.as_str())
                .to_string(),
        }
    }
}

impl fmt::Display for Source {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Source::Http(u) => f.write_str(u.as_str()),
            Source::File(p) => match Url::from_file_path(p) {
                Ok(u) => f.write_str(u.as_str()),
                Err(()) => write!(f, "file:{}", p.display()),
            },
            Source::JarEntry { jar, entry } => match Url::from_file_path(jar) {
                Ok(u) => write!(f, "jar:{}!/{}", u.as_str(), entry),
                Err(()) => write!(f, "jar:file:{}!/{}", jar.display(), entry),
            },
        }
    }
}

impl TryFrom<&str> for Source {
    type Error = DownloadError;
    fn try_from(s: &str) -> Result<Self, Self::Error> {
        Source::parse(s)
    }
}

impl From<Url> for Source {
    fn from(u: Url) -> Self {
        if u.scheme() == "file"
            && let Ok(p) = u.to_file_path()
        {
            return Source::File(p);
        }
        Source::Http(u)
    }
}

impl From<&Path> for Source {
    fn from(p: &Path) -> Self {
        Source::File(p.to_path_buf())
    }
}

/// The last path segment of a URL, for a readable retry notice (Java `urlFileName`):
/// `https://example/foo/bar.jar?x=1` gives `bar.jar`. An empty path gives the host, a path
/// ending in `/` (or with no `/`) gives the raw path, `None` gives `""`.
pub fn url_file_name(source: Option<&Url>) -> String {
    let Some(u) = source else {
        return String::new();
    };
    let path = u.path();
    // `url` reports "/" for an authority with no path, where Java's `URL.getPath()` reports "".
    // A bare "/" names no file either way, so both give the host.
    if path.is_empty() || path == "/" {
        return u.host_str().unwrap_or("").to_string();
    }
    raw_path_file_name(path)
}

/// [`url_file_name`] over a raw path: the text after the last `/` when there is some, else the
/// whole path.
pub fn raw_path_file_name(path: &str) -> String {
    match path.rfind('/') {
        Some(slash) if slash < path.len() - 1 => path[slash + 1..].to_string(),
        _ => path.to_string(),
    }
}

fn file_url_to_path(s: &str) -> Option<PathBuf> {
    let u = Url::parse(s).ok()?;
    if !u.scheme().eq_ignore_ascii_case("file") {
        return None;
    }
    u.to_file_path().ok()
}

/// Decodes `%XX` escapes (invalid escapes stay as written), like Java's `ParseUtil.decode` for
/// jar entry names.
fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%'
            && i + 2 < b.len()
            && let (Some(h), Some(l)) = (hex_val(b[i + 1]), hex_val(b[i + 2]))
        {
            out.push(h << 4 | l);
            i += 3;
            continue;
        }
        out.push(b[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex_val(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn u(s: &str) -> Url {
        Url::parse(s).unwrap()
    }

    // NetworkUtilitiesTest: urlFileName_*
    #[test]
    fn url_file_name_extracts_last_path_segment() {
        assert_eq!(
            url_file_name(Some(&u("https://example.com/foo/bar.jar"))),
            "bar.jar"
        );
    }

    #[test]
    fn url_file_name_strips_query_string() {
        assert_eq!(
            url_file_name(Some(&u("https://example.com/foo/bar.jar?x=1"))),
            "bar.jar"
        );
    }

    #[test]
    fn url_file_name_falls_back_to_host_when_path_is_empty() {
        assert_eq!(
            url_file_name(Some(&u("https://example.com"))),
            "example.com"
        );
    }

    #[test]
    fn url_file_name_falls_back_to_full_path_when_path_has_no_slash() {
        // `url` always roots an http path, so the no-slash case is the raw-path helper's.
        assert_eq!(raw_path_file_name("nofile"), "nofile");
    }

    #[test]
    fn url_file_name_returns_raw_path_when_path_ends_in_slash() {
        assert_eq!(url_file_name(Some(&u("https://example.com/foo/"))), "/foo/");
    }

    #[test]
    fn url_file_name_returns_empty_string_for_none() {
        assert_eq!(url_file_name(None), "");
    }

    #[test]
    fn parses_every_supported_scheme() {
        assert!(matches!(
            Source::parse("HTTPS://example.com/a.jar").unwrap(),
            Source::Http(_)
        ));
        assert!(matches!(
            Source::parse("http://example.com/a.jar").unwrap(),
            Source::Http(_)
        ));
        #[cfg(windows)]
        {
            let s = Source::parse("file:///C:/data/x.json").unwrap();
            assert_eq!(s, Source::File(PathBuf::from(r"C:\data\x.json")));
            let j = Source::parse("jar:file:/C:/data/modpack.jar!/maven/a/b%20c.jar").unwrap();
            assert_eq!(
                j,
                Source::JarEntry {
                    jar: PathBuf::from(r"C:\data\modpack.jar"),
                    entry: "maven/a/b c.jar".into()
                }
            );
            assert_eq!(j.file_name(), "b c.jar");
        }
        #[cfg(not(windows))]
        {
            let s = Source::parse("file:///data/x.json").unwrap();
            assert_eq!(s, Source::File(PathBuf::from("/data/x.json")));
            let j = Source::parse("jar:file:/data/modpack.jar!/maven/a/b.jar").unwrap();
            assert_eq!(
                j,
                Source::JarEntry {
                    jar: PathBuf::from("/data/modpack.jar"),
                    entry: "maven/a/b.jar".into()
                }
            );
        }
    }

    #[test]
    fn rejects_other_schemes_and_malformed_jars() {
        for bad in [
            "ftp://example.com/a",
            "jar:http://example.com/a.jar!/x",
            "jar:file:/a.jar",
            "jar:file:/a.jar!/",
            "https://",
            "",
            "C:/plain/path",
        ] {
            assert!(Source::parse(bad).is_err(), "{bad} should be rejected");
        }
    }

    #[test]
    fn percent_decode_keeps_invalid_escapes() {
        assert_eq!(percent_decode("a%20b%zz%4"), "a b%zz%4");
    }
}

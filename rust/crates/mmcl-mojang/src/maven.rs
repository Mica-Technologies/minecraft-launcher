// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Maven coordinates (`MavenArtifactPath`, spec 8.6).
//!
//! Grammar: `[`? `group:artifact:version[:classifier]` `[@ext]` `]`?

use std::fmt;

/// A parsed Maven coordinate.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct Coord {
    pub group: String,
    pub artifact: String,
    pub version: String,
    pub classifier: Option<String>,
    /// File extension, default `jar`.
    pub ext: String,
}

impl Coord {
    /// `group/with/slashes/artifact/version/artifact-version[-classifier].ext`,
    /// always with forward slashes.
    pub fn relative_path(&self) -> String {
        let classifier = self
            .classifier
            .as_deref()
            .map(|c| format!("-{c}"))
            .unwrap_or_default();
        format!(
            "{}/{}/{}/{}-{}{}.{}",
            self.group.replace('.', "/"),
            self.artifact,
            self.version,
            self.artifact,
            self.version,
            classifier,
            self.ext
        )
    }
}

/// Why a coordinate was rejected.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum CoordError {
    /// No coordinate was given.
    #[error("Maven coordinate is null")]
    Null,
    /// Fewer than three `:`-separated parts.
    #[error("Invalid Maven coordinate (expected group:artifact:version): {0}")]
    Malformed(String),
    /// A component contains `..`, `/` or `\`.
    #[error("Path traversal detected in Maven coordinate: {0}")]
    Traversal(String),
}

impl CoordError {
    /// The localisation key for this error.
    pub fn l10n_key(&self) -> &'static str {
        match self {
            CoordError::Null => "mojang.error.mavenNull",
            CoordError::Malformed(_) => "mojang.error.mavenMalformed",
            CoordError::Traversal(_) => "mojang.error.mavenTraversal",
        }
    }

    /// The MessageFormat arguments for [`Self::l10n_key`].
    pub fn l10n_args(&self) -> Vec<String> {
        match self {
            CoordError::Null => Vec::new(),
            CoordError::Malformed(c) | CoordError::Traversal(c) => vec![c.clone()],
        }
    }
}

fn has_traversal_chars(component: &str) -> bool {
    component.contains("..")
        || component.contains('/')
        || component.contains('\\')
        || component.contains('\0')
}

/// A group whose dots would turn into a leading, trailing or doubled `/`
/// (`.evil`, `evil.`): `.evil` becomes `/evil/...`, an **absolute** path that
/// `Path::join` would let replace the libraries root. Java's
/// `new File(parent, child)` concatenated instead, so this never escaped there;
/// in Rust it must be rejected.
fn group_has_empty_segment(group: &str) -> bool {
    group.split('.').any(str::is_empty)
}

/// Parse a coordinate, rejecting malformed input and any path traversal.
pub fn parse_strict(coord: &str) -> Result<Coord, CoordError> {
    let mut working = coord;
    if working.len() >= 2 && working.starts_with('[') && working.ends_with(']') {
        working = &working[1..working.len() - 1];
    }
    let mut ext = "jar";
    if let Some(at) = working.find('@') {
        ext = &working[at + 1..];
        working = &working[..at];
    }
    // Java String.split(":") drops trailing empty parts.
    let mut parts: Vec<&str> = working.split(':').collect();
    while parts.last().is_some_and(|p| p.is_empty()) {
        parts.pop();
    }
    if parts.len() < 3 {
        return Err(CoordError::Malformed(coord.to_string()));
    }
    let classifier = parts.get(3).copied();
    let traversal = parts[..3].iter().any(|p| has_traversal_chars(p))
        || classifier.is_some_and(has_traversal_chars)
        || has_traversal_chars(ext)
        // `:` in the extension (`g:a:v@x:y`) would name an NTFS alternate data
        // stream on Windows; the other components cannot hold one.
        || ext.contains(':')
        || (!parts[0].is_empty() && group_has_empty_segment(parts[0]));
    if traversal {
        return Err(CoordError::Traversal(coord.to_string()));
    }
    // Empty group, artifact, version or extension (`:a:v`, `g::v`, `g:a:v@`):
    // Java produced paths like `/a/v/a-v.jar` here; nothing real looks like it.
    if parts[..3].iter().any(|p| p.is_empty()) || ext.is_empty() {
        return Err(CoordError::Malformed(coord.to_string()));
    }
    Ok(Coord {
        group: parts[0].to_string(),
        artifact: parts[1].to_string(),
        version: parts[2].to_string(),
        classifier: classifier.map(str::to_string),
        ext: ext.to_string(),
    })
}

/// [`parse_strict`] for an optional coordinate; `None` is [`CoordError::Null`].
pub fn parse_strict_opt(coord: Option<&str>) -> Result<Coord, CoordError> {
    coord.map_or(Err(CoordError::Null), parse_strict)
}

/// Structural problems give `Ok(None)` (callers skip the entry); traversal is
/// still an error and must never be skipped silently.
pub fn parse_or_none(coord: &str) -> Result<Option<Coord>, CoordError> {
    match parse_strict(coord) {
        Ok(c) => Ok(Some(c)),
        Err(CoordError::Traversal(c)) => Err(CoordError::Traversal(c)),
        Err(_) => Ok(None),
    }
}

/// `parse_strict(coord)?.relative_path()`.
pub fn to_relative_path_strict(coord: &str) -> Result<String, CoordError> {
    parse_strict(coord).map(|c| c.relative_path())
}

/// `parse_or_none(coord)?.map(relative_path)`.
pub fn to_relative_path_or_none(coord: &str) -> Result<Option<String>, CoordError> {
    parse_or_none(coord).map(|c| c.map(|c| c.relative_path()))
}

impl fmt::Display for Coord {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}:{}:{}", self.group, self.artifact, self.version)?;
        if let Some(c) = &self.classifier {
            write!(f, ":{c}")?;
        }
        if self.ext != "jar" {
            write!(f, "@{}", self.ext)?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_group_artifact_version() {
        let c = parse_strict("net.minecraftforge:forge:1.20.1").unwrap();
        assert_eq!(c.group, "net.minecraftforge");
        assert_eq!(c.artifact, "forge");
        assert_eq!(c.version, "1.20.1");
        assert_eq!(c.classifier, None);
        assert_eq!(c.ext, "jar");
    }

    #[test]
    fn parses_optional_classifier() {
        let c = parse_strict("org.lwjgl:lwjgl:3.3.1:natives-windows").unwrap();
        assert_eq!(c.classifier.as_deref(), Some("natives-windows"));
        assert_eq!(c.ext, "jar");
    }

    #[test]
    fn parses_explicit_extension() {
        let c = parse_strict("de.oceanlabs.mcp:mcp_config:1.20.1@zip").unwrap();
        assert_eq!(c.ext, "zip");
        assert_eq!(c.artifact, "mcp_config");
    }

    #[test]
    fn parses_classifier_and_extension_together() {
        let c = parse_strict("org.lwjgl:lwjgl:3.3.1:natives-linux@zip").unwrap();
        assert_eq!(c.classifier.as_deref(), Some("natives-linux"));
        assert_eq!(c.ext, "zip");
    }

    #[test]
    fn strips_forge_style_square_brackets() {
        assert_eq!(
            parse_strict("[net.minecraftforge:forge:1.20.1]").unwrap(),
            parse_strict("net.minecraftforge:forge:1.20.1").unwrap()
        );
        let c = parse_strict("[de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412:mappings@txt]")
            .unwrap();
        assert_eq!(c.classifier.as_deref(), Some("mappings"));
        assert_eq!(c.ext, "txt");
    }

    #[test]
    fn builds_maven_layout_relative_path() {
        assert_eq!(
            to_relative_path_strict("net.minecraftforge:forge:1.20.1").unwrap(),
            "net/minecraftforge/forge/1.20.1/forge-1.20.1.jar"
        );
    }

    #[test]
    fn relative_path_includes_classifier_and_extension() {
        assert_eq!(
            to_relative_path_strict("org.lwjgl:lwjgl:3.3.1:natives-windows@zip").unwrap(),
            "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.zip"
        );
    }

    #[test]
    fn group_dots_become_path_separators() {
        assert!(
            to_relative_path_strict("a.b.c:d:1")
                .unwrap()
                .starts_with("a/b/c/")
        );
    }

    #[test]
    fn null_coordinate_is_rejected() {
        assert_eq!(parse_strict_opt(None), Err(CoordError::Null));
    }

    #[test]
    fn coordinate_with_too_few_components_is_rejected() {
        for c in ["net.minecraftforge:forge", "forge", "", "a:b:", "[]"] {
            assert!(
                matches!(parse_strict(c), Err(CoordError::Malformed(_))),
                "{c}"
            );
        }
    }

    #[test]
    fn traversal_in_group_is_rejected() {
        assert!(matches!(
            parse_strict("..:artifact:1.0"),
            Err(CoordError::Traversal(_))
        ));
        assert!(matches!(
            parse_strict("a/../../etc:artifact:1.0"),
            Err(CoordError::Traversal(_))
        ));
    }

    #[test]
    fn traversal_in_artifact_is_rejected() {
        assert!(matches!(
            parse_strict("group:../evil:1.0"),
            Err(CoordError::Traversal(_))
        ));
    }

    #[test]
    fn traversal_in_version_is_rejected() {
        assert!(matches!(
            parse_strict("group:artifact:../../1.0"),
            Err(CoordError::Traversal(_))
        ));
    }

    #[test]
    fn traversal_in_classifier_is_rejected() {
        assert!(matches!(
            parse_strict("group:artifact:1.0:../evil"),
            Err(CoordError::Traversal(_))
        ));
    }

    #[test]
    fn traversal_in_extension_is_rejected() {
        assert!(matches!(
            parse_strict("group:artifact:1.0@../evil"),
            Err(CoordError::Traversal(_))
        ));
    }

    #[test]
    fn literal_slashes_are_rejected_on_every_component() {
        assert!(matches!(
            parse_strict("group:art/ifact:1.0"),
            Err(CoordError::Traversal(_))
        ));
        assert!(matches!(
            parse_strict("group:artifact:1.0:nat\\\\ives"),
            Err(CoordError::Traversal(_))
        ));
    }

    #[test]
    fn group_dots_that_would_make_an_absolute_or_doubled_path_are_traversal() {
        for c in [".evil:a:1", "evil.:a:1", "a..b:c:1", ".:a:1", "a.b.:c:1"] {
            assert!(
                matches!(parse_strict(c), Err(CoordError::Traversal(_))),
                "{c}"
            );
        }
        // Path::join with an absolute path would replace the libraries root.
        for c in ["g:a:1", "a.b:c:1"] {
            let p = to_relative_path_strict(c).unwrap();
            assert!(!p.starts_with('/') && !p.contains("//"), "{p}");
        }
    }

    #[test]
    fn nul_and_stream_separators_are_traversal() {
        for c in [
            "g\0:a:1",
            "g:a\0:1",
            "g:a:1\0",
            "g:a:1:c\0",
            "g:a:1@j\0ar",
            "g:a:1@x:y",
        ] {
            assert!(
                matches!(parse_strict(c), Err(CoordError::Traversal(_))),
                "{c:?}"
            );
        }
    }

    #[test]
    fn empty_components_are_malformed() {
        for c in [":a:1", "g::1", "g:a::c", "g:a:1@"] {
            assert!(
                matches!(parse_strict(c), Err(CoordError::Malformed(_))),
                "{c}"
            );
            assert_eq!(parse_or_none(c), Ok(None), "{c}");
        }
    }

    #[test]
    fn parse_or_none_returns_none_for_structurally_invalid_input() {
        assert_eq!(parse_or_none("too:few"), Ok(None));
        assert_eq!(parse_or_none(""), Ok(None));
    }

    #[test]
    fn parse_or_none_still_errors_on_path_traversal() {
        assert!(matches!(
            parse_or_none("..:artifact:1.0"),
            Err(CoordError::Traversal(_))
        ));
        assert!(matches!(
            parse_or_none("group:artifact:1.0:../evil"),
            Err(CoordError::Traversal(_))
        ));
        assert!(matches!(
            to_relative_path_or_none("g:a:1@../x"),
            Err(CoordError::Traversal(_))
        ));
    }

    #[test]
    fn parse_or_none_parses_valid_coordinates() {
        let c = parse_or_none("group:artifact:1.0").unwrap().unwrap();
        assert_eq!(c.artifact, "artifact");
        assert_eq!(
            to_relative_path_or_none("g:a:1").unwrap().as_deref(),
            Some("g/a/1/a-1.jar")
        );
    }

    #[test]
    fn display_round_trips() {
        for s in ["g:a:1", "g:a:1:c", "g:a:1:c@zip", "g:a:1@txt"] {
            assert_eq!(parse_strict(s).unwrap().to_string(), s);
        }
    }

    #[test]
    fn error_keys() {
        assert_eq!(CoordError::Null.l10n_key(), "mojang.error.mavenNull");
        assert_eq!(CoordError::Malformed("x".into()).l10n_args(), vec!["x"]);
        assert_eq!(
            CoordError::Traversal("x".into()).l10n_key(),
            "mojang.error.mavenTraversal"
        );
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Classifies a pasted URL (Java `ModpackImportClassifier` / `ModpackImportSource`).

use std::sync::LazyLock;

use regex::Regex;

/// Where a pasted URL points.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ImportSource {
    /// A modrinth.com modpack project (`.mrpack`).
    Modrinth,
    /// A curseforge.com modpack project.
    CurseForge,
    /// A technicpack.net modpack (website or Platform API form).
    Technic,
    /// A Mica manifest (`https://...json`).
    Mica,
    /// None of the above: fall through to add-by-URL.
    Unknown,
}

/// One classification: the platform, the project slug and the optional version pin.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Classification {
    pub source: ImportSource,
    pub slug: Option<String>,
    pub version_id: Option<String>,
}

impl Classification {
    pub const UNKNOWN: Classification = Classification {
        source: ImportSource::Unknown,
        slug: None,
        version_id: None,
    };
}

static MODRINTH: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)^https?://(?:www\.)?modrinth\.com/modpack/([A-Za-z0-9_-]+)(?:/version/([A-Za-z0-9_-]+))?/?(?:\?.*)?$")
        .expect("valid regex")
});
static CURSEFORGE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)^https?://(?:www\.)?curseforge\.com/minecraft/modpacks/([A-Za-z0-9_-]+)(?:/(?:files|download)/(\d+))?/?(?:\?.*)?$")
        .expect("valid regex")
});
static TECHNIC: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)^https?://(?:www\.|api\.)?technicpack\.net/modpack/([A-Za-z0-9._-]+)(?:/[A-Za-z0-9._-]+)?/?(?:\?.*)?$")
        .expect("valid regex")
});
static MICA: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^https?://\S+\.json(?:\?.*)?$").expect("valid regex"));

/// Classifies `url` (surrounding whitespace ignored).
pub fn classify(url: Option<&str>) -> Classification {
    let Some(t) = url.map(str::trim).filter(|t| !t.is_empty()) else {
        return Classification::UNKNOWN;
    };
    let grp = |c: &regex::Captures<'_>, i| c.get(i).map(|m| m.as_str().to_string());
    if let Some(c) = MODRINTH.captures(t) {
        return Classification {
            source: ImportSource::Modrinth,
            slug: grp(&c, 1),
            version_id: grp(&c, 2),
        };
    }
    if let Some(c) = CURSEFORGE.captures(t) {
        return Classification {
            source: ImportSource::CurseForge,
            slug: grp(&c, 1),
            version_id: grp(&c, 2),
        };
    }
    if let Some(c) = TECHNIC.captures(t) {
        return Classification {
            source: ImportSource::Technic,
            slug: grp(&c, 1),
            version_id: None,
        };
    }
    if MICA.is_match(t) {
        return Classification {
            source: ImportSource::Mica,
            slug: None,
            version_id: None,
        };
    }
    Classification::UNKNOWN
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies() {
        let c = classify(Some(
            " https://modrinth.com/modpack/shenanigans/version/abc123 ",
        ));
        assert_eq!(
            (c.source, c.slug.as_deref(), c.version_id.as_deref()),
            (ImportSource::Modrinth, Some("shenanigans"), Some("abc123"))
        );
        assert_eq!(
            classify(Some("https://modrinth.com/mod/sodium")).source,
            ImportSource::Unknown
        );
        let c = classify(Some(
            "https://www.curseforge.com/minecraft/modpacks/rlcraft/files/12345",
        ));
        assert_eq!(
            (c.source, c.version_id.as_deref()),
            (ImportSource::CurseForge, Some("12345"))
        );
        let c = classify(Some("https://api.technicpack.net/modpack/tekkit.552560"));
        assert_eq!(
            (c.source, c.slug.as_deref()),
            (ImportSource::Technic, Some("tekkit.552560"))
        );
        assert_eq!(
            classify(Some("https://www.technicpack.net/modpack/tekkit/mods")).source,
            ImportSource::Technic
        );
        assert_eq!(
            classify(Some("https://host.example/x/manifest.json?v=2")).source,
            ImportSource::Mica
        );
        assert_eq!(
            classify(Some("ftp://x/y.json")).source,
            ImportSource::Unknown
        );
        assert_eq!(classify(None), Classification::UNKNOWN);
        assert_eq!(classify(Some("  ")), Classification::UNKNOWN);
    }
}

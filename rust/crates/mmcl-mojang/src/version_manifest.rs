// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Mojang's `version_manifest_v2.json` (spec 3.1).
//!
//! The download-once-per-process service lives in the launch crate; this is
//! the pure model.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::error::ManifestError;
use crate::error::json::{opt_array, opt_object, opt_string, parse_object};

/// Where the launcher fetches the version manifest from.
pub const VERSION_MANIFEST_URL: &str =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

/// The `latest` block.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Latest {
    #[serde(default)]
    pub release: String,
    #[serde(default)]
    pub snapshot: String,
}

/// One `versions[]` entry.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionEntry {
    pub id: String,
    /// `release`, `snapshot`, `old_beta`, `old_alpha`.
    #[serde(rename = "type", default)]
    pub kind: String,
    /// The version's client.json URL.
    pub url: String,
    #[serde(default)]
    pub time: String,
    #[serde(default)]
    pub release_time: String,
    /// SHA-1 of the client.json. Java ignored it (bug B-3); pass it on.
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub compliance_level: Option<i64>,
}

impl VersionEntry {
    /// `true` for `type == "release"`.
    pub fn is_release(&self) -> bool {
        self.kind == "release"
    }

    /// `sha1` when non-blank.
    pub fn sha1(&self) -> Option<&str> {
        self.sha1
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
    }
}

/// The parsed version manifest.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct VersionManifest {
    #[serde(default)]
    pub latest: Latest,
    pub versions: Vec<VersionEntry>,
}

impl VersionManifest {
    /// Parse `version_manifest_v2.json` (or the cached `config/version.manifest`).
    ///
    /// Only a top-level object with a `versions` array is required. An entry
    /// that is not an object or lacks a string `id` or `url` is dropped, and the
    /// optional fields are read leniently, so one odd entry (Mojang adds fields
    /// and types over time) never makes every version unlaunchable. Java read
    /// the tree lazily and only touched the entries before the match.
    pub fn parse(bytes: &[u8]) -> Result<Self, ManifestError> {
        let raw = parse_object(bytes)?;
        let versions = opt_array(&raw, "versions")
            .ok_or_else(|| ManifestError::Parse("missing the versions array".into()))?;
        let latest = opt_object(&raw, "latest")
            .map(|l| Latest {
                release: opt_string(l, "release").unwrap_or_default(),
                snapshot: opt_string(l, "snapshot").unwrap_or_default(),
            })
            .unwrap_or_default();
        let versions = versions
            .iter()
            .filter_map(Value::as_object)
            .filter_map(|v| {
                Some(VersionEntry {
                    id: v.get("id")?.as_str()?.to_string(),
                    url: v.get("url")?.as_str()?.to_string(),
                    kind: opt_string(v, "type").unwrap_or_default(),
                    time: opt_string(v, "time").unwrap_or_default(),
                    release_time: opt_string(v, "releaseTime").unwrap_or_default(),
                    sha1: opt_string(v, "sha1"),
                    compliance_level: v.get("complianceLevel").and_then(Value::as_i64),
                })
            })
            .collect();
        Ok(VersionManifest { latest, versions })
    }

    /// The entry whose `id` equals `id` exactly (not a prefix match). The first
    /// match wins. Missing → `minecraft.error.versionNotFound`.
    pub fn find(&self, id: &str) -> Result<&VersionEntry, ManifestError> {
        self.versions
            .iter()
            .find(|v| v.id == id)
            .ok_or_else(|| ManifestError::VersionNotFound(id.to_string()))
    }

    /// The client.json URL for `id` (Java `findLibraryManifestUrl`).
    pub fn client_json_url(&self, id: &str) -> Result<&str, ManifestError> {
        self.find(id).map(|v| v.url.as_str())
    }

    /// Release versions in manifest order (newest first).
    pub fn releases(&self) -> impl Iterator<Item = &VersionEntry> {
        self.versions.iter().filter(|v| v.is_release())
    }
}

/// The client.json cache file name under `metadata/`: `client-<version>.json`.
pub fn client_json_cache_file_name(minecraft_version: &str) -> String {
    format!("client-{minecraft_version}.json")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn manifest(entries: &[(&str, &str)]) -> VersionManifest {
        let versions: Vec<_> = entries
            .iter()
            .map(|(id, url)| serde_json::json!({"id": id, "url": url}))
            .collect();
        VersionManifest::parse(
            serde_json::json!({ "versions": versions })
                .to_string()
                .as_bytes(),
        )
        .unwrap()
    }

    #[test]
    fn finds_the_url_for_the_requested_version_id() {
        let m = manifest(&[
            ("1.20.1", "https://example.test/1.20.1.json"),
            ("1.19.2", "https://example.test/1.19.2.json"),
        ]);
        assert_eq!(
            m.client_json_url("1.20.1").unwrap(),
            "https://example.test/1.20.1.json"
        );
    }

    #[test]
    fn picks_the_matching_entry_rather_than_the_first_one() {
        let m = manifest(&[
            ("1.19.2", "https://example.test/1.19.2.json"),
            ("1.20.1", "https://example.test/1.20.1.json"),
            ("1.20.4", "https://example.test/1.20.4.json"),
        ]);
        assert_eq!(
            m.client_json_url("1.20.4").unwrap(),
            "https://example.test/1.20.4.json"
        );
    }

    #[test]
    fn first_duplicate_wins() {
        let m = manifest(&[("1.0", "https://a/"), ("1.0", "https://b/")]);
        assert_eq!(m.client_json_url("1.0").unwrap(), "https://a/");
    }

    #[test]
    fn errors_when_the_requested_version_is_not_present() {
        let m = manifest(&[("1.20.1", "https://example.test/1.20.1.json")]);
        let e = m.find("99.99.99").unwrap_err();
        assert_eq!(e, ManifestError::VersionNotFound("99.99.99".into()));
        assert_eq!(e.l10n_key(), "minecraft.error.versionNotFound");
        assert_eq!(e.l10n_args(), vec!["99.99.99"]);
    }

    #[test]
    fn errors_on_an_empty_versions_array() {
        assert!(manifest(&[]).find("1.20.1").is_err());
    }

    #[test]
    fn matching_is_exact_not_a_prefix_match() {
        let m = manifest(&[("1.20", "https://example.test/1.20.json")]);
        assert!(m.find("1.20.1").is_err());
        assert!(m.find("1.2").is_err());
    }

    #[test]
    fn parses_a_full_v2_manifest() {
        let raw = r#"{"latest":{"release":"1.21.5","snapshot":"25w14a"},
          "versions":[{"id":"25w14a","type":"snapshot","url":"https://piston-meta.mojang.com/v1/packages/aa/25w14a.json",
                       "time":"t","releaseTime":"rt","sha1":"aa","complianceLevel":1},
                      {"id":"1.21.5","type":"release","url":"https://piston-meta.mojang.com/v1/packages/bb/1.21.5.json",
                       "time":"t","releaseTime":"rt2","sha1":"bb","complianceLevel":1}]}"#;
        let m = VersionManifest::parse(raw.as_bytes()).unwrap();
        assert_eq!(m.latest.release, "1.21.5");
        let e = m.find("1.21.5").unwrap();
        assert_eq!(e.sha1(), Some("bb"));
        assert_eq!(e.release_time, "rt2");
        assert_eq!(e.compliance_level, Some(1));
        assert_eq!(
            m.releases().map(|v| v.id.as_str()).collect::<Vec<_>>(),
            vec!["1.21.5"]
        );
    }

    #[test]
    fn missing_versions_or_bad_json_is_a_parse_error() {
        assert!(matches!(
            VersionManifest::parse(b"{}"),
            Err(ManifestError::Parse(_))
        ));
        assert!(matches!(
            VersionManifest::parse(b"not json"),
            Err(ManifestError::Parse(_))
        ));
    }

    #[test]
    fn one_malformed_entry_does_not_hide_the_others() {
        let raw = r#"{"versions": [
            "junk", {"id": "broken"}, {"id": 5, "url": "https://n"},
            {"id": "1.20.1", "url": "https://ok", "time": 17, "complianceLevel": "x"}]}"#;
        let m = VersionManifest::parse(raw.as_bytes()).unwrap();
        assert_eq!(m.versions.len(), 1);
        let e = m.find("1.20.1").unwrap();
        assert_eq!(e.url, "https://ok");
        assert_eq!(e.time, "17");
        assert_eq!(e.compliance_level, None);
        assert!(m.find("broken").is_err());
    }

    #[test]
    fn cache_file_name() {
        assert_eq!(client_json_cache_file_name("1.20.1"), "client-1.20.1.json");
    }
}

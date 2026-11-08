// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! File-list entries (`packMods`, `packConfigs`, `packResourcePacks`, `packShaderPacks`,
//! `packInitialFiles`) as manifest data. Download and verification live in `mmcl-modpack`.

use serde::{Deserialize, Serialize};

use crate::lenient;

/// The side a pack is prepared for. Mirrors `mmcl-core`'s game mode, kept here so this
/// layer-0 crate has no dependencies.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Side {
    Client,
    Server,
}

/// A hash algorithm a manifest entry can declare.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum HashAlgo {
    Sha256,
    Sha1,
    Md5,
}

impl HashAlgo {
    /// The manifest key and lower-case name: `sha256`, `sha1` or `md5`.
    pub fn key(self) -> &'static str {
        match self {
            HashAlgo::Sha256 => "sha256",
            HashAlgo::Sha1 => "sha1",
            HashAlgo::Md5 => "md5",
        }
    }

    /// Length of the hex digest.
    pub fn hex_len(self) -> usize {
        match self {
            HashAlgo::Sha256 => 64,
            HashAlgo::Sha1 => 40,
            HashAlgo::Md5 => 32,
        }
    }

    /// Parses a case-insensitive algorithm name.
    pub fn from_name(name: &str) -> Option<Self> {
        match name.trim().to_ascii_lowercase().as_str() {
            "sha256" | "sha-256" => Some(HashAlgo::Sha256),
            "sha1" | "sha-1" => Some(HashAlgo::Sha1),
            "md5" => Some(HashAlgo::Md5),
            _ => None,
        }
    }
}

/// A declared hash, or `None` when the slot means "no hash": absent, blank, or the `"-1"`
/// sentinel the editor writes for empty slots.
pub fn usable_hash(h: Option<&str>) -> Option<&str> {
    h.filter(|v| !v.trim().is_empty() && *v != "-1")
}

/// One entry of a manifest file list.
///
/// `name` is only meaningful in `packMods`; `client_req` / `server_req` only in `packMods`,
/// `packConfigs` and `packInitialFiles` (resource and shader packs are client-only by list).
/// An absent side flag reads as `false`, as Gson leaves it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ManifestFile {
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub name: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub remote: Option<String>,
    /// `/`-separated path relative to the list's folder, exactly as written (never
    /// separator-converted). Use [`ManifestFile::local_segments`] to join it.
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub local: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub sha1: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub md5: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub sha256: Option<String>,
    #[serde(rename = "clientReq", deserialize_with = "lenient::boolean")]
    pub client_req: bool,
    #[serde(rename = "serverReq", deserialize_with = "lenient::boolean")]
    pub server_req: bool,
    /// Editor-only Modrinth project slug (manifest format 2). Not read at runtime.
    #[serde(
        rename = "modrinthSlug",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub modrinth_slug: Option<String>,
}

impl ManifestFile {
    /// The declared hash in `algo`'s slot, if usable.
    pub fn hash(&self, algo: HashAlgo) -> Option<&str> {
        let slot = match algo {
            HashAlgo::Sha256 => &self.sha256,
            HashAlgo::Sha1 => &self.sha1,
            HashAlgo::Md5 => &self.md5,
        };
        usable_hash(slot.as_deref())
    }

    /// The strongest usable hash: SHA-256, then SHA-1, then MD5. Exactly one is ever
    /// checked; there is no fallback to a weaker one on mismatch.
    pub fn strongest_hash(&self) -> Option<(HashAlgo, &str)> {
        [HashAlgo::Sha256, HashAlgo::Sha1, HashAlgo::Md5]
            .into_iter()
            .find_map(|algo| self.hash(algo).map(|h| (algo, h)))
    }

    /// True when any slot holds a usable hash.
    pub fn has_any_hash(&self) -> bool {
        self.strongest_hash().is_some()
    }

    /// Whether this entry is downloaded and kept on `side`, from its side flags.
    pub fn is_required_for(&self, side: Side) -> bool {
        match side {
            Side::Client => self.client_req,
            Side::Server => self.server_req,
        }
    }

    /// The non-empty path segments of `local`, split on `/` and `\`. `..` and `.` segments
    /// are kept so the caller's containment gate can reject them; an absolute or drive-letter
    /// path shows up as a first segment such as `C:`.
    pub fn local_segments(&self) -> Vec<&str> {
        self.local
            .as_deref()
            .map(|l| l.split(['/', '\\']).filter(|s| !s.is_empty()).collect())
            .unwrap_or_default()
    }

    /// The last segment of `local` (the installed file's name), if any.
    pub fn file_name(&self) -> Option<&str> {
        self.local_segments().last().copied()
    }

    /// True when `remote` is blank: a local-only file, accepted if present.
    pub fn is_local_only(&self) -> bool {
        crate::non_blank(self.remote.as_deref()).is_none()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(sha1: Option<&str>, md5: Option<&str>, sha256: Option<&str>) -> ManifestFile {
        ManifestFile {
            sha1: sha1.map(Into::into),
            md5: md5.map(Into::into),
            sha256: sha256.map(Into::into),
            ..Default::default()
        }
    }

    #[test]
    fn sentinel_blank_and_absent_are_not_hashes() {
        assert_eq!(usable_hash(None), None);
        assert_eq!(usable_hash(Some("")), None);
        assert_eq!(usable_hash(Some("   ")), None);
        assert_eq!(usable_hash(Some("-1")), None);
        assert_eq!(usable_hash(Some("abc")), Some("abc"));
    }

    #[test]
    fn sha256_is_preferred() {
        let f = file(Some("aaa"), Some("bbb"), Some("ccc"));
        assert_eq!(f.strongest_hash(), Some((HashAlgo::Sha256, "ccc")));
    }

    #[test]
    fn falls_through_to_sha1_when_sha256_is_the_sentinel() {
        let f = file(Some("aaa"), Some("bbb"), Some("-1"));
        assert_eq!(f.strongest_hash(), Some((HashAlgo::Sha1, "aaa")));
        let f = file(Some("  "), Some("bbb"), None);
        assert_eq!(f.strongest_hash(), Some((HashAlgo::Md5, "bbb")));
        assert!(!file(None, Some("-1"), Some("")).has_any_hash());
    }

    #[test]
    fn side_matrix() {
        let server_only = ManifestFile {
            client_req: false,
            server_req: true,
            ..Default::default()
        };
        assert!(!server_only.is_required_for(Side::Client));
        assert!(server_only.is_required_for(Side::Server));
        let client_only = ManifestFile {
            client_req: true,
            server_req: false,
            ..Default::default()
        };
        assert!(client_only.is_required_for(Side::Client));
        assert!(!client_only.is_required_for(Side::Server));
        let shared = ManifestFile {
            client_req: true,
            server_req: true,
            ..Default::default()
        };
        assert!(shared.is_required_for(Side::Client) && shared.is_required_for(Side::Server));
    }

    #[test]
    fn absent_side_flags_are_false_at_runtime() {
        let f: ManifestFile = serde_json::from_str("{\"remote\":\"r\",\"local\":\"l\"}").unwrap();
        assert!(!f.client_req && !f.server_req);
    }

    #[test]
    fn local_keeps_slashes_and_splits_into_segments() {
        let f = ManifestFile {
            local: Some("ldic/menu.json".into()),
            ..Default::default()
        };
        assert_eq!(f.local.as_deref(), Some("ldic/menu.json"));
        assert_eq!(f.local_segments(), ["ldic", "menu.json"]);
        assert_eq!(f.file_name(), Some("menu.json"));
        let hostile = ManifestFile {
            local: Some("..\\..//x.jar".into()),
            ..Default::default()
        };
        assert_eq!(hostile.local_segments(), ["..", "..", "x.jar"]);
        assert_eq!(ManifestFile::default().file_name(), None);
    }

    #[test]
    fn algo_names() {
        assert_eq!(HashAlgo::from_name("SHA256"), Some(HashAlgo::Sha256));
        assert_eq!(HashAlgo::from_name("md5"), Some(HashAlgo::Md5));
        assert_eq!(HashAlgo::from_name("crc"), None);
        assert_eq!(HashAlgo::Sha1.key(), "sha1");
        assert_eq!(HashAlgo::Sha1.hex_len(), 40);
    }
}

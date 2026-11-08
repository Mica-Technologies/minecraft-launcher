// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The asset index (`assets/indexes/<id>.json`, spec 3.4 and 6).

use indexmap::IndexMap;
use serde_json::Value;

use crate::error::ManifestError;
use crate::error::json::{as_bool, opt_object, parse_object, required_string};

/// Base URL of Mojang's asset object server.
pub const ASSET_SERVER_URL: &str = "https://resources.download.minecraft.net/";

/// One `objects` entry.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AssetObject {
    /// Hex SHA-1, verbatim; also the object's file name.
    pub hash: String,
    /// Declared size (unused by the launcher).
    pub size: Option<u64>,
}

impl AssetObject {
    /// `https://resources.download.minecraft.net/<hh>/<hash>`.
    pub fn url(&self) -> String {
        object_url(&self.hash)
    }

    /// `<hh>/<hash>`, relative to `shared/assets/objects`.
    pub fn relative_path(&self) -> String {
        object_relative_path(&self.hash)
    }
}

/// Where a legacy index wants a flat copy of its objects.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LegacyLayout {
    /// Modern index: the game reads the hashed objects directly.
    None,
    /// `virtual: true` → `<pack>/assets/virtual/<id>`.
    Virtual,
    /// `map_to_resources: true` → `<pack>/resources` (wins over `virtual`).
    MapToResources,
}

/// A parsed asset index.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AssetIndex {
    pub virtual_: bool,
    pub map_to_resources: bool,
    /// Asset name → object, in file order.
    pub objects: IndexMap<String, AssetObject>,
}

impl AssetIndex {
    /// Parse an asset index. `objects` and each object's `hash` are required,
    /// and every hash must be 40 hex characters (it becomes a path).
    pub fn parse(bytes: &[u8]) -> Result<Self, ManifestError> {
        let raw = parse_object(bytes)?;
        let flag = |k: &str| raw.get(k).and_then(as_bool).unwrap_or(false);
        let objs = opt_object(&raw, "objects")
            .ok_or_else(|| ManifestError::MissingField("objects".into()))?;
        let mut objects = IndexMap::with_capacity(objs.len());
        for (name, obj) in objs {
            let obj = obj
                .as_object()
                .ok_or_else(|| ManifestError::MissingField("hash".into()))?;
            let hash = required_string(obj, "hash")?;
            if !is_sha1_hex(&hash) {
                return Err(ManifestError::BadAssetHash(name.clone()));
            }
            objects.insert(
                name.clone(),
                AssetObject {
                    hash,
                    size: obj.get("size").and_then(Value::as_u64),
                },
            );
        }
        Ok(AssetIndex {
            virtual_: flag("virtual"),
            map_to_resources: flag("map_to_resources"),
            objects,
        })
    }

    /// Which flat legacy tree, if any, to materialise.
    pub fn legacy_layout(&self) -> LegacyLayout {
        if self.map_to_resources {
            LegacyLayout::MapToResources
        } else if self.virtual_ {
            LegacyLayout::Virtual
        } else {
            LegacyLayout::None
        }
    }
}

fn is_sha1_hex(s: &str) -> bool {
    s.len() == 40 && s.bytes().all(|b| b.is_ascii_hexdigit())
}

/// `https://resources.download.minecraft.net/<hash[0..2]>/<hash>`.
pub fn object_url(hash: &str) -> String {
    format!("{ASSET_SERVER_URL}{}", object_relative_path(hash))
}

/// `<hash[0..2]>/<hash>`.
pub fn object_relative_path(hash: &str) -> String {
    let prefix = hash.get(..2).unwrap_or(hash);
    format!("{prefix}/{hash}")
}

/// The index file name under `shared/assets/indexes/`: `<id>.json`.
pub fn index_file_name(asset_index_id: &str) -> String {
    format!("{asset_index_id}.json")
}

#[cfg(test)]
mod tests {
    use super::*;

    const H1: &str = "bdf48ef6b5d0d23bbb02e17d04865216179f510a";
    const H2: &str = "0123456789ABCDEF0123456789abcdef01234567";

    #[test]
    fn parses_objects_in_order_with_flags() {
        let raw = format!(
            r#"{{"virtual": true, "objects": {{
                "minecraft/sounds/b.ogg": {{"hash": "{H1}", "size": 12345}},
                "a.png": {{"hash": "{H2}"}} }} }}"#
        );
        let idx = AssetIndex::parse(raw.as_bytes()).unwrap();
        assert!(idx.virtual_);
        assert!(!idx.map_to_resources);
        assert_eq!(idx.legacy_layout(), LegacyLayout::Virtual);
        assert_eq!(
            idx.objects.keys().collect::<Vec<_>>(),
            vec!["minecraft/sounds/b.ogg", "a.png"]
        );
        assert_eq!(idx.objects["minecraft/sounds/b.ogg"].size, Some(12345));
        assert_eq!(
            idx.objects["a.png"].hash, H2,
            "kept verbatim: it is a file name"
        );
    }

    #[test]
    fn map_to_resources_wins() {
        let idx =
            AssetIndex::parse(br#"{"virtual": true, "map_to_resources": true, "objects": {}}"#)
                .unwrap();
        assert_eq!(idx.legacy_layout(), LegacyLayout::MapToResources);
        let idx = AssetIndex::parse(br#"{"objects": {}}"#).unwrap();
        assert_eq!(idx.legacy_layout(), LegacyLayout::None);
    }

    #[test]
    fn urls_and_paths() {
        assert_eq!(
            object_url(H1),
            format!("https://resources.download.minecraft.net/bd/{H1}")
        );
        let o = AssetObject {
            hash: H1.into(),
            size: None,
        };
        assert_eq!(o.relative_path(), format!("bd/{H1}"));
        assert_eq!(o.url(), object_url(H1));
        assert_eq!(index_file_name("1.20"), "1.20.json");
    }

    #[test]
    fn required_fields_and_hash_validation() {
        assert_eq!(
            AssetIndex::parse(b"{}"),
            Err(ManifestError::MissingField("objects".into()))
        );
        assert_eq!(
            AssetIndex::parse(br#"{"objects": {"x": {"size": 1}}}"#),
            Err(ManifestError::MissingField("hash".into()))
        );
        assert_eq!(
            AssetIndex::parse(br#"{"objects": {"x": {"hash": "../../etc/passwd"}}}"#),
            Err(ManifestError::BadAssetHash("x".into()))
        );
        assert!(AssetIndex::parse(b"[]").is_err());
    }
}

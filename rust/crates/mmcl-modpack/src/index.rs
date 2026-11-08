// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `<root>/installs/install_index.json` (Java `InstallIndex`): the card fields of every
//! installed pack, so the library renders before any manifest loads.
//!
//! **Guards:** every load-modify-save runs under one process-wide lock, and every save writes a
//! uniquely named temp file then renames it (a shared `.tmp` let two writers interleave and
//! publish garbled JSON). Stale temp files are reaped on load.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime};

use indexmap::IndexMap;
use mmcl_i18n::tf;
use mmcl_manifest::StringOrArray;
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};

use crate::pack::GameModPack;

/// The schema version this launcher writes and reads.
pub const CURRENT_SCHEMA_VERSION: i32 = 1;
/// An unchanged entry is still rewritten when its `updatedAt` is this old.
pub const UPDATED_AT_REFRESH_MS: i64 = 24 * 60 * 60 * 1000;
/// Temp files older than this are deleted on load.
pub const STALE_TEMP_AGE: Duration = Duration::from_secs(5 * 60);

static LOCK: Mutex<()> = Mutex::new(());
static TMP_SEQ: AtomicU64 = AtomicU64::new(0);

fn lenient_i64<'de, D: serde::Deserializer<'de>>(d: D) -> Result<i64, D::Error> {
    let v = serde_json::Value::deserialize(d)?;
    Ok(mmcl_util::json::as_gson_i64(&v).unwrap_or(0))
}

fn lenient_i32<'de, D: serde::Deserializer<'de>>(d: D) -> Result<i32, D::Error> {
    let v = serde_json::Value::deserialize(d)?;
    Ok(mmcl_manifest::lenient::coerce_i32(&v).unwrap_or(0))
}

/// The card subset of one pack.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct IndexEntry {
    #[serde(
        rename = "packName",
        skip_serializing_if = "Option::is_none",
        deserialize_with = "mmcl_manifest::lenient::string"
    )]
    pub pack_name: Option<String>,
    #[serde(
        rename = "packVersion",
        skip_serializing_if = "Option::is_none",
        deserialize_with = "mmcl_manifest::lenient::string"
    )]
    pub pack_version: Option<String>,
    #[serde(
        rename = "packURL",
        skip_serializing_if = "Option::is_none",
        deserialize_with = "mmcl_manifest::lenient::string"
    )]
    pub pack_url: Option<String>,
    #[serde(
        rename = "packLogoURL",
        skip_serializing_if = "mmcl_manifest::string_or_array::is_absent",
        deserialize_with = "mmcl_manifest::string_or_array::optional"
    )]
    pub pack_logo_url: Option<StringOrArray>,
    #[serde(
        rename = "packLogoSha1",
        skip_serializing_if = "Option::is_none",
        deserialize_with = "mmcl_manifest::lenient::string"
    )]
    pub pack_logo_sha1: Option<String>,
    #[serde(
        rename = "packBackgroundURL",
        skip_serializing_if = "mmcl_manifest::string_or_array::is_absent",
        deserialize_with = "mmcl_manifest::string_or_array::optional"
    )]
    pub pack_background_url: Option<StringOrArray>,
    #[serde(
        rename = "packBackgroundSha1",
        skip_serializing_if = "Option::is_none",
        deserialize_with = "mmcl_manifest::lenient::string"
    )]
    pub pack_background_sha1: Option<String>,
    #[serde(
        rename = "packMinRAMGB",
        skip_serializing_if = "Option::is_none",
        deserialize_with = "mmcl_manifest::lenient::string"
    )]
    pub pack_min_ram_gb: Option<String>,
    #[serde(
        rename = "packUnstable",
        deserialize_with = "mmcl_manifest::lenient::boolean"
    )]
    pub pack_unstable: bool,
    #[serde(
        rename = "packCustomDiscordRpc",
        deserialize_with = "mmcl_manifest::lenient::boolean"
    )]
    pub pack_custom_discord_rpc: bool,
    #[serde(rename = "updatedAt", deserialize_with = "lenient_i64")]
    pub updated_at: i64,
}

fn soa_eq(a: &Option<StringOrArray>, b: &Option<StringOrArray>) -> bool {
    let empty = StringOrArray::default();
    a.as_ref().unwrap_or(&empty) == b.as_ref().unwrap_or(&empty)
}

impl IndexEntry {
    /// The card fields of `pack`, stamped now.
    pub fn of(pack: &GameModPack) -> IndexEntry {
        let m = pack.manifest();
        IndexEntry {
            pack_name: m.pack_name.clone(),
            pack_version: m.pack_version.clone(),
            pack_url: m.pack_url.clone(),
            pack_logo_url: m.pack_logo_url.clone(),
            pack_logo_sha1: m.pack_logo_sha1.clone(),
            pack_background_url: m.pack_background_url.clone(),
            pack_background_sha1: m.pack_background_sha1.clone(),
            pack_min_ram_gb: m.pack_min_ram_gb.clone(),
            pack_unstable: m.pack_unstable,
            pack_custom_discord_rpc: m.pack_custom_discord_rpc,
            updated_at: chrono::Utc::now().timestamp_millis(),
        }
    }

    /// Every field but `updatedAt` equal.
    pub fn same_content(&self, o: &IndexEntry) -> bool {
        self.pack_name == o.pack_name
            && self.pack_version == o.pack_version
            && self.pack_url == o.pack_url
            && soa_eq(&self.pack_logo_url, &o.pack_logo_url)
            && self.pack_logo_sha1 == o.pack_logo_sha1
            && soa_eq(&self.pack_background_url, &o.pack_background_url)
            && self.pack_background_sha1 == o.pack_background_sha1
            && self.pack_min_ram_gb == o.pack_min_ram_gb
            && self.pack_unstable == o.pack_unstable
            && self.pack_custom_discord_rpc == o.pack_custom_discord_rpc
    }
}

/// The whole index.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct InstallIndex {
    #[serde(deserialize_with = "lenient_i32")]
    pub version: i32,
    #[serde(deserialize_with = "packs_or_empty")]
    pub packs: IndexMap<String, IndexEntry>,
}

fn packs_or_empty<'de, D: serde::Deserializer<'de>>(
    d: D,
) -> Result<IndexMap<String, IndexEntry>, D::Error> {
    let v = serde_json::Value::deserialize(d)?;
    let serde_json::Value::Object(o) = v else {
        return Ok(IndexMap::new());
    };
    Ok(o.into_iter()
        .filter_map(|(k, v)| serde_json::from_value::<IndexEntry>(v).ok().map(|e| (k, e)))
        .collect())
}

impl Default for InstallIndex {
    fn default() -> Self {
        InstallIndex {
            version: CURRENT_SCHEMA_VERSION,
            packs: IndexMap::new(),
        }
    }
}

fn tmp_prefix(path: &Path) -> String {
    format!(
        "{}.tmp.",
        path.file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default()
    )
}

fn reap_stale_temp_files(path: &Path) {
    let Some(dir) = path.parent() else {
        return;
    };
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    let prefix = tmp_prefix(path);
    let now = SystemTime::now();
    for e in entries.flatten() {
        if !e.file_name().to_string_lossy().starts_with(&prefix) {
            continue;
        }
        let old = e
            .metadata()
            .and_then(|m| m.modified())
            .ok()
            .and_then(|m| now.duration_since(m).ok())
            .is_some_and(|age| age > STALE_TEMP_AGE);
        if old {
            let _ = fs::remove_file(e.path());
        }
    }
}

impl InstallIndex {
    fn load_unlocked(path: &Path) -> InstallIndex {
        reap_stale_temp_files(path);
        if !path.is_file() {
            return InstallIndex::default();
        }
        let parsed = fs::read_to_string(path)
            .map_err(|e| e.to_string())
            .and_then(|t| serde_json::from_str::<InstallIndex>(&t).map_err(|e| e.to_string()));
        match parsed {
            Ok(i) if i.version > CURRENT_SCHEMA_VERSION => {
                mmcl_log::warn_silent(&tf!(
                    "log.installIndex.versionTooNew",
                    i.version.to_string(),
                    CURRENT_SCHEMA_VERSION.to_string()
                ));
                InstallIndex::default()
            }
            Ok(i) => i,
            Err(e) => {
                mmcl_log::warn_silent(&tf!("log.installIndex.readFailed", e));
                InstallIndex::default()
            }
        }
    }

    /// Reads the index (empty when missing, unreadable or from a newer launcher).
    pub fn load(path: &Path) -> InstallIndex {
        let _g = LOCK.lock();
        Self::load_unlocked(path)
    }

    /// Writes the index through a unique temp file and a rename.
    pub fn save(&self, path: &Path) {
        let _g = LOCK.lock();
        self.save_unlocked(path);
    }

    fn save_unlocked(&self, path: &Path) {
        if let Some(parent) = path.parent()
            && let Err(e) = fs::create_dir_all(parent)
        {
            mmcl_log::warn_silent(&tf!(
                "log.installIndex.createParentFailed",
                parent.display().to_string(),
                e.to_string()
            ));
            return;
        }
        let json = serde_json::to_value(self)
            .map(|v| mmcl_manifest::gson::to_compact_string(&v))
            .unwrap_or_default();
        let tmp = PathBuf::from(format!(
            "{}{}",
            path.with_file_name(tmp_prefix(path)).display(),
            TMP_SEQ.fetch_add(1, Ordering::SeqCst) + 1
        ));
        let via_tmp = fs::write(&tmp, &json).and_then(|_| {
            fs::rename(&tmp, path).or_else(|_| {
                let _ = fs::remove_file(path);
                fs::rename(&tmp, path)
            })
        });
        if via_tmp.is_err() {
            let _ = fs::remove_file(&tmp);
            if let Err(e) = fs::write(path, &json) {
                mmcl_log::warn_silent(&tf!(
                    "log.installIndex.writeFailed",
                    e.kind().to_string(),
                    e.to_string()
                ));
            }
        }
    }

    /// The entry for `url`.
    pub fn get(&self, url: &str) -> Option<&IndexEntry> {
        self.packs.get(url)
    }

    /// Inserts or replaces the entry for `url` (no save).
    pub fn upsert(&mut self, url: &str, pack: &GameModPack) {
        if url.trim().is_empty() {
            return;
        }
        self.packs.insert(url.to_string(), IndexEntry::of(pack));
    }

    /// Load, upsert and save under one lock; skips the write when nothing changed and the
    /// previous stamp is less than a day old.
    pub fn upsert_and_save(path: &Path, url: &str, pack: &GameModPack) {
        if url.trim().is_empty() {
            return;
        }
        let _g = LOCK.lock();
        let mut idx = Self::load_unlocked(path);
        let before = idx.get(url).cloned();
        idx.upsert(url, pack);
        if let (Some(b), Some(a)) = (before, idx.get(url))
            && b.same_content(a)
            && a.updated_at - b.updated_at < UPDATED_AT_REFRESH_MS
        {
            return;
        }
        idx.save_unlocked(path);
    }

    /// Load, remove and save under one lock.
    pub fn remove_and_save(path: &Path, url: &str) {
        if url.trim().is_empty() {
            return;
        }
        let _g = LOCK.lock();
        let mut idx = Self::load_unlocked(path);
        idx.packs.shift_remove(url);
        idx.save_unlocked(path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_core::{DataPaths, GameMode};
    use mmcl_manifest::PackManifest;
    use std::sync::Arc;

    fn pack(name: &str, ver: &str) -> Arc<GameModPack> {
        let paths = Arc::new(DataPaths::at_root("/unused", GameMode::Client));
        GameModPack::from_manifest(
            paths,
            PackManifest {
                pack_name: Some(name.into()),
                pack_version: Some(ver.into()),
                pack_background_url: Some(StringOrArray::of([
                    "https://a/1.png",
                    "https://a/2.png",
                ])),
                pack_min_ram_gb: Some("6".into()),
                pack_custom_discord_rpc: true,
                ..Default::default()
            },
            None,
            None,
        )
    }

    #[test]
    fn round_trip_shape() {
        let d = tempfile::tempdir().unwrap();
        let p = d.path().join("installs").join("install_index.json");
        InstallIndex::upsert_and_save(&p, "https://x/alto.mmcjson", &pack("Alto", "1"));
        let text = fs::read_to_string(&p).unwrap();
        assert!(text.starts_with(
            r#"{"version":1,"packs":{"https://x/alto.mmcjson":{"packName":"Alto","packVersion":"1","packBackgroundURL":["https://a/1.png","https://a/2.png"],"packMinRAMGB":"6","packUnstable":false,"packCustomDiscordRpc":true,"updatedAt":"#
        ), "{text}");
        let idx = InstallIndex::load(&p);
        assert_eq!(
            idx.get("https://x/alto.mmcjson")
                .unwrap()
                .pack_name
                .as_deref(),
            Some("Alto")
        );
        InstallIndex::remove_and_save(&p, "https://x/alto.mmcjson");
        assert!(InstallIndex::load(&p).packs.is_empty());
    }

    #[test]
    fn newer_version_or_garbage_reads_empty() {
        let d = tempfile::tempdir().unwrap();
        let p = d.path().join("install_index.json");
        fs::write(&p, r#"{"version":2,"packs":{"a":{}}}"#).unwrap();
        assert!(InstallIndex::load(&p).packs.is_empty());
        fs::write(&p, "{garbage").unwrap();
        assert!(InstallIndex::load(&p).packs.is_empty());
        fs::write(&p, r#"{"version":1,"packs":null}"#).unwrap();
        assert!(InstallIndex::load(&p).packs.is_empty());
    }

    // ---- InstallIndexConcurrencyTest ----

    #[test]
    fn concurrent_upserts_neither_corrupt_nor_lose_entries() {
        let d = tempfile::tempdir().unwrap();
        let p = d.path().join("install_index.json");
        std::thread::scope(|s| {
            for i in 0..16 {
                let p = &p;
                s.spawn(move || {
                    InstallIndex::upsert_and_save(
                        p,
                        &format!("https://x/{i}.mmcjson"),
                        &pack(&format!("P{i}"), "1"),
                    );
                });
            }
        });
        let idx = InstallIndex::load(&p);
        assert_eq!(idx.packs.len(), 16);
        // No temp files left behind.
        let leftovers = fs::read_dir(d.path())
            .unwrap()
            .filter(|e| {
                e.as_ref()
                    .unwrap()
                    .file_name()
                    .to_string_lossy()
                    .contains(".tmp.")
            })
            .count();
        assert_eq!(leftovers, 0);
    }

    #[test]
    fn unchanged_upsert_does_not_rewrite_but_a_change_does() {
        let d = tempfile::tempdir().unwrap();
        let p = d.path().join("install_index.json");
        InstallIndex::upsert_and_save(&p, "u", &pack("A", "1"));
        let before = fs::read_to_string(&p).unwrap();
        std::thread::sleep(Duration::from_millis(20));
        InstallIndex::upsert_and_save(&p, "u", &pack("A", "1"));
        assert_eq!(fs::read_to_string(&p).unwrap(), before, "untouched");
        InstallIndex::upsert_and_save(&p, "u", &pack("A", "2"));
        assert!(
            fs::read_to_string(&p)
                .unwrap()
                .contains("\"packVersion\":\"2\"")
        );
    }

    #[test]
    fn stale_temp_files_are_reaped() {
        let d = tempfile::tempdir().unwrap();
        let p = d.path().join("install_index.json");
        let stale = d.path().join("install_index.json.tmp.99");
        fs::write(&stale, "x").unwrap();
        let f = fs::File::options().write(true).open(&stale).unwrap();
        f.set_modified(SystemTime::now() - Duration::from_secs(600))
            .unwrap();
        drop(f);
        let fresh = d.path().join("install_index.json.tmp.100");
        fs::write(&fresh, "x").unwrap();
        let _ = InstallIndex::load(&p);
        assert!(!stale.exists());
        assert!(fresh.exists());
    }
}

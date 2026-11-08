// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Per-pack sidecar files (Java `PackDotFile` and `ServerFavoritesStore`).
//!
//! Every text sidecar goes through [`PackDotFile`], which holds the canonical per-path lock
//! (`mmcl_core::filelock`) so a read-modify-write is never interleaved within the process.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use mmcl_core::filelock::file_lock;

/// `<packRoot>/.installed_version`.
pub const INSTALLED_VERSION_FILE: &str = ".installed_version";
/// `<packRoot>/.launch_history`.
pub const LAUNCH_HISTORY_FILE: &str = ".launch_history";
/// `<packRoot>/.seen_news`.
pub const SEEN_NEWS_FILE: &str = ".seen_news";

/// Locked access to small text files in a pack folder.
pub struct PackDotFile;

impl PackDotFile {
    fn path(pack_root: &Path, name: &str) -> PathBuf {
        pack_root.join(name)
    }

    /// The file's text, or `None` when absent or unreadable.
    pub fn read(pack_root: &Path, name: &str) -> Option<String> {
        let path = Self::path(pack_root, name);
        let lock = file_lock(&path);
        let _g = lock.lock();
        fs::read_to_string(&path).ok()
    }

    /// The file's lines (`\n` or `\r\n`), empty when absent or unreadable.
    pub fn read_lines(pack_root: &Path, name: &str) -> Vec<String> {
        Self::read(pack_root, name)
            .map(|t| t.lines().map(str::to_string).collect())
            .unwrap_or_default()
    }

    /// Writes `content` (UTF-8), creating the pack folder.
    pub fn write(pack_root: &Path, name: &str, content: &str) -> io::Result<()> {
        let path = Self::path(pack_root, name);
        let lock = file_lock(&path);
        let _g = lock.lock();
        fs::create_dir_all(pack_root)?;
        fs::write(&path, content)
    }

    /// Reads, transforms and writes the file under one lock. `f` receives the current text
    /// (`None` when absent) and returns the new text, which is also returned.
    pub fn modify(
        pack_root: &Path,
        name: &str,
        f: impl FnOnce(Option<String>) -> String,
    ) -> io::Result<String> {
        let path = Self::path(pack_root, name);
        let lock = file_lock(&path);
        let _g = lock.lock();
        let current = fs::read_to_string(&path).ok();
        let updated = f(current);
        fs::create_dir_all(pack_root)?;
        fs::write(&path, &updated)?;
        Ok(updated)
    }
}

/// Server favourites (`<packRoot>/server-favorites.json`).
pub mod servers {
    use super::*;
    use mmcl_i18n::tf;
    use mmcl_manifest::ServerFavorite;
    use serde_json::{Map, Value};

    /// The favourites file name.
    pub const FILE_NAME: &str = "server-favorites.json";

    fn read_root(pack_root: &Path) -> Option<Map<String, Value>> {
        let text = PackDotFile::read(pack_root, FILE_NAME)?;
        match mmcl_manifest::gson::parse_lenient(&text) {
            Ok((Value::Object(o), _)) => Some(o),
            _ => None,
        }
    }

    /// `disableDefaultServer` is set.
    pub fn is_default_server_disabled(pack_root: &Path) -> bool {
        read_root(pack_root)
            .and_then(|o| {
                o.get("disableDefaultServer")
                    .map(mmcl_manifest::lenient::coerce_bool)
            })
            .unwrap_or(false)
    }

    /// Sets `disableDefaultServer`, keeping the favourites.
    pub fn set_default_server_disabled(pack_root: &Path, disabled: bool) -> io::Result<()> {
        let existing = load(pack_root, "");
        write_all(pack_root, &existing, disabled)
    }

    /// The favourites. Missing file, bad JSON, no `favorites` array: empty. Non-objects and
    /// blank hosts are skipped; a missing or out-of-range port is 25565; a blank name is the
    /// host. `pack_name` only labels the warning on a read failure.
    pub fn load(pack_root: &Path, pack_name: &str) -> Vec<ServerFavorite> {
        let path = pack_root.join(FILE_NAME);
        if !path.is_file() {
            return Vec::new();
        }
        let Some(text) = PackDotFile::read(pack_root, FILE_NAME) else {
            return Vec::new();
        };
        let root = match mmcl_manifest::gson::parse_lenient(&text) {
            Ok((Value::Object(o), _)) => o,
            Ok(_) => return Vec::new(),
            Err(e) => {
                mmcl_log::warn_silent(&tf!(
                    "log.serverFavorites.readFailed",
                    pack_name,
                    e.to_string()
                ));
                return Vec::new();
            }
        };
        let Some(Value::Array(arr)) = root.get("favorites") else {
            return Vec::new();
        };
        arr.iter()
            .filter_map(Value::as_object)
            .filter_map(|o| {
                let s = |k: &str| match o.get(k) {
                    Some(v @ (Value::String(_) | Value::Number(_) | Value::Bool(_))) => {
                        mmcl_manifest::lenient::coerce_string(v)
                    }
                    _ => None,
                };
                let host = s("host").filter(|h| !h.trim().is_empty())?;
                let port = o
                    .get("port")
                    .and_then(mmcl_manifest::lenient::coerce_i32)
                    .filter(|p| (1..=65535).contains(p))
                    .map_or(ServerFavorite::DEFAULT_PORT, |p| p as u16);
                let name = s("name")
                    .filter(|n| !n.trim().is_empty())
                    .unwrap_or_else(|| host.clone());
                Some(ServerFavorite::new(name, host, port))
            })
            .collect()
    }

    /// Rewrites the favourites, keeping the current `disableDefaultServer` flag.
    pub fn save(pack_root: &Path, favorites: &[ServerFavorite]) -> io::Result<()> {
        write_all(pack_root, favorites, is_default_server_disabled(pack_root))
    }

    fn write_all(pack_root: &Path, favorites: &[ServerFavorite], disabled: bool) -> io::Result<()> {
        let arr: Vec<Value> = favorites
            .iter()
            .filter(|f| !f.host.trim().is_empty())
            .map(|f| {
                let mut o = Map::new();
                o.insert("name".into(), Value::from(f.name.clone()));
                o.insert("host".into(), Value::from(f.host.clone()));
                o.insert("port".into(), Value::from(f.port));
                Value::Object(o)
            })
            .collect();
        let mut root = Map::new();
        root.insert("favorites".into(), Value::Array(arr));
        if disabled {
            root.insert("disableDefaultServer".into(), Value::Bool(true));
        }
        PackDotFile::write(
            pack_root,
            FILE_NAME,
            &mmcl_manifest::gson::to_compact_string(&Value::Object(root)),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_manifest::ServerFavorite;

    // ---- PackDotFileTest ----

    #[test]
    fn absent_reads_are_empty() {
        let d = tempfile::tempdir().unwrap();
        assert!(PackDotFile::read(d.path(), ".x").is_none());
        assert!(PackDotFile::read_lines(d.path(), ".x").is_empty());
    }

    #[test]
    fn write_read_round_trip_and_root_creation() {
        let d = tempfile::tempdir().unwrap();
        let root = d.path().join("installs/Pack");
        PackDotFile::write(&root, ".x", "a\nb\r\nc").unwrap();
        assert_eq!(PackDotFile::read(&root, ".x").unwrap(), "a\nb\r\nc");
        assert_eq!(PackDotFile::read_lines(&root, ".x"), ["a", "b", "c"]);
    }

    #[test]
    fn modify_sees_existing_or_none() {
        let d = tempfile::tempdir().unwrap();
        let out = PackDotFile::modify(d.path(), ".y", |cur| {
            assert!(cur.is_none());
            "1".into()
        })
        .unwrap();
        assert_eq!(out, "1");
        PackDotFile::modify(d.path(), ".y", |cur| {
            assert_eq!(cur.as_deref(), Some("1"));
            "2".into()
        })
        .unwrap();
        assert_eq!(PackDotFile::read(d.path(), ".y").unwrap(), "2");
    }

    // ---- ServerFavoritesStoreTest ----

    #[test]
    fn favourites_round_trip_and_flag_persistence() {
        let d = tempfile::tempdir().unwrap();
        let r = d.path();
        assert!(servers::load(r, "P").is_empty());
        assert!(!servers::is_default_server_disabled(r));
        let favs = vec![
            ServerFavorite::new("Alto", "alto.example.com", 8585),
            ServerFavorite::new("", "", 1),
        ];
        servers::save(r, &favs).unwrap();
        let text = std::fs::read_to_string(r.join(servers::FILE_NAME)).unwrap();
        assert_eq!(
            text,
            r#"{"favorites":[{"name":"Alto","host":"alto.example.com","port":8585}]}"#
        );
        servers::set_default_server_disabled(r, true).unwrap();
        assert!(servers::is_default_server_disabled(r));
        servers::save(r, &favs[..1]).unwrap();
        assert!(
            servers::is_default_server_disabled(r),
            "flag survives a save"
        );
        assert_eq!(servers::load(r, "P").len(), 1);
        servers::set_default_server_disabled(r, false).unwrap();
        let text = std::fs::read_to_string(r.join(servers::FILE_NAME)).unwrap();
        assert!(!text.contains("disableDefaultServer"));
    }

    #[test]
    fn malformed_and_partial_entries() {
        let d = tempfile::tempdir().unwrap();
        let r = d.path();
        std::fs::write(r.join(servers::FILE_NAME), "{not json").unwrap();
        assert!(servers::load(r, "P").is_empty());
        std::fs::write(r.join(servers::FILE_NAME), "[1,2]").unwrap();
        assert!(servers::load(r, "P").is_empty());
        std::fs::write(
            r.join(servers::FILE_NAME),
            r#"{"favorites":[1,{"host":" "},{"host":"a.b","port":99999},{"host":"c.d","port":"25570","name":" "}]}"#,
        )
        .unwrap();
        let l = servers::load(r, "P");
        assert_eq!(l.len(), 2);
        assert_eq!(l[0], ServerFavorite::new("a.b", "a.b", 25565));
        assert_eq!(l[1], ServerFavorite::new("c.d", "c.d", 25570));
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Importers (Java `game.modpack.import_`). Each one turns a foreign pack into a Mica manifest
//! under `<root>/config/imported-manifests/` and installs it by its `file:` URL, so the rest of
//! the runtime treats imported packs like hosted ones.
//!
//! * [`mmcjson`]: a `.mmcjson` file, copied verbatim (unknown keys survive).
//! * [`mrpack`]: a Modrinth `.mrpack` (bounded download, file lists by path prefix, loader
//!   installer URL and hash, project icon staged, `overrides/` extracted).
//! * [`zip`]: a Mica export ZIP; Technic server ZIPs are delegated to [`technic`].
//! * [`prism`]: a MultiMC / Prism instance folder (user-owned content, sync skipped).
//! * [`technic`]: a Technic server ZIP (mods become local-only entries).
//! * [`classifier`] / [`modrinth`]: pasted-URL classification and the Modrinth preview API.
//!
//! Every archive is extracted with Zip-Slip checks and decompression-bomb caps counted on the
//! bytes actually read ([`crate::ziputil::extract_bounded`]).

pub mod classifier;
pub mod mmcjson;
pub mod modrinth;
pub mod mrpack;
pub mod prism;
pub mod technic;
pub mod zip;

use std::fs;
use std::path::PathBuf;
use std::sync::Arc;

use mmcl_core::DataPaths;
use mmcl_i18n::tf;

pub use classifier::{Classification, ImportSource, classify};

use crate::error::{ModpackError, Result};
use crate::manager::ModPackManager;

/// The folder (under `config/`) imported manifests are written to.
pub const IMPORTED_MANIFESTS_DIR: &str = mmcl_core::paths::IMPORTED_MANIFESTS_FOLDER;

/// `[^A-Za-z0-9._-]` replaced by `_`; blank or empty input gives `fallback`.
pub fn sanitize_file_part(raw: Option<&str>, fallback: &str) -> String {
    let Some(raw) = raw.filter(|r| !r.trim().is_empty()) else {
        return fallback.to_string();
    };
    let s: String = raw
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
                c
            } else {
                '_'
            }
        })
        .collect();
    if s.is_empty() {
        fallback.to_string()
    } else {
        s
    }
}

/// ASCII letters and digits only (a pack folder name).
pub(crate) fn alnum(s: &str) -> String {
    s.chars().filter(char::is_ascii_alphanumeric).collect()
}

/// Writes `body` as `imported-manifests/<file_name>` and returns its path.
pub(crate) fn write_imported_manifest(
    paths: &DataPaths,
    file_name: &str,
    body: &str,
) -> std::io::Result<PathBuf> {
    let dir = paths.imported_manifests_dir();
    fs::create_dir_all(&dir)?;
    let path = dir.join(file_name);
    fs::write(&path, body)?;
    Ok(path)
}

/// Installs an imported manifest by URL, wrapping a failure in `importer.error.registerFailed`.
pub(crate) async fn register(
    manager: &Arc<ModPackManager>,
    url: &str,
) -> Result<Arc<crate::GameModPack>> {
    let pack = manager
        .install_by_url(url)
        .await
        .map_err(|e| ModpackError::Import(tf!("importer.error.registerFailed", e.to_string())))?;
    // An import replaces the folder's content, so whatever an earlier install recorded as
    // verified and scanned no longer describes it: the first launch hashes and scans in full.
    if let Ok(root) = pack.checked_pack_root() {
        let _ = fs::remove_file(root.join(crate::verify_state::VERIFY_STATE_FILE));
    }
    Ok(pack)
}

#[cfg(test)]
pub(crate) mod test_support {
    use std::io::Write;
    use std::path::Path;
    use std::sync::Arc;

    use mmcl_config::{Config, ConfigStore, Document};
    use mmcl_core::{DataPaths, GameMode};

    use crate::http::fake::FakeHttp;
    use crate::manager::ModPackManager;

    pub fn manager(root: &Path) -> (Arc<ModPackManager>, Arc<FakeHttp>) {
        let http = FakeHttp::new();
        // Offline: imported `file:` manifests need no network, and nothing else is fetched.
        http.set_offline(true);
        let paths = Arc::new(DataPaths::at_root(root, GameMode::Client));
        let cfg = Config::new(ConfigStore::in_memory(Document::new()));
        (ModPackManager::new(paths, http.clone(), cfg), http)
    }

    pub fn write_zip(path: &Path, entries: &[(&str, &[u8])]) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let mut z = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        for (n, b) in entries {
            if n.ends_with('/') {
                z.add_directory(*n, zip::write::SimpleFileOptions::default())
                    .unwrap();
            } else {
                z.start_file(*n, zip::write::SimpleFileOptions::default())
                    .unwrap();
                z.write_all(b).unwrap();
            }
        }
        z.finish().unwrap();
    }

    pub fn zip_bytes(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut z = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        for (n, b) in entries {
            z.start_file(*n, zip::write::SimpleFileOptions::default())
                .unwrap();
            z.write_all(b).unwrap();
        }
        z.finish().unwrap().into_inner()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize_rules() {
        assert_eq!(sanitize_file_part(Some("My Pack!"), "imported"), "My_Pack_");
        assert_eq!(sanitize_file_part(Some("  "), "imported"), "imported");
        assert_eq!(sanitize_file_part(None, "unknown"), "unknown");
        assert_eq!(sanitize_file_part(Some("a.b-c_d"), "x"), "a.b-c_d");
        assert_eq!(alnum("Héllo World 2"), "HlloWorld2");
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Mica export ZIP import (Java `ModpackZipImporter`): requires `mica-export.json` with a
//! `mica-export-v*` format and the embedded `manifest.json`; extracts the folder into the pack
//! root and installs the manifest. A ZIP without the marker that looks like a Technic server
//! pack is handed to [`super::technic`].

use std::fs::File;
use std::io::Read;
use std::path::Path;
use std::sync::Arc;

use mmcl_i18n::{t, tf};
use serde_json::Value;

use super::{alnum, register, sanitize_file_part, write_imported_manifest};
use crate::error::{ModpackError, Result};
use crate::export::{EXPORT_FORMAT_V2, MANIFEST_FILENAME, MARKER_FILENAME};
use crate::manager::ModPackManager;

fn read_entry<R: Read + std::io::Seek>(
    zip: &mut ::zip::ZipArchive<R>,
    name: &str,
) -> Option<std::io::Result<String>> {
    let mut f = zip.by_name(name).ok()?;
    let mut s = String::new();
    // Capped like a downloaded manifest: a decompression bomb must not exhaust memory.
    Some(
        (&mut f)
            .take(mmcl_net::MANIFEST_MAX_BYTES)
            .read_to_string(&mut s)
            .map(|_| s),
    )
}

/// Imports and installs a Mica export (or Technic server) ZIP. Returns the manifest `file:` URL.
pub async fn import_zip(manager: &Arc<ModPackManager>, zip_file: &Path) -> Result<String> {
    if !zip_file.is_file() {
        return Err(ModpackError::Import(
            t!("importer.error.pickZipFile").into_owned(),
        ));
    }
    let read_err = |e: &dyn std::fmt::Display| {
        ModpackError::Import(tf!("importer.error.readZipFailed", e.to_string()))
    };
    let mut zip = ::zip::ZipArchive::new(File::open(zip_file).map_err(|e| read_err(&e))?)
        .map_err(|e| read_err(&e))?;
    let Some(marker) = read_entry(&mut zip, MARKER_FILENAME) else {
        if super::technic::looks_like_technic_server_zip(&mut zip) {
            mmcl_log::std(&t!("log.zipImporter.delegatingTechnic"));
            drop(zip);
            return super::technic::import_zip(manager, zip_file).await;
        }
        return Err(ModpackError::Import(tf!(
            "zipImporter.error.notMicaExport",
            MARKER_FILENAME
        )));
    };
    let marker = marker.map_err(|e| read_err(&e))?;
    let format = match mmcl_manifest::gson::parse_lenient(&marker) {
        Ok((Value::Object(o), _)) => o
            .get("format")
            .and_then(mmcl_manifest::lenient::coerce_string),
        Ok(_) => None,
        Err(e) => {
            return Err(ModpackError::Import(tf!(
                "zipImporter.error.markerCorrupt",
                e.to_string()
            )));
        }
    };
    if !format
        .as_deref()
        .is_some_and(|f| f.starts_with("mica-export-v"))
    {
        return Err(ModpackError::Import(tf!(
            "zipImporter.error.unknownFormat",
            format.unwrap_or_else(|| "null".into()),
            EXPORT_FORMAT_V2
        )));
    }
    let body = read_entry(&mut zip, MANIFEST_FILENAME)
        .ok_or_else(|| ModpackError::Import(t!("zipImporter.error.noManifest").into_owned()))?
        .map_err(|e| read_err(&e))?;
    let name = match mmcl_manifest::gson::parse_lenient(&body) {
        Ok((Value::Object(o), _)) => o
            .get("packName")
            .and_then(mmcl_manifest::lenient::coerce_string),
        Ok(_) => None,
        Err(e) => {
            return Err(ModpackError::Import(tf!(
                "zipImporter.error.manifestCorrupt",
                e.to_string()
            )));
        }
    }
    .filter(|n| !n.trim().is_empty())
    .ok_or_else(|| ModpackError::Import(t!("zipImporter.error.noPackName").into_owned()))?;
    // The install folder must be the one the manifest's name resolves to, or the pack would
    // launch from a different (empty) folder.
    let folder = alnum(&name);
    if folder.is_empty() {
        return Err(ModpackError::EmptyFolderName(name));
    }
    let paths = manager.paths().clone();
    let install = paths.pack_install_dir(&folder);
    let target = install.clone();
    crate::managed::blocking(move || {
        crate::ziputil::extract_bounded(
            &mut zip,
            &target,
            |n| n == MARKER_FILENAME || n == MANIFEST_FILENAME,
            |_| {},
        )
    })
    .await?
    .map_err(|e| read_err(&e))?;
    let path = write_imported_manifest(
        &paths,
        &format!("zip-{}.json", sanitize_file_part(Some(&folder), "unknown")),
        &body,
    )
    .map_err(|e| read_err(&e))?;
    mmcl_log::std(&tf!(
        "log.zipImporter.wroteManifest",
        path.display().to_string(),
        install.display().to_string()
    ));
    let url = crate::fetch::path_to_file_url(&path);
    register(manager, &url).await?;
    Ok(url)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::test_support::{manager, write_zip};
    use std::fs;

    #[tokio::test]
    async fn export_import_round_trip() {
        let d = tempfile::tempdir().unwrap();
        let (m, _h) = manager(&d.path().join("root"));
        let z = d.path().join("e.zip");
        let body = r#"{"packName":"Round Trip","packVersion":"3","packMods":[]}"#;
        write_zip(
            &z,
            &[
                (MARKER_FILENAME, br#"{"format":"mica-export-v2"}"#),
                (MANIFEST_FILENAME, body.as_bytes()),
                ("config/a.cfg", b"a"),
                ("saves/W/", b""),
            ],
        );
        let url = import_zip(&m, &z).await.unwrap();
        let root = m.paths().pack_install_dir("RoundTrip");
        assert_eq!(fs::read(root.join("config/a.cfg")).unwrap(), b"a");
        assert!(root.join("saves/W").is_dir());
        assert!(!root.join(MARKER_FILENAME).exists());
        assert!(
            crate::fetch::file_url_to_path(&url)
                .unwrap()
                .ends_with("zip-RoundTrip.json")
        );
        assert!(m.installed_by_url(&url).is_some());
    }

    #[tokio::test]
    async fn a_forged_or_stale_verify_state_never_survives_an_import() {
        let d = tempfile::tempdir().unwrap();
        let (m, _h) = manager(&d.path().join("root"));
        let root = m.paths().pack_install_dir("Forged");
        // An earlier install's state in the same folder.
        fs::create_dir_all(&root).unwrap();
        fs::write(
            root.join(crate::verify_state::VERIFY_STATE_FILE),
            r#"{"lastScannedAt":1}"#,
        )
        .unwrap();
        let z = d.path().join("e.zip");
        write_zip(
            &z,
            &[
                (MARKER_FILENAME, br#"{"format":"mica-export-v2"}"#),
                (MANIFEST_FILENAME, br#"{"packName":"Forged","packMods":[]}"#),
                (
                    ".verify_state.json",
                    br#"{"lastScannedAt":99999999999999,"verifiedAt":99999999999999}"#,
                ),
                ("mods/evil.jar", b"x"),
            ],
        );
        import_zip(&m, &z).await.unwrap();
        assert!(root.join("mods/evil.jar").is_file());
        assert!(
            !root.join(crate::verify_state::VERIFY_STATE_FILE).exists(),
            "the first launch must verify and scan in full"
        );
    }

    #[tokio::test]
    async fn rejects_bad_exports() {
        let d = tempfile::tempdir().unwrap();
        let (m, _h) = manager(&d.path().join("root"));
        let z = d.path().join("e.zip");
        write_zip(&z, &[("random.txt", b"x")]);
        assert!(import_zip(&m, &z).await.is_err());
        write_zip(
            &z,
            &[
                (MARKER_FILENAME, br#"{"format":"other"}"#),
                (MANIFEST_FILENAME, b"{}"),
            ],
        );
        assert!(import_zip(&m, &z).await.is_err());
        write_zip(&z, &[(MARKER_FILENAME, br#"{"format":"mica-export-v2"}"#)]);
        assert!(import_zip(&m, &z).await.is_err());
        write_zip(
            &z,
            &[
                (MARKER_FILENAME, br#"{"format":"mica-export-v2"}"#),
                (MANIFEST_FILENAME, br#"{"packName":"ok"}"#),
                ("../../x", b"x"),
            ],
        );
        assert!(import_zip(&m, &z).await.is_err());
        assert!(import_zip(&m, &d.path().join("missing.zip")).await.is_err());
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `.mmcjson` import (Java `MmcjsonImporter`): validate, copy verbatim, install.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use mmcl_core::DataPaths;
use mmcl_i18n::{t, tf};
use serde_json::Value;

use super::{register, sanitize_file_part, write_imported_manifest};
use crate::error::{ModpackError, Result};
use crate::manager::ModPackManager;

/// Largest accepted manifest file.
pub const MAX_MANIFEST_BYTES: u64 = 50 * 1024 * 1024;

/// Validates `file` and copies it to `imported-manifests/mmcjson-<name>.json` byte for byte.
/// Returns the written path.
pub fn stage(paths: &DataPaths, file: &Path) -> Result<PathBuf> {
    let meta = fs::metadata(file)
        .ok()
        .filter(|m| m.is_file())
        .ok_or_else(|| ModpackError::Import(t!("mmcjsonImporter.error.pickFile").into_owned()))?;
    if meta.len() > MAX_MANIFEST_BYTES {
        return Err(ModpackError::Import(tf!(
            "mmcjsonImporter.error.tooLarge",
            meta.len().to_string()
        )));
    }
    let body = fs::read_to_string(file).map_err(|e| {
        ModpackError::Import(tf!("mmcjsonImporter.error.readFailed", e.to_string()))
    })?;
    let obj = match mmcl_manifest::gson::parse_lenient(&body) {
        Ok((Value::Object(o), _)) => o,
        _ => {
            return Err(ModpackError::Import(
                t!("mmcjsonImporter.error.invalidJson").into_owned(),
            ));
        }
    };
    let name = match obj.get("packName") {
        Some(v @ (Value::String(_) | Value::Number(_) | Value::Bool(_))) => {
            mmcl_manifest::lenient::coerce_string(v)
        }
        _ => None,
    }
    .filter(|n| !n.trim().is_empty())
    .ok_or_else(|| ModpackError::Import(t!("mmcjsonImporter.error.noPackName").into_owned()))?;
    let path = write_imported_manifest(
        paths,
        &format!(
            "mmcjson-{}.json",
            sanitize_file_part(Some(&name), "imported")
        ),
        &body,
    )
    .map_err(|e| ModpackError::Import(tf!("mmcjsonImporter.error.writeFailed", e.to_string())))?;
    mmcl_log::std(&tf!(
        "log.mmcjsonImporter.wroteManifest",
        path.display().to_string(),
        &name
    ));
    Ok(path)
}

/// Imports and installs a `.mmcjson` file. Returns its `file:` URL.
pub async fn import_mmcjson_file(manager: &Arc<ModPackManager>, file: &Path) -> Result<String> {
    let path = stage(manager.paths(), file)?;
    let url = crate::fetch::path_to_file_url(&path);
    register(manager, &url).await?;
    Ok(url)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::test_support::manager;

    #[tokio::test]
    async fn imports_verbatim_and_installs() {
        let d = tempfile::tempdir().unwrap();
        let (m, _http) = manager(&d.path().join("root"));
        let src = d.path().join("pack.mmcjson");
        let body =
            r#"{"packName":"My Pack","packVersion":"1.0","futureKey":{"x":1},"packMods":[]}"#;
        fs::write(&src, body).unwrap();
        let url = import_mmcjson_file(&m, &src).await.unwrap();
        let written = crate::fetch::file_url_to_path(&url).unwrap();
        assert!(written.ends_with("mmcjson-My_Pack.json"));
        assert_eq!(fs::read_to_string(&written).unwrap(), body);
        assert!(m.installed_by_url(&url).is_some());
        assert_eq!(m.config().installed_mod_packs(), [url]);
    }

    #[test]
    fn rejects_bad_input() {
        let d = tempfile::tempdir().unwrap();
        let paths = DataPaths::at_root(d.path(), mmcl_core::GameMode::Client);
        assert!(stage(&paths, &d.path().join("missing.mmcjson")).is_err());
        let f = d.path().join("x.mmcjson");
        fs::write(&f, "[1,2]").unwrap();
        assert!(stage(&paths, &f).is_err());
        fs::write(&f, r#"{"packName":"  "}"#).unwrap();
        assert!(stage(&paths, &f).is_err());
        fs::write(&f, r#"{"packName":{"a":1}}"#).unwrap();
        assert!(stage(&paths, &f).is_err());
    }
}

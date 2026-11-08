// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Modpack export (Java `ModpackExporter`): share the manifest URL, share the manifest JSON, or
//! export the install folder as a ZIP that [`crate::import::zip`] reads back.

use std::collections::HashSet;
use std::fs::{self, File};
use std::io::{self, BufWriter};
use std::path::Path;

use mmcl_i18n::{t, tf};
use serde_json::Value;
use walkdir::WalkDir;
use zip::ZipWriter;

use crate::fetch::ManifestFetcher;
use crate::pack::GameModPack;
use crate::ziputil;

/// The marker file inside an export ZIP.
pub const MARKER_FILENAME: &str = "mica-export.json";
/// The manifest body inside an export ZIP.
pub const MANIFEST_FILENAME: &str = "manifest.json";
/// The current export format.
pub const EXPORT_FORMAT_V2: &str = "mica-export-v2";

/// Top-level folders never exported (re-downloaded or private).
pub const DEFAULT_EXCLUSIONS: [&str; 8] = [
    "libraries",
    "bin",
    "assets",
    "runtime",
    "logs",
    "crash-reports",
    "natives",
    "versions",
];
/// The worlds folder, exported only when asked.
pub const WORLDS_FOLDER: &str = "saves";

/// How a pack can be shared.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ExportMode {
    /// The manifest is hosted: share its URL.
    ShareUrl,
    /// A local manifest whose mods all download from the web: share the JSON.
    ShareManifestJson,
    /// Anything else: export the folder.
    ExportZip,
}

/// `true` when the manifest has no `packMods` or every entry's `remote` is `http(s)`.
pub fn all_pack_mods_remote_http(manifest_json: &str) -> bool {
    let o = match mmcl_manifest::gson::parse_lenient(manifest_json) {
        Ok((Value::Object(o), _)) => o,
        other => {
            let detail = match other {
                Err(e) => e.to_string(),
                Ok(_) => t!("modpack.error.notJsonObject").into_owned(),
            };
            mmcl_log::warn_silent(&tf!(
                "log.modpackExporter.classifyParseFailed",
                "JSON",
                detail
            ));
            return false;
        }
    };
    let Some(Value::Array(mods)) = o.get("packMods") else {
        return true;
    };
    mods.iter().all(|m| {
        let Some(r) = m
            .as_object()
            .and_then(|o| o.get("remote"))
            .and_then(mmcl_manifest::lenient::coerce_string)
        else {
            return false;
        };
        let l = r.trim().to_ascii_lowercase();
        !l.is_empty() && (l.starts_with("http://") || l.starts_with("https://"))
    })
}

/// Picks the export mode for `pack`.
pub fn classify_export(pack: &GameModPack, fetcher: &ManifestFetcher) -> ExportMode {
    let Some(url) = pack.manifest_url() else {
        return ExportMode::ExportZip;
    };
    if url.starts_with("http://") || url.starts_with("https://") {
        return ExportMode::ShareUrl;
    }
    if url.starts_with("file:")
        && let Some(body) = fetcher.load_manifest_text(url)
        && all_pack_mods_remote_http(&body)
    {
        return ExportMode::ShareManifestJson;
    }
    ExportMode::ExportZip
}

/// A JSON string literal (every control character escaped), or `null`.
fn json_string(s: Option<&str>) -> String {
    match s {
        None => "null".into(),
        Some(s) => Value::String(s.to_string()).to_string(),
    }
}

/// The `mica-export.json` marker text.
pub fn marker_json(pack: &GameModPack, worlds_included: bool, exported_at_ms: i64) -> String {
    let mc = pack.minecraft_version_hint();
    let loader = pack.mod_loader_type();
    format!(
        "{{\n  \"format\": \"{EXPORT_FORMAT_V2}\",\n  \"packName\": {},\n  \"packVersion\": {},\n  \"minecraftVersion\": {},\n  \"loader\": {},\n  \"worldsIncluded\": {worlds_included},\n  \"exportedAtMs\": {exported_at_ms}\n}}\n",
        json_string(pack.manifest().pack_name.as_deref()),
        json_string(pack.manifest().pack_version.as_deref()),
        json_string(mc.as_deref()),
        json_string(loader.as_deref()),
    )
}

/// Exports the pack folder (minus [`DEFAULT_EXCLUSIONS`], and `saves` unless asked) with the
/// marker and the manifest body. `progress` receives the bytes written so far.
pub fn export_to_zip(
    pack: &GameModPack,
    fetcher: &ManifestFetcher,
    dest: &Path,
    include_saves: bool,
    progress: impl FnMut(u64),
) -> io::Result<()> {
    let mut created = false;
    let res = export_to_zip_inner(pack, fetcher, dest, include_saves, progress, &mut created);
    // Never leave a truncated archive that looks like a finished export.
    if res.is_err() && created {
        let _ = fs::remove_file(dest);
    }
    res
}

fn export_to_zip_inner(
    pack: &GameModPack,
    fetcher: &ManifestFetcher,
    dest: &Path,
    include_saves: bool,
    mut progress: impl FnMut(u64),
    created: &mut bool,
) -> io::Result<()> {
    let root = pack
        .checked_pack_root()
        .map_err(|_| io::Error::other(t!("detailModal.export.error.noInstallFolder")))?;
    if !root.is_dir() {
        return Err(io::Error::other(tf!(
            "detailModal.export.error.installFolderMissing",
            root.display().to_string()
        )));
    }
    let mut excludes: HashSet<&str> = DEFAULT_EXCLUSIONS.into_iter().collect();
    if !include_saves {
        excludes.insert(WORLDS_FOLDER);
    }
    if let Some(p) = dest.parent() {
        fs::create_dir_all(p)?;
    }
    let dest_abs = crate::managed::abs_norm(dest);
    let file = File::create(dest)?;
    *created = true;
    let mut zip = ZipWriter::new(BufWriter::new(file));
    ziputil::add_bytes(
        &mut zip,
        MARKER_FILENAME,
        marker_json(pack, include_saves, chrono::Utc::now().timestamp_millis()).as_bytes(),
    )?;
    match pack
        .manifest_url()
        .and_then(|u| fetcher.load_manifest_text(u))
        .filter(|b| !b.trim().is_empty())
    {
        Some(body) => ziputil::add_bytes(&mut zip, MANIFEST_FILENAME, body.as_bytes())?,
        None => mmcl_log::warn_silent(&tf!(
            "log.modpackExporter.noManifestBody",
            pack.name(),
            MANIFEST_FILENAME
        )),
    }
    let mut written = 0u64;
    let walker = WalkDir::new(&root)
        .min_depth(1)
        .sort_by_file_name()
        .into_iter()
        .filter_entry(|e| {
            if e.depth() == 1 && e.file_type().is_dir() {
                return !excludes.contains(e.file_name().to_string_lossy().as_ref());
            }
            true
        });
    for entry in walker {
        let entry = match entry {
            Ok(e) => e,
            Err(e) => {
                mmcl_log::warn_silent(&tf!(
                    "log.modpackExporter.skipUnreadable",
                    e.path()
                        .map(|p| p.display().to_string())
                        .unwrap_or_default(),
                    e.to_string()
                ));
                continue;
            }
        };
        if !entry.file_type().is_file() || crate::managed::abs_norm(entry.path()) == dest_abs {
            continue;
        }
        let rel = entry.path().strip_prefix(&root).unwrap_or(entry.path());
        let name = rel.to_string_lossy().replace('\\', "/");
        // A file that cannot be opened (locked by a running game, no permission) is skipped
        // before its entry starts; a failure mid-copy would leave a truncated entry, so it
        // aborts the export instead.
        let file = match File::open(entry.path()) {
            Ok(f) => f,
            Err(e) => {
                mmcl_log::warn_silent(&tf!(
                    "log.modpackExporter.skipUnreadable",
                    entry.path().display().to_string(),
                    e.to_string()
                ));
                continue;
            }
        };
        written += ziputil::add_open_file(&mut zip, &name, file)?;
        progress(written);
    }
    zip.finish().map_err(io::Error::other)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::fake::FakeHttp;
    use mmcl_core::{DataPaths, GameMode};
    use std::sync::Arc;

    #[test]
    fn classify_rules() {
        assert!(all_pack_mods_remote_http(r#"{"packName":"x"}"#));
        assert!(all_pack_mods_remote_http(
            r#"{"packMods":[{"remote":"HTTPS://a/b.jar"}]}"#
        ));
        assert!(!all_pack_mods_remote_http(
            r#"{"packMods":[{"remote":""}]}"#
        ));
        assert!(!all_pack_mods_remote_http(r#"{"packMods":[1]}"#));
        assert!(!all_pack_mods_remote_http(
            r#"{"packMods":[{"remote":"jar:file:/x!/y"}]}"#
        ));
        assert!(!all_pack_mods_remote_http("garbage"));

        let d = tempfile::tempdir().unwrap();
        let paths = Arc::new(DataPaths::at_root(d.path(), GameMode::Client));
        let f = ManifestFetcher::new(paths.clone(), FakeHttp::new());
        let m = mmcl_manifest::PackManifest {
            pack_name: Some("X".into()),
            ..Default::default()
        };
        let hosted = GameModPack::from_manifest(
            paths.clone(),
            m.clone(),
            Some("https://h/m.json".into()),
            None,
        );
        assert_eq!(classify_export(&hosted, &f), ExportMode::ShareUrl);
        let mf = d.path().join("m.json");
        fs::write(
            &mf,
            r#"{"packName":"X","packMods":[{"remote":"https://a/b.jar"}]}"#,
        )
        .unwrap();
        let local = GameModPack::from_manifest(
            paths.clone(),
            m.clone(),
            Some(crate::fetch::path_to_file_url(&mf)),
            None,
        );
        assert_eq!(classify_export(&local, &f), ExportMode::ShareManifestJson);
        fs::write(&mf, r#"{"packName":"X","packMods":[{"remote":""}]}"#).unwrap();
        assert_eq!(classify_export(&local, &f), ExportMode::ExportZip);
        let none = GameModPack::from_manifest(paths, m, None, None);
        assert_eq!(classify_export(&none, &f), ExportMode::ExportZip);
    }

    #[test]
    fn zip_export_round_trip_shape() {
        let d = tempfile::tempdir().unwrap();
        let paths = Arc::new(DataPaths::at_root(d.path(), GameMode::Client));
        let f = ManifestFetcher::new(paths.clone(), FakeHttp::new());
        let mf = d.path().join("m.json");
        let body = r#"{"packName":"Exp \"Q\"","packVersion":"1"}"#;
        fs::write(&mf, body).unwrap();
        let m = mmcl_manifest::PackManifest::parse(body).unwrap();
        let p =
            GameModPack::from_manifest(paths, m, Some(crate::fetch::path_to_file_url(&mf)), None);
        let root = p.pack_root();
        for (rel, data) in [
            ("mods/a.jar", "a"),
            ("config/c.cfg", "c"),
            ("bin/modpack.jar", "x"),
            ("saves/W/level.dat", "w"),
            ("options.txt", "o"),
        ] {
            fs::create_dir_all(root.join(rel).parent().unwrap()).unwrap();
            fs::write(root.join(rel), data).unwrap();
        }
        let out = d.path().join("out/export.zip");
        let mut last = 0;
        export_to_zip(&p, &f, &out, false, |n| last = n).unwrap();
        assert_eq!(last, 3);
        let mut z = zip::ZipArchive::new(File::open(&out).unwrap()).unwrap();
        let names: Vec<String> = (0..z.len())
            .map(|i| z.by_index(i).unwrap().name().to_string())
            .collect();
        assert_eq!(
            names,
            [
                MARKER_FILENAME,
                MANIFEST_FILENAME,
                "config/c.cfg",
                "mods/a.jar",
                "options.txt"
            ]
        );
        let mut marker = String::new();
        io::Read::read_to_string(&mut z.by_name(MARKER_FILENAME).unwrap(), &mut marker).unwrap();
        let v: Value = serde_json::from_str(&marker).unwrap();
        assert_eq!(v["format"], EXPORT_FORMAT_V2);
        assert_eq!(v["packName"], "Exp \"Q\"");
        assert_eq!(v["loader"], "forge");
        assert_eq!(v["worldsIncluded"], false);
        export_to_zip(&p, &f, &out, true, |_| {}).unwrap();
        let mut z = zip::ZipArchive::new(File::open(&out).unwrap()).unwrap();
        assert!(z.by_name("saves/W/level.dat").is_ok());
    }
}

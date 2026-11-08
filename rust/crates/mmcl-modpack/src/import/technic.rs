// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Technic server ZIP import (Java `TechnicServerZipImporter`).
//!
//! The ZIP is extracted into the pack folder (minus the top-level server jar and launch
//! scripts); every jar directly in `mods/` becomes a local-only `packMods` entry (empty
//! `remote`), and the loader is detected from the server jar's contents.
//!
//! Fixed from Java: the mod entries were written with `local: "mods/<file>"` (resolved as
//! `mods/mods/<file>`) and without side flags (read as "not required"), so the first launch
//! failed on missing files and the floating sweep deleted every extracted mod. Entries now use
//! the bare file name and `clientReq`/`serverReq: true`.

use std::fs::File;
use std::io::{Cursor, Read, Seek};
use std::path::Path;
use std::sync::{Arc, LazyLock};

use mmcl_core::consts::modpack::{MOD_LOADER_FABRIC, MOD_LOADER_FORGE, MOD_LOADER_NEOFORGE};
use mmcl_i18n::{t, tf};
use mmcl_manifest::pack::{BUNDLED_BACKGROUND_URL, BUNDLED_LOGO_URL};
use regex::Regex;
use serde_json::{Map, Value};

use super::{alnum, register, sanitize_file_part, write_imported_manifest};
use crate::error::{ModpackError, Result};
use crate::manager::ModPackManager;

/// Most bytes of the server jar read into memory for loader detection (a decompression bomb
/// named as the server jar must not exhaust memory; real server jars are far smaller).
const MAX_SERVER_JAR_BYTES: u64 = 256 * 1024 * 1024;
/// Most bytes read from a launch script or `fmlversion.properties`.
const MAX_SMALL_ENTRY_BYTES: u64 = 1024 * 1024;

static NAME_VERSION: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^(.+?)[_-]Server[_-](.+)\.zip$").expect("valid regex"));
static JAR_ARG: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?i)-jar\s+([^\s"]+\.jar)"#).expect("valid regex"));

fn names<R: Read + Seek>(zip: &::zip::ZipArchive<R>) -> Vec<String> {
    zip.file_names().map(|n| n.replace('\\', "/")).collect()
}

fn is_top_level_jar(n: &str) -> bool {
    !n.contains('/') && n.to_lowercase().ends_with(".jar")
}

fn is_launch_script(n: &str) -> bool {
    n.eq_ignore_ascii_case("launch.bat") || n.eq_ignore_ascii_case("launch.sh")
}

/// A `mods/` folder, a top-level jar and a `launch.bat` / `launch.sh`.
pub fn looks_like_technic_server_zip<R: Read + Seek>(zip: &mut ::zip::ZipArchive<R>) -> bool {
    let n = names(zip);
    n.iter().any(|x| x.starts_with("mods/"))
        && n.iter().any(|x| is_top_level_jar(x))
        && n.iter().any(|x| is_launch_script(x))
}

/// `(pack name, version)` from `<name>[_-]Server[_-]<version>.zip`, else the base name and
/// `imported`.
pub fn name_and_version(file_name: &str) -> (String, String) {
    if let Some(c) = NAME_VERSION.captures(file_name) {
        return (c[1].replace('_', " ").trim().to_string(), c[2].to_string());
    }
    let base = match file_name.rfind('.') {
        Some(i) if i > 0 => &file_name[..i],
        _ => file_name,
    };
    (base.replace('_', " ").trim().to_string(), "imported".into())
}

/// The server jar named by `-jar <x>.jar` in a launch script.
pub fn server_jar_from_launch_script<R: Read + Seek>(
    zip: &mut ::zip::ZipArchive<R>,
) -> Option<String> {
    for script in ["launch.bat", "launch.sh"] {
        let Ok(mut f) = zip.by_name(script) else {
            continue;
        };
        let mut body = String::new();
        if (&mut f)
            .take(MAX_SMALL_ENTRY_BYTES)
            .read_to_string(&mut body)
            .is_err()
        {
            continue;
        }
        if let Some(c) = JAR_ARG.captures(&body) {
            let j = &c[1];
            return Some(j.rsplit(['/', '\\']).next().unwrap_or(j).to_string());
        }
    }
    None
}

/// What the server jar says about its loader.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LoaderInfo {
    pub loader: &'static str,
    pub mc_version: Option<String>,
}

/// Scans the server jar's entries for loader markers.
pub fn detect_loader(jar_bytes: &[u8]) -> Option<LoaderInfo> {
    let mut z = ::zip::ZipArchive::new(Cursor::new(jar_bytes)).ok()?;
    let (mut neo, mut fabric, mut forge, mut fml) = (false, false, false, false);
    let mut mc = None;
    for i in 0..z.len() {
        let Ok(mut e) = z.by_index(i) else {
            continue;
        };
        let name = e.name().to_string();
        if name == "fmlversion.properties" {
            let mut buf = Vec::new();
            let _ = (&mut e).take(MAX_SMALL_ENTRY_BYTES).read_to_end(&mut buf);
            let props = mmcl_i18n::properties::parse(&mmcl_i18n::properties::decode(&buf));
            mc = props.get("fmlbuild.mcversion").cloned();
            fml = true;
            mmcl_log::std(&tf!(
                "log.technicImporter.foundFmlVersion",
                mc.clone().unwrap_or_else(|| "null".into())
            ));
            continue;
        }
        if name.starts_with("net/neoforged/") {
            neo = true;
        } else if name.starts_with("net/fabricmc/")
            || name == "fabric-server-launch.properties"
            || name == "fabric.mod.json"
        {
            fabric = true;
        } else if name.starts_with("net/minecraftforge/") || name.starts_with("cpw/mods/fml/") {
            forge = true;
        }
    }
    let loader = if neo {
        MOD_LOADER_NEOFORGE
    } else if fabric {
        MOD_LOADER_FABRIC
    } else if forge || fml {
        MOD_LOADER_FORGE
    } else {
        return None;
    };
    Some(LoaderInfo {
        loader,
        mc_version: mc,
    })
}

/// The Mica manifest for a Technic server pack.
pub fn build_manifest(
    name: &str,
    version: &str,
    mods: &[String],
    loader: Option<&LoaderInfo>,
) -> Value {
    let mut m = Map::new();
    m.insert("manifestFormat".into(), 2.into());
    m.insert("packName".into(), name.into());
    m.insert("packVersion".into(), version.into());
    m.insert("packURL".into(), "".into());
    m.insert("packMinRAMGB".into(), "3".into());
    m.insert("packUnstable".into(), false.into());
    m.insert("packCustomDiscordRpc".into(), false.into());
    m.insert("packLogoURL".into(), BUNDLED_LOGO_URL.into());
    m.insert("packBackgroundURL".into(), BUNDLED_BACKGROUND_URL.into());
    m.insert(
        "packModLoader".into(),
        loader.map_or(MOD_LOADER_FORGE, |l| l.loader).into(),
    );
    m.insert("packModLoaderURL".into(), "".into());
    m.insert("packModLoaderHash".into(), "".into());
    m.insert("packForgeURL".into(), "".into());
    m.insert("packForgeHash".into(), "".into());
    m.insert(
        "packMinecraftVersion".into(),
        loader
            .and_then(|l| l.mc_version.clone())
            .unwrap_or_default()
            .into(),
    );
    m.insert("packScanExclusions".into(), Value::Array(Vec::new()));
    let mods: Vec<Value> = mods
        .iter()
        .map(|f| {
            let stem = match f.rfind('.') {
                Some(i) if i > 0 => &f[..i],
                _ => f.as_str(),
            };
            let mut e = Map::new();
            e.insert("name".into(), stem.into());
            e.insert("remote".into(), "".into());
            e.insert("local".into(), f.as_str().into());
            e.insert("clientReq".into(), true.into());
            e.insert("serverReq".into(), true.into());
            Value::Object(e)
        })
        .collect();
    m.insert("packMods".into(), Value::Array(mods));
    for k in [
        "packConfigs",
        "packResourcePacks",
        "packShaderPacks",
        "packInitialFiles",
    ] {
        m.insert(k.into(), Value::Array(Vec::new()));
    }
    Value::Object(m)
}

/// Imports and installs a Technic server ZIP. Returns the manifest `file:` URL.
pub async fn import_zip(manager: &Arc<ModPackManager>, zip_file: &Path) -> Result<String> {
    if !zip_file.is_file() {
        return Err(ModpackError::Import(
            t!("importer.error.pickZipFile").into_owned(),
        ));
    }
    let read_err = |e: &dyn std::fmt::Display| {
        ModpackError::Import(tf!("importer.error.readZipFailed", e.to_string()))
    };
    let file_name = zip_file
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let (mut name, version) = name_and_version(&file_name);
    let mut folder = alnum(&name);
    if folder.is_empty() {
        // Keep the manifest name and the folder in step, or the pack root would be wrong.
        name = "TechnicPack".into();
        folder = name.clone();
    }
    let paths = manager.paths().clone();
    let install = paths.pack_install_dir(&folder);
    let mut zip = ::zip::ZipArchive::new(File::open(zip_file).map_err(|e| read_err(&e))?)
        .map_err(|e| read_err(&e))?;
    let target = install.clone();
    let (zip, mods) = crate::managed::blocking(move || {
        let mut mods = Vec::new();
        let r = crate::ziputil::extract_bounded(
            &mut zip,
            &target,
            |n| is_top_level_jar(n) || is_launch_script(n),
            |n| {
                if let Some(rel) = n.strip_prefix("mods/")
                    && !rel.is_empty()
                    && !rel.contains('/')
                {
                    let l = rel.to_lowercase();
                    if l.ends_with(".jar") || l.ends_with(".zip") {
                        mods.push(rel.to_string());
                    }
                }
            },
        );
        r.map(|_| (zip, mods))
    })
    .await?
    .map_err(|e| read_err(&e))?;
    let mut zip = zip;
    let jar = server_jar_from_launch_script(&mut zip);
    mmcl_log::std(&tf!(
        "log.technicImporter.launchScriptJar",
        jar.clone()
            .unwrap_or_else(|| t!("log.technicImporter.none").into_owned())
    ));
    let jar = jar.or_else(|| {
        let j = names(&zip).into_iter().find(|n| is_top_level_jar(n));
        mmcl_log::std(&tf!(
            "log.technicImporter.fallbackJar",
            j.clone()
                .unwrap_or_else(|| t!("log.technicImporter.none").into_owned())
        ));
        j
    });
    let loader = jar.as_deref().and_then(|j| {
        let mut f = zip.by_name(j).ok()?;
        let mut bytes = Vec::new();
        if let Err(e) = (&mut f).take(MAX_SERVER_JAR_BYTES).read_to_end(&mut bytes) {
            mmcl_log::warn_silent(&tf!("log.technicImporter.readJarFailed", j, e.to_string()));
            return None;
        }
        detect_loader(&bytes)
    });
    match &loader {
        Some(l) => mmcl_log::std(&tf!(
            "log.technicImporter.detectedLoader",
            l.loader,
            l.mc_version
                .as_deref()
                .map(|v| tf!("log.technicImporter.forMc", v))
                .unwrap_or_default(),
            jar.clone().unwrap_or_default()
        )),
        None => mmcl_log::std(&tf!(
            "log.technicImporter.noLoaderMarkers",
            jar.clone()
                .unwrap_or_else(|| t!("log.technicImporter.anyJar").into_owned())
        )),
    }
    let manifest = build_manifest(&name, &version, &mods, loader.as_ref());
    let path = write_imported_manifest(
        &paths,
        &format!(
            "technic-server-{}.json",
            sanitize_file_part(Some(&folder), "unknown")
        ),
        &mmcl_manifest::gson::to_compact_string(&manifest),
    )
    .map_err(|e| read_err(&e))?;
    mmcl_log::std(&tf!(
        "log.technicImporter.wroteManifest",
        path.display().to_string(),
        mods.len().to_string(),
        install.display().to_string()
    ));
    let url = crate::fetch::path_to_file_url(&path);
    register(manager, &url).await?;
    Ok(url)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::test_support::{manager, write_zip, zip_bytes};
    use std::fs;

    #[test]
    fn names_and_versions() {
        assert_eq!(
            name_and_version("Tekkit_Legends_Server_v1.1.1.zip"),
            ("Tekkit Legends".into(), "v1.1.1".into())
        );
        assert_eq!(
            name_and_version("MyPack-server-2.zip"),
            ("MyPack".into(), "2".into())
        );
        assert_eq!(
            name_and_version("just_a_pack.zip"),
            ("just a pack".into(), "imported".into())
        );
    }

    #[test]
    fn loader_detection() {
        let forge = zip_bytes(&[
            ("net/minecraftforge/x.class", b"x"),
            ("fmlversion.properties", b"fmlbuild.mcversion=1.7.10\n"),
        ]);
        assert_eq!(
            detect_loader(&forge),
            Some(LoaderInfo {
                loader: "forge",
                mc_version: Some("1.7.10".into())
            })
        );
        let fabric = zip_bytes(&[("fabric-server-launch.properties", b"")]);
        assert_eq!(detect_loader(&fabric).unwrap().loader, "fabric");
        let neo = zip_bytes(&[("net/neoforged/a", b""), ("net/minecraftforge/b", b"")]);
        assert_eq!(detect_loader(&neo).unwrap().loader, "neoforge");
        assert!(detect_loader(&zip_bytes(&[("a/b", b"")])).is_none());
        assert!(detect_loader(b"not a zip").is_none());
    }

    #[tokio::test]
    async fn imports_server_zip_with_local_only_mods() {
        let d = tempfile::tempdir().unwrap();
        let (m, _h) = manager(&d.path().join("root"));
        let server = zip_bytes(&[("cpw/mods/fml/x.class", b"x")]);
        let z = d.path().join("Tekkit_Server_1.2.zip");
        write_zip(
            &z,
            &[
                ("mods/", b""),
                ("mods/a.jar", b"a"),
                ("mods/sub/b.jar", b"b"),
                ("config/c.cfg", b"c"),
                ("forge-universal.jar", &server),
                ("launch.sh", b"java -Xmx2G -jar forge-universal.jar nogui"),
            ],
        );
        let url = crate::import::zip::import_zip(&m, &z).await.unwrap();
        let root = m.paths().pack_install_dir("Tekkit");
        assert!(root.join("mods/a.jar").is_file());
        assert!(root.join("config/c.cfg").is_file());
        assert!(!root.join("launch.sh").exists());
        assert!(!root.join("forge-universal.jar").exists());
        let body = fs::read_to_string(crate::fetch::file_url_to_path(&url).unwrap()).unwrap();
        let pm = mmcl_manifest::PackManifest::parse(&body).unwrap();
        assert_eq!(pm.mods().len(), 1);
        assert_eq!(pm.mods()[0].local.as_deref(), Some("a.jar"));
        assert!(pm.mods()[0].client_req && pm.mods()[0].server_req && pm.mods()[0].is_local_only());
        assert_eq!(pm.mod_loader_type(), "forge");
        assert_eq!(pm.pack_version.as_deref(), Some("1.2"));
    }
}

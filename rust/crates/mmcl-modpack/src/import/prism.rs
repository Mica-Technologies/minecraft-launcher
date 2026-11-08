// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! MultiMC / Prism Launcher instance import (Java `PrismInstanceImporter`).
//!
//! Reads `instance.cfg` and `mmc-pack.json`, writes a manifest with `packImportedSkipSync` (the
//! content is user-owned, so file sync never sweeps or downloads mods), installs it, then copies
//! the instance's `.minecraft/` (or `minecraft/`) into the pack folder, skipping `bin/`.

use std::fs;
use std::path::Path;
use std::sync::Arc;

use mmcl_core::consts::modpack::{MOD_LOADER_FABRIC, MOD_LOADER_FORGE, MOD_LOADER_NEOFORGE};
use mmcl_i18n::{t, tf};
use serde_json::{Map, Value};

use super::{register, sanitize_file_part, write_imported_manifest};
use crate::error::{ModpackError, Result};
use crate::manager::ModPackManager;

/// Largest accepted `instance.cfg` / `mmc-pack.json`.
pub const MAX_METADATA_BYTES: u64 = 5 * 1024 * 1024;

/// What `mmc-pack.json` declares.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Components {
    pub mc_version: String,
    pub loader_type: Option<&'static str>,
    pub loader_version: Option<String>,
    pub loader_installer_url: Option<String>,
}

fn err(key: &str) -> ModpackError {
    ModpackError::Import(t!(key).into_owned())
}

/// Parses `mmc-pack.json` text.
pub fn parse_components(text: &str) -> Result<Components> {
    let root = match mmcl_manifest::gson::parse_lenient(text) {
        Ok((Value::Object(o), _)) => o,
        Ok(_) => {
            return Err(ModpackError::Import(tf!(
                "prismImporter.error.invalidPackJson",
                t!("modpack.error.notJsonObject")
            )));
        }
        Err(e) => {
            return Err(ModpackError::Import(tf!(
                "prismImporter.error.invalidPackJson",
                e.to_string()
            )));
        }
    };
    let Some(Value::Array(list)) = root.get("components") else {
        return Err(err("prismImporter.error.noComponents"));
    };
    let mut c = Components::default();
    let mut mc = None;
    for comp in list.iter().filter_map(Value::as_object) {
        let prim = |k: &str| match comp.get(k) {
            Some(v @ (Value::String(_) | Value::Number(_) | Value::Bool(_))) => {
                mmcl_manifest::lenient::coerce_string(v)
            }
            _ => None,
        };
        let Some(uid) = prim("uid") else {
            continue;
        };
        let version = prim("version");
        match uid.as_str() {
            "net.minecraft" => mc = version,
            "net.minecraftforge" => {
                (c.loader_type, c.loader_version) = (Some(MOD_LOADER_FORGE), version)
            }
            "net.neoforged" => {
                (c.loader_type, c.loader_version) = (Some(MOD_LOADER_NEOFORGE), version)
            }
            "net.fabricmc.fabric-loader" | "org.quiltmc.quilt-loader" => {
                (c.loader_type, c.loader_version) = (Some(MOD_LOADER_FABRIC), version)
            }
            _ => {}
        }
    }
    c.mc_version = mc
        .filter(|v| !v.trim().is_empty())
        .ok_or_else(|| err("prismImporter.error.noMcVersion"))?;
    if let (Some(t), Some(v)) = (c.loader_type, c.loader_version.as_deref()) {
        c.loader_installer_url = Some(super::mrpack::loader_installer_url(t, &c.mc_version, v));
    }
    Ok(c)
}

/// The manifest for an instance (key order as Java writes it).
pub fn build_manifest(name: &str, c: &Components) -> Value {
    let mut m = Map::new();
    m.insert("packName".into(), name.into());
    m.insert("packVersion".into(), "1.0.0".into());
    m.insert("packURL".into(), "".into());
    m.insert("packMinRAMGB".into(), "2".into());
    m.insert("packMinecraftVersion".into(), c.mc_version.clone().into());
    match c.loader_type {
        Some(t) => {
            let url = c.loader_installer_url.clone().unwrap_or_default();
            m.insert("packModLoader".into(), t.into());
            m.insert("packModLoaderURL".into(), url.clone().into());
            m.insert("packModLoaderHash".into(), "".into());
            if t == MOD_LOADER_FORGE {
                m.insert("packForgeURL".into(), url.into());
                m.insert("packForgeHash".into(), "".into());
            }
        }
        None => {
            m.insert("vanillaVersion".into(), true.into());
        }
    }
    for k in [
        "packMods",
        "packConfigs",
        "packResourcePacks",
        "packShaderPacks",
        "packInitialFiles",
    ] {
        m.insert(k.into(), Value::Array(Vec::new()));
    }
    m.insert("packImportedSkipSync".into(), true.into());
    Value::Object(m)
}

fn read_capped(path: &Path, label: &str) -> Result<Vec<u8>> {
    if fs::metadata(path).map(|m| m.len()).unwrap_or(0) > MAX_METADATA_BYTES {
        return Err(ModpackError::Import(tf!(
            "prismImporter.error.fileTooLarge",
            label
        )));
    }
    fs::read(path)
        .map_err(|e| ModpackError::Import(tf!("prismImporter.error.readCfgFailed", e.to_string())))
}

/// Copies `src` into `dest`, skipping `src/bin` and symlinks.
pub fn copy_tree_skip_bin(src: &Path, dest: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dest)?;
    let walker = walkdir::WalkDir::new(src)
        .min_depth(1)
        .into_iter()
        .filter_entry(|e| !(e.depth() == 1 && e.file_type().is_dir() && e.file_name() == "bin"));
    for e in walker {
        let e = e.map_err(std::io::Error::other)?;
        let rel = e.path().strip_prefix(src).map_err(std::io::Error::other)?;
        let target = dest.join(rel);
        if e.file_type().is_dir() {
            fs::create_dir_all(&target)?;
        } else if e.file_type().is_file()
            && !crate::ziputil::is_launcher_state_file(&e.file_name().to_string_lossy())
        {
            fs::copy(e.path(), &target)?;
        }
    }
    Ok(())
}

/// Imports and installs an instance folder. Returns the manifest `file:` URL.
pub async fn import_instance(manager: &Arc<ModPackManager>, instance_dir: &Path) -> Result<String> {
    if !instance_dir.is_dir() {
        return Err(err("prismImporter.error.pickFolder"));
    }
    let cfg = instance_dir.join("instance.cfg");
    let pack_json = instance_dir.join("mmc-pack.json");
    if !cfg.is_file() || !pack_json.is_file() {
        return Err(err("prismImporter.error.notInstance"));
    }
    let mc_folder = [".minecraft", "minecraft"]
        .iter()
        .map(|n| instance_dir.join(n))
        .find(|p| p.is_dir())
        .ok_or_else(|| err("prismImporter.error.noMinecraftFolder"))?;
    let props = mmcl_i18n::properties::parse(&mmcl_i18n::properties::decode(&read_capped(
        &cfg,
        "instance.cfg",
    )?));
    let name = props
        .get("name")
        .cloned()
        .filter(|n| !n.trim().is_empty())
        .unwrap_or_else(|| {
            instance_dir
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default()
        });
    let text = String::from_utf8_lossy(&read_capped(&pack_json, "mmc-pack.json")?).into_owned();
    let components = parse_components(&text)?;
    let manifest = build_manifest(&name, &components);
    let path = write_imported_manifest(
        manager.paths(),
        &format!("prism-{}.json", sanitize_file_part(Some(&name), "imported")),
        &mmcl_manifest::gson::to_compact_string(&manifest),
    )
    .map_err(|e| {
        ModpackError::Import(tf!(
            "prismImporter.error.writeManifestFailed",
            e.to_string()
        ))
    })?;
    let url = crate::fetch::path_to_file_url(&path);
    mmcl_log::std(&tf!(
        "log.prismImporter.wroteManifest",
        &name,
        path.display().to_string()
    ));
    let pack = register(manager, &url).await?;
    let root = pack.checked_pack_root()?;
    let (s, d) = (mc_folder, root);
    crate::managed::blocking(move || copy_tree_skip_bin(&s, &d))
        .await?
        .map_err(|e| ModpackError::Import(tf!("prismImporter.error.copyFailed", e.to_string())))?;
    mmcl_log::std(&tf!("log.prismImporter.copiedContents", &name));
    Ok(url)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::test_support::manager;

    #[test]
    fn components() {
        let c = parse_components(
            r#"{"components":[{"uid":"org.lwjgl3"},{"uid":"net.minecraft","version":"1.20.1"},{"uid":"net.minecraftforge","version":"47.3.0"},5]}"#,
        )
        .unwrap();
        assert_eq!(c.mc_version, "1.20.1");
        assert_eq!(c.loader_type, Some("forge"));
        assert!(
            c.loader_installer_url
                .unwrap()
                .ends_with("forge-1.20.1-47.3.0-installer.jar")
        );
        let q = parse_components(r#"{"components":[{"uid":"net.minecraft","version":"1.21"},{"uid":"org.quiltmc.quilt-loader","version":"0.26"}]}"#).unwrap();
        assert_eq!(q.loader_type, Some("fabric"));
        assert!(parse_components(r#"{"components":[]}"#).is_err());
        assert!(parse_components(r#"{}"#).is_err());
        assert!(parse_components("nope").is_err());
    }

    #[tokio::test]
    async fn imports_an_instance() {
        let d = tempfile::tempdir().unwrap();
        let (m, _h) = manager(&d.path().join("root"));
        let inst = d.path().join("MyInst");
        fs::create_dir_all(inst.join(".minecraft/mods")).unwrap();
        fs::create_dir_all(inst.join(".minecraft/bin")).unwrap();
        fs::write(inst.join(".minecraft/mods/a.jar"), "a").unwrap();
        fs::write(inst.join(".minecraft/bin/minecraft.jar"), "x").unwrap();
        fs::write(
            inst.join("instance.cfg"),
            "InstanceType=OneSix\nname=Cool Instance\n",
        )
        .unwrap();
        fs::write(
            inst.join("mmc-pack.json"),
            r#"{"components":[{"uid":"net.minecraft","version":"1.20.1"},{"uid":"net.fabricmc.fabric-loader","version":"0.16.0"}]}"#,
        )
        .unwrap();
        let url = import_instance(&m, &inst).await.unwrap();
        let p = m.installed_by_url(&url).unwrap();
        assert!(p.manifest().pack_imported_skip_sync);
        assert!(p.pack_root().join("mods/a.jar").is_file());
        assert!(!p.pack_root().join("bin/minecraft.jar").exists());
        assert!(
            crate::fetch::file_url_to_path(&url)
                .unwrap()
                .ends_with("prism-Cool_Instance.json")
        );
        assert!(import_instance(&m, &d.path().join("nope")).await.is_err());
    }
}

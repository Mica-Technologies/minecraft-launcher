// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Modrinth `.mrpack` import (Java `MrpackImporter`).
//!
//! The pack is downloaded (HTTPS, 200 MiB cap), `modrinth.index.json` becomes a Mica manifest
//! (files sorted into the five lists by path prefix, loader installer URL and SHA-1, the project
//! icon staged into the shared image cache), and `overrides/` + `client-overrides/` are
//! extracted into the pack folder with Zip-Slip and size caps. Jars an override puts directly in
//! `mods/` become local-only `packMods` entries so the floating-mod sweep keeps them. (Java did
//! not extract overrides at all, so packs that ship their configs that way started broken.)

use std::fs::{self, File};
use std::io::{Read, Seek};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use mmcl_core::DataPaths;
use mmcl_i18n::{t, tf};
use mmcl_manifest::pack::{BUNDLED_BACKGROUND_URL, BUNDLED_LOGO_URL};
use serde_json::{Map, Value};

use super::{alnum, register, sanitize_file_part, write_imported_manifest};
use crate::error::{ModpackError, Result};
use crate::http::PackHttp;
use crate::manager::ModPackManager;

/// Largest accepted `.mrpack`.
pub const MAX_MRPACK_BYTES: u64 = 200 * 1024 * 1024;
/// Loader dependency keys this importer supports.
pub const SUPPORTED_LOADERS: [&str; 3] = ["forge", "neoforge", "fabric-loader"];

/// One `files[]` entry of `modrinth.index.json`.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct IndexFile {
    pub path: Option<String>,
    pub sha1: Option<String>,
    pub sha512: Option<String>,
    pub env_client: Option<String>,
    pub env_server: Option<String>,
    pub downloads: Vec<String>,
    pub file_size: i64,
}

/// `modrinth.index.json`.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ModrinthIndex {
    pub format_version: i32,
    pub game: Option<String>,
    pub version_id: Option<String>,
    pub name: Option<String>,
    pub summary: Option<String>,
    pub files: Vec<IndexFile>,
    /// In file order.
    pub dependencies: Vec<(String, String)>,
}

fn s(o: &Map<String, Value>, k: &str) -> Option<String> {
    o.get(k).and_then(mmcl_manifest::lenient::coerce_string)
}

impl ModrinthIndex {
    /// Parses the index leniently (unknown keys ignored, bad elements skipped).
    pub fn parse(text: &str) -> Option<ModrinthIndex> {
        let (Value::Object(o), _) = mmcl_manifest::gson::parse_lenient(text).ok()? else {
            return None;
        };
        let files = o
            .get("files")
            .and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(Value::as_object)
                    .map(|f| {
                        let hashes = f.get("hashes").and_then(Value::as_object);
                        let env = f.get("env").and_then(Value::as_object);
                        IndexFile {
                            path: s(f, "path"),
                            sha1: hashes.and_then(|h| s(h, "sha1")),
                            sha512: hashes.and_then(|h| s(h, "sha512")),
                            env_client: env.and_then(|e| s(e, "client")),
                            env_server: env.and_then(|e| s(e, "server")),
                            downloads: f
                                .get("downloads")
                                .and_then(Value::as_array)
                                .map(|d| {
                                    d.iter()
                                        .filter_map(mmcl_manifest::lenient::coerce_string)
                                        .collect()
                                })
                                .unwrap_or_default(),
                            file_size: f
                                .get("fileSize")
                                .and_then(mmcl_util::json::as_gson_i64)
                                .unwrap_or(0),
                        }
                    })
                    .collect()
            })
            .unwrap_or_default();
        let dependencies = o
            .get("dependencies")
            .and_then(Value::as_object)
            .map(|d| {
                d.iter()
                    .filter_map(|(k, v)| {
                        mmcl_manifest::lenient::coerce_string(v).map(|v| (k.clone(), v))
                    })
                    .collect()
            })
            .unwrap_or_default();
        Some(ModrinthIndex {
            format_version: o
                .get("formatVersion")
                .and_then(mmcl_manifest::lenient::coerce_i32)
                .unwrap_or(0),
            game: s(&o, "game"),
            version_id: s(&o, "versionId"),
            name: s(&o, "name"),
            summary: s(&o, "summary"),
            files,
            dependencies,
        })
    }

    fn dep(&self, k: &str) -> Option<&str> {
        self.dependencies
            .iter()
            .find(|(a, _)| a == k)
            .map(|(_, v)| v.as_str())
    }

    /// The first supported loader dependency key.
    pub fn pick_loader(&self) -> Option<&str> {
        self.dependencies
            .iter()
            .map(|(k, _)| k.as_str())
            .find(|k| SUPPORTED_LOADERS.contains(k))
    }

    /// Files whose path starts with `mods/`.
    pub fn mod_count(&self) -> usize {
        self.files
            .iter()
            .filter(|f| {
                f.path
                    .as_deref()
                    .is_some_and(|p| p.to_lowercase().starts_with("mods/"))
            })
            .count()
    }
}

/// What an import produced.
#[derive(Clone, Debug)]
pub struct MrpackResult {
    pub local_manifest_url: String,
    pub index: ModrinthIndex,
    pub mod_count: usize,
}

/// A leading `v`/`V` before a digit is dropped (the UI adds its own); blank is `1.0.0`.
pub fn normalize_imported_version(raw: Option<&str>) -> String {
    let Some(t) = raw.map(str::trim).filter(|t| !t.is_empty()) else {
        return "1.0.0".into();
    };
    let mut c = t.chars();
    match (c.next(), c.next()) {
        (Some('v' | 'V'), Some(d)) if d.is_ascii_digit() => t[1..].to_string(),
        _ => t.to_string(),
    }
}

/// `forge` / `neoforge` / `fabric` from the dependency key.
pub fn loader_key_to_type(key: &str) -> &'static str {
    match key {
        "neoforge" => "neoforge",
        "fabric-loader" => "fabric",
        _ => "forge",
    }
}

/// The installer (Forge, NeoForge) or profile (Fabric) URL.
pub fn loader_installer_url(loader_type: &str, mc: &str, loader_version: &str) -> String {
    use crate::catalog::loaders::{
        fabric_profile_url, forge_installer_url, neoforge_installer_url,
    };
    match loader_type {
        "neoforge" => neoforge_installer_url(loader_version),
        "fabric" => fabric_profile_url(mc, loader_version),
        _ => forge_installer_url(mc, loader_version),
    }
}

/// A version string safe to put in a URL path segment: letters, digits, `.`, `_`, `+`, `-`,
/// and not a `.`/`..` segment.
pub fn is_safe_version(v: &str) -> bool {
    !v.is_empty()
        && v != "."
        && v != ".."
        && v.bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'+' | b'-'))
}

fn file_name_of(p: &str) -> &str {
    p.rsplit('/').next().unwrap_or(p)
}

fn entry(
    name: &str,
    remote: &str,
    local: &str,
    sha1: Option<&str>,
    client: bool,
    server: bool,
) -> Value {
    let mut n = Map::new();
    n.insert("name".into(), name.into());
    n.insert("remote".into(), remote.into());
    n.insert("local".into(), local.into());
    if let Some(h) = sha1 {
        n.insert("sha1".into(), h.into());
    }
    n.insert("clientReq".into(), client.into());
    n.insert("serverReq".into(), server.into());
    Value::Object(n)
}

/// The file lists of the manifest, by path prefix. Server-unsupported files are kept (the
/// launcher is a client) and files without a SHA-1 are skipped.
pub fn build_file_lists(index: &ModrinthIndex) -> [Vec<Value>; 4] {
    let (mut mods, mut configs, mut rps, mut shaders) =
        (Vec::new(), Vec::new(), Vec::new(), Vec::new());
    for f in &index.files {
        let (Some(path), Some(url)) = (f.path.as_deref(), f.downloads.first()) else {
            continue;
        };
        if f.env_client
            .as_deref()
            .is_some_and(|c| c.eq_ignore_ascii_case("unsupported"))
        {
            continue;
        }
        let path = path.replace('\\', "/");
        let Some(sha1) = f.sha1.as_deref().filter(|h| !h.trim().is_empty()) else {
            mmcl_log::warn_silent(&tf!("log.mrpackImporter.skipNoSha1", &path));
            continue;
        };
        let server = f
            .env_server
            .as_deref()
            .is_some_and(|s| s.eq_ignore_ascii_case("required"));
        // The entry's `local` keeps any sub-folder below the list's folder.
        let (list, rel) = if let Some(r) = strip_prefix_ci(&path, "config/") {
            (&mut configs, r)
        } else if let Some(r) = strip_prefix_ci(&path, "resourcepacks/") {
            (&mut rps, r)
        } else if let Some(r) = strip_prefix_ci(&path, "shaderpacks/") {
            (&mut shaders, r)
        } else if let Some(r) = strip_prefix_ci(&path, "mods/") {
            (&mut mods, r)
        } else {
            (&mut mods, file_name_of(&path))
        };
        list.push(entry(
            file_name_of(&path),
            url,
            rel,
            Some(sha1),
            true,
            server,
        ));
    }
    [mods, configs, rps, shaders]
}

/// `path` without the ASCII `prefix` (compared ignoring ASCII case). Slices `path` itself, so
/// a non-ASCII character whose lowercase form is ASCII (the Kelvin sign lowercases to `k`) can
/// neither shift the cut nor split a character.
fn strip_prefix_ci<'a>(path: &'a str, prefix: &str) -> Option<&'a str> {
    path.get(..prefix.len())
        .filter(|head| head.eq_ignore_ascii_case(prefix))
        .map(|_| &path[prefix.len()..])
        .filter(|r| !r.is_empty())
}

async fn installer_sha1(http: &dyn PackHttp, url: &str) -> Option<String> {
    let tmp =
        std::env::temp_dir().join(format!("mica-forge-installer-{}.jar", uuid::Uuid::new_v4()));
    let res: std::result::Result<String, String> = async {
        http.download(url, &tmp, None)
            .await
            .map_err(|e| e.to_string())?;
        let t = tmp.clone();
        crate::managed::blocking(move || {
            crate::managed::hash_file(&t, mmcl_manifest::HashAlgo::Sha1)
        })
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())
    }
    .await;
    let _ = fs::remove_file(&tmp);
    match res {
        Ok(h) => Some(h),
        Err(e) => {
            mmcl_log::warn_silent(&tf!("log.mrpackImporter.forgeHashFailed", url, &e));
            None
        }
    }
}

/// Downloads an `https` icon into the shared image cache; returns its SHA-1 name.
async fn stage_logo(paths: &DataPaths, http: &dyn PackHttp, icon: Option<&str>) -> Option<String> {
    let icon = icon.filter(|i| i.trim().to_lowercase().starts_with("https://"))?;
    let tmp = std::env::temp_dir().join(format!("mica-imported-logo-{}.png", uuid::Uuid::new_v4()));
    let res = async {
        http.download(icon, &tmp, None).await.ok()?;
        let t = tmp.clone();
        let sha = crate::managed::blocking(move || {
            if !mmcl_archive::image::ensure_decodable(&t) {
                return None;
            }
            crate::managed::hash_file(&t, mmcl_manifest::HashAlgo::Sha1).ok()
        })
        .await
        .ok()
        .flatten()?;
        let meta = paths.metadata_dir();
        fs::create_dir_all(&meta).ok()?;
        let dest = meta.join(format!("{sha}.png"));
        if !dest.exists() && fs::rename(&tmp, &dest).is_err() {
            fs::copy(&tmp, &dest).ok()?;
        }
        Some(sha)
    }
    .await;
    let _ = fs::remove_file(&tmp);
    match &res {
        Some(sha) => mmcl_log::std(&tf!("log.mrpackImporter.stagedIcon", sha)),
        None => mmcl_log::std(&tf!("log.mrpackImporter.noIconStaged", icon)),
    }
    res
}

/// Extracts `overrides/` then `client-overrides/` into `pack_root`; returns local-only mod
/// entries for jars placed directly in `mods/`.
pub fn extract_overrides<R: Read + Seek>(
    zip: &mut ::zip::ZipArchive<R>,
    pack_root: &Path,
) -> Result<Vec<Value>> {
    let mut entries = Vec::new();
    for (prefix, server) in [("overrides/", true), ("client-overrides/", false)] {
        let mut jars: Vec<String> = Vec::new();
        let tmp = pack_root.join(format!(".import-{}", uuid::Uuid::new_v4()));
        let res = crate::ziputil::extract_bounded(
            zip,
            &tmp,
            |n| !n.starts_with(prefix),
            |n| {
                if let Some(rest) = n.strip_prefix(prefix).and_then(|r| r.strip_prefix("mods/"))
                    && !rest.contains('/')
                    && rest.to_lowercase().ends_with(".jar")
                {
                    jars.push(rest.to_string());
                }
            },
        );
        let moved =
            res.and_then(|_| merge_tree(&tmp.join(prefix.trim_end_matches('/')), pack_root));
        let _ = fs::remove_dir_all(&tmp);
        moved.map_err(|e| {
            ModpackError::Import(tf!(
                "mrpackImporter.error.failed",
                "overrides",
                e.to_string()
            ))
        })?;
        for j in jars {
            let name = j
                .strip_suffix(".jar")
                .or_else(|| j.strip_suffix(".JAR"))
                .unwrap_or(&j)
                .to_string();
            entries.push(entry(&name, "", &j, None, true, server));
        }
    }
    Ok(entries)
}

/// Moves every file under `src` into `dest` (replacing), creating folders.
fn merge_tree(src: &Path, dest: &Path) -> std::io::Result<()> {
    if !src.is_dir() {
        return Ok(());
    }
    for e in walkdir::WalkDir::new(src).min_depth(1) {
        let e = e.map_err(std::io::Error::other)?;
        let rel = e.path().strip_prefix(src).map_err(std::io::Error::other)?;
        let target = dest.join(rel);
        if e.file_type().is_dir() {
            fs::create_dir_all(&target)?;
        } else if e.file_type().is_file() {
            if let Some(p) = target.parent() {
                fs::create_dir_all(p)?;
            }
            if fs::rename(e.path(), &target).is_err() {
                fs::copy(e.path(), &target)?;
            }
        }
    }
    Ok(())
}

/// Builds the manifest object (no network: the installer hash and logo sha are passed in).
pub fn build_manifest(
    index: &ModrinthIndex,
    mc: &str,
    loader_key: &str,
    logo: Option<(&str, &str)>,
    installer_hash: &str,
    override_mods: Vec<Value>,
) -> Value {
    let loader_type = loader_key_to_type(loader_key);
    let loader_version = index.dep(loader_key).unwrap_or("");
    let installer_url = loader_installer_url(loader_type, mc, loader_version);
    let mut m = Map::new();
    let name = index
        .name
        .clone()
        .filter(|n| !n.trim().is_empty())
        .unwrap_or_else(|| "Imported Modrinth Pack".into());
    m.insert("packName".into(), name.into());
    m.insert(
        "packVersion".into(),
        normalize_imported_version(index.version_id.as_deref()).into(),
    );
    m.insert("packURL".into(), "https://modrinth.com/".into());
    m.insert("packMinRAMGB".into(), "4".into());
    match logo {
        Some((url, sha)) => {
            m.insert("packLogoSha1".into(), sha.into());
            m.insert("packLogoURL".into(), url.into());
        }
        None => {
            m.insert("packLogoURL".into(), BUNDLED_LOGO_URL.into());
        }
    }
    m.insert("packBackgroundURL".into(), BUNDLED_BACKGROUND_URL.into());
    m.insert("packModLoader".into(), loader_type.into());
    m.insert("packModLoaderURL".into(), installer_url.into());
    m.insert("packModLoaderHash".into(), installer_hash.into());
    m.insert("packForgeURL".into(), "".into());
    m.insert("packForgeHash".into(), "".into());
    m.insert("packMinecraftVersion".into(), mc.into());
    m.insert("packScanExclusions".into(), Value::Array(Vec::new()));
    m.insert("packScanAcknowledgements".into(), Value::Array(Vec::new()));
    let [mut mods, configs, rps, shaders] = build_file_lists(index);
    mods.extend(override_mods);
    m.insert("packMods".into(), Value::Array(mods));
    m.insert("packConfigs".into(), Value::Array(configs));
    m.insert("packResourcePacks".into(), Value::Array(rps));
    m.insert("packShaderPacks".into(), Value::Array(shaders));
    m.insert("packInitialFiles".into(), Value::Array(Vec::new()));
    Value::Object(m)
}

/// Imports a local `.mrpack` file (no install). `slug` names the manifest file; `icon_url`
/// (https) is staged as the logo.
pub async fn import_mrpack_file(
    paths: &DataPaths,
    http: &dyn PackHttp,
    mrpack: &Path,
    slug: Option<&str>,
    icon_url: Option<&str>,
) -> Result<MrpackResult> {
    let fail = |e: &dyn std::fmt::Display| {
        ModpackError::Import(tf!("mrpackImporter.error.readIndexFailed", e.to_string()))
    };
    let mut zip =
        ::zip::ZipArchive::new(File::open(mrpack).map_err(|e| fail(&e))?).map_err(|e| fail(&e))?;
    let text = {
        let mut f = zip
            .by_name("modrinth.index.json")
            .map_err(|_| ModpackError::Import(t!("mrpackImporter.error.notMrpack").into_owned()))?;
        let mut s = String::new();
        (&mut f)
            .take(mmcl_net::MANIFEST_MAX_BYTES)
            .read_to_string(&mut s)
            .map_err(|e| fail(&e))?;
        s
    };
    let index = ModrinthIndex::parse(&text)
        .ok_or_else(|| ModpackError::Import(t!("mrpackImporter.error.notMrpack").into_owned()))?;
    mmcl_log::std(&tf!(
        "log.mrpackImporter.parsed",
        index.name.clone().unwrap_or_default(),
        index.version_id.clone().unwrap_or_default(),
        index.files.len().to_string()
    ));
    let Some(loader_key) = index.pick_loader().map(str::to_string) else {
        mmcl_log::std(&tf!(
            "log.mrpackImporter.unsupportedLoader",
            format!("{:?}", index.dependencies)
        ));
        return Err(ModpackError::Import(
            t!("mrpackImporter.error.unsupportedLoader").into_owned(),
        ));
    };
    let mc = index
        .dep("minecraft")
        .filter(|v| is_safe_version(v))
        .map(str::to_string)
        .ok_or_else(|| ModpackError::Import(t!("mrpackImporter.error.noMcVersion").into_owned()))?;
    // Both versions are spliced into loader download URLs: only plain version text may reach
    // them (no `/`, `?`, `#` or `..` steering the request elsewhere on the host).
    if !index.dep(&loader_key).is_some_and(is_safe_version) {
        return Err(ModpackError::Import(
            t!("mrpackImporter.error.unsupportedLoader").into_owned(),
        ));
    }
    let loader_type = loader_key_to_type(&loader_key);
    mmcl_log::std(&tf!(
        "log.mrpackImporter.target",
        &mc,
        loader_type,
        index.dep(&loader_key).unwrap_or("")
    ));
    let installer_hash = if loader_type == "fabric" {
        String::new()
    } else {
        let url = loader_installer_url(loader_type, &mc, index.dep(&loader_key).unwrap_or(""));
        installer_sha1(http, &url).await.ok_or_else(|| {
            ModpackError::Import(tf!(
                "mrpackImporter.error.installerFetchFailed",
                loader_type,
                &url
            ))
        })?
    };
    let logo_sha = stage_logo(paths, http, icon_url).await;
    let pack_name = index
        .name
        .clone()
        .filter(|n| !n.trim().is_empty())
        .unwrap_or_else(|| "Imported Modrinth Pack".into());
    let folder = alnum(&pack_name);
    // Extraction can be gigabytes of disk I/O: keep it off the async worker threads.
    let override_mods = if folder.is_empty() {
        Vec::new()
    } else {
        let root = paths.pack_install_dir(&folder);
        crate::managed::blocking(move || extract_overrides(&mut zip, &root)).await??
    };
    let logo = match (&logo_sha, icon_url) {
        (Some(sha), Some(url)) => Some((url, sha.as_str())),
        _ => None,
    };
    let manifest = build_manifest(
        &index,
        &mc,
        &loader_key,
        logo,
        &installer_hash,
        override_mods,
    );
    let file = format!(
        "modrinth-{}-{}.json",
        sanitize_file_part(slug, "imported"),
        sanitize_file_part(index.version_id.as_deref(), "unknown")
    );
    let path: PathBuf = write_imported_manifest(
        paths,
        &file,
        &mmcl_manifest::gson::to_compact_string(&manifest),
    )
    .map_err(|e| {
        ModpackError::Import(tf!(
            "mrpackImporter.error.writeManifestFailed",
            e.to_string()
        ))
    })?;
    mmcl_log::std(&tf!(
        "log.mrpackImporter.wroteManifest",
        path.display().to_string()
    ));
    let url = crate::fetch::path_to_file_url(&path);
    let mod_count = index.mod_count();
    mmcl_log::std(&tf!(
        "log.mrpackImporter.success",
        mod_count.to_string(),
        &url
    ));
    Ok(MrpackResult {
        local_manifest_url: url,
        index,
        mod_count,
    })
}

/// Downloads a `.mrpack` (HTTPS, capped) and imports it (no install).
pub async fn import_mrpack(
    paths: &DataPaths,
    http: &dyn PackHttp,
    download_url: &str,
    slug: Option<&str>,
    icon_url: Option<&str>,
) -> Result<MrpackResult> {
    if download_url.trim().is_empty() {
        return Err(ModpackError::Import(
            t!("mrpackImporter.error.noUrl").into_owned(),
        ));
    }
    mmcl_log::std(&tf!(
        "log.mrpackImporter.starting",
        slug.unwrap_or(""),
        download_url
    ));
    let tmp = std::env::temp_dir().join(format!("mica-mrpack-{}.mrpack", uuid::Uuid::new_v4()));
    let res = async {
        http.download_bounded(download_url, &tmp, MAX_MRPACK_BYTES)
            .await
            .map_err(|e| {
                ModpackError::Import(tf!("mrpackImporter.error.downloadFailed", e.to_string()))
            })?;
        import_mrpack_file(paths, http, &tmp, slug, icon_url).await
    }
    .await;
    let _ = fs::remove_file(&tmp);
    res
}

/// [`import_mrpack`], then installs the result.
pub async fn import_and_install_mrpack(
    manager: &Arc<ModPackManager>,
    download_url: &str,
    slug: Option<&str>,
    icon_url: Option<&str>,
) -> Result<MrpackResult> {
    let http = manager.fetcher().http().clone();
    let r = import_mrpack(manager.paths(), http.as_ref(), download_url, slug, icon_url).await?;
    register(manager, &r.local_manifest_url).await?;
    Ok(r)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::fake::FakeHttp;
    use crate::import::test_support::write_zip;

    const INDEX: &str = r#"{
      "formatVersion": 1, "game": "minecraft", "versionId": "v2.1", "name": "Fab Pack",
      "files": [
        {"path": "mods/sodium.jar", "hashes": {"sha1": "s1", "sha512": "x"}, "env": {"client": "required", "server": "unsupported"},
         "downloads": ["https://cdn.modrinth.com/sodium.jar"], "fileSize": 10},
        {"path": "config/sub/a.toml", "hashes": {"sha1": "s2"}, "downloads": ["https://cdn/a.toml"]},
        {"path": "resourcepacks/rp.zip", "hashes": {"sha1": "s3"}, "env": {"client": "optional", "server": "required"}, "downloads": ["https://cdn/rp.zip"]},
        {"path": "shaderpacks/sh.zip", "hashes": {"sha1": "s4"}, "downloads": ["https://cdn/sh.zip"]},
        {"path": "mods/serveronly.jar", "hashes": {"sha1": "s5"}, "env": {"client": "unsupported"}, "downloads": ["https://cdn/so.jar"]},
        {"path": "mods/nosha.jar", "hashes": {}, "downloads": ["https://cdn/n.jar"]},
        {"path": "odd/thing.jar", "hashes": {"sha1": "s6"}, "downloads": ["https://cdn/t.jar"]},
        null
      ],
      "dependencies": {"minecraft": "1.21.5", "fabric-loader": "0.16.10"}
    }"#;

    #[test]
    fn only_plain_versions_reach_loader_urls() {
        for ok in ["1.21.5", "0.16.10", "47.3.0", "1.20.1-47.3.0", "21.1.0-beta", "1.0+build.3"] {
            assert!(is_safe_version(ok), "{ok}");
        }
        for bad in ["", ".", "..", "../x", "1.0/../../a", "1.0?x=1", "1.0#f", "a b", "1.0%2f"] {
            assert!(!is_safe_version(bad), "{bad}");
        }
    }

    #[test]
    fn prefix_match_never_splits_a_character() {
        // The Kelvin sign (3 bytes) lowercases to an ASCII `k`; slicing a lowercased copy's
        // offsets into the original used to panic on such a path.
        let tricky = "resourcepac\u{212A}s/x.zip";
        assert_eq!(strip_prefix_ci(tricky, "resourcepacks/"), None);
        assert_eq!(strip_prefix_ci("Mods/a.jar", "mods/"), Some("a.jar"));
        assert_eq!(strip_prefix_ci("mods/", "mods/"), None);
        assert_eq!(strip_prefix_ci("m\u{e9}", "mods/"), None);
        let idx = ModrinthIndex {
            files: vec![IndexFile {
                path: Some(tricky.into()),
                sha1: Some("aa".into()),
                downloads: vec!["https://cdn/x.zip".into()],
                ..Default::default()
            }],
            ..Default::default()
        };
        let [mods, ..] = build_file_lists(&idx);
        assert_eq!(mods.len(), 1, "falls back to a mod named by its file name");
    }

    #[test]
    fn version_normalisation() {
        assert_eq!(normalize_imported_version(Some("v43")), "43");
        assert_eq!(normalize_imported_version(Some("V2.1.0")), "2.1.0");
        assert_eq!(
            normalize_imported_version(Some("vintage-1.0")),
            "vintage-1.0"
        );
        assert_eq!(normalize_imported_version(None), "1.0.0");
    }

    #[test]
    fn index_to_manifest() {
        let idx = ModrinthIndex::parse(INDEX).unwrap();
        assert_eq!(idx.pick_loader(), Some("fabric-loader"));
        assert_eq!(idx.mod_count(), 3);
        let m = build_manifest(&idx, "1.21.5", "fabric-loader", None, "", vec![]);
        let pm = mmcl_manifest::PackManifest::from_value(&m).unwrap();
        assert_eq!(pm.pack_version.as_deref(), Some("2.1"));
        assert_eq!(pm.mod_loader_type(), "fabric");
        assert_eq!(
            pm.mod_loader_url(),
            Some("https://meta.fabricmc.net/v2/versions/loader/1.21.5/0.16.10/profile/json")
        );
        let mods: Vec<&str> = pm
            .mods()
            .iter()
            .map(|f| f.local.as_deref().unwrap())
            .collect();
        assert_eq!(mods, ["sodium.jar", "thing.jar"]);
        assert!(pm.mods()[0].client_req && !pm.mods()[0].server_req);
        assert_eq!(pm.configs()[0].local.as_deref(), Some("sub/a.toml"));
        assert!(pm.resource_packs()[0].server_req);
        assert_eq!(pm.shader_packs().len(), 1);
        assert!(!pm.has_custom_logo());
    }

    #[tokio::test]
    async fn imports_a_local_mrpack_with_overrides() {
        let d = tempfile::tempdir().unwrap();
        let paths = DataPaths::at_root(d.path().join("root"), mmcl_core::GameMode::Client);
        let f = d.path().join("p.mrpack");
        write_zip(
            &f,
            &[
                ("modrinth.index.json", INDEX.as_bytes()),
                ("overrides/config/extra.cfg", b"cfg"),
                ("overrides/mods/bundled.jar", b"jar"),
                ("client-overrides/options.txt", b"opts"),
                ("overrides/../../escape.txt", b"bad"),
            ],
        );
        let http = FakeHttp::new();
        let e = import_mrpack_file(&paths, http.as_ref(), &f, Some("fab"), None).await;
        // The escaping override entry is refused, and nothing is written.
        assert!(e.is_err());
        assert!(!d.path().join("root/installs/FabPack/escape.txt").exists());
        write_zip(
            &f,
            &[
                ("modrinth.index.json", INDEX.as_bytes()),
                ("overrides/config/extra.cfg", b"cfg"),
                ("overrides/mods/bundled.jar", b"jar"),
                ("client-overrides/options.txt", b"opts"),
            ],
        );
        let r = import_mrpack_file(&paths, http.as_ref(), &f, Some("fab"), None)
            .await
            .unwrap();
        let root = paths.pack_install_dir("FabPack");
        assert_eq!(fs::read(root.join("config/extra.cfg")).unwrap(), b"cfg");
        assert_eq!(fs::read(root.join("options.txt")).unwrap(), b"opts");
        assert!(root.join("mods/bundled.jar").is_file());
        let path = crate::fetch::file_url_to_path(&r.local_manifest_url).unwrap();
        assert!(path.ends_with("modrinth-fab-v2.1.json"));
        let pm = mmcl_manifest::PackManifest::parse(&fs::read_to_string(&path).unwrap()).unwrap();
        let bundled = pm
            .mods()
            .iter()
            .find(|m| m.local.as_deref() == Some("bundled.jar"))
            .unwrap();
        assert!(bundled.is_local_only() && bundled.client_req && bundled.server_req);
    }

    #[tokio::test]
    async fn forge_pack_needs_the_installer_hash() {
        let d = tempfile::tempdir().unwrap();
        let paths = DataPaths::at_root(d.path(), mmcl_core::GameMode::Client);
        let f = d.path().join("p.mrpack");
        let idx = r#"{"name":"F","versionId":"1","files":[],"dependencies":{"minecraft":"1.20.1","forge":"47.3.0"}}"#;
        write_zip(&f, &[("modrinth.index.json", idx.as_bytes())]);
        let http = FakeHttp::new();
        assert!(
            import_mrpack_file(&paths, http.as_ref(), &f, None, None)
                .await
                .is_err()
        );
        let url = loader_installer_url("forge", "1.20.1", "47.3.0");
        http.body(&url, b"installer".to_vec());
        let r = import_mrpack_file(&paths, http.as_ref(), &f, None, None)
            .await
            .unwrap();
        let body =
            fs::read_to_string(crate::fetch::file_url_to_path(&r.local_manifest_url).unwrap())
                .unwrap();
        assert!(body.contains(&mmcl_util::hash::sha1_hex(b"installer")));
        // Unsupported loader / missing MC version.
        write_zip(
            &f,
            &[(
                "modrinth.index.json",
                br#"{"dependencies":{"minecraft":"1.20.1","quilt-loader":"1"}}"#,
            )],
        );
        assert!(
            import_mrpack_file(&paths, http.as_ref(), &f, None, None)
                .await
                .is_err()
        );
        write_zip(&f, &[("other.json", b"{}")]);
        assert!(
            import_mrpack_file(&paths, http.as_ref(), &f, None, None)
                .await
                .is_err()
        );
    }
}

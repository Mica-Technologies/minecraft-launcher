// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Catalogues for Browse and the editor: modloader versions (Java `LoaderVersionManager`) and
//! vanilla Minecraft versions (Java `VanillaVersionManager`).

/// Forge, NeoForge and Fabric version lists, and "install a bare loader" packs.
pub mod loaders {
    use std::collections::HashMap;
    use std::fs;
    use std::path::PathBuf;
    use std::sync::{Arc, LazyLock};

    use mmcl_core::DataPaths;
    use mmcl_i18n::{t, tf};
    use mmcl_manifest::pack::{BUNDLED_BACKGROUND_URL, BUNDLED_LOGO_URL};
    use mmcl_manifest::version::compare_versions;
    use parking_lot::Mutex;
    use regex::Regex;
    use serde_json::{Map, Value};

    use crate::error::{ModpackError, Result};
    use crate::http::PackHttp;
    use crate::manager::ModPackManager;

    pub const FORGE_PROMOTIONS_URL: &str =
        "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    pub const NEOFORGE_METADATA_URL: &str =
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    pub const FABRIC_GAME_URL: &str = "https://meta.fabricmc.net/v2/versions/game";
    pub const FABRIC_LOADER_URL: &str = "https://meta.fabricmc.net/v2/versions/loader";

    /// One installable loader version.
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct LoaderVersion {
        pub loader_type: String,
        pub mc_version: String,
        pub loader_version: String,
        pub installer_url: String,
    }

    fn sanitize(s: &str) -> String {
        s.chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
                    c
                } else {
                    '_'
                }
            })
            .collect()
    }

    /// `Forge`, `NeoForge`, `Fabric`, or the type itself.
    pub fn pretty_loader_name(t: &str) -> &str {
        match t {
            "forge" => "Forge",
            "neoforge" => "NeoForge",
            "fabric" => "Fabric",
            other => other,
        }
    }

    fn url_for_loader(t: &str) -> &'static str {
        match t {
            "forge" => "https://files.minecraftforge.net",
            "neoforge" => "https://neoforged.net",
            "fabric" => "https://fabricmc.net",
            _ => "https://minecraft.net",
        }
    }

    impl LoaderVersion {
        /// `"<Loader> <mc> (<version>)"`.
        pub fn display_name(&self) -> String {
            format!(
                "{} {} ({})",
                pretty_loader_name(&self.loader_type),
                self.mc_version,
                self.loader_version
            )
        }
        /// `loader-<type>-<mc>-<version>.json`.
        pub fn manifest_filename(&self) -> String {
            format!(
                "loader-{}-{}-{}.json",
                self.loader_type,
                sanitize(&self.mc_version),
                sanitize(&self.loader_version)
            )
        }
    }

    pub fn forge_installer_url(mc: &str, ver: &str) -> String {
        format!(
            "https://maven.minecraftforge.net/net/minecraftforge/forge/{mc}-{ver}/forge-{mc}-{ver}-installer.jar"
        )
    }
    pub fn neoforge_installer_url(ver: &str) -> String {
        format!(
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/{ver}/neoforge-{ver}-installer.jar"
        )
    }
    pub fn fabric_profile_url(mc: &str, loader: &str) -> String {
        format!("https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/profile/json")
    }

    /// Forge versions from `promotions_slim.json`: newest MC first, recommended then latest.
    pub fn parse_forge_promotions(json: &str) -> Option<Vec<LoaderVersion>> {
        let v: Value = serde_json::from_str(json).ok()?;
        let promos = v.get("promos")?.as_object()?;
        let mut rec: HashMap<String, String> = HashMap::new();
        let mut latest: HashMap<String, String> = HashMap::new();
        for (k, val) in promos {
            let Some(ver) = mmcl_manifest::lenient::coerce_string(val) else {
                continue;
            };
            if let Some(mc) = k.strip_suffix("-recommended") {
                rec.insert(mc.to_string(), ver);
            } else if let Some(mc) = k.strip_suffix("-latest") {
                latest.insert(mc.to_string(), ver);
            }
        }
        let mut mcs: Vec<&String> = latest.keys().collect();
        mcs.sort_by(|a, b| compare_versions(b, a).then_with(|| b.cmp(a)));
        let mk = |mc: &str, ver: &str| LoaderVersion {
            loader_type: "forge".into(),
            mc_version: mc.into(),
            loader_version: ver.into(),
            installer_url: forge_installer_url(mc, ver),
        };
        let mut out = Vec::new();
        for mc in mcs {
            if let Some(r) = rec.get(mc) {
                out.push(mk(mc, r));
            }
            let l = &latest[mc];
            if rec.get(mc) != Some(l) {
                out.push(mk(mc, l));
            }
        }
        Some(out)
    }

    /// NeoForge `21.1.x` targets MC `1.21.1`; `20.4.x` targets `1.20.4`; `21.0.x` targets `1.21`.
    pub fn neoforge_mc_version_for(v: &str) -> Option<String> {
        let mut parts = v.split('.');
        let major: i32 = parts.next()?.parse().ok()?;
        let minor: i32 = parts.next()?.parse().ok()?;
        Some(if minor == 0 {
            format!("1.{major}")
        } else {
            format!("1.{major}.{minor}")
        })
    }

    static VERSION_TAG: LazyLock<Regex> =
        LazyLock::new(|| Regex::new("<version>([^<]+)</version>").expect("valid regex"));

    /// NeoForge versions from `maven-metadata.xml`, newest first.
    pub fn parse_neoforge_metadata(xml: &str) -> Vec<LoaderVersion> {
        let mut versions: Vec<String> = VERSION_TAG
            .captures_iter(xml)
            .map(|c| c[1].to_string())
            .collect();
        versions.sort_by(|a, b| compare_versions(b, a));
        versions
            .into_iter()
            .filter_map(|v| {
                let mc = neoforge_mc_version_for(&v)?;
                Some(LoaderVersion {
                    loader_type: "neoforge".into(),
                    mc_version: mc,
                    installer_url: neoforge_installer_url(&v),
                    loader_version: v,
                })
            })
            .collect()
    }

    /// Fabric: every stable game version with the newest stable loader.
    pub fn parse_fabric(games_json: &str, loaders_json: &str) -> Option<Vec<LoaderVersion>> {
        let games: Vec<Value> = serde_json::from_str(games_json).ok()?;
        let loaders: Vec<Value> = serde_json::from_str(loaders_json).ok()?;
        let ver = |o: &Value| {
            o.get("version")
                .and_then(mmcl_manifest::lenient::coerce_string)
        };
        let latest = loaders
            .iter()
            .find(|l| l.get("stable").and_then(Value::as_bool) == Some(true))
            .and_then(ver)
            .or_else(|| loaders.first().and_then(ver))?;
        Some(
            games
                .iter()
                .filter(|g| g.get("stable").and_then(Value::as_bool) != Some(false))
                .filter_map(ver)
                .map(|mc| LoaderVersion {
                    loader_type: "fabric".into(),
                    installer_url: fabric_profile_url(&mc, &latest),
                    mc_version: mc,
                    loader_version: latest.clone(),
                })
                .collect(),
        )
    }

    /// Process-wide cache of each list. Only a successful fetch is cached (Java cached an
    /// empty list after a failure until restart).
    #[derive(Default)]
    struct Cache {
        forge: Option<Vec<LoaderVersion>>,
        neoforge: Option<Vec<LoaderVersion>>,
        fabric: Option<Vec<LoaderVersion>>,
    }
    static CACHE: LazyLock<Mutex<Cache>> = LazyLock::new(Default::default);

    pub async fn forge_versions(http: &dyn PackHttp) -> Vec<LoaderVersion> {
        if let Some(v) = CACHE.lock().forge.clone() {
            return v;
        }
        match http
            .get_string(FORGE_PROMOTIONS_URL)
            .await
            .ok()
            .and_then(|b| parse_forge_promotions(&b))
        {
            Some(v) => {
                CACHE.lock().forge = Some(v.clone());
                v
            }
            None => {
                mmcl_log::warn_silent(&t!("log.loaderVersionManager.fetchForge"));
                Vec::new()
            }
        }
    }

    pub async fn neoforge_versions(http: &dyn PackHttp) -> Vec<LoaderVersion> {
        if let Some(v) = CACHE.lock().neoforge.clone() {
            return v;
        }
        match http.get_string(NEOFORGE_METADATA_URL).await {
            Ok(xml) => {
                let v = parse_neoforge_metadata(&xml);
                CACHE.lock().neoforge = Some(v.clone());
                v
            }
            Err(_) => {
                mmcl_log::warn_silent(&t!("log.loaderVersionManager.fetchNeoForge"));
                Vec::new()
            }
        }
    }

    pub async fn fabric_versions(http: &dyn PackHttp) -> Vec<LoaderVersion> {
        if let Some(v) = CACHE.lock().fabric.clone() {
            return v;
        }
        let games = http.get_string(FABRIC_GAME_URL).await;
        let loaders = http.get_string(FABRIC_LOADER_URL).await;
        match (games, loaders) {
            (Ok(g), Ok(l)) if parse_fabric(&g, &l).is_some() => {
                let v = parse_fabric(&g, &l).unwrap_or_default();
                CACHE.lock().fabric = Some(v.clone());
                v
            }
            _ => {
                mmcl_log::warn_silent(&t!("log.loaderVersionManager.fetchFabric"));
                Vec::new()
            }
        }
    }

    /// `<root>/config/imported-manifests/<manifest filename>`.
    pub fn manifest_path_for(paths: &DataPaths, v: &LoaderVersion) -> PathBuf {
        paths.imported_manifests_dir().join(v.manifest_filename())
    }

    /// `true` once the loader pack's manifest exists.
    pub fn is_installed(paths: &DataPaths, v: &LoaderVersion) -> bool {
        manifest_path_for(paths, v).exists()
    }

    /// The bare-loader manifest (key order as Java writes it).
    pub fn build_manifest(v: &LoaderVersion) -> Value {
        let mut m = Map::new();
        let s = |x: &str| Value::from(x);
        m.insert("packName".into(), s(&v.display_name()));
        m.insert("packVersion".into(), s(&v.loader_version));
        m.insert("packURL".into(), s(url_for_loader(&v.loader_type)));
        m.insert("packLogoURL".into(), s(BUNDLED_LOGO_URL));
        m.insert("packBackgroundURL".into(), s(BUNDLED_BACKGROUND_URL));
        m.insert("packMinRAMGB".into(), s("2"));
        m.insert("packUnstable".into(), Value::Bool(false));
        m.insert("packCustomDiscordRpc".into(), Value::Bool(false));
        m.insert("packModLoader".into(), s(&v.loader_type));
        m.insert("packModLoaderURL".into(), s(&v.installer_url));
        m.insert("packModLoaderHash".into(), s(""));
        let forge = v.loader_type == "forge";
        m.insert(
            "packForgeURL".into(),
            s(if forge { &v.installer_url } else { "" }),
        );
        m.insert("packForgeHash".into(), s(""));
        for k in [
            "packScanExclusions",
            "packMods",
            "packConfigs",
            "packResourcePacks",
            "packShaderPacks",
            "packInitialFiles",
        ] {
            m.insert(k.into(), Value::Array(Vec::new()));
        }
        Value::Object(m)
    }

    /// Writes the bare-loader manifest (if absent) and installs it. Returns its `file:` URL.
    pub async fn install_version(
        manager: &Arc<ModPackManager>,
        v: &LoaderVersion,
    ) -> Result<String> {
        let path = manifest_path_for(manager.paths(), v);
        if !path.exists() {
            if let Some(p) = path.parent() {
                fs::create_dir_all(p)?;
            }
            fs::write(
                &path,
                mmcl_manifest::gson::to_compact_string(&build_manifest(v)),
            )?;
            mmcl_log::std(&tf!(
                "log.loaderVersionManager.wroteManifest",
                v.display_name(),
                path.display().to_string()
            ));
        }
        let url = crate::fetch::path_to_file_url(&path);
        manager.install_by_url(&url).await.map_err(|e| match e {
            ModpackError::Manifest(m) => ModpackError::Manifest(m),
            other => other,
        })?;
        Ok(url)
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn forge_promotions() {
            let j = r#"{"homepage":"x","promos":{"1.12.2-latest":"14.23.5.2860","1.12.2-recommended":"14.23.5.2859",
                "1.20.1-latest":"47.3.0","1.20.1-recommended":"47.3.0","1.21-latest":"51.0.33"}}"#;
            let v = parse_forge_promotions(j).unwrap();
            let got: Vec<(&str, &str)> = v
                .iter()
                .map(|x| (x.mc_version.as_str(), x.loader_version.as_str()))
                .collect();
            assert_eq!(
                got,
                [
                    ("1.21", "51.0.33"),
                    ("1.20.1", "47.3.0"),
                    ("1.12.2", "14.23.5.2859"),
                    ("1.12.2", "14.23.5.2860")
                ]
            );
            assert_eq!(
                v[3].installer_url,
                "https://maven.minecraftforge.net/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860-installer.jar"
            );
            assert!(parse_forge_promotions("[]").is_none());
        }

        #[test]
        fn neoforge_metadata() {
            let xml = "<versions><version>20.4.80</version><version>21.0.5</version><version>21.1.77</version><version>junk</version></versions>";
            let v = parse_neoforge_metadata(xml);
            let got: Vec<(&str, &str)> = v
                .iter()
                .map(|x| (x.mc_version.as_str(), x.loader_version.as_str()))
                .collect();
            assert_eq!(
                got,
                [
                    ("1.21.1", "21.1.77"),
                    ("1.21", "21.0.5"),
                    ("1.20.4", "20.4.80")
                ]
            );
        }

        #[test]
        fn fabric_lists() {
            let games = r#"[{"version":"1.21.5","stable":true},{"version":"25w10a","stable":false},{"version":"1.21.4","stable":true}]"#;
            let loaders =
                r#"[{"version":"0.17.0-beta","stable":false},{"version":"0.16.10","stable":true}]"#;
            let v = parse_fabric(games, loaders).unwrap();
            assert_eq!(v.len(), 2);
            assert_eq!(v[0].loader_version, "0.16.10");
            assert_eq!(
                v[0].installer_url,
                "https://meta.fabricmc.net/v2/versions/loader/1.21.5/0.16.10/profile/json"
            );
        }

        #[test]
        fn manifest_names_and_shape() {
            let v = LoaderVersion {
                loader_type: "forge".into(),
                mc_version: "1.20.1".into(),
                loader_version: "47.3.0/x".into(),
                installer_url: "https://i/forge.jar".into(),
            };
            assert_eq!(v.display_name(), "Forge 1.20.1 (47.3.0/x)");
            assert_eq!(v.manifest_filename(), "loader-forge-1.20.1-47.3.0_x.json");
            let m = build_manifest(&v);
            assert_eq!(m["packForgeURL"], "https://i/forge.jar");
            let parsed = mmcl_manifest::PackManifest::from_value(&m).unwrap();
            assert_eq!(parsed.pack_mods.as_deref(), Some(&[][..]));
            assert!(!parsed.has_custom_logo());
        }
    }
}

/// Vanilla Minecraft versions from Mojang's version manifest.
pub mod vanilla {
    use std::fs;
    use std::sync::LazyLock;

    use mmcl_config::Config;
    use mmcl_core::DataPaths;
    use mmcl_i18n::t;
    use mmcl_mojang::version_manifest::{VersionEntry, VersionManifest};
    use parking_lot::Mutex;

    use crate::http::PackHttp;

    static CACHE: LazyLock<Mutex<Option<Vec<VersionEntry>>>> = LazyLock::new(Default::default);

    /// Every version, newest first (cached once loaded). Reads the launcher's cached
    /// `version.manifest`, downloading it when missing.
    pub async fn all_versions(paths: &DataPaths, http: &dyn PackHttp) -> Vec<VersionEntry> {
        if let Some(v) = CACHE.lock().clone() {
            return v;
        }
        let file = paths.mc_version_manifest_file();
        let bytes = match fs::read(&file) {
            Ok(b) => b,
            Err(_) => match http
                .get_bounded(
                    mmcl_core::consts::modpack::MINECRAFT_VERSION_MANIFEST_URL,
                    mmcl_net::MANIFEST_MAX_BYTES,
                )
                .await
            {
                Ok(body) => {
                    if let Some(p) = file.parent() {
                        let _ = fs::create_dir_all(p);
                    }
                    let _ = fs::write(&file, &body);
                    body.into_bytes()
                }
                Err(_) => {
                    mmcl_log::error_silent(&t!("log.vanillaVersionManager.downloadFailed"));
                    return Vec::new();
                }
            },
        };
        match VersionManifest::parse(&bytes) {
            Ok(m) => {
                *CACHE.lock() = Some(m.versions.clone());
                m.versions
            }
            Err(e) => {
                mmcl_log::error_silent(&t!("log.vanillaVersionManager.loadFailed"));
                mmcl_log::error_silent(&e.to_string());
                Vec::new()
            }
        }
    }

    /// Versions of one `type` (`release`, `snapshot`, ...), or every one for `"all"`.
    pub fn filter_by_type(all: &[VersionEntry], kind: &str) -> Vec<VersionEntry> {
        if kind == "all" {
            return all.to_vec();
        }
        all.iter().filter(|v| v.kind == kind).cloned().collect()
    }

    pub async fn versions_by_type(
        paths: &DataPaths,
        http: &dyn PackHttp,
        kind: &str,
    ) -> Vec<VersionEntry> {
        filter_by_type(&all_versions(paths, http).await, kind)
    }

    pub fn installed_version_ids(c: &Config) -> Vec<String> {
        c.installed_vanilla_versions()
    }
    pub fn install_version(c: &Config, id: &str) {
        let mut v = c.installed_vanilla_versions();
        if !v.iter().any(|x| x == id) {
            v.push(id.to_string());
            c.set_installed_vanilla_versions(&v);
        }
    }
    pub fn uninstall_version(c: &Config, id: &str) {
        let mut v = c.installed_vanilla_versions();
        if let Some(i) = v.iter().position(|x| x == id) {
            v.remove(i);
        }
        c.set_installed_vanilla_versions(&v);
    }
    pub fn is_installed(c: &Config, id: &str) -> bool {
        c.installed_vanilla_versions().iter().any(|x| x == id)
    }
    pub fn display_name(id: &str) -> String {
        format!("Minecraft {id}")
    }
    pub fn friendly_name(id: &str) -> String {
        format!("Minecraft {id} (Vanilla)")
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use mmcl_config::{ConfigStore, Document};

        #[test]
        fn install_uninstall_ids() {
            let c = Config::new(ConfigStore::in_memory(Document::new()));
            install_version(&c, "1.20.4");
            install_version(&c, "1.20.4");
            install_version(&c, "1.21");
            assert_eq!(installed_version_ids(&c), ["1.20.4", "1.21"]);
            assert!(is_installed(&c, "1.21"));
            uninstall_version(&c, "1.20.4");
            assert_eq!(installed_version_ids(&c), ["1.21"]);
            assert_eq!(friendly_name("1.21"), "Minecraft 1.21 (Vanilla)");
        }
    }
}

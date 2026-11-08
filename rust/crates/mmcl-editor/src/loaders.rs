// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Forge / NeoForge / Fabric version pickers: feed parsing (pure) and fetching.

use std::cmp::Ordering;
use std::collections::BTreeMap;
use std::sync::LazyLock;

use mmcl_i18n::tf;
use mmcl_manifest::version::compare_versions;
use regex::Regex;
use serde_json::Value;

use crate::backend::{EditorBackend, NetError};

/// Forge promotions feed.
pub const FORGE_PROMOS_URL: &str =
    "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
/// NeoForge Maven metadata.
pub const NEOFORGE_METADATA_URL: &str =
    "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
/// Fabric game versions.
pub const FABRIC_GAME_URL: &str = "https://meta.fabricmc.net/v2/versions/game";
/// Fabric loader versions.
pub const FABRIC_LOADER_URL: &str = "https://meta.fabricmc.net/v2/versions/loader";

/// One pickable version: what the list shows and the URL it sets.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VersionChoice {
    pub label: String,
    pub url: String,
}

/// Why a feed gave nothing pickable.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum LoaderFeedError {
    /// The request failed.
    #[error(transparent)]
    Net(#[from] NetError),
    /// The feed did not parse.
    #[error("{0}")]
    Parse(String),
    /// Fabric meta listed no loader versions.
    #[error("Fabric meta returned no loader versions")]
    NoFabricLoaders,
}

/// The Forge installer URL for `mc` + `ver`.
pub fn forge_installer_url(mc: &str, ver: &str) -> String {
    format!(
        "https://maven.minecraftforge.net/net/minecraftforge/forge/{mc}-{ver}/forge-{mc}-{ver}-installer.jar"
    )
}

/// The NeoForge installer URL for `v`.
pub fn neoforge_installer_url(v: &str) -> String {
    format!(
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/{v}/neoforge-{v}-installer.jar"
    )
}

/// The Fabric profile JSON URL for `mc` + `loader`.
pub fn fabric_profile_url(mc: &str, loader: &str) -> String {
    format!("https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/profile/json")
}

fn descending(a: &str, b: &str) -> Ordering {
    compare_versions(b, a)
}

/// Forge entries from `promotions_slim.json`. Lists every MC version that has a `-latest`
/// promotion (newest first); narrowed to `prefilter` only when that version is listed.
pub fn forge_entries(
    promos_json: &str,
    prefilter: &str,
) -> Result<Vec<VersionChoice>, LoaderFeedError> {
    let root: Value =
        serde_json::from_str(promos_json).map_err(|e| LoaderFeedError::Parse(e.to_string()))?;
    let promos = root
        .get("promos")
        .and_then(Value::as_object)
        .ok_or_else(|| LoaderFeedError::Parse("promos".into()))?;
    let mut recommended: BTreeMap<String, String> = BTreeMap::new();
    let mut latest: BTreeMap<String, String> = BTreeMap::new();
    for (k, v) in promos {
        let Some(ver) = v
            .as_str()
            .map(str::to_string)
            .or_else(|| v.as_number().map(|n| n.to_string()))
        else {
            continue;
        };
        if let Some(mc) = k.strip_suffix("-recommended") {
            recommended.insert(mc.to_string(), ver);
        } else if let Some(mc) = k.strip_suffix("-latest") {
            latest.insert(mc.to_string(), ver);
        }
    }
    let mut mcs: Vec<&String> = latest.keys().collect();
    mcs.sort_by(|a, b| descending(a, b));
    let pre = prefilter.trim();
    if !pre.is_empty() && mcs.iter().any(|m| m.as_str() == pre) {
        mcs.retain(|m| m.as_str() == pre);
    }
    let mut out = Vec::new();
    for mc in mcs {
        let rec = recommended.get(mc);
        if let Some(r) = rec {
            out.push(VersionChoice {
                label: tf!(
                    "editor.versionPick.forgeRecommended",
                    mc.as_str(),
                    r.as_str()
                ),
                url: forge_installer_url(mc, r),
            });
        }
        if let Some(l) = latest.get(mc)
            && rec != Some(l)
        {
            out.push(VersionChoice {
                label: tf!("editor.versionPick.forgeLatest", mc.as_str(), l.as_str()),
                url: forge_installer_url(mc, l),
            });
        }
    }
    Ok(out)
}

/// The Minecraft version a NeoForge version targets: `21.1.95` -> `1.21.1`, `20.4.190` ->
/// `1.20.4`, `21.0.x` -> `1.21`. From MC 26.1 on, NeoForge numbers follow Minecraft's own
/// year-based versions (`26.1.0.x` targets `26.1`), so a major of 25 or more maps to
/// `major.minor` without the `1.` prefix.
pub fn neoforge_mc_version(v: &str) -> Option<String> {
    let parts: Vec<&str> = v.split('.').collect();
    if parts.len() < 2 {
        return None;
    }
    let major: i32 = parts[0].trim().parse().ok()?;
    let minor: i32 = parts[1].trim().parse().ok()?;
    if major >= 25 {
        return Some(if minor == 0 {
            format!("{major}")
        } else {
            format!("{major}.{minor}")
        });
    }
    Some(if minor == 0 {
        format!("1.{major}")
    } else {
        format!("1.{major}.{minor}")
    })
}

static VERSION_TAG: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"<version>([^<]+)</version>").expect("static regex"));

/// NeoForge entries from `maven-metadata.xml`, newest first.
pub fn neoforge_entries(metadata_xml: &str) -> Vec<VersionChoice> {
    let mut versions: Vec<String> = VERSION_TAG
        .captures_iter(metadata_xml)
        .map(|c| c[1].trim().to_string())
        .filter(|v| !v.is_empty())
        .collect();
    versions.sort_by(|a, b| descending(a, b));
    versions
        .into_iter()
        .map(|v| VersionChoice {
            label: match neoforge_mc_version(&v) {
                Some(mc) => tf!("editor.versionPick.neoForgeWithMc", mc, v.as_str()),
                None => tf!("editor.versionPick.neoForge", v.as_str()),
            },
            url: neoforge_installer_url(&v),
        })
        .collect()
}

/// Fabric entries: every game version not marked `"stable": false`, paired with the first
/// stable loader (else the first loader).
pub fn fabric_entries(
    game_json: &str,
    loader_json: &str,
) -> Result<Vec<VersionChoice>, LoaderFeedError> {
    let parse = |s: &str| -> Result<Vec<Value>, LoaderFeedError> {
        match serde_json::from_str::<Value>(s) {
            Ok(Value::Array(a)) => Ok(a),
            Ok(_) => Err(LoaderFeedError::Parse("expected an array".into())),
            Err(e) => Err(LoaderFeedError::Parse(e.to_string())),
        }
    };
    let games = parse(game_json)?;
    let loaders = parse(loader_json)?;
    let version_of = |o: &Value| o.get("version").and_then(Value::as_str).map(str::to_string);
    let loader = loaders
        .iter()
        .find(|l| l.get("stable").and_then(Value::as_bool) == Some(true))
        .or_else(|| loaders.first())
        .and_then(version_of)
        .ok_or(LoaderFeedError::NoFabricLoaders)?;
    Ok(games
        .iter()
        .filter(|g| g.get("stable").and_then(Value::as_bool) != Some(false))
        .filter_map(version_of)
        .map(|mc| VersionChoice {
            label: tf!("editor.versionPick.fabric", mc.as_str(), loader.as_str()),
            url: fabric_profile_url(&mc, &loader),
        })
        .collect())
}

/// Fetches the Forge list.
pub async fn fetch_forge(
    backend: &dyn EditorBackend,
    prefilter: &str,
) -> Result<Vec<VersionChoice>, LoaderFeedError> {
    let text = backend.get_text(FORGE_PROMOS_URL).await?;
    forge_entries(&text, prefilter)
}

/// Fetches the NeoForge list.
pub async fn fetch_neoforge(
    backend: &dyn EditorBackend,
) -> Result<Vec<VersionChoice>, LoaderFeedError> {
    let text = backend.get_text(NEOFORGE_METADATA_URL).await?;
    Ok(neoforge_entries(&text))
}

/// Fetches the Fabric list.
pub async fn fetch_fabric(
    backend: &dyn EditorBackend,
) -> Result<Vec<VersionChoice>, LoaderFeedError> {
    let games = backend.get_text(FABRIC_GAME_URL).await?;
    let loaders = backend.get_text(FABRIC_LOADER_URL).await?;
    fabric_entries(&games, &loaders)
}

/// The installer's SHA-1 (what `packModLoaderHash` holds), from a temp download
/// `{label lower}_installer_*.jar`.
pub async fn installer_sha1(
    backend: &dyn EditorBackend,
    url: &str,
    label: &str,
) -> Result<String, NetError> {
    let prefix = format!("{}_installer_", label.to_lowercase());
    crate::hashing::remote_hashes(backend, url, &prefix, ".jar")
        .await
        .map(|h| h.sha1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::backend::fake::FakeBackend;

    const PROMOS: &str = r#"{"homepage":"x","promos":{
        "1.12.2-recommended":"14.23.5.2859","1.12.2-latest":"14.23.5.2860",
        "1.20.1-recommended":"47.3.0","1.20.1-latest":"47.3.0",
        "1.21.4-latest":"54.0.1",
        "1.7.10-recommended":"10.13.4.1614"}}"#;

    #[test]
    fn forge_promos_split_and_dedupe() {
        let e = forge_entries(PROMOS, "").unwrap();
        let urls: Vec<&str> = e.iter().map(|c| c.url.as_str()).collect();
        assert_eq!(
            urls,
            [
                forge_installer_url("1.21.4", "54.0.1").as_str(),
                forge_installer_url("1.20.1", "47.3.0").as_str(),
                forge_installer_url("1.12.2", "14.23.5.2859").as_str(),
                forge_installer_url("1.12.2", "14.23.5.2860").as_str(),
            ]
        );
        // 1.7.10 has only a recommended key: not listed (Java behaviour).
        assert!(!urls.iter().any(|u| u.contains("1.7.10")));
        assert!(e[0].label.contains("54.0.1"));
        assert!(e[2].label.contains("recommended"));
    }

    #[test]
    fn forge_prefilter_narrows_or_falls_back() {
        assert_eq!(forge_entries(PROMOS, " 1.20.1 ").unwrap().len(), 1);
        assert_eq!(forge_entries(PROMOS, "1.99").unwrap().len(), 4);
        assert!(forge_entries("[]", "").is_err());
        assert!(forge_entries("not json", "").is_err());
    }

    #[test]
    fn neoforge_mc_versions() {
        assert_eq!(neoforge_mc_version("21.1.95").as_deref(), Some("1.21.1"));
        assert_eq!(neoforge_mc_version("20.4.190").as_deref(), Some("1.20.4"));
        assert_eq!(neoforge_mc_version("21.0.143").as_deref(), Some("1.21"));
        assert_eq!(
            neoforge_mc_version("26.1.0.5-beta").as_deref(),
            Some("26.1")
        );
        assert_eq!(neoforge_mc_version("21"), None);
        assert_eq!(neoforge_mc_version("x.1.2"), None);
    }

    #[test]
    fn neoforge_metadata_sorted_newest_first() {
        let xml = "<metadata><versioning><versions><version>20.4.190</version>\
                   <version>21.1.95</version><version>21.0.143</version><version>bogus</version>\
                   </versions></versioning></metadata>";
        let e = neoforge_entries(xml);
        assert_eq!(e.len(), 4);
        assert!(e[0].url.contains("/21.1.95/neoforge-21.1.95-installer.jar"));
        assert!(e[0].label.contains("1.21.1"));
        let bogus = e.iter().find(|c| c.url.contains("bogus")).unwrap();
        assert_eq!(bogus.label, tf!("editor.versionPick.neoForge", "bogus"));
    }

    #[test]
    fn fabric_stable_filtering() {
        let games = r#"[{"version":"1.21.4","stable":true},{"version":"25w02a","stable":false},{"version":"1.20.1"}]"#;
        let loaders =
            r#"[{"version":"0.17.0-beta","stable":false},{"version":"0.16.10","stable":true}]"#;
        let e = fabric_entries(games, loaders).unwrap();
        assert_eq!(e.len(), 2);
        assert_eq!(e[0].url, fabric_profile_url("1.21.4", "0.16.10"));
        assert_eq!(e[1].url, fabric_profile_url("1.20.1", "0.16.10"));
        // No stable loader: the first one.
        let e = fabric_entries(games, r#"[{"version":"0.1"}]"#).unwrap();
        assert!(e[0].url.contains("/0.1/"));
        assert_eq!(
            fabric_entries(games, "[]"),
            Err(LoaderFeedError::NoFabricLoaders)
        );
    }

    #[test]
    fn fetching_uses_the_feeds_and_hashes_installers() {
        let b = FakeBackend::default()
            .text(FORGE_PROMOS_URL, PROMOS)
            .file("https://x/forge.jar", b"abc");
        let e = futures::executor::block_on(fetch_forge(&b, "")).unwrap();
        assert_eq!(e.len(), 4);
        assert!(futures::executor::block_on(fetch_neoforge(&b)).is_err());
        let h = futures::executor::block_on(installer_sha1(&b, "https://x/forge.jar", "Forge"))
            .unwrap();
        assert_eq!(h, "a9993e364706816aba3e25717850c26c9cd0d89d");
        assert!(
            futures::executor::block_on(installer_sha1(&b, "https://x/none.jar", "Forge")).is_err()
        );
    }
}

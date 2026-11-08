// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The runtime view of a hosted modpack manifest.

use chrono::NaiveDate;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::error::ManifestParseError;
use crate::files::ManifestFile;
use crate::gson::{self, Syntax};
use crate::lenient;
use crate::news::{LinkItem, NewsItem};
use crate::scan::ScanAcknowledgement;
use crate::server::ServerFavorite;
use crate::string_or_array::{self, StringOrArray};

/// The manifest's `packModLoader` value for Forge (also the default).
pub const MOD_LOADER_FORGE: &str = "forge";
/// The manifest's `packModLoader` value for NeoForge.
pub const MOD_LOADER_NEOFORGE: &str = "neoforge";
/// The manifest's `packModLoader` value for Fabric.
pub const MOD_LOADER_FABRIC: &str = "fabric";

/// Stable sentinel for the bundled default logo, written into generated manifests in place
/// of Java's install-location-dependent resource URL.
pub const BUNDLED_LOGO_URL: &str = "mmcl-bundled:logo.png";
/// Stable sentinel for the bundled default background.
pub const BUNDLED_BACKGROUND_URL: &str = "mmcl-bundled:background.png";

/// True for a `file:` / `jar:` URL (Java's bundled resource form) whose path ends in `/name`.
fn is_bundled_resource_url(url: &str, name: &str) -> bool {
    let lower = url.trim().to_ascii_lowercase();
    (lower.starts_with("file:") || lower.starts_with("jar:"))
        && lower.ends_with(&format!("/{name}"))
}

/// True when `url` names the bundled default logo: the Rust sentinel, or an old Java
/// resource URL ending in `/micaminecraftlauncher.png`.
pub fn is_bundled_logo_url(url: &str) -> bool {
    url.trim() == BUNDLED_LOGO_URL || is_bundled_resource_url(url, "micaminecraftlauncher.png")
}

/// True when `url` names the bundled default background: the Rust sentinel, or an old Java
/// resource URL ending in `/default_background.png`, or in `/micaminecraftlauncher.png`
/// (Java's `MODPACK_DEFAULT_BG_URL` falls back to the launcher icon when the background
/// resource is missing, and that URL was written into generated manifests too).
pub fn is_bundled_background_url(url: &str) -> bool {
    url.trim() == BUNDLED_BACKGROUND_URL
        || is_bundled_resource_url(url, "default_background.png")
        || is_bundled_resource_url(url, "micaminecraftlauncher.png")
}

/// Which loader a pack declares.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum ModLoaderKind {
    Forge,
    NeoForge,
    Fabric,
    /// A value the launcher does not support; loader construction fails with
    /// "Unsupported modloader type".
    Unknown(String),
}

impl ModLoaderKind {
    /// From the lower-cased type string ([`PackManifest::mod_loader_type`]).
    pub fn from_type(t: &str) -> Self {
        match t {
            MOD_LOADER_FORGE => ModLoaderKind::Forge,
            MOD_LOADER_NEOFORGE => ModLoaderKind::NeoForge,
            MOD_LOADER_FABRIC => ModLoaderKind::Fabric,
            other => ModLoaderKind::Unknown(other.to_string()),
        }
    }

    /// The manifest string for this loader.
    pub fn as_str(&self) -> &str {
        match self {
            ModLoaderKind::Forge => MOD_LOADER_FORGE,
            ModLoaderKind::NeoForge => MOD_LOADER_NEOFORGE,
            ModLoaderKind::Fabric => MOD_LOADER_FABRIC,
            ModLoaderKind::Unknown(s) => s,
        }
    }
}

/// A manifest plus which syntax its text needed.
#[derive(Clone, Debug, PartialEq)]
pub struct ParsedManifest {
    pub manifest: PackManifest,
    /// [`Syntax::Lenient`] means the caller should log [`crate::LOG_KEY_LENIENT_SYNTAX`].
    pub syntax: Syntax,
}

/// A hosted modpack manifest (`.mmcjson`), as the runtime reads it.
///
/// Every field is optional; absent keys keep Gson's defaults (`None`, `false`). Parse with
/// [`PackManifest::parse`] or [`PackManifest::from_value`], never with `serde_json::from_str`
/// directly, which would reject duplicate keys that Gson accepts.
///
/// File lists keep the `None` vs empty distinction: `packMods: null` (or absent) means "do
/// not touch the mods folder", `[]` means "a pack with no mods".
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct PackManifest {
    /// Editor-written format version (2 adds per-entry `modrinthSlug`). Not read at runtime.
    #[serde(
        rename = "manifestFormat",
        deserialize_with = "lenient::opt_i32",
        skip_serializing_if = "Option::is_none"
    )]
    pub manifest_format: Option<i32>,
    #[serde(
        rename = "packName",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_name: Option<String>,
    #[serde(
        rename = "packVersion",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_version: Option<String>,
    #[serde(
        rename = "packURL",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_url: Option<String>,
    #[serde(rename = "packUnstable", deserialize_with = "lenient::boolean")]
    pub pack_unstable: bool,
    #[serde(rename = "packCustomDiscordRpc", deserialize_with = "lenient::boolean")]
    pub pack_custom_discord_rpc: bool,
    #[serde(
        rename = "packLogoURL",
        deserialize_with = "string_or_array::optional",
        skip_serializing_if = "string_or_array::is_absent"
    )]
    pub pack_logo_url: Option<StringOrArray>,
    #[serde(
        rename = "packLogoSha1",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_logo_sha1: Option<String>,
    #[serde(
        rename = "packBackgroundURL",
        deserialize_with = "string_or_array::optional",
        skip_serializing_if = "string_or_array::is_absent"
    )]
    pub pack_background_url: Option<StringOrArray>,
    #[serde(
        rename = "packBackgroundSha1",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_background_sha1: Option<String>,
    /// Minimum RAM in GB, as written (`"6"`). Read it through [`PackManifest::min_ram_gb`].
    #[serde(
        rename = "packMinRAMGB",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_min_ram_gb: Option<String>,
    #[serde(
        rename = "packForgeURL",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_forge_url: Option<String>,
    #[serde(
        rename = "packForgeHash",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_forge_hash: Option<String>,
    #[serde(
        rename = "packModLoader",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_mod_loader: Option<String>,
    #[serde(
        rename = "packModLoaderURL",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_mod_loader_url: Option<String>,
    #[serde(
        rename = "packModLoaderHash",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_mod_loader_hash: Option<String>,
    /// Informational Minecraft version (editor and importers). Not read at launch.
    #[serde(
        rename = "packMinecraftVersion",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_minecraft_version: Option<String>,
    #[serde(
        rename = "packDefaultServerHost",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_default_server_host: Option<String>,
    #[serde(
        rename = "packDefaultServerPort",
        deserialize_with = "lenient::opt_i32",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_default_server_port: Option<i32>,
    #[serde(
        rename = "packDefaultServerName",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_default_server_name: Option<String>,
    #[serde(rename = "packImportedSkipSync", deserialize_with = "lenient::boolean")]
    pub pack_imported_skip_sync: bool,
    /// Untrusted: always filter through the security crate's exclusion policy before use.
    #[serde(
        rename = "packScanExclusions",
        deserialize_with = "lenient::string_list",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_scan_exclusions: Option<Vec<String>>,
    #[serde(
        rename = "packScanAcknowledgements",
        deserialize_with = "lenient::vec_skip_null",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_scan_acknowledgements: Option<Vec<ScanAcknowledgement>>,
    #[serde(
        rename = "packNews",
        deserialize_with = "lenient::vec_skip_null",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_news: Option<Vec<NewsItem>>,
    #[serde(
        rename = "packLinks",
        deserialize_with = "lenient::vec_skip_null",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_links: Option<Vec<LinkItem>>,
    #[serde(
        rename = "packMods",
        deserialize_with = "lenient::vec_skip_null",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_mods: Option<Vec<ManifestFile>>,
    #[serde(
        rename = "packConfigs",
        deserialize_with = "lenient::vec_skip_null",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_configs: Option<Vec<ManifestFile>>,
    #[serde(
        rename = "packResourcePacks",
        deserialize_with = "lenient::vec_skip_null",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_resource_packs: Option<Vec<ManifestFile>>,
    #[serde(
        rename = "packShaderPacks",
        deserialize_with = "lenient::vec_skip_null",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_shader_packs: Option<Vec<ManifestFile>>,
    #[serde(
        rename = "packInitialFiles",
        deserialize_with = "lenient::vec_skip_null",
        skip_serializing_if = "Option::is_none"
    )]
    pub pack_initial_files: Option<Vec<ManifestFile>>,
}

fn slice<T>(v: &Option<Vec<T>>) -> &[T] {
    v.as_deref().unwrap_or(&[])
}

impl PackManifest {
    /// Parses manifest text with Gson's leniency (see the crate docs).
    pub fn parse(body: &str) -> Result<Self, ManifestParseError> {
        Self::parse_with_syntax(body).map(|p| p.manifest)
    }

    /// [`PackManifest::parse`], also reporting whether the lenient fallback was needed.
    pub fn parse_with_syntax(body: &str) -> Result<ParsedManifest, ManifestParseError> {
        let (value, syntax) = gson::parse_lenient(body)?;
        Ok(ParsedManifest {
            manifest: Self::from_value(&value)?,
            syntax,
        })
    }

    /// Converts an already-parsed JSON value. Fails only when it is not an object.
    pub fn from_value(value: &Value) -> Result<Self, ManifestParseError> {
        if !value.is_object() {
            return Err(ManifestParseError::NotAnObject);
        }
        // Every field adapter is infallible, so a failure here would be a bug in this crate;
        // still report it rather than panic.
        serde_json::from_value(value.clone()).map_err(|e| ManifestParseError::Syntax {
            message: e.to_string(),
        })
    }

    /// The manifest as a JSON value (absent fields omitted, as Gson writes it).
    pub fn to_value(&self) -> Value {
        serde_json::to_value(self).unwrap_or(Value::Null)
    }

    /// Gson-style pretty JSON of [`PackManifest::to_value`].
    pub fn to_pretty_json(&self) -> String {
        gson::to_pretty_string(&self.to_value())
    }

    /// The pack name with every character outside ASCII `[A-Za-z0-9]` removed: the pack's
    /// folder name. Empty when the name is absent or has no such characters; callers must
    /// refuse to sync such a pack (its root would be the installs folder itself).
    pub fn sanitized_name(&self) -> String {
        crate::ascii_alnum_only(self.pack_name.as_deref().unwrap_or(""))
    }

    /// `"<name>: <version>"`, or `None` without a name. A missing version prints as `null`,
    /// as Java's `String.format` does.
    pub fn friendly_name(&self) -> Option<String> {
        let name = self.pack_name.as_deref()?;
        Some(format!(
            "{name}: {}",
            self.pack_version.as_deref().unwrap_or("null")
        ))
    }

    /// Minimum RAM in GB: blank or unparseable is 0, NaN or negative is 0, at most 1024.
    pub fn min_ram_gb(&self) -> f64 {
        let Some(raw) = crate::non_blank(self.pack_min_ram_gb.as_deref()) else {
            return 0.0;
        };
        match lenient::parse_java_double(raw) {
            Some(v) if v.is_nan() || v < 0.0 => 0.0,
            Some(v) => v.min(1024.0),
            None => 0.0,
        }
    }

    /// `packModLoader` trimmed and lower-cased, or `forge` when absent or blank. (Vanilla
    /// packs have no loader; that is the pack runtime's concern.)
    pub fn mod_loader_type(&self) -> String {
        crate::non_blank(self.pack_mod_loader.as_deref())
            .map(str::to_lowercase)
            .unwrap_or_else(|| MOD_LOADER_FORGE.to_string())
    }

    /// [`PackManifest::mod_loader_type`] as an enum.
    pub fn mod_loader_kind(&self) -> ModLoaderKind {
        ModLoaderKind::from_type(&self.mod_loader_type())
    }

    /// The loader artifact URL: `packModLoaderURL` when non-blank, else the legacy
    /// `packForgeURL` (which may itself be absent or blank).
    pub fn mod_loader_url(&self) -> Option<&str> {
        match crate::non_blank(self.pack_mod_loader_url.as_deref()) {
            Some(_) => self.pack_mod_loader_url.as_deref(),
            None => self.pack_forge_url.as_deref(),
        }
    }

    /// The loader artifact SHA-1: `packModLoaderHash` when non-blank, else `packForgeHash`.
    pub fn mod_loader_hash(&self) -> Option<&str> {
        match crate::non_blank(self.pack_mod_loader_hash.as_deref()) {
            Some(_) => self.pack_mod_loader_hash.as_deref(),
            None => self.pack_forge_hash.as_deref(),
        }
    }

    /// Whether the loader runs post-install steps (the "Game patching" progress row): true
    /// for everything but Fabric. Vanilla packs answer false in the pack runtime.
    pub fn uses_post_install_steps(&self) -> bool {
        self.mod_loader_kind() != ModLoaderKind::Fabric
    }

    /// The default quick-join server: `None` without a host; a port outside 1..=65535 (or
    /// none) is 25565; a blank name falls back to the host as written.
    pub fn default_server(&self) -> Option<ServerFavorite> {
        let raw_host = self.pack_default_server_host.as_deref()?;
        let host = crate::non_blank(Some(raw_host))?;
        let port = self
            .pack_default_server_port
            .filter(|p| (1..=65535).contains(p))
            .map_or(ServerFavorite::DEFAULT_PORT, |p| p as u16);
        let name = match crate::non_blank(self.pack_default_server_name.as_deref()) {
            Some(_) => self.pack_default_server_name.clone().unwrap_or_default(),
            None => raw_host.to_string(),
        };
        Some(ServerFavorite::new(name, host, port))
    }

    /// Scan exclusions as written (untrusted; filter before use).
    pub fn scan_exclusions(&self) -> &[String] {
        slice(&self.pack_scan_exclusions)
    }

    /// Scan acknowledgements.
    pub fn scan_acknowledgements(&self) -> &[ScanAcknowledgement] {
        slice(&self.pack_scan_acknowledgements)
    }

    /// Every news item.
    pub fn news(&self) -> &[NewsItem] {
        slice(&self.pack_news)
    }

    /// Every link.
    pub fn links(&self) -> &[LinkItem] {
        slice(&self.pack_links)
    }

    /// Renderable, unexpired news: pinned first, then newest first, undated last. Stable.
    pub fn visible_news(&self, today: NaiveDate) -> Vec<&NewsItem> {
        let mut visible: Vec<&NewsItem> = self
            .news()
            .iter()
            .filter(|n| n.is_renderable() && !n.is_expired(today))
            .collect();
        visible.sort_by(|a, b| {
            b.is_pinned()
                .cmp(&a.is_pinned())
                .then_with(|| b.sort_epoch().cmp(&a.sort_epoch()))
        });
        visible
    }

    /// Renderable links in manifest order.
    pub fn visible_links(&self) -> Vec<&LinkItem> {
        self.links().iter().filter(|l| l.is_renderable()).collect()
    }

    /// The mod list (empty when absent).
    pub fn mods(&self) -> &[ManifestFile] {
        slice(&self.pack_mods)
    }

    /// The config list (empty when absent).
    pub fn configs(&self) -> &[ManifestFile] {
        slice(&self.pack_configs)
    }

    /// The resource pack list (empty when absent).
    pub fn resource_packs(&self) -> &[ManifestFile] {
        slice(&self.pack_resource_packs)
    }

    /// The shader pack list (empty when absent).
    pub fn shader_packs(&self) -> &[ManifestFile] {
        slice(&self.pack_shader_packs)
    }

    /// The initial-file list (empty when absent).
    pub fn initial_files(&self) -> &[ManifestFile] {
        slice(&self.pack_initial_files)
    }

    /// Every declared logo URL, in order.
    pub fn logo_urls(&self) -> &[String] {
        self.pack_logo_url.as_ref().map_or(&[], StringOrArray::all)
    }

    /// Every declared background URL, in order.
    pub fn background_urls(&self) -> &[String] {
        self.pack_background_url
            .as_ref()
            .map_or(&[], StringOrArray::all)
    }

    /// The first logo URL.
    pub fn logo_url(&self) -> Option<&str> {
        self.logo_urls().first().map(String::as_str)
    }

    /// The first background URL.
    pub fn background_url(&self) -> Option<&str> {
        self.background_urls().first().map(String::as_str)
    }

    /// True when any declared logo is not the bundled default.
    pub fn has_custom_logo(&self) -> bool {
        self.logo_urls().iter().any(|u| !is_bundled_logo_url(u))
    }

    /// True when any declared background is not the bundled default.
    pub fn has_custom_background(&self) -> bool {
        self.background_urls()
            .iter()
            .any(|u| !is_bundled_background_url(u))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ALTO: &str = r#"{
      "packName": "Alto",
      "packVersion": "26.11.4.3",
      "packCustomDiscordRpc": true,
      "packURL": "https://minecraft.micatechnologies.com",
      "packLogoURL": "https://example.test/alto/pack.png",
      "packLogoSha1": "54787d3bf0957c79da8b9e097be07616d8244b0e",
      "packBackgroundURL": ["https://example.test/c.png", "https://example.test/b.png", "https://example.test/a.png"],
      "packMinRAMGB": "6",
      "packDefaultServerHost": "alto.example.com",
      "packDefaultServerPort": 8585,
      "packDefaultServerName": "Alto Minecraft Server",
      "packForgeURL": "https://maven.minecraftforge.net/forge-installer.jar",
      "packForgeHash": "5e8a91f71ef3d1f77de3f8d3261aedcc2f551c9d",
      "packScanExclusions": ["dynmap"],
      "packScanAcknowledgements": [{ "innerSha256": "7a43", "kind": "LAUNCHER_CREDENTIAL_FILE_REF",
                                     "locator": "optifine/Installer.updateLauncherJson", "reason": "r" }],
      "packNews": [{ "id": "2026-11-04-v26.11.4.3", "title": "T", "body": "B", "date": "2026-11-04",
                     "type": "update", "url": "https://github.com/x" }],
      "packLinks": [{ "title": "Website", "url": "https://minecraft.micatechnologies.com", "description": "d" }],
      "packMods": [{ "name": "3D Skin Layers", "remote": "https://cdn.modrinth.com/x.jar", "local": "3d-skin-layers.jar",
                     "sha1": "332d", "clientReq": true, "serverReq": false, "sha256": "d1b3" }],
      "packConfigs": [{ "remote": "https://example.test/csm_pack.cfg", "local": "csm_pack.cfg", "sha1": "8d50",
                        "clientReq": true, "serverReq": false, "sha256": "3386" }],
      "packResourcePacks": [{ "remote": "https://example.test/rp.zip", "local": "rp.zip", "sha1": "9ea3", "sha256": "4a4b" }],
      "packInitialFiles": [{ "remote": "https://example.test/options.txt", "local": "options.txt", "sha1": "-1",
                             "clientReq": true, "serverReq": false, "sha256": "-1" }]
    }"#;

    fn d(y: i32, m: u32, day: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, day).unwrap()
    }

    #[test]
    fn parses_the_alto_shape() {
        let p = PackManifest::parse_with_syntax(ALTO).unwrap();
        assert_eq!(p.syntax, Syntax::Standard);
        let m = p.manifest;
        assert_eq!(m.pack_name.as_deref(), Some("Alto"));
        assert!(m.pack_custom_discord_rpc);
        assert!(!m.pack_unstable);
        assert_eq!(m.background_urls().len(), 3);
        assert_eq!(m.logo_url(), Some("https://example.test/alto/pack.png"));
        assert_eq!(m.min_ram_gb(), 6.0);
        assert_eq!(m.mod_loader_type(), "forge");
        assert_eq!(m.mod_loader_kind(), ModLoaderKind::Forge);
        assert_eq!(
            m.mod_loader_url(),
            Some("https://maven.minecraftforge.net/forge-installer.jar")
        );
        assert!(m.uses_post_install_steps());
        assert_eq!(
            m.default_server(),
            Some(ServerFavorite::new(
                "Alto Minecraft Server",
                "alto.example.com",
                8585
            ))
        );
        assert_eq!(m.scan_exclusions(), ["dynmap"]);
        assert_eq!(m.scan_acknowledgements().len(), 1);
        assert_eq!(m.mods().len(), 1);
        assert!(m.mods()[0].client_req && !m.mods()[0].server_req);
        assert_eq!(m.resource_packs()[0].strongest_hash().unwrap().1, "4a4b");
        assert!(!m.initial_files()[0].has_any_hash());
        assert!(m.pack_shader_packs.is_none());
        assert_eq!(m.visible_news(d(2026, 11, 7)).len(), 1);
        assert_eq!(m.visible_links().len(), 1);
        assert_eq!(m.friendly_name().as_deref(), Some("Alto: 26.11.4.3"));
        assert_eq!(m.sanitized_name(), "Alto");
        assert!(m.has_custom_logo() && m.has_custom_background());
    }

    #[test]
    fn number_as_string_and_string_as_bool() {
        let m = PackManifest::parse(
            r#"{"packMinRAMGB": 6, "packUnstable": "true", "packCustomDiscordRpc": "yes",
                "packDefaultServerHost": "h", "packDefaultServerPort": "8585", "packVersion": 1.5}"#,
        )
        .unwrap();
        assert_eq!(m.pack_min_ram_gb.as_deref(), Some("6"));
        assert!(m.pack_unstable);
        assert!(!m.pack_custom_discord_rpc);
        assert_eq!(m.pack_default_server_port, Some(8585));
        assert_eq!(m.pack_version.as_deref(), Some("1.5"));
    }

    #[test]
    fn duplicate_keys_last_wins() {
        let m = PackManifest::parse(r#"{"packName": "first", "packName": "second"}"#).unwrap();
        assert_eq!(m.pack_name.as_deref(), Some("second"));
    }

    #[test]
    fn null_is_absent_and_null_entries_are_dropped() {
        let m = PackManifest::parse(
            r#"{"packName": null, "packMods": [null, {"local": "a.jar"}, null], "packConfigs": null,
                "packScanExclusions": ["a", null, 3], "packNews": [null]}"#,
        )
        .unwrap();
        assert_eq!(m.pack_name, None);
        assert_eq!(m.mods().len(), 1);
        assert!(m.pack_configs.is_none());
        assert_eq!(m.scan_exclusions(), ["a", "3"]);
        assert_eq!(m.pack_news.as_deref().map(<[NewsItem]>::len), Some(0));
    }

    #[test]
    fn null_vs_empty_mod_list() {
        assert!(PackManifest::parse("{}").unwrap().pack_mods.is_none());
        assert_eq!(
            PackManifest::parse(r#"{"packMods": []}"#)
                .unwrap()
                .pack_mods,
            Some(vec![])
        );
    }

    #[test]
    fn wrong_types_read_as_absent() {
        let m = PackManifest::parse(
            r#"{"packName": {"a": 1}, "packMods": "x", "packDefaultServerPort": 85.5, "packLogoURL": {"a": 1}}"#,
        )
        .unwrap();
        assert_eq!(m.pack_name, None);
        assert!(m.pack_mods.is_none());
        assert_eq!(m.pack_default_server_port, None);
        assert_eq!(m.pack_logo_url, Some(StringOrArray::default()));
    }

    #[test]
    fn comment_bearing_json_falls_back_to_lenient() {
        let p = PackManifest::parse_with_syntax(
            "{ // a comment\n 'packName': 'Lenient', packMods: [], }",
        )
        .unwrap();
        assert_eq!(p.syntax, Syntax::Lenient);
        assert_eq!(p.manifest.pack_name.as_deref(), Some("Lenient"));
        assert_eq!(p.manifest.pack_mods, Some(vec![]));
    }

    #[test]
    fn non_objects_are_rejected() {
        assert_eq!(
            PackManifest::parse("[1, 2]"),
            Err(ManifestParseError::NotAnObject)
        );
        assert!(matches!(
            PackManifest::parse("nope"),
            Err(ManifestParseError::Syntax { .. })
        ));
    }

    #[test]
    fn min_ram_is_defensive() {
        let ram = |v: &str| {
            PackManifest {
                pack_min_ram_gb: Some(v.into()),
                ..Default::default()
            }
            .min_ram_gb()
        };
        assert_eq!(ram("lots"), 0.0);
        assert_eq!(ram(""), 0.0);
        assert_eq!(ram("-4"), 0.0);
        assert_eq!(ram("NaN"), 0.0);
        assert_eq!(ram("1.5"), 1.5);
        assert_eq!(ram(" 8 "), 8.0);
        assert_eq!(ram("99999"), 1024.0);
        assert_eq!(ram("Infinity"), 1024.0);
        assert_eq!(PackManifest::default().min_ram_gb(), 0.0);
    }

    #[test]
    fn loader_resolution_and_fallbacks() {
        let m = PackManifest::parse(
            r#"{"packModLoader": " Fabric ", "packModLoaderURL": "https://meta.fabricmc.net/x",
                "packModLoaderHash": "", "packForgeURL": "", "packForgeHash": "legacy"}"#,
        )
        .unwrap();
        assert_eq!(m.mod_loader_type(), "fabric");
        assert_eq!(m.mod_loader_kind(), ModLoaderKind::Fabric);
        assert!(!m.uses_post_install_steps());
        assert_eq!(m.mod_loader_url(), Some("https://meta.fabricmc.net/x"));
        assert_eq!(m.mod_loader_hash(), Some("legacy"));
        let blank =
            PackManifest::parse(r#"{"packModLoader": "  ", "packModLoaderURL": " "}"#).unwrap();
        assert_eq!(blank.mod_loader_type(), "forge");
        assert_eq!(blank.mod_loader_url(), None);
        let odd = PackManifest::parse(r#"{"packModLoader": "Quilt"}"#).unwrap();
        assert_eq!(
            odd.mod_loader_kind(),
            ModLoaderKind::Unknown("quilt".into())
        );
        assert!(odd.uses_post_install_steps());
    }

    #[test]
    fn default_server_rules() {
        let server = |host: Option<&str>, port: Option<i32>, name: Option<&str>| {
            PackManifest {
                pack_default_server_host: host.map(Into::into),
                pack_default_server_port: port,
                pack_default_server_name: name.map(Into::into),
                ..Default::default()
            }
            .default_server()
        };
        assert_eq!(server(None, None, None), None);
        assert_eq!(server(Some("  "), Some(1), None), None);
        assert_eq!(
            server(Some(" h "), Some(70000), Some(" ")),
            Some(ServerFavorite::new(" h ", "h", 25565))
        );
        assert_eq!(server(Some("h"), Some(0), None).unwrap().port, 25565);
        assert_eq!(server(Some("h"), Some(25566), Some("N")).unwrap().name, "N");
    }

    #[test]
    fn visible_news_order() {
        let item = |id: &str, date: Option<&str>, pinned: bool, expires: Option<&str>| NewsItem {
            id: Some(id.into()),
            title: Some(id.into()),
            date: date.map(Into::into),
            pinned,
            expires: expires.map(Into::into),
            ..Default::default()
        };
        let m = PackManifest {
            pack_news: Some(vec![
                item("old", Some("2026-01-01"), false, None),
                item("undated", None, false, None),
                item("new", Some("2026-10-01"), false, None),
                item("pinned-old", Some("2025-01-01"), true, None),
                item("expired", Some("2026-11-01"), false, Some("2026-11-05")),
                NewsItem::default(),
            ]),
            ..Default::default()
        };
        let ids: Vec<_> = m
            .visible_news(d(2026, 11, 7))
            .iter()
            .map(|n| n.id().unwrap().to_string())
            .collect();
        assert_eq!(ids, ["pinned-old", "new", "old", "undated"]);
    }

    #[test]
    fn sanitized_and_friendly_names() {
        let m = PackManifest {
            pack_name: Some("My Pack v2 (\u{e9}t\u{e9})!".into()),
            ..Default::default()
        };
        assert_eq!(m.sanitized_name(), "MyPackv2t");
        assert_eq!(
            m.friendly_name().as_deref(),
            Some("My Pack v2 (\u{e9}t\u{e9})!: null")
        );
        assert_eq!(PackManifest::default().sanitized_name(), "");
        assert_eq!(PackManifest::default().friendly_name(), None);
    }

    #[test]
    fn bundled_defaults_are_not_custom() {
        let m = PackManifest {
            pack_logo_url: Some(StringOrArray::of([
                BUNDLED_LOGO_URL,
                "jar:file:/C:/Program%20Files/Mica/app.jar!/micaminecraftlauncher.png",
                "file:/opt/mica/micaminecraftlauncher.png",
            ])),
            pack_background_url: Some(StringOrArray::of([BUNDLED_BACKGROUND_URL])),
            ..Default::default()
        };
        assert!(!m.has_custom_logo());
        assert!(!m.has_custom_background());
        assert!(!PackManifest::default().has_custom_logo());
        assert!(is_bundled_background_url("FILE:/x/default_background.png"));
        assert!(is_bundled_background_url(
            "jar:file:/opt/mica/app.jar!/micaminecraftlauncher.png"
        ));
        assert!(!is_bundled_background_url(
            "https://example.test/default_background.png"
        ));
        assert!(!is_bundled_logo_url(
            "https://example.test/micaminecraftlauncher.png"
        ));
    }

    #[test]
    fn serialization_round_trips() {
        let m = PackManifest::parse(ALTO).unwrap();
        let back = PackManifest::parse(&m.to_pretty_json()).unwrap();
        assert_eq!(back, m);
        let v = m.to_value();
        assert!(v.get("packShaderPacks").is_none());
        assert_eq!(
            v["packLogoURL"],
            Value::String("https://example.test/alto/pack.png".into())
        );
        assert!(v["packBackgroundURL"].is_array());
        assert_eq!(v["packImportedSkipSync"], Value::Bool(false));
    }
}

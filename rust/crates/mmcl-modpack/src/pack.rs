// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The runtime pack (Java `GameModPack` + `GameModPackMetadata`): an immutable
//! [`PackManifest`] plus identity, kind and per-instance caches.
//!
//! A pack re-fetched from the network is a new object, so its caches (loader, library
//! manifest, session-verified files) start empty and everything is re-verified.
//!
//! The loader lives in `mmcl-launch`, which depends on this crate. To avoid the cycle this
//! crate defines [`ModLoaderFactory`] and [`PackLoader`]; `mmcl-launch` registers a factory at
//! startup ([`set_mod_loader_factory`]) and the pack memoises what it builds.

use std::any::Any;
use std::collections::HashSet;
use std::fmt;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, SystemTime};

use chrono::NaiveDate;
use futures::future::BoxFuture;
use indexmap::IndexSet;
use mmcl_core::DataPaths;
use mmcl_i18n::{t, tf};
use mmcl_manifest::pack::{BUNDLED_BACKGROUND_URL, BUNDLED_LOGO_URL};
use mmcl_manifest::{NewsItem, PackManifest, ServerFavorite, Side, StringOrArray};
use parking_lot::{Mutex, RwLock};

use crate::context::PrepareContext;
use crate::environment::PackEnvironment;
use crate::error::{ModpackError, Result};
use crate::index::IndexEntry;
use crate::managed::{SessionVerified, side_of};
use crate::sidecar::{
    INSTALLED_VERSION_FILE, LAUNCH_HISTORY_FILE, PackDotFile, SEEN_NEWS_FILE, servers,
};
use crate::verify_state::VerifyState;

/// What kind of pack object this is.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PackKind {
    /// A fully loaded manifest.
    Manifest,
    /// The vanilla "pack" for one Minecraft version.
    Vanilla { mc_version: String },
    /// The manifest could not be loaded. Never synced or launched.
    Failed { error: String },
    /// Built from an install-index entry: only card fields are set. Never synced or launched
    /// until a full load replaces it.
    Stub,
    /// The GUI's "no selection" placeholder.
    Placeholder,
}

/// The loader facts this crate needs; `mmcl-launch`'s loaders implement it.
pub trait PackLoader: Send + Sync {
    /// Display name (`Forge`, `NeoForge`, `Fabric`).
    fn loader_name(&self) -> String;
    /// The loader's own version.
    fn loader_version(&self) -> String;
    /// The Minecraft version the loader targets.
    fn minecraft_version(&self) -> String;
    /// The concrete loader, for the launch crate to downcast.
    fn as_any(&self) -> &dyn Any;
}

/// Builds a pack's loader (registered by `mmcl-launch`).
pub trait ModLoaderFactory: Send + Sync {
    /// Opens the loader for `pack` (which is never vanilla, failed or a stub).
    fn create<'a>(
        &'a self,
        pack: &'a Arc<GameModPack>,
    ) -> BoxFuture<'a, Result<Arc<dyn PackLoader>>>;
}

static LOADER_FACTORY: OnceLock<Arc<dyn ModLoaderFactory>> = OnceLock::new();

/// Registers the process-wide loader factory. `false` when one is already registered.
pub fn set_mod_loader_factory(factory: Arc<dyn ModLoaderFactory>) -> bool {
    LOADER_FACTORY.set(factory).is_ok()
}

/// The registered loader factory.
pub fn mod_loader_factory() -> Option<Arc<dyn ModLoaderFactory>> {
    LOADER_FACTORY.get().cloned()
}

/// `.launch_history`: last played, total play time, launch count.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct LaunchHistory {
    pub last_played_ms: i64,
    pub total_play_time_ms: i64,
    pub launch_count: i32,
}

impl LaunchHistory {
    /// Parses the three-line file; any error gives zeros.
    pub fn parse(text: &str) -> LaunchHistory {
        let lines: Vec<&str> = text.trim().split('\n').map(str::trim).collect();
        let parsed = (|| {
            Some(LaunchHistory {
                last_played_ms: lines.first()?.parse().ok()?,
                total_play_time_ms: lines.get(1)?.parse().ok()?,
                launch_count: lines.get(2)?.parse().ok()?,
            })
        })();
        parsed.unwrap_or_default()
    }

    /// The file text: `last\ntotal\ncount\n`.
    pub fn render(&self) -> String {
        format!(
            "{}\n{}\n{}\n",
            self.last_played_ms, self.total_play_time_ms, self.launch_count
        )
    }
}

fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

/// One modpack at runtime.
pub struct GameModPack {
    manifest: PackManifest,
    kind: PackKind,
    manifest_url: Option<String>,
    manifest_sha256: Option<String>,
    paths: Arc<DataPaths>,
    prepare_context: RwLock<Arc<PrepareContext>>,
    quick_join: Mutex<Option<ServerFavorite>>,
    env: OnceLock<PackEnvironment>,
    loader: tokio::sync::Mutex<Option<Arc<dyn PackLoader>>>,
    loader_snapshot: Mutex<Option<Arc<dyn PackLoader>>>,
    library_manifest: Mutex<Option<Arc<dyn Any + Send + Sync>>>,
    update_available: Mutex<Option<bool>>,
    history: Mutex<Option<LaunchHistory>>,
    session: Arc<SessionVerified>,
}

impl fmt::Debug for GameModPack {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("GameModPack")
            .field("name", &self.manifest.pack_name)
            .field("version", &self.manifest.pack_version)
            .field("kind", &self.kind)
            .field("manifest_url", &self.manifest_url)
            .finish()
    }
}

impl fmt::Display for GameModPack {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.friendly_name().unwrap_or_default())
    }
}

/// The URL shortened for a failed pack's name: `"..." + last 37 chars` past 40 chars.
fn short_url(url: &str) -> String {
    let chars: Vec<char> = url.chars().collect();
    if chars.len() > 40 {
        format!(
            "...{}",
            chars[chars.len() - 37..].iter().collect::<String>()
        )
    } else {
        url.to_string()
    }
}

impl GameModPack {
    fn build(
        paths: Arc<DataPaths>,
        manifest: PackManifest,
        kind: PackKind,
        manifest_url: Option<String>,
        manifest_sha256: Option<String>,
    ) -> Arc<GameModPack> {
        Arc::new(GameModPack {
            manifest,
            kind,
            manifest_url,
            manifest_sha256,
            paths,
            prepare_context: RwLock::new(Arc::new(PrepareContext::NONE)),
            quick_join: Mutex::new(None),
            env: OnceLock::new(),
            loader: tokio::sync::Mutex::new(None),
            loader_snapshot: Mutex::new(None),
            library_manifest: Mutex::new(None),
            update_available: Mutex::new(None),
            history: Mutex::new(None),
            session: SessionVerified::new(),
        })
    }

    /// A pack from a loaded manifest. `sha256` is the body's hash (see
    /// [`crate::verify_state::compute_manifest_sha256`]).
    pub fn from_manifest(
        paths: Arc<DataPaths>,
        manifest: PackManifest,
        manifest_url: Option<String>,
        sha256: Option<String>,
    ) -> Arc<GameModPack> {
        Self::build(paths, manifest, PackKind::Manifest, manifest_url, sha256)
    }

    /// The vanilla pack for `version_id`.
    pub fn vanilla(paths: Arc<DataPaths>, version_id: &str) -> Arc<GameModPack> {
        let manifest = PackManifest {
            pack_name: Some(tf!("modpack.vanilla.name", version_id)),
            pack_version: Some(version_id.to_string()),
            pack_url: Some("https://minecraft.net".into()),
            pack_logo_url: Some(StringOrArray::of([BUNDLED_LOGO_URL])),
            pack_background_url: Some(StringOrArray::of([BUNDLED_BACKGROUND_URL])),
            pack_min_ram_gb: Some("2".into()),
            pack_mods: Some(Vec::new()),
            ..Default::default()
        };
        Self::build(
            paths,
            manifest,
            PackKind::Vanilla {
                mc_version: version_id.to_string(),
            },
            None,
            None,
        )
    }

    /// The placeholder for a manifest that could not be loaded. It has a name, so it shows in
    /// lists, but it is never synced or launched.
    pub fn failed(paths: Arc<DataPaths>, manifest_url: &str, error: &str) -> Arc<GameModPack> {
        let manifest = PackManifest {
            pack_name: Some(tf!("modpack.failed.name", short_url(manifest_url))),
            pack_version: Some(t!("modpack.failed.version").into_owned()),
            pack_logo_url: Some(StringOrArray::of([BUNDLED_LOGO_URL])),
            pack_background_url: Some(StringOrArray::of([BUNDLED_BACKGROUND_URL])),
            pack_min_ram_gb: Some("2".into()),
            ..Default::default()
        };
        Self::build(
            paths,
            manifest,
            PackKind::Failed {
                error: error.to_string(),
            },
            Some(manifest_url.to_string()),
            None,
        )
    }

    /// A card-only pack from an install-index entry.
    pub fn stub_from_index(paths: Arc<DataPaths>, url: &str, e: &IndexEntry) -> Arc<GameModPack> {
        let manifest = PackManifest {
            pack_name: e.pack_name.clone(),
            pack_version: e.pack_version.clone(),
            pack_url: e.pack_url.clone(),
            pack_logo_url: e.pack_logo_url.clone(),
            pack_logo_sha1: e.pack_logo_sha1.clone(),
            pack_background_url: e.pack_background_url.clone(),
            pack_background_sha1: e.pack_background_sha1.clone(),
            pack_min_ram_gb: e.pack_min_ram_gb.clone(),
            pack_unstable: e.pack_unstable,
            pack_custom_discord_rpc: e.pack_custom_discord_rpc,
            ..Default::default()
        };
        Self::build(paths, manifest, PackKind::Stub, Some(url.to_string()), None)
    }

    /// The GUI's "no selection" placeholder.
    pub fn none(paths: Arc<DataPaths>) -> Arc<GameModPack> {
        let manifest = PackManifest {
            pack_name: Some(t!("modpack.none.name").into_owned()),
            pack_version: Some(t!("modpack.none.version").into_owned()),
            pack_logo_url: Some(StringOrArray::of([
                mmcl_core::consts::gui::URL_MINECRAFT_NO_MOD_PACK_IMAGE,
            ])),
            ..Default::default()
        };
        Self::build(paths, manifest, PackKind::Placeholder, None, None)
    }

    // ---- identity ------------------------------------------------------------------------

    pub fn manifest(&self) -> &PackManifest {
        &self.manifest
    }
    pub fn kind(&self) -> &PackKind {
        &self.kind
    }
    pub fn paths(&self) -> &Arc<DataPaths> {
        &self.paths
    }
    /// The side this pack is prepared for (from the data paths' game mode).
    pub fn side(&self) -> Side {
        side_of(self.paths.mode())
    }
    /// The URL the pack was loaded from (`https:` or `file:`); set even for failed packs.
    pub fn manifest_url(&self) -> Option<&str> {
        self.manifest_url.as_deref()
    }
    /// Lowercase hex SHA-256 of the manifest body as loaded.
    pub fn manifest_sha256(&self) -> Option<&str> {
        self.manifest_sha256.as_deref()
    }
    pub fn is_vanilla(&self) -> bool {
        matches!(self.kind, PackKind::Vanilla { .. })
    }
    pub fn vanilla_mc_version(&self) -> Option<&str> {
        match &self.kind {
            PackKind::Vanilla { mc_version } => Some(mc_version),
            _ => None,
        }
    }
    pub fn is_failed_load(&self) -> bool {
        matches!(self.kind, PackKind::Failed { .. })
    }
    /// The load error of a failed pack.
    pub fn failed_error(&self) -> Option<&str> {
        match &self.kind {
            PackKind::Failed { error } => Some(error),
            _ => None,
        }
    }
    pub fn is_stub(&self) -> bool {
        matches!(self.kind, PackKind::Stub)
    }
    /// `false` for failed, stub and placeholder packs.
    pub fn is_launchable(&self) -> bool {
        matches!(self.kind, PackKind::Manifest | PackKind::Vanilla { .. })
    }
    /// The pack name (`""` when absent).
    pub fn name(&self) -> String {
        self.manifest.pack_name.clone().unwrap_or_default()
    }
    /// The pack version (`""` when absent).
    pub fn version(&self) -> String {
        self.manifest.pack_version.clone().unwrap_or_default()
    }
    /// `"<name>: <version>"`.
    pub fn friendly_name(&self) -> Option<String> {
        self.manifest.friendly_name()
    }

    /// The folder name: ASCII letters and digits of the name. Vanilla packs use the fixed,
    /// non-localized `Minecraft<version>` so a translation can never move the folder.
    pub fn sanitized_name(&self) -> String {
        match &self.kind {
            PackKind::Vanilla { mc_version } => format!("Minecraft{mc_version}")
                .chars()
                .filter(char::is_ascii_alphanumeric)
                .collect(),
            PackKind::Placeholder => String::new(),
            _ => self.manifest.sanitized_name(),
        }
    }

    /// `<root>/installs/<sanitized name>`.
    pub fn pack_root(&self) -> PathBuf {
        self.paths.pack_install_dir(&self.sanitized_name())
    }

    /// [`GameModPack::pack_root`], refusing a name that sanitizes to nothing (whose "root"
    /// would be the installs folder itself).
    pub fn checked_pack_root(&self) -> Result<PathBuf> {
        if self.sanitized_name().is_empty() {
            return Err(ModpackError::EmptyFolderName(self.name()));
        }
        Ok(self.pack_root())
    }

    pub fn bin_folder(&self) -> PathBuf {
        self.pack_root().join("bin")
    }
    pub fn mods_dir(&self) -> PathBuf {
        self.pack_root().join("mods")
    }
    pub fn config_dir(&self) -> PathBuf {
        self.pack_root().join("config")
    }

    /// The per-pack settings key: the manifest URL, or `vanilla:<version>`.
    pub fn settings_key(&self) -> Option<String> {
        if let Some(u) = self
            .manifest_url
            .as_deref()
            .filter(|u| !u.trim().is_empty())
        {
            return Some(u.to_string());
        }
        self.vanilla_mc_version().map(|v| format!("vanilla:{v}"))
    }

    // ---- loader facts --------------------------------------------------------------------

    /// `forge` / `neoforge` / `fabric` (or what the manifest says), `None` for vanilla.
    pub fn mod_loader_type(&self) -> Option<String> {
        (!self.is_vanilla()).then(|| self.manifest.mod_loader_type())
    }
    pub fn mod_loader_url(&self) -> Option<&str> {
        if self.is_vanilla() {
            return None;
        }
        self.manifest.mod_loader_url()
    }
    pub fn mod_loader_hash(&self) -> Option<&str> {
        if self.is_vanilla() {
            return None;
        }
        self.manifest.mod_loader_hash()
    }
    /// Whether the "Game patching" step exists: Forge and NeoForge only.
    pub fn uses_post_install_steps(&self) -> bool {
        !self.is_vanilla() && self.manifest.uses_post_install_steps()
    }
    pub fn min_ram_gb(&self) -> f64 {
        self.manifest.min_ram_gb()
    }

    /// The memoised loader (built through the registered factory). `None` for vanilla.
    pub async fn mod_loader(self: &Arc<Self>) -> Result<Option<Arc<dyn PackLoader>>> {
        match mod_loader_factory() {
            Some(f) => self.mod_loader_with(f.as_ref()).await,
            None if self.is_vanilla() => Ok(None),
            None => Err(ModpackError::NoLoaderFactory),
        }
    }

    /// [`GameModPack::mod_loader`] with an explicit factory.
    pub async fn mod_loader_with(
        self: &Arc<Self>,
        factory: &dyn ModLoaderFactory,
    ) -> Result<Option<Arc<dyn PackLoader>>> {
        match &self.kind {
            PackKind::Vanilla { .. } => return Ok(None),
            PackKind::Failed { .. } => return Err(ModpackError::ManifestFetchFailed(self.name())),
            PackKind::Stub | PackKind::Placeholder => {
                return Err(ModpackError::ManifestStub(self.name()));
            }
            PackKind::Manifest => {}
        }
        let mut slot = self.loader.lock().await;
        if let Some(l) = slot.as_ref() {
            return Ok(Some(l.clone()));
        }
        let built = factory.create(self).await?;
        *slot = Some(built.clone());
        *self.loader_snapshot.lock() = Some(built.clone());
        Ok(Some(built))
    }

    /// The loader if it was already built (never builds one).
    pub fn cached_mod_loader(&self) -> Option<Arc<dyn PackLoader>> {
        self.loader_snapshot.lock().clone()
    }

    /// The Minecraft version without building anything: vanilla's version, the built loader's,
    /// or the manifest's informational `packMinecraftVersion`.
    pub fn minecraft_version_hint(&self) -> Option<String> {
        if let Some(v) = self.vanilla_mc_version() {
            return Some(v.to_string());
        }
        if let Some(l) = self.cached_mod_loader() {
            return Some(l.minecraft_version());
        }
        self.manifest
            .pack_minecraft_version
            .clone()
            .filter(|v| !v.trim().is_empty())
    }

    /// The Minecraft version (vanilla's, else the loader's).
    pub async fn minecraft_version(self: &Arc<Self>) -> Result<Option<String>> {
        if let Some(v) = self.vanilla_mc_version() {
            return Ok(Some(v.to_string()));
        }
        Ok(self.mod_loader().await?.map(|l| l.minecraft_version()))
    }

    /// The loader's display name, `None` for vanilla.
    pub async fn loader_name(self: &Arc<Self>) -> Result<Option<String>> {
        Ok(self.mod_loader().await?.map(|l| l.loader_name()))
    }

    /// The loader's version, `None` for vanilla.
    pub async fn loader_version(self: &Arc<Self>) -> Result<Option<String>> {
        Ok(self.mod_loader().await?.map(|l| l.loader_version()))
    }

    /// The memo slot for the parsed Mojang library manifest (owned by the launch crate).
    pub fn library_manifest_slot(&self) -> &Mutex<Option<Arc<dyn Any + Send + Sync>>> {
        &self.library_manifest
    }

    /// Files verified in this pack instance's session.
    pub fn session_verified(&self) -> &Arc<SessionVerified> {
        &self.session
    }

    // ---- prepare context -----------------------------------------------------------------

    /// The current prepare run's context (`NONE` outside a run).
    pub fn prepare_context(&self) -> Arc<PrepareContext> {
        self.prepare_context.read().clone()
    }

    /// Sets the context for a run; returns the previous one to restore afterwards.
    pub fn set_prepare_context(&self, c: Arc<PrepareContext>) -> Arc<PrepareContext> {
        std::mem::replace(&mut *self.prepare_context.write(), c)
    }

    // ---- servers -------------------------------------------------------------------------

    /// The manifest's default server.
    pub fn default_server(&self) -> Option<ServerFavorite> {
        self.manifest.default_server()
    }
    /// Sets (or clears) the one-shot server for the next launch.
    pub fn set_quick_join_server(&self, s: Option<ServerFavorite>) {
        *self.quick_join.lock() = s;
    }
    /// The pending one-shot server, without consuming it.
    pub fn quick_join_server(&self) -> Option<ServerFavorite> {
        self.quick_join.lock().clone()
    }
    /// Takes the one-shot server; without one, the default server unless the user disabled it.
    pub fn consume_quick_join_server(&self) -> Option<ServerFavorite> {
        if let Some(s) = self.quick_join.lock().take() {
            return Some(s);
        }
        if servers::is_default_server_disabled(&self.pack_root()) {
            return None;
        }
        self.default_server()
    }
    pub fn server_favorites(&self) -> Vec<ServerFavorite> {
        servers::load(&self.pack_root(), &self.name())
    }

    // ---- images --------------------------------------------------------------------------

    pub fn has_custom_logo(&self) -> bool {
        self.manifest.has_custom_logo()
    }
    pub fn has_custom_background(&self) -> bool {
        self.manifest.has_custom_background()
    }
    pub fn logo_url(&self) -> Option<&str> {
        self.manifest.logo_url()
    }
    pub fn background_url(&self) -> Option<&str> {
        self.manifest.background_url()
    }
    pub fn logo_url_count(&self) -> usize {
        self.manifest.logo_urls().len()
    }
    pub fn background_url_count(&self) -> usize {
        self.manifest.background_urls().len()
    }

    /// The pack's lazy image/folder helper.
    pub fn environment(&self) -> &PackEnvironment {
        self.env.get_or_init(PackEnvironment::new)
    }

    /// Creates the pack folders (see [`PackEnvironment::prepare`]).
    pub fn prepare_environment(&self) -> Result<()> {
        self.environment().prepare(self)
    }

    // ---- crash report --------------------------------------------------------------------

    /// The newest `crash-reports/*.txt`, if it was written in the last 60 seconds.
    pub fn latest_crash_report(&self) -> Option<String> {
        latest_crash_report_in(&self.pack_root().join("crash-reports"), SystemTime::now())
    }

    // ---- sidecars ------------------------------------------------------------------------

    /// `.installed_version`, trimmed.
    pub fn installed_version(&self) -> Option<String> {
        PackDotFile::read(&self.pack_root(), INSTALLED_VERSION_FILE)
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
    }

    /// Writes the current version as installed (after a successful launch).
    pub fn save_installed_version(&self) {
        let Ok(root) = self.checked_pack_root() else {
            return;
        };
        if let Err(_e) = PackDotFile::write(&root, INSTALLED_VERSION_FILE, &self.version()) {
            mmcl_log::warn_silent(&tf!(
                "log.gameModPackMetadata.unableToSaveInstalledVersion",
                self.name()
            ));
        }
        *self.update_available.lock() = None;
    }

    /// Whether the installed version is older than the manifest's.
    pub fn is_update_available(&self) -> bool {
        if let Some(v) = *self.update_available.lock() {
            return v;
        }
        let v = match (
            self.installed_version(),
            self.manifest.pack_version.as_deref(),
        ) {
            (Some(installed), Some(remote)) if !remote.trim().is_empty() => {
                mmcl_manifest::version::compare_versions(&installed, remote)
                    == std::cmp::Ordering::Less
            }
            _ => false,
        };
        *self.update_available.lock() = Some(v);
        v
    }

    fn history(&self) -> LaunchHistory {
        let mut h = self.history.lock();
        if let Some(v) = *h {
            return v;
        }
        let v = PackDotFile::read(&self.pack_root(), LAUNCH_HISTORY_FILE)
            .map(|t| LaunchHistory::parse(&t))
            .unwrap_or_default();
        *h = Some(v);
        v
    }

    /// Records a launch: now as last played, count + 1.
    pub fn record_launch_start(&self) {
        let Ok(root) = self.checked_pack_root() else {
            return;
        };
        let mut cache = self.history.lock();
        let res = PackDotFile::modify(&root, LAUNCH_HISTORY_FILE, |cur| {
            let lines: Vec<String> = cur
                .unwrap_or_default()
                .trim()
                .split('\n')
                .map(|s| s.trim().to_string())
                .collect();
            let total = lines.get(1).and_then(|s| s.parse::<i64>().ok());
            let count = lines.get(2).and_then(|s| s.parse::<i32>().ok());
            if lines.len() > 1 && (total.is_none() || count.is_none()) {
                mmcl_log::warn_silent(&tf!(
                    "log.gameModPackMetadata.unableToReadLaunchHistory",
                    self.name()
                ));
            }
            let h = LaunchHistory {
                last_played_ms: now_ms(),
                total_play_time_ms: total.unwrap_or(0),
                launch_count: count.unwrap_or(0).saturating_add(1),
            };
            *cache = Some(h);
            h.render()
        });
        if res.is_err() {
            mmcl_log::warn_silent(&tf!(
                "log.gameModPackMetadata.unableToSaveLaunchHistory",
                self.name()
            ));
        }
    }

    /// Adds a finished session's play time.
    pub fn record_session_end(&self, duration_ms: i64) {
        let Ok(root) = self.checked_pack_root() else {
            return;
        };
        let mut cache = self.history.lock();
        let res = PackDotFile::modify(&root, LAUNCH_HISTORY_FILE, |cur| {
            let lines: Vec<String> = cur
                .unwrap_or_default()
                .trim()
                .split('\n')
                .map(|s| s.trim().to_string())
                .collect();
            let get = |i: usize| lines.get(i).and_then(|s| s.parse::<i64>().ok());
            let h = LaunchHistory {
                last_played_ms: get(0).unwrap_or_else(now_ms),
                total_play_time_ms: get(1).map_or(duration_ms, |t| t.saturating_add(duration_ms)),
                launch_count: lines
                    .get(2)
                    .and_then(|s| s.parse::<i32>().ok())
                    .unwrap_or(1),
            };
            *cache = Some(h);
            h.render()
        });
        if res.is_err() {
            mmcl_log::warn_silent(&tf!(
                "log.gameModPackMetadata.unableToSaveLaunchHistory",
                self.name()
            ));
        }
    }

    pub fn last_played_ms(&self) -> i64 {
        self.history().last_played_ms
    }
    pub fn total_play_time_ms(&self) -> i64 {
        self.history().total_play_time_ms
    }
    pub fn launch_count(&self) -> i32 {
        self.history().launch_count
    }
    /// Use this, not a comparison with the localized "never" label.
    pub fn is_never_played(&self) -> bool {
        self.last_played_ms() == 0
    }

    /// Renderable, unexpired news (see [`PackManifest::visible_news`]).
    pub fn visible_news(&self, today: NaiveDate) -> Vec<&NewsItem> {
        self.manifest.visible_news(today)
    }

    /// Ids in `.seen_news`.
    pub fn seen_news_ids(&self) -> HashSet<String> {
        PackDotFile::read_lines(&self.pack_root(), SEEN_NEWS_FILE)
            .into_iter()
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty())
            .collect()
    }

    /// Visible items with an id not yet seen.
    pub fn unread_news_count(&self, today: NaiveDate) -> usize {
        let seen = self.seen_news_ids();
        self.visible_news(today)
            .iter()
            .filter_map(|n| n.id())
            .filter(|id| !seen.contains(*id))
            .count()
    }

    /// Marks every visible item seen, keeping ids that rotated out of the manifest.
    pub fn mark_all_news_seen(&self, today: NaiveDate) {
        let visible: Vec<String> = self
            .visible_news(today)
            .iter()
            .filter_map(|n| n.id().map(str::to_string))
            .collect();
        if visible.is_empty() {
            return;
        }
        let Ok(root) = self.checked_pack_root() else {
            return;
        };
        let res = PackDotFile::modify(&root, SEEN_NEWS_FILE, |cur| {
            let mut ids: IndexSet<String> = visible.iter().cloned().collect();
            for l in cur.unwrap_or_default().lines() {
                let l = l.trim();
                if !l.is_empty() {
                    ids.insert(l.to_string());
                }
            }
            let mut out = ids.into_iter().collect::<Vec<_>>().join("\n");
            out.push('\n');
            out
        });
        if res.is_err() {
            mmcl_log::warn_silent(&tf!(
                "log.gameModPackMetadata.unableToSaveSeenNews",
                self.name()
            ));
        }
    }

    /// `.verify_state.json`.
    pub fn load_verify_state(&self) -> Option<VerifyState> {
        VerifyState::load(&self.pack_root(), &self.name())
    }

    /// Writes `.verify_state.json`.
    pub fn save_verify_state(&self, s: &VerifyState) {
        if let Ok(root) = self.checked_pack_root() {
            s.save(&root, &self.name());
        }
    }
}

/// The newest `*.txt` in `dir` modified within 60 s of `now`.
pub fn latest_crash_report_in(dir: &Path, now: SystemTime) -> Option<String> {
    let newest = fs::read_dir(dir)
        .ok()?
        .filter_map(|e| e.ok())
        .filter(|e| {
            e.path()
                .extension()
                .is_some_and(|x| x.eq_ignore_ascii_case("txt"))
        })
        .filter_map(|e| {
            let m = e.metadata().ok()?;
            m.is_file().then(|| (m.modified().ok(), e.path()))
        })
        .filter_map(|(m, p)| m.map(|m| (m, p)))
        .max_by_key(|(m, _)| *m)?;
    let age = now.duration_since(newest.0).unwrap_or(Duration::ZERO);
    if age > Duration::from_secs(60) {
        return None;
    }
    match fs::read(&newest.1) {
        Ok(b) => Some(String::from_utf8_lossy(&b).into_owned()),
        Err(e) => {
            mmcl_log::warn_silent(&tf!("log.gameModPack.crashReportReadFailed", e.to_string()));
            None
        }
    }
}

#[cfg(test)]
mod tests;

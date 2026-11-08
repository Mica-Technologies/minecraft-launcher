// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! What Home and Browse need from the launcher, as one trait ([`LibraryBackend`]).
//!
//! [`EngineLibrary`] implements it over [`mmcl_engine::Engine`]; [`EmptyLibrary`] answers
//! "not available" for everything (no engine: previews, tests). `mmcl-app` or a test can
//! install another backend with [`set_backend`].

use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;
use std::rc::Rc;
use std::sync::LazyLock;
use std::sync::atomic::{AtomicBool, Ordering};

use gpui::{App, Global};
use mmcl_engine::{
    AccountView, AnnouncementsView, Engine, EngineError, EngineEvent, ImportOutcome, ImportPreview,
    ImportRequest, ImportSourceView, ImportToken, LaunchOptions, LoaderVersionView,
    ModrinthProjectView, OperationId, PackId, PackSummary, Setting, VanillaVersionView,
};
use mmcl_i18n::t;

/// A boxed, sendable future (runs on the engine's runtime; awaitable from GPUI).
pub type BoxFut<T> = Pin<Box<dyn Future<Output = T> + Send + 'static>>;

/// Per-pack facts that need an async engine read (`pack_details`).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct PackExtras {
    /// The install folder.
    pub root: Option<PathBuf>,
    /// `Forge`, `NeoForge`, `Fabric` ...
    pub loader_name: Option<String>,
    /// The loader version (`1.12.2-14.23.5.2860`).
    pub loader_version: Option<String>,
    /// Newest update-log entry (ms since the epoch).
    pub newest_update_ms: Option<i64>,
}

/// Cached image files of a pack (no network).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct PackImages {
    /// Every cached logo, primary first.
    pub logos: Vec<PathBuf>,
    /// Every cached background, primary first.
    pub backgrounds: Vec<PathBuf>,
}

/// The settings the screens read.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LibrarySettings {
    /// `showPackBackgrounds`.
    pub show_pack_backgrounds: bool,
    /// `imageCycleInterval` token.
    pub image_cycle_interval: String,
    /// `imageCycleShuffle`.
    pub image_cycle_shuffle: bool,
    /// `lastModPack`.
    pub last_mod_pack: String,
    /// `quickStartCompleted`.
    pub quick_start_completed: bool,
    /// `launcherUpdateCheck`: look for a newer launcher release.
    pub launcher_update_check: bool,
}

impl Default for LibrarySettings {
    fn default() -> Self {
        LibrarySettings {
            show_pack_backgrounds: true,
            image_cycle_interval: "30s".into(),
            image_cycle_shuffle: false,
            last_mod_pack: String::new(),
            quick_start_completed: true,
            launcher_update_check: true,
        }
    }
}

/// A pasted URL, classified (`ModpackImportClassifier`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Classified {
    /// Where it points.
    pub source: ImportSourceView,
    /// The project slug, when the URL names one.
    pub slug: Option<String>,
    /// The version / file id, when the URL names one.
    pub version_id: Option<String>,
}

/// A Modrinth project, for the import preview (pass it back to
/// [`LibraryBackend::install_modrinth`]).
pub type ModrinthPreview = ModrinthProjectView;

/// The Modrinth preview lookup.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PreviewResult {
    /// Found.
    Found(Box<ModrinthPreview>),
    /// Modrinth returned nothing (offline, unknown slug).
    NotFound,
    /// The lookup failed with a message (for example, not a Modrinth project URL).
    Failed(String),
    /// This backend cannot preview; import directly after a plain confirmation.
    Unsupported,
}

/// A failed import.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportFailure {
    /// An expected import error (bad archive, missing file: `ImportException`) rather than an
    /// unexpected one.
    pub expected: bool,
    /// The localized message.
    pub message: String,
}

/// The pre-export facts of the official launcher export.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OfficialStatus {
    /// The official launcher's data folder exists.
    pub available: bool,
    /// Its `.minecraft` folder (shown when missing).
    pub dot_minecraft: PathBuf,
    /// The version id the profile will use.
    pub version_id: String,
    /// The loader version is already installed there.
    pub loader_installed: bool,
    /// The profile name the export creates (`<pack> (Mica)`).
    pub profile_name: String,
}

/// How an official launcher export ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OfficialOutcome {
    /// Created; the profile name.
    Exported(String),
    /// Created, but the loader version is still missing.
    LoaderMissing(String),
}

/// An official launcher export's outcome plus the loader installer's failure, when the install
/// ran and failed (Java's `installLoader.failed` toast).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OfficialReport {
    /// How the export ended.
    pub outcome: OfficialOutcome,
    /// The localized `officialExport.installLoader.failed.body` (with the installer's text).
    pub loader_failure: Option<String>,
}

/// How a pack verify ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VerifyOutcome {
    /// Verified.
    Done,
    /// This backend cannot verify; carry on without it.
    Unsupported,
    /// Failed with a message.
    Failed(String),
}

/// A newer launcher release.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateAvailable {
    pub version: String,
    pub url: String,
}

/// Everything Home and Browse need.
pub trait LibraryBackend: 'static {
    // ---- snapshots (cheap, foreground) ----

    /// Installed packs, in library order.
    fn installed_packs(&self) -> Vec<PackSummary>;
    /// Installed vanilla version ids.
    fn installed_vanilla_ids(&self) -> Vec<String>;
    /// The available packs, `None` while the list is still loading.
    fn available_packs_if_ready(&self) -> Option<Vec<PackSummary>>;
    /// The settings the screens read.
    fn settings(&self) -> LibrarySettings;
    /// Records the last played pack.
    fn set_last_mod_pack(&self, name: &str);
    /// The announcement banners.
    fn announcements(&self) -> AnnouncementsView;
    /// Hides a banner for this run.
    fn dismiss_announcement(&self, text: &str);
    /// A development build.
    fn is_dev(&self) -> bool;
    /// The launcher version.
    fn version(&self) -> String;
    /// The default account.
    fn default_account(&self) -> Option<AccountView>;
    /// Every account.
    fn accounts(&self) -> Vec<AccountView>;
    /// The account a pack launches as (its override), if set.
    fn account_override(&self, id: &PackId) -> Option<String>;
    /// How many games prepare or run.
    fn active_session_count(&self) -> usize;
    /// The active session of a pack.
    fn active_session_for_pack(&self, id: &PackId) -> Option<mmcl_engine::SessionId>;
    /// Classifies a pasted URL.
    fn classify(&self, url: &str) -> Classified;
    /// The folder the official loader logos are cached in (`config/loader-logos`).
    fn loader_logos_dir(&self) -> Option<PathBuf>;
    /// The game folder an official launcher export of `p` uses (shown before removing it).
    fn official_export_dir(&self, p: &PackSummary) -> Option<PathBuf>;
    /// Cached image files of an installed pack.
    fn pack_images(&self, id: &PackId) -> PackImages;
    /// Discord presence for a screen (localized text).
    fn set_presence(&self, text: &str);

    // ---- operations (async) ----

    /// Folder, loader and update-log facts of an installed pack.
    fn pack_extras(&self, id: &PackId) -> BoxFut<Option<PackExtras>>;
    /// F5: re-probe the network, re-fetch announcements, reload both pack lists.
    fn refresh(&self) -> BoxFut<()>;
    /// Waits for the available list (the first fetch).
    fn available_packs(&self) -> BoxFut<Vec<PackSummary>>;
    /// Vanilla versions of a kind (`release`, `snapshot`, `old_beta`, `old_alpha`).
    fn vanilla_versions(&self, kind: &str) -> BoxFut<Vec<VanillaVersionView>>;
    /// Loader versions (`forge`, `neoforge`, `fabric`).
    fn loader_versions(&self, loader: &str) -> BoxFut<Vec<LoaderVersionView>>;
    /// Installs the pack at a manifest URL.
    fn install_by_url(&self, url: &str) -> BoxFut<Result<PackId, String>>;
    /// Installs an available pack.
    fn install_available(&self, friendly_name: &str) -> BoxFut<Result<PackId, String>>;
    /// Uninstalls a pack.
    fn uninstall(&self, id: &PackId, delete_files: bool) -> BoxFut<Result<(), String>>;
    /// Adds a vanilla version.
    fn install_vanilla(&self, version: &str) -> BoxFut<Result<(), String>>;
    /// Removes a vanilla version (and its folder when `delete_files`).
    fn uninstall_vanilla(&self, version: &str, delete_files: bool) -> BoxFut<Result<(), String>>;
    /// Installs a loader version as an empty pack.
    fn install_loader(&self, version: &LoaderVersionView) -> BoxFut<Result<(), String>>;
    /// Imports a pack.
    fn import(&self, request: ImportRequest) -> BoxFut<Result<ImportOutcome, ImportFailure>>;
    /// The Modrinth project behind a URL (nothing is installed).
    fn modrinth_preview(&self, url: &str) -> BoxFut<PreviewResult>;
    /// Downloads, translates and installs a previewed Modrinth project.
    fn install_modrinth(
        &self,
        project: &ModrinthPreview,
    ) -> BoxFut<Result<ImportOutcome, ImportFailure>>;
    /// Step one of an import with confirmation: reads the source (a Modrinth `.mrpack` is
    /// downloaded and its index read) without installing anything.
    fn prepare_import(
        &self,
        request: ImportRequest,
    ) -> BoxFut<Result<ImportPreview, ImportFailure>>;
    /// Step one for a previewed Modrinth project.
    fn prepare_modrinth(
        &self,
        project: &ModrinthPreview,
    ) -> BoxFut<Result<ImportPreview, ImportFailure>>;
    /// Step two: installs a prepared import.
    fn commit_import(&self, token: ImportToken) -> BoxFut<Result<ImportOutcome, ImportFailure>>;
    /// Drops a prepared import (its download is deleted); `false` when it names nothing.
    fn discard_import(&self, token: ImportToken) -> bool;
    /// Warms the `client.json` cache of the last-played pack (idle main screen).
    fn prefetch_last_played(&self) -> BoxFut<()>;
    /// Verifies a pack's files and waits for the result.
    fn verify(&self, id: &PackId) -> BoxFut<VerifyOutcome>;
    /// Writes a zip export.
    fn export_zip(&self, id: &PackId, dest: PathBuf) -> BoxFut<Result<(), String>>;
    /// Starts a game.
    fn launch(&self, id: &PackId) -> BoxFut<Result<(), String>>;
    /// Downloads a pack's declared images into the cache; `true` on success.
    fn cache_images(&self, id: &PackId) -> BoxFut<bool>;
    /// Downloads a remote image (an available pack's logo).
    fn fetch_bytes(&self, url: &str) -> BoxFut<Result<Vec<u8>, String>>;
    /// A newer launcher release, if any.
    fn check_update(&self) -> BoxFut<Option<UpdateAvailable>>;
    /// The official launcher export pre-checks.
    fn official_status(&self, id: &PackId) -> BoxFut<Result<OfficialStatus, String>>;
    /// Installs the loader into the official launcher (`LoaderInstallerRunner`).
    fn official_install_loader(&self, id: &PackId) -> BoxFut<Result<String, String>>;
    /// Verifies the pack, installs the loader when `install_loader` (and it is missing), then
    /// creates the official launcher profile.
    fn official_export(
        &self,
        id: &PackId,
        install_loader: bool,
    ) -> BoxFut<Result<OfficialReport, String>>;
    /// Whether a profile was exported for the pack.
    fn official_has_export(&self, id: &PackId) -> bool;
    /// Removes the official launcher profile; the profile name.
    fn official_remove(&self, id: &PackId) -> BoxFut<Result<String, String>>;
}

/// The installed backend.
#[derive(Clone)]
pub struct BackendGlobal(pub Rc<dyn LibraryBackend>);

impl Global for BackendGlobal {}

/// Installs a backend (`mmcl-app`, tests). Without one the engine is used.
pub fn set_backend(cx: &mut App, backend: Rc<dyn LibraryBackend>) {
    cx.set_global(BackendGlobal(backend));
}

/// The backend: the installed one, else the engine, else an empty one.
pub fn backend(cx: &App) -> Rc<dyn LibraryBackend> {
    if let Some(b) = cx.try_global::<BackendGlobal>() {
        return b.0.clone();
    }
    match crate::services::try_engine(cx) {
        Some(engine) => Rc::new(EngineLibrary(engine)),
        None => Rc::new(EmptyLibrary),
    }
}

/// The "not available in this build" message.
pub fn unavailable() -> String {
    t!("library.action.unavailable").into_owned()
}

/// Set once the first available-packs fetch has finished (an empty list is then "ready").
static AVAILABLE_FETCHED: AtomicBool = AtomicBool::new(false);

/// The available list as "ready": an empty list counts as loaded once the first fetch has
/// finished (offline, empty manifest), so the screens do not show "Loading" on every visit.
pub fn ready_available(list: Vec<PackSummary>, fetched: bool) -> Option<Vec<PackSummary>> {
    (fetched || !list.is_empty()).then_some(list)
}

fn ready<T: Send + 'static>(v: T) -> BoxFut<T> {
    Box::pin(async move { v })
}

// ---------------------------------------------------------------------------------------------
// URL classification (slug / id parts; the engine gives the source)

/// The path segments of an http(s) URL, without empty ones.
fn segments(url: &str) -> Vec<String> {
    url::Url::parse(url.trim())
        .ok()
        .map(|u| {
            u.path_segments()
                .map(|s| s.filter(|p| !p.is_empty()).map(str::to_string).collect())
                .unwrap_or_default()
        })
        .unwrap_or_default()
}

/// The slug and version id of a project URL for `source`.
pub fn slug_parts(source: ImportSourceView, url: &str) -> (Option<String>, Option<String>) {
    let seg = segments(url);
    let after = |name: &str| {
        seg.iter()
            .position(|s| s.eq_ignore_ascii_case(name))
            .and_then(|i| seg.get(i + 1).cloned())
    };
    match source {
        ImportSourceView::Modrinth => {
            let slug = after("modpack").or_else(|| after("project"));
            (slug, after("version"))
        }
        ImportSourceView::CurseForge => (after("modpacks"), after("files")),
        ImportSourceView::Technic => {
            // `/modpack/<slug>.<id>` on the site; keep the slug only.
            let slug = after("modpack").map(|s| match s.rsplit_once('.') {
                Some((head, tail)) if tail.chars().all(|c| c.is_ascii_digit()) => head.to_string(),
                _ => s,
            });
            (slug, None)
        }
        _ => (None, None),
    }
}

// ---------------------------------------------------------------------------------------------
// Engine implementation

/// [`LibraryBackend`] over the engine.
#[derive(Clone)]
pub struct EngineLibrary(pub Engine);

/// The shared client for remote pack images (generic User-Agent, no personal data).
static IMAGE_CLIENT: LazyLock<Option<reqwest::Client>> = LazyLock::new(|| {
    reqwest::Client::builder()
        .user_agent("MicaMinecraftLauncher")
        .timeout(std::time::Duration::from_secs(20))
        .build()
        .ok()
});

/// Largest remote image accepted (bytes).
const MAX_IMAGE_BYTES: usize = 16 * 1024 * 1024;

fn msg(e: EngineError) -> String {
    e.message()
}

fn import_failure(err: EngineError) -> ImportFailure {
    ImportFailure {
        expected: matches!(
            err,
            EngineError::Modpack(_) | EngineError::Io(_) | EngineError::Other(_)
        ),
        message: err.message(),
    }
}

/// How an official export outcome reads in the UI (vanilla never misses a loader).
pub fn official_outcome(o: mmcl_engine::OfficialExportOutcome, vanilla: bool) -> OfficialOutcome {
    if o.loader_installed || vanilla {
        OfficialOutcome::Exported(o.profile_name)
    } else {
        OfficialOutcome::LoaderMissing(o.version_id)
    }
}

/// How an official export report reads in the UI: the outcome plus the loader-install failure
/// text (a blank text counts as none).
pub fn official_report(r: mmcl_engine::OfficialExportReport, vanilla: bool) -> OfficialReport {
    OfficialReport {
        outcome: official_outcome(r.outcome, vanilla),
        loader_failure: r.loader_install_error.filter(|s| !s.trim().is_empty()),
    }
}

impl EngineLibrary {
    fn spawn<T: Send + 'static>(
        &self,
        fut: impl Future<Output = T> + Send + 'static,
        aborted: T,
    ) -> BoxFut<T> {
        let task = self.0.spawn(fut);
        Box::pin(async move { task.await.unwrap_or(aborted) })
    }

    fn spawn_res<T: Send + 'static>(
        &self,
        fut: impl Future<Output = Result<T, String>> + Send + 'static,
    ) -> BoxFut<Result<T, String>> {
        let task = self.0.spawn(fut);
        Box::pin(async move {
            task.await
                .unwrap_or_else(|_| Err(t!("engine.error.cancelled").into_owned()))
        })
    }

    fn spawn_import<T: Send + 'static>(
        &self,
        fut: impl Future<Output = Result<T, ImportFailure>> + Send + 'static,
    ) -> BoxFut<Result<T, ImportFailure>> {
        let task = self.0.spawn(fut);
        Box::pin(async move {
            task.await.unwrap_or_else(|_| {
                Err(ImportFailure {
                    expected: false,
                    message: t!("engine.error.cancelled").into_owned(),
                })
            })
        })
    }
}

/// Waits for `op`'s [`EngineEvent::OperationFinished`].
async fn await_operation(rx: &mut tokio::sync::broadcast::Receiver<EngineEvent>, op: OperationId) {
    use tokio::sync::broadcast::error::RecvError;
    loop {
        match rx.recv().await {
            Ok(EngineEvent::OperationFinished(done)) if done == op => return,
            Ok(EngineEvent::ShuttingDown) | Err(RecvError::Closed) => return,
            _ => {}
        }
    }
}

fn parse_day_ms(day: &str) -> Option<i64> {
    let d = chrono::NaiveDate::parse_from_str(day.trim(), "%Y-%m-%d").ok()?;
    Some(d.and_hms_opt(0, 0, 0)?.and_utc().timestamp_millis())
}

impl LibraryBackend for EngineLibrary {
    fn installed_packs(&self) -> Vec<PackSummary> {
        self.0.installed_packs()
    }

    fn installed_vanilla_ids(&self) -> Vec<String> {
        // `installed_packs` ends with the installed vanilla versions.
        self.0
            .installed_packs()
            .into_iter()
            .filter(|p| p.is_vanilla)
            .filter_map(|p| p.id.vanilla_version().map(str::to_string))
            .collect()
    }

    fn available_packs_if_ready(&self) -> Option<Vec<PackSummary>> {
        ready_available(
            self.0.available_packs_if_ready(),
            AVAILABLE_FETCHED.load(Ordering::SeqCst),
        )
    }

    fn settings(&self) -> LibrarySettings {
        let s = self.0.settings();
        LibrarySettings {
            show_pack_backgrounds: s.show_pack_backgrounds,
            image_cycle_interval: s.image_cycle_interval,
            image_cycle_shuffle: s.image_cycle_shuffle,
            last_mod_pack: s.last_mod_pack,
            quick_start_completed: s.quick_start_completed,
            launcher_update_check: s.launcher_update_check,
        }
    }

    fn set_last_mod_pack(&self, name: &str) {
        if let Err(e) = self.0.set(Setting::LastModPack(name.to_string())) {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!("log.library.settingFailed", e.message())
            );
        }
    }

    fn announcements(&self) -> AnnouncementsView {
        self.0.announcements()
    }

    fn dismiss_announcement(&self, text: &str) {
        self.0.dismiss_announcement(text);
    }

    fn is_dev(&self) -> bool {
        self.0.build_info().is_dev
    }

    fn version(&self) -> String {
        self.0.build_info().version.to_string()
    }

    fn default_account(&self) -> Option<AccountView> {
        self.0.default_account()
    }

    fn accounts(&self) -> Vec<AccountView> {
        self.0.accounts()
    }

    fn account_override(&self, id: &PackId) -> Option<String> {
        self.0.config().account_override_for_pack(id.as_str())
    }

    fn active_session_count(&self) -> usize {
        self.0.active_sessions().len()
    }

    fn active_session_for_pack(&self, id: &PackId) -> Option<mmcl_engine::SessionId> {
        self.0.active_session_for_pack(id).map(|s| s.id)
    }

    fn classify(&self, url: &str) -> Classified {
        let source = self.0.classify_import_url(url);
        let (slug, version_id) = slug_parts(source, url);
        Classified {
            source,
            slug,
            version_id,
        }
    }

    fn loader_logos_dir(&self) -> Option<PathBuf> {
        Some(self.0.paths().loader_logos_dir())
    }

    fn official_export_dir(&self, p: &PackSummary) -> Option<PathBuf> {
        use mmcl_engine::engine::official_export::{profile_display_name, sanitize_folder_name};
        Some(
            self.0
                .paths()
                .official_launcher_exports_dir()
                .join(sanitize_folder_name(&profile_display_name(
                    &p.name,
                    p.is_vanilla,
                ))),
        )
    }

    fn pack_images(&self, id: &PackId) -> PackImages {
        let v = self.0.pack_images(id);
        PackImages {
            logos: v.logos,
            backgrounds: v.backgrounds,
        }
    }

    fn set_presence(&self, text: &str) {
        self.0.set_discord_menu_presence(text);
    }

    fn pack_extras(&self, id: &PackId) -> BoxFut<Option<PackExtras>> {
        let e = self.0.clone();
        let id = id.clone();
        self.spawn(
            async move {
                let d = e.pack_details(&id).await.ok()?;
                Some(PackExtras {
                    root: Some(d.root),
                    loader_name: d.loader_name,
                    loader_version: d.loader_version,
                    newest_update_ms: d.update_log.first().and_then(|u| parse_day_ms(&u.when)),
                })
            },
            None,
        )
    }

    fn refresh(&self) -> BoxFut<()> {
        let e = self.0.clone();
        self.spawn(
            async move {
                let online = e.check_network().await;
                let _ = e.refresh_announcements().await;
                e.refresh_packs().await;
                if online {
                    let _ = e.available_packs().await;
                }
            },
            (),
        )
    }

    fn available_packs(&self) -> BoxFut<Vec<PackSummary>> {
        let e = self.0.clone();
        self.spawn(
            async move {
                let list = e.available_packs().await;
                AVAILABLE_FETCHED.store(true, Ordering::SeqCst);
                list
            },
            Vec::new(),
        )
    }

    fn vanilla_versions(&self, kind: &str) -> BoxFut<Vec<VanillaVersionView>> {
        let e = self.0.clone();
        let kind = kind.to_string();
        self.spawn(async move { e.vanilla_versions(&kind).await }, Vec::new())
    }

    fn loader_versions(&self, loader: &str) -> BoxFut<Vec<LoaderVersionView>> {
        let e = self.0.clone();
        let loader = loader.to_string();
        self.spawn(async move { e.loader_versions(&loader).await }, Vec::new())
    }

    fn install_by_url(&self, url: &str) -> BoxFut<Result<PackId, String>> {
        let e = self.0.clone();
        let url = url.to_string();
        self.spawn_res(async move { e.install_pack_by_url(&url).await.map_err(msg) })
    }

    fn install_available(&self, friendly_name: &str) -> BoxFut<Result<PackId, String>> {
        let e = self.0.clone();
        let name = friendly_name.to_string();
        self.spawn_res(async move { e.install_available_pack(&name).await.map_err(msg) })
    }

    fn uninstall(&self, id: &PackId, delete_files: bool) -> BoxFut<Result<(), String>> {
        let e = self.0.clone();
        let id = id.clone();
        self.spawn_res(async move { e.uninstall_pack(&id, delete_files).await.map_err(msg) })
    }

    fn install_vanilla(&self, version: &str) -> BoxFut<Result<(), String>> {
        let e = self.0.clone();
        let v = version.to_string();
        self.spawn_res(async move { e.install_vanilla(&v).await.map(|_| ()).map_err(msg) })
    }

    fn uninstall_vanilla(&self, version: &str, delete_files: bool) -> BoxFut<Result<(), String>> {
        let e = self.0.clone();
        let v = version.to_string();
        self.spawn_res(async move {
            e.uninstall_pack(&PackId::vanilla(&v), delete_files)
                .await
                .map_err(msg)
        })
    }

    fn install_loader(&self, version: &LoaderVersionView) -> BoxFut<Result<(), String>> {
        let e = self.0.clone();
        let v = version.clone();
        self.spawn_res(async move { e.install_loader_version(&v).await.map(|_| ()).map_err(msg) })
    }

    fn import(&self, request: ImportRequest) -> BoxFut<Result<ImportOutcome, ImportFailure>> {
        let e = self.0.clone();
        self.spawn_import(async move { e.import(request).await.map_err(import_failure) })
    }

    fn modrinth_preview(&self, url: &str) -> BoxFut<PreviewResult> {
        let e = self.0.clone();
        let url = url.to_string();
        self.spawn(
            async move {
                match e.preview_modrinth(&url).await {
                    Ok(Some(p)) => PreviewResult::Found(Box::new(p)),
                    Ok(None) => PreviewResult::NotFound,
                    Err(err) => PreviewResult::Failed(err.message()),
                }
            },
            PreviewResult::NotFound,
        )
    }

    fn install_modrinth(
        &self,
        project: &ModrinthPreview,
    ) -> BoxFut<Result<ImportOutcome, ImportFailure>> {
        let e = self.0.clone();
        let p = project.clone();
        self.spawn_import(async move { e.install_modrinth(&p).await.map_err(import_failure) })
    }

    fn prepare_import(
        &self,
        request: ImportRequest,
    ) -> BoxFut<Result<ImportPreview, ImportFailure>> {
        let e = self.0.clone();
        self.spawn_import(async move { e.prepare_import(request).await.map_err(import_failure) })
    }

    fn prepare_modrinth(
        &self,
        project: &ModrinthPreview,
    ) -> BoxFut<Result<ImportPreview, ImportFailure>> {
        let e = self.0.clone();
        let p = project.clone();
        self.spawn_import(async move { e.prepare_modrinth(&p).await.map_err(import_failure) })
    }

    fn commit_import(&self, token: ImportToken) -> BoxFut<Result<ImportOutcome, ImportFailure>> {
        let e = self.0.clone();
        self.spawn_import(async move { e.commit_import(token).await.map_err(import_failure) })
    }

    fn discard_import(&self, token: ImportToken) -> bool {
        self.0.discard_import(token)
    }

    fn prefetch_last_played(&self) -> BoxFut<()> {
        let e = self.0.clone();
        self.spawn(
            async move {
                let _ = e.prefetch_last_played().await;
            },
            (),
        )
    }

    fn verify(&self, id: &PackId) -> BoxFut<VerifyOutcome> {
        let e = self.0.clone();
        let id = id.clone();
        self.spawn(
            async move {
                let mut rx = e.subscribe();
                let op = e.next_operation_id();
                match e.verify_pack_with(&id, op).await {
                    Ok(op) => {
                        await_operation(&mut rx, op).await;
                        VerifyOutcome::Done
                    }
                    Err(err) if err.is_not_implemented() => VerifyOutcome::Unsupported,
                    Err(err) => VerifyOutcome::Failed(err.message()),
                }
            },
            VerifyOutcome::Unsupported,
        )
    }

    fn export_zip(&self, id: &PackId, dest: PathBuf) -> BoxFut<Result<(), String>> {
        let e = self.0.clone();
        let id = id.clone();
        self.spawn_res(async move {
            e.export_pack(
                &id,
                mmcl_engine::ExportRequest::Zip {
                    dest,
                    include_saves: false,
                },
            )
            .await
            .map(|_| ())
            .map_err(msg)
        })
    }

    fn launch(&self, id: &PackId) -> BoxFut<Result<(), String>> {
        let e = self.0.clone();
        let id = id.clone();
        self.spawn_res(async move {
            e.launch(&id, LaunchOptions::default())
                .await
                .map(|_| ())
                .map_err(msg)
        })
    }

    fn cache_images(&self, id: &PackId) -> BoxFut<bool> {
        let e = self.0.clone();
        let id = id.clone();
        self.spawn(
            async move {
                e.cache_pack_images(&id)
                    .await
                    .is_ok_and(|v| !v.logos.is_empty() || !v.backgrounds.is_empty())
            },
            false,
        )
    }

    fn fetch_bytes(&self, url: &str) -> BoxFut<Result<Vec<u8>, String>> {
        let Some(url) = mmcl_ui_kit::sys::sanitized_http_url(url) else {
            return ready(Err(t!("log.modpack.refuseNonHttpUrl").into_owned()));
        };
        self.spawn_res(async move {
            let client = IMAGE_CLIENT.as_ref().ok_or_else(unavailable)?;
            let resp = client
                .get(&url)
                .send()
                .await
                .and_then(reqwest::Response::error_for_status)
                .map_err(|e| e.to_string())?;
            if resp
                .content_length()
                .is_some_and(|n| n as usize > MAX_IMAGE_BYTES)
            {
                return Err(unavailable());
            }
            let bytes = resp.bytes().await.map_err(|e| e.to_string())?;
            if bytes.len() > MAX_IMAGE_BYTES {
                return Err(unavailable());
            }
            Ok(bytes.to_vec())
        })
    }

    fn check_update(&self) -> BoxFut<Option<UpdateAvailable>> {
        let e = self.0.clone();
        self.spawn(
            async move {
                e.check_launcher_update()
                    .await
                    .ok()
                    .flatten()
                    .map(|u| UpdateAvailable {
                        version: u.version,
                        url: u.url,
                    })
            },
            None,
        )
    }

    fn official_status(&self, id: &PackId) -> BoxFut<Result<OfficialStatus, String>> {
        let e = self.0.clone();
        let id = id.clone();
        self.spawn_res(async move {
            e.official_export_status(&id)
                .await
                .map(|s| OfficialStatus {
                    available: s.available,
                    dot_minecraft: s.dot_minecraft,
                    version_id: s.version_id,
                    loader_installed: s.loader_installed,
                    profile_name: s.profile_name,
                })
                .map_err(msg)
        })
    }

    fn official_install_loader(&self, id: &PackId) -> BoxFut<Result<String, String>> {
        let e = self.0.clone();
        let id = id.clone();
        self.spawn_res(async move { e.install_official_loader(&id).await.map_err(msg) })
    }

    fn official_export(
        &self,
        id: &PackId,
        install_loader: bool,
    ) -> BoxFut<Result<OfficialReport, String>> {
        let e = self.0.clone();
        let id = id.clone();
        self.spawn_res(async move {
            let vanilla = id.vanilla_version().is_some();
            let op = e.next_operation_id();
            e.export_official_report(&id, install_loader, op)
                .await
                .map(|r| official_report(r, vanilla))
                .map_err(msg)
        })
    }

    fn official_has_export(&self, id: &PackId) -> bool {
        self.0.has_official_export(id)
    }

    fn official_remove(&self, id: &PackId) -> BoxFut<Result<String, String>> {
        let e = self.0.clone();
        let id = id.clone();
        self.spawn_res(async move {
            let name = e.pack(&id).map(|p| {
                mmcl_engine::engine::official_export::profile_display_name(&p.name, p.is_vanilla)
            });
            e.remove_official_export(&id).await.map_err(msg)?;
            Ok(name.unwrap_or_default())
        })
    }
}

// ---------------------------------------------------------------------------------------------
// Empty backend (no engine: previews, some tests)

/// A backend with nothing in it.
#[derive(Debug, Default, Clone, Copy)]
pub struct EmptyLibrary;

impl LibraryBackend for EmptyLibrary {
    fn installed_packs(&self) -> Vec<PackSummary> {
        Vec::new()
    }
    fn installed_vanilla_ids(&self) -> Vec<String> {
        Vec::new()
    }
    fn available_packs_if_ready(&self) -> Option<Vec<PackSummary>> {
        Some(Vec::new())
    }
    fn settings(&self) -> LibrarySettings {
        LibrarySettings::default()
    }
    fn set_last_mod_pack(&self, _name: &str) {}
    fn announcements(&self) -> AnnouncementsView {
        AnnouncementsView::default()
    }
    fn dismiss_announcement(&self, _text: &str) {}
    fn is_dev(&self) -> bool {
        false
    }
    fn version(&self) -> String {
        String::new()
    }
    fn default_account(&self) -> Option<AccountView> {
        None
    }
    fn accounts(&self) -> Vec<AccountView> {
        Vec::new()
    }
    fn account_override(&self, _id: &PackId) -> Option<String> {
        None
    }
    fn active_session_count(&self) -> usize {
        0
    }
    fn active_session_for_pack(&self, _id: &PackId) -> Option<mmcl_engine::SessionId> {
        None
    }
    fn classify(&self, url: &str) -> Classified {
        let _ = url;
        Classified {
            source: ImportSourceView::Unknown,
            slug: None,
            version_id: None,
        }
    }
    fn loader_logos_dir(&self) -> Option<PathBuf> {
        None
    }
    fn official_export_dir(&self, _p: &PackSummary) -> Option<PathBuf> {
        None
    }
    fn pack_images(&self, _id: &PackId) -> PackImages {
        PackImages::default()
    }
    fn set_presence(&self, _text: &str) {}
    fn pack_extras(&self, _id: &PackId) -> BoxFut<Option<PackExtras>> {
        ready(None)
    }
    fn refresh(&self) -> BoxFut<()> {
        ready(())
    }
    fn available_packs(&self) -> BoxFut<Vec<PackSummary>> {
        ready(Vec::new())
    }
    fn vanilla_versions(&self, _kind: &str) -> BoxFut<Vec<VanillaVersionView>> {
        ready(Vec::new())
    }
    fn loader_versions(&self, _loader: &str) -> BoxFut<Vec<LoaderVersionView>> {
        ready(Vec::new())
    }
    fn install_by_url(&self, _url: &str) -> BoxFut<Result<PackId, String>> {
        ready(Err(unavailable()))
    }
    fn install_available(&self, _f: &str) -> BoxFut<Result<PackId, String>> {
        ready(Err(unavailable()))
    }
    fn uninstall(&self, _id: &PackId, _d: bool) -> BoxFut<Result<(), String>> {
        ready(Err(unavailable()))
    }
    fn install_vanilla(&self, _v: &str) -> BoxFut<Result<(), String>> {
        ready(Err(unavailable()))
    }
    fn uninstall_vanilla(&self, _v: &str, _d: bool) -> BoxFut<Result<(), String>> {
        ready(Err(unavailable()))
    }
    fn install_loader(&self, _v: &LoaderVersionView) -> BoxFut<Result<(), String>> {
        ready(Err(unavailable()))
    }
    fn import(&self, _r: ImportRequest) -> BoxFut<Result<ImportOutcome, ImportFailure>> {
        ready(Err(ImportFailure {
            expected: false,
            message: unavailable(),
        }))
    }
    fn modrinth_preview(&self, _url: &str) -> BoxFut<PreviewResult> {
        ready(PreviewResult::Unsupported)
    }
    fn install_modrinth(
        &self,
        _p: &ModrinthPreview,
    ) -> BoxFut<Result<ImportOutcome, ImportFailure>> {
        ready(Err(ImportFailure {
            expected: false,
            message: unavailable(),
        }))
    }
    fn prepare_import(&self, _r: ImportRequest) -> BoxFut<Result<ImportPreview, ImportFailure>> {
        ready(Err(ImportFailure {
            expected: false,
            message: unavailable(),
        }))
    }
    fn prepare_modrinth(
        &self,
        _p: &ModrinthPreview,
    ) -> BoxFut<Result<ImportPreview, ImportFailure>> {
        ready(Err(ImportFailure {
            expected: false,
            message: unavailable(),
        }))
    }
    fn commit_import(&self, _t: ImportToken) -> BoxFut<Result<ImportOutcome, ImportFailure>> {
        ready(Err(ImportFailure {
            expected: false,
            message: unavailable(),
        }))
    }
    fn discard_import(&self, _t: ImportToken) -> bool {
        false
    }
    fn prefetch_last_played(&self) -> BoxFut<()> {
        ready(())
    }
    fn verify(&self, _id: &PackId) -> BoxFut<VerifyOutcome> {
        ready(VerifyOutcome::Unsupported)
    }
    fn export_zip(&self, _id: &PackId, _d: PathBuf) -> BoxFut<Result<(), String>> {
        ready(Err(unavailable()))
    }
    fn launch(&self, _id: &PackId) -> BoxFut<Result<(), String>> {
        ready(Err(unavailable()))
    }
    fn cache_images(&self, _id: &PackId) -> BoxFut<bool> {
        ready(false)
    }
    fn fetch_bytes(&self, _url: &str) -> BoxFut<Result<Vec<u8>, String>> {
        ready(Err(unavailable()))
    }
    fn check_update(&self) -> BoxFut<Option<UpdateAvailable>> {
        ready(None)
    }
    fn official_status(&self, _id: &PackId) -> BoxFut<Result<OfficialStatus, String>> {
        ready(Err(unavailable()))
    }
    fn official_install_loader(&self, _id: &PackId) -> BoxFut<Result<String, String>> {
        ready(Err(unavailable()))
    }
    fn official_export(&self, _id: &PackId, _l: bool) -> BoxFut<Result<OfficialReport, String>> {
        ready(Err(unavailable()))
    }
    fn official_has_export(&self, _id: &PackId) -> bool {
        false
    }
    fn official_remove(&self, _id: &PackId) -> BoxFut<Result<String, String>> {
        ready(Err(unavailable()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slugs_come_from_the_project_path() {
        assert_eq!(
            slug_parts(
                ImportSourceView::Modrinth,
                "https://modrinth.com/modpack/fabulously-optimized/version/abc123"
            ),
            (Some("fabulously-optimized".into()), Some("abc123".into()))
        );
        assert_eq!(
            slug_parts(
                ImportSourceView::CurseForge,
                "https://www.curseforge.com/minecraft/modpacks/all-the-mods-9/files/5458414"
            ),
            (Some("all-the-mods-9".into()), Some("5458414".into()))
        );
        assert_eq!(
            slug_parts(
                ImportSourceView::Technic,
                "https://www.technicpack.net/modpack/tekkit.552547"
            ),
            (Some("tekkit".into()), None)
        );
        assert_eq!(
            slug_parts(ImportSourceView::Mica, "https://example.com/pack.json"),
            (None, None)
        );
        assert_eq!(
            slug_parts(ImportSourceView::Modrinth, "not a url"),
            (None, None)
        );
    }

    #[test]
    fn empty_available_list_is_ready_once_fetched() {
        assert_eq!(ready_available(Vec::new(), false), None);
        assert_eq!(ready_available(Vec::new(), true), Some(Vec::new()));
        let one = vec![PackSummary::default()];
        assert_eq!(ready_available(one.clone(), false), Some(one));
    }

    #[test]
    fn official_outcomes_flag_a_missing_loader() {
        let o = |installed| mmcl_engine::OfficialExportOutcome {
            profile_name: "P (Mica)".into(),
            version_id: "1.20.1-forge-47".into(),
            loader_installed: installed,
        };
        assert_eq!(
            official_outcome(o(true), false),
            OfficialOutcome::Exported("P (Mica)".into())
        );
        assert_eq!(
            official_outcome(o(false), false),
            OfficialOutcome::LoaderMissing("1.20.1-forge-47".into())
        );
        assert_eq!(
            official_outcome(o(false), true),
            OfficialOutcome::Exported("P (Mica)".into())
        );
    }

    #[test]
    fn official_reports_carry_the_loader_failure() {
        let outcome = mmcl_engine::OfficialExportOutcome {
            profile_name: "P (Mica)".into(),
            version_id: "1.20.1-forge-47".into(),
            loader_installed: false,
        };
        let report = |err: Option<&str>| mmcl_engine::OfficialExportReport {
            outcome: outcome.clone(),
            loader_install_error: err.map(str::to_string),
            loader_installer_message: err.map(str::to_string),
        };
        let r = official_report(report(Some("installer said no")), false);
        assert_eq!(
            r.outcome,
            OfficialOutcome::LoaderMissing("1.20.1-forge-47".into())
        );
        assert_eq!(r.loader_failure.as_deref(), Some("installer said no"));
        assert_eq!(
            official_report(report(Some("  ")), false).loader_failure,
            None
        );
        assert_eq!(official_report(report(None), false).loader_failure, None);
    }

    #[test]
    fn the_empty_backend_prepares_nothing() {
        assert!(!EmptyLibrary.discard_import(ImportToken(3)));
    }

    #[test]
    fn update_log_days_parse() {
        assert_eq!(parse_day_ms("1970-01-02"), Some(86_400_000));
        assert_eq!(parse_day_ms(""), None);
    }
}

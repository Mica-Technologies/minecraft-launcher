// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`Engine`]: the one facade. It owns the tokio runtime, the config, the account manager,
//! the pack manager, the runtime manager, the session store and the event bus, and exposes every
//! launcher operation as an async method (or a sync getter for cheap snapshots).
//!
//! Front ends hold a cloned `Engine` (it is an `Arc` inside). GPUI code runs an operation with
//! [`Engine::spawn`] and awaits the returned [`EngineTask`] on its own executor; the TUI and
//! server mode can use [`Engine::block_on`].

pub mod authoring;
mod catalog;
pub mod diag;
mod hosts;
mod import_confirm;
mod launch;
mod maintenance;
pub mod net_hooks;
pub mod official_export;
mod operations;

pub use net_hooks::OfflineHttp;

use std::collections::HashMap;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant};

use anyhow::Context as _;
use parking_lot::Mutex;
use tokio::sync::{broadcast, watch};

use mmcl_auth::{AuthService, SignInFlow, SignInKind};
use mmcl_config::Config;
use mmcl_core::{DataPaths, GameMode};
use mmcl_modpack::{GameModPack, ModPackManager, NetHttp, PackHttp};
use mmcl_net::CancelToken;
use mmcl_runtime::RuntimeManager;

use crate::error::{EngineError, EngineResult, LaunchRefusal};
use crate::event::{
    EngineEvent, Notification, NotificationKind, OperationId, OperationProgress, SignInEvent,
};
use crate::model::*;
use crate::session::{
    LogSnapshot, LogSubscription, SessionId, SessionInfo, SessionPhase, SessionStore,
};
use crate::settings::{Setting, Settings, SideEffects};
use crate::task::EngineTask;

/// Where the engine's tokio runtime comes from.
#[derive(Debug)]
pub enum RuntimeChoice {
    /// The engine builds and owns a multi-thread runtime (`None` = tokio's default worker count).
    Own { worker_threads: Option<usize> },
    /// Use a runtime the caller owns (tests, an app that already has one).
    Handle(tokio::runtime::Handle),
}

/// How to build an [`Engine`].
pub struct EngineOptions {
    /// The data folder (and with it the game mode).
    pub paths: DataPaths,
    pub runtime: RuntimeChoice,
    /// Broadcast buffer per subscriber.
    pub event_capacity: usize,
    /// Whether the web view sign-in helper can run here (the app knows; `true` on desktops).
    pub web_view_supported: bool,
    /// Start the background work at once: the pack list load, the event forwarders, the offline
    /// watcher, the RGB / Discord hosts. Off for tests that want a quiet engine.
    pub start_background_work: bool,
    /// The terminal UI drives this engine (passed to the launch pipeline).
    pub tui: bool,
    /// Keep RGB and Discord on the newest running game (Java `RunningGameFollower`). A front
    /// end with a follower of its own turns this off.
    pub follow_running_games: bool,
    /// The network the pack manager, catalogues, importers and launch pipeline use. `None` =
    /// the real network ([`NetHttp`]); tests pass [`OfflineHttp`] (or their own fake) so no
    /// request ever leaves the machine.
    pub http: Option<Arc<dyn PackHttp>>,
    /// Install the process-wide network hooks in [`Engine::new`]: the `mmcl-net` proxy
    /// settings provider (read from this engine's config) and the battery download throttle.
    /// On by default; tests that build many engines may turn it off.
    pub net_hooks: bool,
}

impl std::fmt::Debug for EngineOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("EngineOptions")
            .field("paths", &self.paths)
            .field("runtime", &self.runtime)
            .field("event_capacity", &self.event_capacity)
            .field("web_view_supported", &self.web_view_supported)
            .field("start_background_work", &self.start_background_work)
            .field("tui", &self.tui)
            .field("follow_running_games", &self.follow_running_games)
            .field("http", &self.http.as_ref().map(|_| "custom"))
            .field("net_hooks", &self.net_hooks)
            .finish()
    }
}

impl EngineOptions {
    /// Defaults for a data folder: own runtime, 1024 buffered events, background work on.
    pub fn new(paths: DataPaths) -> EngineOptions {
        EngineOptions {
            paths,
            runtime: RuntimeChoice::Own {
                worker_threads: None,
            },
            event_capacity: 1024,
            web_view_supported: true,
            start_background_work: true,
            tui: false,
            follow_running_games: true,
            http: None,
            net_hooks: true,
        }
    }

    /// Defaults for the standard data folder of `mode`.
    pub fn for_mode(mode: GameMode) -> EngineOptions {
        EngineOptions::new(DataPaths::for_mode(mode))
    }

    /// A quiet, network-free engine for tests: the caller's runtime, no background work, no
    /// running-game follower, no process-wide network hooks, and [`OfflineHttp`].
    pub fn offline_for_tests(paths: DataPaths, handle: tokio::runtime::Handle) -> EngineOptions {
        let mut o = EngineOptions::new(paths);
        o.runtime = RuntimeChoice::Handle(handle);
        o.start_background_work = false;
        o.follow_running_games = false;
        o.net_hooks = false;
        o.http = Some(Arc::new(OfflineHttp));
        o
    }
}

/// The result of [`Engine::apply_crash_action`]: what the front end still has to do.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CrashActionResult {
    /// The engine did it.
    Done,
    /// Go to the Settings screen.
    OpenSettings,
    /// Go to Runtime management.
    OpenRuntime,
    /// Open this folder in the file manager.
    OpenPath(PathBuf),
    /// Open this URL in the browser.
    OpenUrl(String),
}

/// What to launch with.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct LaunchOptions {
    /// Play as this account (UUID) instead of the pack's override / the default.
    pub account_uuid: Option<String>,
    /// Join this server once the game is up (`host[:port]`).
    pub quick_join: Option<String>,
    /// Verify every file even when the pack's verify state says it is fresh.
    pub force_verify: bool,
}

/// One sign-in to run.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SignInRequest {
    pub kind: SignInKind,
    /// "Stay signed in".
    pub remember: bool,
    /// The login screen makes the new account the default; Add-account does not.
    pub make_default: bool,
    /// The launcher window (HWND on Windows) for the WAM broker.
    pub window_handle: Option<isize>,
}

/// Adapts the machine cipher to the config's secret seam.
struct ConfigCipher(mmcl_secure::MachineCipher);

impl mmcl_config::SecretCipher for ConfigCipher {
    fn encrypt(&self, plaintext: &str) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        self.0.encrypt_str(plaintext).map_err(|e| Box::new(e) as _)
    }

    fn decrypt(&self, encoded: &str) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        match self.0.decrypt_str(encoded) {
            Ok(Some(s)) => Ok(s),
            Ok(None) => Err("empty".into()),
            Err(e) => Err(Box::new(e)),
        }
    }
}

/// Renders crash texts through `mmcl-i18n`.
struct I18nResolver;

impl mmcl_crash::TextResolver for I18nResolver {
    fn get(&self, key: &str) -> String {
        mmcl_i18n::get(key).into_owned()
    }

    fn format(&self, key: &str, args: &[mmcl_crash::ResolvedArg<'_>]) -> String {
        let fmt: Vec<mmcl_i18n::FmtArg> = args
            .iter()
            .map(|a| match a {
                mmcl_crash::ResolvedArg::Str(s) => mmcl_i18n::FmtArg::from(s.as_ref()),
                mmcl_crash::ResolvedArg::Int(n) => mmcl_i18n::FmtArg::Int(*n),
            })
            .collect();
        mmcl_i18n::format(key, &fmt)
    }
}

struct Inner {
    runtime: Mutex<Option<tokio::runtime::Runtime>>,
    handle: tokio::runtime::Handle,
    paths: Arc<DataPaths>,
    config: Config,
    cipher: Arc<ConfigCipher>,
    auth: Arc<AuthService>,
    packs: Arc<ModPackManager>,
    runtimes: Arc<RuntimeManager>,
    sessions: Arc<SessionStore>,
    events: broadcast::Sender<EngineEvent>,
    shutdown_tx: watch::Sender<bool>,
    sign_in_cancel: Mutex<Option<CancelToken>>,
    next_op: AtomicU64,
    web_view_supported: bool,
    shut_down: AtomicBool,
    /// What the launch pipeline runs with.
    launch_env: mmcl_launch::LaunchEnv,
    /// Cancel tokens and processes of the active sessions.
    live: Mutex<HashMap<SessionId, launch::LiveSession>>,
    /// The running malware scan.
    scan_cancel: Mutex<Option<CancelToken>>,
    follow_games: bool,
    /// The session RGB / Discord show.
    followed: Mutex<Option<SessionId>>,
    follower: mmcl_session::LatestUpdateRunner,
    /// Packs whose files a verify / update / reinstall is rewriting right now.
    busy: Mutex<std::collections::HashSet<PackId>>,
    /// The network every engine operation uses.
    http: Arc<dyn PackHttp>,
    /// The vanilla pseudo-packs, one object per installed version (they hold per-run state).
    vanilla: Mutex<HashMap<String, Arc<GameModPack>>>,
    /// RGB and Discord hosts installed and RGB bootstrapped ([`Engine::start_presence`]).
    presence_started: AtomicBool,
    /// Running verify / update / launch operations (cancel token, step rows).
    ops: Mutex<HashMap<OperationId, operations::OpControl>>,
    /// The operation id of each launch.
    session_ops: Mutex<HashMap<SessionId, OperationId>>,
    /// Imports waiting for confirmation, by token.
    prepared_imports: Mutex<std::collections::BTreeMap<u64, import_confirm::PendingImport>>,
    next_import: AtomicU64,
    /// Reloads the running web view sign-in's page ("Try again").
    sign_in_reloader: Mutex<Option<mmcl_auth::PageReloader>>,
}

impl Drop for Inner {
    fn drop(&mut self) {
        let _ = self.shutdown_tx.send(true);
        if let Some(rt) = self.runtime.get_mut().take() {
            // Dropping a runtime inside an async context panics; never block here.
            rt.shutdown_background();
        }
    }
}

/// The application facade. Cheap to clone.
#[derive(Clone)]
pub struct Engine {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for Engine {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Engine")
            .field("root", &self.inner.paths.root())
            .field("mode", &self.inner.paths.mode())
            .finish_non_exhaustive()
    }
}

fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

// =============================================================================================
// Lifecycle
// =============================================================================================

impl Engine {
    /// Builds the engine: the runtime, the config, the services, and (when asked) the background
    /// work. Errors only when the runtime or the HTTP client cannot be built.
    pub fn new(options: EngineOptions) -> anyhow::Result<Engine> {
        let (runtime, handle) = match options.runtime {
            RuntimeChoice::Own { worker_threads } => {
                let mut b = tokio::runtime::Builder::new_multi_thread();
                b.enable_all().thread_name("mmcl-engine");
                if let Some(n) = worker_threads {
                    b.worker_threads(n.max(1));
                }
                let rt = b.build().context("building the engine runtime")?;
                let h = rt.handle().clone();
                (Some(rt), h)
            }
            RuntimeChoice::Handle(h) => (None, h),
        };
        let _guard = handle.enter();
        let paths = Arc::new(options.paths);
        let config = Config::open(&paths);
        let cipher = Arc::new(ConfigCipher(mmcl_secure::MachineCipher::for_paths(&paths)));
        let auth = AuthService::new(&paths, config.clone(), &handle)
            .map_err(|e| anyhow::anyhow!(e.to_string()))
            .context("building the sign-in service")?;
        let http = options.http.clone().unwrap_or_else(NetHttp::shared);
        if options.net_hooks {
            net_hooks::install(&config);
        }
        let packs = ModPackManager::new(paths.clone(), http.clone(), config.clone());
        let runtimes = RuntimeManager::new(paths.clone());
        let mut launch_env =
            mmcl_launch::LaunchEnv::new(paths.clone(), runtimes.clone(), Arc::new(config.clone()));
        launch_env.tui = options.tui;
        launch_env.http = http.clone();
        // First engine of the process wins (the factory is process-wide).
        mmcl_launch::register_loader_factory(launch_env.clone());
        let sessions = Arc::new(SessionStore::default());
        let (events, _) = broadcast::channel(options.event_capacity.max(16));
        let (shutdown_tx, _) = watch::channel(false);

        let engine = Engine {
            inner: Arc::new(Inner {
                runtime: Mutex::new(runtime),
                handle,
                paths,
                config,
                cipher,
                auth,
                packs,
                runtimes,
                sessions,
                events,
                shutdown_tx,
                sign_in_cancel: Mutex::new(None),
                next_op: AtomicU64::new(1),
                web_view_supported: options.web_view_supported,
                shut_down: AtomicBool::new(false),
                launch_env,
                live: Mutex::new(HashMap::new()),
                scan_cancel: Mutex::new(None),
                follow_games: options.follow_running_games,
                followed: Mutex::new(None),
                busy: Mutex::new(std::collections::HashSet::new()),
                follower: mmcl_session::LatestUpdateRunner::on_daemon_thread(
                    "mmcl-running-game-follower",
                ),
                http,
                vanilla: Mutex::new(HashMap::new()),
                presence_started: AtomicBool::new(false),
                ops: Mutex::new(HashMap::new()),
                session_ops: Mutex::new(HashMap::new()),
                prepared_imports: Mutex::new(std::collections::BTreeMap::new()),
                next_import: AtomicU64::new(1),
                sign_in_reloader: Mutex::new(None),
            }),
        };
        engine.wire();
        if options.start_background_work {
            engine.start_background_work();
        }
        Ok(engine)
    }

    /// Hooks the services' callbacks onto the event bus (no tasks).
    fn wire(&self) {
        let i = &self.inner;
        let sessions = i.sessions.clone();
        i.packs.set_active_check(Arc::new(move |p: &GameModPack| {
            sessions.active_for_pack(&PackId::of(p)).is_some()
        }));
        let tx = i.events.clone();
        i.packs
            .set_notifier(Arc::new(move |title: &str, body: &str| {
                let _ = tx.send(EngineEvent::Notification(Notification::new(
                    NotificationKind::Warning,
                    title,
                    body,
                )));
            }));
        let tx = i.events.clone();
        i.auth.accounts().add_listener(Arc::new(move || {
            let _ = tx.send(EngineEvent::AccountsChanged);
        }));
        let tx = i.events.clone();
        i.auth
            .set_status_callback(Some(Arc::new(move |section: &str, detail: &str| {
                let _ = tx.send(EngineEvent::SignIn(SignInEvent::Status {
                    section: section.to_string(),
                    detail: detail.to_string(),
                }));
            })));
    }

    /// Starts the pack list load and the forwarders (pack list changes, offline state).
    /// Idempotent enough: call it once.
    pub fn start_background_work(&self) {
        let i = &self.inner;
        // Pack list changes.
        let mut rx = i.packs.subscribe();
        let tx = i.events.clone();
        let mut stop = i.shutdown_tx.subscribe();
        i.handle.spawn(async move {
            loop {
                tokio::select! {
                    r = rx.changed() => {
                        if r.is_err() { break; }
                        let _ = tx.send(EngineEvent::PacksChanged);
                    }
                    _ = stop.changed() => break,
                }
            }
        });
        // Offline state.
        let tx = i.events.clone();
        let mut stop = i.shutdown_tx.subscribe();
        i.handle.spawn(async move {
            let mut last = mmcl_net::is_offline();
            let mut tick = tokio::time::interval(Duration::from_millis(750));
            loop {
                tokio::select! {
                    _ = tick.tick() => {
                        let now = mmcl_net::is_offline();
                        if now != last {
                            last = now;
                            let _ = tx.send(EngineEvent::OfflineChanged(now));
                        }
                    }
                    _ = stop.changed() => break,
                }
            }
        });
        // Initial pack load. Server mode waits for the revalidation (Java step 16), so a
        // headless start always launches the latest manifest.
        let packs = i.packs.clone();
        let server = self.mode() == GameMode::Server;
        i.handle.spawn(async move {
            if server {
                packs.fetch_installed(true).await;
            }
            packs.fetch_mod_pack_info().await;
        });
        // RGB and Discord read the launcher through the engine; RGB starts in the background.
        self.start_presence();
        // Fire-and-forget startup checks (Java steps 14-15).
        let me = self.clone();
        i.handle.spawn(async move {
            let _ = me.inner.http.refresh_network_availability().await;
            me.inner.auth.try_preemptive_background_renewal();
            let _ = me.refresh_announcements().await;
        });
    }

    /// Installs the RGB and Discord hosts over this engine and bootstraps RGB (Java bootstraps
    /// RGB in every mode). Idempotent: only the first call does anything. Called by
    /// [`Engine::start_background_work`] and by [`Engine::run_server`], so a headless server
    /// gets RGB and Discord too without starting the GUI's background work.
    pub fn start_presence(&self) {
        if self.inner.presence_started.swap(true, Ordering::SeqCst) {
            return;
        }
        self.install_hosts();
        mmcl_rgb::integration::bootstrap();
    }

    /// The network this engine uses (the real one unless [`EngineOptions::http`] replaced it).
    pub fn http(&self) -> Arc<dyn PackHttp> {
        self.inner.http.clone()
    }

    /// Runs `fut` on the engine's runtime; the returned task can be awaited from any executor.
    pub fn spawn<F, T>(&self, fut: F) -> EngineTask<T>
    where
        F: Future<Output = T> + Send + 'static,
        T: Send + 'static,
    {
        EngineTask::spawn(&self.inner.handle, fut)
    }

    /// Runs `fut` to completion on the engine's runtime, blocking this thread (TUI, server mode,
    /// tests). Must not be called from inside the runtime.
    pub fn block_on<F: Future>(&self, fut: F) -> F::Output {
        self.inner.handle.block_on(fut)
    }

    /// The runtime handle (for crates that need to spawn their own tasks).
    pub fn handle(&self) -> &tokio::runtime::Handle {
        &self.inner.handle
    }

    /// A new receiver on the event bus.
    pub fn subscribe(&self) -> broadcast::Receiver<EngineEvent> {
        self.inner.events.subscribe()
    }

    /// Emits an event (front ends and helper tasks may publish too, e.g. a notification).
    pub fn emit(&self, event: EngineEvent) {
        let _ = self.inner.events.send(event);
    }

    /// A fresh id for a long-running operation's progress events.
    pub fn next_operation_id(&self) -> OperationId {
        OperationId(self.inner.next_op.fetch_add(1, Ordering::Relaxed))
    }

    /// Flushes the config, stops RGB and Discord, and stops the background work. Games keep
    /// running (they are separate processes).
    pub async fn shutdown(&self) {
        if self.inner.shut_down.swap(true, Ordering::SeqCst) {
            return;
        }
        self.emit(EngineEvent::ShuttingDown);
        let _ = self.inner.shutdown_tx.send(true);
        if let Some(c) = self.inner.sign_in_cancel.lock().take() {
            c.cancel();
        }
        if let Some(c) = self.inner.scan_cancel.lock().as_ref() {
            c.cancel();
        }
        mmcl_rgb::integration::shutdown();
        mmcl_discord::Presence::global().exit();
        let config = self.inner.config.clone();
        let _ = tokio::task::spawn_blocking(move || config.flush()).await;
    }

    /// `true` after [`Engine::shutdown`].
    pub fn is_shut_down(&self) -> bool {
        self.inner.shut_down.load(Ordering::SeqCst)
    }

    /// The data folder layout.
    pub fn paths(&self) -> &DataPaths {
        &self.inner.paths
    }

    /// Client (GUI/TUI) or server mode.
    pub fn mode(&self) -> GameMode {
        self.inner.paths.mode()
    }

    /// The typed config. Front ends use [`Engine::settings`] / [`Engine::set`]; this is the
    /// escape hatch for keys with no setting (window bounds and other shell state).
    pub fn config(&self) -> &Config {
        &self.inner.config
    }

    /// The running build.
    pub fn build_info(&self) -> &'static mmcl_core::BuildInfo {
        mmcl_core::build::info()
    }

    /// The launcher-wide status line.
    pub fn status(&self) -> LauncherStatus {
        let b = self.build_info();
        let user = self.inner.auth.accounts().default_user();
        LauncherStatus {
            launcher_version: b.version.to_string(),
            display_name: b.display_name.to_string(),
            is_dev: b.is_dev,
            signed_in: user.is_some(),
            username: user.map(|u| u.name),
            installed_pack_count: self.inner.packs.installed_snapshot().len(),
            offline: mmcl_net::is_offline(),
            server_mode: self.mode() == GameMode::Server,
        }
    }
}

// =============================================================================================
// Packs
// =============================================================================================

impl Engine {
    fn summarize(&self, pack: &GameModPack, installed: bool) -> PackSummary {
        let m = pack.manifest();
        let id = PackId::of(pack);
        let load_state = if pack.is_failed_load() {
            PackLoadState::Failed
        } else if pack.is_stub() {
            PackLoadState::Stub
        } else {
            PackLoadState::Loaded
        };
        let today = chrono::Local::now().date_naive();
        PackSummary {
            active: self.inner.sessions.active_for_pack(&id).is_some(),
            id,
            name: pack.name(),
            friendly_name: pack.friendly_name().unwrap_or_else(|| pack.name()),
            version: pack.version(),
            installed_version: if installed {
                pack.installed_version()
            } else {
                None
            },
            installed,
            update_available: installed && pack.is_update_available(),
            is_vanilla: pack.is_vanilla(),
            mod_loader: pack.mod_loader_type(),
            minecraft_version: pack.minecraft_version_hint(),
            unstable: m.pack_unstable,
            load_state,
            load_error: pack.failed_error().map(str::to_string),
            manifest_url: pack.manifest_url().map(str::to_string),
            logo_url: pack.logo_url().map(str::to_string),
            logo_count: pack.logo_url_count(),
            background_url: pack.background_url().map(str::to_string),
            background_count: pack.background_url_count(),
            min_ram_gb: pack.min_ram_gb(),
            last_played_ms: pack.last_played_ms(),
            total_play_time_ms: pack.total_play_time_ms(),
            launch_count: pack.launch_count(),
            never_played: pack.is_never_played(),
            unread_news: if installed {
                pack.unread_news_count(today)
            } else {
                0
            },
            website: m
                .pack_url
                .as_deref()
                .map(str::trim)
                .filter(|u| !u.is_empty())
                .map(str::to_string),
            logo_path: if installed {
                Some(pack.environment().logo_path_raw(pack)).filter(|p| p.is_file())
            } else {
                None
            },
            custom_discord_rpc: m.pack_custom_discord_rpc,
        }
    }

    /// The installed pack object of `id`: a modpack of the library, else an installed vanilla
    /// version (`vanilla:<version>`).
    fn installed_pack(&self, id: &PackId) -> Option<Arc<GameModPack>> {
        if let Some(v) = id.vanilla_version() {
            return self.vanilla_pack(v);
        }
        self.inner
            .packs
            .installed_snapshot()
            .iter()
            .find(|p| &PackId::of(p) == id)
            .cloned()
    }

    /// The pack object of an installed vanilla version (one per version, kept while it stays
    /// installed).
    fn vanilla_pack(&self, version: &str) -> Option<Arc<GameModPack>> {
        if !mmcl_modpack::catalog::vanilla::is_installed(&self.inner.config, version) {
            self.inner.vanilla.lock().remove(version);
            return None;
        }
        let mut map = self.inner.vanilla.lock();
        Some(
            map.entry(version.to_string())
                .or_insert_with(|| GameModPack::vanilla(self.inner.paths.clone(), version))
                .clone(),
        )
    }

    /// Every installed vanilla version's pack, in the config's order.
    fn vanilla_packs(&self) -> Vec<Arc<GameModPack>> {
        let ids = mmcl_modpack::catalog::vanilla::installed_version_ids(&self.inner.config);
        let mut map = self.inner.vanilla.lock();
        map.retain(|k, _| ids.contains(k));
        ids.iter()
            .map(|v| {
                map.entry(v.clone())
                    .or_insert_with(|| GameModPack::vanilla(self.inner.paths.clone(), v))
                    .clone()
            })
            .collect()
    }

    /// The pack object behind `id` wherever it is: installed (modpack or vanilla), else in the
    /// available list (if loaded).
    fn any_pack(&self, id: &PackId) -> Option<Arc<GameModPack>> {
        self.installed_pack(id).or_else(|| {
            self.inner
                .packs
                .available_if_ready()
                .iter()
                .find(|p| &PackId::of(p) == id)
                .cloned()
        })
    }

    fn require_installed(&self, id: &PackId) -> EngineResult<Arc<GameModPack>> {
        self.installed_pack(id)
            .ok_or_else(|| EngineError::PackNotInstalled(id.clone()))
    }

    /// The installed packs, in library order (a snapshot; triggers the first load if needed),
    /// followed by the installed vanilla versions (`is_vanilla`, id `vanilla:<version>`), as
    /// the Java library lists them. Use [`Engine::installed_modpacks`] for modpacks only.
    pub fn installed_packs(&self) -> Vec<PackSummary> {
        let mut out = self.installed_modpacks();
        out.extend(self.vanilla_packs().iter().map(|p| self.summarize(p, true)));
        out
    }

    /// The installed modpacks only (no vanilla versions), in library order.
    pub fn installed_modpacks(&self) -> Vec<PackSummary> {
        self.inner
            .packs
            .installed()
            .iter()
            .map(|p| self.summarize(p, true))
            .collect()
    }

    /// The installable packs (fetches the list the first time).
    pub async fn available_packs(&self) -> Vec<PackSummary> {
        let installed: Vec<PackId> = self
            .inner
            .packs
            .installed_snapshot()
            .iter()
            .map(|p| PackId::of(p))
            .collect();
        self.inner
            .packs
            .available()
            .await
            .iter()
            .map(|p| self.summarize(p, installed.contains(&PackId::of(p))))
            .collect()
    }

    /// The available packs if the list is already loaded (never waits).
    pub fn available_packs_if_ready(&self) -> Vec<PackSummary> {
        self.inner
            .packs
            .available_if_ready()
            .iter()
            .map(|p| self.summarize(p, false))
            .collect()
    }

    /// Reloads only the installed list. With `block_until_revalidated` it waits for the
    /// background manifest revalidation (headless server start, so it launches the latest
    /// manifest). For a front end that starts the engine without its background work.
    pub async fn reload_installed_packs(&self, block_until_revalidated: bool) {
        self.inner
            .packs
            .fetch_installed(block_until_revalidated)
            .await;
        self.emit(EngineEvent::PacksChanged);
    }

    /// Reloads the installed and available lists from their sources.
    pub async fn refresh_packs(&self) {
        self.inner.packs.fetch_mod_pack_info().await;
        self.emit(EngineEvent::PacksChanged);
    }

    /// One installed pack.
    pub fn pack(&self, id: &PackId) -> Option<PackSummary> {
        self.installed_pack(id).map(|p| self.summarize(&p, true))
    }

    /// An installed pack by friendly name (exact), then by pack name.
    pub fn find_installed_by_name(&self, name: &str) -> Option<PackSummary> {
        let p = self
            .inner
            .packs
            .installed_by_friendly_name(name)
            .or_else(|| self.inner.packs.installed_by_name(name))?;
        Some(self.summarize(&p, true))
    }

    /// The detail modal's data.
    pub async fn pack_details(&self, id: &PackId) -> EngineResult<PackDetails> {
        let pack = self.require_installed(id)?;
        let summary = self.summarize(&pack, true);
        let key = id.as_str().to_string();
        let today = chrono::Local::now().date_naive();
        let to_server = |s: &mmcl_modpack::ServerFavorite| ServerView {
            name: s.name.clone(),
            host: s.host.clone(),
            port: Some(i32::from(s.port)),
        };
        let manifest_url = pack.manifest_url().unwrap_or_default().to_string();
        let export_mode =
            match mmcl_modpack::export::classify_export(&pack, self.inner.packs.fetcher()) {
                mmcl_modpack::export::ExportMode::ShareUrl => ExportModeView::ShareUrl,
                mmcl_modpack::export::ExportMode::ShareManifestJson => {
                    ExportModeView::ShareManifestJson
                }
                mmcl_modpack::export::ExportMode::ExportZip => ExportModeView::ExportZip,
            };
        Ok(PackDetails {
            root: pack.pack_root(),
            loader_name: pack.loader_name().await.ok().flatten(),
            loader_version: pack.loader_version().await.ok().flatten(),
            news: pack
                .visible_news(today)
                .into_iter()
                .map(|n| PackNewsView {
                    id: n.id.clone(),
                    title: n.title.clone(),
                    body: n.body.clone(),
                    date: n.date.clone(),
                    url: n.url.clone(),
                })
                .collect(),
            update_log: mmcl_modpack::update_log::read_entries(&pack)
                .into_iter()
                .map(|e| UpdateLogView {
                    when: e.format_timestamp(),
                    version: e.new.clone(),
                })
                .collect(),
            servers: pack.server_favorites().iter().map(to_server).collect(),
            default_server: pack.default_server().as_ref().map(to_server),
            account_override: self.inner.config.account_override_for_pack(&key),
            always_verify_on_launch: self.inner.config.always_verify_on_launch(&manifest_url),
            scan_frequency: self
                .inner
                .config
                .scan_frequency_for_pack(&manifest_url)
                .map(|f| f.name().to_string()),
            has_crash_report: pack.latest_crash_report().is_some(),
            export_mode,
            summary,
        })
    }

    /// Installs (registers and loads) the pack at a manifest URL.
    pub async fn install_pack_by_url(&self, url: &str) -> EngineResult<PackId> {
        let pack = self.inner.packs.install_by_url(url.trim()).await?;
        let id = PackId::of(&pack);
        self.emit(EngineEvent::PacksChanged);
        Ok(id)
    }

    /// Installs an available pack by its friendly name.
    pub async fn install_available_pack(&self, friendly_name: &str) -> EngineResult<PackId> {
        let pack = self
            .inner
            .packs
            .install_by_friendly_name(friendly_name)
            .await?;
        let id = PackId::of(&pack);
        self.emit(EngineEvent::PacksChanged);
        Ok(id)
    }

    /// Uninstalls a pack (and deletes its folder when `delete_files`). Refused while it runs.
    pub async fn uninstall_pack(&self, id: &PackId, delete_files: bool) -> EngineResult<()> {
        let pack = self.require_installed(id)?;
        let _busy = launch::BusyGuard::take(self, id, &pack.name())?;
        if let Some(version) = pack.vanilla_mc_version() {
            return self.uninstall_vanilla(&pack, version, delete_files).await;
        }
        if !self.inner.packs.uninstall(&pack, delete_files).await {
            return Err(EngineError::PackInUse(pack.name()));
        }
        self.emit(EngineEvent::PacksChanged);
        Ok(())
    }

    /// Downloads and verifies every file of the pack (the "Verify game files" action): the
    /// launch pipeline forced FULL, without starting the game. Progress arrives as
    /// [`EngineEvent::Progress`]; returns when done. Refused while the pack is in use.
    pub async fn verify_pack(&self, id: &PackId) -> EngineResult<OperationId> {
        self.verify_pack_with(id, self.next_operation_id()).await
    }

    /// [`Engine::verify_pack`] reporting under a caller-chosen id (from
    /// [`Engine::next_operation_id`]), so the caller can match `Progress` /
    /// `OperationFinished` events while it runs. Returns `op`.
    pub async fn verify_pack_with(
        &self,
        id: &PackId,
        op: OperationId,
    ) -> EngineResult<OperationId> {
        self.verify_pack_impl(id, op).await
    }

    /// Verifies every installed pack in turn (Settings → Advanced). Packs that failed to load
    /// are skipped; the first error is returned after every other pack ran.
    pub async fn verify_all_packs(&self) -> EngineResult<OperationId> {
        self.verify_all_packs_with(self.next_operation_id()).await
    }

    /// [`Engine::verify_all_packs`] under a caller-chosen operation id.
    pub async fn verify_all_packs_with(&self, op: OperationId) -> EngineResult<OperationId> {
        self.verify_all_impl(op).await
    }

    /// Updates the pack to its published version: re-fetch and replace (backup first when
    /// enabled), then a FULL sync, then record the installed version.
    pub async fn update_pack(&self, id: &PackId) -> EngineResult<OperationId> {
        self.update_pack_with(id, self.next_operation_id()).await
    }

    /// [`Engine::update_pack`] under a caller-chosen operation id.
    pub async fn update_pack_with(
        &self,
        id: &PackId,
        op: OperationId,
    ) -> EngineResult<OperationId> {
        self.update_pack_impl(id, op).await
    }

    /// The manifest text of a pack, verbatim (MCP, editor, export), from disk only: an
    /// installed pack, else an available pack of the loaded list, else (when `id` is itself a
    /// manifest URL) that URL's cached copy. `None` for vanilla versions and for manifests
    /// never fetched. [`Engine::manifest_text`] also waits for the available list.
    pub fn pack_manifest_text(&self, id: &PackId) -> Option<String> {
        let url = match self.any_pack(id) {
            Some(pack) => pack.manifest_url()?.to_string(),
            // Only a remote URL's cache copy: a raw `file:` id (MCP passes ids through) must
            // never read an arbitrary local file.
            None if id.as_str().to_ascii_lowercase().starts_with("https://") => {
                id.as_str().to_string()
            }
            None => return None,
        };
        self.inner.packs.fetcher().load_manifest_text(&url)
    }

    /// [`Engine::pack_manifest_text`] for any pack, installed or available: loads the available
    /// list first when the pack is not installed and the list is not loaded yet (MCP
    /// `get_modpack_manifest`, the `mica://modpack/{n}/manifest` resource).
    pub async fn manifest_text(&self, id: &PackId) -> Option<String> {
        if self.installed_pack(id).is_none() && self.any_pack(id).is_none() {
            let _ = self.inner.packs.available().await;
        }
        self.pack_manifest_text(id)
    }

    /// An available (not installed) pack by friendly name, then by pack name.
    pub fn find_available_by_name(&self, name: &str) -> Option<PackSummary> {
        let list = self.inner.packs.available_if_ready();
        let p = list
            .iter()
            .find(|p| p.friendly_name().as_deref() == Some(name))
            .or_else(|| list.iter().find(|p| p.name() == name))?;
        Some(self.summarize(p, false))
    }

    /// Marks all of a pack's visible news as seen.
    pub fn mark_news_seen(&self, id: &PackId) -> EngineResult<()> {
        let pack = self.require_installed(id)?;
        pack.mark_all_news_seen(chrono::Local::now().date_naive());
        self.emit(EngineEvent::PackChanged(id.clone()));
        Ok(())
    }

    /// Sets (or clears) the account this pack launches as.
    pub fn set_pack_account_override(&self, id: &PackId, uuid: Option<&str>) -> EngineResult<()> {
        if let Some(u) = uuid
            && self.inner.auth.accounts().account(u).is_none()
        {
            return Err(EngineError::AccountNotFound(u.to_string()));
        }
        self.inner
            .config
            .set_account_override_for_pack(id.as_str(), uuid);
        self.emit(EngineEvent::PackChanged(id.clone()));
        Ok(())
    }

    /// Sets the pack's "always verify on launch" flag.
    pub fn set_pack_always_verify(&self, id: &PackId, value: bool) -> EngineResult<()> {
        let pack = self.require_installed(id)?;
        let url = pack.manifest_url().unwrap_or_default().to_string();
        self.inner.config.set_always_verify_on_launch(&url, value);
        self.emit(EngineEvent::PackChanged(id.clone()));
        Ok(())
    }

    /// Sets (or clears, `None` = default) the pack's scan frequency.
    pub fn set_pack_scan_frequency(
        &self,
        id: &PackId,
        frequency: Option<mmcl_config::ScanFrequency>,
    ) -> EngineResult<()> {
        let pack = self.require_installed(id)?;
        let url = pack.manifest_url().unwrap_or_default().to_string();
        self.inner
            .config
            .set_scan_frequency_for_pack(&url, frequency);
        self.emit(EngineEvent::PackChanged(id.clone()));
        Ok(())
    }

    /// A folder of the pack (for "open folder" buttons).
    pub fn pack_folder(
        &self,
        id: &PackId,
        folder: mmcl_crash::PackFolder,
    ) -> EngineResult<PathBuf> {
        let pack = self.require_installed(id)?;
        let root = pack.pack_root();
        Ok(match folder {
            mmcl_crash::PackFolder::Root => root,
            mmcl_crash::PackFolder::Mods => root.join("mods"),
            mmcl_crash::PackFolder::CrashReports => root.join("crash-reports"),
        })
    }

    /// Disk use of the pack (bounded: 40,000 entries, 1.5 s; symlinks are not followed).
    pub async fn pack_footprint(&self, id: &PackId) -> EngineResult<PackFootprint> {
        let root = self.require_installed(id)?.pack_root();
        tokio::task::spawn_blocking(move || footprint(&root, 40_000, Duration::from_millis(1500)))
            .await
            .map_err(|e| EngineError::Other(e.to_string()))
    }

    /// The pack's newest crash report (written in the last minute), diagnosed.
    pub fn latest_crash(&self, id: &PackId) -> Option<CrashView> {
        let pack = self.installed_pack(id)?;
        let report = pack.latest_crash_report()?;
        Some(self.diagnose(Some(&report), Some(&pack), 1))
    }

    /// Diagnoses a crash text for a pack (exit code as recorded, 1 when unknown).
    pub fn diagnose_crash(&self, text: &str, id: Option<&PackId>, exit_code: i32) -> CrashView {
        let pack = id.and_then(|i| self.installed_pack(i));
        self.diagnose(Some(text), pack.as_deref(), exit_code)
    }

    fn diagnose(
        &self,
        text: Option<&str>,
        pack: Option<&GameModPack>,
        exit_code: i32,
    ) -> CrashView {
        let name = pack.map(|p| p.name());
        let root = pack.map(|p| p.pack_root());
        let info = pack.map(|p| mmcl_crash::PackInfo {
            name: name.as_deref(),
            root: root.as_deref(),
            url: p.manifest_url(),
        });
        let env = mmcl_crash::Env {
            max_ram_mb: u64::try_from(self.inner.config.max_ram_mb()).unwrap_or(0),
            system_ram_gb: 0,
        };
        let d = mmcl_crash::analyze(text, info.as_ref(), exit_code, &env);
        let r = I18nResolver;
        CrashView {
            category: format!("{:?}", d.category),
            severity: match d.severity {
                mmcl_crash::Severity::Critical => CrashSeverity::Critical,
                mmcl_crash::Severity::Warning => CrashSeverity::Warning,
                mmcl_crash::Severity::Info => CrashSeverity::Info,
            },
            title: d.title.resolve(&r),
            summary: d.summary.resolve(&r),
            suggestions: d
                .suggestions
                .iter()
                .map(|s| CrashSuggestionView {
                    label: s.label.resolve(&r),
                    primary: s.primary,
                    action: s.action.clone(),
                })
                .collect(),
            report: text.map(str::to_string),
        }
    }

    /// Runs a crash suggestion's action, or says what the front end must do for it.
    pub async fn apply_crash_action(
        &self,
        action: &mmcl_crash::CrashAction,
        pack: Option<&PackId>,
    ) -> EngineResult<CrashActionResult> {
        use mmcl_crash::CrashAction as A;
        let need_pack = || {
            pack.ok_or_else(|| {
                EngineError::Other(mmcl_i18n::t!("engine.error.crashActionNeedsPack").into_owned())
            })
        };
        Ok(match action {
            A::SetMaxRamMb(mb) => {
                self.set(Setting::MaxRamMb(i64::try_from(*mb).unwrap_or(i64::MAX)))?;
                CrashActionResult::Done
            }
            A::OpenSettings => CrashActionResult::OpenSettings,
            A::OpenRuntime => CrashActionResult::OpenRuntime,
            A::OpenUrl(u) => CrashActionResult::OpenUrl(u.clone()),
            A::OpenPackFolder(f) => {
                CrashActionResult::OpenPath(self.pack_folder(need_pack()?, *f)?)
            }
            A::DisableMod(path) => {
                let name = path
                    .file_name()
                    .map(|n| n.to_string_lossy().into_owned())
                    .unwrap_or_default();
                // Only a jar directly inside this pack's mods folder may be renamed: the
                // action can arrive from a front end (or MCP) with any path in it.
                let mods = self.pack_folder(need_pack()?, mmcl_crash::PackFolder::Mods)?;
                if !is_direct_child(path, &mods)
                    || !mmcl_modpack::conflicts::disable_jar(&mods, &name)
                {
                    return Err(EngineError::Other(mmcl_i18n::tf!(
                        "engine.error.disableModFailed",
                        name
                    )));
                }
                CrashActionResult::Done
            }
            A::ReinstallPack => {
                self.reinstall_impl(need_pack()?).await?;
                CrashActionResult::Done
            }
        })
    }

    /// Vanilla Minecraft versions of a kind (`release`, `snapshot`, ...; `""` = all).
    pub async fn vanilla_versions(&self, kind: &str) -> Vec<VanillaVersionView> {
        let http = self.inner.http.clone();
        let all =
            mmcl_modpack::catalog::vanilla::all_versions(&self.inner.paths, http.as_ref()).await;
        let list = if kind.is_empty() {
            all
        } else {
            mmcl_modpack::catalog::vanilla::filter_by_type(&all, kind)
        };
        list.into_iter()
            .map(|v| VanillaVersionView {
                installed: mmcl_modpack::catalog::vanilla::is_installed(&self.inner.config, &v.id),
                id: v.id,
                kind: v.kind,
                release_time: v.release_time,
            })
            .collect()
    }

    /// Adds a vanilla version to the library.
    pub async fn install_vanilla(&self, mc_version: &str) -> EngineResult<PackId> {
        mmcl_modpack::catalog::vanilla::install_version(&self.inner.config, mc_version);
        self.inner.packs.fetch_installed(false).await;
        self.emit(EngineEvent::PacksChanged);
        Ok(PackId::vanilla(mc_version))
    }

    /// Loader versions of `loader_type` (`forge`, `neoforge`, `fabric`).
    pub async fn loader_versions(&self, loader_type: &str) -> Vec<LoaderVersionView> {
        use mmcl_modpack::catalog::loaders;
        let http = self.inner.http.clone();
        let list = match loader_type.to_ascii_lowercase().as_str() {
            "forge" => loaders::forge_versions(http.as_ref()).await,
            "neoforge" => loaders::neoforge_versions(http.as_ref()).await,
            "fabric" => loaders::fabric_versions(http.as_ref()).await,
            _ => Vec::new(),
        };
        list.into_iter()
            .map(|v| LoaderVersionView {
                installed: loaders::is_installed(&self.inner.paths, &v),
                display_name: v.display_name(),
                loader_type: v.loader_type.clone(),
                minecraft_version: v.mc_version.clone(),
                loader_version: v.loader_version.clone(),
            })
            .collect()
    }

    /// Creates a new, empty pack (MCP `create_modpack`, quick-start wizard).
    pub async fn create_pack(
        &self,
        name: &str,
        mod_loader: Option<&str>,
        mod_loader_url: Option<&str>,
    ) -> EngineResult<PackId> {
        self.create_pack_impl(name, mod_loader, mod_loader_url)
            .await
    }

    /// Copies a pack under a new name (minor version bumped) and installs the copy.
    pub async fn fork_pack(&self, source: &PackId, new_name: &str) -> EngineResult<PackId> {
        self.fork_pack_impl(source, new_name).await
    }

    /// Writes an edited manifest back to a pack with a local (`file:`) manifest (editor Save),
    /// then reloads the pack list. A remote manifest is refused.
    pub async fn save_manifest(&self, id: &PackId, manifest_json: &str) -> EngineResult<()> {
        self.save_manifest_impl(id, manifest_json).await
    }

    /// Whether this launcher authored the pack's manifest (so add / remove mod work on it).
    pub fn is_authored_pack(&self, id: &PackId) -> bool {
        self.authored_manifest_of(id).is_ok()
    }

    /// Adds a mod entry to an owned pack's manifest (MCP `add_mod_to_modpack`).
    pub async fn add_mod(
        &self,
        id: &PackId,
        mod_name: &str,
        remote_url: &str,
        local_path: &str,
    ) -> EngineResult<()> {
        self.add_mod_impl(id, mod_name, remote_url, local_path)
            .await
    }

    /// Removes the first mod matching local path, name or Modrinth slug from an owned pack.
    pub async fn remove_mod(&self, id: &PackId, identifier: &str) -> EngineResult<()> {
        self.remove_mod_impl(id, identifier).await
    }

    /// Searches Modrinth mods (editor "add a mod").
    pub async fn search_modrinth(
        &self,
        query: &str,
        mc_version: Option<&str>,
        loader: Option<&str>,
        limit: usize,
    ) -> Vec<ModSearchHit> {
        let http = self.inner.http.clone();
        mmcl_modpack::import::modrinth::search(http.as_ref(), query, mc_version, loader, limit)
            .await
            .into_iter()
            .map(ModSearchHit::from)
            .collect()
    }

    /// The newest file of a Modrinth project compatible with the pack's version and loader.
    pub async fn resolve_modrinth_file(
        &self,
        project_id: &str,
        mc_version: Option<&str>,
        loader: Option<&str>,
    ) -> Option<ModFileView> {
        let http = self.inner.http.clone();
        mmcl_modpack::import::modrinth::resolve_latest_compatible_file(
            http.as_ref(),
            project_id,
            mc_version,
            loader,
        )
        .await
        .map(|f| ModFileView {
            filename: f.filename,
            url: f.url,
            sha1: f.sha1,
            sha512: f.sha512,
            size: f.size,
            version_number: None,
        })
    }
}

// =============================================================================================
// Launch and sessions
// =============================================================================================

impl Engine {
    /// Why launching `pack` (as `account_uuid`, else its override, else the default) would be
    /// refused right now; `None` when it may go ahead.
    pub fn why_launch_blocked(
        &self,
        id: &PackId,
        account_uuid: Option<&str>,
    ) -> Option<EngineError> {
        if mmcl_net::Announcements::global().disable_gameplay() {
            return Some(EngineError::LaunchRefused(LaunchRefusal::GameplayDisabled));
        }
        let Some(pack) = self.installed_pack(id) else {
            return Some(EngineError::PackNotInstalled(id.clone()));
        };
        if let Some(s) = self.inner.sessions.active_for_pack(id) {
            return Some(EngineError::LaunchRefused(
                LaunchRefusal::PackAlreadyRunning {
                    pack_name: s.pack_name,
                },
            ));
        }
        if self.mode() == GameMode::Server {
            return None;
        }
        let accounts = self.inner.auth.accounts();
        let (uuid, name) = match account_uuid {
            Some(u) => match accounts.account(u) {
                Some(a) if a.status == mmcl_auth::Status::NeedsSignIn => {
                    return Some(EngineError::Account(mmcl_auth::Problem::NeedsSignIn));
                }
                Some(a) => (a.uuid, a.display_name),
                None => return Some(EngineError::AccountNotFound(u.to_string())),
            },
            None => {
                let over = self.inner.config.account_override_for_pack(id.as_str());
                let r = mmcl_auth::resolve(over.as_deref(), &accounts.accounts());
                match (r.problem, r.uuid) {
                    (Some(p), _) => return Some(EngineError::Account(p)),
                    (None, Some(u)) => (u, r.account_name.unwrap_or_default()),
                    (None, None) => {
                        return Some(EngineError::Account(mmcl_auth::Problem::NoAccount));
                    }
                }
            }
        };
        if let Some(s) = self.inner.sessions.active_for_account(&uuid) {
            return Some(EngineError::LaunchRefused(LaunchRefusal::AccountBusy {
                account_name: name,
                pack_name: s.pack_name,
            }));
        }
        let _ = pack;
        None
    }

    /// The known mod conflicts among the pack's active jars (Java `ModConflictDetector.scan`).
    /// A GUI calls this before [`Engine::launch`] and asks the user what to do.
    pub fn mod_conflicts(&self, id: &PackId) -> EngineResult<Vec<ModConflictView>> {
        let mods = self.pack_folder(id, mmcl_crash::PackFolder::Mods)?;
        Ok(mmcl_modpack::conflicts::scan(&mods)
            .into_iter()
            .map(|c| ModConflictView {
                title: mmcl_i18n::get(c.title_key).into_owned(),
                description: mmcl_i18n::get(c.description_key).into_owned(),
                first_jar: c.first_jar,
                second_jar: c.second_jar,
            })
            .collect())
    }

    /// Disables one jar of the pack's `mods` folder (renamed to `<jar>.disabled`); the
    /// conflict dialog's "Disable" button. Refused while the pack is in use.
    pub fn disable_mod_jar(&self, id: &PackId, jar_name: &str) -> EngineResult<()> {
        let pack = self.require_installed(id)?;
        let _busy = launch::BusyGuard::take(self, id, &pack.name())?;
        let mods = self.pack_folder(id, mmcl_crash::PackFolder::Mods)?;
        if mmcl_modpack::conflicts::disable_jar(&mods, jar_name) {
            self.emit(EngineEvent::PackChanged(id.clone()));
            Ok(())
        } else {
            Err(EngineError::Other(mmcl_i18n::tf!(
                "notification.launch.disableModFailed.title",
                jar_name
            )))
        }
    }

    /// Launches a pack: admission, account resolution, prepare (sync, runtime, loader, scan),
    /// start. Returns as soon as the session exists; follow it with [`EngineEvent::SessionChanged`].
    pub async fn launch(&self, id: &PackId, options: LaunchOptions) -> EngineResult<SessionId> {
        if let Some(e) = self.why_launch_blocked(id, options.account_uuid.as_deref()) {
            return Err(e);
        }
        self.launch_impl(id, options, None).await
    }

    /// [`Engine::launch`] reporting under a caller-chosen operation id (from
    /// [`Engine::next_operation_id`]): while the launch prepares, its rows arrive as
    /// [`EngineEvent::Progress`] and [`EngineEvent::OperationSteps`] of `op`, and
    /// [`EngineEvent::OperationFinished`]`(op)` follows when the game started or the launch
    /// failed. [`Engine::cancel_operation`]`(op)` cancels it. Every launch has such an id
    /// ([`Engine::operation_for_session`]); a refused launch sends `OperationFinished(op)` too.
    pub async fn launch_with(
        &self,
        id: &PackId,
        options: LaunchOptions,
        op: OperationId,
    ) -> EngineResult<SessionId> {
        let res = match self.why_launch_blocked(id, options.account_uuid.as_deref()) {
            Some(e) => Err(e),
            None => self.launch_impl(id, options, Some(op)).await,
        };
        if res.is_err() {
            self.emit(EngineEvent::OperationFinished(op));
        }
        res
    }

    /// Runs a pack as a dedicated server in this process's terminal (server mode); returns the
    /// server's exit code. A crashed server restarts up to 3 times, 5 s apart, re-reading the
    /// manifest first. Ctrl-C stops the restarts (the server, sharing the terminal, shuts down
    /// on its own); a second Ctrl-C kills it. Only in server mode.
    pub async fn run_server(&self, id: &PackId) -> EngineResult<i32> {
        self.run_server_impl(id).await
    }

    /// Every session, oldest first (active and up to 20 ended).
    pub fn sessions(&self) -> Vec<SessionInfo> {
        self.inner.sessions.list()
    }

    /// The active sessions.
    pub fn active_sessions(&self) -> Vec<SessionInfo> {
        self.sessions()
            .into_iter()
            .filter(|s| s.phase.is_active())
            .collect()
    }

    /// `true` while any game prepares or runs (quit / restart confirmations).
    pub fn has_active_sessions(&self) -> bool {
        self.inner.sessions.has_active()
    }

    /// One session.
    pub fn session(&self, id: SessionId) -> Option<SessionInfo> {
        self.inner.sessions.get(id)
    }

    /// The active session of a pack.
    pub fn active_session_for_pack(&self, id: &PackId) -> Option<SessionInfo> {
        self.inner.sessions.active_for_pack(id)
    }

    /// The running game shown in Discord / RGB: the newest running one.
    pub fn newest_running_session(&self) -> Option<SessionInfo> {
        self.sessions()
            .into_iter()
            .filter(|s| s.phase == SessionPhase::Running)
            .fold(None, |best: Option<SessionInfo>, s| match &best {
                Some(b) if b.started_ms > s.started_ms => best,
                _ => Some(s),
            })
    }

    /// Cancels a preparing launch (no effect once the game runs).
    pub fn cancel_launch(&self, id: SessionId) -> EngineResult<()> {
        let info = self
            .inner
            .sessions
            .update(id, |s| {
                if s.phase == SessionPhase::Preparing {
                    s.cancelled = true;
                }
            })
            .ok_or(EngineError::SessionNotFound(id))?;
        if info.phase == SessionPhase::Preparing {
            self.cancel_token_of(id);
        }
        self.emit(EngineEvent::SessionChanged(Arc::new(info)));
        Ok(())
    }

    /// Stops a running game: asks it to exit, or kills it when `force`.
    pub async fn stop_game(&self, id: SessionId, force: bool) -> EngineResult<()> {
        let s = self.session(id).ok_or(EngineError::SessionNotFound(id))?;
        if s.phase == SessionPhase::Preparing {
            return self.cancel_launch(id);
        }
        if !s.phase.is_active() {
            return Ok(());
        }
        self.stop_running(id, force)
    }

    /// Removes an ended session from the list.
    pub fn dismiss_session(&self, id: SessionId) -> bool {
        let removed = self.inner.sessions.dismiss(id);
        if removed {
            self.forget_session_op(id);
            self.emit(EngineEvent::SessionRemoved(id));
        }
        removed
    }

    /// The session's log, cut to the last `max_lines` lines (0 = all).
    pub fn session_log(&self, id: SessionId, max_lines: usize) -> EngineResult<LogSnapshot> {
        self.inner
            .sessions
            .log_snapshot(id, max_lines)
            .ok_or(EngineError::SessionNotFound(id))
    }

    /// A lossless live log: the snapshot plus every later batch.
    pub fn subscribe_log(&self, id: SessionId, max_lines: usize) -> EngineResult<LogSubscription> {
        self.inner
            .sessions
            .subscribe(id, max_lines)
            .ok_or(EngineError::SessionNotFound(id))
    }

    /// The whole log, read from the session log file when the in-memory copy was cut.
    pub async fn session_full_log(&self, id: SessionId) -> EngineResult<String> {
        let info = self.session(id).ok_or(EngineError::SessionNotFound(id))?;
        if info.log_truncated
            && let Some(file) = info.log_file.clone()
            && let Ok(text) = tokio::fs::read(&file).await
        {
            return Ok(String::from_utf8_lossy(&text).into_owned());
        }
        Ok(self.session_log(id, 0)?.text)
    }

    /// The session's crash report and diagnosis (report, else the log).
    pub async fn session_crash(&self, id: SessionId) -> EngineResult<CrashView> {
        let info = self.session(id).ok_or(EngineError::SessionNotFound(id))?;
        let text = match self.inner.sessions.crash_report(id) {
            Some(r) => r,
            None => self.session_full_log(id).await?,
        };
        let pack = self.installed_pack(&info.pack);
        Ok(self.diagnose(Some(&text), pack.as_deref(), info.exit_code.unwrap_or(1)))
    }
}

// =============================================================================================
// Accounts and sign-in
// =============================================================================================

impl Engine {
    /// Every signed-in account, default first.
    pub fn accounts(&self) -> Vec<AccountView> {
        self.inner
            .auth
            .accounts()
            .accounts()
            .iter()
            .map(AccountView::from)
            .collect()
    }

    /// The default account.
    pub fn default_account(&self) -> Option<AccountView> {
        self.accounts().into_iter().find(|a| a.is_default)
    }

    /// Makes `uuid` the default account.
    pub fn set_default_account(&self, uuid: &str) -> EngineResult<()> {
        if self.inner.auth.accounts().set_default(Some(uuid)) {
            Ok(())
        } else {
            Err(EngineError::AccountNotFound(uuid.to_string()))
        }
    }

    /// Signs an account out (removes its saved sign-in).
    pub async fn sign_out(&self, uuid: &str) -> EngineResult<()> {
        if self.inner.auth.accounts().remove(uuid) {
            Ok(())
        } else {
            Err(EngineError::AccountNotFound(uuid.to_string()))
        }
    }

    /// Renews an account's sign-in now.
    pub async fn refresh_account(&self, uuid: &str) -> EngineResult<AccountView> {
        let accounts = self.inner.auth.accounts();
        if accounts.account(uuid).is_none() {
            return Err(EngineError::AccountNotFound(uuid.to_string()));
        }
        accounts
            .refresh(uuid, true)
            .await
            .map_err(|e| EngineError::Other(e.message))?;
        accounts
            .account(uuid)
            .map(|a| AccountView::from(&a))
            .ok_or_else(|| EngineError::AccountNotFound(uuid.to_string()))
    }

    /// The sign-in flows to offer, the default first (device code first in gamepad mode).
    pub fn sign_in_flows(&self) -> Vec<SignInKind> {
        let gamepad = mmcl_core::handheld::effective(self.inner.config.gamepad_mode()).enabled;
        self.inner
            .auth
            .offered_flows(gamepad, self.inner.web_view_supported)
    }

    /// Runs one sign-in to the end. A device code flow reports its code through
    /// [`EngineEvent::SignIn`] ([`SignInEvent::DeviceCode`]); chain steps arrive as
    /// [`SignInEvent::Status`]. [`Engine::cancel_sign_in`] stops it.
    pub async fn sign_in(&self, request: SignInRequest) -> EngineResult<AccountView> {
        let cancel = CancelToken::new();
        {
            let mut slot = self.inner.sign_in_cancel.lock();
            if slot.as_ref().is_some_and(|c| !c.is_cancelled()) {
                return Err(EngineError::SignInBusy);
            }
            *slot = Some(cancel.clone());
        }
        let auth = &self.inner.auth;
        let flow: Box<dyn SignInFlow> = match request.kind {
            SignInKind::WebView => {
                let tx = self.inner.events.clone();
                let flow = auth.web_view_flow().with_page_events(Arc::new(
                    move |e: mmcl_auth::SignInPageEvent| {
                        if e == mmcl_auth::SignInPageEvent::LoadFailed {
                            mmcl_log::warn_silent(&mmcl_i18n::t!("log.login.pageLoadFailed"));
                        }
                        let _ = tx.send(EngineEvent::SignInPage(e.into()));
                    },
                ));
                *self.inner.sign_in_reloader.lock() = Some(flow.reloader());
                Box::new(flow)
            }
            SignInKind::DeviceCode => {
                let tx = self.inner.events.clone();
                Box::new(auth.device_code_flow(Arc::new(move |p| {
                    let _ = tx.send(EngineEvent::SignIn(SignInEvent::DeviceCode(p.clone())));
                })))
            }
            SignInKind::Wam => match auth.wam_flow(request.window_handle.unwrap_or(0)) {
                Ok(f) => Box::new(f),
                Err(e) => {
                    self.inner.sign_in_cancel.lock().take();
                    return Err(e.into());
                }
            },
        };
        let outcome = auth
            .sign_in(
                flow.as_ref(),
                &cancel,
                request.remember,
                request.make_default,
            )
            .await;
        self.inner.sign_in_cancel.lock().take();
        self.inner.sign_in_reloader.lock().take();
        match outcome {
            mmcl_auth::AuthOutcome::Ok(user) => {
                let view = auth
                    .accounts()
                    .account(&user.uuid)
                    .map(|a| AccountView::from(&a))
                    .unwrap_or(AccountView {
                        uuid: user.uuid.clone(),
                        name: user.name.clone(),
                        status: AccountStatus::Ready,
                        is_default: request.make_default,
                        remembered: request.remember,
                        last_used_ms: now_ms(),
                    });
                Ok(view)
            }
            other => Err(other.into()),
        }
    }

    /// Loads Microsoft's page again in the running web view sign-in (the "Try again" button of
    /// the [`crate::SignInPageState::LoadFailed`] state). `false` when no web view sign-in is
    /// running.
    pub fn retry_sign_in_page(&self) -> bool {
        let reloader = self.inner.sign_in_reloader.lock().clone();
        reloader.is_some_and(|r| r.reload())
    }

    /// Cancels the running sign-in, if any.
    pub fn cancel_sign_in(&self) {
        if let Some(c) = self.inner.sign_in_cancel.lock().take() {
            c.cancel();
        }
    }

    /// Finishes a sign-in from an authorization code (a web view the front end ran itself).
    pub async fn sign_in_with_code(
        &self,
        code: &str,
        remember: bool,
        make_default: bool,
    ) -> EngineResult<AccountView> {
        let auth = &self.inner.auth;
        let outcome = if make_default {
            auth.login_with_microsoft(code, remember).await
        } else {
            auth.add_account_with_microsoft(code, remember).await
        };
        match outcome {
            mmcl_auth::AuthOutcome::Ok(user) => auth
                .accounts()
                .account(&user.uuid)
                .map(|a| AccountView::from(&a))
                .ok_or(EngineError::AccountNotFound(user.uuid)),
            other => Err(other.into()),
        }
    }
}

// =============================================================================================
// Settings
// =============================================================================================

impl Engine {
    /// Every setting.
    pub fn settings(&self) -> Settings {
        Settings::read(&self.inner.config)
    }

    /// Writes one setting and runs its side effects (proxy reload, RGB restart, Discord exit).
    pub fn set(&self, setting: Setting) -> EngineResult<SideEffects> {
        let fx = setting.apply(&self.inner.config)?;
        self.run_side_effects(fx);
        self.emit(EngineEvent::SettingChanged(setting.key()));
        Ok(fx)
    }

    /// Writes several settings (the Settings screen's Save); stops at the first invalid one.
    pub fn set_many(
        &self,
        settings: impl IntoIterator<Item = Setting>,
    ) -> EngineResult<SideEffects> {
        let mut all = SideEffects::default();
        // "Proxy on" is stored as off while the host is blank, so it is applied after the
        // host in the same batch, whatever order the screen listed them in.
        let (enable, mut ordered): (Vec<Setting>, Vec<Setting>) = settings
            .into_iter()
            .partition(|s| matches!(s, Setting::ProxyEnable(_)));
        ordered.extend(enable);
        for s in ordered {
            let fx = self.set(s)?;
            all.reload_proxy |= fx.reload_proxy;
            all.restart_rgb |= fx.restart_rgb;
            all.rgb_on_menu |= fx.rgb_on_menu;
            all.discord_exit |= fx.discord_exit;
            all.needs_restart |= fx.needs_restart;
            all.refresh_mcp |= fx.refresh_mcp;
            all.warn_compressed_oops |= fx.warn_compressed_oops;
        }
        Ok(all)
    }

    fn run_side_effects(&self, fx: SideEffects) {
        if fx.reload_proxy {
            mmcl_net::reload_proxy();
        }
        if fx.restart_rgb {
            mmcl_rgb::integration::restart_from_settings();
        } else if fx.rgb_on_menu {
            mmcl_rgb::integration::on_menu(None);
        }
        if fx.discord_exit {
            mmcl_discord::Presence::global().exit();
        }
        if fx.warn_compressed_oops {
            self.emit(EngineEvent::Notification(Notification::new(
                NotificationKind::Warning,
                mmcl_i18n::t!("notification.settings.maxRamAboveCompressedOops.title"),
                mmcl_i18n::t!("notification.settings.maxRamAboveCompressedOops.body"),
            )));
        }
    }

    /// The saved main-window placement.
    pub fn window_bounds(&self) -> mmcl_config::WindowBounds {
        self.inner.config.window_bounds()
    }

    /// Saves the main-window placement.
    pub fn set_window_bounds(&self, bounds: mmcl_config::WindowBounds) {
        self.inner.config.set_window_bounds(bounds);
    }

    /// Whether a CurseForge API key is stored (the key itself is never shown).
    pub fn has_curseforge_api_key(&self) -> bool {
        self.inner.config.has_curseforge_api_key()
    }

    /// Stores (trimmed) or clears (`None` / blank) the CurseForge API key, encrypted.
    pub fn set_curseforge_api_key(&self, key: Option<&str>) {
        let key = key.map(str::trim).filter(|k| !k.is_empty());
        self.inner
            .config
            .set_curseforge_api_key(self.inner.cipher.as_ref(), key);
        self.emit(EngineEvent::SettingChanged(
            mmcl_config::keys::CURSEFORGE_API_KEY_KEY,
        ));
    }

    /// Writes the settings to a file (Settings → System → Export).
    pub async fn export_settings(&self, dest: &Path) -> EngineResult<()> {
        let config = self.inner.config.clone();
        let dest = dest.to_path_buf();
        tokio::task::spawn_blocking(move || config.export_to(&dest))
            .await
            .map_err(|e| EngineError::Other(e.to_string()))??;
        Ok(())
    }

    /// Replaces the settings from a file (Settings → System → Import).
    pub async fn import_settings(&self, src: &Path) -> EngineResult<()> {
        let config = self.inner.config.clone();
        let src = src.to_path_buf();
        tokio::task::spawn_blocking(move || config.import_from(&src))
            .await
            .map_err(|e| EngineError::Other(e.to_string()))??;
        mmcl_net::reload_proxy();
        self.emit(EngineEvent::SettingChanged(""));
        Ok(())
    }

    /// Deletes the whole data folder and asks the front end to restart
    /// ([`EngineEvent::RestartRequested`]). Refused while games run. The engine is shut down
    /// first (and stays shut down), and the log file sink is closed so the folder can move;
    /// the caller must release its single-instance lock before calling and must then relaunch
    /// the process, never restart in-process. A folder in use fails the rename with nothing
    /// deleted (`settings.resetLauncher.failed`).
    pub async fn reset_launcher(&self) -> EngineResult<()> {
        self.reset_launcher_impl().await
    }

    /// Total and used system memory (Settings → Game).
    pub async fn system_memory(&self) -> EngineResult<SystemMemory> {
        self.system_memory_impl().await
    }

    /// JVM arguments tuned for this machine and `max_ram_gb` (the "Generate" button).
    pub fn hardware_tuned_jvm_args(&self, max_ram_gb: f64) -> EngineResult<String> {
        self.hardware_tuned_jvm_args_impl(max_ram_gb)
    }

    /// The localized one-line basis of [`Engine::hardware_tuned_jvm_args`] (cores, RAM, heap).
    pub fn hardware_tuning_summary(&self, max_ram_gb: f64) -> String {
        mmcl_util::jvm_args::summary(max_ram_gb.round().clamp(1.0, f64::from(i32::MAX)) as i32)
    }
}

// =============================================================================================
// Runtimes, backups, export and import
// =============================================================================================

impl Engine {
    /// The installed Java runtimes (sizes every tree; runs off the caller's thread).
    pub async fn runtimes(&self) -> Vec<RuntimeView> {
        let rm = self.inner.runtimes.clone();
        tokio::task::spawn_blocking(move || {
            rm.installed()
                .into_iter()
                .map(|r| RuntimeView {
                    in_use: rm.is_in_use(&r.component),
                    version: (!r.version.is_empty()).then_some(r.version),
                    component: r.component,
                    path: r.path,
                    size_mb: r.size_mb,
                })
                .collect()
        })
        .await
        .unwrap_or_default()
    }

    /// Installs or repairs a runtime component, reporting [`EngineEvent::Progress`].
    pub async fn install_runtime(&self, component: &str) -> EngineResult<OperationId> {
        self.install_runtime_with(component, self.next_operation_id())
            .await
    }

    /// [`Engine::install_runtime`] under a caller-chosen operation id.
    pub async fn install_runtime_with(
        &self,
        component: &str,
        op: OperationId,
    ) -> EngineResult<OperationId> {
        let tx = self.inner.events.clone();
        let progress = move |label: &str, text: &str, percent: f64| {
            let _ = tx.send(EngineEvent::Progress(OperationProgress {
                operation: op,
                label: label.to_string(),
                text: text.to_string(),
                fraction: if percent < 0.0 { -1.0 } else { percent / 100.0 },
            }));
        };
        let res = self
            .inner
            .runtimes
            .verify(component, Some(&progress), &CancelToken::new())
            .await;
        self.emit(EngineEvent::OperationFinished(op));
        res?;
        Ok(op)
    }

    /// Deletes a runtime (fails while a game uses it).
    pub async fn remove_runtime(&self, component: &str) -> EngineResult<()> {
        self.inner.runtimes.clear(component).await?;
        Ok(())
    }

    /// Deletes every runtime; returns the components that could not be deleted.
    pub async fn remove_all_runtimes(&self) -> Vec<String> {
        let mut failed = Vec::new();
        for r in self.inner.runtimes.installed() {
            if self.inner.runtimes.clear(&r.component).await.is_err() {
                failed.push(r.component);
            }
        }
        failed
    }

    /// The pack's backups, newest first.
    pub async fn backups(&self, id: &PackId) -> EngineResult<Vec<BackupView>> {
        let dir = self
            .require_installed(id)?
            .pack_root()
            .join(mmcl_modpack::backup::BACKUPS_DIR);
        tokio::task::spawn_blocking(move || list_backups(&dir))
            .await
            .map_err(|e| EngineError::Other(e.to_string()))
    }

    /// Backs up the pack's `config` (and `saves` when the setting says so) now.
    pub async fn backup_now(&self, id: &PackId) -> EngineResult<Option<PathBuf>> {
        let pack = self.require_installed(id)?;
        let mut policy = mmcl_modpack::backup::BackupPolicy::from_config(&self.inner.config);
        policy.auto_before_update = true;
        tokio::task::spawn_blocking(move || {
            let root = pack.checked_pack_root().ok()?;
            let out = mmcl_modpack::backup::backup_folder(
                &root,
                &policy,
                chrono::Local::now().naive_local(),
            );
            mmcl_modpack::backup::prune_old_backups(&pack, &policy);
            out
        })
        .await
        .map_err(|e| EngineError::Other(e.to_string()))
    }

    /// Restores a backup over the pack folder.
    pub async fn restore_backup(&self, id: &PackId, backup: &Path) -> EngineResult<()> {
        self.restore_backup_impl(id, backup).await
    }

    /// Exports a pack: a zip, the manifest JSON, or a profile in the official launcher (which
    /// verifies, installs the loader when missing and reports the outcome as a notification;
    /// see [`Engine::export_official`] for the step-by-step form).
    pub async fn export_pack(
        &self,
        id: &PackId,
        request: ExportRequest,
    ) -> EngineResult<OperationId> {
        self.export_pack_with(id, request, self.next_operation_id())
            .await
    }

    /// [`Engine::export_pack`] under a caller-chosen operation id.
    pub async fn export_pack_with(
        &self,
        id: &PackId,
        request: ExportRequest,
        op: OperationId,
    ) -> EngineResult<OperationId> {
        let pack = match self.require_installed(id) {
            Ok(p) => p,
            Err(e) => {
                self.emit(EngineEvent::OperationFinished(op));
                return Err(e);
            }
        };
        match request {
            ExportRequest::Zip {
                dest,
                include_saves,
            } => {
                let packs = self.inner.packs.clone();
                let tx = self.inner.events.clone();
                let label = mmcl_i18n::t!("engine.progress.export").into_owned();
                let res = tokio::task::spawn_blocking(move || {
                    mmcl_modpack::export::export_to_zip(
                        &pack,
                        packs.fetcher(),
                        &dest,
                        include_saves,
                        |bytes| {
                            let _ = tx.send(EngineEvent::Progress(OperationProgress {
                                operation: op,
                                label: label.clone(),
                                text: mmcl_util::units::bytes(
                                    i64::try_from(bytes).unwrap_or(i64::MAX),
                                ),
                                fraction: -1.0,
                            }));
                        },
                    )
                })
                .await;
                self.emit(EngineEvent::OperationFinished(op));
                res.map_err(|e| EngineError::Other(e.to_string()))??;
            }
            ExportRequest::ManifestJson { dest } => {
                let res = match self.pack_manifest_text(id) {
                    Some(text) => tokio::fs::write(&dest, text)
                        .await
                        .map_err(EngineError::from),
                    None => Err(EngineError::PackNotFound(id.clone())),
                };
                self.emit(EngineEvent::OperationFinished(op));
                res?;
            }
            ExportRequest::OfficialLauncher => {
                let res = self.export_official_notifying(id, op).await;
                self.emit(EngineEvent::OperationFinished(op));
                res?;
            }
        }
        Ok(op)
    }

    /// Where a pasted URL points.
    pub fn classify_import_url(&self, url: &str) -> ImportSourceView {
        use mmcl_modpack::import::ImportSource as S;
        match mmcl_modpack::import::classify(Some(url)).source {
            S::Modrinth => ImportSourceView::Modrinth,
            S::CurseForge => ImportSourceView::CurseForge,
            S::Technic => ImportSourceView::Technic,
            S::Mica => ImportSourceView::Mica,
            S::Unknown => ImportSourceView::Unknown,
        }
    }

    /// Imports a pack from a URL, file or instance folder and installs it.
    pub async fn import(&self, request: ImportRequest) -> EngineResult<ImportOutcome> {
        use mmcl_modpack::import;
        let m = &self.inner.packs;
        let (url, mod_count) = match request {
            ImportRequest::Url(u) => {
                let c = import::classify(Some(&u));
                match c.source {
                    import::ImportSource::Mica => {
                        let id = self.install_pack_by_url(&u).await?;
                        return Ok(ImportOutcome {
                            pack: id,
                            mod_count: None,
                        });
                    }
                    import::ImportSource::Modrinth => {
                        // Preview, then install: the same two steps a front end with an
                        // import-confirm dialog runs itself.
                        let project = self.preview_modrinth(&u).await?.ok_or_else(|| {
                            EngineError::Other(mmcl_i18n::tf!(
                                "engine.error.modrinthProjectNotFound",
                                c.slug.as_deref().unwrap_or_default()
                            ))
                        })?;
                        return self.install_modrinth(&project).await;
                    }
                    // No direct import (the Java launcher offers to open the page instead):
                    // CurseForge needs a paid API key; Technic packs come as server zips.
                    import::ImportSource::CurseForge => {
                        return Err(EngineError::Other(
                            mmcl_i18n::t!("dialog.import.curseforge.header").into_owned(),
                        ));
                    }
                    import::ImportSource::Technic => {
                        return Err(EngineError::Other(
                            mmcl_i18n::t!("dialog.import.technic.header").into_owned(),
                        ));
                    }
                    import::ImportSource::Unknown => {
                        // Anything else is tried as a Mica manifest URL.
                        let id = self.install_pack_by_url(&u).await?;
                        return Ok(ImportOutcome {
                            pack: id,
                            mod_count: None,
                        });
                    }
                }
            }
            ImportRequest::MrpackFile(p) => {
                let http: Arc<dyn PackHttp> = m.fetcher().http().clone();
                let r =
                    import::mrpack::import_mrpack_file(m.paths(), http.as_ref(), &p, None, None)
                        .await?;
                m.install_by_url(&r.local_manifest_url).await?;
                (r.local_manifest_url, Some(r.mod_count))
            }
            ImportRequest::ZipFile(p) => (import::zip::import_zip(m, &p).await?, None),
            ImportRequest::ManifestFile(p) => {
                (import::mmcjson::import_mmcjson_file(m, &p).await?, None)
            }
            ImportRequest::PrismInstance(p) => (import::prism::import_instance(m, &p).await?, None),
        };
        self.emit(EngineEvent::PacksChanged);
        let pack = m
            .installed_by_url(&url)
            .map(|p| PackId::of(&p))
            .unwrap_or_else(|| PackId::new(url));
        Ok(ImportOutcome { pack, mod_count })
    }
}

// =============================================================================================
// Network, notifications, Discord, RGB, announcements, misc
// =============================================================================================

impl Engine {
    /// Whether the launcher is offline.
    pub fn is_offline(&self) -> bool {
        mmcl_net::is_offline()
    }

    /// Probes the network now; returns `true` when online.
    pub async fn check_network(&self) -> bool {
        let online = self.inner.http.refresh_network_availability().await;
        self.emit(EngineEvent::OfflineChanged(!online));
        online
    }

    /// Shows a toast through every front end.
    pub fn notify(&self, notification: Notification) {
        self.emit(EngineEvent::Notification(notification));
    }

    /// Shows the "selecting a pack" / screen presence in Discord.
    pub fn set_discord_menu_presence(&self, screen: &str) {
        mmcl_discord::Presence::global().set_menu_presence(screen);
    }

    /// The RGB status chip.
    pub fn rgb_status(&self) -> RgbStatusView {
        let enabled = self.inner.config.rgb_enable();
        let c = mmcl_rgb::integration::current();
        let status = match &c {
            Some(i) => i.controller().status(),
            None => mmcl_rgb::RgbController::global().status(),
        };
        let running = match &c {
            Some(i) => i.controller().is_running(),
            None => mmcl_rgb::RgbController::global().is_running(),
        };
        let chip = status.chip(enabled);
        RgbStatusView {
            enabled,
            running,
            text: chip.text,
            tone: match chip.tone {
                mmcl_rgb::controller::ChipTone::Muted => ChipTone::Muted,
                mmcl_rgb::controller::ChipTone::Success => ChipTone::Success,
                mmcl_rgb::controller::ChipTone::Warn => ChipTone::Warn,
                mmcl_rgb::controller::ChipTone::Danger => ChipTone::Danger,
            },
        }
    }

    /// Flashes the connection test colour; `false` when RGB is off.
    pub fn rgb_test(&self) -> bool {
        mmcl_rgb::integration::run_connection_test()
    }

    /// Restarts the RGB controller from the settings.
    pub fn rgb_restart(&self) {
        mmcl_rgb::integration::restart_from_settings();
        self.emit(EngineEvent::RgbStatusChanged(self.rgb_status()));
    }

    /// The announcement banners (as last fetched).
    pub fn announcements(&self) -> AnnouncementsView {
        let a = mmcl_net::Announcements::global();
        AnnouncementsView {
            home: a.home(),
            login: a.login(),
            settings: a.config(),
            modpacks_edit: a.modpacks_edit(),
            disable_gameplay: a.disable_gameplay(),
            disable_modpacks_edit: a.disable_modpacks_edit(),
        }
    }

    /// Fetches the announcements again.
    pub async fn refresh_announcements(&self) -> AnnouncementsView {
        let _ = mmcl_net::Announcements::global().start_check_async().await;
        self.emit(EngineEvent::AnnouncementsChanged);
        self.announcements()
    }

    /// Hides a banner for this run.
    pub fn dismiss_announcement(&self, text: &str) {
        mmcl_net::Announcements::global().dismiss_for_session(text);
        self.emit(EngineEvent::AnnouncementsChanged);
    }

    /// Checks GitHub for a newer launcher release.
    pub async fn check_launcher_update(&self) -> EngineResult<Option<UpdateInfo>> {
        self.check_launcher_update_impl().await
    }

    /// Scans a folder for malware (Settings → Security), reporting [`EngineEvent::Progress`]
    /// (`text` = the latest output line).
    pub async fn scan_folder(&self, folder: &Path) -> EngineResult<ScanSummary> {
        self.scan_folder_with(folder, self.next_operation_id())
            .await
    }

    /// [`Engine::scan_folder`] reporting `Progress` under a caller-chosen id (so the screen
    /// can tell the scan's progress from other operations'); `OperationFinished(op)` ends it.
    /// [`Engine::cancel_scan`] stops it: the summary then has `cancelled = true`.
    pub async fn scan_folder_with(
        &self,
        folder: &Path,
        op: OperationId,
    ) -> EngineResult<ScanSummary> {
        self.scan_folder_impl(folder, op).await
    }

    /// Cancels a running malware scan.
    pub fn cancel_scan(&self) {
        self.cancel_scan_impl();
    }
}

// =============================================================================================
// Helpers
// =============================================================================================

/// The bounded footprint walk (MCP `footprint_of`).
pub(crate) fn footprint(root: &Path, max_entries: usize, budget: Duration) -> PackFootprint {
    let mut out = PackFootprint {
        size_bytes: 0,
        world_count: 0,
        approximate: false,
    };
    if !root.is_dir() {
        return out;
    }
    let saves = root.join("saves");
    let start = Instant::now();
    let mut stack = vec![root.to_path_buf()];
    let mut visited = 0usize;
    while let Some(dir) = stack.pop() {
        if start.elapsed() > budget || visited >= max_entries {
            out.approximate = true;
            break;
        }
        let Ok(rd) = std::fs::read_dir(&dir) else {
            out.approximate = true;
            continue;
        };
        for entry in rd.flatten() {
            visited += 1;
            let Ok(ft) = entry.file_type() else { continue };
            if ft.is_symlink() {
                continue;
            }
            if ft.is_dir() {
                if dir == saves {
                    out.world_count += 1;
                }
                stack.push(entry.path());
            } else if ft.is_file() {
                out.size_bytes += entry.metadata().map(|m| m.len()).unwrap_or(0);
            }
        }
    }
    out
}

/// `true` when `path` names an entry directly inside `dir` (lexically, after making both
/// absolute; `..` segments are resolved first, so they cannot climb out).
pub(crate) fn is_direct_child(path: &Path, dir: &Path) -> bool {
    let norm = |p: &Path| {
        std::path::absolute(p)
            .ok()
            .map(|a| mmcl_core::paths::normalize_lexical(&a))
    };
    match (norm(path), norm(dir)) {
        (Some(p), Some(d)) => p.parent() == Some(d.as_path()) && p.file_name().is_some(),
        _ => false,
    }
}

fn list_backups(dir: &Path) -> Vec<BackupView> {
    let Ok(rd) = std::fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut out: Vec<BackupView> = rd
        .flatten()
        .filter(|e| e.path().extension().is_some_and(|x| x == "zip"))
        .filter_map(|e| {
            let md = e.metadata().ok()?;
            let modified_ms = md
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX));
            Some(BackupView {
                file_name: e.file_name().to_string_lossy().into_owned(),
                path: e.path(),
                size_bytes: md.len(),
                modified_ms,
            })
        })
        .collect();
    out.sort_by_key(|b| std::cmp::Reverse(b.modified_ms));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn quiet_engine(root: &Path) -> (tokio::runtime::Runtime, Engine) {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .unwrap();
        let o = EngineOptions::offline_for_tests(
            DataPaths::at_root(root, GameMode::Client),
            rt.handle().clone(),
        );
        let e = Engine::new(o).unwrap();
        (rt, e)
    }

    #[test]
    fn footprint_counts_worlds_and_bytes() {
        let d = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(d.path().join("saves/World1/region")).unwrap();
        std::fs::create_dir_all(d.path().join("saves/World2")).unwrap();
        std::fs::write(d.path().join("saves/World1/level.dat"), [0u8; 100]).unwrap();
        std::fs::write(d.path().join("options.txt"), [0u8; 20]).unwrap();
        let f = footprint(d.path(), 40_000, Duration::from_secs(5));
        assert_eq!(f.world_count, 2);
        assert_eq!(f.size_bytes, 120);
        assert!(!f.approximate);
        let missing = footprint(&d.path().join("nope"), 10, Duration::from_secs(1));
        assert_eq!(missing.size_bytes, 0);
    }

    #[test]
    fn footprint_stops_at_the_entry_budget() {
        let d = tempfile::tempdir().unwrap();
        for i in 0..10 {
            std::fs::create_dir_all(d.path().join(format!("d{i}/x"))).unwrap();
        }
        assert!(footprint(d.path(), 3, Duration::from_secs(5)).approximate);
    }

    #[test]
    fn an_engine_starts_quietly_and_reads_settings() {
        let d = tempfile::tempdir().unwrap();
        let (_rt, e) = quiet_engine(d.path());
        assert_eq!(e.mode(), GameMode::Client);
        assert!(e.accounts().is_empty());
        assert!(e.sessions().is_empty());
        let fx = e.set(Setting::UiScalePercent(150)).unwrap();
        assert_eq!(fx, SideEffects::default());
        assert_eq!(e.settings().ui_scale_percent, 150);
        let status = e.status();
        assert!(!status.signed_in);
    }

    #[test]
    fn spawned_tasks_resolve_on_any_executor() {
        let d = tempfile::tempdir().unwrap();
        let (_rt, e) = quiet_engine(d.path());
        let t = e.spawn(async { 41 + 1 });
        assert_eq!(futures::executor::block_on(t), Ok(42));
    }

    #[test]
    fn launching_an_unknown_pack_is_refused() {
        let d = tempfile::tempdir().unwrap();
        let (_rt, e) = quiet_engine(d.path());
        let err = futures::executor::block_on(e.spawn({
            let e = e.clone();
            async move {
                e.launch(&PackId::new("nope"), LaunchOptions::default())
                    .await
            }
        }))
        .unwrap()
        .unwrap_err();
        assert!(matches!(err, EngineError::PackNotInstalled(_)));
    }

    #[test]
    fn unknown_sessions_report_not_found() {
        let d = tempfile::tempdir().unwrap();
        let (_rt, e) = quiet_engine(d.path());
        assert!(matches!(
            e.session_log(SessionId(999_999), 0),
            Err(EngineError::SessionNotFound(_))
        ));
        assert!(!e.dismiss_session(SessionId(999_999)));
    }

    #[test]
    fn events_reach_subscribers() {
        let d = tempfile::tempdir().unwrap();
        let (_rt, e) = quiet_engine(d.path());
        let mut rx = e.subscribe();
        e.set(Setting::WavyProgress(false)).unwrap();
        match rx.try_recv().unwrap() {
            EngineEvent::SettingChanged(k) => assert_eq!(k, "wavyProgress"),
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn proxy_on_survives_being_listed_before_its_host() {
        let d = tempfile::tempdir().unwrap();
        let (_rt, e) = quiet_engine(d.path());
        e.set_many([
            Setting::ProxyEnable(true),
            Setting::ProxyHost("proxy.example".into()),
        ])
        .unwrap();
        assert!(e.config().proxy_enable());
        assert_eq!(e.config().proxy_host(), "proxy.example");
    }

    #[test]
    fn only_direct_children_of_the_mods_folder_qualify() {
        let d = tempfile::tempdir().unwrap();
        let mods = d.path().join("pack").join("mods");
        assert!(is_direct_child(&mods.join("a.jar"), &mods));
        assert!(!is_direct_child(&mods.join("sub").join("a.jar"), &mods));
        assert!(!is_direct_child(&mods.join("..").join("a.jar"), &mods));
        assert!(!is_direct_child(
            &d.path().join("other").join("a.jar"),
            &mods
        ));
        assert!(!is_direct_child(&mods, &mods));
    }

    #[test]
    fn a_crash_action_needing_a_pack_says_so() {
        let d = tempfile::tempdir().unwrap();
        let (_rt, e) = quiet_engine(d.path());
        let err = futures::executor::block_on(e.spawn({
            let e = e.clone();
            async move {
                e.apply_crash_action(
                    &mmcl_crash::CrashAction::DisableMod(PathBuf::from("/tmp/x.jar")),
                    None,
                )
                .await
            }
        }))
        .unwrap()
        .unwrap_err();
        assert!(!err.message().trim().is_empty());
    }

    #[test]
    fn backups_are_listed_newest_first() {
        let d = tempfile::tempdir().unwrap();
        std::fs::write(d.path().join("a.zip"), b"1").unwrap();
        std::fs::write(d.path().join("notes.txt"), b"1").unwrap();
        let l = list_backups(d.path());
        assert_eq!(l.len(), 1);
        assert_eq!(l[0].file_name, "a.zip");
    }
}

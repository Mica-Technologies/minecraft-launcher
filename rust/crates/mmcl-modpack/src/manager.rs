// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The installed and available pack lists (Java `GameModPackManager`), as a service object.
//!
//! Readers take lock-free snapshots ([`ArcSwap`]); list mutations happen under a short
//! synchronous lock that is never held across an `.await`; the high-level operations (fetch,
//! install, uninstall) are serialised by an async lock that is never held while waiting for the
//! background available fetch (the Java deadlock).
//!
//! Guards:
//! * a fresh fetch replaces the index stub **in place** by URL (appending next to it once made
//!   by-name lookups find the stub, whose null mod list launched the game modless);
//! * a background revalidate that fails keeps the cached pack (a network blip must not replace
//!   a good pack with an unlaunchable one);
//! * a headless server can wait for revalidation, so it never launches last start's manifest;
//! * re-installing an installed URL replaces it in place (Java appended a duplicate config URL);
//! * a pack that is launching or running is never uninstalled.

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use arc_swap::{ArcSwap, ArcSwapOption};
use futures::FutureExt;
use futures::future::{BoxFuture, Shared};
use indexmap::IndexSet;
use mmcl_config::Config;
use mmcl_core::DataPaths;
use mmcl_i18n::{t, tf};
use parking_lot::{Mutex, RwLock};
use tokio::sync::{Semaphore, watch};

use crate::backup::{self, BackupPolicy};
use crate::error::{ModpackError, Result};
use crate::fetch::{ManifestFetcher, Notifier};
use crate::http::PackHttp;
use crate::index::InstallIndex;
use crate::pack::GameModPack;
use crate::update_log;

/// How long a blocking launch waits for revalidation.
pub const REVALIDATE_BLOCK_TIMEOUT: Duration = Duration::from_secs(120);
/// The available-packs list key.
pub const AVAILABLE_KEY: &str = mmcl_core::consts::modpack::AVAILABLE_PACKS_MANIFEST_LIST_KEY;

/// A pack list snapshot.
pub type PackList = Arc<Vec<Arc<GameModPack>>>;
/// `(localized message, error)` for background failures.
pub type BackgroundErrorListener = Arc<dyn Fn(&str, &ModpackError) + Send + Sync>;
/// "Is this pack launching or running?" (the session registry answers).
pub type ActiveCheck = Arc<dyn Fn(&GameModPack) -> bool + Send + Sync>;

type SharedTask = Shared<BoxFuture<'static, ()>>;

/// Replaces the first pack with `url` by `fresh`, else appends it.
pub(crate) fn replace_or_append_by_url(
    list: &mut Vec<Arc<GameModPack>>,
    url: &str,
    fresh: Arc<GameModPack>,
) {
    match list.iter().position(|p| p.manifest_url() == Some(url)) {
        Some(i) => list[i] = fresh,
        None => list.push(fresh),
    }
}

/// Replaces the first pack with `url` by `fresh`; `false` when none has it.
pub(crate) fn replace_by_url(
    list: &mut [Arc<GameModPack>],
    url: &str,
    fresh: Arc<GameModPack>,
) -> bool {
    match list.iter().position(|p| p.manifest_url() == Some(url)) {
        Some(i) => {
            list[i] = fresh;
            true
        }
        None => false,
    }
}

/// The uninstall sequence with its guard: refuse an active pack before touching anything;
/// otherwise delete the folder when asked (a failure there does not stop the uninstall), then
/// unregister.
pub(crate) fn uninstall_guarded(
    pack: &GameModPack,
    delete_files: bool,
    is_active: impl Fn(&GameModPack) -> bool,
    delete_folder: impl FnOnce(&GameModPack),
    unregister: impl FnOnce(&GameModPack),
) -> bool {
    if is_active(pack) {
        return false;
    }
    if delete_files {
        delete_folder(pack);
    }
    unregister(pack);
    true
}

/// Parses `installable.json`, skipping elements that are not strings.
pub fn parse_available_list(body: &str) -> Vec<String> {
    match mmcl_manifest::gson::parse_lenient(body) {
        Ok((serde_json::Value::Object(o), _)) => match o.get(AVAILABLE_KEY) {
            Some(serde_json::Value::Array(a)) => a
                .iter()
                .filter_map(|v| {
                    v.as_str()
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .map(str::to_string)
                })
                .collect(),
            _ => Vec::new(),
        },
        _ => Vec::new(),
    }
}

/// The pack lists service.
pub struct ModPackManager {
    paths: Arc<DataPaths>,
    fetcher: ManifestFetcher,
    config: Config,
    available_url: RwLock<String>,
    is_active: RwLock<ActiveCheck>,
    notifier: RwLock<Notifier>,
    listener: RwLock<Option<BackgroundErrorListener>>,
    installed: ArcSwap<Vec<Arc<GameModPack>>>,
    available: ArcSwapOption<Vec<Arc<GameModPack>>>,
    list_lock: Mutex<()>,
    op_lock: tokio::sync::Mutex<()>,
    available_lock: tokio::sync::Mutex<()>,
    available_fetch: Mutex<Option<SharedTask>>,
    installed_revalidate: Mutex<Option<SharedTask>>,
    loaded_once: AtomicBool,
    version_tx: watch::Sender<u64>,
    fetch_sem: Arc<Semaphore>,
}

fn fetch_pool_size() -> usize {
    let cores = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4);
    (2 * cores).max(4)
}

impl ModPackManager {
    /// A manager over `paths` and `config`, fetching through `http`.
    pub fn new(paths: Arc<DataPaths>, http: Arc<dyn PackHttp>, config: Config) -> Arc<Self> {
        let fetcher = ManifestFetcher::new(paths.clone(), http);
        Self::with_fetcher(fetcher, config)
    }

    /// A manager over an explicit fetcher.
    pub fn with_fetcher(fetcher: ManifestFetcher, config: Config) -> Arc<Self> {
        let (tx, _rx) = watch::channel(0u64);
        Arc::new(ModPackManager {
            paths: fetcher.paths().clone(),
            fetcher,
            config,
            available_url: RwLock::new(
                mmcl_core::consts::modpack::AVAILABLE_PACKS_MANIFEST_URL.to_string(),
            ),
            is_active: RwLock::new(Arc::new(|_| false)),
            notifier: RwLock::new(Arc::new(|_title, body| mmcl_log::warn(body))),
            listener: RwLock::new(None),
            installed: ArcSwap::from_pointee(Vec::new()),
            available: ArcSwapOption::empty(),
            list_lock: Mutex::new(()),
            op_lock: tokio::sync::Mutex::new(()),
            available_lock: tokio::sync::Mutex::new(()),
            available_fetch: Mutex::new(None),
            installed_revalidate: Mutex::new(None),
            loaded_once: AtomicBool::new(false),
            version_tx: tx,
            fetch_sem: Arc::new(Semaphore::new(fetch_pool_size())),
        })
    }

    /// Overrides the available-list URL (tests, mirrors).
    pub fn set_available_url(&self, url: &str) {
        *self.available_url.write() = url.to_string();
    }

    pub fn fetcher(&self) -> &ManifestFetcher {
        &self.fetcher
    }
    pub fn paths(&self) -> &Arc<DataPaths> {
        &self.paths
    }
    pub fn config(&self) -> &Config {
        &self.config
    }

    /// Registers the "is this pack active?" check (the session registry).
    pub fn set_active_check(&self, f: ActiveCheck) {
        *self.is_active.write() = f;
    }

    /// Replaces the toast notifier (`(title, body)`).
    pub fn set_notifier(&self, n: Notifier) {
        *self.notifier.write() = n;
    }

    /// Sets (or clears) the listener for background fetch failures. Its panics are caught.
    pub fn set_background_error_listener(&self, l: Option<BackgroundErrorListener>) {
        *self.listener.write() = l;
    }

    /// Bumped whenever a list changes.
    pub fn subscribe(&self) -> watch::Receiver<u64> {
        self.version_tx.subscribe()
    }

    fn bump(&self) {
        self.version_tx.send_modify(|v| *v = v.wrapping_add(1));
    }

    fn fire_background_error(&self, msg: &str, err: &ModpackError) {
        let l = self.listener.read().clone();
        if let Some(l) = l {
            let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| l(msg, err)));
            if r.is_err() {
                mmcl_log::warn_silent(&tf!("log.gameModPackManager.bgErrorListenerThrew", "panic"));
            }
        }
    }

    fn mutate_installed(&self, f: impl FnOnce(&mut Vec<Arc<GameModPack>>)) {
        let _g = self.list_lock.lock();
        let mut v = (**self.installed.load()).clone();
        f(&mut v);
        self.installed.store(Arc::new(v));
        drop(_g);
        self.bump();
    }

    fn offline(&self) -> bool {
        self.fetcher.http().is_offline()
    }

    // ---- getters -------------------------------------------------------------------------

    /// The installed packs (lock-free). The first call starts a background load.
    pub fn installed(self: &Arc<Self>) -> PackList {
        if !self.loaded_once.swap(true, Ordering::SeqCst) {
            let me = self.clone();
            mmcl_net::pool::runtime_handle().spawn(async move { me.fetch_mod_pack_info().await });
        }
        self.installed.load_full()
    }

    /// The installed packs without triggering a load.
    pub fn installed_snapshot(&self) -> PackList {
        self.installed.load_full()
    }

    /// The available packs, waiting for the background fetch (fetching lazily if never run).
    pub async fn available(self: &Arc<Self>) -> PackList {
        let pending = self.available_fetch.lock().clone();
        if let Some(f) = pending {
            f.await;
        }
        if self.available.load().is_none() {
            self.fetch_available().await;
        }
        self.available_if_ready()
    }

    /// The available packs now, or empty.
    pub fn available_if_ready(&self) -> PackList {
        self.available.load_full().unwrap_or_default()
    }

    pub fn installed_urls(&self) -> Vec<String> {
        self.installed
            .load()
            .iter()
            .filter_map(|p| p.manifest_url().map(str::to_string))
            .collect()
    }
    pub fn installed_friendly_names(&self) -> Vec<String> {
        self.installed
            .load()
            .iter()
            .filter_map(|p| p.friendly_name())
            .collect()
    }
    pub fn available_friendly_names(&self) -> Vec<String> {
        self.available_if_ready()
            .iter()
            .filter_map(|p| p.friendly_name())
            .collect()
    }

    /// First installed pack whose name equals `name` ignoring case.
    pub fn installed_by_name(&self, name: &str) -> Option<Arc<GameModPack>> {
        let n = name.to_lowercase();
        self.installed
            .load()
            .iter()
            .find(|p| {
                p.manifest()
                    .pack_name
                    .as_deref()
                    .is_some_and(|x| x.to_lowercase() == n)
            })
            .cloned()
    }

    /// The installed pack with this manifest URL: exact match first, then ignoring case
    /// (kept for compatibility with Java callers).
    pub fn installed_by_url(&self, url: &str) -> Option<Arc<GameModPack>> {
        let list = self.installed.load();
        list.iter()
            .find(|p| p.manifest_url() == Some(url))
            .or_else(|| {
                let u = url.to_lowercase();
                list.iter()
                    .find(|p| p.manifest_url().is_some_and(|x| x.to_lowercase() == u))
            })
            .cloned()
    }

    /// The **last** installed pack whose friendly name equals `name` ignoring case (as Java).
    pub fn installed_by_friendly_name(&self, name: &str) -> Option<Arc<GameModPack>> {
        let n = name.to_lowercase();
        self.installed
            .load()
            .iter()
            .rev()
            .find(|p| p.friendly_name().is_some_and(|f| f.to_lowercase() == n))
            .cloned()
    }

    /// `true` while the background available fetch runs.
    pub fn is_available_fetch_pending(&self) -> bool {
        self.available_fetch
            .lock()
            .as_ref()
            .is_some_and(|f| f.peek().is_none())
    }

    /// The background revalidate of the installed packs, if one started.
    pub fn installed_revalidate(&self) -> Option<Shared<BoxFuture<'static, ()>>> {
        self.installed_revalidate.lock().clone()
    }

    /// `true` while the background revalidate runs.
    pub fn is_installed_revalidate_pending(&self) -> bool {
        self.installed_revalidate
            .lock()
            .as_ref()
            .is_some_and(|f| f.peek().is_none())
    }

    // ---- fetching ------------------------------------------------------------------------

    /// Refreshes the network flag, reloads the installed list, then the available list when
    /// online.
    pub async fn fetch_mod_pack_info(self: &Arc<Self>) {
        self.loaded_once.store(true, Ordering::SeqCst);
        self.fetcher.http().refresh_network_availability().await;
        self.fetch_installed(false).await;
        if !self.offline() {
            self.fetch_available().await;
        }
    }

    /// Reloads the installed list: index stubs, then disk caches, then the network for
    /// misses, then (online) a background revalidate of the rest. With
    /// `block_until_revalidated` (headless server, auto-launch) waits for that revalidate up
    /// to [`REVALIDATE_BLOCK_TIMEOUT`].
    pub async fn fetch_installed(self: &Arc<Self>, block_until_revalidated: bool) {
        self.loaded_once.store(true, Ordering::SeqCst);
        let _op = self.op_lock.lock().await;
        // De-duplicated: Java could write the same URL twice, and a second stub for it would
        // survive the in-place replace below (by-name lookups take the last match, so they
        // would find the stub and its null mod list).
        let urls: Vec<String> = self
            .config
            .installed_mod_packs()
            .into_iter()
            .filter(|u| !u.trim().is_empty())
            .collect::<IndexSet<_>>()
            .into_iter()
            .collect();
        self.mutate_installed(|v| v.clear());

        // Phase 1a: stubs from the index (one file read).
        let index = InstallIndex::load(&self.paths.install_index_file());
        let stubs: Vec<Arc<GameModPack>> = urls
            .iter()
            .filter_map(|u| {
                index
                    .get(u)
                    .map(|e| GameModPack::stub_from_index(self.paths.clone(), u, e))
            })
            .collect();
        self.mutate_installed(|v| v.extend(stubs));

        // Phase 1b: disk caches in parallel.
        let disk = futures::future::join_all(urls.iter().map(|u| {
            let (f, u) = (self.fetcher.clone(), u.clone());
            async move {
                let r = tokio::task::spawn_blocking({
                    let u = u.clone();
                    move || f.get_from_cache(&u, true)
                })
                .await
                .ok()
                .flatten();
                (u, r)
            }
        }))
        .await;
        let mut misses = Vec::new();
        let mut from_cache = Vec::new();
        for (u, r) in disk {
            match r {
                Some(p) => {
                    self.mutate_installed(|v| replace_or_append_by_url(v, &u, p));
                    from_cache.push(u);
                }
                None => misses.push(u),
            }
        }

        // Phase 1b network for the misses.
        let fresh = futures::future::join_all(misses.iter().map(|u| {
            let (me, u) = (self.clone(), u.clone());
            async move {
                let _permit = me.fetch_sem.clone().acquire_owned().await.ok();
                (u.clone(), me.fetcher.get(&u, true).await)
            }
        }))
        .await;
        for (u, p) in fresh {
            update_log::record_remote_version_seen(&p);
            self.mutate_installed(|v| replace_or_append_by_url(v, &u, p));
        }

        // Phase 2: background revalidate of what came from caches and stubs.
        let online = !self.offline();
        let revalidate_urls: Vec<String> = from_cache;
        let started = if online && !self.installed.load().is_empty() && !revalidate_urls.is_empty()
        {
            let me = self.clone();
            let task: BoxFuture<'static, ()> =
                async move { me.revalidate(revalidate_urls).await }.boxed();
            let shared = task.shared();
            *self.installed_revalidate.lock() = Some(shared.clone());
            let s2 = shared.clone();
            mmcl_net::pool::runtime_handle().spawn(s2);
            Some(shared)
        } else {
            None
        };
        drop(_op);

        if block_until_revalidated {
            match started {
                Some(f) => {
                    mmcl_log::std(&t!("log.gameModPackManager.waitingRevalidation"));
                    match tokio::time::timeout(REVALIDATE_BLOCK_TIMEOUT, f).await {
                        Ok(()) => mmcl_log::std(&t!("log.gameModPackManager.revalidationComplete")),
                        Err(_) => mmcl_log::error_silent(&tf!(
                            "log.gameModPackManager.revalidationTimedOut",
                            REVALIDATE_BLOCK_TIMEOUT.as_secs().to_string()
                        )),
                    }
                }
                None if !online => {
                    mmcl_log::error_silent(&t!("log.gameModPackManager.notRevalidatedOffline"))
                }
                None if self.installed.load().is_empty() => {
                    mmcl_log::error_silent(&t!("log.gameModPackManager.notRevalidatedNoPacks"))
                }
                // Everything was fetched fresh from the network in this pass.
                None => mmcl_log::std(&t!("log.gameModPackManager.revalidationComplete")),
            }
        }
    }

    async fn revalidate(self: Arc<Self>, urls: Vec<String>) {
        let me = self.clone();
        let run = mmcl_net::pool::runtime_handle().spawn(async move {
            futures::future::join_all(urls.into_iter().map(|u| {
                let me = me.clone();
                async move {
                    let _permit = me.fetch_sem.clone().acquire_owned().await.ok();
                    // Skip packs uninstalled since the pass started.
                    if !me.installed_urls().contains(&u) {
                        return;
                    }
                    let fresh = me.fetcher.get(&u, true).await;
                    if fresh.is_failed_load() {
                        mmcl_log::warn_silent(&tf!(
                            "log.gameModPackManager.revalidateUnusable",
                            &u
                        ));
                        return;
                    }
                    update_log::record_remote_version_seen(&fresh);
                    me.mutate_installed(|v| {
                        replace_by_url(v, &u, fresh);
                    });
                }
            }))
            .await;
        });
        if let Err(e) = run.await {
            mmcl_log::error_silent(&t!("log.gameModPackManager.installedRevalidateFailed"));
            let err = ModpackError::Manifest(e.to_string());
            self.fire_background_error(&t!("gameModPackManager.installedRevalidateFailed"), &err);
        }
    }

    /// Loads the available list (packs not installed), without images or folders.
    pub async fn fetch_available(self: &Arc<Self>) {
        let _g = self.available_lock.lock().await;
        let list_url = self.available_url.read().clone();
        let body = match self
            .fetcher
            .http()
            .get_bounded(&list_url, mmcl_net::MANIFEST_MAX_BYTES)
            .await
        {
            Ok(b) => b,
            Err(e) => {
                mmcl_log::error_silent(&t!("log.gameModPackManager.availableFetchFailed"));
                mmcl_log::error_silent(&e.to_string());
                return;
            }
        };
        let installed: HashSet<String> = self.installed_urls().into_iter().collect();
        let urls: Vec<String> = parse_available_list(&body)
            .into_iter()
            .filter(|u| !installed.contains(u))
            .collect::<IndexSet<_>>()
            .into_iter()
            .collect();
        self.available.store(Some(Arc::new(Vec::new())));
        let packs = futures::future::join_all(urls.iter().map(|u| {
            let (me, u) = (self.clone(), u.clone());
            async move {
                let _permit = me.fetch_sem.clone().acquire_owned().await.ok();
                me.fetcher.get(&u, false).await
            }
        }))
        .await;
        let list: Vec<Arc<GameModPack>> = packs
            .into_iter()
            .filter(|p| p.friendly_name().is_some())
            .collect();
        self.available.store(Some(Arc::new(list)));
        self.bump();
    }

    /// Starts the background available fetch once (offline: an already finished task).
    pub fn start_available_fetch(self: &Arc<Self>) -> Shared<BoxFuture<'static, ()>> {
        let mut slot = self.available_fetch.lock();
        if let Some(f) = slot.as_ref() {
            return f.clone();
        }
        let task: BoxFuture<'static, ()> = if self.offline() {
            futures::future::ready(()).boxed()
        } else {
            let me = self.clone();
            async move {
                let inner = me.clone();
                let h = mmcl_net::pool::runtime_handle()
                    .spawn(async move { inner.fetch_available().await });
                if let Err(e) = h.await {
                    mmcl_log::error_silent(&t!("log.gameModPackManager.availableFetchFailed"));
                    let err = ModpackError::Manifest(e.to_string());
                    me.fire_background_error(&t!("gameModPackManager.availableFetchFailed"), &err);
                }
            }
            .boxed()
        };
        let shared = task.shared();
        mmcl_net::pool::runtime_handle().spawn(shared.clone());
        *slot = Some(shared.clone());
        shared
    }

    // ---- mutations -----------------------------------------------------------------------

    /// Writes the installed URLs (de-duplicated, in order) to the config.
    pub fn save_to_config(&self) {
        let urls: Vec<String> = self
            .installed_urls()
            .into_iter()
            .collect::<IndexSet<_>>()
            .into_iter()
            .collect();
        self.config.set_installed_mod_packs(&urls);
    }

    /// Adds (or replaces in place, by URL) a pack, saves and reloads.
    pub async fn install(self: &Arc<Self>, pack: Arc<GameModPack>) {
        {
            let _op = self.op_lock.lock().await;
            match pack.manifest_url().map(str::to_string) {
                Some(u) => self.mutate_installed(|v| replace_or_append_by_url(v, &u, pack.clone())),
                None => self.mutate_installed(|v| v.push(pack.clone())),
            }
            self.save_to_config();
        }
        self.fetch_mod_pack_info().await;
    }

    /// Fetches and installs `url`. Backs up the existing install first when the URL is
    /// already installed. A failed fetch is refused for a new install, and an existing entry is
    /// kept when its update fetch fails.
    pub async fn install_by_url(self: &Arc<Self>, url: &str) -> Result<Arc<GameModPack>> {
        let pack = self.fetcher.get(url, true).await;
        if let Some(err) = pack.failed_error() {
            mmcl_log::error_silent(&tf!("log.gameModPackManager.unableToInstallModPack", url));
            return Err(ModpackError::Manifest(err.to_string()));
        }
        if self.installed_urls().iter().any(|u| u == url) {
            let policy = BackupPolicy::from_config(&self.config);
            backup::backup_before_update(&pack, &policy);
        }
        self.install(pack.clone()).await;
        Ok(pack)
    }

    /// Installs the available pack with this exact friendly name.
    pub async fn install_by_friendly_name(
        self: &Arc<Self>,
        name: &str,
    ) -> Result<Arc<GameModPack>> {
        let available = self.available().await;
        let Some(p) = available
            .iter()
            .find(|p| p.friendly_name().as_deref() == Some(name))
            .cloned()
        else {
            mmcl_log::error_silent(&tf!("modpack.error.notAvailable", name));
            return Err(ModpackError::NotAvailable(name.to_string()));
        };
        match p.manifest_url().map(str::to_string) {
            Some(u) => self.install_by_url(&u).await,
            None => {
                self.install(p.clone()).await;
                Ok(p)
            }
        }
    }

    /// Uninstalls `pack`, deleting its folder when asked. Refused (returns `false`, with a
    /// toast) while the pack is launching or running.
    pub async fn uninstall(self: &Arc<Self>, pack: &Arc<GameModPack>, delete_files: bool) -> bool {
        let is_active = self.is_active.read().clone();
        // An in-flight revalidate would re-create the folder we are about to delete.
        if delete_files
            && !is_active(pack)
            && let Some(f) = self.installed_revalidate()
        {
            let _ = tokio::time::timeout(Duration::from_secs(30), f).await;
        }
        let mut unregistered = false;
        let ok = {
            let _op = self.op_lock.lock().await;
            uninstall_guarded(
                pack,
                delete_files,
                |p| is_active(p),
                |p| {
                    if let Ok(root) = p.checked_pack_root()
                        && root.exists()
                        && let Err(e) = std::fs::remove_dir_all(&root)
                    {
                        mmcl_log::warn_silent(&tf!(
                            "log.gameLibrary.deleteFolderFailed",
                            e.to_string()
                        ));
                    }
                },
                |p| {
                    let url = p.manifest_url().map(str::to_string);
                    self.mutate_installed(|v| match &url {
                        Some(u) => v.retain(|x| x.manifest_url() != Some(u.as_str())),
                        None => v.retain(|x| !Arc::ptr_eq(x, pack)),
                    });
                    if let Some(u) = &url {
                        InstallIndex::remove_and_save(&self.paths.install_index_file(), u);
                    }
                    self.save_to_config();
                    unregistered = true;
                },
            )
        };
        if !ok {
            mmcl_log::warn_silent(&tf!(
                "log.modPackManager.uninstallRefusedRunning",
                pack.name()
            ));
            let n = self.notifier.read().clone();
            n(
                &t!("notification.pack.running.title"),
                &tf!("notification.pack.running.uninstall", pack.name()),
            );
            return false;
        }
        if unregistered {
            self.fetch_mod_pack_info().await;
        }
        true
    }

    /// Uninstalls the first pack with exactly this URL (files kept).
    pub async fn uninstall_by_url(self: &Arc<Self>, url: &str) -> bool {
        let p = self
            .installed
            .load()
            .iter()
            .find(|p| p.manifest_url() == Some(url))
            .cloned();
        match p {
            Some(p) => self.uninstall(&p, false).await,
            None => false,
        }
    }

    /// Uninstalls the first pack with exactly this friendly name (files kept).
    pub async fn uninstall_by_friendly_name(self: &Arc<Self>, name: &str) -> bool {
        let p = self
            .installed
            .load()
            .iter()
            .find(|p| p.friendly_name().as_deref() == Some(name))
            .cloned();
        match p {
            Some(p) => self.uninstall(&p, false).await,
            None => false,
        }
    }

    /// The pack folder of `pack` (for callers that show it).
    pub fn pack_folder(pack: &GameModPack) -> PathBuf {
        pack.pack_root()
    }
}

#[cfg(test)]
mod tests;

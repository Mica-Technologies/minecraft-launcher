// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`RuntimeManager`]: verify, install, list and delete Java runtimes (Java `RuntimeManager`).
//!
//! Three flows, picked per component and host:
//!
//! * `jre-legacy` → Bell-SW Liberica 8u392 (spec 9.3), on every host.
//! * any other component on a host Mojang publishes runtimes for → the Mojang component
//!   manifest (spec 9.2).
//! * any other component on Linux ARM64, where Mojang publishes nothing (Q-2) → the latest
//!   Liberica JRE of the component's Java major, Adoptium as the second source. The folder is
//!   still `runtime/<component>/` with `bin/java` at the top, so the fast path is the same.
//!
//! **Marker protocol.** `.version` is deleted before any file of an install changes and written
//! last on success. A folder with a marker is whole; one without is reinstalled. Never reorder
//! those writes.

use std::collections::{HashMap, HashSet};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Weak};
use std::time::{Duration, Instant};

use futures::StreamExt;
use mmcl_core::DataPaths;
use mmcl_core::consts::runtime::{
    MOJANG_RUNTIME_INDEX_FILE_NAME, RUNTIME_MANIFEST_FILE_NAME, RUNTIME_VERSION_FILE_NAME,
};
use mmcl_core::paths::normalize_lexical;
use mmcl_i18n::{t, tf};
use mmcl_mojang::runtime_index::{
    ComponentManifest, RUNTIME_INDEX_URL, RuntimeFile, RuntimeIndex, component_for_major,
};
use mmcl_mojang::{Arch, HostInfo, Os};
use mmcl_net::CancelToken;
use parking_lot::Mutex;
use tokio::task::JoinHandle;

use crate::error::RuntimeError;
use crate::fetch::{Fetcher, NetFetcher};
use crate::fsutil::{self, find_java_executable, mark_java_binaries_executable, read_marker};
use crate::install::{self, InstalledRuntime, is_unsafe_entry_name, resolve_within};
use crate::inuse::InUseRegistry;
use crate::liberica::{self, Digest, Release, ReleaseSource};
use crate::progress::{RuntimeProgress, report_progress};

/// The Liberica JRE 8 component.
pub const LEGACY_COMPONENT: &str = "jre-legacy";
/// How long a fetched Mojang runtime index is trusted in memory.
pub const RUNTIME_INDEX_TTL: Duration = Duration::from_secs(10 * 60);
/// Download attempts per Mojang runtime file before its hash failure is final.
pub const FILE_HASH_ATTEMPTS: u32 = 3;
/// The cached Bell-SW API response for `jre-legacy`.
pub const LEGACY_API_FILE_NAME: &str = "jre-legacy.api.json";
/// The cached Bell-SW API response of a non-Mojang runtime.
pub const LIBERICA_API_FILE_NAME: &str = "liberica.api.json";
/// The cached Adoptium API response of a non-Mojang runtime.
pub const ADOPTIUM_API_FILE_NAME: &str = "adoptium.api.json";
/// Which source a non-Mojang runtime was installed from (`liberica` or `adoptium`).
pub const SOURCE_FILE_NAME: &str = ".source";
/// Version recorded when a Mojang runtime's marker could not be read.
pub const UNKNOWN_VERSION: &str = "Unknown";
/// Version recorded when the JRE 8 archive held no `java` and PATH `java` is used (parity).
pub const SYSTEM_JAVA_VERSION: &str = "Unknown (System Java)";

const STAGING_DIR_NAME: &str = ".extract";
const PROGRESS_EVERY_FILES: usize = 20;

/// A verified runtime.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ResolvedRuntime {
    pub component: String,
    /// The Java executable (absolute; `java` only in the JRE 8 "System Java" case).
    pub java: PathBuf,
    /// The installed version; empty when an in-use runtime was kept without reading its marker.
    pub version: String,
}

/// Where a component's files come from on this host.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RuntimeSource {
    /// Bell-SW Liberica 8u392 (`jre-legacy`).
    LegacyLiberica,
    /// Mojang's runtime index, with this platform key.
    Mojang(&'static str),
    /// Latest Liberica (then Adoptium) JRE of this Java major.
    Alternative(u32),
    /// Nothing can provide this component here.
    Unavailable,
}

/// The Java major a Mojang component stands for, used where Mojang publishes no build.
pub fn component_major(component: &str) -> Option<u32> {
    match component {
        LEGACY_COMPONENT => Some(8),
        "java-runtime-alpha" => Some(16),
        "java-runtime-beta" | "java-runtime-gamma" | "java-runtime-gamma-snapshot" => Some(17),
        "java-runtime-delta" => Some(21),
        "java-runtime-epsilon" => Some(25),
        _ => None,
    }
}

/// `true` on hosts that get their non-legacy runtimes from Liberica/Adoptium (Linux ARM64).
pub fn uses_alternative_source(host: &HostInfo) -> bool {
    host.mojang_runtime_key().is_none() && host.os == Os::Linux && host.arch == Arch::Aarch64
}

/// Which flow serves `component` on `host`.
pub fn source_for(component: &str, host: &HostInfo) -> RuntimeSource {
    if component == LEGACY_COMPONENT {
        return RuntimeSource::LegacyLiberica;
    }
    if let Some(key) = host.mojang_runtime_key() {
        return RuntimeSource::Mojang(key);
    }
    if uses_alternative_source(host)
        && let Some(major) = component_major(component)
    {
        return RuntimeSource::Alternative(major);
    }
    RuntimeSource::Unavailable
}

fn host_token(host: &HostInfo) -> String {
    format!("{:?}-{}", host.os, host.java_os_arch).to_ascii_lowercase()
}

enum UpdateCheck {
    Mojang,
    Alternative(u32),
    Legacy,
}

/// The process-wide Java runtime manager.
pub struct RuntimeManager {
    me: Weak<RuntimeManager>,
    paths: Arc<DataPaths>,
    host: HostInfo,
    fetcher: Arc<dyn Fetcher>,
    locks: Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>,
    verified: Mutex<HashMap<String, (PathBuf, String)>>,
    in_use: InUseRegistry,
    index: tokio::sync::Mutex<Option<(Arc<RuntimeIndex>, Instant)>>,
    index_ttl: Duration,
    update_checked: Mutex<HashSet<String>>,
    background: Mutex<Vec<JoinHandle<()>>>,
}

impl std::fmt::Debug for RuntimeManager {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RuntimeManager")
            .field("root", &self.paths.runtime_dir())
            .field("host", &self.host)
            .finish_non_exhaustive()
    }
}

impl RuntimeManager {
    /// A manager on the real host, downloading through `mmcl-net`.
    pub fn new(paths: Arc<DataPaths>) -> Arc<Self> {
        Self::with_parts(paths, HostInfo::detect().clone(), Arc::new(NetFetcher))
    }

    /// A manager with an explicit host and download seam (tests, tools).
    pub fn with_parts(
        paths: Arc<DataPaths>,
        host: HostInfo,
        fetcher: Arc<dyn Fetcher>,
    ) -> Arc<Self> {
        Self::with_ttl(paths, host, fetcher, RUNTIME_INDEX_TTL)
    }

    fn with_ttl(
        paths: Arc<DataPaths>,
        host: HostInfo,
        fetcher: Arc<dyn Fetcher>,
        index_ttl: Duration,
    ) -> Arc<Self> {
        Arc::new_cyclic(|me| RuntimeManager {
            me: me.clone(),
            paths,
            host,
            fetcher,
            locks: Mutex::new(HashMap::new()),
            verified: Mutex::new(HashMap::new()),
            in_use: InUseRegistry::new(),
            index: tokio::sync::Mutex::new(None),
            index_ttl,
            update_checked: Mutex::new(HashSet::new()),
            background: Mutex::new(Vec::new()),
        })
    }

    /// The host this manager installs for.
    pub fn host(&self) -> &HostInfo {
        &self.host
    }

    /// `<data>/runtime/<component>`.
    pub fn component_dir(&self, component: &str) -> PathBuf {
        self.paths.runtime_component_dir(component)
    }

    /// Java major → component (8 → `jre-legacy`, 17 → `java-runtime-gamma`, ...).
    pub fn component_for_major(major: i32) -> &'static str {
        component_for_major(major)
    }

    fn lock_for(&self, component: &str) -> Arc<tokio::sync::Mutex<()>> {
        self.locks
            .lock()
            .entry(component.to_string())
            .or_insert_with(|| Arc::new(tokio::sync::Mutex::new(())))
            .clone()
    }

    /// Verifies `component`, installing or repairing it as needed. Serialised per component;
    /// different components install in parallel. Never falls back to PATH `java` for a
    /// Mojang component: an unrecoverable failure is an error.
    pub async fn verify(
        &self,
        component: &str,
        progress: Option<&dyn RuntimeProgress>,
        cancel: &CancelToken,
    ) -> Result<ResolvedRuntime, RuntimeError> {
        let lock = self.lock_for(component);
        let _guard = lock.lock().await;
        let resolved = match source_for(component, &self.host) {
            RuntimeSource::LegacyLiberica => self.verify_legacy(progress, cancel).await?,
            RuntimeSource::Mojang(platform) => {
                self.verify_mojang(component, platform, progress, cancel)
                    .await?
            }
            RuntimeSource::Alternative(major) => {
                self.verify_alternative(component, major, progress, cancel)
                    .await?
            }
            RuntimeSource::Unavailable => {
                let cause = if uses_alternative_source(&self.host) {
                    RuntimeError::UnsupportedComponent {
                        component: component.to_string(),
                    }
                } else {
                    RuntimeError::PlatformNotFound {
                        platform: host_token(&self.host),
                    }
                };
                mmcl_log::error(&tf!(
                    "log.runtimeManager.installFailed",
                    component,
                    cause.to_string()
                ));
                return Err(RuntimeError::InstallFailed {
                    component: component.to_string(),
                    cause: Box::new(cause),
                });
            }
        };
        self.verified.lock().insert(
            component.to_string(),
            (resolved.java.clone(), resolved.version.clone()),
        );
        Ok(resolved)
    }

    /// [`Self::verify`] for a Java major version.
    pub async fn verify_for_major(
        &self,
        major: i32,
        progress: Option<&dyn RuntimeProgress>,
        cancel: &CancelToken,
    ) -> Result<ResolvedRuntime, RuntimeError> {
        self.verify(component_for_major(major), progress, cancel)
            .await
    }

    /// The Java executable of `component`, verifying it first when this session has not.
    pub async fn java_path(
        &self,
        component: &str,
        cancel: &CancelToken,
    ) -> Result<PathBuf, RuntimeError> {
        if let Some((p, _)) = self.verified.lock().get(component) {
            return Ok(p.clone());
        }
        Ok(self.verify(component, None, cancel).await?.java)
    }

    /// [`Self::java_path`] for a Java major version.
    pub async fn java_path_for_major(
        &self,
        major: i32,
        cancel: &CancelToken,
    ) -> Result<PathBuf, RuntimeError> {
        self.java_path(component_for_major(major), cancel).await
    }

    /// The version verified this session, if any.
    pub fn java_version(&self, component: &str) -> Option<String> {
        self.verified.lock().get(component).map(|(_, v)| v.clone())
    }

    /// Records that the game `pid` runs on `component` until `exited` resolves.
    pub fn mark_in_use(
        &self,
        component: &str,
        pid: u32,
        exited: impl std::future::Future<Output = ()> + Send + 'static,
    ) {
        self.in_use.mark(component, pid, exited);
    }

    /// `true` while a running game uses `component`.
    pub fn is_in_use(&self, component: &str) -> bool {
        self.in_use.is_in_use(component)
    }

    /// Deletes `component`'s folder and forgets it. Refused while a game uses it.
    pub async fn clear(&self, component: &str) -> Result<(), RuntimeError> {
        if self.is_in_use(component) {
            return Err(RuntimeError::InUse {
                component: component.to_string(),
            });
        }
        let lock = self.lock_for(component);
        let _guard = lock.lock().await;
        if self.is_in_use(component) {
            return Err(RuntimeError::InUse {
                component: component.to_string(),
            });
        }
        let dir = self.component_dir(component);
        if dir.exists() {
            tokio::task::spawn_blocking(move || fs::remove_dir_all(&dir))
                .await
                .map_err(|e| RuntimeError::Io(io::Error::other(e.to_string())))??;
        }
        self.verified.lock().remove(component);
        self.update_checked.lock().remove(component);
        Ok(())
    }

    /// [`Self::clear`] for a Java major version.
    pub async fn clear_for_major(&self, major: i32) -> Result<(), RuntimeError> {
        self.clear(component_for_major(major)).await
    }

    /// Every installed runtime (a folder under `runtime/` with a `.version` marker).
    pub fn installed(&self) -> Vec<InstalledRuntime> {
        install::scan_installed(&self.paths.runtime_dir())
    }

    /// Waits for the background update checks spawned so far (tests, orderly shutdown).
    pub async fn join_background(&self) {
        let handles: Vec<_> = std::mem::take(&mut *self.background.lock());
        for h in handles {
            let _ = h.await;
        }
    }

    // ------------------------------------------------------------------ Mojang flow

    async fn verify_mojang(
        &self,
        component: &str,
        platform: &'static str,
        progress: Option<&dyn RuntimeProgress>,
        cancel: &CancelToken,
    ) -> Result<ResolvedRuntime, RuntimeError> {
        let label = tf!("runtime.label.named", component);
        report_progress(progress, &label, &t!("runtime.status.checking"), 5.0);
        let dir = self.component_dir(component);
        let _ = fs::create_dir_all(&dir);
        let marker = dir.join(RUNTIME_VERSION_FILE_NAME);
        let exe = absolute(&dir.join(self.host.java_exec_rel_path()));

        if let Some(r) =
            self.fast_path(component, &dir, &exe, &label, progress, UpdateCheck::Mojang)
        {
            return Ok(r);
        }
        if let Some(r) = self.in_use_defer(component, &exe, &label, progress) {
            return Ok(r);
        }

        match self
            .install_mojang(component, platform, &dir, &exe, &label, progress, cancel)
            .await
        {
            Ok(r) => {
                report_progress(progress, &label, &t!("runtime.status.completed"), 100.0);
                Ok(r)
            }
            Err(e) => {
                self.fallback_to_existing(component, &marker, &exe, &label, progress, cancel, e)
            }
        }
    }

    /// Marker and exe present: trust them without touching the network, and look for an update
    /// in the background (the next launch installs it).
    fn fast_path(
        &self,
        component: &str,
        dir: &Path,
        exe: &Path,
        label: &str,
        progress: Option<&dyn RuntimeProgress>,
        check: UpdateCheck,
    ) -> Option<ResolvedRuntime> {
        let marker = dir.join(RUNTIME_VERSION_FILE_NAME);
        if !(marker.exists() && exe.exists()) {
            return None;
        }
        let version = read_marker(&marker).unwrap_or_else(|| UNKNOWN_VERSION.to_string());
        mmcl_log::std(&tf!(
            "log.runtimeManager.alreadyInstalledBgCheck",
            component,
            version.as_str()
        ));
        report_progress(
            progress,
            label,
            &t!("runtime.status.alreadyInstalled"),
            100.0,
        );
        self.spawn_update_check(component, version.clone(), check);
        Some(ResolvedRuntime {
            component: component.to_string(),
            java: exe.to_path_buf(),
            version,
        })
    }

    /// The marker was dropped by an update check, but a running game uses the runtime: keep
    /// the installed files (overwriting them would break the game, and Windows locks them).
    fn in_use_defer(
        &self,
        component: &str,
        exe: &Path,
        label: &str,
        progress: Option<&dyn RuntimeProgress>,
    ) -> Option<ResolvedRuntime> {
        if !(self.is_in_use(component) && exe.exists()) {
            return None;
        }
        mmcl_log::std(&tf!("log.runtimeManager.inUseDeferUpdate", component));
        report_progress(
            progress,
            label,
            &t!("runtime.status.alreadyInstalled"),
            100.0,
        );
        Some(ResolvedRuntime {
            component: component.to_string(),
            java: exe.to_path_buf(),
            version: String::new(),
        })
    }

    /// After a failed install: use the old runtime only when its marker shows it is whole (the
    /// attempt failed before changing anything). A cancel is never papered over.
    #[allow(clippy::too_many_arguments)]
    fn fallback_to_existing(
        &self,
        component: &str,
        marker: &Path,
        exe: &Path,
        label: &str,
        progress: Option<&dyn RuntimeProgress>,
        cancel: &CancelToken,
        e: RuntimeError,
    ) -> Result<ResolvedRuntime, RuntimeError> {
        mmcl_log::error(&tf!(
            "log.runtimeManager.installFailed",
            component,
            e.to_string()
        ));
        if e.is_cancelled() || cancel.is_cancelled() {
            return Err(RuntimeError::Cancelled);
        }
        if !marker.is_file() || !exe.exists() {
            return Err(RuntimeError::InstallFailed {
                component: component.to_string(),
                cause: Box::new(e),
            });
        }
        let version = read_marker(marker).unwrap_or_else(|| UNKNOWN_VERSION.to_string());
        mmcl_log::std(&tf!(
            "log.runtimeManager.usingExisting",
            exe.display().to_string()
        ));
        report_progress(progress, label, &t!("runtime.status.completed"), 100.0);
        Ok(ResolvedRuntime {
            component: component.to_string(),
            java: exe.to_path_buf(),
            version,
        })
    }

    #[allow(clippy::too_many_arguments)]
    async fn install_mojang(
        &self,
        component: &str,
        platform: &'static str,
        dir: &Path,
        exe: &Path,
        label: &str,
        progress: Option<&dyn RuntimeProgress>,
        cancel: &CancelToken,
    ) -> Result<ResolvedRuntime, RuntimeError> {
        check_cancel(cancel)?;
        report_progress(progress, label, &t!("runtime.status.fetchingIndex"), 10.0);
        let index = self.mojang_index(cancel).await?;
        let entry = index.entry(platform, component)?.clone();
        let version_name = entry.version.name.clone();
        let marker = dir.join(RUNTIME_VERSION_FILE_NAME);

        if read_marker(&marker).as_deref() == Some(version_name.as_str()) && exe.exists() {
            mmcl_log::std(&tf!(
                "log.runtimeManager.alreadyInstalled",
                component,
                version_name.as_str()
            ));
            report_progress(
                progress,
                label,
                &t!("runtime.status.alreadyInstalled"),
                100.0,
            );
            return Ok(ResolvedRuntime {
                component: component.to_string(),
                java: exe.to_path_buf(),
                version: version_name,
            });
        }

        report_progress(
            progress,
            label,
            &t!("runtime.status.downloadingManifest"),
            15.0,
        );
        let manifest_file = dir.join(RUNTIME_MANIFEST_FILE_NAME);
        self.fetch(&entry.manifest.url, &manifest_file, None, cancel)
            .await?;
        let manifest = ComponentManifest::parse(&fs::read(&manifest_file)?)?;
        let total = manifest.files.len();
        mmcl_log::std(&tf!(
            "log.runtimeManager.installing",
            component,
            version_name.as_str(),
            total.to_string()
        ));

        // From here the folder's files change: drop the marker first.
        remove_file_if_exists(&marker)?;

        let base = normalize_lexical(&absolute(dir));
        // Symlinks exist only where `link` entries are created (macOS, Linux). There every
        // entry is also checked on disk, so links left by an earlier install cannot redirect
        // a write outside the folder (see `install::parent_physically_within`).
        #[cfg(unix)]
        let base_canon = Some(fs::canonicalize(&base)?);
        #[cfg(not(unix))]
        let base_canon: Option<PathBuf> = None;
        let mut tasks = Vec::new();
        let mut links = Vec::new();
        // Pass 1 (sequential, manifest order): validate names, create directories, collect
        // files and links. Directories must exist before the parallel writes target them.
        // Nothing creates a symlink before pass 2 ends, so the on-disk checks here hold for
        // the parallel writes too.
        for (rel, entry) in &manifest.files {
            if is_unsafe_entry_name(rel) {
                mmcl_log::warn_silent(&tf!("log.runtimeManager.unsafeEntryName", rel.as_str()));
                continue;
            }
            let Some(resolved) = resolve_within(&base, rel) else {
                mmcl_log::warn_silent(&tf!("log.runtimeManager.entryEscapesBase", rel.as_str()));
                continue;
            };
            if let Some(bc) = base_canon.as_deref()
                && !install::parent_physically_within(bc, &resolved)
            {
                mmcl_log::warn_silent(&tf!("log.runtimeManager.entryEscapesBase", rel.as_str()));
                continue;
            }
            match entry {
                RuntimeFile::Directory => fs::create_dir_all(&resolved)?,
                RuntimeFile::File {
                    executable,
                    downloads,
                } => {
                    let raw = downloads.raw.as_ref().ok_or_else(|| {
                        RuntimeError::Json(format!("{rel}: missing downloads.raw"))
                    })?;
                    let sha1 = raw
                        .sha1
                        .clone()
                        .ok_or_else(|| RuntimeError::Json(format!("{rel}: missing sha1")))?;
                    // An earlier install's link at a path that is now a file: hashing it
                    // would read the link's target, and the bytes must land here, not there.
                    if fs::symlink_metadata(&resolved).is_ok_and(|m| m.file_type().is_symlink()) {
                        fs::remove_file(&resolved)?;
                    }
                    tasks.push(FileTask {
                        rel: rel.clone(),
                        path: resolved,
                        url: raw.url.clone(),
                        sha1,
                        executable: *executable,
                    });
                }
                RuntimeFile::Link { target } => links.push((rel.clone(), resolved, target.clone())),
                RuntimeFile::Unknown => {}
            }
        }

        // Pass 2 (parallel): a runtime is hundreds of small, latency-bound files.
        if !tasks.is_empty() {
            let n = tasks.len();
            let pool = n.min(12.max(num_cpus())).max(1);
            let mut stream =
                futures::stream::iter(tasks.into_iter().map(|t| self.run_file_task(t, cancel)))
                    .buffer_unordered(pool);
            let mut done = 0usize;
            while let Some(r) = stream.next().await {
                if let Err(e) = r {
                    if e.is_cancelled() || cancel.is_cancelled() {
                        return Err(RuntimeError::Cancelled);
                    }
                    return Err(RuntimeError::FileFailed(e.to_string()));
                }
                done += 1;
                if progress.is_some() && done.is_multiple_of(PROGRESS_EVERY_FILES) {
                    let pct = 15.0 + 80.0 * done as f64 / total.max(1) as f64;
                    report_progress(
                        progress,
                        label,
                        &tf!(
                            "runtime.status.installingFiles",
                            done.to_string(),
                            total.to_string()
                        ),
                        pct,
                    );
                }
            }
        }
        check_cancel(cancel)?;

        create_links(base_canon.as_deref(), &links);

        fs::write(&marker, &version_name)?;

        if exe.exists() {
            mmcl_log::std(&tf!(
                "log.runtimeManager.installedSuccess",
                component,
                version_name.as_str()
            ));
            return Ok(ResolvedRuntime {
                component: component.to_string(),
                java: exe.to_path_buf(),
                version: version_name,
            });
        }
        mmcl_log::error(&tf!(
            "log.runtimeManager.javaExecNotFound",
            exe.display().to_string()
        ));
        match find_java_executable(dir, &self.host) {
            Some(found) => Ok(ResolvedRuntime {
                component: component.to_string(),
                java: absolute(&found),
                version: version_name,
            }),
            None => Err(RuntimeError::JavaExecMissing),
        }
    }

    async fn run_file_task(&self, t: FileTask, cancel: &CancelToken) -> Result<(), RuntimeError> {
        check_cancel(cancel)?;
        if !hash_ok(&t.path, Digest::Sha1, &t.sha1).await {
            if let Some(parent) = t.path.parent() {
                fs::create_dir_all(parent)?;
            }
            let mut verified = false;
            for attempt in 1..=FILE_HASH_ATTEMPTS {
                self.fetch(&t.url, &t.path, None, cancel).await?;
                if hash_ok(&t.path, Digest::Sha1, &t.sha1).await {
                    verified = true;
                    break;
                }
                let _ = fs::remove_file(&t.path);
                mmcl_log::warn_silent(&tf!(
                    "log.runtimeManager.hashRetry",
                    attempt.to_string(),
                    FILE_HASH_ATTEMPTS.to_string(),
                    t.rel.as_str()
                ));
            }
            if !verified {
                return Err(RuntimeError::FileHashFailed {
                    attempts: FILE_HASH_ATTEMPTS,
                    relative_path: t.rel,
                    url: t.url,
                });
            }
        }
        if t.executable {
            fsutil::set_executable(&t.path)?;
        }
        Ok(())
    }

    /// The Mojang runtime index, cached in memory for [`RUNTIME_INDEX_TTL`]. On expiry it is
    /// downloaded to `runtime/mojang-runtime-index.json`; when that fails the file on disk is
    /// used if there is one.
    async fn mojang_index(&self, cancel: &CancelToken) -> Result<Arc<RuntimeIndex>, RuntimeError> {
        let mut cached = self.index.lock().await;
        if let Some((idx, at)) = cached.as_ref()
            && at.elapsed() < self.index_ttl
        {
            return Ok(idx.clone());
        }
        let root = self.paths.runtime_dir();
        let _ = fs::create_dir_all(&root);
        let file = root.join(MOJANG_RUNTIME_INDEX_FILE_NAME);
        if let Err(e) = self.fetch(RUNTIME_INDEX_URL, &file, None, cancel).await {
            if e.is_cancelled() || !file.exists() {
                return Err(e);
            }
            mmcl_log::warn_silent(&t!("log.runtimeManager.indexUpdateFailedCached"));
        }
        let idx = Arc::new(RuntimeIndex::parse(&fs::read(&file)?)?);
        *cached = Some((idx.clone(), Instant::now()));
        Ok(idx)
    }

    // ------------------------------------------------------------------ update checks

    fn spawn_update_check(&self, component: &str, installed: String, check: UpdateCheck) {
        // One check per component per session (the launch pipeline verifies twice, B-11).
        if !self.update_checked.lock().insert(component.to_string()) {
            return;
        }
        let Some(me) = self.me.upgrade() else {
            return;
        };
        let Ok(handle) = tokio::runtime::Handle::try_current() else {
            return;
        };
        let component = component.to_string();
        let jh = handle.spawn(async move {
            if let Err(e) = me.update_check(&component, &installed, check).await {
                mmcl_log::warn_silent(&tf!(
                    "log.runtimeManager.bgUpdateCheckFailed",
                    e.to_string()
                ));
            }
        });
        let mut bg = self.background.lock();
        bg.retain(|h| !h.is_finished());
        bg.push(jh);
    }

    async fn update_check(
        &self,
        component: &str,
        installed: &str,
        check: UpdateCheck,
    ) -> Result<(), RuntimeError> {
        let cancel = CancelToken::new();
        let dir = self.component_dir(component);
        let latest = match check {
            UpdateCheck::Mojang => {
                let Some(platform) = self.host.mojang_runtime_key() else {
                    return Ok(());
                };
                let index = self.mojang_index(&cancel).await?;
                match index.entry(platform, component) {
                    Ok(e) => e.version.name.clone(),
                    Err(_) => return Ok(()),
                }
            }
            UpdateCheck::Alternative(major) => {
                let Some(source) = read_marker(&dir.join(SOURCE_FILE_NAME))
                    .as_deref()
                    .and_then(ReleaseSource::parse)
                else {
                    return Ok(());
                };
                match self.fetch_alt_release(source, major, &dir, &cancel).await? {
                    Some(r) => r.version,
                    None => return Ok(()),
                }
            }
            UpdateCheck::Legacy => {
                let (os, arch) = liberica::os_arch(&self.host);
                let api_file = dir.join(LEGACY_API_FILE_NAME);
                self.fetch(
                    &liberica::api_url(os, arch),
                    &api_file,
                    Some("application/json"),
                    &cancel,
                )
                .await?;
                match liberica::parse_liberica(&fs::read(&api_file)?, false)? {
                    Some(r) => r.version,
                    None => return Ok(()),
                }
            }
        };
        if latest != installed {
            mmcl_log::std(&tf!(
                "log.runtimeManager.newerAvailable",
                component,
                installed,
                latest.as_str()
            ));
            // Under the component lock, so a verify in progress never sees half a decision.
            // Re-read the marker there: a verify that ran while this check was on the network
            // may already have installed `latest` (or something else), and dropping that fresh
            // marker would force a needless full reinstall on the next launch.
            let lock = self.lock_for(component);
            let _guard = lock.lock().await;
            let marker = dir.join(RUNTIME_VERSION_FILE_NAME);
            if read_marker(&marker).as_deref() == Some(installed) {
                remove_file_if_exists(&marker)?;
            }
        }
        Ok(())
    }

    // ------------------------------------------------------------------ Liberica JRE 8

    async fn verify_legacy(
        &self,
        progress: Option<&dyn RuntimeProgress>,
        cancel: &CancelToken,
    ) -> Result<ResolvedRuntime, RuntimeError> {
        let component = LEGACY_COMPONENT;
        let label = t!("runtime.label.legacy").into_owned();
        report_progress(progress, &label, &t!("runtime.status.checking"), 5.0);
        let dir = self.component_dir(component);
        let _ = fs::create_dir_all(&dir);
        let marker = dir.join(RUNTIME_VERSION_FILE_NAME);

        // Offline fast path (B-10 fixed): marker, folder and exe from the cached API answer.
        if let Some(r) = self.legacy_fast_path(&dir) {
            mmcl_log::std(&tf!(
                "log.runtimeManager.jre8AlreadyInstalled",
                r.version.as_str()
            ));
            report_progress(
                progress,
                &label,
                &t!("runtime.status.alreadyInstalled"),
                100.0,
            );
            self.spawn_update_check(component, r.version.clone(), UpdateCheck::Legacy);
            return Ok(r);
        }
        if self.is_in_use(component)
            && let Some(exe) = find_java_executable(&dir, &self.host)
        {
            mmcl_log::std(&tf!("log.runtimeManager.inUseDeferUpdate", component));
            report_progress(
                progress,
                &label,
                &t!("runtime.status.alreadyInstalled"),
                100.0,
            );
            return Ok(ResolvedRuntime {
                component: component.to_string(),
                java: absolute(&exe),
                version: String::new(),
            });
        }

        match self.install_legacy(&dir, &label, progress, cancel).await {
            Ok(r) => {
                report_progress(progress, &label, &t!("runtime.status.completed"), 100.0);
                Ok(r)
            }
            Err(e) => {
                mmcl_log::error(&tf!("log.runtimeManager.jre8InstallFailed", e.to_string()));
                if e.is_cancelled() || cancel.is_cancelled() {
                    return Err(RuntimeError::Cancelled);
                }
                let existing = if marker.is_file() {
                    find_java_executable(&dir, &self.host)
                } else {
                    None
                };
                let Some(existing) = existing else {
                    return Err(RuntimeError::Jre8InstallFailed { cause: Box::new(e) });
                };
                let existing = absolute(&existing);
                let version = read_marker(&marker).unwrap_or_else(|| UNKNOWN_VERSION.to_string());
                mmcl_log::std(&tf!(
                    "log.runtimeManager.usingExisting",
                    existing.display().to_string()
                ));
                report_progress(progress, &label, &t!("runtime.status.completed"), 100.0);
                Ok(ResolvedRuntime {
                    component: component.to_string(),
                    java: existing,
                    version,
                })
            }
        }
    }

    fn legacy_exe_in(&self, folder: &Path) -> Option<PathBuf> {
        let direct = folder.join(self.host.java_exec_rel_path());
        if direct.exists() {
            return Some(absolute(&direct));
        }
        find_java_executable(folder, &self.host).map(|p| absolute(&p))
    }

    /// The extracted folder and its macOS `.jre` alternative. Both names are built from API
    /// fields (`bundleType`), and the install deletes them recursively, so a name that is not a
    /// plain file name (`../..`, a separator) is refused instead of being joined (Java joined it
    /// unchecked).
    fn legacy_folders(
        &self,
        dir: &Path,
        info: &Release,
    ) -> Result<(PathBuf, PathBuf), RuntimeError> {
        let name = liberica::extracted_folder_name(
            &info.bundle_type,
            info.feature_version,
            info.update_version,
        );
        let alt = format!("{name}.{}", info.bundle_type);
        for n in [&name, &alt] {
            if !liberica::is_plain_file_name(n) {
                return Err(RuntimeError::UnsafeArchiveName { name: n.clone() });
            }
        }
        Ok((dir.join(name), dir.join(alt)))
    }

    fn legacy_fast_path(&self, dir: &Path) -> Option<ResolvedRuntime> {
        let marker = dir.join(RUNTIME_VERSION_FILE_NAME);
        let installed = read_marker(&marker)?;
        let info = liberica::parse_liberica(&fs::read(dir.join(LEGACY_API_FILE_NAME)).ok()?, false)
            .ok()??;
        if info.version != installed {
            return None;
        }
        let (primary, alt) = self.legacy_folders(dir, &info).ok()?;
        let eff = liberica::resolve_effective_folder(&primary, &alt);
        if !eff.exists() {
            return None;
        }
        let exe = self.legacy_exe_in(&eff)?;
        mark_java_binaries_executable(&exe);
        Some(ResolvedRuntime {
            component: LEGACY_COMPONENT.to_string(),
            java: exe,
            version: installed,
        })
    }

    async fn install_legacy(
        &self,
        dir: &Path,
        label: &str,
        progress: Option<&dyn RuntimeProgress>,
        cancel: &CancelToken,
    ) -> Result<ResolvedRuntime, RuntimeError> {
        let component = LEGACY_COMPONENT;
        let (os, arch) = liberica::os_arch(&self.host);
        let api = liberica::api_url(os, arch);
        report_progress(
            progress,
            label,
            &t!("runtime.status.fetchingJre8Info"),
            15.0,
        );
        let api_file = dir.join(LEGACY_API_FILE_NAME);
        let info = match self
            .fetch(&api, &api_file, Some("application/json"), cancel)
            .await
        {
            Ok(()) => liberica::parse_liberica(&fs::read(&api_file)?, false)?,
            Err(e) => {
                if e.is_cancelled() || !api_file.exists() {
                    return Err(e);
                }
                let cached = liberica::parse_liberica(&fs::read(&api_file)?, false)?;
                mmcl_log::warn_silent(&t!("log.runtimeManager.usingCachedJre8Info"));
                cached
            }
        };
        let info = info.ok_or(RuntimeError::NoJre8Info)?;
        let version = info.version.clone();
        let marker = dir.join(RUNTIME_VERSION_FILE_NAME);
        let (primary, alt) = self.legacy_folders(dir, &info)?;
        let eff = liberica::resolve_effective_folder(&primary, &alt);

        if eff.exists()
            && read_marker(&marker).as_deref() == Some(version.as_str())
            && let Some(exe) = self.legacy_exe_in(&eff)
        {
            // Heals installs that landed without execute bits.
            mark_java_binaries_executable(&exe);
            mmcl_log::std(&tf!(
                "log.runtimeManager.jre8AlreadyInstalled",
                version.as_str()
            ));
            report_progress(
                progress,
                label,
                &t!("runtime.status.alreadyInstalled"),
                100.0,
            );
            return Ok(ResolvedRuntime {
                component: component.to_string(),
                java: exe,
                version,
            });
        }

        report_progress(progress, label, &t!("runtime.status.verifyingJre8"), 30.0);
        if !liberica::is_plain_file_name(&info.filename) {
            return Err(RuntimeError::UnsafeArchiveName {
                name: info.filename.clone(),
            });
        }
        let archive = dir.join(&info.filename);
        if !hash_ok(&archive, Digest::Sha1, &info.hash).await {
            report_progress(progress, label, &t!("runtime.status.downloadingJre8"), -1.0);
            self.fetch(&info.download_url, &archive, None, cancel)
                .await?;
            // A corrupt, truncated or substituted archive must never be extracted and run.
            if !hash_ok(&archive, Digest::Sha1, &info.hash).await {
                let _ = fs::remove_file(&archive);
                return Err(RuntimeError::Jre8HashFailed {
                    file: info.filename.clone(),
                });
            }
        }
        check_cancel(cancel)?;

        report_progress(progress, label, &t!("runtime.status.extractingJre8"), 75.0);
        // The old runtime goes now: drop the marker first.
        remove_file_if_exists(&marker)?;
        remove_dir_if_exists(&primary)?;
        remove_dir_if_exists(&alt)?;
        extract(&archive, dir, info.tar_gz).await?;

        let eff = liberica::resolve_effective_folder(&primary, &alt);
        let resolved = match self.legacy_exe_in(&eff) {
            Some(exe) => {
                mark_java_binaries_executable(&exe);
                // Only a real extracted executable earns the marker.
                fs::write(&marker, &version)?;
                ResolvedRuntime {
                    component: component.to_string(),
                    java: exe,
                    version,
                }
            }
            None => {
                mmcl_log::error(&tf!(
                    "log.runtimeManager.systemJavaFallback",
                    eff.display().to_string()
                ));
                ResolvedRuntime {
                    component: component.to_string(),
                    java: PathBuf::from("java"),
                    version: SYSTEM_JAVA_VERSION.to_string(),
                }
            }
        };
        mmcl_log::std(&tf!(
            "log.runtimeManager.jre8InstalledSuccess",
            resolved.version.as_str()
        ));
        Ok(resolved)
    }

    // ------------------------------------------------------------------ Linux ARM64 flow

    async fn verify_alternative(
        &self,
        component: &str,
        major: u32,
        progress: Option<&dyn RuntimeProgress>,
        cancel: &CancelToken,
    ) -> Result<ResolvedRuntime, RuntimeError> {
        let label = tf!("runtime.label.named", component);
        report_progress(progress, &label, &t!("runtime.status.checking"), 5.0);
        let dir = self.component_dir(component);
        let _ = fs::create_dir_all(&dir);
        let marker = dir.join(RUNTIME_VERSION_FILE_NAME);
        let exe = absolute(&dir.join(self.host.java_exec_rel_path()));

        if let Some(r) = self.fast_path(
            component,
            &dir,
            &exe,
            &label,
            progress,
            UpdateCheck::Alternative(major),
        ) {
            return Ok(r);
        }
        if let Some(r) = self.in_use_defer(component, &exe, &label, progress) {
            return Ok(r);
        }
        match self
            .install_alternative(component, major, &dir, &exe, &label, progress, cancel)
            .await
        {
            Ok(r) => {
                report_progress(progress, &label, &t!("runtime.status.completed"), 100.0);
                Ok(r)
            }
            Err(e) => {
                self.fallback_to_existing(component, &marker, &exe, &label, progress, cancel, e)
            }
        }
    }

    async fn fetch_alt_release(
        &self,
        source: ReleaseSource,
        major: u32,
        dir: &Path,
        cancel: &CancelToken,
    ) -> Result<Option<Release>, RuntimeError> {
        let (url, file) = match source {
            ReleaseSource::Liberica => {
                let (os, arch) = liberica::os_arch(&self.host);
                (
                    liberica::latest_api_url(major, os, arch),
                    dir.join(LIBERICA_API_FILE_NAME),
                )
            }
            ReleaseSource::Adoptium => (
                liberica::adoptium_api_url(major, &self.host),
                dir.join(ADOPTIUM_API_FILE_NAME),
            ),
        };
        self.fetch(&url, &file, Some("application/json"), cancel)
            .await?;
        parse_alt(source, &fs::read(&file)?)
    }

    async fn resolve_alt_release(
        &self,
        major: u32,
        dir: &Path,
        cancel: &CancelToken,
    ) -> Result<Release, RuntimeError> {
        for source in [ReleaseSource::Liberica, ReleaseSource::Adoptium] {
            match self.fetch_alt_release(source, major, dir, cancel).await {
                Ok(Some(r)) => return Ok(r),
                Ok(None) => mmcl_log::warn_silent(&tf!(
                    "log.runtimeManager.altSourceFailed",
                    major.to_string(),
                    source.display_name(),
                    RuntimeError::NoAltBuild { major }.to_string()
                )),
                Err(e) if e.is_cancelled() => return Err(RuntimeError::Cancelled),
                Err(e) => mmcl_log::warn_silent(&tf!(
                    "log.runtimeManager.altSourceFailed",
                    major.to_string(),
                    source.display_name(),
                    e.to_string()
                )),
            }
        }
        // Offline: the answers cached by an earlier run.
        for (source, name) in [
            (ReleaseSource::Liberica, LIBERICA_API_FILE_NAME),
            (ReleaseSource::Adoptium, ADOPTIUM_API_FILE_NAME),
        ] {
            if let Ok(bytes) = fs::read(dir.join(name))
                && let Ok(Some(r)) = parse_alt(source, &bytes)
            {
                mmcl_log::warn_silent(&tf!(
                    "log.runtimeManager.usingCachedAltInfo",
                    major.to_string(),
                    source.display_name()
                ));
                return Ok(r);
            }
        }
        Err(RuntimeError::NoAltBuild { major })
    }

    #[allow(clippy::too_many_arguments)]
    async fn install_alternative(
        &self,
        component: &str,
        major: u32,
        dir: &Path,
        exe: &Path,
        label: &str,
        progress: Option<&dyn RuntimeProgress>,
        cancel: &CancelToken,
    ) -> Result<ResolvedRuntime, RuntimeError> {
        check_cancel(cancel)?;
        mmcl_log::std(&tf!(
            "log.runtimeManager.altSource",
            component,
            major.to_string()
        ));
        report_progress(
            progress,
            label,
            &tf!("runtime.status.fetchingAltInfo", major.to_string()),
            10.0,
        );
        let release = self.resolve_alt_release(major, dir, cancel).await?;
        let marker = dir.join(RUNTIME_VERSION_FILE_NAME);
        if read_marker(&marker).as_deref() == Some(release.version.as_str()) && exe.exists() {
            mmcl_log::std(&tf!(
                "log.runtimeManager.alreadyInstalled",
                component,
                release.version.as_str()
            ));
            report_progress(
                progress,
                label,
                &t!("runtime.status.alreadyInstalled"),
                100.0,
            );
            return Ok(ResolvedRuntime {
                component: component.to_string(),
                java: exe.to_path_buf(),
                version: release.version,
            });
        }
        if !liberica::is_plain_file_name(&release.filename) {
            return Err(RuntimeError::UnsafeArchiveName {
                name: release.filename.clone(),
            });
        }
        let archive = dir.join(&release.filename);
        if !hash_ok(&archive, release.digest, &release.hash).await {
            report_progress(
                progress,
                label,
                &tf!("runtime.status.downloadingAlt", major.to_string()),
                -1.0,
            );
            self.fetch(&release.download_url, &archive, None, cancel)
                .await?;
            if !hash_ok(&archive, release.digest, &release.hash).await {
                let _ = fs::remove_file(&archive);
                return Err(RuntimeError::ArchiveHashFailed {
                    algorithm: release.digest.name().to_string(),
                    file: release.filename.clone(),
                });
            }
        }
        check_cancel(cancel)?;

        report_progress(
            progress,
            label,
            &tf!("runtime.status.extractingAlt", major.to_string()),
            75.0,
        );
        remove_file_if_exists(&marker)?;
        clear_payload(dir, &release.filename)?;
        let staging = dir.join(STAGING_DIR_NAME);
        remove_dir_if_exists(&staging)?;
        fs::create_dir_all(&staging)?;
        extract(&archive, &staging, release.tar_gz).await?;
        let root = staging_root(&staging, &self.host).ok_or(RuntimeError::JavaExecMissing)?;
        for entry in fs::read_dir(&root)? {
            let entry = entry?;
            let to = dir.join(entry.file_name());
            if to == staging {
                continue;
            }
            fs::rename(entry.path(), to)?;
        }
        remove_dir_if_exists(&staging)?;

        let java = if exe.exists() {
            exe.to_path_buf()
        } else {
            find_java_executable(dir, &self.host)
                .map(|p| absolute(&p))
                .ok_or(RuntimeError::JavaExecMissing)?
        };
        mark_java_binaries_executable(&java);
        fs::write(dir.join(SOURCE_FILE_NAME), release.source.as_str())?;
        fs::write(&marker, &release.version)?;
        mmcl_log::std(&tf!(
            "log.runtimeManager.altInstalledSuccess",
            component,
            release.version.as_str(),
            release.source.display_name()
        ));
        Ok(ResolvedRuntime {
            component: component.to_string(),
            java,
            version: release.version,
        })
    }

    // ------------------------------------------------------------------ helpers

    async fn fetch(
        &self,
        url: &str,
        dest: &Path,
        accept: Option<&str>,
        cancel: &CancelToken,
    ) -> Result<(), RuntimeError> {
        check_cancel(cancel)?;
        match self.fetcher.fetch(url, dest, accept, cancel).await {
            Ok(()) => Ok(()),
            Err(e) if e.is_cancelled() => Err(RuntimeError::Cancelled),
            Err(e) => Err(RuntimeError::Download(e)),
        }
    }
}

struct FileTask {
    rel: String,
    path: PathBuf,
    url: String,
    sha1: String,
    executable: bool,
}

fn parse_alt(source: ReleaseSource, bytes: &[u8]) -> Result<Option<Release>, RuntimeError> {
    match source {
        ReleaseSource::Liberica => liberica::parse_liberica(bytes, true),
        ReleaseSource::Adoptium => liberica::parse_adoptium(bytes),
    }
}

fn check_cancel(cancel: &CancelToken) -> Result<(), RuntimeError> {
    if cancel.is_cancelled() {
        Err(RuntimeError::Cancelled)
    } else {
        Ok(())
    }
}

fn num_cpus() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
}

fn absolute(p: &Path) -> PathBuf {
    std::path::absolute(p).unwrap_or_else(|_| p.to_path_buf())
}

fn remove_file_if_exists(p: &Path) -> io::Result<()> {
    match fs::remove_file(p) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

fn remove_dir_if_exists(p: &Path) -> io::Result<()> {
    if p.is_dir() {
        fs::remove_dir_all(p)
    } else {
        Ok(())
    }
}

async fn hash_ok(path: &Path, digest: Digest, expected: &str) -> bool {
    let p = path.to_path_buf();
    let h = expected.to_string();
    tokio::task::spawn_blocking(move || match digest {
        Digest::Sha1 => mmcl_util::hash::verify_sha1(&p, &h),
        Digest::Sha256 => mmcl_util::hash::verify_sha256(&p, &h),
    })
    .await
    .unwrap_or(false)
}

async fn extract(archive: &Path, target: &Path, tar_gz: bool) -> Result<(), RuntimeError> {
    let a = archive.to_path_buf();
    let t = target.to_path_buf();
    tokio::task::spawn_blocking(move || {
        if tar_gz {
            mmcl_archive::extract_tar_gz(&a, &t)
        } else {
            mmcl_archive::extract_zip(&a, &t)
        }
    })
    .await
    .map_err(|e| RuntimeError::Io(io::Error::other(e.to_string())))??;
    Ok(())
}

/// Removes an earlier install's files from a non-Mojang runtime folder, keeping the current
/// archive and the cached API answers.
fn clear_payload(dir: &Path, keep_archive: &str) -> io::Result<()> {
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name == keep_archive || name == LIBERICA_API_FILE_NAME || name == ADOPTIUM_API_FILE_NAME
        {
            continue;
        }
        let p = entry.path();
        if fs::symlink_metadata(&p)?.is_dir() {
            fs::remove_dir_all(&p)?;
        } else {
            fs::remove_file(&p)?;
        }
    }
    Ok(())
}

/// The JRE home inside an extracted archive: the staging folder itself when it has `bin/`, else
/// the first child folder (by name) that has one, else the folder two levels above the first
/// `java` found.
fn staging_root(staging: &Path, host: &HostInfo) -> Option<PathBuf> {
    if staging.join("bin").is_dir() {
        return Some(staging.to_path_buf());
    }
    let mut children: Vec<_> = fs::read_dir(staging)
        .ok()?
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| p.is_dir())
        .collect();
    children.sort();
    if let Some(c) = children.iter().find(|c| c.join("bin").is_dir()) {
        return Some(c.clone());
    }
    let java = find_java_executable(staging, host)?;
    // A `java` directly in the staging folder would put "two levels up" at the runtime
    // folder itself; only a home inside the staging folder can be moved into place.
    java.parent()?
        .parent()
        .filter(|root| root.starts_with(staging))
        .map(Path::to_path_buf)
}

/// Creates the manifest's `link` entries as symlinks on macOS and Linux (Q-9). Each target must
/// resolve inside the runtime folder **on disk**: the link's parent is canonicalised (so links
/// made earlier, in this install or a previous one, are followed) and the target is resolved
/// from there. Checking only the lexical path would let a chain of individually contained links
/// point outside. Windows keeps Java's behaviour and skips them (symlinks need elevation there),
/// and passes no canonical base.
fn create_links(base_canon: Option<&Path>, links: &[(String, PathBuf, String)]) {
    #[cfg(unix)]
    for (rel, path, target) in links {
        let Some(base) = base_canon else {
            return;
        };
        let escapes =
            || mmcl_log::warn_silent(&tf!("log.runtimeManager.linkEscapesBase", rel.as_str()));
        let (Some(parent), Some(name)) = (path.parent(), path.file_name()) else {
            escapes();
            continue;
        };
        if !install::parent_physically_within(base, path) {
            escapes();
            continue;
        }
        if let Err(e) = fs::create_dir_all(parent) {
            mmcl_log::warn_silent(&tf!(
                "log.runtimeManager.linkFailed",
                rel.as_str(),
                e.to_string()
            ));
            continue;
        }
        let Ok(parent_canon) = fs::canonicalize(parent) else {
            escapes();
            continue;
        };
        let phys = parent_canon.join(name);
        if install::resolve_link_target(base, &phys, target).is_none() {
            escapes();
            continue;
        }
        if let Ok(existing) = fs::read_link(&phys) {
            if existing == Path::new(target) {
                continue;
            }
            let _ = fs::remove_file(&phys);
        } else if phys.is_file() {
            let _ = fs::remove_file(&phys);
        }
        if let Err(e) = std::os::unix::fs::symlink(target, &phys) {
            mmcl_log::warn_silent(&tf!(
                "log.runtimeManager.linkFailed",
                rel.as_str(),
                e.to_string()
            ));
        }
    }
    #[cfg(not(unix))]
    {
        let _ = (base_canon, links);
    }
}

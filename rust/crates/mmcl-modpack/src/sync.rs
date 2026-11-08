// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! File sync (Java `GameModPackFileSync`): mods, configs, resource packs, shader packs and
//! initial files.
//!
//! Guards, each from a past incident:
//!
//! * a failed or stub pack never syncs: its unknown mod list would read as "no mods" and the
//!   sweep would empty `mods/` (a headless server once booted modless that way);
//! * the floating-mod sweep never runs without an authoritative (non-null) mod list;
//! * single-mod packs download (an old `size() > 1` check skipped them);
//! * every required mod is asserted present and non-empty after the sync, so the game never
//!   starts without them;
//! * the sweep compares normalised paths (case-insensitively on Windows and macOS), so a `local`
//!   whose case differs from the file is not deleted and re-downloaded every launch;
//! * a managed mod the user disabled (`<file>.disabled`, from the content browser or the
//!   conflict prompt) is kept disabled: the sweep keeps it and the sync does not bring the
//!   enabled copy back (Java deleted it and re-enabled the mod at the next launch).

use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use mmcl_i18n::{t, tf};
use mmcl_manifest::{ManifestFile, Side};
use mmcl_net::{AwaitError, CancelToken, DownloadPool, DownloadTracker, await_all};
use mmcl_progress::{DownloadMeter, ProgressSink};

use crate::context::PrepareContext;
use crate::error::{ModpackError, Result};
use crate::http::PackHttp;
use crate::managed::{ManagedFile, abs_norm};
use crate::meter::tracker_of;
use crate::pack::{GameModPack, PackKind};

/// The mod download bound.
pub const MODS_TIMEOUT: Duration = Duration::from_secs(30 * 60);
/// Suffix the content browser and the conflict prompt give a disabled jar.
pub const DISABLED_SUFFIX: &str = ".disabled";

/// What a sync runs with.
#[derive(Clone)]
pub struct SyncEnv {
    pub side: Side,
    pub http: Arc<dyn PackHttp>,
    pub pool: DownloadPool,
    pub sink: Arc<dyn ProgressSink>,
    /// The run's context; `None` uses the pack's current one.
    pub ctx: Option<Arc<PrepareContext>>,
    pub mods_timeout: Duration,
    pub cancel: Option<CancelToken>,
}

impl SyncEnv {
    /// A sync on the shared download pool, using the pack's prepare context.
    pub fn new(side: Side, http: Arc<dyn PackHttp>, sink: Arc<dyn ProgressSink>) -> Self {
        SyncEnv {
            side,
            http,
            pool: DownloadPool::global().clone(),
            sink,
            ctx: None,
            mods_timeout: MODS_TIMEOUT,
            cancel: None,
        }
    }
}

/// A path key for comparisons: absolute, normalised, case-folded where the file system is.
fn path_key(p: &Path) -> String {
    let s = abs_norm(p).to_string_lossy().into_owned();
    if cfg!(any(windows, target_os = "macos")) {
        s.to_lowercase()
    } else {
        s
    }
}

fn disabled_path(p: &Path) -> PathBuf {
    let mut s = p.as_os_str().to_owned();
    s.push(DISABLED_SUFFIX);
    PathBuf::from(s)
}

fn is_nonempty_file(p: &Path) -> bool {
    fs::metadata(p).is_ok_and(|m| m.is_file() && m.len() > 0)
}

/// One sync of one pack.
pub struct FileSync {
    pack: Arc<GameModPack>,
    env: SyncEnv,
    meter: Option<Arc<dyn DownloadMeter>>,
    tracker: Option<Arc<DownloadTracker>>,
}

impl FileSync {
    /// Prepares a sync; sets the sink's file count to every file it will process.
    pub fn new(pack: Arc<GameModPack>, env: SyncEnv) -> Self {
        let meter = env.sink.download_tracker();
        let tracker = tracker_of(env.sink.as_ref());
        if let Some(m) = &meter {
            let mut total = pack.manifest().mods().len()
                + pack.manifest().configs().len()
                + pack.manifest().initial_files().len();
            if env.side != Side::Server {
                total +=
                    pack.manifest().resource_packs().len() + pack.manifest().shader_packs().len();
            }
            m.set_total_file_count(total as u64);
        }
        FileSync {
            pack,
            env,
            meter,
            tracker,
        }
    }

    fn ctx(&self) -> Arc<PrepareContext> {
        self.env
            .ctx
            .clone()
            .unwrap_or_else(|| self.pack.prepare_context())
    }

    fn managed(&self, spec: &ManifestFile, prefix: PathBuf) -> ManagedFile {
        ManagedFile::new(spec.clone(), prefix, self.pack.paths().root())
            .with_tracker(self.tracker.clone())
            .with_session(self.pack.session_verified().clone())
    }

    fn complete_file(&self) {
        if let Some(m) = &self.meter {
            m.complete_file();
        }
    }

    fn guard_loaded(&self) -> Result<PathBuf> {
        match self.pack.kind() {
            PackKind::Failed { .. } => Err(ModpackError::ManifestFetchFailed(self.pack.name())),
            PackKind::Stub | PackKind::Placeholder => {
                Err(ModpackError::ManifestStub(self.pack.name()))
            }
            _ => self.pack.checked_pack_root(),
        }
    }

    /// Mods required on this side whose disabled copy exists and whose enabled copy does not
    /// (kept disabled, not synced). When both copies exist the enabled one is on the classpath,
    /// so it is verified like any other mod.
    fn user_disabled(&self, mods_dir: &Path) -> HashSet<String> {
        self.pack
            .manifest()
            .mods()
            .iter()
            .filter(|m| m.is_required_for(self.env.side))
            .map(|m| self.managed(m, mods_dir.to_path_buf()))
            .filter(ManagedFile::is_contained)
            .map(|f| f.full_path())
            .filter(|p| disabled_path(p).is_file() && fs::symlink_metadata(p).is_err())
            .map(|p| path_key(&p))
            .collect()
    }

    /// Deletes files in `mods/` that are not mods of this pack for this side. Never runs
    /// without an authoritative mod list. Directories are left alone.
    pub fn clear_floating_mods(&self) -> Result<()> {
        let root = self.guard_loaded()?;
        let Some(mods) = self.pack.manifest().pack_mods.as_ref() else {
            mmcl_log::warn_silent(&t!("log.gameModPackFileSync.floatingCleanupSkipped"));
            return Ok(());
        };
        let mods_dir = root.join("mods");
        let valid: HashSet<String> = mods
            .iter()
            .filter(|m| m.is_required_for(self.env.side))
            .map(|m| self.managed(m, mods_dir.clone()))
            .filter(ManagedFile::is_contained)
            .map(|f| path_key(&f.full_path()))
            .collect();
        let Ok(entries) = fs::read_dir(&mods_dir) else {
            return Ok(());
        };
        for e in entries.flatten() {
            let p = e.path();
            if !e.file_type().is_ok_and(|t| t.is_file()) {
                continue;
            }
            let key = path_key(&p);
            if valid.contains(&key) {
                continue;
            }
            // A disabled copy of a managed mod stays.
            if let Some(stem) = key.strip_suffix(DISABLED_SUFFIX)
                && valid.contains(stem)
            {
                continue;
            }
            if fs::remove_file(&p).is_err() {
                mmcl_log::error_silent(&t!("log.gameModPackFileSync.sanitizeDeleteFailed"));
            }
        }
        Ok(())
    }

    /// Syncs the mods: sweep, parallel download on the shared pool, existence assertion.
    pub async fn fetch_latest_mods(&self) -> Result<()> {
        let sink = self.env.sink.clone();
        if self.pack.manifest().pack_imported_skip_sync {
            sink.submit_progress(&t!("fileSync.progress.importedSkip"), 100.0);
            return Ok(());
        }
        let root = self.guard_loaded()?;
        self.clear_floating_mods()?;
        sink.submit_progress(&t!("fileSync.progress.floatingRemoved"), 30.0);
        let Some(mods) = self.pack.manifest().pack_mods.clone() else {
            sink.submit_progress(&t!("fileSync.progress.noMods"), 100.0);
            return Ok(());
        };
        let mods_dir = root.join("mods");
        let disabled = self.user_disabled(&mods_dir);
        if !mods.is_empty() {
            let total = mods.len();
            let done = Arc::new(AtomicUsize::new(0));
            let ctx = self.ctx();
            let mut handles = Vec::with_capacity(total);
            for spec in &mods {
                let file = self.managed(spec, mods_dir.clone());
                let skip = disabled.contains(&path_key(&file.full_path()));
                let (http, ctx, sink, done, meter) = (
                    self.env.http.clone(),
                    ctx.clone(),
                    sink.clone(),
                    done.clone(),
                    self.meter.clone(),
                );
                let side = self.env.side;
                handles.push(self.env.pool.submit(move |_token| async move {
                    if !skip {
                        file.update_for(side, &ctx, http.as_ref()).await?;
                    }
                    let n = done.fetch_add(1, Ordering::SeqCst) + 1;
                    sink.submit_progress(
                        &tf!(
                            "fileSync.progress.downloadingMods",
                            n.to_string(),
                            total.to_string()
                        ),
                        70.0 / total as f64,
                    );
                    if let Some(m) = meter {
                        m.complete_file();
                    }
                    Ok::<(), ModpackError>(())
                }));
            }
            await_all(handles, self.env.mods_timeout, self.env.cancel.as_ref())
                .await
                .map_err(|e| match e {
                    AwaitError::Timeout => ModpackError::ModsTimedOut,
                    AwaitError::Cancelled => ModpackError::ModsInterrupted,
                    AwaitError::Failed(err) => ModpackError::ModsFailed(err.to_string()),
                    AwaitError::Panicked(msg) => ModpackError::ModsFailed(msg),
                })?;
        }
        // Post-sync existence assertion.
        let required: Vec<ManagedFile> = mods
            .iter()
            .filter(|m| m.is_required_for(self.env.side))
            .map(|m| self.managed(m, mods_dir.clone()))
            .collect();
        let missing: Vec<String> = required
            .iter()
            .filter(|f| {
                let p = f.full_path();
                !is_nonempty_file(&p) && !disabled.contains(&path_key(&p))
            })
            .map(ManagedFile::file_name)
            .collect();
        if !missing.is_empty() {
            return Err(ModpackError::ModsMissing {
                pack: self.pack.name(),
                missing: missing.len(),
                required: required.len(),
                names: missing.join(", "),
            });
        }
        mmcl_log::debug(&tf!(
            "log.gameModPackFileSync.modCheckPassed",
            self.pack.name(),
            required.len().to_string()
        ));
        Ok(())
    }

    async fn sync_list(
        &self,
        list: Option<&Vec<ManifestFile>>,
        prefix: PathBuf,
        none_key: &str,
        side_aware: bool,
    ) -> Result<()> {
        let sink = &self.env.sink;
        let Some(list) = list else {
            sink.submit_progress(&t!(none_key), 100.0);
            return Ok(());
        };
        let ctx = self.ctx();
        let len = list.len().max(1) as f64;
        for spec in list {
            let f = self.managed(spec, prefix.clone());
            let name = f.file_name();
            sink.set_curr_text(&tf!("fileSync.progress.downloading", &name));
            if side_aware {
                f.update_for(self.env.side, &ctx, self.env.http.as_ref())
                    .await?;
            } else {
                f.update(&ctx, self.env.http.as_ref()).await?;
            }
            sink.submit_progress(&tf!("fileSync.progress.verified", &name), 100.0 / len);
            self.complete_file();
        }
        Ok(())
    }

    /// Syncs `config/`.
    pub async fn fetch_latest_configs(&self) -> Result<()> {
        let root = self.guard_loaded()?;
        self.sync_list(
            self.pack.manifest().pack_configs.as_ref(),
            root.join("config"),
            "fileSync.progress.noConfigs",
            true,
        )
        .await
    }

    /// Syncs `resourcepacks/` (client only).
    pub async fn fetch_latest_resource_packs(&self) -> Result<()> {
        let root = self.guard_loaded()?;
        if self.env.side == Side::Server {
            return Ok(());
        }
        self.sync_list(
            self.pack.manifest().pack_resource_packs.as_ref(),
            root.join("resourcepacks"),
            "fileSync.progress.noResourcePacks",
            false,
        )
        .await
    }

    /// Syncs `shaderpacks/` (client only).
    pub async fn fetch_latest_shader_packs(&self) -> Result<()> {
        let root = self.guard_loaded()?;
        if self.env.side == Side::Server {
            return Ok(());
        }
        self.sync_list(
            self.pack.manifest().pack_shader_packs.as_ref(),
            root.join("shaderpacks"),
            "fileSync.progress.noShaderPacks",
            false,
        )
        .await
    }

    /// Syncs the initial files at the pack root.
    pub async fn fetch_latest_initial_files(&self) -> Result<()> {
        let root = self.guard_loaded()?;
        self.sync_list(
            self.pack.manifest().pack_initial_files.as_ref(),
            root,
            "fileSync.progress.noInitialFiles",
            true,
        )
        .await
    }

    /// All five, in the launcher's order: mods, configs, resource packs, shader packs, initial
    /// files.
    pub async fn sync_all(&self) -> Result<()> {
        self.fetch_latest_mods().await?;
        self.fetch_latest_configs().await?;
        self.fetch_latest_resource_packs().await?;
        self.fetch_latest_shader_packs().await?;
        self.fetch_latest_initial_files().await
    }
}

#[cfg(test)]
mod tests;

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Settings-screen operations that are not plain settings: restore a backup, reset the
//! launcher, system memory, hardware-tuned JVM args, the malware scan and the launcher update
//! check.

use std::path::{Path, PathBuf};

use parking_lot::Mutex;

use mmcl_net::CancelToken;

use super::Engine;
use crate::error::{EngineError, EngineResult};
use crate::event::{EngineEvent, OperationId, OperationProgress};
use crate::model::{PackId, ScanSummary, SystemMemory, UpdateInfo};

const GIB: f64 = 1024.0 * 1024.0 * 1024.0;

/// Where `root` is renamed before a reset deletes it: `<name>.reset-<stamp>` beside it.
pub(crate) fn reset_aside_path(root: &Path, now_ms: i64) -> PathBuf {
    let name = root
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| "launcher".to_string());
    root.with_file_name(format!("{name}.reset-{now_ms}"))
}

/// Renames `root` aside (atomic: a file in use fails the rename with nothing touched). `None`
/// when there is no root.
pub(crate) fn move_aside_for_reset(root: &Path, now_ms: i64) -> std::io::Result<Option<PathBuf>> {
    if !root.exists() {
        return Ok(None);
    }
    let aside = reset_aside_path(root, now_ms);
    std::fs::rename(root, &aside)?;
    Ok(Some(aside))
}

/// The only folders a backup holds (`mmcl_modpack::backup::backup_folder`), so the only ones a
/// restore may replace. Anything else in a zip found in the backups folder (`mods`, `.backups`,
/// loose files) is ignored rather than swapped over the pack.
pub(crate) const RESTORABLE_DIRS: [&str; 2] = ["config", "saves"];

/// The restorable top-level folders a backup zip holds (`config/...`, `saves/...`).
fn backup_top_dirs(zip_path: &Path) -> std::io::Result<Vec<String>> {
    let file = std::fs::File::open(zip_path)?;
    let mut zip = zip::ZipArchive::new(file).map_err(std::io::Error::other)?;
    let mut tops: Vec<String> = Vec::new();
    for i in 0..zip.len() {
        let entry = zip.by_index(i).map_err(std::io::Error::other)?;
        let name = entry.name().replace('\\', "/");
        // Only entries inside a folder count: a loose `config` file is not the folder.
        if let Some((top, _)) = name.split_once('/')
            && RESTORABLE_DIRS.contains(&top)
            && !tops.iter().any(|t| t == top)
        {
            tops.push(top.to_string());
        }
    }
    Ok(tops)
}

/// Restores `zip_path` over `root`: extracts into a staging folder inside the pack (contained
/// extraction), then swaps each restored top-level folder in, keeping the old one until the
/// swap succeeded.
pub(crate) fn restore_into(root: &Path, zip_path: &Path, stamp: i64) -> std::io::Result<()> {
    let staging = root.join(format!(".restore-{stamp}"));
    let _ = std::fs::remove_dir_all(&staging);
    std::fs::create_dir_all(&staging)?;
    let result = (|| -> std::io::Result<()> {
        mmcl_archive::extract_zip(zip_path, &staging).map_err(std::io::Error::other)?;
        for top in backup_top_dirs(zip_path)? {
            let src = staging.join(&top);
            if !src.is_dir() {
                continue;
            }
            let dst = root.join(&top);
            let old = root.join(format!(".restore-old-{stamp}-{top}"));
            let had_old = dst.exists();
            if had_old {
                std::fs::rename(&dst, &old)?;
            }
            if let Err(e) = std::fs::rename(&src, &dst) {
                if had_old {
                    let _ = std::fs::rename(&old, &dst);
                }
                return Err(e);
            }
            if had_old {
                let _ = std::fs::remove_dir_all(&old).or_else(|_| std::fs::remove_file(&old));
            }
        }
        Ok(())
    })();
    let _ = std::fs::remove_dir_all(&staging);
    result
}

impl Engine {
    /// [`Engine::restore_backup`].
    pub(super) async fn restore_backup_impl(&self, id: &PackId, backup: &Path) -> EngineResult<()> {
        let pack = self.require_installed(id)?;
        let _busy = super::launch::BusyGuard::take(self, id, &pack.name())?;
        let root = pack.checked_pack_root()?;
        let dir = root.join(mmcl_modpack::backup::BACKUPS_DIR);
        let backup = std::path::absolute(backup)?;
        let inside = backup
            .parent()
            .is_some_and(|p| std::path::absolute(&dir).is_ok_and(|d| d == p));
        if !inside || !backup.is_file() {
            return Err(EngineError::Other(mmcl_i18n::tf!(
                "engine.error.backupNotFound",
                backup.display().to_string()
            )));
        }
        let stamp = super::now_ms();
        tokio::task::spawn_blocking(move || restore_into(&root, &backup, stamp))
            .await
            .map_err(|e| EngineError::Other(e.to_string()))??;
        mmcl_log::std(&mmcl_i18n::tf!("log.engine.backupRestored", pack.name()));
        self.emit(EngineEvent::PackChanged(id.clone()));
        Ok(())
    }

    /// [`Engine::reset_launcher`]: shuts the engine down, renames the data folder aside and
    /// deletes it, then asks the front end to start the launcher again. The front end must
    /// exit (and relaunch the process) rather than restart in-process, or the in-memory config
    /// and accounts would be written back.
    pub(super) async fn reset_launcher_impl(&self) -> EngineResult<()> {
        if self.has_active_sessions() {
            return Err(EngineError::Other(
                mmcl_i18n::t!("dialog.settings.resetLauncher.gamesRunning").into_owned(),
            ));
        }
        self.shutdown().await;
        mmcl_log::flush();
        mmcl_log::shutdown();
        let root = mmcl_core::paths::normalize_lexical(&std::path::absolute(self.paths().root())?);
        let stamp = super::now_ms();
        let aside = match tokio::task::spawn_blocking(move || move_aside_for_reset(&root, stamp))
            .await
            .map_err(|e| EngineError::Other(e.to_string()))?
        {
            Ok(a) => a,
            Err(e) => {
                return Err(EngineError::Other(mmcl_i18n::tf!(
                    "settings.resetLauncher.failed",
                    e.to_string()
                )));
            }
        };
        if let Some(aside) = aside {
            let shown = aside.display().to_string();
            let res = tokio::task::spawn_blocking(move || std::fs::remove_dir_all(&aside)).await;
            if !matches!(res, Ok(Ok(()))) {
                mmcl_log::warn_silent(&mmcl_i18n::tf!("log.launcherCore.resetLeftover", shown));
            }
        }
        self.emit(EngineEvent::RestartRequested);
        Ok(())
    }

    /// [`Engine::system_memory`].
    pub(super) async fn system_memory_impl(&self) -> EngineResult<SystemMemory> {
        tokio::task::spawn_blocking(|| {
            let mut sys = sysinfo::System::new();
            sys.refresh_memory();
            let total = sys.total_memory();
            let total = if total == 0 {
                mmcl_util::jvm_args::total_ram_bytes()
            } else {
                total
            };
            SystemMemory {
                total_gb: total as f64 / GIB,
                used_gb: sys.used_memory().min(total) as f64 / GIB,
            }
        })
        .await
        .map_err(|e| EngineError::Other(e.to_string()))
    }

    /// [`Engine::hardware_tuned_jvm_args`].
    pub(super) fn hardware_tuned_jvm_args_impl(&self, max_ram_gb: f64) -> EngineResult<String> {
        if !max_ram_gb.is_finite() || max_ram_gb <= 0.0 {
            return Err(EngineError::InvalidSetting {
                key: mmcl_config::keys::MAX_RAM_KEY,
                reason: max_ram_gb.to_string(),
            });
        }
        let gb = max_ram_gb.round().clamp(1.0, f64::from(i32::MAX)) as i32;
        Ok(mmcl_util::jvm_args::generate(gb))
    }

    /// [`Engine::check_launcher_update`].
    pub(super) async fn check_launcher_update_impl(&self) -> EngineResult<Option<UpdateInfo>> {
        let installed = self.build_info().version;
        match mmcl_net::update::check_for_update(self.paths(), installed).await {
            Ok(r) => Ok(r.map(|r| UpdateInfo {
                version: r.version,
                url: r.url,
                notes: None,
            })),
            Err(_) if mmcl_net::is_offline() => Err(EngineError::Offline),
            Err(e) => Err(EngineError::Other(e.to_string())),
        }
    }

    /// [`Engine::scan_folder`]: Nekodetector stage 1 + 2, then the supplemental scanner.
    pub(super) async fn scan_folder_impl(
        &self,
        folder: &Path,
        op: OperationId,
    ) -> EngineResult<ScanSummary> {
        let cancel = CancelToken::new();
        {
            let mut slot = self.inner.scan_cancel.lock();
            if let Some(c) = slot.as_ref()
                && !c.is_cancelled()
            {
                self.emit(EngineEvent::OperationFinished(op));
                return Err(EngineError::Other(
                    mmcl_i18n::t!("engine.error.scanBusy").into_owned(),
                ));
            }
            *slot = Some(cancel.clone());
        }
        let tx = self.inner.events.clone();
        let folder = folder.to_path_buf();
        let label = mmcl_i18n::t!("engine.progress.scan").into_owned();
        let c2 = cancel.clone();
        let res = tokio::task::spawn_blocking(move || {
            let threads = (mmcl_util::jvm_args::available_cores() / 2).max(1);
            let fraction = Mutex::new(-1.0f64);
            let send = |text: String, f: f64| {
                let _ = tx.send(EngineEvent::Progress(OperationProgress {
                    operation: op,
                    label: label.clone(),
                    text,
                    fraction: f,
                }));
            };
            let last_line = Mutex::new(String::new());
            let log = |line: &str| {
                let line = mmcl_scan::strip_ansi(line);
                mmcl_log::std(&line);
                *last_line.lock() = line.clone();
                send(line, *fraction.lock());
            };
            let progress = |done: f64, total: f64| {
                let f = if total > 0.0 {
                    (done / total).clamp(0.0, 1.0)
                } else {
                    -1.0
                };
                *fraction.lock() = f;
                send(last_line.lock().clone(), f);
            };
            let empty: [&str; 0] = [];
            let neko =
                mmcl_scan::neko::run(threads, &folder, true, &empty, &log, Some(&progress), &c2);
            let (stage1, stage2, cancelled) = match neko {
                Ok(r) => (r.stage1.len(), r.stage2.len(), false),
                Err(mmcl_scan::NekoError::Cancelled) => (0, 0, true),
                Err(e) => return Err(EngineError::Other(e.to_string())),
            };
            let (mut high, mut medium) = (0usize, 0usize);
            if !cancelled {
                match mmcl_scan::supplemental::scan_folder(&folder, &empty, threads, &c2) {
                    Ok(findings) => {
                        for f in &findings {
                            match f.severity {
                                mmcl_scan::Severity::High => high += 1,
                                mmcl_scan::Severity::Medium => medium += 1,
                            }
                            log(&mmcl_i18n::tf!("settings.scan.supplemental", f.to_string()));
                        }
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
                    Err(e) => log(&mmcl_i18n::tf!(
                        "settings.scan.supplementalIoError",
                        e.to_string()
                    )),
                }
            }
            let cancelled = cancelled || c2.is_cancelled();
            let total = stage1 + stage2 + high + medium;
            let summary_line = if total > 0 {
                mmcl_i18n::tf!(
                    "settings.scan.infectionsFound",
                    stage1.to_string(),
                    stage2.to_string(),
                    high.to_string(),
                    medium.to_string()
                )
            } else if cancelled {
                mmcl_i18n::t!("settings.scan.canceled").into_owned()
            } else {
                mmcl_i18n::t!("settings.scan.noInfections").into_owned()
            };
            mmcl_log::std(&summary_line);
            send(summary_line, 1.0);
            Ok(ScanSummary {
                stage1,
                stage2,
                high,
                medium,
                cancelled,
            })
        })
        .await
        .map_err(|e| EngineError::Other(e.to_string()));
        self.inner.scan_cancel.lock().take();
        self.emit(EngineEvent::OperationFinished(op));
        res?
    }

    /// [`Engine::cancel_scan`].
    pub(super) fn cancel_scan_impl(&self) {
        if let Some(c) = self.inner.scan_cancel.lock().as_ref() {
            c.cancel();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reset_moves_the_root_aside() {
        let d = tempfile::tempdir().unwrap();
        let root = d.path().join(".MicaMinecraftLauncherRS");
        std::fs::create_dir_all(root.join("config")).unwrap();
        let aside = move_aside_for_reset(&root, 42).unwrap().unwrap();
        assert!(!root.exists());
        assert!(aside.ends_with(".MicaMinecraftLauncherRS.reset-42"));
        assert!(aside.join("config").is_dir());
        assert!(move_aside_for_reset(&root, 43).unwrap().is_none());
    }

    #[test]
    fn a_backup_restores_over_the_pack_folder() {
        let d = tempfile::tempdir().unwrap();
        let root = d.path().join("pack");
        std::fs::create_dir_all(root.join("config")).unwrap();
        std::fs::write(root.join("config/a.cfg"), "old").unwrap();
        std::fs::write(root.join("config/extra.cfg"), "x").unwrap();
        std::fs::create_dir_all(root.join("mods")).unwrap();
        std::fs::write(root.join("mods/m.jar"), "jar").unwrap();
        let zip_path = d.path().join("b.zip");
        {
            let f = std::fs::File::create(&zip_path).unwrap();
            let mut z = zip::ZipWriter::new(f);
            let o = zip::write::SimpleFileOptions::default();
            z.add_directory("config/", o).unwrap();
            z.start_file("config/a.cfg", o).unwrap();
            std::io::Write::write_all(&mut z, b"new").unwrap();
            z.finish().unwrap();
        }
        restore_into(&root, &zip_path, 7).unwrap();
        assert_eq!(
            std::fs::read_to_string(root.join("config/a.cfg")).unwrap(),
            "new"
        );
        assert!(!root.join("config/extra.cfg").exists());
        assert!(root.join("mods/m.jar").is_file());
        assert!(!root.join(".restore-7").exists());
        assert!(!root.join(".restore-old-7-config").exists());
    }

    #[test]
    fn a_restore_only_replaces_config_and_saves() {
        let d = tempfile::tempdir().unwrap();
        let root = d.path().join("pack");
        std::fs::create_dir_all(root.join("mods")).unwrap();
        std::fs::write(root.join("mods/real.jar"), "jar").unwrap();
        std::fs::create_dir_all(root.join(".backups")).unwrap();
        std::fs::write(root.join(".backups/keep.zip"), "z").unwrap();
        let zip_path = d.path().join("evil.zip");
        {
            let f = std::fs::File::create(&zip_path).unwrap();
            let mut z = zip::ZipWriter::new(f);
            let o = zip::write::SimpleFileOptions::default();
            for (name, body) in [
                ("mods/evil.jar", "x"),
                (".backups/other.zip", "x"),
                ("options.txt", "x"),
                ("saves/World/level.dat", "lvl"),
            ] {
                z.start_file(name, o).unwrap();
                std::io::Write::write_all(&mut z, body.as_bytes()).unwrap();
            }
            z.finish().unwrap();
        }
        restore_into(&root, &zip_path, 9).unwrap();
        assert!(root.join("mods/real.jar").is_file());
        assert!(!root.join("mods/evil.jar").exists());
        assert!(root.join(".backups/keep.zip").is_file());
        assert!(!root.join(".backups/other.zip").exists());
        assert!(!root.join("options.txt").exists());
        assert_eq!(
            std::fs::read_to_string(root.join("saves/World/level.dat")).unwrap(),
            "lvl"
        );
    }
}

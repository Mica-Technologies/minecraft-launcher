// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Pre-update backups (Java `ModpackBackupManager`): `config/` (and optionally `saves/`)
//! zipped to `<packRoot>/.backups/<yyyy-MM-dd--HH-mm-ss>.zip` before an update over an
//! existing install, then pruned by count and age.

use std::fs::{self, File};
use std::io::{self, BufWriter};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

use mmcl_config::Config;
use mmcl_i18n::tf;
use zip::ZipWriter;

use crate::pack::GameModPack;
use crate::ziputil;

/// The backups folder inside a pack.
pub const BACKUPS_DIR: &str = mmcl_core::paths::BACKUPS_FOLDER;

/// The backup settings.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BackupPolicy {
    pub auto_before_update: bool,
    pub include_saves: bool,
    /// 0 = no count cap.
    pub max_count: i32,
    /// 0 = no age cap.
    pub max_age_days: i32,
}

impl Default for BackupPolicy {
    fn default() -> Self {
        BackupPolicy {
            auto_before_update: true,
            include_saves: false,
            max_count: 3,
            max_age_days: 14,
        }
    }
}

impl BackupPolicy {
    /// From the launcher settings.
    pub fn from_config(c: &Config) -> Self {
        BackupPolicy {
            auto_before_update: c.backup_auto_before_update(),
            include_saves: c.backup_include_saves(),
            max_count: c.backup_max_count_per_pack(),
            max_age_days: c.backup_max_age_days(),
        }
    }
}

/// Backs up `pack` before an update. `None` when disabled, nothing to back up, or on error.
pub fn backup_before_update(pack: &GameModPack, policy: &BackupPolicy) -> Option<PathBuf> {
    let root = pack.checked_pack_root().ok()?;
    let out = backup_folder(&root, policy, chrono::Local::now().naive_local())?;
    prune_in(&root, policy, &pack.name(), SystemTime::now());
    Some(out)
}

/// The core of [`backup_before_update`] for a pack folder.
pub fn backup_folder(
    root: &Path,
    policy: &BackupPolicy,
    now: chrono::NaiveDateTime,
) -> Option<PathBuf> {
    if !policy.auto_before_update || !root.is_dir() {
        return None;
    }
    let mut subdirs: Vec<&str> = vec!["config"];
    if policy.include_saves {
        subdirs.push("saves");
    }
    let subdirs: Vec<&str> = subdirs
        .into_iter()
        .filter(|d| root.join(d).is_dir())
        .collect();
    if subdirs.is_empty() {
        return None;
    }
    let dir = root.join(BACKUPS_DIR);
    if fs::create_dir_all(&dir).is_err() {
        mmcl_log::warn_silent(&tf!(
            "log.modpackBackup.createDirFailed",
            dir.display().to_string()
        ));
        return None;
    }
    let zip_path = dir.join(format!(
        "{}.zip",
        now.format(mmcl_core::paths::LOG_FILE_STAMP_FORMAT)
    ));
    let res = (|| -> io::Result<()> {
        let mut zip = ZipWriter::new(BufWriter::new(File::create(&zip_path)?));
        for d in &subdirs {
            add_tree(&mut zip, &root.join(d), &format!("{d}/"))?;
        }
        zip.finish().map_err(io::Error::other)?;
        Ok(())
    })();
    if let Err(e) = res {
        mmcl_log::warn_silent(&tf!("log.modpackBackup.backupFailed", e.to_string()));
        let _ = fs::remove_file(&zip_path);
        return None;
    }
    let size = fs::metadata(&zip_path).map(|m| m.len() as i64).unwrap_or(0);
    mmcl_log::std(&tf!(
        "log.modpackBackup.created",
        zip_path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default(),
        mmcl_util::units::bytes(size)
    ));
    Some(zip_path)
}

fn add_tree<W: io::Write + io::Seek>(
    zip: &mut ZipWriter<W>,
    dir: &Path,
    prefix: &str,
) -> io::Result<()> {
    let mut entries: Vec<_> = fs::read_dir(dir)?.flatten().collect();
    entries.sort_by_key(|e| e.file_name());
    for e in entries {
        let ft = e.file_type()?;
        if ft.is_symlink() {
            continue;
        }
        let name = format!("{prefix}{}", e.file_name().to_string_lossy());
        if ft.is_dir() {
            ziputil::add_dir(zip, &name)?;
            add_tree(zip, &e.path(), &format!("{name}/"))?;
        } else {
            ziputil::add_file(zip, &name, &e.path())?;
        }
    }
    Ok(())
}

/// Prunes `pack`'s backups by the policy.
pub fn prune_old_backups(pack: &GameModPack, policy: &BackupPolicy) {
    if let Ok(root) = pack.checked_pack_root() {
        prune_in(&root, policy, &pack.name(), SystemTime::now());
    }
}

/// Keeps the newest `max_count` zips and drops those older than `max_age_days`.
pub fn prune_in(root: &Path, policy: &BackupPolicy, pack_name: &str, now: SystemTime) {
    let dir = root.join(BACKUPS_DIR);
    let Ok(rd) = fs::read_dir(&dir) else {
        return;
    };
    let mut zips: Vec<(SystemTime, PathBuf)> = rd
        .flatten()
        .filter(|e| {
            e.file_type().is_ok_and(|t| t.is_file())
                && e.file_name().to_string_lossy().ends_with(".zip")
        })
        .map(|e| {
            (
                e.metadata()
                    .and_then(|m| m.modified())
                    .unwrap_or(SystemTime::UNIX_EPOCH),
                e.path(),
            )
        })
        .collect();
    zips.sort_by_key(|z| std::cmp::Reverse(z.0));
    let max_count = policy.max_count.max(0) as usize;
    let max_age = policy.max_age_days.max(0) as u64;
    let cutoff = now.checked_sub(Duration::from_secs(max_age * 86_400));
    let mut deleted = 0;
    for (i, (mtime, p)) in zips.iter().enumerate() {
        let over_count = max_count > 0 && i >= max_count;
        let too_old = max_age > 0 && cutoff.is_some_and(|c| *mtime < c);
        if over_count || too_old {
            if fs::remove_file(p).is_ok() {
                deleted += 1;
            } else {
                mmcl_log::warn_silent(&tf!(
                    "log.modpackBackup.deleteOldFailed",
                    p.file_name()
                        .map(|n| n.to_string_lossy().into_owned())
                        .unwrap_or_default()
                ));
            }
        }
    }
    if deleted > 0 {
        mmcl_log::std(&tf!(
            "log.modpackBackup.pruned",
            deleted.to_string(),
            pack_name
        ));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn now() -> chrono::NaiveDateTime {
        chrono::NaiveDate::from_ymd_opt(2026, 11, 7)
            .unwrap()
            .and_hms_opt(10, 20, 30)
            .unwrap()
    }

    #[test]
    fn backs_up_config_and_optionally_saves() {
        let d = tempfile::tempdir().unwrap();
        let r = d.path();
        fs::create_dir_all(r.join("config/sub")).unwrap();
        fs::write(r.join("config/a.cfg"), "a").unwrap();
        fs::write(r.join("config/sub/b.cfg"), "b").unwrap();
        fs::create_dir_all(r.join("saves/World")).unwrap();
        fs::write(r.join("saves/World/level.dat"), "w").unwrap();
        let p = BackupPolicy::default();
        let z = backup_folder(r, &p, now()).unwrap();
        assert_eq!(z.file_name().unwrap(), "2026-11-07--10-20-30.zip");
        let mut a = zip::ZipArchive::new(File::open(&z).unwrap()).unwrap();
        let names: Vec<String> = (0..a.len())
            .map(|i| a.by_index(i).unwrap().name().to_string())
            .collect();
        assert_eq!(names, ["config/a.cfg", "config/sub/", "config/sub/b.cfg"]);
        let p2 = BackupPolicy {
            include_saves: true,
            ..p
        };
        let z2 = backup_folder(r, &p2, now() + chrono::Duration::seconds(1)).unwrap();
        let mut a = zip::ZipArchive::new(File::open(&z2).unwrap()).unwrap();
        assert!(a.by_name("saves/World/level.dat").is_ok());
        // Disabled, or nothing to back up: none.
        assert!(
            backup_folder(
                r,
                &BackupPolicy {
                    auto_before_update: false,
                    ..p
                },
                now()
            )
            .is_none()
        );
        let e = tempfile::tempdir().unwrap();
        assert!(backup_folder(e.path(), &p, now()).is_none());
    }

    #[test]
    fn prunes_by_count_and_age() {
        let d = tempfile::tempdir().unwrap();
        let dir = d.path().join(BACKUPS_DIR);
        fs::create_dir_all(&dir).unwrap();
        let base = SystemTime::now();
        for i in 0..5u64 {
            let p = dir.join(format!("{i}.zip"));
            fs::write(&p, "z").unwrap();
            let f = File::options().write(true).open(&p).unwrap();
            f.set_modified(base - Duration::from_secs(i * 86_400))
                .unwrap();
        }
        fs::write(dir.join("keep.txt"), "x").unwrap();
        prune_in(
            d.path(),
            &BackupPolicy {
                max_count: 3,
                max_age_days: 0,
                ..Default::default()
            },
            "P",
            base,
        );
        let left: Vec<String> = fs::read_dir(&dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        assert_eq!(left.len(), 4, "{left:?}");
        prune_in(
            d.path(),
            &BackupPolicy {
                max_count: 0,
                max_age_days: 1,
                ..Default::default()
            },
            "P",
            base,
        );
        let zips = fs::read_dir(&dir)
            .unwrap()
            .filter(|e| {
                e.as_ref()
                    .unwrap()
                    .file_name()
                    .to_string_lossy()
                    .ends_with(".zip")
            })
            .count();
        assert_eq!(zips, 2);
    }
}

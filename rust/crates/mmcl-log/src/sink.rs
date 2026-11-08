// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The session log file: owner-only creation, size-based rotation and the 5 s flush timer.

use std::fs::{self, File, OpenOptions};
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::thread::JoinHandle;
use std::time::Duration;

use parking_lot::Mutex;

/// A log file larger than this is rotated before a new session writes to it (10 MiB).
pub const MAX_LOG_FILE_SIZE: u64 = 10 * 1024 * 1024;

/// How many rotated generations (`.1` .. `.3`) are kept.
pub const MAX_LOG_BACKUPS: u32 = 3;

/// How often buffered log output is flushed to disk.
pub const FLUSH_INTERVAL: Duration = Duration::from_secs(5);

/// The line separator written to the file (Java `System.lineSeparator()`).
#[cfg(windows)]
pub const LINE_SEP: &str = "\r\n";
/// The line separator written to the file (Java `System.lineSeparator()`).
#[cfg(not(windows))]
pub const LINE_SEP: &str = "\n";

/// One open log file.
pub(crate) struct OpenFile {
    pub(crate) writer: BufWriter<File>,
    pub(crate) path: PathBuf,
    /// Set after the first failed write or flush, so a broken disk reports once, not per line.
    pub(crate) failed: bool,
}

/// The shared slot both the logger and the flush thread see.
pub(crate) type FileSlot = Arc<Mutex<Option<OpenFile>>>;

/// The `log-flush` thread and the channel that stops it.
pub(crate) struct Flusher {
    stop: Sender<()>,
    handle: JoinHandle<()>,
}

impl Flusher {
    /// Starts the flush thread. `on_fail` runs (outside the slot lock) the first time a flush
    /// fails.
    pub(crate) fn spawn(slot: FileSlot, on_fail: impl Fn() + Send + 'static) -> io::Result<Self> {
        let (stop, rx) = mpsc::channel::<()>();
        let handle = std::thread::Builder::new()
            .name("log-flush".into())
            .spawn(move || {
                // Ends when stop is requested or the sender (the logger's flusher) is dropped.
                while let Err(RecvTimeoutError::Timeout) = rx.recv_timeout(FLUSH_INTERVAL) {
                    let failed_now = {
                        let mut guard = slot.lock();
                        match guard.as_mut() {
                            Some(f) if !f.failed => {
                                if f.writer.flush().is_err() {
                                    f.failed = true;
                                    true
                                } else {
                                    false
                                }
                            }
                            _ => false,
                        }
                    };
                    if failed_now {
                        on_fail();
                    }
                }
            })?;
        Ok(Flusher { stop, handle })
    }

    /// Stops the thread and waits for it.
    pub(crate) fn stop(self) {
        let _ = self.stop.send(());
        let _ = self.handle.join();
    }
}

/// Opens (creating or truncating) the log file, owner-only from creation on Unix and tightened
/// before the first write elsewhere.
pub(crate) fn open_owner_only(path: &Path) -> io::Result<File> {
    let mut opts = OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let file = opts.open(path)?;
    // Idempotent: also tightens a pre-existing file that kept looser permissions, and applies the
    // owner-only ACL on Windows. Best-effort, as in Java.
    let _ = mmcl_core::fsx::owner_only(path, false);
    Ok(file)
}

/// `<file>.<n>` next to `file`.
pub(crate) fn backup_path(base: &Path, n: u32) -> PathBuf {
    let mut s = base.as_os_str().to_os_string();
    s.push(format!(".{n}"));
    PathBuf::from(s)
}

/// Shifts `file` into the backup sequence: deletes `.3`, moves `.2` → `.3`, `.1` → `.2`, then the
/// file itself → `.1`. Only existing files move; gaps are fine. Each failed move is reported to
/// `on_fail(file_name, error)` and the rotation continues.
pub(crate) fn rotate_with(file: &Path, mut on_fail: impl FnMut(&str, &io::Error)) {
    let base = std::path::absolute(file).unwrap_or_else(|_| file.to_path_buf());
    let oldest = backup_path(&base, MAX_LOG_BACKUPS);
    if oldest.exists() {
        let _ = fs::remove_file(&oldest);
    }
    let mut shift = |from: &Path, to: &Path| {
        if let Err(e) = fs::rename(from, to) {
            let name = from
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default();
            on_fail(&name, &e);
        } else {
            let _ = mmcl_core::fsx::owner_only(to, false);
        }
    };
    for i in (1..MAX_LOG_BACKUPS).rev() {
        let from = backup_path(&base, i);
        if from.exists() {
            shift(&from, &backup_path(&base, i + 1));
        }
    }
    shift(&base, &backup_path(&base, 1));
}

#[cfg(test)]
mod tests {
    //! Port of `LoggerRotationTest`.
    use super::*;

    fn write(p: &Path, s: &str) {
        fs::write(p, s).unwrap();
    }

    fn read(p: &Path) -> String {
        fs::read_to_string(p).unwrap()
    }

    fn rotate(p: &Path) {
        rotate_with(p, |name, e| panic!("rotation of {name} failed: {e}"));
    }

    #[test]
    fn rotating_with_no_existing_backups_renames_the_log_to_dot_one() {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("launcher.log");
        write(&log, "current session");
        rotate(&log);
        assert!(!log.exists());
        assert_eq!(read(&dir.path().join("launcher.log.1")), "current session");
    }

    #[test]
    fn rotating_shifts_an_existing_backup_up_by_one() {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("launcher.log");
        write(&log, "newest");
        write(&dir.path().join("launcher.log.1"), "previously newest");
        rotate(&log);
        assert_eq!(read(&dir.path().join("launcher.log.1")), "newest");
        assert_eq!(
            read(&dir.path().join("launcher.log.2")),
            "previously newest"
        );
    }

    #[test]
    fn rotating_at_capacity_deletes_the_oldest_backup_and_shifts_the_rest() {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("launcher.log");
        write(&log, "gen0");
        for i in 1..=MAX_LOG_BACKUPS {
            write(
                &dir.path().join(format!("launcher.log.{i}")),
                &format!("gen{i}"),
            );
        }
        rotate(&log);
        assert_eq!(read(&dir.path().join("launcher.log.1")), "gen0");
        for i in 1..MAX_LOG_BACKUPS {
            assert_eq!(
                read(&dir.path().join(format!("launcher.log.{}", i + 1))),
                format!("gen{i}")
            );
        }
        assert!(
            !dir.path()
                .join(format!("launcher.log.{}", MAX_LOG_BACKUPS + 1))
                .exists()
        );
    }

    #[test]
    fn rotating_with_a_gap_in_the_backup_sequence_only_shifts_existing_files() {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("launcher.log");
        write(&log, "current");
        write(&dir.path().join("launcher.log.1"), "old-one");
        rotate(&log);
        assert_eq!(read(&dir.path().join("launcher.log.1")), "current");
        assert_eq!(read(&dir.path().join("launcher.log.2")), "old-one");
        assert!(!dir.path().join("launcher.log.3").exists());
    }

    #[test]
    fn rotating_twice_on_a_fresh_log_is_safe() {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("launcher.log");
        write(&log, "first");
        rotate(&log);
        write(&log, "second");
        rotate(&log);
        assert_eq!(read(&dir.path().join("launcher.log.1")), "second");
        assert_eq!(read(&dir.path().join("launcher.log.2")), "first");
    }

    #[test]
    fn a_failed_move_is_reported_and_rotation_continues() {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("launcher.log");
        // No live log: the final move fails, the .1 -> .2 shift still happens.
        write(&dir.path().join("launcher.log.1"), "old");
        let mut failures = Vec::new();
        rotate_with(&log, |name, _| failures.push(name.to_string()));
        assert_eq!(failures, vec!["launcher.log".to_string()]);
        assert_eq!(read(&dir.path().join("launcher.log.2")), "old");
    }

    #[cfg(unix)]
    #[test]
    fn opened_file_is_owner_only_and_truncated() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("launcher.log");
        write(&log, "stale content");
        drop(open_owner_only(&log).unwrap());
        assert_eq!(read(&log), "");
        let mode = fs::metadata(&log).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `logs/startup.log` (Java `StartupDiagnostics`): a tiny append-only record of launch starts,
//! panics, second-launch hand-offs and early failures.
//!
//! It works before the logger and before the game mode is known, which is why it always lives
//! under the *client* root. Lines are `yyyy-MM-dd HH:mm:ss.SSS [pid N] message` in UTF-8 and
//! local time. A file over 256 KiB is moved to `startup.log.1` first. Best-effort: every error is
//! swallowed. Messages are hardcoded English on purpose (written before any locale applies).

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Once;

use parking_lot::Mutex;

use crate::redact::redact;
use crate::sink::LINE_SEP;

/// The file is rolled to `startup.log.1` once it passes this size (256 KiB).
pub const MAX_BYTES: u64 = 256 * 1024;

static LOCK: Mutex<()> = Mutex::new(());

/// `<client root>/logs/startup.log`, whatever the game mode.
pub fn log_path() -> PathBuf {
    mmcl_core::DataPaths::startup_log_file()
}

/// Appends `message` to the startup log.
pub fn record(message: &str) {
    append(&log_path(), message);
}

/// Appends `message` followed by the error and its `source()` chain.
pub fn record_err(message: &str, e: &(dyn std::error::Error + 'static)) {
    let chain = crate::logger::error_chain(e).replace('\n', LINE_SEP);
    record(&format!("{message}{LINE_SEP}{chain}"));
}

/// Records the launch start line (`Start <version>[ (dev)] on <os> <arch>, ...`).
pub fn record_launch(argc: usize) {
    record(&mmcl_core::build::launch_record_line(argc));
}

/// Installs a panic hook that records the panic (with a backtrace) to the startup log, writes an
/// `ERROR` line to the session log, then runs the previous hook (the standard stderr report).
/// Installing it more than once has no further effect.
pub fn install_panic_hook() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        let previous = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            let thread = std::thread::current();
            let name = thread.name().unwrap_or("<unnamed>");
            let backtrace = std::backtrace::Backtrace::force_capture();
            record(&format!(
                "Uncaught exception on thread \"{name}\"{LINE_SEP}{info}{LINE_SEP}{backtrace}"
            ));
            crate::global().panic_line(name, &info.to_string());
            previous(info);
        }));
    });
}

/// Appends one stamped line to `file`, rolling it first if it is over [`MAX_BYTES`].
pub fn append(file: &Path, message: &str) {
    let _guard = LOCK.lock();
    let _ = try_append(file, message);
}

fn try_append(file: &Path, message: &str) -> std::io::Result<()> {
    if let Some(parent) = file.parent().filter(|p| !p.as_os_str().is_empty()) {
        fs::create_dir_all(parent)?;
    }
    if let Ok(meta) = fs::metadata(file)
        && meta.is_file()
        && meta.len() > MAX_BYTES
    {
        let mut rolled = file.as_os_str().to_os_string();
        rolled.push(".1");
        fs::rename(file, PathBuf::from(rolled))?;
    }
    let stamp = chrono::Local::now().format("%Y-%m-%d %H:%M:%S%.3f");
    let line = format!(
        "{stamp} [pid {}] {}{LINE_SEP}",
        std::process::id(),
        redact(message)
    );
    let mut f = OpenOptions::new().create(true).append(true).open(file)?;
    f.write_all(line.as_bytes())
}

#[cfg(test)]
mod tests {
    //! Port of `StartupDiagnosticsTest`.
    use super::*;

    fn lines(p: &Path) -> Vec<String> {
        fs::read_to_string(p)
            .unwrap()
            .lines()
            .map(str::to_string)
            .collect()
    }

    #[test]
    fn appends_timestamped_lines_with_the_process_id() {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("logs").join("startup.log");
        append(&log, "first");
        append(&log, "second");
        let lines = lines(&log);
        assert_eq!(lines.len(), 2);
        assert!(lines[0].ends_with(&format!("[pid {}] first", std::process::id())));
        assert!(lines[1].ends_with("] second"));
        // yyyy-MM-dd HH:mm:ss.SSS
        let stamp = &lines[0][..23];
        assert!(chrono::NaiveDateTime::parse_from_str(stamp, "%Y-%m-%d %H:%M:%S%.3f").is_ok());
    }

    #[test]
    fn rolls_over_once_the_log_passes_its_limit() {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("startup.log");
        fs::write(&log, "x".repeat(MAX_BYTES as usize + 1)).unwrap();
        append(&log, "after rollover");
        assert!(
            fs::metadata(dir.path().join("startup.log.1"))
                .unwrap()
                .len()
                > MAX_BYTES
        );
        let lines = lines(&log);
        assert_eq!(lines.len(), 1);
        assert!(lines[0].ends_with("after rollover"));
    }

    #[test]
    fn never_panics_when_the_log_cannot_be_written() {
        let dir = tempfile::tempdir().unwrap();
        let blocker = dir.path().join("logs");
        fs::write(&blocker, "not a folder").unwrap();
        append(&blocker.join("startup.log"), "lost");
        assert!(!blocker.is_dir());
    }

    #[test]
    fn the_log_sits_in_the_client_folder_whatever_the_game_mode() {
        let expected = mmcl_core::DataPaths::client()
            .logs_dir()
            .join("startup.log");
        assert_eq!(log_path(), expected);
        assert!(log_path().ends_with(Path::new("logs").join("startup.log")));
    }

    #[test]
    fn recorded_lines_are_redacted() {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("startup.log");
        append(&log, "args --accessToken secret");
        let text = fs::read_to_string(&log).unwrap();
        assert!(!text.contains("secret"));
    }
}

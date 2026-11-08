// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `StartupDiagnostics`: a tiny always-on log of process starts and crashes.
//!
//! It lives at `<client root>/logs/startup.log` whatever the game mode, because it is written
//! before the command line is parsed (when a mode-dependent path would resolve to the working
//! directory). Lines are English on purpose (written before any locale is set up) and every
//! failure is swallowed: this log must never stop the launcher starting.

use std::fs::OpenOptions;
use std::io::Write;
use std::path::{Path, PathBuf};

use mmcl_core::DataPaths;
use parking_lot::Mutex;

/// Size past which the log rolls over to `startup.log.1`.
pub const MAX_BYTES: u64 = 256 * 1024;

static LOCK: Mutex<()> = Mutex::new(());

#[cfg(windows)]
const NEWLINE: &str = "\r\n";
#[cfg(not(windows))]
const NEWLINE: &str = "\n";

/// The startup log's path (client root, independent of the game mode).
pub fn log_path() -> PathBuf {
    DataPaths::startup_log_file()
}

/// Appends `yyyy-MM-dd HH:mm:ss.SSS [pid N] <message>` to `file`, first rolling it over to
/// `<file>.1` (replacing) when it is larger than [`MAX_BYTES`]. Never fails.
pub fn append(file: &Path, message: &str) {
    let _guard = LOCK.lock();
    let _ = try_append(file, message);
}

fn try_append(file: &Path, message: &str) -> std::io::Result<()> {
    if let Some(parent) = file.parent() {
        std::fs::create_dir_all(parent)?;
    }
    if let Ok(meta) = std::fs::metadata(file)
        && meta.is_file()
        && meta.len() > MAX_BYTES
    {
        let mut rolled = file.as_os_str().to_owned();
        rolled.push(".1");
        let rolled = PathBuf::from(rolled);
        let _ = std::fs::remove_file(&rolled);
        std::fs::rename(file, &rolled)?;
    }
    let line = format!(
        "{} [pid {}] {}{}",
        chrono::Local::now().format("%Y-%m-%d %H:%M:%S%.3f"),
        std::process::id(),
        message,
        NEWLINE
    );
    let mut f = OpenOptions::new().create(true).append(true).open(file)?;
    f.write_all(line.as_bytes())
}

/// Records a line in the startup log.
pub fn record(message: &str) {
    append(&log_path(), message);
}

/// Records a line followed by an error and its details.
pub fn record_error(message: &str, error: &dyn std::fmt::Display) {
    record(&format!("{message}: {error}"));
}

/// Records the process start: version, flavour, OS, compiler and the argument **count**
/// (arguments can carry deep links, so their text is never written).
pub fn record_launch(argument_count: usize) {
    record(&mmcl_core::build::launch_record_line(argument_count));
}

/// Installs a panic hook that records `Uncaught exception on thread "<name>"` with the panic
/// message, location and a backtrace, then runs the previously installed hook.
pub fn install_panic_hook() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let thread = std::thread::current();
        let name = thread.name().unwrap_or("<unnamed>");
        let payload = panic_message(info.payload());
        let location = info
            .location()
            .map(|l| format!(" at {}:{}", l.file(), l.line()))
            .unwrap_or_default();
        let backtrace = std::backtrace::Backtrace::force_capture();
        record(&format!(
            "Uncaught exception on thread \"{name}\": {payload}{location}{NEWLINE}{backtrace}"
        ));
        previous(info);
    }));
}

/// The text of a panic payload (`&str` or `String`), or a placeholder.
pub fn panic_message(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "<non-string panic payload>".to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn appends_timestamped_lines_with_the_process_id() {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("logs").join("startup.log");
        append(&log, "first");
        append(&log, "second");
        let text = std::fs::read_to_string(&log).unwrap();
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines.len(), 2);
        assert!(lines[0].ends_with(&format!("[pid {}] first", std::process::id())));
        assert!(lines[1].ends_with("] second"));
        // yyyy-MM-dd HH:mm:ss.SSS
        let ts = &lines[0][..23];
        assert!(
            chrono::NaiveDateTime::parse_from_str(ts, "%Y-%m-%d %H:%M:%S%.3f").is_ok(),
            "{ts}"
        );
    }

    #[test]
    fn rolls_over_once_the_log_passes_its_limit() {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("startup.log");
        std::fs::write(&log, "x".repeat(MAX_BYTES as usize + 1)).unwrap();
        append(&log, "after rollover");
        assert!(
            std::fs::metadata(dir.path().join("startup.log.1"))
                .unwrap()
                .len()
                > MAX_BYTES
        );
        let text = std::fs::read_to_string(&log).unwrap();
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines.len(), 1);
        assert!(lines[0].ends_with("after rollover"));
    }

    #[test]
    fn never_panics_when_the_log_cannot_be_written() {
        let dir = tempfile::tempdir().unwrap();
        let blocker = dir.path().join("logs");
        std::fs::write(&blocker, "not a folder").unwrap();
        append(&blocker.join("startup.log"), "lost");
        assert!(!blocker.is_dir());
    }

    #[test]
    fn the_log_sits_in_the_client_folder_whatever_the_game_mode() {
        assert_eq!(
            log_path(),
            DataPaths::client().logs_dir().join("startup.log")
        );
    }

    #[test]
    fn panic_payloads_are_readable() {
        let s: Box<dyn std::any::Any + Send> = Box::new("boom");
        assert_eq!(panic_message(s.as_ref()), "boom");
        let s: Box<dyn std::any::Any + Send> = Box::new(String::from("bang"));
        assert_eq!(panic_message(s.as_ref()), "bang");
        let s: Box<dyn std::any::Any + Send> = Box::new(7u8);
        assert_eq!(panic_message(s.as_ref()), "<non-string panic payload>");
    }
}

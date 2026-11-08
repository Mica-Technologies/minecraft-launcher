// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The [`Logger`]: line format, console and file output, debug gating and UI dispatch.

use std::cell::Cell;
use std::fmt;
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use parking_lot::{Mutex, RwLock};

use crate::redact::redact;
use crate::sink::{self, FileSlot, Flusher, LINE_SEP, MAX_LOG_FILE_SIZE, OpenFile};
use crate::ui::{ToastHook, UiSink};

/// A launcher log level. The prefix text is hardcoded ASCII and never localized, so log lines
/// stay greppable in every language.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum Level {
    /// `ERROR`, to stderr.
    Error,
    /// `WARN`, to stderr.
    Warn,
    /// `STD` (Java `logStd`, tracing `INFO`), to stdout.
    Std,
    /// `DEBUG` (tracing `DEBUG` and `TRACE`), to stdout, gated.
    Debug,
}

impl Level {
    /// The level word in the line prefix.
    pub const fn as_str(self) -> &'static str {
        match self {
            Level::Error => "ERROR",
            Level::Warn => "WARN",
            Level::Std => "STD",
            Level::Debug => "DEBUG",
        }
    }

    /// `true` for the levels written to stderr.
    pub const fn is_stderr(self) -> bool {
        matches!(self, Level::Error | Level::Warn)
    }
}

/// `[<app name>/<LEVEL>] <redacted message>`, with no timestamp (as in Java).
pub fn format_line(app_name: &str, level: Level, msg: &str) -> String {
    format!("[{app_name}/{}] {}", level.as_str(), redact(msg))
}

/// Where console output goes. The default is the process's stdout and stderr; tests and the TUI
/// can provide their own.
pub trait Console: Send + Sync {
    /// Writes `text` plus a newline to standard output.
    fn out(&self, text: &str);
    /// Writes `text` plus a newline to standard error.
    fn err(&self, text: &str);
}

/// The process's real stdout and stderr. Write errors (a closed pipe) are ignored.
pub struct StdConsole;

impl Console for StdConsole {
    fn out(&self, text: &str) {
        let mut out = io::stdout().lock();
        let _ = writeln!(out, "{text}");
    }

    fn err(&self, text: &str) {
        let mut err = io::stderr().lock();
        let _ = writeln!(err, "{text}");
    }
}

/// Lines logged before the first file sink opens are kept (up to this many) and replayed into
/// the file, so the early startup lines are not lost from the session log.
const EARLY_LINE_LIMIT: usize = 2000;

thread_local! {
    /// Set while a UI hook runs on this thread, so a hook that logs does not recurse.
    static IN_UI: Cell<bool> = const { Cell::new(false) };
}

struct UiGuard;

impl UiGuard {
    fn enter() -> Option<UiGuard> {
        IN_UI.with(|f| {
            if f.get() {
                None
            } else {
                f.set(true);
                Some(UiGuard)
            }
        })
    }
}

impl Drop for UiGuard {
    fn drop(&mut self) {
        IN_UI.with(|f| f.set(false));
    }
}

/// The launcher logger. One process-wide instance lives behind [`crate::global`]; separate
/// instances (with their own [`Console`]) exist for tests.
pub struct Logger {
    app_name: RwLock<&'static str>,
    is_dev: AtomicBool,
    file_only: AtomicBool,
    debug_ready: AtomicBool,
    debug_enabled: AtomicBool,
    console: Box<dyn Console>,
    file: FileSlot,
    flusher: Mutex<Option<Flusher>>,
    /// `Some` until the first file sink opens.
    early: Mutex<Option<Vec<String>>>,
    ui: RwLock<Option<Arc<dyn UiSink>>>,
    toast: RwLock<Option<Arc<ToastHook>>>,
}

impl fmt::Debug for Logger {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Logger")
            .field("app_name", &*self.app_name.read())
            .field("is_dev", &self.is_dev.load(Ordering::Relaxed))
            .field("file", &self.log_file())
            .finish_non_exhaustive()
    }
}

impl Logger {
    /// A logger writing to `console`, with no file sink yet.
    pub fn new(app_name: &'static str, is_dev: bool, console: Box<dyn Console>) -> Logger {
        Logger {
            app_name: RwLock::new(app_name),
            is_dev: AtomicBool::new(is_dev),
            file_only: AtomicBool::new(false),
            debug_ready: AtomicBool::new(false),
            debug_enabled: AtomicBool::new(false),
            console,
            file: Arc::new(Mutex::new(None)),
            flusher: Mutex::new(None),
            early: Mutex::new(Some(Vec::new())),
            ui: RwLock::new(None),
            toast: RwLock::new(None),
        }
    }

    /// Sets the console identity: the app name in the prefix, the dev flag (dev builds always
    /// print debug lines) and file-only mode (TUI: the terminal belongs to the TUI, so log lines
    /// never touch it).
    pub fn configure(&self, app_name: &'static str, is_dev: bool, file_only: bool) {
        *self.app_name.write() = app_name;
        self.is_dev.store(is_dev, Ordering::Release);
        self.file_only.store(file_only, Ordering::Release);
    }

    /// Turns file-only (TUI) mode on or off.
    pub fn set_file_only(&self, file_only: bool) {
        self.file_only.store(file_only, Ordering::Release);
    }

    /// The app name used in line prefixes.
    pub fn app_name(&self) -> &'static str {
        *self.app_name.read()
    }

    // ---- debug gating ----

    /// Java `enableConfigBackedDebugLogging`: the config is loaded, so debug lines may now be
    /// gated on its `debug` value. Before this, release builds drop debug lines.
    pub fn enable_config_backed_debug(&self, enabled: bool) {
        self.debug_enabled.store(enabled, Ordering::Release);
        self.debug_ready.store(true, Ordering::Release);
    }

    /// Pushed by the config crate whenever the `debug` setting changes. The logger never reads
    /// the config itself (that inverted lock order and deadlocked in Java).
    pub fn set_debug_enabled(&self, enabled: bool) {
        self.debug_enabled.store(enabled, Ordering::Release);
    }

    /// Whether a debug line would be written right now.
    pub fn debug_allowed(&self) -> bool {
        self.is_dev.load(Ordering::Acquire)
            || (self.debug_ready.load(Ordering::Acquire)
                && self.debug_enabled.load(Ordering::Acquire))
    }

    // ---- output ----

    /// Writes one already-formatted block (one or more lines) to the console stream and file.
    fn write_block(&self, to_stderr: bool, text: &str) {
        self.write_file(text);
        if !self.file_only.load(Ordering::Acquire) {
            if to_stderr {
                self.console.err(text);
            } else {
                self.console.out(text);
            }
        }
    }

    fn write_file(&self, text: &str) {
        let mut guard = self.file.lock();
        match guard.as_mut() {
            Some(f) => {
                if !f.failed && write_lines(&mut f.writer, text).is_err() {
                    f.failed = true;
                }
            }
            None => {
                let mut early = self.early.lock();
                if let Some(buf) = early.as_mut()
                    && buf.len() < EARLY_LINE_LIMIT
                {
                    buf.push(text.to_string());
                }
            }
        }
    }

    /// Logs `msg` at `level`, redacted, with the `[App/LEVEL]` prefix. Debug lines obey
    /// [`debug_allowed`](Self::debug_allowed).
    pub fn log(&self, level: Level, msg: &str) {
        if level == Level::Debug && !self.debug_allowed() {
            return;
        }
        let line = format_line(self.app_name(), level, msg);
        self.write_block(level.is_stderr(), &line);
    }

    /// Java `logStd`.
    pub fn std(&self, msg: &str) {
        self.log(Level::Std, msg);
    }

    /// Java `logDebug`.
    pub fn debug(&self, msg: &str) {
        self.log(Level::Debug, msg);
    }

    /// Java `logWarningSilent`: stderr only.
    pub fn warn_silent(&self, msg: &str) {
        self.log(Level::Warn, msg);
    }

    /// Java `logErrorSilent`: stderr only.
    pub fn error_silent(&self, msg: &str) {
        self.log(Level::Error, msg);
    }

    /// Writes a redacted multi-line block (a stack trace or error chain) to stderr with no
    /// prefix, as Java's `logThrowable` printed it.
    pub fn raw_err(&self, block: &str) {
        let text = redact(block.trim_end_matches(['\r', '\n']));
        self.write_block(true, &text);
    }

    /// Java `logThrowable`: the error and its `source()` chain, redacted, to stderr.
    pub fn throwable(&self, e: &(dyn std::error::Error + 'static)) {
        self.raw_err(&error_chain(e));
    }

    /// Like [`throwable`](Self::throwable) for a report whose `Debug` output is the full trace,
    /// such as `anyhow::Error` (`{:?}` prints the chain and any captured backtrace).
    pub fn throwable_report(&self, report: &dyn fmt::Debug) {
        self.raw_err(&format!("{report:?}"));
    }

    /// Java `logWarningSilent(prefix, throwable)`. `class` names the error type (Java printed
    /// the exception's simple class name).
    pub fn warn_silent_err_named(&self, prefix: &str, class: &str, msg: Option<&str>) {
        let msg = msg.filter(|m| !m.is_empty());
        let line = if prefix.is_empty() {
            match msg {
                Some(m) => format!("{class}: {m}"),
                None => class.to_string(),
            }
        } else {
            match msg {
                Some(m) => mmcl_i18n::tf!("log.logger.operationFailedMessage", prefix, class, m),
                None => mmcl_i18n::tf!("log.logger.operationFailed", prefix, class),
            }
        };
        self.warn_silent(&line);
    }

    // ---- UI-raising calls ----

    fn ui_sink(&self) -> Option<Arc<dyn UiSink>> {
        self.ui.read().clone()
    }

    /// Registers the GUI side of the dialog-raising calls.
    pub fn set_ui_sink(&self, sink: Arc<dyn UiSink>) {
        *self.ui.write() = Some(sink);
    }

    /// Removes the GUI sink (the window shell is going away).
    pub fn clear_ui_sink(&self) {
        *self.ui.write() = None;
    }

    /// Registers the engine's warning-toast callback.
    pub fn set_toast_hook(&self, hook: Arc<ToastHook>) {
        *self.toast.write() = Some(hook);
    }

    /// Removes the warning-toast callback.
    pub fn clear_toast_hook(&self) {
        *self.toast.write() = None;
    }

    /// Runs `f` with the UI sink when one is registered, has a window, and this thread is not
    /// already inside a UI call. No logger lock is held while `f` runs.
    fn with_window<R>(&self, f: impl FnOnce(&dyn UiSink) -> R) -> Option<R> {
        let sink = self.ui_sink()?;
        let _guard = UiGuard::enter()?;
        if !sink.has_window() {
            return None;
        }
        Some(f(sink.as_ref()))
    }

    /// Java `logError`: a blocking modal when a window is up, then the stderr line.
    pub fn error(&self, msg: &str) {
        let shown = redact(msg);
        self.with_window(|s| s.error_modal(&shown));
        self.error_silent(msg);
    }

    /// Java `logErrorAsync`: the stderr line, then a non-blocking dialog.
    pub fn error_async(&self, msg: &str) {
        self.error_silent(msg);
        let shown = redact(msg);
        self.with_window(|s| s.error_async(&shown));
    }

    /// Java `logErrorMultiline`: like [`error`](Self::error), keeping line breaks in the dialog.
    pub fn error_multiline(&self, msg: &str) {
        let shown = redact(msg);
        self.with_window(|s| s.error_multiline(&shown));
        self.error_silent(msg);
    }

    /// Java `logErrorConfirmRetry`: a retry dialog; `false` when there is no window.
    pub fn error_confirm_retry(&self, msg: &str, retry: &str) -> bool {
        let shown = redact(msg);
        let retry = self
            .with_window(|s| s.error_retry(&shown, retry))
            .unwrap_or(false);
        self.error_silent(msg);
        retry
    }

    /// Java `logWarning`: a non-modal toast (`dialog.alert.warning.title`), then the stderr line.
    ///
    /// The toast goes to the engine's [`ToastHook`] when one is registered, otherwise to the
    /// [`UiSink`] when it has a window.
    pub fn warn(&self, msg: &str) {
        let shown = redact(msg);
        let hook = self.toast.read().clone();
        match hook {
            Some(hook) => {
                if let Some(_guard) = UiGuard::enter() {
                    hook(&mmcl_i18n::t!("dialog.alert.warning.title"), &shown);
                }
            }
            None => {
                self.with_window(|s| {
                    s.warn_toast(&mmcl_i18n::t!("dialog.alert.warning.title"), &shown)
                });
            }
        }
        self.warn_silent(msg);
    }

    // ---- file sink ----

    /// The open session log file, if any.
    pub fn log_file(&self) -> Option<PathBuf> {
        self.file.lock().as_ref().map(|f| f.path.clone())
    }

    /// Java `initLogSys(file)`. Idempotent: any previous sink is shut down first (which releases
    /// the handle before rotation; Windows cannot rename an open file). Creates the parent
    /// folders, rotates a file over 10 MiB, creates the file owner-only and truncated, starts the
    /// 5 s flush thread (falling back to unbuffered output if it cannot start), replays the lines
    /// logged before the first sink, and logs `LOG_SYSTEM_INITIALIZED_TEXT`.
    pub fn init_file_sink(&self, path: &Path) -> io::Result<()> {
        self.shutdown();

        if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty())
            && std::fs::create_dir_all(parent).is_err()
            && !parent.is_dir()
        {
            self.debug(&mmcl_i18n::t!("LOG_FILE_DIR_NOT_CREATED_TEXT"));
        }

        if let Ok(meta) = std::fs::metadata(path)
            && meta.is_file()
            && meta.len() > MAX_LOG_FILE_SIZE
        {
            self.rotate(path);
        }

        let file = match sink::open_owner_only(path) {
            Ok(f) => f,
            Err(e) => {
                self.error(&mmcl_i18n::t!("LOG_FILE_NOT_CREATED_TEXT"));
                return Err(e);
            }
        };

        let slot = self.file.clone();
        // The flush thread only reports through the console: it holds no logger reference.
        let flusher = Flusher::spawn(slot, || {
            let msg = mmcl_i18n::t!("log.logger.flushFailed");
            let _ = writeln!(io::stderr(), "{msg}");
        });
        let buffered = flusher.is_ok();
        let writer = if buffered {
            BufWriter::new(file)
        } else {
            BufWriter::with_capacity(0, file)
        };

        {
            let mut guard = self.file.lock();
            let mut open = OpenFile {
                writer,
                path: path.to_path_buf(),
                failed: false,
            };
            if let Some(early) = self.early.lock().take() {
                for block in early {
                    if write_lines(&mut open.writer, &block).is_err() {
                        open.failed = true;
                        break;
                    }
                }
            }
            *guard = Some(open);
        }
        *self.flusher.lock() = flusher.ok();

        if !buffered {
            self.error_silent(&mmcl_i18n::t!("log.logger.unbufferedFallback"));
        }
        self.std(&mmcl_i18n::t!("LOG_SYSTEM_INITIALIZED_TEXT"));
        Ok(())
    }

    /// Flushes buffered file output now.
    pub fn flush(&self) {
        if let Some(f) = self.file.lock().as_mut() {
            let _ = f.writer.flush();
        }
    }

    /// Java `shutdownLogSys`: stops the flush thread, then flushes and closes the file. Later
    /// lines go to the console only.
    pub fn shutdown(&self) {
        if let Some(flusher) = self.flusher.lock().take() {
            flusher.stop();
        }
        let open = self.file.lock().take();
        if let Some(mut f) = open {
            let _ = f.writer.flush();
        }
    }

    /// Java `rotateLogFiles`: shifts the backups (`.3` deleted, `.2` → `.3`, `.1` → `.2`) and moves
    /// `file` to `.1`. A failed move logs `log.logger.rotateFailed` and rotation continues.
    pub fn rotate(&self, file: &Path) {
        sink::rotate_with(file, |name, e| {
            let kind = format!("{:?}", e.kind());
            self.warn_silent(&mmcl_i18n::tf!("log.logger.rotateFailed", name, kind));
        });
    }

    /// Writes a panic report to the session log without waiting on locks (the panicking thread
    /// may hold one).
    pub(crate) fn panic_line(&self, thread: &str, info: &str) {
        let msg = mmcl_i18n::tf!("log.logger.panic", thread, info);
        let line = format_line(self.app_name(), Level::Error, &msg);
        if let Some(mut guard) = self.file.try_lock()
            && let Some(f) = guard.as_mut()
        {
            let _ = write_lines(&mut f.writer, &line);
            let _ = f.writer.flush();
        }
        if !self.file_only.load(Ordering::Acquire) {
            self.console.err(&line);
        }
    }
}

impl Drop for Logger {
    fn drop(&mut self) {
        self.shutdown();
    }
}

/// Writes `text` to the file, ending every line with the platform separator.
fn write_lines(w: &mut impl Write, text: &str) -> io::Result<()> {
    for line in text.split('\n') {
        w.write_all(line.strip_suffix('\r').unwrap_or(line).as_bytes())?;
        w.write_all(LINE_SEP.as_bytes())?;
    }
    Ok(())
}

/// `error`, then `Caused by: <source>` for each source, one per line.
pub fn error_chain(e: &(dyn std::error::Error + 'static)) -> String {
    let mut out = e.to_string();
    let mut cur = e.source();
    while let Some(s) = cur {
        out.push_str("\nCaused by: ");
        out.push_str(&s.to_string());
        cur = s.source();
    }
    out
}

/// The last path segment of a type name, without generic arguments: `std::io::error::Error`
/// becomes `Error`, `dyn core::error::Error` becomes `Error`.
pub fn short_type_name(full: &str) -> &str {
    let full = full.strip_prefix("dyn ").unwrap_or(full);
    let base = full.split('<').next().unwrap_or(full);
    base.rsplit("::").next().unwrap_or(base)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    #[derive(Default)]
    pub(crate) struct Capture {
        pub(crate) out: Mutex<Vec<String>>,
        pub(crate) err: Mutex<Vec<String>>,
    }

    /// A console that records into a shared [`Capture`].
    pub(crate) struct CaptureConsole(pub(crate) Arc<Capture>);

    impl Console for CaptureConsole {
        fn out(&self, text: &str) {
            self.0.out.lock().push(text.to_string());
        }
        fn err(&self, text: &str) {
            self.0.err.lock().push(text.to_string());
        }
    }

    pub(crate) fn logger(dev: bool) -> (Logger, Arc<Capture>) {
        let cap = Arc::new(Capture::default());
        let l = Logger::new("Test App", dev, Box::new(CaptureConsole(cap.clone())));
        (l, cap)
    }

    #[test]
    fn line_format_has_app_and_level_prefix() {
        assert_eq!(
            format_line(
                "Mica Minecraft Launcher",
                Level::Std,
                "Logging subsystem has been initialized."
            ),
            "[Mica Minecraft Launcher/STD] Logging subsystem has been initialized."
        );
        assert_eq!(format_line("A", Level::Error, "x"), "[A/ERROR] x");
        assert_eq!(format_line("A", Level::Warn, "x"), "[A/WARN] x");
        assert_eq!(format_line("A", Level::Debug, "x"), "[A/DEBUG] x");
    }

    #[test]
    fn std_and_debug_go_to_stdout_warn_and_error_to_stderr() {
        let (l, cap) = logger(true);
        l.std("s");
        l.debug("d");
        l.warn_silent("w");
        l.error_silent("e");
        assert_eq!(
            *cap.out.lock(),
            vec!["[Test App/STD] s", "[Test App/DEBUG] d"]
        );
        assert_eq!(
            *cap.err.lock(),
            vec!["[Test App/WARN] w", "[Test App/ERROR] e"]
        );
    }

    #[test]
    fn every_line_is_redacted() {
        let (l, cap) = logger(false);
        l.std("java --accessToken secret123 -Xmx2G");
        assert_eq!(
            cap.out.lock()[0],
            "[Test App/STD] java --accessToken [REDACTED] -Xmx2G"
        );
    }

    #[test]
    fn release_debug_lines_are_dropped_until_config_backed_debug_is_ready() {
        let (l, cap) = logger(false);
        l.set_debug_enabled(true);
        l.debug("before ready");
        assert!(cap.out.lock().is_empty());
        l.enable_config_backed_debug(false);
        l.debug("ready but disabled");
        assert!(cap.out.lock().is_empty());
        l.set_debug_enabled(true);
        l.debug("enabled");
        assert_eq!(*cap.out.lock(), vec!["[Test App/DEBUG] enabled"]);
    }

    #[test]
    fn dev_builds_always_print_debug() {
        let (l, cap) = logger(true);
        l.debug("x");
        assert_eq!(cap.out.lock().len(), 1);
    }

    #[test]
    fn file_only_mode_keeps_the_console_clean() {
        let (l, cap) = logger(true);
        l.set_file_only(true);
        l.std("hidden");
        l.error_silent("hidden too");
        assert!(cap.out.lock().is_empty());
        assert!(cap.err.lock().is_empty());
    }

    #[test]
    fn throwable_prints_the_source_chain_redacted_without_prefix() {
        #[derive(Debug)]
        struct Outer(io::Error);
        impl fmt::Display for Outer {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str("download failed --accessToken abc")
            }
        }
        impl std::error::Error for Outer {
            fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
                Some(&self.0)
            }
        }
        let (l, cap) = logger(false);
        l.throwable(&Outer(io::Error::other("connection reset")));
        assert_eq!(
            cap.err.lock()[0],
            "download failed --accessToken [REDACTED]\nCaused by: connection reset"
        );
    }

    #[test]
    fn warn_silent_err_matches_java_shapes() {
        let (l, cap) = logger(false);
        l.warn_silent_err_named("", "Error", Some("boom"));
        l.warn_silent_err_named("", "Error", None);
        l.warn_silent_err_named("Saving", "Error", None);
        l.warn_silent_err_named("Saving", "Error", Some("disk full"));
        let err = cap.err.lock();
        assert_eq!(err[0], "[Test App/WARN] Error: boom");
        assert_eq!(err[1], "[Test App/WARN] Error");
        // Built through the active bundle so the test holds in any OS language.
        let failed = mmcl_i18n::tf!("log.logger.operationFailed", "Saving", "Error");
        let failed_msg = mmcl_i18n::tf!(
            "log.logger.operationFailedMessage",
            "Saving",
            "Error",
            "disk full"
        );
        assert_eq!(err[2], format!("[Test App/WARN] {failed}"));
        assert_eq!(err[3], format!("[Test App/WARN] {failed_msg}"));
        if mmcl_i18n::lookup("log.logger.operationFailed")
            == mmcl_i18n::english("log.logger.operationFailed")
        {
            assert_eq!(err[2], "[Test App/WARN] Saving failed (Error)");
            assert_eq!(err[3], "[Test App/WARN] Saving failed (Error): disk full");
        }
    }

    #[test]
    fn short_type_names() {
        assert_eq!(short_type_name("std::io::error::Error"), "Error");
        assert_eq!(short_type_name("dyn core::error::Error"), "Error");
        assert_eq!(short_type_name("my::Wrapper<std::io::Error>"), "Wrapper");
        assert_eq!(short_type_name("Plain"), "Plain");
    }

    // ---- UI dispatch ----

    #[derive(Default)]
    struct FakeUi {
        window: bool,
        retry: bool,
        calls: Mutex<Vec<String>>,
    }

    impl UiSink for FakeUi {
        fn error_modal(&self, msg: &str) {
            self.calls.lock().push(format!("modal:{msg}"));
        }
        fn error_async(&self, msg: &str) {
            self.calls.lock().push(format!("async:{msg}"));
        }
        fn error_multiline(&self, msg: &str) {
            self.calls.lock().push(format!("multi:{msg}"));
        }
        fn error_retry(&self, msg: &str, retry: &str) -> bool {
            self.calls.lock().push(format!("retry:{msg}:{retry}"));
            self.retry
        }
        fn warn_toast(&self, title: &str, msg: &str) {
            self.calls.lock().push(format!("toast:{title}:{msg}"));
        }
        fn has_window(&self) -> bool {
            self.window
        }
    }

    #[test]
    fn dialog_calls_reach_the_sink_only_with_a_window() {
        let (l, cap) = logger(false);
        let ui = Arc::new(FakeUi::default());
        l.set_ui_sink(ui.clone());
        l.error("no window");
        assert!(ui.calls.lock().is_empty());
        assert!(!l.error_confirm_retry("x", "Retry"));
        assert_eq!(cap.err.lock().len(), 2);

        let ui = Arc::new(FakeUi {
            window: true,
            retry: true,
            ..Default::default()
        });
        l.set_ui_sink(ui.clone());
        l.error("a --clientToken tok");
        l.error_async("b");
        l.error_multiline("c\nd");
        assert!(l.error_confirm_retry("e", "Retry"));
        assert_eq!(
            ui.calls.lock()[..4],
            [
                "modal:a --clientToken [REDACTED]".to_string(),
                "async:b".to_string(),
                "multi:c\nd".to_string(),
                "retry:e:Retry".to_string()
            ]
        );
        l.clear_ui_sink();
        l.error("after clear");
        assert_eq!(ui.calls.lock().len(), 4);
    }

    #[test]
    fn warnings_toast_through_the_hook_first_then_the_sink() {
        let (l, cap) = logger(false);
        let ui = Arc::new(FakeUi {
            window: true,
            ..Default::default()
        });
        l.set_ui_sink(ui.clone());
        l.warn("via sink");
        assert_eq!(ui.calls.lock().len(), 1);
        assert!(ui.calls.lock()[0].ends_with(":via sink"));

        let hooked = Arc::new(Mutex::new(Vec::<(String, String)>::new()));
        let h = hooked.clone();
        l.set_toast_hook(Arc::new(move |t: &str, m: &str| {
            h.lock().push((t.to_string(), m.to_string()))
        }));
        l.warn("via hook");
        assert_eq!(
            ui.calls.lock().len(),
            1,
            "the hook takes priority over the sink"
        );
        assert_eq!(hooked.lock()[0].1, "via hook");
        assert!(!hooked.lock()[0].0.is_empty());
        l.clear_toast_hook();
        assert_eq!(
            *cap.err.lock(),
            vec!["[Test App/WARN] via sink", "[Test App/WARN] via hook"]
        );
    }

    #[test]
    fn a_hook_that_warns_does_not_recurse() {
        let (l, cap) = logger(false);
        let l = Arc::new(l);
        let inner = Arc::downgrade(&l);
        let count = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let c = count.clone();
        l.set_toast_hook(Arc::new(move |_t: &str, _m: &str| {
            c.fetch_add(1, Ordering::SeqCst);
            if let Some(l) = inner.upgrade() {
                l.warn("nested");
            }
        }));
        l.warn("outer");
        assert_eq!(count.load(Ordering::SeqCst), 1);
        assert_eq!(cap.err.lock().len(), 2);
        l.clear_toast_hook();
    }

    // ---- file sink ----

    #[test]
    fn file_sink_tees_lines_and_replays_early_ones() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("logs").join("session.log");
        let (l, cap) = logger(false);
        l.std("before the file");
        l.init_file_sink(&path).unwrap();
        l.error_silent("after --accessToken t0k");
        l.shutdown();
        l.std("console only");

        let text = std::fs::read_to_string(&path).unwrap();
        let lines: Vec<&str> = text.split(LINE_SEP).filter(|s| !s.is_empty()).collect();
        assert_eq!(lines[0], "[Test App/STD] before the file");
        assert!(lines[1].starts_with("[Test App/STD] "));
        assert_eq!(lines[2], "[Test App/ERROR] after --accessToken [REDACTED]");
        assert_eq!(lines.len(), 3);
        assert_eq!(
            cap.out.lock().last().unwrap(),
            "[Test App/STD] console only"
        );
        assert!(l.log_file().is_none());
    }

    #[test]
    fn reinit_truncates_and_only_the_first_sink_gets_early_lines() {
        let dir = tempfile::tempdir().unwrap();
        let a = dir.path().join("a.log");
        let (l, _cap) = logger(false);
        l.std("early");
        l.init_file_sink(&a).unwrap();
        l.std("one");
        l.init_file_sink(&a).unwrap();
        l.std("two");
        l.flush();
        let text = std::fs::read_to_string(&a).unwrap();
        assert!(!text.contains("early"));
        assert!(!text.contains("one"));
        assert!(text.contains("[Test App/STD] two"));
        assert_eq!(l.log_file().as_deref(), Some(a.as_path()));
        l.shutdown();
    }

    #[test]
    fn an_oversized_log_is_rotated_before_reuse() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("big.log");
        let f = std::fs::File::create(&path).unwrap();
        f.set_len(MAX_LOG_FILE_SIZE + 1).unwrap();
        drop(f);
        let (l, _cap) = logger(false);
        l.init_file_sink(&path).unwrap();
        l.shutdown();
        let backup = dir.path().join("big.log.1");
        assert_eq!(
            std::fs::metadata(&backup).unwrap().len(),
            MAX_LOG_FILE_SIZE + 1
        );
        assert!(std::fs::metadata(&path).unwrap().len() < 1024);
    }

    #[test]
    fn an_unopenable_file_is_an_error() {
        let dir = tempfile::tempdir().unwrap();
        let blocker = dir.path().join("logs");
        std::fs::write(&blocker, "not a folder").unwrap();
        let (l, cap) = logger(false);
        assert!(l.init_file_sink(&blocker.join("x.log")).is_err());
        assert!(l.log_file().is_none());
        assert!(!cap.err.lock().is_empty());
    }

    #[cfg(unix)]
    #[test]
    fn the_session_log_is_owner_only() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("s.log");
        let (l, _cap) = logger(false);
        l.init_file_sink(&path).unwrap();
        let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
        l.shutdown();
    }

    #[test]
    fn panic_line_is_written_to_file_and_console() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("p.log");
        let (l, cap) = logger(false);
        l.init_file_sink(&path).unwrap();
        l.panic_line("worker", "boom");
        l.shutdown();
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(text.contains("worker"));
        assert!(text.contains("boom"));
        assert!(cap.err.lock().iter().any(|s| s.contains("boom")));
    }
}

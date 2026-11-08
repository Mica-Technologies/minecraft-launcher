// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Logging for the Mica Minecraft Launcher (Java `files/Logger`, `SensitiveDataRedactor`,
//! `StartupDiagnostics`).
//!
//! - **Line format:** `[<app name>/<LEVEL>] <message>` with LEVEL one of `ERROR`, `WARN`, `STD`,
//!   `DEBUG`; no timestamp; the prefix is never localized. `STD`/`DEBUG` go to stdout,
//!   `ERROR`/`WARN` to stderr.
//! - **Redaction:** every line goes through [`redact::redact`]. [`redact::redact_strict`] also
//!   strips UUIDs, for text that leaves the process.
//! - **Session log:** [`init_file_sink`] opens `logs/<APP>_<MODE>_<stamp>.log` owner-only and
//!   truncated, rotates a file over 10 MiB (3 backups), flushes every 5 s, and tees every line to
//!   it (file-only in TUI mode). Lines logged before the first sink are replayed into it.
//! - **`tracing`:** [`install_console`] installs a subscriber whose layer writes events through
//!   the same logger (`info!` = `STD`). Dependencies are filtered to `WARN`.
//! - **Debug gating:** dev builds always print debug lines; release builds drop them until
//!   [`enable_config_backed_debug`] runs, then follow [`set_debug_enabled`]. The logger never
//!   reads the config.
//! - **UI hooks:** [`error`], [`error_async`], [`error_multiline`], [`error_confirm_retry`] raise
//!   dialogs through a registered [`UiSink`]; [`warn`] raises a toast through the engine's
//!   [`ToastHook`] (or the sink). Without hooks they are plain log lines.
//! - **Startup diagnostics:** [`startup_diag`] writes `logs/startup.log` under the client root.

use std::fmt;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock};

pub mod layer;
pub mod logger;
pub mod redact;
mod sink;
pub mod startup_diag;
pub mod ui;

pub use layer::LauncherLayer;
pub use logger::{Console, Level, Logger, StdConsole, format_line};
pub use redact::{redact, redact_strict};
pub use sink::{FLUSH_INTERVAL, LINE_SEP, MAX_LOG_BACKUPS, MAX_LOG_FILE_SIZE};
pub use ui::{ToastHook, UiSink};

static GLOBAL: LazyLock<Arc<Logger>> = LazyLock::new(|| {
    let info = mmcl_core::build::info();
    Arc::new(Logger::new(
        info.app_name,
        info.is_dev,
        Box::new(StdConsole),
    ))
});

/// The process-wide logger behind the free functions and the `tracing` layer.
pub fn global() -> &'static Arc<Logger> {
    &GLOBAL
}

/// Configures the console and installs the `tracing` subscriber (once per process; later calls
/// only update the settings). `app_name` is the Java-compatible name in the line prefix
/// (`mmcl_core::build::info().app_name`); `tui_file_only` keeps log lines off the terminal.
///
/// Never call this on the `--mcp` stdio relay path: stdout there is protocol-only.
pub fn install_console(app_name: &'static str, is_dev: bool, tui_file_only: bool) {
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::util::SubscriberInitExt;

    GLOBAL.configure(app_name, is_dev, tui_file_only);
    // `try_init` also bridges `log`-crate records (tracing-log). A second call fails harmlessly.
    let _ = tracing_subscriber::registry()
        .with(LauncherLayer::new(GLOBAL.clone()))
        .try_init();
}

/// Switches TUI file-only mode after [`install_console`].
pub fn set_tui_file_only(file_only: bool) {
    GLOBAL.set_file_only(file_only);
}

/// Opens the session log file (Java `initLogSys`). See [`Logger::init_file_sink`].
pub fn init_file_sink(file: &Path) -> io::Result<()> {
    GLOBAL.init_file_sink(file)
}

/// The open session log file, if any.
pub fn log_file() -> Option<PathBuf> {
    GLOBAL.log_file()
}

/// Flushes the session log now.
pub fn flush() {
    GLOBAL.flush();
}

/// Stops the flush timer, then flushes and closes the session log (Java `shutdownLogSys`).
pub fn shutdown() {
    GLOBAL.shutdown();
}

/// Shifts `file` into its `.1`..`.3` backups (Java `rotateLogFiles`).
pub fn rotate(file: &Path) {
    GLOBAL.rotate(file);
}

/// Registers the GUI side of the dialog-raising calls (set by `mmcl-app`).
pub fn set_ui_sink(s: Arc<dyn UiSink>) {
    GLOBAL.set_ui_sink(s);
}

/// Removes the GUI sink.
pub fn clear_ui_sink() {
    GLOBAL.clear_ui_sink();
}

/// Registers the engine's warning-toast callback, called with `(title, message)`.
pub fn set_toast_hook(hook: impl Fn(&str, &str) + Send + Sync + 'static) {
    GLOBAL.set_toast_hook(Arc::new(hook));
}

/// Removes the warning-toast callback.
pub fn clear_toast_hook() {
    GLOBAL.clear_toast_hook();
}

/// The config is loaded: gate release-build debug lines on `enabled` from now on.
pub fn enable_config_backed_debug(enabled: bool) {
    GLOBAL.enable_config_backed_debug(enabled);
}

/// The `debug` setting changed (pushed by the config crate).
pub fn set_debug_enabled(enabled: bool) {
    GLOBAL.set_debug_enabled(enabled);
}

/// Whether a debug line would be written right now.
pub fn debug_enabled() -> bool {
    GLOBAL.debug_allowed()
}

/// Java `logStd`.
pub fn std(msg: &str) {
    GLOBAL.std(msg);
}

/// Java `logDebug`.
pub fn debug(msg: &str) {
    GLOBAL.debug(msg);
}

/// Java `logWarningSilent`.
pub fn warn_silent(msg: &str) {
    GLOBAL.warn_silent(msg);
}

/// Java `logErrorSilent`.
pub fn error_silent(msg: &str) {
    GLOBAL.error_silent(msg);
}

/// Java `logWarning`: a toast, then the stderr line.
pub fn warn(msg: &str) {
    GLOBAL.warn(msg);
}

/// Java `logError`: a blocking modal (with a window), then the stderr line.
pub fn error(msg: &str) {
    GLOBAL.error(msg);
}

/// Java `logErrorAsync`: the stderr line, then a non-blocking dialog.
pub fn error_async(msg: &str) {
    GLOBAL.error_async(msg);
}

/// Java `logErrorMultiline`: like [`error`], keeping line breaks in the dialog.
pub fn error_multiline(msg: &str) {
    GLOBAL.error_multiline(msg);
}

/// Java `logErrorConfirmRetry`: `true` when the user chose retry; `false` without a window.
pub fn error_confirm_retry(msg: &str, retry: &str) -> bool {
    GLOBAL.error_confirm_retry(msg, retry)
}

/// Java `logThrowable`: the error and its source chain, redacted, to stderr.
pub fn throwable(e: &(dyn std::error::Error + 'static)) {
    GLOBAL.throwable(e);
}

/// [`throwable`] for a report printed with `{:?}` (e.g. `anyhow::Error`, which includes the
/// chain and any captured backtrace).
pub fn throwable_report(report: &dyn fmt::Debug) {
    GLOBAL.throwable_report(report);
}

/// Java `logWarningSilent(prefix, throwable)`: `Type: msg` with an empty prefix, otherwise
/// `log.logger.operationFailed[Message]` with the error's short type name.
pub fn warn_silent_err<E: std::error::Error + ?Sized>(prefix: &str, e: &E) {
    let class = logger::short_type_name(std::any::type_name::<E>());
    let msg = e.to_string();
    GLOBAL.warn_silent_err_named(prefix, class, Some(&msg));
}

/// Like [`warn_silent_err`] with an optional error (Java's null throwable logs just `prefix`).
pub fn warn_silent_opt_err<E: std::error::Error + ?Sized>(prefix: &str, e: Option<&E>) {
    match e {
        Some(e) => warn_silent_err(prefix, e),
        None => warn_silent(prefix),
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn the_overlay_and_java_keys_this_crate_uses_exist() {
        for key in [
            "LOG_FILE_DIR_NOT_CREATED_TEXT",
            "LOG_FILE_NOT_CREATED_TEXT",
            "LOG_SYSTEM_INITIALIZED_TEXT",
            "dialog.alert.warning.title",
            "log.logger.flushFailed",
            "log.logger.unbufferedFallback",
            "log.logger.rotateFailed",
            "log.logger.operationFailed",
            "log.logger.operationFailedMessage",
            "log.logger.panic",
        ] {
            assert!(mmcl_i18n::english(key).is_some(), "missing key {key}");
        }
    }

    #[test]
    fn global_logger_uses_the_build_identity() {
        assert_eq!(
            super::global().app_name(),
            mmcl_core::build::info().app_name
        );
    }
}

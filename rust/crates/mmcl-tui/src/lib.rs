// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The full-screen terminal UI started by `--cli` / `--tui` (Rust port of the Java `tui`
//! package, ratatui + crossterm in place of Lanterna).
//!
//! It runs the launcher as a normal client (same config and data folder as the GUI) with four
//! views: Library, Browse, Logs and Settings, plus a status bar with the running count, the live
//! total playtime and the signed-in account. Quitting leaves games running.
//!
//! * [`mode`]: `--cli` / `--tui` detection and the process-wide TUI flag.
//! * [`run`] / [`run_engine`]: the entry points `mmcl-app` calls.
//! * [`host::TuiHost`]: everything the UI needs from the launcher (implemented over
//!   [`mmcl_engine::Engine`] by [`host::EngineHost`], faked in tests).
//! * [`format`], [`theme`], [`log_view`], [`runtime`], [`progress`]: pure pieces with tests.
//! * [`follower`]: keeps RGB lighting and Discord presence on the newest running game.

pub mod app;
pub mod follower;
pub mod format;
pub mod host;
pub mod log_view;
pub mod mode;
pub mod progress;
pub mod runtime;
pub mod terminal;
pub mod theme;
pub mod widgets;

use std::sync::Arc;

pub use app::{App, Msg, View};
pub use follower::{
    FollowerSink, PresenceSink, RunningGameFollower, install_running_game_follower,
};
pub use host::{EngineHost, HostEvent, TuiHost};
pub use mode::{enable, is_enabled, requested_in};
pub use progress::{ProgressLines, TuiProgress};
pub use runtime::TuiRuntime;
pub use theme::TuiTheme;

/// Why the terminal UI could not run.
#[derive(Debug, thiserror::Error)]
pub enum TuiError {
    /// stdin or stdout is not an interactive terminal (IDE console, pipe, service).
    #[error("no interactive terminal")]
    NoTerminal,
    /// Raw mode or the alternate screen could not be entered.
    #[error("terminal setup failed: {0}")]
    Setup(std::io::Error),
    /// The terminal went away or failed while the UI ran.
    #[error("terminal I/O error: {0}")]
    Io(#[from] std::io::Error),
}

impl TuiError {
    /// The localized text shown on the real terminal.
    pub fn message(&self) -> String {
        match self {
            TuiError::NoTerminal => mmcl_i18n::t!("tui.error.noTerminal").into_owned(),
            TuiError::Setup(e) => mmcl_i18n::tf!("tui.error.setup", e.to_string()),
            TuiError::Io(e) => mmcl_i18n::tf!("tui.error.generic", e.to_string()),
        }
    }
}

/// Runs the terminal UI over `host`; blocks until the user quits. The terminal is restored on
/// every exit path (return, error, panic).
pub fn run(host: Arc<dyn TuiHost>) -> Result<(), TuiError> {
    terminal::run_app(host, mmcl_i18n::localizer())
}

/// What [`run_engine`] ended with; [`RunOutcome::exit_code`] is the process exit code.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RunOutcome {
    /// The user quit.
    Quit,
    /// There is no saved account; `tui.auth.noAccount` was printed.
    NoAccount,
    /// No interactive terminal or setup failed; the reason was printed.
    NoTerminal,
    /// The UI failed while running; `tui.error.generic` was printed.
    Failed,
}

impl RunOutcome {
    /// `0` for [`RunOutcome::Quit`] and [`RunOutcome::NoAccount`] (as Java), `1` otherwise.
    pub fn exit_code(self) -> i32 {
        match self {
            RunOutcome::Quit | RunOutcome::NoAccount => 0,
            RunOutcome::NoTerminal | RunOutcome::Failed => 1,
        }
    }
}

/// The TUI branch of the session start (spec B.2 step 6) over an engine: refuses without a
/// saved account, installs the running-game follower (RGB + Discord follow TUI-launched games),
/// starts the available-pack fetch and runs the UI. Messages go to the real terminal. Does not
/// shut the engine down; the caller runs its normal cleanup afterwards.
pub fn run_engine(engine: &mmcl_engine::Engine) -> RunOutcome {
    if engine.accounts().is_empty() {
        eprintln!("{}", mmcl_i18n::t!("tui.auth.noAccount"));
        return RunOutcome::NoAccount;
    }
    install_running_game_follower(engine);
    let host = EngineHost::new(engine.clone());
    match run(host) {
        Ok(()) => RunOutcome::Quit,
        Err(e @ (TuiError::NoTerminal | TuiError::Setup(_))) => {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!("log.tui.terminalUnavailable", e.to_string())
            );
            eprintln!("{}", e.message());
            RunOutcome::NoTerminal
        }
        Err(e) => {
            tracing::error!("{}", mmcl_i18n::tf!("log.tui.failed", e.to_string()));
            eprintln!("{}", e.message());
            RunOutcome::Failed
        }
    }
}

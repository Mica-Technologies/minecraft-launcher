// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Mica Minecraft Launcher executable (core-config-i18n §5 and §6, packaging-ci §3.7).
//!
//! Order of `main`, each step before anything the next one needs:
//!
//! 1. Helper processes whose stdout is a protocol: `--rgb-helper` (vendor RGB SDKs) and
//!    `--mcp` (the MCP stdio relay). No logger, no startup record, nothing else on stdout.
//! 2. The startup guard: panic hook and the `startup.log` launch line.
//! 3. `--msa-signin` (the web-view sign-in helper) and `--diag-manifest`.
//! 4. Platform hardening, the TUI flag, the console attach for terminal modes, the console
//!    logger.
//! 5. The single-instance lock; a second launch hands its deep link to the first and exits.
//! 6. The session: argument grammar, game mode, the engine, the locale, the session log file,
//!    then the GUI, the terminal UI or the headless server.

#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod deeplink;
mod gui;
mod launch_flow;
mod lifecycle;
mod mcp_glue;
mod second_launch;
mod server;
mod startup_guard;
mod ui_sink;

use mmcl_core::args::{binary_name, usage_synopsis};
use mmcl_core::{CommandLine, GameMode, ModeAction, PreSession};
use mmcl_engine::{Engine, EngineOptions};
use mmcl_i18n::{t, tf};

use crate::startup_guard::DialogLevel;

fn main() {
    std::process::exit(run());
}

fn run() -> i32 {
    let cl = CommandLine::from_env();

    // 1. Protocol helpers.
    if mmcl_rgb::helper::helper_requested(&cl.raw) {
        return mmcl_rgb::helper::run_helper(&cl.raw);
    }
    if cl.pre_session == PreSession::Mcp {
        let (stdin, stdout, stderr) = (std::io::stdin(), std::io::stdout(), std::io::stderr());
        return mmcl_mcp::relay::run(
            &mmcl_mcp::endpoint::default_path(),
            stdin.lock(),
            stdout.lock(),
            stderr.lock(),
        );
    }

    // 2. Startup guard.
    startup_guard::install(cl.raw.len(), cl.pre_session.records_launch());

    // 3. Other pre-session forms.
    match &cl.pre_session {
        PreSession::MsaSignin(args) => return mmcl_signin_webview::run_helper(args),
        PreSession::DiagManifest(url) => {
            startup_guard::attach_console();
            eprintln!("{}", tf!("app.diagManifest.unavailable", url.as_str()));
            return 2;
        }
        PreSession::Mcp | PreSession::Normal => {}
    }

    // 4. Hardening, terminal modes, console logger.
    mmcl_platform::early_init();
    mmcl_core::handheld::set_cli_flag(cl.gamepad_flag);
    if cl.tui {
        mmcl_tui::enable();
    }
    let terminal_mode = cl.tui || !cl.likely_client() || cl.session.invalid;
    if terminal_mode {
        startup_guard::attach_console();
    }
    let info = mmcl_core::build::info();
    mmcl_log::install_console(info.app_name, info.is_dev, cl.tui);

    // 5. Single instance.
    if !mmcl_platform::single_instance::try_acquire() {
        let headless = mmcl_core::mode::is_headless(&|k| std::env::var(k).ok());
        return second_launch::run(&cl.raw, cl.tui, headless || !cl.likely_client());
    }

    // 6. The session.
    let code = session(&cl);
    mmcl_platform::single_instance::release();
    code
}

/// The session (core-config-i18n §6.2) for an argv that passed the pre-session checks.
fn session(cl: &CommandLine) -> i32 {
    let args = cl.session.clone();
    if args.invalid {
        let msg = format!(
            "{}\n{}: {}",
            t!("INVALID_ARGS_SPECIFIED_TEXT"),
            t!("USAGE_TEXT"),
            usage_synopsis(&binary_name())
        );
        mmcl_log::error_silent(&msg);
        return 1;
    }
    let mode = match args.mode_action {
        ModeAction::Client => GameMode::Client,
        ModeAction::Server => GameMode::Server,
        ModeAction::Infer | ModeAction::None => mmcl_core::mode::infer(),
    };
    mmcl_core::mode::set(mode);

    let mut options = EngineOptions::for_mode(mode);
    options.tui = cl.tui;
    // The terminal UI installs its own running-game follower.
    options.follow_running_games = !cl.tui;
    options.web_view_supported = mmcl_signin_webview::is_supported();
    // A headless server needs none of the GUI's background work, and loads its pack list
    // itself (blocking on the revalidation).
    options.start_background_work = mode != GameMode::Server;
    let engine = match Engine::new(options) {
        Ok(e) => e,
        Err(e) => {
            let body = tf!("app.startup.failed", format!("{e:#}"));
            startup_guard::record(&format!("Engine start failed: {e:#}"));
            startup_guard::show_native_dialog(
                DialogLevel::Error,
                mmcl_core::AppIdentity::current().display_name,
                &body,
                cl.tui || mode == GameMode::Server,
            );
            return 1;
        }
    };

    // Locale, debug logging, the session log file.
    mmcl_i18n::init(mmcl_i18n::locale::resolve(
        &engine.config().locale_override(),
    ));
    mmcl_log::enable_config_backed_debug(engine.config().debug_logging());
    let log_file = engine
        .paths()
        .session_log_file(&chrono::Local::now().naive_local());
    if let Err(e) = mmcl_log::init_file_sink(&log_file) {
        startup_guard::record(&format!("Session log unavailable: {e}"));
    }
    log_session_start(cl);

    if cl.tui {
        let code = mmcl_tui::run_engine(&engine).exit_code();
        lifecycle::cleanup(Some(&engine), None);
        return code;
    }
    if mode == GameMode::Server {
        let code = server::run(&engine, &args.modpack_selection);
        lifecycle::cleanup(Some(&engine), None);
        return code;
    }
    gui::run(engine, args, None)
}

fn log_session_start(cl: &CommandLine) {
    tracing::debug!("{}", t!("log.launcherSession.loggingConfigured"));
    for arg in &cl.raw {
        tracing::debug!("  {}", mmcl_log::redact(arg));
    }
    if mmcl_core::build::info().is_dev {
        tracing::debug!("{}", t!("log.launcherSession.devModeNotice"));
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn every_app_key_exists() {
        for k in [
            "app.diagManifest.unavailable",
            "app.startup.failed",
            "INVALID_ARGS_SPECIFIED_TEXT",
            "USAGE_TEXT",
            "PACK_NOT_INSTALLED_WILL_DEFAULT_TO_FIRST_TEXT",
            "NO_MOD_PACKS_INSTALLED_CANT_LAUNCH_SERVER_TEXT",
            "PERFORMING_APP_CLEANUP_TEXT",
            "FINISHED_APP_CLEANUP_TEXT",
            "SEE_YOU_SOON_TEXT",
            "REMEMBERED_ACCOUNT_NOT_FOUND_SHOWING_LOGIN",
            "log.launcherSession.loggingConfigured",
            "log.launcherSession.devModeNotice",
            "log.launcherCore.cleanupStepFailed",
            "log.launcherCore.cleanupStepTimedOut",
            "log.launcherCore.lifecycleTransitionIgnored",
            "log.launcherCore.mainGuiLoadFailed",
            "log.launcherCore.relaunchingProcess",
            "log.launcherCore.relaunchUnavailable",
            "settings.resetLauncher.startAgain",
            "tui.alreadyRunning",
            "startup.alreadyRunning.noWindow",
            "startup.alreadyRunning.unreachable",
            "startup.portUnavailable",
        ] {
            assert!(mmcl_i18n::contains(k), "{k}");
        }
    }
}

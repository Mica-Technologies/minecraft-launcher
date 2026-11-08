// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! GUI mode: the GPUI application, the screen crates wired into the shell, the MCP server, the
//! platform host, and the session flow (sign in if needed, then the main screen, the CLI pack
//! auto-launch and the pending deep link).
//!
//! Threading (core-config-i18n §6.6): GPUI owns the main thread. Close and relaunch tear the
//! windows down on the GPUI thread, run the blocking back-end cleanup on a dedicated thread,
//! then quit the run loop. An in-process restart rebuilds the main window and keeps the
//! engine, the games and the Running Games window alive.

use std::rc::Rc;
use std::sync::Arc;

use futures::channel::oneshot;
use gpui::{App, AsyncApp, Global, QuitMode};
use mmcl_core::LauncherArgs;
use mmcl_engine::{Engine, Setting};
use mmcl_i18n::{t, tf};
use mmcl_mcp::McpBootstrap;
use mmcl_platform::{MenuAction, PlatformHost, RecentPack};
use mmcl_ui::{GuiHandle, ScreenId, ScreenRequest};
use parking_lot::Mutex;

use crate::lifecycle::{self, Lifecycle};
use crate::mcp_glue::{GuiConsentPresenter, PanelAdapter};

/// Deep links that arrived before the main screen was up.
static PENDING_URIS: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// Queues a deep link for the session flow (`setPendingUri`).
pub fn queue_uri(uri: String) {
    PENDING_URIS.lock().push(uri);
}

fn take_pending_uris() -> Vec<String> {
    std::mem::take(&mut *PENDING_URIS.lock())
}

/// Whether the session flow has reached the main screen (deep links run at once after that).
static SESSION_READY: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// What the hooks need, as a GPUI global.
#[derive(Clone)]
struct AppState {
    engine: Engine,
    mcp: Arc<McpBootstrap>,
    args: LauncherArgs,
}

impl Global for AppState {}

fn state(cx: &App) -> AppState {
    cx.global::<AppState>().clone()
}

/// Runs the GUI until the launcher closes. Returns the process exit code.
pub fn run(engine: Engine, args: LauncherArgs, initial_error: Option<String>) -> i32 {
    if let Some(uri) = args.pending_uri.clone() {
        queue_uri(uri);
    }
    let app = gpui_platform::application()
        .with_assets(mmcl_ui_kit::Assets)
        .with_quit_mode(QuitMode::Explicit);
    app.on_open_urls(|urls| {
        for url in urls {
            match mmcl_platform::host::host() {
                Some(host) if SESSION_READY.load(std::sync::atomic::Ordering::Acquire) => {
                    host.handle_deep_link(&url)
                }
                _ => queue_uri(url),
            }
        }
    });
    let shared: Arc<Mutex<Option<Arc<McpBootstrap>>>> = Arc::new(Mutex::new(None));
    let shared_in = shared.clone();
    let engine_in = engine.clone();
    app.run(move |cx| {
        let mcp = setup(cx, engine_in, args);
        *shared_in.lock() = Some(mcp);
        start_session(cx, initial_error);
    });
    // The run loop returned (Windows, Linux): make sure the back end is cleaned up.
    let mcp = shared.lock().take();
    lifecycle::cleanup(Some(&engine), mcp.as_ref());
    mmcl_log::std(&t!("SEE_YOU_SOON_TEXT"));
    0
}

/// Initialises every UI crate and hook. Returns the MCP bootstrap.
fn setup(cx: &mut App, engine: Engine, args: LauncherArgs) -> Arc<McpBootstrap> {
    mmcl_ui::init(cx, engine.clone());
    mmcl_ui::screens::register_all(cx);

    mmcl_editor::init(
        cx,
        mmcl_editor::EditorServices::new(mmcl_editor::EngineBackend::shared(engine.clone()))
            .with_help(|topic, _window, cx| mmcl_ui::shell::show_help(cx, topic))
            .with_quit(mmcl_ui::shell::request_quit),
    );
    mmcl_editor::register(cx);
    mmcl_editor::screen::bind_keys(cx);

    mmcl_running_games::init(cx, engine.clone());

    let gui = mmcl_ui::gui_handle(cx).expect("mmcl_ui::init installs the controller");
    crate::ui_sink::mark_gui_thread();
    mmcl_log::set_ui_sink(Arc::new(crate::ui_sink::GuiUiSink { gui: gui.clone() }));
    let host =
        mmcl_mcp::live::EngineHost::new(engine.clone(), GuiConsentPresenter::prompt(gui.clone()));
    let mcp = Arc::new(McpBootstrap::new(Arc::new(host)));
    mmcl_ui::screens::settings::mcp::set_mcp_panel(
        cx,
        Rc::new(PanelAdapter {
            mcp: mcp.clone(),
            engine: engine.clone(),
        }),
    );
    {
        // Binding the port and building the registries stays off the GPUI thread.
        let mcp = mcp.clone();
        let _ = std::thread::Builder::new()
            .name("mcp-start".into())
            .spawn(move || mcp.start_if_enabled());
    }

    cx.set_global(AppState {
        engine: engine.clone(),
        mcp: mcp.clone(),
        args,
    });
    install_hooks(cx, &engine);
    mmcl_platform::host::set_host(Arc::new(AppHost {
        engine: engine.clone(),
        gui,
    }));
    mcp
}

fn install_hooks(cx: &mut App, engine: &Engine) {
    use mmcl_ui::screens::home_browse as library;
    use mmcl_ui::shell;

    shell::set_close_app_handler(cx, close_app);
    shell::set_restart_handler(cx, restart_app);
    shell::on_theme_changed(cx, mmcl_running_games::refresh_theme);
    shell::on_screen_shown(cx, |id, window, cx| {
        mmcl_running_games::follow_screen(cx, Some(window.window_handle()));
        mmcl_running_games::refresh_theme(cx);
        if let Some(e) = mmcl_ui::try_engine(cx) {
            e.set_discord_menu_presence(id.as_str());
        }
    });

    library::set_show_session_handler(cx, |id, cx| mmcl_running_games::show_session(cx, id));
    library::set_show_running_games_handler(cx, mmcl_running_games::show_window);
    let e = engine.clone();
    library::set_play_handler(cx, move |id, cx| {
        crate::launch_flow::start(e.clone(), id, cx);
    });

    let e = engine.clone();
    shell::set_play_recent_handler(cx, move |index, cx| {
        if let Some(pack) = recent_packs(&e, index + 1).get(index)
            && let Some(summary) = e.find_installed_by_name(&pack.pack_name)
        {
            library::start_play(cx, &summary);
        }
    });
    let e = engine.clone();
    shell::set_menu_toggle_handler(cx, move |toggle, _cx| {
        let s = e.settings();
        let setting = match toggle {
            shell::MenuToggle::DiscordRpc => Setting::DiscordRpc(!s.discord_rpc),
            shell::MenuToggle::KeepGamesWindowOpen => Setting::InGameConsole(!s.in_game_console),
            shell::MenuToggle::PackBackgrounds => {
                Setting::ShowPackBackgrounds(!s.show_pack_backgrounds)
            }
        };
        if let Err(err) = e.set(setting) {
            tracing::warn!("{}", err.message());
        }
    });
}

/// Shows the dock under every screen (again after an in-process restart cleared it).
fn attach_dock(cx: &mut App) {
    if let Some(dock) = mmcl_running_games::dock_host(cx) {
        mmcl_ui::shell::set_bottom_dock(cx, Some(dock.into()));
    }
}

/// The session flow (core-config-i18n §6.2 steps 9-22, GUI branch).
fn start_session(cx: &mut App, initial_error: Option<String>) {
    let st = state(cx);
    SESSION_READY.store(false, std::sync::atomic::Ordering::Release);
    if let Err(e) = mmcl_ui::shell::start_gui(cx) {
        tracing::error!("{} ({e})", t!("log.launcherCore.mainGuiLoadFailed"));
        crate::startup_guard::show_native_dialog(
            crate::startup_guard::DialogLevel::Error,
            mmcl_core::AppIdentity::current().display_name,
            &e.message(),
            false,
        );
        close_app(cx);
        return;
    }
    attach_dock(cx);
    if st.engine.has_active_sessions() {
        // Games survive an in-process restart: show them again.
        mmcl_running_games::show_window(cx);
    }
    let Some(gui) = mmcl_ui::gui_handle(cx) else {
        return;
    };
    cx.spawn(async move |cx| {
        session_flow(st, gui, initial_error, cx).await;
    })
    .detach();
}

async fn session_flow(
    st: AppState,
    gui: GuiHandle,
    initial_error: Option<String>,
    cx: &mut AsyncApp,
) {
    let engine = st.engine.clone();
    if engine.accounts().is_empty() {
        tracing::debug!("{}", t!("REMEMBERED_ACCOUNT_NOT_FOUND_SHOWING_LOGIN"));
        let Some(login) = mmcl_ui::screens::accounts::go_to_login(&gui).await else {
            tracing::error!("{}", t!("log.launcherCore.mainGuiLoadFailed"));
            cx.update(close_app);
            return;
        };
        if let Some(err) = initial_error.as_deref().filter(|e| !e.trim().is_empty()) {
            gui.show_error(err.to_string()).await;
        }
        login.wait_for_login_success().await;
    } else if let Some(err) = initial_error.as_deref().filter(|e| !e.trim().is_empty()) {
        gui.show_error(err.to_string()).await;
    }

    if !gui.go_to(ScreenId::HOME).await {
        tracing::error!("{}", t!("log.launcherCore.mainGuiLoadFailed"));
    }
    Lifecycle::global().end();
    SESSION_READY.store(true, std::sync::atomic::Ordering::Release);

    // Desktop-shortcut auto-launch of a pack named on the command line.
    let selection = st.args.modpack_selection.trim().to_string();
    if !selection.is_empty() {
        match engine.find_installed_by_name(&selection) {
            Some(pack) => cx.update(|cx| mmcl_ui::screens::home_browse::start_play(cx, &pack)),
            None => {
                let msg = format!(
                    "{selection} {}",
                    t!("PACK_NOT_INSTALLED_WILL_DEFAULT_TO_FIRST_TEXT")
                );
                gui.show_error(msg).await;
            }
        }
    }

    for uri in take_pending_uris() {
        cx.update(|cx| crate::deeplink::handle(&uri, &engine, cx));
    }
}

/// `closeApp`: no confirmation (the guarded path is `shell::request_quit`).
pub fn close_app(cx: &mut App) {
    if !Lifecycle::global().begin() {
        return;
    }
    shutdown_and(cx, None);
}

/// Tears the GUI down, runs the back-end cleanup off the GPUI thread, then (optionally spawns
/// `relaunch` and) quits.
fn shutdown_and(cx: &mut App, relaunch: Option<std::path::PathBuf>) {
    let st = state(cx);
    mmcl_log::clear_ui_sink();
    mmcl_platform::host::clear_host();
    mmcl_running_games::shutdown(cx);
    mmcl_ui::shell::exit(cx);
    for window in cx.windows() {
        let _ = window.update(cx, |_, window, _| window.remove_window());
    }
    let (tx, rx) = oneshot::channel::<()>();
    let spawned = std::thread::Builder::new()
        .name("Launcher-Close".into())
        .spawn(move || {
            lifecycle::cleanup(Some(&st.engine), Some(&st.mcp));
            if let Some(exe) = relaunch
                && let Err(e) = lifecycle::spawn_relaunch(&exe)
            {
                crate::startup_guard::record(&format!("Relaunch failed: {e}"));
                crate::startup_guard::show_native_dialog(
                    crate::startup_guard::DialogLevel::Warning,
                    mmcl_core::AppIdentity::current().display_name,
                    &t!("settings.resetLauncher.startAgain"),
                    false,
                );
            }
            let _ = tx.send(());
        });
    if spawned.is_err() {
        cx.quit();
        return;
    }
    cx.spawn(async move |cx| {
        let _ = rx.await;
        mmcl_log::std(&t!("SEE_YOU_SOON_TEXT"));
        cx.update(|cx| cx.quit());
    })
    .detach();
}

/// `restartApp`: after a reset (the engine already shut down and its folder is gone) the
/// process must start again, never in-process; otherwise the GUI is rebuilt in-process and the
/// engine, games and Running Games window stay.
pub fn restart_app(cx: &mut App) {
    if !Lifecycle::global().begin() {
        return;
    }
    let st = state(cx);
    if st.engine.is_shut_down() {
        match lifecycle::relaunch_executable() {
            Some(exe) => {
                tracing::info!(
                    "{}",
                    tf!(
                        "log.launcherCore.relaunchingProcess",
                        exe.display().to_string()
                    )
                );
                shutdown_and(cx, Some(exe));
            }
            None => {
                tracing::info!("{}", t!("log.launcherCore.relaunchUnavailable"));
                crate::startup_guard::show_native_dialog(
                    crate::startup_guard::DialogLevel::Warning,
                    mmcl_core::AppIdentity::current().display_name,
                    &t!("settings.resetLauncher.startAgain"),
                    false,
                );
                shutdown_and(cx, None);
            }
        }
        return;
    }
    mmcl_ui::shell::exit(cx);
    cx.set_global(AppState {
        args: st.args.for_restart(),
        ..st
    });
    start_session(cx, None);
}

/// Up to `max` played packs, most recent first.
fn recent_packs(engine: &Engine, max: usize) -> Vec<RecentPack> {
    let mut packs: Vec<_> = engine
        .installed_packs()
        .into_iter()
        .filter(|p| p.last_played_ms > 0)
        .collect();
    packs.sort_by_key(|p| std::cmp::Reverse(p.last_played_ms));
    packs
        .into_iter()
        .take(max)
        .map(|p| {
            let display = if p.friendly_name.trim().is_empty() {
                p.name.clone()
            } else {
                p.friendly_name.clone()
            };
            RecentPack::new(p.name, display, p.last_played_ms)
        })
        .collect()
}

/// What the platform crate (IPC, tray, dock, jump list) calls into.
struct AppHost {
    engine: Engine,
    gui: GuiHandle,
}

impl PlatformHost for AppHost {
    fn has_main_window(&self) -> bool {
        self.gui.is_started()
    }

    fn focus_window(&self) {
        self.gui.request_focus();
    }

    fn handle_deep_link(&self, uri: &str) {
        if !SESSION_READY.load(std::sync::atomic::Ordering::Acquire) {
            queue_uri(uri.to_string());
            return;
        }
        let uri = uri.to_string();
        let engine = self.engine.clone();
        self.gui
            .run(move |cx| crate::deeplink::handle(&uri, &engine, cx));
    }

    fn on_menu_action(&self, action: MenuAction) {
        let engine = self.engine.clone();
        self.gui.run(move |cx| menu_action(action, &engine, cx));
    }

    fn recent_packs(&self, max: usize) -> Vec<RecentPack> {
        recent_packs(&self.engine, max)
    }

    fn battery_throttle_enabled(&self) -> Option<bool> {
        Some(self.engine.settings().battery_throttle)
    }
}

fn menu_action(action: MenuAction, engine: &Engine, cx: &mut App) {
    use mmcl_ui::screens::home_browse::start_play;
    match action {
        MenuAction::ShowLauncher => mmcl_ui::shell::request_focus(cx),
        MenuAction::PlayLast => {
            let last = engine.settings().last_mod_pack;
            if let Some(pack) = engine.find_installed_by_name(&last) {
                mmcl_ui::shell::request_focus(cx);
                start_play(cx, &pack);
            }
        }
        MenuAction::PlayModpack(name) => {
            if let Some(pack) = engine.find_installed_by_name(&name) {
                mmcl_ui::shell::request_focus(cx);
                start_play(cx, &pack);
            }
        }
        MenuAction::OpenLastModsFolder => {
            let last = engine.settings().last_mod_pack;
            if let Some(pack) = engine.find_installed_by_name(&last) {
                let folder = engine.pack_folder(&pack.id, mmcl_engine::PackFolder::Mods);
                match folder {
                    Ok(folder) => {
                        if let Err(e) = mmcl_platform::open::open_folder(&folder) {
                            tracing::warn!("{e}");
                        }
                    }
                    Err(e) => tracing::warn!("{}", e.message()),
                }
            }
        }
        MenuAction::OpenBrowse => {
            mmcl_ui::shell::request_focus(cx);
            mmcl_ui::shell::navigate_gated(cx, ScreenRequest::new(ScreenId::BROWSE));
        }
        MenuAction::OpenSettings => {
            mmcl_ui::shell::request_focus(cx);
            mmcl_ui::shell::navigate_gated(cx, ScreenRequest::new(ScreenId::SETTINGS));
        }
        MenuAction::OpenHelp => {
            mmcl_ui::shell::show_help(cx, mmcl_ui::HelpTopic::GettingStarted);
        }
        MenuAction::Quit => mmcl_ui::shell::request_quit(cx),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pending_uris_drain_once() {
        queue_uri("mmcl://open".into());
        queue_uri("mmcl://play?name=a".into());
        let drained = take_pending_uris();
        assert_eq!(drained, vec!["mmcl://open", "mmcl://play?name=a"]);
        assert!(take_pending_uris().is_empty());
    }
}

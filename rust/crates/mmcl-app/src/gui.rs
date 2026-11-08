// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! GUI mode: the GPUI application, the screen crates wired into the shell, the MCP server, the
//! platform host, and the session flow (sign in if needed, then the main screen, the CLI pack
//! auto-launch and the pending deep links and opened files).
//!
//! Wiring is split in two so a test can build the whole GUI on GPUI's test platform:
//! [`setup_ui`] does everything inside GPUI (screens, keys, hooks, dialogs, menus, services);
//! [`setup_os`] starts what reaches the operating system (MCP port, platform host, tray, jump
//! list, toasts, gamepad thread).
//!
//! Threading (core-config-i18n §6.6): GPUI owns the main thread. Close and relaunch tear the
//! windows down on the GPUI thread, run the blocking back-end cleanup on a dedicated thread,
//! then quit the run loop. An in-process restart rebuilds the main window and keeps the
//! engine, the games and the Running Games window alive.

use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;

use futures::channel::oneshot;
use gpui::{App, AsyncApp, Global, QuitMode};
use mmcl_core::LauncherArgs;
use mmcl_engine::{Engine, Setting};
use mmcl_i18n::{t, tf};
use mmcl_mcp::McpBootstrap;
use mmcl_platform::{MenuAction, PlatformHost, RecentPack};
use mmcl_ui::{GuiHandle, ScreenId};
use parking_lot::Mutex;

use crate::lifecycle::{self, Lifecycle};
use crate::mcp_glue::PanelAdapter;

/// Deep links that arrived before the main screen was up.
static PENDING_URIS: Mutex<Vec<String>> = Mutex::new(Vec::new());
/// `.mmcjson` files opened before the main screen was up (`LauncherCore.addPendingMmcjsonFile`).
static PENDING_FILES: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());

/// Queues a deep link for the session flow (`setPendingUri`).
pub fn queue_uri(uri: String) {
    PENDING_URIS.lock().push(uri);
}

fn take_pending_uris() -> Vec<String> {
    std::mem::take(&mut *PENDING_URIS.lock())
}

fn take_pending_files() -> Vec<PathBuf> {
    std::mem::take(&mut *PENDING_FILES.lock())
}

/// Whether the session flow has reached the main screen (deep links run at once after that).
static SESSION_READY: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn session_ready() -> bool {
    SESSION_READY.load(std::sync::atomic::Ordering::Acquire)
}

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

/// What an opened URL is: a launcher deep link, or a `.mmcjson` file (Finder or the dock).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OpenedUrl {
    DeepLink(String),
    MmcjsonFile(PathBuf),
    Other,
}

/// Classifies a URL GPUI's `on_open_urls` delivered.
pub fn classify_opened(url: &str) -> OpenedUrl {
    if let Some(path) = mmcl_ui::shell::mmcjson_path(url.trim()) {
        return OpenedUrl::MmcjsonFile(path);
    }
    if mmcl_util::deeplink::is_launcher_uri(Some(url)) {
        OpenedUrl::DeepLink(url.to_string())
    } else {
        OpenedUrl::Other
    }
}

fn on_opened(url: String) {
    match classify_opened(&url) {
        OpenedUrl::DeepLink(uri) => match mmcl_platform::host::host() {
            Some(host) if session_ready() => host.handle_deep_link(&uri),
            _ => queue_uri(uri),
        },
        OpenedUrl::MmcjsonFile(path) => match app_host() {
            Some(h) if session_ready() => h.import_file(path),
            _ => PENDING_FILES.lock().push(path),
        },
        OpenedUrl::Other => {}
    }
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
            on_opened(url);
        }
    });
    let shared: Arc<Mutex<Option<Arc<McpBootstrap>>>> = Arc::new(Mutex::new(None));
    let shared_in = shared.clone();
    let engine_in = engine.clone();
    app.run(move |cx| {
        let mcp = setup_ui(cx, engine_in.clone(), args);
        setup_os(cx, &engine_in, &mcp);
        *shared_in.lock() = Some(mcp);
        start_session(cx, initial_error);
    });
    // The run loop returned (Windows, Linux): make sure the back end is cleaned up.
    let mcp = shared.lock().take();
    lifecycle::cleanup(Some(&engine), mcp.as_ref());
    mmcl_log::std(&t!("SEE_YOU_SOON_TEXT"));
    0
}

/// Everything inside GPUI: the UI crates, screens, key bindings, hooks, the dialogs, the menu
/// bar, the services the UI crates leave to the app, and the MCP bootstrap (not started).
pub(crate) fn setup_ui(cx: &mut App, engine: Engine, args: LauncherArgs) -> Arc<McpBootstrap> {
    crate::bundled::install();
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
    crate::services::install(cx, &engine);

    let gui = mmcl_ui::gui_handle(cx).expect("mmcl_ui::init installs the controller");
    crate::ui_sink::mark_gui_thread();
    mmcl_log::set_ui_sink(Arc::new(crate::ui_sink::GuiUiSink { gui: gui.clone() }));
    let host = mmcl_mcp::live::EngineHost::new(
        engine.clone(),
        crate::dialogs::consent::GpuiConsentPresenter::prompt(gui.clone()),
    );
    let mcp = Arc::new(McpBootstrap::new(Arc::new(host)));
    mmcl_ui::screens::settings::mcp::set_mcp_panel(
        cx,
        Rc::new(PanelAdapter {
            mcp: mcp.clone(),
            engine: engine.clone(),
        }),
    );

    cx.set_global(AppState {
        engine: engine.clone(),
        mcp: mcp.clone(),
        args,
    });
    install_hooks(cx, &engine);
    crate::dialogs::install(cx, &engine);
    crate::detail_hooks::install(cx, &engine);
    // The shell's event-driven desktop integration (mmcl-ui `shell::desktop`): engine toasts
    // through the platform backend (tray balloons on Windows), taskbar / dock launch progress,
    // the short-launch "Ready to play" toast, and the menu bar, macOS dock menu and jump list
    // kept in step with screens, launches and settings. The app must not wire a second copy.
    mmcl_ui::install_desktop_integration(cx);
    cx.set_global(HostSlot(Some(Arc::new(AppHost {
        engine: engine.clone(),
        gui,
    }))));
    mcp
}

/// What reaches the OS: the MCP port, the platform host (IPC, tray, dock, jump list), the tray
/// icon, the gamepad poller.
pub(crate) fn setup_os(cx: &mut App, engine: &Engine, mcp: &Arc<McpBootstrap>) {
    {
        // Binding the port and building the registries stays off the GPUI thread.
        let mcp = mcp.clone();
        let _ = std::thread::Builder::new()
            .name("mcp-start".into())
            .spawn(move || mcp.start_if_enabled());
    }
    if let Some(host) = cx.try_global::<HostSlot>().and_then(|h| h.0.clone()) {
        *APP_HOST.lock() = Some(host.clone());
        mmcl_platform::host::set_host(host);
    }
    // Windows and Linux: the tray icon (Show / Play Last / Open Mods / Quit) for the session,
    // up from the start rather than with the first toast.
    mmcl_platform::tray::ensure();
    crate::gamepad::start(cx);
    // Ctrl-C / SIGTERM / console close: flush the config and clean up before the process ends.
    crate::signals::install(engine, mcp.clone());
}

/// The installed host, so `on_open_urls` (outside GPUI) can reach it.
#[derive(Clone, Default)]
struct HostSlot(Option<Arc<AppHost>>);

impl Global for HostSlot {}

static APP_HOST: Mutex<Option<Arc<AppHost>>> = Mutex::new(None);

fn app_host() -> Option<Arc<AppHost>> {
    APP_HOST.lock().clone()
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

    mmcl_running_games::set_dock_room_provider(cx, |window_height, _window, cx| {
        // The title bar keeps its native size; the rest is in layout px at 100 %.
        let title_bar = mmcl_ui::shell::title_bar::height().as_f32() / mmcl_ui::scale::factor(cx);
        let screen_min = mmcl_ui::shell::current_screen(cx).map(|s| s.min_size(cx).height.as_f32());
        dock_room(window_height, title_bar, screen_min)
    });
    // Crash-card Open Settings / Open Runtime ask the current screen first (the editor's
    // unsaved-changes prompt), like every other navigation.
    mmcl_running_games::set_navigate_handler(cx, |req, cx| shell::navigate_gated(cx, req));
    library::set_show_session_handler(cx, |id, cx| mmcl_running_games::show_session(cx, id));
    library::set_show_running_games_handler(cx, mmcl_running_games::show_window);
    library::set_account_switcher_handler(cx, mmcl_ui::screens::accounts::show_switcher);
    mmcl_ui::screens::settings::set_add_account_handler(cx, |window, cx| {
        mmcl_ui::screens::accounts::show_add_account(Some(window.window_handle()), cx)
    });

    let e = engine.clone();
    shell::set_play_recent_handler(cx, move |index, cx| {
        // The same list, in the same order, as the menu bar's Play Recent submenu.
        if let Some(pack) = recent_packs(&e, mmcl_ui::shell::menu_bar::RECENT_MAX).get(index)
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

/// The room the docked Running Games view may take (layout px), Java `ScaledRoot.dockRoom`:
/// the window height minus the title bar and the current screen's minimum height, never
/// negative. `None` without a screen (the dock then takes its full share).
pub fn dock_room(window_height: f32, title_bar: f32, screen_min: Option<f32>) -> Option<f32> {
    screen_min.map(|min| (window_height - title_bar - min).max(0.0))
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
    // (start_gui bound the taskbar progress to the new window and re-synced the menus.)
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
    for file in take_pending_files() {
        // `dispatchMmcjsonImport`: toasts the result, reloads Home only when it is showing.
        cx.update(|cx| mmcl_ui::shell::import_mmcjson(file, cx));
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
    *APP_HOST.lock() = None;
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
    // The rebuilt screens come back in the configured language (a settings import may have
    // changed it without going through Save & Restart's own switch).
    mmcl_i18n::set_locale(mmcl_i18n::locale::resolve(
        &st.engine.config().locale_override(),
    ));
    cx.set_global(AppState {
        args: st.args.for_restart(),
        ..st
    });
    start_session(cx, None);
}

/// Up to `max` played packs, most recent first (the shell's order, so every surface agrees).
pub(crate) fn recent_packs(engine: &Engine, max: usize) -> Vec<RecentPack> {
    mmcl_ui::shell::menu_bar::recent_packs(engine.installed_packs(), max)
}

/// What the platform crate (IPC, tray, dock, jump list) calls into.
struct AppHost {
    engine: Engine,
    gui: GuiHandle,
}

impl AppHost {
    fn import_file(&self, path: PathBuf) {
        self.gui
            .run(move |cx| mmcl_ui::shell::import_mmcjson(path, cx));
    }
}

impl PlatformHost for AppHost {
    fn has_main_window(&self) -> bool {
        self.gui.is_started()
    }

    fn focus_window(&self) {
        self.gui.request_focus();
    }

    fn handle_deep_link(&self, uri: &str) {
        if !session_ready() {
            queue_uri(uri.to_string());
            return;
        }
        let uri = uri.to_string();
        let engine = self.engine.clone();
        self.gui
            .run(move |cx| crate::deeplink::handle(&uri, &engine, cx));
    }

    fn on_menu_action(&self, action: MenuAction) {
        // `LauncherActions`: the shell runs it, with Java's toasts when there is no last pack.
        self.gui
            .run(move |cx| mmcl_ui::shell::run_shell_action(action, cx));
    }

    fn recent_packs(&self, max: usize) -> Vec<RecentPack> {
        recent_packs(&self.engine, max)
    }

    fn battery_throttle_enabled(&self) -> Option<bool> {
        Some(self.engine.settings().battery_throttle)
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

    #[test]
    fn the_dock_never_squeezes_the_screen_below_its_minimum() {
        assert_eq!(dock_room(900.0, 32.0, Some(600.0)), Some(268.0));
        assert_eq!(dock_room(500.0, 32.0, Some(600.0)), Some(0.0));
        assert_eq!(dock_room(900.0, 32.0, None), None);
    }

    #[test]
    fn opened_urls_are_deep_links_or_mmcjson_files() {
        assert_eq!(
            classify_opened("mmcl://open"),
            OpenedUrl::DeepLink("mmcl://open".into())
        );
        let file = if cfg!(windows) {
            "file:///C:/Users/x/My%20Pack.MMCJSON"
        } else {
            "file:///Users/x/My%20Pack.MMCJSON"
        };
        match classify_opened(file) {
            OpenedUrl::MmcjsonFile(p) => {
                assert!(p.to_string_lossy().ends_with("My Pack.MMCJSON"), "{p:?}")
            }
            other => panic!("{other:?}"),
        }
        assert_eq!(classify_opened("file:///tmp/notes.txt"), OpenedUrl::Other);
        assert_eq!(classify_opened("https://example.com"), OpenedUrl::Other);
    }
}

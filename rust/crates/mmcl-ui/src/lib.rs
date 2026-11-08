// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Mica Minecraft Launcher's main window and its screens (gui-shell spec).
//!
//! | Module | Java origin | What |
//! |---|---|---|
//! | [`shell`] | `MCLauncherGuiController`, `MCLauncherGuiWindow`, `WindowsTitleBarControls`, `LauncherActions`, `NotificationManager` | main window, custom title bar, navigation + gate, close guard, [`GuiHandle`], hooks, menu bar sync, tray/dock actions, desktop integration |
//! | [`actions`] | `KeyboardShortcutManager` | actions, key bindings, key contexts |
//! | [`menus`] | `SystemMenuBarManager` | the menu bar |
//! | [`theme`] | `applyTheme`, `forceThemeChange` | theme resolution and native chrome hookup |
//! | [`scale`], [`motion`] | `UiScale`, `Motion` | interface scale, transitions |
//! | [`dialogs`] | `GUIUtilities` dialogs | questions and alerts owned by the main window |
//! | [`widgets`] | shared controls | kit widgets + [`widgets::NavBar`] |
//! | [`placement`] | `WindowPlacement`, `ScaledMinSize` | pure placement rules |
//! | [`services`] | - | [`ShellBackend`], engine access, engine events on the foreground |
//! | [`screens`] | the screen controllers | Home/Browse, accounts, settings, progress/help |
//!
//! Start-up (GUI mode), inside `gpui_platform::application().with_assets(mmcl_ui_kit::Assets).run(..)`:
//!
//! ```ignore
//! mmcl_ui::init(cx, engine.clone());          // kit, theme, scale, keys, menus, controller
//! mmcl_ui::screens::register_all(cx);         // this crate's screens
//! // mmcl-app: register the editor / Running Games screens and the shell hooks
//! mmcl_ui::shell::start_gui(cx)?;             // the window (cold-start placeholder)
//! mmcl_ui::shell::go_to(cx, ScreenRequest::new(ScreenId::HOME));
//! ```

pub mod actions;
pub mod dialogs;
pub mod menus;
pub mod motion;
pub mod placement;
pub mod scale;
pub mod screens;
pub mod services;
pub mod shell;
pub mod theme;
pub mod widgets;

use std::rc::Rc;

use gpui::{App, Global, Subscription};
use mmcl_engine::{Engine, Setting};

pub use dialogs::{Answer, Question};
pub use mmcl_ui_kit::{
    CloseDecision, HelpTopic, Navigator, Screen, ScreenHandle, ScreenId, ScreenRegistry,
    ScreenRequest,
};
pub use services::{
    EngineEvents, ShellBackend, ShellSettings, UiEvent, engine, events, run, try_engine,
};
pub use shell::{GuiHandle, MainWindow, ShellError};

/// How to initialise the UI.
pub struct UiOptions {
    /// The engine (screens use it; also the default backend).
    pub engine: Option<Engine>,
    /// The shell backend; defaults to the engine, else a no-op backend.
    pub backend: Option<Rc<dyn ShellBackend>>,
}

impl UiOptions {
    /// Options for a real session.
    pub fn with_engine(engine: Engine) -> Self {
        UiOptions {
            engine: Some(engine),
            backend: None,
        }
    }

    /// Options with only a backend (tests, previews).
    pub fn with_backend(backend: Rc<dyn ShellBackend>) -> Self {
        UiOptions {
            engine: None,
            backend: Some(backend),
        }
    }
}

/// Settings watchers of this session.
struct SettingsWatch(#[allow(dead_code)] Vec<Subscription>);

impl Global for SettingsWatch {}

/// Initialises the GUI for a session with an engine. See [`init_with`].
pub fn init(cx: &mut App, engine: Engine) {
    init_with(cx, UiOptions::with_engine(engine));
}

/// Initialises the GUI: the kit (gpui-component, fonts, dialogs, nav keys), the services, the
/// initial theme / scale / motion from the settings, key bindings and action handlers, the menu
/// bar, the controller, and live re-application of appearance settings. Idempotent for the
/// app-wide parts; call once per app (an in-process restart keeps them).
pub fn init_with(cx: &mut App, options: UiOptions) {
    let backend: Rc<dyn ShellBackend> = match (&options.backend, &options.engine) {
        (Some(b), _) => b.clone(),
        (None, Some(e)) => Rc::new(e.clone()),
        (None, None) => Rc::new(services::NullBackend),
    };
    cx.set_global(services::Backend(backend.clone()));
    let settings = backend.shell_settings();
    let initial = theme::resolve(
        &settings.theme,
        theme::is_dark(cx.window_appearance()),
        false,
    );
    if !mmcl_ui_kit::dialogs::is_enabled(cx) {
        mmcl_ui_kit::init(cx, initial);
    } else {
        mmcl_ui_kit::set_theme(cx, initial);
    }
    mmcl_ui_kit::scale::set_percent(cx, settings.ui_scale_percent);
    motion::apply_settings(cx, settings.reduce_motion, settings.wavy_progress);

    if !cx.has_global::<shell::GuiController>() {
        actions::bind_global_keys(cx);
        actions::install_handlers(cx);
    }
    shell::controller::install(cx);

    if let Some(engine) = options.engine {
        mmcl_ui_kit::controls::set_offline(cx, engine.is_offline());
        cx.set_global(services::UiEngine(engine.clone()));
        let events = EngineEvents::install(cx, &engine);
        let watch = cx.subscribe(&events, |_, event: &UiEvent, cx| {
            on_setting_event(event, cx);
        });
        cx.set_global(SettingsWatch(vec![watch]));
    }
    // The bar from the current settings; [`install_desktop_integration`] keeps it in step.
    shell::menu_bar::refresh_now(cx);
}

/// Turns on the event-driven desktop integration of [`shell::desktop`]: engine notifications
/// as toasts through the platform backend (tray balloons on Windows), taskbar / dock progress
/// for preparing launches, the short-launch "Ready to play" toast, and the menu bar, dock menu
/// and jump list kept in step with every screen swap, launch and setting change. Call once
/// after [`init`], instead of (not as well as) an app-side equivalent: both would toast twice.
pub fn install_desktop_integration(cx: &mut App) {
    shell::desktop::install(cx);
}

fn on_setting_event(event: &UiEvent, cx: &mut App) {
    let settings = |cx: &App| services::backend(cx).shell_settings();
    if event.is_setting(&Setting::Theme(String::new())) {
        shell::force_theme_refresh(cx);
    } else if event.is_setting(&Setting::ResizableWindows(false)) {
        let resizable = settings(cx).resizable_windows;
        shell::set_resizable(cx, resizable);
    } else if event.is_setting(&Setting::UiScalePercent(0)) {
        let percent = settings(cx).ui_scale_percent;
        scale::set_ui_scale(cx, percent);
    } else if event.is_setting(&Setting::ReduceMotion(false))
        || event.is_setting(&Setting::WavyProgress(false))
    {
        let s = settings(cx);
        motion::apply_settings(cx, s.reduce_motion, s.wavy_progress);
    } else if matches!(event, UiEvent::Lagged) {
        let s = settings(cx);
        shell::force_theme_refresh(cx);
        scale::set_ui_scale(cx, s.ui_scale_percent);
        motion::apply_settings(cx, s.reduce_motion, s.wavy_progress);
    }
}

/// A [`GuiHandle`] for other threads (after [`init`]).
pub fn gui_handle(cx: &App) -> Option<GuiHandle> {
    shell::handle(cx)
}

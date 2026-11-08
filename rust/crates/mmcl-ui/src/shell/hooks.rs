// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Hooks the shell calls into code it does not own (`mmcl-app`, `mmcl-running-games`, and the
//! help window in this crate's `screens::misc`). Each has a sensible fallback, so the shell runs
//! with none installed.
//!
//! | Hook | Set by | Fallback |
//! |---|---|---|
//! | [`set_help_handler`] | `screens::misc` (help window) | log, nothing shown |
//! | [`set_close_app_handler`] | `mmcl-app` (`LauncherCore.closeApp`) | `cx.quit()` |
//! | [`set_restart_handler`] | `mmcl-app` (`LauncherCore.restartApp`) | close the app |
//! | [`on_theme_changed`] | help window, Running Games window | - |
//! | [`on_exit`] | Running Games `shutdown`, help `cleanup` | - |
//! | [`on_screen_shown`] | Running Games `followScreen`, Discord presence | - |
//! | [`set_bottom_dock`] | Running Games docked view | no dock |
//! | [`set_play_recent_handler`] | `mmcl-app` (recent packs) | ignored |
//! | [`set_menu_toggle_handler`] | `mmcl-app` (View menu check items) | ignored |

use std::rc::Rc;

use gpui::{AnyView, App, Global, Window};
use mmcl_ui_kit::{HelpTopic, ScreenId};

/// Opens help on a topic.
pub type HelpHandler = Rc<dyn Fn(HelpTopic, &mut App)>;
/// An app-level step (close, restart, theme refresh, exit).
pub type AppHandler = Rc<dyn Fn(&mut App)>;
/// Runs after every screen swap.
pub type ScreenShownHandler = Rc<dyn Fn(ScreenId, &mut Window, &mut App)>;
/// Plays the n-th recent pack (0-based) from the menu bar.
pub type PlayRecentHandler = Rc<dyn Fn(usize, &mut App)>;
/// A View-menu toggle was picked.
pub type MenuToggleHandler = Rc<dyn Fn(MenuToggle, &mut App)>;

/// The View-menu check items whose state lives outside the shell.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum MenuToggle {
    /// Discord rich presence.
    DiscordRpc,
    /// Keep the Running Games window open.
    KeepGamesWindowOpen,
    /// Show pack backgrounds.
    PackBackgrounds,
}

/// The hook table (a per-app global: it survives in-process restarts, like the app crate that
/// fills it).
#[derive(Default, Clone)]
pub struct ShellHooks {
    pub(crate) help: Option<HelpHandler>,
    pub(crate) close_app: Option<AppHandler>,
    pub(crate) restart: Option<AppHandler>,
    pub(crate) theme_changed: Vec<AppHandler>,
    pub(crate) exit: Vec<AppHandler>,
    pub(crate) screen_shown: Vec<ScreenShownHandler>,
    pub(crate) bottom_dock: Option<AnyView>,
    pub(crate) play_recent: Option<PlayRecentHandler>,
    pub(crate) menu_toggle: Option<MenuToggleHandler>,
}

impl Global for ShellHooks {}

fn hooks(cx: &mut App) -> &mut ShellHooks {
    cx.default_global::<ShellHooks>()
}

fn read(cx: &App) -> ShellHooks {
    cx.try_global::<ShellHooks>().cloned().unwrap_or_default()
}

/// Installs the help opener (`HelpWindow.show`).
pub fn set_help_handler(cx: &mut App, f: impl Fn(HelpTopic, &mut App) + 'static) {
    hooks(cx).help = Some(Rc::new(f));
}

/// Installs the app-close step (`LauncherCore.closeApp`).
pub fn set_close_app_handler(cx: &mut App, f: impl Fn(&mut App) + 'static) {
    hooks(cx).close_app = Some(Rc::new(f));
}

/// Installs the in-process restart step (`LauncherCore.restartApp`).
pub fn set_restart_handler(cx: &mut App, f: impl Fn(&mut App) + 'static) {
    hooks(cx).restart = Some(Rc::new(f));
}

/// Runs `f` after every theme change (`forceThemeChange` refreshing secondary windows).
pub fn on_theme_changed(cx: &mut App, f: impl Fn(&mut App) + 'static) {
    hooks(cx).theme_changed.push(Rc::new(f));
}

/// Runs `f` when the GUI exits (session end or restart), before the main window closes.
pub fn on_exit(cx: &mut App, f: impl Fn(&mut App) + 'static) {
    hooks(cx).exit.push(Rc::new(f));
}

/// Runs `f` after every screen swap, with the new screen's id.
pub fn on_screen_shown(cx: &mut App, f: impl Fn(ScreenId, &mut Window, &mut App) + 'static) {
    hooks(cx).screen_shown.push(Rc::new(f));
}

/// Shows (or with `None` removes) a view docked under every screen (the docked Running Games
/// view). The shell keeps it across screen swaps.
pub fn set_bottom_dock(cx: &mut App, view: Option<AnyView>) {
    hooks(cx).bottom_dock = view;
    cx.refresh_windows();
}

/// The docked view, if any.
pub fn bottom_dock(cx: &App) -> Option<AnyView> {
    cx.try_global::<ShellHooks>()
        .and_then(|h| h.bottom_dock.clone())
}

/// Installs the "Play Recent" menu handler.
pub fn set_play_recent_handler(cx: &mut App, f: impl Fn(usize, &mut App) + 'static) {
    hooks(cx).play_recent = Some(Rc::new(f));
}

/// Installs the View-menu toggle handler.
pub fn set_menu_toggle_handler(cx: &mut App, f: impl Fn(MenuToggle, &mut App) + 'static) {
    hooks(cx).menu_toggle = Some(Rc::new(f));
}

/// Opens help on `topic` (`HelpWindow.show`).
pub fn show_help(cx: &mut App, topic: HelpTopic) {
    match read(cx).help {
        Some(f) => f(topic, cx),
        None => tracing::debug!(
            "{}",
            mmcl_i18n::tf!("log.ui.helpUnavailable", topic.name_key())
        ),
    }
}

/// Closes the application (no confirmation; see [`crate::shell::request_quit`] for the guarded
/// path).
pub fn close_app(cx: &mut App) {
    match read(cx).close_app {
        Some(f) => f(cx),
        None => {
            crate::shell::controller::exit(cx);
            cx.quit();
        }
    }
}

/// Restarts the launcher in-process (no confirmation; see
/// [`crate::shell::controller::confirm_restart_while_games_run`]).
pub fn restart_app(cx: &mut App) {
    match read(cx).restart {
        Some(f) => f(cx),
        None => close_app(cx),
    }
}

pub(crate) fn run_theme_changed(cx: &mut App) {
    for f in read(cx).theme_changed {
        f(cx);
    }
}

pub(crate) fn run_exit(cx: &mut App) {
    for f in read(cx).exit {
        f(cx);
    }
}

pub(crate) fn run_screen_shown(id: ScreenId, window: &mut Window, cx: &mut App) {
    for f in read(cx).screen_shown {
        f(id, window, cx);
    }
}

pub(crate) fn play_recent(index: usize, cx: &mut App) {
    if let Some(f) = read(cx).play_recent {
        f(index, cx);
    }
}

pub(crate) fn menu_toggle(toggle: MenuToggle, cx: &mut App) {
    if let Some(f) = read(cx).menu_toggle {
        f(toggle, cx);
    }
}

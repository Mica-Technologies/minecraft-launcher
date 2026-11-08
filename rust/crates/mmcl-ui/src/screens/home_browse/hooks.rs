// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Hooks into windows and flows this module does not own (`mmcl-app` installs them): the
//! detail modal, the Running Games window, the account switcher, the quick-start wizard, the
//! Modrinth import-confirm dialog and the launch flow. Each has a fallback, so Home and Browse
//! work with none installed.

use std::rc::Rc;

use gpui::{App, Global, Task, Window};
use mmcl_engine::{PackId, SessionId};

/// The import-confirm dialog's input (`MCLauncherImportConfirmDialog`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportConfirm {
    /// The pack name.
    pub name: String,
    /// The pack version.
    pub version: Option<String>,
}

type WindowFn = Rc<dyn Fn(&mut Window, &mut App)>;
type PackWindowFn = Rc<dyn Fn(PackId, &mut Window, &mut App)>;
type SessionFn = Rc<dyn Fn(SessionId, &mut App)>;
type AppFn = Rc<dyn Fn(&mut App)>;
type ConfirmFn = Rc<dyn Fn(ImportConfirm, &mut App) -> Task<bool>>;
type PackFn = Rc<dyn Fn(PackId, &mut App)>;

/// The hook table.
#[derive(Default, Clone)]
pub struct LibraryHooks {
    pub(crate) open_detail: Option<PackWindowFn>,
    pub(crate) show_session: Option<SessionFn>,
    pub(crate) show_running_games: Option<AppFn>,
    pub(crate) account_switcher: Option<WindowFn>,
    pub(crate) quick_start: Option<WindowFn>,
    pub(crate) confirm_import: Option<ConfirmFn>,
    pub(crate) play: Option<PackFn>,
}

impl Global for LibraryHooks {}

fn hooks(cx: &mut App) -> &mut LibraryHooks {
    cx.default_global::<LibraryHooks>()
}

pub(crate) fn read(cx: &App) -> LibraryHooks {
    cx.try_global::<LibraryHooks>().cloned().unwrap_or_default()
}

/// A card's single click: open the pack detail modal. Without it a click does nothing.
pub fn set_open_detail_handler(cx: &mut App, f: impl Fn(PackId, &mut Window, &mut App) + 'static) {
    hooks(cx).open_detail = Some(Rc::new(f));
}

/// Play on a running pack: bring its Running Games tab forward (`RunningGamesWindow.showSession`).
pub fn set_show_session_handler(cx: &mut App, f: impl Fn(SessionId, &mut App) + 'static) {
    hooks(cx).show_session = Some(Rc::new(f));
}

/// The Home "Running games (n)" button (`RunningGamesWindow.showWindow`).
pub fn set_show_running_games_handler(cx: &mut App, f: impl Fn(&mut App) + 'static) {
    hooks(cx).show_running_games = Some(Rc::new(f));
}

/// The avatar / name click (`AccountSwitcherMenu.show`). Fallback: Settings, accounts category.
pub fn set_account_switcher_handler(cx: &mut App, f: impl Fn(&mut Window, &mut App) + 'static) {
    hooks(cx).account_switcher = Some(Rc::new(f));
}

/// Opens the quick-start wizard (first run, when `quickStartCompleted` is false).
pub fn set_quick_start_handler(cx: &mut App, f: impl Fn(&mut Window, &mut App) + 'static) {
    hooks(cx).quick_start = Some(Rc::new(f));
}

/// Shows the Modrinth import-confirm dialog; resolves `true` to import. Fallback: a question.
pub fn set_confirm_import_handler(
    cx: &mut App,
    f: impl Fn(ImportConfirm, &mut App) -> Task<bool> + 'static,
) {
    hooks(cx).confirm_import = Some(Rc::new(f));
}

/// Replaces the launch (`LauncherCore.play`). Fallback: the backend's launch with an error
/// dialog on refusal.
pub fn set_play_handler(cx: &mut App, f: impl Fn(PackId, &mut App) + 'static) {
    hooks(cx).play = Some(Rc::new(f));
}

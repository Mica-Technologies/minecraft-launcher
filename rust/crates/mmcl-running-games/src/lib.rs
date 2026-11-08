// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Running Games window and its docked view (Java `RunningGamesWindow`, `GameSessionPane`,
//! `SessionLogView`, `LogTrimPolicy`).
//!
//! One tab per game session: its launch steps while it prepares, then its live log (virtualized,
//! searchable, auto-scrolling), uptime, Stop / Kill, a crash diagnosis card and how it ended.
//! Launching never takes over the main window. The view lives in its own window (default) or
//! docked along the bottom of the main window; closing or collapsing it only hides it.
//!
//! | Module | Java origin | What |
//! |---|---|---|
//! | [`view`] | `RunningGamesWindow` | [`RunningGamesView`] (state + body), [`DockHost`], the separate window, the public API |
//! | [`pane`] | `GameSessionPane` | one game's pane, step rows, crash card, `format_duration` |
//! | [`log_view`] | `SessionLogView` | the virtualized log list with row selection and find highlight |
//! | [`lines`] | `SessionLogView` (static helpers) | `split_lines`, `join_lines`, `find_match`, `match_position`, [`lines::LogLines`] |
//! | [`trim`] | `LogTrimPolicy` | view and capture cap arithmetic |
//! | [`backend`] | (new) | [`GamesBackend`], implemented by `mmcl_engine::Engine` |
//!
//! Wiring (in `mmcl-app`, GUI mode, after `mmcl_ui_kit::init`):
//!
//! ```ignore
//! mmcl_running_games::init(cx, engine.clone());
//! // the shell renders `mmcl_running_games::dock_host(cx)` under its screen and calls
//! // `follow_screen` / `refresh_theme` on every screen change;
//! // the launch flow calls `show_session(cx, id)`;
//! mmcl_running_games::set_navigate_handler(cx, |req, cx| mmcl_ui::shell::navigate_gated(cx, req));
//! mmcl_running_games::set_dock_room_provider(cx, |window_height, window, cx| /* room */ None);
//! ```
//!
//! The view reacts to the sessions by itself: once a game is up it steps aside when "Show
//! console on launch" is off (`hide_unless_preparing`), and a crash shows that game's tab again.
//! [`init`] binds the tab keys (Ctrl+Tab, Ctrl+Shift+Tab, arrows on the tab strip) once.

pub mod backend;
pub mod lines;
pub mod log_view;
pub mod pane;
pub mod trim;
pub mod view;
mod widgets;

use std::rc::Rc;

use gpui::{App, Entity, Global};

pub use backend::{Backend, GamesBackend};
pub use log_view::SessionLogView;
pub use pane::{GameSessionPane, NavigateHandler, format_duration, set_navigate_handler};
pub use view::{
    DOCK_SHARE_MAX, DOCK_SHARE_MIN, DockHost, DockRoomProvider, PhaseReaction, RunningGames,
    RunningGamesView, clamp_share, dock_host, dock_visible, drag_share, follow_screen,
    hide_unless_preparing, phase_reaction, refresh_theme, set_dock_room, set_dock_room_provider,
    set_docked, show_session, show_window, shutdown, tab_index_after,
};

/// The Running Games model handle (the spec's `RunningGamesModel`).
pub type RunningGamesModel = Entity<RunningGamesView>;

/// Installs the Running Games singleton over the engine: listens to its sessions from now on.
/// Opens nothing; the window opens on the first reveal, the dock shows once a game runs.
pub fn init(cx: &mut App, engine: mmcl_engine::Engine) -> RunningGamesModel {
    init_with_backend(cx, Rc::new(engine))
}

/// [`init`] over any backend (tests, alternative front ends).
pub fn init_with_backend(cx: &mut App, backend: Backend) -> RunningGamesModel {
    if cx.try_global::<KeysBound>().is_none() {
        cx.set_global(KeysBound);
        view::bind_keys(cx);
    }
    RunningGames::install(cx, backend)
}

/// Marks the key bindings as installed (an in-process restart calls [`init`] again).
struct KeysBound;

impl Global for KeysBound {}

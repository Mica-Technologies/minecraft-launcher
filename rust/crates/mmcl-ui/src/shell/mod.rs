// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The window shell: the main window, its custom title bar, navigation and its gate, the close
//! guard, the controller and the thread-safe [`GuiHandle`].
//!
//! Screens are not built here: each screen implements [`mmcl_ui_kit::Screen`] and is registered
//! with [`mmcl_ui_kit::ScreenRegistry`] (this crate's [`crate::screens::register_all`] for its
//! own screens, `mmcl-app` for the editor and anything else). Navigation is
//! [`mmcl_ui_kit::Navigator::go_to`] (no gate, for the session flow) or [`navigate_gated`] (for
//! toolbar buttons, shortcuts, menus, tray).
//!
//! Lifecycle on every swap ([`MainWindow::set_screen`]): previous `cleanup` (a panic is logged and
//! never blocks the swap), new `setup`, grow to its minimum / preferred size, window title, theme
//! (native chrome only on a real sheet change), fade-through, then `after_show` and the
//! [`hooks::on_screen_shown`] hooks.

pub mod controller;
pub mod hooks;
pub mod main_window;
pub mod placeholder;
pub mod title_bar;

pub use controller::{
    GuiController, GuiHandle, GuiState, ShellError, UiCommand, confirm_quit_while_games_run,
    confirm_restart_while_games_run, current_help_topic, current_screen, exit, force_theme_refresh,
    go_to, handle, is_started, main_view, main_window_handle, may_leave_for_launch,
    may_navigate_away, navigate_gated, on_close_requested, prebuild_main, request_focus,
    request_quit, start_gui, with_main_window,
};
pub use hooks::{
    MenuToggle, close_app, on_exit, on_screen_shown, on_theme_changed, restart_app,
    set_bottom_dock, set_close_app_handler, set_help_handler, set_menu_toggle_handler,
    set_play_recent_handler, set_restart_handler, show_help,
};
pub use main_window::MainWindow;
pub use title_bar::{AppTitleBar, help_button};

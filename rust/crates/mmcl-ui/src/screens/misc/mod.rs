// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Progress screens and the help window (gui-shell spec sections 8 and 9):
//!
//! | Module | Java | What |
//! |---|---|---|
//! | [`progress`] | `MCLauncherProgressGui` | three-label progress screen + [`ProgressHandle`] |
//! | [`launch_progress`] | `MCLauncherLaunchProgressGui` | step-list screen + [`LaunchProgressHandle`] |
//! | [`visuals`] | `BlockyHorizon`, voxel hop, `progressGlow`, `progressCard` | shared backdrop |
//! | [`help`] | `MCLauncherHelpWindow` | singleton help window (HTML -> Markdown -> `TextView`) |
//!
//! [`register`] registers both screens and installs the help window as the shell's help opener
//! (title-bar help button, F1, Help menu).

use gpui::App;
use mmcl_ui_kit::{HelpTopic, ScreenId};

use super::placeholder::ScreenSpec;

pub mod help;
pub mod launch_progress;
pub mod progress;
pub mod visuals;

pub use launch_progress::{
    LaunchProgressHandle, LaunchProgressScreen, go_to_launch_progress, show_launch_progress,
};
pub use progress::{
    CancelHandler, Progress, ProgressHandle, ProgressScreen, go_to_progress, show_progress,
};

/// The screens this module provides (their identities; kept for the shell's tests).
pub const SPECS: [ScreenSpec; 2] = [
    ScreenSpec {
        id: ScreenId::PROGRESS,
        title_key: "window.title.loading",
        help: HelpTopic::GettingStarted,
        allows_navigation: false,
        min: (750.0, 475.0),
        pref: None,
    },
    ScreenSpec {
        id: ScreenId::LAUNCH_PROGRESS,
        title_key: "window.title.launching",
        help: HelpTopic::GettingStarted,
        allows_navigation: false,
        min: (750.0, 475.0),
        pref: None,
    },
];

/// Registers the progress screens and installs the help window.
pub fn register(cx: &mut App) {
    progress::register(cx);
    launch_progress::register(cx);
    help::install(cx);
}

/// Opens help on `topic` (`HelpWindow.show`).
pub fn show_help(cx: &mut App, topic: HelpTopic) {
    help::show(topic, cx);
}

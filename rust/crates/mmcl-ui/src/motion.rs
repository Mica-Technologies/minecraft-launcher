// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Motion (`Motion`): the kit's transitions, re-exported for screen code, plus the settings
//! hookup (reduce motion, the wavy progress style).
//!
//! - `element.enter(id)`: dialogs, modals (300 ms fade, emphasized decelerate).
//! - `element.exit(id)` + [`after_exit`]: 150 ms fade out, then the follow-up.
//! - `element.fade_through(id)`: 220 ms fade + 8 px rise; the shell runs it on every screen swap
//!   and Settings uses it on every category switch. Key the id on a counter to replay it.
//!
//! Reduce motion snaps every transition to rest and stops ambient animation.

use gpui::App;

pub use mmcl_ui_kit::MotionExt;
pub use mmcl_ui_kit::motion::{
    EMPHASIZED_ACCELERATE, EMPHASIZED_DECELERATE, FADE_THROUGH, LONG, MEDIUM, SHORT, STANDARD,
    after_exit, apply_reduce_motion, clock_seconds, cubic_bezier, easing, is_reduced,
    request_frame,
};

/// Applies the motion settings (start-up and after Settings saves them).
pub fn apply_settings(cx: &mut App, reduce_motion: bool, wavy_progress: bool) {
    apply_reduce_motion(cx, reduce_motion);
    mmcl_ui_kit::wavy::apply_wavy_enabled(cx, wavy_progress);
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Interface scale (`UiScale`): the kit's rem scaling plus the shell's re-apply of the main
//! window's minimum size on every change (guarded bug 13).
//!
//! Author every layout length with [`u`] (px at 100 %) so it follows the scale. Native-size
//! chrome (title bar, caption buttons, traffic-light inset) stays in `px`.

use gpui::App;

pub use mmcl_ui_kit::scale::{
    DEFAULT_PERCENT, OFFERED, SmoothScroll, UiScale, apply_to_window, nearest, percent, rem_size,
    scaled_px, u,
};

/// Sets the interface scale (snapped to an offered value), refreshes every window and
/// re-applies the main window's minimum size for the new scale.
pub fn set_ui_scale(cx: &mut App, percent: i32) {
    mmcl_ui_kit::scale::set_percent(cx, percent);
    if let (Some(handle), Some(main)) = (
        crate::shell::main_window_handle(cx),
        crate::shell::main_view(cx),
    ) {
        let _ = handle.update(cx, |_, window, cx| {
            main.update(cx, |m, cx| m.apply_min_size(window, cx));
        });
    }
}

/// The scale factor (1.0 at 100 %).
pub fn factor(cx: &App) -> f32 {
    percent(cx) as f32 / 100.0
}

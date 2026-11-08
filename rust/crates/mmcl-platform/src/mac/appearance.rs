// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The NSWindow appearance (`MacOsVibrancyManager` title-bar part).
//!
//! **Guards:** DarkAqua/Aqua, not VibrantDark/VibrantLight (which do not theme a window
//! frame), and set on the NSWindow, not the content view (the title bar reads the window's
//! appearance). Vibrancy itself is GPUI's `WindowBackgroundAppearance::Blurred`.

use objc2::MainThreadMarker;
use objc2_app_kit::{
    NSAppearance, NSAppearanceCustomization, NSAppearanceNameAqua, NSAppearanceNameDarkAqua,
    NSView, NSWindow,
};

use objc2::rc::Retained;

/// The NSWindow owning the content view at `ns_view`. Main thread only.
fn window_of(ns_view: usize, _mtm: MainThreadMarker) -> Option<Retained<NSWindow>> {
    if ns_view == 0 {
        return None;
    }
    // SAFETY: `ns_view` comes from a live `AppKitWindowHandle` of a window the caller holds,
    // and we are on the main thread.
    let view: &NSView = unsafe { &*(ns_view as *const NSView) };
    view.window()
}

fn set(ns_view: usize, appearance: Option<Retained<NSAppearance>>) -> bool {
    let Some(mtm) = MainThreadMarker::new() else {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.platform.notMainThread", "NSWindow.setAppearance")
        );
        return false;
    };
    crate::guard(|| {
        let Some(window) = window_of(ns_view, mtm) else {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!(
                    "log.macVibrancy.appearanceSetFailed",
                    "no NSWindow for the view"
                )
            );
            return false;
        };
        window.setAppearance(appearance.as_deref());
        true
    })
}

/// Sets DarkAqua (`dark`) or Aqua on the window of `ns_view`.
pub fn apply(ns_view: usize, dark: bool) -> bool {
    // SAFETY: AppKit's appearance name constants are immutable statics.
    let name = unsafe {
        if dark {
            NSAppearanceNameDarkAqua
        } else {
            NSAppearanceNameAqua
        }
    };
    let Some(appearance) = NSAppearance::appearanceNamed(name) else {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.macVibrancy.appearanceSetFailed", name.to_string())
        );
        return false;
    };
    set(ns_view, Some(appearance))
}

/// Clears the window appearance so it follows the OS again.
pub fn clear(ns_view: usize) -> bool {
    set(ns_view, None)
}

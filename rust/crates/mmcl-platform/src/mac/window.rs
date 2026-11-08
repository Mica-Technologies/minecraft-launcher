// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! NSWindow style changes on an open window (`Stage.setResizable`).

use objc2::MainThreadMarker;
use objc2_app_kit::{NSView, NSWindowStyleMask};

/// Adds or removes `NSWindowStyleMaskResizable` on the window of `ns_view` (also greys the
/// green zoom button). Main thread only.
pub fn set_resizable(ns_view: usize, resizable: bool) -> bool {
    if ns_view == 0 {
        return false;
    }
    let Some(_mtm) = MainThreadMarker::new() else {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.platform.notMainThread", "NSWindow.setStyleMask")
        );
        return false;
    };
    crate::guard(|| {
        // SAFETY: `ns_view` comes from a live `AppKitWindowHandle` of a window the caller holds,
        // and we are on the main thread.
        let view: &NSView = unsafe { &*(ns_view as *const NSView) };
        let Some(window) = view.window() else {
            return false;
        };
        let mask = window.styleMask();
        let next = if resizable {
            mask | NSWindowStyleMask::Resizable
        } else {
            mask & !NSWindowStyleMask::Resizable
        };
        if next != mask {
            window.setStyleMask(next);
        }
        true
    })
}

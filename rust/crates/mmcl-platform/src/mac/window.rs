// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! NSWindow changes on an open window: resizable (`Stage.setResizable`), owned child windows
//! and window-modal sheets (`Stage.initOwner` / `initModality`), and the content minimum size.

use objc2::MainThreadMarker;
use objc2::rc::Retained;
use objc2_app_kit::{NSView, NSWindow, NSWindowOrderingMode, NSWindowStyleMask};
use objc2_foundation::NSSize;

use crate::window::{LogicalSize, grow_to_min};

/// The NSWindow of a content view pointer, retained. Main thread only.
fn window_of(ns_view: usize, _mtm: MainThreadMarker) -> Option<Retained<NSWindow>> {
    if ns_view == 0 {
        return None;
    }
    // SAFETY: `ns_view` comes from a live `AppKitWindowHandle` of a window the caller holds,
    // and we are on the main thread (the marker proves it).
    let view: &NSView = unsafe { &*(ns_view as *const NSView) };
    view.window()
}

/// Adds or removes `NSWindowStyleMaskResizable` on the window of `ns_view` (also greys the
/// green zoom button). Main thread only.
pub fn set_resizable(ns_view: usize, resizable: bool) -> bool {
    if ns_view == 0 {
        return false;
    }
    let Some(mtm) = MainThreadMarker::new() else {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.platform.notMainThread", "NSWindow.setStyleMask")
        );
        return false;
    };
    crate::guard(|| {
        let Some(window) = window_of(ns_view, mtm) else {
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

/// A child-window or sheet relationship; holds both windows retained so undoing it is safe
/// even after GPUI has closed the child.
pub struct Link {
    child: Retained<NSWindow>,
    owner: Retained<NSWindow>,
    sheet: bool,
}

impl Link {
    /// Whether the child is attached as the owner's sheet (window-modal).
    pub fn is_sheet(&self) -> bool {
        self.sheet
    }
}

/// Attaches the window of `child_view` to the window of `owner_view`: as a window-modal sheet
/// when `sheet` (and the owner has no sheet yet), else as a child window ordered above the
/// owner (moves with it, hides when it is minimised). Main thread only.
pub fn link(child_view: usize, owner_view: usize, sheet: bool) -> Option<Link> {
    let mtm = MainThreadMarker::new()?;
    crate::guard(|| {
        let child = window_of(child_view, mtm)?;
        let owner = window_of(owner_view, mtm)?;
        if Retained::as_ptr(&child) == Retained::as_ptr(&owner) {
            return None;
        }
        let sheet = sheet && owner.attachedSheet().is_none();
        if sheet {
            owner.beginSheet_completionHandler(&child, None);
        } else {
            // SAFETY: both windows are live and retained, on the main thread.
            unsafe { owner.addChildWindow_ordered(&child, NSWindowOrderingMode::Above) };
        }
        Some(Link {
            child,
            owner,
            sheet,
        })
    })
}

/// Undoes [`link`]: ends the sheet or detaches the child window. Main thread only (a call from
/// another thread is dropped, which is harmless for a closed child).
pub fn unlink(link: Link) {
    if MainThreadMarker::new().is_none() {
        return;
    }
    crate::guard(|| {
        if link.sheet {
            link.owner.endSheet(&link.child);
        } else {
            link.owner.removeChildWindow(&link.child);
        }
    });
}

/// Sets (or with `None` resets to zero) the content minimum size of the window of `ns_view`,
/// in points, growing the window when its content is smaller. Main thread only.
pub fn set_min_size(ns_view: usize, min: Option<LogicalSize>) -> bool {
    let Some(mtm) = MainThreadMarker::new() else {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.platform.notMainThread", "NSWindow.setContentMinSize")
        );
        return false;
    };
    crate::guard(|| {
        let Some(window) = window_of(ns_view, mtm) else {
            return false;
        };
        let size = min.unwrap_or(LogicalSize::new(0.0, 0.0));
        window.setContentMinSize(NSSize::new(size.width.max(0.0), size.height.max(0.0)));
        if min.is_some() && !window.isZoomed() {
            let content = window.contentRectForFrameRect(window.frame()).size;
            let current = (content.width.ceil() as i32, content.height.ceil() as i32);
            if let Some((w, h)) = grow_to_min(current, size.to_physical(1.0)) {
                window.setContentSize(NSSize::new(f64::from(w), f64::from(h)));
            }
        }
        true
    })
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Secondary-window relationships GPUI 0.3.11 does not offer: owned and modal windows
//! (`Stage.initOwner` / `initModality`) and a minimum window size that can change while the
//! window is open (`Stage.setMinWidth` / `setMinHeight` following the interface scale).
//!
//! | | Owned | Modal | Runtime minimum |
//! |---|---|---|---|
//! | Windows | `GWLP_HWNDPARENT` | + `EnableWindow(owner, FALSE)` | `WM_GETMINMAXINFO` subclass |
//! | macOS | `addChildWindow:ordered:` | `beginSheet:` (a window-modal sheet) | `setContentMinSize:` |
//! | X11 | `WM_TRANSIENT_FOR` | + `_NET_WM_STATE_MODAL` | `WM_NORMAL_HINTS` min size |
//! | Wayland | not reachable (GPUI owns the `xdg_toplevel`) | no | no |
//!
//! Everything is best effort and never panics; a call that cannot work on this platform
//! returns `false`. Calls must come from the thread that owns the windows (GPUI's main thread).

use std::collections::HashMap;
use std::marker::PhantomData;
use std::sync::LazyLock;

use parking_lot::Mutex;

use crate::handle::NativeHandle;

/// A size in logical (scale-independent) pixels.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LogicalSize {
    /// Width.
    pub width: f64,
    /// Height.
    pub height: f64,
}

impl LogicalSize {
    /// A size.
    pub const fn new(width: f64, height: f64) -> LogicalSize {
        LogicalSize { width, height }
    }

    /// The size in device pixels at `scale` (1.0 = 96 DPI / non-Retina), rounded up so the
    /// content never ends up a pixel short. Negative or non-finite inputs give 0.
    pub fn to_physical(self, scale: f64) -> (i32, i32) {
        let scale = if scale.is_finite() && scale > 0.0 {
            scale
        } else {
            1.0
        };
        let dim = |v: f64| {
            if v.is_finite() && v > 0.0 {
                (v * scale).ceil().min(i32::MAX as f64) as i32
            } else {
                0
            }
        };
        (dim(self.width), dim(self.height))
    }
}

/// The size a window must grow to so it is at least `min`, or `None` when it already is.
pub fn grow_to_min(current: (i32, i32), min: (i32, i32)) -> Option<(i32, i32)> {
    let next = (current.0.max(min.0), current.1.max(min.1));
    (next != current).then_some(next)
}

/// A stable key for a native window, for the per-owner modal ledger.
fn handle_key(h: NativeHandle) -> Option<u64> {
    match h {
        NativeHandle::Win32 { hwnd } => Some(hwnd as u64),
        NativeHandle::AppKit { ns_view } => Some(ns_view as u64),
        NativeHandle::X11 { window } => Some(window),
        NativeHandle::Wayland { surface } => Some(surface as u64),
        NativeHandle::Unsupported => None,
    }
}

/// How many modal children each owner has open, so a second modal child does not re-enable
/// its owner when the first one closes.
#[derive(Debug, Default)]
pub(crate) struct ModalLedger {
    depth: HashMap<u64, u32>,
}

#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
impl ModalLedger {
    /// Counts a modal child of `owner`; `true` when it is the first (disable the owner now).
    pub(crate) fn begin(&mut self, owner: u64) -> bool {
        let d = self.depth.entry(owner).or_insert(0);
        *d += 1;
        *d == 1
    }

    /// Uncounts one; `true` when it was the last (re-enable the owner now). Unbalanced calls
    /// are ignored.
    pub(crate) fn end(&mut self, owner: u64) -> bool {
        match self.depth.get_mut(&owner) {
            Some(d) if *d > 1 => {
                *d -= 1;
                false
            }
            Some(_) => {
                self.depth.remove(&owner);
                true
            }
            None => false,
        }
    }

    /// Open modal children of `owner`.
    #[cfg(test)]
    pub(crate) fn depth(&self, owner: u64) -> u32 {
        self.depth.get(&owner).copied().unwrap_or(0)
    }
}

#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
static LEDGER: LazyLock<Mutex<ModalLedger>> = LazyLock::new(|| Mutex::new(ModalLedger::default()));

/// A live owner relationship between a secondary window and its owner (and, when modal, the
/// owner's disabled state). Undo it with [`WindowLink::release`] **before** the child closes:
/// on Windows a disabled owner that is re-enabled only after its modal child is destroyed loses
/// the activation to another application. Dropping the link releases it too, as a fallback.
///
/// Not `Send`: it holds AppKit objects on macOS and must stay on the main thread everywhere.
#[must_use = "dropping the link releases the owner relationship at once"]
pub struct WindowLink {
    child: NativeHandle,
    owner: NativeHandle,
    modal: bool,
    active: bool,
    #[cfg(target_os = "macos")]
    mac: Option<crate::mac::window::Link>,
    _not_send: PhantomData<*const ()>,
}

impl std::fmt::Debug for WindowLink {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("WindowLink")
            .field("child", &self.child)
            .field("owner", &self.owner)
            .field("modal", &self.modal)
            .field("active", &self.active)
            .finish()
    }
}

impl WindowLink {
    /// Makes `child` owned by `owner` (stays above it, minimises with it) and, with `modal`,
    /// blocks input to `owner` while the link lives. A link that could not be made is inert
    /// ([`WindowLink::is_active`] is `false`).
    pub fn new(child: NativeHandle, owner: NativeHandle, modal: bool) -> WindowLink {
        let mut link = WindowLink {
            child,
            owner,
            modal,
            active: false,
            #[cfg(target_os = "macos")]
            mac: None,
            _not_send: PhantomData,
        };
        if child == owner || handle_key(child).is_none() || handle_key(owner).is_none() {
            return link;
        }
        link.active = link.attach();
        if !link.active {
            tracing::debug!(
                "{}",
                mmcl_i18n::tf!("log.window.ownerFailed", format!("{child:?} -> {owner:?}"))
            );
        }
        link
    }

    /// An inert link (for code paths without an owner).
    pub fn none() -> WindowLink {
        WindowLink {
            child: NativeHandle::Unsupported,
            owner: NativeHandle::Unsupported,
            modal: false,
            active: false,
            #[cfg(target_os = "macos")]
            mac: None,
            _not_send: PhantomData,
        }
    }

    /// Whether the relationship is in place.
    pub fn is_active(&self) -> bool {
        self.active
    }

    /// Whether the link was asked to be modal.
    pub fn is_modal(&self) -> bool {
        self.modal
    }

    /// The owner's handle.
    pub fn owner(&self) -> NativeHandle {
        self.owner
    }

    /// Undoes the relationship (re-enables a modal owner and gives it the focus back). Safe to
    /// call more than once.
    pub fn release(&mut self) {
        if !self.active {
            return;
        }
        self.active = false;
        self.detach();
    }

    #[cfg(windows)]
    fn attach(&mut self) -> bool {
        let (NativeHandle::Win32 { hwnd: c }, NativeHandle::Win32 { hwnd: o }) =
            (self.child, self.owner)
        else {
            return false;
        };
        if !crate::win::set_owner(c, Some(o)) {
            return false;
        }
        if self.modal && LEDGER.lock().begin(o as u64) {
            crate::win::window::set_enabled(o, false);
        }
        true
    }

    #[cfg(windows)]
    fn detach(&mut self) {
        let (NativeHandle::Win32 { hwnd: c }, NativeHandle::Win32 { hwnd: o }) =
            (self.child, self.owner)
        else {
            return;
        };
        if self.modal && LEDGER.lock().end(o as u64) {
            crate::win::window::set_enabled(o, true);
            crate::win::window::reactivate_owner(c, o);
        }
        if crate::win::window::is_window(c) {
            crate::win::set_owner(c, None);
        }
    }

    #[cfg(target_os = "macos")]
    fn attach(&mut self) -> bool {
        let (NativeHandle::AppKit { ns_view: c }, NativeHandle::AppKit { ns_view: o }) =
            (self.child, self.owner)
        else {
            return false;
        };
        // A window shows one sheet at a time: a second modal child becomes a child window.
        let sheet = self.modal && LEDGER.lock().begin(o as u64);
        match crate::mac::window::link(c, o, sheet) {
            Some(link) => {
                self.mac = Some(link);
                true
            }
            None => {
                if sheet {
                    LEDGER.lock().end(o as u64);
                }
                false
            }
        }
    }

    #[cfg(target_os = "macos")]
    fn detach(&mut self) {
        if let Some(link) = self.mac.take() {
            if link.is_sheet()
                && let NativeHandle::AppKit { ns_view: o } = self.owner
            {
                LEDGER.lock().end(o as u64);
            }
            crate::mac::window::unlink(link);
        }
    }

    #[cfg(target_os = "linux")]
    fn attach(&mut self) -> bool {
        let (NativeHandle::X11 { window: c }, NativeHandle::X11 { window: o }) =
            (self.child, self.owner)
        else {
            // Wayland: xdg_toplevel.set_parent needs GPUI's toplevel proxy, which raw handles
            // do not reach.
            return false;
        };
        let (Ok(c), Ok(o)) = (u32::try_from(c), u32::try_from(o)) else {
            return false;
        };
        if !crate::linux::x11::set_transient_for(c, Some(o)) {
            return false;
        }
        if self.modal {
            crate::linux::x11::set_modal_state(c, true);
        }
        true
    }

    #[cfg(target_os = "linux")]
    fn detach(&mut self) {
        if let NativeHandle::X11 { window: c } = self.child
            && let Ok(c) = u32::try_from(c)
        {
            if self.modal {
                crate::linux::x11::set_modal_state(c, false);
            }
            crate::linux::x11::set_transient_for(c, None);
        }
    }

    #[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
    fn attach(&mut self) -> bool {
        false
    }

    #[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
    fn detach(&mut self) {}
}

impl Drop for WindowLink {
    fn drop(&mut self) {
        crate::guard(|| self.release());
    }
}

/// Sets (`Some`) or clears (`None`) the minimum **content** size of an open window, in logical
/// pixels; `scale` is the window's device scale factor (used where the platform takes device
/// pixels: X11). The window grows at once when it is smaller than the new minimum (unless it
/// is maximised). This value replaces GPUI's `window_min_size` while set.
pub fn set_min_content_size(window: NativeHandle, min: Option<LogicalSize>, scale: f64) -> bool {
    let ok = match window {
        #[cfg(windows)]
        NativeHandle::Win32 { hwnd } => crate::win::window::set_min_size(hwnd, min),
        #[cfg(target_os = "macos")]
        NativeHandle::AppKit { ns_view } => crate::mac::window::set_min_size(ns_view, min),
        #[cfg(target_os = "linux")]
        NativeHandle::X11 { window } => u32::try_from(window)
            .is_ok_and(|w| crate::linux::x11::set_min_size(w, min.map(|m| m.to_physical(scale)))),
        _ => false,
    };
    let _ = scale;
    if !ok {
        tracing::debug!(
            "{}",
            mmcl_i18n::tf!("log.window.minSizeFailed", format!("{window:?}"))
        );
    }
    ok
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn logical_sizes_round_up_to_device_pixels() {
        assert_eq!(LogicalSize::new(800.0, 600.0).to_physical(1.0), (800, 600));
        assert_eq!(LogicalSize::new(800.0, 600.0).to_physical(1.5), (1200, 900));
        assert_eq!(LogicalSize::new(100.2, 50.0).to_physical(1.25), (126, 63));
        assert_eq!(LogicalSize::new(-5.0, f64::NAN).to_physical(2.0), (0, 0));
        assert_eq!(LogicalSize::new(10.0, 10.0).to_physical(0.0), (10, 10));
        assert_eq!(LogicalSize::new(10.0, 10.0).to_physical(f64::NAN), (10, 10));
    }

    #[test]
    fn windows_only_grow_where_they_are_too_small() {
        assert_eq!(grow_to_min((800, 600), (640, 480)), None);
        assert_eq!(grow_to_min((500, 600), (640, 480)), Some((640, 600)));
        assert_eq!(grow_to_min((500, 400), (640, 480)), Some((640, 480)));
        assert_eq!(grow_to_min((640, 480), (640, 480)), None);
    }

    #[test]
    fn the_ledger_disables_on_the_first_and_enables_on_the_last_modal() {
        let mut l = ModalLedger::default();
        assert!(l.begin(7), "first modal child disables the owner");
        assert!(!l.begin(7), "second does not");
        assert_eq!(l.depth(7), 2);
        assert!(!l.end(7), "owner stays disabled while one is open");
        assert!(l.end(7), "last one re-enables");
        assert!(!l.end(7), "unbalanced end is ignored");
        assert_eq!(l.depth(7), 0);
        assert!(l.begin(8));
        assert_eq!(l.depth(7), 0, "owners are independent");
    }

    #[test]
    fn unsupported_or_self_links_are_inert() {
        let mut link = WindowLink::new(NativeHandle::Unsupported, NativeHandle::Unsupported, true);
        assert!(!link.is_active());
        link.release();
        let h = NativeHandle::X11 { window: 5 };
        let link = WindowLink::new(h, h, false);
        assert!(!link.is_active(), "a window cannot own itself");
        assert!(!WindowLink::none().is_active());
        assert!(!set_min_content_size(
            NativeHandle::Unsupported,
            Some(LogicalSize::new(1.0, 1.0)),
            1.0
        ));
    }

    #[test]
    fn handle_keys_cover_every_real_handle() {
        assert_eq!(handle_key(NativeHandle::Win32 { hwnd: 9 }), Some(9));
        assert_eq!(handle_key(NativeHandle::X11 { window: 3 }), Some(3));
        assert_eq!(handle_key(NativeHandle::Unsupported), None);
    }
}

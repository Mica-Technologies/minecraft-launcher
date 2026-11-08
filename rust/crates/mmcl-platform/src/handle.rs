// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Native handles from a `raw-window-handle` 0.6 window (a `gpui::Window`, a `tao` window, ...).
//!
//! On Windows the handle is the HWND; on macOS it is the window's content `NSView` (the window
//! is `[ns_view window]`); on Linux an X11 window id or a Wayland surface. Only the window
//! handle is read: some back ends do not implement the display handle on every OS.

use raw_window_handle::{HasWindowHandle, RawWindowHandle};

/// The native window behind a `HasWindowHandle`, reduced to plain values that are `Send`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NativeHandle {
    /// A Win32 `HWND`.
    Win32 { hwnd: isize },
    /// An AppKit `NSView*` (the window's content view).
    AppKit { ns_view: usize },
    /// An X11 window id (Xlib or XCB).
    X11 { window: u64 },
    /// A Wayland `wl_surface*`.
    Wayland { surface: usize },
    /// No handle, or one of a kind this crate does not use.
    Unsupported,
}

/// The native handle of `window`, or [`NativeHandle::Unsupported`] when it has none.
pub fn native_handle<W: HasWindowHandle + ?Sized>(window: &W) -> NativeHandle {
    match window.window_handle() {
        Ok(h) => from_raw(h.as_raw()),
        Err(_) => NativeHandle::Unsupported,
    }
}

/// Converts a raw handle.
pub fn from_raw(raw: RawWindowHandle) -> NativeHandle {
    match raw {
        RawWindowHandle::Win32(h) => NativeHandle::Win32 { hwnd: h.hwnd.get() },
        RawWindowHandle::AppKit(h) => NativeHandle::AppKit {
            ns_view: h.ns_view.as_ptr() as usize,
        },
        // `c_ulong` is 64-bit on Linux and 32-bit on Windows.
        #[allow(clippy::unnecessary_cast)]
        RawWindowHandle::Xlib(h) => NativeHandle::X11 {
            window: h.window as u64,
        },
        RawWindowHandle::Xcb(h) => NativeHandle::X11 {
            window: u64::from(h.window.get()),
        },
        RawWindowHandle::Wayland(h) => NativeHandle::Wayland {
            surface: h.surface.as_ptr() as usize,
        },
        _ => NativeHandle::Unsupported,
    }
}

/// The HWND of `window`, if it is a Win32 window.
pub fn hwnd_of<W: HasWindowHandle + ?Sized>(window: &W) -> Option<isize> {
    match native_handle(window) {
        NativeHandle::Win32 { hwnd } => Some(hwnd),
        _ => None,
    }
}

/// The content `NSView*` of `window`, if it is an AppKit window.
pub fn ns_view_of<W: HasWindowHandle + ?Sized>(window: &W) -> Option<usize> {
    match native_handle(window) {
        NativeHandle::AppKit { ns_view } => Some(ns_view),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use raw_window_handle::{
        AppKitWindowHandle, HandleError, Win32WindowHandle, WindowHandle, XcbWindowHandle,
    };
    use std::num::{NonZeroIsize, NonZeroU32};
    use std::ptr::NonNull;

    struct Fake(Option<RawWindowHandle>);

    impl HasWindowHandle for Fake {
        fn window_handle(&self) -> Result<WindowHandle<'_>, HandleError> {
            match self.0 {
                // SAFETY: the test never dereferences the handle.
                Some(raw) => Ok(unsafe { WindowHandle::borrow_raw(raw) }),
                None => Err(HandleError::Unavailable),
            }
        }
    }

    #[test]
    fn win32_handles_give_the_hwnd() {
        let raw = Win32WindowHandle::new(NonZeroIsize::new(0x1234).unwrap()).into();
        assert_eq!(hwnd_of(&Fake(Some(raw))), Some(0x1234));
        assert_eq!(ns_view_of(&Fake(Some(raw))), None);
    }

    #[test]
    fn appkit_and_xcb_handles_convert() {
        let view = NonNull::<std::ffi::c_void>::dangling();
        let raw = AppKitWindowHandle::new(view).into();
        assert_eq!(ns_view_of(&Fake(Some(raw))), Some(view.as_ptr() as usize));
        let raw = XcbWindowHandle::new(NonZeroU32::new(77).unwrap()).into();
        assert_eq!(
            native_handle(&Fake(Some(raw))),
            NativeHandle::X11 { window: 77 }
        );
    }

    #[test]
    fn a_missing_handle_is_unsupported() {
        assert_eq!(native_handle(&Fake(None)), NativeHandle::Unsupported);
        assert_eq!(hwnd_of(&Fake(None)), None);
    }
}

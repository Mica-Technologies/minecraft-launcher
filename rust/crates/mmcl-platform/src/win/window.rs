// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Owner enable/disable for modal windows and the runtime minimum size, via a
//! `WM_GETMINMAXINFO` window subclass (`SetWindowSubclass`, comctl32 v6).
//!
//! The subclass sees every message before GPUI's window procedure. For `WM_GETMINMAXINFO` it
//! lets GPUI fill the structure first and then writes our minimum over it, so the value set
//! here replaces GPUI's creation-time `window_min_size`.

use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::LazyLock;

use parking_lot::Mutex;
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, RECT, WPARAM};
use windows::Win32::UI::HiDpi::GetDpiForWindow;
use windows::Win32::UI::Input::KeyboardAndMouse::EnableWindow;
use windows::Win32::UI::Shell::{DefSubclassProc, RemoveWindowSubclass, SetWindowSubclass};
use windows::Win32::UI::WindowsAndMessaging::{
    GetClientRect, GetForegroundWindow, GetWindowRect, GetWindowThreadProcessId, IsIconic,
    IsWindow, IsZoomed, MINMAXINFO, SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOOWNERZORDER, SWP_NOZORDER,
    SetForegroundWindow, SetWindowPos, WM_GETMINMAXINFO, WM_NCDESTROY,
};

use super::hwnd;
use crate::window::{LogicalSize, grow_to_min};

/// Our subclass id (any constant unique to this procedure).
const SUBCLASS_ID: usize = 0x4D4D_434C; // "MMCL"

/// The logical minimum content size per HWND.
static MIN_SIZES: LazyLock<Mutex<HashMap<isize, LogicalSize>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// Enables or disables mouse and keyboard input to `h` (the owner of a modal window).
pub fn set_enabled(h: isize, enabled: bool) -> bool {
    crate::guard(|| unsafe {
        let _ = EnableWindow(hwnd(h), enabled);
        true
    })
}

/// Whether `h` is still a window.
pub fn is_window(h: isize) -> bool {
    crate::guard(|| unsafe { IsWindow(Some(hwnd(h))).as_bool() })
}

/// After a modal child closes: gives `owner` the foreground back when the child is gone and
/// the foreground is not a window of this process (Windows hands it to another app when the
/// owner was still disabled at the moment the child was destroyed).
pub fn reactivate_owner(child: isize, owner: isize) {
    crate::guard(|| unsafe {
        if IsWindow(Some(hwnd(child))).as_bool() {
            // Still open: destroying an owned window activates its (now enabled) owner itself.
            return;
        }
        let fg = GetForegroundWindow();
        let ours = !fg.0.is_null() && {
            let mut pid = 0u32;
            GetWindowThreadProcessId(fg, Some(&mut pid));
            pid == std::process::id()
        };
        if !ours {
            let _ = SetForegroundWindow(hwnd(owner));
        }
    });
}

/// The `ptMinTrackSize` for a logical minimum content size: the content in device pixels at
/// `dpi` plus the non-client frame (`frame` = window size - client size). Pure.
pub(crate) fn min_track_size(min: LogicalSize, dpi: u32, frame: (i32, i32)) -> (i32, i32) {
    let dpi = if dpi == 0 { 96 } else { dpi };
    let (w, h) = min.to_physical(f64::from(dpi) / 96.0);
    (
        w.saturating_add(frame.0.max(0)),
        h.saturating_add(frame.1.max(0)),
    )
}

/// The frame size (window rect minus client rect) of `w`.
unsafe fn frame_extra(w: HWND) -> Option<((i32, i32), (i32, i32))> {
    let mut wr = RECT::default();
    let mut cr = RECT::default();
    unsafe {
        GetWindowRect(w, &mut wr).ok()?;
        GetClientRect(w, &mut cr).ok()?;
    }
    let window = (wr.right - wr.left, wr.bottom - wr.top);
    let client = (cr.right - cr.left, cr.bottom - cr.top);
    Some((window, (window.0 - client.0, window.1 - client.1)))
}

unsafe extern "system" fn subclass_proc(
    w: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
    _id: usize,
    _data: usize,
) -> LRESULT {
    let result = unsafe { DefSubclassProc(w, msg, wparam, lparam) };
    match msg {
        WM_GETMINMAXINFO => {
            let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
                let Some(min) = MIN_SIZES.lock().get(&(w.0 as isize)).copied() else {
                    return;
                };
                let Some((_, frame)) = frame_extra(w) else {
                    return;
                };
                let (x, y) = min_track_size(min, GetDpiForWindow(w), frame);
                let info = &mut *(lparam.0 as *mut MINMAXINFO);
                info.ptMinTrackSize.x = x;
                info.ptMinTrackSize.y = y;
            }));
            LRESULT(0)
        }
        WM_NCDESTROY => {
            MIN_SIZES.lock().remove(&(w.0 as isize));
            unsafe {
                let _ = RemoveWindowSubclass(w, Some(subclass_proc), SUBCLASS_ID);
            }
            result
        }
        _ => result,
    }
}

/// Sets or clears the runtime minimum content size of `h` and grows the window when it is
/// smaller than the new minimum (not when maximised or minimised).
pub fn set_min_size(h: isize, min: Option<LogicalSize>) -> bool {
    crate::guard(|| unsafe {
        let w = hwnd(h);
        if !IsWindow(Some(w)).as_bool() {
            return false;
        }
        let Some(min) = min else {
            MIN_SIZES.lock().remove(&h);
            return true;
        };
        MIN_SIZES.lock().insert(h, min);
        // Idempotent: the same procedure + id only updates the reference data.
        if !SetWindowSubclass(w, Some(subclass_proc), SUBCLASS_ID, 0).as_bool() {
            MIN_SIZES.lock().remove(&h);
            return false;
        }
        if IsZoomed(w).as_bool() || IsIconic(w).as_bool() {
            return true;
        }
        if let Some((window, frame)) = frame_extra(w) {
            let track = min_track_size(min, GetDpiForWindow(w), frame);
            if let Some((nw, nh)) = grow_to_min(window, track) {
                let _ = SetWindowPos(
                    w,
                    None,
                    0,
                    0,
                    nw,
                    nh,
                    SWP_NOMOVE | SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_NOACTIVATE,
                );
            }
        }
        true
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn track_size_scales_the_content_and_adds_the_frame() {
        let min = LogicalSize::new(800.0, 600.0);
        assert_eq!(min_track_size(min, 96, (0, 0)), (800, 600));
        assert_eq!(min_track_size(min, 144, (16, 39)), (1216, 939));
        assert_eq!(min_track_size(min, 0, (0, 0)), (800, 600), "0 DPI means 96");
        assert_eq!(min_track_size(min, 96, (-4, -4)), (800, 600));
    }

    #[test]
    fn dead_windows_are_refused() {
        // HWND 0 is never a window.
        assert!(!set_min_size(0, Some(LogicalSize::new(10.0, 10.0))));
        assert!(!is_window(0));
    }
}

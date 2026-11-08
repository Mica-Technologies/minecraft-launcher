// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Windows integration: startup hardening, foreground hand-off, window refresh helpers and the
//! COM/shell pieces in the submodules.

pub mod com;
pub mod dwm;
pub mod jump_list;
pub mod registry;
pub mod shell_link;
pub mod taskbar;
pub mod tray;
pub mod window;

use windows::Win32::Foundation::{HWND, RECT};
use windows::Win32::Graphics::Gdi::{
    RDW_ALLCHILDREN, RDW_ERASE, RDW_FRAME, RDW_INVALIDATE, RDW_UPDATENOW, RedrawWindow,
};
use windows::Win32::System::LibraryLoader::SetDllDirectoryW;
use windows::Win32::UI::HiDpi::{
    DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE, DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2,
    PROCESS_PER_MONITOR_DPI_AWARE, SetProcessDpiAwareness, SetProcessDpiAwarenessContext,
};
use windows::Win32::UI::Shell::{
    SHCNE_ASSOCCHANGED, SHCNF_IDLIST, SHChangeNotify, SetCurrentProcessExplicitAppUserModelID,
};
use windows::Win32::UI::WindowsAndMessaging::{
    ASFW_ANY, AllowSetForegroundWindow, GWL_STYLE, GWLP_HWNDPARENT, GetWindowLongPtrW,
    GetWindowRect, IsIconic, SW_RESTORE, SWP_FRAMECHANGED, SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOSIZE,
    SWP_NOZORDER, SetForegroundWindow, SetWindowLongPtrW, SetWindowPos, ShowWindow, WS_MAXIMIZEBOX,
    WS_THICKFRAME,
};
use windows::core::{HSTRING, w};

/// A raw `isize` HWND as the `windows` type.
pub(crate) fn hwnd(h: isize) -> HWND {
    HWND(h as *mut core::ffi::c_void)
}

/// DLL hardening, DPI awareness and the AppUserModelID, in that order. Call first in `main`.
pub fn early_init(aumid: &str) {
    harden_dll_search();
    enable_per_monitor_v2();
    set_app_user_model_id(aumid);
}

/// `SetDllDirectoryW("")`: takes the current directory out of the DLL search order. `PATH`
/// stays in, so installed RGB vendor SDKs still resolve; `SetDefaultDllDirectories` is
/// deliberately not called (it removes `PATH`). Must run before any `libloading` call.
pub fn harden_dll_search() -> bool {
    match crate::guard(|| Some(unsafe { SetDllDirectoryW(w!("")) })) {
        Some(Ok(())) => true,
        Some(Err(_)) => {
            tracing::warn!("{}", mmcl_i18n::t!("log.dllHardening.returnedFalse"));
            false
        }
        None => {
            tracing::warn!("{}", mmcl_i18n::tf!("log.dllHardening.failed", "panic"));
            false
        }
    }
}

/// Per-monitor-v2 DPI awareness: `SetProcessDpiAwarenessContext(-4)`, then `(-3)`, then
/// `shcore!SetProcessDpiAwareness(2)`. The embedded manifest normally does this already; a
/// failure here (including "already set") is harmless. Must happen before any window.
pub fn enable_per_monitor_v2() -> bool {
    crate::guard(|| unsafe {
        if SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2).is_ok() {
            return true;
        }
        if SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE).is_ok() {
            return true;
        }
        match SetProcessDpiAwareness(PROCESS_PER_MONITOR_DPI_AWARE) {
            Ok(()) => true,
            Err(e) => {
                tracing::debug!(
                    "{}",
                    mmcl_i18n::tf!("log.dpiAwareness.setupFailed", e.to_string())
                );
                false
            }
        }
    })
}

/// `SetCurrentProcessExplicitAppUserModelID`: groups taskbar buttons, toasts and the jump list
/// under this identity's AUMID. The installer's Start-menu shortcut carries the same id.
pub fn set_app_user_model_id(aumid: &str) -> bool {
    let id = HSTRING::from(aumid);
    crate::best_effort("log.platform.aumidFailed", || unsafe {
        SetCurrentProcessExplicitAppUserModelID(&id)
    })
    .is_some()
}

/// `AllowSetForegroundWindow(ASFW_ANY)`: lets the running instance take the foreground when
/// this (user-started) process forwards a request to it.
pub fn allow_any_foreground() {
    crate::guard(|| unsafe {
        let _ = AllowSetForegroundWindow(ASFW_ANY);
    });
}

/// Restores `h` when minimized and brings it to the foreground.
pub fn bring_to_front(h: isize) -> bool {
    crate::guard(|| unsafe {
        let w = hwnd(h);
        if IsIconic(w).as_bool() {
            let _ = ShowWindow(w, SW_RESTORE);
        }
        SetForegroundWindow(w).as_bool()
    })
}

/// `RedrawWindow` with invalidate, erase, frame, all children and update now (`0x0585`).
pub fn force_full_repaint(h: isize) -> bool {
    crate::guard(|| unsafe {
        let ok = RedrawWindow(
            Some(hwnd(h)),
            None,
            None,
            RDW_INVALIDATE | RDW_ERASE | RDW_FRAME | RDW_ALLCHILDREN | RDW_UPDATENOW,
        )
        .as_bool();
        if !ok {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!("log.windowChrome.redrawFailed", "RedrawWindow")
            );
        }
        ok
    })
}

/// `SetWindowPos(SWP_FRAMECHANGED)` at the window's real rectangle (falling back to a no-move,
/// no-size refresh when the rectangle cannot be read).
pub fn force_frame_refresh(h: isize) -> bool {
    crate::guard(|| unsafe {
        let w = hwnd(h);
        let mut r = RECT::default();
        let result = if GetWindowRect(w, &mut r).is_ok() {
            SetWindowPos(
                w,
                None,
                r.left,
                r.top,
                r.right - r.left,
                r.bottom - r.top,
                SWP_FRAMECHANGED | SWP_NOZORDER | SWP_NOACTIVATE,
            )
        } else {
            SetWindowPos(
                w,
                None,
                0,
                0,
                0,
                0,
                SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE,
            )
        };
        match result {
            Ok(()) => true,
            Err(e) => {
                tracing::warn!(
                    "{}",
                    mmcl_i18n::tf!("log.shellRefresh.setWindowPosFailed", e.to_string())
                );
                false
            }
        }
    })
}

/// The `GWL_STYLE` bits that make a window resizable: the sizing border and the maximize box.
pub(crate) const RESIZE_STYLE_BITS: isize = (WS_THICKFRAME.0 | WS_MAXIMIZEBOX.0) as isize;

/// `style` with the resize bits set or cleared (pure).
pub(crate) fn resizable_style(style: isize, resizable: bool) -> isize {
    if resizable {
        style | RESIZE_STYLE_BITS
    } else {
        style & !RESIZE_STYLE_BITS
    }
}

/// Adds or removes `WS_THICKFRAME | WS_MAXIMIZEBOX` and re-asserts the frame
/// (`SWP_FRAMECHANGED`), so the sizing border, edge drags, Aero Snap resizing and
/// double-click maximize follow the setting on an open window.
pub fn set_resizable(h: isize, resizable: bool) -> bool {
    crate::guard(|| unsafe {
        let w = hwnd(h);
        let style = GetWindowLongPtrW(w, GWL_STYLE);
        let next = resizable_style(style, resizable);
        if next != style {
            SetWindowLongPtrW(w, GWL_STYLE, next);
        }
        force_frame_refresh(h)
    })
}

/// Sets (or with `None` clears) the owner window (`GWLP_HWNDPARENT`): an owned window stays
/// above its owner and minimizes with it (`initOwner`).
pub fn set_owner(h: isize, owner: Option<isize>) -> bool {
    crate::guard(|| unsafe {
        SetWindowLongPtrW(hwnd(h), GWLP_HWNDPARENT, owner.unwrap_or(0));
        true
    })
}

/// `SHChangeNotify(SHCNE_ASSOCCHANGED)` so Explorer picks up new associations.
pub fn notify_association_changed() {
    crate::guard(|| unsafe { SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, None, None) });
}

/// `SO_EXCLUSIVEADDRUSE` on a socket before it is bound, as Java's `ServerSocket` does on
/// Windows, so no other process can co-bind the single-instance port with `SO_REUSEADDR`.
pub(crate) fn set_exclusive_addr_use(socket: &socket2::Socket) -> std::io::Result<()> {
    use std::os::windows::io::AsRawSocket;
    use windows::Win32::Networking::WinSock::{
        SO_EXCLUSIVEADDRUSE, SOCKET, SOL_SOCKET, setsockopt,
    };
    let on: i32 = 1;
    let rc = unsafe {
        setsockopt(
            SOCKET(socket.as_raw_socket() as usize),
            SOL_SOCKET,
            SO_EXCLUSIVEADDRUSE,
            Some(&on.to_ne_bytes()),
        )
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

/// UTF-16 copy of `s` into a fixed buffer, truncated and NUL-terminated.
pub(crate) fn copy_wide<const N: usize>(dst: &mut [u16; N], s: &str) {
    let mut i = 0;
    for u in s.encode_utf16() {
        if i + 1 >= N {
            break;
        }
        dst[i] = u;
        i += 1;
    }
    dst[i] = 0;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn copy_wide_truncates_and_terminates() {
        let mut buf = [1u16; 4];
        copy_wide(&mut buf, "abcdef");
        assert_eq!(buf, [b'a' as u16, b'b' as u16, b'c' as u16, 0]);
        let mut buf = [1u16; 4];
        copy_wide(&mut buf, "");
        assert_eq!(buf[0], 0);
    }

    #[test]
    fn resizable_style_toggles_only_the_resize_bits() {
        let base = WS_SYSMENU_BIT | 0x10;
        let on = resizable_style(base, true);
        assert_eq!(on & RESIZE_STYLE_BITS, RESIZE_STYLE_BITS);
        assert_eq!(on & !RESIZE_STYLE_BITS, base);
        let off = resizable_style(on, false);
        assert_eq!(off, base);
        assert_eq!(resizable_style(off, false), base, "idempotent");
    }

    const WS_SYSMENU_BIT: isize = 0x0008_0000;

    #[test]
    fn startup_steps_are_safe_to_repeat() {
        enable_per_monitor_v2();
        allow_any_foreground();
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The notification-area icon and its balloons (`NotificationManager` tray on Windows).
//!
//! A dedicated thread owns a hidden top-level window (message-only windows miss the
//! `TaskbarCreated` broadcast, which re-adds the icon after Explorer restarts) and pumps its
//! messages, so nothing depends on GPUI's loop. Balloons are sent with `NIF_INFO`; Windows 10
//! and later show them as toasts without needing a Start-menu AUMID shortcut.

use std::cell::RefCell;
use std::sync::mpsc::{Receiver, Sender, channel, sync_channel};
use std::thread::JoinHandle;

use parking_lot::Mutex;
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::Graphics::Gdi::{
    BI_RGB, BITMAPINFO, BITMAPINFOHEADER, CreateBitmap, CreateDIBSection, DIB_RGB_COLORS,
    DeleteObject, HGDIOBJ,
};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::Shell::{
    NIF_ICON, NIF_INFO, NIF_MESSAGE, NIF_SHOWTIP, NIF_TIP, NIIF_ERROR, NIIF_INFO, NIIF_WARNING,
    NIM_ADD, NIM_DELETE, NIM_MODIFY, NIM_SETVERSION, NOTIFYICON_VERSION_4, NOTIFYICONDATAW,
    Shell_NotifyIconW,
};
use windows::Win32::UI::WindowsAndMessaging::{
    AppendMenuW, CreateIconIndirect, CreatePopupMenu, CreateWindowExW, DefWindowProcW, DestroyIcon,
    DestroyMenu, DestroyWindow, DispatchMessageW, GetCursorPos, GetMessageW, GetSystemMetrics,
    HICON, HMENU, ICONINFO, MF_GRAYED, MF_POPUP, MF_SEPARATOR, MF_STRING, MSG, PostMessageW,
    PostQuitMessage, RegisterClassW, RegisterWindowMessageW, SM_CXSMICON, SetForegroundWindow,
    TPM_NONOTIFY, TPM_RETURNCMD, TPM_RIGHTBUTTON, TrackPopupMenuEx, TranslateMessage,
    WINDOW_EX_STYLE, WM_CONTEXTMENU, WM_DESTROY, WM_LBUTTONDBLCLK, WM_LBUTTONUP, WM_NULL,
    WM_RBUTTONUP, WM_USER, WNDCLASSW, WS_OVERLAPPED,
};
use windows::core::{HSTRING, PCWSTR, w};

use super::copy_wide;
use crate::error::{PlatformError, Result};
use crate::menus::{MenuAction, MenuEntry};
use crate::notify::Kind;

const WM_TRAY_CALLBACK: u32 = WM_USER + 101;
const WM_TRAY_WAKE: u32 = WM_USER + 102;
const NIN_SELECT: u32 = WM_USER;
const NIN_KEYSELECT: u32 = WM_USER + 1;
const NIN_BALLOONUSERCLICK: u32 = WM_USER + 5;
const ICON_ID: u32 = 1;

enum TrayMsg {
    Balloon {
        kind: Kind,
        title: String,
        body: String,
    },
    Quit,
}

struct Handle {
    hwnd: isize,
    tx: Sender<TrayMsg>,
    thread: Option<JoinHandle<()>>,
}

static TRAY: Mutex<Option<Handle>> = Mutex::new(None);

struct Local {
    rx: Receiver<TrayMsg>,
    icon: HICON,
    taskbar_created: u32,
    actions: Vec<MenuAction>,
}

thread_local! {
    static LOCAL: RefCell<Option<Local>> = const { RefCell::new(None) };
}

/// Creates the tray icon if it does not exist. Logs and gives up on failure.
pub fn ensure() {
    let mut tray = TRAY.lock();
    if tray.is_some() {
        return;
    }
    match start() {
        Ok(h) => *tray = Some(h),
        Err(e) => tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.notification.trayInitFailed", e.to_string())
        ),
    }
}

/// Whether the tray icon exists.
pub fn is_active() -> bool {
    TRAY.lock().is_some()
}

/// Shows a balloon on the tray icon.
pub fn balloon(kind: Kind, title: &str, body: &str) -> Result<()> {
    let tray = TRAY.lock();
    let Some(h) = tray.as_ref() else {
        return Err(PlatformError::Unsupported);
    };
    h.tx.send(TrayMsg::Balloon {
        kind,
        title: title.to_string(),
        body: body.to_string(),
    })
    .map_err(|_| PlatformError::Other("tray thread gone".into()))?;
    wake(h.hwnd)
}

/// Removes the icon and ends the tray thread (idempotent).
pub fn shutdown() {
    let handle = TRAY.lock().take();
    if let Some(mut h) = handle {
        let _ = h.tx.send(TrayMsg::Quit);
        let _ = wake(h.hwnd);
        if let Some(t) = h.thread.take() {
            let _ = t.join();
        }
    }
}

fn wake(hwnd: isize) -> Result<()> {
    unsafe { PostMessageW(Some(super::hwnd(hwnd)), WM_TRAY_WAKE, WPARAM(0), LPARAM(0)) }
        .map_err(|e| PlatformError::hr("PostMessageW", e))
}

fn start() -> Result<Handle> {
    let img = crate::icon::decode(crate::LAUNCHER_ICON_PNG).inspect_err(|_| {
        tracing::warn!("{}", mmcl_i18n::t!("log.notification.trayImageMissing"));
    })?;
    let (tx, rx) = channel::<TrayMsg>();
    let (ready_tx, ready_rx) = sync_channel::<Result<isize>>(1);
    let thread = std::thread::Builder::new()
        .name("mmcl-tray".into())
        .spawn(move || {
            let created = crate::guard(|| Some(create_window(rx, &img)))
                .unwrap_or_else(|| Err(PlatformError::Other("panic".into())));
            let ok = created.is_ok();
            let _ = ready_tx.send(created);
            if ok {
                pump();
            }
        })
        .map_err(PlatformError::Io)?;
    match ready_rx.recv() {
        Ok(Ok(hwnd)) => Ok(Handle {
            hwnd,
            tx,
            thread: Some(thread),
        }),
        Ok(Err(e)) => {
            let _ = thread.join();
            Err(e)
        }
        Err(_) => Err(PlatformError::Other("tray thread ended".into())),
    }
}

fn create_window(rx: Receiver<TrayMsg>, img: &image::RgbaImage) -> Result<isize> {
    unsafe {
        let instance =
            GetModuleHandleW(None).map_err(|e| PlatformError::hr("GetModuleHandleW", e))?;
        let class = w!("MmclTrayWindow");
        let wc = WNDCLASSW {
            lpfnWndProc: Some(wndproc),
            hInstance: instance.into(),
            lpszClassName: class,
            ..Default::default()
        };
        // A second registration (tray recreated after shutdown) fails harmlessly.
        let _ = RegisterClassW(&wc);
        let title = HSTRING::from(crate::tray::tooltip());
        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE(0),
            class,
            &title,
            WS_OVERLAPPED,
            0,
            0,
            0,
            0,
            None,
            None,
            Some(instance.into()),
            None,
        )
        .map_err(|e| PlatformError::hr("CreateWindowExW", e))?;

        let size = GetSystemMetrics(SM_CXSMICON).max(16) as u32;
        let icon = make_icon(&crate::icon::scaled(img, size))?;
        let taskbar_created = RegisterWindowMessageW(w!("TaskbarCreated"));
        LOCAL.with(|l| {
            *l.borrow_mut() = Some(Local {
                rx,
                icon,
                taskbar_created,
                actions: Vec::new(),
            })
        });
        if !add_icon(hwnd, icon) {
            let _ = DestroyWindow(hwnd);
            LOCAL.with(|l| l.borrow_mut().take());
            let _ = DestroyIcon(icon);
            return Err(PlatformError::Other(
                "Shell_NotifyIconW(NIM_ADD) failed".into(),
            ));
        }
        Ok(hwnd.0 as isize)
    }
}

fn pump() {
    let mut msg = MSG::default();
    unsafe {
        while GetMessageW(&mut msg, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
}

fn base_data(hwnd: HWND) -> NOTIFYICONDATAW {
    NOTIFYICONDATAW {
        cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
        hWnd: hwnd,
        uID: ICON_ID,
        ..Default::default()
    }
}

unsafe fn add_icon(hwnd: HWND, icon: HICON) -> bool {
    let mut nid = base_data(hwnd);
    nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP;
    nid.uCallbackMessage = WM_TRAY_CALLBACK;
    nid.hIcon = icon;
    copy_wide(&mut nid.szTip, crate::tray::tooltip());
    unsafe {
        if !Shell_NotifyIconW(NIM_ADD, &nid).as_bool() {
            return false;
        }
        nid.Anonymous.uVersion = NOTIFYICON_VERSION_4;
        let _ = Shell_NotifyIconW(NIM_SETVERSION, &nid);
    }
    true
}

unsafe fn make_icon(img: &image::RgbaImage) -> Result<HICON> {
    let (w, h) = (img.width() as i32, img.height() as i32);
    let bmi = BITMAPINFO {
        bmiHeader: BITMAPINFOHEADER {
            biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
            biWidth: w,
            biHeight: -h, // top-down
            biPlanes: 1,
            biBitCount: 32,
            biCompression: BI_RGB.0,
            ..Default::default()
        },
        ..Default::default()
    };
    unsafe {
        let mut bits: *mut core::ffi::c_void = std::ptr::null_mut();
        let color = CreateDIBSection(None, &bmi, DIB_RGB_COLORS, &mut bits, None, 0)
            .map_err(|e| PlatformError::hr("CreateDIBSection", e))?;
        let bgra = crate::icon::bgra(img);
        std::ptr::copy_nonoverlapping(bgra.as_ptr(), bits.cast::<u8>(), bgra.len());
        let mask = CreateBitmap(w, h, 1, 1, None);
        let info = ICONINFO {
            fIcon: true.into(),
            xHotspot: 0,
            yHotspot: 0,
            hbmMask: mask,
            hbmColor: color,
        };
        let icon = CreateIconIndirect(&info);
        let _ = DeleteObject(HGDIOBJ(color.0));
        let _ = DeleteObject(HGDIOBJ(mask.0));
        icon.map_err(|e| PlatformError::hr("CreateIconIndirect", e))
    }
}

unsafe extern "system" fn wndproc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    let handled = std::panic::catch_unwind(|| handle(hwnd, msg, wparam, lparam)).unwrap_or(None);
    match handled {
        Some(r) => r,
        None => unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) },
    }
}

fn handle(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> Option<LRESULT> {
    match msg {
        WM_TRAY_WAKE => {
            drain(hwnd);
            Some(LRESULT(0))
        }
        WM_TRAY_CALLBACK => {
            let event = (lparam.0 as u32) & 0xFFFF;
            match event {
                NIN_SELECT | NIN_KEYSELECT | NIN_BALLOONUSERCLICK | WM_LBUTTONDBLCLK => {
                    crate::menus::dispatch(MenuAction::ShowLauncher);
                }
                WM_CONTEXTMENU => {
                    let x = (wparam.0 & 0xFFFF) as u16 as i16 as i32;
                    let y = ((wparam.0 >> 16) & 0xFFFF) as u16 as i16 as i32;
                    show_menu(hwnd, x, y);
                }
                // Pre-version-4 shells (NIM_SETVERSION refused) send plain mouse messages.
                WM_LBUTTONUP => crate::menus::dispatch(MenuAction::ShowLauncher),
                WM_RBUTTONUP => {
                    let mut pt = windows::Win32::Foundation::POINT::default();
                    if unsafe { GetCursorPos(&mut pt) }.is_ok() {
                        show_menu(hwnd, pt.x, pt.y);
                    }
                }
                _ => {}
            }
            Some(LRESULT(0))
        }
        WM_DESTROY => {
            unsafe { PostQuitMessage(0) };
            Some(LRESULT(0))
        }
        m => {
            let recreate = LOCAL.with(|l| {
                l.borrow()
                    .as_ref()
                    .and_then(|l| (l.taskbar_created == m).then_some(l.icon))
            });
            if let Some(icon) = recreate {
                unsafe { add_icon(hwnd, icon) };
                return Some(LRESULT(0));
            }
            None
        }
    }
}

fn drain(hwnd: HWND) {
    let mut quit = false;
    let mut balloons = Vec::new();
    LOCAL.with(|l| {
        if let Some(local) = l.borrow().as_ref() {
            while let Ok(m) = local.rx.try_recv() {
                match m {
                    TrayMsg::Balloon { kind, title, body } => balloons.push((kind, title, body)),
                    TrayMsg::Quit => quit = true,
                }
            }
        }
    });
    for (kind, title, body) in balloons {
        let mut nid = base_data(hwnd);
        nid.uFlags = NIF_INFO;
        copy_wide(&mut nid.szInfoTitle, &title);
        copy_wide(&mut nid.szInfo, if body.is_empty() { " " } else { &body });
        nid.dwInfoFlags = match kind {
            Kind::Info | Kind::Success => NIIF_INFO,
            Kind::Warn => NIIF_WARNING,
            Kind::Error => NIIF_ERROR,
        };
        if !unsafe { Shell_NotifyIconW(NIM_MODIFY, &nid) }.as_bool() {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!("log.notification.displayFailed", "Shell_NotifyIconW")
            );
        }
    }
    if quit {
        let nid = base_data(hwnd);
        unsafe {
            let _ = Shell_NotifyIconW(NIM_DELETE, &nid);
        }
        let icon = LOCAL.with(|l| l.borrow_mut().take().map(|l| l.icon));
        unsafe {
            if let Some(icon) = icon {
                let _ = DestroyIcon(icon);
            }
            let _ = DestroyWindow(hwnd);
        }
    }
}

fn append(menu: HMENU, entries: &[MenuEntry], actions: &mut Vec<MenuAction>) {
    for e in entries {
        unsafe {
            match e {
                MenuEntry::Item {
                    label,
                    action,
                    enabled,
                } => {
                    actions.push(action.clone());
                    let id = actions.len();
                    let flags = if *enabled {
                        MF_STRING
                    } else {
                        MF_STRING | MF_GRAYED
                    };
                    let _ = AppendMenuW(menu, flags, id, &HSTRING::from(label.as_str()));
                }
                MenuEntry::Disabled { label } => {
                    let _ = AppendMenuW(
                        menu,
                        MF_STRING | MF_GRAYED,
                        0,
                        &HSTRING::from(label.as_str()),
                    );
                }
                MenuEntry::Separator => {
                    let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
                }
                MenuEntry::Submenu { label, items } => {
                    if let Ok(sub) = CreatePopupMenu() {
                        append(sub, items, actions);
                        let _ = AppendMenuW(
                            menu,
                            MF_POPUP,
                            sub.0 as usize,
                            &HSTRING::from(label.as_str()),
                        );
                    }
                }
            }
        }
    }
}

fn show_menu(hwnd: HWND, x: i32, y: i32) {
    let entries = crate::menus::tray_menu(crate::tray::tooltip());
    let Ok(menu) = (unsafe { CreatePopupMenu() }) else {
        return;
    };
    let mut actions = Vec::new();
    append(menu, &entries, &mut actions);
    let cmd = unsafe {
        // Without the foreground the menu does not close when the user clicks elsewhere.
        let _ = SetForegroundWindow(hwnd);
        let cmd = TrackPopupMenuEx(
            menu,
            (TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_NONOTIFY).0,
            x,
            y,
            hwnd,
            None,
        );
        let _ = PostMessageW(Some(hwnd), WM_NULL, WPARAM(0), LPARAM(0));
        let _ = DestroyMenu(menu);
        cmd.0
    };
    LOCAL.with(|l| {
        if let Some(local) = l.borrow_mut().as_mut() {
            local.actions = actions;
        }
    });
    if cmd > 0 {
        let action = LOCAL.with(|l| {
            l.borrow()
                .as_ref()
                .and_then(|l| l.actions.get(cmd as usize - 1).cloned())
        });
        if let Some(a) = action {
            crate::menus::dispatch(a);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Puts a real icon in the notification area for a moment; run by hand with `--ignored`.
    #[test]
    #[ignore = "shows a real tray icon"]
    fn the_tray_icon_comes_and_goes() {
        ensure();
        assert!(is_active());
        ensure();
        shutdown();
        assert!(!is_active());
        shutdown();
        ensure();
        assert!(is_active(), "the tray can be recreated after a shutdown");
        shutdown();
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Window chrome over raw window handles (`WindowChromeManager`, `MacOsVibrancyManager`,
//! `WindowsShellRefresh`).
//!
//! Windows: DWM attributes (immersive dark mode, caption/border/text colours, system backdrop),
//! `DwmExtendFrameIntoClientArea(-1)` (**Guard**, issue #80: re-arms composition after the
//! caption is dropped and lets Mica show), Mica support, repaint and frame refresh.
//! macOS: the NSWindow appearance (DarkAqua/Aqua) so the title-bar chrome follows the app
//! theme; vibrancy itself is GPUI's `WindowBackgroundAppearance::Blurred`.
//! Linux: nothing (the compositor owns the frame).
//!
//! GPUI re-applies immersive dark mode from the **system** appearance on
//! `WM_SETTINGCHANGE ImmersiveColorSet`; our value follows the **app** theme, so call
//! [`apply_title_bar_dark_mode`] again from `observe_window_appearance` and after every theme
//! change. macOS calls must come from the main thread (GPUI window code does).

use raw_window_handle::HasWindowHandle;

#[allow(unused_imports)]
use crate::handle::{hwnd_of, ns_view_of};

/// An sRGB colour for the DWM colour attributes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rgb {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

impl Rgb {
    /// A colour.
    pub const fn new(r: u8, g: u8, b: u8) -> Rgb {
        Rgb { r, g, b }
    }

    /// The Win32 `COLORREF` (`0x00BBGGRR`).
    pub const fn colorref(self) -> u32 {
        (self.b as u32) << 16 | (self.g as u32) << 8 | self.r as u32
    }
}

/// `DWMWA_COLOR_DEFAULT`: the system colour.
pub const DWM_COLOR_DEFAULT: u32 = 0xFFFF_FFFF;
/// `DWMWA_COLOR_NONE`: no fill, so Mica shows through.
pub const DWM_COLOR_NONE: u32 = 0xFFFF_FFFE;

/// The `COLORREF` value for an optional colour (`None` = the system default).
pub const fn color_value(c: Option<Rgb>) -> u32 {
    match c {
        Some(c) => c.colorref(),
        None => DWM_COLOR_DEFAULT,
    }
}

/// A Windows 11 system backdrop (`DWMWA_SYSTEMBACKDROP_TYPE`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Backdrop {
    /// No system backdrop (`DWMSBT_NONE`).
    None,
    /// Mica (`DWMSBT_MAINWINDOW`).
    Mica,
    /// Acrylic (`DWMSBT_TRANSIENTWINDOW`).
    Acrylic,
    /// Mica Alt (`DWMSBT_TABBEDWINDOW`).
    MicaAlt,
}

impl Backdrop {
    /// The attribute value: 1 none, 2 Mica, 3 Acrylic, 4 Mica Alt.
    pub const fn value(self) -> u32 {
        match self {
            Backdrop::None => 1,
            Backdrop::Mica => 2,
            Backdrop::Acrylic => 3,
            Backdrop::MicaAlt => 4,
        }
    }
}

/// Dark or light title-bar chrome following the app theme. Windows: immersive dark mode
/// (attribute 20, retried with 19 for Windows 10 19H1/19H2). macOS: the NSWindow appearance.
pub fn apply_title_bar_dark_mode<W: HasWindowHandle + ?Sized>(window: &W, dark: bool) -> bool {
    #[cfg(windows)]
    {
        hwnd_of(window).is_some_and(|h| crate::win::dwm::set_dark_mode(h, dark))
    }
    #[cfg(target_os = "macos")]
    {
        ns_view_of(window).is_some_and(|v| crate::mac::appearance::apply(v, dark))
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        let _ = (window, dark);
        false
    }
}

/// The whole-window appearance for the app theme: on macOS the NSWindow appearance (DarkAqua
/// or Aqua; **Guard:** not the Vibrant variants, which do not theme a frame, and on the window,
/// not the content view); on Windows the same as [`apply_title_bar_dark_mode`].
pub fn apply_window_appearance<W: HasWindowHandle + ?Sized>(window: &W, dark: bool) -> bool {
    apply_title_bar_dark_mode(window, dark)
}

/// Resets the window appearance to follow the OS (macOS); on Windows applies the OS dark mode.
pub fn clear_window_appearance<W: HasWindowHandle + ?Sized>(window: &W) -> bool {
    #[cfg(target_os = "macos")]
    {
        ns_view_of(window).is_some_and(crate::mac::appearance::clear)
    }
    #[cfg(not(target_os = "macos"))]
    {
        apply_title_bar_dark_mode(window, crate::theme::is_os_dark())
    }
}

/// Sets the caption colour (`None` = system default). Windows 11 only; a no-op elsewhere.
pub fn apply_caption_color<W: HasWindowHandle + ?Sized>(window: &W, color: Option<Rgb>) -> bool {
    set_color(window, 35, color_value(color))
}

/// Sets the border colour (`None` = system default).
pub fn apply_border_color<W: HasWindowHandle + ?Sized>(window: &W, color: Option<Rgb>) -> bool {
    set_color(window, 34, color_value(color))
}

/// Sets the caption text colour (`None` = system default).
pub fn apply_caption_text_color<W: HasWindowHandle + ?Sized>(
    window: &W,
    color: Option<Rgb>,
) -> bool {
    set_color(window, 36, color_value(color))
}

/// Removes the caption fill so Mica shows through.
pub fn clear_caption_color<W: HasWindowHandle + ?Sized>(window: &W) -> bool {
    set_color(window, 35, DWM_COLOR_NONE)
}

/// Removes the border colour.
pub fn clear_border_color<W: HasWindowHandle + ?Sized>(window: &W) -> bool {
    set_color(window, 34, DWM_COLOR_NONE)
}

fn set_color<W: HasWindowHandle + ?Sized>(window: &W, attr: u32, value: u32) -> bool {
    #[cfg(windows)]
    {
        hwnd_of(window).is_some_and(|h| crate::win::dwm::set_color_attr(h, attr, value))
    }
    #[cfg(not(windows))]
    {
        let _ = (window, attr, value);
        false
    }
}

/// Sets the system backdrop. `true` only when DWM accepted it (`S_OK`); Windows 10 and
/// Windows 11 before 22H2 refuse it.
pub fn apply_backdrop<W: HasWindowHandle + ?Sized>(window: &W, kind: Backdrop) -> bool {
    #[cfg(windows)]
    {
        hwnd_of(window).is_some_and(|h| crate::win::dwm::apply_backdrop(h, kind))
    }
    #[cfg(not(windows))]
    {
        let _ = (window, kind);
        false
    }
}

/// Whether a Mica backdrop will actually show: transparency effects are on (read on every
/// call; the user, accessibility and battery saver flip it at runtime) and the OS accepts the
/// backdrop (probed once per process, only once a window exists). **Guard:** a transparent
/// scene over a refused or disabled backdrop renders pure black, so the Native theme falls back
/// to an opaque palette when this is `false`.
pub fn supports_mica<W: HasWindowHandle + ?Sized>(window: &W) -> bool {
    #[cfg(windows)]
    {
        crate::win::dwm::supports_mica(hwnd_of(window))
    }
    #[cfg(not(windows))]
    {
        let _ = window;
        false
    }
}

/// `DwmExtendFrameIntoClientArea(-1, -1, -1, -1)`: call after creating a window with a custom
/// caption. Harmless if GPUI also does it.
pub fn extend_frame_into_client_area<W: HasWindowHandle + ?Sized>(window: &W) -> bool {
    #[cfg(windows)]
    {
        hwnd_of(window).is_some_and(crate::win::dwm::extend_frame)
    }
    #[cfg(not(windows))]
    {
        let _ = window;
        false
    }
}

/// `RedrawWindow(RDW_INVALIDATE | RDW_ERASE | RDW_FRAME | RDW_ALLCHILDREN | RDW_UPDATENOW)`.
/// The caller also calls `window.refresh()` for GPUI's own scene.
pub fn force_full_repaint<W: HasWindowHandle + ?Sized>(window: &W) -> bool {
    #[cfg(windows)]
    {
        hwnd_of(window).is_some_and(crate::win::force_full_repaint)
    }
    #[cfg(not(windows))]
    {
        let _ = window;
        false
    }
}

/// Re-asserts the window's real position with `SWP_FRAMECHANGED`, which Windows 11's
/// per-monitor taskbar notices (a no-move version gets debounced). Call when the window moves
/// between monitors.
pub fn force_frame_refresh<W: HasWindowHandle + ?Sized>(window: &W) -> bool {
    #[cfg(windows)]
    {
        hwnd_of(window).is_some_and(crate::win::force_frame_refresh)
    }
    #[cfg(not(windows))]
    {
        let _ = window;
        false
    }
}

/// Makes an open window resizable or fixed-size (`Stage.setResizable`): GPUI fixes
/// `is_resizable` when the window opens, so the "Resizable windows" setting is applied live
/// here. Windows: `WS_THICKFRAME | WS_MAXIMIZEBOX` plus a frame refresh. macOS:
/// `NSWindowStyleMaskResizable` (main thread). Linux: unsupported (`false`); the setting takes
/// effect on the next window.
pub fn set_resizable<W: HasWindowHandle + ?Sized>(window: &W, resizable: bool) -> bool {
    #[cfg(windows)]
    {
        hwnd_of(window).is_some_and(|h| crate::win::set_resizable(h, resizable))
    }
    #[cfg(target_os = "macos")]
    {
        ns_view_of(window).is_some_and(|v| crate::mac::window::set_resizable(v, resizable))
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        let _ = (window, resizable);
        false
    }
}

/// Makes `window` owned by `owner` (`initOwner`: it stays above its owner). The owner is a
/// [`crate::NativeHandle`] read earlier with [`crate::handle::native_handle`], since a GUI
/// toolkit seldom lends two windows at once. Windows only (`GWLP_HWNDPARENT`); elsewhere
/// `false`.
pub fn set_owner<W: HasWindowHandle + ?Sized>(window: &W, owner: crate::NativeHandle) -> bool {
    #[cfg(windows)]
    {
        match (hwnd_of(window), owner) {
            (Some(w), crate::NativeHandle::Win32 { hwnd: o }) if w != o => {
                crate::win::set_owner(w, Some(o))
            }
            _ => false,
        }
    }
    #[cfg(not(windows))]
    {
        let _ = (window, owner);
        false
    }
}

/// Brings the window to the foreground: restores it when minimized, then
/// `SetForegroundWindow` (Windows). Elsewhere `false`; use GPUI's `activate_window`.
pub fn bring_to_front<W: HasWindowHandle + ?Sized>(window: &W) -> bool {
    #[cfg(windows)]
    {
        hwnd_of(window).is_some_and(crate::win::bring_to_front)
    }
    #[cfg(not(windows))]
    {
        let _ = window;
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colorref_is_bgr() {
        assert_eq!(Rgb::new(0x11, 0x22, 0x33).colorref(), 0x0033_2211);
        assert_eq!(color_value(None), 0xFFFF_FFFF);
        assert_eq!(color_value(Some(Rgb::new(255, 0, 0))), 0x0000_00FF);
    }

    #[test]
    fn backdrop_values_match_dwm() {
        assert_eq!(
            [
                Backdrop::None,
                Backdrop::Mica,
                Backdrop::Acrylic,
                Backdrop::MicaAlt
            ]
            .map(Backdrop::value),
            [1, 2, 3, 4]
        );
    }
}

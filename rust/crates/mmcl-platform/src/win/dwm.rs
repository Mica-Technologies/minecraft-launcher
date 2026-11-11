// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! DWM window attributes (`WindowChromeManager`).

use std::sync::OnceLock;

use windows::Win32::Graphics::Dwm::{
    DWMWINDOWATTRIBUTE, DwmExtendFrameIntoClientArea, DwmSetWindowAttribute,
};
use windows::Win32::UI::Controls::MARGINS;

use super::hwnd;
use crate::chrome::Backdrop;

/// `DWMWA_USE_IMMERSIVE_DARK_MODE`.
pub const DWMWA_USE_IMMERSIVE_DARK_MODE: u32 = 20;
/// The pre-20H1 value of the same attribute (Windows 10 19H1/19H2).
pub const DWMWA_USE_IMMERSIVE_DARK_MODE_OLD: u32 = 19;
/// `DWMWA_SYSTEMBACKDROP_TYPE`.
pub const DWMWA_SYSTEMBACKDROP_TYPE: u32 = 38;

fn set_u32(h: isize, attr: u32, value: u32) -> windows::core::Result<()> {
    unsafe {
        DwmSetWindowAttribute(
            hwnd(h),
            DWMWINDOWATTRIBUTE(attr as i32),
            (&value as *const u32).cast(),
            std::mem::size_of::<u32>() as u32,
        )
    }
}

/// Immersive dark mode on the title bar; retries with attribute 19 when 20 is refused.
pub fn set_dark_mode(h: isize, dark: bool) -> bool {
    crate::guard(|| {
        let value = u32::from(dark);
        if set_u32(h, DWMWA_USE_IMMERSIVE_DARK_MODE, value).is_ok() {
            return true;
        }
        match set_u32(h, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, value) {
            Ok(()) => true,
            Err(e) => {
                tracing::warn!(
                    "{}",
                    mmcl_i18n::tf!("log.windowChrome.darkModeFailed", e.to_string())
                );
                false
            }
        }
    })
}

/// A colour attribute (34 border, 35 caption, 36 caption text) as a `COLORREF`.
pub fn set_color_attr(h: isize, attr: u32, value: u32) -> bool {
    crate::guard(|| match set_u32(h, attr, value) {
        Ok(()) => true,
        Err(e) => {
            tracing::debug!(
                "{}",
                mmcl_i18n::tf!(
                    "log.windowChrome.colorFailed",
                    attr.to_string(),
                    e.to_string()
                )
            );
            false
        }
    })
}

/// The system backdrop; `true` only on `S_OK`.
pub fn apply_backdrop(h: isize, kind: Backdrop) -> bool {
    crate::guard(
        || match set_u32(h, DWMWA_SYSTEMBACKDROP_TYPE, kind.value()) {
            Ok(()) => true,
            Err(e) => {
                tracing::debug!(
                    "{}",
                    mmcl_i18n::tf!("log.windowChrome.backdropFailed", e.to_string())
                );
                false
            }
        },
    )
}

/// The margins that put the DWM frame (and so the Mica backdrop) behind the whole client area.
///
/// Not the usual "sheet of glass" (all -1): with that, DWM also paints the caption's own
/// minimize, maximize and close buttons into the client area, on top of the launcher's drawn
/// ones. A bottom margin taller than any window covers the same area without the caption
/// buttons, and the window keeps `WS_CAPTION`, which a maximized window needs to stay off the
/// taskbar. (Java drops `WS_CAPTION` instead and handles maximize in its own window procedure;
/// GPUI owns the window procedure here.)
pub(crate) const CLIENT_GLASS: MARGINS = MARGINS {
    cxLeftWidth: 0,
    cxRightWidth: 0,
    cyTopHeight: 0,
    cyBottomHeight: 65_535,
};

/// `DwmExtendFrameIntoClientArea` with [`CLIENT_GLASS`].
pub fn extend_frame(h: isize) -> bool {
    crate::guard(
        || match unsafe { DwmExtendFrameIntoClientArea(hwnd(h), &CLIENT_GLASS) } {
            Ok(()) => true,
            Err(e) => {
                tracing::warn!(
                    "{}",
                    mmcl_i18n::tf!("log.windowChrome.extendFrameFailed", e.to_string())
                );
                false
            }
        },
    )
}

/// The `EnableTransparency` setting, read fresh each call. Missing or unreadable counts as on.
pub fn transparency_enabled() -> bool {
    let key = windows_registry::CURRENT_USER
        .open(r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
    match key.and_then(|k| k.get_u32("EnableTransparency")) {
        Ok(v) => v != 0,
        Err(e) => {
            // ERROR_FILE_NOT_FOUND: the value was never written, which means "on".
            if e.code().0 as u32 != 0x8007_0002 {
                tracing::debug!(
                    "{}",
                    mmcl_i18n::tf!("log.windowChrome.transparencyReadFailed", e.to_string())
                );
            }
            true
        }
    }
}

static MICA_OS_SUPPORT: OnceLock<bool> = OnceLock::new();

/// Transparency on **and** the OS accepts Mica (probed once with a real HWND, then cached).
pub fn supports_mica(h: Option<isize>) -> bool {
    if !transparency_enabled() {
        return false;
    }
    if let Some(&s) = MICA_OS_SUPPORT.get() {
        return s;
    }
    let Some(h) = h else {
        return false;
    };
    *MICA_OS_SUPPORT.get_or_init(|| apply_backdrop(h, Backdrop::Mica))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A -1 margin is the "sheet of glass", which brings DWM's caption buttons into the client
    /// area (doubled buttons). The glass must come from the bottom margin alone.
    #[test]
    fn client_glass_is_not_the_sheet_of_glass() {
        let m = CLIENT_GLASS;
        assert_eq!((m.cxLeftWidth, m.cxRightWidth, m.cyTopHeight), (0, 0, 0));
        assert!(m.cyBottomHeight >= 16_384, "taller than any window");
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The tray icon (Windows notification area, Linux StatusNotifierItem).
//!
//! Created lazily by the first notification (or by [`ensure`] if the app wants it at startup)
//! and kept for the session. Left-click or activate shows the launcher; right-click shows the
//! shared [`crate::menus::tray_menu`]. The tooltip is the app name. macOS has no tray icon:
//! the dock menu covers the same actions.

/// Creates the tray icon if it does not exist yet. Failures are logged
/// (`log.notification.trayInitFailed`) and leave notifications on the log-only path.
pub fn ensure() {
    #[cfg(windows)]
    crate::win::tray::ensure();
    #[cfg(target_os = "linux")]
    crate::linux::tray::ensure();
}

/// Whether a tray icon is showing.
pub fn is_active() -> bool {
    #[cfg(windows)]
    {
        crate::win::tray::is_active()
    }
    #[cfg(target_os = "linux")]
    {
        crate::linux::tray::is_active()
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        false
    }
}

/// Removes the tray icon (idempotent).
pub fn shutdown() {
    #[cfg(windows)]
    crate::win::tray::shutdown();
    #[cfg(target_os = "linux")]
    crate::linux::tray::shutdown();
}

/// The tooltip / title of the tray icon.
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
pub(crate) fn tooltip() -> &'static str {
    mmcl_core::AppIdentity::current().display_name
}

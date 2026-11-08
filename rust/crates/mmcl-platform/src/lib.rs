// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! OS-shell integration for the Mica Minecraft Launcher (Rust port of the launcher's native
//! `utilities` classes and `system.DesktopShortcutManager`).
//!
//! Everything works on [`raw_window_handle`] 0.6 handles, so this crate does not depend on
//! GPUI: a `gpui::Window` implements `HasWindowHandle` and can be passed straight in.
//!
//! - [`theme`]: is the OS in dark mode.
//! - [`single_instance`]: the loopback single-instance lock and its token-checked IPC.
//! - [`chrome`]: DWM dark mode, caption colours, Mica/backdrop, frame refresh (Windows) and the
//!   NSWindow appearance (macOS).
//! - [`progress`]: one taskbar/dock progress owner shared by every launch.
//! - [`notify`] and [`tray`]: toasts with a log paper trail, and the lazily created tray icon.
//! - [`menus`]: the tray and dock menu definitions.
//! - [`recents`]: the recent-modpacks surfaces (jump list, `.desktop` actions).
//! - [`register`]: `mmcl://` and `.mmcjson` registration helpers. The Rust identity registers
//!   neither; see [`mmcl_core::AppIdentity`].
//! - [`shortcut`]: desktop shortcuts for a modpack.
//! - [`power`]: battery probe and the download throttle gate.
//! - [`open`]: open a folder or an http(s) link.
//! - [`host`]: the callbacks the app crate provides (focus, deep links, menu actions, recents).
//! - [`window`]: owned and modal secondary windows, runtime minimum window size.
//! - [`early_init`]: Windows startup hardening (DLL search path, DPI awareness, AUMID).
//!
//! Every native step is best effort: it is gated on the platform, logs failures silently and
//! never panics across an FFI boundary (see [`best_effort`]).

pub mod chrome;
pub mod error;
pub mod handle;
pub mod host;
pub mod icon;
pub mod menus;
pub mod notify;
pub mod open;
pub mod power;
pub mod progress;
pub mod recents;
pub mod register;
pub mod shortcut;
pub mod single_instance;
pub mod theme;
pub mod tray;
pub mod window;

#[cfg(target_os = "linux")]
pub mod linux;
#[cfg(target_os = "macos")]
pub mod mac;
#[cfg(windows)]
pub mod win;

pub use error::PlatformError;
pub use handle::NativeHandle;
pub use host::{PlatformHost, RecentPack};
pub use menus::{MenuAction, MenuEntry};
pub use mmcl_util::Progress;
pub use single_instance::ForwardResult;
pub use window::{LogicalSize, WindowLink};

use std::fmt::Display;
use std::panic::{AssertUnwindSafe, catch_unwind};

/// The bundled launcher icon (`micaminecraftlauncher.png`), used for the tray and Linux icons.
pub(crate) const LAUNCHER_ICON_PNG: &[u8] =
    include_bytes!("../../../../src/main/resources/micaminecraftlauncher.png");

/// The bundled Explorer icon for `.mmcjson` files.
#[cfg_attr(not(windows), allow(dead_code))]
pub(crate) const MMCJSON_ICON_ICO: &[u8] =
    include_bytes!("../../../../src/main/resources/mmcjson-file.ico");

/// Runs a best-effort native step. On an error or a panic it logs `key` (a localization key
/// with one `{0}` slot for the error) as a silent warning and returns `None`. Never panics.
pub fn best_effort<T, E: Display>(
    key: &'static str,
    f: impl FnOnce() -> Result<T, E>,
) -> Option<T> {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(v)) => Some(v),
        Ok(Err(e)) => {
            tracing::warn!("{}", mmcl_i18n::tf!(key, e.to_string()));
            None
        }
        Err(payload) => {
            let msg = mmcl_util::startup_log::panic_message(payload.as_ref());
            tracing::warn!("{}", mmcl_i18n::tf!(key, msg));
            None
        }
    }
}

/// Runs `f`, swallowing a panic (for callback bodies invoked from native code).
pub(crate) fn guard<T: Default>(f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_default()
}

/// Startup hardening, to call in the first lines of `main` before any window or DLL load.
///
/// Windows: removes the current directory from the DLL search order (`SetDllDirectoryW("")`,
/// keeping `PATH` so vendor RGB SDKs still resolve), opts into per-monitor-v2 DPI awareness,
/// and sets this identity's AppUserModelID (taskbar grouping, jump list, toasts). Elsewhere it
/// does nothing.
pub fn early_init() {
    #[cfg(windows)]
    win::early_init(mmcl_core::AppIdentity::current().win_aumid);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn best_effort_passes_values_through() {
        assert_eq!(
            best_effort("log.taskbar.updateFailed", || Ok::<_, String>(7)),
            Some(7)
        );
    }

    #[test]
    fn best_effort_swallows_errors_and_panics() {
        assert_eq!(
            best_effort("log.taskbar.updateFailed", || Err::<i32, _>("nope")),
            None
        );
        let r: Option<i32> = best_effort("log.taskbar.updateFailed", || -> Result<i32, String> {
            panic!("boom")
        });
        assert_eq!(r, None);
    }

    /// Every localization key this crate uses, on any OS.
    const KEYS: &[&str] = &[
        "jumpList.category.recentModpacks",
        "jumpList.tooltip.play",
        "log.desktopShortcut.created",
        "log.desktopShortcut.iconConversionFailed",
        "log.dllHardening.failed",
        "log.dllHardening.returnedFalse",
        "log.dpiAwareness.setupFailed",
        "log.jumpList.coInitFailed",
        "log.jumpList.hresultFailure",
        "log.jumpList.noExePath",
        "log.jumpList.propertyStoreFailed",
        "log.jumpListManager.refreshFailed",
        "log.macDock.progressUpdateFailed",
        "log.macVibrancy.appearanceSetFailed",
        "log.notification.displayFailed",
        "log.notification.fallback",
        "log.notification.trayImageMissing",
        "log.notification.trayInitFailed",
        "log.platform.aumidFailed",
        "log.platform.notMainThread",
        "log.platform.openFailed",
        "log.platform.openUrlRejected",
        "log.powerState.probeFailed",
        "log.powerState.throttleEngaged",
        "log.schemeRegistrar.devModeSkip",
        "log.schemeRegistrar.exePathUnknown",
        "log.schemeRegistrar.icoStageFailed",
        "log.schemeRegistrar.iconInstallFailed",
        "log.schemeRegistrar.iconInstalled",
        "log.schemeRegistrar.identitySkip",
        "log.schemeRegistrar.linuxDesktopEntryWritten",
        "log.schemeRegistrar.linuxRegistered",
        "log.schemeRegistrar.mimeRegisterFailed",
        "log.schemeRegistrar.mimeRegistered",
        "log.schemeRegistrar.registrationFailed",
        "log.schemeRegistrar.windowsRegistered",
        "log.shellRefresh.setWindowPosFailed",
        "log.singleInstance.badToken",
        "log.singleInstance.dispatchDeepLink",
        "log.singleInstance.lockAcquired",
        "log.singleInstance.tokenPersistFailed",
        "log.singleInstance.unrecognizedPayload",
        "log.taskbar.errorOverlayFailed",
        "log.taskbar.initFailed",
        "log.taskbar.stopFailed",
        "log.taskbar.updateFailed",
        "log.window.minSizeFailed",
        "log.window.ownerFailed",
        "log.window.x11Unavailable",
        "log.windowChrome.backdropFailed",
        "log.windowChrome.colorFailed",
        "log.windowChrome.darkModeFailed",
        "log.windowChrome.extendFrameFailed",
        "log.windowChrome.redrawFailed",
        "log.windowChrome.transparencyReadFailed",
        "main.navbar.settings",
        "menu.dock.openMods",
        "menu.dock.playLast",
        "menu.dock.quit",
        "menu.dock.show",
        "menu.modpacks.browse",
        "menu.modpacks.playRecent",
        "menu.modpacks.playRecent.empty",
        "shortcut.error.exePathUnknown",
        "shortcut.error.noDesktop",
        "shortcut.error.shellLinkFailed",
        "shortcut.error.writeFailed",
        "startup.alreadyRunning.noWindow",
        "startup.alreadyRunning.unreachable",
    ];

    #[test]
    fn every_localization_key_exists() {
        let missing: Vec<&str> = KEYS
            .iter()
            .copied()
            .filter(|k| !mmcl_i18n::contains(k))
            .collect();
        assert!(missing.is_empty(), "missing keys: {missing:?}");
    }

    #[test]
    fn bundled_icons_are_present() {
        assert!(LAUNCHER_ICON_PNG.starts_with(b"\x89PNG"));
        assert_eq!(&MMCJSON_ICON_ICO[..4], &[0, 0, 1, 0]);
    }
}

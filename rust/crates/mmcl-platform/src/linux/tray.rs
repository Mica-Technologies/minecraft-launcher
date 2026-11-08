// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The StatusNotifierItem tray icon (`ksni`): KDE, and GNOME with the AppIndicator extension.
//! `tray-icon` is avoided on Linux because it needs GTK, libappindicator and a GTK main loop
//! that GPUI does not run.

use ksni::blocking::TrayMethods;
use ksni::menu::{StandardItem, SubMenu};
use ksni::{Icon, MenuItem, ToolTip, Tray};
use parking_lot::Mutex;

use crate::menus::{MenuAction, MenuEntry, dispatch};

static HANDLE: Mutex<Option<ksni::blocking::Handle<LauncherTray>>> = Mutex::new(None);

/// The tray model.
pub struct LauncherTray {
    pixmaps: Vec<Icon>,
}

impl LauncherTray {
    fn new() -> LauncherTray {
        let pixmaps = crate::icon::decode(crate::LAUNCHER_ICON_PNG)
            .map(|img| {
                [22u32, 32, 48, 64]
                    .into_iter()
                    .map(|s| {
                        let scaled = crate::icon::scaled(&img, s);
                        Icon {
                            width: s as i32,
                            height: s as i32,
                            data: crate::icon::argb32_be(&scaled),
                        }
                    })
                    .collect()
            })
            .unwrap_or_else(|_| {
                tracing::warn!("{}", mmcl_i18n::t!("log.notification.trayImageMissing"));
                Vec::new()
            });
        LauncherTray { pixmaps }
    }
}

fn convert(entries: &[MenuEntry]) -> Vec<MenuItem<LauncherTray>> {
    entries
        .iter()
        .map(|e| match e {
            MenuEntry::Item {
                label,
                action,
                enabled,
            } => {
                let action: MenuAction = action.clone();
                StandardItem {
                    label: label.replace('_', "__"),
                    enabled: *enabled,
                    activate: Box::new(move |_| dispatch(action.clone())),
                    ..Default::default()
                }
                .into()
            }
            MenuEntry::Disabled { label } => StandardItem {
                label: label.replace('_', "__"),
                enabled: false,
                ..Default::default()
            }
            .into(),
            MenuEntry::Separator => MenuItem::Separator,
            MenuEntry::Submenu { label, items } => SubMenu {
                label: label.replace('_', "__"),
                submenu: convert(items),
                ..Default::default()
            }
            .into(),
        })
        .collect()
}

impl Tray for LauncherTray {
    fn id(&self) -> String {
        mmcl_core::AppIdentity::current()
            .linux_package_name
            .to_string()
    }

    fn title(&self) -> String {
        crate::tray::tooltip().to_string()
    }

    fn icon_name(&self) -> String {
        mmcl_core::AppIdentity::current()
            .linux_package_name
            .to_string()
    }

    fn icon_pixmap(&self) -> Vec<Icon> {
        self.pixmaps.clone()
    }

    fn tool_tip(&self) -> ToolTip {
        ToolTip {
            title: crate::tray::tooltip().to_string(),
            ..Default::default()
        }
    }

    fn activate(&mut self, _x: i32, _y: i32) {
        dispatch(MenuAction::ShowLauncher);
    }

    fn menu(&self) -> Vec<MenuItem<Self>> {
        convert(&crate::menus::tray_menu(crate::tray::tooltip()))
    }
}

/// Starts the tray service if it is not running.
pub fn ensure() {
    let mut handle = HANDLE.lock();
    if handle.as_ref().is_some_and(|h| !h.is_closed()) {
        return;
    }
    let spawned = crate::guard(|| {
        Some(
            LauncherTray::new()
                .disable_dbus_name(super::is_flatpak())
                .spawn()
                .map_err(|e| e.to_string()),
        )
    })
    .unwrap_or_else(|| Err("panic".into()));
    match spawned {
        Ok(h) => *handle = Some(h),
        Err(e) => tracing::warn!("{}", mmcl_i18n::tf!("log.notification.trayInitFailed", e)),
    }
}

/// Whether the tray service is running.
pub fn is_active() -> bool {
    HANDLE.lock().as_ref().is_some_and(|h| !h.is_closed())
}

/// Stops the tray service (idempotent).
pub fn shutdown() {
    let handle = HANDLE.lock().take();
    if let Some(h) = handle {
        crate::guard(|| h.shutdown().wait());
    }
}

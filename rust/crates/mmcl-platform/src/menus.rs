// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Tray and dock menu definitions (`LauncherActions`), as plain data.
//!
//! The Windows and Linux trays render [`tray_menu`] themselves. On macOS the app crate turns
//! [`dock_menu`] into GPUI `MenuItem`s for `cx.set_dock_menu` (never call that on Windows: GPUI
//! rewrites the jump list there). The actions run through [`crate::PlatformHost::on_menu_action`].

use crate::host::RecentPack;

/// The most recent packs the macOS dock menu lists.
pub const DOCK_RECENT_MAX: usize = 8;

/// What a shell menu item does.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MenuAction {
    /// Bring the launcher window forward.
    ShowLauncher,
    /// Play the last selected modpack (`ConfigManager.lastModPackSelected`).
    PlayLast,
    /// Play this pack (by pack name).
    PlayModpack(String),
    /// Open the last pack's `mods` folder.
    OpenLastModsFolder,
    /// Open the Browse screen (checks `may_navigate_away` first).
    OpenBrowse,
    /// Open Settings (checks `may_navigate_away` first).
    OpenSettings,
    /// Open the getting-started help topic.
    OpenHelp,
    /// Quit the launcher (`request_quit`).
    Quit,
}

/// One entry of a shell menu.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MenuEntry {
    /// A clickable item.
    Item {
        label: String,
        action: MenuAction,
        enabled: bool,
    },
    /// A disabled informational line.
    Disabled { label: String },
    /// A separator line.
    Separator,
    /// A submenu.
    Submenu {
        label: String,
        items: Vec<MenuEntry>,
    },
}

impl MenuEntry {
    fn item(label: impl Into<String>, action: MenuAction) -> MenuEntry {
        MenuEntry::Item {
            label: label.into(),
            action,
            enabled: true,
        }
    }

    /// Visits every clickable item, depth first.
    pub fn for_each_item<'a>(
        entries: &'a [MenuEntry],
        f: &mut impl FnMut(&'a str, &'a MenuAction),
    ) {
        for e in entries {
            match e {
                MenuEntry::Item { label, action, .. } => f(label, action),
                MenuEntry::Submenu { items, .. } => MenuEntry::for_each_item(items, f),
                MenuEntry::Disabled { .. } | MenuEntry::Separator => {}
            }
        }
    }
}

/// The shared tray menu (Windows and Linux): Show, separator, Play Last, Open Mods, separator,
/// Quit. `app_name` fills the Show and Quit labels.
pub fn tray_menu(app_name: &str) -> Vec<MenuEntry> {
    vec![
        MenuEntry::item(
            mmcl_i18n::tf!("menu.dock.show", app_name),
            MenuAction::ShowLauncher,
        ),
        MenuEntry::Separator,
        MenuEntry::item(mmcl_i18n::t!("menu.dock.playLast"), MenuAction::PlayLast),
        MenuEntry::item(
            mmcl_i18n::t!("menu.dock.openMods"),
            MenuAction::OpenLastModsFolder,
        ),
        MenuEntry::Separator,
        MenuEntry::item(mmcl_i18n::tf!("menu.dock.quit", app_name), MenuAction::Quit),
    ]
}

/// The macOS dock menu: Show, separator, Play Recent (up to [`DOCK_RECENT_MAX`] packs with a
/// last-played time, newest first; one disabled "No recent modpacks" line when there are none),
/// Open Mods, separator, Browse, Settings, separator, Quit.
pub fn dock_menu(app_name: &str, recent: &[RecentPack]) -> Vec<MenuEntry> {
    let mut played: Vec<&RecentPack> = recent
        .iter()
        .filter(|p| p.last_played_ms > 0 && !p.pack_name.trim().is_empty())
        .collect();
    played.sort_by_key(|p| std::cmp::Reverse(p.last_played_ms));
    played.truncate(DOCK_RECENT_MAX);
    let mut items: Vec<MenuEntry> = played
        .iter()
        .map(|p| {
            MenuEntry::item(
                p.safe_display_name(),
                MenuAction::PlayModpack(p.pack_name.clone()),
            )
        })
        .collect();
    if items.is_empty() {
        items.push(MenuEntry::Disabled {
            label: mmcl_i18n::t!("menu.modpacks.playRecent.empty").into_owned(),
        });
    }
    vec![
        MenuEntry::item(
            mmcl_i18n::tf!("menu.dock.show", app_name),
            MenuAction::ShowLauncher,
        ),
        MenuEntry::Separator,
        MenuEntry::Submenu {
            label: mmcl_i18n::t!("menu.modpacks.playRecent").into_owned(),
            items,
        },
        MenuEntry::item(
            mmcl_i18n::t!("menu.dock.openMods"),
            MenuAction::OpenLastModsFolder,
        ),
        MenuEntry::Separator,
        MenuEntry::item(
            mmcl_i18n::t!("menu.modpacks.browse"),
            MenuAction::OpenBrowse,
        ),
        MenuEntry::item(
            mmcl_i18n::t!("main.navbar.settings"),
            MenuAction::OpenSettings,
        ),
        MenuEntry::Separator,
        MenuEntry::item(mmcl_i18n::tf!("menu.dock.quit", app_name), MenuAction::Quit),
    ]
}

/// Runs `action` through the installed host on a worker (menu callbacks arrive on native
/// threads that must not block).
pub fn dispatch(action: MenuAction) {
    mmcl_util::tasks::spawn(move || {
        crate::guard(|| {
            if let Some(h) = crate::host::host() {
                h.on_menu_action(action);
            }
        })
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tray_menu_has_the_shared_shape() {
        let m = tray_menu("Mica");
        assert_eq!(m.len(), 6);
        assert!(
            matches!(&m[0], MenuEntry::Item { label, action: MenuAction::ShowLauncher, .. } if label.contains("Mica"))
        );
        assert_eq!(m[1], MenuEntry::Separator);
        assert!(
            matches!(&m[5], MenuEntry::Item { action: MenuAction::Quit, label, .. } if label.contains("Mica"))
        );
    }

    #[test]
    fn dock_menu_lists_played_packs_newest_first_capped_at_eight() {
        let packs: Vec<RecentPack> = (0..12)
            .map(|i| {
                RecentPack::new(
                    format!("p{i}"),
                    format!("Pack {i}"),
                    if i == 3 { 0 } else { i as i64 * 10 },
                )
            })
            .collect();
        let m = dock_menu("Mica", &packs);
        let MenuEntry::Submenu { items, .. } = &m[2] else {
            panic!("no submenu")
        };
        assert_eq!(items.len(), 8);
        assert!(
            matches!(&items[0], MenuEntry::Item { action: MenuAction::PlayModpack(n), .. } if n == "p11")
        );
        let mut names = Vec::new();
        MenuEntry::for_each_item(items, &mut |_, a| names.push(a.clone()));
        assert!(!names.contains(&MenuAction::PlayModpack("p3".into())));
    }

    #[test]
    fn dock_menu_without_recents_has_a_disabled_line() {
        let m = dock_menu("Mica", &[]);
        let MenuEntry::Submenu { items, .. } = &m[2] else {
            panic!("no submenu")
        };
        assert!(matches!(&items[..], [MenuEntry::Disabled { .. }]));
        assert_eq!(m.len(), 9);
    }
}

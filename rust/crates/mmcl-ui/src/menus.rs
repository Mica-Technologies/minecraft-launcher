// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The application menu bar (`SystemMenuBarManager`; shown by macOS, ignored elsewhere).
//!
//! One bar per app, built with [`set_app_menus`]; call it again after each launch to refresh
//! the Play Recent submenu and the View check items.

use gpui::{App, Menu, MenuItem};
use mmcl_i18n::t;
use mmcl_theme::ThemeId;

use crate::actions::*;

/// What the dynamic parts of the menu bar show.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MenuState {
    /// Recent pack names, newest first (at most nine get an item).
    pub recent_packs: Vec<String>,
    /// The configured theme (radio check in View > Theme).
    pub theme: Option<ThemeId>,
    /// View > Discord Rich Presence.
    pub discord_rpc: bool,
    /// View > Keep Running Games Window Open.
    pub keep_games_window_open: bool,
    /// View > Show Pack Backgrounds.
    pub pack_backgrounds: bool,
}

fn checked(item: MenuItem, on: bool) -> MenuItem {
    match item {
        MenuItem::Action {
            name,
            action,
            os_action,
            disabled,
            ..
        } => MenuItem::Action {
            name,
            action,
            os_action,
            checked: on,
            disabled,
        },
        other => other,
    }
}

fn disabled(item: MenuItem) -> MenuItem {
    match item {
        MenuItem::Action {
            name,
            action,
            os_action,
            checked,
            ..
        } => MenuItem::Action {
            name,
            action,
            os_action,
            checked,
            disabled: true,
        },
        other => other,
    }
}

fn recent_item(index: usize, name: &str) -> Option<MenuItem> {
    let name = name.to_string();
    Some(match index {
        0 => MenuItem::action(name, PlayRecent1),
        1 => MenuItem::action(name, PlayRecent2),
        2 => MenuItem::action(name, PlayRecent3),
        3 => MenuItem::action(name, PlayRecent4),
        4 => MenuItem::action(name, PlayRecent5),
        5 => MenuItem::action(name, PlayRecent6),
        6 => MenuItem::action(name, PlayRecent7),
        7 => MenuItem::action(name, PlayRecent8),
        8 => MenuItem::action(name, PlayRecent9),
        _ => return None,
    })
}

fn theme_item(id: ThemeId, current: Option<ThemeId>) -> MenuItem {
    let label = t!(id.label_key()).into_owned();
    let item = match id {
        ThemeId::Automatic => MenuItem::action(label, ThemeAutomatic),
        ThemeId::Dark => MenuItem::action(label, ThemeDark),
        ThemeId::Light => MenuItem::action(label, ThemeLight),
        ThemeId::BlueGray => MenuItem::action(label, ThemeBlueGray),
        ThemeId::OrangePurple => MenuItem::action(label, ThemeOrangePurple),
        ThemeId::Creeper => MenuItem::action(label, ThemeCreeper),
        ThemeId::Native => MenuItem::action(label, ThemeNative),
    };
    checked(item, current == Some(id))
}

/// Builds the menu bar.
pub fn build_app_menus(state: &MenuState) -> Vec<Menu> {
    let app_name = mmcl_core::AppIdentity::current().display_name;
    let mut recent: Vec<MenuItem> = state
        .recent_packs
        .iter()
        .enumerate()
        .filter_map(|(i, n)| recent_item(i, n))
        .collect();
    if recent.is_empty() {
        recent.push(disabled(MenuItem::action(
            t!("menu.modpacks.playRecent.empty").into_owned(),
            PlayRecent1,
        )));
    }
    vec![
        Menu {
            name: app_name.into(),
            items: vec![
                MenuItem::action(t!("main.navbar.settings").into_owned(), OpenSettings),
                MenuItem::separator(),
                MenuItem::action(t!("main.navbar.exit").into_owned(), Quit),
            ],
            disabled: false,
        },
        Menu {
            name: t!("menu.modpacks.title").into_owned().into(),
            items: vec![
                MenuItem::action(t!("menu.modpacks.home").into_owned(), OpenHome),
                MenuItem::action(t!("menu.modpacks.browse").into_owned(), OpenBrowse),
                MenuItem::action(t!("menu.modpacks.refresh").into_owned(), Refresh),
                MenuItem::separator(),
                MenuItem::submenu(Menu {
                    name: t!("menu.modpacks.playRecent").into_owned().into(),
                    items: recent,
                    disabled: false,
                }),
            ],
            disabled: false,
        },
        Menu {
            name: t!("menu.game.title").into_owned().into(),
            items: vec![
                MenuItem::action(t!("menu.game.runtimes").into_owned(), OpenRuntimes),
                MenuItem::action(t!("menu.game.editor").into_owned(), OpenEditor),
            ],
            disabled: false,
        },
        Menu {
            name: t!("menu.view.title").into_owned().into(),
            items: vec![
                MenuItem::submenu(Menu {
                    name: t!("menu.view.theme").into_owned().into(),
                    items: ThemeId::ALL
                        .iter()
                        .map(|id| theme_item(*id, state.theme))
                        .collect(),
                    disabled: false,
                }),
                checked(
                    MenuItem::action(t!("menu.view.discordRpc").into_owned(), ToggleDiscordRpc),
                    state.discord_rpc,
                ),
                checked(
                    MenuItem::action(
                        t!("menu.view.keepGamesWindowOpen").into_owned(),
                        ToggleKeepGamesWindowOpen,
                    ),
                    state.keep_games_window_open,
                ),
                checked(
                    MenuItem::action(
                        t!("menu.view.packBackgrounds").into_owned(),
                        TogglePackBackgrounds,
                    ),
                    state.pack_backgrounds,
                ),
            ],
            disabled: false,
        },
        Menu {
            name: t!("menu.window.title").into_owned().into(),
            items: vec![
                MenuItem::action(t!("menu.window.minimize").into_owned(), Minimize),
                MenuItem::action(t!("menu.window.zoom").into_owned(), Zoom),
            ],
            disabled: false,
        },
        Menu {
            name: t!("menu.help.title").into_owned().into(),
            items: vec![
                MenuItem::action(t!("menu.help.help").into_owned(), OpenHelp),
                MenuItem::action(t!("menu.help.website").into_owned(), OpenWebsite),
            ],
            disabled: false,
        },
    ]
}

/// Installs (or refreshes) the menu bar.
pub fn set_app_menus(cx: &mut App, state: &MenuState) {
    cx.set_menus(build_app_menus(state));
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(menu: &Menu) -> Vec<String> {
        menu.items
            .iter()
            .filter_map(|i| match i {
                MenuItem::Action { name, .. } => Some(name.to_string()),
                MenuItem::Submenu(m) => Some(m.name.to_string()),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn menu_bar_has_every_java_menu() {
        let menus = build_app_menus(&MenuState::default());
        assert_eq!(menus.len(), 6);
        let modpacks = names(&menus[1]);
        assert!(
            modpacks.contains(
                &mmcl_i18n::english("menu.modpacks.home")
                    .unwrap()
                    .to_string()
            )
        );
        let help = names(&menus[5]);
        assert_eq!(help.len(), 2);
    }

    #[test]
    fn play_recent_lists_at_most_nine_or_an_empty_item() {
        let empty = build_app_menus(&MenuState::default());
        let MenuItem::Submenu(recent) = &empty[1].items[4] else {
            panic!("play recent submenu")
        };
        assert_eq!(recent.items.len(), 1);
        assert!(matches!(
            recent.items[0],
            MenuItem::Action { disabled: true, .. }
        ));

        let state = MenuState {
            recent_packs: (0..12).map(|i| format!("Pack {i}")).collect(),
            theme: Some(ThemeId::Dark),
            ..MenuState::default()
        };
        let menus = build_app_menus(&state);
        let MenuItem::Submenu(recent) = &menus[1].items[4] else {
            panic!("play recent submenu")
        };
        assert_eq!(recent.items.len(), 9);
        let MenuItem::Submenu(themes) = &menus[3].items[0] else {
            panic!("theme submenu")
        };
        let checked_count = themes
            .items
            .iter()
            .filter(|i| matches!(i, MenuItem::Action { checked: true, .. }))
            .count();
        assert_eq!(checked_count, 1);
    }
}

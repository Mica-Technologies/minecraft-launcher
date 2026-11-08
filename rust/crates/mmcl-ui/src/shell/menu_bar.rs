// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Keeps the menu bar and the macOS dock menu in step with the launcher
//! (`SystemMenuBarManager.refreshDynamicMenus`, `LauncherActions.buildDockMenu`).
//!
//! [`crate::menus::build_app_menus`] builds the bar from a [`MenuState`]; this module fills
//! that state from the engine (recent packs, the theme, the three View toggles), adds the
//! standard macOS application-menu items (About, Services, Hide, Hide Others, Show All, Quit)
//! and the View separator Java had, and installs the result. [`refresh`] runs at start-up,
//! after every screen swap and after every change that moves a check mark or a recent pack
//! (`desktop` subscribes to those engine events). It is coalesced to one rebuild per effect
//! cycle and skips the native call when nothing changed.
//!
//! The dock menu is rebuilt with it on macOS only: GPUI's `set_dock_menu` rewrites the jump
//! list on Windows, which `mmcl_platform::recents` owns.

use gpui::{App, Global, Menu, MenuItem, SystemMenuType};
use mmcl_engine::{Engine, PackSummary};
use mmcl_i18n::{t, tf};
use mmcl_platform::{MenuAction, MenuEntry, RecentPack};
use mmcl_theme::ThemeId;

use crate::actions::*;
use crate::menus::{self, MenuState};
use crate::services;

/// How many packs Modpacks > Play Recent lists (`SystemMenuBarManager.RECENT_MAX`).
pub const RECENT_MAX: usize = 8;

/// Installed packs played at least once, newest first, at most `max` (`gatherRecents`). The
/// order matches the Play Recent handler's, so menu index `i` plays entry `i`.
pub fn recent_packs(packs: Vec<PackSummary>, max: usize) -> Vec<RecentPack> {
    let mut played: Vec<PackSummary> = packs.into_iter().filter(|p| p.last_played_ms > 0).collect();
    played.sort_by_key(|p| std::cmp::Reverse(p.last_played_ms));
    played
        .into_iter()
        .take(max)
        .map(|p| {
            let display = if p.friendly_name.trim().is_empty() {
                p.name.clone()
            } else {
                p.friendly_name.clone()
            };
            RecentPack::new(p.name, display, p.last_played_ms)
        })
        .collect()
}

/// What the menu bar shows, read from the engine.
pub fn menu_state(engine: &Engine) -> (MenuState, Vec<RecentPack>) {
    let s = engine.settings();
    let recents = recent_packs(engine.installed_packs(), RECENT_MAX);
    let state = MenuState {
        recent_packs: recents.iter().map(|r| r.safe_display_name()).collect(),
        theme: ThemeId::from_config_lenient(&s.theme),
        discord_rpc: s.discord_rpc,
        keep_games_window_open: s.in_game_console,
        pack_backgrounds: s.show_pack_backgrounds,
    };
    (state, recents)
}

/// The macOS application menu: About, Settings, Services, Hide / Hide Others / Show All,
/// Quit (what `useSystemMenuBar` plus the Desktop handlers gave the Java launcher).
pub fn app_menu_items(app_name: &str) -> Vec<MenuItem> {
    vec![
        MenuItem::action(tf!("dialog.about.title", app_name), About),
        MenuItem::separator(),
        MenuItem::action(t!("main.navbar.settings").into_owned(), OpenSettings),
        MenuItem::separator(),
        MenuItem::os_submenu(
            t!("menu.app.services").into_owned(),
            SystemMenuType::Services,
        ),
        MenuItem::separator(),
        MenuItem::action(tf!("menu.app.hide", app_name), HideApp),
        MenuItem::action(t!("menu.app.hideOthers").into_owned(), HideOtherApps),
        MenuItem::action(t!("menu.app.showAll").into_owned(), ShowAllApps),
        MenuItem::separator(),
        MenuItem::action(tf!("menu.dock.quit", app_name), Quit),
    ]
}

/// Completes the bar [`menus::build_app_menus`] made: the full application menu, and the
/// separator between View > Theme and the three toggles.
pub fn decorate(mut bar: Vec<Menu>) -> Vec<Menu> {
    let app_name = mmcl_core::AppIdentity::current().display_name;
    if let Some(first) = bar.first_mut() {
        first.items = app_menu_items(app_name);
    }
    let view_title = t!("menu.view.title");
    if let Some(view) = bar
        .iter_mut()
        .find(|m| m.name.as_ref() == view_title.as_ref())
        && view.items.len() > 1
        && !matches!(view.items[1], MenuItem::Separator)
    {
        view.items.insert(1, MenuItem::separator());
    }
    bar
}

/// The whole menu bar for `state`.
pub fn build(state: &MenuState) -> Vec<Menu> {
    let mut state = state.clone();
    state.recent_packs.truncate(RECENT_MAX);
    decorate(menus::build_app_menus(&state))
}

fn recent_action(index: usize) -> Option<Box<dyn gpui::Action>> {
    Some(match index {
        0 => Box::new(PlayRecent1),
        1 => Box::new(PlayRecent2),
        2 => Box::new(PlayRecent3),
        3 => Box::new(PlayRecent4),
        4 => Box::new(PlayRecent5),
        5 => Box::new(PlayRecent6),
        6 => Box::new(PlayRecent7),
        7 => Box::new(PlayRecent8),
        8 => Box::new(PlayRecent9),
        _ => return None,
    })
}

fn action_item(name: String, action: Box<dyn gpui::Action>, disabled: bool) -> MenuItem {
    MenuItem::Action {
        name: name.into(),
        action,
        os_action: None,
        checked: false,
        disabled,
    }
}

/// The GPUI action a shell-menu action maps to. A pack is played through its index in
/// `recents` (the Play Recent order); an unknown pack maps to `None`.
pub fn dock_action(action: &MenuAction, recents: &[RecentPack]) -> Option<Box<dyn gpui::Action>> {
    Some(match action {
        MenuAction::ShowLauncher => Box::new(ShowLauncher),
        MenuAction::PlayLast => Box::new(PlayLastPack),
        MenuAction::PlayModpack(name) => {
            let index = recents.iter().position(|r| &r.pack_name == name)?;
            return recent_action(index);
        }
        MenuAction::OpenLastModsFolder => Box::new(OpenLastMods),
        MenuAction::OpenBrowse => Box::new(OpenBrowse),
        MenuAction::OpenSettings => Box::new(OpenSettings),
        MenuAction::OpenHelp => Box::new(OpenHelp),
        MenuAction::Quit => Box::new(Quit),
    })
}

/// Converts the platform's dock menu definition to GPUI menu items.
pub fn dock_items(entries: &[MenuEntry], recents: &[RecentPack]) -> Vec<MenuItem> {
    entries
        .iter()
        .filter_map(|e| match e {
            MenuEntry::Item {
                label,
                action,
                enabled,
            } => dock_action(action, recents).map(|a| action_item(label.clone(), a, !enabled)),
            MenuEntry::Disabled { label } => {
                Some(action_item(label.clone(), Box::new(PlayRecent1), true))
            }
            MenuEntry::Separator => Some(MenuItem::separator()),
            MenuEntry::Submenu { label, items } => Some(MenuItem::submenu(Menu {
                name: label.clone().into(),
                items: dock_items(items, recents),
                disabled: false,
            })),
        })
        .collect()
}

/// What was installed last, and whether a rebuild is queued.
#[derive(Default)]
struct MenuSync {
    installed: Option<(MenuState, Vec<RecentPack>)>,
    queued: bool,
}

impl Global for MenuSync {}

/// Rebuilds the menu bar (and the macOS dock menu) at the end of this effect cycle. Cheap to
/// call often: calls in one cycle coalesce, and an unchanged state is not re-installed.
pub fn refresh(cx: &mut App) {
    let sync = cx.default_global::<MenuSync>();
    if sync.queued {
        return;
    }
    sync.queued = true;
    cx.defer(|cx| {
        cx.default_global::<MenuSync>().queued = false;
        refresh_now(cx);
    });
}

/// Rebuilds now (see [`refresh`]).
pub fn refresh_now(cx: &mut App) {
    let (state, recents) = match services::try_engine(cx) {
        Some(engine) => menu_state(&engine),
        None => (
            MenuState {
                theme: ThemeId::from_config_lenient(&services::backend(cx).shell_settings().theme),
                ..MenuState::default()
            },
            Vec::new(),
        ),
    };
    let next = (state, recents);
    if cx
        .try_global::<MenuSync>()
        .and_then(|s| s.installed.as_ref())
        == Some(&next)
    {
        return;
    }
    cx.set_menus(build(&next.0));
    if cfg!(target_os = "macos") {
        let app_name = mmcl_core::AppIdentity::current().display_name;
        let entries = mmcl_platform::menus::dock_menu(app_name, &next.1);
        cx.set_dock_menu(dock_items(&entries, &next.1));
    }
    cx.default_global::<MenuSync>().installed = Some(next);
}

/// Forgets what was installed, so the next [`refresh`] re-installs (an in-process restart).
pub fn invalidate(cx: &mut App) {
    cx.default_global::<MenuSync>().installed = None;
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_engine::PackId;

    fn pack(name: &str, friendly: &str, played: i64) -> PackSummary {
        PackSummary {
            id: PackId(name.into()),
            name: name.into(),
            friendly_name: friendly.into(),
            last_played_ms: played,
            never_played: played <= 0,
            ..Default::default()
        }
    }

    #[test]
    fn recents_are_played_packs_newest_first_capped_at_eight() {
        let mut packs: Vec<PackSummary> = (1..=12)
            .map(|i| pack(&format!("p{i}"), &format!("Pack {i}"), i * 100))
            .collect();
        packs.push(pack("never", "Never", 0));
        packs.push(pack("blank", "  ", 5000));
        let r = recent_packs(packs, RECENT_MAX);
        assert_eq!(r.len(), 8);
        assert_eq!(r[0].pack_name, "blank");
        assert_eq!(
            r[0].display_name, "blank",
            "a blank friendly name falls back"
        );
        assert_eq!(r[1].pack_name, "p12");
        assert!(r.iter().all(|p| p.pack_name != "never"));
    }

    fn names(items: &[MenuItem]) -> Vec<String> {
        items
            .iter()
            .map(|i| match i {
                MenuItem::Action { name, .. } => name.to_string(),
                MenuItem::Submenu(m) => format!("[{}]", m.name),
                MenuItem::SystemMenu(m) => format!("<{}>", m.name),
                MenuItem::Separator => "-".into(),
            })
            .collect()
    }

    #[test]
    fn the_app_menu_has_the_standard_macos_items() {
        let bar = build(&MenuState::default());
        let app = names(&bar[0].items);
        let app_name = mmcl_core::AppIdentity::current().display_name;
        assert_eq!(app[0], tf!("dialog.about.title", app_name));
        assert!(app.contains(&format!("<{}>", t!("menu.app.services"))));
        assert!(app.contains(&tf!("menu.app.hide", app_name)));
        assert!(app.contains(&t!("menu.app.hideOthers").into_owned()));
        assert!(app.contains(&t!("menu.app.showAll").into_owned()));
        assert_eq!(app.last().unwrap(), &tf!("menu.dock.quit", app_name));
    }

    #[test]
    fn view_menu_separates_the_theme_submenu_from_the_toggles() {
        let bar = build(&MenuState::default());
        let view = names(&bar[3].items);
        assert!(view[0].starts_with('['));
        assert_eq!(view[1], "-");
        assert_eq!(view.len(), 5);
        // Idempotent: decorating twice adds no second separator.
        let again = decorate(build(&MenuState::default()));
        assert_eq!(names(&again[3].items).len(), 5);
    }

    #[test]
    fn play_recent_is_capped_at_eight_and_checks_follow_the_state() {
        let state = MenuState {
            recent_packs: (0..12).map(|i| format!("Pack {i}")).collect(),
            theme: Some(ThemeId::Light),
            discord_rpc: true,
            keep_games_window_open: false,
            pack_backgrounds: true,
        };
        let bar = build(&state);
        let MenuItem::Submenu(recent) = &bar[1].items[4] else {
            panic!("play recent submenu")
        };
        assert_eq!(recent.items.len(), RECENT_MAX);
        let checks: Vec<bool> = bar[3].items[2..]
            .iter()
            .map(|i| matches!(i, MenuItem::Action { checked: true, .. }))
            .collect();
        assert_eq!(checks, vec![true, false, true]);
    }

    #[test]
    fn the_dock_menu_maps_every_entry_to_an_action() {
        let recents = vec![
            RecentPack::new("a", "Pack A", 30),
            RecentPack::new("b", "Pack B", 20),
        ];
        let entries = mmcl_platform::menus::dock_menu("Mica", &recents);
        let items = dock_items(&entries, &recents);
        assert_eq!(items.len(), entries.len());
        let MenuItem::Submenu(play) = &items[2] else {
            panic!("play recent submenu")
        };
        assert_eq!(names(&play.items), vec!["Pack A", "Pack B"]);
        let MenuItem::Action { action, .. } = &play.items[1] else {
            panic!("action")
        };
        assert!(action.partial_eq(&PlayRecent2));
        assert!(
            dock_action(&MenuAction::PlayModpack("zzz".into()), &recents).is_none(),
            "a pack outside the recents has no index"
        );
        let empty = dock_items(&mmcl_platform::menus::dock_menu("Mica", &[]), &[]);
        let MenuItem::Submenu(play) = &empty[2] else {
            panic!("play recent submenu")
        };
        assert!(matches!(
            play.items[..],
            [MenuItem::Action { disabled: true, .. }]
        ));
    }

    #[test]
    fn menu_strings_exist() {
        for k in [
            "dialog.about.title",
            "dialog.about.body",
            "menu.app.services",
            "menu.app.hide",
            "menu.app.hideOthers",
            "menu.app.showAll",
            "menu.dock.quit",
        ] {
            assert!(mmcl_i18n::english(k).is_some(), "{k}");
        }
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Settings and Runtime Management (gui-shell spec sections 6 and 7): every setting with its
//! save semantics, the saved accounts list, the malware scan, the MCP panel, and the runtime
//! list / refresh / delete screen.
//!
//! | Module | Java origin |
//! |---|---|
//! | [`screen`] | `MCLauncherSettingsGui`, `settingsGUI.fxml`, `SettingsGroups` |
//! | [`runtime`] | `MCLauncherRuntimeGui`, `runtimeManagementGUI.fxml` |
//! | [`logic`] | the pure helpers (`...JvmPresetLogic`, `...LanguageLogic`, `...ValidationLogic`, `SettingsOptionLabels`, `SettingsNavTile`) |
//! | [`mcp`] | what Security needs from `McpBootstrap` ([`mcp::McpPanel`], installed by `mmcl-app`) |
//! | [`widgets`] | group cards, rows, dropdown items |
//!
//! Hooks for `mmcl-app`: [`set_add_account_handler`] (the Add-account dialog of
//! `screens::accounts`; without it Add account opens the Login screen) and
//! [`mcp::set_mcp_panel`].

use std::rc::Rc;

use gpui::{App, Global, Window};
use mmcl_theme::Rgba;
use mmcl_theme::Tokens;
use mmcl_ui_kit::{HelpTopic, Icon, ScreenId, ScreenRegistry, ScreenRequest};

use super::placeholder::ScreenSpec;

pub mod logic;
pub mod mcp;
pub mod runtime;
pub mod screen;
pub mod widgets;

pub use runtime::RuntimeScreen;
pub use screen::SettingsScreen;

/// The screens this module provides.
pub const SPECS: [ScreenSpec; 2] = [
    ScreenSpec {
        id: ScreenId::SETTINGS,
        title_key: "main.navbar.settings",
        help: HelpTopic::Settings,
        allows_navigation: true,
        min: (750.0, 475.0),
        pref: None,
    },
    ScreenSpec {
        id: ScreenId::RUNTIME,
        title_key: "runtime.navbar.title",
        help: HelpTopic::RuntimeManagement,
        allows_navigation: false,
        min: (750.0, 475.0),
        pref: None,
    },
];

/// A sidebar icon colour role (`-md-icon-<hue>`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Hue {
    Blue,
    Green,
    Orange,
    Yellow,
    Cyan,
    Grey,
    Purple,
    Pink,
}

impl Hue {
    /// `(disc, glyph)` colours of this hue.
    pub fn colors(self, t: &Tokens) -> (Rgba, Rgba) {
        match self {
            Hue::Blue => (t.md_icon_blue, t.md_on_icon_blue),
            Hue::Green => (t.md_icon_green, t.md_on_icon_green),
            Hue::Orange => (t.md_icon_orange, t.md_on_icon_orange),
            Hue::Yellow => (t.md_icon_yellow, t.md_on_icon_yellow),
            Hue::Cyan => (t.md_icon_cyan, t.md_on_icon_cyan),
            Hue::Grey => (t.md_icon_grey, t.md_on_icon_grey),
            Hue::Purple => (t.md_icon_purple, t.md_on_icon_purple),
            Hue::Pink => (t.md_icon_pink, t.md_on_icon_pink),
        }
    }
}

/// One Settings category (sidebar tile + content pane).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Category {
    /// The id (`settings.nav.<id>.summary`).
    pub id: &'static str,
    /// The title key.
    pub title_key: &'static str,
    pub icon: Icon,
    pub hue: Hue,
}

impl Category {
    /// The summary key.
    pub fn summary_key(&self) -> String {
        format!("settings.nav.{}.summary", self.id)
    }
}

/// The categories, in sidebar order.
pub const CATEGORIES: [Category; 10] = [
    Category {
        id: "account",
        title_key: "settings.fxml.account",
        icon: Icon::Account,
        hue: Hue::Blue,
    },
    Category {
        id: "game",
        title_key: "settings.fxml.game",
        icon: Icon::Game,
        hue: Hue::Green,
    },
    Category {
        id: "appearance",
        title_key: "settings.fxml.appearance",
        icon: Icon::Appearance,
        hue: Hue::Orange,
    },
    Category {
        id: "advanced",
        title_key: "settings.fxml.advanced",
        icon: Icon::Advanced,
        hue: Hue::Yellow,
    },
    Category {
        id: "network",
        title_key: "settings.fxml.network",
        icon: Icon::Network,
        hue: Hue::Blue,
    },
    Category {
        id: "security",
        title_key: "settings.fxml.security",
        icon: Icon::Security,
        hue: Hue::Cyan,
    },
    Category {
        id: "system",
        title_key: "settings.fxml.system",
        icon: Icon::System,
        hue: Hue::Grey,
    },
    Category {
        id: "discord",
        title_key: "settings.fxml.discord",
        icon: Icon::Discord,
        hue: Hue::Purple,
    },
    Category {
        id: "rgb",
        title_key: "settings.fxml.rgb",
        icon: Icon::Rgb,
        hue: Hue::Pink,
    },
    Category {
        id: "about",
        title_key: "settings.fxml.about",
        icon: Icon::About,
        hue: Hue::Grey,
    },
];

/// The sidebar groups (`.settingsNavGroup`), as runs of category indices.
pub const NAV_GROUPS: [&[usize]; 5] = [&[0], &[1, 2, 3], &[4, 5, 6], &[7, 8], &[9]];

/// Category indices callers use.
pub mod category {
    pub const ACCOUNT: usize = 0;
    pub const GAME: usize = 1;
    pub const APPEARANCE: usize = 2;
    pub const ADVANCED: usize = 3;
    pub const NETWORK: usize = 4;
    pub const SECURITY: usize = 5;
    pub const SYSTEM: usize = 6;
    pub const DISCORD: usize = 7;
    pub const RGB: usize = 8;
    pub const ABOUT: usize = 9;
}

/// Opens the Add-account dialog for a window.
pub type AddAccountHandler = Rc<dyn Fn(&mut Window, &mut App)>;

#[derive(Clone)]
struct AddAccountHook(AddAccountHandler);

impl Global for AddAccountHook {}

/// Installs the Add-account opener (`AddAccountDialog.show(window)`, from `screens::accounts`).
pub fn set_add_account_handler(cx: &mut App, f: impl Fn(&mut Window, &mut App) + 'static) {
    cx.set_global(AddAccountHook(Rc::new(f)));
}

/// Opens the Add-account dialog, or the Login screen when no dialog is installed.
pub fn open_add_account(window: &mut Window, cx: &mut App) {
    match cx.try_global::<AddAccountHook>().map(|h| h.0.clone()) {
        Some(f) => f(window, cx),
        None => crate::shell::go_to(cx, ScreenRequest::new(ScreenId::LOGIN)),
    }
}

/// `showCategory(i)` from outside (Home's account chip, the account switcher): switches the
/// open Settings screen in place, else navigates there (gated) with the category.
pub fn open_category(cx: &mut App, index: usize) {
    let index = index.min(CATEGORIES.len() - 1);
    if let Some(screen) =
        crate::shell::current_screen(cx).and_then(|s| s.downcast::<SettingsScreen>())
    {
        screen.update(cx, |s, cx| s.show_category(index, cx));
        return;
    }
    crate::shell::navigate_gated(cx, ScreenRequest::with_payload(ScreenId::SETTINGS, index));
}

/// Registers Settings and Runtime Management.
pub fn register(cx: &mut App) {
    ScreenRegistry::register_view(cx, ScreenId::SETTINGS, |mut req, window, cx| {
        let category = req.take_payload::<usize>().unwrap_or(0);
        SettingsScreen::new(category, window, cx)
    });
    ScreenRegistry::register_view(cx, ScreenId::RUNTIME, |_req, window, cx| {
        RuntimeScreen::new(window, cx)
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_category_has_title_and_summary() {
        for c in CATEGORIES {
            assert!(mmcl_i18n::english(c.title_key).is_some(), "{}", c.title_key);
            assert!(mmcl_i18n::english(&c.summary_key()).is_some(), "{}", c.id);
        }
    }

    #[test]
    fn groups_cover_every_category_once_in_order() {
        let flat: Vec<usize> = NAV_GROUPS.iter().flat_map(|g| g.iter().copied()).collect();
        assert_eq!(flat, (0..CATEGORIES.len()).collect::<Vec<_>>());
        assert_eq!(CATEGORIES[category::SYSTEM].id, "system");
        assert_eq!(runtime::RETURN_CATEGORY, category::SYSTEM);
        assert_eq!(CATEGORIES[category::ABOUT].id, "about");
    }
}

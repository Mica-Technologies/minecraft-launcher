// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Home and Browse (gui-shell spec sections 3 and 4).
//!
//! | Module | Java origin | What |
//! |---|---|---|
//! | [`home`] | `MCLauncherMainGui` | [`HomeScreen`] |
//! | [`browse`] | `MCLauncherGameLibraryGui` | [`BrowseScreen`] |
//! | [`model`] | `LibraryViewModel`, `FilterOptionLabels` | filter / sort / pagination state, option ids and labels |
//! | [`home_logic`] | `buildFilteredSortedPacks`, card text | Home's pure rules |
//! | [`browse_logic`] | `collectEntries`, `LibraryCard` text | Browse's pure rules, the mod-name search cache |
//! | [`card`] | `ModpackHeroCard`, `LibraryCard` | the card elements |
//! | [`visuals`] | `applyDynamicBackground`, `LogoTransparencyDetector`, `PackBackgroundLayer` | palette, gradients, image loading |
//! | [`cycle`] | `ModpackImageCycleClock` | multi-image cycling |
//! | [`pack_actions`] | `buildPackContextMenu`, `confirmAndUninstallModpack`, `startPlay` | shared pack actions |
//! | [`backend`] | - | [`backend::LibraryBackend`], implemented over the engine |
//! | [`hooks`] | - | detail modal, Running Games, account switcher, quick start, import confirm, launch |
//!
//! Home takes an optional `String` payload: the name of a pack to bring into view (`-c <pack>`).

use std::collections::HashSet;
use std::sync::LazyLock;

use gpui::App;
use mmcl_ui_kit::{HelpTopic, ScreenId, ScreenRegistry};
use parking_lot::Mutex;

use super::placeholder::ScreenSpec;

pub mod backend;
pub mod browse;
pub mod browse_logic;
pub mod card;
pub mod controls;
pub mod cycle;
pub mod home;
pub mod home_logic;
pub mod hooks;
pub mod model;
pub mod pack_actions;
pub mod visuals;

pub use backend::{EngineLibrary, LibraryBackend, set_backend};
pub use browse::BrowseScreen;
pub use home::HomeScreen;
pub use hooks::{
    ImportConfirm, set_account_switcher_handler, set_confirm_import_handler,
    set_confirm_import_preview_handler, set_open_detail_handler, set_play_handler,
    set_quick_start_handler, set_show_running_games_handler, set_show_session_handler,
};
pub use pack_actions::{build_pack_context_menu, confirm_and_uninstall_modpack, start_play};

/// The screens this module provides.
pub const SPECS: [ScreenSpec; 2] = [
    ScreenSpec {
        id: ScreenId::HOME,
        title_key: "menu.modpacks.home",
        help: HelpTopic::MainScreen,
        allows_navigation: true,
        min: (750.0, 475.0),
        pref: None,
    },
    ScreenSpec {
        id: ScreenId::BROWSE,
        title_key: "browse.navbar.title",
        help: HelpTopic::BrowseLibrary,
        allows_navigation: true,
        min: (750.0, 475.0),
        pref: None,
    },
];

/// Registers Home and Browse.
pub fn register(cx: &mut App) {
    ScreenRegistry::register_view(cx, ScreenId::HOME, |mut req, window, cx| {
        let select = req.take_payload::<String>();
        HomeScreen::new(select, window, cx)
    });
    ScreenRegistry::register_view(cx, ScreenId::BROWSE, |_req, window, cx| {
        BrowseScreen::new(window, cx)
    });
}

/// Announcement texts dismissed this session (`AnnouncementManager.dismissAnnouncementForSession`).
static DISMISSED: LazyLock<Mutex<HashSet<String>>> = LazyLock::new(Default::default);

/// Whether a banner text was dismissed this session.
pub fn is_dismissed(text: &str) -> bool {
    DISMISSED.lock().contains(text)
}

/// Dismisses a banner text for this session.
pub fn dismiss(text: &str) {
    DISMISSED.lock().insert(text.to_string());
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    /// Every literal key passed to `t!` / `tf!` in this module exists in the English bundle
    /// (Java bundle or the Rust overlay).
    #[test]
    fn every_used_key_resolves() {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("src/screens/home_browse");
        let re = regex_lite();
        let mut missing = Vec::new();
        for entry in std::fs::read_dir(&dir).unwrap() {
            let path = entry.unwrap().path();
            let text = std::fs::read_to_string(&path).unwrap();
            for key in re(&text) {
                if mmcl_i18n::english(&key).is_none() {
                    missing.push(format!(
                        "{}: {key}",
                        path.file_name().unwrap().to_string_lossy()
                    ));
                }
            }
        }
        assert!(missing.is_empty(), "missing keys: {missing:#?}");
    }

    /// Extracts `t!("key"` / `tf!("key"` literals (plus key constants in `"a.b.c"` tables).
    fn regex_lite() -> impl Fn(&str) -> Vec<String> {
        |text: &str| {
            let mut out = Vec::new();
            for marker in [
                "t!(\"",
                "tf!(\"",
                "heading: \"",
                "sub: \"",
                "(\"main.contextMenu.",
            ] {
                let mut rest = text;
                while let Some(i) = rest.find(marker) {
                    let after = &rest[i + marker.len()..];
                    if let Some(end) = after.find('"') {
                        let key = if marker.starts_with("(\"main") {
                            format!("main.contextMenu.{}", &after[..end])
                        } else {
                            after[..end].to_string()
                        };
                        if key.contains('.') && !key.contains(' ') && !key.contains('{') {
                            out.push(key);
                        }
                    }
                    rest = after;
                }
            }
            out
        }
    }

    #[test]
    fn dismissals_are_remembered() {
        assert!(!super::is_dismissed("hello-test"));
        super::dismiss("hello-test");
        assert!(super::is_dismissed("hello-test"));
    }
}

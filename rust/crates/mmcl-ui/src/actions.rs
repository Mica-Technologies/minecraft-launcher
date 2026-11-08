// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Keyboard shortcuts (`KeyboardShortcutManager`) and the actions the menu bar dispatches.
//!
//! Global navigation chords live in the [`NAVIGABLE_CONTEXT`] key context, which the shell sets
//! on its root only while the current screen [`installs_global_shortcuts`]; Login and the
//! progress screens therefore never get them. `secondary` is Cmd on macOS and Ctrl elsewhere
//! (JavaFX "shortcut"). No binding uses Alt (Alt+Cmd+letter is a text accelerator) and bare
//! Cmd+M is left to macOS Minimize.
//!
//! | Chord | Action | Where |
//! |---|---|---|
//! | F1, secondary-shift-/ | [`OpenHelp`] | navigable screens |
//! | secondary-, | [`OpenSettings`] | navigable screens |
//! | secondary-l | [`OpenBrowse`] | navigable screens |
//! | secondary-e | [`OpenEditor`] | navigable screens |
//! | secondary-shift-m | [`OpenHome`] | navigable screens |
//! | F5 | [`Refresh`] | [`HOME_CONTEXT`] |
//! | Enter | [`PlayLast`] | [`HOME_CONTEXT`] |
//! | secondary-f | [`FocusSearch`] | [`HOME_CONTEXT`], [`BROWSE_CONTEXT`] |
//! | cmd-q | [`Quit`] | macOS, everywhere |
//!
//! Screen-specific actions ([`Refresh`], [`PlayLast`], [`FocusSearch`]) are handled by the
//! screens: set the context on the screen root (`.key_context(HOME_CONTEXT)`) and add
//! `.on_action(cx.listener(..))`. Navigation actions are handled by the shell (app-level
//! listeners in [`install_handlers`]), always behind the navigation gate.
//!
//! [`installs_global_shortcuts`]: mmcl_ui_kit::Screen::installs_global_shortcuts

use gpui::{App, KeyBinding, actions};
use mmcl_ui_kit::{ScreenId, ScreenRequest};

use crate::shell::{self, hooks};

actions!(
    mmcl,
    [
        /// Open Settings (secondary-,).
        OpenSettings,
        /// Open Browse (secondary-L).
        OpenBrowse,
        /// Open a blank modpack editor (secondary-E).
        OpenEditor,
        /// Go Home (secondary-shift-M).
        OpenHome,
        /// Open Runtime Management (menu bar).
        OpenRuntimes,
        /// Open help on the current screen's topic (F1).
        OpenHelp,
        /// Refresh the pack list (F5, Home).
        Refresh,
        /// Focus the search field (secondary-F, Home and Browse).
        FocusSearch,
        /// Play the first playable card (Enter, Home).
        PlayLast,
        /// Quit, asking first when games run (Cmd+Q, menu bar).
        Quit,
        /// Minimize the main window (menu bar).
        Minimize,
        /// Zoom / maximize the main window (menu bar).
        Zoom,
        /// Open the Mica Technologies website (menu bar).
        OpenWebsite,
    ]
);

actions!(
    mmcl_recent,
    [
        /// Play recent pack 1.
        PlayRecent1,
        /// Play recent pack 2.
        PlayRecent2,
        /// Play recent pack 3.
        PlayRecent3,
        /// Play recent pack 4.
        PlayRecent4,
        /// Play recent pack 5.
        PlayRecent5,
        /// Play recent pack 6.
        PlayRecent6,
        /// Play recent pack 7.
        PlayRecent7,
        /// Play recent pack 8.
        PlayRecent8,
        /// Play recent pack 9.
        PlayRecent9,
    ]
);

actions!(
    mmcl_view,
    [
        /// View > Theme > Automatic.
        ThemeAutomatic,
        /// View > Theme > Dark.
        ThemeDark,
        /// View > Theme > Light.
        ThemeLight,
        /// View > Theme > Blue+gray.
        ThemeBlueGray,
        /// View > Theme > Orange+purple.
        ThemeOrangePurple,
        /// View > Theme > Creeper.
        ThemeCreeper,
        /// View > Theme > Native (Mica).
        ThemeNative,
        /// View > Discord Rich Presence.
        ToggleDiscordRpc,
        /// View > Keep Running Games Window Open.
        ToggleKeepGamesWindowOpen,
        /// View > Show Pack Backgrounds.
        TogglePackBackgrounds,
    ]
);

/// The key context of screens that take the global navigation chords.
pub const NAVIGABLE_CONTEXT: &str = "Navigable";
/// The key context Home sets on its root (F5, Enter, secondary-F).
pub const HOME_CONTEXT: &str = "Home";
/// The key context Browse sets on its root (secondary-F).
pub const BROWSE_CONTEXT: &str = "Browse";
/// The Mica Technologies website (Help > Visit Mica Technologies).
pub const WEBSITE_URL: &str = "https://micatechnologies.com";

/// The global and per-screen key bindings, as `(keystrokes, context)` pairs (for tests and the
/// keyboard-shortcuts help page).
pub fn binding_table() -> Vec<(&'static str, &'static str, Option<&'static str>)> {
    let mut v = vec![
        ("f1", "OpenHelp", Some(NAVIGABLE_CONTEXT)),
        ("secondary-shift-/", "OpenHelp", Some(NAVIGABLE_CONTEXT)),
        ("secondary-,", "OpenSettings", Some(NAVIGABLE_CONTEXT)),
        ("secondary-l", "OpenBrowse", Some(NAVIGABLE_CONTEXT)),
        ("secondary-e", "OpenEditor", Some(NAVIGABLE_CONTEXT)),
        ("secondary-shift-m", "OpenHome", Some(NAVIGABLE_CONTEXT)),
        ("f5", "Refresh", Some(HOME_CONTEXT)),
        ("enter", "PlayLast", Some(HOME_CONTEXT)),
        ("secondary-f", "FocusSearch", Some("Home || Browse")),
    ];
    if cfg!(target_os = "macos") {
        v.push(("cmd-q", "Quit", None));
    }
    v
}

/// `secondary-<keys>` resolved for this OS: `KeyBinding::new` does not resolve `secondary`.
#[cfg(target_os = "macos")]
macro_rules! sec {
    ($keys:literal) => {
        concat!("cmd-", $keys)
    };
}
#[cfg(not(target_os = "macos"))]
macro_rules! sec {
    ($keys:literal) => {
        concat!("ctrl-", $keys)
    };
}

/// Binds the shortcuts (once per app).
pub fn bind_global_keys(cx: &mut App) {
    let nav = Some(NAVIGABLE_CONTEXT);
    let search = Some("Home || Browse");
    let mut bindings = vec![
        KeyBinding::new("f1", OpenHelp, nav),
        KeyBinding::new(sec!("shift-/"), OpenHelp, nav),
        KeyBinding::new(sec!(","), OpenSettings, nav),
        KeyBinding::new(sec!("l"), OpenBrowse, nav),
        KeyBinding::new(sec!("e"), OpenEditor, nav),
        KeyBinding::new(sec!("shift-m"), OpenHome, nav),
        KeyBinding::new("f5", Refresh, Some(HOME_CONTEXT)),
        KeyBinding::new("enter", PlayLast, Some(HOME_CONTEXT)),
        KeyBinding::new(sec!("f"), FocusSearch, search),
    ];
    if cfg!(target_os = "macos") {
        bindings.push(KeyBinding::new("cmd-q", Quit, None));
    }
    cx.bind_keys(bindings);
}

fn go(cx: &mut App, id: ScreenId) {
    shell::navigate_gated(cx, ScreenRequest::new(id));
}

/// Installs the app-level handlers for the navigation and menu actions (once per app).
pub fn install_handlers(cx: &mut App) {
    cx.on_action(|_: &OpenSettings, cx| go(cx, ScreenId::SETTINGS));
    cx.on_action(|_: &OpenBrowse, cx| go(cx, ScreenId::BROWSE));
    cx.on_action(|_: &OpenEditor, cx| go(cx, ScreenId::EDITOR));
    cx.on_action(|_: &OpenHome, cx| go(cx, ScreenId::HOME));
    cx.on_action(|_: &OpenRuntimes, cx| go(cx, ScreenId::RUNTIME));
    cx.on_action(|_: &OpenHelp, cx| {
        let topic = shell::current_help_topic(cx);
        hooks::show_help(cx, topic);
    });
    cx.on_action(|_: &Quit, cx| shell::request_quit(cx));
    cx.on_action(|_: &Minimize, cx| {
        shell::with_main_window(cx, |window, _| window.minimize_window());
    });
    cx.on_action(|_: &Zoom, cx| {
        shell::with_main_window(cx, |window, _| window.zoom_window());
    });
    cx.on_action(|_: &OpenWebsite, cx| mmcl_ui_kit::open_external_url(cx, WEBSITE_URL));

    cx.on_action(|_: &PlayRecent1, cx| hooks::play_recent(0, cx));
    cx.on_action(|_: &PlayRecent2, cx| hooks::play_recent(1, cx));
    cx.on_action(|_: &PlayRecent3, cx| hooks::play_recent(2, cx));
    cx.on_action(|_: &PlayRecent4, cx| hooks::play_recent(3, cx));
    cx.on_action(|_: &PlayRecent5, cx| hooks::play_recent(4, cx));
    cx.on_action(|_: &PlayRecent6, cx| hooks::play_recent(5, cx));
    cx.on_action(|_: &PlayRecent7, cx| hooks::play_recent(6, cx));
    cx.on_action(|_: &PlayRecent8, cx| hooks::play_recent(7, cx));
    cx.on_action(|_: &PlayRecent9, cx| hooks::play_recent(8, cx));

    use mmcl_theme::ThemeId;
    cx.on_action(|_: &ThemeAutomatic, cx| {
        crate::theme::set_configured_theme(cx, ThemeId::Automatic)
    });
    cx.on_action(|_: &ThemeDark, cx| crate::theme::set_configured_theme(cx, ThemeId::Dark));
    cx.on_action(|_: &ThemeLight, cx| crate::theme::set_configured_theme(cx, ThemeId::Light));
    cx.on_action(|_: &ThemeBlueGray, cx| crate::theme::set_configured_theme(cx, ThemeId::BlueGray));
    cx.on_action(|_: &ThemeOrangePurple, cx| {
        crate::theme::set_configured_theme(cx, ThemeId::OrangePurple)
    });
    cx.on_action(|_: &ThemeCreeper, cx| crate::theme::set_configured_theme(cx, ThemeId::Creeper));
    cx.on_action(|_: &ThemeNative, cx| crate::theme::set_configured_theme(cx, ThemeId::Native));
    cx.on_action(|_: &ToggleDiscordRpc, cx| hooks::menu_toggle(hooks::MenuToggle::DiscordRpc, cx));
    cx.on_action(|_: &ToggleKeepGamesWindowOpen, cx| {
        hooks::menu_toggle(hooks::MenuToggle::KeepGamesWindowOpen, cx)
    });
    cx.on_action(|_: &TogglePackBackgrounds, cx| {
        hooks::menu_toggle(hooks::MenuToggle::PackBackgrounds, cx)
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Guarded bug 27: no Alt chords, and bare Cmd+M is never bound.
    #[test]
    fn shortcuts_avoid_alt_and_bare_cmd_m() {
        for (keys, _, _) in binding_table() {
            assert!(!keys.contains("alt-"), "{keys} uses Alt");
            assert_ne!(keys, "secondary-m");
            assert_ne!(keys, "cmd-m");
        }
    }

    #[test]
    fn navigation_chords_are_scoped_to_navigable_screens() {
        for (keys, action, ctx) in binding_table() {
            if action.starts_with("Open") {
                assert_eq!(ctx, Some(NAVIGABLE_CONTEXT), "{keys}");
            }
        }
    }
}

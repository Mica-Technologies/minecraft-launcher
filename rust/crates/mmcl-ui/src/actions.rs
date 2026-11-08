// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Keyboard shortcuts (`KeyboardShortcutManager`) and the actions the menu bar dispatches.
//!
//! Global navigation chords live in the [`NAVIGABLE_CONTEXT`] key context, which the shell sets
//! on its root only while the current screen [`installs_global_shortcuts`]; Login and the
//! progress screens therefore never get them. `secondary` is Cmd on macOS and Ctrl elsewhere
//! (JavaFX "shortcut"). No binding uses Alt (Alt+Cmd+letter is a text accelerator). Cmd+M is
//! bound to [`Minimize`] on macOS only: GPUI's Window > Minimize item is a custom action, not
//! AppKit's `performMiniaturize:`, so macOS does not supply the chord itself.
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
//! | cmd-shift-h | [`OpenHome`] | macOS, navigable screens (the Java menu accelerator) |
//! | cmd-r | [`Refresh`] | macOS, everywhere (Home handles it itself; elsewhere the shell) |
//! | cmd-m | [`Minimize`] | macOS, everywhere |
//! | cmd-h | [`HideApp`] | macOS, everywhere |
//! | cmd-1 .. cmd-8 | [`PlayRecent1`] .. [`PlayRecent8`] | macOS, everywhere |
//!
//! Screen-specific actions ([`Refresh`], [`PlayLast`], [`FocusSearch`]) are handled by the
//! screens: set the context on the screen root (`.key_context(HOME_CONTEXT)`) and add
//! `.on_action(cx.listener(..))`. Navigation actions are handled by the shell (app-level
//! listeners in [`install_handlers`]), always behind the navigation gate. [`Refresh`] also has
//! an app-level handler: from any other screen it re-checks the announcements, re-fetches the
//! pack list and goes Home (`SystemMenuBarManager.refreshMain`).
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
        /// The About dialog (macOS application menu).
        About,
        /// Hide the application (macOS, Cmd+H).
        HideApp,
        /// Hide the other applications (macOS).
        HideOtherApps,
        /// Show every application again (macOS).
        ShowAllApps,
        /// Bring the launcher window forward (dock menu).
        ShowLauncher,
        /// Play the last selected modpack (dock / tray).
        PlayLastPack,
        /// Open the last selected modpack's mods folder (dock / tray).
        OpenLastMods,
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
        v.push(("cmd-shift-h", "OpenHome", Some(NAVIGABLE_CONTEXT)));
        v.push(("cmd-r", "Refresh", None));
        v.push(("cmd-m", "Minimize", None));
        v.push(("cmd-h", "HideApp", None));
        for (keys, action) in RECENT_CHORDS {
            v.push((keys, action, None));
        }
    }
    v
}

/// The Play Recent quick-launch chords (macOS): the first [`crate::shell::menu_bar::RECENT_MAX`]
/// entries of Modpacks > Play Recent.
const RECENT_CHORDS: [(&str, &str); 8] = [
    ("cmd-1", "PlayRecent1"),
    ("cmd-2", "PlayRecent2"),
    ("cmd-3", "PlayRecent3"),
    ("cmd-4", "PlayRecent4"),
    ("cmd-5", "PlayRecent5"),
    ("cmd-6", "PlayRecent6"),
    ("cmd-7", "PlayRecent7"),
    ("cmd-8", "PlayRecent8"),
];

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
        bindings.extend([
            KeyBinding::new("cmd-q", Quit, None),
            KeyBinding::new("cmd-shift-h", OpenHome, nav),
            KeyBinding::new("cmd-r", Refresh, None),
            KeyBinding::new("cmd-m", Minimize, None),
            KeyBinding::new("cmd-h", HideApp, None),
            KeyBinding::new("cmd-1", PlayRecent1, None),
            KeyBinding::new("cmd-2", PlayRecent2, None),
            KeyBinding::new("cmd-3", PlayRecent3, None),
            KeyBinding::new("cmd-4", PlayRecent4, None),
            KeyBinding::new("cmd-5", PlayRecent5, None),
            KeyBinding::new("cmd-6", PlayRecent6, None),
            KeyBinding::new("cmd-7", PlayRecent7, None),
            KeyBinding::new("cmd-8", PlayRecent8, None),
        ]);
    }
    cx.bind_keys(bindings);
}

/// Navigation from a shortcut, menu or the dock: the main window comes forward (a dock or
/// Running Games window click may have started it), then the gate decides.
fn go(cx: &mut App, id: ScreenId) {
    if shell::is_started(cx) {
        shell::request_focus(cx);
    }
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
    cx.on_action(|_: &Refresh, cx| shell::shell_actions::refresh_and_go_home(cx));
    cx.on_action(|_: &About, cx| shell::shell_actions::show_about(cx));
    cx.on_action(|_: &HideApp, cx| cx.hide());
    cx.on_action(|_: &HideOtherApps, cx| cx.hide_other_apps());
    cx.on_action(|_: &ShowAllApps, cx| cx.unhide_other_apps());
    cx.on_action(|_: &ShowLauncher, cx| {
        shell::shell_actions::run(mmcl_platform::MenuAction::ShowLauncher, cx)
    });
    cx.on_action(|_: &PlayLastPack, cx| {
        shell::shell_actions::run(mmcl_platform::MenuAction::PlayLast, cx)
    });
    cx.on_action(|_: &OpenLastMods, cx| {
        shell::shell_actions::run(mmcl_platform::MenuAction::OpenLastModsFolder, cx)
    });

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

    /// Guarded bug 27: no Alt chords; bare Cmd+M only ever means Minimize (macOS), and the
    /// secondary-M chord stays unbound on every OS.
    #[test]
    fn shortcuts_avoid_alt_and_bare_cmd_m() {
        for (keys, action, ctx) in binding_table() {
            assert!(!keys.contains("alt-"), "{keys} uses Alt");
            assert_ne!(keys, "secondary-m");
            if keys == "cmd-m" {
                assert_eq!((action, ctx), ("Minimize", None));
            }
        }
    }

    #[test]
    fn no_chord_is_bound_twice_in_one_context() {
        let table = binding_table();
        let mut seen = std::collections::HashSet::new();
        for (keys, _, ctx) in &table {
            assert!(seen.insert((*keys, *ctx)), "{keys} bound twice in {ctx:?}");
        }
    }

    /// Play Recent quick-launch covers exactly the entries the submenu lists (Java caps the
    /// list at eight).
    #[test]
    fn recent_chords_match_the_submenu_cap() {
        assert_eq!(RECENT_CHORDS.len(), crate::shell::menu_bar::RECENT_MAX);
        for (i, (keys, action)) in RECENT_CHORDS.iter().enumerate() {
            assert_eq!(*keys, format!("cmd-{}", i + 1));
            assert_eq!(*action, format!("PlayRecent{}", i + 1));
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

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launcher's screens, one module per screen group. Each module owns everything inside it
//! and exposes `pub fn register(cx: &mut App)`, which registers its screens with
//! [`mmcl_ui_kit::ScreenRegistry`] (and any shell hooks it provides, such as the help opener).
//!
//! | Module | Screens and parts |
//! |---|---|
//! | [`home_browse`] | Home (hero cards, filter/sort/pagination, pack context menu), Browse (library cards, install/uninstall, Modrinth import) |
//! | [`accounts`] | Login, `SignInPanel`, Add-account dialog (web view + device code), account switcher, avatars |
//! | [`settings`] | Settings (every setting, saved accounts, malware scan), Runtime Management |
//! | [`misc`] | Progress, Launch progress, the Help window, anything else in the gui-shell spec |
//!
//! Until a module fills a screen in, it registers a [`placeholder::PlaceholderScreen`] with the
//! real screen's identity.

use gpui::App;

pub mod accounts;
pub mod home_browse;
pub mod misc;
pub mod placeholder;
pub mod settings;

/// Registers every screen of this crate (`mmcl-app` calls it once per app, after
/// [`crate::init`]; it registers the editor and Running Games screens itself).
pub fn register_all(cx: &mut App) {
    home_browse::register(cx);
    accounts::register(cx);
    settings::register(cx);
    misc::register(cx);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_placeholder_title_key_resolves() {
        let specs = home_browse::SPECS
            .iter()
            .chain(accounts::SPECS.iter())
            .chain(settings::SPECS.iter())
            .chain(misc::SPECS.iter());
        let mut ids = std::collections::HashSet::new();
        for spec in specs {
            assert!(
                mmcl_i18n::english(spec.title_key).is_some(),
                "{}",
                spec.title_key
            );
            assert!(ids.insert(spec.id), "{:?} registered twice", spec.id);
        }
        assert_eq!(ids.len(), 7);
    }
}

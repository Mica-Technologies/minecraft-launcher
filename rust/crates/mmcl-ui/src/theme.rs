// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Theming hookup for the launcher windows (`applyTheme`, `forceThemeChange`,
//! `installCurrentThemeStylesheets`).
//!
//! The palette is the [`ActiveTheme`] global from `mmcl-ui-kit`; every view reads tokens at
//! render time, so a theme change is "resolve, set the global, refresh windows". This module adds
//! what the windows need around that:
//!
//! - [`resolve_for`]: configured theme + the window's OS appearance + Mica support ->
//!   [`ActiveTheme`].
//! - [`apply_main`]: the main window's apply step. Native chrome (backdrop, DWM dark title bar)
//!   is touched **only when the token sheet actually changes** (guarded bug 8: re-running it on
//!   plain navigation flickered), and a sheet change toggles the backdrop through opaque before
//!   Mica (guarded bug 10: DWM caches Mica).
//! - [`os_appearance_changed`]: OS light/dark events act only for themes that follow the OS
//!   (Automatic, Native; guarded bug 11).
//! - [`secondary_window_background`] / [`secondary_fill`]: secondary windows (help, add account,
//!   wizard) always paint a solid floor and are never composited over Mica.

use gpui::{App, Global, Hsla, Window, WindowAppearance, WindowBackgroundAppearance};
use mmcl_theme::{Platform, Theme, ThemeId, TokenSheet};
use mmcl_ui_kit::{ActiveTheme, set_theme};

use crate::services;
use crate::shell::hooks;

/// Whether a GPUI window appearance is dark.
pub fn is_dark(appearance: WindowAppearance) -> bool {
    matches!(
        appearance,
        WindowAppearance::Dark | WindowAppearance::VibrantDark
    )
}

/// Whether Mica can show behind the main window (probed once the window exists, Windows only).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct MicaSupport(pub bool);

impl Global for MicaSupport {}

/// What the native chrome depends on: the token sheet and whether Mica can show.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ChromeKey {
    /// The token sheet.
    pub sheet: TokenSheet,
    /// Whether Mica was supported when the chrome was applied.
    pub mica_supported: bool,
}

impl ChromeKey {
    /// The key of a resolved theme.
    pub fn of(theme: ActiveTheme) -> Self {
        Self {
            sheet: theme.theme.sheet,
            mica_supported: theme.mica_supported,
        }
    }
}

/// What the native chrome was last applied for (per session).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct AppliedChrome(pub Option<ChromeKey>);

impl Global for AppliedChrome {}

/// Resolves the theme for a configured value and OS appearance.
pub fn resolve(configured: &str, os_dark: bool, mica_supported: bool) -> ActiveTheme {
    ActiveTheme {
        theme: Theme::from_config(configured, os_dark, Platform::current()),
        mica_supported,
    }
}

/// Resolves the configured theme for `window`'s OS appearance.
pub fn resolve_for(window: &Window, cx: &App) -> ActiveTheme {
    let configured = services::backend(cx).shell_settings().theme;
    let mica = cx
        .try_global::<MicaSupport>()
        .copied()
        .unwrap_or_default()
        .0;
    resolve(&configured, is_dark(window.appearance()), mica)
}

/// What [`plan`] decided to do for a theme apply.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ApplyPlan {
    /// Set the global (the resolved theme differs from the current one, or forced).
    pub set_global: bool,
    /// Re-run the native chrome (the token sheet changed, or never applied).
    pub chrome: bool,
}

/// The pure decision of [`apply_main`].
///
/// The chrome re-runs when the sheet changes **or** when Mica support changes: the first screen
/// applies the theme before the window is probed for Mica, so without the second check the
/// chrome stayed opaque (GPUI clears an opaque window to white) under Native's transparent
/// palette.
pub fn plan(
    current: Option<ActiveTheme>,
    next: ActiveTheme,
    applied: Option<ChromeKey>,
    force: bool,
) -> ApplyPlan {
    ApplyPlan {
        set_global: force || current != Some(next),
        chrome: applied != Some(ChromeKey::of(next)),
    }
}

/// Applies the configured theme to the main window. `force` re-sets the global and runs the
/// theme-changed hooks even when nothing changed (`forceThemeChange`).
pub fn apply_main(window: &mut Window, cx: &mut App, force: bool) {
    let next = resolve_for(window, cx);
    let current = cx.try_global::<ActiveTheme>().copied();
    let applied = cx
        .try_global::<AppliedChrome>()
        .copied()
        .unwrap_or_default()
        .0;
    let decision = plan(current, next, applied, force);
    if decision.set_global {
        set_theme(cx, next);
    }
    if decision.chrome {
        apply_native_chrome(window, next, applied.is_some());
        cx.set_global(AppliedChrome(Some(ChromeKey::of(next))));
    }
    if decision.set_global {
        hooks::run_theme_changed(cx);
    }
}

/// The OS switched light/dark: only Automatic and Native follow it.
pub fn os_appearance_changed(window: &mut Window, cx: &mut App) {
    let configured = services::backend(cx).shell_settings().theme;
    let follows = ThemeId::from_config_lenient(&configured).is_some_and(ThemeId::follows_os);
    if follows {
        apply_main(window, cx, false);
    }
}

fn apply_native_chrome(window: &mut Window, theme: ActiveTheme, was_applied: bool) {
    let background = theme.window_background(false);
    if was_applied && background == WindowBackgroundAppearance::MicaBackdrop {
        // DWM ignores a repeated Mica request, so a dark -> light flip kept the old tint.
        window.set_background_appearance(WindowBackgroundAppearance::Opaque);
    }
    if background == WindowBackgroundAppearance::MicaBackdrop {
        // GPUI sets the DWM system backdrop but not the frame extension. With our custom
        // caption, DWM then paints Mica only in the (absent) frame and the client area shows
        // white, so extend the frame over the whole client area (Java's issue #80 fix too).
        let _ = mmcl_platform::chrome::extend_frame_into_client_area(&*window);
    }
    window.set_background_appearance(background);
    let _ = mmcl_platform::chrome::apply_title_bar_dark_mode(&*window, !theme.is_light_chrome());
}

/// Probes Mica support on a realised window (Windows) and records it.
pub fn probe_mica(window: &Window, cx: &mut App) -> bool {
    let supported = cfg!(windows) && mmcl_platform::chrome::supports_mica(window);
    cx.set_global(MicaSupport(supported));
    supported
}

/// The background appearance for a secondary window (always opaque).
pub fn secondary_window_background() -> WindowBackgroundAppearance {
    WindowBackgroundAppearance::Opaque
}

/// The solid floor a secondary window paints behind its content.
pub fn secondary_fill(cx: &App) -> Hsla {
    use mmcl_ui_kit::ThemeExt as _;
    cx.launcher_theme().solid_bg(true)
}

/// Applies the title-bar mode for a secondary window (call once it is open; dialogs pick the
/// theme at creation, the help window again on every theme change).
pub fn install_theme_on(window: &mut Window, cx: &App) {
    use mmcl_ui_kit::ThemeExt as _;
    let theme = cx.launcher_theme();
    let _ = mmcl_platform::chrome::apply_title_bar_dark_mode(&*window, !theme.is_light_chrome());
}

/// Saves a theme picked from the menu bar and applies it.
pub fn set_configured_theme(cx: &mut App, id: ThemeId) {
    if let Some(engine) = services::try_engine(cx)
        && let Err(e) = engine.set(mmcl_engine::Setting::Theme(id.config_value().to_string()))
    {
        tracing::warn!("{}", mmcl_i18n::tf!("log.ui.themeSaveFailed", e.message()));
    }
    crate::shell::force_theme_refresh(cx);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn appearance_mapping() {
        assert!(is_dark(WindowAppearance::Dark));
        assert!(is_dark(WindowAppearance::VibrantDark));
        assert!(!is_dark(WindowAppearance::Light));
        assert!(!is_dark(WindowAppearance::VibrantLight));
    }

    /// Guarded bug 20: the saved value matches case-insensitively.
    #[test]
    fn saved_theme_matches_case_insensitively() {
        let a = resolve("native (mica)", true, true);
        let b = resolve("Native (Mica)", true, true);
        assert_eq!(a, b);
        assert_eq!(a.theme.id, ThemeId::Native);
    }

    /// Guarded bug 8: a plain navigation (same theme) neither re-sets the global nor touches
    /// native chrome.
    #[test]
    fn plain_navigation_does_not_touch_chrome() {
        let t = resolve("Dark", true, false);
        let p = plan(Some(t), t, Some(ChromeKey::of(t)), false);
        assert_eq!(
            p,
            ApplyPlan {
                set_global: false,
                chrome: false
            }
        );
        let forced = plan(Some(t), t, Some(ChromeKey::of(t)), true);
        assert!(forced.set_global && !forced.chrome);
    }

    /// The first screen applies Native before the Mica probe; the forced apply after the probe
    /// must re-run the chrome so the window becomes Mica instead of staying opaque white.
    #[test]
    fn mica_support_change_reapplies_chrome() {
        let before_probe = resolve("Native (Mica)", true, false);
        let after_probe = resolve("Native (Mica)", true, true);
        assert_eq!(before_probe.theme.sheet, after_probe.theme.sheet);
        let p = plan(
            Some(before_probe),
            after_probe,
            Some(ChromeKey::of(before_probe)),
            true,
        );
        assert!(p.set_global && p.chrome);
    }

    #[test]
    fn a_sheet_change_reapplies_chrome() {
        let dark = resolve("Dark", true, false);
        let light = resolve("Light", true, false);
        let p = plan(Some(dark), light, Some(ChromeKey::of(dark)), false);
        assert!(p.set_global && p.chrome);
        let first = plan(None, dark, None, false);
        assert!(
            first.set_global && first.chrome,
            "first apply always runs chrome"
        );
    }

    #[test]
    fn os_flip_changes_automatic_only_through_the_sheet() {
        let dark = resolve("Automatic", true, false);
        let light = resolve("Automatic", false, false);
        assert_ne!(dark.theme.sheet, light.theme.sheet);
        let fixed_dark = resolve("Creeper", true, false);
        let fixed_light = resolve("Creeper", false, false);
        assert_eq!(fixed_dark.theme.sheet, fixed_light.theme.sheet);
    }
}

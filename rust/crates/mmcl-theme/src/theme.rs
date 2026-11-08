// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The configured theme, and how it resolves to a token sheet per OS and dark mode.

use crate::{Rgba, TokenSheet, Tokens};

/// The seven themes a user can pick (config key `theme`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ThemeId {
    /// `"Automatic"`: dark or light following the OS.
    Automatic,
    /// `"Dark"`.
    Dark,
    /// `"Light"`.
    Light,
    /// `"Blue+gray"`.
    BlueGray,
    /// `"Orange+purple"`.
    OrangePurple,
    /// `"Creeper"`.
    Creeper,
    /// `"Native (Mica)"`: translucent over the OS backdrop where there is one.
    Native,
}

impl ThemeId {
    /// Every theme, in the order the Settings dropdown lists them (Java `ALLOWED_THEMES`).
    pub const ALL: [ThemeId; 7] = [
        ThemeId::Automatic,
        ThemeId::Dark,
        ThemeId::Light,
        ThemeId::BlueGray,
        ThemeId::OrangePurple,
        ThemeId::Creeper,
        ThemeId::Native,
    ];

    /// The default theme (`"Native (Mica)"`).
    pub const DEFAULT: ThemeId = ThemeId::Native;

    /// The config key the theme is stored under.
    pub const CONFIG_KEY: &'static str = "theme";

    /// Parses the exact config string. Use [`ThemeId::from_config_lenient`] to also accept other
    /// capitalisations.
    pub fn from_config(s: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|t| t.config_value() == s)
    }

    /// Parses a config string ignoring case and surrounding whitespace, the way Settings matches
    /// the saved value against the canonical list (an old capitalisation bug otherwise reset
    /// "Native (Mica)" to Automatic every launch).
    pub fn from_config_lenient(s: &str) -> Option<Self> {
        let s = s.trim();
        Self::ALL
            .into_iter()
            .find(|t| t.config_value().eq_ignore_ascii_case(s))
    }

    /// The exact string written to the config.
    pub const fn config_value(self) -> &'static str {
        match self {
            ThemeId::Automatic => "Automatic",
            ThemeId::Dark => "Dark",
            ThemeId::Light => "Light",
            ThemeId::BlueGray => "Blue+gray",
            ThemeId::OrangePurple => "Orange+purple",
            ThemeId::Creeper => "Creeper",
            ThemeId::Native => "Native (Mica)",
        }
    }

    /// The localization key of the theme's label in Settings.
    pub const fn label_key(self) -> &'static str {
        match self {
            ThemeId::Automatic => "settings.theme.option.automatic",
            ThemeId::Dark => "settings.theme.option.dark",
            ThemeId::Light => "settings.theme.option.light",
            ThemeId::BlueGray => "settings.theme.option.blueGray",
            ThemeId::OrangePurple => "settings.theme.option.orangePurple",
            ThemeId::Creeper => "settings.theme.option.creeper",
            ThemeId::Native => "settings.theme.option.native",
        }
    }

    /// Whether the theme depends on the OS dark/light preference, so OS theme events must
    /// re-apply it (Automatic and Native only).
    pub const fn follows_os(self) -> bool {
        matches!(self, ThemeId::Automatic | ThemeId::Native)
    }
}

impl Default for ThemeId {
    fn default() -> Self {
        Self::DEFAULT
    }
}

/// The desktop OS family, for the per-OS parts of theme resolution.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Platform {
    /// Windows.
    Windows,
    /// macOS.
    MacOs,
    /// Linux and other Unix desktops (no system backdrop).
    Linux,
}

impl Platform {
    /// The platform this build targets.
    pub const fn current() -> Self {
        if cfg!(target_os = "windows") {
            Platform::Windows
        } else if cfg!(target_os = "macos") {
            Platform::MacOs
        } else {
            Platform::Linux
        }
    }

    /// Whether the OS can composite a backdrop (Mica / vibrancy) behind a window at all.
    pub const fn has_backdrop(self) -> bool {
        matches!(self, Platform::Windows | Platform::MacOs)
    }
}

/// Resolves a theme to its token sheet.
///
/// | Theme | Sheet |
/// |---|---|
/// | Dark / Light / Blue+gray / Orange+purple / Creeper | their own |
/// | Automatic | `os_dark ? Dark : Light` |
/// | Native (Mica) | macOS / Windows: `os_dark ? Native : NativeLight`; Linux: `os_dark ? Dark : Light` |
///
/// The Linux rule is the main window's (`switchToNativeTheme`), used everywhere: with no
/// backdrop the translucent native tokens would be near-invisible.
pub const fn resolve(theme: ThemeId, os_dark: bool, platform: Platform) -> TokenSheet {
    match theme {
        ThemeId::Dark => TokenSheet::Dark,
        ThemeId::Light => TokenSheet::Light,
        ThemeId::BlueGray => TokenSheet::BlueGray,
        ThemeId::OrangePurple => TokenSheet::OrangePurple,
        ThemeId::Creeper => TokenSheet::Creeper,
        ThemeId::Automatic => {
            if os_dark {
                TokenSheet::Dark
            } else {
                TokenSheet::Light
            }
        }
        ThemeId::Native => match (platform.has_backdrop(), os_dark) {
            (true, true) => TokenSheet::Native,
            (true, false) => TokenSheet::NativeLight,
            (false, true) => TokenSheet::Dark,
            (false, false) => TokenSheet::Light,
        },
    }
}

/// Resolves a raw config string: exact or case-insensitive match, anything else -> `Dark`
/// (Java's `default` branch).
pub fn resolve_config(value: &str, os_dark: bool, platform: Platform) -> TokenSheet {
    match ThemeId::from_config_lenient(value) {
        Some(theme) => resolve(theme, os_dark, platform),
        None => TokenSheet::Dark,
    }
}

/// Resolves when the OS dark-mode reading may be unavailable (`None`: the detector failed).
///
/// Automatic then resolves to `None`, meaning "apply nothing and keep the previous sheet", as
/// Java did. Native falls back to dark (Java's `osDark = true` default for secondary windows).
pub const fn resolve_detected(
    theme: ThemeId,
    os_dark: Option<bool>,
    platform: Platform,
) -> Option<TokenSheet> {
    match (theme, os_dark) {
        (ThemeId::Automatic, None) => None,
        (_, Some(dark)) => Some(resolve(theme, dark, platform)),
        (_, None) => Some(resolve(theme, true, platform)),
    }
}

/// Whether the window chrome (title bar) should be in light mode: the resolved sheet is `Light`
/// or `NativeLight`.
///
/// Java's `isLightChrome` was `Light || (Native && !osDark)`, which gave Automatic-on-a-light-OS
/// a dark title bar over a light window; deriving it from the resolved sheet fixes that.
pub const fn is_light_chrome(theme: ThemeId, os_dark: bool) -> bool {
    // Every platform agrees on light vs dark for a given theme; only native vs opaque differs.
    resolve(theme, os_dark, Platform::Windows).is_light()
}

/// The first Windows build with the system Mica backdrop (`DWMWA_SYSTEMBACKDROP_TYPE`): 22H2.
pub const MICA_MIN_WINDOWS_BUILD: u32 = 22621;

/// Whether a Windows build number supports Mica (no realised window needed).
pub const fn mica_supported(windows_build: u32) -> bool {
    windows_build >= MICA_MIN_WINDOWS_BUILD
}

/// The OS backdrop a window should request.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Backdrop {
    /// Opaque window, no backdrop.
    None,
    /// Windows 11 22H2+ Mica (`DWMSBT_MAINWINDOW`).
    Mica,
    /// macOS vibrancy (NSVisualEffectView; GPUI `Blurred`).
    Vibrancy,
}

/// The backdrop for the main window: only the native sheets get one, and only where the OS
/// provides it (macOS always, Windows when `mica_supported`).
pub const fn backdrop(sheet: TokenSheet, platform: Platform, mica_supported: bool) -> Backdrop {
    if !sheet.is_native() {
        return Backdrop::None;
    }
    match platform {
        Platform::MacOs => Backdrop::Vibrancy,
        Platform::Windows if mica_supported => Backdrop::Mica,
        _ => Backdrop::None,
    }
}

/// What a window paints behind its content: `None` means transparent (a backdrop shows
/// through), `Some(colour)` a solid fill.
///
/// Secondary windows are always solid (they are not set up for composition; transparency there
/// showed white-on-white). The main window is transparent only when [`backdrop`] gives one; a
/// native sheet without a backdrop (Windows 10, pre-22H2) paints its solid floor, since a
/// transparent window with no backdrop renders black.
pub fn window_fill(
    sheet: TokenSheet,
    platform: Platform,
    mica_supported: bool,
    secondary_window: bool,
) -> Option<Rgba> {
    if !secondary_window && backdrop(sheet, platform, mica_supported) != Backdrop::None {
        None
    } else {
        Some(sheet.solid_bg(secondary_window))
    }
}

/// Whether a theme pass must re-run the native chrome pipeline (backdrop, title-bar mode,
/// caption colour): only when the sheet actually changed. Plain navigations re-applying the
/// same sheet must not touch the chrome (it flickered on every screen change).
pub fn chrome_changed(previous: Option<TokenSheet>, next: TokenSheet) -> bool {
    previous != Some(next)
}

/// A fully resolved theme: what the user picked, what it resolved to and the inputs used.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Theme {
    /// The configured theme.
    pub id: ThemeId,
    /// The token sheet it resolved to.
    pub sheet: TokenSheet,
    /// The OS dark-mode reading used.
    pub os_dark: bool,
    /// The platform used.
    pub platform: Platform,
}

impl Theme {
    /// Resolves `id` for the given OS state.
    pub const fn new(id: ThemeId, os_dark: bool, platform: Platform) -> Self {
        Theme {
            id,
            sheet: resolve(id, os_dark, platform),
            os_dark,
            platform,
        }
    }

    /// Resolves a raw config value (unknown values give the Dark sheet; `id` is then `Dark`).
    pub fn from_config(value: &str, os_dark: bool, platform: Platform) -> Self {
        Self::new(
            ThemeId::from_config_lenient(value).unwrap_or(ThemeId::Dark),
            os_dark,
            platform,
        )
    }

    /// The colour tokens.
    pub const fn tokens(&self) -> &'static Tokens {
        self.sheet.tokens()
    }

    /// Whether the title bar should be light.
    pub const fn is_light_chrome(&self) -> bool {
        self.sheet.is_light()
    }

    /// The solid background (see [`TokenSheet::solid_bg`]).
    pub fn solid_bg(&self, secondary_window: bool) -> Rgba {
        self.sheet.solid_bg(secondary_window)
    }

    /// The main window's backdrop.
    pub const fn backdrop(&self, mica_supported: bool) -> Backdrop {
        backdrop(self.sheet, self.platform, mica_supported)
    }

    /// The window fill (see [`window_fill`]).
    pub fn window_fill(&self, mica_supported: bool, secondary_window: bool) -> Option<Rgba> {
        window_fill(self.sheet, self.platform, mica_supported, secondary_window)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ALL_PLATFORMS: [Platform; 3] = [Platform::Windows, Platform::MacOs, Platform::Linux];

    #[test]
    fn config_strings_are_exact() {
        let expected = [
            "Automatic",
            "Dark",
            "Light",
            "Blue+gray",
            "Orange+purple",
            "Creeper",
            "Native (Mica)",
        ];
        for (theme, value) in ThemeId::ALL.into_iter().zip(expected) {
            assert_eq!(theme.config_value(), value);
            assert_eq!(ThemeId::from_config(value), Some(theme));
        }
        assert_eq!(ThemeId::default().config_value(), "Native (Mica)");
        assert_eq!(ThemeId::from_config("native (mica)"), None);
        assert_eq!(ThemeId::from_config("Retro"), None);
        assert_eq!(ThemeId::from_config(""), None);
    }

    #[test]
    fn saved_value_matched_case_insensitively() {
        assert_eq!(
            ThemeId::from_config_lenient("native (mica)"),
            Some(ThemeId::Native)
        );
        assert_eq!(
            ThemeId::from_config_lenient("  BLUE+GRAY "),
            Some(ThemeId::BlueGray)
        );
        assert_eq!(ThemeId::from_config_lenient("Retro"), None);
    }

    #[test]
    fn every_theme_has_a_label_key() {
        let mut keys: Vec<_> = ThemeId::ALL.iter().map(|t| t.label_key()).collect();
        keys.sort();
        keys.dedup();
        assert_eq!(keys.len(), 7);
        // The keys exist in the Java bundle.
        let bundle = std::fs::read_to_string(
            std::path::Path::new(crate::tokens::CSS_DIR).join("../lang/DisplayStrings.properties"),
        )
        .expect("read bundle");
        for theme in ThemeId::ALL {
            let prefix = format!("{}=", theme.label_key());
            assert!(bundle.lines().any(|l| l.starts_with(&prefix)), "{prefix}");
        }
    }

    #[test]
    fn fixed_themes_ignore_the_os() {
        for platform in ALL_PLATFORMS {
            for dark in [true, false] {
                assert_eq!(resolve(ThemeId::Dark, dark, platform), TokenSheet::Dark);
                assert_eq!(resolve(ThemeId::Light, dark, platform), TokenSheet::Light);
                assert_eq!(
                    resolve(ThemeId::BlueGray, dark, platform),
                    TokenSheet::BlueGray
                );
                assert_eq!(
                    resolve(ThemeId::OrangePurple, dark, platform),
                    TokenSheet::OrangePurple
                );
                assert_eq!(
                    resolve(ThemeId::Creeper, dark, platform),
                    TokenSheet::Creeper
                );
            }
        }
    }

    #[test]
    fn automatic_follows_the_os() {
        for platform in ALL_PLATFORMS {
            assert_eq!(
                resolve(ThemeId::Automatic, true, platform),
                TokenSheet::Dark
            );
            assert_eq!(
                resolve(ThemeId::Automatic, false, platform),
                TokenSheet::Light
            );
        }
    }

    #[test]
    fn native_uses_translucent_sheets_only_where_there_is_a_backdrop() {
        for platform in [Platform::Windows, Platform::MacOs] {
            assert_eq!(resolve(ThemeId::Native, true, platform), TokenSheet::Native);
            assert_eq!(
                resolve(ThemeId::Native, false, platform),
                TokenSheet::NativeLight
            );
        }
        assert_eq!(
            resolve(ThemeId::Native, true, Platform::Linux),
            TokenSheet::Dark
        );
        assert_eq!(
            resolve(ThemeId::Native, false, Platform::Linux),
            TokenSheet::Light
        );
    }

    #[test]
    fn unknown_config_values_resolve_to_dark() {
        assert_eq!(
            resolve_config("Retro", false, Platform::Windows),
            TokenSheet::Dark
        );
        assert_eq!(resolve_config("", false, Platform::MacOs), TokenSheet::Dark);
        assert_eq!(
            resolve_config("light", true, Platform::Linux),
            TokenSheet::Light
        );
        assert_eq!(
            Theme::from_config("Retro", false, Platform::Windows).id,
            ThemeId::Dark
        );
    }

    #[test]
    fn failed_detector_keeps_automatic_unchanged() {
        assert_eq!(
            resolve_detected(ThemeId::Automatic, None, Platform::Windows),
            None
        );
        assert_eq!(
            resolve_detected(ThemeId::Automatic, Some(false), Platform::Windows),
            Some(TokenSheet::Light)
        );
        assert_eq!(
            resolve_detected(ThemeId::Native, None, Platform::Windows),
            Some(TokenSheet::Native)
        );
        assert_eq!(
            resolve_detected(ThemeId::Creeper, None, Platform::Linux),
            Some(TokenSheet::Creeper)
        );
    }

    #[test]
    fn light_chrome_follows_the_resolved_sheet() {
        assert!(is_light_chrome(ThemeId::Light, true));
        assert!(is_light_chrome(ThemeId::Native, false));
        assert!(!is_light_chrome(ThemeId::Native, true));
        // Java said dark chrome here, over a light window.
        assert!(is_light_chrome(ThemeId::Automatic, false));
        assert!(!is_light_chrome(ThemeId::Automatic, true));
        for theme in [
            ThemeId::Dark,
            ThemeId::BlueGray,
            ThemeId::OrangePurple,
            ThemeId::Creeper,
        ] {
            assert!(!is_light_chrome(theme, false));
        }
        for platform in ALL_PLATFORMS {
            for theme in ThemeId::ALL {
                for dark in [true, false] {
                    assert_eq!(
                        Theme::new(theme, dark, platform).is_light_chrome(),
                        is_light_chrome(theme, dark)
                    );
                }
            }
        }
    }

    #[test]
    fn only_automatic_and_native_follow_the_os() {
        let following: Vec<_> = ThemeId::ALL
            .into_iter()
            .filter(|t| t.follows_os())
            .collect();
        assert_eq!(following, vec![ThemeId::Automatic, ThemeId::Native]);
    }

    #[test]
    fn mica_needs_22h2() {
        assert!(!mica_supported(19045));
        assert!(!mica_supported(22000));
        assert!(mica_supported(22621));
        assert!(mica_supported(26100));
    }

    #[test]
    fn backdrops_and_fills() {
        assert_eq!(
            backdrop(TokenSheet::Native, Platform::Windows, true),
            Backdrop::Mica
        );
        assert_eq!(
            backdrop(TokenSheet::NativeLight, Platform::Windows, false),
            Backdrop::None
        );
        assert_eq!(
            backdrop(TokenSheet::Native, Platform::MacOs, false),
            Backdrop::Vibrancy
        );
        assert_eq!(
            backdrop(TokenSheet::Native, Platform::Linux, true),
            Backdrop::None
        );
        assert_eq!(
            backdrop(TokenSheet::Dark, Platform::Windows, true),
            Backdrop::None
        );

        // Native falls back to an opaque bg where no backdrop exists.
        assert_eq!(
            window_fill(TokenSheet::Native, Platform::Windows, true, false),
            None
        );
        assert_eq!(
            window_fill(TokenSheet::Native, Platform::Windows, false, false),
            Some(Rgba::opaque(0x14181F))
        );
        // Secondary windows are always solid.
        assert_eq!(
            window_fill(TokenSheet::Native, Platform::Windows, true, true),
            Some(Rgba::opaque(0x1C1B1F))
        );
        assert_eq!(
            window_fill(TokenSheet::BlueGray, Platform::MacOs, true, false),
            Some(Rgba::opaque(0x0E141D))
        );
        let theme = Theme::new(ThemeId::Native, false, Platform::MacOs);
        assert_eq!(theme.backdrop(false), Backdrop::Vibrancy);
        assert_eq!(theme.window_fill(false, false), None);
        assert_eq!(theme.solid_bg(true), Rgba::opaque(0xF5F6FA));
        assert_eq!(theme.tokens(), TokenSheet::NativeLight.tokens());
    }

    #[test]
    fn chrome_only_reapplied_on_a_real_change() {
        assert!(chrome_changed(None, TokenSheet::Dark));
        assert!(chrome_changed(
            Some(TokenSheet::Native),
            TokenSheet::NativeLight
        ));
        assert!(!chrome_changed(Some(TokenSheet::Dark), TokenSheet::Dark));
    }

    #[test]
    fn current_platform_is_consistent() {
        let p = Platform::current();
        assert_eq!(p.has_backdrop(), p != Platform::Linux);
    }
}

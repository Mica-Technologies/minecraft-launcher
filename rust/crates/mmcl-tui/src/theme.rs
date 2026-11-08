// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The terminal theme (Java `TuiTheme`): light or dark from the launcher's Theme setting, in
//! ANSI named colours so the terminal's own palette applies.

use mmcl_config::keys::{THEME_AUTOMATIC, THEME_LIGHT, THEME_NATIVE};
use ratatui::style::{Color, Modifier, Style};

/// Light or dark.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TuiTheme {
    pub is_light: bool,
}

impl TuiTheme {
    /// Light when the theme is `Light`, or `Native (Mica)` / `Automatic` on a light OS; dark for
    /// `Dark` and every colourful theme. The saved name is matched case-insensitively.
    pub fn resolve(theme: &str, os_is_dark: bool) -> TuiTheme {
        let t = mmcl_engine::settings::canonical_theme(theme);
        let is_light =
            t == THEME_LIGHT || ((t == THEME_NATIVE || t == THEME_AUTOMATIC) && !os_is_dark);
        TuiTheme { is_light }
    }

    /// The dark theme.
    pub const DARK: TuiTheme = TuiTheme { is_light: false };

    fn fg(&self) -> Color {
        if self.is_light {
            Color::Black
        } else {
            Color::White
        }
    }

    fn bg(&self) -> Color {
        if self.is_light {
            Color::White
        } else {
            Color::Black
        }
    }

    /// Normal text and the screen background.
    pub fn base(&self) -> Style {
        Style::default().fg(self.fg()).bg(self.bg())
    }

    /// The header line.
    pub fn header(&self) -> Style {
        self.base().add_modifier(Modifier::BOLD)
    }

    /// Text input fields.
    pub fn editable(&self) -> Style {
        self.base().add_modifier(Modifier::UNDERLINED)
    }

    /// The selected row or button.
    pub fn selected(&self) -> Style {
        if self.is_light {
            Style::default().fg(Color::White).bg(Color::Blue)
        } else {
            Style::default().fg(Color::Black).bg(Color::White)
        }
    }

    /// Rows that do nothing (empty-list notes).
    pub fn inert(&self) -> Style {
        self.base().add_modifier(Modifier::DIM)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn light_and_dark_resolution() {
        assert!(TuiTheme::resolve("Light", true).is_light);
        assert!(TuiTheme::resolve("light", true).is_light);
        assert!(!TuiTheme::resolve("Dark", false).is_light);
        assert!(TuiTheme::resolve("Automatic", false).is_light);
        assert!(!TuiTheme::resolve("Automatic", true).is_light);
        assert!(TuiTheme::resolve("Native (Mica)", false).is_light);
        assert!(!TuiTheme::resolve("Native (Mica)", true).is_light);
        for colourful in ["Blue+gray", "Orange+purple", "Creeper"] {
            assert!(!TuiTheme::resolve(colourful, false).is_light, "{colourful}");
        }
    }

    #[test]
    fn colours_match_the_java_table() {
        let light = TuiTheme { is_light: true };
        assert_eq!(light.base().fg, Some(Color::Black));
        assert_eq!(light.base().bg, Some(Color::White));
        assert_eq!(light.selected().fg, Some(Color::White));
        assert_eq!(light.selected().bg, Some(Color::Blue));
        let dark = TuiTheme::DARK;
        assert_eq!(dark.base().fg, Some(Color::White));
        assert_eq!(dark.base().bg, Some(Color::Black));
        assert_eq!(dark.selected().fg, Some(Color::Black));
        assert_eq!(dark.selected().bg, Some(Color::White));
    }
}

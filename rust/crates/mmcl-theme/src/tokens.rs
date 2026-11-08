// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The seven token sheets and their colour tables.
//!
//! The tables are generated at build time from `src/main/resources/ui/ui-tokens-*.css` (see
//! `build.rs`), so they always match the Java launcher's sheets.

use crate::Rgba;

include!(concat!(env!("OUT_DIR"), "/tokens.rs"));

/// One of the seven token sheets (`ui/ui-tokens-<name>.css`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum TokenSheet {
    /// `ui-tokens-dark.css`
    Dark,
    /// `ui-tokens-light.css`
    Light,
    /// `ui-tokens-bluegray.css`
    BlueGray,
    /// `ui-tokens-orangepurple.css`
    OrangePurple,
    /// `ui-tokens-creeper.css`
    Creeper,
    /// `ui-tokens-native.css` (translucent, over a dark OS backdrop)
    Native,
    /// `ui-tokens-native-light.css` (translucent, over a light OS backdrop)
    NativeLight,
}

impl TokenSheet {
    /// Every sheet, in declaration order.
    pub const ALL: [TokenSheet; 7] = [
        TokenSheet::Dark,
        TokenSheet::Light,
        TokenSheet::BlueGray,
        TokenSheet::OrangePurple,
        TokenSheet::Creeper,
        TokenSheet::Native,
        TokenSheet::NativeLight,
    ];

    /// The sheet's CSS file name, e.g. `ui-tokens-dark.css`.
    pub const fn file_name(self) -> &'static str {
        match self {
            TokenSheet::Dark => "ui-tokens-dark.css",
            TokenSheet::Light => "ui-tokens-light.css",
            TokenSheet::BlueGray => "ui-tokens-bluegray.css",
            TokenSheet::OrangePurple => "ui-tokens-orangepurple.css",
            TokenSheet::Creeper => "ui-tokens-creeper.css",
            TokenSheet::Native => "ui-tokens-native.css",
            TokenSheet::NativeLight => "ui-tokens-native-light.css",
        }
    }

    /// The Java classpath path, e.g. `ui/ui-tokens-dark.css`.
    pub fn resource_path(self) -> String {
        format!("ui/{}", self.file_name())
    }

    /// Recognises a sheet from a file name or a path ending in one (also the `blue-gray` /
    /// `orange-purple` spellings Java's `themeBgHexStatic` accepted).
    pub fn from_file_name(name: &str) -> Option<Self> {
        let name = name.rsplit(['/', '\\']).next().unwrap_or(name);
        match name {
            "ui-tokens-blue-gray.css" => Some(TokenSheet::BlueGray),
            "ui-tokens-orange-purple.css" => Some(TokenSheet::OrangePurple),
            _ => Self::ALL.into_iter().find(|s| s.file_name() == name),
        }
    }

    /// The sheet's colour tokens.
    pub const fn tokens(self) -> &'static Tokens {
        match self {
            TokenSheet::Dark => &DARK,
            TokenSheet::Light => &LIGHT,
            TokenSheet::BlueGray => &BLUE_GRAY,
            TokenSheet::OrangePurple => &ORANGE_PURPLE,
            TokenSheet::Creeper => &CREEPER,
            TokenSheet::Native => &NATIVE,
            TokenSheet::NativeLight => &NATIVE_LIGHT,
        }
    }

    /// Whether the sheet is a light palette (light title bar / "light chrome").
    pub const fn is_light(self) -> bool {
        matches!(self, TokenSheet::Light | TokenSheet::NativeLight)
    }

    /// Whether the sheet is one of the two translucent native sheets that expect an OS backdrop.
    pub const fn is_native(self) -> bool {
        matches!(self, TokenSheet::Native | TokenSheet::NativeLight)
    }

    /// The opaque background to paint for this sheet.
    ///
    /// Opaque sheets use their own `-color-bg`. The native sheets' `-color-bg` is transparent,
    /// so they use a solid floor instead: `#14181F` in the main window (under Mica) and
    /// `#1C1B1F` in secondary windows for dark, `#F5F6FA` for light in both.
    pub fn solid_bg(self, secondary_window: bool) -> Rgba {
        match self {
            TokenSheet::Native if secondary_window => Rgba::opaque(0x1C1B1F),
            TokenSheet::Native => Rgba::opaque(0x14181F),
            TokenSheet::NativeLight => Rgba::opaque(0xF5F6FA),
            opaque => opaque.tokens().color_bg,
        }
    }

    /// The opaque overrides the native sheets add outside `.root`, or `None` for opaque sheets.
    pub const fn native_overrides(self) -> Option<&'static NativeOverrides> {
        match self {
            TokenSheet::Native => Some(&NATIVE_OVERRIDES),
            TokenSheet::NativeLight => Some(&NATIVE_LIGHT_OVERRIDES),
            _ => None,
        }
    }
}

/// Looks up a sheet's tokens (the spec's `tokens(sheet)`).
pub const fn tokens(sheet: TokenSheet) -> &'static Tokens {
    sheet.tokens()
}

/// The solid background for a sheet (the spec's `solid_bg(sheet, secondary_window)`).
pub fn solid_bg(sheet: TokenSheet, secondary_window: bool) -> Rgba {
    sheet.solid_bg(secondary_window)
}

/// `#RRGGBB` of [`solid_bg`], the form the Java launcher's `themeBgHex` returned.
pub fn bg_hex(sheet: TokenSheet, secondary_window: bool) -> String {
    sheet.solid_bg(secondary_window).to_hex_rgb()
}

/// The rules the native token sheets add after `.root`.
///
/// Root panes (`.rootPane`, `.rootPane.hero-surface`) are transparent there so the OS backdrop
/// shows; the modpack-detail modal is forced opaque so it reads as a solid panel; the switch's
/// off-X uses the opaque `-color-popup`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct NativeOverrides {
    /// `.modpackDetailCard` background.
    pub detail_card: Rgba,
    /// `.modpackDetailCard .modpackDetailSection` background.
    pub detail_section: Rgba,
    /// `.modpackDetailCard .modpackDetailSection` border.
    pub detail_section_border: Rgba,
    /// `.modpackDetailCard .modpackDetailActions` background.
    pub detail_actions: Rgba,
    /// `.modpackDetailCard .modpackDetailActions` border.
    pub detail_actions_border: Rgba,
    /// `.modpackDetailQuickAction` background (also armed / focused).
    pub quick_action: Rgba,
    /// `.modpackDetailQuickAction:hover` background.
    pub quick_action_hover: Rgba,
    /// `.modpackDetailSecondaryBtn` background (also armed / focused).
    pub secondary_button: Rgba,
    /// `.modpackDetailSecondaryBtn:hover` background.
    pub secondary_button_hover: Rgba,
    /// `.mfx-toggle-button .m3-switch-icon-off` fill (`-color-popup`).
    pub switch_icon_off: Rgba,
}

const NATIVE_OVERRIDES: NativeOverrides = NativeOverrides {
    detail_card: Rgba::opaque(0x1C2230),
    detail_section: Rgba::opaque(0x252B3A),
    detail_section_border: Rgba(0xDCE6_F52E),
    detail_actions: Rgba::opaque(0x161A23),
    detail_actions_border: Rgba(0xDCE6_F52E),
    quick_action: Rgba::opaque(0x2B3144),
    quick_action_hover: Rgba::opaque(0x353C52),
    secondary_button: Rgba::opaque(0x2B3144),
    secondary_button_hover: Rgba::opaque(0x353C52),
    switch_icon_off: NATIVE.color_popup,
};

const NATIVE_LIGHT_OVERRIDES: NativeOverrides = NativeOverrides {
    detail_card: Rgba::opaque(0xFFFFFF),
    detail_section: Rgba::opaque(0xF4F5F8),
    detail_section_border: Rgba(0x0F17_2A1F),
    detail_actions: Rgba::opaque(0xF0F2F6),
    detail_actions_border: Rgba(0x0F17_2A1F),
    quick_action: Rgba::opaque(0xFFFFFF),
    quick_action_hover: Rgba::opaque(0xEDEFF3),
    secondary_button: Rgba::opaque(0xFFFFFF),
    secondary_button_hover: Rgba::opaque(0xEDEFF3),
    switch_icon_off: NATIVE_LIGHT.color_popup,
};

#[cfg(test)]
mod tests {
    use super::*;
    use crate::css;
    use std::collections::BTreeSet;
    use std::path::Path;

    fn source(file: &str) -> String {
        std::fs::read_to_string(Path::new(CSS_DIR).join(file)).expect("read sheet")
    }

    #[test]
    fn every_theme_defines_the_same_tokens() {
        let reference: BTreeSet<String> = css::root_tokens(&source(TokenSheet::Dark.file_name()))
            .unwrap()
            .into_iter()
            .map(|(k, _)| k)
            .collect();
        for sheet in TokenSheet::ALL {
            let set: BTreeSet<String> = css::root_tokens(&source(sheet.file_name()))
                .unwrap()
                .into_iter()
                .map(|(k, _)| k)
                .collect();
            assert_eq!(
                reference,
                set,
                "{} defines a different token set",
                sheet.file_name()
            );
        }
        assert!(
            reference.iter().any(|k| k.starts_with("-md-")),
            "no generated -md-* roles"
        );
        assert_eq!(reference.len(), TOKEN_COUNT);
        assert_eq!(
            reference,
            TOKEN_NAMES.iter().map(|s| s.to_string()).collect()
        );
    }

    #[test]
    fn tables_match_the_sheets() {
        for sheet in TokenSheet::ALL {
            let t = sheet.tokens();
            let decls = css::root_tokens(&source(sheet.file_name())).unwrap();
            for (name, raw) in &decls {
                let raw = if raw.starts_with('-') {
                    decls
                        .iter()
                        .find(|(k, _)| k == raw)
                        .map(|(_, v)| v.clone())
                        .unwrap()
                } else {
                    raw.clone()
                };
                assert_eq!(
                    t.get(name),
                    Rgba::parse_css(&raw),
                    "{} {name}",
                    sheet.file_name()
                );
            }
            assert_eq!(t.values().len(), TOKEN_COUNT);
            for (i, name) in TOKEN_NAMES.iter().enumerate() {
                assert_eq!(t.get(name), Some(t.values()[i]));
            }
        }
        assert_eq!(DARK.get("-color-nope"), None);
    }

    #[test]
    fn spot_check_values_from_the_spec_table() {
        assert_eq!(DARK.color_bg, Rgba::opaque(0x0C1017));
        assert_eq!(LIGHT.color_bg, Rgba::WHITE);
        assert_eq!(BLUE_GRAY.color_bg, Rgba::opaque(0x0E141D));
        assert_eq!(ORANGE_PURPLE.color_text_on_primary, Rgba::opaque(0x36003C));
        assert_eq!(CREEPER.color_overlay, Rgba(0x0000_00A6));
        assert_eq!(NATIVE.color_bg, Rgba(0x0000_0000));
        assert_eq!(NATIVE_LIGHT.color_bg, Rgba(0xFFFF_FF00));
        assert_eq!(NATIVE.color_border, Rgba(0xDCE6_F542));
        assert_eq!(DARK.md_primary, Rgba::opaque(0x6FCF3D));
        assert_eq!(DARK.md_state_pressed_on_surface, Rgba(0xEDEF_F21A));
    }

    #[test]
    fn popup_is_a_resolved_reference_and_opaque_everywhere() {
        for sheet in TokenSheet::ALL {
            let t = sheet.tokens();
            assert!(t.color_popup.is_opaque(), "{sheet:?} popup");
            assert!(t.color_popup_border.a() > 0, "{sheet:?} popup border");
        }
        assert!(
            TOKEN_REFERENCES
                .iter()
                .any(|(_, k, _)| *k == "-color-popup")
        );
        for (file, token, target) in TOKEN_REFERENCES {
            let sheet = TokenSheet::from_file_name(file).unwrap();
            assert_eq!(sheet.tokens().get(token), sheet.tokens().get(target));
        }
        assert_eq!(DARK.color_popup, DARK.md_surface_container_high);
    }

    #[test]
    fn file_names_round_trip() {
        for sheet in TokenSheet::ALL {
            assert_eq!(TokenSheet::from_file_name(sheet.file_name()), Some(sheet));
            assert_eq!(
                TokenSheet::from_file_name(&sheet.resource_path()),
                Some(sheet)
            );
        }
        assert_eq!(
            TokenSheet::from_file_name("ui\\ui-tokens-blue-gray.css"),
            Some(TokenSheet::BlueGray)
        );
        assert_eq!(
            TokenSheet::from_file_name("ui-tokens-orange-purple.css"),
            Some(TokenSheet::OrangePurple)
        );
        assert_eq!(TokenSheet::from_file_name("ui-base.css"), None);
    }

    #[test]
    fn solid_backgrounds_use_one_table() {
        let main = |s: TokenSheet| bg_hex(s, false);
        let secondary = |s: TokenSheet| bg_hex(s, true);
        assert_eq!(main(TokenSheet::Dark), "#0C1017");
        assert_eq!(main(TokenSheet::Light), "#FFFFFF");
        // Main window used #121721 and secondary windows #0E141D; the sheet's own bg wins.
        assert_eq!(main(TokenSheet::BlueGray), "#0E141D");
        assert_eq!(secondary(TokenSheet::BlueGray), "#0E141D");
        assert_eq!(main(TokenSheet::OrangePurple), "#201221");
        assert_eq!(main(TokenSheet::Creeper), "#0C130C");
        assert_eq!(main(TokenSheet::Native), "#14181F");
        assert_eq!(secondary(TokenSheet::Native), "#1C1B1F");
        assert_eq!(main(TokenSheet::NativeLight), "#F5F6FA");
        assert_eq!(secondary(TokenSheet::NativeLight), "#F5F6FA");
        for sheet in TokenSheet::ALL {
            assert!(solid_bg(sheet, false).is_opaque() && solid_bg(sheet, true).is_opaque());
        }
    }

    #[test]
    fn native_overrides_match_the_sheets() {
        for sheet in TokenSheet::ALL {
            let rules = css::rules(&source(sheet.file_name()));
            let find = |selector: &str, prop: &str| -> Option<Rgba> {
                rules
                    .iter()
                    .filter(|r| r.selectors.iter().any(|s| s == selector))
                    .flat_map(|r| r.declarations.iter())
                    .find(|(k, _)| k == prop)
                    .and_then(|(_, v)| {
                        if v.starts_with('-') {
                            sheet.tokens().get(v)
                        } else {
                            Rgba::parse_css(v)
                        }
                    })
            };
            let Some(o) = sheet.native_overrides() else {
                assert_eq!(find(".modpackDetailCard", "-fx-background-color"), None);
                continue;
            };
            let bg = "-fx-background-color";
            let border = "-fx-border-color";
            let section = ".modpackDetailCard .modpackDetailSection";
            let actions = ".modpackDetailCard .modpackDetailActions";
            let quick = ".modpackDetailCard .modpackDetailQuickAction";
            let secondary = ".modpackDetailCard .modpackDetailSecondaryBtn";
            assert_eq!(find(".rootPane", bg), Some(Rgba::TRANSPARENT));
            assert_eq!(find(".rootPane.hero-surface", bg), Some(Rgba::TRANSPARENT));
            assert_eq!(find(".modpackDetailCard", bg), Some(o.detail_card));
            assert_eq!(find(section, bg), Some(o.detail_section));
            assert_eq!(find(section, border), Some(o.detail_section_border));
            assert_eq!(find(actions, bg), Some(o.detail_actions));
            assert_eq!(find(actions, border), Some(o.detail_actions_border));
            assert_eq!(find(quick, bg), Some(o.quick_action));
            assert_eq!(find(&format!("{quick}:armed"), bg), Some(o.quick_action));
            assert_eq!(find(&format!("{quick}:focused"), bg), Some(o.quick_action));
            assert_eq!(
                find(&format!("{quick}:hover"), bg),
                Some(o.quick_action_hover)
            );
            assert_eq!(find(secondary, bg), Some(o.secondary_button));
            assert_eq!(
                find(&format!("{secondary}:hover"), bg),
                Some(o.secondary_button_hover)
            );
            assert_eq!(
                find(".mfx-toggle-button .m3-switch-icon-off", "-fx-fill"),
                Some(o.switch_icon_off)
            );
            assert!(o.switch_icon_off.is_opaque());
        }
    }
}

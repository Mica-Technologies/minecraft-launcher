// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The RGB settings snapshot ([`RgbSettings`]) the host builds from the launcher config.
//!
//! The config keys and their JSON format belong to the config subsystem (`rgbEnable`,
//! `rgbBackend`, `rgbEnable<Backend>`, `rgbMenuEffectEnable`, `rgbEffectStyle`,
//! `rgbUsePackColors`, `rgbHighlightKeys`); this module only maps their string values.

use crate::backend::BackendKind;

/// The `rgbBackend` choice.
///
/// Java bug fixed here: Java's config validated against six values only, so `corsair` and
/// `asus-aura` read back as `auto` and manual mode for those two was unreachable. Every one of
/// the eight values round-trips through [`BackendChoice::from_config`] /
/// [`BackendChoice::config_value`].
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash, Default)]
pub enum BackendChoice {
    #[default]
    Auto,
    OpenRgb,
    ChromaRest,
    ChromaNative,
    WindowsDl,
    Corsair,
    AsusAura,
    None,
}

impl BackendChoice {
    /// The Settings dropdown order.
    pub const DROPDOWN: [BackendChoice; 8] = [
        BackendChoice::Auto,
        BackendChoice::OpenRgb,
        BackendChoice::ChromaNative,
        BackendChoice::ChromaRest,
        BackendChoice::WindowsDl,
        BackendChoice::Corsair,
        BackendChoice::AsusAura,
        BackendChoice::None,
    ];

    /// Parses a config value (trimmed, case-insensitive). `None` for an unknown value; callers
    /// that want Java's "unknown means auto" use [`BackendChoice::from_config_or_auto`].
    pub fn parse(value: &str) -> Option<BackendChoice> {
        Some(match value.trim().to_ascii_lowercase().as_str() {
            "auto" => BackendChoice::Auto,
            "openrgb" => BackendChoice::OpenRgb,
            "chroma" => BackendChoice::ChromaRest,
            "chroma-native" => BackendChoice::ChromaNative,
            "windows-dl" => BackendChoice::WindowsDl,
            "corsair" => BackendChoice::Corsair,
            "asus-aura" => BackendChoice::AsusAura,
            "none" => BackendChoice::None,
            _ => return None,
        })
    }

    /// The stored value (absent -> `auto`); an unknown value logs a warning and means `auto`.
    pub fn from_config(value: Option<&str>) -> BackendChoice {
        match value {
            None => BackendChoice::Auto,
            Some(v) => BackendChoice::parse(v).unwrap_or_else(|| {
                tracing::warn!(
                    "{}",
                    mmcl_i18n::tf!("log.rgb.registry.unrecognizedChoiceAuto", v)
                );
                BackendChoice::Auto
            }),
        }
    }

    /// [`BackendChoice::parse`] with unknown values mapped to `Auto` silently.
    pub fn from_config_or_auto(value: &str) -> BackendChoice {
        BackendChoice::parse(value).unwrap_or(BackendChoice::Auto)
    }

    /// The value written to `rgbBackend`.
    pub const fn config_value(self) -> &'static str {
        match self {
            BackendChoice::Auto => "auto",
            BackendChoice::OpenRgb => "openrgb",
            BackendChoice::ChromaRest => "chroma",
            BackendChoice::ChromaNative => "chroma-native",
            BackendChoice::WindowsDl => "windows-dl",
            BackendChoice::Corsair => "corsair",
            BackendChoice::AsusAura => "asus-aura",
            BackendChoice::None => "none",
        }
    }

    /// The backend a manual choice names (`None` for Auto and None).
    pub const fn kind(self) -> Option<BackendKind> {
        match self {
            BackendChoice::OpenRgb => Some(BackendKind::OpenRgb),
            BackendChoice::ChromaRest => Some(BackendKind::ChromaRest),
            BackendChoice::ChromaNative => Some(BackendKind::ChromaNative),
            BackendChoice::WindowsDl => Some(BackendKind::WindowsDynamicLighting),
            BackendChoice::Corsair => Some(BackendKind::Corsair),
            BackendChoice::AsusAura => Some(BackendKind::AsusAura),
            BackendChoice::Auto | BackendChoice::None => None,
        }
    }

    /// The dropdown label: the product name, or the localised Auto / None.
    pub fn label(self) -> String {
        match self {
            BackendChoice::Auto => mmcl_i18n::t!("settings.rgb.backend.auto").into_owned(),
            BackendChoice::None => mmcl_i18n::t!("settings.rgb.backend.none").into_owned(),
            other => other.kind().map_or("", BackendKind::label).to_string(),
        }
    }
}

/// The per-backend Auto-mode switches (`rgbEnable<Backend>`).
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub struct AutoToggles {
    pub openrgb: bool,
    pub chroma_native: bool,
    pub chroma_rest: bool,
    pub windows_dl: bool,
    pub corsair: bool,
    pub asus_aura: bool,
}

impl Default for AutoToggles {
    /// The config defaults: everything on except the deprecated Chroma REST path.
    fn default() -> Self {
        AutoToggles {
            openrgb: true,
            chroma_native: true,
            chroma_rest: false,
            windows_dl: true,
            corsair: true,
            asus_aura: true,
        }
    }
}

impl AutoToggles {
    /// Whether Auto mode may use `kind`.
    pub fn allows(&self, kind: BackendKind) -> bool {
        match kind {
            BackendKind::OpenRgb => self.openrgb,
            BackendKind::ChromaNative => self.chroma_native,
            BackendKind::ChromaRest => self.chroma_rest,
            BackendKind::WindowsDynamicLighting => self.windows_dl,
            BackendKind::Corsair => self.corsair,
            BackendKind::AsusAura => self.asus_aura,
            BackendKind::NoOp => false,
        }
    }
}

/// The menu effect style (`rgbEffectStyle`).
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash, Default)]
pub enum EffectStyle {
    Solid,
    #[default]
    Breathe,
    Pulse,
    Cycle,
    Rainbow,
}

impl EffectStyle {
    /// Every style, in the Settings order.
    pub const ALL: [EffectStyle; 5] = [
        EffectStyle::Solid,
        EffectStyle::Breathe,
        EffectStyle::Pulse,
        EffectStyle::Cycle,
        EffectStyle::Rainbow,
    ];

    /// Parses a config value; unknown values read as the default (`breathe`).
    pub fn from_config(value: &str) -> EffectStyle {
        match value.trim().to_ascii_lowercase().as_str() {
            "solid" => EffectStyle::Solid,
            "breathe" => EffectStyle::Breathe,
            "pulse" => EffectStyle::Pulse,
            "cycle" => EffectStyle::Cycle,
            "rainbow" => EffectStyle::Rainbow,
            _ => EffectStyle::default(),
        }
    }

    /// The stored value.
    pub const fn config_value(self) -> &'static str {
        match self {
            EffectStyle::Solid => "solid",
            EffectStyle::Breathe => "breathe",
            EffectStyle::Pulse => "pulse",
            EffectStyle::Cycle => "cycle",
            EffectStyle::Rainbow => "rainbow",
        }
    }
}

/// Everything the RGB subsystem reads from the config, as one snapshot.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RgbSettings {
    /// `rgbEnable` (master switch; default off).
    pub enabled: bool,
    pub backend: BackendChoice,
    pub auto_enabled: AutoToggles,
    /// `rgbMenuEffectEnable`.
    pub menu_effect_enabled: bool,
    pub effect_style: EffectStyle,
    /// `rgbUsePackColors`.
    pub use_pack_colors: bool,
    /// `rgbHighlightKeys`.
    pub highlight_keys: bool,
    /// The config `theme` value (`Dark`, `Light`, ...).
    pub theme: String,
}

impl Default for RgbSettings {
    /// The config defaults.
    fn default() -> Self {
        RgbSettings {
            enabled: false,
            backend: BackendChoice::Auto,
            auto_enabled: AutoToggles::default(),
            menu_effect_enabled: true,
            effect_style: EffectStyle::Breathe,
            use_pack_colors: true,
            highlight_keys: true,
            theme: String::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_backend_choice_round_trips_including_corsair_and_aura() {
        for c in BackendChoice::DROPDOWN {
            assert_eq!(BackendChoice::parse(c.config_value()), Some(c));
            assert_eq!(BackendChoice::from_config(Some(c.config_value())), c);
        }
        assert_eq!(
            BackendChoice::from_config(Some("corsair")),
            BackendChoice::Corsair
        );
        assert_eq!(
            BackendChoice::from_config(Some("asus-aura")),
            BackendChoice::AsusAura
        );
        assert_eq!(
            BackendChoice::from_config(Some("bogus")),
            BackendChoice::Auto
        );
        assert_eq!(BackendChoice::from_config(None), BackendChoice::Auto);
        assert_eq!(BackendChoice::Corsair.label(), "Corsair iCUE");
    }

    #[test]
    fn effect_style_parse() {
        for s in EffectStyle::ALL {
            assert_eq!(EffectStyle::from_config(s.config_value()), s);
        }
        assert_eq!(EffectStyle::from_config("disco"), EffectStyle::Breathe);
    }
}

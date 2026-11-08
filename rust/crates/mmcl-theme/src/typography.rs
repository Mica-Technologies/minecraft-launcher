// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Fonts and the type scale. Only the sizes in [`SCALE`] are used anywhere in the UI.

/// The bundled UI face (regular and bold).
pub const INTER: &str = "Inter";
/// The bundled semibold face, its own family (weight 600 must name it).
pub const INTER_SEMIBOLD: &str = "Inter SemiBold";
/// The bundled monospace face (display heading, consoles).
pub const JETBRAINS_MONO: &str = "JetBrains Mono";

/// Fallbacks after Inter, in order.
pub const UI_FALLBACKS: [&str; 5] = [
    "Segoe UI Variable",
    "Segoe UI",
    "SF Pro Text",
    "Roboto",
    "system-ui",
];
/// Fallbacks after Inter SemiBold, in order.
pub const SEMIBOLD_FALLBACKS: [&str; 6] = [
    "Segoe UI Semibold",
    "Segoe UI Variable",
    "Segoe UI",
    "SF Pro Text",
    "Roboto",
    "system-ui",
];
/// Fallbacks after JetBrains Mono, in order.
pub const MONO_FALLBACKS: [&str; 5] = [
    "Cascadia Code",
    "Cascadia Mono",
    "Consolas",
    "Menlo",
    "Courier New",
];

/// The root font size (px).
pub const BASE_SIZE: f32 = 14.0;

/// Every allowed font size (px): label-small 11, body-small / label-medium 12, body-medium 14,
/// body-large / title-medium 16, title-large 22, headline-small 24, headline-medium 28.
pub const SCALE: [f32; 7] = [11.0, 12.0, 14.0, 16.0, 22.0, 24.0, 28.0];

/// Whether a size is on the scale.
pub fn is_on_scale(size: f32) -> bool {
    SCALE.contains(&size)
}

/// Font weights in use.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Weight {
    /// 400.
    Regular,
    /// 600 (rendered with [`INTER_SEMIBOLD`]).
    SemiBold,
    /// 700.
    Bold,
}

impl Weight {
    /// The CSS numeric weight.
    pub const fn value(self) -> u16 {
        match self {
            Weight::Regular => 400,
            Weight::SemiBold => 600,
            Weight::Bold => 700,
        }
    }
}

/// A font family stack.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Family {
    /// Inter (regular / bold).
    Ui,
    /// Inter SemiBold.
    UiSemiBold,
    /// JetBrains Mono.
    Mono,
}

impl Family {
    /// The primary (bundled) family name.
    pub const fn name(self) -> &'static str {
        match self {
            Family::Ui => INTER,
            Family::UiSemiBold => INTER_SEMIBOLD,
            Family::Mono => JETBRAINS_MONO,
        }
    }

    /// The fallbacks after the primary family.
    pub const fn fallbacks(self) -> &'static [&'static str] {
        match self {
            Family::Ui => &UI_FALLBACKS,
            Family::UiSemiBold => &SEMIBOLD_FALLBACKS,
            Family::Mono => &MONO_FALLBACKS,
        }
    }
}

/// A text style: size, weight and family.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TextStyle {
    /// Size in px.
    pub size: f32,
    /// Weight.
    pub weight: Weight,
    /// Family stack.
    pub family: Family,
}

/// The type roles the UI uses (the CSS `type-*` / `heading-*` classes and a few component roles).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum TypeRole {
    /// `.heading-display`: JetBrains Mono 700 24.
    HeadingDisplay,
    /// `.heading-h1`: 600 28.
    HeadingH1,
    /// `.heading-h2`: 600 22.
    HeadingH2,
    /// `.heading-h3`: 600 16.
    HeadingH3,
    /// `.type-headline-medium`: 600 28.
    HeadlineMedium,
    /// `.type-headline-small`: 600 24.
    HeadlineSmall,
    /// `.type-title-large`: 600 22.
    TitleLarge,
    /// `.type-title-medium`: 600 16.
    TitleMedium,
    /// `.type-title-small`: 600 14.
    TitleSmall,
    /// `.type-body-large`: 16.
    BodyLarge,
    /// `.type-body-medium`: 14.
    BodyMedium,
    /// `.type-body-small`: 12.
    BodySmall,
    /// `.type-label-large`: 600 14.
    LabelLarge,
    /// `.type-label-medium`: 600 12.
    LabelMedium,
    /// `.type-label-small`: 11.
    LabelSmall,
    /// `.settingsSectionTitle`: 600 14.
    SettingsSectionTitle,
    /// `.settingsHint`: 12.
    SettingsHint,
    /// Chips (`.stat-chip`, `.chip-offline`): 600 11.
    Chip,
    /// `.navBrand`: 700 14.
    NavBrand,
}

impl TypeRole {
    /// Every role.
    pub const ALL: [TypeRole; 19] = [
        TypeRole::HeadingDisplay,
        TypeRole::HeadingH1,
        TypeRole::HeadingH2,
        TypeRole::HeadingH3,
        TypeRole::HeadlineMedium,
        TypeRole::HeadlineSmall,
        TypeRole::TitleLarge,
        TypeRole::TitleMedium,
        TypeRole::TitleSmall,
        TypeRole::BodyLarge,
        TypeRole::BodyMedium,
        TypeRole::BodySmall,
        TypeRole::LabelLarge,
        TypeRole::LabelMedium,
        TypeRole::LabelSmall,
        TypeRole::SettingsSectionTitle,
        TypeRole::SettingsHint,
        TypeRole::Chip,
        TypeRole::NavBrand,
    ];

    /// The role's style.
    pub const fn style(self) -> TextStyle {
        const fn semi(size: f32) -> TextStyle {
            TextStyle {
                size,
                weight: Weight::SemiBold,
                family: Family::UiSemiBold,
            }
        }
        const fn regular(size: f32) -> TextStyle {
            TextStyle {
                size,
                weight: Weight::Regular,
                family: Family::Ui,
            }
        }
        match self {
            TypeRole::HeadingDisplay => TextStyle {
                size: 24.0,
                weight: Weight::Bold,
                family: Family::Mono,
            },
            TypeRole::HeadingH1 | TypeRole::HeadlineMedium => semi(28.0),
            TypeRole::HeadingH2 | TypeRole::TitleLarge => semi(22.0),
            TypeRole::HeadingH3 | TypeRole::TitleMedium => semi(16.0),
            TypeRole::HeadlineSmall => semi(24.0),
            TypeRole::TitleSmall | TypeRole::LabelLarge | TypeRole::SettingsSectionTitle => {
                semi(14.0)
            }
            TypeRole::LabelMedium => semi(12.0),
            TypeRole::Chip => semi(11.0),
            TypeRole::BodyLarge => regular(16.0),
            TypeRole::BodyMedium => regular(BASE_SIZE),
            TypeRole::BodySmall | TypeRole::SettingsHint => regular(12.0),
            TypeRole::LabelSmall => regular(11.0),
            TypeRole::NavBrand => TextStyle {
                size: 14.0,
                weight: Weight::Bold,
                family: Family::Ui,
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::css;

    #[test]
    fn every_role_is_on_the_scale_and_semibold_names_its_face() {
        for role in TypeRole::ALL {
            let s = role.style();
            assert!(is_on_scale(s.size), "{role:?}");
            assert_eq!(
                s.weight == Weight::SemiBold,
                s.family == Family::UiSemiBold,
                "{role:?}"
            );
        }
        assert!(is_on_scale(BASE_SIZE));
        assert_eq!(Weight::SemiBold.value(), 600);
        assert_eq!(Family::UiSemiBold.name(), "Inter SemiBold");
        assert_eq!(Family::Mono.fallbacks()[0], "Cascadia Code");
    }

    fn sheets() -> Vec<(String, String)> {
        std::fs::read_dir(crate::tokens::CSS_DIR)
            .unwrap()
            .map(|e| e.unwrap().path())
            .filter(|p| p.extension().is_some_and(|e| e == "css"))
            .map(|p| {
                (
                    p.display().to_string(),
                    std::fs::read_to_string(&p).unwrap(),
                )
            })
            .collect()
    }

    /// Port of `TypeScaleTest.stylesheetFontSizesAreOnTheScale`.
    #[test]
    fn stylesheet_font_sizes_are_on_the_scale() {
        let mut off = Vec::new();
        let mut seen = 0;
        for (name, source) in sheets() {
            for rule in css::rules(&source) {
                for (prop, value) in &rule.declarations {
                    if prop == "-fx-font-size" {
                        seen += 1;
                        let size = value.trim_end_matches("px").trim().parse::<f32>();
                        if !size.is_ok_and(is_on_scale) {
                            off.push(format!("{name}: {value}"));
                        }
                    }
                }
            }
        }
        assert!(
            seen > 50,
            "only {seen} font sizes found; is the CSS reader broken?"
        );
        assert!(
            off.is_empty(),
            "Font sizes off the type scale:\n  {}",
            off.join("\n  ")
        );
    }

    /// Port of `TypeScaleTest.semiboldNamesItsFaceAndNoOtherWeightsAreUsed`.
    #[test]
    fn semibold_names_its_face_and_no_other_weights_are_used() {
        let base = std::fs::read_to_string(
            std::path::Path::new(crate::tokens::CSS_DIR).join("ui-base.css"),
        )
        .unwrap();
        let mut off = Vec::new();
        let mut seen = 0;
        for rule in css::rules(&base) {
            let get = |p: &str| {
                rule.declarations
                    .iter()
                    .find(|(k, _)| k == p)
                    .map(|(_, v)| v.as_str())
            };
            let Some(weight) = get("-fx-font-weight") else {
                continue;
            };
            seen += 1;
            if !["normal", "400", "600", "700", "bold"].contains(&weight) {
                off.push(format!("{}: weight {weight}", rule.selectors.join(", ")));
            } else if weight == "600"
                && !get("-fx-font-family").is_some_and(|f| f.starts_with("\"Inter SemiBold\""))
            {
                off.push(format!(
                    "{}: weight 600 without \"Inter SemiBold\"",
                    rule.selectors.join(", ")
                ));
            }
        }
        assert!(
            seen > 20,
            "only {seen} weights found; is the CSS reader broken?"
        );
        assert!(off.is_empty(), "{}", off.join("\n  "));
    }
}

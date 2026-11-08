// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Corner radii (Material 3 shape scale). Only these radii are used anywhere in the UI.

/// Square corners.
pub const NONE: f32 = 0.0;
/// Extra-small: 4 px.
pub const EXTRA_SMALL: f32 = 4.0;
/// Small: 8 px (chips).
pub const SMALL: f32 = 8.0;
/// Medium: 12 px (cards).
pub const MEDIUM: f32 = 12.0;
/// Large: 16 px (hero cards).
pub const LARGE: f32 = 16.0;
/// Extra-large: 28 px (progress card, dialogs).
pub const EXTRA_LARGE: f32 = 28.0;
/// Fully round (pills).
pub const FULL: f32 = 999.0;

/// Every radius on the scale.
pub const SCALE: [f32; 7] = [NONE, EXTRA_SMALL, SMALL, MEDIUM, LARGE, EXTRA_LARGE, FULL];

/// Whether `radius` is on the scale.
pub fn is_on_scale(radius: f32) -> bool {
    SCALE.contains(&radius)
}

/// The radius of a clip inset `inset` px inside a shape of radius `outer`, so the two curves
/// stay concentric: `max(0, outer - inset)`.
pub fn inner(outer: f32, inset: f32) -> f32 {
    (outer - inset).max(0.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::css;

    #[test]
    fn inner_stays_concentric() {
        assert_eq!(inner(LARGE, 4.0), 12.0);
        assert_eq!(inner(EXTRA_SMALL, 8.0), 0.0);
        assert_eq!(inner(MEDIUM, 2.0), 10.0);
    }

    #[test]
    fn scale_is_the_material_steps() {
        assert!(SCALE.iter().all(|r| is_on_scale(*r)));
        assert!(!is_on_scale(10.0));
    }

    /// Port of `ShapeScaleTest.stylesheetRadiiAreOnTheScale` over the Java sheets.
    #[test]
    fn stylesheet_radii_are_on_the_scale() {
        let mut off = Vec::new();
        let mut seen = 0;
        for entry in std::fs::read_dir(crate::tokens::CSS_DIR).unwrap() {
            let path = entry.unwrap().path();
            if path.extension().is_none_or(|e| e != "css") {
                continue;
            }
            let source = std::fs::read_to_string(&path).unwrap();
            for rule in css::rules(&source) {
                for (prop, value) in &rule.declarations {
                    if prop != "-fx-background-radius" && prop != "-fx-border-radius" {
                        continue;
                    }
                    seen += 1;
                    let ok = value
                        .replace("!important", "")
                        .split([' ', ','])
                        .filter(|v| !v.is_empty())
                        .all(|v| {
                            v.trim_end_matches("px")
                                .parse::<f32>()
                                .is_ok_and(is_on_scale)
                        });
                    if !ok {
                        off.push(format!("{}: {prop}: {value}", path.display()));
                    }
                }
            }
        }
        assert!(
            seen > 50,
            "only {seen} radii found; is the CSS reader broken?"
        );
        assert!(
            off.is_empty(),
            "Radii off the shape scale:\n  {}",
            off.join("\n  ")
        );
    }
}

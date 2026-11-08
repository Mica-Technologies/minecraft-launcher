// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! WCAG 2 contrast, used by the token tests and available to UI checks.

use crate::Rgba;

/// WCAG AA minimum for normal text.
pub const AA_TEXT: f64 = 4.5;
/// WCAG AA minimum for non-text UI parts such as outlines and icons.
pub const AA_NON_TEXT: f64 = 3.0;

/// Relative luminance (0 black .. 1 white) of a colour's RGB, ignoring alpha.
pub fn luminance(c: Rgba) -> f64 {
    let lin = |v: u8| {
        let s = f64::from(v) / 255.0;
        if s <= 0.03928 {
            s / 12.92
        } else {
            ((s + 0.055) / 1.055).powf(2.4)
        }
    };
    0.2126 * lin(c.r()) + 0.7152 * lin(c.g()) + 0.0722 * lin(c.b())
}

/// Contrast ratio (1..21) between two colours' RGB, ignoring alpha.
pub fn contrast(a: Rgba, b: Rgba) -> f64 {
    let (la, lb) = (luminance(a), luminance(b));
    (la.max(lb) + 0.05) / (la.min(lb) + 0.05)
}

/// Contrast of `fg` drawn on `bg`, where `bg` is composited over `backdrop` first and `fg` over
/// the result, so translucent tokens are judged on what they actually sit on.
pub fn contrast_on(fg: Rgba, bg: Rgba, backdrop: Rgba) -> f64 {
    let background = bg.over(backdrop);
    contrast(fg.over(background), background)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::TokenSheet;

    #[test]
    fn contrast_matches_known_values() {
        assert!((contrast(Rgba::WHITE, Rgba::BLACK) - 21.0).abs() < 0.01);
        assert!((contrast(Rgba::opaque(0x777777), Rgba::opaque(0x777777)) - 1.0).abs() < 0.01);
        let grey = Rgba::parse_css("rgba(255, 255, 255, 0.5)")
            .unwrap()
            .over(Rgba::BLACK);
        assert!(grey.rgb().abs_diff(0x808080) <= 0x010101);
    }

    /// Port of `ThemeTokensTest.textMeetsContrastOnEveryRoleItIsDrawnOn`.
    #[test]
    fn text_meets_contrast_on_every_role_it_is_drawn_on() {
        const HUES: [&str; 8] = [
            "blue", "cyan", "green", "yellow", "orange", "pink", "purple", "grey",
        ];
        let mut failures = Vec::new();
        for sheet in TokenSheet::ALL {
            let t = sheet.tokens();
            let get = |name: &str| t.get(name).unwrap_or_else(|| panic!("missing {name}"));
            // Translucent (native) surfaces are judged over the opaque popup colour, the closest
            // stand-in for the OS backdrop.
            let backdrop = get("-color-popup").over(Rgba::BLACK);
            let mut check = |fg: &str, bg: &str, min: f64| {
                let ratio = contrast_on(get(fg), get(bg), backdrop);
                if ratio < min {
                    failures.push(format!(
                        "{}: {fg} on {bg} = {ratio:.2} (needs {min})",
                        sheet.file_name()
                    ));
                }
            };
            for group in [
                "primary",
                "secondary",
                "error",
                "success",
                "warning",
                "tertiary",
            ] {
                check(&format!("-md-on-{group}"), &format!("-md-{group}"), AA_TEXT);
                check(
                    &format!("-md-on-{group}-container"),
                    &format!("-md-{group}-container"),
                    AA_TEXT,
                );
            }
            for level in ["-lowest", "-low", "", "-high", "-highest"] {
                let surface = format!("-md-surface-container{level}");
                check("-md-on-surface", &surface, AA_TEXT);
                check("-md-on-surface-variant", &surface, AA_TEXT);
                check("-md-text-primary", &surface, AA_TEXT);
            }
            check("-md-outline", "-md-surface-container", AA_NON_TEXT);
            for hue in HUES {
                check(
                    &format!("-md-on-icon-{hue}"),
                    &format!("-md-icon-{hue}"),
                    AA_NON_TEXT,
                );
            }
            check("-md-inverse-on-surface", "-md-inverse-surface", AA_TEXT);
            check("-md-on-active-indicator", "-md-active-indicator", AA_TEXT);
            check("-md-text-primary", "-md-surface", AA_TEXT);
            check("-color-text-on-primary", "-color-primary", AA_TEXT);
            check("-color-text", "-color-popup", AA_TEXT);
        }
        assert!(
            failures.is_empty(),
            "Contrast below WCAG AA:\n  {}",
            failures.join("\n  ")
        );
    }
}

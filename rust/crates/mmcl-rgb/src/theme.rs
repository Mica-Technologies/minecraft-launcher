// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Theme accent colours and palette derivation (Java `ThemeAccentColors`).
//!
//! The accents mirror `-color-primary` in the launcher's theme token tables. They are also used
//! outside RGB (the macOS toolbar tint), so they are public.

use crate::color::RgbColor;

/// Dark theme accent (also the fallback).
pub const ACCENT_DARK: RgbColor = RgbColor::new(0x6F, 0xCF, 0x3D);
/// Light theme accent.
pub const ACCENT_LIGHT: RgbColor = RgbColor::new(0x3C, 0x85, 0x27);
/// `Blue+gray` accent.
pub const ACCENT_BLUE_GRAY: RgbColor = RgbColor::new(0x06, 0x68, 0xE1);
/// `Creeper` accent.
pub const ACCENT_CREEPER: RgbColor = RgbColor::new(0x43, 0xD2, 0x2D);
/// `Orange+purple` accent.
pub const ACCENT_ORANGE_PURPLE: RgbColor = RgbColor::new(0xD2, 0x57, 0xDB);

/// The accent for a config `theme` value. `Native (Mica)` and `Automatic` follow the OS
/// (`os_is_dark`); anything unknown or absent uses the dark accent.
pub fn accent_for_theme(theme: Option<&str>, os_is_dark: bool) -> RgbColor {
    let follow_os = if os_is_dark {
        ACCENT_DARK
    } else {
        ACCENT_LIGHT
    };
    match theme.map(str::trim) {
        Some("Dark") => ACCENT_DARK,
        Some("Light") => ACCENT_LIGHT,
        Some("Blue+gray") => ACCENT_BLUE_GRAY,
        Some("Creeper") => ACCENT_CREEPER,
        Some("Orange+purple") => ACCENT_ORANGE_PURPLE,
        Some("Native (Mica)") | Some("Automatic") => follow_os,
        _ => ACCENT_DARK,
    }
}

/// `count` colours spread 30 degrees apart in hue around `primary`, which sits in the middle
/// (same saturation and lightness). `count < 1` is treated as 1.
pub fn derive_palette(primary: RgbColor, count: usize) -> Vec<RgbColor> {
    let count = count.max(1);
    if count == 1 {
        return vec![primary];
    }
    let (h, s, l) = primary.to_hsl();
    let start = -30.0 * (count as f64 - 1.0) / 2.0;
    (0..count)
        .map(|i| {
            let hue = ((h + start + 30.0 * i as f64) % 360.0 + 360.0) % 360.0;
            RgbColor::hsl_to_rgb(hue, s, l)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accents_per_theme() {
        assert_eq!(accent_for_theme(Some("Dark"), false), ACCENT_DARK);
        assert_eq!(accent_for_theme(Some("Light"), true), ACCENT_LIGHT);
        assert_eq!(accent_for_theme(Some("Blue+gray"), true), ACCENT_BLUE_GRAY);
        assert_eq!(accent_for_theme(Some("Creeper"), true), ACCENT_CREEPER);
        assert_eq!(
            accent_for_theme(Some("Orange+purple"), true),
            ACCENT_ORANGE_PURPLE
        );
        for t in ["Native (Mica)", "Automatic"] {
            assert_eq!(accent_for_theme(Some(t), true), ACCENT_DARK);
            assert_eq!(accent_for_theme(Some(t), false), ACCENT_LIGHT);
        }
        assert_eq!(accent_for_theme(Some("Vaporwave"), false), ACCENT_DARK);
        assert_eq!(accent_for_theme(None, false), ACCENT_DARK);
    }

    #[test]
    fn derive_palette_is_centred() {
        let c = ACCENT_BLUE_GRAY;
        assert_eq!(derive_palette(c, 0), vec![c]);
        assert_eq!(derive_palette(c, 1), vec![c]);
        let p = derive_palette(c, 3);
        assert_eq!(p.len(), 3);
        let d = |a: u8, b: u8| (a as i32 - b as i32).abs();
        assert!(d(p[1].r, c.r) <= 1 && d(p[1].g, c.g) <= 1 && d(p[1].b, c.b) <= 1);
        let (h0, _, _) = p[0].to_hsl();
        let (h1, _, _) = p[1].to_hsl();
        assert!(((h1 - h0 + 360.0) % 360.0 - 30.0).abs() < 2.0);
    }

    #[test]
    fn derive_palette_wraps_hue() {
        let red = RgbColor::new(255, 0, 0); // hue 0: the first entry wraps to 330.
        let p = derive_palette(red, 3);
        let (h0, _, _) = p[0].to_hsl();
        assert!((h0 - 330.0).abs() < 1.0, "got {h0}");
    }
}

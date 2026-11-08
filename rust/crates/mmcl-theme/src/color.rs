// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The colour type every token uses.

use std::fmt;

/// A colour packed as `0xRRGGBBAA` (8 bits per channel, straight alpha).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct Rgba(pub u32);

impl Rgba {
    /// Fully transparent black.
    pub const TRANSPARENT: Rgba = Rgba(0x0000_0000);
    /// Opaque white.
    pub const WHITE: Rgba = Rgba(0xFFFF_FFFF);
    /// Opaque black.
    pub const BLACK: Rgba = Rgba(0x0000_00FF);

    /// Builds a colour from its four channels.
    pub const fn new(r: u8, g: u8, b: u8, a: u8) -> Self {
        Rgba(((r as u32) << 24) | ((g as u32) << 16) | ((b as u32) << 8) | a as u32)
    }

    /// An opaque colour from `0xRRGGBB`.
    pub const fn opaque(rgb: u32) -> Self {
        Rgba(((rgb & 0x00FF_FFFF) << 8) | 0xFF)
    }

    /// Parses a CSS colour (`#RGB`, `#RRGGBB`, `#RRGGBBAA`, `rgb()`, `rgba()`, `white`, `black`,
    /// `transparent`).
    pub fn parse_css(value: &str) -> Option<Self> {
        crate::css::parse_color(value).map(Rgba)
    }

    /// Red channel.
    pub const fn r(self) -> u8 {
        (self.0 >> 24) as u8
    }

    /// Green channel.
    pub const fn g(self) -> u8 {
        (self.0 >> 16) as u8
    }

    /// Blue channel.
    pub const fn b(self) -> u8 {
        (self.0 >> 8) as u8
    }

    /// Alpha channel (0 transparent .. 255 opaque).
    pub const fn a(self) -> u8 {
        self.0 as u8
    }

    /// The colour without alpha, as `0xRRGGBB`.
    pub const fn rgb(self) -> u32 {
        self.0 >> 8
    }

    /// Alpha as 0.0..=1.0.
    pub fn alpha(self) -> f32 {
        f32::from(self.a()) / 255.0
    }

    /// Whether alpha is 255.
    pub const fn is_opaque(self) -> bool {
        self.a() == 0xFF
    }

    /// The same colour with a different alpha.
    pub const fn with_alpha(self, a: u8) -> Self {
        Rgba((self.0 & 0xFFFF_FF00) | a as u32)
    }

    /// Composites this colour over `backdrop` (whose own alpha is ignored), giving an opaque
    /// colour. Channels are rounded the way `ThemeTokensTest` rounds them.
    pub fn over(self, backdrop: Rgba) -> Rgba {
        let alpha = f64::from(self.a()) / 255.0;
        let mix = |over: u8, under: u8| -> u8 {
            (f64::from(over) * alpha + f64::from(under) * (1.0 - alpha)).round() as u8
        };
        Rgba::new(
            mix(self.r(), backdrop.r()),
            mix(self.g(), backdrop.g()),
            mix(self.b(), backdrop.b()),
            0xFF,
        )
    }

    /// `[r, g, b, a]`, each 0.0..=1.0 (for a GPUI `Rgba`).
    pub fn to_f32(self) -> [f32; 4] {
        [
            f32::from(self.r()) / 255.0,
            f32::from(self.g()) / 255.0,
            f32::from(self.b()) / 255.0,
            self.alpha(),
        ]
    }

    /// `[h, s, l, a]`, each 0.0..=1.0 (hue as a fraction of a turn, as GPUI's `Hsla` stores it).
    pub fn to_hsla(self) -> [f32; 4] {
        let [r, g, b, a] = self.to_f32();
        let max = r.max(g).max(b);
        let min = r.min(g).min(b);
        let l = (max + min) / 2.0;
        let delta = max - min;
        if delta == 0.0 {
            return [0.0, 0.0, l, a];
        }
        let s = if l > 0.5 {
            delta / (2.0 - max - min)
        } else {
            delta / (max + min)
        };
        let h = if max == r {
            ((g - b) / delta).rem_euclid(6.0)
        } else if max == g {
            (b - r) / delta + 2.0
        } else {
            (r - g) / delta + 4.0
        } / 6.0;
        [h, s, l, a]
    }

    /// `#RRGGBB`, ignoring alpha (the form the Java launcher passes around as a bg hex).
    pub fn to_hex_rgb(self) -> String {
        format!("#{:06X}", self.rgb())
    }

    /// `#RRGGBB` when opaque, else `#RRGGBBAA`.
    pub fn to_hex(self) -> String {
        if self.is_opaque() {
            self.to_hex_rgb()
        } else {
            format!("#{:08X}", self.0)
        }
    }

    /// A Win32 `COLORREF` (`0x00BBGGRR`), as DWM caption and border colours take it.
    pub const fn to_colorref(self) -> u32 {
        ((self.b() as u32) << 16) | ((self.g() as u32) << 8) | self.r() as u32
    }
}

impl fmt::Debug for Rgba {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Rgba(0x{:08X})", self.0)
    }
}

impl fmt::Display for Rgba {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.to_hex())
    }
}

impl From<u32> for Rgba {
    fn from(v: u32) -> Self {
        Rgba(v)
    }
}

impl From<Rgba> for u32 {
    fn from(c: Rgba) -> Self {
        c.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn channels_and_packing() {
        let c = Rgba::new(0x12, 0x34, 0x56, 0x78);
        assert_eq!(c.0, 0x1234_5678);
        assert_eq!((c.r(), c.g(), c.b(), c.a()), (0x12, 0x34, 0x56, 0x78));
        assert_eq!(c.rgb(), 0x123456);
        assert_eq!(Rgba::opaque(0x0C1017), Rgba(0x0C10_17FF));
        assert_eq!(c.with_alpha(0xFF), Rgba(0x1234_56FF));
    }

    #[test]
    fn parses_every_form_the_sheets_use() {
        assert_eq!(Rgba::parse_css("#0C1017"), Some(Rgba(0x0C10_17FF)));
        assert_eq!(Rgba::parse_css("#fff"), Some(Rgba::WHITE));
        assert_eq!(Rgba::parse_css("#11223344"), Some(Rgba(0x1122_3344)));
        assert_eq!(Rgba::parse_css("white"), Some(Rgba::WHITE));
        assert_eq!(Rgba::parse_css("transparent"), Some(Rgba::TRANSPARENT));
        assert_eq!(
            Rgba::parse_css("rgba(0, 0, 0, 0.55)"),
            Some(Rgba(0x0000_008C))
        );
        assert_eq!(
            Rgba::parse_css("rgba(255, 255, 255, 0.0)"),
            Some(Rgba(0xFFFF_FF00))
        );
        assert_eq!(Rgba::parse_css("rgba(0, 0, 0, 0)"), Some(Rgba(0)));
        assert_eq!(Rgba::parse_css("rgb(1, 2, 3)"), Some(Rgba(0x0102_03FF)));
        assert_eq!(
            Rgba::parse_css("rgba(1, 2, 3, 50%)"),
            Some(Rgba(0x0102_0380))
        );
        assert_eq!(Rgba::parse_css("-md-surface-container-high"), None);
        assert_eq!(Rgba::parse_css("#12345"), None);
        assert_eq!(Rgba::parse_css("rgba(300, 0, 0, 1)"), None);
        assert_eq!(Rgba::parse_css("rgba(0, 0, 0, 2)"), None);
    }

    #[test]
    fn half_white_over_black_is_mid_grey() {
        let grey = Rgba::parse_css("rgba(255, 255, 255, 0.5)")
            .unwrap()
            .over(Rgba::BLACK);
        assert!(grey.rgb().abs_diff(0x808080) <= 0x010101, "{grey}");
        assert!(grey.is_opaque());
        assert_eq!(
            Rgba::opaque(0x123456).over(Rgba::WHITE),
            Rgba::opaque(0x123456)
        );
    }

    #[test]
    fn conversions() {
        assert_eq!(Rgba::opaque(0x0C1017).to_hex_rgb(), "#0C1017");
        assert_eq!(Rgba(0x0000_008C).to_hex(), "#0000008C");
        assert_eq!(Rgba::opaque(0x112233).to_colorref(), 0x0033_2211);
        assert_eq!(Rgba::WHITE.to_f32(), [1.0, 1.0, 1.0, 1.0]);
        let [h, s, l, a] = Rgba::opaque(0xFF0000).to_hsla();
        assert!((h - 0.0).abs() < 1e-6 && (s - 1.0).abs() < 1e-6 && (l - 0.5).abs() < 1e-6);
        assert_eq!(a, 1.0);
        let [h, ..] = Rgba::opaque(0x00FF00).to_hsla();
        assert!((h - 1.0 / 3.0).abs() < 1e-6);
        let [h, ..] = Rgba::opaque(0x0000FF).to_hsla();
        assert!((h - 2.0 / 3.0).abs() < 1e-6);
        assert_eq!(Rgba::opaque(0x808080).to_hsla()[1], 0.0);
    }
}

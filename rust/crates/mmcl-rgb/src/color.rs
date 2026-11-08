// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`RgbColor`]: the 24-bit colour every effect and backend speaks (Java `RgbColor`).

use std::fmt;

use crate::error::RgbError;

/// A 24-bit RGB colour. Channels are `u8`, so an out-of-range value is unrepresentable; the
/// arithmetic paths that work in wider integers use [`RgbColor::try_new`].
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash, Default)]
pub struct RgbColor {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

impl RgbColor {
    /// `#000000`.
    pub const BLACK: RgbColor = RgbColor::new(0, 0, 0);
    /// `#FFFFFF`.
    pub const WHITE: RgbColor = RgbColor::new(255, 255, 255);

    /// A colour from its three channels.
    pub const fn new(r: u8, g: u8, b: u8) -> RgbColor {
        RgbColor { r, g, b }
    }

    /// A colour from wide integers; any channel outside `0..=255` is an error (Java's
    /// constructor check).
    pub fn try_new(r: i32, g: i32, b: i32) -> Result<RgbColor, RgbError> {
        let ch =
            |v: i32| u8::try_from(v).map_err(|_| RgbError::InvalidColor(format!("{r},{g},{b}")));
        Ok(RgbColor::new(ch(r)?, ch(g)?, ch(b)?))
    }

    /// Parses `#RRGGBB` or `RRGGBB` (exactly six hex digits after the optional `#`).
    pub fn from_hex(hex: &str) -> Result<RgbColor, RgbError> {
        let digits = hex.strip_prefix('#').unwrap_or(hex);
        if digits.len() != 6 || !digits.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(RgbError::InvalidColor(hex.to_string()));
        }
        let v =
            u32::from_str_radix(digits, 16).map_err(|_| RgbError::InvalidColor(hex.to_string()))?;
        Ok(RgbColor::from_packed(v))
    }

    /// The colour of a `0x00RRGGBB` value (the top byte is ignored).
    pub const fn from_packed(v: u32) -> RgbColor {
        RgbColor::new((v >> 16) as u8, (v >> 8) as u8, v as u8)
    }

    /// `0x00RRGGBB`.
    pub const fn pack_rgb(self) -> u32 {
        ((self.r as u32) << 16) | ((self.g as u32) << 8) | self.b as u32
    }

    /// Razer's `COLORREF` packing, `(b << 16) | (g << 8) | r` (red in the low byte). Used by
    /// both Chroma paths.
    pub const fn chroma_pack(self) -> u32 {
        ((self.b as u32) << 16) | ((self.g as u32) << 8) | self.r as u32
    }

    /// Linear blend from `a` (t = 0) to `b` (t = 1); `t` is clamped to `[0, 1]`.
    pub fn blend(a: RgbColor, b: RgbColor, t: f64) -> RgbColor {
        let t = if t.is_nan() { 0.0 } else { t.clamp(0.0, 1.0) };
        let mix = |x: u8, y: u8| {
            let v = x as f64 + (y as f64 - x as f64) * t;
            v.round().clamp(0.0, 255.0) as u8
        };
        RgbColor::new(mix(a.r, b.r), mix(a.g, b.g), mix(a.b, b.b))
    }

    /// The channel inversion `(255 - r, 255 - g, 255 - b)`. (Java's doc calls this an HSL
    /// rotation; the code inverts the channels, and so does this port.)
    pub const fn complement(self) -> RgbColor {
        RgbColor::new(255 - self.r, 255 - self.g, 255 - self.b)
    }

    /// HSL to RGB: `hue_deg` in degrees (any value; wrapped), `s` and `l` in `[0, 1]`.
    pub fn hsl_to_rgb(hue_deg: f64, s: f64, l: f64) -> RgbColor {
        let h = ((hue_deg % 360.0) + 360.0) % 360.0;
        let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
        let x = c * (1.0 - ((h / 60.0) % 2.0 - 1.0).abs());
        let m = l - c / 2.0;
        let (r1, g1, b1) = if h < 60.0 {
            (c, x, 0.0)
        } else if h < 120.0 {
            (x, c, 0.0)
        } else if h < 180.0 {
            (0.0, c, x)
        } else if h < 240.0 {
            (0.0, x, c)
        } else if h < 300.0 {
            (x, 0.0, c)
        } else {
            (c, 0.0, x)
        };
        let ch = |v: f64| ((v + m) * 255.0).round().clamp(0.0, 255.0) as u8;
        RgbColor::new(ch(r1), ch(g1), ch(b1))
    }

    /// RGB to HSL: `(hue degrees in [0, 360), saturation, lightness)`.
    pub fn to_hsl(self) -> (f64, f64, f64) {
        let r = self.r as f64 / 255.0;
        let g = self.g as f64 / 255.0;
        let b = self.b as f64 / 255.0;
        let max = r.max(g).max(b);
        let min = r.min(g).min(b);
        let l = (max + min) / 2.0;
        if max == min {
            return (0.0, 0.0, l);
        }
        let d = max - min;
        let s = if l > 0.5 {
            d / (2.0 - max - min)
        } else {
            d / (max + min)
        };
        let h = if max == r {
            (g - b) / d + if g < b { 6.0 } else { 0.0 }
        } else if max == g {
            (b - r) / d + 2.0
        } else {
            (r - g) / d + 4.0
        };
        (h * 60.0, s, l)
    }

    /// Euclidean distance between two colours in normalised `0..1` RGB (`0..=sqrt(3)`).
    pub fn distance(a: RgbColor, b: RgbColor) -> f64 {
        let d = |x: u8, y: u8| (x as f64 - y as f64) / 255.0;
        let (dr, dg, db) = (d(a.r, b.r), d(a.g, b.g), d(a.b, b.b));
        (dr * dr + dg * dg + db * db).sqrt()
    }
}

impl fmt::Display for RgbColor {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "#{:02X}{:02X}{:02X}", self.r, self.g, self.b)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn from_hex_accepts_and_rejects() {
        assert_eq!(
            RgbColor::from_hex("#6FCF3D").unwrap(),
            RgbColor::new(0x6F, 0xCF, 0x3D)
        );
        assert_eq!(
            RgbColor::from_hex("6fcf3d").unwrap(),
            RgbColor::new(0x6F, 0xCF, 0x3D)
        );
        for bad in [
            "", "#", "#12345", "#1234567", "12345G", "##123456", "+12345", " 123456",
        ] {
            assert!(
                RgbColor::from_hex(bad).is_err(),
                "{bad:?} should be rejected"
            );
        }
    }

    #[test]
    fn try_new_range() {
        assert!(RgbColor::try_new(0, 255, 128).is_ok());
        assert!(RgbColor::try_new(-1, 0, 0).is_err());
        assert!(RgbColor::try_new(0, 256, 0).is_err());
    }

    #[test]
    fn blend_clamps_and_interpolates() {
        let a = RgbColor::new(0, 100, 200);
        let b = RgbColor::new(255, 0, 0);
        assert_eq!(RgbColor::blend(a, b, -1.0), a);
        assert_eq!(RgbColor::blend(a, b, 2.0), b);
        assert_eq!(RgbColor::blend(a, b, 0.5), RgbColor::new(128, 50, 100));
    }

    #[test]
    fn complement_pack_and_display() {
        let c = RgbColor::new(0x12, 0x34, 0x56);
        assert_eq!(c.complement(), RgbColor::new(0xED, 0xCB, 0xA9));
        assert_eq!(c.pack_rgb(), 0x0012_3456);
        assert_eq!(c.chroma_pack(), 0x0056_3412);
        assert_eq!(c.to_string(), "#123456");
        assert_eq!(RgbColor::from_packed(0xFF12_3456), c);
    }

    #[test]
    fn hsl_round_trip() {
        for c in [
            RgbColor::new(0x6F, 0xCF, 0x3D),
            RgbColor::new(0x06, 0x68, 0xE1),
            RgbColor::new(0xD2, 0x57, 0xDB),
            RgbColor::new(10, 10, 10),
        ] {
            let (h, s, l) = c.to_hsl();
            let back = RgbColor::hsl_to_rgb(h, s, l);
            assert!((back.r as i32 - c.r as i32).abs() <= 1);
            assert!((back.g as i32 - c.g as i32).abs() <= 1);
            assert!((back.b as i32 - c.b as i32).abs() <= 1);
        }
    }
}

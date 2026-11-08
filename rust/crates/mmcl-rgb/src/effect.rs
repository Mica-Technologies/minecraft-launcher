// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The [`RgbEffect`] trait and the built-in effects (Java `RgbEffect`, `effects/*`).
//!
//! Effects are pure functions of the time since they were activated: they hold immutable
//! configuration only, never a clock, and must be cheap (the engine calls them ~30 times a
//! second).

use std::collections::BTreeSet;
use std::f64::consts::PI;

use crate::color::RgbColor;
use crate::error::RgbError;
use crate::frame::RgbFrame;
use crate::key::KeyboardKey;

/// A lighting effect.
pub trait RgbEffect: Send + Sync {
    /// The label shown in logs and status (internal, not localised; Java parity).
    fn name(&self) -> String;
    /// The frame `elapsed_ms` after activation; `None` means "no change this tick".
    fn frame_at(&self, elapsed_ms: u64) -> Option<RgbFrame>;
}

fn check_period(period_ms: i64) -> Result<u64, RgbError> {
    if period_ms <= 0 {
        Err(RgbError::InvalidEffect(format!(
            "period must be positive, got {period_ms}"
        )))
    } else {
        Ok(period_ms as u64)
    }
}

/// `(elapsed % period) / period`, in `[0, 1)`.
fn phase(elapsed_ms: u64, period_ms: u64) -> f64 {
    (elapsed_ms % period_ms) as f64 / period_ms as f64
}

/// One colour, forever.
#[derive(Clone, Debug)]
pub struct SolidEffect {
    name: String,
    frame: RgbFrame,
}

impl SolidEffect {
    pub fn new(name: impl Into<String>, color: RgbColor) -> SolidEffect {
        SolidEffect {
            name: name.into(),
            frame: RgbFrame::solid(color),
        }
    }

    /// The colour.
    pub fn color(&self) -> RgbColor {
        self.frame.background()
    }
}

impl RgbEffect for SolidEffect {
    fn name(&self) -> String {
        self.name.clone()
    }

    fn frame_at(&self, _elapsed_ms: u64) -> Option<RgbFrame> {
        Some(self.frame)
    }
}

/// A cosine ease between two colours: `a` at the start, `b` at half the period.
#[derive(Clone, Debug)]
pub struct PulseEffect {
    name: String,
    a: RgbColor,
    b: RgbColor,
    period_ms: u64,
}

impl PulseEffect {
    pub fn new(
        name: impl Into<String>,
        a: RgbColor,
        b: RgbColor,
        period_ms: i64,
    ) -> Result<PulseEffect, RgbError> {
        Ok(PulseEffect {
            name: name.into(),
            a,
            b,
            period_ms: check_period(period_ms)?,
        })
    }
}

impl RgbEffect for PulseEffect {
    fn name(&self) -> String {
        self.name.clone()
    }

    fn frame_at(&self, elapsed_ms: u64) -> Option<RgbFrame> {
        let p = phase(elapsed_ms, self.period_ms);
        let t = (1.0 - (2.0 * PI * p).cos()) / 2.0;
        Some(RgbFrame::solid(RgbColor::blend(self.a, self.b, t)))
    }
}

/// Eases through a palette, one segment per colour, wrapping from the last back to the first.
#[derive(Clone, Debug)]
pub struct CycleEffect {
    name: String,
    colors: Vec<RgbColor>,
    period_ms: u64,
}

impl CycleEffect {
    pub fn new(
        name: impl Into<String>,
        colors: Vec<RgbColor>,
        period_ms: i64,
    ) -> Result<CycleEffect, RgbError> {
        if colors.is_empty() {
            return Err(RgbError::InvalidEffect("cycle palette is empty".into()));
        }
        Ok(CycleEffect {
            name: name.into(),
            colors,
            period_ms: check_period(period_ms)?,
        })
    }

    /// Number of colours in the palette.
    pub fn color_count(&self) -> usize {
        self.colors.len()
    }
}

impl RgbEffect for CycleEffect {
    fn name(&self) -> String {
        self.name.clone()
    }

    fn frame_at(&self, elapsed_ms: u64) -> Option<RgbFrame> {
        let n = self.colors.len();
        if n == 1 {
            return Some(RgbFrame::solid(self.colors[0]));
        }
        let scaled = phase(elapsed_ms, self.period_ms) * n as f64;
        let seg = (scaled.floor() as usize).min(n - 1);
        let seg_phase = scaled - seg as f64;
        let t = (1.0 - (PI * seg_phase).cos()) / 2.0;
        let from = self.colors[seg];
        let to = self.colors[(seg + 1) % n];
        Some(RgbFrame::solid(RgbColor::blend(from, to, t)))
    }
}

/// A full-saturation hue sweep.
#[derive(Clone, Debug)]
pub struct RainbowEffect {
    name: String,
    period_ms: u64,
}

impl RainbowEffect {
    pub fn new(name: impl Into<String>, period_ms: i64) -> Result<RainbowEffect, RgbError> {
        Ok(RainbowEffect {
            name: name.into(),
            period_ms: check_period(period_ms)?,
        })
    }
}

impl RgbEffect for RainbowEffect {
    fn name(&self) -> String {
        self.name.clone()
    }

    fn frame_at(&self, elapsed_ms: u64) -> Option<RgbFrame> {
        let hue = phase(elapsed_ms, self.period_ms) * 360.0;
        Some(RgbFrame::solid(RgbColor::hsl_to_rgb(hue, 1.0, 0.5)))
    }
}

/// The static in-game look: the pack colour as background with the movement keys highlighted.
#[derive(Clone, Debug)]
pub struct InGameEffect {
    name: String,
    frame: RgbFrame,
}

impl InGameEffect {
    /// W, A, S, D, E, Q, F, Space, Left Shift and Left Ctrl.
    pub const DEFAULT_HIGHLIGHTED_KEYS: [KeyboardKey; 10] = [
        KeyboardKey::W,
        KeyboardKey::A,
        KeyboardKey::S,
        KeyboardKey::D,
        KeyboardKey::E,
        KeyboardKey::Q,
        KeyboardKey::F,
        KeyboardKey::Space,
        KeyboardKey::LeftShift,
        KeyboardKey::LeftCtrl,
    ];

    pub fn new(
        pack_display_name: &str,
        background: RgbColor,
        highlight: RgbColor,
        keys: &BTreeSet<KeyboardKey>,
    ) -> InGameEffect {
        let name = if pack_display_name.is_empty() {
            "In-Game".to_string()
        } else {
            format!("In-Game ({pack_display_name})")
        };
        InGameEffect {
            name,
            frame: RgbFrame::new(background, keys.iter().map(|k| (*k, highlight))),
        }
    }

    /// The one frame this effect shows.
    pub fn frame(&self) -> RgbFrame {
        self.frame
    }
}

impl RgbEffect for InGameEffect {
    fn name(&self) -> String {
        self.name.clone()
    }

    fn frame_at(&self, _elapsed_ms: u64) -> Option<RgbFrame> {
        Some(self.frame)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const RED: RgbColor = RgbColor::new(255, 0, 0);
    const GREEN: RgbColor = RgbColor::new(0, 255, 0);
    const BLUE: RgbColor = RgbColor::new(0, 0, 255);

    fn bg(e: &dyn RgbEffect, t: u64) -> RgbColor {
        e.frame_at(t).unwrap().background()
    }

    fn approx(expected: RgbColor, actual: RgbColor) {
        let d = |a: u8, b: u8| (a as i32 - b as i32).abs();
        assert!(
            d(expected.r, actual.r) <= 1
                && d(expected.g, actual.g) <= 1
                && d(expected.b, actual.b) <= 1,
            "expected {expected} got {actual}"
        );
    }

    // ---- CycleEffectTest ----

    #[test]
    fn cycle_empty_palette_rejected() {
        assert!(CycleEffect::new("x", vec![], 1000).is_err());
    }

    #[test]
    fn cycle_non_positive_period_rejected() {
        assert!(CycleEffect::new("x", vec![RED], 0).is_err());
        assert!(CycleEffect::new("x", vec![RED], -1).is_err());
    }

    #[test]
    fn cycle_single_color_is_solid() {
        let e = CycleEffect::new("x", vec![RED], 1000).unwrap();
        for t in [0, 1, 250, 999, 1000, 123_456] {
            assert_eq!(bg(&e, t), RED);
        }
    }

    #[test]
    fn cycle_segment_starts_hit_each_color() {
        let e = CycleEffect::new("x", vec![RED, GREEN, BLUE], 3000).unwrap();
        assert_eq!(bg(&e, 0), RED);
        assert_eq!(bg(&e, 1000), GREEN);
        assert_eq!(bg(&e, 2000), BLUE);
    }

    #[test]
    fn cycle_midpoints_blend_half() {
        let e = CycleEffect::new("x", vec![RED, GREEN, BLUE], 3000).unwrap();
        approx(RgbColor::blend(RED, GREEN, 0.5), bg(&e, 500));
        approx(RgbColor::blend(GREEN, BLUE, 0.5), bg(&e, 1500));
        approx(RgbColor::blend(BLUE, RED, 0.5), bg(&e, 2500));
    }

    #[test]
    fn cycle_wraps_at_period() {
        let e = CycleEffect::new("x", vec![RED, GREEN, BLUE], 3000).unwrap();
        assert_eq!(bg(&e, 0), bg(&e, 3000));
        assert_eq!(bg(&e, 500), bg(&e, 3500));
        assert_eq!(bg(&e, 2999), bg(&e, 5999));
    }

    #[test]
    fn cycle_color_count() {
        assert_eq!(
            CycleEffect::new("x", vec![RED, GREEN, BLUE], 3000)
                .unwrap()
                .color_count(),
            3
        );
        assert_eq!(
            CycleEffect::new("x", vec![RED], 3000)
                .unwrap()
                .color_count(),
            1
        );
    }

    // ---- RainbowEffectTest ----

    #[test]
    fn rainbow_non_positive_period_rejected() {
        assert!(RainbowEffect::new("x", 0).is_err());
        assert!(RainbowEffect::new("x", -1).is_err());
    }

    #[test]
    fn rainbow_zero_phase_is_red() {
        let e = RainbowEffect::new("x", 6000).unwrap();
        assert_eq!(bg(&e, 0), RED);
    }

    #[test]
    fn rainbow_primary_stops() {
        let e = RainbowEffect::new("x", 6000).unwrap();
        approx(RED, bg(&e, 0));
        approx(RgbColor::new(255, 255, 0), bg(&e, 1000));
        approx(GREEN, bg(&e, 2000));
        approx(RgbColor::new(0, 255, 255), bg(&e, 3000));
        approx(BLUE, bg(&e, 4000));
        approx(RgbColor::new(255, 0, 255), bg(&e, 5000));
    }

    #[test]
    fn rainbow_wraps() {
        let e = RainbowEffect::new("x", 6000).unwrap();
        assert_eq!(bg(&e, 0), bg(&e, 6000));
        assert_eq!(bg(&e, 1500), bg(&e, 7500));
    }

    #[test]
    fn hsl_to_rgb_stops() {
        approx(RED, RgbColor::hsl_to_rgb(0.0, 1.0, 0.5));
        approx(
            RgbColor::new(255, 255, 0),
            RgbColor::hsl_to_rgb(60.0, 1.0, 0.5),
        );
        approx(
            RgbColor::new(0, 255, 255),
            RgbColor::hsl_to_rgb(180.0, 1.0, 0.5),
        );
        let mid = RgbColor::hsl_to_rgb(0.0, 0.5, 0.75);
        assert!((220..=224).contains(&mid.r), "R was {}", mid.r);
        assert_eq!(mid.g, mid.b);
    }

    // ---- new tests ----

    #[test]
    fn pulse_starts_at_a_and_peaks_at_b() {
        let e = PulseEffect::new("p", RED, BLUE, 2000).unwrap();
        assert_eq!(bg(&e, 0), RED);
        assert_eq!(bg(&e, 1000), BLUE);
        assert_eq!(bg(&e, 2000), RED);
        assert!(PulseEffect::new("p", RED, BLUE, 0).is_err());
    }

    #[test]
    fn in_game_names_and_frame() {
        let keys: BTreeSet<_> = InGameEffect::DEFAULT_HIGHLIGHTED_KEYS.into_iter().collect();
        let e = InGameEffect::new("", RED, GREEN, &keys);
        assert_eq!(e.name(), "In-Game");
        let f = e.frame_at(42).unwrap();
        assert_eq!(f.background(), RED);
        assert_eq!(f.color_for(KeyboardKey::W), GREEN);
        assert_eq!(f.color_for(KeyboardKey::Escape), RED);
        assert_eq!(
            InGameEffect::new("Pack", RED, GREEN, &BTreeSet::new()).name(),
            "In-Game (Pack)"
        );
        assert_eq!(
            SolidEffect::new("s", BLUE).frame_at(9).unwrap(),
            RgbFrame::solid(BLUE)
        );
    }
}

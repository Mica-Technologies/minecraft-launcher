// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Motion tokens (Material 3 easing and durations) and a cubic-bezier evaluator.

use std::time::Duration;

/// A CSS-style `cubic-bezier(x1, y1, x2, y2)` easing curve from (0,0) to (1,1).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CubicBezier {
    /// First control point x (0..=1).
    pub x1: f32,
    /// First control point y.
    pub y1: f32,
    /// Second control point x (0..=1).
    pub x2: f32,
    /// Second control point y.
    pub y2: f32,
}

impl CubicBezier {
    /// A curve with the given control points.
    pub const fn new(x1: f32, y1: f32, x2: f32, y2: f32) -> Self {
        CubicBezier { x1, y1, x2, y2 }
    }

    fn component(t: f32, p1: f32, p2: f32) -> f32 {
        let u = 1.0 - t;
        3.0 * u * u * t * p1 + 3.0 * u * t * t * p2 + t * t * t
    }

    fn derivative(t: f32, p1: f32, p2: f32) -> f32 {
        let u = 1.0 - t;
        3.0 * u * u * p1 + 6.0 * u * t * (p2 - p1) + 3.0 * t * t * (1.0 - p2)
    }

    /// Eased progress for linear progress `x` (clamped to 0..=1): solves the curve's x for `x`
    /// by Newton-Raphson (bisection fallback) and returns its y.
    pub fn eval(&self, x: f32) -> f32 {
        let x = x.clamp(0.0, 1.0);
        if x == 0.0 || x == 1.0 {
            return x;
        }
        let mut t = x;
        for _ in 0..8 {
            let err = Self::component(t, self.x1, self.x2) - x;
            if err.abs() < 1e-6 {
                return Self::component(t, self.y1, self.y2);
            }
            let d = Self::derivative(t, self.x1, self.x2);
            if d.abs() < 1e-6 {
                break;
            }
            t = (t - err / d).clamp(0.0, 1.0);
        }
        let (mut lo, mut hi) = (0.0f32, 1.0f32);
        t = x;
        for _ in 0..40 {
            let v = Self::component(t, self.x1, self.x2);
            if (v - x).abs() < 1e-6 {
                break;
            }
            if v < x {
                lo = t;
            } else {
                hi = t;
            }
            t = (lo + hi) / 2.0;
        }
        Self::component(t, self.y1, self.y2)
    }
}

/// Standard easing `cubic-bezier(0.2, 0, 0, 1)`.
pub const STANDARD: CubicBezier = CubicBezier::new(0.2, 0.0, 0.0, 1.0);
/// Emphasized decelerate `cubic-bezier(0.05, 0.7, 0.1, 1)` (things entering).
pub const EMPHASIZED_DECELERATE: CubicBezier = CubicBezier::new(0.05, 0.7, 0.1, 1.0);
/// Emphasized accelerate `cubic-bezier(0.3, 0, 0.8, 0.15)` (things leaving).
pub const EMPHASIZED_ACCELERATE: CubicBezier = CubicBezier::new(0.3, 0.0, 0.8, 0.15);

/// Short duration: 150 ms.
pub const SHORT: Duration = Duration::from_millis(150);
/// Medium duration: 300 ms.
pub const MEDIUM: Duration = Duration::from_millis(300);
/// Long duration: 450 ms.
pub const LONG: Duration = Duration::from_millis(450);
/// Fade-through (screen swaps, Settings categories, Running Games tabs): 220 ms.
pub const FADE_THROUGH: Duration = Duration::from_millis(220);
/// Home's one-time first-show root fade: 180 ms.
pub const FIRST_SHOW_FADE: Duration = Duration::from_millis(180);

/// Scale an entering element grows from, and an exiting one shrinks to.
pub const ENTER_EXIT_SCALE: f32 = 0.96;
/// Distance (px) a fade-through rises from.
pub const FADE_THROUGH_RISE: f32 = 8.0;

/// One transition's parameters.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Transition {
    /// How long it runs.
    pub duration: Duration,
    /// Its easing.
    pub easing: CubicBezier,
}

/// `enter`: 300 ms fade 0 -> 1 + scale 0.96 -> 1, emphasized decelerate (dialogs, detail modal).
pub const ENTER: Transition = Transition {
    duration: MEDIUM,
    easing: EMPHASIZED_DECELERATE,
};
/// `exit`: 150 ms fade -> 0 + scale -> 0.96, emphasized accelerate.
pub const EXIT: Transition = Transition {
    duration: SHORT,
    easing: EMPHASIZED_ACCELERATE,
};
/// `fade_through`: 220 ms fade 0 -> 1 + rise 8 -> 0 px, emphasized decelerate.
pub const FADE_THROUGH_TRANSITION: Transition = Transition {
    duration: FADE_THROUGH,
    easing: EMPHASIZED_DECELERATE,
};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoints_are_fixed() {
        for curve in [STANDARD, EMPHASIZED_DECELERATE, EMPHASIZED_ACCELERATE] {
            assert_eq!(curve.eval(0.0), 0.0);
            assert_eq!(curve.eval(1.0), 1.0);
            assert_eq!(curve.eval(-1.0), 0.0);
            assert_eq!(curve.eval(2.0), 1.0);
        }
    }

    #[test]
    fn linear_curve_is_identity() {
        let linear = CubicBezier::new(1.0 / 3.0, 1.0 / 3.0, 2.0 / 3.0, 2.0 / 3.0);
        for i in 0..=10 {
            let x = i as f32 / 10.0;
            assert!((linear.eval(x) - x).abs() < 1e-4, "{x}");
        }
    }

    #[test]
    fn decelerate_leads_and_accelerate_lags() {
        assert!(EMPHASIZED_DECELERATE.eval(0.25) > 0.6);
        assert!(EMPHASIZED_ACCELERATE.eval(0.5) < 0.3);
        // Monotonic for these curves.
        for curve in [STANDARD, EMPHASIZED_DECELERATE, EMPHASIZED_ACCELERATE] {
            let mut last = 0.0;
            for i in 1..=100 {
                let y = curve.eval(i as f32 / 100.0);
                assert!(y + 1e-5 >= last, "{curve:?} at {i}");
                last = y;
            }
        }
    }

    #[test]
    fn known_value_for_css_ease() {
        // CSS `ease` = cubic-bezier(0.25, 0.1, 0.25, 1); at x = 0.5, y ~= 0.8024.
        let ease = CubicBezier::new(0.25, 0.1, 0.25, 1.0);
        assert!((ease.eval(0.5) - 0.8024).abs() < 1e-3);
    }

    #[test]
    fn durations() {
        assert_eq!(ENTER.duration.as_millis(), 300);
        assert_eq!(EXIT.duration.as_millis(), 150);
        assert_eq!(FADE_THROUGH_TRANSITION.duration.as_millis(), 220);
        assert_eq!(LONG.as_millis(), 450);
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `LoadingIndicator`: the Material 3 Expressive loading indicator. A filled shape that turns
//! while morphing through seven shapes, each change landing with an overshoot. Default 32x32,
//! fill `-md-primary`. Animates only while painted (see [`crate::motion`]'s animation gate).

use std::f64::consts::{FRAC_PI_2, TAU};

use gpui::{
    App, Bounds, ElementId, InteractiveElement, IntoElement, ParentElement, PathBuilder, Pixels,
    RenderOnce, Styled, Window, canvas, div, point, px,
};

use crate::scale::u;
use crate::motion;
use crate::theme::{ThemeExt, hsla};

/// `(lobes, depth)` of the seven shapes, in morph order.
pub const SHAPES: [(f64, f64); 7] = [
    (9., 0.09),
    (5., 0.13),
    (4., 0.14),
    (12., 0.05),
    (2., 0.16),
    (6., 0.10),
    (8., 0.07),
];
/// Seconds per morph.
pub const MORPH_S: f64 = 0.65;
/// Seconds per full steady turn.
pub const TURN_S: f64 = 1.6;
/// Points around the outline.
pub const SAMPLES: usize = 96;
/// Default size in px.
pub const DEFAULT_SIZE: f32 = 32.0;

/// A shape's radius (relative to the outer radius, so at most 1) at angle `t`.
pub fn shape_radius((lobes, depth): (f64, f64), t: f64) -> f64 {
    (1.0 + depth * (lobes * t).cos()) / (1.0 + depth)
}

/// Overshooting ease (`easeOutBack`, c1 = 1.4).
pub fn ease_out_back(p: f64) -> f64 {
    let c1 = 1.4;
    let c3 = c1 + 1.0;
    1.0 + c3 * (p - 1.0).powi(3) + c1 * (p - 1.0).powi(2)
}

/// The closed outline at time `s` seconds, centre `(cx, cy)`, outer radius `r`.
pub fn outline(s: f64, cx: f64, cy: f64, r: f64) -> [(f64, f64); SAMPLES] {
    let cycle = s / MORPH_S;
    let i = cycle.floor() as i64;
    let p = cycle - i as f64;
    let from = SHAPES[i.rem_euclid(7) as usize];
    let to = SHAPES[(i + 1).rem_euclid(7) as usize];
    let e = ease_out_back(p);
    // A steady turn plus a quarter turn per morph.
    let rot = s / TURN_S * TAU + (i as f64 + e) * FRAC_PI_2;
    let mut out = [(0.0, 0.0); SAMPLES];
    for (k, slot) in out.iter_mut().enumerate() {
        let t = k as f64 * TAU / SAMPLES as f64;
        let rr = r * (shape_radius(from, t) * (1.0 - e) + shape_radius(to, t) * e);
        *slot = ((t + rot).cos() * rr + cx, (t + rot).sin() * rr + cy);
    }
    out
}

/// The outline drawn at clock time `s`: under reduce motion the clock is held at 0 (one shape,
/// no morph) and the shape turns `(s * 90) % 360` degrees, so it still reads as busy.
pub fn frame_outline(s: f64, reduced: bool, cx: f64, cy: f64, r: f64) -> [(f64, f64); SAMPLES] {
    if !reduced {
        return outline(s, cx, cy, r);
    }
    let base = outline(0.0, 0.0, 0.0, r);
    let angle = ((s * 90.0) % 360.0).to_radians();
    let (sin, cos) = angle.sin_cos();
    base.map(|(x, y)| (x * cos - y * sin + cx, x * sin + y * cos + cy))
}

/// The loading indicator element.
#[derive(IntoElement)]
pub struct LoadingIndicator {
    id: ElementId,
    size: f32,
}

impl LoadingIndicator {
    /// An indicator of the default 32 px size.
    pub fn new(id: impl Into<ElementId>) -> Self {
        LoadingIndicator {
            id: id.into(),
            size: DEFAULT_SIZE,
        }
    }

    /// Overrides the size (px, square).
    pub fn size(mut self, size: f32) -> Self {
        self.size = size;
        self
    }
}

fn paint_outline(bounds: Bounds<Pixels>, window: &mut Window, cx: &mut App) {
    let w = f64::from(f32::from(bounds.size.width));
    let h = f64::from(f32::from(bounds.size.height));
    let r = w.min(h) / 2.0;
    if r <= 0.0 {
        return;
    }
    let cx0 = f64::from(f32::from(bounds.origin.x)) + w / 2.0;
    let cy0 = f64::from(f32::from(bounds.origin.y)) + h / 2.0;
    let pts = frame_outline(motion::clock_seconds(), motion::is_reduced(), cx0, cy0, r);
    let points: Vec<_> = pts
        .iter()
        .map(|(x, y)| point(px(*x as f32), px(*y as f32)))
        .collect();
    let mut builder = PathBuilder::fill();
    builder.add_polygon(&points, true);
    if let Ok(path) = builder.build() {
        window.paint_path(path, hsla(cx.tokens().md_primary));
    }
    motion::request_frame(window);
}

impl RenderOnce for LoadingIndicator {
    fn render(self, _window: &mut Window, _cx: &mut App) -> impl IntoElement {
        div().id(self.id).flex_none().size(u(self.size)).child(
            canvas(
                |_, _, _| {},
                |bounds, _, window, cx| paint_outline(bounds, window, cx),
            )
            .size_full(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Port of `ProgressVisualsTest.loadingShapesStayInsideTheirRadiusAndMorphSmoothly`.
    #[test]
    fn loading_shapes_stay_inside_their_radius_and_morph_smoothly() {
        for shape in SHAPES {
            for i in 0..360 {
                let r = shape_radius(shape, f64::from(i).to_radians());
                assert!(r > 0.6 && r <= 1.0 + 1e-9, "radius {r}");
            }
        }
        assert!((ease_out_back(0.0)).abs() < 1e-9);
        assert!((ease_out_back(1.0) - 1.0).abs() < 1e-9);

        // Every point stays inside the overshoot envelope of the outer radius.
        let r = 16.0;
        for f in 0..400 {
            let s = f as f64 / 60.0;
            for (x, y) in outline(s, 16.0, 16.0, r) {
                let d = ((x - 16.0).powi(2) + (y - 16.0).powi(2)).sqrt();
                assert!(d <= r * 1.1, "point at {d} > radius at s={s}");
                assert!(d > r * 0.5, "point at {d} collapsed at s={s}");
            }
        }

        // Consecutive 60 fps frames move each point a bounded amount (no jumps at morph seams).
        let mut prev = outline(0.0, 16.0, 16.0, r);
        for f in 1..600 {
            let next = outline(f as f64 / 60.0, 16.0, 16.0, r);
            for (a, b) in prev.iter().zip(next.iter()) {
                let step = ((a.0 - b.0).powi(2) + (a.1 - b.1).powi(2)).sqrt();
                assert!(step < r * 0.5, "jump of {step} at frame {f}");
            }
            prev = next;
        }
        assert_eq!(outline(3.21, 16.0, 16.0, 16.0).len(), SAMPLES);
    }

    #[test]
    fn reduced_motion_keeps_one_shape_and_turns() {
        let a = frame_outline(0.0, true, 0.0, 0.0, 10.0);
        let b = frame_outline(1.0, true, 0.0, 0.0, 10.0);
        // Same shape: radii at matching samples are equal.
        for (p, q) in a.iter().zip(b.iter()) {
            let rp = (p.0 * p.0 + p.1 * p.1).sqrt();
            let rq = (q.0 * q.0 + q.1 * q.1).sqrt();
            assert!((rp - rq).abs() < 1e-9);
        }
        // But turned by 90 degrees.
        let angle = |p: (f64, f64)| p.1.atan2(p.0);
        let delta = (angle(b[0]) - angle(a[0])).rem_euclid(TAU);
        assert!((delta - FRAC_PI_2).abs() < 1e-9, "{delta}");
    }
}

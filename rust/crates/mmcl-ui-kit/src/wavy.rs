// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `WavyProgressBar`: the Material 3 Expressive wavy linear progress indicator.
//!
//! The wave (`-md-primary`) runs up to the progress, then a gap, the flat track
//! (`-md-active-indicator`) and a primary stop dot at the end. Progress is `0..=1`, or negative
//! for indeterminate (a wave segment sweeps across). The global [`set_wavy_enabled`] switch
//! (Settings -> Appearance) draws the bar flat but keeps the gap and stop dot.
//!
//! The wave phase comes from the shared motion clock, so a rebuilt bar keeps moving smoothly.
//! The bar asks for frames only while painted (the animation gate).

use std::f64::consts::TAU;
use std::sync::atomic::{AtomicBool, Ordering};

use gpui::{
    App, Bounds, Context, Hsla, IntoElement, ParentElement, PathBuilder, Pixels, Render, Styled,
    Window, canvas, div, fill, point, px, size,
};

use crate::scale::u;
use crate::motion;
use crate::theme::{ThemeExt, hsla};

/// Stroke width (px).
pub const STROKE: f64 = 4.0;
/// Wave amplitude (px).
pub const AMPLITUDE: f64 = 3.0;
/// Wavelength (px).
pub const WAVELENGTH: f64 = 28.0;
/// Gap between the wave and the track (px).
pub const GAP: f64 = 4.0;
/// Seconds per wave period.
pub const WAVE_PERIOD_S: f64 = 1.2;
/// Seconds per indeterminate sweep.
pub const SWEEP_PERIOD_S: f64 = 1.8;
/// Fraction of the width the indeterminate segment covers.
pub const SWEEP_FRACTION: f64 = 0.38;
/// Fixed height: `2 * AMPLITUDE + STROKE + 2`.
pub const HEIGHT: f64 = 2.0 * AMPLITUDE + STROKE + 2.0;
/// Preferred width (px).
pub const PREF_WIDTH: f64 = 240.0;

static WAVY: AtomicBool = AtomicBool::new(true);

/// Turns the wave on or off for every bar (process-wide). Bars restyle on their next frame;
/// use [`apply_wavy_enabled`] to repaint immediately.
pub fn set_wavy_enabled(enabled: bool) {
    WAVY.store(enabled, Ordering::Relaxed);
}

/// Whether bars draw a wave.
pub fn is_wavy_enabled() -> bool {
    WAVY.load(Ordering::Relaxed)
}

/// [`set_wavy_enabled`] and repaint every window.
pub fn apply_wavy_enabled(cx: &mut App, enabled: bool) {
    set_wavy_enabled(enabled);
    cx.refresh_windows();
}

/// The wave polyline from `from` to `to` around `cy`: 2 px steps; the first wavelength ramps
/// in from flat so a short wave grows out of the line. Always ends exactly at `to`.
pub fn wave_points(from: f64, to: f64, cy: f64, phase: f64, amplitude: f64) -> Vec<(f64, f64)> {
    let y_at = |xx: f64| {
        let ramp = ((xx - from) / WAVELENGTH).min(1.0);
        cy + (xx / WAVELENGTH * TAU - phase).sin() * amplitude * ramp
    };
    let mut out = Vec::new();
    let mut x = from;
    while x <= to + 0.001 {
        let xx = x.min(to);
        out.push((xx, y_at(xx)));
        x += 2.0;
    }
    if out.last().is_none_or(|(lx, _)| (*lx - to).abs() > 1e-9) {
        out.push((to, y_at(to)));
    }
    out
}

/// The wave phase at clock time `s` (held at 0 under reduce motion).
pub fn phase_at(s: f64, reduced: bool) -> f64 {
    if reduced {
        0.0
    } else {
        (s % WAVE_PERIOD_S) / WAVE_PERIOD_S * TAU
    }
}

/// One step of the eased drawn value toward `target` (snaps within 0.001).
pub fn ease_shown(shown: f64, target: f64) -> f64 {
    let target = target.min(1.0);
    let next = shown + (target - shown) * 0.2;
    if (target - next).abs() < 0.001 {
        target
    } else {
        next
    }
}

/// What to draw in one frame, in px.
#[derive(Debug, Clone, PartialEq)]
pub struct WavyFrame {
    /// The wave segment `(from, to)`, if any.
    pub wave: Option<(f64, f64)>,
    /// Flat track segments.
    pub tracks: Vec<(f64, f64)>,
    /// Stop dot centre x, if shown.
    pub stop_dot: Option<f64>,
}

/// Lays out an indeterminate frame at clock `s` over inner width `w` starting at `x0`.
/// The sweep keeps moving under reduce motion (it is the "working" signal).
pub fn indeterminate_frame(s: f64, x0: f64, w: f64) -> WavyFrame {
    let t = (s % SWEEP_PERIOD_S) / SWEEP_PERIOD_S;
    let len = w * SWEEP_FRACTION;
    let start = -len + (w + len) * t;
    let a = start.max(0.0);
    let b = (start + len).min(w);
    let mut tracks = Vec::new();
    if a - GAP > STROKE {
        tracks.push((x0 + STROKE / 2.0, x0 + a - GAP));
    }
    if w - (b + GAP) > STROKE {
        tracks.push((x0 + b + GAP, x0 + w - STROKE / 2.0));
    }
    WavyFrame {
        wave: (b > a).then_some((x0 + a, x0 + b)),
        tracks,
        stop_dot: None,
    }
}

/// Lays out a determinate frame for drawn value `shown` over inner width `w` from `x0`.
pub fn determinate_frame(shown: f64, x0: f64, w: f64) -> WavyFrame {
    let end = x0 + STROKE / 2.0 + (w - STROKE) * shown;
    let wave = (shown > 0.0).then_some((x0 + STROKE / 2.0, end));
    let track_from = if shown > 0.0 {
        end + GAP + STROKE
    } else {
        x0 + STROKE / 2.0
    };
    let track_to = x0 + w - STROKE / 2.0;
    let tracks = if track_to > track_from {
        vec![(track_from, track_to)]
    } else {
        Vec::new()
    };
    WavyFrame {
        wave,
        tracks,
        stop_dot: (shown < 0.999).then_some(track_to),
    }
}

/// The wavy progress bar view. Keep one per bar (`cx.new(|_| WavyProgressBar::new(p))`).
pub struct WavyProgressBar {
    target: f64,
    shown: f64,
}

impl WavyProgressBar {
    /// A bar at `initial` progress (negative = indeterminate).
    pub fn new(initial: f64) -> Self {
        WavyProgressBar {
            target: initial,
            shown: initial.max(0.0),
        }
    }

    /// An indeterminate bar.
    pub fn indeterminate() -> Self {
        Self::new(-1.0)
    }

    /// Sets the progress (`0..=1`, negative = indeterminate); the drawn value eases toward it.
    pub fn set_progress(&mut self, progress: f64, cx: &mut Context<Self>) {
        if self.target != progress {
            self.target = progress;
            cx.notify();
        }
    }

    /// The current target.
    pub fn progress(&self) -> f64 {
        self.target
    }

    /// Whether the bar is indeterminate.
    pub fn is_indeterminate(&self) -> bool {
        self.target < 0.0
    }
}

/// Maps layout units (px at 100 %) of the bar to window pixels: `x0`/`cy` are the bar's left
/// edge and centre line, `f` the interface scale factor.
#[derive(Clone, Copy)]
struct Mapping {
    x0: f64,
    cy: f64,
    f: f64,
}

impl Mapping {
    fn point(self, (x, y): (f64, f64)) -> gpui::Point<Pixels> {
        point(px((self.x0 + x * self.f) as f32), px((self.cy + y * self.f) as f32))
    }
}

fn stroke_polyline(window: &mut Window, map: Mapping, pts: &[(f64, f64)], color: Hsla) {
    if pts.len() < 2 {
        return;
    }
    let mut builder = PathBuilder::stroke(px((STROKE * map.f) as f32));
    builder.move_to(map.point(pts[0]));
    for p in &pts[1..] {
        builder.line_to(map.point(*p));
    }
    if let Ok(path) = builder.build() {
        window.paint_path(path, color);
    }
    // Round caps.
    for p in [pts[0], pts[pts.len() - 1]] {
        dot(window, map, p, STROKE / 2.0, color);
    }
}

fn dot(window: &mut Window, map: Mapping, centre: (f64, f64), r: f64, color: Hsla) {
    let c = map.point(centre);
    let r = px((r * map.f) as f32);
    let bounds = Bounds::new(point(c.x - r, c.y - r), size(r * 2.0, r * 2.0));
    window.paint_quad(fill(bounds, color).corner_radii(r));
}

fn paint_frame(map: Mapping, frame: WavyFrame, wavy: bool, window: &mut Window, cx: &mut App) {
    let tokens = cx.tokens();
    let wave_color = hsla(tokens.md_primary);
    let track_color = hsla(tokens.md_active_indicator);
    let phase = phase_at(motion::clock_seconds(), motion::is_reduced());
    let amp = if wavy { AMPLITUDE } else { 0.0 };
    for (from, to) in &frame.tracks {
        stroke_polyline(window, map, &[(*from, 0.0), (*to, 0.0)], track_color);
    }
    if let Some((from, to)) = frame.wave {
        stroke_polyline(window, map, &wave_points(from, to, 0.0, phase, amp), wave_color);
    }
    if let Some(x) = frame.stop_dot {
        dot(window, map, (x, 0.0), STROKE / 2.0, wave_color);
    }
    motion::request_frame(window);
}

impl Render for WavyProgressBar {
    fn render(&mut self, _window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        let indeterminate = self.target < 0.0;
        if !indeterminate {
            self.shown = ease_shown(self.shown, self.target);
        }
        let shown = self.shown;
        let wavy = is_wavy_enabled();
        div().w_full().min_w(u(48.0)).h(u(HEIGHT as f32)).child(
            canvas(
                move |_, _, _| {},
                move |bounds, _, window, cx| {
                    let f = f64::from(f32::from(window.rem_size())) / f64::from(crate::scale::REM_BASE);
                    let f = if f > 0.0 { f } else { 1.0 };
                    let map = Mapping {
                        x0: f64::from(f32::from(bounds.origin.x)),
                        cy: f64::from(f32::from(bounds.origin.y))
                            + f64::from(f32::from(bounds.size.height)) / 2.0,
                        f,
                    };
                    // Lay out in unscaled units so stroke, amplitude and wavelength scale too.
                    let w = f64::from(f32::from(bounds.size.width)) / f;
                    let frame = if indeterminate {
                        indeterminate_frame(motion::clock_seconds(), 0.0, w)
                    } else {
                        determinate_frame(shown, 0.0, w)
                    };
                    paint_frame(map, frame, wavy, window, cx);
                },
            )
            .size_full(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Port of `ProgressVisualsTest.waveStartsFlatStaysInBoundsAndEndsAtTheEnd`.
    #[test]
    fn wave_starts_flat_stays_in_bounds_and_ends_at_the_end() {
        let wave = wave_points(10.0, 210.0, 50.0, 0.7, AMPLITUDE);
        assert!((wave[0].0 - 10.0).abs() < 1e-9);
        assert!(
            (wave[0].1 - 50.0).abs() < 1e-9,
            "the wave ramps in from the centre line"
        );
        assert!((wave.last().unwrap().0 - 210.0).abs() < 1e-9);
        for (_, y) in &wave[1..] {
            assert!(
                (y - 50.0).abs() <= AMPLITUDE + 1e-9,
                "wave stays within its amplitude"
            );
        }
        // Odd lengths still end exactly at the end.
        let odd = wave_points(0.0, 101.0, 0.0, 0.0, AMPLITUDE);
        assert!((odd.last().unwrap().0 - 101.0).abs() < 1e-9);
    }

    /// Port of `ProgressVisualsTest.zeroAmplitudeIsFlat`.
    #[test]
    fn zero_amplitude_is_flat() {
        for (_, y) in wave_points(0.0, 100.0, 20.0, 1.3, 0.0) {
            assert!((y - 20.0).abs() < 1e-9);
        }
    }

    #[test]
    fn height_and_phase() {
        assert_eq!(HEIGHT, 12.0);
        assert_eq!(phase_at(0.6, true), 0.0);
        assert!((phase_at(0.6, false) - std::f64::consts::PI).abs() < 1e-9);
    }

    #[test]
    fn shown_eases_and_snaps() {
        let mut shown = 0.0;
        for _ in 0..200 {
            shown = ease_shown(shown, 0.5);
        }
        assert_eq!(shown, 0.5);
        assert!((ease_shown(0.0, 1.0) - 0.2).abs() < 1e-12);
        assert_eq!(ease_shown(0.0, 2.0), 0.2, "targets above 1 are capped");
    }

    #[test]
    fn determinate_layout() {
        let empty = determinate_frame(0.0, 0.0, 200.0);
        assert!(empty.wave.is_none());
        assert_eq!(empty.tracks, vec![(2.0, 198.0)]);
        assert_eq!(empty.stop_dot, Some(198.0));

        let half = determinate_frame(0.5, 0.0, 200.0);
        let (from, to) = half.wave.unwrap();
        assert_eq!(from, 2.0);
        assert_eq!(to, 2.0 + 196.0 * 0.5);
        assert_eq!(half.tracks, vec![(to + GAP + STROKE, 198.0)]);

        let full = determinate_frame(1.0, 0.0, 200.0);
        assert!(full.tracks.is_empty());
        assert!(full.stop_dot.is_none());
    }

    #[test]
    fn indeterminate_layout_sweeps() {
        let at_start = indeterminate_frame(0.0, 0.0, 200.0);
        assert!(
            at_start.wave.is_none(),
            "segment starts fully off the left edge"
        );
        let mid = indeterminate_frame(0.9, 0.0, 200.0);
        let (a, b) = mid.wave.unwrap();
        assert!(a > 0.0 && b <= 200.0 && b > a);
        assert!(mid.stop_dot.is_none());
        for (from, to) in mid.tracks {
            assert!(to > from);
        }
    }
}

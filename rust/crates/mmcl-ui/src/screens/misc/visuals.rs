// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The progress screens' backdrop and decorations (gui-shell spec 8.1 and 8.3): the glow, the
//! `BlockyHorizon` skyline, the hopping voxel cubes and the floating progress card.
//!
//! Everything is drawn with `canvas` elements that ask for animation frames only while painted
//! (the animation gate), and stand still under reduce motion.

use gpui::{
    AnyElement, App, Bounds, Div, Hsla, IntoElement, ParentElement, PathBuilder, Pixels, Styled,
    Window, canvas, div, linear_color_stop, linear_gradient, point, px,
};
use mmcl_ui_kit::motion;
use mmcl_ui_kit::theme::{ThemeExt as _, hsla};

use crate::scale::u;
use crate::shell::placeholder::{CUBE, DIRT, FACES, GRASS, STONE};

/// The window's layout scale factor (1.0 at 100 %), from the rem size.
fn factor(window: &Window) -> f32 {
    let f = f32::from(window.rem_size()) / mmcl_ui_kit::scale::REM_BASE;
    if f > 0.0 { f } else { 1.0 }
}

// ---------------------------------------------------------------------------------------------
// BlockyHorizon
// ---------------------------------------------------------------------------------------------

/// Width and height of one horizon block (px at 100 %).
pub const BLOCK: f32 = 24.0;
/// The horizon's height: six blocks.
pub const HORIZON_HEIGHT: f32 = BLOCK * 6.0;

/// `BlockyHorizon.columnHeight`: how many blocks tall column `column` of `layer` is (1..=max).
/// A sum of slow waves, so neighbouring columns form hills; fixed, so it never jumps on resize.
pub fn column_height(column: i32, layer: i32, max: i32) -> i32 {
    let x = f64::from(column) + f64::from(layer) * 37.0;
    let v = 0.55 * (x * 0.31).sin() + 0.30 * (x * 0.67 + 1.3).sin() + 0.15 * (x * 1.9 + 0.4).sin();
    1 + ((v + 1.0) / 2.0 * f64::from(max - 1)).round() as i32
}

/// The stepped outline of one layer (layout px, origin top-left of the horizon box `h` tall).
pub fn skyline(columns: i32, layer: i32, max: i32, h: f32, offset: f32) -> Vec<(f32, f32)> {
    let mut pts = vec![(-offset, h)];
    for c in 0..columns {
        let top = h - column_height(c, layer, max) as f32 * BLOCK;
        let x = c as f32 * BLOCK - offset;
        pts.push((x, top));
        pts.push((x + BLOCK, top));
    }
    pts.push((columns as f32 * BLOCK - offset, h));
    pts
}

fn paint_polygon(window: &mut Window, origin: (f32, f32), f: f32, pts: &[(f32, f32)], c: Hsla) {
    let points: Vec<_> = pts
        .iter()
        .map(|(x, y)| point(px(origin.0 + x * f), px(origin.1 + y * f)))
        .collect();
    let mut b = PathBuilder::fill();
    b.add_polygon(&points, true);
    if let Ok(path) = b.build() {
        window.paint_path(path, c);
    }
}

fn paint_horizon(bounds: Bounds<Pixels>, window: &mut Window, cx: &mut App) {
    let t = cx.tokens();
    let f = factor(window);
    let origin = (f32::from(bounds.origin.x), f32::from(bounds.origin.y));
    let w = f32::from(bounds.size.width) / f;
    let h = f32::from(bounds.size.height) / f;
    if w <= 0.0 || h <= 0.0 {
        return;
    }
    let columns = (w / BLOCK).ceil() as i32 + 1;
    let half = BLOCK / 2.0;
    paint_polygon(
        window,
        origin,
        f,
        &skyline(columns, 0, 6, h, 0.0),
        hsla(t.md_surface_container_low),
    );
    paint_polygon(
        window,
        origin,
        f,
        &skyline(columns, 1, 3, h, half),
        hsla(t.md_surface_container),
    );
    // Seams: the block grid inside the front layer.
    let seam = hsla(t.md_surface_container_low);
    let mut builder = PathBuilder::stroke(px(f.max(1.0)));
    let mut any = false;
    for c in 0..columns {
        let height = column_height(c, 1, 3);
        let x = c as f32 * BLOCK - half;
        let top = h - height as f32 * BLOCK;
        builder.move_to(point(px(origin.0 + (x + 0.5) * f), px(origin.1 + top * f)));
        builder.line_to(point(px(origin.0 + (x + 0.5) * f), px(origin.1 + h * f)));
        for b in 1..height {
            let y = top + b as f32 * BLOCK + 0.5;
            builder.move_to(point(px(origin.0 + x * f), px(origin.1 + y * f)));
            builder.line_to(point(px(origin.0 + (x + BLOCK) * f), px(origin.1 + y * f)));
        }
        any = true;
    }
    if any && let Ok(path) = builder.build() {
        window.paint_path(path, seam);
    }
    // Grass caps on the front layer.
    let grass = hsla(t.md_active_indicator);
    let cap = BLOCK / 6.0;
    for c in 0..columns {
        let top = h - column_height(c, 1, 3) as f32 * BLOCK;
        let x = c as f32 * BLOCK - half;
        paint_polygon(
            window,
            origin,
            f,
            &[
                (x, top),
                (x + BLOCK, top),
                (x + BLOCK, top + cap),
                (x, top + cap),
            ],
            grass,
        );
    }
}

/// The horizon strip (mouse-transparent; place it at the bottom of the screen).
pub fn blocky_horizon() -> impl IntoElement {
    div()
        .absolute()
        .left_0()
        .right_0()
        .bottom_0()
        .h(u(HORIZON_HEIGHT))
        .child(canvas(|_, _, _| {}, |b, _, w, cx| paint_horizon(b, w, cx)).size_full())
}

/// The glow behind the card (`progressGlow`): the hero stops washed in from the top and the
/// bottom. GPUI has linear gradients only, so the two radial washes become two linear ones.
pub fn progress_glow(cx: &App) -> impl IntoElement {
    let t = cx.tokens();
    let clear = |c: mmcl_theme::Rgba| {
        let mut h = hsla(c);
        h.a = 0.0;
        h
    };
    div()
        .absolute()
        .size_full()
        .child(div().absolute().size_full().bg(linear_gradient(
            180.0,
            linear_color_stop(hsla(t.color_hero_stop_1), 0.0),
            linear_color_stop(clear(t.color_hero_stop_1), 0.6),
        )))
        .child(div().absolute().size_full().bg(linear_gradient(
            0.0,
            linear_color_stop(hsla(t.color_hero_stop_2), 0.0),
            linear_color_stop(clear(t.color_hero_stop_2), 0.55),
        )))
}

// ---------------------------------------------------------------------------------------------
// Voxel hop
// ---------------------------------------------------------------------------------------------

/// One hop cycle (ms).
pub const HOP_CYCLE_MS: f32 = 1000.0;
/// Start delays of the three cubes (ms).
pub const HOP_DELAYS_MS: [f32; 3] = [0.0, 150.0, 300.0];
/// How high a cube hops (px at 100 %).
pub const HOP_HEIGHT: f32 = 14.0;
/// Gap between the cubes.
pub const VOXEL_SPACING: f32 = 14.0;
/// Shadow ellipse radii.
pub const SHADOW_RX: f32 = 15.0;
/// Shadow ellipse vertical radius.
pub const SHADOW_RY: f32 = 3.0;
/// Gap between a cube and its shadow.
pub const SHADOW_GAP: f32 = 6.0;

/// One frame of a cube's hop.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct HopFrame {
    /// Vertical offset (negative = up).
    pub translate_y: f32,
    /// Horizontal squash.
    pub scale_x: f32,
    /// Vertical squash.
    pub scale_y: f32,
    /// Shadow width factor.
    pub shadow_scale: f32,
    /// Shadow opacity.
    pub shadow_opacity: f32,
}

impl HopFrame {
    /// The resting frame (reduce motion).
    pub const REST: HopFrame = HopFrame {
        translate_y: 0.0,
        scale_x: 1.0,
        scale_y: 1.0,
        shadow_scale: 1.0,
        shadow_opacity: 1.0,
    };
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// The hop at `ms` into the cycle (already shifted by the cube's delay), per the spec keyframes:
/// t=0 squashed (scaleY 0.9, scaleX 1.06), t=90 unsquashed (rise), t=380 top (-14, rise),
/// t=660 landed (fall), rest until 1000. The shadow shrinks and fades towards the top.
pub fn hop_at(ms: f32) -> HopFrame {
    let rise = motion::cubic_bezier(0.2, 0.0, 0.0, 1.0);
    let fall = motion::cubic_bezier(0.4, 0.0, 1.0, 1.0);
    let ms = ms.rem_euclid(HOP_CYCLE_MS);
    let (scale_x, scale_y) = if ms < 90.0 {
        let e = rise(ms / 90.0);
        (lerp(1.06, 1.0, e), lerp(0.9, 1.0, e))
    } else if ms >= 660.0 {
        // Lands squashed for the next cycle: ease back into the t=0 pose during the rest.
        let e = ((ms - 660.0) / (HOP_CYCLE_MS - 660.0)).clamp(0.0, 1.0);
        (lerp(1.0, 1.06, e), lerp(1.0, 0.9, e))
    } else {
        (1.0, 1.0)
    };
    let (translate_y, k) = if ms < 380.0 {
        let e = rise(ms / 380.0);
        (lerp(0.0, -HOP_HEIGHT, e), e)
    } else if ms < 660.0 {
        let e = fall((ms - 380.0) / 280.0);
        (lerp(-HOP_HEIGHT, 0.0, e), 1.0 - e)
    } else {
        (0.0, 0.0)
    };
    HopFrame {
        translate_y,
        scale_x,
        scale_y,
        shadow_scale: lerp(1.0, 0.6, k),
        shadow_opacity: lerp(1.0, 0.45, k),
    }
}

fn paint_voxel(index: usize, bounds: Bounds<Pixels>, window: &mut Window, cx: &mut App) {
    let f = factor(window);
    let reduced = motion::is_reduced();
    let frame = if reduced {
        HopFrame::REST
    } else {
        hop_at((motion::clock_seconds() * 1000.0) as f32 - HOP_DELAYS_MS[index])
    };
    let colors = [DIRT, GRASS, STONE][index % 3];
    let ox = f32::from(bounds.origin.x);
    let oy = f32::from(bounds.origin.y);
    let w = f32::from(bounds.size.width) / f;
    // Layout (layout px): cube area of CUBE + HOP_HEIGHT on top, then the gap and shadow.
    let cube_bottom = HOP_HEIGHT + CUBE;
    let cx0 = w / 2.0;
    // The cube, scaled around its bottom centre and lifted.
    for (face, rgb) in FACES.iter().zip(colors) {
        let pts: Vec<_> = face
            .iter()
            .map(|(x, y)| {
                let lx = cx0 + (x - CUBE / 2.0) * frame.scale_x;
                let ly = cube_bottom + (y - CUBE) * frame.scale_y + frame.translate_y;
                point(px(ox + lx * f), px(oy + ly * f))
            })
            .collect();
        let mut b = PathBuilder::fill();
        b.add_polygon(&pts, true);
        if let Ok(path) = b.build() {
            window.paint_path(path, gpui::rgb(rgb));
        }
    }
    // The shadow ellipse.
    let mut shadow = hsla(cx.tokens().color_shadow_soft);
    shadow.a *= frame.shadow_opacity;
    let cy = cube_bottom + SHADOW_GAP + SHADOW_RY;
    let rx = SHADOW_RX * frame.shadow_scale;
    let pts: Vec<_> = (0..32)
        .map(|i| {
            let a = i as f32 / 32.0 * std::f32::consts::TAU;
            point(
                px(ox + (cx0 + a.cos() * rx) * f),
                px(oy + (cy + a.sin() * SHADOW_RY) * f),
            )
        })
        .collect();
    let mut b = PathBuilder::fill();
    b.add_polygon(&pts, true);
    if let Ok(path) = b.build() {
        window.paint_path(path, shadow);
    }
    if !reduced {
        motion::request_frame(window);
    }
}

/// The three hopping cubes with their shadows.
pub fn voxel_row() -> impl IntoElement {
    let height = HOP_HEIGHT + CUBE + SHADOW_GAP + SHADOW_RY * 2.0;
    div()
        .flex()
        .justify_center()
        .gap(u(VOXEL_SPACING))
        .pb(u(8.0))
        .children((0..3).map(|i| {
            div().w(u(SHADOW_RX * 2.0 + 6.0)).h(u(height)).child(
                canvas(
                    |_, _, _| {},
                    move |b: Bounds<Pixels>, _: (), w: &mut Window, cx: &mut App| {
                        paint_voxel(i, b, w, cx)
                    },
                )
                .size_full(),
            )
        }))
}

/// The progress card (`progressCard`): the surface every progress screen centres.
pub fn progress_card(cx: &App) -> Div {
    let t = cx.tokens();
    div()
        .flex()
        .flex_col()
        .rounded(u(mmcl_theme::shape::EXTRA_LARGE))
        .bg(hsla(t.md_surface_container_high))
        .shadow(vec![gpui::BoxShadow {
            color: {
                let mut s = hsla(t.color_shadow);
                s.a *= 0.6;
                s
            },
            offset: point(px(0.0), px(12.0)),
            blur_radius: px(32.0),
            spread_radius: px(0.0),
            inset: false,
        }])
}

/// The progress screens' root: glow, horizon, then `card` centred.
pub fn progress_backdrop(card: AnyElement, cx: &App) -> impl IntoElement {
    div()
        .relative()
        .size_full()
        .overflow_hidden()
        .child(progress_glow(cx))
        .child(blocky_horizon())
        .child(
            div()
                .absolute()
                .size_full()
                .flex()
                .items_center()
                .justify_center()
                .p(u(24.0))
                .child(card),
        )
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Port of `ProgressVisualsTest`: horizon columns stay within height and repeat exactly.
    #[test]
    fn horizon_columns_stay_within_height_and_repeat() {
        for layer in 0..2 {
            for max in [3, 6] {
                for c in -50..500 {
                    let h = column_height(c, layer, max);
                    assert!((1..=max).contains(&h), "{c} {layer} {max} -> {h}");
                    assert_eq!(h, column_height(c, layer, max));
                }
            }
        }
        // The layers differ (different waves), so the hills do not line up.
        let same = (0..40).all(|c| column_height(c, 0, 6) == column_height(c, 1, 6));
        assert!(!same);
        // A wider skyline starts with the same columns (no jump on resize).
        let narrow = skyline(10, 0, 6, 144.0, 0.0);
        let wide = skyline(30, 0, 6, 144.0, 0.0);
        assert_eq!(&narrow[..21], &wide[..21]);
    }

    #[test]
    fn hop_keyframes() {
        let start = hop_at(0.0);
        assert!((start.scale_y - 0.9).abs() < 1e-4 && (start.scale_x - 1.06).abs() < 1e-4);
        assert_eq!(start.translate_y, 0.0);
        let unsquashed = hop_at(90.0);
        assert!((unsquashed.scale_x - 1.0).abs() < 1e-4);
        let top = hop_at(380.0);
        assert!((top.translate_y + HOP_HEIGHT).abs() < 1e-3, "{top:?}");
        assert!((top.shadow_scale - 0.6).abs() < 1e-3 && (top.shadow_opacity - 0.45).abs() < 1e-3);
        let landed = hop_at(660.0);
        assert!(landed.translate_y.abs() < 1e-3);
        assert!((landed.shadow_opacity - 1.0).abs() < 1e-3);
        for ms in 0..2000 {
            let f = hop_at(ms as f32);
            assert!((-HOP_HEIGHT - 1e-3..=1e-3).contains(&f.translate_y));
            assert!(f.shadow_opacity >= 0.45 - 1e-3 && f.shadow_opacity <= 1.0 + 1e-3);
        }
        assert_eq!(hop_at(1250.0), hop_at(250.0), "the cycle repeats");
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The cold-start placeholder (`MCLauncherGuiWindow` placeholder scene): an empty window at
//! first paint, then three bouncing voxel cubes (dirt, grass, stone) after one idle frame if no
//! real screen has arrived yet (guarded bug 29: decorations never cost first paint).

use std::time::Duration;

use gpui::{
    Animation, AnimationExt as _, Bounds, Context, Hsla, IntoElement, ParentElement, PathBuilder,
    Pixels, Render, Styled, Window, canvas, div, point, px,
};
use mmcl_ui_kit::motion;

/// Cube edge (px at 100 %).
pub const CUBE: f32 = 36.0;
/// Gap between cubes.
pub const SPACING: f32 = 10.0;
/// Bounce height.
pub const BOUNCE: f32 = 8.0;
/// One half-cycle (up or down).
pub const HALF_CYCLE: Duration = Duration::from_millis(500);
/// Start delays of the three cubes.
pub const DELAYS_MS: [u64; 3] = [0, 150, 300];

/// Face colours (top, left, right): brand constants, not theme tokens.
pub const DIRT: [u32; 3] = [0x8B6A3F, 0x5C4322, 0x3F2E14];
/// Grass.
pub const GRASS: [u32; 3] = [0x6FCF3D, 0x3C8527, 0x2A6B1E];
/// Stone.
pub const STONE: [u32; 3] = [0xA8AEB8, 0x6B7280, 0x4B5563];

/// The three faces of a 36x36 isometric cube (SVG coordinates): top, left, right.
pub const FACES: [[(f32, f32); 4]; 3] = [
    [(18.0, 0.0), (36.0, 9.0), (18.0, 18.0), (0.0, 9.0)],
    [(0.0, 9.0), (18.0, 18.0), (18.0, 36.0), (0.0, 27.0)],
    [(36.0, 9.0), (18.0, 18.0), (18.0, 36.0), (36.0, 27.0)],
];

/// The bounce offset (px, negative = up) of a cube at linear cycle position `t` (0..1 over a
/// full up-and-down cycle), shifted by its delay. Ease-both like JavaFX `EASE_BOTH`.
pub fn bounce_offset(t: f32, delay_ms: u64) -> f32 {
    let cycle = 2.0 * HALF_CYCLE.as_millis() as f32;
    let shifted = (t - delay_ms as f32 / cycle).rem_euclid(1.0);
    // Triangle 0 -> 1 -> 0 over the cycle, eased.
    let tri = if shifted < 0.5 {
        shifted * 2.0
    } else {
        2.0 - shifted * 2.0
    };
    let eased = tri * tri * (3.0 - 2.0 * tri);
    -BOUNCE * eased
}

fn color(rgb: u32) -> Hsla {
    gpui::rgb(rgb).into()
}

fn cube(colors: [u32; 3], scale: f32) -> impl IntoElement {
    canvas(
        |_, _, _| {},
        move |bounds: Bounds<Pixels>, _, window, _| {
            for (face, rgb) in FACES.iter().zip(colors) {
                let mut path = PathBuilder::fill();
                for (i, (x, y)) in face.iter().enumerate() {
                    let p = point(
                        bounds.origin.x + px(x * scale),
                        bounds.origin.y + px(y * scale),
                    );
                    if i == 0 {
                        path.move_to(p);
                    } else {
                        path.line_to(p);
                    }
                }
                path.close();
                if let Ok(path) = path.build() {
                    window.paint_path(path, color(rgb));
                }
            }
        },
    )
    .size(px(CUBE * scale))
}

/// The placeholder view.
pub struct Placeholder {
    decorated: bool,
}

impl Placeholder {
    /// An empty placeholder; the cubes attach after the first frame.
    pub fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let this = cx.entity().downgrade();
        window.on_next_frame(move |_, cx| {
            let _ = this.update(cx, |p, cx| {
                p.decorated = true;
                cx.notify();
            });
        });
        Placeholder { decorated: false }
    }

    /// Whether the cubes are showing.
    pub fn is_decorated(&self) -> bool {
        self.decorated
    }
}

impl Render for Placeholder {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let root = div().size_full().flex().items_center().justify_center();
        if !self.decorated {
            return root;
        }
        let scale = mmcl_ui_kit::scale::percent(cx) as f32 / 100.0;
        let reduced = motion::is_reduced();
        root.child(
            div().flex().gap(px(SPACING * scale)).children(
                [DIRT, GRASS, STONE]
                    .into_iter()
                    .zip(DELAYS_MS)
                    .enumerate()
                    .map(|(i, (colors, delay))| {
                        let el = div().relative().child(cube(colors, scale));
                        if reduced {
                            el.into_any_element()
                        } else {
                            el.with_animation(
                                ("mmcl-voxel", i),
                                Animation::new(HALF_CYCLE * 2).repeat(),
                                move |el, t| el.top(px(bounce_offset(t, delay) * scale)),
                            )
                            .into_any_element()
                        }
                    }),
            ),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bounce_stays_in_range_and_is_staggered() {
        for i in 0..=100 {
            let t = i as f32 / 100.0;
            for d in DELAYS_MS {
                let y = bounce_offset(t, d);
                assert!((-BOUNCE..=0.0).contains(&y), "{t} {d} -> {y}");
            }
        }
        assert_eq!(bounce_offset(0.0, 0), 0.0);
        assert!(
            (bounce_offset(0.5, 0) + BOUNCE).abs() < 1e-5,
            "top of the bounce"
        );
        assert_ne!(bounce_offset(0.25, 0), bounce_offset(0.25, 150));
    }

    #[test]
    fn faces_fit_the_cube() {
        for face in FACES {
            for (x, y) in face {
                assert!((0.0..=CUBE).contains(&x) && (0.0..=CUBE).contains(&y));
            }
        }
    }
}

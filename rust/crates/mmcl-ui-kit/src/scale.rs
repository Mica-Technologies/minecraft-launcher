// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Interface scale (`UiScale`) and smooth scrolling (`SmoothScroll`).
//!
//! GPUI has no layout transform, but every `rems()` length follows `window.set_rem_size(..)`.
//! Author layout sizes with [`u`] (px against a 16 px base) and call [`apply_to_window`] on every
//! window (main, help, add-account, running games, wizard) when it opens and when the scale
//! changes. Popups GPUI draws live in the same window and scale with it; native prompts do not.

use gpui::{App, Global, Pixels, Point, Rems, ScrollHandle, Window, point, px, rems};

/// The offered scale percents.
pub const OFFERED: [i32; 5] = [50, 75, 100, 125, 150];
/// The default percent.
pub const DEFAULT_PERCENT: i32 = 100;
/// The config key.
pub const CONFIG_KEY: &str = "uiScalePercent";
/// The rem base (px at 100 %).
pub const REM_BASE: f32 = 16.0;

/// Snaps a percent to the nearest offered value. Starts from 100 and walks the list in order,
/// replacing the best only on a strictly smaller distance, so a tie keeps 100 when it is one
/// side of the tie, else the lower value (62 -> 50, 63 -> 75, 88 -> 100, 112 -> 100, 113 -> 125).
pub fn nearest(percent: i32) -> i32 {
    let mut best = DEFAULT_PERCENT;
    let mut best_distance = (percent - best).abs();
    for candidate in OFFERED {
        let distance = (percent - candidate).abs();
        if distance < best_distance {
            best = candidate;
            best_distance = distance;
        }
    }
    best
}

/// A layout length in px at 100 %, as rems so it follows the scale.
pub fn u(px_at_100: f32) -> Rems {
    rems(px_at_100 / REM_BASE)
}

/// A length in px at 100 %, scaled to the current interface scale (for APIs that take `Pixels`,
/// such as gpui-component's dialog width).
pub fn scaled_px(cx: &App, px_at_100: f32) -> Pixels {
    px(px_at_100 * percent(cx) as f32 / 100.0)
}

/// The rem size for a percent.
pub fn rem_size(percent: i32) -> Pixels {
    px(REM_BASE * nearest(percent) as f32 / 100.0)
}

/// The current interface scale.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UiScale(pub i32);

impl Default for UiScale {
    fn default() -> Self {
        UiScale(DEFAULT_PERCENT)
    }
}

impl Global for UiScale {}

impl UiScale {
    /// The scale factor (1.0 at 100 %).
    pub fn factor(self) -> f32 {
        self.0 as f32 / 100.0
    }
}

/// The current scale percent.
pub fn percent(cx: &App) -> i32 {
    cx.try_global::<UiScale>().copied().unwrap_or_default().0
}

/// Sets the scale (snapped) and refreshes every window.
///
/// gpui-component's `Root` sets every frame's rem size to its theme `font_size`, so the scale is
/// carried there too: windows under a `Root` follow it automatically; other windows call
/// [`apply_to_window`] from their root's render.
pub fn set_percent(cx: &mut App, percent: i32) {
    cx.set_global(UiScale(nearest(percent)));
    sync_component_rem(cx);
    cx.refresh_windows();
}

/// Pushes the current rem size into gpui-component's theme (`font_size` doubles as the rem).
pub fn sync_component_rem(cx: &mut App) {
    if !cx.has_global::<gpui_component::theme::Theme>() {
        return;
    }
    let size = rem_size(percent(cx));
    if gpui_component::theme::Theme::global(cx).font_size != size {
        gpui_component::theme::Theme::update(cx, |t| t.font_size = size);
    }
}

/// Applies the current scale to a window's rem size (call from the window root's render).
pub fn apply_to_window(window: &mut Window, cx: &App) {
    let size = rem_size(percent(cx));
    if window.rem_size() != size {
        window.set_rem_size(size);
    }
}

/// Eased wheel scrolling: wheel deltas accumulate into a clamped target, and each frame moves the
/// shown offset a fraction of the remaining distance until it is within epsilon.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct SmoothScroll {
    /// Where the scroll is heading (px, 0 = top, positive = further down).
    pub target: f32,
    /// Where it is now.
    pub current: f32,
}

impl SmoothScroll {
    /// Fraction of the remaining distance covered per frame.
    pub const FACTOR: f32 = 0.25;
    /// Stop when this close (px).
    pub const EPSILON: f32 = 0.5;

    /// Adds a wheel delta (positive scrolls down), clamped to `0..=max`.
    pub fn wheel(&mut self, delta: f32, max: f32) {
        self.target = (self.target + delta).clamp(0.0, max.max(0.0));
    }

    /// Advances one frame; returns whether it is still moving.
    pub fn step(&mut self) -> bool {
        let remaining = self.target - self.current;
        if remaining.abs() <= Self::EPSILON {
            self.current = self.target;
            return false;
        }
        self.current += remaining * Self::FACTOR;
        true
    }

    /// Follows a scroll handle: syncs from it, steps, writes the offset back, and asks for another
    /// frame while moving. Call from the scrolled element's paint.
    pub fn drive(&mut self, handle: &ScrollHandle, window: &Window) {
        let max = f32::from(handle.max_offset().y);
        self.target = self.target.clamp(0.0, max.max(0.0));
        let moving = self.step();
        let offset: Point<Pixels> = point(handle.offset().x, px(-self.current));
        handle.set_offset(offset);
        if moving {
            crate::motion::request_frame(window);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Port of `UiScaleTest.snapsToTheNearestOfferedScale`.
    #[test]
    fn snaps_to_the_nearest_offered_scale() {
        for (input, expected) in [
            (62, 50),
            (63, 75),
            (87, 75),
            (88, 100),
            (112, 100),
            (113, 125),
            (100, 100),
            (150, 150),
            (10, 50),
            (400, 150),
            (-5, 50),
        ] {
            assert_eq!(nearest(input), expected, "{input}");
        }
    }

    #[test]
    fn rems_follow_the_scale() {
        assert_eq!(rem_size(100), px(16.0));
        assert_eq!(rem_size(150), px(24.0));
        assert_eq!(rem_size(87), px(12.0));
        assert_eq!(u(32.0), rems(2.0));
        assert_eq!(UiScale(125).factor(), 1.25);
    }

    #[test]
    fn smooth_scroll_approaches_and_stops() {
        let mut s = SmoothScroll::default();
        s.wheel(100.0, 1000.0);
        s.wheel(-500.0, 1000.0);
        assert_eq!(s.target, 0.0, "clamped at the top");
        s.wheel(300.0, 200.0);
        assert_eq!(s.target, 200.0, "clamped at the bottom");
        let mut frames = 0;
        while s.step() {
            frames += 1;
            assert!(s.current <= 200.0);
            assert!(frames < 100);
        }
        assert_eq!(s.current, 200.0);
        assert!(frames > 3, "eased over several frames");
    }
}

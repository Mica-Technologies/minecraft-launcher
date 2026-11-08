// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Window placement (`WindowPlacement`, `ScaledMinSize`) and the saved-bounds rules of
//! `MCLauncherGuiWindow`, as pure functions over plain rectangles so they are unit-tested
//! without a window.
//!
//! All values are logical pixels (GPUI `Pixels`, JavaFX layout units), x/y the top-left corner.

use mmcl_engine::WindowBounds;

/// A rectangle in logical screen pixels.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Rect {
    /// Left edge.
    pub x: f64,
    /// Top edge.
    pub y: f64,
    /// Width.
    pub w: f64,
    /// Height.
    pub h: f64,
}

impl Rect {
    /// A rectangle.
    pub const fn new(x: f64, y: f64, w: f64, h: f64) -> Rect {
        Rect { x, y, w, h }
    }

    /// Right edge.
    pub fn max_x(&self) -> f64 {
        self.x + self.w
    }

    /// Bottom edge.
    pub fn max_y(&self) -> f64 {
        self.y + self.h
    }

    /// The centre point.
    pub fn center(&self) -> (f64, f64) {
        (self.x + self.w / 2.0, self.y + self.h / 2.0)
    }

    /// Whether the point lies inside (left/top inclusive, right/bottom exclusive).
    pub fn contains(&self, px: f64, py: f64) -> bool {
        px >= self.x && px < self.max_x() && py >= self.y && py < self.max_y()
    }

    /// Whether the two rectangles overlap by a positive area.
    pub fn intersects(&self, other: &Rect) -> bool {
        self.x < other.max_x()
            && other.x < self.max_x()
            && self.y < other.max_y()
            && other.y < self.max_y()
    }

    /// A rectangle of size `w`x`h` centred in this one.
    pub fn centered(&self, w: f64, h: f64) -> Rect {
        Rect::new(
            self.x + (self.w - w) / 2.0,
            self.y + (self.h - h) / 2.0,
            w,
            h,
        )
    }
}

/// The height of the strip that has to stay visible for the window to be grabbable.
pub const TITLE_STRIP_HEIGHT: f64 = 40.0;
/// The minimum visible width of that strip.
pub const MIN_GRAB_WIDTH: f64 = 100.0;

/// Whether enough of the window's title bar is on some screen for the user to grab it: the top
/// 40 px strip lies vertically inside a screen and the middle half of it overlaps the screen by
/// at least 100 px (or the whole middle half when it is narrower).
pub fn title_bar_on_screen(b: Rect, screens: &[Rect]) -> bool {
    let strip = TITLE_STRIP_HEIGHT.min(b.h);
    let left = b.x + 0.25 * b.w;
    let right = b.x + 0.75 * b.w;
    let needed = MIN_GRAB_WIDTH.min(right - left);
    screens.iter().any(|s| {
        b.y >= s.y && b.y + strip <= s.max_y() && (right.min(s.max_x()) - left.max(s.x)) >= needed
    })
}

/// Moves (and if needed shrinks) `b` so it lies inside `s`.
pub fn clamp_into(b: Rect, s: Rect) -> Rect {
    let w = b.w.min(s.w);
    let h = b.h.min(s.h);
    let x = b.x.max(s.x).min(s.max_x() - w);
    let y = b.y.max(s.y).min(s.max_y() - h);
    Rect::new(x, y, w, h)
}

/// Room kept free around a minimum size on small displays.
pub const SCREEN_MARGIN: f64 = 24.0;

/// `ScaledMinSize.clamp`: the base minimum times the interface scale, capped to the display
/// minus a margin so a large scale on a small laptop never pushes the window's bottom (and its
/// buttons) off screen. An unknown (non-positive or NaN) display size leaves it uncapped.
pub fn clamp_min(base: f64, scale: f64, available: f64) -> f64 {
    let wanted = (base * scale).max(0.0);
    if available.is_nan() || available <= 0.0 {
        wanted
    } else {
        wanted.min((available - SCREEN_MARGIN).max(0.0))
    }
}

/// The main window's preferred size (layout px at 100 %).
pub const PREF_SIZE: (f64, f64) = (1000.0, 800.0);
/// The main window's base minimum size (layout px at 100 %).
pub const MIN_SIZE: (f64, f64) = (750.0, 600.0);

/// Why a saved placement was or was not used.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Restore {
    /// Use the saved bounds as they are.
    Saved { bounds: Rect, maximized: bool },
    /// The title bar would be off screen; moved onto the primary screen.
    MovedOnScreen { bounds: Rect, maximized: bool },
    /// Off screen and too small once moved: centre a default window instead.
    OffScreen,
    /// Nothing usable saved.
    None,
}

impl Restore {
    /// The bounds and maximized flag to open with, if any.
    pub fn placement(self) -> Option<(Rect, bool)> {
        match self {
            Restore::Saved { bounds, maximized } | Restore::MovedOnScreen { bounds, maximized } => {
                Some((bounds, maximized))
            }
            Restore::OffScreen | Restore::None => None,
        }
    }
}

/// `restoreSavedBounds`: usable only if all four numbers are set and at least the base minimum;
/// a title bar off every screen is clamped into the primary screen, and given up on when that
/// leaves it below the minimum.
pub fn restore(saved: &WindowBounds, screens: &[Rect], primary: Option<Rect>) -> Restore {
    if !saved.is_complete() || saved.width < MIN_SIZE.0 || saved.height < MIN_SIZE.1 {
        return Restore::None;
    }
    let bounds = Rect::new(saved.x, saved.y, saved.width, saved.height);
    if screens.is_empty() || title_bar_on_screen(bounds, screens) {
        return Restore::Saved {
            bounds,
            maximized: saved.maximized,
        };
    }
    let Some(primary) = primary.or_else(|| screens.first().copied()) else {
        return Restore::OffScreen;
    };
    let clamped = clamp_into(bounds, primary);
    if clamped.w < MIN_SIZE.0 || clamped.h < MIN_SIZE.1 {
        Restore::OffScreen
    } else {
        Restore::MovedOnScreen {
            bounds: clamped,
            maximized: saved.maximized,
        }
    }
}

/// The screen a window belongs to: the one under its centre, else any it overlaps, else the
/// primary (the Java `ScaledMinSize.apply` lookup).
pub fn screen_for(window: Rect, screens: &[Rect], primary: Option<Rect>) -> Option<Rect> {
    let (cx, cy) = window.center();
    screens
        .iter()
        .find(|s| s.contains(cx, cy))
        .or_else(|| screens.iter().find(|s| s.intersects(&window)))
        .copied()
        .or(primary)
        .or_else(|| screens.first().copied())
}

/// What a window state reports after a move or resize.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum BoundsState {
    /// A normal window at these bounds.
    Windowed(Rect),
    /// Maximized; the rectangle is the restore (un-maximized) size.
    Maximized(Rect),
    /// Full screen; the rectangle is the restore size.
    Fullscreen(Rect),
    /// Minimized (nothing to record).
    Minimized,
}

/// Tracks the last un-maximized geometry for persistence (`lastNormal*`, `normalBoundsSeen`).
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct BoundsTracker {
    last_normal: Option<Rect>,
    maximized: bool,
}

impl BoundsTracker {
    /// Seeds the tracker from a restored placement.
    pub fn seeded(bounds: Rect, maximized: bool) -> Self {
        BoundsTracker {
            last_normal: Some(bounds),
            maximized,
        }
    }

    /// Records a state change. Returns whether anything changed (so a debounce restarts).
    pub fn observe(&mut self, state: BoundsState) -> bool {
        let before = *self;
        match state {
            BoundsState::Windowed(r) => {
                if r.w > 0.0 && r.h > 0.0 {
                    self.last_normal = Some(r);
                }
                self.maximized = false;
            }
            BoundsState::Maximized(r) | BoundsState::Fullscreen(r) => {
                // GPUI reports the restore size alongside: that is the "last normal" geometry.
                if r.w > 0.0 && r.h > 0.0 {
                    self.last_normal = Some(r);
                }
                self.maximized = matches!(state, BoundsState::Maximized(_));
            }
            BoundsState::Minimized => {}
        }
        *self != before
    }

    /// The values to save: the last normal geometry plus the maximized flag (never the
    /// maximized geometry), or `None` before any normal bounds were seen.
    pub fn to_saved(&self) -> Option<WindowBounds> {
        let r = self.last_normal?;
        Some(WindowBounds {
            x: r.x,
            y: r.y,
            width: r.w,
            height: r.h,
            maximized: self.maximized,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SCREEN: Rect = Rect::new(0.0, 0.0, 1920.0, 1040.0);

    // ---- WindowPlacementTest ----

    #[test]
    fn well_inside_is_kept() {
        assert!(title_bar_on_screen(
            Rect::new(100.0, 100.0, 1000.0, 800.0),
            &[SCREEN]
        ));
    }

    #[test]
    fn one_pixel_overlap_is_rejected() {
        // Only 1 px of the middle half overlaps the screen horizontally.
        let b = Rect::new(-749.0, 100.0, 1000.0, 800.0);
        // middle half = [-499, 1]; overlap = 1 px < 100.
        assert!(!title_bar_on_screen(b, &[SCREEN]));
    }

    #[test]
    fn title_bar_above_the_visible_area_is_rejected() {
        assert!(!title_bar_on_screen(
            Rect::new(100.0, -20.0, 1000.0, 800.0),
            &[SCREEN]
        ));
    }

    #[test]
    fn behind_the_taskbar_is_rejected() {
        // Visible area ends at 1040; the strip starting at 1020 would reach 1060.
        assert!(!title_bar_on_screen(
            Rect::new(100.0, 1020.0, 1000.0, 800.0),
            &[SCREEN]
        ));
    }

    #[test]
    fn a_disconnected_screen_is_rejected() {
        // Saved on a second monitor to the right that is no longer attached.
        assert!(!title_bar_on_screen(
            Rect::new(2200.0, 100.0, 1000.0, 800.0),
            &[SCREEN]
        ));
        let second = Rect::new(1920.0, 0.0, 1920.0, 1080.0);
        assert!(title_bar_on_screen(
            Rect::new(2200.0, 100.0, 1000.0, 800.0),
            &[SCREEN, second]
        ));
    }

    #[test]
    fn hanging_partly_off_the_side_is_kept_while_grabbable() {
        // middle half = [1550, 2050]; overlap with the screen = 370 >= 100.
        assert!(title_bar_on_screen(
            Rect::new(1300.0, 100.0, 1000.0, 800.0),
            &[SCREEN]
        ));
    }

    #[test]
    fn clamp_keeps_size_and_moves_inside() {
        let c = clamp_into(Rect::new(1500.0, 900.0, 1000.0, 800.0), SCREEN);
        assert_eq!(c, Rect::new(920.0, 240.0, 1000.0, 800.0));
    }

    #[test]
    fn clamp_shrinks_oversize() {
        let c = clamp_into(Rect::new(-50.0, -50.0, 3000.0, 2000.0), SCREEN);
        assert_eq!(c, SCREEN);
    }

    // ---- ScaledMinSizeTest ----

    #[test]
    fn min_size_scales_when_it_fits() {
        assert_eq!(clamp_min(760.0, 1.25, 1440.0), 950.0);
        assert_eq!(clamp_min(750.0, 1.0, 1920.0), 750.0);
    }

    #[test]
    fn min_size_caps_to_the_display_minus_margin() {
        // The login screen's 760 px at 125 % on a 768 px laptop.
        assert_eq!(clamp_min(760.0, 1.25, 768.0), 744.0);
    }

    #[test]
    fn unknown_or_tiny_displays_degrade_safely() {
        assert_eq!(clamp_min(750.0, 1.5, 0.0), 1125.0);
        assert_eq!(clamp_min(750.0, 1.5, -1.0), 1125.0);
        assert_eq!(clamp_min(750.0, 1.5, f64::NAN), 1125.0);
        assert_eq!(clamp_min(750.0, 1.0, 10.0), 0.0);
        assert_eq!(clamp_min(-5.0, 1.0, 1000.0), 0.0);
    }

    // ---- restore / tracker ----

    fn saved(x: f64, y: f64, w: f64, h: f64, maximized: bool) -> WindowBounds {
        WindowBounds {
            x,
            y,
            width: w,
            height: h,
            maximized,
        }
    }

    #[test]
    fn restore_rules() {
        assert_eq!(
            restore(&saved(f64::NAN, 0.0, 1000.0, 800.0, false), &[SCREEN], None),
            Restore::None
        );
        assert_eq!(
            restore(&saved(0.0, 0.0, 700.0, 800.0, false), &[SCREEN], None),
            Restore::None,
            "below the base minimum"
        );
        assert_eq!(
            restore(&saved(10.0, 10.0, 1000.0, 800.0, true), &[SCREEN], None),
            Restore::Saved {
                bounds: Rect::new(10.0, 10.0, 1000.0, 800.0),
                maximized: true
            }
        );
        assert_eq!(
            restore(
                &saved(4000.0, 10.0, 1000.0, 800.0, false),
                &[SCREEN],
                Some(SCREEN)
            ),
            Restore::MovedOnScreen {
                bounds: Rect::new(920.0, 10.0, 1000.0, 800.0),
                maximized: false
            }
        );
        let tiny = Rect::new(0.0, 0.0, 800.0, 500.0);
        assert_eq!(
            restore(
                &saved(4000.0, 10.0, 1000.0, 800.0, false),
                &[tiny],
                Some(tiny)
            ),
            Restore::OffScreen
        );
        assert!(Restore::OffScreen.placement().is_none());
    }

    #[test]
    fn screen_lookup_prefers_the_centre() {
        let right = Rect::new(1920.0, 0.0, 2560.0, 1440.0);
        let w = Rect::new(1800.0, 100.0, 1000.0, 800.0);
        assert_eq!(screen_for(w, &[SCREEN, right], Some(SCREEN)), Some(right));
        let off = Rect::new(9000.0, 9000.0, 10.0, 10.0);
        assert_eq!(
            screen_for(off, &[SCREEN, right], Some(SCREEN)),
            Some(SCREEN)
        );
        assert_eq!(screen_for(off, &[], None), None);
    }

    #[test]
    fn tracker_saves_the_unmaximized_geometry() {
        let mut t = BoundsTracker::default();
        assert_eq!(t.to_saved(), None, "nothing before normal bounds are seen");
        assert!(t.observe(BoundsState::Windowed(Rect::new(1.0, 2.0, 1000.0, 800.0))));
        assert!(t.observe(BoundsState::Maximized(Rect::new(1.0, 2.0, 1000.0, 800.0))));
        let s = t.to_saved().unwrap();
        assert_eq!(
            (s.x, s.y, s.width, s.height, s.maximized),
            (1.0, 2.0, 1000.0, 800.0, true)
        );
        assert!(!t.observe(BoundsState::Minimized));
        t.observe(BoundsState::Windowed(Rect::new(1.0, 2.0, 0.0, 0.0)));
        assert_eq!(
            t.to_saved().unwrap().width,
            1000.0,
            "a zero size is not recorded"
        );
        let seeded = BoundsTracker::seeded(Rect::new(5.0, 5.0, 900.0, 700.0), false);
        assert_eq!(seeded.to_saved().unwrap().width, 900.0);
    }
}

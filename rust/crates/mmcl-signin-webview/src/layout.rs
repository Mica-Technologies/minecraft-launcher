// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Window geometry for the sign-in window. Pure arithmetic in physical pixels.

/// Preferred inner size in logical pixels. The Java sign-in frame prefers a 520 px wide web
/// view; the extra height fits Microsoft's account picker without scrolling.
pub const PREFERRED_SIZE: (f64, f64) = (520.0, 720.0);

/// Minimum inner size in logical pixels (the Java web view's minimum width is 440).
pub const MIN_SIZE: (f64, f64) = (440.0, 520.0);

/// The largest share of the monitor's work area the window may cover.
const MAX_MONITOR_SHARE: f64 = 0.9;

/// Shrinks `wanted` (physical) so it covers at most 90% of the monitor, keeping it at least
/// `min` (physical) unless the monitor itself is smaller.
pub fn fit_size(wanted: (u32, u32), min: (u32, u32), monitor: (u32, u32)) -> (u32, u32) {
    let fit = |want: u32, min: u32, mon: u32| -> u32 {
        if mon == 0 {
            return want;
        }
        let cap = (f64::from(mon) * MAX_MONITOR_SHARE).floor() as u32;
        want.min(cap).max(min.min(mon))
    };
    (
        fit(wanted.0, min.0, monitor.0),
        fit(wanted.1, min.1, monitor.1),
    )
}

/// The top-left position that centres a window of `window` size on a monitor at `origin` with
/// `monitor` size. A window larger than the monitor is pinned to its top-left corner so the
/// title bar stays reachable.
pub fn centered(origin: (i32, i32), monitor: (u32, u32), window: (u32, u32)) -> (i32, i32) {
    let axis = |o: i32, m: u32, w: u32| -> i32 {
        let free = i64::from(m) - i64::from(w);
        let offset = if free > 0 { free / 2 } else { 0 };
        (i64::from(o) + offset).clamp(i64::from(i32::MIN), i64::from(i32::MAX)) as i32
    };
    (
        axis(origin.0, monitor.0, window.0),
        axis(origin.1, monitor.1, window.1),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_window_is_centred_on_its_monitor() {
        assert_eq!(centered((0, 0), (1920, 1080), (520, 720)), (700, 180));
        // A second monitor to the left of the primary one.
        assert_eq!(centered((-2560, 0), (2560, 1440), (1040, 1440)), (-1800, 0));
    }

    #[test]
    fn a_window_larger_than_the_monitor_keeps_its_title_bar_on_screen() {
        assert_eq!(centered((100, 50), (800, 600), (1000, 900)), (100, 50));
    }

    #[test]
    fn a_small_monitor_shrinks_the_window_but_not_below_the_minimum() {
        assert_eq!(fit_size((520, 720), (440, 520), (1920, 1080)), (520, 720));
        assert_eq!(fit_size((520, 720), (440, 520), (1280, 720)), (520, 648));
        assert_eq!(fit_size((520, 720), (440, 520), (1280, 500)), (520, 500));
        assert_eq!(fit_size((520, 720), (440, 520), (0, 0)), (520, 720));
    }

    #[test]
    fn the_steam_deck_screen_fits() {
        // 1280x800 at scale 1.
        let size = fit_size((520, 720), (440, 520), (1280, 800));
        assert_eq!(size, (520, 720));
        assert_eq!(centered((0, 0), (1280, 800), size), (380, 40));
    }
}

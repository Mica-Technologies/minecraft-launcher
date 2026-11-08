// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Multi-image cycling (issue #43): `ModpackImageCycleClock`.
//!
//! One clock per app ticks every card in lockstep. A screen holds a [`CycleSubscription`] while
//! it shows a card with more than one logo or background; the clock stops when the last one is
//! dropped (screen cleanup drops it, so a disposed screen never keeps the timer alive). Cards
//! pick `images[tick % n]`, so a rebind never needs an unsubscribe step.

use std::cell::Cell;
use std::rc::Rc;
use std::time::Duration;

use gpui::{App, Global, Task};

/// The interval of a config token (`5s` ... `7d`); `never`, unknown and `None` -> no cycling.
pub fn token_to_duration(token: Option<&str>) -> Option<Duration> {
    let secs = match token? {
        "5s" => 5,
        "15s" => 15,
        "30s" => 30,
        "1m" => 60,
        "5m" => 5 * 60,
        "15m" => 15 * 60,
        "30m" => 30 * 60,
        "1h" => 3600,
        "6h" => 6 * 3600,
        "12h" => 12 * 3600,
        "1d" => 24 * 3600,
        "7d" => 7 * 24 * 3600,
        _ => return None,
    };
    Some(Duration::from_secs(secs))
}

/// Whether a pack can cycle (`canCycle`).
pub fn can_cycle(logo_count: usize, has_custom_logo: bool, bg_count: usize, show_bg: bool) -> bool {
    (has_custom_logo && logo_count > 1) || (show_bg && bg_count > 1)
}

/// The clock.
#[derive(Default)]
pub struct CycleClock {
    tick: u64,
    interval: Option<Duration>,
    listeners: Rc<Cell<usize>>,
    running: Rc<Cell<bool>>,
    task: Option<Task<()>>,
}

impl Global for CycleClock {}

/// Keeps the clock running while alive.
pub struct CycleSubscription {
    listeners: Rc<Cell<usize>>,
}

impl Drop for CycleSubscription {
    fn drop(&mut self) {
        self.listeners.set(self.listeners.get().saturating_sub(1));
    }
}

/// The current tick.
pub fn tick(cx: &App) -> u64 {
    cx.try_global::<CycleClock>().map_or(0, |c| c.tick)
}

/// Sets the interval from the config token (`reconfigure`: stop and start).
pub fn configure(cx: &mut App, token: &str) {
    let interval = token_to_duration(Some(token));
    let clock = cx.default_global::<CycleClock>();
    if clock.interval == interval {
        return;
    }
    clock.interval = interval;
    clock.task = None;
    clock.running.set(false);
    ensure_running(cx);
}

/// Subscribes to ticks.
pub fn subscribe(cx: &mut App) -> CycleSubscription {
    let clock = cx.default_global::<CycleClock>();
    clock.listeners.set(clock.listeners.get() + 1);
    let sub = CycleSubscription {
        listeners: clock.listeners.clone(),
    };
    ensure_running(cx);
    sub
}

fn ensure_running(cx: &mut App) {
    let clock = cx.default_global::<CycleClock>();
    let Some(interval) = clock.interval else {
        return;
    };
    if clock.running.get() || clock.listeners.get() == 0 {
        return;
    }
    clock.running.set(true);
    let listeners = clock.listeners.clone();
    let running = clock.running.clone();
    let task = cx.spawn(async move |cx| {
        loop {
            cx.background_executor().timer(interval).await;
            if listeners.get() == 0 {
                running.set(false);
                break;
            }
            cx.update(|cx| {
                cx.default_global::<CycleClock>().tick += 1;
                cx.refresh_windows();
            });
        }
    });
    cx.default_global::<CycleClock>().task = Some(task);
}

/// Picks `items[tick % n]` (the first when empty-safe).
pub fn pick<T: Clone>(items: &[T], tick: u64) -> Option<T> {
    if items.is_empty() {
        None
    } else {
        Some(items[(tick % items.len() as u64) as usize].clone())
    }
}

/// A shuffled copy (once per bind) with a deterministic seed per pack.
pub fn shuffled<T: Clone>(items: &[T], seed: &str) -> Vec<T> {
    let mut v = items.to_vec();
    let mut state: u64 = seed.bytes().fold(0xcbf2_9ce4_8422_2325, |h, b| {
        (h ^ u64::from(b)).wrapping_mul(0x100_0000_01b3)
    });
    for i in (1..v.len()).rev() {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        let j = (state % (i as u64 + 1)) as usize;
        v.swap(i, j);
    }
    v
}

#[cfg(test)]
mod tests {
    use super::*;

    // Port of ModpackImageCycleClockTest.

    #[test]
    fn maps_every_seconds_and_minutes_token() {
        let s = Duration::from_secs;
        assert_eq!(token_to_duration(Some("5s")), Some(s(5)));
        assert_eq!(token_to_duration(Some("15s")), Some(s(15)));
        assert_eq!(token_to_duration(Some("30s")), Some(s(30)));
        assert_eq!(token_to_duration(Some("1m")), Some(s(60)));
        assert_eq!(token_to_duration(Some("5m")), Some(s(300)));
        assert_eq!(token_to_duration(Some("15m")), Some(s(900)));
        assert_eq!(token_to_duration(Some("30m")), Some(s(1800)));
    }

    #[test]
    fn maps_hours_and_long_intervals() {
        let h = |n: u64| Duration::from_secs(n * 3600);
        assert_eq!(token_to_duration(Some("1h")), Some(h(1)));
        assert_eq!(token_to_duration(Some("6h")), Some(h(6)));
        assert_eq!(token_to_duration(Some("12h")), Some(h(12)));
        assert_eq!(token_to_duration(Some("1d")), Some(h(24)));
        assert_eq!(token_to_duration(Some("7d")), Some(h(168)));
    }

    #[test]
    fn never_unknown_and_null_map_to_no_cycling() {
        assert_eq!(token_to_duration(Some("never")), None);
        assert_eq!(token_to_duration(Some("bogus")), None);
        assert_eq!(token_to_duration(None), None);
    }

    #[test]
    fn every_canonical_option_resolves() {
        for token in mmcl_engine::settings::IMAGE_CYCLE_INTERVALS {
            let d = token_to_duration(Some(token));
            if token == "never" {
                assert!(d.is_none());
            } else {
                assert!(d.is_some(), "{token}");
            }
        }
    }

    #[test]
    fn cycling_and_picking() {
        assert!(can_cycle(2, true, 0, false));
        assert!(!can_cycle(2, false, 1, true));
        assert!(can_cycle(1, true, 3, true));
        assert!(can_cycle(3, true, 3, false));
        assert_eq!(pick(&[1, 2, 3], 4), Some(2));
        assert_eq!(pick::<i32>(&[], 4), None);
        let s = shuffled(&[1, 2, 3, 4, 5], "pack");
        assert_eq!(s.len(), 5);
        assert_eq!(s, shuffled(&[1, 2, 3, 4, 5], "pack"));
    }

    #[gpui::test]
    fn the_clock_stops_without_listeners(cx: &mut gpui::TestAppContext) {
        cx.update(|cx| {
            configure(cx, "5s");
            let sub = subscribe(cx);
            assert!(cx.global::<CycleClock>().running.get());
            drop(sub);
            assert_eq!(cx.global::<CycleClock>().listeners.get(), 0);
        });
        cx.executor().advance_clock(Duration::from_secs(6));
        cx.run_until_parked();
        let sub = cx.update(|cx| {
            assert!(!cx.global::<CycleClock>().running.get());
            assert_eq!(tick(cx), 0);
            subscribe(cx)
        });
        cx.executor().advance_clock(Duration::from_secs(6));
        cx.run_until_parked();
        cx.update(|cx| assert_eq!(tick(cx), 1));
        drop(sub);
    }
}

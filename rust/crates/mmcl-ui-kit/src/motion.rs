// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Motion (`Motion`) and the animation gate (`AnimationGate`).
//!
//! Easing tokens and durations come from [`mmcl_theme::motion`]. Transitions:
//!
//! - [`MotionExt::enter`]: opacity 0 -> 1 over 300 ms, emphasized decelerate (dialogs, modals).
//! - [`MotionExt::exit`]: opacity -> 0 over 150 ms, emphasized accelerate; pair it with
//!   [`after_exit`] to run the "done" step once it has played.
//! - [`MotionExt::fade_through`]: opacity 0 -> 1 and a rise of 8 px over 220 ms (screen swaps,
//!   Settings categories, Running Games tabs).
//!
//! GPUI has no element scale transform, so the Java 0.96 -> 1 scale of `enter`/`exit` is dropped
//! (opacity only). Key the animation id on a per-show counter to replay it on every show.
//!
//! Reduce motion ([`set_reduce_motion`], Settings -> Appearance) snaps transitions to rest and
//! stops ambient animation (wavy wave, loading-shape morph). [`apply_reduce_motion`] also tells
//! GPUI (`App::set_reduce_motion`), whose `with_animation` then jumps straight to the end.
//!
//! **Animation gate.** An element that is not rendered is not painted, so the Java weak-listener
//! machinery becomes one rule: animated widgets call [`request_frame`] only from their own
//! `paint`, never from a free-running task. A screen swapped out, a collapsed section or a hidden
//! window therefore schedules no frames.

use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant};

use gpui::{AnimationExt as _, AnyElement, App, ElementId, IntoElement, Styled, Task, Window, px};
use mmcl_theme::motion::{self as tokens, CubicBezier};

pub use mmcl_theme::motion::{
    EMPHASIZED_ACCELERATE, EMPHASIZED_DECELERATE, FADE_THROUGH, LONG, MEDIUM, SHORT, STANDARD,
};

static REDUCED: AtomicBool = AtomicBool::new(false);
static FRAMES_REQUESTED: AtomicU64 = AtomicU64::new(0);

/// Turns reduce motion on or off (process-wide).
pub fn set_reduce_motion(reduced: bool) {
    REDUCED.store(reduced, Ordering::Relaxed);
}

/// Whether reduce motion is on.
pub fn is_reduced() -> bool {
    REDUCED.load(Ordering::Relaxed)
}

/// [`set_reduce_motion`] plus GPUI's own flag; refreshes every window.
pub fn apply_reduce_motion(cx: &mut App, reduced: bool) {
    set_reduce_motion(reduced);
    cx.set_reduce_motion(reduced);
    cx.refresh_windows();
}

/// A CSS `cubic-bezier(x1, y1, x2, y2)` easing as a GPUI easing function.
pub fn cubic_bezier(x1: f32, y1: f32, x2: f32, y2: f32) -> impl Fn(f32) -> f32 + Clone + 'static {
    let curve = CubicBezier::new(x1, y1, x2, y2);
    move |t| curve.eval(t)
}

/// A token curve as a GPUI easing function.
pub fn easing(curve: CubicBezier) -> impl Fn(f32) -> f32 + Clone + 'static {
    move |t| curve.eval(t)
}

/// Seconds since the process-wide animation clock started.
///
/// Every ambient animation (wave phase, loading shape) reads this shared clock, not a per-widget
/// one, so a rebuilt widget keeps moving smoothly instead of restarting.
pub fn clock_seconds() -> f64 {
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_secs_f64()
}

/// Asks for another frame. Call this **only** from the paint of a visible animated element (the
/// animation gate); never from a timer.
pub fn request_frame(window: &Window) {
    FRAMES_REQUESTED.fetch_add(1, Ordering::Relaxed);
    window.request_animation_frame();
}

/// How many frames [`request_frame`] has asked for since start (diagnostics and tests).
pub fn frames_requested() -> u64 {
    FRAMES_REQUESTED.load(Ordering::Relaxed)
}

/// How long to wait before running an exit's "done" step (zero under reduce motion).
pub fn exit_delay() -> Duration {
    if is_reduced() {
        Duration::ZERO
    } else {
        tokens::EXIT.duration
    }
}

/// Runs `done` after the exit transition has played (immediately under reduce motion).
/// Drop the returned task to cancel.
pub fn after_exit(cx: &mut App, done: impl FnOnce(&mut App) + 'static) -> Task<()> {
    let delay = exit_delay();
    if delay.is_zero() {
        done(cx);
        return Task::ready(());
    }
    let timer = cx.background_executor().timer(delay);
    cx.spawn(async move |cx| {
        timer.await;
        cx.update(done);
    })
}

/// A transition's animation, or `None` under reduce motion (snap to rest).
fn animation(transition: tokens::Transition) -> Option<gpui::Animation> {
    if is_reduced() {
        return None;
    }
    Some(gpui::Animation::new(transition.duration).with_easing(easing(transition.easing)))
}

/// Opacity of an exiting element at linear progress `t` (eased).
pub fn exit_opacity(t: f32) -> f32 {
    1.0 - tokens::EXIT.easing.eval(t)
}

/// The rise (px) of a fade-through at eased progress `t`.
pub fn fade_through_offset(t: f32) -> f32 {
    tokens::FADE_THROUGH_RISE * (1.0 - t)
}

/// The launcher's transitions on any styled element.
pub trait MotionExt: Styled + IntoElement + Sized + 'static {
    /// Fade in over [`MEDIUM`], emphasized decelerate.
    fn enter(self, id: impl Into<ElementId>) -> AnyElement {
        match animation(tokens::ENTER) {
            Some(a) => self
                .with_animation(id, a, |el, t| el.opacity(t))
                .into_any_element(),
            None => self.into_any_element(),
        }
    }

    /// Fade out over [`SHORT`], emphasized accelerate. The element ends invisible; run the
    /// follow-up with [`after_exit`].
    fn exit(self, id: impl Into<ElementId>) -> AnyElement {
        match animation(tokens::Transition {
            duration: tokens::EXIT.duration,
            // The curve is applied in `exit_opacity`, so the animation itself runs linear.
            easing: CubicBezier::new(0.0, 0.0, 1.0, 1.0),
        }) {
            Some(a) => self
                .with_animation(id, a, |el, t| el.opacity(exit_opacity(t)))
                .into_any_element(),
            None => self.opacity(0.0).into_any_element(),
        }
    }

    /// Fade in and rise 8 px over 220 ms, emphasized decelerate.
    fn fade_through(self, id: impl Into<ElementId>) -> AnyElement {
        match animation(tokens::FADE_THROUGH_TRANSITION) {
            Some(a) => self
                .with_animation(id, a, |el, t| {
                    el.opacity(t).relative().top(px(fade_through_offset(t)))
                })
                .into_any_element(),
            None => self.into_any_element(),
        }
    }
}

impl<E: Styled + IntoElement + 'static> MotionExt for E {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cubic_bezier_matches_the_tokens() {
        let f = cubic_bezier(0.05, 0.7, 0.1, 1.0);
        for i in 0..=10 {
            let x = i as f32 / 10.0;
            assert_eq!(f(x), EMPHASIZED_DECELERATE.eval(x));
        }
        assert_eq!(f(0.0), 0.0);
        assert_eq!(f(1.0), 1.0);
    }

    #[test]
    fn exit_and_fade_through_end_at_rest() {
        assert_eq!(exit_opacity(0.0), 1.0);
        assert_eq!(exit_opacity(1.0), 0.0);
        assert_eq!(fade_through_offset(0.0), 8.0);
        assert_eq!(fade_through_offset(1.0), 0.0);
    }

    #[test]
    fn reduce_motion_snaps() {
        set_reduce_motion(true);
        assert!(is_reduced());
        assert_eq!(exit_delay(), Duration::ZERO);
        assert!(animation(tokens::ENTER).is_none());
        set_reduce_motion(false);
        assert_eq!(exit_delay(), Duration::from_millis(150));
        assert!(animation(tokens::ENTER).is_some());
    }

    #[test]
    fn clock_is_monotonic() {
        let a = clock_seconds();
        let b = clock_seconds();
        assert!(b >= a);
    }
}

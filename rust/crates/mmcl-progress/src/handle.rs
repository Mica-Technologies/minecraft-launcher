// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! A progress sink bound to one step, for one parallel branch of the launch.

use crate::bridge::ErrorHandler;
use crate::clock::Throttle;
use crate::{
    Clock, DownloadMeter, LaunchProgressTracker, MonotonicClock, ProgressSink, SUBMIT_THROTTLE,
    StepId,
};
use parking_lot::Mutex;
use std::sync::Arc;

#[derive(Debug, Default)]
struct HandleState {
    accumulator: f64,
    throttle: Throttle,
}

/// Legacy sink bound to one tracker step (Java `StepProgressHandle`).
///
/// Section progress accumulates (cap 100) and is pushed to the step as `acc / 100` together
/// with the detail text, at most every [`SUBMIT_THROTTLE`] unless it reached 100.
/// `set_curr_text` and non-empty `end_section` text become the sub-text; `start_section`
/// resets the step to 0 and shows a non-empty title as the sub-text. Operations on a step the
/// tracker does not have are ignored.
pub struct StepHandle {
    tracker: Arc<LaunchProgressTracker>,
    step: StepId,
    state: Mutex<HandleState>,
    meter: Option<Arc<dyn DownloadMeter>>,
    clock: Arc<dyn Clock>,
    on_error: Option<ErrorHandler>,
}

impl std::fmt::Debug for StepHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("StepHandle")
            .field("step", &self.step)
            .field("state", &*self.state.lock())
            .finish_non_exhaustive()
    }
}

impl StepHandle {
    /// A handle for `step` on `tracker`, with no download meter and the real clock.
    pub fn new(tracker: Arc<LaunchProgressTracker>, step: StepId) -> Self {
        Self {
            tracker,
            step,
            state: Mutex::new(HandleState::default()),
            meter: None,
            clock: Arc::new(MonotonicClock::new()),
            on_error: None,
        }
    }

    /// Attach the download meter returned by [`ProgressSink::download_tracker`].
    pub fn with_download_meter(mut self, meter: Arc<dyn DownloadMeter>) -> Self {
        self.meter = Some(meter);
        self
    }

    /// Replace the throttle clock (tests).
    pub fn with_clock(mut self, clock: Arc<dyn Clock>) -> Self {
        self.clock = clock;
        self
    }

    /// Route [`ProgressSink::on_error`] to `handler` (the GUI shows a warning toast).
    pub fn with_error_handler(mut self, handler: ErrorHandler) -> Self {
        self.on_error = Some(handler);
        self
    }

    /// The step this handle reports to.
    pub fn step_id(&self) -> StepId {
        self.step
    }

    /// The tracker this handle reports to.
    pub fn tracker(&self) -> &Arc<LaunchProgressTracker> {
        &self.tracker
    }

    /// Reset the accumulator and mark the step running.
    pub fn mark_running(&self) {
        self.state.lock().accumulator = 0.0;
        let _ = self.tracker.mark_running(self.step);
    }

    /// Mark the step done.
    pub fn mark_done(&self) {
        let _ = self.tracker.mark_done(self.step);
    }

    /// Mark the step failed.
    pub fn mark_failed(&self, msg: &str) {
        let _ = self.tracker.mark_failed(self.step, msg);
    }

    /// Mark the step skipped; a non-empty `reason` becomes the sub-text first.
    pub fn mark_skipped(&self, reason: &str) {
        if !reason.is_empty() {
            let _ = self.tracker.set_sub_text(self.step, reason);
        }
        let _ = self.tracker.mark_skipped(self.step);
    }
}

impl ProgressSink for StepHandle {
    fn submit_progress(&self, detail: &str, section_delta: f64) {
        let now = self.clock.now();
        let push = {
            let mut s = self.state.lock();
            s.accumulator = (s.accumulator + section_delta).min(100.0);
            let full = s.accumulator >= 100.0;
            s.throttle
                .should_fire(now, SUBMIT_THROTTLE, full)
                .then_some(s.accumulator)
        };
        if let Some(acc) = push {
            let _ = self.tracker.submit_progress(self.step, acc / 100.0, detail);
        }
    }

    fn set_curr_text(&self, detail: &str) {
        let _ = self.tracker.set_sub_text(self.step, detail);
    }

    fn start_section(&self, title: &str, _size: f64) {
        self.state.lock().accumulator = 0.0;
        let _ = self.tracker.set_progress(self.step, 0.0);
        if !title.is_empty() {
            let _ = self.tracker.set_sub_text(self.step, title);
        }
    }

    fn end_section(&self, text: &str) {
        if !text.is_empty() {
            let _ = self.tracker.set_sub_text(self.step, text);
        }
    }

    fn download_tracker(&self) -> Option<Arc<dyn DownloadMeter>> {
        self.meter.clone()
    }

    fn on_error(&self, msg: &str, cause: Option<&(dyn std::error::Error + Send + Sync)>) {
        if let Some(h) = &self.on_error {
            h(msg, cause);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::StepState;
    use crate::clock::test_clock::ManualClock;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn setup() -> (
        Arc<LaunchProgressTracker>,
        Arc<ManualClock>,
        StepHandle,
        Arc<AtomicUsize>,
    ) {
        let tracker = LaunchProgressTracker::for_steps(&[StepId::McLibsAssets, StepId::JreInstall]);
        let clock = Arc::new(ManualClock::default());
        let count = Arc::new(AtomicUsize::new(0));
        {
            let count = Arc::clone(&count);
            tracker.add_listener(Arc::new(move |_| {
                count.fetch_add(1, Ordering::SeqCst);
            }));
        }
        let h = StepHandle::new(Arc::clone(&tracker), StepId::JreInstall).with_clock(clock.clone());
        (tracker, clock, h, count)
    }

    #[test]
    fn submit_accumulates_and_throttles() {
        let (tracker, clock, h, count) = setup();
        h.mark_running();
        assert_eq!(count.load(Ordering::SeqCst), 1);
        h.submit_progress("a", 25.0);
        assert_eq!(count.load(Ordering::SeqCst), 2);
        clock.advance_ms(5);
        h.submit_progress("b", 25.0); // throttled
        assert_eq!(count.load(Ordering::SeqCst), 2);
        let s = tracker.step(StepId::JreInstall).unwrap();
        assert_eq!((s.progress, s.sub_text.as_str()), (0.25, "a"));
        clock.advance_ms(50);
        h.submit_progress("c", 25.0);
        let s = tracker.step(StepId::JreInstall).unwrap();
        assert_eq!((s.progress, s.sub_text.as_str()), (0.75, "c"));
        clock.advance_ms(1);
        h.submit_progress("d", 500.0); // reaches 100: bypasses throttle, capped
        let s = tracker.step(StepId::JreInstall).unwrap();
        assert_eq!((s.progress, s.sub_text.as_str()), (1.0, "d"));
        assert_eq!(s.state, StepState::Running);
    }

    #[test]
    fn sections_and_text_map_to_the_step() {
        let (tracker, _clock, h, _count) = setup();
        h.mark_running();
        h.submit_progress("x", 60.0);
        h.start_section("Downloading Java", 50.0);
        let s = tracker.step(StepId::JreInstall).unwrap();
        assert_eq!((s.progress, s.sub_text.as_str()), (0.0, "Downloading Java"));
        h.start_section("", 50.0);
        assert_eq!(
            tracker.step(StepId::JreInstall).unwrap().sub_text,
            "Downloading Java"
        );
        h.set_curr_text("Extracting");
        assert_eq!(
            tracker.step(StepId::JreInstall).unwrap().sub_text,
            "Extracting"
        );
        h.end_section("");
        assert_eq!(
            tracker.step(StepId::JreInstall).unwrap().sub_text,
            "Extracting"
        );
        h.end_section("Ready");
        assert_eq!(tracker.step(StepId::JreInstall).unwrap().sub_text, "Ready");
        h.mark_done();
        assert_eq!(
            tracker.step(StepId::JreInstall).unwrap().state,
            StepState::Done
        );
        // The other step is untouched.
        assert_eq!(
            tracker.step(StepId::McLibsAssets).unwrap().state,
            StepState::Pending
        );
    }

    #[test]
    fn skipped_reason_becomes_sub_text_and_failed_records_error() {
        let (tracker, _clock, h, _count) = setup();
        h.mark_skipped("Already installed");
        let s = tracker.step(StepId::JreInstall).unwrap();
        assert_eq!(
            (s.state, s.sub_text.as_str()),
            (StepState::Skipped, "Already installed")
        );
        h.mark_failed("disk full");
        let s = tracker.step(StepId::JreInstall).unwrap();
        assert_eq!(s.error.as_deref(), Some("disk full"));
    }

    #[test]
    fn handle_for_an_absent_step_is_inert() {
        let tracker = LaunchProgressTracker::for_steps(&[StepId::JreInstall]);
        let h = StepHandle::new(Arc::clone(&tracker), StepId::ForgeProcessors);
        h.mark_running();
        h.submit_progress("x", 100.0);
        h.mark_skipped("n/a");
        assert_eq!(tracker.steps().len(), 1);
        assert_eq!(
            tracker.step(StepId::JreInstall).unwrap().state,
            StepState::Pending
        );
    }

    #[test]
    fn mark_running_resets_the_accumulator() {
        let (tracker, clock, h, _count) = setup();
        h.mark_running();
        h.submit_progress("a", 80.0);
        h.mark_running();
        clock.advance_ms(100);
        h.submit_progress("b", 10.0);
        assert!((tracker.step(StepId::JreInstall).unwrap().progress - 0.1).abs() < 1e-9);
    }
}

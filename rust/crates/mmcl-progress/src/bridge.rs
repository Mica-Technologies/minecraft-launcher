// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Legacy sink to tracker bridge for the serial part of the launch pipeline.

use crate::{DownloadMeter, LaunchProgressTracker, ProgressSink, StepHandle, StepId};
use parking_lot::Mutex;
use std::sync::Arc;

/// Receives [`ProgressSink::on_error`] (the GUI shows a warning toast).
pub type ErrorHandler =
    Arc<dyn Fn(&str, Option<&(dyn std::error::Error + Send + Sync)>) + Send + Sync>;

/// Builds one download meter per sink (the bridge and every handle own their own, as each
/// Java provider owned its own `DownloadTracker`).
pub type MeterFactory = Arc<dyn Fn() -> Arc<dyn DownloadMeter> + Send + Sync>;

#[derive(Debug, Default)]
struct BridgeState {
    active: Option<StepId>,
    accumulator: f64,
}

/// Legacy sink that drives a [`LaunchProgressTracker`] (Java `LaunchTrackerProgressBridge`).
///
/// `enter_step` makes a step active, resets the section accumulator and marks it running;
/// `complete_step` / `fail_step` clear the active step only when it matches, then mark it.
/// `submit_progress` (not throttled) adds to the accumulator (cap 100) and pushes `acc / 100`
/// plus the detail to the active step; without an active step it is a no-op, as are
/// `set_curr_text`, `start_section` and `end_section`. Parallel branches use
/// [`TrackerBridge::handle_for`] instead.
pub struct TrackerBridge {
    tracker: Arc<LaunchProgressTracker>,
    state: Mutex<BridgeState>,
    meter: Option<Arc<dyn DownloadMeter>>,
    meter_factory: Option<MeterFactory>,
    on_error: Option<ErrorHandler>,
}

impl std::fmt::Debug for TrackerBridge {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TrackerBridge")
            .field("state", &*self.state.lock())
            .finish_non_exhaustive()
    }
}

impl TrackerBridge {
    /// A bridge onto `tracker` with no download meter and no error handler.
    pub fn new(tracker: Arc<LaunchProgressTracker>) -> Self {
        Self {
            tracker,
            state: Mutex::new(BridgeState::default()),
            meter: None,
            meter_factory: None,
            on_error: None,
        }
    }

    /// Give the bridge (now) and every later [`Self::handle_for`] handle a meter from
    /// `factory`.
    pub fn with_download_meters(mut self, factory: MeterFactory) -> Self {
        self.meter = Some(factory());
        self.meter_factory = Some(factory);
        self
    }

    /// Route `on_error` from the bridge and its handles to `handler`.
    pub fn with_error_handler(mut self, handler: ErrorHandler) -> Self {
        self.on_error = Some(handler);
        self
    }

    /// The tracker this bridge drives.
    pub fn tracker(&self) -> &Arc<LaunchProgressTracker> {
        &self.tracker
    }

    /// The step currently receiving section progress, if any.
    pub fn active_step(&self) -> Option<StepId> {
        self.state.lock().active
    }

    /// A sink bound to `id`, for one parallel branch.
    pub fn handle_for(&self, id: StepId) -> StepHandle {
        let mut h = StepHandle::new(Arc::clone(&self.tracker), id);
        if let Some(f) = &self.meter_factory {
            h = h.with_download_meter(f());
        }
        if let Some(e) = &self.on_error {
            h = h.with_error_handler(Arc::clone(e));
        }
        h
    }

    fn finish(&self, id: StepId) {
        let mut s = self.state.lock();
        if s.active == Some(id) {
            s.active = None;
            s.accumulator = 0.0;
        }
    }
}

impl ProgressSink for TrackerBridge {
    fn submit_progress(&self, detail: &str, section_delta: f64) {
        let push = {
            let mut s = self.state.lock();
            match s.active {
                None => None,
                Some(step) => {
                    s.accumulator = (s.accumulator + section_delta).min(100.0);
                    Some((step, s.accumulator))
                }
            }
        };
        if let Some((step, acc)) = push {
            let _ = self.tracker.submit_progress(step, acc / 100.0, detail);
        }
    }

    fn set_curr_text(&self, detail: &str) {
        if let Some(step) = self.active_step() {
            let _ = self.tracker.set_sub_text(step, detail);
        }
    }

    fn start_section(&self, title: &str, _size: f64) {
        let step = {
            let mut s = self.state.lock();
            s.accumulator = 0.0;
            s.active
        };
        if let Some(step) = step {
            let _ = self.tracker.set_progress(step, 0.0);
            if !title.is_empty() {
                let _ = self.tracker.set_sub_text(step, title);
            }
        }
    }

    fn end_section(&self, text: &str) {
        if let Some(step) = self.active_step()
            && !text.is_empty()
        {
            let _ = self.tracker.set_sub_text(step, text);
        }
    }

    fn download_tracker(&self) -> Option<Arc<dyn DownloadMeter>> {
        self.meter.clone()
    }

    fn enter_step(&self, id: StepId) {
        {
            let mut s = self.state.lock();
            s.active = Some(id);
            s.accumulator = 0.0;
        }
        let _ = self.tracker.mark_running(id);
    }

    fn complete_step(&self, id: StepId) {
        self.finish(id);
        let _ = self.tracker.mark_done(id);
    }

    fn fail_step(&self, id: StepId, msg: &str) {
        self.finish(id);
        let _ = self.tracker.mark_failed(id, msg);
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
    use crate::section::tests::StubMeter;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn bridge() -> (Arc<LaunchProgressTracker>, TrackerBridge) {
        let t = LaunchProgressTracker::for_steps(&StepId::launch_steps(false, true));
        (Arc::clone(&t), TrackerBridge::new(t))
    }

    #[test]
    fn serial_pipeline_drives_the_active_step() {
        let (t, b) = bridge();
        b.submit_progress("ignored", 50.0); // no active step: no-op
        assert!(
            t.steps()
                .iter()
                .all(|s| s.state == StepState::Pending && s.progress == 0.0)
        );
        b.enter_step(StepId::ModpackContent);
        assert_eq!(b.active_step(), Some(StepId::ModpackContent));
        b.submit_progress("mod a", 30.0);
        b.submit_progress("mod b", 30.0); // no throttle in the bridge
        let s = t.step(StepId::ModpackContent).unwrap();
        assert_eq!(s.state, StepState::Running);
        assert!((s.progress - 0.6).abs() < 1e-9);
        assert_eq!(s.sub_text, "mod b");
        b.submit_progress("over", 90.0);
        assert_eq!(t.step(StepId::ModpackContent).unwrap().progress, 1.0);
        b.start_section("Configs", 20.0);
        let s = t.step(StepId::ModpackContent).unwrap();
        assert_eq!(
            (s.progress, s.sub_text.as_str(), s.state),
            (0.0, "Configs", StepState::Running)
        );
        b.set_curr_text("config 1");
        assert_eq!(t.step(StepId::ModpackContent).unwrap().sub_text, "config 1");
        b.end_section("configs done");
        assert_eq!(
            t.step(StepId::ModpackContent).unwrap().sub_text,
            "configs done"
        );
        b.complete_step(StepId::ModpackContent);
        assert_eq!(b.active_step(), None);
        assert_eq!(
            t.step(StepId::ModpackContent).unwrap().state,
            StepState::Done
        );
        b.set_curr_text("nowhere");
        assert_eq!(
            t.step(StepId::ModpackContent).unwrap().sub_text,
            "configs done"
        );
    }

    #[test]
    fn finishing_another_step_keeps_the_active_one() {
        let (t, b) = bridge();
        b.enter_step(StepId::ForgeLibs);
        b.submit_progress("lib", 40.0);
        b.complete_step(StepId::JreInstall); // a parallel branch's step
        assert_eq!(b.active_step(), Some(StepId::ForgeLibs));
        assert_eq!(t.step(StepId::JreInstall).unwrap().state, StepState::Done);
        b.submit_progress("lib2", 10.0);
        assert!((t.step(StepId::ForgeLibs).unwrap().progress - 0.5).abs() < 1e-9);
        b.fail_step(StepId::ForgeLibs, "404");
        assert_eq!(b.active_step(), None);
        let s = t.step(StepId::ForgeLibs).unwrap();
        assert_eq!(
            (s.state, s.error.as_deref()),
            (StepState::Failed, Some("404"))
        );
    }

    #[test]
    fn enter_step_resets_the_accumulator() {
        let (t, b) = bridge();
        b.enter_step(StepId::McLibsAssets);
        b.submit_progress("a", 70.0);
        b.enter_step(StepId::SecurityScan);
        b.submit_progress("b", 10.0);
        assert!((t.step(StepId::SecurityScan).unwrap().progress - 0.1).abs() < 1e-9);
    }

    #[test]
    fn handles_get_their_own_meter_and_the_error_handler() {
        let made = Arc::new(AtomicUsize::new(0));
        let errors = Arc::new(Mutex::new(Vec::<String>::new()));
        let t = LaunchProgressTracker::for_steps(&[StepId::JreInstall]);
        let b = {
            let made = Arc::clone(&made);
            let errors = Arc::clone(&errors);
            TrackerBridge::new(t)
                .with_download_meters(Arc::new(move || {
                    made.fetch_add(1, Ordering::SeqCst);
                    Arc::new(StubMeter(String::new())) as Arc<dyn DownloadMeter>
                }))
                .with_error_handler(Arc::new(move |m, _| errors.lock().push(m.to_owned())))
        };
        assert_eq!(made.load(Ordering::SeqCst), 1);
        assert!(b.download_tracker().is_some());
        let h = b.handle_for(StepId::JreInstall);
        assert_eq!(made.load(Ordering::SeqCst), 2);
        assert!(h.download_tracker().is_some());
        assert_eq!(h.step_id(), StepId::JreInstall);
        b.on_error("rate limited", None);
        h.on_error("hash retry", None);
        assert_eq!(*errors.lock(), vec!["rate limited", "hash retry"]);
        // Without configuration there is no meter.
        let plain = TrackerBridge::new(LaunchProgressTracker::for_steps(&[]));
        assert!(plain.download_tracker().is_none());
        assert!(
            plain
                .handle_for(StepId::JreInstall)
                .download_tracker()
                .is_none()
        );
    }

    #[test]
    fn listener_may_call_back_into_the_bridge() {
        let t = LaunchProgressTracker::for_steps(&[StepId::McLibsAssets]);
        let b = Arc::new(TrackerBridge::new(Arc::clone(&t)));
        let weak = Arc::downgrade(&b);
        let seen = Arc::new(Mutex::new(Vec::new()));
        {
            let seen = Arc::clone(&seen);
            t.add_listener(Arc::new(move |_| {
                if let Some(b) = weak.upgrade() {
                    seen.lock().push(b.active_step());
                }
            }));
        }
        b.enter_step(StepId::McLibsAssets);
        b.submit_progress("x", 5.0);
        b.complete_step(StepId::McLibsAssets);
        assert_eq!(
            *seen.lock(),
            vec![Some(StepId::McLibsAssets), Some(StepId::McLibsAssets), None]
        );
    }
}

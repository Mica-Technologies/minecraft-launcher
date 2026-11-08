// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The multi-row launch progress model.

use crate::StepId;
use parking_lot::{Mutex, RwLock};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

/// Lifecycle of one row: `Pending -> Running -> Done | Failed | Skipped`.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum StepState {
    /// Not started yet.
    Pending,
    /// In flight; `progress` is meaningful.
    Running,
    /// Finished successfully (`progress` is 1).
    Done,
    /// Finished with an error; see `error`.
    Failed,
    /// Not needed for this launch.
    Skipped,
}

impl StepState {
    /// The Java enum constant name (`PENDING`, `RUNNING`, ...).
    pub const fn name(self) -> &'static str {
        match self {
            StepState::Pending => "PENDING",
            StepState::Running => "RUNNING",
            StepState::Done => "DONE",
            StepState::Failed => "FAILED",
            StepState::Skipped => "SKIPPED",
        }
    }

    /// Done, failed or skipped.
    pub const fn is_finished(self) -> bool {
        matches!(
            self,
            StepState::Done | StepState::Failed | StepState::Skipped
        )
    }
}

/// A point-in-time copy of one row.
#[derive(Clone, Debug, PartialEq)]
pub struct StepSnapshot {
    /// Which step.
    pub id: StepId,
    /// Its state.
    pub state: StepState,
    /// Progress fraction, clamped to `[0, 1]`.
    pub progress: f64,
    /// Detail line under the label; never null (empty when unset).
    pub sub_text: String,
    /// Error message set by `mark_failed`, cleared by `mark_running`.
    pub error: Option<String>,
}

/// Name used by the spec's crate map for Java's `LaunchProgressTracker.Step`.
pub type Step = StepSnapshot;

impl StepSnapshot {
    fn new(id: StepId) -> Self {
        Self {
            id,
            state: StepState::Pending,
            progress: 0.0,
            sub_text: String::new(),
            error: None,
        }
    }
}

/// Operation on a step that this tracker was not built with (Java: `IllegalArgumentException`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
#[error("step {0} is not part of this tracker")]
pub struct UnknownStep(pub StepId);

/// A step-change listener. Runs on the mutating thread, after the tracker lock is released.
pub type Listener = Arc<dyn Fn(&StepSnapshot) + Send + Sync>;

/// Handle returned by [`LaunchProgressTracker::add_listener`], for removal.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub struct ListenerId(u64);

/// Ordered launch steps with per-step state, progress and sub-text.
///
/// Mutations hold the tracker lock only while they change the row; listeners are called
/// afterwards with a snapshot, so a listener may read or mutate the tracker (from any thread)
/// without deadlocking. Every mutation produces exactly one notification, and a panicking
/// listener is isolated from the others and from the caller.
pub struct LaunchProgressTracker {
    steps: Mutex<Vec<StepSnapshot>>,
    listeners: RwLock<Vec<(ListenerId, Listener)>>,
    next_listener: AtomicU64,
}

impl std::fmt::Debug for LaunchProgressTracker {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LaunchProgressTracker")
            .field("steps", &*self.steps.lock())
            .field("listeners", &self.listeners.read().len())
            .finish()
    }
}

impl LaunchProgressTracker {
    /// A tracker with exactly the listed steps, in the given order, all pending. A step that is
    /// listed twice keeps only its first position.
    pub fn for_steps(ids: &[StepId]) -> Arc<Self> {
        Arc::new(Self::new(ids))
    }

    /// Like [`Self::for_steps`] but not wrapped in an `Arc`.
    pub fn new(ids: &[StepId]) -> Self {
        let mut steps: Vec<StepSnapshot> = Vec::with_capacity(ids.len());
        for &id in ids {
            if !steps.iter().any(|s| s.id == id) {
                steps.push(StepSnapshot::new(id));
            }
        }
        Self {
            steps: Mutex::new(steps),
            listeners: RwLock::new(Vec::new()),
            next_listener: AtomicU64::new(1),
        }
    }

    /// Snapshot of every row, in display order.
    pub fn steps(&self) -> Vec<StepSnapshot> {
        self.steps.lock().clone()
    }

    /// The step ids, in display order.
    pub fn step_ids(&self) -> Vec<StepId> {
        self.steps.lock().iter().map(|s| s.id).collect()
    }

    /// Snapshot of one row, `None` when the tracker was built without it.
    pub fn step(&self, id: StepId) -> Option<StepSnapshot> {
        self.steps.lock().iter().find(|s| s.id == id).cloned()
    }

    /// Whether the tracker has this step.
    pub fn contains(&self, id: StepId) -> bool {
        self.steps.lock().iter().any(|s| s.id == id)
    }

    /// Overall completion in `[0, 1]`: finished rows (done, failed, skipped) count 1, a running
    /// row counts its progress, a pending row 0; divided by the row count. 0 with no rows.
    pub fn overall_fraction(&self) -> f64 {
        let steps = self.steps.lock();
        if steps.is_empty() {
            return 0.0;
        }
        let done: f64 = steps
            .iter()
            .map(|s| match s.state {
                StepState::Done | StepState::Failed | StepState::Skipped => 1.0,
                StepState::Running => s.progress.clamp(0.0, 1.0),
                StepState::Pending => 0.0,
            })
            .sum();
        done / steps.len() as f64
    }

    /// The rows currently running (used to push retry / rate-limit / hash-retry messages onto
    /// every in-flight row).
    pub fn running_steps(&self) -> Vec<StepId> {
        self.steps
            .lock()
            .iter()
            .filter(|s| s.state == StepState::Running)
            .map(|s| s.id)
            .collect()
    }

    /// Register a listener; it receives every subsequent change.
    pub fn add_listener(&self, listener: Listener) -> ListenerId {
        let id = ListenerId(self.next_listener.fetch_add(1, Ordering::Relaxed));
        self.listeners.write().push((id, listener));
        id
    }

    /// Unregister a listener. Unknown ids are ignored.
    pub fn remove_listener(&self, id: ListenerId) {
        self.listeners.write().retain(|(lid, _)| *lid != id);
    }

    /// Mark a row running and clear its error.
    pub fn mark_running(&self, id: StepId) -> Result<(), UnknownStep> {
        self.mutate(id, |s| {
            s.state = StepState::Running;
            s.error = None;
        })
    }

    /// Mark a row done; its progress becomes 1.
    pub fn mark_done(&self, id: StepId) -> Result<(), UnknownStep> {
        self.mutate(id, |s| {
            s.state = StepState::Done;
            s.progress = 1.0;
        })
    }

    /// Mark a row failed with an error message.
    pub fn mark_failed(&self, id: StepId, msg: impl Into<String>) -> Result<(), UnknownStep> {
        let msg = msg.into();
        self.mutate(id, move |s| {
            s.state = StepState::Failed;
            s.error = Some(msg);
        })
    }

    /// Mark a row skipped.
    pub fn mark_skipped(&self, id: StepId) -> Result<(), UnknownStep> {
        self.mutate(id, |s| s.state = StepState::Skipped)
    }

    /// Set a row's progress fraction (clamped to `[0, 1]`; NaN becomes 0).
    pub fn set_progress(&self, id: StepId, p: f64) -> Result<(), UnknownStep> {
        self.mutate(id, |s| s.progress = clamp_fraction(p))
    }

    /// Set a row's sub-text.
    pub fn set_sub_text(&self, id: StepId, t: impl Into<String>) -> Result<(), UnknownStep> {
        let t = t.into();
        self.mutate(id, move |s| s.sub_text = t)
    }

    /// Set progress and sub-text together, with one notification.
    pub fn submit_progress(
        &self,
        id: StepId,
        p: f64,
        t: impl Into<String>,
    ) -> Result<(), UnknownStep> {
        let t = t.into();
        self.mutate(id, move |s| {
            s.progress = clamp_fraction(p);
            s.sub_text = t;
        })
    }

    fn mutate(&self, id: StepId, f: impl FnOnce(&mut StepSnapshot)) -> Result<(), UnknownStep> {
        let snapshot = {
            let mut steps = self.steps.lock();
            let step = steps
                .iter_mut()
                .find(|s| s.id == id)
                .ok_or(UnknownStep(id))?;
            f(step);
            step.clone()
        };
        self.notify(&snapshot);
        Ok(())
    }

    fn notify(&self, snapshot: &StepSnapshot) {
        // Copy the list so a listener may add or remove listeners without deadlocking.
        let listeners: Vec<Listener> = self
            .listeners
            .read()
            .iter()
            .map(|(_, l)| Arc::clone(l))
            .collect();
        for l in listeners {
            let _ = catch_unwind(AssertUnwindSafe(|| l(snapshot)));
        }
    }
}

fn clamp_fraction(p: f64) -> f64 {
    if p.is_nan() { 0.0 } else { p.clamp(0.0, 1.0) }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicUsize};
    use std::sync::mpsc;
    use std::thread;
    use std::time::Duration;

    // Port of LaunchProgressOverallTest.
    #[test]
    fn finished_running_and_pending_steps_count_their_share() {
        use StepId::*;
        let t = LaunchProgressTracker::for_steps(&[
            McLibsAssets,
            JreInstall,
            SecurityScan,
            ModpackContent,
        ]);
        assert!((t.overall_fraction() - 0.0).abs() < 1e-9);
        t.mark_done(McLibsAssets).unwrap();
        t.mark_running(JreInstall).unwrap();
        t.set_progress(JreInstall, 0.5).unwrap();
        t.mark_skipped(ModpackContent).unwrap();
        assert!((t.overall_fraction() - (1.0 + 0.5 + 0.0 + 1.0) / 4.0).abs() < 1e-9);
        t.mark_failed(SecurityScan, "boom").unwrap();
        assert!((t.overall_fraction() - 3.5 / 4.0).abs() < 1e-9);
    }

    #[test]
    fn no_steps_is_zero() {
        assert_eq!(
            LaunchProgressTracker::for_steps(&[]).overall_fraction(),
            0.0
        );
    }

    // Port of LaunchProgressTrackerNotifyTest.listenersAreNotifiedOutsideTheTrackerMonitor.
    #[test]
    fn listeners_are_notified_outside_the_tracker_lock() {
        let tracker =
            LaunchProgressTracker::for_steps(&[StepId::ModpackContent, StepId::ForgeLibs]);
        let (entered_tx, entered_rx) = mpsc::channel::<()>();
        let (done_tx, done_rx) = mpsc::channel::<()>();
        let done_rx = Mutex::new(done_rx);
        let entered_tx = Mutex::new(entered_tx);
        let first = AtomicBool::new(true);
        let first = Arc::new(first);
        let (outcome_tx, outcome_rx) = mpsc::channel::<bool>();
        let outcome_tx = Mutex::new(outcome_tx);
        {
            let first = Arc::clone(&first);
            tracker.add_listener(Arc::new(move |_s: &StepSnapshot| {
                if first.swap(false, Ordering::SeqCst) {
                    let _ = entered_tx.lock().send(());
                    let ok = done_rx.lock().recv_timeout(Duration::from_secs(5)).is_ok();
                    let _ = outcome_tx.lock().send(ok);
                }
            }));
        }
        let t2 = Arc::clone(&tracker);
        let second = thread::spawn(move || {
            entered_rx.recv_timeout(Duration::from_secs(5)).unwrap();
            t2.set_sub_text(StepId::ForgeLibs, "entered").unwrap();
            done_tx.send(()).unwrap();
        });
        let t1 = Arc::clone(&tracker);
        let first_thread = thread::spawn(move || t1.mark_running(StepId::ModpackContent).unwrap());
        assert!(
            outcome_rx.recv_timeout(Duration::from_secs(10)).unwrap(),
            "a mutator on a second thread never completed while a listener was in flight"
        );
        first_thread.join().unwrap();
        second.join().unwrap();
        assert_eq!(tracker.step(StepId::ForgeLibs).unwrap().sub_text, "entered");
    }

    // Port of LaunchProgressTrackerNotifyTest.mutationsProduceOneNotificationEach.
    #[test]
    fn mutations_produce_one_notification_each() {
        let tracker = LaunchProgressTracker::for_steps(&[StepId::McLibsAssets]);
        let events = Arc::new(Mutex::new(Vec::<String>::new()));
        {
            let events = Arc::clone(&events);
            tracker.add_listener(Arc::new(move |s: &StepSnapshot| {
                events.lock().push(format!("{}:{}", s.id, s.state.name()));
            }));
        }
        tracker.mark_running(StepId::McLibsAssets).unwrap();
        tracker
            .submit_progress(StepId::McLibsAssets, 0.5, "halfway")
            .unwrap();
        tracker.mark_done(StepId::McLibsAssets).unwrap();
        let ev = events.lock().clone();
        assert_eq!(
            ev,
            vec![
                "MC_LIBS_ASSETS:RUNNING",
                "MC_LIBS_ASSETS:RUNNING",
                "MC_LIBS_ASSETS:DONE"
            ]
        );
        assert_eq!(tracker.step(StepId::McLibsAssets).unwrap().progress, 1.0);
    }

    #[test]
    fn absent_steps_are_an_error_and_do_not_notify() {
        let tracker = LaunchProgressTracker::for_steps(&[StepId::JreInstall]);
        let count = Arc::new(AtomicUsize::new(0));
        {
            let count = Arc::clone(&count);
            tracker.add_listener(Arc::new(move |_| {
                count.fetch_add(1, Ordering::SeqCst);
            }));
        }
        assert_eq!(
            tracker.mark_running(StepId::ForgeProcessors),
            Err(UnknownStep(StepId::ForgeProcessors))
        );
        assert!(tracker.step(StepId::ForgeProcessors).is_none());
        assert_eq!(count.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn progress_is_clamped_and_running_clears_the_error() {
        let t = LaunchProgressTracker::for_steps(&[StepId::SecurityScan]);
        t.set_progress(StepId::SecurityScan, 7.0).unwrap();
        assert_eq!(t.step(StepId::SecurityScan).unwrap().progress, 1.0);
        t.set_progress(StepId::SecurityScan, -1.0).unwrap();
        assert_eq!(t.step(StepId::SecurityScan).unwrap().progress, 0.0);
        t.set_progress(StepId::SecurityScan, f64::NAN).unwrap();
        assert_eq!(t.step(StepId::SecurityScan).unwrap().progress, 0.0);
        t.mark_failed(StepId::SecurityScan, "bad").unwrap();
        let s = t.step(StepId::SecurityScan).unwrap();
        assert_eq!(s.state, StepState::Failed);
        assert_eq!(s.error.as_deref(), Some("bad"));
        t.mark_running(StepId::SecurityScan).unwrap();
        assert_eq!(t.step(StepId::SecurityScan).unwrap().error, None);
    }

    #[test]
    fn order_is_kept_and_running_steps_are_listed() {
        use StepId::*;
        let t = LaunchProgressTracker::for_steps(&[SecurityScan, ModpackContent, JreInstall]);
        assert_eq!(t.step_ids(), vec![SecurityScan, ModpackContent, JreInstall]);
        t.mark_running(JreInstall).unwrap();
        t.mark_running(SecurityScan).unwrap();
        assert_eq!(t.running_steps(), vec![SecurityScan, JreInstall]);
        assert!(t.steps().iter().all(|s| s.sub_text.is_empty()));
    }

    #[test]
    fn panicking_listener_does_not_stop_others_and_removal_works() {
        let t = LaunchProgressTracker::for_steps(&[StepId::ForgeLibs]);
        let hits = Arc::new(AtomicUsize::new(0));
        let bad = t.add_listener(Arc::new(|_| panic!("listener failure")));
        let good = {
            let hits = Arc::clone(&hits);
            t.add_listener(Arc::new(move |_| {
                hits.fetch_add(1, Ordering::SeqCst);
            }))
        };
        t.mark_running(StepId::ForgeLibs).unwrap();
        assert_eq!(hits.load(Ordering::SeqCst), 1);
        t.remove_listener(bad);
        t.remove_listener(good);
        t.mark_done(StepId::ForgeLibs).unwrap();
        assert_eq!(hits.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn listener_may_reenter_the_tracker_on_the_same_thread() {
        let t = LaunchProgressTracker::for_steps(&[StepId::McLibsAssets]);
        let weak = Arc::downgrade(&t);
        let seen = Arc::new(Mutex::new(None));
        {
            let seen = Arc::clone(&seen);
            t.add_listener(Arc::new(move |_| {
                if let Some(t) = weak.upgrade() {
                    *seen.lock() = Some(t.overall_fraction());
                }
            }));
        }
        t.mark_done(StepId::McLibsAssets).unwrap();
        assert_eq!(*seen.lock(), Some(1.0));
    }
}

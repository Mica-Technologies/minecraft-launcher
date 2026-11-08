// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The running-operation registry: the cancel token and step tracker of every verify, update
//! and launch, keyed by [`OperationId`], so a front end can show Java's Launch Progress rows for
//! a verify (with Cancel) and follow a launch by an operation id.

use std::sync::Arc;

use mmcl_net::CancelToken;
use mmcl_progress::LaunchProgressTracker;

use super::Engine;
use super::launch::step_view;
use crate::event::{EngineEvent, OperationId, OperationStepsView};
use crate::model::PackId;
use crate::session::SessionId;

/// What the engine holds for one running operation.
pub(crate) struct OpControl {
    pub(crate) cancel: CancelToken,
    /// The step tracker of the pack being worked on (verify / update / export).
    pub(crate) tracker: Option<Arc<LaunchProgressTracker>>,
    pub(crate) pack: Option<PackId>,
    pub(crate) label: String,
    /// Set for a launch: its rows live in the session.
    pub(crate) session: Option<SessionId>,
}

impl OpControl {
    fn new(cancel: CancelToken) -> OpControl {
        OpControl {
            cancel,
            tracker: None,
            pack: None,
            label: String::new(),
            session: None,
        }
    }
}

/// Removes its operation from the registry when dropped (only the guard that registered it).
pub(crate) struct OpGuard {
    engine: Engine,
    op: OperationId,
    owner: bool,
}

impl Drop for OpGuard {
    fn drop(&mut self) {
        if self.owner {
            self.engine.inner.ops.lock().remove(&self.op);
        }
    }
}

/// The overall fraction of a set of rows (Done / Failed / Skipped count as finished).
pub(crate) fn rows_fraction(steps: &[crate::session::LaunchStepView]) -> f64 {
    use crate::session::StepStateView as S;
    if steps.is_empty() {
        return 0.0;
    }
    let sum: f64 = steps
        .iter()
        .map(|s| match s.state {
            S::Done | S::Failed | S::Skipped => 1.0,
            _ => s.progress.clamp(0.0, 1.0),
        })
        .sum();
    sum / steps.len() as f64
}

impl Engine {
    /// Registers `op` (when it is not registered yet) and returns its guard and cancel token.
    /// A nested call (verify-all running each pack under one id) shares the outer token.
    pub(crate) fn begin_op(&self, op: OperationId) -> (OpGuard, CancelToken) {
        let mut ops = self.inner.ops.lock();
        let (cancel, owner) = match ops.get(&op) {
            Some(c) => (c.cancel.clone(), false),
            None => {
                let cancel = CancelToken::new();
                ops.insert(op, OpControl::new(cancel.clone()));
                (cancel, true)
            }
        };
        (
            OpGuard {
                engine: self.clone(),
                op,
                owner,
            },
            cancel,
        )
    }

    /// Records the tracker the operation's rows come from now.
    pub(crate) fn set_op_tracker(
        &self,
        op: OperationId,
        pack: PackId,
        label: String,
        tracker: Arc<LaunchProgressTracker>,
    ) {
        if let Some(c) = self.inner.ops.lock().get_mut(&op) {
            c.tracker = Some(tracker);
            c.pack = Some(pack);
            c.label = label;
        }
    }

    /// Registers a launch's operation: its cancel token is the session's.
    pub(crate) fn register_launch_op(
        &self,
        op: OperationId,
        sid: SessionId,
        pack: PackId,
        label: String,
        cancel: CancelToken,
    ) {
        self.inner.session_ops.lock().insert(sid, op);
        self.inner.ops.lock().insert(
            op,
            OpControl {
                cancel,
                tracker: None,
                pack: Some(pack),
                label,
                session: Some(sid),
            },
        );
    }

    /// Ends a launch's operation (its preparation is over) and sends `OperationFinished`.
    pub(crate) fn finish_launch_op(&self, sid: SessionId) {
        let op = self.inner.session_ops.lock().get(&sid).copied();
        if let Some(op) = op {
            self.inner.ops.lock().remove(&op);
            self.emit(EngineEvent::OperationFinished(op));
        }
    }

    /// Forgets the operation ids of sessions that are gone.
    pub(crate) fn forget_session_op(&self, sid: SessionId) {
        self.inner.session_ops.lock().remove(&sid);
    }

    /// Cancels a running operation: a verify, update or official export stops at its next
    /// check (the step rows show it failed, the call returns [`crate::EngineError::Cancelled`]);
    /// a launch is cancelled like [`Engine::cancel_launch`]. `false` when `op` is not running.
    pub fn cancel_operation(&self, op: OperationId) -> bool {
        let (cancel, session) = match self.inner.ops.lock().get(&op) {
            Some(c) => (c.cancel.clone(), c.session),
            None => return false,
        };
        mmcl_log::std(&mmcl_i18n::tf!(
            "log.engine.operationCancelRequested",
            op.0.to_string()
        ));
        match session {
            Some(sid) => {
                let _ = self.cancel_launch(sid);
            }
            None => cancel.cancel(),
        }
        if let Some(v) = self.operation_steps(op) {
            self.emit(EngineEvent::OperationSteps(Arc::new(v)));
        }
        true
    }

    /// Whether `op` is still running.
    pub fn is_operation_running(&self, op: OperationId) -> bool {
        self.inner.ops.lock().contains_key(&op)
    }

    /// The step rows of a running operation (a verify, update, official export or launch);
    /// `None` when it is not running or has no rows yet.
    pub fn operation_steps(&self, op: OperationId) -> Option<OperationStepsView> {
        let (tracker, pack, label, session, cancelled) = {
            let ops = self.inner.ops.lock();
            let c = ops.get(&op)?;
            (
                c.tracker.clone(),
                c.pack.clone(),
                c.label.clone(),
                c.session,
                c.cancel.is_cancelled(),
            )
        };
        let steps = match (tracker, session) {
            (Some(t), _) => t.steps().iter().map(step_view).collect(),
            (None, Some(sid)) => self.inner.sessions.get(sid)?.steps,
            (None, None) => return None,
        };
        let cancel_requested = cancelled
            || session
                .and_then(|s| self.inner.sessions.get(s))
                .is_some_and(|s| s.cancelled);
        Some(OperationStepsView {
            operation: op,
            pack,
            session,
            label,
            fraction: rows_fraction(&steps),
            steps,
            cancel_requested,
        })
    }

    /// The operation id a launch reports `Progress` / `OperationSteps` / `OperationFinished`
    /// under (every launch has one; [`Engine::launch_with`] picks it up front).
    pub fn operation_for_session(&self, sid: SessionId) -> Option<OperationId> {
        self.inner.session_ops.lock().get(&sid).copied()
    }

    /// The session a launch operation belongs to.
    pub fn session_for_operation(&self, op: OperationId) -> Option<SessionId> {
        self.inner
            .session_ops
            .lock()
            .iter()
            .find(|(_, o)| **o == op)
            .map(|(s, _)| *s)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::session::{LaunchStepView, StepStateView};

    fn row(state: StepStateView, progress: f64) -> LaunchStepView {
        LaunchStepView {
            id: "X".into(),
            label: "x".into(),
            state,
            progress,
            sub_text: String::new(),
            error: None,
        }
    }

    #[test]
    fn the_fraction_counts_finished_rows_whole() {
        assert_eq!(rows_fraction(&[]), 0.0);
        let rows = [
            row(StepStateView::Done, 0.0),
            row(StepStateView::Running, 0.5),
            row(StepStateView::Pending, 0.0),
            row(StepStateView::Skipped, 0.0),
        ];
        assert!((rows_fraction(&rows) - 0.625).abs() < 1e-9);
    }
}

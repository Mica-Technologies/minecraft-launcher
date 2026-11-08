// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Launch progress for the progress window (Java `TuiProgressProvider`).
//!
//! [`TuiProgress`] forwards each `(percent, section, detail, download status)` update unchanged
//! to a listener. [`ProgressLines`] is what the TUI makes of an update: the three lines of the
//! progress window. [`from_session`] / [`from_operation`] turn the engine's session snapshots
//! and progress events into such updates.

use mmcl_engine::{OperationProgress, SessionInfo, StepStateView};

/// The listener a [`TuiProgress`] forwards to.
pub type ProgressListener =
    Box<dyn Fn(f64, Option<&str>, Option<&str>, Option<&str>) + Send + Sync>;

/// Forwards every progress update, unchanged and in order, to its listener (none tolerated).
#[derive(Default)]
pub struct TuiProgress {
    listener: Option<ProgressListener>,
}

impl std::fmt::Debug for TuiProgress {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TuiProgress")
            .field("has_listener", &self.listener.is_some())
            .finish()
    }
}

impl TuiProgress {
    /// A provider forwarding to `listener`.
    pub fn new(listener: Option<ProgressListener>) -> TuiProgress {
        TuiProgress { listener }
    }

    /// A provider forwarding to a closure.
    pub fn from_fn(
        f: impl Fn(f64, Option<&str>, Option<&str>, Option<&str>) + Send + Sync + 'static,
    ) -> TuiProgress {
        TuiProgress::new(Some(Box::new(f)))
    }

    /// One update; `None`s pass through as `None`.
    pub fn update(
        &self,
        percent: f64,
        section: Option<&str>,
        detail: Option<&str>,
        download_status: Option<&str>,
    ) {
        if let Some(l) = &self.listener {
            l(percent, section, detail, download_status);
        }
    }
}

/// The three lines of the launch progress window.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ProgressLines {
    pub section: String,
    pub detail: String,
    pub percent: String,
}

impl ProgressLines {
    /// The lines for one update: missing texts become `""`, a non-blank download status is
    /// appended to the detail (three spaces apart), and the percent is clamped to 0..100 and
    /// truncated to a whole number.
    pub fn from_update(
        percent: f64,
        section: Option<&str>,
        detail: Option<&str>,
        status: Option<&str>,
    ) -> ProgressLines {
        let mut d = detail.unwrap_or("").to_string();
        if let Some(s) = status.filter(|s| !s.trim().is_empty()) {
            if d.is_empty() {
                d = s.to_string();
            } else {
                d = format!("{d}   {s}");
            }
        }
        let p = if percent.is_nan() {
            0.0
        } else {
            percent.clamp(0.0, 100.0)
        };
        ProgressLines {
            section: section.unwrap_or("").to_string(),
            detail: d,
            percent: format!("{}%", p as i64),
        }
    }
}

/// An update from a session snapshot: the overall percent over its launch steps, the running
/// (else the last finished) step as the section and its sub-text as the detail. `None` while the
/// session has no steps yet.
pub fn from_session(info: &SessionInfo) -> Option<(f64, String, String)> {
    if info.steps.is_empty() {
        return None;
    }
    let total: f64 = info
        .steps
        .iter()
        .map(|s| match s.state {
            StepStateView::Done | StepStateView::Skipped => 1.0,
            StepStateView::Running | StepStateView::Failed => s.progress.clamp(0.0, 1.0),
            StepStateView::Pending => 0.0,
        })
        .sum();
    let percent = total / info.steps.len() as f64 * 100.0;
    let current = info
        .steps
        .iter()
        .find(|s| s.state == StepStateView::Running)
        .or_else(|| {
            info.steps
                .iter()
                .rev()
                .find(|s| matches!(s.state, StepStateView::Done | StepStateView::Failed))
        })?;
    Some((percent, current.label.clone(), current.sub_text.clone()))
}

/// An update from an engine progress event (`fraction` 0..1, negative = indeterminate).
pub fn from_operation(p: &OperationProgress) -> (f64, String, String) {
    let percent = if p.fraction < 0.0 {
        0.0
    } else {
        p.fraction * 100.0
    };
    (percent, p.label.clone(), p.text.clone())
}

#[cfg(test)]
mod tests {
    use super::*;
    use parking_lot::Mutex;
    use std::sync::Arc;

    type Call = (f64, Option<String>, Option<String>, Option<String>);

    fn recording() -> (TuiProgress, Arc<Mutex<Vec<Call>>>) {
        let calls = Arc::new(Mutex::new(Vec::new()));
        let c = calls.clone();
        let p = TuiProgress::from_fn(move |pct, s, d, st| {
            c.lock().push((
                pct,
                s.map(str::to_string),
                d.map(str::to_string),
                st.map(str::to_string),
            ));
        });
        (p, calls)
    }

    // TuiProgressProviderTest

    #[test]
    fn forwards_all_four_args_unchanged() {
        let (p, calls) = recording();
        p.update(42.5, Some("Section"), Some("Detail"), Some("3 MB/s"));
        assert_eq!(
            calls.lock()[0],
            (
                42.5,
                Some("Section".into()),
                Some("Detail".into()),
                Some("3 MB/s".into())
            )
        );
    }

    #[test]
    fn forwards_every_call_in_order() {
        let (p, calls) = recording();
        for i in 0..5 {
            p.update(f64::from(i), Some(&i.to_string()), None, None);
        }
        let got: Vec<f64> = calls.lock().iter().map(|c| c.0).collect();
        assert_eq!(got, vec![0.0, 1.0, 2.0, 3.0, 4.0]);
    }

    #[test]
    fn nulls_pass_through() {
        let (p, calls) = recording();
        p.update(0.0, None, None, None);
        assert_eq!(calls.lock()[0], (0.0, None, None, None));
    }

    #[test]
    fn no_listener_does_not_panic() {
        TuiProgress::new(None).update(50.0, Some("a"), Some("b"), Some("c"));
        TuiProgress::default().update(50.0, None, None, None);
    }

    #[test]
    fn is_shareable_across_threads() {
        fn assert_send_sync<T: Send + Sync>() {}
        assert_send_sync::<TuiProgress>();
    }

    // The TUI's conversion

    #[test]
    fn lines_join_detail_and_status() {
        let l = ProgressLines::from_update(12.9, Some("Files"), Some("mod.jar"), Some("3 MB/s"));
        assert_eq!(l.section, "Files");
        assert_eq!(l.detail, "mod.jar   3 MB/s");
        assert_eq!(l.percent, "12%");
        let l = ProgressLines::from_update(150.0, None, None, Some("3 MB/s"));
        assert_eq!(l.detail, "3 MB/s");
        assert_eq!(l.percent, "100%");
        let l = ProgressLines::from_update(-3.0, None, Some("d"), Some("  "));
        assert_eq!(l.detail, "d");
        assert_eq!(l.percent, "0%");
        assert_eq!(
            ProgressLines::from_update(f64::NAN, None, None, None).percent,
            "0%"
        );
    }
}

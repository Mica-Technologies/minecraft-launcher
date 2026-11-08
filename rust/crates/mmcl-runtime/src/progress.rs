// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Runtime progress fan-out (Java `reportProgress`).
//!
//! A verify reports `(label, text, percent)` to an optional [`RuntimeProgress`] (the GUI's
//! standalone runtime progress view, or an adapter onto a launch's [`ProgressSink`]) and
//! **always** logs `"<label>: <text> (<pct>%)"`, the percent left out when it is negative
//! (indeterminate).

use mmcl_progress::ProgressSink;

/// Receives runtime verify and install progress.
pub trait RuntimeProgress: Send + Sync {
    /// `label` is the upper heading (which runtime), `text` the current step, `percent` 0-100 or
    /// negative for indeterminate.
    fn report(&self, label: &str, text: &str, percent: f64);
}

impl<F: Fn(&str, &str, f64) + Send + Sync> RuntimeProgress for F {
    fn report(&self, label: &str, text: &str, percent: f64) {
        self(label, text, percent)
    }
}

/// Forwards only the step text to a launch's [`ProgressSink`] (Java's inline
/// `RuntimeProgressCallback`, which received the lower text).
pub struct SinkProgress<'a>(pub &'a dyn ProgressSink);

impl RuntimeProgress for SinkProgress<'_> {
    fn report(&self, _label: &str, text: &str, _percent: f64) {
        self.0.set_curr_text(text);
    }
}

/// The log line for one progress report.
pub fn progress_line(label: &str, text: &str, percent: f64) -> String {
    if percent >= 0.0 {
        format!("{label}: {text} ({}%)", percent as i64)
    } else {
        format!("{label}: {text}")
    }
}

/// Reports to `progress` (when present) and logs the line.
pub fn report_progress(
    progress: Option<&dyn RuntimeProgress>,
    label: &str,
    text: &str,
    percent: f64,
) {
    if let Some(p) = progress {
        p.report(label, text, percent);
    }
    mmcl_log::std(&progress_line(label, text, percent));
}

#[cfg(test)]
mod tests {
    use super::*;
    use parking_lot::Mutex;

    #[test]
    fn report_with_no_sink_does_not_panic() {
        report_progress(None, "Label", "Text", 50.0);
    }

    #[test]
    fn report_invokes_the_callback() {
        let received = Mutex::new(Vec::<(String, String, f64)>::new());
        let cb = |l: &str, t: &str, p: f64| received.lock().push((l.into(), t.into(), p));
        report_progress(Some(&cb), "Java Runtime", "Installing files (3/10)", 30.0);
        assert_eq!(
            *received.lock(),
            vec![(
                "Java Runtime".to_string(),
                "Installing files (3/10)".to_string(),
                30.0
            )]
        );
    }

    #[test]
    fn negative_percent_is_indeterminate() {
        let received = Mutex::new(Vec::<String>::new());
        let cb = |_: &str, t: &str, _: f64| received.lock().push(t.into());
        report_progress(Some(&cb), "L", "Downloading...", -1.0);
        assert_eq!(*received.lock(), vec!["Downloading...".to_string()]);
        assert_eq!(
            progress_line("L", "Downloading...", -1.0),
            "L: Downloading..."
        );
        assert_eq!(progress_line("L", "T", 37.9), "L: T (37%)");
    }

    #[test]
    fn sink_adapter_forwards_the_lower_text() {
        struct Rec(Mutex<Vec<String>>);
        impl ProgressSink for Rec {
            fn submit_progress(&self, _: &str, _: f64) {}
            fn set_curr_text(&self, d: &str) {
                self.0.lock().push(d.into());
            }
            fn start_section(&self, _: &str, _: f64) {}
            fn end_section(&self, _: &str) {}
        }
        let rec = Rec(Mutex::new(Vec::new()));
        let p = SinkProgress(&rec);
        report_progress(Some(&p), "Upper", "Lower", 10.0);
        assert_eq!(*rec.0.lock(), vec!["Lower".to_string()]);
    }
}

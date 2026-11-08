// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The legacy progress sink every sub-call reports to.

use crate::{DownloadMeter, StepId};
use std::sync::Arc;

/// Legacy progress sink (Java `GameModPackProgressProvider`), used by file sync, the loader,
/// the runtime manager and the scanner.
///
/// Sections live on a 0-100 scale: `start_section(title, size)` opens a section worth `size`
/// percent, `submit_progress(detail, delta)` adds `delta` (0-100 within the section),
/// `end_section(text)` closes it. Step hooks have no-op defaults; only sinks backed by a
/// [`crate::LaunchProgressTracker`] use them.
pub trait ProgressSink: Send + Sync {
    /// Add `section_delta` percent (of the current section) and set the detail text.
    fn submit_progress(&self, detail: &str, section_delta: f64);
    /// Set the detail text.
    fn set_curr_text(&self, detail: &str);
    /// Open a section worth `size` percent of the whole.
    fn start_section(&self, title: &str, size: f64);
    /// Close the current section.
    fn end_section(&self, text: &str);
    /// Force the overall progress to complete, with a final title.
    fn signal_complete(&self, _title: &str) {}
    /// The download meter shared by every file of the current sync, if this sink has one.
    fn download_tracker(&self) -> Option<Arc<dyn DownloadMeter>> {
        None
    }
    /// A pipeline step starts.
    fn enter_step(&self, _id: StepId) {}
    /// A pipeline step finished.
    fn complete_step(&self, _id: StepId) {}
    /// A pipeline step failed.
    fn fail_step(&self, _id: StepId, _msg: &str) {}
    /// A non-fatal error worth showing the user (a warning toast in the GUI).
    fn on_error(&self, _msg: &str, _cause: Option<&(dyn std::error::Error + Send + Sync)>) {}
}

/// The sink that drops everything (Java: `progressProvider == null`).
#[derive(Debug, Default, Clone, Copy)]
pub struct NullSink;

impl ProgressSink for NullSink {
    fn submit_progress(&self, _detail: &str, _section_delta: f64) {}
    fn set_curr_text(&self, _detail: &str) {}
    fn start_section(&self, _title: &str, _size: f64) {}
    fn end_section(&self, _text: &str) {}
}

macro_rules! forward_sink {
    ($ty:ty) => {
        impl<T: ProgressSink + ?Sized> ProgressSink for $ty {
            fn submit_progress(&self, detail: &str, section_delta: f64) {
                (**self).submit_progress(detail, section_delta)
            }
            fn set_curr_text(&self, detail: &str) {
                (**self).set_curr_text(detail)
            }
            fn start_section(&self, title: &str, size: f64) {
                (**self).start_section(title, size)
            }
            fn end_section(&self, text: &str) {
                (**self).end_section(text)
            }
            fn signal_complete(&self, title: &str) {
                (**self).signal_complete(title)
            }
            fn download_tracker(&self) -> Option<Arc<dyn DownloadMeter>> {
                (**self).download_tracker()
            }
            fn enter_step(&self, id: StepId) {
                (**self).enter_step(id)
            }
            fn complete_step(&self, id: StepId) {
                (**self).complete_step(id)
            }
            fn fail_step(&self, id: StepId, msg: &str) {
                (**self).fail_step(id, msg)
            }
            fn on_error(&self, msg: &str, cause: Option<&(dyn std::error::Error + Send + Sync)>) {
                (**self).on_error(msg, cause)
            }
        }
    };
}

forward_sink!(Arc<T>);
forward_sink!(&T);
forward_sink!(Box<T>);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn null_sink_accepts_everything() {
        let s: Arc<dyn ProgressSink> = Arc::new(NullSink);
        s.start_section("a", 10.0);
        s.submit_progress("b", 50.0);
        s.set_curr_text("c");
        s.end_section("d");
        s.signal_complete("e");
        s.enter_step(StepId::JreInstall);
        s.complete_step(StepId::JreInstall);
        s.fail_step(StepId::JreInstall, "x");
        s.on_error("x", None);
        assert!(s.download_tracker().is_none());
        // Forwarding through Arc / & works.
        let r: &dyn ProgressSink = &s;
        r.submit_progress("f", 1.0);
    }
}

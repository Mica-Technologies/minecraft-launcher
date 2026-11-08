// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Bridges the network crate's [`DownloadTracker`] to the progress crate's [`DownloadMeter`].

use std::any::Any;
use std::sync::Arc;

use mmcl_net::DownloadTracker;
use mmcl_progress::{DownloadMeter, ProgressSink};

/// A [`DownloadTracker`] seen as a [`DownloadMeter`]. Progress sinks carry one of these; file
/// sync recovers the tracker with [`tracker_of`] to hand it to each download.
#[derive(Clone, Debug)]
pub struct TrackerMeter(pub Arc<DownloadTracker>);

impl TrackerMeter {
    /// A fresh tracker wrapped as a meter.
    pub fn new_shared() -> Arc<dyn DownloadMeter> {
        Arc::new(TrackerMeter(Arc::new(DownloadTracker::new())))
    }
}

impl DownloadMeter for TrackerMeter {
    fn reset(&self) {
        self.0.reset();
    }
    fn set_total_file_count(&self, count: u64) {
        self.0
            .set_total_file_count(i64::try_from(count).unwrap_or(i64::MAX));
    }
    fn register_download(&self, expected_bytes: i64) {
        self.0.register_download(expected_bytes);
    }
    fn add_bytes(&self, bytes: i64) {
        self.0.add_bytes(bytes);
    }
    fn complete_download(&self) {
        self.0.complete_download();
    }
    fn complete_file(&self) {
        self.0.complete_file();
    }
    fn formatted_status(&self) -> String {
        self.0.formatted_status()
    }
    fn as_any(&self) -> &dyn Any {
        self
    }
}

/// The byte tracker behind a sink's meter, when the meter is a [`TrackerMeter`].
pub fn tracker_of(sink: &dyn ProgressSink) -> Option<Arc<DownloadTracker>> {
    let meter = sink.download_tracker()?;
    meter
        .as_any()
        .downcast_ref::<TrackerMeter>()
        .map(|m| m.0.clone())
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_progress::NullSink;

    struct WithMeter(Arc<dyn DownloadMeter>);
    impl ProgressSink for WithMeter {
        fn submit_progress(&self, _: &str, _: f64) {}
        fn set_curr_text(&self, _: &str) {}
        fn start_section(&self, _: &str, _: f64) {}
        fn end_section(&self, _: &str) {}
        fn download_tracker(&self) -> Option<Arc<dyn DownloadMeter>> {
            Some(self.0.clone())
        }
    }

    #[test]
    fn recovers_the_tracker() {
        let meter = TrackerMeter::new_shared();
        meter.set_total_file_count(3);
        meter.complete_file();
        let sink = WithMeter(meter);
        let t = tracker_of(&sink).expect("tracker");
        assert_eq!(t.total_files(), 3);
        assert_eq!(t.completed_files(), 1);
        assert!(tracker_of(&NullSink).is_none());
    }
}

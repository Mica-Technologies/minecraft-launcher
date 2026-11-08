// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The weighted single-bar progress model (Java `GameModPackProgressProvider`).

use crate::clock::Throttle;
use crate::{Clock, DownloadMeter, MonotonicClock, ProgressSink};
use parking_lot::Mutex;
use std::sync::Arc;
use std::time::Duration;

/// Full scale of the weighted model.
pub const PROGRESS_PERCENT_BASE: f64 = 100.0;

/// Minimum gap between two `submit_progress` notifications (bypassed when a section reaches
/// 100).
pub const SUBMIT_THROTTLE: Duration = Duration::from_millis(50);

#[derive(Debug)]
struct State {
    completed: f64,
    section_title: String,
    detail: String,
    section_size: f64,
    section_progress: f64,
    throttle: Throttle,
}

/// What a fire hands the handler, captured under the lock and delivered after it.
struct Fire {
    percent: f64,
    title: String,
    detail: String,
}

/// Weighted sections on a 0-100 scale, firing
/// `handler(percent, section_title, detail, download_status)` on every change.
///
/// * `start_section(title, size)` folds the open section's weighted progress into the completed
///   percent, opens a section worth `size`, clears the detail, fires immediately.
/// * `submit_progress(detail, delta)` adds `delta` to the section (cap 100) and fires at most
///   every [`SUBMIT_THROTTLE`] unless the section reached 100.
/// * `set_curr_text(detail)` fires immediately.
/// * `end_section(text)` adds the full section size to the completed percent and fires.
/// * `signal_complete(title)` forces 100.
///
/// Overall percent = `completed + section_progress * size / 100`. `download_status` is the
/// attached [`DownloadMeter`]'s formatted status (empty without one). The handler runs on the
/// calling thread, outside the internal lock.
pub struct SectionProgress<F: Fn(f64, &str, &str, &str) + Send + Sync> {
    handler: F,
    state: Mutex<State>,
    meter: Option<Arc<dyn DownloadMeter>>,
    clock: Arc<dyn Clock>,
}

impl<F: Fn(f64, &str, &str, &str) + Send + Sync> SectionProgress<F> {
    /// A model with no download meter and the real clock.
    pub fn new(handler: F) -> Self {
        Self {
            handler,
            state: Mutex::new(State {
                completed: 0.0,
                section_title: String::new(),
                detail: String::new(),
                section_size: PROGRESS_PERCENT_BASE,
                section_progress: 0.0,
                throttle: Throttle::default(),
            }),
            meter: None,
            clock: Arc::new(MonotonicClock::new()),
        }
    }

    /// Attach the download meter this sink owns for the whole sync (Java: the provider's own
    /// `DownloadTracker`).
    pub fn with_download_meter(mut self, meter: Arc<dyn DownloadMeter>) -> Self {
        self.meter = Some(meter);
        self
    }

    /// Replace the throttle clock (tests).
    pub fn with_clock(mut self, clock: Arc<dyn Clock>) -> Self {
        self.clock = clock;
        self
    }

    /// Current overall percent (0-100).
    pub fn percent(&self) -> f64 {
        overall(&self.state.lock())
    }

    /// Current section title.
    pub fn section_title(&self) -> String {
        self.state.lock().section_title.clone()
    }

    /// Current detail text.
    pub fn detail(&self) -> String {
        self.state.lock().detail.clone()
    }

    fn fire(&self, fire: Fire) {
        let status = self
            .meter
            .as_ref()
            .map(|m| m.formatted_status())
            .unwrap_or_default();
        (self.handler)(fire.percent, &fire.title, &fire.detail, &status);
    }
}

fn overall(s: &State) -> f64 {
    s.completed + s.section_progress * (s.section_size / PROGRESS_PERCENT_BASE)
}

fn capture(s: &State) -> Fire {
    Fire {
        percent: overall(s),
        title: s.section_title.clone(),
        detail: s.detail.clone(),
    }
}

impl<F: Fn(f64, &str, &str, &str) + Send + Sync> ProgressSink for SectionProgress<F> {
    fn submit_progress(&self, detail: &str, section_delta: f64) {
        let now = self.clock.now();
        let fire = {
            let mut s = self.state.lock();
            s.detail = detail.to_owned();
            s.section_progress = (s.section_progress + section_delta).min(PROGRESS_PERCENT_BASE);
            let full = s.section_progress >= PROGRESS_PERCENT_BASE;
            if s.throttle.should_fire(now, SUBMIT_THROTTLE, full) {
                Some(capture(&s))
            } else {
                None
            }
        };
        if let Some(f) = fire {
            self.fire(f);
        }
    }

    fn set_curr_text(&self, detail: &str) {
        let f = {
            let mut s = self.state.lock();
            s.detail = detail.to_owned();
            capture(&s)
        };
        self.fire(f);
    }

    fn start_section(&self, title: &str, size: f64) {
        let f = {
            let mut s = self.state.lock();
            // Fold the open section's weighted share (the Java code added it unweighted).
            s.completed += s.section_progress * (s.section_size / PROGRESS_PERCENT_BASE);
            s.section_title = title.to_owned();
            s.detail.clear();
            s.section_size = size;
            s.section_progress = 0.0;
            capture(&s)
        };
        self.fire(f);
    }

    fn end_section(&self, text: &str) {
        let f = {
            let mut s = self.state.lock();
            s.completed += s.section_size;
            s.section_progress = 0.0;
            s.detail = text.to_owned();
            s.section_size = 0.0;
            capture(&s)
        };
        self.fire(f);
    }

    fn signal_complete(&self, title: &str) {
        let f = {
            let mut s = self.state.lock();
            s.completed = PROGRESS_PERCENT_BASE;
            s.section_progress = 0.0;
            s.section_size = 0.0;
            s.section_title = title.to_owned();
            s.detail.clear();
            capture(&s)
        };
        self.fire(f);
    }

    fn download_tracker(&self) -> Option<Arc<dyn DownloadMeter>> {
        self.meter.clone()
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::clock::test_clock::ManualClock;
    use std::any::Any;

    type Events = Arc<Mutex<Vec<(f64, String, String, String)>>>;

    type Handler = Box<dyn Fn(f64, &str, &str, &str) + Send + Sync>;

    fn model(clock: Arc<ManualClock>) -> (SectionProgress<Handler>, Events) {
        let events: Events = Arc::default();
        let e = Arc::clone(&events);
        let handler: Handler = Box::new(move |p, t: &str, d: &str, st: &str| {
            e.lock()
                .push((p, t.to_owned(), d.to_owned(), st.to_owned()));
        });
        let m = SectionProgress::new(handler).with_clock(clock);
        (m, events)
    }

    #[test]
    fn weighted_sections_accumulate() {
        let clock = Arc::new(ManualClock::default());
        let (m, events) = model(Arc::clone(&clock));
        m.start_section("Mods", 40.0);
        m.submit_progress("a.jar", 50.0);
        assert!((m.percent() - 20.0).abs() < 1e-9);
        m.end_section("mods done");
        assert!((m.percent() - 40.0).abs() < 1e-9);
        m.start_section("Configs", 60.0);
        m.submit_progress("cfg", 200.0); // capped at 100
        assert!((m.percent() - 100.0).abs() < 1e-9);
        m.end_section("configs done");
        assert!((m.percent() - 100.0).abs() < 1e-9);
        let ev = events.lock();
        assert_eq!(ev.len(), 6);
        assert_eq!(ev[0], (0.0, "Mods".into(), String::new(), String::new()));
        assert_eq!(ev[1].2, "a.jar");
        assert_eq!(ev[2].2, "mods done");
    }

    #[test]
    fn unended_section_is_folded_weighted() {
        let clock = Arc::new(ManualClock::default());
        let (m, _events) = model(clock);
        m.start_section("A", 30.0);
        m.submit_progress("x", 50.0);
        assert!((m.percent() - 15.0).abs() < 1e-9);
        m.start_section("B", 70.0);
        // Continuous: still 15, not 50 (the Java quirk).
        assert!((m.percent() - 15.0).abs() < 1e-9);
    }

    #[test]
    fn submit_is_throttled_except_at_full_section() {
        let clock = Arc::new(ManualClock::default());
        let (m, events) = model(Arc::clone(&clock));
        m.start_section("S", 100.0);
        let base = events.lock().len();
        m.submit_progress("1", 10.0); // first fire
        clock.advance_ms(10);
        m.submit_progress("2", 10.0); // throttled
        clock.advance_ms(10);
        m.submit_progress("3", 10.0); // throttled
        assert_eq!(events.lock().len(), base + 1);
        clock.advance_ms(50);
        m.submit_progress("4", 10.0); // due
        assert_eq!(events.lock().len(), base + 2);
        assert!((events.lock().last().unwrap().0 - 40.0).abs() < 1e-9);
        m.submit_progress("5", 100.0); // full bypasses the throttle
        assert_eq!(events.lock().len(), base + 3);
        assert_eq!(events.lock().last().unwrap().2, "5");
        // Throttled submits still advance the state.
        assert!((m.percent() - 100.0).abs() < 1e-9);
    }

    #[test]
    fn set_text_fires_immediately_and_signal_complete_forces_100() {
        let clock = Arc::new(ManualClock::default());
        let (m, events) = model(clock);
        m.start_section("S", 10.0);
        m.set_curr_text("one");
        m.set_curr_text("two");
        assert_eq!(events.lock().len(), 3);
        m.signal_complete("Starting Minecraft");
        let last = events.lock().last().cloned().unwrap();
        assert_eq!(
            last,
            (
                100.0,
                "Starting Minecraft".into(),
                String::new(),
                String::new()
            )
        );
        assert_eq!(m.section_title(), "Starting Minecraft");
        assert_eq!(m.detail(), "");
    }

    pub(crate) struct StubMeter(pub String);

    impl DownloadMeter for StubMeter {
        fn reset(&self) {}
        fn set_total_file_count(&self, _count: u64) {}
        fn register_download(&self, _expected_bytes: i64) {}
        fn add_bytes(&self, _bytes: i64) {}
        fn complete_download(&self) {}
        fn complete_file(&self) {}
        fn formatted_status(&self) -> String {
            self.0.clone()
        }
        fn as_any(&self) -> &dyn Any {
            self
        }
    }

    #[test]
    fn download_status_comes_from_the_meter() {
        let events: Events = Arc::default();
        let e = Arc::clone(&events);
        let m = SectionProgress::new(move |p, t: &str, d: &str, st: &str| {
            e.lock()
                .push((p, t.to_owned(), d.to_owned(), st.to_owned()));
        })
        .with_download_meter(Arc::new(StubMeter("1 MB/s".into())));
        m.set_curr_text("x");
        assert_eq!(events.lock()[0].3, "1 MB/s");
        let meter = m.download_tracker().unwrap();
        assert!(meter.as_any().downcast_ref::<StubMeter>().is_some());
    }
}

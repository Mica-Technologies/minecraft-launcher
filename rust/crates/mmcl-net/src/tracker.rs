// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `DownloadTracker`: aggregate byte and file progress, speed and ETA for one launch.
//!
//! Every field is atomic, so download tasks update it without a lock. The ETA is file-count
//! based because many servers omit `Content-Length`.

use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use mmcl_i18n::tf;

/// Interval between speed samples.
pub const SPEED_SAMPLE_MS: i64 = 500;

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Byte, file and speed counters shared by a launch's downloads.
#[derive(Debug, Default)]
pub struct DownloadTracker {
    total_downloaded: AtomicI64,
    total_expected: AtomicI64,
    active: AtomicI64,
    completed_downloads: AtomicI64,
    completed_files: AtomicI64,
    total_files: AtomicI64,
    start_ms: AtomicI64,
    /// `f64` bits.
    speed: AtomicU64,
    last_speed_update_ms: AtomicI64,
    last_speed_bytes: AtomicI64,
}

impl DownloadTracker {
    pub fn new() -> Self {
        Self::default()
    }

    /// Clears every counter.
    pub fn reset(&self) {
        for a in [
            &self.total_downloaded,
            &self.total_expected,
            &self.active,
            &self.completed_downloads,
            &self.completed_files,
            &self.total_files,
            &self.start_ms,
            &self.last_speed_update_ms,
            &self.last_speed_bytes,
        ] {
            a.store(0, Ordering::SeqCst);
        }
        self.speed.store(0f64.to_bits(), Ordering::SeqCst);
    }

    /// The number of files the launch will process (downloaded or already verified).
    pub fn set_total_file_count(&self, n: i64) {
        self.total_files.store(n, Ordering::SeqCst);
    }

    /// Registers one download expected to deliver `expected` bytes (`<= 0` when unknown).
    pub fn register_download(&self, expected: i64) {
        self.register_download_at(expected, now_ms());
    }

    fn register_download_at(&self, expected: i64, now: i64) {
        if expected > 0 {
            self.total_expected.fetch_add(expected, Ordering::SeqCst);
        }
        if self
            .start_ms
            .compare_exchange(0, now, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok()
        {
            self.last_speed_update_ms.store(now, Ordering::SeqCst);
        }
        self.active.fetch_add(1, Ordering::SeqCst);
    }

    /// Adds `n` bytes (negative to roll back a failed attempt) and refreshes the speed.
    pub fn add_bytes(&self, n: i64) {
        self.add_bytes_at(n, now_ms());
    }

    fn add_bytes_at(&self, n: i64, now: i64) {
        let current = self.total_downloaded.fetch_add(n, Ordering::SeqCst) + n;
        let last = self.last_speed_update_ms.load(Ordering::SeqCst);
        let elapsed = now - last;
        if elapsed >= SPEED_SAMPLE_MS
            && self
                .last_speed_update_ms
                .compare_exchange(last, now, Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
        {
            let delta = current - self.last_speed_bytes.swap(current, Ordering::SeqCst);
            let instant = delta as f64 * 1000.0 / elapsed as f64;
            let old = f64::from_bits(self.speed.load(Ordering::SeqCst));
            let next = (old * 0.3 + instant * 0.7).max(0.0);
            self.speed.store(next.to_bits(), Ordering::SeqCst);
        }
    }

    /// One download finished.
    pub fn complete_download(&self) {
        self.active.fetch_sub(1, Ordering::SeqCst);
        self.completed_downloads.fetch_add(1, Ordering::SeqCst);
    }

    /// One file finished, whether or not it was downloaded.
    pub fn complete_file(&self) {
        self.completed_files.fetch_add(1, Ordering::SeqCst);
    }

    pub fn total_bytes_downloaded(&self) -> i64 {
        self.total_downloaded.load(Ordering::SeqCst)
    }

    pub fn total_bytes_expected(&self) -> i64 {
        self.total_expected.load(Ordering::SeqCst)
    }

    pub fn active_downloads(&self) -> i64 {
        self.active.load(Ordering::SeqCst)
    }

    pub fn completed_downloads(&self) -> i64 {
        self.completed_downloads.load(Ordering::SeqCst)
    }

    pub fn completed_files(&self) -> i64 {
        self.completed_files.load(Ordering::SeqCst)
    }

    pub fn total_files(&self) -> i64 {
        self.total_files.load(Ordering::SeqCst)
    }

    /// Smoothed bytes per second.
    pub fn speed_bytes_per_sec(&self) -> f64 {
        f64::from_bits(self.speed.load(Ordering::SeqCst))
    }

    /// Seconds remaining, or -1 when unknown.
    pub fn eta_seconds(&self) -> i64 {
        self.eta_seconds_at(now_ms())
    }

    fn eta_seconds_at(&self, now: i64) -> i64 {
        let completed = self.completed_files();
        let total = self.total_files();
        let start = self.start_ms.load(Ordering::SeqCst);
        if completed < 2 || total <= 0 || completed >= total || start == 0 {
            return -1;
        }
        let elapsed = (now - start).max(0) as f64;
        let ms_per_file = elapsed / completed as f64;
        ((ms_per_file * (total - completed) as f64) / 1000.0) as i64
    }

    /// `"2.4 MB/s"`, or `""` below 1 B/s.
    pub fn formatted_speed(&self) -> String {
        let s = self.speed_bytes_per_sec();
        if s < 1.0 {
            String::new()
        } else {
            mmcl_util::units::bytes_per_second(s)
        }
    }

    /// `"42s remaining"`, `"3:42 remaining"`, `"1:02:03 remaining"`, or `""` when unknown.
    pub fn formatted_eta(&self) -> String {
        format_eta(self.eta_seconds())
    }

    /// `speed[ — eta][ — file count]`, or just the file count with no speed yet.
    pub fn formatted_status(&self) -> String {
        compose_status(
            &self.formatted_speed(),
            &self.formatted_eta(),
            self.completed_files(),
            self.total_files(),
        )
    }

    #[cfg(test)]
    pub(crate) fn set_speed_for_test(&self, s: f64) {
        self.speed.store(s.to_bits(), Ordering::SeqCst);
    }
}

/// Formats an ETA in seconds (negative is unknown, `""`).
pub fn format_eta(eta: i64) -> String {
    if eta < 0 {
        String::new()
    } else if eta < 60 {
        tf!("download.eta.seconds", eta.to_string())
    } else if eta < 3600 {
        tf!(
            "download.eta.remaining",
            format!("{}:{:02}", eta / 60, eta % 60)
        )
    } else {
        tf!(
            "download.eta.remaining",
            format!("{}:{:02}:{:02}", eta / 3600, (eta % 3600) / 60, eta % 60)
        )
    }
}

fn compose_status(speed: &str, eta: &str, completed: i64, total: i64) -> String {
    let file_count = if total > 0 {
        tf!(
            "download.fileCount",
            completed.to_string(),
            total.to_string()
        )
    } else {
        String::new()
    };
    if speed.is_empty() {
        return file_count;
    }
    let mut s = speed.to_string();
    if !eta.is_empty() {
        s.push_str(" \u{2014} ");
        s.push_str(eta);
    }
    if !file_count.is_empty() {
        s.push_str(" \u{2014} ");
        s.push_str(&file_count);
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    fn en() {
        mmcl_i18n::set_locale(mmcl_i18n::Locale::en_us());
    }

    #[test]
    fn registering_counts_expected_bytes_and_active_downloads() {
        let t = DownloadTracker::new();
        t.register_download_at(100, 1_000);
        t.register_download_at(-1, 1_200);
        assert_eq!(t.total_bytes_expected(), 100);
        assert_eq!(t.active_downloads(), 2);
        t.complete_download();
        assert_eq!(t.active_downloads(), 1);
        assert_eq!(t.completed_downloads(), 1);
    }

    #[test]
    fn speed_is_smoothed_and_sampled_every_500ms() {
        let t = DownloadTracker::new();
        t.register_download_at(-1, 1_000);
        t.add_bytes_at(1_000, 1_100); // too soon: no sample
        assert_eq!(t.speed_bytes_per_sec(), 0.0);
        t.add_bytes_at(0, 2_000); // 1000 bytes over 1000 ms
        assert!((t.speed_bytes_per_sec() - 700.0).abs() < 1e-9);
        t.add_bytes_at(2_000, 3_000); // 2000 B/s instant: 700*0.3 + 2000*0.7
        assert!((t.speed_bytes_per_sec() - 1_610.0).abs() < 1e-9);
    }

    #[test]
    fn negative_bytes_roll_back_the_total() {
        let t = DownloadTracker::new();
        t.add_bytes_at(500, 0);
        t.add_bytes_at(-500, 0);
        assert_eq!(t.total_bytes_downloaded(), 0);
    }

    #[test]
    fn eta_is_unknown_until_two_files_complete() {
        let t = DownloadTracker::new();
        t.set_total_file_count(10);
        t.register_download_at(-1, 1_000);
        t.complete_file();
        assert_eq!(t.eta_seconds_at(5_000), -1);
        t.complete_file();
        // 4000 ms for 2 files: 2000 ms each, 8 remaining = 16 s.
        assert_eq!(t.eta_seconds_at(5_000), 16);
        for _ in 0..8 {
            t.complete_file();
        }
        assert_eq!(t.eta_seconds_at(5_000), -1);
    }

    #[test]
    fn eta_and_status_formatting() {
        en();
        assert_eq!(format_eta(-1), "");
        assert_eq!(format_eta(42), "42s remaining");
        assert_eq!(format_eta(222), "3:42 remaining");
        assert_eq!(format_eta(3_723), "1:02:03 remaining");
        assert_eq!(compose_status("", "", 3, 10), "3/10 files");
        assert_eq!(compose_status("", "", 0, 0), "");
        assert_eq!(
            compose_status("2.4 MB/s", "3:42 remaining", 3, 10),
            "2.4 MB/s \u{2014} 3:42 remaining \u{2014} 3/10 files"
        );
        assert_eq!(compose_status("2.4 MB/s", "", 0, 0), "2.4 MB/s");
    }

    #[test]
    fn formatted_speed_is_empty_below_one_byte_per_second() {
        en();
        let t = DownloadTracker::new();
        assert_eq!(t.formatted_speed(), "");
        t.set_speed_for_test(2.4 * 1024.0 * 1024.0);
        assert_eq!(t.formatted_speed(), "2.4 MB/s");
    }
}

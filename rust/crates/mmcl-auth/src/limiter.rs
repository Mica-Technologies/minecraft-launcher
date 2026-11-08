// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launcher-wide rate limit and backoff for Microsoft calls (all accounts and interactive
//! sign-ins together). Callers queue behind one async mutex that is held while waiting, as the
//! Java `static synchronized` method did.

use std::sync::atomic::{AtomicU32, Ordering};
use std::time::Duration;

use mmcl_i18n::{t, tf};
use tokio::sync::Mutex;
use tokio::time::Instant;

use crate::status::StatusHub;

/// Minimum gap between Microsoft calls.
pub const MIN_AUTH_INTERVAL: Duration = Duration::from_millis(5000);
/// Backoff cap.
pub const MAX_BACKOFF: Duration = Duration::from_millis(120_000);

/// The rate limiter.
#[derive(Debug)]
pub struct RateLimiter {
    last_attempt: Mutex<Option<Instant>>,
    failures: AtomicU32,
    min_interval: Duration,
}

impl Default for RateLimiter {
    fn default() -> Self {
        RateLimiter::new(MIN_AUTH_INTERVAL)
    }
}

/// The backoff after `failures` consecutive failures: `min(5 s * 2^min(n, 10), 120 s)`, so 10 s,
/// 20 s, 40 s, 80 s, then 120 s; none without failures.
pub fn backoff_ms(failures: u32) -> u64 {
    if failures == 0 {
        return 0;
    }
    let exp = failures.min(10);
    (5000u64 << exp).min(MAX_BACKOFF.as_millis() as u64)
}

/// How long to wait: the larger of what remains of the minimum gap and of the backoff.
pub fn wait_for(failures: u32, elapsed: Duration, min_interval: Duration) -> Duration {
    let backoff = Duration::from_millis(backoff_ms(failures));
    min_interval
        .saturating_sub(elapsed)
        .max(backoff.saturating_sub(elapsed))
}

impl RateLimiter {
    /// A limiter with a custom minimum gap (the backoff is fixed).
    pub fn new(min_interval: Duration) -> RateLimiter {
        RateLimiter {
            last_attempt: Mutex::new(None),
            failures: AtomicU32::new(0),
            min_interval,
        }
    }

    /// Consecutive failures so far.
    pub fn failures(&self) -> u32 {
        self.failures.load(Ordering::SeqCst)
    }

    /// Waits for this caller's turn, reporting the wait when it is a backoff.
    pub async fn acquire(&self, status: Option<&StatusHub>) {
        let mut last = self.last_attempt.lock().await;
        let failures = self.failures();
        let wait = match *last {
            None => Duration::ZERO,
            Some(at) => wait_for(failures, at.elapsed(), self.min_interval),
        };
        if !wait.is_zero() {
            if failures > 0 {
                let secs = wait.as_secs().to_string();
                mmcl_log::debug(&tf!("log.authManager.authBackoff", secs.as_str(), failures));
                if let Some(s) = status {
                    s.report(
                        &t!("authManager.status.signingIn"),
                        &tf!("authManager.status.waitingRetry", secs, failures + 1),
                    );
                }
            }
            tokio::time::sleep(wait).await;
        }
        *last = Some(Instant::now());
    }

    /// Counts a failure.
    pub fn record_failure(&self) {
        self.failures.fetch_add(1, Ordering::SeqCst);
    }

    /// Resets the failure count.
    pub fn record_success(&self) {
        self.failures.store(0, Ordering::SeqCst);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_doubles_from_ten_seconds_and_caps() {
        assert_eq!(backoff_ms(0), 0);
        assert_eq!(backoff_ms(1), 10_000);
        assert_eq!(backoff_ms(2), 20_000);
        assert_eq!(backoff_ms(3), 40_000);
        assert_eq!(backoff_ms(4), 80_000);
        assert_eq!(backoff_ms(5), 120_000);
        assert_eq!(backoff_ms(50), 120_000);
    }

    #[test]
    fn wait_is_the_larger_remaining_gap() {
        let min = Duration::from_secs(5);
        assert_eq!(
            wait_for(0, Duration::from_secs(1), min),
            Duration::from_secs(4)
        );
        assert_eq!(wait_for(0, Duration::from_secs(9), min), Duration::ZERO);
        assert_eq!(
            wait_for(1, Duration::from_secs(1), min),
            Duration::from_secs(9)
        );
        assert_eq!(wait_for(1, Duration::from_secs(11), min), Duration::ZERO);
    }

    #[tokio::test]
    async fn the_first_call_is_free_and_the_next_waits_for_the_gap() {
        let l = RateLimiter::new(Duration::from_millis(60));
        let t0 = std::time::Instant::now();
        l.acquire(None).await;
        assert!(t0.elapsed() < Duration::from_millis(50));
        l.acquire(None).await;
        assert!(t0.elapsed() >= Duration::from_millis(55));
        l.record_failure();
        assert_eq!(l.failures(), 1);
        l.record_success();
        assert_eq!(l.failures(), 0);
    }
}

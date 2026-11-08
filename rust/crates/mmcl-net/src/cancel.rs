// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`CancelToken`]: the explicit counterpart of Java's thread interrupt.
//!
//! Cloning shares the token. [`CancelToken::cancel`] is sticky. Async code awaits
//! [`CancelToken::cancelled`] or sleeps with [`CancelToken::sleep`]; blocking code polls
//! [`CancelToken::is_cancelled`] or sleeps with [`CancelToken::wait_timeout`].

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use parking_lot::{Condvar, Mutex};
use tokio::sync::Notify;

#[derive(Default)]
struct Inner {
    flag: AtomicBool,
    notify: Notify,
    lock: Mutex<()>,
    cond: Condvar,
}

/// A shared, sticky cancel flag.
#[derive(Clone, Default)]
pub struct CancelToken {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for CancelToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CancelToken")
            .field("cancelled", &self.is_cancelled())
            .finish()
    }
}

impl CancelToken {
    /// A fresh, not cancelled token.
    pub fn new() -> Self {
        Self::default()
    }

    /// Cancels the token and wakes every waiter. Idempotent.
    pub fn cancel(&self) {
        if !self.inner.flag.swap(true, Ordering::SeqCst) {
            self.inner.notify.notify_waiters();
            let _g = self.inner.lock.lock();
            self.inner.cond.notify_all();
        }
    }

    /// `true` once [`cancel`](Self::cancel) has run.
    pub fn is_cancelled(&self) -> bool {
        self.inner.flag.load(Ordering::SeqCst)
    }

    /// Resolves when the token is cancelled (at once if it already is).
    pub async fn cancelled(&self) {
        loop {
            let notified = self.inner.notify.notified();
            tokio::pin!(notified);
            // Register before checking, so a cancel between the check and the await is not lost.
            notified.as_mut().enable();
            if self.is_cancelled() {
                return;
            }
            notified.await;
        }
    }

    /// Sleeps for `d` unless cancelled first. Returns `true` when the sleep was cut short.
    pub async fn sleep(&self, d: Duration) -> bool {
        tokio::select! {
            _ = self.cancelled() => true,
            _ = tokio::time::sleep(d) => self.is_cancelled(),
        }
    }

    /// Blocking sleep for `d` unless cancelled first. Returns `true` when cancelled. Never call
    /// it on an async executor thread.
    pub fn wait_timeout(&self, d: Duration) -> bool {
        let deadline = std::time::Instant::now() + d;
        let mut g = self.inner.lock.lock();
        while !self.is_cancelled() {
            if self.inner.cond.wait_until(&mut g, deadline).timed_out() {
                break;
            }
        }
        self.is_cancelled()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn async_sleep_is_cut_short_by_cancel() {
        let t = CancelToken::new();
        let t2 = t.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(20)).await;
            t2.cancel();
        });
        let started = std::time::Instant::now();
        assert!(t.sleep(Duration::from_secs(30)).await);
        assert!(started.elapsed() < Duration::from_secs(10));
        // Already cancelled: returns at once.
        t.cancelled().await;
    }

    #[test]
    fn blocking_wait_is_cut_short_by_cancel() {
        let t = CancelToken::new();
        assert!(!t.wait_timeout(Duration::from_millis(5)));
        let t2 = t.clone();
        let h = std::thread::spawn(move || t2.wait_timeout(Duration::from_secs(30)));
        std::thread::sleep(Duration::from_millis(20));
        t.cancel();
        assert!(h.join().unwrap());
    }
}

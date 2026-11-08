// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The battery-saver download throttle hook.
//!
//! Power state lives in `mmcl-platform`, above this crate, so the app registers a
//! [`ThrottlePolicy`]. The download loop asks it once per chunk; the answer must be cheap
//! (the platform caches its probe for 5 s and checks "on battery" before touching config).
//! Each stream keeps its own deadline state and sleeps with the pure arithmetic in
//! `mmcl_util::throttle`.

use std::sync::Arc;
use std::time::{Duration, Instant};

use arc_swap::ArcSwapOption;
use mmcl_util::throttle::{ThrottleState, next_throttle_sleep_nanos};

/// Decides whether downloads are throttled right now.
pub trait ThrottlePolicy: Send + Sync {
    /// The per-stream cap in bytes per second while throttling, else `None`.
    fn bytes_per_second(&self) -> Option<i64>;
}

static POLICY: ArcSwapOption<Box<dyn ThrottlePolicy>> = ArcSwapOption::const_empty();

/// Registers the policy (the app passes the platform power-state manager).
pub fn set_throttle_policy(p: impl ThrottlePolicy + 'static) {
    POLICY.store(Some(Arc::new(Box::new(p))));
}

/// Removes the policy: no throttling.
pub fn clear_throttle_policy() {
    POLICY.store(None);
}

/// One stream's throttle state.
pub(crate) struct StreamThrottle {
    state: ThrottleState,
    anchor: Instant,
}

impl StreamThrottle {
    pub(crate) fn new() -> Self {
        StreamThrottle {
            state: [0, 0],
            anchor: Instant::now(),
        }
    }

    /// How long to sleep after `chunk` bytes, given the policy now.
    pub(crate) fn after_chunk(&mut self, chunk: usize) -> Option<Duration> {
        let policy = POLICY.load();
        let bps = policy.as_ref()?.bytes_per_second()?;
        // +1 so the first reading is never the "unset" value 0.
        let now = self.anchor.elapsed().as_nanos() as i64 + 1;
        let sleep = next_throttle_sleep_nanos(&mut self.state, now, chunk as i64, bps);
        (sleep > 0).then(|| Duration::from_nanos(sleep as u64))
    }
}

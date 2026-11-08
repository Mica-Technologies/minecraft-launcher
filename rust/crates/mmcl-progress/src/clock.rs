// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Injectable monotonic clock for the 50 ms progress throttles.

use std::time::{Duration, Instant};

/// A monotonic time source. `now` returns the time elapsed since an arbitrary fixed origin.
pub trait Clock: Send + Sync {
    /// Monotonic time since this clock's origin.
    fn now(&self) -> Duration;
}

/// The real clock: [`Instant`] measured from the moment the clock was created.
#[derive(Debug, Clone, Copy)]
pub struct MonotonicClock {
    origin: Instant,
}

impl MonotonicClock {
    /// A clock whose origin is now.
    pub fn new() -> Self {
        Self {
            origin: Instant::now(),
        }
    }
}

impl Default for MonotonicClock {
    fn default() -> Self {
        Self::new()
    }
}

impl Clock for MonotonicClock {
    fn now(&self) -> Duration {
        self.origin.elapsed()
    }
}

/// Throttle gate shared by [`crate::SectionProgress`] and [`crate::StepHandle`]: fires on the
/// first call, then at most once per `interval`, unless `force` is set.
#[derive(Debug, Default)]
pub(crate) struct Throttle {
    last_fire: Option<Duration>,
}

impl Throttle {
    pub(crate) fn should_fire(&mut self, now: Duration, interval: Duration, force: bool) -> bool {
        let due = match self.last_fire {
            None => true,
            Some(last) => now.saturating_sub(last) >= interval,
        };
        if due || force {
            self.last_fire = Some(now);
            true
        } else {
            false
        }
    }
}

#[cfg(test)]
pub(crate) mod test_clock {
    use super::Clock;
    use parking_lot::Mutex;
    use std::time::Duration;

    /// A manually advanced clock for throttle tests.
    #[derive(Default)]
    pub(crate) struct ManualClock {
        now: Mutex<Duration>,
    }

    impl ManualClock {
        pub(crate) fn advance_ms(&self, ms: u64) {
            *self.now.lock() += Duration::from_millis(ms);
        }
    }

    impl Clock for ManualClock {
        fn now(&self) -> Duration {
            *self.now.lock()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn throttle_fires_first_then_waits_for_the_interval_unless_forced() {
        let mut t = Throttle::default();
        let iv = Duration::from_millis(50);
        assert!(t.should_fire(Duration::from_millis(0), iv, false));
        assert!(!t.should_fire(Duration::from_millis(10), iv, false));
        assert!(t.should_fire(Duration::from_millis(20), iv, true));
        assert!(!t.should_fire(Duration::from_millis(60), iv, false));
        assert!(t.should_fire(Duration::from_millis(70), iv, false));
    }

    #[test]
    fn monotonic_clock_advances() {
        let c = MonotonicClock::new();
        let a = c.now();
        let b = c.now();
        assert!(b >= a);
    }
}

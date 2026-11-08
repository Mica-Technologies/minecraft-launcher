// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The per-backend circuit breaker (Java `RgbBackendHealth`).

/// Breaker state. `Dead` is one-way for the session.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum HealthState {
    Healthy,
    Degraded,
    Dead,
}

/// Healthy -> Degraded after 3 consecutive failures (exponential back-off between retries,
/// 30 s doubling to 30 min) -> Dead after 10. A success resets to Healthy unless Dead.
///
/// Times are milliseconds from any monotonic origin, passed in by the caller.
#[derive(Clone, Debug)]
pub struct BackendHealth {
    state: HealthState,
    consecutive_failures: u32,
    next_retry_at: u64,
    current_backoff_ms: u64,
}

impl BackendHealth {
    pub const DEGRADED_THRESHOLD: u32 = 3;
    pub const DEAD_THRESHOLD: u32 = 10;
    pub const INITIAL_BACKOFF_MS: u64 = 30_000;
    pub const MAX_BACKOFF_MS: u64 = 30 * 60_000;

    pub fn new() -> BackendHealth {
        BackendHealth {
            state: HealthState::Healthy,
            consecutive_failures: 0,
            next_retry_at: 0,
            current_backoff_ms: Self::INITIAL_BACKOFF_MS,
        }
    }

    /// Whether the backend may be called at `now_ms`.
    pub fn can_call(&self, now_ms: u64) -> bool {
        match self.state {
            HealthState::Healthy => true,
            HealthState::Degraded => now_ms >= self.next_retry_at,
            HealthState::Dead => false,
        }
    }

    /// A call succeeded.
    pub fn record_success(&mut self) {
        if self.state == HealthState::Dead {
            return;
        }
        self.consecutive_failures = 0;
        self.current_backoff_ms = Self::INITIAL_BACKOFF_MS;
        self.state = HealthState::Healthy;
    }

    /// A call failed at `now_ms`.
    pub fn record_failure(&mut self, now_ms: u64) {
        self.consecutive_failures = self.consecutive_failures.saturating_add(1);
        if self.consecutive_failures >= Self::DEAD_THRESHOLD {
            self.state = HealthState::Dead;
            return;
        }
        if self.consecutive_failures >= Self::DEGRADED_THRESHOLD {
            self.state = HealthState::Degraded;
            self.next_retry_at = now_ms.saturating_add(self.current_backoff_ms);
            self.current_backoff_ms = (self.current_backoff_ms * 2).min(Self::MAX_BACKOFF_MS);
        }
    }

    pub fn state(&self) -> HealthState {
        self.state
    }

    pub fn consecutive_failures(&self) -> u32 {
        self.consecutive_failures
    }
}

impl Default for BackendHealth {
    fn default() -> Self {
        BackendHealth::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn initial_state_is_healthy_and_can_call() {
        let h = BackendHealth::new();
        assert_eq!(h.state(), HealthState::Healthy);
        assert!(h.can_call(0));
        assert_eq!(h.consecutive_failures(), 0);
    }

    #[test]
    fn two_failures_still_healthy() {
        let mut h = BackendHealth::new();
        h.record_failure(0);
        h.record_failure(1);
        assert_eq!(h.state(), HealthState::Healthy);
        assert!(h.can_call(2));
    }

    #[test]
    fn third_failure_flips_to_degraded() {
        let mut h = BackendHealth::new();
        h.record_failure(100);
        h.record_failure(200);
        h.record_failure(300);
        assert_eq!(h.state(), HealthState::Degraded);
        assert!(!h.can_call(300));
        assert!(!h.can_call(30_000));
        assert!(h.can_call(30_301));
    }

    #[test]
    fn success_from_degraded_restores_healthy() {
        let mut h = BackendHealth::new();
        for _ in 0..3 {
            h.record_failure(0);
        }
        h.record_success();
        assert_eq!(h.state(), HealthState::Healthy);
        assert_eq!(h.consecutive_failures(), 0);
        assert!(h.can_call(0));
    }

    #[test]
    fn backoff_doubles_on_each_degraded_failure() {
        let mut h = BackendHealth::new();
        let t = 0;
        h.record_failure(t);
        h.record_failure(t);
        h.record_failure(t);
        h.record_failure(t + 30_001);
        assert!(!h.can_call(t + 30_001 + 59_999));
        assert!(h.can_call(t + 30_001 + 60_001));
    }

    #[test]
    fn ten_failures_enter_dead_and_stay_there() {
        let mut h = BackendHealth::new();
        for i in 0..10 {
            h.record_failure(i);
        }
        assert_eq!(h.state(), HealthState::Dead);
        assert!(!h.can_call(0));
        assert!(!h.can_call(u64::MAX));
        h.record_success();
        assert_eq!(h.state(), HealthState::Dead);
        assert!(!h.can_call(0));
    }

    #[test]
    fn record_failure_past_dead_stays_dead() {
        let mut h = BackendHealth::new();
        for i in 0..20 {
            h.record_failure(i);
        }
        assert_eq!(h.state(), HealthState::Dead);
        assert_eq!(h.consecutive_failures(), 20);
    }
}

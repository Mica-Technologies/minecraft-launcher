// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Helpers shared by several backends: the never-succeeded drop rule and background dedup.

use std::collections::HashMap;

/// A family (device type, endpoint, device) that failed this many times in a row without ever
/// succeeding this session is dropped from rotation.
pub const NEVER_SUCCEEDED_DROP_THRESHOLD: u32 = 5;

#[derive(Default, Clone, Debug)]
struct FamilyState {
    failures: u32,
    ever_succeeded: bool,
    dropped: bool,
}

/// What [`Families::failure`] decided.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum FailureOutcome {
    /// Counted; the family stays in rotation (log a silent warning).
    Counted,
    /// This failure dropped the family for the session (log "giving up" once).
    JustDropped { failures: u32 },
    /// The family was already dropped (log nothing).
    AlreadyDropped,
}

/// Per-family bookkeeping for partial-rig tolerance.
#[derive(Default, Clone, Debug)]
pub struct Families {
    map: HashMap<String, FamilyState>,
}

impl Families {
    pub fn new() -> Families {
        Families::default()
    }

    /// Whether `family` was dropped for the session.
    pub fn is_dropped(&self, family: &str) -> bool {
        self.map.get(family).is_some_and(|s| s.dropped)
    }

    /// Records a success; `true` for the first success this session (log it once).
    pub fn success(&mut self, family: &str) -> bool {
        let s = self.map.entry(family.to_string()).or_default();
        s.failures = 0;
        let first = !s.ever_succeeded;
        s.ever_succeeded = true;
        first
    }

    /// Records a failure and applies the drop rule (a family that worked once is never dropped:
    /// its failures should reach the breaker).
    pub fn failure(&mut self, family: &str) -> FailureOutcome {
        let s = self.map.entry(family.to_string()).or_default();
        if s.dropped {
            return FailureOutcome::AlreadyDropped;
        }
        s.failures = s.failures.saturating_add(1);
        if !s.ever_succeeded && s.failures >= NEVER_SUCCEEDED_DROP_THRESHOLD {
            s.dropped = true;
            FailureOutcome::JustDropped {
                failures: s.failures,
            }
        } else {
            FailureOutcome::Counted
        }
    }

    /// Forgets everything (shutdown).
    pub fn reset(&mut self) {
        self.map.clear();
    }
}

/// Remembers the last background pushed, for backends that paint only the background.
#[derive(Default, Clone, Copy, Debug)]
pub struct BackgroundDedup {
    last: Option<u32>,
}

impl BackgroundDedup {
    /// `true` when `packed` is what the devices already show.
    pub fn is_unchanged(&self, packed: u32) -> bool {
        self.last == Some(packed)
    }

    /// Call after a frame was delivered.
    pub fn delivered(&mut self, packed: u32) {
        self.last = Some(packed);
    }

    pub fn reset(&mut self) {
        self.last = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn never_succeeded_family_is_dropped_after_five() {
        let mut f = Families::new();
        for _ in 0..4 {
            assert_eq!(f.failure("mouse"), FailureOutcome::Counted);
        }
        assert_eq!(
            f.failure("mouse"),
            FailureOutcome::JustDropped { failures: 5 }
        );
        assert!(f.is_dropped("mouse"));
        assert_eq!(f.failure("mouse"), FailureOutcome::AlreadyDropped);
    }

    #[test]
    fn a_family_that_worked_is_never_dropped() {
        let mut f = Families::new();
        assert!(f.success("kb"));
        assert!(!f.success("kb"));
        for _ in 0..20 {
            assert_eq!(f.failure("kb"), FailureOutcome::Counted);
        }
        assert!(!f.is_dropped("kb"));
        f.reset();
        assert!(f.success("kb"), "first success is per session");
    }

    #[test]
    fn dedup() {
        let mut d = BackgroundDedup::default();
        assert!(!d.is_unchanged(5));
        d.delivered(5);
        assert!(d.is_unchanged(5));
        assert!(!d.is_unchanged(6));
        d.reset();
        assert!(!d.is_unchanged(5));
    }
}

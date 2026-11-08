// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The swappable `(section, detail)` status callback the startup progress screen shows.

use std::sync::Arc;

use parking_lot::RwLock;

/// A status callback.
pub type StatusCallback = Arc<dyn Fn(&str, &str) + Send + Sync>;

/// Holds the current status callback; reports go nowhere when none is set.
#[derive(Default)]
pub struct StatusHub {
    cb: RwLock<Option<StatusCallback>>,
}

impl std::fmt::Debug for StatusHub {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("StatusHub")
            .field("set", &self.cb.read().is_some())
            .finish()
    }
}

impl StatusHub {
    /// Sets or clears the callback.
    pub fn set(&self, cb: Option<StatusCallback>) {
        *self.cb.write() = cb;
    }

    /// Reports a status. The callback runs outside the lock.
    pub fn report(&self, section: &str, detail: &str) {
        let cb = self.cb.read().clone();
        if let Some(cb) = cb {
            cb(section, detail);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reports_reach_the_current_callback_only() {
        let hub = StatusHub::default();
        hub.report("ignored", "nobody listens");
        let seen = Arc::new(parking_lot::Mutex::new(Vec::new()));
        let s = seen.clone();
        hub.set(Some(Arc::new(move |a: &str, b: &str| {
            s.lock().push(format!("{a}/{b}"))
        })));
        hub.report("Signing In", "Checking session...");
        hub.set(None);
        hub.report("x", "y");
        assert_eq!(
            *seen.lock(),
            vec!["Signing In/Checking session...".to_string()]
        );
    }
}

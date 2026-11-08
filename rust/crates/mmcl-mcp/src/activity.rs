// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The in-memory activity log behind the Settings page (Java `McpActivityLog`).
//!
//! Bounded to [`MAX_ENTRIES`], oldest evicted, newest first on read. Every text field is
//! strictly redacted **before** storage, so the log never holds a credential in memory (the
//! Settings page is a screenshot away from a support thread). Not persisted across runs.

use std::collections::VecDeque;

use parking_lot::Mutex;

/// How many entries are kept.
pub const MAX_ENTRIES: usize = 200;

/// What happened to one call.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Decision {
    /// The content gate refused it before anyone was asked.
    Refused,
    /// Policy, the user, a timeout or a missing window said no.
    Denied,
    /// It ran and succeeded.
    Allowed,
    /// It ran and failed (a tool error, or it threw).
    Failed,
}

impl Decision {
    /// The Settings label key: `settings.mcp.activity.decision.<refused|denied|allowed|failed>`.
    pub fn label_key(self) -> &'static str {
        match self {
            Decision::Refused => "settings.mcp.activity.decision.refused",
            Decision::Denied => "settings.mcp.activity.decision.denied",
            Decision::Allowed => "settings.mcp.activity.decision.allowed",
            Decision::Failed => "settings.mcp.activity.decision.failed",
        }
    }
}

/// One recorded call.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub timestamp_ms: u64,
    pub client_name: String,
    pub tool_name: String,
    pub decision: Decision,
    pub detail: String,
}

/// The log.
#[derive(Default)]
pub struct ActivityLog {
    entries: Mutex<VecDeque<Entry>>,
}

impl ActivityLog {
    pub fn new() -> ActivityLog {
        ActivityLog::default()
    }

    /// Records a call. Ignored when the tool name is empty.
    pub fn record(
        &self,
        timestamp_ms: u64,
        client_name: &str,
        tool_name: &str,
        decision: Decision,
        detail: &str,
    ) {
        if tool_name.is_empty() {
            return;
        }
        let entry = Entry {
            timestamp_ms,
            client_name: mmcl_log::redact_strict(client_name).into_owned(),
            tool_name: mmcl_log::redact_strict(tool_name).into_owned(),
            decision,
            detail: mmcl_log::redact_strict(detail).into_owned(),
        };
        let mut q = self.entries.lock();
        q.push_back(entry);
        while q.len() > MAX_ENTRIES {
            q.pop_front();
        }
    }

    /// Every entry, newest first.
    pub fn recent(&self) -> Vec<Entry> {
        self.entries.lock().iter().rev().cloned().collect()
    }

    pub fn len(&self) -> usize {
        self.entries.lock().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn clear(&self) {
        self.entries.lock().clear();
    }
}

#[cfg(test)]
mod tests {
    //! Port of `McpActivityLogTest`.
    use super::*;

    #[test]
    fn records_newest_first() {
        let log = ActivityLog::new();
        log.record(1, "c", "a", Decision::Allowed, "");
        log.record(2, "c", "b", Decision::Denied, "not approved");
        let r = log.recent();
        assert_eq!(r[0].tool_name, "b");
        assert_eq!(r[1].tool_name, "a");
        assert_eq!(r[0].detail, "not approved");
    }

    #[test]
    fn is_bounded_and_drops_the_oldest() {
        let log = ActivityLog::new();
        for i in 0..(MAX_ENTRIES as u64 + 25) {
            log.record(i, "c", "t", Decision::Allowed, "");
        }
        assert_eq!(log.len(), MAX_ENTRIES);
        let r = log.recent();
        assert_eq!(r[0].timestamp_ms, MAX_ENTRIES as u64 + 24);
        assert_eq!(r.last().unwrap().timestamp_ms, 25);
    }

    #[test]
    fn an_empty_tool_name_is_ignored() {
        let log = ActivityLog::new();
        log.record(1, "c", "", Decision::Allowed, "");
        assert!(log.is_empty());
    }

    #[test]
    fn every_text_field_is_redacted_before_storage() {
        let log = ActivityLog::new();
        let uuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
        log.record(
            1,
            &format!("client {uuid}"),
            "t",
            Decision::Failed,
            &format!("--accessToken abc.def detail {uuid}"),
        );
        let e = &log.recent()[0];
        assert!(!e.client_name.contains(uuid));
        assert!(!e.detail.contains(uuid));
        assert!(!e.detail.contains("abc.def"));
    }

    #[test]
    fn clear_empties() {
        let log = ActivityLog::new();
        log.record(1, "c", "t", Decision::Refused, "x");
        log.clear();
        assert!(log.recent().is_empty());
    }

    #[test]
    fn decision_labels() {
        assert_eq!(
            Decision::Refused.label_key(),
            "settings.mcp.activity.decision.refused"
        );
        assert!(mmcl_i18n::english(Decision::Failed.label_key()).is_some());
    }
}

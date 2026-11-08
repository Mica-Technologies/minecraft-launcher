// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Engine-level game sessions: the ids, phases and snapshots the Running Games window, the TUI
//! and MCP render, plus the engine's own session store. The store is deliberately small: it
//! records what the launch pipeline reports (phase changes, output lines, the crash report) and
//! hands out snapshots and live log subscriptions. `mmcl-session` will sit behind it once that
//! crate lands; these types stay the front ends' view of it.

use std::collections::VecDeque;
use std::fmt;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use parking_lot::Mutex;
use serde::Serialize;
use tokio::sync::mpsc;

use crate::model::PackId;

/// A process-unique session id (counts from 1).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize)]
pub struct SessionId(pub u64);

impl fmt::Display for SessionId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

/// A session's phase.
///
/// ```text
/// Preparing --process started--> Running --exit 0--> Exited
///     |                                  \--other--> Crashed
///     \--no game: cancelled ? Cancelled : Failed
/// ```
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize)]
pub enum SessionPhase {
    Preparing,
    Running,
    Exited,
    Crashed,
    Cancelled,
    Failed,
}

impl SessionPhase {
    /// `Preparing` or `Running`.
    pub fn is_active(self) -> bool {
        matches!(self, SessionPhase::Preparing | SessionPhase::Running)
    }

    /// The localization key of the status chip.
    pub fn l10n_key(self) -> &'static str {
        match self {
            SessionPhase::Preparing => "session.status.preparing",
            SessionPhase::Running => "session.status.running",
            SessionPhase::Exited => "session.status.exited",
            SessionPhase::Crashed => "session.status.crashed",
            SessionPhase::Cancelled => "session.status.cancelled",
            SessionPhase::Failed => "session.status.failed",
        }
    }

    /// `"preparing"` / `"running"` / ... (MCP and logs).
    pub fn as_str(self) -> &'static str {
        match self {
            SessionPhase::Preparing => "preparing",
            SessionPhase::Running => "running",
            SessionPhase::Exited => "exited",
            SessionPhase::Crashed => "crashed",
            SessionPhase::Cancelled => "cancelled",
            SessionPhase::Failed => "failed",
        }
    }
}

/// State of one launch step.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum StepStateView {
    Pending,
    Running,
    Done,
    Failed,
    Skipped,
}

impl From<mmcl_progress::StepState> for StepStateView {
    fn from(s: mmcl_progress::StepState) -> Self {
        match s {
            mmcl_progress::StepState::Pending => StepStateView::Pending,
            mmcl_progress::StepState::Running => StepStateView::Running,
            mmcl_progress::StepState::Done => StepStateView::Done,
            mmcl_progress::StepState::Failed => StepStateView::Failed,
            mmcl_progress::StepState::Skipped => StepStateView::Skipped,
        }
    }
}

/// One row of the preparing view (`LaunchProgressTracker` step).
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct LaunchStepView {
    /// The step id (`launchStep.<id>` labels it).
    pub id: String,
    /// Localized label.
    pub label: String,
    pub state: StepStateView,
    /// 0..1.
    pub progress: f64,
    pub sub_text: String,
    pub error: Option<String>,
}

/// A snapshot of one session.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct SessionInfo {
    pub id: SessionId,
    pub pack: PackId,
    pub pack_name: String,
    /// `None` in server mode.
    pub account_uuid: Option<String>,
    pub account_name: Option<String>,
    pub phase: SessionPhase,
    pub cancelled: bool,
    /// When the game process started (0 while preparing).
    pub started_ms: i64,
    /// When it ended (0 while active).
    pub ended_ms: i64,
    /// `None` while active; -1 when the OS gave no code.
    pub exit_code: Option<i32>,
    pub steps: Vec<LaunchStepView>,
    pub has_crash_report: bool,
    pub log_file: Option<PathBuf>,
    /// The in-memory log lost its head (the file has everything).
    pub log_truncated: bool,
}

impl SessionInfo {
    /// Seconds since the game started (0 unless it is running or has ended).
    pub fn uptime_ms(&self, now_ms: i64) -> i64 {
        if self.started_ms <= 0 {
            return 0;
        }
        let end = if self.phase.is_active() {
            now_ms
        } else {
            self.ended_ms
        };
        (end - self.started_ms).max(0)
    }
}

/// The current log text of a session.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct LogSnapshot {
    pub text: String,
    /// The text was cut to the last `max_lines` lines.
    pub clipped: bool,
    /// The capture ended (the game exited).
    pub closed: bool,
}

/// What a live log subscription receives.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LogEvent {
    /// New redacted lines, in order (one batch per flush).
    Lines(Arc<[String]>),
    /// The capture ended.
    Closed,
}

/// A live log subscription. Dropping it unsubscribes.
#[derive(Debug)]
pub struct LogSubscription {
    /// The lines before the subscription (cut to `max_lines`).
    pub snapshot: String,
    pub clipped: bool,
    /// Every line after the snapshot, none missed and none repeated.
    pub events: mpsc::UnboundedReceiver<LogEvent>,
}

/// Formats an uptime as `h:mm:ss` / `m:ss` inside `session.uptime`.
pub fn format_uptime(ms: i64) -> String {
    let ms = ms.max(0);
    let s = (ms / 1000) % 60;
    let m = (ms / 60_000) % 60;
    let h = ms / 3_600_000;
    let clock = if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    };
    mmcl_i18n::tf!("session.uptime", clock)
}

/// The index just after the line that keeps the last `max_lines` lines (0 = keep all).
pub fn tail_start(text: &str, max_lines: usize) -> usize {
    if max_lines == 0 {
        return 0;
    }
    let body = text.strip_suffix('\n').unwrap_or(text);
    let mut seen = 0usize;
    for (i, b) in body.bytes().enumerate().rev() {
        if b == b'\n' {
            seen += 1;
            if seen == max_lines {
                return i + 1;
            }
        }
    }
    0
}

// ------------------------------------------------------------------------------------------
// The store
// ------------------------------------------------------------------------------------------

/// Ended sessions kept for the Running Games window.
pub(crate) const MAX_ENDED: usize = 20;
/// Characters of log kept in memory per session.
pub(crate) const LOG_RETAIN_CHARS: usize = 4_000_000;

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

struct Entry {
    info: SessionInfo,
    log: String,
    log_closed: bool,
    crash_report: Option<String>,
    subscribers: Vec<mpsc::UnboundedSender<LogEvent>>,
}

/// The engine's session list (oldest first).
#[derive(Default)]
pub(crate) struct SessionStore {
    entries: Mutex<VecDeque<Entry>>,
    /// Ended sessions the [`MAX_ENDED`] limit dropped, not yet reported as removed.
    pruned: Mutex<Vec<SessionId>>,
}

impl SessionStore {
    /// Admits a launch atomically (Java `GameSessionRegistry.tryRegister`): refused while the
    /// pack, or the account, already has an active session. On success the new session is
    /// recorded as preparing.
    pub(crate) fn try_begin(
        &self,
        pack: PackId,
        pack_name: String,
        account_uuid: Option<String>,
        account_name: Option<String>,
    ) -> Result<SessionId, crate::error::LaunchRefusal> {
        use crate::error::LaunchRefusal;
        let mut e = self.entries.lock();
        if let Some(s) = e
            .iter()
            .find(|x| x.info.phase.is_active() && x.info.pack == pack)
        {
            return Err(LaunchRefusal::PackAlreadyRunning {
                pack_name: s.info.pack_name.clone(),
            });
        }
        if let Some(uuid) = account_uuid.as_deref()
            && let Some(s) = e
                .iter()
                .find(|x| x.info.phase.is_active() && x.info.account_uuid.as_deref() == Some(uuid))
        {
            return Err(LaunchRefusal::AccountBusy {
                account_name: account_name.clone().unwrap_or_default(),
                pack_name: s.info.pack_name.clone(),
            });
        }
        let id = self.push(&mut e, pack, pack_name, account_uuid, account_name);
        Ok(id)
    }

    /// Records a new preparing session and returns its id (no admission check).
    #[cfg(test)]
    pub(crate) fn begin(
        &self,
        pack: PackId,
        pack_name: String,
        account_uuid: Option<String>,
        account_name: Option<String>,
    ) -> SessionId {
        let mut e = self.entries.lock();
        self.push(&mut e, pack, pack_name, account_uuid, account_name)
    }

    fn push(
        &self,
        e: &mut VecDeque<Entry>,
        pack: PackId,
        pack_name: String,
        account_uuid: Option<String>,
        account_name: Option<String>,
    ) -> SessionId {
        let id = SessionId(NEXT_ID.fetch_add(1, Ordering::Relaxed));
        let info = SessionInfo {
            id,
            pack,
            pack_name,
            account_uuid,
            account_name,
            phase: SessionPhase::Preparing,
            cancelled: false,
            started_ms: 0,
            ended_ms: 0,
            exit_code: None,
            steps: Vec::new(),
            has_crash_report: false,
            log_file: None,
            log_truncated: false,
        };
        e.push_back(Entry {
            info,
            log: String::new(),
            log_closed: false,
            crash_report: None,
            subscribers: Vec::new(),
        });
        self.record_pruned(prune(e));
        id
    }

    fn record_pruned(&self, ids: Vec<SessionId>) {
        if !ids.is_empty() {
            self.pruned.lock().extend(ids);
        }
    }

    /// The sessions the ended-session limit dropped since the last call.
    pub(crate) fn take_pruned(&self) -> Vec<SessionId> {
        std::mem::take(&mut *self.pruned.lock())
    }

    pub(crate) fn list(&self) -> Vec<SessionInfo> {
        self.entries.lock().iter().map(|e| e.info.clone()).collect()
    }

    pub(crate) fn get(&self, id: SessionId) -> Option<SessionInfo> {
        self.entries
            .lock()
            .iter()
            .find(|e| e.info.id == id)
            .map(|e| e.info.clone())
    }

    pub(crate) fn active_for_pack(&self, pack: &PackId) -> Option<SessionInfo> {
        self.entries
            .lock()
            .iter()
            .find(|e| e.info.phase.is_active() && &e.info.pack == pack)
            .map(|e| e.info.clone())
    }

    pub(crate) fn active_for_account(&self, uuid: &str) -> Option<SessionInfo> {
        self.entries
            .lock()
            .iter()
            .find(|e| e.info.phase.is_active() && e.info.account_uuid.as_deref() == Some(uuid))
            .map(|e| e.info.clone())
    }

    pub(crate) fn has_active(&self) -> bool {
        self.entries.lock().iter().any(|e| e.info.phase.is_active())
    }

    /// Applies `f` to the session; returns the new snapshot.
    pub(crate) fn update(
        &self,
        id: SessionId,
        f: impl FnOnce(&mut SessionInfo),
    ) -> Option<SessionInfo> {
        let mut e = self.entries.lock();
        let entry = e.iter_mut().find(|e| e.info.id == id)?;
        f(&mut entry.info);
        let out = entry.info.clone();
        let removed = prune(&mut e);
        drop(e);
        self.record_pruned(removed);
        Some(out)
    }

    /// Removes an ended session; `false` when it is active or unknown.
    pub(crate) fn dismiss(&self, id: SessionId) -> bool {
        let mut e = self.entries.lock();
        match e.iter().position(|x| x.info.id == id) {
            Some(i) if !e[i].info.phase.is_active() => {
                e.remove(i);
                true
            }
            _ => false,
        }
    }

    /// Appends redacted lines and forwards them to subscribers.
    pub(crate) fn push_lines(&self, id: SessionId, lines: Vec<String>) {
        if lines.is_empty() {
            return;
        }
        let mut e = self.entries.lock();
        let Some(entry) = e.iter_mut().find(|e| e.info.id == id) else {
            return;
        };
        for l in &lines {
            entry.log.push_str(l);
            entry.log.push('\n');
        }
        if entry.log.len() > LOG_RETAIN_CHARS {
            let cut = retain_cut(&entry.log, LOG_RETAIN_CHARS);
            entry.log.drain(..cut);
            entry.info.log_truncated = true;
        }
        let batch: Arc<[String]> = lines.into();
        entry
            .subscribers
            .retain(|s| s.send(LogEvent::Lines(batch.clone())).is_ok());
    }

    /// Marks the capture closed.
    pub(crate) fn close_log(&self, id: SessionId) {
        let mut e = self.entries.lock();
        if let Some(entry) = e.iter_mut().find(|e| e.info.id == id) {
            entry.log_closed = true;
            for s in entry.subscribers.drain(..) {
                let _ = s.send(LogEvent::Closed);
            }
        }
    }

    pub(crate) fn set_crash_report(&self, id: SessionId, report: Option<String>) {
        let mut e = self.entries.lock();
        if let Some(entry) = e.iter_mut().find(|e| e.info.id == id) {
            entry.info.has_crash_report = report.as_deref().is_some_and(|r| !r.trim().is_empty());
            entry.crash_report = report;
        }
    }

    pub(crate) fn crash_report(&self, id: SessionId) -> Option<String> {
        self.entries
            .lock()
            .iter()
            .find(|e| e.info.id == id)
            .and_then(|e| e.crash_report.clone())
    }

    pub(crate) fn log_snapshot(&self, id: SessionId, max_lines: usize) -> Option<LogSnapshot> {
        let e = self.entries.lock();
        let entry = e.iter().find(|e| e.info.id == id)?;
        let start = tail_start(&entry.log, max_lines);
        Some(LogSnapshot {
            text: entry.log[start..].to_string(),
            clipped: start > 0,
            closed: entry.log_closed,
        })
    }

    pub(crate) fn subscribe(&self, id: SessionId, max_lines: usize) -> Option<LogSubscription> {
        let mut e = self.entries.lock();
        let entry = e.iter_mut().find(|e| e.info.id == id)?;
        let start = tail_start(&entry.log, max_lines);
        let (tx, rx) = mpsc::unbounded_channel();
        if entry.log_closed {
            let _ = tx.send(LogEvent::Closed);
        } else {
            entry.subscribers.push(tx);
        }
        Some(LogSubscription {
            snapshot: entry.log[start..].to_string(),
            clipped: start > 0,
            events: rx,
        })
    }
}

/// Where to cut `log` so at most `keep` bytes remain, starting on a whole line. The cut is
/// always a char boundary, so multi-byte game output never panics the trim.
fn retain_cut(log: &str, keep: usize) -> usize {
    if log.len() <= keep {
        return 0;
    }
    let mut cut = log.len() - keep;
    while !log.is_char_boundary(cut) {
        cut += 1;
    }
    if log.as_bytes()[cut - 1] == b'\n' {
        // Already at the start of a line.
        return cut;
    }
    log[cut..].find('\n').map_or(log.len(), |i| cut + i + 1)
}

/// Drops the oldest ended sessions beyond [`MAX_ENDED`]; returns their ids.
fn prune(e: &mut VecDeque<Entry>) -> Vec<SessionId> {
    let mut removed = Vec::new();
    loop {
        let ended = e.iter().filter(|x| !x.info.phase.is_active()).count();
        if ended <= MAX_ENDED {
            break;
        }
        match e.iter().position(|x| !x.info.phase.is_active()) {
            Some(i) => {
                if let Some(x) = e.remove(i) {
                    removed.push(x.info.id);
                }
            }
            None => break,
        }
    }
    removed
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store_with_one() -> (SessionStore, SessionId) {
        let s = SessionStore::default();
        let id = s.begin(PackId::new("p"), "Pack".into(), Some("u".into()), None);
        (s, id)
    }

    #[test]
    fn tail_start_keeps_the_last_lines() {
        assert_eq!(tail_start("a\nb\nc\n", 0), 0);
        assert_eq!(tail_start("a\nb\nc\n", 2), 2);
        assert_eq!(&"a\nb\nc\n"[tail_start("a\nb\nc\n", 1)..], "c\n");
        assert_eq!(tail_start("a\nb\n", 5), 0);
        assert_eq!(&"a\nb\nc"[tail_start("a\nb\nc", 2)..], "b\nc");
    }

    #[test]
    fn uptime_is_zero_padded() {
        assert!(format_uptime(5 * 60_000 + 3_000).contains("5:03"));
        assert!(format_uptime(3_600_000 + 61_000).contains("1:01:01"));
        assert!(format_uptime(-5).contains("0:00"));
    }

    #[test]
    fn phases_report_activity() {
        assert!(SessionPhase::Preparing.is_active());
        assert!(SessionPhase::Running.is_active());
        for p in [
            SessionPhase::Exited,
            SessionPhase::Crashed,
            SessionPhase::Cancelled,
            SessionPhase::Failed,
        ] {
            assert!(!p.is_active());
            assert!(mmcl_i18n::contains(p.l10n_key()));
        }
    }

    #[test]
    fn active_sessions_cannot_be_dismissed() {
        let (s, id) = store_with_one();
        assert!(!s.dismiss(id));
        s.update(id, |i| i.phase = SessionPhase::Exited);
        assert!(s.dismiss(id));
        assert!(s.get(id).is_none());
    }

    #[test]
    fn a_late_subscriber_misses_nothing_and_sees_nothing_twice() {
        let (s, id) = store_with_one();
        s.push_lines(id, vec!["one".into(), "two".into()]);
        let mut sub = s.subscribe(id, 0).unwrap();
        assert_eq!(sub.snapshot, "one\ntwo\n");
        s.push_lines(id, vec!["three".into()]);
        s.close_log(id);
        match sub.events.try_recv().unwrap() {
            LogEvent::Lines(l) => assert_eq!(&*l, &["three".to_string()]),
            other => panic!("{other:?}"),
        }
        assert_eq!(sub.events.try_recv().unwrap(), LogEvent::Closed);
    }

    #[test]
    fn ended_sessions_are_pruned_but_active_ones_stay() {
        let s = SessionStore::default();
        let active = s.begin(PackId::new("a"), "A".into(), None, None);
        for i in 0..(MAX_ENDED + 5) {
            let id = s.begin(PackId::new(format!("p{i}")), "P".into(), None, None);
            s.update(id, |x| x.phase = SessionPhase::Exited);
        }
        let all = s.list();
        assert!(all.iter().any(|x| x.id == active));
        assert_eq!(
            all.iter().filter(|x| !x.phase.is_active()).count(),
            MAX_ENDED
        );
    }

    #[test]
    fn admission_is_one_game_per_pack_and_per_account() {
        use crate::error::LaunchRefusal;
        let s = SessionStore::default();
        let a = s
            .try_begin(
                PackId::new("p1"),
                "One".into(),
                Some("u1".into()),
                Some("Alex".into()),
            )
            .unwrap();
        assert!(matches!(
            s.try_begin(PackId::new("p1"), "One".into(), Some("u2".into()), None),
            Err(LaunchRefusal::PackAlreadyRunning { pack_name }) if pack_name == "One"
        ));
        assert!(matches!(
            s.try_begin(PackId::new("p2"), "Two".into(), Some("u1".into()), Some("Alex".into())),
            Err(LaunchRefusal::AccountBusy { account_name, pack_name })
                if account_name == "Alex" && pack_name == "One"
        ));
        // Server-mode sessions have no account and only the pack rule applies.
        assert!(
            s.try_begin(PackId::new("p3"), "Three".into(), None, None)
                .is_ok()
        );
        assert!(
            s.try_begin(PackId::new("p4"), "Four".into(), None, None)
                .is_ok()
        );
        s.update(a, |i| i.phase = SessionPhase::Crashed);
        assert!(
            s.try_begin(PackId::new("p1"), "One".into(), Some("u1".into()), None)
                .is_ok()
        );
    }

    #[test]
    fn trimming_a_multibyte_log_never_splits_a_character() {
        // "é" is two bytes, so a plain byte-count cut can land inside one.
        let log = "éééé\nàà\n";
        for keep in 0..log.len() + 2 {
            let cut = retain_cut(log, keep);
            assert!(log.is_char_boundary(cut), "keep {keep}");
            assert!(cut == 0 || cut == log.len() || log.as_bytes()[cut - 1] == b'\n');
        }
        assert_eq!(&log[retain_cut(log, 5)..], "àà\n");
        assert_eq!(retain_cut(log, 100), 0);
        // The store keeps working with multi-byte lines past the limit.
        let (s, id) = store_with_one();
        let line = "§".repeat(LOG_RETAIN_CHARS / 8 + 1);
        for _ in 0..6 {
            s.push_lines(id, vec![line.clone()]);
        }
        let snap = s.log_snapshot(id, 0).unwrap();
        assert!(snap.text.len() <= LOG_RETAIN_CHARS);
        assert!(s.get(id).unwrap().log_truncated);
    }

    #[test]
    fn pruned_sessions_are_reported_once() {
        let s = SessionStore::default();
        let mut ids = Vec::new();
        for i in 0..(MAX_ENDED + 2) {
            let id = s.begin(PackId::new(format!("p{i}")), "P".into(), None, None);
            s.update(id, |x| x.phase = SessionPhase::Exited);
            ids.push(id);
        }
        assert_eq!(s.take_pruned(), vec![ids[0], ids[1]]);
        assert!(s.take_pruned().is_empty());
    }

    #[test]
    fn uptime_uses_the_end_once_ended() {
        let (s, id) = store_with_one();
        let info = s
            .update(id, |i| {
                i.phase = SessionPhase::Exited;
                i.started_ms = 1_000;
                i.ended_ms = 61_000;
            })
            .unwrap();
        assert_eq!(info.uptime_ms(999_999), 60_000);
    }
}

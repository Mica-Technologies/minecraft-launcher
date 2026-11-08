// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! One launch of one pack (Java `GameSession`).
//!
//! ```text
//!             attach_process(p)           exit 0, or any exit after stop()
//! Preparing ───────────────────▶ Running ──────────────────────────────▶ Exited
//!    │                                    └─ other non-zero / unknown (-1) ─▶ Crashed
//!    └─ end_without_game(): cancelled ? Cancelled : Failed
//! ```
//!
//! A game the user stopped (Stop or Kill) exits non-zero; the Java marked it `Crashed` and
//! showed a crash notification. Here [`GameSession::stop`] records
//! [`stop_requested`](GameSession::stop_requested) and such an exit is `Exited`.

use std::fmt;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Weak};

use mmcl_modpack::GameModPack;
use mmcl_progress::LaunchProgressTracker;
use parking_lot::Mutex;
use tokio_util::sync::CancellationToken;

use crate::listeners::{ListenerId, Listeners};
use crate::log::GameLog;
use crate::process::ProcessHandle;

/// Milliseconds since the epoch (a seam for tests).
pub type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;

/// The wall clock.
pub fn system_clock() -> Clock {
    Arc::new(|| chrono::Utc::now().timestamp_millis())
}

/// Where a session is in its life.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Phase {
    /// Downloading, verifying and assembling the launch.
    Preparing,
    /// The game process is running.
    Running,
    /// The game exited normally (code 0, or after the user stopped it).
    Exited,
    /// The game exited with a non-zero (or unknown) code nobody asked for.
    Crashed,
    /// The launch was cancelled before the game started.
    Cancelled,
    /// The launch failed before the game started.
    Failed,
}

impl Phase {
    /// `Preparing` or `Running`.
    pub fn is_active(self) -> bool {
        matches!(self, Phase::Preparing | Phase::Running)
    }

    /// `"preparing"`, `"running"`, ... (logs, MCP).
    pub fn as_str(self) -> &'static str {
        match self {
            Phase::Preparing => "preparing",
            Phase::Running => "running",
            Phase::Exited => "exited",
            Phase::Crashed => "crashed",
            Phase::Cancelled => "cancelled",
            Phase::Failed => "failed",
        }
    }
}

/// A session listener: called after every phase change and every tracker / log / crash
/// report change, outside the session's lock.
pub type SessionListener = dyn Fn(&GameSession) + Send + Sync;

struct Inner {
    phase: Phase,
    process: Option<Arc<dyn ProcessHandle>>,
    started_ms: i64,
    ended_ms: i64,
    exit_code: i32,
    tracker: Option<Arc<LaunchProgressTracker>>,
    log: Option<Arc<GameLog>>,
    crash_report: Option<String>,
}

static IDS: AtomicU64 = AtomicU64::new(1);

/// One launch of one pack. Shared as `Arc<GameSession>`.
pub struct GameSession {
    id: u64,
    pack: Option<Arc<GameModPack>>,
    pack_key: String,
    pack_name: String,
    account_uuid: Option<String>,
    account_name: Option<String>,
    clock: Clock,
    cancelled: AtomicBool,
    stop_requested: AtomicBool,
    cancellation: CancellationToken,
    inner: Mutex<Inner>,
    listeners: Listeners<SessionListener>,
}

impl fmt::Debug for GameSession {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("GameSession")
            .field("id", &self.id)
            .field("pack_key", &self.pack_key)
            .field("pack_name", &self.pack_name)
            .field("account_name", &self.account_name)
            .field("phase", &self.phase())
            .finish_non_exhaustive()
    }
}

impl GameSession {
    /// A new session in `Preparing`. `account_uuid` is `None` for server launches, which never
    /// collide on accounts.
    pub fn new(
        pack: Option<Arc<GameModPack>>,
        pack_key: impl Into<String>,
        pack_name: impl Into<String>,
        account_uuid: Option<String>,
        account_name: Option<String>,
        clock: Clock,
    ) -> Arc<Self> {
        Arc::new(GameSession {
            id: IDS.fetch_add(1, Ordering::Relaxed),
            pack,
            pack_key: pack_key.into(),
            pack_name: pack_name.into(),
            account_uuid,
            account_name,
            clock,
            cancelled: AtomicBool::new(false),
            stop_requested: AtomicBool::new(false),
            cancellation: CancellationToken::new(),
            inner: Mutex::new(Inner {
                phase: Phase::Preparing,
                process: None,
                started_ms: 0,
                ended_ms: 0,
                exit_code: 0,
                tracker: None,
                log: None,
                crash_report: None,
            }),
            listeners: Listeners::default(),
        })
    }

    /// A session for `pack` on the wall clock, keyed by [`key_for`](Self::key_for).
    pub fn for_pack(
        pack: Arc<GameModPack>,
        account_uuid: Option<String>,
        account_name: Option<String>,
    ) -> Arc<Self> {
        let key = Self::key_for(&pack);
        let name = pack.name();
        Self::new(
            Some(pack),
            key,
            name,
            account_uuid,
            account_name,
            system_clock(),
        )
    }

    /// The admission key of a pack: its settings key (manifest URL or `vanilla:<version>`),
    /// else `name:<pack name>`.
    pub fn key_for(pack: &GameModPack) -> String {
        pack.settings_key()
            .unwrap_or_else(|| format!("name:{}", pack.name()))
    }

    /// Process-unique id (counts from 1).
    pub fn id(&self) -> u64 {
        self.id
    }

    pub fn pack(&self) -> Option<&Arc<GameModPack>> {
        self.pack.as_ref()
    }

    pub fn pack_key(&self) -> &str {
        &self.pack_key
    }

    pub fn pack_name(&self) -> &str {
        &self.pack_name
    }

    pub fn account_uuid(&self) -> Option<&str> {
        self.account_uuid.as_deref()
    }

    pub fn account_name(&self) -> Option<&str> {
        self.account_name.as_deref()
    }

    pub fn phase(&self) -> Phase {
        self.inner.lock().phase
    }

    /// Whether the session is `Preparing` or `Running`.
    pub fn is_active(&self) -> bool {
        self.phase().is_active()
    }

    pub fn process(&self) -> Option<Arc<dyn ProcessHandle>> {
        self.inner.lock().process.clone()
    }

    /// The exit code (0 until the game has exited; -1 when the OS gave none).
    pub fn exit_code(&self) -> i32 {
        self.inner.lock().exit_code
    }

    /// When the game process started (0 before).
    pub fn started_ms(&self) -> i64 {
        self.inner.lock().started_ms
    }

    /// When the session ended (0 while active).
    pub fn ended_ms(&self) -> i64 {
        self.inner.lock().ended_ms
    }

    pub fn tracker(&self) -> Option<Arc<LaunchProgressTracker>> {
        self.inner.lock().tracker.clone()
    }

    pub fn log(&self) -> Option<Arc<GameLog>> {
        self.inner.lock().log.clone()
    }

    pub fn crash_report(&self) -> Option<String> {
        self.inner.lock().crash_report.clone()
    }

    pub fn set_tracker(&self, tracker: Option<Arc<LaunchProgressTracker>>) {
        self.inner.lock().tracker = tracker;
        self.notify();
    }

    pub fn set_log(&self, log: Option<Arc<GameLog>>) {
        self.inner.lock().log = log;
        self.notify();
    }

    pub fn set_crash_report(&self, report: Option<String>) {
        self.inner.lock().crash_report = report;
        self.notify();
    }

    /// Whether the launch was cancelled.
    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::SeqCst)
    }

    /// Whether the user asked the running game to stop (Stop or Kill). Its exit is then
    /// `Exited`, and front ends show no crash for it.
    pub fn stop_requested(&self) -> bool {
        self.stop_requested.load(Ordering::SeqCst)
    }

    /// The token the preparation pipeline and its downloads observe; [`cancel`](Self::cancel)
    /// triggers it. Replaces the Java `bindWorker` + interrupt.
    pub fn cancellation(&self) -> CancellationToken {
        self.cancellation.clone()
    }

    /// Cancels the launch while it is still preparing (idempotent; no effect once running).
    /// The phase stays `Preparing` until [`end_without_game`](Self::end_without_game).
    pub fn cancel(&self) {
        if self.phase() == Phase::Preparing
            && self
                .cancelled
                .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
        {
            self.cancellation.cancel();
        }
    }

    /// Stops the running game: `force` kills it, otherwise it is asked to close. Records
    /// [`stop_requested`](Self::stop_requested). No effect when no game is running.
    pub fn stop(&self, force: bool) {
        let Some(p) = self.process() else {
            return;
        };
        if !p.is_alive() {
            return;
        }
        self.stop_requested.store(true, Ordering::SeqCst);
        if force {
            tracing::info!("{}", mmcl_i18n::tf!("log.session.killing", &self.pack_name));
            p.kill();
        } else {
            tracing::info!(
                "{}",
                mmcl_i18n::tf!("log.session.stopping", &self.pack_name)
            );
            p.terminate();
        }
    }

    /// Binds the started game to this session: `Running` now, then `Exited` / `Crashed` when it
    /// exits. Ignored unless the session is `Preparing`. A launch cancelled before the process
    /// is attached kills the process instead (no orphaned game window). Returns whether the
    /// process was attached.
    pub fn attach_process(self: &Arc<Self>, process: Arc<dyn ProcessHandle>) -> bool {
        if self.is_cancelled() {
            if self.phase() == Phase::Preparing {
                process.kill();
            }
            return false;
        }
        {
            let mut inner = self.inner.lock();
            if inner.phase != Phase::Preparing {
                return false;
            }
            inner.process = Some(Arc::clone(&process));
            inner.started_ms = (self.clock)();
            inner.phase = Phase::Running;
        }
        self.notify();
        let weak: Weak<GameSession> = Arc::downgrade(self);
        process.on_exit(Box::new(move |code| {
            if let Some(s) = weak.upgrade() {
                s.on_process_exit(code);
            }
        }));
        true
    }

    fn on_process_exit(&self, code: i32) {
        {
            let mut inner = self.inner.lock();
            if inner.phase != Phase::Running {
                return;
            }
            inner.exit_code = code;
            inner.ended_ms = (self.clock)();
            inner.phase = if code == 0 || self.stop_requested() {
                Phase::Exited
            } else {
                Phase::Crashed
            };
        }
        self.notify();
    }

    /// Ends a launch that never started a game: `Cancelled` if it was cancelled, else
    /// `Failed`. No effect unless `Preparing`. The launch worker calls it on every path.
    pub fn end_without_game(&self) {
        {
            let mut inner = self.inner.lock();
            if inner.phase != Phase::Preparing {
                return;
            }
            inner.ended_ms = (self.clock)();
            inner.phase = if self.is_cancelled() {
                Phase::Cancelled
            } else {
                Phase::Failed
            };
        }
        self.notify();
    }

    pub fn add_listener(&self, listener: Arc<SessionListener>) -> ListenerId {
        self.listeners.add(listener)
    }

    pub fn remove_listener(&self, id: ListenerId) -> bool {
        self.listeners.remove(id)
    }

    fn notify(&self) {
        self.listeners.for_each(|l| l(self));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{FakeProcess, session};

    #[test]
    fn a_session_runs_then_exits() {
        let s = session("p1", Some("a"));
        let p = FakeProcess::new();
        assert!(s.attach_process(p.clone()));
        assert_eq!(s.phase(), Phase::Running);
        assert_eq!(s.started_ms(), 1_000);
        p.finish(0);
        assert_eq!(s.phase(), Phase::Exited);
        assert_eq!(s.exit_code(), 0);
        assert_eq!(s.ended_ms(), 1_000);
    }

    #[test]
    fn a_non_zero_exit_is_a_crash() {
        let s = session("p1", Some("a"));
        let p = FakeProcess::new();
        s.attach_process(p.clone());
        p.finish(1);
        assert_eq!(s.phase(), Phase::Crashed);
        assert_eq!(s.exit_code(), 1);
        assert!(!s.stop_requested());
    }

    #[test]
    fn a_game_the_user_stopped_exits_rather_than_crashes() {
        let asked = session("p1", Some("a"));
        let p1 = FakeProcess::new();
        asked.attach_process(p1.clone());
        asked.stop(false);
        assert!(asked.stop_requested());
        assert_eq!(asked.phase(), Phase::Exited);
        assert_eq!(asked.exit_code(), 143);

        let killed = session("p2", Some("b"));
        let p2 = FakeProcess::new();
        killed.attach_process(p2.clone());
        killed.stop(true);
        assert_eq!(killed.phase(), Phase::Exited);
        assert_eq!(killed.exit_code(), 137);
    }

    #[test]
    fn stopping_a_game_that_is_not_running_records_nothing() {
        let s = session("p1", Some("a"));
        s.stop(false);
        assert!(!s.stop_requested());
        let p = FakeProcess::new();
        s.attach_process(p.clone());
        p.finish(1);
        s.stop(true);
        assert!(!s.stop_requested());
        assert_eq!(s.phase(), Phase::Crashed);
    }

    #[test]
    fn a_launch_that_never_starts_fails_or_is_cancelled() {
        let failed = session("p1", Some("a"));
        failed.end_without_game();
        assert_eq!(failed.phase(), Phase::Failed);

        let cancelled = session("p2", Some("b"));
        cancelled.cancel();
        cancelled.end_without_game();
        assert_eq!(cancelled.phase(), Phase::Cancelled);
    }

    #[test]
    fn end_without_game_leaves_a_running_game_alone() {
        let s = session("p1", Some("a"));
        s.attach_process(FakeProcess::new());
        s.end_without_game();
        assert_eq!(s.phase(), Phase::Running);
    }

    #[test]
    fn cancelling_one_launch_leaves_another_alone() {
        let first = session("p1", Some("a"));
        let second = session("p2", Some("b"));
        let first_token = first.cancellation();
        let second_token = second.cancellation();
        first.cancel();
        assert!(first.is_cancelled());
        assert!(first_token.is_cancelled());
        assert!(!second.is_cancelled());
        assert!(!second_token.is_cancelled());
    }

    #[test]
    fn cancel_does_nothing_once_the_game_is_running() {
        let s = session("p1", Some("a"));
        s.attach_process(FakeProcess::new());
        s.cancel();
        assert!(!s.is_cancelled());
        assert!(!s.cancellation().is_cancelled());
    }

    #[test]
    fn a_process_attached_after_cancel_is_killed() {
        let s = session("p1", Some("a"));
        s.cancel();
        let p = FakeProcess::new();
        assert!(!s.attach_process(p.clone()));
        assert!(p.destroyed_forcibly());
        assert_eq!(s.phase(), Phase::Preparing);
        s.end_without_game();
        assert_eq!(s.phase(), Phase::Cancelled);
    }

    #[test]
    fn a_second_process_is_ignored() {
        let s = session("p1", Some("a"));
        let p1 = FakeProcess::new();
        let p2 = FakeProcess::new();
        assert!(s.attach_process(p1.clone()));
        assert!(!s.attach_process(p2.clone()));
        p2.finish(1);
        assert_eq!(s.phase(), Phase::Running);
    }

    #[test]
    fn stop_asks_and_kill_forces() {
        let asked = session("p1", Some("a"));
        let p1 = FakeProcess::new();
        asked.attach_process(p1.clone());
        asked.stop(false);
        assert!(p1.destroyed());
        assert!(!p1.destroyed_forcibly());

        let killed = session("p2", Some("b"));
        let p2 = FakeProcess::new();
        killed.attach_process(p2.clone());
        killed.stop(true);
        assert!(p2.destroyed_forcibly());
    }

    #[test]
    fn listeners_hear_every_phase_change() {
        let s = session("p1", Some("a"));
        let seen = Arc::new(Mutex::new(Vec::new()));
        let sink = seen.clone();
        s.add_listener(Arc::new(move |x: &GameSession| sink.lock().push(x.phase())));
        let p = FakeProcess::new();
        s.attach_process(p.clone());
        p.finish(0);
        assert_eq!(*seen.lock(), vec![Phase::Running, Phase::Exited]);
    }

    #[test]
    fn a_panicking_listener_does_not_stop_the_others() {
        let s = session("p1", Some("a"));
        let heard = Arc::new(AtomicBool::new(false));
        s.add_listener(Arc::new(|_: &GameSession| panic!("listener failure")));
        let h = heard.clone();
        let id = s.add_listener(Arc::new(move |_: &GameSession| {
            h.store(true, Ordering::SeqCst)
        }));
        s.set_crash_report(Some("report".into()));
        assert!(heard.load(Ordering::SeqCst));
        assert_eq!(s.crash_report().as_deref(), Some("report"));
        assert!(s.remove_listener(id));
        assert!(!s.remove_listener(id));
    }

    #[test]
    fn listeners_may_read_the_session() {
        let s = session("p1", Some("a"));
        let seen = Arc::new(Mutex::new(None));
        let sink = seen.clone();
        s.add_listener(Arc::new(move |x: &GameSession| {
            *sink.lock() = Some((x.phase(), x.tracker().is_some(), x.log().is_some()));
        }));
        s.set_tracker(Some(Arc::new(LaunchProgressTracker::new(&[]))));
        assert_eq!(*seen.lock(), Some((Phase::Preparing, true, false)));
    }

    #[test]
    fn ids_are_unique() {
        let a = session("p1", None);
        let b = session("p1", None);
        assert!(a.id() >= 1);
        assert_ne!(a.id(), b.id());
    }
}

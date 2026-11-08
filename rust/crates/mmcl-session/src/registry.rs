// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Every game session of this launcher process (Java `GameSessionRegistry`): admits launches
//! atomically, tracks the active ones and keeps up to [`MAX_ENDED`] ended ones for display.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock, Weak};

use mmcl_modpack::GameModPack;
use parking_lot::Mutex;

use crate::admission::{self, Decision};
use crate::follower::RunningGameFollower;
use crate::listeners::{ListenerId, Listeners};
use crate::session::GameSession;

/// How many ended sessions are kept (the oldest beyond this are dropped).
pub const MAX_ENDED: usize = 20;

/// A registry change listener.
pub type RegistryListener = dyn Fn() + Send + Sync;

/// The session registry. One per process ([`global`](Self::global)); tests make their own.
pub struct GameSessionRegistry {
    sessions: Mutex<Vec<Arc<GameSession>>>,
    listeners: Listeners<RegistryListener>,
    one_at_a_time: AtomicBool,
}

static GLOBAL: LazyLock<Arc<GameSessionRegistry>> = LazyLock::new(GameSessionRegistry::new);

impl GameSessionRegistry {
    /// An empty registry (games may run side by side).
    pub fn new() -> Arc<Self> {
        Arc::new(GameSessionRegistry {
            sessions: Mutex::new(Vec::new()),
            listeners: Listeners::default(),
            one_at_a_time: AtomicBool::new(false),
        })
    }

    /// The process-wide registry.
    pub fn global() -> &'static Arc<GameSessionRegistry> {
        &GLOBAL
    }

    /// One game at a time (off by default; nothing turns it on today).
    pub fn set_one_at_a_time(&self, value: bool) {
        self.one_at_a_time.store(value, Ordering::SeqCst);
    }

    /// Admits and adds `session`, or returns the refusal (and does not add it). The check and
    /// the add are one atomic step. Listeners hear the addition and every later change of the
    /// session.
    pub fn try_register(self: &Arc<Self>, session: Arc<GameSession>) -> Decision {
        {
            let mut sessions = self.sessions.lock();
            let decision = admission::check(
                &session,
                &sessions,
                self.one_at_a_time.load(Ordering::SeqCst),
            );
            if !decision.ok() {
                return decision;
            }
            sessions.push(Arc::clone(&session));
            prune_ended(&mut sessions);
        }
        let weak: Weak<GameSessionRegistry> = Arc::downgrade(self);
        session.add_listener(Arc::new(move |_: &GameSession| {
            if let Some(registry) = weak.upgrade() {
                prune_ended(&mut registry.sessions.lock());
                registry.fire_changed();
            }
        }));
        self.fire_changed();
        Decision::admitted()
    }

    /// What [`try_register`](Self::try_register) would decide, without registering (for
    /// greying out Play).
    pub fn preview(&self, candidate: &GameSession) -> Decision {
        let sessions = self.sessions.lock();
        admission::check(
            candidate,
            &sessions,
            self.one_at_a_time.load(Ordering::SeqCst),
        )
    }

    /// Every kept session, oldest first.
    pub fn sessions(&self) -> Vec<Arc<GameSession>> {
        self.sessions.lock().clone()
    }

    /// The preparing and running sessions, oldest first.
    pub fn active(&self) -> Vec<Arc<GameSession>> {
        self.sessions
            .lock()
            .iter()
            .filter(|s| s.is_active())
            .cloned()
            .collect()
    }

    pub fn has_active(&self) -> bool {
        self.sessions.lock().iter().any(|s| s.is_active())
    }

    /// The most recently started running game.
    pub fn newest_running(&self) -> Option<Arc<GameSession>> {
        RunningGameFollower::pick(&self.sessions.lock())
    }

    /// The active session of the pack with admission key `pack_key`.
    pub fn active_for_pack(&self, pack_key: &str) -> Option<Arc<GameSession>> {
        self.sessions
            .lock()
            .iter()
            .find(|s| s.is_active() && s.pack_key() == pack_key)
            .cloned()
    }

    /// Whether a game of the pack with admission key `pack_key` is active. Anything that
    /// rewrites a pack's files (verify, uninstall, reinstall) must check this first.
    pub fn is_pack_active(&self, pack_key: &str) -> bool {
        self.active_for_pack(pack_key).is_some()
    }

    /// [`is_pack_active`](Self::is_pack_active) for a pack object.
    pub fn is_game_pack_active(&self, pack: &GameModPack) -> bool {
        self.is_pack_active(&GameSession::key_for(pack))
    }

    /// The session with `id`, if kept.
    pub fn get(&self, id: u64) -> Option<Arc<GameSession>> {
        self.sessions.lock().iter().find(|s| s.id() == id).cloned()
    }

    /// Removes an ended session (an active one is never removed). Returns whether it was.
    pub fn dismiss(&self, id: u64) -> bool {
        let removed = {
            let mut sessions = self.sessions.lock();
            let before = sessions.len();
            sessions.retain(|s| !(s.id() == id && !s.is_active()));
            sessions.len() != before
        };
        if removed {
            self.fire_changed();
        }
        removed
    }

    pub fn add_listener(&self, listener: Arc<RegistryListener>) -> ListenerId {
        self.listeners.add(listener)
    }

    pub fn remove_listener(&self, id: ListenerId) -> bool {
        self.listeners.remove(id)
    }

    fn fire_changed(&self) {
        self.listeners.for_each(|l| l());
    }
}

/// Drops the oldest ended sessions while more than [`MAX_ENDED`] are kept.
fn prune_ended(sessions: &mut Vec<Arc<GameSession>>) {
    let mut ended = sessions.iter().filter(|s| !s.is_active()).count();
    if ended <= MAX_ENDED {
        return;
    }
    sessions.retain(|s| {
        if ended > MAX_ENDED && !s.is_active() {
            ended -= 1;
            false
        } else {
            true
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::admission::Outcome;
    use crate::test_support::{FakeProcess, session};
    use std::sync::atomic::AtomicUsize;

    #[test]
    fn the_registry_admits_atomically() {
        let registry = GameSessionRegistry::new();
        registry.set_one_at_a_time(false);
        assert!(registry.try_register(session("p1", Some("a"))).ok());
        assert!(registry.try_register(session("p2", Some("b"))).ok());
        assert_eq!(
            registry.try_register(session("p1", Some("c"))).outcome,
            Outcome::PackAlreadyRunning
        );
        assert_eq!(
            registry.try_register(session("p3", Some("a"))).outcome,
            Outcome::AccountBusy
        );
        assert_eq!(registry.active().len(), 2);
    }

    #[test]
    fn concurrent_registrations_admit_one_per_pack() {
        let registry = GameSessionRegistry::new();
        let admitted = Arc::new(AtomicUsize::new(0));
        let threads: Vec<_> = (0..8)
            .map(|i| {
                let registry = registry.clone();
                let admitted = admitted.clone();
                std::thread::spawn(move || {
                    let account = format!("acct{i}");
                    if registry.try_register(session("same", Some(&account))).ok() {
                        admitted.fetch_add(1, Ordering::SeqCst);
                    }
                })
            })
            .collect();
        for t in threads {
            t.join().unwrap();
        }
        assert_eq!(admitted.load(Ordering::SeqCst), 1);
        assert_eq!(registry.sessions().len(), 1);
    }

    #[test]
    fn an_ended_game_frees_its_pack_and_account() {
        let registry = GameSessionRegistry::new();
        let first = session("p1", Some("a"));
        registry.try_register(first.clone());
        let p = FakeProcess::new();
        first.attach_process(p.clone());
        assert!(registry.has_active());
        assert!(Arc::ptr_eq(
            &registry.active_for_pack("p1").unwrap(),
            &first
        ));
        assert!(registry.is_pack_active("p1"));
        assert!(Arc::ptr_eq(&registry.newest_running().unwrap(), &first));

        p.finish(0);
        assert!(!registry.has_active());
        assert!(registry.active_for_pack("p1").is_none());
        assert!(registry.newest_running().is_none());
        assert!(registry.try_register(session("p1", Some("a"))).ok());
        assert_eq!(registry.sessions().len(), 2);
    }

    #[test]
    fn preview_does_not_register() {
        let registry = GameSessionRegistry::new();
        registry.try_register(session("p1", Some("a")));
        let candidate = session("p1", Some("b"));
        assert_eq!(
            registry.preview(&candidate).outcome,
            Outcome::PackAlreadyRunning
        );
        assert!(registry.preview(&session("p2", Some("b"))).ok());
        assert_eq!(registry.sessions().len(), 1);
    }

    #[test]
    fn only_ended_sessions_can_be_dismissed() {
        let registry = GameSessionRegistry::new();
        let live = session("p1", Some("a"));
        registry.try_register(live.clone());
        assert!(!registry.dismiss(live.id()));
        assert!(registry.get(live.id()).is_some());
        live.end_without_game();
        assert!(registry.dismiss(live.id()));
        assert!(registry.sessions().is_empty());
        assert!(!registry.dismiss(live.id()));
    }

    #[test]
    fn old_ended_sessions_are_pruned() {
        let registry = GameSessionRegistry::new();
        let mut first_ids = Vec::new();
        for i in 0..MAX_ENDED + 5 {
            let s = session(&format!("p{i}"), Some(&format!("a{i}")));
            first_ids.push(s.id());
            registry.try_register(s.clone());
            s.end_without_game();
        }
        let kept = registry.sessions();
        assert_eq!(kept.len(), MAX_ENDED);
        // The oldest five went.
        assert_eq!(kept[0].id(), first_ids[5]);
    }

    #[test]
    fn pruning_never_removes_an_active_session() {
        let registry = GameSessionRegistry::new();
        let live = session("live", Some("live"));
        registry.try_register(live.clone());
        for i in 0..MAX_ENDED + 3 {
            let s = session(&format!("p{i}"), Some(&format!("a{i}")));
            registry.try_register(s.clone());
            s.end_without_game();
        }
        let kept = registry.sessions();
        assert_eq!(kept.len(), MAX_ENDED + 1);
        assert!(Arc::ptr_eq(&kept[0], &live));
    }

    #[test]
    fn listeners_hear_registrations_and_phase_changes() {
        let registry = GameSessionRegistry::new();
        let changes = Arc::new(AtomicUsize::new(0));
        let c = changes.clone();
        let id = registry.add_listener(Arc::new(move || {
            c.fetch_add(1, Ordering::SeqCst);
        }));
        let s = session("p1", Some("a"));
        registry.try_register(s.clone());
        s.end_without_game();
        assert_eq!(changes.load(Ordering::SeqCst), 2);
        assert!(registry.remove_listener(id));
        registry.dismiss(s.id());
        assert_eq!(changes.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn a_listener_may_read_the_registry() {
        let registry = GameSessionRegistry::new();
        let seen = Arc::new(AtomicUsize::new(0));
        let (r, s2) = (Arc::downgrade(&registry), seen.clone());
        registry.add_listener(Arc::new(move || {
            if let Some(r) = r.upgrade() {
                s2.store(r.sessions().len(), Ordering::SeqCst);
            }
        }));
        registry.try_register(session("p1", Some("a")));
        assert_eq!(seen.load(Ordering::SeqCst), 1);
    }
}

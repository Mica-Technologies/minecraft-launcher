// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Keeps "what are you playing" surfaces (RGB keyboard, Discord presence) on the most recently
//! started running game (Java `RunningGameFollower`).
//!
//! The first game to exit does not clear the others; nothing resets before any game has run.
//! A front end wires it with [`RunningGameFollower::follow`], whose registry listener takes the
//! session snapshot **inside** the follower's lock: two changes on different threads could
//! otherwise apply their snapshots out of order and leave the surfaces showing no game while one
//! runs. The sink should hand its work to a [`LatestUpdateRunner`](crate::LatestUpdateRunner) so
//! a slow "show the game" cannot land after a later "no game".

use std::sync::{Arc, Weak};

use parking_lot::Mutex;

use crate::listeners::ListenerId;
use crate::registry::GameSessionRegistry;
use crate::session::{GameSession, Phase};

/// Where the follower sends its decisions.
pub trait Sink: Send + Sync {
    /// A game is now the one to show.
    fn show_game(&self, session: &Arc<GameSession>);
    /// The shown game ended and no other runs.
    fn show_no_game(&self);
}

/// Follows the newest running game.
pub struct RunningGameFollower {
    sink: Arc<dyn Sink>,
    shown: Mutex<Option<Arc<GameSession>>>,
}

impl RunningGameFollower {
    pub fn new(sink: Arc<dyn Sink>) -> Arc<Self> {
        Arc::new(RunningGameFollower {
            sink,
            shown: Mutex::new(None),
        })
    }

    /// Among the `Running` sessions, the one started last; ties go to the later one in the
    /// list. Preparing sessions don't count.
    pub fn pick(sessions: &[Arc<GameSession>]) -> Option<Arc<GameSession>> {
        let mut best: Option<(&Arc<GameSession>, i64)> = None;
        for s in sessions {
            if s.phase() != Phase::Running {
                continue;
            }
            let started = s.started_ms();
            if best.is_none_or(|(_, b)| started >= b) {
                best = Some((s, started));
            }
        }
        best.map(|(s, _)| Arc::clone(s))
    }

    /// Shows the newest running game of `sessions` if it changed (by identity).
    pub fn update(&self, sessions: &[Arc<GameSession>]) {
        let mut shown = self.shown.lock();
        self.apply(&mut shown, sessions);
    }

    /// [`update`](Self::update) with a snapshot taken inside the follower's lock.
    pub fn update_with(&self, snapshot: impl FnOnce() -> Vec<Arc<GameSession>>) {
        let mut shown = self.shown.lock();
        let sessions = snapshot();
        self.apply(&mut shown, &sessions);
    }

    /// Forgets what is shown and updates, so the current game is pushed again (after an
    /// in-process launcher restart reset Discord and RGB). Does nothing when no game runs.
    pub fn resync(&self, sessions: &[Arc<GameSession>]) {
        let mut shown = self.shown.lock();
        *shown = None;
        self.apply(&mut shown, sessions);
    }

    /// [`resync`](Self::resync) against `registry`, snapshot taken inside the lock.
    pub fn resync_from(&self, registry: &GameSessionRegistry) {
        let mut shown = self.shown.lock();
        *shown = None;
        let sessions = registry.sessions();
        self.apply(&mut shown, &sessions);
    }

    /// The game currently shown.
    pub fn shown(&self) -> Option<Arc<GameSession>> {
        self.shown.lock().clone()
    }

    /// Follows `registry`: every registry change updates the follower. Returns the registry
    /// listener id (remove it to stop following).
    pub fn follow(self: &Arc<Self>, registry: &Arc<GameSessionRegistry>) -> ListenerId {
        let follower: Weak<RunningGameFollower> = Arc::downgrade(self);
        let reg: Weak<GameSessionRegistry> = Arc::downgrade(registry);
        let id = registry.add_listener(Arc::new(move || {
            if let (Some(f), Some(r)) = (follower.upgrade(), reg.upgrade()) {
                f.update_with(|| r.sessions());
            }
        }));
        self.update_with(|| registry.sessions());
        id
    }

    fn apply(&self, shown: &mut Option<Arc<GameSession>>, sessions: &[Arc<GameSession>]) {
        let next = Self::pick(sessions);
        let same = match (&next, shown.as_ref()) {
            (Some(a), Some(b)) => Arc::ptr_eq(a, b),
            (None, None) => true,
            _ => false,
        };
        if same {
            return;
        }
        let previous = std::mem::replace(shown, next.clone());
        if let Some(next) = next {
            self.sink.show_game(&next);
        } else if previous.is_some() {
            self.sink.show_no_game();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::FakeProcess;
    use std::sync::atomic::{AtomicI64, Ordering};

    struct Recorder(Mutex<Vec<String>>);

    impl Sink for Recorder {
        fn show_game(&self, s: &Arc<GameSession>) {
            self.0.lock().push(format!("game:{}", s.pack_key()));
        }
        fn show_no_game(&self) {
            self.0.lock().push("menu".into());
        }
    }

    struct Fixture {
        clock: Arc<AtomicI64>,
        calls: Arc<Recorder>,
        follower: Arc<RunningGameFollower>,
    }

    impl Fixture {
        fn new() -> Self {
            let calls = Arc::new(Recorder(Mutex::new(Vec::new())));
            Fixture {
                clock: Arc::new(AtomicI64::new(1_000)),
                follower: RunningGameFollower::new(calls.clone()),
                calls,
            }
        }

        fn clock(&self) -> crate::Clock {
            let c = self.clock.clone();
            Arc::new(move || c.load(Ordering::SeqCst))
        }

        fn start(&self, pack: &str, process: &Arc<FakeProcess>) -> Arc<GameSession> {
            let s = GameSession::new(
                None,
                pack,
                pack,
                Some(format!("{pack}-account")),
                None,
                self.clock(),
            );
            self.clock.fetch_add(10, Ordering::SeqCst);
            s.attach_process(process.clone());
            s
        }

        fn calls(&self) -> Vec<String> {
            self.calls.0.lock().clone()
        }

        fn clear(&self) {
            self.calls.0.lock().clear();
        }
    }

    #[test]
    fn follows_the_most_recently_started_game() {
        let f = Fixture::new();
        let (p1, p2) = (FakeProcess::new(), FakeProcess::new());
        let first = f.start("p1", &p1);
        f.follower.update(std::slice::from_ref(&first));
        let second = f.start("p2", &p2);
        f.follower.update(&[first, second]);
        assert_eq!(f.calls(), vec!["game:p1", "game:p2"]);
    }

    #[test]
    fn the_first_game_exiting_does_not_clear_the_others() {
        let f = Fixture::new();
        let (p1, p2) = (FakeProcess::new(), FakeProcess::new());
        let first = f.start("p1", &p1);
        let second = f.start("p2", &p2);
        let all = [first, second];
        f.follower.update(&all);
        f.clear();

        p1.finish(0);
        f.follower.update(&all);
        assert!(f.calls().is_empty());

        p2.finish(0);
        f.follower.update(&all);
        assert_eq!(f.calls(), vec!["menu"]);
    }

    #[test]
    fn when_the_shown_game_exits_the_other_takes_over() {
        let f = Fixture::new();
        let (p1, p2) = (FakeProcess::new(), FakeProcess::new());
        let all = [f.start("p1", &p1), f.start("p2", &p2)];
        f.follower.update(&all);
        f.clear();
        p2.finish(0);
        f.follower.update(&all);
        assert_eq!(f.calls(), vec!["game:p1"]);
    }

    #[test]
    fn repeated_updates_do_nothing() {
        let f = Fixture::new();
        let first = [f.start("p1", &FakeProcess::new())];
        f.follower.update(&first);
        f.follower.update(&first);
        assert_eq!(f.calls(), vec!["game:p1"]);
    }

    #[test]
    fn resync_shows_the_followed_game_again() {
        let f = Fixture::new();
        let first = [f.start("p1", &FakeProcess::new())];
        f.follower.update(&first);
        f.follower.resync(&first);
        assert_eq!(f.calls(), vec!["game:p1", "game:p1"]);
    }

    #[test]
    fn resync_with_no_game_running_does_nothing() {
        let f = Fixture::new();
        f.follower.resync(&[]);
        assert!(f.calls().is_empty());
    }

    #[test]
    fn preparing_games_are_not_shown_and_nothing_resets_before_any_game_ran() {
        let f = Fixture::new();
        let preparing = GameSession::new(None, "p1", "p1", Some("a".into()), None, f.clock());
        let all = [preparing.clone()];
        f.follower.update(&all);
        preparing.end_without_game();
        f.follower.update(&all);
        assert!(f.calls().is_empty());
    }

    #[test]
    fn ties_go_to_the_later_session_in_the_list() {
        let clock: crate::Clock = Arc::new(|| 5);
        let a = GameSession::new(None, "a", "a", None, None, clock.clone());
        let b = GameSession::new(None, "b", "b", None, None, clock);
        a.attach_process(FakeProcess::new());
        b.attach_process(FakeProcess::new());
        let picked = RunningGameFollower::pick(&[a, b.clone()]).unwrap();
        assert!(Arc::ptr_eq(&picked, &b));
    }

    #[test]
    fn following_a_registry_tracks_its_games() {
        let f = Fixture::new();
        let registry = GameSessionRegistry::new();
        let id = f.follower.follow(&registry);
        let p = FakeProcess::new();
        let s = GameSession::new(None, "p1", "p1", Some("a".into()), None, f.clock());
        assert!(registry.try_register(s.clone()).ok());
        assert!(f.calls().is_empty());
        s.attach_process(p.clone());
        assert_eq!(f.calls(), vec!["game:p1"]);
        assert!(Arc::ptr_eq(&f.follower.shown().unwrap(), &s));
        f.follower.resync_from(&registry);
        assert_eq!(f.calls(), vec!["game:p1", "game:p1"]);
        p.finish(1);
        assert_eq!(f.calls(), vec!["game:p1", "game:p1", "menu"]);
        assert!(registry.remove_listener(id));
    }
}

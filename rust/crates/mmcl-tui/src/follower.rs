// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Keeps keyboard RGB and Discord presence on the newest running game (Java
//! `RunningGameFollower` + `LauncherCore.ensureRunningGameFollower`). Java never installed it in
//! TUI mode, so TUI-launched games did not show on the devices or in Discord; the port does.

use std::sync::atomic::{AtomicBool, Ordering};

use mmcl_engine::{Engine, EngineEvent, PackSummary, SessionId, SessionInfo, SessionPhase};
use tokio::sync::broadcast::error::RecvError;

/// What the follower drives.
pub trait FollowerSink: Send {
    /// `session` is now the newest running game.
    fn show_game(&mut self, session: &SessionInfo);
    /// The last running game ended.
    fn show_no_game(&mut self);
}

/// Picks the newest running session and tells the sink when that changes.
#[derive(Debug)]
pub struct RunningGameFollower<S: FollowerSink> {
    sink: S,
    shown: Option<SessionId>,
}

impl<S: FollowerSink> RunningGameFollower<S> {
    /// A follower showing nothing yet.
    pub fn new(sink: S) -> Self {
        RunningGameFollower { sink, shown: None }
    }

    /// The running session with the latest start (a tie goes to the later one in the list).
    pub fn pick(sessions: &[SessionInfo]) -> Option<&SessionInfo> {
        let mut best: Option<&SessionInfo> = None;
        for s in sessions {
            if s.phase == SessionPhase::Running && best.is_none_or(|b| s.started_ms >= b.started_ms)
            {
                best = Some(s);
            }
        }
        best
    }

    /// Re-picks from `sessions`; calls the sink only when the shown game changes, and
    /// `show_no_game` only when a game was shown before.
    pub fn update(&mut self, sessions: &[SessionInfo]) {
        let next = Self::pick(sessions);
        let next_id = next.map(|s| s.id);
        if next_id == self.shown {
            return;
        }
        let previous = self.shown;
        self.shown = next_id;
        match next {
            Some(s) => self.sink.show_game(s),
            None if previous.is_some() => self.sink.show_no_game(),
            None => {}
        }
    }

    /// Forgets what was shown and applies `sessions` again (after an in-process restart).
    pub fn resync(&mut self, sessions: &[SessionInfo]) {
        self.shown = None;
        self.update(sessions);
    }

    /// The sink (tests).
    pub fn sink(&self) -> &S {
        &self.sink
    }
}

/// Finds the pack summary of a session's pack.
pub type PackLookup = Box<dyn Fn(&SessionInfo) -> Option<PackSummary> + Send>;

/// The real sink: RGB in-game effect + Discord "in game", back to the menu state after.
/// Both bail out when switched off and contain their own failures.
pub struct PresenceSink {
    lookup: PackLookup,
}

impl PresenceSink {
    /// A sink that looks packs up through `lookup` (for the logo, vanilla flag and invite URL).
    pub fn new(lookup: impl Fn(&SessionInfo) -> Option<PackSummary> + Send + 'static) -> Self {
        PresenceSink {
            lookup: Box::new(lookup),
        }
    }
}

impl FollowerSink for PresenceSink {
    fn show_game(&mut self, session: &SessionInfo) {
        let pack = (self.lookup)(session);
        let name = pack
            .as_ref()
            .map(|p| crate::format::pack_display_name(p).to_string())
            .unwrap_or_else(|| session.pack_name.clone());
        let pack_ref = mmcl_rgb::integration::PackRef {
            key: session.pack.as_str().to_string(),
            friendly_name: name.clone(),
            logo_path: None,
            vanilla: pack.as_ref().is_some_and(|p| p.is_vanilla),
        };
        mmcl_rgb::integration::on_play_started(&pack_ref);
        let url = pack.as_ref().and_then(|p| p.manifest_url.clone());
        mmcl_discord::Presence::global().set_game_presence(&name, false, url.as_deref());
    }

    fn show_no_game(&mut self) {
        mmcl_rgb::integration::on_play_ended();
        mmcl_discord::Presence::global()
            .set_menu_presence(&mmcl_i18n::t!("discordRpc.screen.selectingPack"));
    }
}

static INSTALLED: AtomicBool = AtomicBool::new(false);

/// Installs the follower over `engine`'s sessions on its own ordered thread
/// (`mmcl-running-game-follower`), so a slow "show the game" cannot land after the "no game" of
/// a game that ended quickly. Idempotent: the second call does nothing and returns `false`.
pub fn install_running_game_follower(engine: &Engine) -> bool {
    if INSTALLED.swap(true, Ordering::SeqCst) {
        return false;
    }
    let mut rx = engine.subscribe();
    let lookup_engine = engine.clone();
    let engine = engine.clone();
    let spawned = std::thread::Builder::new()
        .name("mmcl-running-game-follower".into())
        .spawn(move || {
            let sink = PresenceSink::new(move |s| lookup_engine.pack(&s.pack));
            let mut follower = RunningGameFollower::new(sink);
            follower.update(&engine.sessions());
            loop {
                match rx.blocking_recv() {
                    Ok(
                        EngineEvent::SessionStarted(_)
                        | EngineEvent::SessionChanged(_)
                        | EngineEvent::SessionRemoved(_),
                    )
                    | Err(RecvError::Lagged(_)) => follower.update(&engine.sessions()),
                    Ok(EngineEvent::ShuttingDown) | Err(RecvError::Closed) => break,
                    Ok(_) => {}
                }
            }
        });
    if let Err(e) = spawned {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.tui.followerFailed", e.to_string())
        );
        INSTALLED.store(false, Ordering::SeqCst);
        return false;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::tests::session;

    #[derive(Default)]
    struct Recorder {
        calls: Vec<String>,
    }

    impl FollowerSink for Recorder {
        fn show_game(&mut self, s: &SessionInfo) {
            self.calls.push(format!("game {}", s.id.0));
        }
        fn show_no_game(&mut self) {
            self.calls.push("none".into());
        }
    }

    #[test]
    fn picks_the_newest_running_game() {
        let sessions = vec![
            session(1, "a", SessionPhase::Running, 100),
            session(2, "b", SessionPhase::Running, 300),
            session(3, "c", SessionPhase::Preparing, 0),
            session(4, "d", SessionPhase::Exited, 900),
        ];
        let picked = RunningGameFollower::<Recorder>::pick(&sessions).unwrap();
        assert_eq!(picked.id.0, 2);
        let tie = vec![
            session(1, "a", SessionPhase::Running, 100),
            session(2, "b", SessionPhase::Running, 100),
        ];
        assert_eq!(RunningGameFollower::<Recorder>::pick(&tie).unwrap().id.0, 2);
        assert!(RunningGameFollower::<Recorder>::pick(&[]).is_none());
    }

    #[test]
    fn tells_the_sink_only_about_changes() {
        let mut f = RunningGameFollower::new(Recorder::default());
        f.update(&[]);
        assert!(
            f.sink().calls.is_empty(),
            "no game before and after: silent"
        );
        let one = vec![session(1, "a", SessionPhase::Running, 100)];
        f.update(&one);
        f.update(&one);
        let two = vec![
            session(1, "a", SessionPhase::Running, 100),
            session(2, "b", SessionPhase::Running, 200),
        ];
        f.update(&two);
        // The newer game exits: back to the older one, not to "no game".
        f.update(&one);
        f.update(&[session(1, "a", SessionPhase::Exited, 100)]);
        f.update(&[]);
        assert_eq!(f.sink().calls, ["game 1", "game 2", "game 1", "none"]);
    }

    #[test]
    fn resync_shows_the_current_game_again() {
        let mut f = RunningGameFollower::new(Recorder::default());
        let one = vec![session(1, "a", SessionPhase::Running, 100)];
        f.update(&one);
        f.resync(&one);
        assert_eq!(f.sink().calls, ["game 1", "game 1"]);
    }
}

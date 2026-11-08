// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Keeps keyboard RGB and Discord presence on the newest running game (Java
//! `RunningGameFollower` + `LauncherCore.ensureRunningGameFollower`). Java never installed it in
//! TUI mode, so TUI-launched games did not show on the devices or in Discord; the port does.
//!
//! The engine now runs this follower itself over its sessions (`follow_running_games`), so
//! [`crate::run_engine`] relies on it. The pieces here stay for a front end that turns the
//! engine's follower off: the pure pick/update logic and [`GamePresence`] are tested.

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

/// What "show this game" sends to RGB and Discord, worked out from the session and its pack.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GamePresence {
    /// The RGB in-game effect's pack (palette key, label, logo file, vanilla flag).
    pub pack_ref: mmcl_rgb::integration::PackRef,
    /// The name Discord shows.
    pub name: String,
    /// The pack ships its own Discord RPC (`packCustomDiscordRpc`), so the launcher steps
    /// aside instead of showing "in game".
    pub custom_rpc: bool,
    /// The manifest URL, for the Discord invite button.
    pub invite_url: Option<String>,
}

impl GamePresence {
    /// The presence for `session`, using its pack summary when the lookup found one.
    pub fn of(session: &SessionInfo, pack: Option<&PackSummary>) -> GamePresence {
        let name = pack
            .map(|p| crate::format::pack_display_name(p).to_string())
            .unwrap_or_else(|| session.pack_name.clone());
        GamePresence {
            pack_ref: mmcl_rgb::integration::PackRef {
                key: session.pack.as_str().to_string(),
                friendly_name: name.clone(),
                logo_path: pack.and_then(|p| p.logo_path.clone()),
                vanilla: pack.is_some_and(|p| p.is_vanilla),
            },
            name,
            custom_rpc: pack.is_some_and(|p| p.custom_discord_rpc),
            invite_url: pack.and_then(|p| p.manifest_url.clone()),
        }
    }
}

/// The real sink: RGB in-game effect + Discord "in game", back to the menu state after.
/// Both bail out when switched off and contain their own failures.
pub struct PresenceSink {
    lookup: PackLookup,
}

impl PresenceSink {
    /// A sink that looks packs up through `lookup` (for the logo, vanilla flag, custom RPC flag
    /// and invite URL).
    pub fn new(lookup: impl Fn(&SessionInfo) -> Option<PackSummary> + Send + 'static) -> Self {
        PresenceSink {
            lookup: Box::new(lookup),
        }
    }
}

impl FollowerSink for PresenceSink {
    fn show_game(&mut self, session: &SessionInfo) {
        let pack = (self.lookup)(session);
        let p = GamePresence::of(session, pack.as_ref());
        mmcl_rgb::integration::on_play_started(&p.pack_ref);
        mmcl_discord::Presence::global().set_game_presence(
            &p.name,
            p.custom_rpc,
            p.invite_url.as_deref(),
        );
    }

    fn show_no_game(&mut self) {
        mmcl_rgb::integration::on_play_ended();
        mmcl_discord::Presence::global()
            .set_menu_presence(&mmcl_i18n::t!("discordRpc.screen.selectingPack"));
    }
}

static INSTALLED: AtomicBool = AtomicBool::new(false);

/// Installs the follower over `engine`'s sessions on its own ordered thread
/// (`mmcl-tui-game-follower`), so a slow "show the game" cannot land after the "no game" of
/// a game that ended quickly. Idempotent: the second call does nothing and returns `false`.
///
/// Only for an engine built with `EngineOptions::follow_running_games = false`: the engine's
/// own follower already drives RGB and Discord from its sessions, and installing this one as
/// well would send every presence update twice. [`crate::run_engine`] does not call it.
pub fn install_running_game_follower(engine: &Engine) -> bool {
    if INSTALLED.swap(true, Ordering::SeqCst) {
        return false;
    }
    let mut rx = engine.subscribe();
    let lookup_engine = engine.clone();
    let engine = engine.clone();
    let spawned = std::thread::Builder::new()
        .name("mmcl-tui-game-follower".into())
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
    fn presence_carries_the_logo_and_custom_rpc_flag() {
        let s = session(1, "https://example.com/a.json", SessionPhase::Running, 100);
        let mut pack = crate::format::tests::pack("Alpha", "https://example.com/a.json");
        pack.logo_path = Some(std::path::PathBuf::from("/data/logo.png"));
        pack.custom_discord_rpc = true;
        let p = GamePresence::of(&s, Some(&pack));
        assert_eq!(p.name, "Alpha");
        assert_eq!(p.pack_ref.friendly_name, "Alpha");
        assert_eq!(p.pack_ref.key, s.pack.as_str());
        assert_eq!(
            p.pack_ref.logo_path.as_deref(),
            Some(std::path::Path::new("/data/logo.png"))
        );
        assert!(!p.pack_ref.vanilla);
        assert!(p.custom_rpc);
        assert_eq!(p.invite_url.as_deref(), Some("https://example.com/a.json"));
    }

    #[test]
    fn presence_without_a_pack_falls_back_to_the_session() {
        let s = session(1, "gone", SessionPhase::Running, 100);
        let p = GamePresence::of(&s, None);
        assert_eq!(p.name, s.pack_name);
        assert_eq!(p.pack_ref.logo_path, None);
        assert!(!p.custom_rpc);
        assert!(!p.pack_ref.vanilla);
        assert_eq!(p.invite_url, None);
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

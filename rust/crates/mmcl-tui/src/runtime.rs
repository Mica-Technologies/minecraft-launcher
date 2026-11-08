// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The running-game view of the TUI (Java `TuiRuntime` + `RunningGame`).
//!
//! Java kept its own map of games with its own pipe readers and exit watcher. The port follows
//! the spec's recommendation instead: the engine's sessions own the process, the single pipe
//! drainer (the session log) and the playtime record, and this is a thin snapshot over them.

use mmcl_engine::{PackId, PackSummary, SessionInfo, SessionPhase};

/// A snapshot of the running games, in launch order.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct TuiRuntime {
    running: Vec<SessionInfo>,
}

impl TuiRuntime {
    /// The running sessions of `sessions` (phase `Running`), oldest launch first.
    pub fn from_sessions(sessions: &[SessionInfo]) -> TuiRuntime {
        let mut running: Vec<SessionInfo> = sessions
            .iter()
            .filter(|s| s.phase == SessionPhase::Running)
            .cloned()
            .collect();
        running.sort_by_key(|s| (s.started_ms, s.id));
        TuiRuntime { running }
    }

    /// How many games run.
    pub fn running_count(&self) -> usize {
        self.running.len()
    }

    /// Every running game, oldest launch first.
    pub fn running(&self) -> &[SessionInfo] {
        &self.running
    }

    /// Whether a game of `pack` runs.
    pub fn is_running(&self, pack: &PackId) -> bool {
        self.running_for(pack).is_some()
    }

    /// The running game of `pack`.
    pub fn running_for(&self, pack: &PackId) -> Option<&SessionInfo> {
        self.running.iter().find(|s| &s.pack == pack)
    }

    /// The saved playtime of every installed pack plus the elapsed time of every running game.
    pub fn live_total_playtime_ms(&self, installed: &[PackSummary], now_ms: i64) -> i64 {
        let saved: i64 = installed.iter().map(|p| p.total_play_time_ms.max(0)).sum();
        let live: i64 = self.running.iter().map(|s| s.uptime_ms(now_ms)).sum();
        saved + live
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use mmcl_engine::SessionId;

    pub(crate) fn session(id: u64, pack: &str, phase: SessionPhase, started: i64) -> SessionInfo {
        SessionInfo {
            id: SessionId(id),
            pack: PackId::new(pack),
            pack_name: format!("Pack {pack}"),
            account_uuid: Some("uuid".into()),
            account_name: Some("Steve".into()),
            phase,
            cancelled: false,
            started_ms: started,
            ended_ms: 0,
            exit_code: None,
            steps: Vec::new(),
            has_crash_report: false,
            log_file: None,
            log_truncated: false,
        }
    }

    #[test]
    fn only_running_sessions_count_in_launch_order() {
        let r = TuiRuntime::from_sessions(&[
            session(3, "c", SessionPhase::Running, 300),
            session(1, "a", SessionPhase::Running, 100),
            session(2, "b", SessionPhase::Preparing, 0),
            session(4, "d", SessionPhase::Exited, 50),
        ]);
        assert_eq!(r.running_count(), 2);
        let order: Vec<u64> = r.running().iter().map(|s| s.id.0).collect();
        assert_eq!(order, vec![1, 3]);
        assert!(r.is_running(&PackId::new("a")));
        assert!(!r.is_running(&PackId::new("b")));
        assert_eq!(r.running_for(&PackId::new("c")).unwrap().id, SessionId(3));
    }

    #[test]
    fn total_playtime_adds_live_games() {
        let mut p1 = crate::format::tests::pack("A", "a");
        p1.total_play_time_ms = 60_000;
        let mut p2 = crate::format::tests::pack("B", "b");
        p2.total_play_time_ms = -5;
        let r = TuiRuntime::from_sessions(&[session(1, "a", SessionPhase::Running, 1_000)]);
        assert_eq!(r.live_total_playtime_ms(&[p1, p2], 31_000), 90_000);
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Running Games view and panes over a scripted backend: tab sync, tab closing, docking and
//! its settings, hiding, the live log, find, and the crash report.

use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;

use futures::FutureExt as _;
use futures::future::LocalBoxFuture;
use gpui::{AppContext as _, TestAppContext};
use mmcl_engine::{
    CrashAction, CrashActionResult, CrashSeverity, CrashView, EngineEvent, LogEvent, LogSnapshot,
    LogSubscription, PackId, SessionId, SessionInfo, SessionPhase,
};
use mmcl_running_games::{GamesBackend, RunningGamesView, init_with_backend};
use tokio::sync::{broadcast, mpsc};

#[derive(Default)]
struct State {
    sessions: Vec<SessionInfo>,
    logs: Vec<(SessionId, String)>,
    log_senders: Vec<(SessionId, mpsc::UnboundedSender<LogEvent>)>,
    dismissed: Vec<SessionId>,
    cancelled: Vec<SessionId>,
    docked: bool,
    dock_height: i32,
    docked_saves: Vec<bool>,
    height_saves: Vec<i32>,
    crash: Option<CrashView>,
    max_lines: i32,
}

struct Fake {
    state: RefCell<State>,
    events: broadcast::Sender<EngineEvent>,
}

impl Fake {
    fn new() -> Rc<Fake> {
        let (events, _) = broadcast::channel(64);
        Rc::new(Fake {
            state: RefCell::new(State {
                dock_height: 420,
                max_lines: 1000,
                ..Default::default()
            }),
            events,
        })
    }

    fn add(&self, info: SessionInfo) {
        self.state.borrow_mut().sessions.push(info.clone());
        let _ = self
            .events
            .send(EngineEvent::SessionStarted(Arc::new(info)));
    }

    fn change(&self, id: SessionId, f: impl FnOnce(&mut SessionInfo)) {
        let info = {
            let mut s = self.state.borrow_mut();
            let info = s.sessions.iter_mut().find(|i| i.id == id).unwrap();
            f(info);
            info.clone()
        };
        let _ = self
            .events
            .send(EngineEvent::SessionChanged(Arc::new(info)));
    }

    fn push(&self, id: SessionId, lines: &[&str]) {
        let lines: Vec<String> = lines.iter().map(|s| s.to_string()).collect();
        let mut s = self.state.borrow_mut();
        match s.logs.iter_mut().find(|(i, _)| *i == id) {
            Some((_, log)) => {
                for l in &lines {
                    log.push_str(l);
                    log.push('\n');
                }
            }
            None => {
                let mut log = String::new();
                for l in &lines {
                    log.push_str(l);
                    log.push('\n');
                }
                s.logs.push((id, log));
            }
        }
        let batch: Arc<[String]> = lines.into();
        for (sid, tx) in &s.log_senders {
            if *sid == id {
                let _ = tx.send(LogEvent::Lines(batch.clone()));
            }
        }
    }

    fn log(&self, id: SessionId) -> String {
        self.state
            .borrow()
            .logs
            .iter()
            .find(|(i, _)| *i == id)
            .map(|(_, l)| l.clone())
            .unwrap_or_default()
    }
}

impl GamesBackend for Fake {
    fn sessions(&self) -> Vec<SessionInfo> {
        self.state.borrow().sessions.clone()
    }
    fn session(&self, id: SessionId) -> Option<SessionInfo> {
        self.state
            .borrow()
            .sessions
            .iter()
            .find(|s| s.id == id)
            .cloned()
    }
    fn events(&self) -> Option<broadcast::Receiver<EngineEvent>> {
        Some(self.events.subscribe())
    }
    fn subscribe_log(&self, id: SessionId, _max_lines: usize) -> Option<LogSubscription> {
        let snapshot = self.log(id);
        let (tx, rx) = mpsc::unbounded_channel();
        self.state.borrow_mut().log_senders.push((id, tx));
        Some(LogSubscription {
            snapshot,
            clipped: false,
            events: rx,
        })
    }
    fn log_tail(&self, id: SessionId, _max_lines: usize) -> Option<LogSnapshot> {
        Some(LogSnapshot {
            text: self.log(id),
            clipped: false,
            closed: false,
        })
    }
    fn full_log(&self, id: SessionId) -> LocalBoxFuture<'static, Option<String>> {
        let log = self.log(id);
        async move { Some(log) }.boxed_local()
    }
    fn crash(&self, _id: SessionId) -> LocalBoxFuture<'static, Option<CrashView>> {
        let crash = self.state.borrow().crash.clone();
        async move { crash }.boxed_local()
    }
    fn cancel(&self, id: SessionId) {
        self.state.borrow_mut().cancelled.push(id);
    }
    fn stop(&self, _id: SessionId, _force: bool) -> LocalBoxFuture<'static, Result<(), String>> {
        async { Ok(()) }.boxed_local()
    }
    fn dismiss(&self, id: SessionId) -> bool {
        let mut s = self.state.borrow_mut();
        s.dismissed.push(id);
        s.sessions.retain(|i| i.id != id);
        true
    }
    fn crash_action(
        &self,
        _action: CrashAction,
        _pack: Option<PackId>,
    ) -> LocalBoxFuture<'static, Result<CrashActionResult, String>> {
        async { Ok(CrashActionResult::Done) }.boxed_local()
    }
    fn docked(&self) -> bool {
        self.state.borrow().docked
    }
    fn set_docked(&self, docked: bool) {
        let mut s = self.state.borrow_mut();
        s.docked = docked;
        s.docked_saves.push(docked);
    }
    fn dock_height(&self) -> i32 {
        self.state.borrow().dock_height
    }
    fn set_dock_height(&self, per_mille: i32) {
        let mut s = self.state.borrow_mut();
        s.dock_height = per_mille;
        s.height_saves.push(per_mille);
    }
    fn console_max_lines(&self) -> i32 {
        self.state.borrow().max_lines
    }
}

fn session(id: u64, phase: SessionPhase) -> SessionInfo {
    SessionInfo {
        id: SessionId(id),
        pack: PackId::new(format!("pack{id}")),
        pack_name: format!("Pack {id}"),
        account_uuid: None,
        account_name: Some("Steve".into()),
        phase,
        cancelled: false,
        started_ms: if phase == SessionPhase::Preparing {
            0
        } else {
            1_000
        },
        ended_ms: 0,
        exit_code: None,
        steps: Vec::new(),
        has_crash_report: false,
        log_file: Some(PathBuf::from("game.log")),
        log_truncated: false,
    }
}

fn install(cx: &mut TestAppContext, fake: &Rc<Fake>) -> gpui::Entity<RunningGamesView> {
    let backend: mmcl_running_games::Backend = fake.clone();
    cx.update(|cx| init_with_backend(cx, backend))
}

#[gpui::test]
fn tabs_follow_the_registry(cx: &mut TestAppContext) {
    let fake = Fake::new();
    fake.add(session(1, SessionPhase::Running));
    let view = install(cx, &fake);
    view.read_with(cx, |v, _| {
        assert_eq!(v.tabs(), [SessionId(1)]);
        assert_eq!(v.selected(), Some(SessionId(1)));
    });
    fake.add(session(2, SessionPhase::Preparing));
    cx.run_until_parked();
    view.read_with(cx, |v, _| {
        assert_eq!(v.tabs(), [SessionId(1), SessionId(2)]);
        assert_eq!(v.selected(), Some(SessionId(1)));
    });
    // A session the registry no longer lists loses its tab and its pane is disposed.
    let pane = view.read_with(cx, |v, _| v.pane(SessionId(1)).cloned().unwrap());
    fake.state
        .borrow_mut()
        .sessions
        .retain(|s| s.id != SessionId(1));
    let _ = fake.events.send(EngineEvent::SessionRemoved(SessionId(1)));
    cx.run_until_parked();
    view.read_with(cx, |v, _| {
        assert_eq!(v.tabs(), [SessionId(2)]);
        assert_eq!(v.selected(), Some(SessionId(2)));
    });
    assert!(pane.read_with(cx, |p, _| p.is_disposed()));
}

#[gpui::test]
fn an_active_tab_cannot_be_closed(cx: &mut TestAppContext) {
    let fake = Fake::new();
    fake.add(session(1, SessionPhase::Running));
    fake.add(session(2, SessionPhase::Exited));
    let view = install(cx, &fake);
    let closed = view.update(cx, |v, cx| v.close_tab(SessionId(1), cx));
    assert!(!closed);
    let closed = view.update(cx, |v, cx| v.close_tab(SessionId(2), cx));
    assert!(closed);
    assert_eq!(fake.state.borrow().dismissed, [SessionId(2)]);
    view.read_with(cx, |v, _| assert_eq!(v.tabs(), [SessionId(1)]));
}

#[gpui::test]
fn docking_saves_the_setting_and_collapsing_hides_only_when_nothing_prepares(
    cx: &mut TestAppContext,
) {
    let fake = Fake::new();
    fake.add(session(1, SessionPhase::Preparing));
    let view = install(cx, &fake);
    view.update(cx, |v, cx| v.set_docked(true, cx));
    assert_eq!(fake.state.borrow().docked_saves, [true]);
    view.read_with(cx, |v, _| {
        assert!(v.is_docked() && v.is_expanded() && v.dock_visible());
    });
    // Docking again is a no-op.
    view.update(cx, |v, cx| v.set_docked(true, cx));
    assert_eq!(fake.state.borrow().docked_saves, [true]);

    view.update(cx, |v, cx| v.hide_unless_preparing(cx));
    view.read_with(cx, |v, _| {
        assert!(v.is_expanded(), "a preparing game keeps it open")
    });
    fake.change(SessionId(1), |s| s.phase = SessionPhase::Running);
    cx.run_until_parked();
    view.update(cx, |v, cx| v.hide_unless_preparing(cx));
    view.read_with(cx, |v, _| assert!(!v.is_expanded()));
    view.update(cx, |v, cx| v.reveal(cx));
    view.read_with(cx, |v, _| assert!(v.is_expanded()));
}

#[gpui::test]
fn the_dock_takes_no_room_without_games(cx: &mut TestAppContext) {
    let fake = Fake::new();
    fake.state.borrow_mut().docked = true;
    let view = install(cx, &fake);
    view.read_with(cx, |v, _| assert!(v.is_docked() && !v.dock_visible()));
    let dock = cx.update(|cx| mmcl_running_games::dock_host(cx)).unwrap();
    assert_eq!(
        dock.read_with(cx, |d, cx| d.current_height(1000.0, cx)),
        0.0
    );
    fake.add(session(1, SessionPhase::Running));
    cx.run_until_parked();
    let h = dock.read_with(cx, |d, cx| d.current_height(1000.0, cx));
    assert!(h >= 420.0, "{h}");
    view.update(cx, |v, cx| v.set_expanded(false, cx));
    let h = dock.read_with(cx, |d, cx| d.current_height(1000.0, cx));
    assert_eq!(h, mmcl_running_games::view::HEADER_HEIGHT);
}

#[gpui::test]
fn the_saved_share_is_clamped(cx: &mut TestAppContext) {
    let fake = Fake::new();
    fake.state.borrow_mut().dock_height = 5000;
    let view = install(cx, &fake);
    view.read_with(cx, |v, _| {
        assert_eq!(v.dock_share(), mmcl_running_games::DOCK_SHARE_MAX)
    });
}

#[gpui::test]
fn the_log_streams_and_find_turns_auto_scroll_off(cx: &mut TestAppContext) {
    let fake = Fake::new();
    fake.add(session(1, SessionPhase::Running));
    fake.push(SessionId(1), &["[main/INFO] starting", "[main/WARN] slow"]);
    let view = install(cx, &fake);
    let pane = view.read_with(cx, |v, _| v.pane(SessionId(1)).cloned().unwrap());
    let text = pane.read_with(cx, |p, cx| p.log_view().read(cx).text());
    assert_eq!(text, "[main/INFO] starting\n[main/WARN] slow\n");

    fake.push(SessionId(1), &["[main/WARN] slower", "[main/INFO] done"]);
    cx.run_until_parked();
    let lines = pane.read_with(cx, |p, cx| p.log_view().read(cx).line_count());
    assert_eq!(lines, 4);

    pane.update(cx, |p, cx| p.find_text("warn", true, cx));
    pane.read_with(cx, |p, _| {
        assert!(!p.auto_scroll());
        assert_eq!(
            p.search_status().as_ref(),
            mmcl_i18n::tf!("session.search.matchCount", 1, 2)
        );
    });
    pane.update(cx, |p, cx| p.find_text("nothing here", true, cx));
    pane.read_with(cx, |p, _| {
        assert_eq!(
            p.search_status().as_ref(),
            mmcl_i18n::t!("session.search.noMatches")
        );
    });
    pane.update(cx, |p, cx| p.find_text("", true, cx));
    pane.read_with(cx, |p, _| assert!(p.search_status().is_empty()));
    pane.update(cx, |p, cx| p.set_auto_scroll(true, cx));
    pane.read_with(cx, |p, _| assert!(p.auto_scroll()));
}

#[gpui::test]
fn trimming_shows_the_truncated_notice(cx: &mut TestAppContext) {
    let fake = Fake::new();
    fake.state.borrow_mut().max_lines = 100;
    fake.add(session(1, SessionPhase::Running));
    let view = install(cx, &fake);
    let pane = view.read_with(cx, |v, _| v.pane(SessionId(1)).cloned().unwrap());
    let lines: Vec<String> = (0..200).map(|i| format!("line {i}")).collect();
    let refs: Vec<&str> = lines.iter().map(String::as_str).collect();
    fake.push(SessionId(1), &refs);
    cx.run_until_parked();
    pane.read_with(cx, |p, cx| {
        assert_eq!(p.truncated_notice(), Some(100));
        assert_eq!(p.log_view().read(cx).line_count(), 100);
    });
}

#[gpui::test]
fn a_crash_with_an_empty_log_opens_on_the_report(cx: &mut TestAppContext) {
    let fake = Fake::new();
    fake.state.borrow_mut().crash = Some(CrashView {
        category: "OutOfMemory".into(),
        severity: CrashSeverity::Critical,
        title: "Out of memory".into(),
        summary: "Give the game more memory.".into(),
        suggestions: Vec::new(),
        report: Some("---- Minecraft Crash Report ----\nboom\n".into()),
    });
    let mut s = session(1, SessionPhase::Crashed);
    s.has_crash_report = true;
    fake.add(s);
    let view = install(cx, &fake);
    cx.run_until_parked();
    let pane = view.read_with(cx, |v, _| v.pane(SessionId(1)).cloned().unwrap());
    pane.read_with(cx, |p, cx| {
        assert_eq!(
            p.diagnosis().map(|d| d.title.as_str()),
            Some("Out of memory")
        );
        assert!(p.showing_crash_report());
        assert!(p.log_view().read(cx).text().contains("Crash Report"));
    });
    pane.update(cx, |p, cx| p.toggle_crash_report(cx));
    pane.read_with(cx, |p, cx| {
        assert!(!p.showing_crash_report());
        assert_eq!(p.log_view().read(cx).line_count(), 0);
    });
}

#[gpui::test]
fn a_crash_with_a_log_stays_on_the_log(cx: &mut TestAppContext) {
    let fake = Fake::new();
    fake.state.borrow_mut().crash = Some(CrashView {
        category: "Unknown".into(),
        severity: CrashSeverity::Info,
        title: "The game crashed".into(),
        summary: String::new(),
        suggestions: Vec::new(),
        report: Some("report".into()),
    });
    let mut s = session(1, SessionPhase::Running);
    s.has_crash_report = true;
    fake.add(s);
    fake.push(SessionId(1), &["some output"]);
    let view = install(cx, &fake);
    fake.change(SessionId(1), |s| s.phase = SessionPhase::Crashed);
    cx.run_until_parked();
    let pane = view.read_with(cx, |v, _| v.pane(SessionId(1)).cloned().unwrap());
    pane.read_with(cx, |p, _| {
        assert!(p.diagnosis().is_some());
        assert!(!p.showing_crash_report());
    });
}

#[gpui::test]
fn a_launch_that_failed_before_the_game_says_so(cx: &mut TestAppContext) {
    let fake = Fake::new();
    fake.add(session(1, SessionPhase::Failed));
    let mut c = session(2, SessionPhase::Cancelled);
    c.started_ms = 0;
    fake.add(c);
    let mut f = session(1, SessionPhase::Failed);
    f.started_ms = 0;
    fake.state.borrow_mut().sessions[0] = f;
    let view = install(cx, &fake);
    let failed = view.read_with(cx, |v, _| v.pane(SessionId(1)).cloned().unwrap());
    let cancelled = view.read_with(cx, |v, _| v.pane(SessionId(2)).cloned().unwrap());
    assert_eq!(
        failed.read_with(cx, |p, cx| p.log_view().read(cx).text()),
        format!("{}\n", mmcl_i18n::t!("session.log.failed"))
    );
    assert_eq!(
        cancelled.read_with(cx, |p, cx| p.log_view().read(cx).text()),
        format!("{}\n", mmcl_i18n::t!("session.log.cancelled"))
    );
}

#[gpui::test]
fn a_setting_change_elsewhere_moves_the_view(cx: &mut TestAppContext) {
    let fake = Fake::new();
    fake.add(session(1, SessionPhase::Running));
    let view = install(cx, &fake);
    fake.state.borrow_mut().docked = true;
    let _ = fake
        .events
        .send(EngineEvent::SettingChanged("runningGamesDocked"));
    cx.run_until_parked();
    view.read_with(cx, |v, _| assert!(v.is_docked()));
    // The view followed the saved value; it did not save it again.
    assert!(fake.state.borrow().docked_saves.is_empty());
}

#[gpui::test]
fn shutdown_disposes_everything(cx: &mut TestAppContext) {
    let fake = Fake::new();
    fake.add(session(1, SessionPhase::Running));
    let view = install(cx, &fake);
    let pane = view.read_with(cx, |v, _| v.pane(SessionId(1)).cloned().unwrap());
    cx.update(mmcl_running_games::shutdown);
    assert!(pane.read_with(cx, |p, _| p.is_disposed()));
    assert!(cx.update(|cx| mmcl_running_games::dock_host(cx)).is_none());
    // Later calls are harmless no-ops.
    cx.update(|cx| mmcl_running_games::show_session(cx, SessionId(1)));
    let _ = cx.update(|cx| cx.new(|_| ()));
}

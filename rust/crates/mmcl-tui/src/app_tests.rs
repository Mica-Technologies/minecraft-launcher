// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

use std::sync::Arc;
use std::sync::mpsc::{self, Receiver};
use std::time::Duration;

use mmcl_config::{Config, ConfigStore};
use mmcl_engine::{LaunchStepView, LogEvent, LogSubscription, SessionInfo, StepStateView};
use parking_lot::Mutex;
use ratatui::Terminal;
use ratatui::backend::TestBackend;
use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use ratatui::style::Color;
use tokio::sync::mpsc::UnboundedSender;

use super::*;
use crate::format::tests::{en, pack};
use crate::host::HostEventSink;
use crate::runtime::tests::session;

struct FakeHost {
    installed: Mutex<Vec<PackSummary>>,
    available: Mutex<Option<Vec<PackSummary>>>,
    sessions: Mutex<Vec<SessionInfo>>,
    config: Config,
    calls: Mutex<Vec<String>>,
    launch_result: Mutex<Result<SessionId, String>>,
    log_senders: Mutex<Vec<(SessionId, UnboundedSender<LogEvent>)>>,
    os_dark: bool,
}

impl FakeHost {
    fn new() -> Arc<FakeHost> {
        Arc::new(FakeHost {
            installed: Mutex::new(Vec::new()),
            available: Mutex::new(None),
            sessions: Mutex::new(Vec::new()),
            config: Config::new(ConfigStore::in_memory(serde_json::Map::new())),
            calls: Mutex::new(Vec::new()),
            launch_result: Mutex::new(Ok(SessionId(1))),
            log_senders: Mutex::new(Vec::new()),
            os_dark: true,
        })
    }

    fn with_packs(packs: Vec<PackSummary>) -> Arc<FakeHost> {
        let h = FakeHost::new();
        *h.installed.lock() = packs;
        h
    }

    fn calls(&self) -> Vec<String> {
        self.calls.lock().clone()
    }

    fn send_log(&self, id: SessionId, lines: &[&str]) {
        let batch: Arc<[String]> = lines
            .iter()
            .map(|s| s.to_string())
            .collect::<Vec<_>>()
            .into();
        for (s, tx) in self.log_senders.lock().iter() {
            if *s == id {
                let _ = tx.send(LogEvent::Lines(batch.clone()));
            }
        }
    }
}

impl TuiHost for FakeHost {
    fn installed_packs(&self) -> Vec<PackSummary> {
        self.installed.lock().clone()
    }
    fn available_packs(&self) -> Option<Vec<PackSummary>> {
        self.available.lock().clone()
    }
    fn install_available(&self, pack: &PackSummary) -> Result<(), String> {
        self.calls.lock().push(format!("install {}", pack.name));
        Ok(())
    }
    fn install_by_url(&self, url: &str) -> Result<(), String> {
        self.calls.lock().push(format!("install-url {url}"));
        Err("bad url".into())
    }
    fn uninstall(&self, pack: &PackId) -> Result<(), String> {
        self.calls.lock().push(format!("uninstall {pack}"));
        self.installed.lock().retain(|p| &p.id != pack);
        Ok(())
    }
    fn launch(&self, pack: &PackId) -> Result<SessionId, String> {
        self.calls.lock().push(format!("launch {pack}"));
        self.launch_result.lock().clone()
    }
    fn cancel_launch(&self, session: SessionId) {
        self.calls.lock().push(format!("cancel {session}"));
    }
    fn sessions(&self) -> Vec<SessionInfo> {
        self.sessions.lock().clone()
    }
    fn subscribe_log(&self, id: SessionId, _max: usize) -> Option<LogSubscription> {
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
        self.log_senders.lock().push((id, tx));
        Some(LogSubscription {
            snapshot: format!("first line of {id}\nsecond line\n"),
            clipped: false,
            events: rx,
        })
    }
    fn stop_game(&self, session: SessionId) -> Result<(), String> {
        self.calls.lock().push(format!("stop {session}"));
        Ok(())
    }
    fn signed_in_name(&self) -> Option<String> {
        Some("Steve".into())
    }
    fn settings(&self) -> Settings {
        Settings::read(&self.config)
    }
    fn set(&self, setting: Setting) -> Result<(), String> {
        setting
            .apply(&self.config)
            .map(|_| ())
            .map_err(|e| e.message())
    }
    fn apply_locale(&self, tag: &str) {
        self.calls.lock().push(format!("locale {tag}"));
    }
    fn os_is_dark(&self) -> bool {
        self.os_dark
    }
    fn now_ms(&self) -> i64 {
        10_000
    }
    fn attach(&self, _sink: HostEventSink) {}
}

fn app_with(host: Arc<FakeHost>) -> (App, Receiver<Msg>) {
    let (tx, rx) = mpsc::channel();
    (App::new(host, en(), tx), rx)
}

fn key(code: KeyCode) -> KeyEvent {
    KeyEvent::new_with_kind(code, KeyModifiers::NONE, KeyEventKind::Press)
}

fn press(app: &mut App, codes: &[KeyCode]) {
    for c in codes {
        app.handle_key(key(*c));
    }
}

fn ch(c: char) -> KeyCode {
    KeyCode::Char(c)
}

/// Waits for one worker message and applies it.
fn pump(app: &mut App, rx: &Receiver<Msg>) {
    let m = rx
        .recv_timeout(Duration::from_secs(5))
        .expect("a worker message");
    app.handle_msg(m);
}

fn screen(app: &mut App, w: u16, h: u16) -> (String, ratatui::buffer::Buffer) {
    let mut t = Terminal::new(TestBackend::new(w, h)).unwrap();
    t.draw(|f| app.render(f)).unwrap();
    let buf = t.backend().buffer().clone();
    let mut s = String::new();
    for y in 0..h {
        for x in 0..w {
            s.push_str(buf[(x, y)].symbol());
        }
        s.push('\n');
    }
    (s, buf)
}

fn two_packs() -> Vec<PackSummary> {
    vec![
        pack("Alpha", "https://a/pack.json"),
        pack("Beta", "https://b/pack.json"),
    ]
}

// ---------------------------------------------------------------------------------------------
// Global keys
// ---------------------------------------------------------------------------------------------

#[test]
fn esc_on_library_is_not_consumed_and_never_quits() {
    let (mut app, _rx) = app_with(FakeHost::new());
    press(&mut app, &[KeyCode::Esc]);
    assert_eq!(app.view(), View::Library);
    assert!(!app.should_quit());
}

#[test]
fn letters_switch_views_and_esc_goes_back() {
    let (mut app, _rx) = app_with(FakeHost::new());
    press(&mut app, &[ch('b')]);
    assert_eq!(app.view(), View::Browse);
    press(&mut app, &[ch('G')]);
    assert_eq!(app.view(), View::Logs);
    press(&mut app, &[ch('s')]);
    assert_eq!(app.view(), View::Settings);
    press(&mut app, &[KeyCode::Esc]);
    assert_eq!(app.view(), View::Library);
    press(&mut app, &[ch('S'), ch('L')]);
    assert_eq!(app.view(), View::Library);
    press(&mut app, &[ch('Q')]);
    assert!(app.should_quit());
}

#[test]
fn ctrl_c_quits() {
    let (mut app, _rx) = app_with(FakeHost::new());
    app.handle_key(KeyEvent::new_with_kind(
        ch('c'),
        KeyModifiers::CONTROL,
        KeyEventKind::Press,
    ));
    assert!(app.should_quit());
}

#[test]
fn n_only_acts_in_logs() {
    let host = FakeHost::with_packs(two_packs());
    *host.sessions.lock() = vec![
        session(1, "https://a/pack.json", SessionPhase::Running, 100),
        session(2, "https://b/pack.json", SessionPhase::Running, 200),
    ];
    let (mut app, _rx) = app_with(host);
    press(&mut app, &[ch('n')]);
    assert_eq!(app.view(), View::Library);
    press(&mut app, &[ch('s'), ch('n')]);
    assert_eq!(app.view(), View::Settings);
    press(&mut app, &[ch('g')]);
    assert_eq!(app.logs_target().unwrap().as_str(), "https://a/pack.json");
    press(&mut app, &[ch('n')]);
    assert_eq!(app.logs_target().unwrap().as_str(), "https://b/pack.json");
    press(&mut app, &[ch('N')]);
    assert_eq!(app.logs_target().unwrap().as_str(), "https://a/pack.json");
}

#[test]
fn letters_are_ignored_while_a_dialog_is_open() {
    let (mut app, _rx) = app_with(FakeHost::with_packs(two_packs()));
    press(&mut app, &[KeyCode::Enter]);
    assert_eq!(app.dialogs().len(), 1);
    press(&mut app, &[ch('b'), ch('q'), KeyCode::Down]);
    assert_eq!(app.view(), View::Library);
    assert!(!app.should_quit());
    press(&mut app, &[KeyCode::Esc]);
    assert!(app.dialogs().is_empty());
    assert_eq!(app.view(), View::Library);
}

// ---------------------------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------------------------

#[test]
fn action_menu_depends_on_running_state() {
    let host = FakeHost::with_packs(two_packs());
    *host.sessions.lock() = vec![session(
        7,
        "https://b/pack.json",
        SessionPhase::Running,
        100,
    )];
    let (mut app, _rx) = app_with(host.clone());
    press(&mut app, &[KeyCode::Enter]);
    match app.dialogs().last().unwrap() {
        Dialog::Menu {
            title,
            description,
            items,
            ..
        } => {
            assert_eq!(title, "Alpha");
            assert_eq!(description, "Choose an action");
            let labels: Vec<&str> = items.iter().map(|i| i.0.as_str()).collect();
            assert_eq!(labels, ["Launch", "Uninstall", "Close"]);
        }
        d => panic!("{d:?}"),
    }
    press(&mut app, &[KeyCode::Esc, KeyCode::Down, KeyCode::Enter]);
    match app.dialogs().last().unwrap() {
        Dialog::Menu {
            description, items, ..
        } => {
            assert_eq!(description, "This pack is running.");
            let labels: Vec<&str> = items.iter().map(|i| i.0.as_str()).collect();
            assert_eq!(labels, ["View logs", "Stop game", "Close"]);
        }
        d => panic!("{d:?}"),
    }
    // View logs targets this pack.
    press(&mut app, &[KeyCode::Enter]);
    assert_eq!(app.view(), View::Logs);
    assert_eq!(app.logs_target().unwrap().as_str(), "https://b/pack.json");
}

#[test]
fn stop_game_runs_on_a_worker() {
    let host = FakeHost::with_packs(two_packs());
    *host.sessions.lock() = vec![session(
        7,
        "https://a/pack.json",
        SessionPhase::Running,
        100,
    )];
    let (mut app, _rx) = app_with(host.clone());
    press(&mut app, &[KeyCode::Enter, KeyCode::Down, KeyCode::Enter]);
    for _ in 0..100 {
        if host.calls().contains(&"stop 7".to_string()) {
            break;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    assert!(host.calls().contains(&"stop 7".to_string()));
}

#[test]
fn uninstall_confirms_then_runs_with_a_spinner() {
    let host = FakeHost::with_packs(two_packs());
    let (mut app, rx) = app_with(host.clone());
    press(
        &mut app,
        &[KeyCode::Down, KeyCode::Enter, KeyCode::Down, KeyCode::Enter],
    );
    match app.dialogs().last().unwrap() {
        Dialog::Confirm {
            body, yes_selected, ..
        } => {
            assert_eq!(body, "Uninstall Beta?\nThis removes its installed files.");
            assert!(!yes_selected, "destructive question defaults to No");
        }
        d => panic!("{d:?}"),
    }
    press(&mut app, &[KeyCode::Left, KeyCode::Enter]);
    assert!(matches!(app.dialogs().last(), Some(Dialog::Spinner { .. })));
    pump(&mut app, &rx);
    assert!(app.dialogs().is_empty());
    assert_eq!(host.calls(), ["uninstall https://b/pack.json"]);
    let (text, _) = screen(&mut app, 120, 20);
    assert!(text.contains("Alpha"));
    assert!(!text.contains("Beta"));
}

#[test]
fn empty_library_shows_an_inert_row() {
    let (mut app, _rx) = app_with(FakeHost::new());
    press(&mut app, &[KeyCode::Enter]);
    assert!(app.dialogs().is_empty());
    let (text, _) = screen(&mut app, 100, 10);
    assert!(text.contains("(no modpacks installed)"));
}

// ---------------------------------------------------------------------------------------------
// Launch
// ---------------------------------------------------------------------------------------------

fn preparing_with_steps(id: u64, pack: &str) -> SessionInfo {
    let mut s = session(id, pack, SessionPhase::Preparing, 0);
    s.steps = vec![
        LaunchStepView {
            id: "files".into(),
            label: "Downloading files".into(),
            state: StepStateView::Running,
            progress: 0.5,
            sub_text: "mod.jar".into(),
            error: None,
        },
        LaunchStepView {
            id: "start".into(),
            label: "Starting".into(),
            state: StepStateView::Pending,
            progress: 0.0,
            sub_text: String::new(),
            error: None,
        },
    ];
    s
}

#[test]
fn launch_shows_progress_until_the_game_runs() {
    let host = FakeHost::with_packs(two_packs());
    let (mut app, rx) = app_with(host.clone());
    press(&mut app, &[KeyCode::Enter, KeyCode::Enter]);
    match app.dialogs().last().unwrap() {
        Dialog::Progress { title, lines, .. } => {
            assert_eq!(title, "Launching Alpha");
            assert_eq!(lines.section, "Preparing\u{2026}");
            assert_eq!(lines.percent, "0%");
        }
        d => panic!("{d:?}"),
    }
    *host.sessions.lock() = vec![preparing_with_steps(1, "https://a/pack.json")];
    pump(&mut app, &rx);
    match app.dialogs().last().unwrap() {
        Dialog::Progress { lines, .. } => {
            assert_eq!(lines.section, "Downloading files");
            assert_eq!(lines.detail, "mod.jar");
            assert_eq!(lines.percent, "25%");
        }
        d => panic!("{d:?}"),
    }
    // A later step snapshot moves it on (launches report through session steps only).
    let mut further = preparing_with_steps(1, "https://a/pack.json");
    further.steps[0].progress = 1.0;
    further.steps[0].state = StepStateView::Done;
    further.steps[1].state = StepStateView::Running;
    *host.sessions.lock() = vec![further.clone()];
    app.handle_msg(Msg::Host(HostEvent::SessionsChanged(Some(Arc::new(
        further,
    )))));
    match app.dialogs().last().unwrap() {
        Dialog::Progress { lines, .. } => {
            assert_eq!(lines.section, "Starting");
            assert_eq!(lines.percent, "50%");
        }
        d => panic!("{d:?}"),
    }
    // Esc asks to cancel.
    press(&mut app, &[KeyCode::Esc]);
    assert!(host.calls().contains(&"cancel 1".to_string()));
    let running = session(1, "https://a/pack.json", SessionPhase::Running, 5_000);
    *host.sessions.lock() = vec![running.clone()];
    app.handle_msg(Msg::Host(HostEvent::SessionsChanged(Some(Arc::new(
        running,
    )))));
    assert!(app.dialogs().is_empty());
    let (text, _) = screen(&mut app, 120, 20);
    assert!(text.contains("\u{25CF} Alpha"));
    assert!(text.contains("1 running"));
    // A running pack is not launched twice.
    app.launch(&pack("Alpha", "https://a/pack.json"));
    assert!(app.dialogs().is_empty());
}

#[test]
fn launch_refusal_shows_the_reason() {
    let host = FakeHost::with_packs(two_packs());
    *host.launch_result.lock() = Err("No saved Microsoft account found.".into());
    let (mut app, rx) = app_with(host);
    press(&mut app, &[KeyCode::Enter, KeyCode::Enter]);
    pump(&mut app, &rx);
    match app.dialogs() {
        [Dialog::Message { title, body }] => {
            assert_eq!(title, "Launch failed");
            assert_eq!(body, "No saved Microsoft account found.");
        }
        d => panic!("{d:?}"),
    }
}

#[test]
fn a_failed_session_shows_its_failed_step() {
    let host = FakeHost::with_packs(two_packs());
    let (mut app, rx) = app_with(host.clone());
    let mut failed = preparing_with_steps(1, "https://a/pack.json");
    failed.phase = SessionPhase::Failed;
    failed.steps[0].state = StepStateView::Failed;
    failed.steps[0].error = Some("disk full".into());
    *host.sessions.lock() = vec![failed];
    press(&mut app, &[KeyCode::Enter, KeyCode::Enter]);
    pump(&mut app, &rx);
    match app.dialogs() {
        [Dialog::Message { body, .. }] => assert_eq!(body, "disk full"),
        d => panic!("{d:?}"),
    }
}

// ---------------------------------------------------------------------------------------------
// Browse
// ---------------------------------------------------------------------------------------------

#[test]
fn browse_loads_then_skips_installed_urls() {
    let host = FakeHost::with_packs(vec![pack("Alpha", "https://a/pack.json")]);
    let (mut app, rx) = app_with(host.clone());
    press(&mut app, &[ch('b')]);
    let (text, _) = screen(&mut app, 120, 12);
    // The full-width plus takes two cells, so match its parts.
    assert!(text.contains('\u{FF0B}') && text.contains("Install from URL"));
    assert!(text.contains("(loading available modpacks"));
    let mut gamma = pack("Gamma", "https://g/pack.json");
    gamma.installed = false;
    *host.available.lock() = Some(vec![pack("Alpha", "https://a/pack.json"), gamma]);
    app.handle_msg(Msg::Host(HostEvent::AvailableReady));
    let (text, _) = screen(&mut app, 120, 12);
    assert!(text.contains("Gamma"));
    assert!(!text.contains("Alpha  "));
    // Install Gamma.
    press(&mut app, &[KeyCode::Down, KeyCode::Enter]);
    match app.dialogs().last().unwrap() {
        Dialog::Confirm {
            body, yes_selected, ..
        } => {
            assert_eq!(body, "Install Gamma?");
            assert!(yes_selected);
        }
        d => panic!("{d:?}"),
    }
    press(&mut app, &[KeyCode::Enter]);
    pump(&mut app, &rx);
    assert_eq!(host.calls(), ["install Gamma"]);
    assert_eq!(app.view(), View::Browse);
}

#[test]
fn browse_says_when_everything_is_installed() {
    let host = FakeHost::with_packs(vec![pack("Alpha", "https://a/pack.json")]);
    *host.available.lock() = Some(vec![pack("Alpha", "https://a/pack.json")]);
    let (mut app, _rx) = app_with(host);
    press(&mut app, &[ch('b')]);
    let (text, _) = screen(&mut app, 120, 12);
    assert!(text.contains("(all available modpacks are already installed)"));
}

#[test]
fn install_from_url_trims_and_reports_errors() {
    let host = FakeHost::new();
    *host.available.lock() = Some(Vec::new());
    let (mut app, rx) = app_with(host.clone());
    press(&mut app, &[ch('b'), KeyCode::Enter]);
    assert!(matches!(app.dialogs().last(), Some(Dialog::Input(_))));
    // Blank does nothing.
    press(&mut app, &[ch(' '), KeyCode::Enter]);
    assert!(app.dialogs().is_empty());
    press(&mut app, &[KeyCode::Enter]);
    for c in " https://x/y.json ".chars() {
        press(&mut app, &[ch(c)]);
    }
    press(&mut app, &[KeyCode::Enter]);
    pump(&mut app, &rx);
    assert_eq!(host.calls(), ["install-url https://x/y.json"]);
    match app.dialogs() {
        [Dialog::Message { title, body }] => {
            assert_eq!(title, "Failed");
            assert_eq!(body, "bad url");
        }
        d => panic!("{d:?}"),
    }
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

#[test]
fn settings_selection_is_kept_across_rebuild() {
    let host = FakeHost::new();
    let (mut app, _rx) = app_with(host.clone());
    press(
        &mut app,
        &[ch('s'), KeyCode::Down, KeyCode::Down, KeyCode::Down],
    );
    assert_eq!(app.selected(), 3);
    let before = host.settings().backup_auto_before_update;
    press(&mut app, &[KeyCode::Enter]);
    assert_eq!(host.settings().backup_auto_before_update, !before);
    assert_eq!(app.selected(), 3);
    // Coming back from another view starts at the top.
    press(&mut app, &[ch('l'), ch('s')]);
    assert_eq!(app.selected(), 0);
}

#[test]
fn number_settings_validate() {
    let host = FakeHost::new();
    let (mut app, _rx) = app_with(host.clone());
    // Max backups per pack is row 7.
    press(&mut app, &[ch('s')]);
    for _ in 0..7 {
        press(&mut app, &[KeyCode::Down]);
    }
    press(&mut app, &[KeyCode::Enter]);
    match app.dialogs().last().unwrap() {
        Dialog::Input(i) => assert_eq!(
            i.value,
            host.settings().backup_max_count_per_pack.to_string()
        ),
        d => panic!("{d:?}"),
    }
    app.handle_key(KeyEvent::new_with_kind(
        ch('u'),
        KeyModifiers::CONTROL,
        KeyEventKind::Press,
    ));
    press(&mut app, &[ch('x'), KeyCode::Enter]);
    match app.dialogs() {
        [Dialog::Message { title, body }] => {
            assert_eq!(title, "Invalid value");
            assert_eq!(body, "Please enter a whole number.");
        }
        d => panic!("{d:?}"),
    }
    press(&mut app, &[KeyCode::Enter, KeyCode::Enter]);
    app.handle_key(KeyEvent::new_with_kind(
        ch('u'),
        KeyModifiers::CONTROL,
        KeyEventKind::Press,
    ));
    press(&mut app, &[ch(' '), ch('7'), KeyCode::Enter]);
    assert!(app.dialogs().is_empty());
    assert_eq!(host.settings().backup_max_count_per_pack, 7);
    assert_eq!(app.selected(), 7);
}

#[test]
fn text_settings_show_empty_and_save() {
    let host = FakeHost::new();
    let (mut app, _rx) = app_with(host.clone());
    press(&mut app, &[ch('s')]);
    for _ in 0..10 {
        press(&mut app, &[KeyCode::Down]);
    }
    let (text, _) = screen(&mut app, 120, 20);
    assert!(text.contains("Proxy host"));
    assert!(text.contains("(empty)"));
    press(&mut app, &[KeyCode::Enter]);
    for c in "proxy.local".chars() {
        press(&mut app, &[ch(c)]);
    }
    press(&mut app, &[KeyCode::Enter]);
    assert_eq!(host.settings().proxy_host, "proxy.local");
}

#[test]
fn theme_pick_applies_live() {
    let host = FakeHost::new();
    let (mut app, _rx) = app_with(host.clone());
    assert!(!app.theme().is_light, "native theme on a dark OS is dark");
    press(&mut app, &[ch('s')]);
    for _ in 0..11 {
        press(&mut app, &[KeyCode::Down]);
    }
    press(&mut app, &[KeyCode::Enter]);
    match app.dialogs().last().unwrap() {
        Dialog::Menu { items, .. } => {
            let labels: Vec<&str> = items.iter().map(|i| i.0.as_str()).collect();
            assert_eq!(
                labels,
                [
                    "Dark",
                    "Light",
                    "Automatic",
                    "Native (Mica)",
                    "Blue+gray",
                    "Orange+purple",
                    "Creeper"
                ]
            );
        }
        d => panic!("{d:?}"),
    }
    press(&mut app, &[KeyCode::Home, KeyCode::Down, KeyCode::Enter]);
    assert_eq!(host.settings().theme, "Light");
    assert!(app.theme().is_light);
    assert_eq!(app.selected(), 11);
}

#[test]
fn language_pick_saves_and_applies() {
    let host = FakeHost::new();
    let (mut app, _rx) = app_with(host.clone());
    press(&mut app, &[ch('s'), KeyCode::End]);
    let (text, _) = screen(&mut app, 120, 20);
    assert!(text.contains("Use OS Language"));
    press(&mut app, &[KeyCode::Enter]);
    match app.dialogs().last().unwrap() {
        Dialog::Menu { items, .. } => {
            assert_eq!(items[0].0, "Use OS Language");
            assert_eq!(items[1].0, "English");
            assert_eq!(items.len(), 2 + mmcl_i18n::SUPPORTED.len());
        }
        d => panic!("{d:?}"),
    }
    press(&mut app, &[KeyCode::Down, KeyCode::Down, KeyCode::Enter]);
    assert_eq!(host.settings().locale_override, mmcl_i18n::SUPPORTED[0].0);
    assert_eq!(
        host.calls(),
        [format!("locale {}", mmcl_i18n::SUPPORTED[0].0)]
    );
    let (text, _) = screen(&mut app, 120, 20);
    assert!(text.contains(mmcl_i18n::SUPPORTED[0].1));
}

// ---------------------------------------------------------------------------------------------
// Logs
// ---------------------------------------------------------------------------------------------

#[test]
fn logs_follow_new_output() {
    let host = FakeHost::with_packs(two_packs());
    *host.sessions.lock() = vec![session(
        3,
        "https://a/pack.json",
        SessionPhase::Running,
        100,
    )];
    let (mut app, _rx) = app_with(host.clone());
    press(&mut app, &[ch('g')]);
    let (text, _) = screen(&mut app, 120, 12);
    assert!(text.contains("Game Log \u{2014} Pack https://a/pack.json"));
    assert!(text.contains("first line of 3"));
    host.send_log(SessionId(3), &["brand new line"]);
    assert!(app.poll_logs());
    let (text, _) = screen(&mut app, 120, 12);
    assert!(text.contains("brand new line"));
    // Global keys still work from the log pane, arrows scroll.
    press(&mut app, &[KeyCode::Up, KeyCode::PageUp, KeyCode::End]);
    assert_eq!(app.view(), View::Logs);
    // The game exits: the view rebuilds into "no running games".
    let mut ended = session(3, "https://a/pack.json", SessionPhase::Exited, 100);
    ended.ended_ms = 9_000;
    *host.sessions.lock() = vec![ended.clone()];
    app.handle_msg(Msg::Host(HostEvent::SessionsChanged(Some(Arc::new(ended)))));
    let (text, _) = screen(&mut app, 120, 12);
    assert!(text.contains("No running games."));
}

#[test]
fn logs_without_games_says_so() {
    let (mut app, _rx) = app_with(FakeHost::new());
    press(&mut app, &[ch('g')]);
    let (text, _) = screen(&mut app, 100, 8);
    assert!(text.contains("No running games. Launch one from the Library (L)."));
}

#[test]
fn logs_header_counts_running_games() {
    let host = FakeHost::with_packs(two_packs());
    *host.sessions.lock() = vec![
        session(1, "https://a/pack.json", SessionPhase::Running, 100),
        session(2, "https://b/pack.json", SessionPhase::Running, 200),
    ];
    let (mut app, _rx) = app_with(host);
    press(&mut app, &[ch('g')]);
    let (text, _) = screen(&mut app, 160, 8);
    assert!(text.contains("(n: next \u{00B7} 2 running)"));
}

// ---------------------------------------------------------------------------------------------
// Rendering in both themes
// ---------------------------------------------------------------------------------------------

#[test]
fn every_view_renders_in_light_and_dark() {
    for light in [false, true] {
        let host = FakeHost::with_packs(two_packs());
        if light {
            host.set(Setting::Theme("Light".into())).unwrap();
        }
        *host.available.lock() = Some(vec![pack("Gamma", "https://g/pack.json")]);
        *host.sessions.lock() = vec![session(
            1,
            "https://a/pack.json",
            SessionPhase::Running,
            100,
        )];
        let (mut app, _rx) = app_with(host);
        assert_eq!(app.theme().is_light, light);
        let (bg, fg) = if light {
            (Color::White, Color::Black)
        } else {
            (Color::Black, Color::White)
        };
        for (k, title) in [
            ('l', "Installed Packs"),
            ('b', "Browse / Install"),
            ('g', "Game Log"),
            ('s', "Settings \u{2014} Enter to change"),
        ] {
            press(&mut app, &[ch(k)]);
            let (text, buf) = screen(&mut app, 120, 24);
            assert!(text.contains(title), "{title} in\n{text}");
            assert!(text.starts_with("  Mica Launcher"), "{text}");
            assert!(text.contains("total playtime"));
            assert!(text.contains("Steve"));
            let corner = &buf[(0, 23)];
            assert_eq!(corner.bg, bg);
            assert_eq!(corner.fg, fg);
        }
        // The selected Settings row uses the selection colours.
        let (_, buf) = screen(&mut app, 120, 24);
        let sel = &buf[(2, 2)];
        assert_eq!(sel.bg, if light { Color::Blue } else { Color::White });
    }
}

#[test]
fn dialogs_render_centred_over_the_view() {
    let (mut app, _rx) = app_with(FakeHost::with_packs(two_packs()));
    press(&mut app, &[KeyCode::Enter]);
    let (text, _) = screen(&mut app, 100, 20);
    assert!(text.contains("Choose an action"));
    assert!(text.contains(" Launch "));
    press(&mut app, &[KeyCode::Down, KeyCode::Enter]);
    let (text, _) = screen(&mut app, 100, 20);
    assert!(text.contains("Uninstall Alpha?"));
    assert!(text.contains("< Yes >"));
}

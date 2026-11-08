// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The terminal UI's state and behaviour (Java `TuiApp`), independent of the real terminal:
//! [`App::handle_key`], [`App::handle_msg`], [`App::tick`] and [`App::render`] are driven by
//! [`crate::terminal`] and by the tests (with ratatui's `TestBackend`).
//!
//! Workers (install, uninstall, launch, stop) run on their own threads and report back only
//! through the [`Msg`] channel; the UI state is never touched from a worker.

use std::sync::Arc;
use std::sync::mpsc::Sender;

use mmcl_config::keys::{
    THEME_AUTOMATIC, THEME_BLUE_GRAY, THEME_CREEPER, THEME_DARK, THEME_LIGHT, THEME_NATIVE,
    THEME_ORANGE_PURPLE,
};
use mmcl_engine::{
    LogEvent, LogSubscription, PackId, PackSummary, SessionId, SessionInfo, SessionPhase, Setting,
    Settings, StepStateView,
};
use mmcl_i18n::{FmtArg, Localizer};
use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::text::Line;
use ratatui::widgets::{Block, List, ListItem, ListState, Paragraph};

use crate::format;
use crate::host::{HostEvent, TuiHost};
use crate::log_view::{LogView, SHOWN_LINES, Scroll};
use crate::progress::{self, ProgressLines};
use crate::runtime::TuiRuntime;
use crate::theme::TuiTheme;
use crate::widgets::{Dialog, DialogOutcome, InputDialog};

/// The four views.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum View {
    Library,
    Browse,
    Logs,
    Settings,
}

impl View {
    fn index(self) -> usize {
        match self {
            View::Library => 0,
            View::Browse => 1,
            View::Logs => 2,
            View::Settings => 3,
        }
    }
}

/// What to show after a task finished.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AfterTask {
    Library,
    Browse,
}

/// A message from a worker thread or the host to the UI thread.
#[derive(Debug)]
pub enum Msg {
    /// Something changed in the launcher.
    Host(HostEvent),
    /// A spinner task finished.
    TaskDone {
        result: Result<(), String>,
        then: AfterTask,
    },
    /// A launch got its session.
    LaunchStarted { pack: PackId, session: SessionId },
    /// A launch was refused or failed before it had a session.
    LaunchFailed { pack: PackId, message: String },
    /// Stopping a game failed.
    StopFailed(String),
}

/// A Yes/No question's action.
#[derive(Clone, Debug, PartialEq)]
pub enum ConfirmAction {
    Uninstall(Box<PackSummary>),
    Install(Box<PackSummary>),
}

/// What a text input is for.
#[derive(Clone, Debug, PartialEq)]
pub enum InputPurpose {
    InstallUrl,
    Setting(SettingRow),
}

/// An action-menu item.
#[derive(Clone, Debug, PartialEq)]
pub enum MenuAction {
    Launch(Box<PackSummary>),
    Uninstall(Box<PackSummary>),
    ViewLogs(PackId),
    Stop(SessionId),
    Theme(&'static str),
    Language(String),
    Close,
}

/// One Settings row, in display order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SettingRow {
    Discord,
    Console,
    UpdateCheck,
    AutoBackup,
    ProxyEnable,
    MinRam,
    MaxRam,
    MaxBackups,
    ProxyPort,
    JvmArgs,
    ProxyHost,
    Theme,
    Language,
}

/// The rows of the Settings view.
pub const SETTING_ROWS: [SettingRow; 13] = [
    SettingRow::Discord,
    SettingRow::Console,
    SettingRow::UpdateCheck,
    SettingRow::AutoBackup,
    SettingRow::ProxyEnable,
    SettingRow::MinRam,
    SettingRow::MaxRam,
    SettingRow::MaxBackups,
    SettingRow::ProxyPort,
    SettingRow::JvmArgs,
    SettingRow::ProxyHost,
    SettingRow::Theme,
    SettingRow::Language,
];

/// The themes of the Theme picker, in the Java TUI's order.
pub const THEME_CHOICES: [&str; 7] = [
    THEME_DARK,
    THEME_LIGHT,
    THEME_AUTOMATIC,
    THEME_NATIVE,
    THEME_BLUE_GRAY,
    THEME_ORANGE_PURPLE,
    THEME_CREEPER,
];

/// The label key of a theme (`SettingsOptionLabels.theme`).
pub fn theme_label_key(theme: &str) -> Option<&'static str> {
    Some(match theme {
        THEME_AUTOMATIC => "settings.theme.option.automatic",
        THEME_DARK => "settings.theme.option.dark",
        THEME_LIGHT => "settings.theme.option.light",
        THEME_BLUE_GRAY => "settings.theme.option.blueGray",
        THEME_ORANGE_PURPLE => "settings.theme.option.orangePurple",
        THEME_CREEPER => "settings.theme.option.creeper",
        THEME_NATIVE => "settings.theme.option.native",
        _ => return None,
    })
}

enum Kind {
    Bool,
    Number,
    Text,
    Theme,
    Language,
}

impl SettingRow {
    /// The label key.
    pub fn label_key(self) -> &'static str {
        match self {
            SettingRow::Discord => "tui.setting.discord",
            SettingRow::Console => "tui.setting.console",
            SettingRow::UpdateCheck => "tui.setting.updateCheck",
            SettingRow::AutoBackup => "tui.setting.autoBackup",
            SettingRow::ProxyEnable => "tui.setting.proxyEnable",
            SettingRow::MinRam => "tui.setting.minRam",
            SettingRow::MaxRam => "tui.setting.maxRam",
            SettingRow::MaxBackups => "tui.setting.maxBackups",
            SettingRow::ProxyPort => "tui.setting.proxyPort",
            SettingRow::JvmArgs => "tui.setting.jvmArgs",
            SettingRow::ProxyHost => "tui.setting.proxyHost",
            SettingRow::Theme => "tui.setting.theme",
            SettingRow::Language => "tui.setting.language",
        }
    }

    fn kind(self) -> Kind {
        match self {
            SettingRow::Discord
            | SettingRow::Console
            | SettingRow::UpdateCheck
            | SettingRow::AutoBackup
            | SettingRow::ProxyEnable => Kind::Bool,
            SettingRow::MinRam
            | SettingRow::MaxRam
            | SettingRow::MaxBackups
            | SettingRow::ProxyPort => Kind::Number,
            SettingRow::JvmArgs | SettingRow::ProxyHost => Kind::Text,
            SettingRow::Theme => Kind::Theme,
            SettingRow::Language => Kind::Language,
        }
    }

    fn bool_value(self, s: &Settings) -> bool {
        match self {
            SettingRow::Discord => s.discord_rpc,
            SettingRow::Console => s.in_game_console,
            SettingRow::UpdateCheck => s.launcher_update_check,
            SettingRow::AutoBackup => s.backup_auto_before_update,
            SettingRow::ProxyEnable => s.proxy_enable,
            _ => false,
        }
    }

    fn with_bool(self, v: bool) -> Option<Setting> {
        Some(match self {
            SettingRow::Discord => Setting::DiscordRpc(v),
            SettingRow::Console => Setting::InGameConsole(v),
            SettingRow::UpdateCheck => Setting::LauncherUpdateCheck(v),
            SettingRow::AutoBackup => Setting::BackupAutoBeforeUpdate(v),
            SettingRow::ProxyEnable => Setting::ProxyEnable(v),
            _ => return None,
        })
    }

    fn text_value(self, s: &Settings) -> String {
        match self {
            SettingRow::MinRam => s.min_ram_mb.to_string(),
            SettingRow::MaxRam => s.max_ram_mb.to_string(),
            SettingRow::MaxBackups => s.backup_max_count_per_pack.to_string(),
            SettingRow::ProxyPort => s.proxy_port.to_string(),
            SettingRow::JvmArgs => s.jvm_args.clone(),
            SettingRow::ProxyHost => s.proxy_host.clone(),
            SettingRow::Theme => s.theme.clone(),
            SettingRow::Language => s.locale_override.clone(),
            _ => String::new(),
        }
    }

    /// The setting for typed text; `Err(())` when a number does not parse.
    fn parse(self, text: &str) -> Result<Option<Setting>, ()> {
        let t = text.trim();
        Ok(Some(match self {
            SettingRow::MinRam => Setting::MinRamMb(t.parse::<i64>().map_err(|_| ())?),
            SettingRow::MaxRam => Setting::MaxRamMb(t.parse::<i64>().map_err(|_| ())?),
            SettingRow::MaxBackups => {
                Setting::BackupMaxCountPerPack(t.parse::<i32>().map_err(|_| ())?)
            }
            SettingRow::ProxyPort => Setting::ProxyPort(t.parse::<i32>().map_err(|_| ())?),
            SettingRow::JvmArgs => Setting::JvmArgs(t.to_string()),
            SettingRow::ProxyHost => Setting::ProxyHost(t.to_string()),
            _ => return Ok(None),
        }))
    }
}

/// A Library row.
#[derive(Clone, Debug, PartialEq)]
enum LibraryRow {
    Empty,
    Pack(Box<PackSummary>),
}

/// A Browse row.
#[derive(Clone, Debug, PartialEq)]
enum BrowseRow {
    InstallFromUrl,
    Loading,
    AllInstalled,
    Pack(Box<PackSummary>),
}

/// The launch the progress window shows.
#[derive(Clone, Debug)]
struct LaunchState {
    pack: PackId,
    session: Option<SessionId>,
}

/// The terminal UI.
pub struct App {
    host: Arc<dyn TuiHost>,
    l: &'static Localizer,
    tx: Sender<Msg>,
    view: View,
    theme: TuiTheme,
    installed: Vec<PackSummary>,
    runtime: TuiRuntime,
    available_ready: bool,
    library: Vec<LibraryRow>,
    browse: Vec<BrowseRow>,
    settings: Settings,
    selected: [usize; 4],
    logs_target: Option<PackId>,
    logs_session: Option<SessionId>,
    log_sub: Option<LogSubscription>,
    log_view: LogView,
    dialogs: Vec<Dialog>,
    launch: Option<LaunchState>,
    signed_in: Option<String>,
    quit: bool,
}

impl App {
    /// A UI over `host`, rendering through `l`, with workers reporting on `tx`. Opens on the
    /// Library.
    pub fn new(host: Arc<dyn TuiHost>, l: &'static Localizer, tx: Sender<Msg>) -> App {
        let settings = host.settings();
        let theme = TuiTheme::resolve(&settings.theme, host.os_is_dark());
        let mut app = App {
            host,
            l,
            tx,
            view: View::Library,
            theme,
            installed: Vec::new(),
            runtime: TuiRuntime::default(),
            available_ready: false,
            library: Vec::new(),
            browse: Vec::new(),
            settings,
            selected: [0; 4],
            logs_target: None,
            logs_session: None,
            log_sub: None,
            log_view: LogView::new(),
            dialogs: Vec::new(),
            launch: None,
            signed_in: None,
            quit: false,
        };
        app.refresh_state();
        app.show_library();
        app
    }

    /// The current view.
    pub fn view(&self) -> View {
        self.view
    }

    /// Whether the user quit.
    pub fn should_quit(&self) -> bool {
        self.quit
    }

    /// The open dialogs, bottom first.
    pub fn dialogs(&self) -> &[Dialog] {
        &self.dialogs
    }

    /// The active theme.
    pub fn theme(&self) -> TuiTheme {
        self.theme
    }

    /// The selected row of the current view.
    pub fn selected(&self) -> usize {
        self.selected[self.view.index()]
    }

    /// The pack the Logs view shows.
    pub fn logs_target(&self) -> Option<&PackId> {
        self.logs_target.as_ref()
    }

    /// The log pane (tests).
    pub fn log_view(&self) -> &LogView {
        &self.log_view
    }

    fn t(&self, key: &str) -> String {
        self.l.get(key).into_owned()
    }

    fn tf(&self, key: &str, args: &[&str]) -> String {
        let a: Vec<FmtArg> = args.iter().map(|s| FmtArg::from(*s)).collect();
        self.l.format(key, &a)
    }

    // ---------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------

    fn refresh_state(&mut self) {
        self.installed = self.host.installed_packs();
        self.runtime = TuiRuntime::from_sessions(&self.host.sessions());
        self.signed_in = self.host.signed_in_name();
    }

    fn row_count(&self) -> usize {
        match self.view {
            View::Library => self.library.len(),
            View::Browse => self.browse.len(),
            View::Settings => SETTING_ROWS.len(),
            View::Logs => 0,
        }
    }

    fn keep_selection(&mut self, view: View) {
        let was_current = self.view == view;
        self.view = view;
        let n = self.row_count();
        let i = view.index();
        self.selected[i] = if was_current {
            self.selected[i].min(n.saturating_sub(1))
        } else {
            0
        };
    }

    /// Shows the Library.
    pub fn show_library(&mut self) {
        self.library = if self.installed.is_empty() {
            vec![LibraryRow::Empty]
        } else {
            self.installed
                .iter()
                .cloned()
                .map(|p| LibraryRow::Pack(Box::new(p)))
                .collect()
        };
        self.keep_selection(View::Library);
    }

    /// Shows Browse.
    pub fn show_browse(&mut self) {
        let mut rows = vec![BrowseRow::InstallFromUrl];
        match self.host.available_packs() {
            None => {
                self.available_ready = false;
                rows.push(BrowseRow::Loading);
            }
            Some(list) => {
                self.available_ready = true;
                let installed_urls: Vec<&str> = self
                    .installed
                    .iter()
                    .filter_map(|p| p.manifest_url.as_deref())
                    .collect();
                let installed_ids: Vec<&PackId> = self.installed.iter().map(|p| &p.id).collect();
                let packs: Vec<BrowseRow> = list
                    .into_iter()
                    .filter(|p| {
                        let url_installed = p
                            .manifest_url
                            .as_deref()
                            .is_some_and(|u| installed_urls.contains(&u));
                        !url_installed && !installed_ids.contains(&&p.id)
                    })
                    .map(|p| BrowseRow::Pack(Box::new(p)))
                    .collect();
                if packs.is_empty() {
                    rows.push(BrowseRow::AllInstalled);
                } else {
                    rows.extend(packs);
                }
            }
        }
        self.browse = rows;
        self.keep_selection(View::Browse);
    }

    /// Shows Settings (keeps the selected row when Settings was already showing).
    pub fn show_settings(&mut self) {
        self.settings = self.host.settings();
        self.keep_selection(View::Settings);
    }

    /// Shows Logs: the target game, else the first running one.
    pub fn show_logs(&mut self) {
        self.view = View::Logs;
        let running = self.runtime.running();
        if running.is_empty() {
            self.logs_session = None;
            self.log_sub = None;
            return;
        }
        let target_running = self
            .logs_target
            .as_ref()
            .and_then(|t| self.runtime.running_for(t))
            .cloned();
        let session = match target_running {
            Some(s) => s,
            None => {
                let first = running[0].clone();
                self.logs_target = Some(first.pack.clone());
                first
            }
        };
        if self.logs_session != Some(session.id) {
            self.logs_session = Some(session.id);
            self.log_view = LogView::new();
            self.log_sub = self.host.subscribe_log(session.id, SHOWN_LINES);
            if let Some(sub) = &self.log_sub {
                let snapshot = sub.snapshot.clone();
                self.log_view.push_text(&snapshot);
            }
        }
        self.poll_logs();
    }

    /// Drains the log subscription and refreshes the pane (follow rule applies). Returns
    /// `true` when the pane changed.
    pub fn poll_logs(&mut self) -> bool {
        if self.view != View::Logs {
            return false;
        }
        let mut closed = false;
        if let Some(sub) = &mut self.log_sub {
            while let Ok(ev) = sub.events.try_recv() {
                match ev {
                    LogEvent::Lines(lines) => self.log_view.push_lines(lines.iter().cloned()),
                    LogEvent::Closed => closed = true,
                }
            }
        }
        let still_running = self
            .logs_target
            .as_ref()
            .is_some_and(|t| self.runtime.is_running(t));
        if self.logs_session.is_some() && (closed || !still_running) {
            let notice = self.t("tui.logs.exited");
            return self.log_view.mark_exited(&notice);
        }
        self.log_view.refresh()
    }

    fn cycle_logs(&mut self) {
        let running = self.runtime.running();
        if running.len() < 2 {
            return;
        }
        let current = self
            .logs_target
            .as_ref()
            .and_then(|t| running.iter().position(|s| &s.pack == t))
            .unwrap_or(0);
        let next = running[(current + 1) % running.len()].pack.clone();
        self.logs_target = Some(next);
        self.show_logs();
    }

    /// A game started or exited: rebuild the views that show running state (never Browse or
    /// Settings, which would lose the user's place).
    fn on_games_changed(&mut self) {
        self.refresh_state();
        match self.view {
            View::Library => self.show_library(),
            View::Logs => self.show_logs(),
            View::Browse | View::Settings => {}
        }
    }

    // ---------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------

    /// Handles one key. Dialogs capture every key; otherwise the global keys run first.
    pub fn handle_key(&mut self, key: KeyEvent) {
        if key.kind == KeyEventKind::Release {
            return;
        }
        if let Some(top) = self.dialogs.last_mut() {
            let outcome = top.handle_key(&key);
            self.on_dialog_outcome(outcome);
            return;
        }
        if self.global_key(&key) {
            return;
        }
        self.view_key(&key);
    }

    /// The global keys; `true` when consumed.
    fn global_key(&mut self, key: &KeyEvent) -> bool {
        if key.modifiers.contains(KeyModifiers::CONTROL) {
            if key.code == KeyCode::Char('c') {
                self.quit = true;
                return true;
            }
            return false;
        }
        if key.modifiers.contains(KeyModifiers::ALT) {
            return false;
        }
        match key.code {
            KeyCode::Esc => {
                if self.view != View::Library {
                    self.show_library();
                    return true;
                }
                false
            }
            KeyCode::Char(c) => match c.to_lowercase().next().unwrap_or(c) {
                'q' => {
                    self.quit = true;
                    true
                }
                'l' => {
                    self.show_library();
                    true
                }
                'b' => {
                    self.show_browse();
                    true
                }
                'g' => {
                    self.show_logs();
                    true
                }
                's' => {
                    self.show_settings();
                    true
                }
                'n' if self.view == View::Logs => {
                    self.cycle_logs();
                    true
                }
                _ => false,
            },
            _ => false,
        }
    }

    fn view_key(&mut self, key: &KeyEvent) {
        if self.view == View::Logs {
            let s = match key.code {
                KeyCode::Up => Scroll::Up,
                KeyCode::Down => Scroll::Down,
                KeyCode::Left => Scroll::Left,
                KeyCode::Right => Scroll::Right,
                KeyCode::PageUp => Scroll::PageUp,
                KeyCode::PageDown => Scroll::PageDown,
                KeyCode::Home => Scroll::Home,
                KeyCode::End => Scroll::End,
                _ => return,
            };
            self.log_view.scroll(s);
            return;
        }
        let n = self.row_count();
        let i = self.view.index();
        let page = 10;
        match key.code {
            KeyCode::Up => self.selected[i] = self.selected[i].saturating_sub(1),
            KeyCode::Down => self.selected[i] = (self.selected[i] + 1).min(n.saturating_sub(1)),
            KeyCode::PageUp => self.selected[i] = self.selected[i].saturating_sub(page),
            KeyCode::PageDown => {
                self.selected[i] = (self.selected[i] + page).min(n.saturating_sub(1))
            }
            KeyCode::Home => self.selected[i] = 0,
            KeyCode::End => self.selected[i] = n.saturating_sub(1),
            KeyCode::Enter | KeyCode::Char(' ') => self.activate(),
            _ => {}
        }
    }

    fn activate(&mut self) {
        let i = self.selected();
        match self.view {
            View::Library => {
                if let Some(LibraryRow::Pack(p)) = self.library.get(i).cloned() {
                    self.pack_actions(&p);
                }
            }
            View::Browse => match self.browse.get(i).cloned() {
                Some(BrowseRow::InstallFromUrl) => {
                    self.dialogs.push(Dialog::Input(InputDialog::new(
                        self.t("tui.install.url.title"),
                        self.t("tui.install.url.prompt"),
                        "",
                        InputPurpose::InstallUrl,
                    )));
                }
                Some(BrowseRow::Pack(p)) => {
                    let name = format::pack_display_name(&p).to_string();
                    self.dialogs.push(Dialog::Confirm {
                        title: self.t("tui.install.title"),
                        body: self.tf("tui.install.confirm", &[&name]),
                        yes_selected: true,
                        action: ConfirmAction::Install(p),
                    });
                }
                _ => {}
            },
            View::Settings => {
                if let Some(row) = SETTING_ROWS.get(i).copied() {
                    self.change_setting(row);
                }
            }
            View::Logs => {}
        }
    }

    fn pack_actions(&mut self, p: &PackSummary) {
        let name = format::pack_display_name(p).to_string();
        let running = self.runtime.running_for(&p.id).map(|s| s.id);
        let mut items = Vec::new();
        let description = match running {
            Some(session) => {
                items.push((
                    self.t("tui.action.viewLogs"),
                    MenuAction::ViewLogs(p.id.clone()),
                ));
                items.push((self.t("tui.action.stop"), MenuAction::Stop(session)));
                self.t("tui.actions.running")
            }
            None => {
                items.push((
                    self.t("tui.action.launch"),
                    MenuAction::Launch(Box::new(p.clone())),
                ));
                items.push((
                    self.t("tui.action.uninstall"),
                    MenuAction::Uninstall(Box::new(p.clone())),
                ));
                self.t("tui.actions.choose")
            }
        };
        items.push((self.t("tui.action.close"), MenuAction::Close));
        self.dialogs.push(Dialog::Menu {
            title: name,
            description,
            items,
            selected: 0,
        });
    }

    fn on_dialog_outcome(&mut self, outcome: DialogOutcome) {
        match outcome {
            DialogOutcome::Keep => {}
            DialogOutcome::Close => {
                self.dialogs.pop();
            }
            DialogOutcome::Confirmed(action) => {
                self.dialogs.pop();
                match action {
                    ConfirmAction::Uninstall(p) => {
                        let name = format::pack_display_name(&p).to_string();
                        let host = self.host.clone();
                        let id = p.id.clone();
                        self.run_with_spinner(
                            self.tf("tui.uninstall.working", &[&name]),
                            AfterTask::Library,
                            move || host.uninstall(&id),
                        );
                    }
                    ConfirmAction::Install(p) => {
                        let name = format::pack_display_name(&p).to_string();
                        let host = self.host.clone();
                        self.run_with_spinner(
                            self.tf("tui.install.working", &[&name]),
                            AfterTask::Browse,
                            move || host.install_available(&p),
                        );
                    }
                }
            }
            DialogOutcome::Input(purpose, text) => {
                self.dialogs.pop();
                match purpose {
                    InputPurpose::InstallUrl => {
                        let url = text.trim().to_string();
                        if url.is_empty() {
                            return;
                        }
                        let host = self.host.clone();
                        self.run_with_spinner(
                            self.t("tui.install.url.working"),
                            AfterTask::Browse,
                            move || host.install_by_url(&url),
                        );
                    }
                    InputPurpose::Setting(row) => {
                        match row.parse(&text) {
                            Err(()) => self.error_dialog(Some(self.t("tui.error.number"))),
                            Ok(Some(setting)) => {
                                if let Err(e) = self.host.set(setting) {
                                    self.error_dialog(Some(e));
                                }
                            }
                            Ok(None) => {}
                        }
                        self.show_settings();
                    }
                }
            }
            DialogOutcome::InputCancelled(purpose) => {
                self.dialogs.pop();
                if matches!(purpose, InputPurpose::Setting(_)) {
                    self.show_settings();
                }
            }
            DialogOutcome::Picked(action) => {
                self.dialogs.pop();
                self.on_menu_action(action);
            }
            DialogOutcome::CancelLaunch => {
                if let Some(session) = self.launch.as_ref().and_then(|l| l.session) {
                    self.host.cancel_launch(session);
                }
            }
        }
    }

    fn on_menu_action(&mut self, action: MenuAction) {
        match action {
            MenuAction::Launch(p) => self.launch(&p),
            MenuAction::Uninstall(p) => {
                let name = format::pack_display_name(&p).to_string();
                self.dialogs.push(Dialog::Confirm {
                    title: self.t("tui.uninstall.title"),
                    body: self.tf("tui.uninstall.confirm", &[&name]),
                    yes_selected: false,
                    action: ConfirmAction::Uninstall(p),
                });
            }
            MenuAction::ViewLogs(id) => {
                self.logs_target = Some(id);
                self.show_logs();
            }
            MenuAction::Stop(session) => {
                let host = self.host.clone();
                let tx = self.tx.clone();
                let spawned =
                    std::thread::Builder::new()
                        .name("tui-stop".into())
                        .spawn(move || {
                            if let Err(e) = host.stop_game(session) {
                                let _ = tx.send(Msg::StopFailed(e));
                            }
                        });
                if let Err(e) = spawned {
                    self.message(self.t("tui.generic.failed"), e.to_string());
                }
            }
            MenuAction::Theme(theme) => {
                if let Err(e) = self.host.set(Setting::Theme(theme.to_string())) {
                    self.error_dialog(Some(e));
                }
                self.apply_theme();
                self.show_settings();
            }
            MenuAction::Language(tag) => {
                match self.host.set(Setting::LocaleOverride(tag.clone())) {
                    Ok(()) => self.host.apply_locale(&tag),
                    Err(e) => self.error_dialog(Some(e)),
                }
                self.show_settings();
            }
            MenuAction::Close => {}
        }
    }

    fn change_setting(&mut self, row: SettingRow) {
        let label = self.t(row.label_key());
        match row.kind() {
            Kind::Bool => {
                if let Some(s) = row.with_bool(!row.bool_value(&self.settings))
                    && let Err(e) = self.host.set(s)
                {
                    self.error_dialog(Some(e));
                }
                self.show_settings();
            }
            Kind::Number | Kind::Text => {
                let value = row.text_value(&self.settings);
                self.dialogs.push(Dialog::Input(InputDialog::new(
                    label.clone(),
                    label,
                    value,
                    InputPurpose::Setting(row),
                )));
            }
            Kind::Theme => {
                let items = THEME_CHOICES
                    .iter()
                    .map(|t| (self.theme_label(t), MenuAction::Theme(t)))
                    .collect();
                self.dialogs.push(Dialog::Menu {
                    title: label,
                    description: String::new(),
                    items,
                    selected: THEME_CHOICES
                        .iter()
                        .position(|t| {
                            *t == mmcl_engine::settings::canonical_theme(&self.settings.theme)
                        })
                        .unwrap_or(0),
                });
            }
            Kind::Language => {
                let mut items = vec![
                    (
                        self.t("tui.locale.osDefault"),
                        MenuAction::Language(String::new()),
                    ),
                    ("English".to_string(), MenuAction::Language("en-US".into())),
                ];
                for (tag, name) in mmcl_i18n::SUPPORTED {
                    items.push((name.to_string(), MenuAction::Language(tag.to_string())));
                }
                let current = self.settings.locale_override.trim().to_string();
                let selected = items
                    .iter()
                    .position(|(_, a)| matches!(a, MenuAction::Language(t) if t.eq_ignore_ascii_case(&current)))
                    .unwrap_or(0);
                self.dialogs.push(Dialog::Menu {
                    title: label,
                    description: String::new(),
                    items,
                    selected,
                });
            }
        }
    }

    fn theme_label(&self, theme: &str) -> String {
        match theme_label_key(theme) {
            Some(k) => self.t(k),
            None => theme.to_string(),
        }
    }

    fn language_label(&self, tag: &str) -> String {
        let tag = tag.trim();
        if tag.is_empty() {
            return self.t("tui.locale.osDefault");
        }
        if tag.eq_ignore_ascii_case("en-US") {
            return "English".to_string();
        }
        mmcl_i18n::display_name(tag)
            .map(str::to_string)
            .unwrap_or_else(|| tag.to_string())
    }

    fn setting_value(&self, row: SettingRow) -> String {
        let s = &self.settings;
        match row.kind() {
            Kind::Bool => {
                if row.bool_value(s) {
                    self.t("tui.settings.on")
                } else {
                    self.t("tui.settings.off")
                }
            }
            Kind::Number => row.text_value(s),
            Kind::Text => {
                let v = row.text_value(s);
                if v.is_empty() {
                    self.t("tui.settings.empty")
                } else {
                    format::truncate(Some(&v), 40)
                }
            }
            Kind::Theme => self.theme_label(&s.theme),
            Kind::Language => self.language_label(&s.locale_override),
        }
    }

    fn apply_theme(&mut self) {
        let s = self.host.settings();
        self.theme = TuiTheme::resolve(&s.theme, self.host.os_is_dark());
    }

    fn message(&mut self, title: String, body: String) {
        self.dialogs.push(Dialog::Message { title, body });
    }

    fn error_dialog(&mut self, message: Option<String>) {
        let body = message
            .filter(|m| !m.trim().is_empty())
            .unwrap_or_else(|| self.t("tui.error.invalid.body"));
        self.message(self.t("tui.error.invalid.title"), body);
    }

    /// Shows a spinner while `work` runs on the `tui-task` thread; afterwards shows the error
    /// or `then`.
    fn run_with_spinner(
        &mut self,
        message: String,
        then: AfterTask,
        work: impl FnOnce() -> Result<(), String> + Send + 'static,
    ) {
        self.dialogs.push(Dialog::Spinner { message, frame: 0 });
        let tx = self.tx.clone();
        let spawned = std::thread::Builder::new()
            .name("tui-task".into())
            .spawn(move || {
                let result = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(work)) {
                    Ok(r) => r,
                    Err(_) => Err(String::new()),
                };
                let _ = tx.send(Msg::TaskDone { result, then });
            });
        if let Err(e) = spawned {
            self.dialogs.pop();
            self.message(self.t("tui.generic.failed"), e.to_string());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Launch
    // ---------------------------------------------------------------------------------------

    /// Starts a launch with the progress window (nothing when the pack already runs or another
    /// launch is showing its progress).
    pub fn launch(&mut self, p: &PackSummary) {
        if self.runtime.is_running(&p.id) || self.launch.is_some() {
            return;
        }
        let name = format::pack_display_name(p).to_string();
        self.dialogs.push(Dialog::Progress {
            title: self.tf("tui.launch.title", &[&name]),
            lines: ProgressLines {
                section: self.t("tui.launch.preparing"),
                detail: String::new(),
                percent: "0%".into(),
            },
            cancelling: false,
        });
        self.launch = Some(LaunchState {
            pack: p.id.clone(),
            session: None,
        });
        let host = self.host.clone();
        let tx = self.tx.clone();
        let id = p.id.clone();
        let spawned = std::thread::Builder::new()
            .name(format!("tui-launch-{}", format::sanitize_for_thread(&name)))
            .spawn(move || {
                let msg = match host.launch(&id) {
                    Ok(session) => Msg::LaunchStarted { pack: id, session },
                    Err(message) => {
                        tracing::warn!(
                            "{}",
                            mmcl_i18n::tf!("log.tui.launchFailed", id.as_str(), message.as_str())
                        );
                        Msg::LaunchFailed { pack: id, message }
                    }
                };
                let _ = tx.send(msg);
            });
        if let Err(e) = spawned {
            self.close_progress();
            self.message(self.t("tui.launch.failed"), e.to_string());
        }
    }

    fn close_progress(&mut self) {
        self.launch = None;
        self.dialogs
            .retain(|d| !matches!(d, Dialog::Progress { .. }));
    }

    fn set_progress(&mut self, percent: f64, section: &str, detail: &str) {
        let new_lines = ProgressLines::from_update(percent, Some(section), Some(detail), None);
        for d in &mut self.dialogs {
            if let Dialog::Progress { lines, .. } = d {
                *lines = new_lines.clone();
            }
        }
    }

    /// Applies a snapshot of the launching session to the progress window.
    fn apply_launch_session(&mut self, info: &SessionInfo) {
        if self.launch.as_ref().and_then(|l| l.session) != Some(info.id) {
            return;
        }
        match info.phase {
            SessionPhase::Preparing => {
                if let Some((p, s, d)) = progress::from_session(info) {
                    self.set_progress(p, &s, &d);
                }
            }
            SessionPhase::Running | SessionPhase::Exited | SessionPhase::Crashed => {
                self.close_progress();
                self.on_games_changed();
            }
            SessionPhase::Cancelled => {
                self.close_progress();
                self.on_games_changed();
            }
            SessionPhase::Failed => {
                self.close_progress();
                let reason = info
                    .steps
                    .iter()
                    .rev()
                    .find(|s| s.state == StepStateView::Failed)
                    .and_then(|s| s.error.clone())
                    .filter(|e| !e.trim().is_empty())
                    .unwrap_or_else(|| self.t(info.phase.l10n_key()));
                self.message(self.t("tui.launch.failed"), reason);
                self.on_games_changed();
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Messages and ticks
    // ---------------------------------------------------------------------------------------

    /// Applies a message from a worker or the host.
    pub fn handle_msg(&mut self, msg: Msg) {
        match msg {
            Msg::TaskDone { result, then } => {
                self.dialogs
                    .retain(|d| !matches!(d, Dialog::Spinner { .. }));
                self.refresh_state();
                match result {
                    Err(e) => {
                        let body = if e.trim().is_empty() {
                            self.t("tui.error.invalid.body")
                        } else {
                            e
                        };
                        self.message(self.t("tui.generic.failed"), body);
                    }
                    Ok(()) => match then {
                        AfterTask::Library => self.show_library(),
                        AfterTask::Browse => self.show_browse(),
                    },
                }
            }
            Msg::LaunchStarted { pack, session } => {
                let Some(state) = self.launch.as_mut().filter(|l| l.pack == pack) else {
                    return;
                };
                state.session = Some(session);
                if let Some(info) = self.host.session(session) {
                    self.apply_launch_session(&info);
                }
            }
            Msg::LaunchFailed { pack, message } => {
                if self.launch.as_ref().is_some_and(|l| l.pack == pack) {
                    self.close_progress();
                }
                self.message(self.t("tui.launch.failed"), message);
            }
            Msg::StopFailed(e) => self.message(self.t("tui.generic.failed"), e),
            Msg::Host(ev) => self.on_host_event(ev),
        }
    }

    fn on_host_event(&mut self, ev: HostEvent) {
        match ev {
            HostEvent::PacksChanged => {
                self.refresh_state();
                match self.view {
                    View::Library => self.show_library(),
                    View::Browse if self.dialogs.is_empty() => self.show_browse(),
                    _ => {}
                }
            }
            HostEvent::AvailableReady => {
                if self.view == View::Browse && !self.available_ready {
                    self.show_browse();
                }
            }
            HostEvent::SessionsChanged(info) => {
                if let Some(info) = &info {
                    self.apply_launch_session(info);
                }
                self.on_games_changed();
            }
            HostEvent::Progress(p) => {
                if self.launch.as_ref().and_then(|l| l.session).map(|s| s.0) == Some(p.operation.0)
                {
                    let (pct, s, d) = progress::from_operation(&p);
                    self.set_progress(pct, &s, &d);
                }
            }
            HostEvent::SettingChanged => {
                self.apply_theme();
                if self.view == View::Settings && self.dialogs.is_empty() {
                    self.show_settings();
                }
            }
            HostEvent::AccountsChanged => self.signed_in = self.host.signed_in_name(),
            HostEvent::ShuttingDown => self.quit = true,
        }
    }

    /// The 1 Hz refresh: status bar data, the launch's state (in case an event was missed), the
    /// spinner and the log pane.
    pub fn tick(&mut self) {
        self.runtime = TuiRuntime::from_sessions(&self.host.sessions());
        self.installed = self.host.installed_packs();
        if let Some(session) = self.launch.as_ref().and_then(|l| l.session)
            && let Some(info) = self.host.session(session)
        {
            self.apply_launch_session(&info);
        }
        if self.view == View::Browse
            && !self.available_ready
            && self.host.available_packs().is_some()
            && self.dialogs.is_empty()
        {
            self.show_browse();
        }
        for d in &mut self.dialogs {
            d.tick();
        }
        self.poll_logs();
    }

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    /// The status bar text.
    pub fn status_text(&self) -> String {
        let now = self.host.now_ms();
        format::status_line(
            self.runtime.running_count(),
            self.runtime.live_total_playtime_ms(&self.installed, now),
            self.signed_in.as_deref(),
            self.l,
        )
    }

    /// Draws the whole screen.
    pub fn render(&mut self, f: &mut Frame) {
        let area = f.area();
        let base = self.theme.base();
        f.render_widget(Block::default().style(base), area);
        let [top, centre, bottom] = Layout::vertical([
            Constraint::Length(1),
            Constraint::Min(3),
            Constraint::Length(1),
        ])
        .areas(area);
        f.render_widget(
            Paragraph::new(format::header_text(Some(area.width), self.l))
                .style(self.theme.header()),
            top,
        );
        match self.view {
            View::Library => self.render_library(f, centre),
            View::Browse => self.render_browse(f, centre),
            View::Settings => self.render_settings(f, centre),
            View::Logs => self.render_logs(f, centre),
        }
        f.render_widget(Paragraph::new(self.status_text()).style(base), bottom);
        for d in &self.dialogs {
            d.render(f, area, self.theme, self.l);
        }
    }

    fn render_list(&self, f: &mut Frame, area: Rect, title: String, rows: Vec<(String, bool)>) {
        let items: Vec<ListItem> = rows
            .into_iter()
            .map(|(text, inert)| {
                let style = if inert {
                    self.theme.inert()
                } else {
                    self.theme.base()
                };
                ListItem::new(Line::from(text)).style(style)
            })
            .collect();
        let list = List::new(items)
            .block(
                Block::bordered()
                    .title(Line::from(title))
                    .style(self.theme.base()),
            )
            .style(self.theme.base())
            .highlight_style(self.theme.selected());
        let mut state = ListState::default().with_selected(Some(self.selected()));
        f.render_stateful_widget(list, area, &mut state);
    }

    fn render_library(&self, f: &mut Frame, area: Rect) {
        let now = self.host.now_ms();
        let rows = self
            .library
            .iter()
            .map(|r| match r {
                LibraryRow::Empty => (self.t("tui.library.empty"), true),
                LibraryRow::Pack(p) => (
                    format::format_pack_row(p, self.runtime.is_running(&p.id), now, self.l),
                    false,
                ),
            })
            .collect();
        self.render_list(f, area, self.t("tui.library.title"), rows);
    }

    fn render_browse(&self, f: &mut Frame, area: Rect) {
        let rows = self
            .browse
            .iter()
            .map(|r| match r {
                BrowseRow::InstallFromUrl => (
                    format!("\u{FF0B}  {}", self.t("tui.browse.installFromUrl")),
                    false,
                ),
                BrowseRow::Loading => (self.t("tui.browse.loading"), true),
                BrowseRow::AllInstalled => (self.t("tui.browse.allInstalled"), true),
                BrowseRow::Pack(p) => (format::format_available_row(p), false),
            })
            .collect();
        self.render_list(f, area, self.t("tui.browse.title"), rows);
    }

    fn render_settings(&self, f: &mut Frame, area: Rect) {
        let rows = SETTING_ROWS
            .iter()
            .map(|r| {
                (
                    format!(
                        "{}{}",
                        format::pad(&self.t(r.label_key()), 30),
                        self.setting_value(*r)
                    ),
                    false,
                )
            })
            .collect();
        self.render_list(f, area, self.t("tui.settings.title"), rows);
    }

    fn render_logs(&mut self, f: &mut Frame, area: Rect) {
        let block = Block::bordered().style(self.theme.base());
        let Some(target) = self
            .logs_target
            .clone()
            .filter(|_| self.logs_session.is_some())
        else {
            let block = block.title(Line::from(self.t("tui.logs.title")));
            f.render_widget(
                Paragraph::new(format!("  {}", self.t("tui.logs.none")))
                    .style(self.theme.base())
                    .block(block),
                area,
            );
            return;
        };
        let name = self
            .runtime
            .running_for(&target)
            .map(|s| s.pack_name.clone())
            .or_else(|| {
                self.installed
                    .iter()
                    .find(|p| p.id == target)
                    .map(|p| format::pack_display_name(p).to_string())
            })
            .unwrap_or_else(|| target.as_str().to_string());
        let mut title = self.tf("tui.logs.header", &[&format::truncate(Some(&name), 32)]);
        let count = self.runtime.running_count();
        if count > 1 {
            title.push_str("   ");
            title.push_str(&self.tf("tui.logs.next", &[&count.to_string()]));
        }
        title.push_str("   [\u{2191}\u{2193}\u{2190}\u{2192}] ");
        title.push_str(&self.t("tui.logs.scrollHint"));
        let block = block.title(Line::from(title));
        let inner = block.inner(area);
        self.log_view.set_height(usize::from(inner.height));
        let (row, col) = self.log_view.scroll_offset();
        let lines: Vec<Line> = self
            .log_view
            .shown()
            .iter()
            .map(|l| Line::from(l.clone()))
            .collect();
        let para = Paragraph::new(lines)
            .style(self.theme.base())
            .block(block)
            .scroll((
                row.min(u16::MAX as usize) as u16,
                col.min(u16::MAX as usize) as u16,
            ));
        f.render_widget(para, area);
    }
}

#[cfg(test)]
#[path = "app_tests.rs"]
mod tests;

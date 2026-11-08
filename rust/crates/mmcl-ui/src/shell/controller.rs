// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `MCLauncherGuiController`: the GUI's app-wide controller ([`GuiController`] global, foreground
//! only) and its thread-safe face ([`GuiHandle`]) for the auth, launch, MCP, tray and deep-link
//! threads.
//!
//! Background callers never block the foreground: a [`GuiHandle`] sends a [`UiCommand`] down a
//! channel a foreground task drains, and awaits a oneshot for the answer.

use std::any::Any;
use std::rc::Rc;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use futures::StreamExt as _;
use futures::channel::{mpsc, oneshot};
use gpui::{
    AnyWindowHandle, App, AppContext as _, AsyncApp, Bounds, Entity, Global, Task, Window,
    WindowBackgroundAppearance, WindowBounds, WindowDecorations, WindowHandle, WindowKind,
    WindowOptions, point, px, size,
};
use gpui_component::{Root, TitleBar};
use mmcl_i18n::{t, tf};
use mmcl_ui_kit::{
    CloseDecision, HelpTopic, Navigator, ScreenHandle, ScreenId, ScreenRequest, ThemeExt as _,
};
use parking_lot::RwLock;

use super::hooks;
use super::main_window::{self, MainWindow};
use crate::dialogs::{self, Answer, Question};
use crate::placement::{self, BoundsTracker, Rect, Restore};
use crate::services;

/// What background threads can read without touching the foreground.
#[derive(Debug, Default)]
pub struct GuiState {
    started: AtomicBool,
    focused: AtomicBool,
    allows_navigation: AtomicBool,
    current: RwLock<Option<ScreenId>>,
}

impl GuiState {
    /// Whether the main window exists.
    pub fn is_started(&self) -> bool {
        self.started.load(Ordering::Acquire)
    }
    /// Whether the main window is the active window.
    pub fn is_focused(&self) -> bool {
        self.focused.load(Ordering::Acquire)
    }
    /// The screen showing.
    pub fn current(&self) -> Option<ScreenId> {
        *self.current.read()
    }
    /// `mayLeaveForLaunch`: no screen, or one that allows toolbar navigation.
    pub fn may_leave_for_launch(&self) -> bool {
        self.current().is_none() || self.allows_navigation.load(Ordering::Acquire)
    }
}

/// A unit of foreground work sent by a [`GuiHandle`].
pub type UiJob = Box<dyn FnOnce(&mut App) + Send>;

/// Commands a [`GuiHandle`] sends to the foreground.
pub enum UiCommand {
    /// Show a screen (`gated`: ask the current screen first). Replies whether it showed.
    GoTo {
        id: ScreenId,
        payload: Option<Box<dyn Any + Send>>,
        gated: bool,
        reply: oneshot::Sender<bool>,
    },
    /// `mayNavigateAway`.
    MayNavigateAway(oneshot::Sender<bool>),
    /// `confirmQuitWhileGamesRun`.
    ConfirmQuit(oneshot::Sender<bool>),
    /// `confirmRestartWhileGamesRun`.
    ConfirmRestart(oneshot::Sender<bool>),
    /// `requestQuit`.
    RequestQuit,
    /// `requestFocus`.
    RequestFocus,
    /// `forceThemeRefresh`.
    ForceThemeRefresh,
    /// `exit`.
    Exit(oneshot::Sender<()>),
    /// A question dialog.
    Ask {
        question: Question,
        multiline: bool,
        reply: oneshot::Sender<Answer>,
    },
    /// An alert (`warning`: warning level, else error).
    Alert {
        text: String,
        warning: bool,
        reply: oneshot::Sender<()>,
    },
    /// An error with a retry button.
    ErrorRetry {
        text: String,
        retry: String,
        reply: oneshot::Sender<bool>,
    },
    /// Any foreground work.
    Run(UiJob),
}

/// The GUI controller global (foreground).
pub struct GuiController {
    window: Option<WindowHandle<Root>>,
    main: Option<Entity<MainWindow>>,
    state: Arc<GuiState>,
    sender: mpsc::UnboundedSender<UiCommand>,
    _drain: Task<()>,
}

impl Global for GuiController {}

/// Why the GUI could not start.
#[derive(Debug, thiserror::Error)]
pub enum ShellError {
    /// `mmcl_ui::init` has not run.
    #[error("the GUI shell is not initialised")]
    NotInitialised,
    /// The OS window could not be opened.
    #[error("the launcher window could not be opened: {0}")]
    Window(String),
}

impl ShellError {
    /// The localized message.
    pub fn message(&self) -> String {
        match self {
            ShellError::NotInitialised => t!("ui.error.shellNotInitialised").into_owned(),
            ShellError::Window(e) => tf!("ui.error.windowOpenFailed", e),
        }
    }
}

/// Installs the controller and its command drain (called by [`crate::init`]).
pub(crate) fn install(cx: &mut App) {
    if cx.has_global::<GuiController>() {
        return;
    }
    let (sender, mut receiver) = mpsc::unbounded::<UiCommand>();
    let drain = cx.spawn(async move |cx| {
        while let Some(command) = receiver.next().await {
            handle_command(command, cx);
        }
    });
    cx.set_global(GuiController {
        window: None,
        main: None,
        state: Arc::new(GuiState::default()),
        sender,
        _drain: drain,
    });
    Navigator::set_handler(cx, Rc::new(navigate_now));
}

fn controller(cx: &App) -> Option<&GuiController> {
    cx.try_global::<GuiController>()
}

/// A [`GuiHandle`] for other threads.
pub fn handle(cx: &App) -> Option<GuiHandle> {
    controller(cx).map(|c| GuiHandle {
        sender: c.sender.clone(),
        state: c.state.clone(),
    })
}

/// The main window's handle.
pub fn main_window_handle(cx: &App) -> Option<AnyWindowHandle> {
    controller(cx).and_then(|c| c.window.map(Into::into))
}

/// The main window's root view.
pub fn main_view(cx: &App) -> Option<Entity<MainWindow>> {
    controller(cx).and_then(|c| c.main.clone())
}

/// Runs `f` with the main window (no-op when it is not open).
pub fn with_main_window(cx: &mut App, f: impl FnOnce(&mut Window, &mut App)) {
    if let Some(handle) = main_window_handle(cx) {
        let _ = handle.update(cx, |_, window, cx| f(window, cx));
    }
}

/// The screen showing now.
pub fn current_screen(cx: &App) -> Option<ScreenHandle> {
    main_view(cx).and_then(|m| m.read(cx).current().cloned())
}

/// The current screen's help topic (`GETTING_STARTED` without one).
pub fn current_help_topic(cx: &App) -> HelpTopic {
    current_screen(cx).map_or(HelpTopic::GettingStarted, |s| s.help_topic(cx))
}

pub(crate) fn set_current(cx: &mut App, id: Option<ScreenId>, allows_navigation: bool) {
    if let Some(c) = controller(cx) {
        *c.state.current.write() = id;
        c.state
            .allows_navigation
            .store(allows_navigation, Ordering::Release);
    }
}

pub(crate) fn set_focused(cx: &mut App, focused: bool) {
    if let Some(c) = controller(cx) {
        c.state.focused.store(focused, Ordering::Release);
    }
}

/// Whether the main window is open.
pub fn is_started(cx: &App) -> bool {
    controller(cx).is_some_and(|c| c.state.is_started())
}

/// The window options for the main window (bounds restored, minimum size, theme backdrop).
pub fn main_window_options(cx: &App) -> (WindowOptions, BoundsTracker) {
    let backend = services::backend(cx);
    let settings = backend.shell_settings();
    let scale = mmcl_ui_kit::scale::percent(cx) as f64 / 100.0;
    let (screens, primary) = main_window::screens(cx);
    let display = primary.or_else(|| screens.first().copied());
    let (aw, ah) = display.map_or((0.0, 0.0), |d| (d.w, d.h));
    let min = size(
        px(placement::clamp_min(placement::MIN_SIZE.0, scale, aw) as f32),
        px(placement::clamp_min(placement::MIN_SIZE.1, scale, ah) as f32),
    );
    let saved = backend.window_bounds();
    let restore = placement::restore(&saved, &screens, primary);
    match restore {
        Restore::MovedOnScreen { .. } => {
            tracing::info!("{}", t!("log.guiWindow.boundsMovedOnScreen"))
        }
        Restore::OffScreen => tracing::debug!("{}", t!("log.guiWindow.boundsOffScreen")),
        _ => {}
    }
    let (bounds, tracker) = match restore.placement() {
        Some((r, maximized)) => {
            let b = Bounds {
                origin: point(px(r.x as f32), px(r.y as f32)),
                size: size(px(r.w as f32), px(r.h as f32)),
            };
            let wb = if maximized {
                WindowBounds::Maximized(b)
            } else {
                WindowBounds::Windowed(b)
            };
            (wb, BoundsTracker::seeded(r, maximized))
        }
        None => {
            let w = placement::clamp_min(placement::PREF_SIZE.0, scale, aw);
            let h = placement::clamp_min(placement::PREF_SIZE.1, scale, ah);
            let area = display.unwrap_or(Rect::new(0.0, 0.0, w, h));
            let r = area.centered(w, h);
            (
                WindowBounds::Windowed(Bounds {
                    origin: point(px(r.x as f32), px(r.y as f32)),
                    size: size(px(r.w as f32), px(r.h as f32)),
                }),
                BoundsTracker::default(),
            )
        }
    };
    let theme = cx.launcher_theme();
    let options = WindowOptions {
        window_bounds: Some(bounds),
        kind: WindowKind::Normal,
        is_resizable: settings.resizable_windows,
        window_min_size: Some(min),
        window_background: theme.window_background(false),
        app_id: Some(
            mmcl_core::AppIdentity::current()
                .linux_desktop_id
                .to_string(),
        ),
        window_decorations: Some(WindowDecorations::Client),
        focus: true,
        show: true,
        ..TitleBar::window_options()
    };
    (options, tracker)
}

/// `startGui`: opens the main window (idempotent). The placeholder shows until the first
/// navigation.
pub fn start_gui(cx: &mut App) -> Result<AnyWindowHandle, ShellError> {
    if !cx.has_global::<GuiController>() {
        return Err(ShellError::NotInitialised);
    }
    if let Some(handle) = main_window_handle(cx) {
        return Ok(handle);
    }
    let (options, tracker) = main_window_options(cx);
    let mut main_entity = None;
    let opened = cx.open_window(options, |window, cx| {
        let main = cx.new(|cx| MainWindow::new(window, cx, tracker));
        main_entity = Some(main.clone());
        cx.new(|cx| Root::new(main, window, cx))
    });
    let handle = match opened {
        Ok(h) => h,
        Err(e) => {
            tracing::error!("{}: {e}", t!("log.controller.startGuiFailed"));
            return Err(ShellError::Window(e.to_string()));
        }
    };
    let main = main_entity.expect("open_window ran its build closure");
    let _ = handle.update(cx, |_, window, cx| {
        crate::theme::probe_mica(window, cx);
        crate::theme::apply_main(window, cx, true);
        window.on_window_should_close(cx, |window, cx| {
            on_close_requested(window, cx);
            false
        });
        set_focused(cx, window.is_window_active());
    });
    let c = cx.global_mut::<GuiController>();
    c.window = Some(handle);
    c.main = Some(main);
    c.state.started.store(true, Ordering::Release);
    Ok(handle.into())
}

/// `prebuildMainGui`: GPUI views are cheap to build, so this is a no-op kept for the API.
pub fn prebuild_main(_cx: &mut App) {}

/// The Navigator handler: show the screen now (starting the window if needed).
fn navigate_now(req: ScreenRequest, cx: &mut App) {
    if let Err(e) = start_gui(cx) {
        tracing::warn!("{} ({})", t!("log.controller.mainNotDisplayed"), e);
        return;
    }
    let (Some(window), Some(main)) = (main_window_handle(cx), main_view(cx)) else {
        return;
    };
    let _ = window.update(cx, |_, window, cx| {
        main.update(cx, |m, cx| m.navigate(req, window, cx))
    });
}

/// Shows a screen now, without the navigation gate (callers that already checked it, and the
/// session flow). Deferred to the end of the effect cycle.
pub fn go_to(cx: &mut App, req: ScreenRequest) {
    if !Navigator::go_to(cx, req) {
        tracing::debug!("{}", t!("log.ui.noShell"));
    }
}

/// `mayNavigateAway`: no screen, or one that allows toolbar navigation and agrees to leave
/// (it may ask about unsaved work).
pub fn may_navigate_away(cx: &mut App) -> Task<bool> {
    let (Some(window), Some(screen)) = (main_window_handle(cx), current_screen(cx)) else {
        return Task::ready(true);
    };
    if !screen.allows_toolbar_navigation(cx) {
        return Task::ready(false);
    }
    window
        .update(cx, |_, window, cx| screen.confirm_navigate_away(window, cx))
        .unwrap_or_else(|_| Task::ready(true))
}

/// `mayLeaveForLaunch`: a launch keeps the screen, so no unsaved-work prompt.
pub fn may_leave_for_launch(cx: &App) -> bool {
    current_screen(cx).is_none_or(|s| s.allows_toolbar_navigation(cx))
}

/// Navigation from outside a screen (shortcuts, menus, tray, toolbar): the gate first.
pub fn navigate_gated(cx: &mut App, req: ScreenRequest) {
    let gate = may_navigate_away(cx);
    cx.spawn(async move |cx| {
        if gate.await {
            cx.update(|cx| go_to(cx, req));
        }
    })
    .detach();
}

/// The quit question (`session.quit.*`).
pub fn quit_question(count: usize) -> Question {
    Question::new(
        t!("session.quit.title"),
        tf!("session.quit.header", count as i64),
        t!("session.quit.body"),
        t!("session.quit.leaveRunning"),
        t!("session.quit.stopGames"),
    )
}

/// The restart question (`session.restart.*`).
pub fn restart_question(count: usize) -> Question {
    Question::new(
        t!("session.restart.title"),
        tf!("session.restart.header", count as i64),
        t!("session.restart.body"),
        t!("session.restart.confirm"),
        t!("dialog.button.cancel"),
    )
}

/// What a quit answer means: `(quit, stop the games first)`.
pub fn quit_decision(answer: Answer) -> (bool, bool) {
    match answer {
        Answer::Closed => (false, false),
        Answer::Primary => (true, false),
        Answer::Secondary => (true, true),
    }
}

/// `confirmQuitWhileGamesRun`: `true` to go ahead. With games running it asks: leave them
/// running, stop them first (cancel + stop each), or stay.
pub async fn confirm_quit_while_games_run(cx: &AsyncApp) -> bool {
    let backend = cx.update(|cx| services::backend(cx));
    let count = backend.active_session_count();
    if count == 0 {
        return true;
    }
    let answer = dialogs::ask(cx, &quit_question(count), None).await;
    let (quit, stop) = quit_decision(answer);
    if stop {
        backend.stop_active_sessions();
    }
    quit
}

/// `confirmRestartWhileGamesRun`: `true` only for "Restart".
pub async fn confirm_restart_while_games_run(cx: &AsyncApp) -> bool {
    let count = cx.update(|cx| services::backend(cx).active_session_count());
    if count == 0 {
        return true;
    }
    dialogs::ask(cx, &restart_question(count), None).await == Answer::Primary
}

/// `requestQuit`: Home Exit, tray/dock Quit, Cmd+Q. Asks when games run, then closes the app.
pub fn request_quit(cx: &mut App) {
    cx.spawn(async move |cx| {
        if confirm_quit_while_games_run(cx).await {
            cx.update(hooks::close_app);
        }
    })
    .detach();
}

/// `requestFocus`: bring the main window forward (tray click, deep links).
pub fn request_focus(cx: &mut App) {
    with_main_window(cx, |window, _| {
        window.activate_window();
        let _ = mmcl_platform::chrome::bring_to_front(&*window);
    });
}

/// `forceThemeRefresh`: re-resolve and re-apply the theme everywhere.
pub fn force_theme_refresh(cx: &mut App) {
    if main_window_handle(cx).is_some() {
        with_main_window(cx, |window, cx| crate::theme::apply_main(window, cx, true));
    } else {
        let configured = services::backend(cx).shell_settings().theme;
        let os_dark = crate::theme::is_dark(cx.window_appearance());
        let mica = cx
            .try_global::<crate::theme::MicaSupport>()
            .copied()
            .unwrap_or_default()
            .0;
        mmcl_ui_kit::set_theme(cx, crate::theme::resolve(&configured, os_dark, mica));
        hooks::run_theme_changed(cx);
    }
}

/// The window close button (and Linux's drawn close button): the screen's own close handler,
/// behind the quit-while-games-run question (guarded bug 26).
pub fn on_close_requested(window: &mut Window, cx: &mut App) {
    let Some(screen) = current_screen(cx) else {
        cx.defer(hooks::close_app);
        return;
    };
    if services::backend(cx).active_session_count() == 0 {
        if screen.on_close_requested(window, cx) == CloseDecision::Proceed {
            cx.defer(hooks::close_app);
        }
        return;
    }
    let Some(handle) = main_window_handle(cx) else {
        return;
    };
    cx.spawn(async move |cx| {
        if !confirm_quit_while_games_run(cx).await {
            return;
        }
        let decision = cx
            .update(|cx| handle.update(cx, |_, window, cx| screen.on_close_requested(window, cx)))
            .unwrap_or(CloseDecision::Proceed);
        if decision == CloseDecision::Proceed {
            cx.update(hooks::close_app);
        }
    })
    .detach();
}

/// `exit`: tears the GUI session down (exit hooks, current screen, saved bounds, the window) so
/// an in-process restart starts clean (guarded bug 30). The controller and its channel stay, so
/// handles held by other threads keep working for the next session.
pub fn exit(cx: &mut App) {
    if !cx.has_global::<GuiController>() {
        return;
    }
    hooks::run_exit(cx);
    let (window, main) = {
        let c = cx.global_mut::<GuiController>();
        c.state.started.store(false, Ordering::Release);
        *c.state.current.write() = None;
        (c.window.take(), c.main.take())
    };
    if let Some(main) = main {
        main.update(cx, |m, cx| m.cleanup(cx));
    }
    if let Some(window) = window {
        let _ = window.update(cx, |_, window, _| window.remove_window());
    }
    Navigator::set_current(cx, None);
    cx.set_global(crate::theme::AppliedChrome::default());
    hooks::set_bottom_dock(cx, None);
}

fn handle_command(command: UiCommand, cx: &mut AsyncApp) {
    match command {
        UiCommand::GoTo {
            id,
            payload,
            gated,
            reply,
        } => {
            let mut req = ScreenRequest::new(id);
            req.payload = payload.map(|p| p as Box<dyn Any>);
            cx.update(|cx| {
                let gate = if gated {
                    may_navigate_away(cx)
                } else {
                    Task::ready(true)
                };
                cx.spawn(async move |cx| {
                    let go = gate.await;
                    if go {
                        cx.update(|cx| {
                            navigate_now(req, cx);
                        });
                    }
                    let shown = go && cx.update(|cx| current_id(cx) == Some(id));
                    let _ = reply.send(shown);
                })
                .detach();
            });
        }
        UiCommand::MayNavigateAway(reply) => {
            let gate = cx.update(may_navigate_away);
            cx.spawn(async move |_| {
                let _ = reply.send(gate.await);
            })
            .detach();
        }
        UiCommand::ConfirmQuit(reply) => {
            cx.spawn(async move |cx| {
                let _ = reply.send(confirm_quit_while_games_run(cx).await);
            })
            .detach();
        }
        UiCommand::ConfirmRestart(reply) => {
            cx.spawn(async move |cx| {
                let _ = reply.send(confirm_restart_while_games_run(cx).await);
            })
            .detach();
        }
        UiCommand::RequestQuit => cx.update(request_quit),
        UiCommand::RequestFocus => cx.update(request_focus),
        UiCommand::ForceThemeRefresh => cx.update(force_theme_refresh),
        UiCommand::Exit(reply) => {
            cx.update(exit);
            let _ = reply.send(());
        }
        UiCommand::Ask {
            question,
            multiline,
            reply,
        } => {
            cx.spawn(async move |cx| {
                let answer = if multiline {
                    dialogs::ask_multiline(cx, &question, None).await
                } else {
                    dialogs::ask(cx, &question, None).await
                };
                let _ = reply.send(answer);
            })
            .detach();
        }
        UiCommand::Alert {
            text,
            warning,
            reply,
        } => {
            cx.spawn(async move |cx| {
                if warning {
                    dialogs::warning(cx, &text, None).await;
                } else {
                    dialogs::error(cx, &text, None).await;
                }
                let _ = reply.send(());
            })
            .detach();
        }
        UiCommand::ErrorRetry { text, retry, reply } => {
            cx.spawn(async move |cx| {
                let _ = reply.send(dialogs::error_retry(cx, &text, &retry, None).await);
            })
            .detach();
        }
        UiCommand::Run(job) => cx.update(job),
    }
}

fn current_id(cx: &App) -> Option<ScreenId> {
    main_view(cx).and_then(|m| main_window::current_id(&m, cx))
}

/// The thread-safe GUI handle (`Send + Sync + Clone`) for code outside the foreground: auth
/// renewal, the launch pipeline, MCP, the tray, deep links. Every call is a message to the
/// foreground; none blocks it.
#[derive(Clone)]
pub struct GuiHandle {
    sender: mpsc::UnboundedSender<UiCommand>,
    state: Arc<GuiState>,
}

impl std::fmt::Debug for GuiHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("GuiHandle")
            .field("started", &self.state.is_started())
            .field("current", &self.state.current())
            .finish()
    }
}

impl GuiHandle {
    fn send(&self, command: UiCommand) -> bool {
        self.sender.unbounded_send(command).is_ok()
    }

    async fn ask_reply<T>(
        &self,
        make: impl FnOnce(oneshot::Sender<T>) -> UiCommand,
        default: T,
    ) -> T {
        let (tx, rx) = oneshot::channel();
        if !self.send(make(tx)) {
            return default;
        }
        rx.await.unwrap_or(default)
    }

    /// Whether the main window is open.
    pub fn is_started(&self) -> bool {
        self.state.is_started()
    }

    /// Whether the main window is focused (gates native toasts).
    pub fn is_launcher_focused(&self) -> bool {
        self.state.is_started() && self.state.is_focused()
    }

    /// The screen showing.
    pub fn current_screen(&self) -> Option<ScreenId> {
        self.state.current()
    }

    /// Shows a screen (no gate). `true` once it is showing.
    pub async fn go_to(&self, id: ScreenId) -> bool {
        self.ask_reply(
            |reply| UiCommand::GoTo {
                id,
                payload: None,
                gated: false,
                reply,
            },
            false,
        )
        .await
    }

    /// Shows a screen with a payload its factory understands (e.g. the Settings category
    /// `usize`, the editor's initial pack).
    pub async fn go_to_with(&self, id: ScreenId, payload: impl Any + Send) -> bool {
        let payload: Box<dyn Any + Send> = Box::new(payload);
        self.ask_reply(
            |reply| UiCommand::GoTo {
                id,
                payload: Some(payload),
                gated: false,
                reply,
            },
            false,
        )
        .await
    }

    /// Shows a screen behind the navigation gate. `false` when the current screen refused.
    pub async fn go_to_gated(&self, id: ScreenId) -> bool {
        self.ask_reply(
            |reply| UiCommand::GoTo {
                id,
                payload: None,
                gated: true,
                reply,
            },
            false,
        )
        .await
    }

    /// `mayNavigateAway` (may show the screen's unsaved-work question).
    pub async fn may_navigate_away(&self) -> bool {
        self.ask_reply(UiCommand::MayNavigateAway, true).await
    }

    /// `mayLeaveForLaunch`.
    pub fn may_leave_for_launch(&self) -> bool {
        self.state.may_leave_for_launch()
    }

    /// `confirmQuitWhileGamesRun`.
    pub async fn confirm_quit_while_games_run(&self) -> bool {
        self.ask_reply(UiCommand::ConfirmQuit, false).await
    }

    /// `confirmRestartWhileGamesRun`.
    pub async fn confirm_restart_while_games_run(&self) -> bool {
        self.ask_reply(UiCommand::ConfirmRestart, false).await
    }

    /// `requestQuit`.
    pub fn request_quit(&self) {
        self.send(UiCommand::RequestQuit);
    }

    /// `requestFocus`.
    pub fn request_focus(&self) {
        self.send(UiCommand::RequestFocus);
    }

    /// `forceThemeRefresh`.
    pub fn force_theme_refresh(&self) {
        self.send(UiCommand::ForceThemeRefresh);
    }

    /// `exit`: tears the GUI session down.
    pub async fn exit(&self) {
        self.ask_reply(UiCommand::Exit, ()).await
    }

    /// A question dialog on the main window.
    pub async fn ask(&self, question: Question) -> Answer {
        self.ask_reply(
            |reply| UiCommand::Ask {
                question,
                multiline: false,
                reply,
            },
            Answer::Closed,
        )
        .await
    }

    /// A multi-line question dialog.
    pub async fn ask_multiline(&self, question: Question) -> Answer {
        self.ask_reply(
            |reply| UiCommand::Ask {
                question,
                multiline: true,
                reply,
            },
            Answer::Closed,
        )
        .await
    }

    /// An error alert.
    pub async fn show_error(&self, text: impl Into<String>) {
        let text = text.into();
        self.ask_reply(
            |reply| UiCommand::Alert {
                text,
                warning: false,
                reply,
            },
            (),
        )
        .await
    }

    /// A warning alert.
    pub async fn show_warning(&self, text: impl Into<String>) {
        let text = text.into();
        self.ask_reply(
            |reply| UiCommand::Alert {
                text,
                warning: true,
                reply,
            },
            (),
        )
        .await
    }

    /// An error with a retry button: `true` to retry.
    pub async fn show_error_retry(
        &self,
        text: impl Into<String>,
        retry: impl Into<String>,
    ) -> bool {
        let (text, retry) = (text.into(), retry.into());
        self.ask_reply(|reply| UiCommand::ErrorRetry { text, retry, reply }, false)
            .await
    }

    /// Runs `job` on the foreground (fire and forget). `false` when the GUI is gone.
    pub fn run(&self, job: impl FnOnce(&mut App) + Send + 'static) -> bool {
        self.send(UiCommand::Run(Box::new(job)))
    }

    /// Runs `job` on the foreground and returns its result (`None` when the GUI is gone).
    pub async fn run_with<R: Send + 'static>(
        &self,
        job: impl FnOnce(&mut App) -> R + Send + 'static,
    ) -> Option<R> {
        let (tx, rx) = oneshot::channel();
        let sent = self.run(move |cx| {
            let _ = tx.send(job(cx));
        });
        if !sent {
            return None;
        }
        rx.await.ok()
    }
}

/// The background appearance a secondary window should open with.
pub fn secondary_background() -> WindowBackgroundAppearance {
    crate::theme::secondary_window_background()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quit_answers() {
        assert_eq!(quit_decision(Answer::Closed), (false, false));
        assert_eq!(quit_decision(Answer::Primary), (true, false));
        assert_eq!(quit_decision(Answer::Secondary), (true, true));
    }

    #[test]
    fn questions_are_localized() {
        let q = quit_question(2);
        assert_eq!(q.title, mmcl_i18n::english("session.quit.title").unwrap());
        assert!(q.header.contains('2'));
        let r = restart_question(1);
        assert_eq!(
            r.primary,
            mmcl_i18n::english("session.restart.confirm").unwrap()
        );
    }

    #[test]
    fn state_defaults() {
        let s = GuiState::default();
        assert!(!s.is_started());
        assert!(s.may_leave_for_launch(), "no screen: a launch may go ahead");
        *s.current.write() = Some(ScreenId::LOGIN);
        assert!(!s.may_leave_for_launch());
        s.allows_navigation.store(true, Ordering::Release);
        assert!(s.may_leave_for_launch());
    }

    fn _assert_send_sync() {
        fn check<T: Send + Sync>() {}
        check::<GuiHandle>();
    }
}

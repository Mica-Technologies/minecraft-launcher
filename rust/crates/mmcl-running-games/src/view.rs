// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `RunningGamesWindow`: one tab per game session, in its own window (default) or docked along
//! the bottom of the main window.
//!
//! [`RunningGamesView`] is the state and the body (header + tabs + selected pane). Exactly one
//! host renders it: the separate window (a gpui-component `Root` over it) or the [`DockHost`]
//! the shell places under its screen. Closing the window or collapsing the dock only hides the
//! view; games keep running and their panes keep logging. GPUI has no window hide, so the window
//! closes and is opened again on the next reveal; the view and its panes live on in between.

use std::collections::HashMap;
use std::rc::Rc;

use gpui::{
    AnyElement, AnyWindowHandle, App, AppContext as _, Context, CursorStyle, DragMoveEvent,
    EmptyView, Entity, FocusHandle, Focusable, Global, InteractiveElement as _, IntoElement,
    KeyBinding, MouseButton, ParentElement, Pixels, Render, ScrollHandle, SharedString,
    StatefulInteractiveElement as _, Styled, Task, TitlebarOptions, Window, WindowOptions, actions,
    div, prelude::FluentBuilder as _, px, size,
};
use gpui_component::button::{Button, ButtonVariants as _};
use gpui_component::menu::{DropdownMenu as _, PopupMenuItem};
use gpui_component::{IconName, Sizable as _};
use mmcl_engine::{EngineEvent, SessionId, SessionInfo, SessionPhase};
use mmcl_i18n::t;
use mmcl_ui_kit::scale::{self, u};
use mmcl_ui_kit::windowing;
use mmcl_ui_kit::{Avatar, Icon, IconButton, MotionExt as _, ThemeExt as _, TooltipExt as _, hsla};
use tokio::sync::broadcast::error::RecvError;

use crate::backend::Backend;
use crate::pane::{GameSessionPane, status_dot_color};
use crate::widgets::{ActionButton, ButtonStyle};

actions!(
    running_games,
    [
        /// The next game's tab (Ctrl+Tab; Right on the focused tab strip). Wraps.
        NextTab,
        /// The previous game's tab (Ctrl+Shift+Tab; Left on the focused tab strip). Wraps.
        PrevTab,
        /// The first tab (Home on the focused tab strip).
        FirstTab,
        /// The last tab (End on the focused tab strip).
        LastTab,
    ]
);

/// The view's key context (Ctrl+Tab / Ctrl+Shift+Tab anywhere inside it).
pub const KEY_CONTEXT: &str = "RunningGames";
/// The tab strip's key context (arrow keys, Home / End while it has focus).
pub const TABS_KEY_CONTEXT: &str = "RunningGamesTabs";

/// Binds the tab keys of a JavaFX `TabPane`: Ctrl+Tab / Ctrl+Shift+Tab inside the view (Ctrl on
/// every platform, as JavaFX used), and Left / Right / Home / End on the focused tab strip.
pub fn bind_keys(cx: &mut App) {
    let view = Some(KEY_CONTEXT);
    let tabs = Some(TABS_KEY_CONTEXT);
    cx.bind_keys([
        KeyBinding::new("ctrl-tab", NextTab, view),
        KeyBinding::new("ctrl-shift-tab", PrevTab, view),
        KeyBinding::new("right", NextTab, tabs),
        KeyBinding::new("left", PrevTab, tabs),
        KeyBinding::new("home", FirstTab, tabs),
        KeyBinding::new("end", LastTab, tabs),
    ]);
}

/// The tab index a tab key selects among `len` tabs from `current`: `delta` steps, wrapping; or
/// the first / last tab for `delta` of `isize::MIN` / `isize::MAX`. `None` with no tabs.
pub fn tab_index_after(len: usize, current: Option<usize>, delta: isize) -> Option<usize> {
    let last = len.checked_sub(1)?;
    match delta {
        isize::MIN => Some(0),
        isize::MAX => Some(last),
        _ => {
            let Some(cur) = current.filter(|c| *c <= last) else {
                return Some(if delta < 0 { last } else { 0 });
            };
            let len = len as isize;
            Some((cur as isize + delta).rem_euclid(len) as usize)
        }
    }
}

/// What a session's phase change asks of the view (Java: `LauncherCore` hides the window once
/// the game is up when "Show console on launch" is off, and `onGameExited` shows the crashed
/// session's tab again).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct PhaseReaction {
    /// Step aside (collapse the dock / hide the window) unless another game still prepares.
    pub step_aside: bool,
    /// Show the session's tab and reveal the view.
    pub reveal: bool,
    /// Re-read the registry: an ended session may have pushed old ended ones out of its
    /// 20-entry history, which sends no removal event of its own.
    pub resync: bool,
}

/// The [`PhaseReaction`] of a session moving from `prev` (`None`: not seen before) to `next`.
pub fn phase_reaction(
    prev: Option<SessionPhase>,
    next: SessionPhase,
    in_game_console: bool,
) -> PhaseReaction {
    let was_active = prev.is_some_and(SessionPhase::is_active);
    PhaseReaction {
        step_aside: !in_game_console
            && prev == Some(SessionPhase::Preparing)
            && next == SessionPhase::Running,
        reveal: was_active && next == SessionPhase::Crashed,
        resync: was_active && !next.is_active(),
    }
}

/// Computes how much height (layout px) the screen above the dock can spare, from the window's
/// height in layout px (`None` = no limit). Installed by the shell with
/// [`set_dock_room_provider`]; read on every dock layout.
pub type DockRoomProvider = Rc<dyn Fn(f32, &Window, &App) -> Option<f32>>;

/// The dock's smallest share of the main window's height.
pub const DOCK_SHARE_MIN: f32 = 0.10;
/// The dock's largest share of the main window's height.
pub const DOCK_SHARE_MAX: f32 = 0.90;
/// The default dock share (`runningGamesDockHeight` 420 per-mille).
pub const DOCK_SHARE_DEFAULT_PER_MILLE: i32 = 420;
/// Room below the header when the dock is expanded (layout px).
pub const DOCK_MIN_BODY: f32 = 240.0;
/// The header strip's height (layout px).
pub const HEADER_HEIGHT: f32 = 44.0;
/// The tab strip's height (layout px).
pub const TAB_STRIP_HEIGHT: f32 = 40.0;
/// The separate window's size at 100 % (layout px).
pub const WINDOW_SIZE: (f32, f32) = (1000.0, 720.0);
/// The separate window's minimum size at 100 % (layout px).
pub const WINDOW_MIN: (f32, f32) = (640.0, 420.0);
/// The key the separate window's bounds are remembered under (this session), so it reopens
/// where and as large as it was when it closed (JavaFX hid the stage and kept them).
pub const BOUNDS_KEY: &str = "runningGames";
/// The resize grip's height (layout px).
pub const GRIP_HEIGHT: f32 = 8.0;

/// Clamps a dock share; NaN (unusable) gives the default.
pub fn clamp_share(share: f32) -> f32 {
    if share.is_nan() {
        return DOCK_SHARE_DEFAULT_PER_MILLE as f32 / 1000.0;
    }
    share.clamp(DOCK_SHARE_MIN, DOCK_SHARE_MAX)
}

/// The dock share after dragging the grip `dragged_up` layout px from a dock of `start_height`,
/// in a window `window_height` tall whose screen can give the dock at most `room`. The height is
/// held to the room **first**: a share above what the screen can spare would show no change while
/// dragging further and then leave the grip dead on the way back down.
pub fn drag_share(start_height: f32, dragged_up: f32, window_height: f32, room: f32) -> f32 {
    if window_height <= 0.0 {
        return clamp_share(f32::NAN);
    }
    let height = (start_height + dragged_up).min(room);
    clamp_share(height / window_height)
}

/// The dock's minimum and preferred heights (layout px), given the window height, the share and
/// the selected pane's minimum. Collapsed, only the header.
pub fn dock_heights(
    expanded: bool,
    window_height: f32,
    share: f32,
    pane_min: Option<f32>,
) -> (f32, f32) {
    if !expanded {
        return (HEADER_HEIGHT, HEADER_HEIGHT);
    }
    let body = match pane_min {
        Some(min) => DOCK_MIN_BODY.max(TAB_STRIP_HEIGHT + min),
        None => DOCK_MIN_BODY,
    };
    let min = HEADER_HEIGHT + body;
    (min, min.max(window_height * share))
}

/// The height the dock takes: always its minimum, its preferred height when the screen above
/// can spare it.
pub fn dock_height(min: f32, pref: f32, room: f32) -> f32 {
    min.max(pref.min(room))
}

/// Which header controls show (pure, tested).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HeaderState {
    pub title: bool,
    pub count: bool,
    pub dock_in: bool,
    pub collapse: bool,
    pub pop_out: bool,
    pub content: bool,
    pub grip: bool,
}

impl HeaderState {
    /// The rules of `refreshHeader`.
    pub fn of(docked: bool, expanded: bool, tabs: usize) -> HeaderState {
        HeaderState {
            // In its own window the window's title already names the view.
            title: docked,
            count: docked && tabs > 0,
            dock_in: !docked,
            collapse: docked,
            pop_out: docked,
            content: !docked || expanded,
            grip: docked && expanded,
        }
    }
}

/// The marker dragged by the resize grip.
#[derive(Debug, Clone, Copy)]
pub struct DockResize;

#[derive(Debug, Clone, Copy)]
struct DragStart {
    y: Pixels,
    height: f32,
}

/// The theme key that decides whether title-bar chrome must be re-applied.
fn theme_key(cx: &App) -> String {
    let theme = cx.launcher_theme().theme;
    format!("{:?}:{}", theme.id, theme.os_dark)
}

/// The Running Games state and body.
pub struct RunningGamesView {
    backend: Backend,
    tabs: Vec<SessionId>,
    infos: HashMap<SessionId, SessionInfo>,
    selected: Option<SessionId>,
    panes: HashMap<SessionId, Entity<GameSessionPane>>,
    docked: bool,
    expanded: bool,
    dock_share: f32,
    dock_room: f32,
    /// The dock's last laid-out height (layout px), where a grip drag starts from.
    dock_height: f32,
    drag: Option<DragStart>,
    applied_theme_key: String,
    main_window: Option<AnyWindowHandle>,
    window: Option<AnyWindowHandle>,
    opening: bool,
    switches: usize,
    shut_down: bool,
    /// The view's focus (Ctrl+Tab works while focus is anywhere inside it).
    focus: FocusHandle,
    /// The tab strip's focus (arrow keys switch tabs).
    tabs_focus: FocusHandle,
    /// The tab strip's scroll state: scrolls the selected tab into view and tells whether the
    /// tabs overflow (then a menu lists every tab).
    tab_scroll: ScrollHandle,
    tabs_overflow: bool,
    /// The tab count the overflow was last measured for.
    overflow_measured: Option<usize>,
    /// Keeps [`BOUNDS_KEY`]'s bounds current while the separate window is open.
    bounds_tracking: Option<gpui::Subscription>,
    _events: Option<Task<()>>,
}

impl RunningGamesView {
    /// The view over `backend`; listens to its event bus.
    pub fn new(backend: Backend, cx: &mut Context<Self>) -> Self {
        let events = backend.events().map(|mut rx| {
            cx.spawn(async move |this, cx| {
                loop {
                    let alive = match rx.recv().await {
                        Ok(event) => this.update(cx, |v, cx| v.on_event(event, cx)).is_ok(),
                        Err(RecvError::Lagged(_)) => this.update(cx, |v, cx| v.sync(cx)).is_ok(),
                        Err(RecvError::Closed) => false,
                    };
                    if !alive {
                        break;
                    }
                }
            })
        });
        let docked = backend.docked();
        let dock_share = clamp_share(backend.dock_height() as f32 / 1000.0);
        let mut view = RunningGamesView {
            backend,
            tabs: Vec::new(),
            infos: HashMap::new(),
            selected: None,
            panes: HashMap::new(),
            docked,
            expanded: true,
            dock_share,
            dock_room: f32::INFINITY,
            dock_height: 0.0,
            drag: None,
            applied_theme_key: theme_key(cx),
            main_window: None,
            window: None,
            opening: false,
            switches: 0,
            shut_down: false,
            focus: cx.focus_handle(),
            tabs_focus: cx.focus_handle(),
            tab_scroll: ScrollHandle::new(),
            tabs_overflow: false,
            overflow_measured: None,
            bounds_tracking: None,
            _events: events,
        };
        view.sync(cx);
        view
    }

    // ------------------------------------------------------------------ state accessors

    /// The tabs, in registry order.
    pub fn tabs(&self) -> &[SessionId] {
        &self.tabs
    }

    /// The selected tab.
    pub fn selected(&self) -> Option<SessionId> {
        self.selected
    }

    /// A tab's pane.
    pub fn pane(&self, id: SessionId) -> Option<&Entity<GameSessionPane>> {
        self.panes.get(&id)
    }

    /// Whether the view is docked in the main window.
    pub fn is_docked(&self) -> bool {
        self.docked
    }

    /// Whether the dock is expanded.
    pub fn is_expanded(&self) -> bool {
        self.expanded
    }

    /// The dock's share of the main window height.
    pub fn dock_share(&self) -> f32 {
        self.dock_share
    }

    /// Whether the separate window is open.
    pub fn is_window_open(&self) -> bool {
        self.window.is_some()
    }

    /// Whether the dock takes room: docked with at least one game.
    pub fn dock_visible(&self) -> bool {
        self.docked && !self.tabs.is_empty() && !self.shut_down
    }

    // ------------------------------------------------------------------ registry

    fn on_event(&mut self, event: EngineEvent, cx: &mut Context<Self>) {
        match event {
            // Every launch shows in its own tab, whoever started it (Java's LauncherCore.play
            // calls showSession for GUI, MCP and deep-link launches alike). Only the GUI's own
            // play flow did this before, so an MCP launch stayed hidden behind the last tab.
            EngineEvent::SessionStarted(info) => self.show_session(info.id, cx),
            EngineEvent::SessionRemoved(_) => self.sync(cx),
            EngineEvent::SessionChanged(info) => {
                let info = (*info).clone();
                match self.panes.get(&info.id).cloned() {
                    Some(pane) => {
                        let (id, next) = (info.id, info.phase);
                        let prev = self.infos.get(&id).map(|i| i.phase);
                        self.infos.insert(id, info.clone());
                        pane.update(cx, |p, cx| p.apply(info, cx));
                        // The setting is read only when the game just came up.
                        let console = prev != Some(SessionPhase::Preparing)
                            || next != SessionPhase::Running
                            || self.backend.in_game_console();
                        let reaction = phase_reaction(prev, next, console);
                        if reaction.resync {
                            self.sync(cx);
                        }
                        if reaction.step_aside {
                            self.hide_unless_preparing(cx);
                        }
                        if reaction.reveal {
                            self.show_session(id, cx);
                        }
                        cx.notify();
                    }
                    None => self.sync(cx),
                }
            }
            EngineEvent::SettingChanged(key) => {
                if key == "runningGamesDocked" {
                    let docked = self.backend.docked();
                    self.set_docked(docked, cx);
                } else if key == "theme" {
                    self.refresh_theme(cx);
                }
            }
            _ => {}
        }
    }

    /// Mirrors the registry: a tab and pane per session, in order; tabs of sessions no longer
    /// listed go (their panes are disposed).
    pub fn sync(&mut self, cx: &mut Context<Self>) {
        if self.shut_down {
            return;
        }
        let sessions = self.backend.sessions();
        let mut order = Vec::with_capacity(sessions.len());
        for info in sessions {
            let id = info.id;
            order.push(id);
            match self.panes.get(&id).cloned() {
                Some(pane) => {
                    let next = info.clone();
                    pane.update(cx, |p, cx| p.apply(next, cx));
                }
                None => {
                    let backend = self.backend.clone();
                    let next = info.clone();
                    let pane = cx.new(|cx| GameSessionPane::new(backend, next, cx));
                    self.panes.insert(id, pane);
                }
            }
            self.infos.insert(id, info);
        }
        let gone: Vec<SessionId> = self
            .panes
            .keys()
            .copied()
            .filter(|id| !order.contains(id))
            .collect();
        for id in gone {
            self.forget(id, cx);
        }
        let old_ix = self
            .selected
            .and_then(|s| self.tabs.iter().position(|t| *t == s));
        self.tabs = order;
        if self.selected.is_none_or(|s| !self.tabs.contains(&s)) {
            self.selected = match old_ix {
                Some(ix) if !self.tabs.is_empty() => Some(self.tabs[ix.min(self.tabs.len() - 1)]),
                _ => self.tabs.first().copied(),
            };
        }
        cx.notify();
    }

    fn forget(&mut self, id: SessionId, cx: &mut Context<Self>) {
        if let Some(pane) = self.panes.remove(&id) {
            pane.update(cx, |p, _| p.dispose());
        }
        self.infos.remove(&id);
        self.tabs.retain(|t| *t != id);
        if self.selected == Some(id) {
            self.selected = None;
        }
    }

    /// Selects a tab (fading its pane in) and scrolls it into view.
    pub fn select(&mut self, id: SessionId, cx: &mut Context<Self>) {
        let Some(ix) = self.tabs.iter().position(|t| *t == id) else {
            return;
        };
        self.tab_scroll.scroll_to_item(ix);
        if self.selected != Some(id) {
            self.selected = Some(id);
            self.switches += 1;
            cx.notify();
        }
    }

    /// A tab key: `delta` tabs on (wrapping), or the first / last tab for `isize::MIN` /
    /// `isize::MAX` (see [`tab_index_after`]).
    pub fn select_relative(&mut self, delta: isize, cx: &mut Context<Self>) {
        let current = self
            .selected
            .and_then(|s| self.tabs.iter().position(|t| *t == s));
        if let Some(ix) = tab_index_after(self.tabs.len(), current, delta) {
            let id = self.tabs[ix];
            self.select(id, cx);
        }
    }

    /// Closes an ended tab (and dismisses its session); an active game's tab stays. Returns
    /// whether it closed.
    pub fn close_tab(&mut self, id: SessionId, cx: &mut Context<Self>) -> bool {
        let active = self.infos.get(&id).is_some_and(|i| i.phase.is_active());
        if active || !self.tabs.contains(&id) {
            return false;
        }
        let ix = self.tabs.iter().position(|t| *t == id);
        self.forget(id, cx);
        if self.selected.is_none() {
            self.selected = ix
                .and_then(|i| self.tabs.get(i.min(self.tabs.len().saturating_sub(1))))
                .copied();
        }
        self.backend.dismiss(id);
        cx.notify();
        true
    }

    // ------------------------------------------------------------------ placement

    /// Shows the session's tab and reveals the view.
    pub fn show_session(&mut self, id: SessionId, cx: &mut Context<Self>) {
        self.sync(cx);
        self.select(id, cx);
        self.reveal(cx);
    }

    /// Docked: expands and focuses the main window. Otherwise: opens or raises the window.
    pub fn reveal(&mut self, cx: &mut Context<Self>) {
        if self.shut_down {
            return;
        }
        if self.docked {
            self.expanded = true;
            self.focus_main_window(cx);
            cx.notify();
            return;
        }
        match self.window {
            Some(handle) => {
                let _ = handle.update(cx, |_, window, _| window.activate_window());
            }
            None => self.open_window(cx),
        }
    }

    /// Collapses the dock or hides the window, unless a game is still preparing (the launch
    /// flow calls this when "Show console on launch" is off).
    pub fn hide_unless_preparing(&mut self, cx: &mut Context<Self>) {
        let preparing = self
            .backend
            .sessions()
            .iter()
            .any(|s| s.phase == SessionPhase::Preparing);
        if preparing {
            return;
        }
        if self.docked {
            self.set_expanded(false, cx);
        } else {
            self.close_window(cx);
        }
    }

    /// Docks into the main window or pops out into the separate window.
    pub fn set_docked(&mut self, dock: bool, cx: &mut Context<Self>) {
        if self.docked == dock {
            return;
        }
        self.docked = dock;
        if self.backend.docked() != dock {
            self.backend.set_docked(dock);
        }
        self.expanded = true;
        if dock {
            self.close_window(cx);
            self.focus_main_window(cx);
        } else {
            self.reveal(cx);
        }
        cx.notify();
    }

    /// Expands or collapses the dock.
    pub fn set_expanded(&mut self, expanded: bool, cx: &mut Context<Self>) {
        self.expanded = expanded;
        cx.notify();
    }

    /// Records the main window (the shell calls this on every screen change), so docking and
    /// revealing can focus it.
    pub fn follow_screen(&mut self, main_window: Option<AnyWindowHandle>, cx: &mut Context<Self>) {
        self.main_window = main_window;
        cx.notify();
    }

    /// The most height (layout px) the screen above can give the dock; `None` = no limit.
    pub fn set_dock_room(&mut self, room: Option<f32>, cx: &mut Context<Self>) {
        let room = room
            .filter(|r| r.is_finite() && *r > 0.0)
            .unwrap_or(f32::INFINITY);
        if room != self.dock_room {
            self.dock_room = room;
            cx.notify();
        }
    }

    /// Sets the dock share (clamped).
    pub fn set_dock_share(&mut self, share: f32, cx: &mut Context<Self>) {
        self.dock_share = clamp_share(share);
        cx.notify();
    }

    /// Re-applies title-bar chrome when the theme or the OS dark mode changed.
    pub fn refresh_theme(&mut self, cx: &mut Context<Self>) {
        let key = theme_key(cx);
        if key == self.applied_theme_key {
            return;
        }
        self.applied_theme_key = key;
        if let Some(handle) = self.window {
            let dark = !cx.launcher_theme().is_light_chrome();
            let _ = handle.update(cx, |_, window, _| {
                mmcl_platform::chrome::apply_title_bar_dark_mode(window, dark);
            });
        }
        cx.notify();
    }

    /// At exit: dispose every pane, close the window, stop listening.
    pub fn shutdown(&mut self, cx: &mut Context<Self>) {
        self.shut_down = true;
        self._events = None;
        for pane in self.panes.values() {
            pane.update(cx, |p, _| p.dispose());
        }
        self.panes.clear();
        self.infos.clear();
        self.tabs.clear();
        self.selected = None;
        self.close_window(cx);
        cx.notify();
    }

    fn focus_main_window(&self, cx: &mut Context<Self>) {
        if let Some(main) = self.main_window {
            let _ = main.update(cx, |_, window, _| window.activate_window());
        }
    }

    fn close_window(&mut self, cx: &mut Context<Self>) {
        self.bounds_tracking = None;
        if let Some(handle) = self.window.take() {
            let _ = handle.update(cx, |_, window, cx| {
                windowing::remember_bounds(BOUNDS_KEY, window, cx);
                window.remove_window();
            });
        }
    }

    fn open_window(&mut self, cx: &mut Context<Self>) {
        if self.opening {
            return;
        }
        self.opening = true;
        let view = cx.entity();
        // Opened after this update ends: the window's root wraps this very entity.
        cx.defer(move |cx| open_window(view, cx));
    }

    // ------------------------------------------------------------------ grip

    fn start_drag(&mut self, y: Pixels) {
        self.drag = Some(DragStart {
            y,
            height: self.dock_height,
        });
    }

    fn drag_to(&mut self, y: Pixels, window_height: f32, cx: &mut Context<Self>) {
        let Some(start) = self.drag else { return };
        let factor = scale_factor(cx);
        let dragged_up = (start.y - y).as_f32() / factor;
        let share = drag_share(start.height, dragged_up, window_height, self.dock_room);
        self.set_dock_share(share, cx);
    }

    fn end_drag(&mut self) {
        if self.drag.take().is_some() {
            self.backend
                .set_dock_height((self.dock_share * 1000.0).round() as i32);
        }
    }

    fn reset_share(&mut self, cx: &mut Context<Self>) {
        self.set_dock_share(DOCK_SHARE_DEFAULT_PER_MILLE as f32 / 1000.0, cx);
        self.backend.set_dock_height(DOCK_SHARE_DEFAULT_PER_MILLE);
    }

    // ------------------------------------------------------------------ rendering

    fn render_header(&self, cx: &mut Context<Self>) -> AnyElement {
        let t = cx.tokens();
        let state = HeaderState::of(self.docked, self.expanded, self.tabs.len());
        let entity = cx.entity();
        let toggle = entity.clone();
        let dock = entity.clone();
        let collapse = entity.clone();
        let pop = entity.clone();
        let header = div()
            .flex()
            .items_center()
            .gap(u(8.0))
            .min_h(u(HEADER_HEIGHT))
            .pl(u(16.0))
            .pr(u(12.0))
            .py(u(4.0))
            .when(self.docked, |el| el.bg(hsla(t.md_surface_container)))
            .child(
                // Docked, a click anywhere on the strip (but its buttons) collapses or expands.
                div()
                    .id("running-games-strip")
                    .flex_1()
                    .flex()
                    .items_center()
                    .gap(u(8.0))
                    .h_full()
                    .min_h(u(36.0))
                    .when(self.docked, |el| {
                        el.cursor_pointer().on_click(move |_, _, cx| {
                            toggle.update(cx, |v, cx| {
                                let next = !v.expanded;
                                v.set_expanded(next, cx);
                            });
                        })
                    })
                    .when(state.title, |el| {
                        el.child(
                            div()
                                .text_size(u(14.0))
                                .font_weight(gpui::FontWeight::SEMIBOLD)
                                .text_color(hsla(t.md_on_surface))
                                .child(t!("session.window.title").into_owned()),
                        )
                    })
                    .when(state.count, |el| {
                        el.child(
                            div()
                                .px(u(8.0))
                                .py(u(1.0))
                                .rounded_full()
                                .bg(hsla(t.md_secondary_container))
                                .text_color(hsla(t.md_on_secondary_container))
                                .text_size(u(12.0))
                                .font_weight(gpui::FontWeight::SEMIBOLD)
                                .child(self.tabs.len().to_string()),
                        )
                    }),
            )
            .when(state.dock_in, |el| {
                el.child(
                    ActionButton::new(
                        "running-games-dock-in",
                        t!("session.dock.dockIn").into_owned(),
                        ButtonStyle::Tonal,
                    )
                    .on_click(move |_, cx| dock.update(cx, |v, cx| v.set_docked(true, cx))),
                )
            })
            .when(state.collapse, |el| {
                let (icon, key) = if self.expanded {
                    (Icon::ArrowDown, "session.dock.collapse")
                } else {
                    (Icon::ArrowUp, "session.dock.expand")
                };
                el.child(
                    IconButton::new("running-games-collapse", icon, t!(key).into_owned()).on_click(
                        move |_, cx| {
                            collapse.update(cx, |v, cx| {
                                let next = !v.expanded;
                                v.set_expanded(next, cx);
                            })
                        },
                    ),
                )
            })
            .when(state.pop_out, |el| {
                el.child(
                    IconButton::new(
                        "running-games-pop-out",
                        Icon::OpenExternal,
                        t!("session.dock.popOut").into_owned(),
                    )
                    .on_click(move |_, cx| pop.update(cx, |v, cx| v.set_docked(false, cx))),
                )
            });
        let grip = state.grip.then(|| {
            let press = entity.clone();
            let reset = entity.clone();
            let handle_color = hsla(t.md_outline_variant);
            let handle_active = hsla(t.md_on_surface_variant);
            div()
                .id("running-games-grip")
                .absolute()
                .top_0()
                .left_0()
                .right_0()
                .h(u(GRIP_HEIGHT))
                .flex()
                .items_center()
                .justify_center()
                .cursor(CursorStyle::ResizeUpDown)
                .group("running-games-grip")
                .launcher_tooltip(t!("session.dock.resize").into_owned())
                .on_mouse_down(MouseButton::Left, move |event, _, cx| {
                    cx.stop_propagation();
                    press.update(cx, |v, _| v.start_drag(event.position.y));
                })
                .on_click(move |event, _, cx| {
                    if event.click_count() == 2 {
                        reset.update(cx, |v, cx| v.reset_share(cx));
                    }
                })
                .on_drag(DockResize, |_, _, _, cx| cx.new(|_| EmptyView))
                .child(
                    div()
                        .w(u(36.0))
                        .h(u(4.0))
                        .rounded_full()
                        .bg(handle_color)
                        .group_hover("running-games-grip", move |s| s.bg(handle_active)),
                )
        });
        div()
            .relative()
            .flex_none()
            .child(header)
            .children(grip)
            .into_any_element()
    }

    fn render_tab(&self, id: SessionId, cx: &mut Context<Self>) -> Option<AnyElement> {
        let info = self.infos.get(&id)?;
        let t = cx.tokens();
        let selected = self.selected == Some(id);
        let closable = !info.phase.is_active();
        let entity = cx.entity();
        let select = entity.clone();
        let close = entity.clone();
        let strip_focus = self.tabs_focus.clone();
        let fg = if selected {
            hsla(t.md_on_surface)
        } else {
            hsla(t.md_on_surface_variant)
        };
        Some(
            div()
                .id(SharedString::from(format!("running-games-tab-{id}")))
                .role(gpui::Role::Tab)
                .aria_label(info.pack_name.clone())
                .aria_selected(selected)
                .flex()
                .flex_none()
                .items_center()
                .gap(u(6.0))
                .h(u(TAB_STRIP_HEIGHT))
                .pl(u(12.0))
                .pr(u(if closable { 4.0 } else { 12.0 }))
                .border_b_2()
                .border_color(if selected {
                    hsla(t.md_primary)
                } else {
                    gpui::transparent_black()
                })
                .cursor_pointer()
                .hover(|s| s.bg(hsla(t.md_state_hover_on_surface)))
                .text_size(u(13.0))
                .font_weight(if selected {
                    gpui::FontWeight::SEMIBOLD
                } else {
                    gpui::FontWeight::NORMAL
                })
                .text_color(fg)
                .on_click(move |_, window, cx| {
                    strip_focus.focus(window, cx);
                    select.update(cx, |v, cx| v.select(id, cx));
                })
                .child(
                    div()
                        .flex_none()
                        .size(u(8.0))
                        .rounded_full()
                        .bg(status_dot_color(info.phase, t)),
                )
                .when_some(info.account_uuid.clone(), |el, uuid| {
                    el.child(Avatar::new(
                        SharedString::from(format!("running-games-tab-avatar-{id}")),
                        Some(uuid),
                        16.0,
                    ))
                })
                .child(info.pack_name.clone())
                .when(closable, |el| {
                    el.child(
                        div()
                            .id(SharedString::from(format!("running-games-tab-close-{id}")))
                            .flex_none()
                            .size(u(24.0))
                            .rounded_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .hover(|s| s.bg(hsla(t.md_state_hover_on_surface)))
                            .launcher_tooltip(t!("session.tab.close").into_owned())
                            .on_click(move |_, _, cx| {
                                cx.stop_propagation();
                                close.update(cx, |v, cx| {
                                    v.close_tab(id, cx);
                                });
                            })
                            .child(Icon::Close.svg(14.0).text_color(fg)),
                    )
                })
                .into_any_element(),
        )
    }

    fn render_content(&self, cx: &mut Context<Self>) -> AnyElement {
        let t = cx.tokens();
        if self.tabs.is_empty() {
            return div()
                .flex_1()
                .flex()
                .items_center()
                .justify_center()
                .p(u(24.0))
                .text_color(hsla(t.color_text_muted))
                .child(t!("session.window.empty").into_owned())
                .into_any_element();
        }
        let tabs: Vec<AnyElement> = self
            .tabs
            .iter()
            .filter_map(|id| self.render_tab(*id, cx))
            .collect();
        let pane = self.selected.and_then(|id| self.panes.get(&id)).cloned();
        let body: AnyElement = match pane {
            Some(pane) => {
                let el = div().flex_1().min_h_0().size_full().child(pane);
                if self.switches > 0 {
                    el.fade_through(("running-games-switch", self.switches))
                } else {
                    el.into_any_element()
                }
            }
            None => div().flex_1().into_any_element(),
        };
        let strip = div()
            .id("running-games-tabs")
            .role(gpui::Role::TabList)
            .key_context(TABS_KEY_CONTEXT)
            .track_focus(&self.tabs_focus)
            .on_action(cx.listener(|v, _: &NextTab, _w, cx| v.select_relative(1, cx)))
            .on_action(cx.listener(|v, _: &PrevTab, _w, cx| v.select_relative(-1, cx)))
            .on_action(cx.listener(|v, _: &FirstTab, _w, cx| v.select_relative(isize::MIN, cx)))
            .on_action(cx.listener(|v, _: &LastTab, _w, cx| v.select_relative(isize::MAX, cx)))
            .flex()
            .flex_1()
            .min_w_0()
            .items_end()
            .overflow_x_scroll()
            .track_scroll(&self.tab_scroll)
            .children(tabs);
        div()
            .flex_1()
            .min_h_0()
            .flex()
            .flex_col()
            .child(
                div()
                    .flex()
                    .flex_none()
                    .items_center()
                    .px(u(8.0))
                    .border_b_1()
                    .border_color(hsla(t.md_outline_variant))
                    .child(strip)
                    .when(self.tabs_overflow, |el| {
                        el.child(self.render_overflow_menu(cx))
                    }),
            )
            .child(body)
            .into_any_element()
    }

    /// The drop-down listing every tab when they overflow the strip (JavaFX `TabPane`).
    fn render_overflow_menu(&self, cx: &mut Context<Self>) -> AnyElement {
        let entries: Vec<(SessionId, SharedString, bool)> = self
            .tabs
            .iter()
            .filter_map(|id| {
                let info = self.infos.get(id)?;
                Some((
                    *id,
                    info.pack_name.clone().into(),
                    self.selected == Some(*id),
                ))
            })
            .collect();
        let entity = cx.entity().downgrade();
        Button::new("running-games-tab-overflow")
            .ghost()
            .small()
            .icon(IconName::ChevronDown)
            .tooltip(t!("session.tab.overflow").into_owned())
            .dropdown_menu(move |mut menu, _, _| {
                for (id, name, selected) in &entries {
                    let target = entity.clone();
                    let id = *id;
                    menu = menu.item(
                        PopupMenuItem::new(name.clone())
                            .checked(*selected)
                            .on_click(move |_, _, cx| {
                                let _ = target.update(cx, |v, cx| v.select(id, cx));
                            }),
                    );
                }
                menu
            })
            .into_any_element()
    }

    fn selected_pane_min(&self, cx: &App) -> Option<f32> {
        self.selected
            .and_then(|id| self.panes.get(&id))
            .map(|p| p.read(cx).min_height())
    }
}

impl Render for RunningGamesView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        if self.window == Some(window.window_handle()) {
            // The separate window's minimum follows the interface scale (cached per window,
            // so this is cheap on every frame).
            windowing::apply_scaled_min_size(window, window_min_base(), cx);
        }
        // Whether the tabs overflow is known only after a layout: measure again on the next
        // frame whenever the tab count changed (window resizes re-render on their own).
        self.tabs_overflow = self.tabs.len() > 1 && self.tab_scroll.max_offset().x > px(0.0);
        if self.overflow_measured != Some(self.tabs.len()) {
            self.overflow_measured = Some(self.tabs.len());
            cx.on_next_frame(window, |_, _, cx| cx.notify());
        }
        let state = HeaderState::of(self.docked, self.expanded, self.tabs.len());
        let header = self.render_header(cx);
        let content = state.content.then(|| self.render_content(cx));
        let floor = cx.launcher_theme().solid_bg(true);
        div()
            .id("running-games")
            .key_context(KEY_CONTEXT)
            .track_focus(&self.focus)
            .on_action(cx.listener(|v, _: &NextTab, _w, cx| v.select_relative(1, cx)))
            .on_action(cx.listener(|v, _: &PrevTab, _w, cx| v.select_relative(-1, cx)))
            .size_full()
            .flex()
            .flex_col()
            .bg(if self.docked { hsla(t.color_bg) } else { floor })
            .text_color(hsla(t.md_on_surface))
            .font_family(mmcl_ui_kit::fonts::UI_FAMILY)
            .child(header)
            .children(content)
    }
}

impl Focusable for RunningGamesView {
    fn focus_handle(&self, _cx: &App) -> FocusHandle {
        self.focus.clone()
    }
}

/// The launcher icon for the window (Java `WindowIcons.apply`). GPUI takes it from
/// `WindowOptions` on X11 only; Windows windows already carry the executable's icon (resource 1,
/// GPUI's window-class icon), macOS uses the bundle icon and Wayland the `app_id`'s desktop
/// file. Decoded once.
pub fn window_icon() -> Option<std::sync::Arc<image::RgbaImage>> {
    static ICON: std::sync::OnceLock<Option<std::sync::Arc<image::RgbaImage>>> =
        std::sync::OnceLock::new();
    ICON.get_or_init(|| {
        let bytes = mmcl_ui_kit::assets::Assets::launcher_asset(mmcl_ui_kit::assets::APP_ICON)?;
        image::load_from_memory_with_format(bytes, image::ImageFormat::Png)
            .map(|img| std::sync::Arc::new(img.to_rgba8()))
            .ok()
    })
    .clone()
}

/// The separate window's minimum at 100 % ([`WINDOW_MIN`]).
fn window_min_base() -> gpui::Size<Pixels> {
    size(px(WINDOW_MIN.0), px(WINDOW_MIN.1))
}

/// The interface scale factor (1.0 at 100 %).
fn scale_factor(cx: &App) -> f32 {
    (scale::percent(cx) as f32 / 100.0).max(0.01)
}

fn open_window(view: Entity<RunningGamesView>, cx: &mut App) {
    view.update(cx, |v, _| v.opening = false);
    if view.read(cx).window.is_some() || view.read(cx).shut_down {
        return;
    }
    let f = scale_factor(cx);
    let theme = cx.launcher_theme();
    let title: SharedString = t!("session.window.title").into_owned().into();
    let identity = mmcl_core::AppIdentity::current();
    let options = WindowOptions {
        // Where and as large as it was when it last closed, else centred at the default size.
        window_bounds: Some(windowing::recall_or_centered(
            cx,
            BOUNDS_KEY,
            size(px(WINDOW_SIZE.0 * f), px(WINDOW_SIZE.1 * f)),
        )),
        titlebar: Some(TitlebarOptions {
            title: Some(title),
            ..Default::default()
        }),
        // The opening minimum; the runtime one (`apply_scaled_min_size`) replaces it and
        // follows later scale changes. Kept for Wayland, where the runtime one is a no-op.
        window_min_size: Some(windowing::scaled_min_size(
            window_min_base(),
            scale::percent(cx),
        )),
        icon: window_icon(),
        window_background: theme.window_background(true),
        app_id: Some(
            identity
                .linux_desktop_id
                .trim_end_matches(".desktop")
                .to_string(),
        ),
        ..Default::default()
    };
    let closer = view.clone();
    let root_view = view.clone();
    let opened = cx.open_window(options, move |window, cx| {
        window.on_window_should_close(cx, move |window, cx| {
            // Closing only hides the view: forget the window (remembering where it was), keep
            // the panes.
            windowing::remember_bounds(BOUNDS_KEY, window, cx);
            closer.update(cx, |v, cx| {
                v.window = None;
                v.bounds_tracking = None;
                cx.notify();
            });
            true
        });
        mmcl_ui_kit::scale::apply_to_window(window, cx);
        root_view.update(cx, |v, cx| {
            v.bounds_tracking = Some(windowing::track_bounds(BOUNDS_KEY, window, cx));
        });
        cx.new(|cx| gpui_component::Root::new(root_view, window, cx))
    });
    match opened {
        Ok(handle) => {
            let any: AnyWindowHandle = handle.into();
            let dark = !theme.is_light_chrome();
            let focus = view.read(cx).focus.clone();
            let _ = any.update(cx, |_, window, cx| {
                mmcl_platform::chrome::apply_title_bar_dark_mode(window, dark);
                windowing::apply_scaled_min_size(window, window_min_base(), cx);
                window.activate_window();
                // Ctrl+Tab works at once, before anything inside was clicked.
                focus.focus(window, cx);
            });
            view.update(cx, |v, cx| {
                v.window = Some(any);
                cx.notify();
            });
        }
        Err(e) => tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.runningGames.windowOpenFailed", e.to_string())
        ),
    }
}

// ---------------------------------------------------------------------------------------------
// DockHost

/// The docked body's host: the shell places it along the bottom of the main window, under its
/// screen. It takes no room unless the view is docked with at least one game; collapsed it is
/// only the header; expanded it takes its share of the window (at least its minimum, at most
/// what the screen above can spare, see [`RunningGamesView::set_dock_room`]).
pub struct DockHost {
    view: Entity<RunningGamesView>,
    _observe: gpui::Subscription,
}

/// The installed [`DockRoomProvider`] (a global, so it outlives an in-process restart that
/// installs a new view and dock).
#[derive(Clone)]
struct DockRoomHook(DockRoomProvider);

impl Global for DockRoomHook {}

impl DockHost {
    /// A host for `view`.
    pub fn new(view: Entity<RunningGamesView>, cx: &mut Context<Self>) -> Self {
        let observe = cx.observe(&view, |_, _, cx| cx.notify());
        DockHost {
            view,
            _observe: observe,
        }
    }

    /// The view it hosts.
    pub fn view(&self) -> &Entity<RunningGamesView> {
        &self.view
    }

    /// The dock's current height in layout px (0 when it takes no room).
    pub fn current_height(&self, window_height: f32, cx: &App) -> f32 {
        let v = self.view.read(cx);
        if !v.dock_visible() {
            return 0.0;
        }
        let (min, pref) = dock_heights(
            v.expanded,
            window_height,
            v.dock_share,
            v.selected_pane_min(cx),
        );
        dock_height(min, pref, v.dock_room)
    }
}

impl Render for DockHost {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let factor = scale_factor(cx);
        let window_height = window.viewport_size().height.as_f32() / factor;
        if let Some(DockRoomHook(provider)) = cx.try_global::<DockRoomHook>().cloned() {
            let room = provider(window_height, window, cx);
            self.view.update(cx, |v, cx| v.set_dock_room(room, cx));
        }
        let height = self.current_height(window_height, cx);
        if height <= 0.0 {
            return div().id("running-games-dock").into_any_element();
        }
        self.view.update(cx, |v, _| v.dock_height = height);
        let t = cx.tokens();
        let moving = self.view.clone();
        let up = self.view.clone();
        let up_out = self.view.clone();
        div()
            .id("running-games-dock")
            .flex_none()
            .w_full()
            .h(u(height))
            .overflow_hidden()
            .border_t_1()
            .border_color(hsla(t.md_outline_variant))
            .on_drag_move(move |event: &DragMoveEvent<DockResize>, window, cx| {
                let factor = scale_factor(cx);
                let window_height = window.viewport_size().height.as_f32() / factor;
                let y = event.event.position.y;
                moving.update(cx, |v, cx| v.drag_to(y, window_height, cx));
            })
            .on_mouse_up(MouseButton::Left, move |_, _, cx| {
                up.update(cx, |v, _| v.end_drag());
            })
            .on_mouse_up_out(MouseButton::Left, move |_, _, cx| {
                up_out.update(cx, |v, _| v.end_drag());
            })
            .child(self.view.clone())
            .into_any_element()
    }
}

// ---------------------------------------------------------------------------------------------
// The global and the public API

/// The Running Games singleton (installed by [`crate::init`]).
pub struct RunningGames {
    view: Entity<RunningGamesView>,
    dock: Entity<DockHost>,
}

impl Global for RunningGames {}

impl RunningGames {
    /// Installs the singleton over `backend` (replacing an earlier one, which is shut down).
    pub fn install(cx: &mut App, backend: Backend) -> Entity<RunningGamesView> {
        if let Some(old) = cx.try_global::<RunningGames>().map(|g| g.view.clone()) {
            old.update(cx, |v, cx| v.shutdown(cx));
        }
        let view = cx.new(|cx| RunningGamesView::new(backend, cx));
        let dock_view = view.clone();
        let dock = cx.new(|cx| DockHost::new(dock_view, cx));
        cx.set_global(RunningGames {
            view: view.clone(),
            dock,
        });
        view
    }

    /// The view, when installed.
    pub fn view(cx: &App) -> Option<Entity<RunningGamesView>> {
        cx.try_global::<RunningGames>().map(|g| g.view.clone())
    }

    /// The dock host, when installed.
    pub fn dock(cx: &App) -> Option<Entity<DockHost>> {
        cx.try_global::<RunningGames>().map(|g| g.dock.clone())
    }

    fn with(cx: &mut App, f: impl FnOnce(&mut RunningGamesView, &mut Context<RunningGamesView>)) {
        if let Some(view) = Self::view(cx) {
            view.update(cx, f);
        }
    }
}

/// Shows a session's tab and reveals the view.
pub fn show_session(cx: &mut App, id: SessionId) {
    RunningGames::with(cx, |v, cx| v.show_session(id, cx));
}

/// Reveals the view (window or dock).
pub fn show_window(cx: &mut App) {
    RunningGames::with(cx, |v, cx| v.reveal(cx));
}

/// Collapses the dock / hides the window unless a game is still preparing.
pub fn hide_unless_preparing(cx: &mut App) {
    RunningGames::with(cx, |v, cx| v.hide_unless_preparing(cx));
}

/// Re-applies the theme's title-bar chrome when the theme key changed (cheap otherwise: the shell
/// calls it on every screen switch).
pub fn refresh_theme(cx: &mut App) {
    RunningGames::with(cx, |v, cx| v.refresh_theme(cx));
}

/// Records the main window after a screen change (the shell keeps rendering [`dock_host`]).
pub fn follow_screen(cx: &mut App, main_window: Option<AnyWindowHandle>) {
    RunningGames::with(cx, |v, cx| v.follow_screen(main_window, cx));
}

/// Tells the dock how much height the screen above can spare (layout px; `None` = no limit).
pub fn set_dock_room(cx: &mut App, room: Option<f32>) {
    RunningGames::with(cx, |v, cx| v.set_dock_room(room, cx));
}

/// Installs how the dock learns the room the screen above can spare, from the main window's
/// height in layout px: Java's `ScaledRoot.dockRoom()` (window height minus the shell chrome
/// and the current screen's minimum height). Evaluated on every dock layout. The shell
/// installs it once (before or after [`crate::init`]); until then the dock takes its full
/// share.
pub fn set_dock_room_provider(
    cx: &mut App,
    provider: impl Fn(f32, &Window, &App) -> Option<f32> + 'static,
) {
    cx.set_global(DockRoomHook(Rc::new(provider)));
    if let Some(dock) = RunningGames::dock(cx) {
        dock.update(cx, |_, cx| cx.notify());
    }
}

/// Docks into the main window or pops out.
pub fn set_docked(cx: &mut App, docked: bool) {
    RunningGames::with(cx, |v, cx| v.set_docked(docked, cx));
}

/// The dock host the shell renders under its screen.
pub fn dock_host(cx: &App) -> Option<Entity<DockHost>> {
    RunningGames::dock(cx)
}

/// At exit: disposes the panes, closes the window, drops the singleton.
pub fn shutdown(cx: &mut App) {
    if let Some(view) = RunningGames::view(cx) {
        view.update(cx, |v, cx| v.shutdown(cx));
        cx.remove_global::<RunningGames>();
    }
}

/// Whether the view takes room docked (for the shell's layout).
pub fn dock_visible(cx: &App) -> bool {
    RunningGames::view(cx).is_some_and(|v| v.read(cx).dock_visible())
}

#[cfg(test)]
mod tests {
    //! Port of `RunningGamesDockResizeTest`, plus the header and dock-size rules.
    use super::*;

    #[test]
    fn the_window_minimum_follows_the_interface_scale() {
        assert_eq!(window_min_base(), size(px(640.0), px(420.0)));
        assert_eq!(
            windowing::scaled_min_size(window_min_base(), 150),
            size(px(960.0), px(630.0))
        );
    }

    #[gpui::test]
    fn the_window_reopens_with_its_last_bounds(cx: &mut gpui::TestAppContext) {
        cx.update(|cx| {
            let default = size(px(WINDOW_SIZE.0), px(WINDOW_SIZE.1));
            assert_eq!(
                windowing::recall_or_centered(cx, BOUNDS_KEY, default)
                    .get_bounds()
                    .size,
                default
            );
            let closed_at =
                gpui::Bounds::new(gpui::point(px(30.0), px(40.0)), size(px(800.0), px(600.0)));
            cx.default_global::<windowing::BoundsMemory>()
                .set(BOUNDS_KEY, gpui::WindowBounds::Windowed(closed_at));
            let reopened = windowing::recall_or_centered(cx, BOUNDS_KEY, default);
            assert_eq!(reopened.get_bounds().size, closed_at.size);
        });
    }

    const WINDOW: f32 = 1000.0;
    const ROOM: f32 = 600.0;

    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-6
    }

    #[test]
    fn dragging_up_grows_the_dock_and_down_shrinks_it() {
        assert!(close(drag_share(420.0, 100.0, WINDOW, ROOM), 0.52));
        assert!(close(drag_share(420.0, -100.0, WINDOW, ROOM), 0.32));
    }

    #[test]
    fn the_dock_never_takes_more_than_the_screen_above_can_spare() {
        assert!(close(drag_share(420.0, 400.0, WINDOW, ROOM), 0.60));
    }

    #[test]
    fn the_share_stays_within_its_bounds() {
        assert_eq!(drag_share(420.0, -900.0, WINDOW, ROOM), DOCK_SHARE_MIN);
        assert_eq!(drag_share(420.0, 900.0, WINDOW, f32::MAX), DOCK_SHARE_MAX);
        assert_eq!(clamp_share(5.0), DOCK_SHARE_MAX);
        assert_eq!(clamp_share(-1.0), DOCK_SHARE_MIN);
    }

    #[test]
    fn an_unusable_value_falls_back_to_the_default() {
        assert!(close(clamp_share(f32::NAN), 0.42));
        assert!(close(drag_share(420.0, 50.0, 0.0, ROOM), 0.42));
    }

    #[test]
    fn header_controls_follow_the_mode() {
        let window = HeaderState::of(false, true, 2);
        assert!(window.dock_in && !window.title && !window.count && !window.collapse);
        assert!(!window.pop_out && window.content && !window.grip);
        let docked = HeaderState::of(true, true, 2);
        assert!(docked.title && docked.count && docked.collapse && docked.pop_out);
        assert!(!docked.dock_in && docked.content && docked.grip);
        let collapsed = HeaderState::of(true, false, 1);
        assert!(!collapsed.content && !collapsed.grip && collapsed.count);
        assert!(!HeaderState::of(true, true, 0).count);
    }

    #[test]
    fn the_dock_sizes_to_its_share_but_never_below_its_minimum() {
        let (min, pref) = dock_heights(false, WINDOW, 0.42, Some(300.0));
        assert_eq!((min, pref), (HEADER_HEIGHT, HEADER_HEIGHT));
        let (min, pref) = dock_heights(true, WINDOW, 0.42, None);
        assert_eq!(min, HEADER_HEIGHT + DOCK_MIN_BODY);
        assert!(close(pref, 420.0));
        // A tall pane minimum wins over the 240 px floor, so Stop and Kill always show.
        let (min, _) = dock_heights(true, WINDOW, 0.1, Some(300.0));
        assert_eq!(min, HEADER_HEIGHT + TAB_STRIP_HEIGHT + 300.0);
        assert_eq!(dock_height(300.0, 500.0, 400.0), 400.0);
        assert_eq!(
            dock_height(300.0, 500.0, 100.0),
            300.0,
            "always the minimum"
        );
        assert_eq!(dock_height(300.0, 500.0, f32::INFINITY), 500.0);
    }

    #[test]
    fn the_window_icon_is_the_launcher_logo() {
        let icon = window_icon().expect("the bundled launcher icon decodes");
        assert!(icon.width() > 0 && icon.height() > 0);
    }

    #[test]
    fn tab_keys_wrap_and_jump_to_the_ends() {
        assert_eq!(tab_index_after(0, None, 1), None);
        assert_eq!(tab_index_after(3, Some(0), 1), Some(1));
        assert_eq!(tab_index_after(3, Some(2), 1), Some(0), "Ctrl+Tab wraps");
        assert_eq!(
            tab_index_after(3, Some(0), -1),
            Some(2),
            "Ctrl+Shift+Tab wraps"
        );
        assert_eq!(tab_index_after(3, None, 1), Some(0));
        assert_eq!(tab_index_after(3, None, -1), Some(2));
        assert_eq!(
            tab_index_after(3, Some(9), 1),
            Some(0),
            "a stale index restarts"
        );
        assert_eq!(tab_index_after(3, Some(1), isize::MIN), Some(0));
        assert_eq!(tab_index_after(3, Some(1), isize::MAX), Some(2));
    }

    #[test]
    fn the_view_steps_aside_once_a_game_is_up_and_returns_on_a_crash() {
        use SessionPhase::*;
        // "Show console on launch" off: Preparing -> Running steps aside.
        let up = phase_reaction(Some(Preparing), Running, false);
        assert!(up.step_aside && !up.reveal && !up.resync);
        // On, or any other change: it stays.
        assert!(!phase_reaction(Some(Preparing), Running, true).step_aside);
        assert!(!phase_reaction(None, Running, false).step_aside);
        assert!(!phase_reaction(Some(Running), Running, false).step_aside);
        // A crash of a live game brings the view back and re-reads the registry.
        let crash = phase_reaction(Some(Running), Crashed, true);
        assert!(crash.reveal && crash.resync && !crash.step_aside);
        assert!(phase_reaction(Some(Preparing), Crashed, false).reveal);
        assert!(
            !phase_reaction(Some(Crashed), Crashed, true).reveal,
            "only once"
        );
        // Other endings only re-read the registry (its 20-entry history may drop old tabs).
        for end in [Exited, Cancelled, Failed] {
            let r = phase_reaction(Some(Running), end, true);
            assert!(r.resync && !r.reveal && !r.step_aside, "{end:?}");
        }
        assert_eq!(
            phase_reaction(Some(Exited), Exited, true),
            PhaseReaction::default()
        );
    }

    #[test]
    fn every_window_key_resolves() {
        for key in [
            "session.window.title",
            "session.window.empty",
            "session.dock.dockIn",
            "session.dock.popOut",
            "session.dock.resize",
            "session.dock.collapse",
            "session.dock.expand",
            "session.tab.close",
            "session.tab.overflow",
            "log.runningGames.windowOpenFailed",
            "log.runningGames.settingSaveFailed",
            "log.runningGames.cancelFailed",
        ] {
            assert!(mmcl_i18n::contains(key), "{key}");
        }
    }
}

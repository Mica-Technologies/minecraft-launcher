// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `GameSessionPane`: one game's tab. While the game prepares it shows the launch steps and a
//! Cancel button; from then on the live log with search, auto-scroll, a crash diagnosis card and
//! crash-report toggle, plus Copy / Open log file and Stop / Kill.
//!
//! The pane is window-independent: it moves between the Running Games window and the dock.
//! Dropping it (or [`GameSessionPane::dispose`]) drops its log subscription and uptime ticker;
//! the game is unaffected.

use std::path::Path;
use std::time::Duration;

use gpui::{
    AnyElement, App, AppContext as _, ClipboardItem, Context, Entity, InteractiveElement as _,
    IntoElement, ParentElement, Render, SharedString, StatefulInteractiveElement as _, Styled,
    Subscription, Task, Window, div, prelude::FluentBuilder as _,
};
use gpui_component::input::{Input, InputEvent, InputState};
use gpui_component::scroll::ScrollableElement as _;
use mmcl_engine::{
    CrashAction, CrashActionResult, CrashSeverity, CrashView, LaunchStepView, LogEvent, SessionId,
    SessionInfo, SessionPhase, StepStateView,
};
use mmcl_i18n::{t, tf};
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::scale::u;
use mmcl_ui_kit::{
    Avatar, FilterChip, Icon, IconButton, Navigator, ScreenId, ScreenRequest, StepBadge, StepState,
    ThemeExt as _, TypeStyleExt as _, WavyProgressBar, hsla,
};

use crate::backend::Backend;
use crate::log_view::SessionLogView;
use crate::trim;
use crate::widgets::{ActionButton, ButtonStyle};

/// The pane's smallest useful height (layout px): header, toolbar, a few log rows and the
/// footer, so Stop and Kill always show when the dock is small. It must cover the fixed parts:
/// padding 32 + header card 84 + search bar 48 + the log's own floor
/// ([`crate::log_view::PREF_HEIGHT`], 120) + footer 52 = 336. At 300 the log overflowed under
/// the footer in the smallest dock.
pub const MIN_HEIGHT: f32 = 360.0;

/// The widest the "Getting the game ready" card grows (layout px), so its lines stay readable
/// in a wide pop-out window.
const STEPS_MAX_WIDTH: f32 = 720.0;

/// Formats an uptime: `m:ss`, or `h:mm:ss` once there are hours, inside `session.uptime`.
/// (The old console did not zero-pad, "5:3"; this does.)
pub fn format_duration(ms: i64) -> String {
    let ms = ms.max(0);
    let s = (ms / 1000) % 60;
    let m = (ms / 60_000) % 60;
    let h = ms / 3_600_000;
    let clock = if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    };
    tf!("session.uptime", clock)
}

/// The uptime label for a session at `now_ms` (empty before the game started).
pub fn uptime_text(info: &SessionInfo, now_ms: i64) -> String {
    if info.started_ms <= 0 {
        return String::new();
    }
    format_duration(info.uptime_ms(now_ms))
}

/// The label of a launch step: the engine's localized label, else `launchStep.<ID>`, else the id.
pub fn step_label(step: &LaunchStepView) -> String {
    if !step.label.trim().is_empty() {
        return step.label.clone();
    }
    let key = format!("launchStep.{}", step.id);
    if mmcl_i18n::contains(&key) {
        t!(&key).into_owned()
    } else {
        step.id.clone()
    }
}

/// The detail line of a step: its error when it failed, else its sub text; `None` when blank.
pub fn step_detail(step: &LaunchStepView) -> Option<String> {
    let detail = if step.state == StepStateView::Failed {
        step.error.clone().unwrap_or_default()
    } else {
        step.sub_text.clone()
    };
    (!detail.trim().is_empty()).then_some(detail)
}

/// A running step's bar value: its progress when above 0, else indeterminate (-1).
pub fn step_bar_progress(step: &LaunchStepView) -> f64 {
    if step.progress > 0.0 {
        step.progress
    } else {
        -1.0
    }
}

/// The step badge state for a step state.
pub fn badge_state(state: StepStateView) -> StepState {
    match state {
        StepStateView::Pending => StepState::Pending,
        StepStateView::Running => StepState::Running,
        StepStateView::Done => StepState::Done,
        StepStateView::Failed => StepState::Failed,
        StepStateView::Skipped => StepState::Skipped,
    }
}

/// What a pane shows for a phase (pure, so the visibility rules are tested).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Visibility {
    /// The launch steps (else the log).
    pub preparing_view: bool,
    pub cancel_enabled: bool,
    pub stop_and_kill: bool,
    pub copy: bool,
    pub open_log_file: bool,
    pub ticker: bool,
}

impl Visibility {
    /// The rules of `refreshFromSession`.
    pub fn of(info: &SessionInfo) -> Visibility {
        let preparing = info.phase == SessionPhase::Preparing;
        let running = info.phase == SessionPhase::Running;
        Visibility {
            preparing_view: preparing,
            cancel_enabled: !info.cancelled,
            stop_and_kill: running,
            copy: !preparing,
            open_log_file: info.log_file.is_some(),
            ticker: running,
        }
    }
}

/// The placeholder log text of a session that ended without a game (`None` otherwise).
pub fn ended_without_game_text(info: &SessionInfo) -> Option<&'static str> {
    if info.started_ms > 0 {
        return None;
    }
    match info.phase {
        SessionPhase::Failed => Some("session.log.failed"),
        SessionPhase::Cancelled => Some("session.log.cancelled"),
        _ => None,
    }
}

/// One game's pane.
pub struct GameSessionPane {
    backend: Backend,
    info: SessionInfo,
    log_view: Entity<SessionLogView>,
    search: Option<(Entity<InputState>, Subscription)>,
    search_status: SharedString,
    auto_scroll: bool,
    /// The cap the truncated notice names, once shown.
    truncated: Option<i32>,
    step_bars: Vec<(String, Entity<WavyProgressBar>)>,
    log_bound: bool,
    log_task: Option<Task<()>>,
    ticker: Option<Task<()>>,
    /// `has_crash_report` when the diagnosis last ran (it reruns once the report arrives).
    diagnosed: Option<bool>,
    diagnosis: Option<CrashView>,
    crash_report: Option<String>,
    showing_crash_report: bool,
    placeholder_shown: bool,
    disposed: bool,
}

impl GameSessionPane {
    /// A pane for `info`.
    pub fn new(backend: Backend, info: SessionInfo, cx: &mut Context<Self>) -> Self {
        let log_view = cx.new(SessionLogView::new);
        let mut pane = GameSessionPane {
            backend,
            info,
            log_view,
            search: None,
            search_status: SharedString::default(),
            auto_scroll: true,
            truncated: None,
            step_bars: Vec::new(),
            log_bound: false,
            log_task: None,
            ticker: None,
            diagnosed: None,
            diagnosis: None,
            crash_report: None,
            showing_crash_report: false,
            placeholder_shown: false,
            disposed: false,
        };
        pane.refresh_from_session(cx);
        pane
    }

    /// The session id.
    pub fn id(&self) -> SessionId {
        self.info.id
    }

    /// The session as last seen.
    pub fn info(&self) -> &SessionInfo {
        &self.info
    }

    /// The log view.
    pub fn log_view(&self) -> &Entity<SessionLogView> {
        &self.log_view
    }

    /// Whether auto-scroll is on.
    pub fn auto_scroll(&self) -> bool {
        self.auto_scroll
    }

    /// The search status text ("2 of 5", "No matches", or empty).
    pub fn search_status(&self) -> &SharedString {
        &self.search_status
    }

    /// Whether the crash report is showing instead of the log.
    pub fn showing_crash_report(&self) -> bool {
        self.showing_crash_report
    }

    /// The diagnosis, once analyzed.
    pub fn diagnosis(&self) -> Option<&CrashView> {
        self.diagnosis.as_ref()
    }

    /// The truncated notice's cap, when shown.
    pub fn truncated_notice(&self) -> Option<i32> {
        self.truncated
    }

    /// Whether the pane was disposed.
    pub fn is_disposed(&self) -> bool {
        self.disposed
    }

    /// The smallest useful height (layout px).
    pub fn min_height(&self) -> f32 {
        MIN_HEIGHT
    }

    /// A new snapshot of the session arrived.
    pub fn apply(&mut self, info: SessionInfo, cx: &mut Context<Self>) {
        if self.disposed || info.id != self.info.id {
            return;
        }
        self.info = info;
        self.refresh_from_session(cx);
    }

    /// Stops the ticker and drops the log subscription; later events are ignored.
    pub fn dispose(&mut self) {
        self.disposed = true;
        self.ticker = None;
        self.log_task = None;
        self.search = None;
    }

    /// Re-applies the session's state: steps, log binding, ticker, diagnosis, placeholders.
    pub fn refresh_from_session(&mut self, cx: &mut Context<Self>) {
        if self.disposed {
            return;
        }
        self.bind_steps(cx);
        self.bind_log(cx);
        let vis = Visibility::of(&self.info);
        if vis.ticker {
            if self.ticker.is_none() {
                self.ticker = Some(cx.spawn(async move |this, cx| {
                    loop {
                        cx.background_executor().timer(Duration::from_secs(1)).await;
                        if this.update(cx, |_, cx| cx.notify()).is_err() {
                            break;
                        }
                    }
                }));
            }
        } else {
            self.ticker = None;
        }
        if self.info.phase == SessionPhase::Crashed
            && self.diagnosed != Some(self.info.has_crash_report)
            && (self.diagnosed.is_none() || self.info.has_crash_report)
        {
            self.show_diagnosis(cx);
        }
        if !self.placeholder_shown
            && let Some(key) = ended_without_game_text(&self.info)
            && self.log_view.read(cx).line_count() == 0
        {
            self.placeholder_shown = true;
            let text = t!(key).into_owned();
            self.log_view.update(cx, |v, cx| v.set_text(&text, cx));
        }
        cx.notify();
    }

    fn max_lines(&self) -> i32 {
        self.backend.console_max_lines()
    }

    fn bind_steps(&mut self, cx: &mut Context<Self>) {
        let ids: Vec<&str> = self.info.steps.iter().map(|s| s.id.as_str()).collect();
        let same = ids.len() == self.step_bars.len()
            && ids.iter().zip(&self.step_bars).all(|(a, (b, _))| *a == b);
        if !same {
            self.step_bars = self
                .info
                .steps
                .iter()
                .map(|s| {
                    let p = step_bar_progress(s);
                    (s.id.clone(), cx.new(|_| WavyProgressBar::new(p)))
                })
                .collect();
        }
        for (step, (_, bar)) in self.info.steps.iter().zip(&self.step_bars) {
            if step.state == StepStateView::Running {
                let p = step_bar_progress(step);
                bar.update(cx, |b, cx| b.set_progress(p, cx));
            }
        }
    }

    fn bind_log(&mut self, cx: &mut Context<Self>) {
        if self.log_bound {
            return;
        }
        let max = self.max_lines();
        let Some(sub) = self
            .backend
            .subscribe_log(self.info.id, usize::try_from(max.max(0)).unwrap_or(0))
        else {
            return;
        };
        self.log_bound = true;
        self.show_log_text(&sub.snapshot, sub.clipped, cx);
        // An ended log keeps only its tail in memory; the rest comes back from the file.
        if !self.info.phase.is_active() && self.info.log_truncated {
            self.load_full_log(cx);
        }
        let mut events = sub.events;
        self.log_task = Some(cx.spawn(async move |this, cx| {
            while let Some(first) = events.recv().await {
                // Everything already queued goes out in one UI update.
                let mut lines: Vec<String> = Vec::new();
                let mut closed = false;
                let mut push = |ev: LogEvent| match ev {
                    LogEvent::Lines(batch) => lines.extend(batch.iter().cloned()),
                    LogEvent::Closed => closed = true,
                };
                push(first);
                while let Ok(ev) = events.try_recv() {
                    push(ev);
                }
                let alive = this.update(cx, |pane, cx| pane.append(&lines, cx)).is_ok();
                if !alive || closed {
                    break;
                }
            }
        }));
    }

    fn show_log_text(&mut self, text: &str, clipped: bool, cx: &mut Context<Self>) {
        let follow = self.auto_scroll;
        self.log_view.update(cx, |v, cx| {
            v.set_text(text, cx);
            if follow {
                v.scroll_to_end(cx);
            }
        });
        if clipped {
            self.show_truncated();
        }
        cx.notify();
    }

    fn load_full_log(&mut self, cx: &mut Context<Self>) {
        let max = self.max_lines();
        let fut = self.backend.full_log(self.info.id);
        cx.spawn(async move |this, cx| {
            let Some(full) = fut.await else { return };
            let drop = trim::tail_start(&full, max);
            let _ = this.update(cx, |pane, cx| {
                if !pane.disposed && !pane.showing_crash_report {
                    pane.show_log_text(&full[drop..], drop > 0, cx);
                }
            });
        })
        .detach();
    }

    /// Appends a batch of lines (ignored while the crash report shows).
    pub fn append(&mut self, lines: &[String], cx: &mut Context<Self>) {
        if self.disposed || self.showing_crash_report || lines.is_empty() {
            return;
        }
        let max = self.max_lines();
        let follow = self.auto_scroll;
        let dropped = self
            .log_view
            .update(cx, |v, cx| v.append(lines, max, follow, cx));
        if dropped > 0 {
            self.show_truncated();
        }
        cx.notify();
    }

    fn show_truncated(&mut self) {
        if self.truncated.is_none() {
            self.truncated = Some(self.max_lines());
        }
    }

    /// Turns auto-scroll on or off; turning it on jumps to the newest line.
    pub fn set_auto_scroll(&mut self, on: bool, cx: &mut Context<Self>) {
        self.auto_scroll = on;
        if on && !self.showing_crash_report {
            self.log_view.update(cx, |v, cx| v.scroll_to_end(cx));
        }
        cx.notify();
    }

    /// Finds `needle` forward or backward and updates the match status. A hit turns auto-scroll
    /// off, so the next batch does not yank the view away from the match.
    pub fn find_text(&mut self, needle: &str, forward: bool, cx: &mut Context<Self>) {
        if needle.is_empty() || self.log_view.read(cx).line_count() == 0 {
            self.search_status = SharedString::default();
            self.log_view.update(cx, |v, cx| {
                v.find("", forward, cx);
            });
            cx.notify();
            return;
        }
        let needle = needle.to_string();
        let found = self
            .log_view
            .update(cx, |v, cx| v.find(&needle, forward, cx));
        match found {
            None => self.search_status = t!("session.search.noMatches").into_owned().into(),
            Some((ordinal, total)) => {
                self.search_status =
                    tf!("session.search.matchCount", ordinal as i64, total as i64).into();
                self.auto_scroll = false;
            }
        }
        cx.notify();
    }

    fn find(&mut self, forward: bool, cx: &mut Context<Self>) {
        let needle = self
            .search
            .as_ref()
            .map(|(s, _)| s.read(cx).value().to_string())
            .unwrap_or_default();
        self.find_text(&needle, forward, cx);
    }

    fn open_log_file(&self, cx: &mut Context<Self>) {
        let Some(path) = self.info.log_file.clone() else {
            return;
        };
        open_path(&path, cx);
    }

    fn copy_log(&self, cx: &mut Context<Self>) {
        let text = self.log_view.read(cx).text();
        cx.write_to_clipboard(ClipboardItem::new_string(text));
    }

    fn stop(&self, force: bool, cx: &mut Context<Self>) {
        let fut = self.backend.stop(self.info.id, force);
        let name = self.info.pack_name.clone();
        cx.spawn(async move |_, _| {
            if let Err(e) = fut.await {
                tracing::warn!("{}", tf!("log.runningGames.stopFailed", &name, &e));
                mmcl_ui_kit::notify::error(&t!("session.stop.failed"), &e);
            }
        })
        .detach();
    }

    fn show_diagnosis(&mut self, cx: &mut Context<Self>) {
        self.diagnosed = Some(self.info.has_crash_report);
        let fut = self.backend.crash(self.info.id);
        cx.spawn(async move |this, cx| {
            let view = fut.await;
            let _ = this.update(cx, |pane, cx| pane.diagnosis_ready(view, cx));
        })
        .detach();
    }

    /// The crash analysis finished: show the card and the report toggle.
    pub fn diagnosis_ready(&mut self, view: Option<CrashView>, cx: &mut Context<Self>) {
        if self.disposed {
            return;
        }
        let Some(view) = view else { return };
        let report = if self.info.has_crash_report {
            view.report.clone().filter(|r| !r.trim().is_empty())
        } else {
            None
        };
        self.diagnosis = Some(view);
        let had_report = self.crash_report.is_some();
        self.crash_report = report;
        // Nothing captured (the game died before logging): open on the crash report.
        let log_blank = self.log_view.read(cx).text().trim().is_empty() || self.placeholder_shown;
        if !had_report && self.crash_report.is_some() && log_blank && !self.showing_crash_report {
            self.toggle_crash_report(cx);
        }
        cx.notify();
    }

    /// Flips between the crash report and the game log.
    pub fn toggle_crash_report(&mut self, cx: &mut Context<Self>) {
        let Some(report) = self.crash_report.clone() else {
            return;
        };
        self.showing_crash_report = !self.showing_crash_report;
        if self.showing_crash_report {
            self.log_view.update(cx, |v, cx| {
                v.set_text(&report, cx);
                v.scroll_to_start(cx);
            });
        } else {
            let max = self.max_lines();
            let tail = self
                .backend
                .log_tail(self.info.id, usize::try_from(max.max(0)).unwrap_or(0));
            match tail {
                Some(t) => self.show_log_text(&t.text, t.clipped, cx),
                None => self.show_log_text("", false, cx),
            }
            if !self.info.phase.is_active() && self.info.log_truncated {
                self.load_full_log(cx);
            }
        }
        cx.notify();
    }

    fn run_crash_action(&self, action: CrashAction, cx: &mut Context<Self>) {
        let fut = self
            .backend
            .crash_action(action, Some(self.info.pack.clone()));
        cx.spawn(async move |_, cx| match fut.await {
            Ok(result) => cx.update(|cx| apply_crash_result(result, cx)),
            Err(e) => {
                tracing::warn!("{}", tf!("log.runningGames.crashActionFailed", &e));
                mmcl_ui_kit::notify::error(&t!("session.crashAction.failed"), &e);
            }
        })
        .detach();
    }

    fn ensure_search(&mut self, window: &mut Window, cx: &mut Context<Self>) -> Entity<InputState> {
        if let Some((state, _)) = &self.search {
            return state.clone();
        }
        let state = cx.new(|cx| {
            InputState::new(window, cx).placeholder(t!("console.search.placeholder").into_owned())
        });
        let sub = cx.subscribe(&state, |pane, _, event: &InputEvent, cx| {
            if let InputEvent::PressEnter { shift, .. } = event {
                pane.find(!*shift, cx);
            }
        });
        self.search = Some((state.clone(), sub));
        state
    }

    // ------------------------------------------------------------------ rendering

    fn render_header(&self, cx: &App) -> impl IntoElement {
        let t = cx.tokens();
        let info = &self.info;
        let subtitle = match &info.account_name {
            Some(name) => tf!("session.subtitle.playingAs", name),
            None => t!("session.subtitle.server").into_owned(),
        };
        let (chip_bg, chip_fg) = status_chip_colors(info.phase, t);
        let uptime = uptime_text(info, now_ms());
        div()
            .flex()
            .flex_none()
            .items_center()
            .gap(u(12.0))
            .mb(u(12.0))
            .px(u(16.0))
            .py(u(12.0))
            .rounded(u(16.0))
            .bg(hsla(t.md_surface_container_low))
            .when_some(info.account_uuid.clone(), |el, uuid| {
                el.child(Avatar::new(
                    SharedString::from(format!("pane-avatar-{}", info.id)),
                    Some(uuid),
                    36.0,
                ))
            })
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(u(2.0))
                    .min_w_0()
                    .child(
                        div()
                            .type_role(TypeRole::HeadingH3)
                            .text_color(hsla(t.md_on_surface))
                            .child(info.pack_name.clone()),
                    )
                    .child(
                        div()
                            .text_size(u(13.0))
                            .text_color(hsla(t.color_text_muted))
                            .child(subtitle),
                    ),
            )
            .child(div().flex_1())
            .when(!uptime.is_empty(), |el| {
                el.child(
                    div()
                        .text_size(u(13.0))
                        .text_color(hsla(t.color_text_muted))
                        .child(uptime),
                )
            })
            .child(
                div()
                    .flex_none()
                    .px(u(12.0))
                    .py(u(4.0))
                    .rounded(u(8.0))
                    .bg(chip_bg)
                    .text_color(chip_fg)
                    .text_size(u(12.0))
                    .font_weight(gpui::FontWeight::SEMIBOLD)
                    .child(t!(info.phase.l10n_key()).into_owned()),
            )
    }

    fn render_steps(&self, vis: Visibility, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let rows: Vec<AnyElement> = self
            .info
            .steps
            .iter()
            .enumerate()
            .map(|(i, step)| {
                let bar = self.step_bars.get(i).map(|(_, b)| b.clone());
                render_step_row(step, bar, cx).into_any_element()
            })
            .collect();
        let id = self.info.id;
        // A steady width (not the width of the current step's text). In a short dock the card
        // shrinks below its content: the heading and Cancel stay put and only the step rows
        // scroll, with a visible scrollbar.
        div()
            .flex()
            .flex_col()
            .gap(u(14.0))
            .w_full()
            .max_w(u(STEPS_MAX_WIDTH))
            .min_h_0()
            .pt(u(16.0))
            .px(u(20.0))
            .pb(u(20.0))
            .rounded(u(16.0))
            .bg(hsla(t.md_surface_container_low))
            .child(
                div()
                    .flex_none()
                    .type_role(TypeRole::HeadingH3)
                    .text_color(hsla(t.md_on_surface))
                    .child(t!("session.preparing.heading").into_owned()),
            )
            .child(
                div()
                    .id("session-steps")
                    .min_h_0()
                    .overflow_y_scrollbar()
                    .child(div().flex().flex_col().gap(u(10.0)).children(rows)),
            )
            .child(
                div().flex().flex_none().child(
                    ActionButton::new(
                        SharedString::from(format!("session-cancel-{id}")),
                        t!("session.button.cancel").into_owned(),
                        ButtonStyle::Tonal,
                    )
                    .disabled(!vis.cancel_enabled)
                    .on_click(on(cx, |pane, _, _| pane.backend.cancel(pane.info.id))),
                ),
            )
    }

    fn render_diagnosis(&self, cx: &mut Context<Self>) -> Option<AnyElement> {
        let view = self.diagnosis.as_ref()?;
        let t = cx.tokens();
        let (bg, title_fg, body_fg, muted_fg) = match view.severity {
            CrashSeverity::Critical => (
                hsla(t.md_error_container),
                hsla(t.md_on_error_container),
                hsla(t.md_on_error_container),
                hsla(t.md_on_error_container),
            ),
            CrashSeverity::Warning => (
                hsla(t.md_warning_container),
                hsla(t.md_on_warning_container),
                hsla(t.md_on_warning_container),
                hsla(t.md_on_warning_container),
            ),
            CrashSeverity::Info => (
                hsla(t.md_surface_container_high),
                hsla(t.md_text_primary),
                hsla(t.md_on_surface),
                hsla(t.color_text_muted),
            ),
        };
        let primary_style = if view.severity == CrashSeverity::Critical {
            ButtonStyle::Error
        } else {
            ButtonStyle::Primary
        };
        let id = self.info.id;
        let actions: Vec<AnyElement> = view
            .suggestions
            .iter()
            .enumerate()
            .map(|(i, s)| match &s.action {
                None => div()
                    .text_size(u(13.0))
                    .text_color(muted_fg)
                    .child(s.label.clone())
                    .into_any_element(),
                Some(action) => {
                    let action = action.clone();
                    ActionButton::new(
                        SharedString::from(format!("crash-action-{id}-{i}")),
                        s.label.clone(),
                        if s.primary {
                            primary_style
                        } else {
                            ButtonStyle::Tonal
                        },
                    )
                    .on_click(on(cx, move |pane, _, cx| {
                        pane.run_crash_action(action.clone(), cx);
                    }))
                    .into_any_element()
                }
            })
            .collect();
        Some(
            div()
                .flex()
                .flex_none()
                .flex_col()
                .gap(u(6.0))
                .px(u(14.0))
                .py(u(12.0))
                .rounded(u(16.0))
                .bg(bg)
                .child(
                    div()
                        .type_role(TypeRole::HeadingH3)
                        .text_color(title_fg)
                        .child(view.title.clone()),
                )
                .child(
                    div()
                        .text_size(u(14.0))
                        .text_color(body_fg)
                        .child(view.summary.clone()),
                )
                .when(!actions.is_empty(), |el| {
                    el.child(
                        div()
                            .flex()
                            .flex_wrap()
                            .items_center()
                            .gap(u(6.0))
                            .children(actions),
                    )
                })
                .into_any_element(),
        )
    }

    fn render_toolbar(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let search = self.ensure_search(window, cx);
        let focused =
            gpui::Focusable::focus_handle(search.read(cx), cx).contains_focused(window, cx);
        let id = self.info.id;
        let auto = self.auto_scroll;
        let pane = cx.entity().downgrade();
        let pane_prev = pane.clone();
        let pane_next = pane.clone();
        let pane_auto = pane.clone();
        let crash_label = if self.showing_crash_report {
            t!("console.crashReportBtn.gameLog")
        } else {
            t!("console.crashReportBtn.crashReport")
        };
        div()
            .flex()
            .flex_none()
            .items_center()
            .gap(u(8.0))
            .child(
                div()
                    .id("session-search")
                    .flex_1()
                    .min_w(u(160.0))
                    .h(u(40.0))
                    .flex()
                    .items_center()
                    .pl(u(10.0))
                    .pr(u(14.0))
                    .rounded_full()
                    .bg(hsla(t.md_surface_container_highest))
                    .border_1()
                    .border_color(if focused {
                        hsla(t.md_primary)
                    } else {
                        gpui::transparent_black()
                    })
                    .child(
                        Input::new(&search)
                            .appearance(false)
                            .prefix(
                                Icon::Search
                                    .svg(18.0)
                                    .text_color(hsla(t.md_on_surface_variant)),
                            )
                            .cleanable(true),
                    ),
            )
            .when(!self.search_status.is_empty(), |el| {
                el.child(
                    div()
                        .flex_none()
                        .text_size(u(13.0))
                        .text_color(hsla(t.color_text_muted))
                        .child(self.search_status.clone()),
                )
            })
            .child(
                IconButton::new(
                    SharedString::from(format!("search-prev-{id}")),
                    Icon::ArrowUp,
                    t!("session.search.previousMatch").into_owned(),
                )
                .on_click(move |_, cx| {
                    let _ = pane_prev.update(cx, |p, cx| p.find(false, cx));
                }),
            )
            .child(
                IconButton::new(
                    SharedString::from(format!("search-next-{id}")),
                    Icon::ArrowDown,
                    t!("session.search.nextMatch").into_owned(),
                )
                .on_click(move |_, cx| {
                    let _ = pane_next.update(cx, |p, cx| p.find(true, cx));
                }),
            )
            .child(
                FilterChip::new(
                    SharedString::from(format!("auto-scroll-{id}")),
                    t!("console.autoPin.label").into_owned(),
                )
                .selected(auto)
                .on_change(move |on, _, cx| {
                    let _ = pane_auto.update(cx, |p, cx| p.set_auto_scroll(on, cx));
                }),
            )
            .when(self.crash_report.is_some(), |el| {
                el.child(
                    ActionButton::new(
                        SharedString::from(format!("crash-toggle-{id}")),
                        crash_label.into_owned(),
                        ButtonStyle::Tonal,
                    )
                    .on_click(on(cx, |pane, _, cx| pane.toggle_crash_report(cx))),
                )
            })
    }

    fn render_notices(&self, cx: &mut Context<Self>) -> Option<AnyElement> {
        let max = self.truncated?;
        let t = cx.tokens();
        let has_file = self.info.log_file.is_some();
        Some(
            div()
                .flex()
                .flex_none()
                .items_center()
                .gap(u(6.0))
                .child(
                    div()
                        .text_size(u(12.0))
                        .text_color(hsla(t.color_text_subtle))
                        .child(tf!("console.truncationLabel", max as i64)),
                )
                .when(has_file, |el| {
                    el.child(
                        div()
                            .id("open-log-link")
                            .cursor_pointer()
                            .text_size(u(12.0))
                            .text_color(hsla(t.md_text_primary))
                            .hover(|s| s.underline())
                            .child(t!("console.openLogLink.text").into_owned())
                            .on_click(cx.listener(|pane, _, _, cx| pane.open_log_file(cx))),
                    )
                })
                .into_any_element(),
        )
    }

    fn render_footer(&self, vis: Visibility, cx: &mut Context<Self>) -> impl IntoElement {
        let id = self.info.id;
        div()
            .flex()
            .flex_none()
            .items_center()
            .gap(u(8.0))
            .pt(u(12.0))
            .when(vis.copy, |el| {
                el.child(
                    ActionButton::new(
                        SharedString::from(format!("copy-log-{id}")),
                        t!("console.copyBtn.label").into_owned(),
                        ButtonStyle::Tonal,
                    )
                    .icon(Icon::Copy)
                    .on_click(on(cx, |pane, _, cx| pane.copy_log(cx))),
                )
            })
            .when(vis.open_log_file, |el| {
                el.child(
                    ActionButton::new(
                        SharedString::from(format!("open-log-{id}")),
                        t!("session.button.openLogFile").into_owned(),
                        ButtonStyle::Text,
                    )
                    .on_click(on(cx, |pane, _, cx| pane.open_log_file(cx))),
                )
            })
            .child(div().flex_1())
            .when(vis.stop_and_kill, |el| {
                el.child(
                    ActionButton::new(
                        SharedString::from(format!("stop-{id}")),
                        t!("session.button.stop").into_owned(),
                        ButtonStyle::Tonal,
                    )
                    .icon(Icon::Stop)
                    .on_click(on(cx, |pane, _, cx| pane.stop(false, cx))),
                )
                .child(
                    ActionButton::new(
                        SharedString::from(format!("kill-{id}")),
                        t!("session.button.kill").into_owned(),
                        ButtonStyle::ErrorTonal,
                    )
                    .on_click(on(cx, |pane, _, cx| pane.stop(true, cx))),
                )
            })
    }
}

impl Render for GameSessionPane {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let vis = Visibility::of(&self.info);
        let header = self.render_header(cx).into_any_element();
        let center: AnyElement = if vis.preparing_view {
            div()
                .flex_1()
                .min_h_0()
                .flex()
                .flex_col()
                .child(self.render_steps(vis, cx).into_any_element())
                .into_any_element()
        } else {
            let card = self.render_diagnosis(cx);
            let toolbar = self.render_toolbar(window, cx).into_any_element();
            let notices = self.render_notices(cx);
            div()
                .flex_1()
                .min_h_0()
                .flex()
                .flex_col()
                .gap(u(8.0))
                .children(card)
                .child(toolbar)
                .child(self.log_view.clone())
                .children(notices)
                .into_any_element()
        };
        let footer = self.render_footer(vis, cx).into_any_element();
        div()
            .size_full()
            .flex()
            .flex_col()
            .p(u(16.0))
            .bg(hsla(t.color_bg))
            .text_color(hsla(t.md_on_surface))
            .child(header)
            .child(center)
            .child(footer)
    }
}

fn render_step_row(
    step: &LaunchStepView,
    bar: Option<Entity<WavyProgressBar>>,
    cx: &App,
) -> impl IntoElement {
    let t = cx.tokens();
    let running = step.state == StepStateView::Running;
    div()
        .flex()
        .items_start()
        .gap(u(10.0))
        .child(StepBadge::new(badge_state(step.state)))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(u(2.0))
                .child(
                    div()
                        .text_size(u(14.0))
                        .text_color(hsla(t.md_on_surface))
                        .child(step_label(step)),
                )
                .when_some(step_detail(step), |el, detail| {
                    el.child(
                        div()
                            .text_size(u(13.0))
                            .text_color(hsla(t.color_text_muted))
                            .child(detail),
                    )
                })
                .when(running, |el| {
                    el.when_some(bar, |el, bar| el.child(div().w_full().child(bar)))
                }),
        )
}

/// The status chip's container and text colours for a phase.
pub fn status_chip_colors(phase: SessionPhase, t: &mmcl_theme::Tokens) -> (gpui::Hsla, gpui::Hsla) {
    match phase {
        SessionPhase::Preparing => (
            hsla(t.md_warning_container),
            hsla(t.md_on_warning_container),
        ),
        SessionPhase::Running => (
            hsla(t.md_success_container),
            hsla(t.md_on_success_container),
        ),
        SessionPhase::Crashed | SessionPhase::Failed => {
            (hsla(t.md_error_container), hsla(t.md_on_error_container))
        }
        SessionPhase::Exited | SessionPhase::Cancelled => (
            hsla(t.md_surface_container_highest),
            hsla(t.md_on_surface_variant),
        ),
    }
}

/// The tab dot's colour for a phase.
pub fn status_dot_color(phase: SessionPhase, t: &mmcl_theme::Tokens) -> gpui::Hsla {
    match phase {
        SessionPhase::Preparing => hsla(t.color_warning),
        SessionPhase::Running => hsla(t.color_primary),
        SessionPhase::Crashed | SessionPhase::Failed => hsla(t.color_danger),
        SessionPhase::Exited | SessionPhase::Cancelled => hsla(t.color_text_subtle),
    }
}

/// Navigation that asks the current screen first (the shell's `navigate_gated`: Java's
/// `mayNavigateAway`, so the editor's unsaved-changes prompt runs before a crash suggestion
/// leaves it).
pub type NavigateHandler = std::rc::Rc<dyn Fn(ScreenRequest, &mut App)>;

#[derive(Clone)]
struct GatedNavigation(NavigateHandler);

impl gpui::Global for GatedNavigation {}

/// Installs the gated navigation the crash card's Open Settings / Open Runtime use. The shell
/// passes `mmcl_ui::shell::navigate_gated`. Without one they fall back to
/// [`Navigator::go_to`], which does not ask the screen.
pub fn set_navigate_handler(cx: &mut App, handler: impl Fn(ScreenRequest, &mut App) + 'static) {
    cx.set_global(GatedNavigation(std::rc::Rc::new(handler)));
}

/// Navigates through the gated handler when one is installed.
pub fn navigate(cx: &mut App, req: ScreenRequest) {
    match cx.try_global::<GatedNavigation>().cloned() {
        Some(GatedNavigation(handler)) => handler(req, cx),
        None => {
            Navigator::go_to(cx, req);
        }
    }
}

/// What the front end does with a crash action's result.
pub fn apply_crash_result(result: CrashActionResult, cx: &mut App) {
    match result {
        CrashActionResult::Done => {}
        CrashActionResult::OpenSettings => navigate(cx, ScreenRequest::new(ScreenId::SETTINGS)),
        CrashActionResult::OpenRuntime => navigate(cx, ScreenRequest::new(ScreenId::RUNTIME)),
        CrashActionResult::OpenPath(path) => mmcl_ui_kit::open_folder(cx, &path),
        CrashActionResult::OpenUrl(url) => mmcl_ui_kit::open_external_url(cx, &url),
    }
}

fn open_path(path: &Path, cx: &App) {
    if path.exists() {
        cx.open_with_system(path);
    } else {
        tracing::warn!(
            "{}",
            tf!("log.console.openLogFileFailed", path.display().to_string())
        );
    }
}

pub(crate) fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

/// An activation handler that updates this view (for controls whose handler takes no event).
pub(crate) fn on<T: 'static>(
    cx: &Context<T>,
    f: impl Fn(&mut T, &mut Window, &mut Context<T>) + 'static,
) -> impl Fn(&mut Window, &mut App) + 'static {
    let weak = cx.entity().downgrade();
    move |window, cx| {
        let _ = weak.update(cx, |this, cx| f(this, window, cx));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_engine::PackId;

    fn info(phase: SessionPhase) -> SessionInfo {
        SessionInfo {
            id: SessionId(1),
            pack: PackId::new("p"),
            pack_name: "Pack".into(),
            account_uuid: None,
            account_name: None,
            phase,
            cancelled: false,
            started_ms: 0,
            ended_ms: 0,
            exit_code: None,
            steps: Vec::new(),
            has_crash_report: false,
            log_file: None,
            log_truncated: false,
        }
    }

    /// Port of `GameSessionPaneUptimeTest`.
    #[test]
    fn pads_seconds_below_ten() {
        assert_eq!(format_duration(63_000), tf!("session.uptime", "1:03"));
        assert_eq!(format_duration(7_000), tf!("session.uptime", "0:07"));
    }

    #[test]
    fn pads_minutes_and_seconds_once_there_are_hours() {
        assert_eq!(
            format_duration(3_600_000 + 5 * 60_000 + 9_000),
            tf!("session.uptime", "1:05:09")
        );
    }

    #[test]
    fn keeps_two_digit_values_as_they_are() {
        assert_eq!(
            format_duration(12 * 60_000 + 34_000),
            tf!("session.uptime", "12:34")
        );
        assert_eq!(format_duration(0), tf!("session.uptime", "0:00"));
        assert_eq!(format_duration(-50), tf!("session.uptime", "0:00"));
    }

    #[test]
    fn uptime_is_empty_before_the_game_starts_and_frozen_after() {
        let mut s = info(SessionPhase::Preparing);
        assert_eq!(uptime_text(&s, 99_000), "");
        s.phase = SessionPhase::Running;
        s.started_ms = 1_000;
        assert_eq!(uptime_text(&s, 61_000), tf!("session.uptime", "1:00"));
        s.phase = SessionPhase::Exited;
        s.ended_ms = 11_000;
        assert_eq!(uptime_text(&s, 999_999), tf!("session.uptime", "0:10"));
    }

    #[test]
    fn visibility_follows_the_phase() {
        let p = Visibility::of(&info(SessionPhase::Preparing));
        assert!(p.preparing_view && !p.copy && !p.stop_and_kill && p.cancel_enabled && !p.ticker);
        let mut r = info(SessionPhase::Running);
        r.log_file = Some("x.log".into());
        let r = Visibility::of(&r);
        assert!(!r.preparing_view && r.copy && r.stop_and_kill && r.open_log_file && r.ticker);
        let mut c = info(SessionPhase::Preparing);
        c.cancelled = true;
        assert!(!Visibility::of(&c).cancel_enabled);
        for phase in [
            SessionPhase::Exited,
            SessionPhase::Crashed,
            SessionPhase::Cancelled,
            SessionPhase::Failed,
        ] {
            let v = Visibility::of(&info(phase));
            assert!(
                !v.stop_and_kill && v.copy && !v.ticker && !v.open_log_file,
                "{phase:?}"
            );
        }
    }

    #[test]
    fn sessions_that_never_started_get_a_placeholder() {
        assert_eq!(
            ended_without_game_text(&info(SessionPhase::Failed)),
            Some("session.log.failed")
        );
        assert_eq!(
            ended_without_game_text(&info(SessionPhase::Cancelled)),
            Some("session.log.cancelled")
        );
        assert_eq!(ended_without_game_text(&info(SessionPhase::Crashed)), None);
        let mut started = info(SessionPhase::Failed);
        started.started_ms = 5;
        assert_eq!(ended_without_game_text(&started), None);
        for key in ["session.log.failed", "session.log.cancelled"] {
            assert!(mmcl_i18n::contains(key), "{key}");
        }
    }

    #[test]
    fn step_rows_follow_the_tracker_rules() {
        let mut step = LaunchStepView {
            id: "FORGE_LIBS".into(),
            label: String::new(),
            state: StepStateView::Running,
            progress: 0.0,
            sub_text: "  ".into(),
            error: Some("boom".into()),
        };
        assert_eq!(step_label(&step), "Modloader libraries");
        assert_eq!(step_detail(&step), None, "blank sub text hides the line");
        assert_eq!(step_bar_progress(&step), -1.0);
        step.progress = 0.4;
        assert_eq!(step_bar_progress(&step), 0.4);
        step.state = StepStateView::Failed;
        assert_eq!(step_detail(&step).as_deref(), Some("boom"));
        step.label = "Libraries".into();
        assert_eq!(step_label(&step), "Libraries");
        step.label.clear();
        step.id = "UNKNOWN_STEP".into();
        assert_eq!(step_label(&step), "UNKNOWN_STEP");
        for id in [
            "MODPACK_CONTENT",
            "FORGE_LIBS",
            "MC_LIBS_ASSETS",
            "JRE_INSTALL",
            "FORGE_PROCESSORS",
            "SECURITY_SCAN",
        ] {
            assert!(mmcl_i18n::contains(&format!("launchStep.{id}")), "{id}");
        }
    }

    #[test]
    fn badges_map_one_to_one() {
        assert_eq!(badge_state(StepStateView::Pending), StepState::Pending);
        assert_eq!(badge_state(StepStateView::Running), StepState::Running);
        assert_eq!(badge_state(StepStateView::Done), StepState::Done);
        assert_eq!(badge_state(StepStateView::Failed), StepState::Failed);
        assert_eq!(badge_state(StepStateView::Skipped), StepState::Skipped);
    }

    #[test]
    fn every_pane_key_resolves() {
        for key in [
            "session.button.cancel",
            "session.button.stop",
            "session.button.kill",
            "session.button.openLogFile",
            "session.preparing.heading",
            "session.search.noMatches",
            "session.search.matchCount",
            "session.search.previousMatch",
            "session.search.nextMatch",
            "session.subtitle.playingAs",
            "session.subtitle.server",
            "session.uptime",
            "console.autoPin.label",
            "console.openLogLink.text",
            "console.search.placeholder",
            "console.copyBtn.label",
            "console.truncationLabel",
            "console.crashReportBtn.crashReport",
            "console.crashReportBtn.gameLog",
            "log.console.openLogFileFailed",
            "session.stop.failed",
            "session.crashAction.failed",
            "log.runningGames.stopFailed",
            "log.runningGames.crashActionFailed",
        ] {
            assert!(mmcl_i18n::contains(key), "{key}");
        }
    }
}

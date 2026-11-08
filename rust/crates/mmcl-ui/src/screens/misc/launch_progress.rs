// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `MCLauncherLaunchProgressGui`: one row per step, and [`LaunchProgressHandle`]. The rows come
//! from either
//!
//! - a [`LaunchProgressTracker`] in this process ([`LaunchProgressHandle::attach_tracker`]), or
//! - an engine operation's step rows ([`LaunchProgressHandle::follow_operation`]): the
//!   [`EngineEvent::OperationSteps`] events of a verify, update or export ("Verify now",
//!   Settings > Verify all). Game launches show their steps in the Running Games window.
//!
//! The tracker's listener runs on worker threads and fires thousands of times on a warm launch
//! (one event per file). It only flips an `AtomicBool` and posts **one** foreground flush, which
//! re-reads every row from the tracker (guarded bug 23); the engine throttles its step events
//! the same way. Rows keep their sub-text and bar managed (only visibility changes), so a row
//! never changes height.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Weak};
use std::time::Duration;

use gpui::{
    AppContext as _, ClickEvent, Context, Entity, IntoElement, ParentElement, Pixels, Render,
    SharedString, Size, Styled, Subscription, Task, Window, div, prelude::FluentBuilder, px, size,
};
use gpui_component::Disableable as _;
use gpui_component::button::Button;
use mmcl_engine::{EngineEvent, LaunchStepView, OperationId, OperationStepsView, StepStateView};
use mmcl_i18n::t;
use mmcl_platform::progress::LaunchId;
use mmcl_progress::{LaunchProgressTracker, ListenerId, StepSnapshot, StepState};
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::theme::{ThemeExt as _, TypeStyleExt as _, hsla};
use mmcl_ui_kit::{HelpTopic, Screen, ScreenId, ScreenRequest, StepBadge, WavyProgressBar};
use parking_lot::Mutex;

use super::progress::CancelHandler;
use super::visuals::{progress_backdrop, progress_card};
use crate::scale::u;
use crate::services::{self, UiEvent};
use crate::shell::{self, GuiHandle};

/// How long a finished step's full bar stays before it hides.
pub const DONE_HIDE_DELAY: Duration = Duration::from_millis(160);

/// The kit badge state for a tracker state.
pub fn badge_state(s: StepState) -> mmcl_ui_kit::StepState {
    match s {
        StepState::Pending => mmcl_ui_kit::StepState::Pending,
        StepState::Running => mmcl_ui_kit::StepState::Running,
        StepState::Done => mmcl_ui_kit::StepState::Done,
        StepState::Failed => mmcl_ui_kit::StepState::Failed,
        StepState::Skipped => mmcl_ui_kit::StepState::Skipped,
    }
}

/// The tracker state of an engine row state.
pub fn state_of_view(s: StepStateView) -> StepState {
    match s {
        StepStateView::Pending => StepState::Pending,
        StepStateView::Running => StepState::Running,
        StepStateView::Done => StepState::Done,
        StepStateView::Failed => StepState::Failed,
        StepStateView::Skipped => StepState::Skipped,
    }
}

/// One step row as the screen shows it, from a tracker step or an engine operation row.
#[derive(Debug, Clone, PartialEq)]
pub struct RowData {
    /// What identifies the row across updates (the step's label key or the engine step id).
    pub key: String,
    /// The localized label.
    pub label: String,
    pub state: StepState,
    /// 0..1.
    pub progress: f64,
    pub sub_text: String,
    pub error: Option<String>,
}

impl RowData {
    /// A tracker step.
    pub fn from_snapshot(s: &StepSnapshot) -> Self {
        RowData {
            key: s.id.label_key().to_string(),
            label: t!(s.id.label_key()).into_owned(),
            state: s.state,
            progress: s.progress,
            sub_text: s.sub_text.clone(),
            error: s.error.clone(),
        }
    }

    /// An engine operation row (a blank label falls back to `launchStep.<id>`).
    pub fn from_view(v: &LaunchStepView) -> Self {
        let label = if v.label.trim().is_empty() {
            let key = format!("launchStep.{}", v.id);
            mmcl_i18n::english(&key)
                .map(|_| t!(key.as_str()).into_owned())
                .unwrap_or_else(|| v.id.clone())
        } else {
            v.label.clone()
        };
        RowData {
            key: v.id.clone(),
            label,
            state: state_of_view(v.state),
            progress: v.progress,
            sub_text: v.sub_text.clone(),
            error: v.error.clone(),
        }
    }
}

/// `renderRow`'s sub-text: the error when FAILED and non-empty, else the sub-text.
pub fn row_sub_text(step: &RowData) -> &str {
    match (&step.state, step.error.as_deref()) {
        (StepState::Failed, Some(e)) if !e.is_empty() => e,
        _ => &step.sub_text,
    }
}

/// What `renderRow` does with a row's bar.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum BarAction {
    /// RUNNING: cancel a pending hide, show, value = progress.
    Show(f64),
    /// DONE after the bar was showing: snap to full, hide after [`DONE_HIDE_DELAY`].
    SnapThenHide,
    /// Hide now (and cancel a pending hide).
    Hide,
    /// DONE while hidden: stay hidden (a pending hide keeps running).
    KeepHidden,
}

/// The bar rule for a step, given whether the bar shows now.
pub fn bar_action(state: StepState, progress: f64, bar_visible: bool) -> BarAction {
    match state {
        StepState::Running => BarAction::Show(progress.clamp(0.0, 1.0)),
        StepState::Done if bar_visible => BarAction::SnapThenHide,
        StepState::Done => BarAction::KeepHidden,
        StepState::Pending | StepState::Failed | StepState::Skipped => BarAction::Hide,
    }
}

/// The taskbar roll-up: finished steps (done, failed, skipped) count 1, running its progress,
/// pending 0, over the step count. 0 for no steps.
pub fn rollup(steps: &[RowData]) -> f64 {
    if steps.is_empty() {
        return 0.0;
    }
    let sum: f64 = steps
        .iter()
        .map(|s| match s.state {
            StepState::Done | StepState::Failed | StepState::Skipped => 1.0,
            StepState::Running => s.progress.clamp(0.0, 1.0),
            StepState::Pending => 0.0,
        })
        .sum();
    sum / steps.len() as f64
}

/// Whether the row list changed shape (rows added, removed or reordered), so the screen
/// rebuilds its rows instead of updating them in place.
pub fn rows_reshaped(shown: &[String], next: &[RowData]) -> bool {
    shown.len() != next.len() || shown.iter().zip(next).any(|(a, b)| *a != b.key)
}

/// The taskbar owner id of a tracker (its address; unique while it lives).
fn launch_id(tracker: &Arc<LaunchProgressTracker>) -> LaunchId {
    LaunchId(Arc::as_ptr(tracker) as usize as u64)
}

/// The taskbar owner id of a followed operation (the top bit set, so it never meets an
/// address).
pub fn operation_launch_id(op: OperationId) -> LaunchId {
    LaunchId(op.0 | (1 << 63))
}

struct LaunchModel {
    title: String,
    subtitle: String,
    cancel: Option<CancelHandler>,
    cancelling: bool,
    tracker: Option<(Arc<LaunchProgressTracker>, ListenerId)>,
    /// The engine operation whose step rows show.
    follow: Option<OperationId>,
    /// Its latest rows.
    op_steps: Option<Arc<OperationStepsView>>,
    /// Bumped on every attach / follow, so the screen rebuilds its rows.
    generation: u64,
}

impl Default for LaunchModel {
    fn default() -> Self {
        LaunchModel {
            title: t!("progress.title.preparingLaunch").into_owned(),
            subtitle: String::new(),
            cancel: None,
            cancelling: false,
            tracker: None,
            follow: None,
            op_steps: None,
            generation: 0,
        }
    }
}

impl LaunchModel {
    /// Takes an engine row update for the followed operation. Returns whether it applied.
    fn apply_steps(&mut self, view: &Arc<OperationStepsView>) -> bool {
        if self.follow != Some(view.operation) {
            return false;
        }
        if view.cancel_requested {
            self.cancelling = true;
        }
        self.op_steps = Some(view.clone());
        true
    }

    /// The rows to show now.
    fn rows(&self) -> Vec<RowData> {
        if let Some((tracker, _)) = &self.tracker {
            return tracker.steps().iter().map(RowData::from_snapshot).collect();
        }
        self.op_steps
            .as_ref()
            .map(|v| v.steps.iter().map(RowData::from_view).collect())
            .unwrap_or_default()
    }

    /// The taskbar id of what the screen follows.
    fn launch_id(&self) -> Option<LaunchId> {
        match (&self.tracker, self.follow) {
            (Some((t, _)), _) => Some(launch_id(t)),
            (None, Some(op)) => Some(operation_launch_id(op)),
            (None, None) => None,
        }
    }
}

/// The model shared between the screen and its handles.
pub struct LaunchShared {
    model: Mutex<LaunchModel>,
    flush_scheduled: AtomicBool,
}

impl LaunchShared {
    fn new() -> Arc<Self> {
        Arc::new(LaunchShared {
            model: Mutex::new(LaunchModel::default()),
            flush_scheduled: AtomicBool::new(false),
        })
    }

    fn detach(&self) -> Option<Arc<LaunchProgressTracker>> {
        let old = self.model.lock().tracker.take();
        old.map(|(tracker, listener)| {
            tracker.remove_listener(listener);
            tracker
        })
    }

    /// Stops following an operation; its taskbar id, if one was followed.
    fn unfollow(&self) -> Option<LaunchId> {
        let mut m = self.model.lock();
        m.op_steps = None;
        m.follow.take().map(operation_launch_id)
    }
}

/// The payload a `ScreenId::LAUNCH_PROGRESS` request carries to bind the screen to a handle.
#[derive(Clone)]
pub struct LaunchProgressBinding(pub Arc<LaunchShared>);

/// Thread-safe control of the Launch progress screen.
#[derive(Clone)]
pub struct LaunchProgressHandle {
    gui: GuiHandle,
    shared: Arc<LaunchShared>,
}

impl std::fmt::Debug for LaunchProgressHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let m = self.shared.model.lock();
        f.debug_struct("LaunchProgressHandle")
            .field("title", &m.title)
            .field("subtitle", &m.subtitle)
            .field("attached", &m.tracker.is_some())
            .field("follow", &m.follow)
            .finish()
    }
}

fn schedule_flush(gui: &GuiHandle, shared: &Arc<LaunchShared>) {
    if shared.flush_scheduled.swap(true, Ordering::AcqRel) {
        return;
    }
    let job = shared.clone();
    let sent = gui.run(move |cx| {
        job.flush_scheduled.store(false, Ordering::Release);
        if let Some(screen) =
            shell::current_screen(cx).and_then(|s| s.downcast::<LaunchProgressScreen>())
            && Arc::ptr_eq(&screen.read(cx).shared, &job)
        {
            screen.update(cx, |s, cx| s.sync(cx));
        }
    });
    if !sent {
        shared.flush_scheduled.store(false, Ordering::Release);
    }
}

impl LaunchProgressHandle {
    fn update(&self, f: impl FnOnce(&mut LaunchModel)) {
        f(&mut self.shared.model.lock());
        schedule_flush(&self.gui, &self.shared);
    }

    /// `setTitle` (`None` -> empty).
    pub fn set_title(&self, s: Option<String>) {
        self.update(|m| m.title = s.unwrap_or_default());
    }

    /// `setSubtitle` (empty hides it).
    pub fn set_subtitle(&self, s: Option<String>) {
        self.update(|m| m.subtitle = s.unwrap_or_default());
    }

    /// `setCancelHandler` (`None` hides the cancel row). A panicking handler is ignored and the
    /// button stays disabled.
    pub fn set_cancel_handler(&self, handler: Option<CancelHandler>) {
        self.update(|m| {
            m.cancel = handler;
            m.cancelling = false;
        });
    }

    /// `attachToTracker`: detaches any previous tracker (and stops following an operation),
    /// then shows one row per step and follows it with a coalescing listener.
    pub fn attach_tracker(&self, tracker: Arc<LaunchProgressTracker>) {
        if let Some(old) = self.shared.detach()
            && !Arc::ptr_eq(&old, &tracker)
        {
            mmcl_platform::progress::end_launch_progress(launch_id(&old));
        }
        if let Some(id) = self.shared.unfollow() {
            mmcl_platform::progress::end_launch_progress(id);
        }
        let weak: Weak<LaunchShared> = Arc::downgrade(&self.shared);
        let gui = self.gui.clone();
        let listener = tracker.add_listener(Arc::new(move |_: &StepSnapshot| {
            if let Some(shared) = weak.upgrade() {
                schedule_flush(&gui, &shared);
            }
        }));
        self.update(|m| {
            m.tracker = Some((tracker, listener));
            m.generation += 1;
        });
    }

    /// Shows the step rows of engine operation `op` (its [`EngineEvent::OperationSteps`]),
    /// starting from `initial` when the caller has them (`Engine::operation_steps`). Detaches a
    /// tracker; `None` stops following.
    pub fn follow_operation(&self, op: Option<OperationId>, initial: Option<OperationStepsView>) {
        if let Some(old) = self.shared.detach() {
            mmcl_platform::progress::end_launch_progress(launch_id(&old));
        }
        if let Some(id) = self.shared.unfollow()
            && op.map(operation_launch_id) != Some(id)
        {
            mmcl_platform::progress::end_launch_progress(id);
        }
        self.update(|m| {
            m.follow = op;
            m.op_steps = initial.filter(|v| Some(v.operation) == op).map(Arc::new);
            m.cancelling = false;
            m.generation += 1;
        });
    }

    /// Applies a step update for the followed operation from any thread (the screen applies
    /// the engine's events itself; this is for callers that read rows on their own).
    pub fn apply_operation_steps(&self, view: OperationStepsView) {
        let view = Arc::new(view);
        let applied = self.shared.model.lock().apply_steps(&view);
        if applied {
            schedule_flush(&self.gui, &self.shared);
        }
    }
}

/// Shows the Launch progress screen from the foreground and returns its handle.
pub fn show_launch_progress(cx: &mut gpui::App) -> Option<LaunchProgressHandle> {
    let gui = shell::handle(cx)?;
    let shared = LaunchShared::new();
    shell::go_to(
        cx,
        ScreenRequest::with_payload(
            ScreenId::LAUNCH_PROGRESS,
            LaunchProgressBinding(shared.clone()),
        ),
    );
    Some(LaunchProgressHandle { gui, shared })
}

/// `goToLaunchProgressGui` from any thread.
pub async fn go_to_launch_progress(gui: &GuiHandle) -> Option<LaunchProgressHandle> {
    let shared = LaunchShared::new();
    let shown = gui
        .go_to_with(
            ScreenId::LAUNCH_PROGRESS,
            LaunchProgressBinding(shared.clone()),
        )
        .await;
    if !shown {
        tracing::warn!("{}", t!("log.controller.launchProgressNotDisplayed"));
        return None;
    }
    Some(LaunchProgressHandle {
        gui: gui.clone(),
        shared,
    })
}

struct Row {
    step: RowData,
    bar: Entity<WavyProgressBar>,
    bar_visible: bool,
    hide: Option<Task<()>>,
}

/// The Launch progress screen.
pub struct LaunchProgressScreen {
    shared: Arc<LaunchShared>,
    title: String,
    subtitle: String,
    cancel: bool,
    cancelling: bool,
    generation: u64,
    rows: Vec<Row>,
    taskbar_attached: bool,
    _events: Option<Subscription>,
}

impl LaunchProgressScreen {
    /// A screen bound to `binding` (a fresh model when `None`), following the engine's step
    /// events for a followed operation.
    pub fn new(binding: Option<LaunchProgressBinding>, cx: &mut Context<Self>) -> Self {
        let events = services::events(cx).map(|events| {
            cx.subscribe(&events, |this: &mut Self, _, ev: &UiEvent, cx| {
                this.on_event(ev, cx)
            })
        });
        LaunchProgressScreen {
            shared: binding.map_or_else(LaunchShared::new, |b| b.0),
            title: String::new(),
            subtitle: String::new(),
            cancel: false,
            cancelling: false,
            generation: u64::MAX,
            rows: Vec::new(),
            taskbar_attached: false,
            _events: events,
        }
    }

    /// A handle for this screen (`None` without a shell).
    pub fn handle(&self, cx: &gpui::App) -> Option<LaunchProgressHandle> {
        Some(LaunchProgressHandle {
            gui: shell::handle(cx)?,
            shared: self.shared.clone(),
        })
    }

    fn on_event(&mut self, ev: &UiEvent, cx: &mut Context<Self>) {
        let applied = match ev {
            UiEvent::Engine(EngineEvent::OperationSteps(view)) => {
                self.shared.model.lock().apply_steps(view)
            }
            // Fell behind: re-read the followed operation's rows.
            UiEvent::Lagged => {
                let follow = self.shared.model.lock().follow;
                match (follow, services::try_engine(cx)) {
                    (Some(op), Some(engine)) => match engine.operation_steps(op) {
                        Some(v) => self.shared.model.lock().apply_steps(&Arc::new(v)),
                        None => false,
                    },
                    _ => false,
                }
            }
            _ => false,
        };
        if applied {
            self.sync(cx);
        }
    }

    fn sync(&mut self, cx: &mut Context<Self>) {
        let (steps, generation, taskbar) = {
            let m = self.shared.model.lock();
            self.title = m.title.clone();
            self.subtitle = m.subtitle.clone();
            self.cancel = m.cancel.is_some();
            self.cancelling = m.cancelling;
            (m.rows(), m.generation, m.launch_id())
        };
        let shown: Vec<String> = self.rows.iter().map(|r| r.step.key.clone()).collect();
        if generation != self.generation || rows_reshaped(&shown, &steps) {
            self.generation = generation;
            // Skipped steps are simply not part of a tracker's step list.
            self.rows = steps
                .iter()
                .map(|s| Row {
                    step: s.clone(),
                    bar: cx.new(|_| WavyProgressBar::new(0.0)),
                    bar_visible: false,
                    hide: None,
                })
                .collect();
        }
        for (i, step) in steps.iter().enumerate() {
            self.render_row(i, step.clone(), cx);
        }
        if self.taskbar_attached
            && let Some(id) = taskbar
        {
            mmcl_platform::progress::set_launch_progress(
                id,
                mmcl_platform::progress::Progress::Fraction(rollup(&steps)),
            );
        }
        cx.notify();
    }

    fn render_row(&mut self, i: usize, step: RowData, cx: &mut Context<Self>) {
        let Some(row) = self.rows.get_mut(i) else {
            return;
        };
        match bar_action(step.state, step.progress, row.bar_visible) {
            BarAction::Show(v) => {
                row.hide = None;
                row.bar_visible = true;
                row.bar.update(cx, |b, cx| b.set_progress(v, cx));
            }
            BarAction::SnapThenHide => {
                row.bar.update(cx, |b, cx| b.set_progress(1.0, cx));
                let timer = cx.background_executor().timer(DONE_HIDE_DELAY);
                let key = row.step.key.clone();
                // Restartable: a newer DONE replaces the pending hide.
                row.hide = Some(cx.spawn(async move |this, cx| {
                    timer.await;
                    let _ = this.update(cx, |s, cx| {
                        if let Some(r) = s.rows.iter_mut().find(|r| r.step.key == key) {
                            r.bar_visible = false;
                            r.hide = None;
                            cx.notify();
                        }
                    });
                }));
            }
            BarAction::Hide => {
                row.hide = None;
                row.bar_visible = false;
            }
            BarAction::KeepHidden => {}
        }
        row.step = step;
    }

    fn cancel_clicked(&mut self, cx: &mut Context<Self>) {
        let handler = {
            let mut m = self.shared.model.lock();
            if m.cancelling {
                return;
            }
            let Some(h) = m.cancel.clone() else { return };
            m.cancelling = true;
            h
        };
        self.sync(cx);
        cx.background_executor()
            .spawn(async move {
                // A panicking handler is ignored; the button stays disabled.
                if catch_unwind(AssertUnwindSafe(|| handler())).is_err() {
                    tracing::warn!("{}", t!("log.progress.cancelFailed"));
                }
            })
            .detach();
    }
}

impl Screen for LaunchProgressScreen {
    fn id(&self) -> ScreenId {
        ScreenId::LAUNCH_PROGRESS
    }

    fn title(&self, _cx: &gpui::App) -> SharedString {
        t!("window.title.launching").into_owned().into()
    }

    fn help_topic(&self) -> HelpTopic {
        HelpTopic::GettingStarted
    }

    fn allows_toolbar_navigation(&self) -> bool {
        false
    }

    fn min_size(&self) -> Size<Pixels> {
        size(px(750.0), px(475.0))
    }

    fn has_own_help_button(&self) -> bool {
        false
    }

    fn setup(&mut self, _window: &mut Window, cx: &mut Context<Self>) {
        self.sync(cx);
    }

    fn after_show(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        mmcl_platform::progress::attach(&*window);
        self.taskbar_attached = true;
        self.sync(cx);
    }

    fn cleanup(&mut self, _cx: &mut Context<Self>) {
        if let Some(tracker) = self.shared.detach() {
            mmcl_platform::progress::end_launch_progress(launch_id(&tracker));
        }
        if let Some(id) = self.shared.unfollow() {
            mmcl_platform::progress::end_launch_progress(id);
        }
        self.taskbar_attached = false;
        for row in &mut self.rows {
            row.hide = None;
        }
    }
}

impl Render for LaunchProgressScreen {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let variant = hsla(t.md_on_surface_variant);
        let rows = self.rows.iter().map(|row| {
            let sub = row_sub_text(&row.step).to_string();
            let sub_visible = !sub.is_empty();
            div()
                .flex()
                .items_center()
                .gap(u(12.0))
                .py(u(4.0))
                .child(StepBadge::new(badge_state(row.step.state)))
                .child(
                    div()
                        .flex()
                        .flex_col()
                        .flex_1()
                        .min_w_0()
                        .child(
                            div()
                                .type_role(TypeRole::TitleSmall)
                                .text_color(hsla(t.md_on_surface))
                                .truncate()
                                .child(row.step.label.clone()),
                        )
                        // Always laid out (one line high) so the row never changes height.
                        .child(
                            div()
                                .h(u(16.0))
                                .type_role(TypeRole::LabelSmall)
                                .text_color(variant)
                                .truncate()
                                .when(!sub_visible, |el| el.invisible())
                                .child(if sub_visible { sub } else { " ".to_string() }),
                        ),
                )
                .child(
                    div()
                        .flex_none()
                        .w(u(200.0))
                        .when(!row.bar_visible, |el| el.invisible())
                        .child(row.bar.clone()),
                )
        });
        let cancelling = self.cancelling;
        let card = progress_card(cx)
            .w(u(600.0))
            .min_w(u(520.0))
            .max_w(u(640.0))
            .pt(u(28.0))
            .px(u(28.0))
            .pb(u(24.0))
            .gap(u(14.0))
            .child(
                div()
                    .max_w(u(560.0))
                    .type_role(TypeRole::TitleLarge)
                    .text_color(hsla(t.md_on_surface))
                    .child(self.title.clone()),
            )
            .when(!self.subtitle.is_empty(), |el| {
                el.child(
                    div()
                        .type_role(TypeRole::BodyMedium)
                        .text_color(variant)
                        .child(self.subtitle.clone()),
                )
            })
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(u(10.0))
                    .py(u(6.0))
                    .max_w(u(560.0))
                    .children(rows),
            )
            .when(self.cancel, |el| {
                el.child(
                    div().pt(u(8.0)).child(
                        Button::new("mmcl-launch-progress-cancel")
                            .label(
                                t!(if cancelling {
                                    "progress.cancelBtn.cancelling"
                                } else {
                                    "progress.cancelBtn.label"
                                })
                                .into_owned(),
                            )
                            .disabled(cancelling)
                            .on_click(
                                cx.listener(|this, _: &ClickEvent, _, cx| this.cancel_clicked(cx)),
                            ),
                    ),
                )
            });
        progress_backdrop(card.into_any_element(), cx)
    }
}

/// Registers the Launch progress screen.
pub fn register(cx: &mut gpui::App) {
    mmcl_ui_kit::ScreenRegistry::register_view(
        cx,
        ScreenId::LAUNCH_PROGRESS,
        |mut req, _window, cx| {
            LaunchProgressScreen::new(req.take_payload::<LaunchProgressBinding>(), cx)
        },
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_progress::StepId;

    fn snap(id: StepId, state: StepState, progress: f64) -> RowData {
        RowData::from_snapshot(&StepSnapshot {
            id,
            state,
            progress,
            sub_text: "sub".into(),
            error: None,
        })
    }

    fn view(id: &str, state: StepStateView) -> LaunchStepView {
        LaunchStepView {
            id: id.into(),
            label: format!("Label {id}"),
            state,
            progress: 0.5,
            sub_text: "files".into(),
            error: None,
        }
    }

    fn steps(op: u64, rows: Vec<LaunchStepView>, cancel: bool) -> Arc<OperationStepsView> {
        Arc::new(OperationStepsView {
            operation: OperationId(op),
            pack: None,
            session: None,
            label: "Verify".into(),
            steps: rows,
            fraction: 0.0,
            cancel_requested: cancel,
        })
    }

    #[test]
    fn sub_text_prefers_a_failure_message() {
        let mut s = snap(StepId::ALL[0], StepState::Failed, 0.0);
        assert_eq!(row_sub_text(&s), "sub");
        s.error = Some(String::new());
        assert_eq!(row_sub_text(&s), "sub", "an empty error keeps the sub-text");
        s.error = Some("boom".into());
        assert_eq!(row_sub_text(&s), "boom");
        s.state = StepState::Running;
        assert_eq!(
            row_sub_text(&s),
            "sub",
            "only a failed step shows its error"
        );
    }

    #[test]
    fn bar_rules() {
        assert_eq!(
            bar_action(StepState::Running, 0.4, false),
            BarAction::Show(0.4)
        );
        assert_eq!(
            bar_action(StepState::Running, 4.0, true),
            BarAction::Show(1.0)
        );
        assert_eq!(
            bar_action(StepState::Done, 1.0, true),
            BarAction::SnapThenHide
        );
        assert_eq!(
            bar_action(StepState::Done, 1.0, false),
            BarAction::KeepHidden
        );
        for s in [StepState::Pending, StepState::Failed, StepState::Skipped] {
            assert_eq!(bar_action(s, 0.5, true), BarAction::Hide);
        }
    }

    #[test]
    fn rollup_counts_finished_steps_as_whole() {
        let ids = StepId::ALL;
        let steps = vec![
            snap(ids[0], StepState::Done, 1.0),
            snap(ids[1], StepState::Failed, 0.2),
            snap(ids[2], StepState::Skipped, 0.0),
            snap(ids[3], StepState::Running, 0.5),
            snap(ids[4], StepState::Pending, 0.9),
        ];
        assert!((rollup(&steps) - 3.5 / 5.0).abs() < 1e-12);
        assert_eq!(rollup(&[]), 0.0);
    }

    #[test]
    fn every_state_maps_to_a_badge() {
        assert_eq!(
            badge_state(StepState::Pending),
            mmcl_ui_kit::StepState::Pending
        );
        assert_eq!(badge_state(StepState::Done), mmcl_ui_kit::StepState::Done);
        assert_eq!(
            badge_state(StepState::Skipped),
            mmcl_ui_kit::StepState::Skipped
        );
        for id in StepId::ALL {
            assert!(mmcl_i18n::english(id.label_key()).is_some(), "{id:?}");
        }
    }

    #[test]
    fn engine_rows_convert() {
        let r = RowData::from_view(&view("files", StepStateView::Running));
        assert_eq!(r.key, "files");
        assert_eq!(r.label, "Label files");
        assert_eq!(r.state, StepState::Running);
        for (v, s) in [
            (StepStateView::Pending, StepState::Pending),
            (StepStateView::Done, StepState::Done),
            (StepStateView::Failed, StepState::Failed),
            (StepStateView::Skipped, StepState::Skipped),
        ] {
            assert_eq!(state_of_view(v), s);
        }
        let mut blank = view("no-such-step-id", StepStateView::Done);
        blank.label = " ".into();
        assert_eq!(RowData::from_view(&blank).label, "no-such-step-id");
    }

    #[test]
    fn only_the_followed_operation_applies() {
        let mut m = LaunchModel::default();
        let v = steps(7, vec![view("a", StepStateView::Running)], false);
        assert!(!m.apply_steps(&v), "nothing followed");
        m.follow = Some(OperationId(8));
        assert!(!m.apply_steps(&v), "another operation");
        m.follow = Some(OperationId(7));
        assert!(m.apply_steps(&v));
        assert_eq!(m.rows().len(), 1);
        assert!(!m.cancelling);
        assert!(m.apply_steps(&steps(7, Vec::new(), true)));
        assert!(m.cancelling, "an engine-side cancel disables the button");
        assert_eq!(m.launch_id(), Some(operation_launch_id(OperationId(7))));
    }

    #[test]
    fn row_lists_rebuild_only_when_reshaped() {
        let rows: Vec<RowData> = ["a", "b"]
            .iter()
            .map(|k| RowData::from_view(&view(k, StepStateView::Pending)))
            .collect();
        let keys = |v: &[&str]| v.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        assert!(!rows_reshaped(&keys(&["a", "b"]), &rows));
        assert!(rows_reshaped(&keys(&["b", "a"]), &rows));
        assert!(rows_reshaped(&keys(&["a"]), &rows));
        assert!(rows_reshaped(&[], &rows));
        assert_ne!(
            operation_launch_id(OperationId(1)).0,
            1,
            "operation ids never meet tracker addresses"
        );
    }

    /// Guarded bug 23: a burst of tracker events schedules one flush.
    #[test]
    fn listener_bursts_coalesce() {
        let shared = LaunchShared::new();
        let mut posted = 0;
        for _ in 0..4000 {
            if !shared.flush_scheduled.swap(true, Ordering::AcqRel) {
                posted += 1;
            }
        }
        assert_eq!(posted, 1);
        shared.flush_scheduled.store(false, Ordering::Release);
        assert!(!shared.flush_scheduled.swap(true, Ordering::AcqRel));
    }

    #[test]
    fn detach_removes_the_listener() {
        let tracker = LaunchProgressTracker::for_steps(&StepId::ALL[..2]);
        let shared = LaunchShared::new();
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let h = hits.clone();
        let listener = tracker.add_listener(Arc::new(move |_| {
            h.fetch_add(1, Ordering::Relaxed);
        }));
        shared.model.lock().tracker = Some((tracker.clone(), listener));
        tracker.mark_running(StepId::ALL[0]).unwrap();
        let before = hits.load(Ordering::Relaxed);
        assert!(before > 0);
        assert!(shared.detach().is_some());
        tracker.mark_done(StepId::ALL[0]).unwrap();
        assert_eq!(hits.load(Ordering::Relaxed), before);
        assert!(shared.detach().is_none());
    }

    fn _assert_send_sync() {
        fn check<T: Send + Sync>() {}
        check::<LaunchProgressHandle>();
        check::<LaunchProgressBinding>();
    }
}

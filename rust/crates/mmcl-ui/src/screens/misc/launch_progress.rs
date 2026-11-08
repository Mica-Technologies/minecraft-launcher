// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `MCLauncherLaunchProgressGui`: one row per step of a [`LaunchProgressTracker`] (used by
//! "Verify now"; game launches show their steps in the Running Games window), and
//! [`LaunchProgressHandle`].
//!
//! The tracker's listener runs on worker threads and fires thousands of times on a warm launch
//! (one event per file). It only flips an `AtomicBool` and posts **one** foreground flush, which
//! re-reads every row from the tracker (guarded bug 23). Rows keep their sub-text and bar
//! managed (only visibility changes), so a row never changes height.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Weak};
use std::time::Duration;

use gpui::{
    AppContext as _, ClickEvent, Context, Entity, IntoElement, ParentElement, Pixels, Render,
    SharedString, Size, Styled, Task, Window, div, prelude::FluentBuilder, px, size,
};
use gpui_component::Disableable as _;
use gpui_component::button::Button;
use mmcl_i18n::t;
use mmcl_platform::progress::LaunchId;
use mmcl_progress::{LaunchProgressTracker, ListenerId, StepId, StepSnapshot, StepState};
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::theme::{ThemeExt as _, TypeStyleExt as _, hsla};
use mmcl_ui_kit::{HelpTopic, Screen, ScreenId, ScreenRequest, StepBadge, WavyProgressBar};
use parking_lot::Mutex;

use super::progress::CancelHandler;
use super::visuals::{progress_backdrop, progress_card};
use crate::scale::u;
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

/// `renderRow`'s sub-text: the error when FAILED and non-empty, else the sub-text.
pub fn row_sub_text(step: &StepSnapshot) -> &str {
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
pub fn rollup(steps: &[StepSnapshot]) -> f64 {
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

/// The taskbar owner id of a tracker (its address; unique while it lives).
fn launch_id(tracker: &Arc<LaunchProgressTracker>) -> LaunchId {
    LaunchId(Arc::as_ptr(tracker) as usize as u64)
}

struct LaunchModel {
    title: String,
    subtitle: String,
    cancel: Option<CancelHandler>,
    cancelling: bool,
    tracker: Option<(Arc<LaunchProgressTracker>, ListenerId)>,
    /// Bumped on every attach, so the screen rebuilds its rows.
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
            generation: 0,
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

    /// `attachToTracker`: detaches any previous tracker, then shows one row per step and
    /// follows it with a coalescing listener.
    pub fn attach_tracker(&self, tracker: Arc<LaunchProgressTracker>) {
        if let Some(old) = self.shared.detach()
            && !Arc::ptr_eq(&old, &tracker)
        {
            mmcl_platform::progress::end_launch_progress(launch_id(&old));
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
    id: StepId,
    step: StepSnapshot,
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
}

impl LaunchProgressScreen {
    /// A screen bound to `binding` (a fresh model when `None`).
    pub fn new(binding: Option<LaunchProgressBinding>) -> Self {
        LaunchProgressScreen {
            shared: binding.map_or_else(LaunchShared::new, |b| b.0),
            title: String::new(),
            subtitle: String::new(),
            cancel: false,
            cancelling: false,
            generation: u64::MAX,
            rows: Vec::new(),
            taskbar_attached: false,
        }
    }

    fn sync(&mut self, cx: &mut Context<Self>) {
        let (tracker, generation) = {
            let m = self.shared.model.lock();
            self.title = m.title.clone();
            self.subtitle = m.subtitle.clone();
            self.cancel = m.cancel.is_some();
            self.cancelling = m.cancelling;
            (m.tracker.as_ref().map(|(t, _)| t.clone()), m.generation)
        };
        let steps = tracker.as_ref().map(|t| t.steps()).unwrap_or_default();
        if generation != self.generation {
            self.generation = generation;
            // Skipped steps are simply not part of a tracker's step list.
            self.rows = steps
                .iter()
                .map(|s| Row {
                    id: s.id,
                    step: s.clone(),
                    bar: cx.new(|_| WavyProgressBar::new(0.0)),
                    bar_visible: false,
                    hide: None,
                })
                .collect();
        }
        for i in 0..self.rows.len() {
            let Some(step) = steps.iter().find(|s| s.id == self.rows[i].id).cloned() else {
                continue;
            };
            self.render_row(i, step, cx);
        }
        if self.taskbar_attached
            && let Some(t) = &tracker
        {
            mmcl_platform::progress::set_launch_progress(
                launch_id(t),
                mmcl_platform::progress::Progress::Fraction(rollup(&steps)),
            );
        }
        cx.notify();
    }

    fn render_row(&mut self, i: usize, step: StepSnapshot, cx: &mut Context<Self>) {
        let row = &mut self.rows[i];
        match bar_action(step.state, step.progress, row.bar_visible) {
            BarAction::Show(v) => {
                row.hide = None;
                row.bar_visible = true;
                row.bar.update(cx, |b, cx| b.set_progress(v, cx));
            }
            BarAction::SnapThenHide => {
                row.bar.update(cx, |b, cx| b.set_progress(1.0, cx));
                let timer = cx.background_executor().timer(DONE_HIDE_DELAY);
                let id = row.id;
                // Restartable: a newer DONE replaces the pending hide.
                row.hide = Some(cx.spawn(async move |this, cx| {
                    timer.await;
                    let _ = this.update(cx, |s, cx| {
                        if let Some(r) = s.rows.iter_mut().find(|r| r.id == id) {
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
                                .child(t!(row.id.label_key()).into_owned()),
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
        |mut req, _window, _cx| {
            LaunchProgressScreen::new(req.take_payload::<LaunchProgressBinding>())
        },
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snap(id: StepId, state: StepState, progress: f64) -> StepSnapshot {
        StepSnapshot {
            id,
            state,
            progress,
            sub_text: "sub".into(),
            error: None,
        }
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

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `MCLauncherProgressGui`: the three-label progress screen (startup sign-in, runtime work,
//! legacy flows), and [`ProgressHandle`], its thread-safe remote control.
//!
//! The handle writes into a shared model and schedules **one** foreground flush per burst
//! (an `AtomicBool` gate), so a worker reporting thousands of updates never floods the UI.
//! The screen can also follow an engine operation's [`EngineEvent::Progress`] events directly
//! ([`ProgressHandle::follow_operation`]).

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use gpui::{
    AppContext as _, ClickEvent, Context, Entity, IntoElement, ParentElement, Pixels, Render,
    SharedString, Size, Styled, Subscription, Window, div, prelude::FluentBuilder, px, size,
};
use gpui_component::Disableable as _;
use gpui_component::button::{Button, ButtonVariants as _};
use mmcl_engine::{EngineEvent, OperationId};
use mmcl_i18n::t;
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::theme::{ThemeExt as _, TypeStyleExt as _, hsla};
use mmcl_ui_kit::{HelpTopic, Screen, ScreenId, ScreenRequest, WavyProgressBar};
use parking_lot::Mutex;

use super::visuals::{progress_backdrop, progress_card, voxel_row};
use crate::scale::u;
use crate::services::{self, UiEvent};
use crate::shell::{self, GuiHandle};

/// `GameModPackProgressProvider.PROGRESS_PERCENT_BASE`.
pub const PERCENT_BASE: f64 = 100.0;
/// `INDETERMINATE`.
pub const INDETERMINATE: f64 = -1.0;

/// A progress value for the bar.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Progress {
    /// `0..=100`.
    Percent(f64),
    /// Unknown length (the bar sweeps).
    Indeterminate,
}

impl Progress {
    /// From the Java convention: `0..=100`, or negative for indeterminate.
    pub fn from_java(p: f64) -> Self {
        if p < 0.0 || p.is_nan() {
            Progress::Indeterminate
        } else {
            Progress::Percent(p.min(PERCENT_BASE))
        }
    }

    /// From a fraction `0..=1` (negative = indeterminate), the engine's convention.
    pub fn from_fraction(f: f64) -> Self {
        if f < 0.0 || f.is_nan() {
            Progress::Indeterminate
        } else {
            Progress::Percent((f * PERCENT_BASE).min(PERCENT_BASE))
        }
    }

    /// The bar value: `0..=1`, or `-1` for indeterminate.
    pub fn bar_value(self) -> f64 {
        match self {
            Progress::Percent(p) => (p / PERCENT_BASE).clamp(0.0, 1.0),
            Progress::Indeterminate => INDETERMINATE,
        }
    }

    /// The taskbar / dock value.
    pub fn taskbar(self) -> mmcl_platform::progress::Progress {
        match self {
            Progress::Percent(_) => mmcl_platform::progress::Progress::Fraction(self.bar_value()),
            Progress::Indeterminate => mmcl_platform::progress::Progress::Indeterminate,
        }
    }
}

/// A cancel action. A panic inside it counts as "did not cancel".
pub type CancelHandler = Arc<dyn Fn() + Send + Sync>;

/// What the screen shows.
#[derive(Clone)]
pub struct ProgressModel {
    /// `upperLabel` (title).
    pub upper: String,
    /// `sectionLabel` (current step).
    pub section: String,
    /// `detailLabel`.
    pub detail: String,
    /// `speedLabel` chip (hidden while empty).
    pub speed: String,
    /// The bar.
    pub progress: Progress,
    /// The cancel action (`None` hides the cancel row).
    pub cancel: Option<CancelHandler>,
    /// The cancel button was pressed and the handler is running / ran.
    pub cancelling: bool,
    /// An engine operation whose progress events drive the labels and bar.
    pub follow: Option<OperationId>,
}

impl Default for ProgressModel {
    fn default() -> Self {
        ProgressModel {
            upper: t!("progress.justAMoment").into_owned(),
            section: String::new(),
            detail: String::new(),
            speed: String::new(),
            progress: Progress::Percent(0.0),
            cancel: None,
            cancelling: false,
            follow: None,
        }
    }
}

impl std::fmt::Debug for ProgressModel {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ProgressModel")
            .field("upper", &self.upper)
            .field("section", &self.section)
            .field("detail", &self.detail)
            .field("speed", &self.speed)
            .field("progress", &self.progress)
            .field("cancel", &self.cancel.is_some())
            .field("cancelling", &self.cancelling)
            .field("follow", &self.follow)
            .finish()
    }
}

impl ProgressModel {
    /// The cancel button's label key.
    pub fn cancel_label_key(&self) -> &'static str {
        if self.cancelling {
            "progress.cancelBtn.cancelling"
        } else {
            "progress.cancelBtn.label"
        }
    }

    /// Applies an engine progress event for the followed operation. Returns whether it applied.
    pub fn apply_operation(&mut self, p: &mmcl_engine::OperationProgress) -> bool {
        if self.follow != Some(p.operation) {
            return false;
        }
        if !p.label.is_empty() {
            self.upper = p.label.clone();
        }
        self.section = p.text.clone();
        self.progress = Progress::from_fraction(p.fraction);
        true
    }
}

/// The model shared between a screen and its handles.
pub struct ProgressShared {
    model: Mutex<ProgressModel>,
    flush_scheduled: AtomicBool,
}

impl ProgressShared {
    fn new() -> Arc<Self> {
        Arc::new(ProgressShared {
            model: Mutex::new(ProgressModel::default()),
            flush_scheduled: AtomicBool::new(false),
        })
    }

    /// A snapshot of the model.
    pub fn snapshot(&self) -> ProgressModel {
        self.model.lock().clone()
    }
}

/// The payload a `ScreenId::PROGRESS` request carries to bind the screen to a handle.
#[derive(Clone)]
pub struct ProgressBinding(pub Arc<ProgressShared>);

/// Thread-safe control of the Progress screen (any thread; marshalled to the UI).
#[derive(Clone)]
pub struct ProgressHandle {
    gui: GuiHandle,
    shared: Arc<ProgressShared>,
}

impl std::fmt::Debug for ProgressHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ProgressHandle")
            .field("model", &*self.shared.model.lock())
            .finish()
    }
}

impl ProgressHandle {
    fn update(&self, f: impl FnOnce(&mut ProgressModel)) {
        f(&mut self.shared.model.lock());
        schedule_flush(&self.gui, &self.shared);
    }

    /// `setUpperLabelText`.
    pub fn set_upper(&self, s: impl Into<String>) {
        let s = s.into();
        self.update(|m| m.upper = s);
    }

    /// `setSectionText` (also `setLowerLabelText`).
    pub fn set_section(&self, s: impl Into<String>) {
        let s = s.into();
        self.update(|m| m.section = s);
    }

    /// `setLowerLabelText`: the section label.
    pub fn set_lower(&self, s: impl Into<String>) {
        self.set_section(s);
    }

    /// `setLabelTexts(upper, lower)`.
    pub fn set_labels(&self, upper: impl Into<String>, lower: impl Into<String>) {
        let (upper, lower) = (upper.into(), lower.into());
        self.update(|m| {
            m.upper = upper;
            m.section = lower;
        });
    }

    /// `setDetailText`.
    pub fn set_detail(&self, s: impl Into<String>) {
        let s = s.into();
        self.update(|m| m.detail = s);
    }

    /// `setSpeedText` (an empty text hides the chip).
    pub fn set_speed(&self, s: impl Into<String>) {
        let s = s.into();
        self.update(|m| m.speed = s);
    }

    /// `setProgress` (also mirrored to the taskbar / dock).
    pub fn set_progress(&self, p: Progress) {
        self.update(|m| m.progress = p);
    }

    /// `setCancelHandler`: `None` hides the cancel row; a new handler resets the button.
    pub fn set_cancel_handler(&self, handler: Option<CancelHandler>) {
        self.update(|m| {
            m.cancel = handler;
            m.cancelling = false;
        });
    }

    /// Drives the labels and bar from an engine operation's progress events.
    pub fn follow_operation(&self, operation: Option<OperationId>) {
        self.update(|m| m.follow = operation);
    }

    /// The current model (for tests and diagnostics).
    pub fn snapshot(&self) -> ProgressModel {
        self.shared.snapshot()
    }
}

fn schedule_flush(gui: &GuiHandle, shared: &Arc<ProgressShared>) {
    if shared.flush_scheduled.swap(true, Ordering::AcqRel) {
        return;
    }
    let job = shared.clone();
    let sent = gui.run(move |cx| {
        job.flush_scheduled.store(false, Ordering::Release);
        flush(cx, &job);
    });
    if !sent {
        // No GUI: clear the gate so a later update can try again.
        shared.flush_scheduled.store(false, Ordering::Release);
    }
}

fn flush(cx: &mut gpui::App, shared: &Arc<ProgressShared>) {
    if let Some(screen) = shell::current_screen(cx).and_then(|s| s.downcast::<ProgressScreen>())
        && Arc::ptr_eq(&screen.read(cx).shared, shared)
    {
        screen.update(cx, |s, cx| s.sync(cx));
    }
}

/// `goToProgressGui` from the foreground: shows the screen and returns its handle (`None` when
/// the shell is not installed).
pub fn show_progress(cx: &mut gpui::App) -> Option<ProgressHandle> {
    let gui = shell::handle(cx)?;
    let shared = ProgressShared::new();
    shell::go_to(
        cx,
        ScreenRequest::with_payload(ScreenId::PROGRESS, ProgressBinding(shared.clone())),
    );
    Some(ProgressHandle { gui, shared })
}

/// `goToProgressGui` from any thread: `None` when the screen could not be shown.
pub async fn go_to_progress(gui: &GuiHandle) -> Option<ProgressHandle> {
    let shared = ProgressShared::new();
    let shown = gui
        .go_to_with(ScreenId::PROGRESS, ProgressBinding(shared.clone()))
        .await;
    if !shown {
        tracing::warn!("{}", t!("log.controller.progressNotDisplayed"));
        return None;
    }
    Some(ProgressHandle {
        gui: gui.clone(),
        shared,
    })
}

/// The Progress screen.
pub struct ProgressScreen {
    shared: Arc<ProgressShared>,
    shown: ProgressModel,
    bar: Entity<WavyProgressBar>,
    taskbar_attached: bool,
    last_taskbar: Option<Progress>,
    _events: Option<Subscription>,
}

impl ProgressScreen {
    /// A screen bound to `binding` (a fresh model when `None`).
    pub fn new(binding: Option<ProgressBinding>, cx: &mut Context<Self>) -> Self {
        let shared = binding.map_or_else(ProgressShared::new, |b| b.0);
        let shown = shared.snapshot();
        let bar = cx.new(|_| WavyProgressBar::new(shown.progress.bar_value()));
        let events = services::events(cx).map(|events| {
            cx.subscribe(&events, |this: &mut Self, _, ev: &UiEvent, cx| {
                if let UiEvent::Engine(EngineEvent::Progress(p)) = ev {
                    let applied = this.shared.model.lock().apply_operation(p);
                    if applied {
                        this.sync(cx);
                    }
                }
            })
        });
        ProgressScreen {
            shared,
            shown,
            bar,
            taskbar_attached: false,
            last_taskbar: None,
            _events: events,
        }
    }

    /// A handle for this screen (`None` without a shell).
    pub fn handle(&self, cx: &gpui::App) -> Option<ProgressHandle> {
        Some(ProgressHandle {
            gui: shell::handle(cx)?,
            shared: self.shared.clone(),
        })
    }

    /// Re-reads the model into the view (bar, taskbar, labels).
    fn sync(&mut self, cx: &mut Context<Self>) {
        self.shown = self.shared.snapshot();
        let value = self.shown.progress.bar_value();
        self.bar.update(cx, |b, cx| b.set_progress(value, cx));
        if self.taskbar_attached && self.last_taskbar != Some(self.shown.progress) {
            self.last_taskbar = Some(self.shown.progress);
            mmcl_platform::progress::set_progress(self.shown.progress.taskbar());
        }
        cx.notify();
    }

    fn cancel_clicked(&mut self, cx: &mut Context<Self>) {
        let handler = {
            let mut m = self.shared.model.lock();
            if m.cancelling {
                return;
            }
            let Some(h) = m.cancel.clone() else { return };
            // Optimistic: the worker may be mid-read and not notice for a moment.
            m.cancelling = true;
            h
        };
        self.sync(cx);
        let shared = self.shared.clone();
        let run = cx
            .background_executor()
            .spawn(async move { catch_unwind(AssertUnwindSafe(|| handler())).is_ok() });
        cx.spawn(async move |this, cx| {
            if !run.await {
                tracing::warn!("{}", t!("log.progress.cancelFailed"));
                shared.model.lock().cancelling = false;
                let _ = this.update(cx, |s, cx| s.sync(cx));
            }
        })
        .detach();
    }
}

impl Screen for ProgressScreen {
    fn id(&self) -> ScreenId {
        ScreenId::PROGRESS
    }

    fn title(&self, _cx: &gpui::App) -> SharedString {
        t!("window.title.loading").into_owned().into()
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

    fn after_show(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        // Only now: native taskbar access before the window is realised crashed.
        mmcl_platform::progress::attach(&*window);
        self.taskbar_attached = true;
        self.last_taskbar = None;
        self.sync(cx);
    }

    fn cleanup(&mut self, _cx: &mut Context<Self>) {
        // The taskbar owner lives for the whole app; a screen only stops it.
        if self.taskbar_attached {
            mmcl_platform::progress::stop();
        }
        self.taskbar_attached = false;
        self._events = None;
    }
}

impl Render for ProgressScreen {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let m = &self.shown;
        let on_surface = hsla(t.md_on_surface);
        let variant = hsla(t.md_on_surface_variant);
        let card = progress_card(cx)
            .items_center()
            .gap(u(10.0))
            .w(u(460.0))
            .min_w(u(400.0))
            .max_w(u(480.0))
            .pt(u(32.0))
            .px(u(32.0))
            .pb(u(28.0))
            .child(voxel_row())
            .child(
                div()
                    .max_w(u(400.0))
                    .text_center()
                    .type_role(TypeRole::TitleLarge)
                    .text_color(on_surface)
                    .child(m.upper.clone()),
            )
            .when(!m.section.is_empty(), |el| {
                el.child(
                    div()
                        .text_center()
                        .type_role(TypeRole::BodyMedium)
                        .text_color(variant)
                        .child(m.section.clone()),
                )
            })
            .child(
                div()
                    .w_full()
                    .max_w(u(360.0))
                    .mt(u(6.0))
                    .mb(u(2.0))
                    .child(self.bar.clone()),
            )
            .when(!m.detail.is_empty(), |el| {
                el.child(
                    div()
                        .text_center()
                        .type_role(TypeRole::BodySmall)
                        .text_color(variant)
                        .child(m.detail.clone()),
                )
            })
            .when(!m.speed.is_empty(), |el| {
                el.child(
                    div()
                        .px(u(12.0))
                        .py(u(4.0))
                        .rounded_full()
                        .bg(hsla(t.md_surface_container_highest))
                        .type_role(TypeRole::BodySmall)
                        .text_color(variant)
                        .child(m.speed.clone()),
                )
            })
            .when(m.cancel.is_some(), |el| {
                let cancelling = m.cancelling;
                el.child(
                    div().pt(u(10.0)).child(
                        Button::new("mmcl-progress-cancel")
                            .label(t!(m.cancel_label_key()).into_owned())
                            .with_variant(gpui_component::button::ButtonVariant::Secondary)
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

/// Registers the Progress screen.
pub fn register(cx: &mut gpui::App) {
    mmcl_ui_kit::ScreenRegistry::register_view(cx, ScreenId::PROGRESS, |mut req, _window, cx| {
        ProgressScreen::new(req.take_payload::<ProgressBinding>(), cx)
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn progress_conventions() {
        assert_eq!(Progress::from_java(-1.0), Progress::Indeterminate);
        assert_eq!(Progress::from_java(50.0).bar_value(), 0.5);
        assert_eq!(Progress::from_java(250.0).bar_value(), 1.0);
        assert_eq!(Progress::from_fraction(0.25), Progress::Percent(25.0));
        assert_eq!(Progress::from_fraction(-0.5), Progress::Indeterminate);
        assert_eq!(Progress::Indeterminate.bar_value(), INDETERMINATE);
        assert_eq!(
            Progress::from_fraction(0.3).taskbar(),
            mmcl_platform::progress::Progress::Fraction(0.3)
        );
    }

    #[test]
    fn model_defaults_and_cancel_label() {
        let mut m = ProgressModel::default();
        assert_eq!(m.upper, mmcl_i18n::english("progress.justAMoment").unwrap());
        assert!(m.section.is_empty() && m.speed.is_empty() && m.cancel.is_none());
        assert_eq!(m.cancel_label_key(), "progress.cancelBtn.label");
        m.cancelling = true;
        assert_eq!(m.cancel_label_key(), "progress.cancelBtn.cancelling");
    }

    #[test]
    fn only_the_followed_operation_applies() {
        let mut m = ProgressModel::default();
        let ev = mmcl_engine::OperationProgress {
            operation: OperationId(7),
            label: "Installing".into(),
            text: "Downloading".into(),
            fraction: 0.5,
        };
        assert!(!m.apply_operation(&ev));
        m.follow = Some(OperationId(7));
        assert!(m.apply_operation(&ev));
        assert_eq!(m.upper, "Installing");
        assert_eq!(m.section, "Downloading");
        assert_eq!(m.progress, Progress::Percent(50.0));
        let other = mmcl_engine::OperationProgress {
            operation: OperationId(8),
            ..ev
        };
        assert!(!m.apply_operation(&other));
    }

    #[test]
    fn i18n_keys_exist() {
        for k in [
            "progress.justAMoment",
            "progress.cancelBtn.label",
            "progress.cancelBtn.cancelling",
            "window.title.loading",
            "log.controller.progressNotDisplayed",
            "log.progress.cancelFailed",
        ] {
            assert!(mmcl_i18n::english(k).is_some(), "{k}");
        }
    }

    fn _assert_send_sync() {
        fn check<T: Send + Sync>() {}
        check::<ProgressHandle>();
        check::<ProgressBinding>();
    }
}

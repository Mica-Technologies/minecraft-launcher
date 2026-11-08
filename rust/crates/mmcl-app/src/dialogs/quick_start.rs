// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The first-run quick-start wizard (Java `MCLauncherQuickStartWizard`, gui-editor-games §11.1).
//!
//! A 620 x 540 window over the launcher with five steps (Welcome, Theme, RAM, Preferences,
//! Done) and a navigation bar: "Step n of 5", Skip, Back, Next / Get Started. Every step keeps
//! its state when the user goes Back. Settings are written as the user changes them, so closing
//! mid-way keeps what was chosen; finishing, skipping and closing all set `quickStartCompleted`.
//!
//! Fixed from Java: the RAM step writes `maxRam` only when the user moves the slider (Java once
//! wrote it while building the step, overwriting the saved RAM just by opening the wizard).

use gpui::{
    AnyWindowHandle, App, AppContext as _, Context, Entity, FontWeight, Global,
    InteractiveElement as _, IntoElement, ParentElement, Render, StatefulInteractiveElement as _,
    Styled, Subscription, Window, WindowHandle, div, px, relative,
};
use gpui_component::Root;
use gpui_component::slider::{Slider, SliderEvent, SliderState, SliderValue};
use mmcl_engine::{Engine, Setting};
use mmcl_i18n::{t, tf};
use mmcl_ui_kit::focus::nav_scope;
use mmcl_ui_kit::scale::u;
use mmcl_ui_kit::{Switch, ThemeExt as _, hsla};

use super::widgets::{Pill, Tone, body, heading, hint, spacer, subtitle};

/// The wizard window size (px at 100 %).
pub const SIZE: (f32, f32) = (620.0, 540.0);
/// The number of steps.
pub const STEP_COUNT: usize = 5;
/// The recommendation never goes above this (GB).
pub const RAM_RECOMMENDATION_MAX_GB: u32 = 16;
/// Slider minimum (GB).
pub const RAM_SLIDER_MIN_GB: u32 = 2;
/// Slider maximum (GB).
pub const RAM_SLIDER_MAX_GB: u32 = 64;
/// The config default of `maxRam` (MB): a value still at it means "never chosen".
pub const MAX_RAM_DEFAULT_MB: i64 = 2048;
/// The system RAM assumed when detection fails (GB).
pub const FALLBACK_SYSTEM_GB: u64 = 8;
/// Tick labels every this many GB, starting at the slider minimum.
pub const RAM_MAJOR_TICK_GB: u32 = 4;

/// The themes offered, in order, with their swatch colour (`THEME_SWATCHES`).
pub const THEME_SWATCHES: [(&str, u32); 7] = [
    ("Native (Mica)", 0x6FCF3D),
    ("Dark", 0x6FCF3D),
    ("Light", 0x3C8527),
    ("Blue+gray", 0x0668E1),
    ("Orange+purple", 0xD257DB),
    ("Creeper", 0x43D22D),
    ("Automatic", 0x888888),
];

/// One step of the wizard.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Step {
    Welcome,
    Theme,
    Ram,
    Preferences,
    Done,
}

impl Step {
    /// Every step, in order.
    pub const ALL: [Step; STEP_COUNT] = [
        Step::Welcome,
        Step::Theme,
        Step::Ram,
        Step::Preferences,
        Step::Done,
    ];
}

/// `max(1, floor(total_gb))` (Java floors bytes / 2^30); `None` (detection failed) is 8.
pub fn system_ram_gb(total_gb: Option<f64>) -> u64 {
    match total_gb {
        Some(gb) if gb.is_finite() && gb > 0.0 => (gb.floor() as u64).max(1),
        Some(_) => 1,
        None => FALLBACK_SYSTEM_GB,
    }
}

/// `max(2, min(16, system / 2))`.
pub fn recommended_ram_gb(system_gb: u64) -> u32 {
    let half = (system_gb / 2).min(u64::from(RAM_RECOMMENDATION_MAX_GB)) as u32;
    half.max(RAM_SLIDER_MIN_GB)
}

/// The slider's start: the recommendation while `maxRam` is still the 2048 MB default, else the
/// saved value clamped to the slider.
pub fn initial_ram_gb(max_ram_mb: i64, recommended_gb: u32) -> u32 {
    if max_ram_mb == MAX_RAM_DEFAULT_MB {
        return recommended_gb;
    }
    let gb = (max_ram_mb / 1024).clamp(i64::from(RAM_SLIDER_MIN_GB), i64::from(RAM_SLIDER_MAX_GB));
    gb as u32
}

/// The slider value snapped to a whole GB inside the slider range.
pub fn snap_ram(value: f32) -> u32 {
    let v = value.round();
    let v = if v.is_finite() { v } else { 0.0 };
    (v.max(RAM_SLIDER_MIN_GB as f32)
        .min(RAM_SLIDER_MAX_GB as f32)) as u32
}

/// The tick labels under the slider: 2, 6, 10, ... up to the maximum.
pub fn ram_tick_labels() -> Vec<u32> {
    (RAM_SLIDER_MIN_GB..=RAM_SLIDER_MAX_GB)
        .step_by(RAM_MAJOR_TICK_GB as usize)
        .collect()
}

/// What Next / Get Started does.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Advance {
    /// Moved to another step.
    Moved,
    /// The last step: finish.
    Finish,
}

/// The wizard's pure state.
#[derive(Debug, Clone, PartialEq)]
pub struct WizardState {
    step: usize,
    /// The selected theme id (config value).
    pub theme: String,
    pub system_gb: u64,
    pub recommended_gb: u32,
    pub ram_gb: u32,
    /// The user moved the RAM slider (only then is `maxRam` written).
    pub ram_touched: bool,
    pub discord: bool,
    pub updates: bool,
}

impl WizardState {
    /// The state for these saved settings.
    pub fn new(
        theme: &str,
        max_ram_mb: i64,
        total_gb: Option<f64>,
        discord: bool,
        updates: bool,
    ) -> Self {
        let system_gb = system_ram_gb(total_gb);
        let recommended_gb = recommended_ram_gb(system_gb);
        WizardState {
            step: 0,
            theme: theme.to_string(),
            system_gb,
            recommended_gb,
            ram_gb: initial_ram_gb(max_ram_mb, recommended_gb),
            ram_touched: false,
            discord,
            updates,
        }
    }

    /// The current step's index (0-based).
    #[allow(dead_code)]
    pub fn index(&self) -> usize {
        self.step
    }

    /// The current step.
    pub fn step(&self) -> Step {
        Step::ALL[self.step]
    }

    /// Next / Get Started.
    pub fn next(&mut self) -> Advance {
        if self.step + 1 >= STEP_COUNT {
            Advance::Finish
        } else {
            self.step += 1;
            Advance::Moved
        }
    }

    /// Back; `false` on the first step (the button is disabled there).
    pub fn back(&mut self) -> bool {
        if self.step == 0 {
            return false;
        }
        self.step -= 1;
        true
    }

    /// Whether Back is enabled.
    pub fn can_go_back(&self) -> bool {
        self.step > 0
    }

    /// Whether the next button reads Get Started.
    pub fn is_last(&self) -> bool {
        self.step + 1 == STEP_COUNT
    }

    /// The progress label key and arguments: `Step {n} of {total}`.
    pub fn progress(&self) -> (usize, usize) {
        (self.step + 1, STEP_COUNT)
    }

    /// Picks a theme. Picking the selected one again is refused (one stays selected); `true`
    /// when it changed.
    pub fn pick_theme(&mut self, id: &str) -> bool {
        if self.theme.eq_ignore_ascii_case(id) {
            return false;
        }
        self.theme = id.to_string();
        true
    }

    /// Whether `id` is the selected theme (the saved value matches case-insensitively).
    pub fn is_theme(&self, id: &str) -> bool {
        self.theme.eq_ignore_ascii_case(id)
    }

    /// The slider moved. Returns the `maxRam` (MB) to save, or `None` when it did not change.
    pub fn move_ram(&mut self, value: f32) -> Option<i64> {
        let gb = snap_ram(value);
        if gb == self.ram_gb && self.ram_touched {
            return None;
        }
        self.ram_touched = true;
        self.ram_gb = gb;
        Some(i64::from(gb) * 1024)
    }
}

/// The open wizard, if any.
#[derive(Default)]
struct OpenWizard(Option<WindowHandle<Root>>);

impl Global for OpenWizard {}

/// The wizard's root view.
pub struct QuickStartWizard {
    engine: Engine,
    state: WizardState,
    slider: Entity<SliderState>,
    _slider_events: Subscription,
    finished: bool,
}

impl QuickStartWizard {
    fn new(
        engine: Engine,
        state: WizardState,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let slider = cx.new(|_| {
            SliderState::new()
                .min(RAM_SLIDER_MIN_GB as f32)
                .max(RAM_SLIDER_MAX_GB as f32)
                .step(1.0)
                .default_value(state.ram_gb as f32)
        });
        let sub = cx.subscribe_in(&slider, window, |this, _, ev: &SliderEvent, _, cx| {
            let value = match ev {
                SliderEvent::Change(v) | SliderEvent::Release(v) => *v,
            };
            if let SliderValue::Single(v) = value {
                this.set_ram(v, cx);
            }
        });
        QuickStartWizard {
            engine,
            state,
            slider,
            _slider_events: sub,
            finished: false,
        }
    }

    fn save(&self, setting: Setting) {
        if let Err(e) = self.engine.set(setting) {
            tracing::warn!("{}", tf!("log.quickStart.saveFailed", e.message()));
        }
    }

    fn set_ram(&mut self, value: f32, cx: &mut Context<Self>) {
        if let Some(mb) = self.state.move_ram(value) {
            self.save(Setting::MaxRamMb(mb));
            cx.notify();
        }
    }

    fn step_ram(&mut self, delta: i32, window: &mut Window, cx: &mut Context<Self>) {
        let next = (self.state.ram_gb as i32 + delta)
            .clamp(RAM_SLIDER_MIN_GB as i32, RAM_SLIDER_MAX_GB as i32) as f32;
        self.slider
            .update(cx, |s, cx| s.set_value(next, window, cx));
        self.set_ram(next, cx);
    }

    fn pick_theme(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        if !self.state.pick_theme(id) {
            return;
        }
        self.save(Setting::Theme(id.to_string()));
        mmcl_ui::shell::force_theme_refresh(cx);
        mmcl_ui::theme::install_theme_on(window, cx);
        cx.notify();
    }

    fn mark_completed(&mut self) {
        if !self.finished {
            self.finished = true;
            self.save(Setting::QuickStartCompleted(true));
        }
    }

    fn finish(&mut self, window: &mut Window) {
        self.mark_completed();
        window.remove_window();
    }

    fn render_step(&self, window: &mut Window, cx: &mut Context<Self>) -> gpui::AnyElement {
        let base = div()
            .flex()
            .flex_col()
            .gap(u(12.0))
            .pt(u(28.0))
            .px(u(36.0))
            .pb(u(24.0));
        match self.state.step() {
            Step::Welcome => base
                .child(heading(t!("quickStart.welcome.heading").into_owned(), cx))
                .child(subtitle(t!("quickStart.welcome.subtitle").into_owned(), cx))
                .child(spacer(18.0))
                .child(body(t!("quickStart.welcome.hint").into_owned(), cx))
                .into_any_element(),
            Step::Theme => {
                let entity = cx.entity().downgrade();
                let options = THEME_SWATCHES.iter().enumerate().map(|(i, (id, rgb))| {
                    let id = *id;
                    let weak = entity.clone();
                    let swatch = div().size(u(14.0)).rounded(u(4.0)).bg(gpui::rgb(*rgb));
                    div().w(u(170.0)).flex().child(
                        Pill::new(
                            ("qs-theme", i),
                            mmcl_ui::screens::settings::logic::theme_label(id),
                            Tone::Outline,
                        )
                        .grow()
                        .leading(swatch)
                        .selected(self.state.is_theme(id))
                        .on_click(move |window, cx| {
                            let _ = weak.update(cx, |this, cx| this.pick_theme(id, window, cx));
                        }),
                    )
                });
                base.child(heading(t!("quickStart.theme.heading").into_owned(), cx))
                    .child(subtitle(t!("quickStart.theme.subtitle").into_owned(), cx))
                    .child(spacer(14.0))
                    .child(div().flex().flex_wrap().gap(u(10.0)).children(options))
                    .into_any_element()
            }
            Step::Ram => self.render_ram(base, window, cx),
            Step::Preferences => {
                let w1 = cx.entity().downgrade();
                let w2 = cx.entity().downgrade();
                base.child(heading(
                    t!("quickStart.preferences.heading").into_owned(),
                    cx,
                ))
                .child(subtitle(
                    t!("quickStart.preferences.subtitle").into_owned(),
                    cx,
                ))
                .child(spacer(10.0))
                .child(
                    Switch::new("qs-discord")
                        .label(t!("quickStart.preferences.discord").into_owned())
                        .checked(self.state.discord)
                        .on_change(move |v, _, cx| {
                            let _ = w1.update(cx, |this, cx| {
                                this.state.discord = v;
                                this.save(Setting::DiscordRpc(v));
                                cx.notify();
                            });
                        }),
                )
                .child(hint(
                    t!("quickStart.preferences.discordHint").into_owned(),
                    cx,
                ))
                .child(spacer(6.0))
                .child(
                    Switch::new("qs-updates")
                        .label(t!("quickStart.preferences.updates").into_owned())
                        .checked(self.state.updates)
                        .on_change(move |v, _, cx| {
                            let _ = w2.update(cx, |this, cx| {
                                this.state.updates = v;
                                this.save(Setting::LauncherUpdateCheck(v));
                                cx.notify();
                            });
                        }),
                )
                .child(hint(
                    t!("quickStart.preferences.updatesHint").into_owned(),
                    cx,
                ))
                .into_any_element()
            }
            Step::Done => base
                .child(heading(t!("quickStart.done.heading").into_owned(), cx))
                .child(subtitle(t!("quickStart.done.subtitle").into_owned(), cx))
                .child(spacer(18.0))
                .child(body(t!("quickStart.done.recap").into_owned(), cx))
                .into_any_element(),
        }
    }

    fn render_ram(
        &self,
        base: gpui::Div,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let t = cx.tokens();
        let span = (RAM_SLIDER_MAX_GB - RAM_SLIDER_MIN_GB) as f32;
        let ticks = ram_tick_labels().into_iter().map(|gb| {
            let pos = (gb - RAM_SLIDER_MIN_GB) as f32 / span;
            div()
                .absolute()
                .left(relative(pos))
                .text_size(u(11.0))
                .text_color(hsla(t.color_text_subtle))
                .child(gb.to_string())
        });
        let minus = cx.entity().downgrade();
        let plus = cx.entity().downgrade();
        base.child(heading(t!("quickStart.ram.heading").into_owned(), cx))
            .child(subtitle(t!("quickStart.ram.subtitle").into_owned(), cx))
            .child(spacer(8.0))
            .child(body(
                tf!(
                    "quickStart.ram.recommendation",
                    self.state.system_gb as i64,
                    self.state.recommended_gb as i64,
                    RAM_RECOMMENDATION_MAX_GB as i64
                ),
                cx,
            ))
            .child(spacer(8.0))
            .child(
                div()
                    .w(u(460.0))
                    .flex()
                    .flex_col()
                    .gap(u(4.0))
                    .child(
                        Slider::new(&self.slider)
                            .horizontal()
                            .accessibility_label(t!("quickStart.ram.heading").into_owned()),
                    )
                    .child(div().relative().h(u(14.0)).mr(u(12.0)).children(ticks)),
            )
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(u(8.0))
                    .child(
                        Pill::new("qs-ram-minus", "\u{2212}", Tone::Tonal)
                            .tooltip(t!("quickStart.ram.less").into_owned())
                            .disabled(self.state.ram_gb <= RAM_SLIDER_MIN_GB)
                            .on_click(move |window, cx| {
                                let _ = minus.update(cx, |this, cx| this.step_ram(-1, window, cx));
                            }),
                    )
                    .child(
                        div()
                            .min_w(u(140.0))
                            .text_size(u(16.0))
                            .font_weight(FontWeight::SEMIBOLD)
                            .text_color(hsla(t.md_primary))
                            .child(tf!("quickStart.ram.value", self.state.ram_gb as i64)),
                    )
                    .child(
                        Pill::new("qs-ram-plus", "+", Tone::Tonal)
                            .tooltip(t!("quickStart.ram.more").into_owned())
                            .disabled(self.state.ram_gb >= RAM_SLIDER_MAX_GB)
                            .on_click(move |window, cx| {
                                let _ = plus.update(cx, |this, cx| this.step_ram(1, window, cx));
                            }),
                    ),
            )
            .into_any_element()
    }
}

impl Render for QuickStartWizard {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let (n, total) = self.state.progress();
        let back = cx.entity().downgrade();
        let next = cx.entity().downgrade();
        let skip = cx.entity().downgrade();
        let next_label = if self.state.is_last() {
            t!("quickStart.nextBtn.getStarted")
        } else {
            t!("quickStart.nextBtn.next")
        };
        nav_scope("quick-start")
            .size_full()
            .flex()
            .flex_col()
            .bg(mmcl_ui::theme::secondary_fill(cx))
            .text_color(hsla(t.color_text))
            .font_family(mmcl_ui_kit::fonts::UI_FAMILY)
            .child(
                gpui_component::TitleBar::new()
                    .bg(gpui::transparent_black())
                    .border_color(gpui::transparent_black())
                    .child(
                        div()
                            .text_size(px(12.0))
                            .text_color(hsla(t.color_text_muted))
                            .child(t!("window.welcome.title").into_owned()),
                    ),
            )
            .child(
                div()
                    .id("qs-step")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .child(self.render_step(window, cx)),
            )
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(u(8.0))
                    .pt(u(12.0))
                    .px(u(18.0))
                    .pb(u(14.0))
                    .border_t_1()
                    .border_color(hsla(t.md_outline_variant))
                    .child(
                        div()
                            .text_size(u(13.0))
                            .text_color(hsla(t.color_text_muted))
                            .child(tf!("quickStart.progressIndicator", n as i64, total as i64)),
                    )
                    .child(div().flex_1())
                    .child(
                        Pill::new("qs-skip", t!("quickStart.skipBtn").into_owned(), Tone::Text)
                            .on_click(move |window, cx| {
                                let _ = skip.update(cx, |this, _| this.finish(window));
                            }),
                    )
                    .child(
                        Pill::new(
                            "qs-back",
                            t!("quickStart.backBtn").into_owned(),
                            Tone::Tonal,
                        )
                        .disabled(!self.state.can_go_back())
                        .on_click(move |_, cx| {
                            let _ = back.update(cx, |this, cx| {
                                if this.state.back() {
                                    cx.notify();
                                }
                            });
                        }),
                    )
                    .child(
                        Pill::new("qs-next", next_label.into_owned(), Tone::Primary).on_click(
                            move |window, cx| {
                                let _ = next.update(cx, |this, cx| match this.state.next() {
                                    Advance::Moved => cx.notify(),
                                    Advance::Finish => this.finish(window),
                                });
                            },
                        ),
                    ),
            )
    }
}

/// Opens the wizard over `owner` (the main window when `None`), or focuses the open one. It
/// reads the saved settings and the system RAM first (off the UI thread).
pub fn show_quick_start(engine: Engine, owner: Option<AnyWindowHandle>, cx: &mut App) {
    if let Some(open) = cx.try_global::<OpenWizard>().and_then(|o| o.0)
        && open
            .update(cx, |_, window, _| window.activate_window())
            .is_ok()
    {
        return;
    }
    let e = engine.clone();
    let memory = engine.spawn(async move { e.system_memory().await });
    cx.spawn(async move |cx| {
        let total_gb = match memory.await {
            Ok(Ok(m)) => Some(m.total_gb),
            Ok(Err(err)) => {
                tracing::warn!("{}", tf!("log.quickStart.ramDetectFailed", err.message()));
                None
            }
            Err(_) => None,
        };
        cx.update(|cx| open_window(engine, total_gb, owner, cx));
    })
    .detach();
}

/// Opens the wizard only while `quickStartCompleted` is false.
pub fn show_quick_start_if_needed(engine: Engine, owner: Option<AnyWindowHandle>, cx: &mut App) {
    if !engine.settings().quick_start_completed {
        show_quick_start(engine, owner, cx);
    }
}

fn open_window(
    engine: Engine,
    total_gb: Option<f64>,
    owner: Option<AnyWindowHandle>,
    cx: &mut App,
) {
    let s = engine.settings();
    let state = WizardState::new(
        &s.theme,
        s.max_ram_mb,
        total_gb,
        s.discord_rpc,
        s.launcher_update_check,
    );
    let mut view = None;
    let opened = super::open_secondary_window(
        super::WindowSpec {
            title: t!("window.welcome.title").into_owned(),
            size: SIZE,
            min_size: None,
            resizable: false,
        },
        owner,
        cx,
        |window, cx| {
            let v = cx.new(|cx| QuickStartWizard::new(engine, state, window, cx));
            view = Some(v.clone());
            v.into()
        },
    );
    let Some(handle) = opened else {
        return;
    };
    if let Some(view) = view {
        let _ = handle.update(cx, |_, window, cx| {
            // Closing the window counts as Skip: the user is not asked again.
            window.on_window_should_close(cx, move |_, cx| {
                view.update(cx, |this, _| this.mark_completed());
                true
            });
        });
    }
    cx.set_global(OpenWizard(Some(handle)));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recommendation_is_half_capped_and_floored() {
        assert_eq!(recommended_ram_gb(1), 2);
        assert_eq!(recommended_ram_gb(4), 2);
        assert_eq!(recommended_ram_gb(8), 4);
        assert_eq!(recommended_ram_gb(15), 7);
        assert_eq!(recommended_ram_gb(32), 16);
        assert_eq!(recommended_ram_gb(128), 16);
    }

    #[test]
    fn system_ram_floors_and_falls_back() {
        assert_eq!(system_ram_gb(Some(15.6)), 15);
        assert_eq!(system_ram_gb(Some(0.4)), 1);
        assert_eq!(system_ram_gb(Some(0.0)), 1);
        assert_eq!(system_ram_gb(None), FALLBACK_SYSTEM_GB);
        assert_eq!(system_ram_gb(Some(f64::NAN)), 1);
    }

    #[test]
    fn initial_ram_uses_the_recommendation_only_for_the_default() {
        assert_eq!(initial_ram_gb(2048, 8), 8);
        assert_eq!(initial_ram_gb(6144, 8), 6);
        assert_eq!(initial_ram_gb(1024, 8), RAM_SLIDER_MIN_GB);
        assert_eq!(initial_ram_gb(200 * 1024, 8), RAM_SLIDER_MAX_GB);
    }

    #[test]
    fn navigation_walks_five_steps_and_finishes() {
        let mut s = WizardState::new("Dark", 2048, Some(16.0), true, true);
        assert_eq!(s.step(), Step::Welcome);
        assert!(!s.can_go_back());
        assert!(!s.back());
        assert_eq!(s.progress(), (1, 5));
        for expected in [Step::Theme, Step::Ram, Step::Preferences, Step::Done] {
            assert_eq!(s.next(), Advance::Moved);
            assert_eq!(s.step(), expected);
        }
        assert!(s.is_last());
        assert_eq!(s.progress(), (5, 5));
        assert_eq!(s.next(), Advance::Finish);
        assert_eq!(s.step(), Step::Done);
        assert!(s.back());
        assert_eq!(s.step(), Step::Preferences);
    }

    #[test]
    fn back_keeps_choices() {
        let mut s = WizardState::new("Dark", 2048, Some(16.0), false, true);
        s.next();
        assert!(s.pick_theme("Creeper"));
        s.next();
        s.move_ram(10.0);
        s.back();
        s.back();
        s.next();
        assert!(s.is_theme("creeper"));
        s.next();
        assert_eq!(s.ram_gb, 10);
    }

    #[test]
    fn the_selected_theme_cannot_be_deselected() {
        let mut s = WizardState::new("Native (Mica)", 2048, None, true, true);
        assert!(!s.pick_theme("native (mica)"));
        assert!(s.is_theme("Native (Mica)"));
        assert!(s.pick_theme("Light"));
        assert!(!s.is_theme("Native (Mica)"));
    }

    /// Guards the Java bug: opening the wizard never writes RAM; moving the slider does.
    #[test]
    fn ram_is_saved_only_when_moved() {
        let mut s = WizardState::new("Dark", 2048, Some(16.0), true, true);
        assert_eq!(s.ram_gb, 8);
        assert!(!s.ram_touched);
        assert_eq!(s.move_ram(8.2), Some(8 * 1024));
        assert!(s.ram_touched);
        assert_eq!(
            s.move_ram(7.9),
            None,
            "same snapped value is not saved twice"
        );
        assert_eq!(s.move_ram(63.6), Some(64 * 1024));
        assert_eq!(s.move_ram(0.0), Some(2 * 1024));
    }

    #[test]
    fn ticks_run_every_four_gb_from_the_minimum() {
        let ticks = ram_tick_labels();
        assert_eq!(ticks.first(), Some(&2));
        assert_eq!(ticks[1], 6);
        assert_eq!(ticks.last(), Some(&62));
        assert_eq!(ticks.len(), 16);
    }

    #[test]
    fn every_theme_has_a_label() {
        for (id, _) in THEME_SWATCHES {
            assert!(
                mmcl_ui::screens::settings::logic::theme_label_key(id).is_some(),
                "{id}"
            );
        }
    }
}

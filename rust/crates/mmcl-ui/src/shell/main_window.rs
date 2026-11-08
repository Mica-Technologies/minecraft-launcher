// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The main window's root view (`MCLauncherGuiWindow`): one screen at a time under the custom
//! title bar, with the screen lifecycle, minimum size, bounds persistence, theme and OS
//! appearance hookup, and the optional bottom dock.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::time::Duration;

use gpui::{
    AnyElement, App, AppContext as _, Context, ElementId, Entity, FocusHandle,
    InteractiveElement as _, IntoElement, ParentElement, Pixels, Render, Size, Styled,
    Subscription, Task, Window, WindowBounds, div, prelude::FluentBuilder, px, size,
};
use mmcl_i18n::tf;
use mmcl_ui_kit::theme::{ThemeExt as _, hsla};
use mmcl_ui_kit::{
    HelpTopic, MotionExt as _, Navigator, ScreenHandle, ScreenId, ScreenRegistry, ScreenRequest,
};

use super::controller;
use super::hooks;
use super::placeholder::Placeholder;
use super::title_bar::AppTitleBar;
use crate::actions::NAVIGABLE_CONTEXT;
use crate::placement::{self, BoundsState, BoundsTracker, Rect};
use crate::services;

/// How long window moves/resizes settle before the bounds are saved.
pub const BOUNDS_DEBOUNCE: Duration = Duration::from_millis(500);
/// How long a move settles before the display it ended on is compared (`monitorChangeDebouncer`).
pub const MONITOR_DEBOUNCE: Duration = Duration::from_millis(200);
/// The delay of the second frame refresh after a monitor change (the Windows 11 shell sometimes
/// drops the first one while it debounces the drag).
pub const MONITOR_RETRY: Duration = Duration::from_millis(200);

/// Whether the window moved to another display (`nudgeIfMonitorChanged`): both displays known
/// and different. The first sighting only seeds the comparison.
pub fn monitor_changed(previous: Option<Rect>, current: Option<Rect>) -> bool {
    matches!((previous, current), (Some(p), Some(c)) if p != c)
}

/// The main window's root view.
pub struct MainWindow {
    current: Option<ScreenHandle>,
    placeholder: Option<Entity<Placeholder>>,
    swap_count: u64,
    focus: FocusHandle,
    tracker: BoundsTracker,
    persist: Option<Task<()>>,
    /// The display the window's centre was on at the last settled move.
    display: Option<Rect>,
    monitor_check: Option<Task<()>>,
    _subscriptions: Vec<Subscription>,
}

pub(crate) fn rect_of(b: gpui::Bounds<Pixels>) -> Rect {
    Rect::new(
        f64::from(f32::from(b.origin.x)),
        f64::from(f32::from(b.origin.y)),
        f64::from(f32::from(b.size.width)),
        f64::from(f32::from(b.size.height)),
    )
}

pub(crate) fn screens(cx: &App) -> (Vec<Rect>, Option<Rect>) {
    let all = cx
        .displays()
        .iter()
        .map(|d| rect_of(d.visible_bounds()))
        .collect();
    let primary = cx.primary_display().map(|d| rect_of(d.visible_bounds()));
    (all, primary)
}

impl MainWindow {
    /// Builds the root view with the cold-start placeholder.
    pub fn new(window: &mut Window, cx: &mut Context<Self>, tracker: BoundsTracker) -> Self {
        let placeholder = cx.new(|cx| Placeholder::new(window, cx));
        let subscriptions = vec![
            cx.observe_window_bounds(window, |this, window, cx| this.bounds_changed(window, cx)),
            cx.observe_window_appearance(window, |_, window, cx| {
                crate::theme::os_appearance_changed(window, cx);
            }),
            cx.observe_window_activation(window, |_, window, cx| {
                controller::set_focused(cx, window.is_window_active());
            }),
            cx.observe_global_in::<mmcl_ui_kit::ActiveTheme>(window, |_, _, cx| cx.notify()),
        ];
        MainWindow {
            current: None,
            placeholder: Some(placeholder),
            swap_count: 0,
            focus: cx.focus_handle(),
            tracker,
            persist: None,
            display: None,
            monitor_check: None,
            _subscriptions: subscriptions,
        }
    }

    /// The screen showing now.
    pub fn current(&self) -> Option<&ScreenHandle> {
        self.current.as_ref()
    }

    /// Whether the placeholder is still showing (no real screen yet).
    pub fn is_placeholder(&self) -> bool {
        self.current.is_none()
    }

    /// How many swaps ran (keys the fade-through).
    pub fn swap_count(&self) -> u64 {
        self.swap_count
    }

    /// Builds the screen for `req` from the registry and shows it. Unregistered ids are logged
    /// and leave the current screen in place. Returns whether a screen was shown.
    pub fn navigate(
        &mut self,
        req: ScreenRequest,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> bool {
        let id = req.id;
        match ScreenRegistry::build(req, window, cx) {
            Some(screen) => {
                self.set_screen(screen, window, cx);
                true
            }
            None => {
                tracing::warn!("{}", tf!("log.ui.screenNotRegistered", id.as_str()));
                false
            }
        }
    }

    /// `setScene`: swaps to `screen`, running the lifecycle. Serialized by construction: it runs
    /// on the foreground and each call cleans up exactly the screen it replaces.
    pub fn set_screen(
        &mut self,
        screen: ScreenHandle,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        // 1. Clean up the previous screen; a panic there is logged and never blocks the swap.
        if let Some(previous) = self.current.take() {
            let same = previous.view().entity_id() == screen.view().entity_id();
            if !same {
                cleanup_logged(&previous, cx);
            }
        }
        // 2. The placeholder (and its pending decoration) goes on the first swap.
        self.placeholder = None;
        // 3. Setup before the screen is attached.
        screen.setup(window, cx);
        let id = screen.id(cx);
        // 4. Size: grow to the screen's minimum / preferred size if the window is smaller.
        self.current = Some(screen.clone());
        self.apply_min_size(window, cx);
        // 5. Title, theme (no native chrome unless the sheet changed).
        let app_name = mmcl_core::AppIdentity::current().display_name;
        window.set_window_title(&screen.window_title(app_name, cx));
        crate::theme::apply_main(window, cx, false);
        // 6. Fade through on every swap.
        self.swap_count += 1;
        let allows = screen.allows_toolbar_navigation(cx);
        controller::set_current(cx, Some(id), allows);
        Navigator::set_current(cx, Some(id));
        services::backend(cx).set_menu_presence(id.as_str());
        // Keep keyboard focus inside the window so the screen's key contexts resolve.
        if window.focused(cx).is_none() {
            self.focus.focus(window, cx);
        }
        cx.notify();
        // 7. After show: on the next effect cycle, once the screen is attached.
        cx.defer_in(window, move |_, window, cx| {
            screen.after_show(window, cx);
            hooks::run_screen_shown(id, window, cx);
        });
    }

    /// Cleans up the current screen (exit / restart; leaking it pinned the old window).
    pub fn cleanup(&mut self, cx: &mut Context<Self>) {
        if let Some(screen) = self.current.take() {
            cleanup_logged(&screen, cx);
        }
        self.persist = None;
        self.monitor_check = None;
        self.flush_bounds(cx);
    }

    /// The minimum size for the current screen at the current scale, capped to the display.
    pub fn min_size_for(&self, window: &Window, cx: &App) -> Size<Pixels> {
        let base = self
            .current
            .as_ref()
            .map(|s| s.min_size(cx))
            .unwrap_or(size(
                px(placement::MIN_SIZE.0 as f32),
                px(placement::MIN_SIZE.1 as f32),
            ));
        let base_w = f64::from(f32::from(base.width)).max(placement::MIN_SIZE.0);
        let base_h = f64::from(f32::from(base.height)).max(placement::MIN_SIZE.1);
        let scale = mmcl_ui_kit::scale::percent(cx) as f64 / 100.0;
        let (all, primary) = screens(cx);
        let display = placement::screen_for(rect_of(window.bounds()), &all, primary);
        let (aw, ah) = display.map_or((0.0, 0.0), |d| (d.w, d.h));
        size(
            px(placement::clamp_min(base_w, scale, aw) as f32),
            px(placement::clamp_min(base_h, scale, ah) as f32),
        )
    }

    /// Re-applies the minimum (and preferred) size: the OS minimum follows the current screen
    /// and interface scale (GPUI alone fixes it at window creation), and a window smaller than
    /// the minimum or the preferred size grows.
    pub fn apply_min_size(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let min = self.min_size_for(window, cx);
        // Where the platform cannot change it (Wayland), growing below still holds the line.
        let _ = mmcl_ui_kit::windowing::set_min_size(window, min);
        if window.is_maximized() || window.is_fullscreen() {
            return;
        }
        let mut wanted = min;
        if let Some(pref) = self.current.as_ref().and_then(|s| s.pref_size(cx)) {
            let scale = mmcl_ui_kit::scale::percent(cx) as f64 / 100.0;
            let (all, primary) = screens(cx);
            let display = placement::screen_for(rect_of(window.bounds()), &all, primary);
            let (aw, ah) = display.map_or((0.0, 0.0), |d| (d.w, d.h));
            wanted.width = wanted.width.max(px(placement::clamp_min(
                f64::from(f32::from(pref.width)),
                scale,
                aw,
            ) as f32));
            wanted.height = wanted.height.max(px(placement::clamp_min(
                f64::from(f32::from(pref.height)),
                scale,
                ah,
            ) as f32));
        }
        let now = window.viewport_size();
        if now.width < wanted.width || now.height < wanted.height {
            window.resize(size(
                now.width.max(wanted.width),
                now.height.max(wanted.height),
            ));
        }
    }

    fn bounds_changed(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let state = match window.window_bounds() {
            WindowBounds::Windowed(b) => BoundsState::Windowed(rect_of(b)),
            WindowBounds::Maximized(b) => BoundsState::Maximized(rect_of(b)),
            WindowBounds::Fullscreen(b) => BoundsState::Fullscreen(rect_of(b)),
        };
        if self.tracker.observe(state) {
            let timer = cx.background_executor().timer(BOUNDS_DEBOUNCE);
            self.persist = Some(cx.spawn(async move |this, cx| {
                timer.await;
                let _ = this.update(cx, |this, cx| this.flush_bounds(cx));
            }));
        }
        if self.display.is_none() {
            self.display = self.display_now(window, cx);
        }
        let timer = cx.background_executor().timer(MONITOR_DEBOUNCE);
        self.monitor_check = Some(cx.spawn_in(window, async move |this, cx| {
            timer.await;
            let _ = this.update_in(cx, |this, window, cx| this.check_monitor(window, cx));
        }));
    }

    fn display_now(&self, window: &Window, cx: &App) -> Option<Rect> {
        let (all, primary) = screens(cx);
        placement::screen_for(rect_of(window.bounds()), &all, primary)
    }

    /// The monitor-change nudge (`nudgeIfMonitorChanged`, Windows): when a settled move lands
    /// the window on another display, re-cap the minimum size for that display and re-assert
    /// the frame twice (`SWP_FRAMECHANGED`) so the per-monitor taskbar moves the button too.
    /// GPUI's own window procedure re-scales on `WM_DPICHANGED` but never prods the shell.
    fn check_monitor(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let current = self.display_now(window, cx);
        let previous = std::mem::replace(&mut self.display, current);
        if !monitor_changed(previous, current) {
            return;
        }
        self.apply_min_size(window, cx);
        if cfg!(windows) {
            mmcl_platform::chrome::force_frame_refresh(&*window);
            let retry = cx.background_executor().timer(MONITOR_RETRY);
            self.monitor_check = Some(cx.spawn_in(window, async move |_, cx| {
                retry.await;
                let _ = cx.update(|window, _| {
                    mmcl_platform::chrome::force_frame_refresh(&*window);
                });
            }));
        }
    }

    /// Writes the last normal bounds + maximized flag now (skipped before any were seen).
    pub fn flush_bounds(&mut self, cx: &mut App) {
        if let Some(saved) = self.tracker.to_saved() {
            services::backend(cx).set_window_bounds(saved);
        }
    }

    fn help_button_topic(&self, cx: &App) -> Option<HelpTopic> {
        match &self.current {
            Some(screen) if !screen.has_own_help_button(cx) => Some(screen.help_topic(cx)),
            Some(_) => None,
            None => None,
        }
    }

    fn content(&self) -> AnyElement {
        match (&self.current, &self.placeholder) {
            (Some(screen), _) => {
                div()
                    .size_full()
                    .child(screen.view())
                    .fade_through(ElementId::NamedInteger(
                        "mmcl-screen".into(),
                        self.swap_count,
                    ))
            }
            (None, Some(p)) => div().size_full().child(p.clone()).into_any_element(),
            (None, None) => div().size_full().into_any_element(),
        }
    }
}

fn cleanup_logged(screen: &ScreenHandle, cx: &mut App) {
    if let Err(panic) = catch_unwind(AssertUnwindSafe(|| screen.cleanup(cx))) {
        let what = panic
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| panic.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "panic".to_string());
        tracing::warn!("{}", tf!("log.guiWindow.cleanupFailed", what));
    }
}

impl Render for MainWindow {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.launcher_theme();
        let t = theme.tokens();
        let fill = theme.window_fill(false);
        let (title, navigable, has_nav_bar) = match &self.current {
            Some(screen) => (
                screen.window_title(mmcl_core::AppIdentity::current().display_name, cx),
                screen.installs_global_shortcuts(cx),
                screen.allows_toolbar_navigation(cx),
            ),
            None => (
                mmcl_core::AppIdentity::current().display_name.into(),
                false,
                false,
            ),
        };
        let help = self.help_button_topic(cx);
        let dock = hooks::bottom_dock(cx);
        let _ = window;
        div()
            .id("mmcl-main-window")
            .track_focus(&self.focus)
            .when(navigable, |el| el.key_context(NAVIGABLE_CONTEXT))
            .size_full()
            .flex()
            .flex_col()
            .when_some(fill, |el, fill| el.bg(fill))
            .text_color(hsla(t.color_text))
            .font_family(mmcl_ui_kit::fonts::UI_FAMILY)
            // Screens with a navbar show the brand and screen name there; the strip above
            // then carries only the drag area and window controls, so nothing shows twice.
            .child(AppTitleBar::new(title, help).brand(!has_nav_bar))
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .relative()
                    .overflow_hidden()
                    .child(self.content()),
            )
            .when_some(dock, |el, dock| el.child(div().flex_none().child(dock)))
    }
}

/// The screen id a [`MainWindow`] shows, read through its entity.
pub fn current_id(main: &Entity<MainWindow>, cx: &App) -> Option<ScreenId> {
    main.read(cx).current.as_ref().map(|s| s.id(cx))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_real_display_change_nudges() {
        let a = Rect::new(0.0, 0.0, 1920.0, 1080.0);
        let b = Rect::new(1920.0, 0.0, 2560.0, 1440.0);
        assert!(!monitor_changed(None, Some(a)), "the first sighting seeds");
        assert!(!monitor_changed(Some(a), Some(a)));
        assert!(!monitor_changed(Some(a), None));
        assert!(monitor_changed(Some(a), Some(b)));
    }
}

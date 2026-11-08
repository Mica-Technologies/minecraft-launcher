// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `MaterialSwitchSkin` as a GPUI control: a Material 3 switch at 3/4 of mobile size.
//!
//! Off: outlined track, handle (`-md-outline`) with an X. On: filled primary track, handle
//! (`-md-on-primary`) with a check. The handle is the same size in both states, slides over
//! 150 ms with emphasized-decelerate easing when the state flips, the icons cross-fade, and the
//! handle swells while pressed. The label sits on the left, the switch at the right edge. Click,
//! Space, Enter and gamepad A toggle it.

use std::rc::Rc;
use std::time::Duration;

use gpui::{
    Animation, AnimationExt as _, AnyElement, App, ElementId, InteractiveElement, IntoElement,
    ParentElement, RenderOnce, SharedString, Styled, Window, div, prelude::FluentBuilder as _,
};
use mmcl_theme::{Rgba, TokenSheet};

use crate::scale::u;
use crate::controls::DISABLED_OPACITY;
use crate::controls::ToggleHandler;
use crate::focus::{Activatable, child_id, focus_handle_for, nav_marker};
use crate::icons::Icon;
use crate::motion;
use crate::theme::{ThemeExt, hsla};

/// Track width (px). The Java skin uses 40 (3/4 of Material's 52, rounded to even pixels).
pub const TRACK_WIDTH: f32 = 40.0;
/// Track height (px): 3/4 of 32.
pub const TRACK_HEIGHT: f32 = 24.0;
/// Gap between label and switch (px).
pub const GAP: f32 = 16.0;
/// Handle diameter (px).
pub const HANDLE: f32 = 18.0;
/// Handle diameter while pressed (px).
pub const HANDLE_PRESSED: f32 = 21.0;
/// Hover / focus / press halo diameter (px).
pub const HALO: f32 = 30.0;
/// Icon size inside the handle at full scale (px).
pub const ICON: f32 = 12.0;
/// Track border width (px); absolutely placed children sit inside it.
pub const BORDER: f32 = 1.5;
/// Slide duration.
pub const SLIDE: Duration = Duration::from_millis(150);

/// The handle centre's x offset from the track's left edge at position `p` (0 off .. 1 on).
pub fn handle_center_x(p: f32) -> f32 {
    TRACK_HEIGHT / 2.0 + (TRACK_WIDTH - TRACK_HEIGHT) * p.clamp(0.0, 1.0)
}

/// `(check, cross)` icon scale at position `p`: the X shrinks out as the check grows in.
pub fn icon_scales(p: f32) -> (f32, f32) {
    let p = p.clamp(0.0, 1.0);
    (p.max(0.01), (1.0 - p).max(0.01))
}

/// The off-X colour: native sheets paint it with their opaque override (the translucent role
/// vanished), every other sheet with `-md-surface-container-highest`.
pub fn off_icon_color(sheet: TokenSheet) -> Rgba {
    sheet
        .native_overrides()
        .map(|o| o.switch_icon_off)
        .unwrap_or(sheet.tokens().md_surface_container_highest)
}

/// Per-switch state kept across frames.
#[derive(Debug, Default)]
struct SwitchState {
    pressed: bool,
    last_checked: Option<bool>,
    flips: usize,
}

/// The switch element.
#[derive(IntoElement)]
pub struct Switch {
    id: ElementId,
    checked: bool,
    label: Option<SharedString>,
    disabled: bool,
    on_change: Option<ToggleHandler>,
}

impl Switch {
    /// A switch.
    pub fn new(id: impl Into<ElementId>) -> Self {
        Switch {
            id: id.into(),
            checked: false,
            label: None,
            disabled: false,
            on_change: None,
        }
    }

    /// Sets the state.
    pub fn checked(mut self, checked: bool) -> Self {
        self.checked = checked;
        self
    }

    /// A label on the left.
    pub fn label(mut self, label: impl Into<SharedString>) -> Self {
        self.label = Some(label.into());
        self
    }

    /// Disables it.
    pub fn disabled(mut self, disabled: bool) -> Self {
        self.disabled = disabled;
        self
    }

    /// Called with the new state when toggled.
    pub fn on_change(mut self, f: impl Fn(bool, &mut Window, &mut App) + 'static) -> Self {
        self.on_change = Some(Rc::new(f));
        self
    }
}

/// The handle at position `p`.
#[derive(IntoElement)]
struct HandleAt {
    p: f32,
    pressed: bool,
}

impl RenderOnce for HandleAt {
    fn render(self, _window: &mut Window, cx: &mut App) -> impl IntoElement {
        let theme = cx.launcher_theme();
        let t = theme.tokens();
        let p = self.p;
        let on = p >= 0.5;
        let size = if self.pressed { HANDLE_PRESSED } else { HANDLE };
        let (check, cross) = icon_scales(p);
        div()
            .absolute()
            .left(u(handle_center_x(p) - BORDER - size / 2.0))
            .top(u((TRACK_HEIGHT - 2.0 * BORDER - size) / 2.0))
            .size(u(size))
            .rounded_full()
            .bg(hsla(if on { t.md_on_primary } else { t.md_outline }))
            .flex()
            .items_center()
            .justify_center()
            .child(
                div().absolute().opacity(1.0 - p).child(
                    Icon::Close
                        .svg(ICON * cross)
                        .text_color(hsla(off_icon_color(theme.sheet()))),
                ),
            )
            .child(
                div()
                    .absolute()
                    .opacity(p)
                    .child(Icon::Check.svg(ICON * check).text_color(hsla(t.md_primary))),
            )
    }
}

impl RenderOnce for Switch {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.tokens();
        let focus = focus_handle_for(&self.id, window, cx);
        let checked = self.checked;
        let disabled = self.disabled;
        let on_change = self.on_change.clone();
        let target = if checked { 1.0 } else { 0.0 };
        let state = window.use_keyed_state(child_id(&self.id, "switch"), cx, |_, _| {
            SwitchState::default()
        });
        // Animate only on a real flip, never on first show.
        let (pressed, flips) = state.update(cx, |s, _| {
            if s.last_checked.is_some_and(|last| last != checked) {
                s.flips += 1;
            }
            s.last_checked = Some(checked);
            (s.pressed, s.flips)
        });
        let focused = focus.is_focused(window);

        let track = div()
            .relative()
            .flex_none()
            .w(u(TRACK_WIDTH))
            .h(u(TRACK_HEIGHT))
            .rounded_full()
            .border(u(BORDER))
            .bg(hsla(if checked {
                t.md_primary
            } else {
                t.md_surface_container_highest
            }))
            .border_color(hsla(if checked { t.md_primary } else { t.md_outline }));

        // Halo (hover / focus / press state layer) around the handle.
        let halo_color = match (checked, pressed) {
            (true, true) => t.md_state_pressed_primary,
            (true, false) => t.md_state_hover_primary,
            (false, true) => t.md_state_pressed_on_surface,
            (false, false) => t.md_state_hover_on_surface,
        };
        let halo = div()
            .absolute()
            .left(u(handle_center_x(target) - BORDER - HALO / 2.0))
            .top(u((TRACK_HEIGHT - 2.0 * BORDER - HALO) / 2.0))
            .size(u(HALO))
            .rounded_full()
            .bg(hsla(halo_color))
            .when(!(focused || pressed) || disabled, |el| el.invisible());

        let moving: AnyElement = if flips == 0 || motion::is_reduced() {
            HandleAt { p: target, pressed }.into_any_element()
        } else {
            let start = 1.0 - target;
            let curve = motion::easing(motion::EMPHASIZED_DECELERATE);
            let id = child_id(&self.id, format!("slide-{flips}"));
            div()
                .absolute()
                .top_0()
                .left_0()
                .size_full()
                .with_animation(id, Animation::new(SLIDE), move |el, delta| {
                    let p = start + (target - start) * curve(delta);
                    el.child(HandleAt { p, pressed })
                })
                .into_any_element()
        };

        let pressed_down = state.clone();
        let pressed_up = state;
        div()
            .id(self.id)
            .track_focus(&focus)
            .relative()
            .child(nav_marker(&focus))
            .py(u(4.0))
            .flex()
            .items_center()
            .gap(u(GAP))
            .when_some(self.label, |el, label| {
                el.child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_color(hsla(t.md_on_surface))
                        .child(label),
                )
            })
            .child(track.child(halo).child(moving))
            .when(disabled, |el| el.opacity(DISABLED_OPACITY))
            .when(!disabled, |el| {
                el.cursor_pointer()
                    .on_mouse_down(gpui::MouseButton::Left, move |_, _, cx| {
                        pressed_down.update(cx, |s, cx| {
                            s.pressed = true;
                            cx.notify();
                        });
                    })
                    .on_mouse_up(gpui::MouseButton::Left, move |_, _, cx| {
                        pressed_up.update(cx, |s, cx| {
                            s.pressed = false;
                            cx.notify();
                        });
                    })
                    .on_activate(Rc::new(move |window, cx| {
                        if let Some(f) = &on_change {
                            f(!checked, window, cx);
                        }
                    }))
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handle_runs_end_to_end() {
        assert_eq!(handle_center_x(0.0), 12.0);
        assert_eq!(handle_center_x(1.0), 28.0);
        assert_eq!(handle_center_x(2.0), 28.0);
        // The handle stays inside the track at both ends.
        assert!(handle_center_x(0.0) - HANDLE / 2.0 >= 0.0);
        assert!(handle_center_x(1.0) + HANDLE / 2.0 <= TRACK_WIDTH);
    }

    #[test]
    fn icons_cross_fade() {
        assert_eq!(icon_scales(0.0), (0.01, 1.0));
        assert_eq!(icon_scales(1.0), (1.0, 0.01));
        let (c, x) = icon_scales(0.5);
        assert_eq!(c, x);
    }

    #[test]
    fn native_sheets_use_the_opaque_off_icon() {
        let native = TokenSheet::Native
            .native_overrides()
            .unwrap()
            .switch_icon_off;
        assert_eq!(off_icon_color(TokenSheet::Native), native);
        assert!(native.is_opaque());
        assert_eq!(
            off_icon_color(TokenSheet::Dark),
            TokenSheet::Dark.tokens().md_surface_container_highest
        );
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The labelled pill buttons the Running Games view uses (`MFXButton` with the launcher's
//! `primary` / `tonalBtn` / `textBtn` / `errorTonalBtn` classes), focusable and activatable like
//! every other launcher control.

use std::rc::Rc;

use gpui::{
    App, ElementId, Hsla, InteractiveElement, IntoElement, ParentElement, RenderOnce, SharedString,
    StatefulInteractiveElement, Styled, Window, div, prelude::FluentBuilder as _,
};
use mmcl_theme::Tokens;
use mmcl_ui_kit::controls::DISABLED_OPACITY;
use mmcl_ui_kit::focus::{Activatable as _, ActivateHandler, focus_handle_for, nav_marker};
use mmcl_ui_kit::scale::u;
use mmcl_ui_kit::{Icon, ThemeExt as _, hsla};

/// A button's emphasis.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ButtonStyle {
    /// Filled primary (the main fix in a crash card).
    Primary,
    /// Filled error (the main fix on a critical crash card, where green on red clashes).
    Error,
    /// Tonal surface (Dock in, crash-report toggle, Stop, Cancel).
    Tonal,
    /// Error-tonal (Kill).
    ErrorTonal,
    /// Text only (Open log file).
    Text,
}

impl ButtonStyle {
    fn colors(self, t: &Tokens) -> (Option<Hsla>, Hsla) {
        match self {
            ButtonStyle::Primary => (Some(hsla(t.md_primary)), hsla(t.md_on_primary)),
            ButtonStyle::Error => (Some(hsla(t.md_error)), hsla(t.md_on_error)),
            ButtonStyle::Tonal => (
                Some(hsla(t.md_surface_container_highest)),
                hsla(t.md_on_surface),
            ),
            ButtonStyle::ErrorTonal => (
                Some(hsla(t.md_error_container)),
                hsla(t.md_on_error_container),
            ),
            ButtonStyle::Text => (None, hsla(t.md_text_primary)),
        }
    }
}

/// A labelled pill button with an optional leading icon.
#[derive(IntoElement)]
pub struct ActionButton {
    id: ElementId,
    label: SharedString,
    style: ButtonStyle,
    icon: Option<Icon>,
    disabled: bool,
    on_click: Option<ActivateHandler>,
}

impl ActionButton {
    /// A button.
    pub fn new(
        id: impl Into<ElementId>,
        label: impl Into<SharedString>,
        style: ButtonStyle,
    ) -> Self {
        ActionButton {
            id: id.into(),
            label: label.into(),
            style,
            icon: None,
            disabled: false,
            on_click: None,
        }
    }

    /// A leading icon.
    pub fn icon(mut self, icon: Icon) -> Self {
        self.icon = Some(icon);
        self
    }

    /// Disables it.
    pub fn disabled(mut self, disabled: bool) -> Self {
        self.disabled = disabled;
        self
    }

    /// The activation handler (click, Enter, Space, gamepad A).
    pub fn on_click(mut self, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        self.on_click = Some(Rc::new(f));
        self
    }
}

impl RenderOnce for ActionButton {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.tokens();
        let focus = focus_handle_for(&self.id, window, cx);
        let (bg, fg) = self.style.colors(t);
        let hover = hsla(t.md_state_hover_on_surface);
        let pressed = hsla(t.md_state_pressed_on_surface);
        let ring = hsla(t.md_primary);
        let disabled = self.disabled;
        let handler = self.on_click.clone();
        div()
            .id(self.id)
            .track_focus(&focus)
            .relative()
            .child(nav_marker(&focus))
            .flex_none()
            .flex()
            .items_center()
            .gap(u(6.0))
            .h(u(36.0))
            .px(u(16.0))
            .rounded_full()
            .border_1()
            .border_color(gpui::transparent_black())
            .when_some(bg, |el, bg| el.bg(bg))
            .text_color(fg)
            .text_size(u(14.0))
            .font_weight(gpui::FontWeight::SEMIBOLD)
            .when_some(self.icon, |el, icon| {
                el.child(icon.svg(18.0).text_color(fg))
            })
            .child(self.label)
            .when(disabled, |el| el.opacity(DISABLED_OPACITY))
            .when(!disabled, |el| {
                el.cursor_pointer()
                    .hover(move |s| s.bg(bg.map_or(hover, |b| blend(b, hover))))
                    .active(move |s| s.bg(bg.map_or(pressed, |b| blend(b, pressed))))
                    .focus_visible(move |s| s.border_color(ring))
                    .on_activate(Rc::new(move |window, cx| {
                        if let Some(f) = &handler {
                            f(window, cx);
                        }
                    }))
            })
    }
}

/// `layer` painted over `base` (Material's state layer over a filled container).
pub fn blend(base: Hsla, layer: Hsla) -> Hsla {
    let a = gpui::Rgba::from(base);
    let b = gpui::Rgba::from(layer);
    let alpha = b.a;
    gpui::Rgba {
        r: a.r + (b.r - a.r) * alpha,
        g: a.g + (b.g - a.g) * alpha,
        b: a.b + (b.b - a.b) * alpha,
        a: a.a.max(alpha),
    }
    .into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_transparent_layer_keeps_the_base() {
        let base: Hsla = gpui::Rgba {
            r: 0.2,
            g: 0.4,
            b: 0.6,
            a: 1.0,
        }
        .into();
        let out = gpui::Rgba::from(blend(base, gpui::transparent_black()));
        assert!((out.r - 0.2).abs() < 1e-4 && (out.b - 0.6).abs() < 1e-4);
        let full = gpui::Rgba::from(blend(base, gpui::white()));
        assert!((full.r - 1.0).abs() < 1e-4);
    }
}

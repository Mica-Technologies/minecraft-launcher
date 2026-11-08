// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Small building blocks the dialogs share: a focusable pill button (keyboard, gamepad and
//! pointer), text roles, chips, a collapsible section box and a window frame for the secondary
//! windows (wizard, import confirm, MCP consent).

use std::rc::Rc;

use gpui::{
    AnyElement, App, Div, ElementId, FontWeight, Hsla, InteractiveElement, IntoElement,
    ParentElement, RenderOnce, SharedString, StatefulInteractiveElement, Styled, Window, div,
    prelude::FluentBuilder as _,
};
use mmcl_ui_kit::controls::DISABLED_OPACITY;
use mmcl_ui_kit::focus::{Activatable as _, ActivateHandler, focus_handle_for, nav_marker};
use mmcl_ui_kit::scale::u;
use mmcl_ui_kit::{Icon, ThemeExt as _, TooltipExt as _, hsla};

/// A button's emphasis.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tone {
    /// Filled primary (the main action).
    Primary,
    /// Tonal surface (secondary actions).
    Tonal,
    /// Outlined (tab-like or neutral choices).
    Outline,
    /// Text only (Skip, links).
    Text,
    /// Error tonal (Delete).
    Danger,
}

/// `(container, content)` colours of a tone.
fn tone_colors(tone: Tone, cx: &App) -> (Option<Hsla>, Hsla) {
    let t = cx.tokens();
    match tone {
        Tone::Primary => (Some(hsla(t.md_primary)), hsla(t.md_on_primary)),
        Tone::Tonal => (
            Some(hsla(t.md_surface_container_highest)),
            hsla(t.md_on_surface),
        ),
        Tone::Outline => (None, hsla(t.md_on_surface)),
        Tone::Text => (None, hsla(t.md_primary)),
        Tone::Danger => (
            Some(hsla(t.md_error_container)),
            hsla(t.md_on_error_container),
        ),
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

/// A labelled pill button, focusable and activatable with click, Enter, Space or gamepad A,
/// and reachable by the arrow keys / d-pad inside a `nav_scope`.
#[derive(IntoElement)]
pub struct Pill {
    id: ElementId,
    label: SharedString,
    tone: Tone,
    icon: Option<Icon>,
    leading: Option<AnyElement>,
    disabled: bool,
    selected: bool,
    grow: bool,
    height: f32,
    tooltip: Option<SharedString>,
    on_click: Option<ActivateHandler>,
}

impl Pill {
    /// A button.
    pub fn new(id: impl Into<ElementId>, label: impl Into<SharedString>, tone: Tone) -> Self {
        Pill {
            id: id.into(),
            label: label.into(),
            tone,
            icon: None,
            leading: None,
            disabled: false,
            selected: false,
            grow: false,
            height: 36.0,
            tooltip: None,
            on_click: None,
        }
    }

    /// A leading glyph.
    pub fn icon(mut self, icon: Icon) -> Self {
        self.icon = Some(icon);
        self
    }

    /// A leading element (a colour swatch).
    pub fn leading(mut self, el: impl IntoElement) -> Self {
        self.leading = Some(el.into_any_element());
        self
    }

    /// Disables it.
    pub fn disabled(mut self, disabled: bool) -> Self {
        self.disabled = disabled;
        self
    }

    /// Marks it as the selected option of a group (filled with the active indicator).
    pub fn selected(mut self, selected: bool) -> Self {
        self.selected = selected;
        self
    }

    /// Takes the free width of a row.
    pub fn grow(mut self) -> Self {
        self.grow = true;
        self
    }

    /// The height (px at 100 %).
    pub fn height(mut self, h: f32) -> Self {
        self.height = h;
        self
    }

    /// A tooltip (also shown while disabled, to say why).
    pub fn tooltip(mut self, text: impl Into<SharedString>) -> Self {
        self.tooltip = Some(text.into());
        self
    }

    /// The activation handler.
    pub fn on_click(mut self, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        self.on_click = Some(Rc::new(f));
        self
    }
}

impl RenderOnce for Pill {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.tokens();
        let focus = focus_handle_for(&self.id, window, cx);
        let (mut bg, mut fg) = tone_colors(self.tone, cx);
        if self.selected {
            bg = Some(hsla(t.md_active_indicator));
            fg = hsla(t.md_on_active_indicator);
        }
        let outline = matches!(self.tone, Tone::Outline) && !self.selected;
        let hover = hsla(t.md_state_hover_on_surface);
        let pressed = hsla(t.md_state_pressed_on_surface);
        let ring = hsla(t.md_primary);
        let border = hsla(t.md_outline_variant);
        let disabled = self.disabled;
        let handler = self.on_click.clone();
        div()
            .id(self.id)
            .track_focus(&focus)
            .relative()
            .child(nav_marker(&focus))
            .when(self.grow, |el| el.flex_1())
            .when(!self.grow, |el| el.flex_none())
            .flex()
            .items_center()
            .justify_center()
            .gap(u(6.0))
            .h(u(self.height))
            .px(u(16.0))
            .rounded_full()
            .border_2()
            .border_color(gpui::transparent_black())
            .when(outline, |el| el.border_1().border_color(border))
            .when_some(bg, |el, bg| el.bg(bg))
            .text_color(fg)
            .text_size(u(14.0))
            .font_weight(FontWeight::SEMIBOLD)
            .when_some(self.leading, |el, l| el.child(l))
            .when_some(self.icon, |el, icon| {
                el.child(icon.svg(18.0).text_color(fg))
            })
            .child(self.label)
            .when_some(self.tooltip, |el, tip| el.launcher_tooltip(tip))
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

/// A screen heading (`wizardHeading`, `heading-h2`).
pub fn heading(text: impl Into<SharedString>, cx: &App) -> Div {
    div()
        .text_size(u(22.0))
        .font_weight(FontWeight::BOLD)
        .text_color(hsla(cx.tokens().md_on_surface))
        .child(text.into())
}

/// A section heading inside a card.
pub fn title(text: impl Into<SharedString>, cx: &App) -> Div {
    div()
        .text_size(u(15.0))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(hsla(cx.tokens().md_on_surface))
        .child(text.into())
}

/// A subtitle (`wizardSubtitle`, `subtle`).
pub fn subtitle(text: impl Into<SharedString>, cx: &App) -> Div {
    div()
        .text_size(u(14.0))
        .text_color(hsla(cx.tokens().color_text_muted))
        .child(text.into())
}

/// Body text (`wizardBody`).
pub fn body(text: impl Into<SharedString>, cx: &App) -> Div {
    div()
        .text_size(u(14.0))
        .text_color(hsla(cx.tokens().md_on_surface))
        .child(text.into())
}

/// A small hint (`wizardHint`, `type-label-small subtle`).
pub fn hint(text: impl Into<SharedString>, cx: &App) -> Div {
    div()
        .text_size(u(12.0))
        .text_color(hsla(cx.tokens().color_text_subtle))
        .child(text.into())
}

/// A monospace line (`text-mono`).
pub fn mono(text: impl Into<SharedString>, cx: &App) -> Div {
    div()
        .font_family(mmcl_ui_kit::fonts::MONO_FAMILY)
        .text_size(u(12.0))
        .text_color(hsla(cx.tokens().md_on_surface))
        .child(text.into())
}

/// A fixed vertical gap.
pub fn spacer(h: f32) -> Div {
    div().flex_none().h(u(h))
}

/// A neutral stat chip (`stat-chip`).
pub fn chip(text: impl Into<SharedString>, cx: &App) -> Div {
    let t = cx.tokens();
    div()
        .flex_none()
        .px(u(10.0))
        .py(u(3.0))
        .rounded_full()
        .bg(hsla(t.md_surface_container_high))
        .text_size(u(12.0))
        .font_weight(FontWeight::MEDIUM)
        .text_color(hsla(t.md_on_surface_variant))
        .child(text.into())
}

/// A tinted chip (`stat-chip-warn` / `-success`).
pub fn tinted_chip(text: impl Into<SharedString>, bg: Hsla, fg: Hsla) -> Div {
    div()
        .flex_none()
        .px(u(10.0))
        .py(u(3.0))
        .rounded_full()
        .bg(bg)
        .text_size(u(12.0))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(fg)
        .child(text.into())
}

/// A rounded card surface (`modpackDetailSection`).
pub fn card(cx: &App) -> Div {
    let t = cx.tokens();
    div()
        .flex()
        .flex_col()
        .gap(u(6.0))
        .p(u(12.0))
        .rounded(u(12.0))
        .bg(hsla(t.md_surface_container_low))
        .border_1()
        .border_color(hsla(t.md_outline_variant))
}

/// A collapsible section box (`buildSectionBox`): a clickable heading row with a chevron, and
/// its body when expanded.
pub fn section_box(
    id: impl Into<ElementId>,
    heading_text: impl Into<SharedString>,
    expanded: bool,
    on_toggle: impl Fn(&mut Window, &mut App) + 'static,
    content: impl FnOnce() -> Vec<AnyElement>,
    cx: &App,
) -> Div {
    let t = cx.tokens();
    let id: ElementId = id.into();
    let chevron = if expanded {
        Icon::ArrowUp
    } else {
        Icon::ArrowDown
    };
    let header = Pill::new(id, heading_text, Tone::Text)
        .icon(chevron)
        .height(32.0)
        .on_click(on_toggle);
    div()
        .flex()
        .flex_col()
        .gap(u(8.0))
        .p(u(12.0))
        .rounded(u(16.0))
        .bg(hsla(t.md_surface_container))
        .child(div().flex().child(header))
        .when(expanded, |el| el.children(content()))
}

/// A muted one-line placeholder (empty list, loading).
pub fn muted(text: impl Into<SharedString>, cx: &App) -> Div {
    div()
        .text_size(u(13.0))
        .text_color(hsla(cx.tokens().color_text_muted))
        .child(text.into())
}

/// The loading row (`buildLoadingPlaceholder`): spinner and `detailModal.section.loading`.
pub fn loading_row(id: impl Into<ElementId>, cx: &App) -> Div {
    div()
        .flex()
        .items_center()
        .gap(u(8.0))
        .py(u(4.0))
        .child(mmcl_ui_kit::LoadingIndicator::new(id).size(20.0))
        .child(muted(
            mmcl_i18n::t!("detailModal.section.loading").into_owned(),
            cx,
        ))
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
        let none: Hsla = gpui::Rgba {
            r: 1.0,
            g: 1.0,
            b: 1.0,
            a: 0.0,
        }
        .into();
        let out = gpui::Rgba::from(blend(base, none));
        assert!((out.r - 0.2).abs() < 1e-3 && (out.b - 0.6).abs() < 1e-3);
        assert!((out.a - 1.0).abs() < 1e-3);
    }
}

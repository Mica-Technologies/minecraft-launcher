// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Small shared controls: [`FilterChip`], [`IconButton`], [`SearchField`], [`empty_state_badge`],
//! [`AnnouncementBanner`] and [`OfflineChip`].
//!
//! Every interactive control is focusable, shows a focus ring for keyboard/gamepad focus,
//! activates on click, Enter, Space and gamepad A, and records itself for spatial navigation.

use std::rc::Rc;

use gpui::{
    App, ElementId, Entity, Global, InteractiveElement, IntoElement, ParentElement, RenderOnce,
    SharedString, StatefulInteractiveElement, Styled, Window, div, prelude::FluentBuilder as _,
};
use gpui_component::input::{Input, InputState};
use mmcl_i18n::t;

use crate::focus::{Activatable, ActivateHandler, child_id, focus_handle_for, nav_marker};
use crate::icons::Icon;
use crate::scale::u;
use crate::theme::{ThemeExt, hsla};
use crate::tooltip::TooltipExt;

/// A handler receiving the new state of a toggle.
pub type ToggleHandler = Rc<dyn Fn(bool, &mut Window, &mut App)>;

/// Opacity of a disabled control.
pub const DISABLED_OPACITY: f32 = 0.38;

// ---------------------------------------------------------------------------------------------
// FilterChip

/// A toggle chip: outlined when off; filled with the active-indicator tone and a leading 16 px
/// check when on (8 px corners, 12/600 text).
#[derive(IntoElement)]
pub struct FilterChip {
    id: ElementId,
    label: SharedString,
    selected: bool,
    tooltip: Option<SharedString>,
    on_change: Option<ToggleHandler>,
}

impl FilterChip {
    /// A chip with a label.
    pub fn new(id: impl Into<ElementId>, label: impl Into<SharedString>) -> Self {
        FilterChip {
            id: id.into(),
            label: label.into(),
            selected: false,
            tooltip: None,
            on_change: None,
        }
    }

    /// Sets whether it is on.
    pub fn selected(mut self, selected: bool) -> Self {
        self.selected = selected;
        self
    }

    /// A tooltip.
    pub fn tooltip(mut self, text: impl Into<SharedString>) -> Self {
        self.tooltip = Some(text.into());
        self
    }

    /// Called with the new state when toggled.
    pub fn on_change(mut self, f: impl Fn(bool, &mut Window, &mut App) + 'static) -> Self {
        self.on_change = Some(Rc::new(f));
        self
    }
}

impl RenderOnce for FilterChip {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.tokens();
        let focus = focus_handle_for(&self.id, window, cx);
        let selected = self.selected;
        let on_change = self.on_change.clone();
        let hover = hsla(t.md_state_hover_on_surface);
        let ring = hsla(t.md_primary);
        div()
            .id(self.id)
            .track_focus(&focus)
            .relative()
            .child(nav_marker(&focus))
            .flex()
            .items_center()
            .gap(u(2.0))
            .py(u(5.0))
            .pl(u(if selected { 8.0 } else { 12.0 }))
            .pr(u(12.0))
            .rounded(u(mmcl_theme::shape::SMALL))
            .border_1()
            .cursor_pointer()
            .text_size(u(12.0))
            .font_weight(gpui::FontWeight::SEMIBOLD)
            .when(selected, |el| {
                el.bg(hsla(t.md_active_indicator))
                    .border_color(gpui::transparent_black())
                    .text_color(hsla(t.md_on_active_indicator))
                    .child(
                        Icon::Check
                            .svg(16.0)
                            .text_color(hsla(t.md_on_active_indicator)),
                    )
            })
            .when(!selected, |el| {
                el.border_color(hsla(t.md_outline))
                    .text_color(hsla(t.md_on_surface_variant))
            })
            .hover(move |s| s.bg(hover))
            .focus_visible(move |s| s.border_color(ring))
            .child(self.label)
            .when_some(self.tooltip, |el, tip| el.launcher_tooltip(tip))
            .on_activate(Rc::new(move |window, cx| {
                if let Some(f) = &on_change {
                    f(!selected, window, cx);
                }
            }))
    }
}

// ---------------------------------------------------------------------------------------------
// IconButton

/// A 40 px round icon button (`IconButtons.decorate`): no container, a hover/press state layer,
/// one glyph; the label becomes the tooltip and the accessible name.
#[derive(IntoElement)]
pub struct IconButton {
    id: ElementId,
    icon: Icon,
    label: SharedString,
    size: f32,
    disabled: bool,
    on_click: Option<ActivateHandler>,
}

impl IconButton {
    /// Standard button size (px).
    pub const SIZE: f32 = 40.0;
    /// Size inside 48 px tool rows (navbar, pagination).
    pub const COMPACT_SIZE: f32 = 36.0;

    /// An icon button; `label` is the tooltip and accessible name.
    pub fn new(id: impl Into<ElementId>, icon: Icon, label: impl Into<SharedString>) -> Self {
        IconButton {
            id: id.into(),
            icon,
            label: label.into(),
            size: Self::SIZE,
            disabled: false,
            on_click: None,
        }
    }

    /// The compact 36 px variant.
    pub fn compact(mut self) -> Self {
        self.size = Self::COMPACT_SIZE;
        self
    }

    /// Disables it (38 % opacity, no activation).
    pub fn disabled(mut self, disabled: bool) -> Self {
        self.disabled = disabled;
        self
    }

    /// The activation handler.
    pub fn on_click(mut self, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        self.on_click = Some(Rc::new(f));
        self
    }

    /// The accessible label.
    pub fn label(&self) -> &SharedString {
        &self.label
    }
}

impl RenderOnce for IconButton {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.tokens();
        let focus = focus_handle_for(&self.id, window, cx);
        let glyph = hsla(t.md_on_surface_variant);
        let hover = hsla(t.md_state_hover_on_surface);
        let pressed = hsla(t.md_state_pressed_on_surface);
        let ring = hsla(t.md_primary);
        let padding = if self.size < Self::SIZE { 6.0 } else { 8.0 };
        let disabled = self.disabled;
        let handler = self.on_click.clone();
        div()
            .id(self.id)
            .track_focus(&focus)
            .relative()
            .child(nav_marker(&focus))
            .flex_none()
            .size(u(self.size))
            .p(u(padding))
            .rounded_full()
            .border_1()
            .border_color(gpui::transparent_black())
            .flex()
            .items_center()
            .justify_center()
            .child(self.icon.svg(self.size - 2.0 * padding).text_color(glyph))
            .launcher_tooltip(self.label)
            .when(disabled, |el| el.opacity(DISABLED_OPACITY))
            .when(!disabled, |el| {
                el.cursor_pointer()
                    .hover(move |s| s.bg(hover))
                    .active(move |s| s.bg(pressed))
                    .focus_visible(move |s| s.border_color(ring))
                    .on_activate(Rc::new(move |window, cx| {
                        if let Some(f) = &handler {
                            f(window, cx);
                        }
                    }))
            })
    }
}

// ---------------------------------------------------------------------------------------------
// SearchField

/// A full-pill search field with a leading 18 px search glyph, one surface level up from the bar
/// it sits in (`SearchFields.decorate`). Wraps a gpui-component `Input`.
#[derive(IntoElement)]
pub struct SearchField {
    state: Entity<InputState>,
    tooltip: Option<SharedString>,
}

impl SearchField {
    /// A search field over an input state (create the state with
    /// `InputState::new(window, cx).placeholder(t!("filter.search.prompt"))`).
    pub fn new(state: &Entity<InputState>) -> Self {
        SearchField {
            state: state.clone(),
            tooltip: None,
        }
    }

    /// A tooltip.
    pub fn tooltip(mut self, text: impl Into<SharedString>) -> Self {
        self.tooltip = Some(text.into());
        self
    }
}

impl RenderOnce for SearchField {
    fn render(self, _window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.tokens();
        let input = Input::new(&self.state)
            .appearance(false)
            .prefix(
                Icon::Search
                    .svg(18.0)
                    .text_color(hsla(t.md_on_surface_variant)),
            )
            .cleanable(true);
        let ring = hsla(t.md_primary);
        let focused = gpui::Focusable::focus_handle(self.state.read(cx), cx);
        div()
            .id("mmcl-search-field")
            .h(u(32.0))
            .min_w(u(180.0))
            .flex()
            .items_center()
            .pl(u(10.0))
            .pr(u(14.0))
            .rounded_full()
            .bg(hsla(t.md_surface_container_highest))
            .border_1()
            .border_color(gpui::transparent_black())
            .when(focused.contains_focused(_window, cx), move |el| {
                el.border_color(ring)
            })
            .child(input)
            .when_some(self.tooltip, |el, tip| el.launcher_tooltip(tip))
    }
}

// ---------------------------------------------------------------------------------------------
// Empty state badge

/// Badge diameter (px).
pub const EMPTY_BADGE_SIZE: f32 = 72.0;

/// The 72x72 tonal badge with a glyph above an empty screen's heading (`EmptyStates.badge`).
pub fn empty_state_badge(icon: Icon, cx: &App) -> impl IntoElement {
    let t = cx.tokens();
    div()
        .flex_none()
        .size(u(EMPTY_BADGE_SIZE))
        .rounded(u(mmcl_theme::shape::EXTRA_LARGE))
        .bg(hsla(t.md_surface_container_high))
        .flex()
        .items_center()
        .justify_center()
        .child(icon.svg(36.0).text_color(hsla(t.md_on_surface_variant)))
}

// ---------------------------------------------------------------------------------------------
// Announcement banner

/// Height of a shown banner row (px).
pub const BANNER_HEIGHT: f32 = 40.0;

/// The banner row height for `text`: 40 when non-blank, else 0 (`AnnouncementBanners.show`).
pub fn banner_height(text: Option<&str>) -> f32 {
    if text.is_some_and(|t| !t.trim().is_empty()) {
        BANNER_HEIGHT
    } else {
        0.0
    }
}

/// The warning-coloured announcement banner: icon, wrapping text, optional dismiss glyph.
/// Renders as an empty zero-height row when the text is blank.
#[derive(IntoElement)]
pub struct AnnouncementBanner {
    id: ElementId,
    text: Option<SharedString>,
    on_dismiss: Option<ActivateHandler>,
}

impl AnnouncementBanner {
    /// A banner for `text` (blank or `None` hides it).
    pub fn new(id: impl Into<ElementId>, text: Option<SharedString>) -> Self {
        AnnouncementBanner {
            id: id.into(),
            text,
            on_dismiss: None,
        }
    }

    /// Shows the dismiss glyph and calls `f` when used.
    pub fn on_dismiss(mut self, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        self.on_dismiss = Some(Rc::new(f));
        self
    }
}

impl RenderOnce for AnnouncementBanner {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let height = banner_height(self.text.as_deref());
        let row = div().id(self.id.clone()).h(u(height)).flex_none();
        let Some(text) = self.text.filter(|_| height > 0.0) else {
            return row;
        };
        let t = cx.tokens();
        let fg = hsla(t.color_text_inverse);
        let dismiss = self.on_dismiss.map(|handler| {
            let id = child_id(&self.id, "dismiss");
            let focus = focus_handle_for(&id, window, cx);
            div()
                .id(id)
                .track_focus(&focus)
                .relative()
                .child(nav_marker(&focus))
                .cursor_pointer()
                .opacity(0.8)
                .hover(|s| s.opacity(1.0))
                .child(Icon::Close.svg(18.0).text_color(fg))
                .launcher_tooltip(t!("tooltip.main.announcementClose"))
                .on_activate(handler)
        });
        row.flex()
            .items_center()
            .gap(u(8.0))
            .px(u(12.0))
            .py(u(4.0))
            .bg(hsla(t.color_warning))
            .text_color(fg)
            .font_weight(gpui::FontWeight::SEMIBOLD)
            .child(Icon::Announcement.svg(18.0).text_color(fg))
            .child(div().flex_1().text_center().child(text))
            .children(dismiss)
    }
}

// ---------------------------------------------------------------------------------------------
// Offline chip

/// The network's offline state, as the net slice reports it. The app keeps this global in step
/// with the network layer ([`set_offline`]); chips update live.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct OfflineState(pub bool);

impl Global for OfflineState {}

/// Records the offline state and repaints every window.
pub fn set_offline(cx: &mut App, offline: bool) {
    if cx.try_global::<OfflineState>().copied() != Some(OfflineState(offline)) {
        cx.set_global(OfflineState(offline));
        cx.refresh_windows();
    }
}

/// Whether the launcher is offline.
pub fn is_offline(cx: &App) -> bool {
    cx.try_global::<OfflineState>().is_some_and(|s| s.0)
}

/// The "Offline" pill (`OfflineIndicator`): visible only while offline, tooltip
/// `tooltip.offline`.
#[derive(IntoElement)]
pub struct OfflineChip {
    id: ElementId,
}

impl OfflineChip {
    /// The chip.
    pub fn new(id: impl Into<ElementId>) -> Self {
        OfflineChip { id: id.into() }
    }
}

impl RenderOnce for OfflineChip {
    fn render(self, _window: &mut Window, cx: &mut App) -> impl IntoElement {
        let shown = is_offline(cx);
        let t = cx.tokens();
        div()
            .id(self.id)
            .when(!shown, |el| el.hidden())
            .when(shown, |el| {
                el.flex_none()
                    .px(u(10.0))
                    .py(u(2.0))
                    .rounded_full()
                    .bg(hsla(t.color_danger))
                    .text_color(hsla(t.color_text_on_primary))
                    .text_size(u(11.0))
                    .font_weight(gpui::FontWeight::SEMIBOLD)
                    .child(t!("main.fetchLabel.offline"))
                    .launcher_tooltip(t!("tooltip.offline"))
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn banner_is_forty_or_zero() {
        assert_eq!(banner_height(Some("Maintenance tonight")), 40.0);
        assert_eq!(banner_height(Some("   ")), 0.0);
        assert_eq!(banner_height(Some("")), 0.0);
        assert_eq!(banner_height(None), 0.0);
    }
}

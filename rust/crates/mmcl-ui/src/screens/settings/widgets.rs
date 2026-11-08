// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Layout pieces shared by the Settings and Runtime screens: the option item for dropdowns, the
//! settings group card (`.settingsGroup`) with its rows (`.settingsRow`), section titles, hints
//! and the bottom bar.

use gpui::{
    AnyElement, App, AppContext as _, Context, Entity, IntoElement, ParentElement, SharedString,
    Styled, Window, div, prelude::FluentBuilder as _,
};
use gpui_component::IndexPath;
use gpui_component::searchable_list::SearchableListItem;
use gpui_component::select::SelectState;
use mmcl_theme::shape;
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::theme::{ThemeExt as _, TypeStyleExt as _, hsla};

use crate::scale::u;

/// A dropdown entry: a label and its index in the screen's option list.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Opt {
    pub label: SharedString,
    pub index: usize,
}

impl SearchableListItem for Opt {
    type Value = usize;

    fn title(&self) -> SharedString {
        self.label.clone()
    }

    fn value(&self) -> &usize {
        &self.index
    }
}

/// The dropdown state type the screens use.
pub type OptSelect = SelectState<Vec<Opt>>;

/// Options from labels, indexed in order.
pub fn opts(labels: impl IntoIterator<Item = impl Into<SharedString>>) -> Vec<Opt> {
    labels
        .into_iter()
        .enumerate()
        .map(|(index, label)| Opt {
            label: label.into(),
            index,
        })
        .collect()
}

/// A dropdown state over `labels` with `selected` chosen.
pub fn new_select<V: 'static>(
    labels: Vec<String>,
    selected: Option<usize>,
    window: &mut Window,
    cx: &mut Context<V>,
) -> Entity<OptSelect> {
    let items = opts(labels);
    let selected = selected.filter(|i| *i < items.len()).map(IndexPath::new);
    cx.new(|cx| SelectState::new(items, selected, window, cx))
}

/// Selects `index` in a dropdown (no event fires).
pub fn select_index(
    state: &Entity<OptSelect>,
    index: Option<usize>,
    window: &mut Window,
    cx: &mut App,
) {
    state.update(cx, |s, cx| {
        s.set_selected_index(index.map(IndexPath::new), window, cx)
    });
}

/// A category's header (`.settingsCategoryHeader`).
pub fn category_header(text: impl Into<SharedString>, cx: &App) -> impl IntoElement {
    div()
        .type_role(TypeRole::HeadlineSmall)
        .text_color(hsla(cx.tokens().color_text))
        .pb(u(4.0))
        .child(text.into())
}

/// A section title above a group (`.settingsSectionTitle`).
pub fn section_title(text: impl Into<SharedString>, cx: &App) -> impl IntoElement {
    div()
        .type_role(TypeRole::TitleSmall)
        .text_color(hsla(cx.tokens().color_text_muted))
        .pt(u(12.0))
        .pb(u(2.0))
        .px(u(4.0))
        .child(text.into())
}

/// A muted hint line (`.settingsHint`).
pub fn hint(text: impl Into<SharedString>, cx: &App) -> impl IntoElement {
    div()
        .type_role(TypeRole::BodySmall)
        .text_color(hsla(cx.tokens().color_text_muted))
        .child(text.into())
}

/// A group card of rows (`SettingsGroups`): only visible rows are passed in, and each is tagged
/// first / middle / last by its position, so the card stays one rounded shape whatever is
/// hidden. An empty group renders nothing.
pub fn group(rows: Vec<AnyElement>, danger: bool, cx: &App) -> AnyElement {
    if rows.is_empty() {
        return div().into_any_element();
    }
    let t = cx.tokens();
    let n = rows.len();
    let mut card = div()
        .flex()
        .flex_col()
        .w_full()
        .rounded(u(shape::LARGE))
        .bg(hsla(t.md_surface_container_low))
        .border_1()
        .border_color(hsla(if danger {
            t.color_danger
        } else {
            t.color_border
        }))
        .overflow_hidden();
    for (i, row) in rows.into_iter().enumerate() {
        card = card.child(
            div()
                .w_full()
                .px(u(16.0))
                .py(u(12.0))
                .when(i + 1 < n, |d| {
                    d.border_b_1().border_color(hsla(t.color_divider))
                })
                .child(row),
        );
    }
    card.into_any_element()
}

/// A row: a label (with an optional hint under it) on the left, the control on the right.
pub fn labeled_row(
    label: impl IntoElement,
    hint_text: Option<SharedString>,
    control: impl IntoElement,
    cx: &App,
) -> AnyElement {
    let t = cx.tokens();
    div()
        .flex()
        .items_center()
        .gap(u(16.0))
        .w_full()
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(u(2.0))
                .child(
                    div()
                        .type_role(TypeRole::BodyMedium)
                        .text_color(hsla(t.color_text))
                        .child(label),
                )
                .when_some(hint_text, |d, h| d.child(hint(h, cx))),
        )
        .child(div().flex_none().child(control))
        .into_any_element()
}

/// A row holding one full-width element with an optional hint under it.
pub fn stacked_row(
    main: impl IntoElement,
    hint_text: Option<SharedString>,
    cx: &App,
) -> AnyElement {
    div()
        .flex()
        .flex_col()
        .gap(u(4.0))
        .w_full()
        .child(main)
        .when_some(hint_text, |d, h| d.child(hint(h, cx)))
        .into_any_element()
}

/// A row of buttons (wrapping).
pub fn button_row(buttons: Vec<AnyElement>) -> AnyElement {
    div()
        .flex()
        .flex_wrap()
        .items_center()
        .gap(u(8.0))
        .children(buttons)
        .into_any_element()
}

/// The 44 px bottom bar: `leading` on the left, `trailing` on the right.
pub fn bottom_bar(leading: AnyElement, trailing: Vec<AnyElement>, cx: &App) -> impl IntoElement {
    let t = cx.tokens();
    div()
        .flex_none()
        .h(u(52.0))
        .w_full()
        .flex()
        .items_center()
        .gap(u(8.0))
        .px(u(16.0))
        .bg(hsla(t.color_bg_soft))
        .border_t_1()
        .border_color(hsla(t.color_border))
        .child(div().flex_1().min_w_0().child(leading))
        .children(trailing)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn options_are_indexed_in_order() {
        let o = opts(["a", "b", "c"]);
        assert_eq!(o.len(), 3);
        assert_eq!(o[2].index, 2);
        assert_eq!(o[1].title().as_ref(), "b");
        assert_eq!(*o[0].value(), 0);
    }
}

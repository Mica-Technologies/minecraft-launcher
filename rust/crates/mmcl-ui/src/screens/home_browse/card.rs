// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pack cards: Home's hero card (`ModpackHeroCard`, 360 wide) and Browse's library card
//! (`LibraryCard`, 300 wide), plus the import placeholder card.
//!
//! Cards are stateless elements rebuilt from the pack they show on every render, so tooltips,
//! badges, classes and the context menu can never go stale across rebinds (guarded bug 6). The
//! 130 ms single-click timer lives in the screen and is dropped on every rebuild (guarded
//! bug 4).

use std::rc::Rc;

use gpui::{
    AnyElement, App, ClickEvent, ElementId, ImageSource, InteractiveElement, IntoElement,
    ObjectFit, ParentElement, RenderOnce, SharedString, StatefulInteractiveElement, Styled,
    StyledImage, Window, div, img, linear_color_stop, linear_gradient, prelude::FluentBuilder as _,
};
use gpui_component::Disableable as _;
use gpui_component::button::{Button, ButtonVariants as _};
use gpui_component::menu::{ContextMenuExt as _, PopupMenu};
use mmcl_theme::shape;
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::theme::{ThemeExt as _, TypeStyleExt as _, hsla};
use mmcl_ui_kit::{LoadingIndicator, TooltipExt as _};

use super::home_logic::BadgeTone;
use super::visuals::CardVisual;
use crate::scale::u;

/// Hero card width (px).
pub const HERO_WIDTH: f32 = 360.0;
/// Hero card image height.
pub const HERO_IMAGE_H: f32 = 150.0;
/// Library card width.
pub const LIBRARY_WIDTH: f32 = 300.0;
/// Library card image height.
pub const LIBRARY_IMAGE_H: f32 = 110.0;

/// A click on the card body (`click_count` 1 or 2).
pub type CardClick = Rc<dyn Fn(usize, &mut Window, &mut App)>;
/// Builds the context menu.
pub type MenuBuilder = Rc<dyn Fn(PopupMenu, &mut Window, &mut App) -> PopupMenu>;
/// A button handler.
pub type Action = Rc<dyn Fn(&mut Window, &mut App)>;

/// One card button.
pub struct CardButton {
    pub label: SharedString,
    pub style: ButtonStyle,
    pub disabled: bool,
    pub grows: bool,
    pub tooltip: Option<SharedString>,
    pub on_click: Action,
}

/// A card button's style.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ButtonStyle {
    /// `primary`.
    Primary,
    /// `heroCardSecondaryBtn` / `secondary`.
    Secondary,
    /// `dangerZone`.
    Danger,
}

/// Geometry of one card kind.
#[derive(Debug, Clone, Copy)]
pub struct CardGeometry {
    pub width: f32,
    pub image_h: f32,
    pub logo_box: f32,
    pub logo: f32,
    pub body_x: f32,
    pub body_bottom: f32,
    pub name_role: TypeRole,
    pub badge_pad: (f32, f32),
}

impl CardGeometry {
    /// The hero card.
    pub const HERO: CardGeometry = CardGeometry {
        width: HERO_WIDTH,
        image_h: HERO_IMAGE_H,
        logo_box: 72.0,
        logo: 68.0,
        body_x: 18.0,
        body_bottom: 16.0,
        name_role: TypeRole::HeadingH2,
        badge_pad: (10.0, 12.0),
    };
    /// The library card.
    pub const LIBRARY: CardGeometry = CardGeometry {
        width: LIBRARY_WIDTH,
        image_h: LIBRARY_IMAGE_H,
        logo_box: 56.0,
        logo: 52.0,
        body_x: 14.0,
        body_bottom: 10.0,
        name_role: TypeRole::HeadingH3,
        badge_pad: (8.0, 10.0),
    };
}

/// A pack card.
#[derive(IntoElement)]
pub struct PackCard {
    pub id: ElementId,
    pub geometry: CardGeometry,
    pub visual: CardVisual,
    pub background: Option<ImageSource>,
    pub badges: Vec<(String, BadgeTone)>,
    pub name: SharedString,
    pub chips: Vec<String>,
    pub status: SharedString,
    pub buttons: Vec<CardButton>,
    pub on_click: Option<CardClick>,
    pub menu: Option<MenuBuilder>,
    pub focused: bool,
}

fn badge(text: String, tone: BadgeTone, cx: &App) -> impl IntoElement {
    let t = cx.tokens();
    let (bg, fg) = match tone {
        BadgeTone::Success => (t.md_success_container, t.md_on_success_container),
        BadgeTone::Warn => (t.md_warning_container, t.md_on_warning_container),
        BadgeTone::Info => (t.md_primary_container, t.md_on_primary_container),
        BadgeTone::Neutral => (t.md_surface_container_highest, t.md_on_surface),
    };
    div()
        .px(u(8.0))
        .py(u(2.0))
        .rounded_full()
        .bg(hsla(bg))
        .text_color(hsla(fg))
        .type_role(TypeRole::LabelSmall)
        .child(text)
}

/// A chip row entry.
pub fn chip(text: String, cx: &App) -> impl IntoElement {
    let t = cx.tokens();
    div()
        .px(u(8.0))
        .py(u(2.0))
        .rounded(u(shape::SMALL))
        .border_1()
        .border_color(hsla(t.md_outline_variant))
        .text_color(hsla(t.md_on_surface_variant))
        .type_role(TypeRole::LabelSmall)
        .child(text)
}

/// The image area: gradient, background, veil and badges.
fn image_area(
    g: &CardGeometry,
    visual: &CardVisual,
    background: Option<ImageSource>,
    badges: Vec<(String, BadgeTone)>,
    cx: &App,
) -> impl IntoElement {
    let veil = linear_gradient(
        90.0,
        linear_color_stop(gpui::hsla(0.0, 0.0, 0.0, 0.40), 0.0),
        linear_color_stop(gpui::hsla(0.0, 0.0, 0.0, 0.70), 1.0),
    );
    div()
        .relative()
        .w_full()
        .h(u(g.image_h))
        .overflow_hidden()
        .rounded_t(u(shape::LARGE))
        .bg(hsla(cx.tokens().md_surface_container_high))
        .when_some(visual.gradient.clone(), |el, grad| {
            el.child(
                img(ImageSource::Image(grad))
                    .absolute()
                    .size_full()
                    .object_fit(ObjectFit::Fill),
            )
        })
        .when_some(background, |el, bg| {
            el.child(
                div()
                    .absolute()
                    .size_full()
                    .child(img(bg).size_full().object_fit(ObjectFit::Cover)),
            )
        })
        .child(div().absolute().size_full().bg(veil))
        .child(
            div()
                .absolute()
                .top(u(g.badge_pad.0))
                .right(u(g.badge_pad.1))
                .flex()
                .gap(u(6.0))
                .children(badges.into_iter().map(|(t, tone)| badge(t, tone, cx))),
        )
}

fn logo_frame(g: &CardGeometry, visual: &CardVisual, id: &ElementId, cx: &App) -> impl IntoElement {
    let t = cx.tokens();
    let frame = !visual.logo_transparent;
    let logo = if visual.logo_loaded && !mmcl_ui_kit::motion::is_reduced() {
        mmcl_ui_kit::FadeImage::new(
            mmcl_ui_kit::focus::child_id(id, "logo"),
            visual.logo.clone(),
        )
        .size(g.logo)
        .into_any_element()
    } else {
        img(visual.logo.clone())
            .size(u(g.logo))
            .object_fit(ObjectFit::Contain)
            .into_any_element()
    };
    div()
        .flex_none()
        .size(u(g.logo_box))
        .mt(u(-g.logo_box / 2.0))
        .flex()
        .items_center()
        .justify_center()
        .rounded(u(shape::MEDIUM))
        .when(frame, |el| {
            el.bg(hsla(t.md_surface_container))
                .border_1()
                .border_color(hsla(t.md_outline_variant))
                .shadow_md()
        })
        .child(
            div()
                .size(u(g.logo))
                .overflow_hidden()
                .rounded(u(10.0))
                .child(logo),
        )
}

fn button(id: ElementId, b: CardButton) -> AnyElement {
    let handler = b.on_click.clone();
    let mut btn = Button::new(id)
        .label(b.label)
        .disabled(b.disabled)
        .h(u(38.0))
        .on_click(move |_: &ClickEvent, window, cx| handler(window, cx));
    btn = match b.style {
        ButtonStyle::Primary => btn.primary(),
        ButtonStyle::Danger => btn.danger(),
        ButtonStyle::Secondary => btn.outline(),
    };
    if let Some(tip) = b.tooltip {
        btn = btn.tooltip(tip);
    }
    if b.grows {
        div().flex_1().child(btn.w_full()).into_any_element()
    } else {
        btn.into_any_element()
    }
}

impl RenderOnce for PackCard {
    fn render(self, _window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.tokens();
        let g = self.geometry;
        let ring = hsla(t.md_primary);
        let click = self.on_click.clone();
        let body_id = mmcl_ui_kit::focus::child_id(&self.id, "body");
        let body = div()
            .id(body_id)
            .cursor_pointer()
            .flex()
            .flex_col()
            .child(image_area(
                &g,
                &self.visual,
                self.background,
                self.badges,
                cx,
            ))
            .child(
                div()
                    .px(u(g.body_x))
                    .pb(u(g.body_bottom))
                    .flex()
                    .flex_col()
                    .gap(u(if g.width > 320.0 { 6.0 } else { 4.0 }))
                    .child(logo_frame(&g, &self.visual, &self.id, cx))
                    .child(
                        div()
                            .type_role(g.name_role)
                            .text_color(hsla(t.color_text))
                            .max_w(u(g.width - 2.0 * g.body_x))
                            .child(self.name),
                    )
                    .child(
                        div()
                            .flex()
                            .flex_wrap()
                            .gap(u(6.0))
                            .children(self.chips.into_iter().map(|c| chip(c, cx))),
                    )
                    .child(
                        div()
                            .type_role(TypeRole::LabelSmall)
                            .text_color(hsla(t.color_text_muted))
                            .child(self.status),
                    ),
            )
            .when_some(click, |el, click| {
                el.on_click(move |ev: &ClickEvent, window, cx| {
                    if ev.standard_click() {
                        click(ev.click_count(), window, cx);
                    }
                })
            });
        let id = self.id.clone();
        let actions = div()
            .px(u(g.body_x))
            .pb(u(if g.width > 320.0 { 16.0 } else { 12.0 }))
            .flex()
            .gap(u(8.0))
            .children(
                self.buttons
                    .into_iter()
                    .enumerate()
                    .map(|(i, b)| button(mmcl_ui_kit::focus::child_id(&id, format!("btn{i}")), b)),
            );
        let card = div()
            .id(self.id)
            .flex_none()
            .w(u(g.width))
            .flex()
            .flex_col()
            .rounded(u(shape::LARGE))
            .bg(hsla(t.md_surface_container))
            .border_1()
            .border_color(if self.focused {
                ring
            } else {
                hsla(t.md_outline_variant)
            })
            .shadow_sm()
            .child(body)
            .child(actions);
        match self.menu {
            Some(menu) => card
                .context_menu(move |m, window, cx| menu(m, window, cx))
                .into_any_element(),
            None => card.into_any_element(),
        }
    }
}

/// The import placeholder card (300 wide, Forge default gradient, a loading indicator).
#[derive(IntoElement)]
pub struct ImportPlaceholder {
    pub name: SharedString,
    pub status: SharedString,
    pub gradient: Option<std::sync::Arc<gpui::Image>>,
}

impl RenderOnce for ImportPlaceholder {
    fn render(self, _window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.tokens();
        div()
            .id("browse-import-placeholder")
            .flex_none()
            .w(u(LIBRARY_WIDTH))
            .flex()
            .flex_col()
            .rounded(u(shape::LARGE))
            .bg(hsla(t.md_surface_container))
            .border_1()
            .border_color(hsla(t.md_outline_variant))
            .child(
                div()
                    .relative()
                    .h(u(LIBRARY_IMAGE_H))
                    .overflow_hidden()
                    .rounded_t(u(shape::LARGE))
                    .flex()
                    .items_center()
                    .justify_center()
                    .when_some(self.gradient, |el, g| {
                        el.child(
                            img(ImageSource::Image(g))
                                .absolute()
                                .size_full()
                                .object_fit(ObjectFit::Fill),
                        )
                    })
                    .child(LoadingIndicator::new("browse-import-loading").size(56.0)),
            )
            .child(
                div()
                    .p(u(14.0))
                    .flex()
                    .flex_col()
                    .gap(u(4.0))
                    .child(
                        div()
                            .type_role(TypeRole::HeadingH2)
                            .text_color(hsla(t.color_text))
                            .child(self.name),
                    )
                    .child(
                        div()
                            .id("browse-import-status")
                            .type_role(TypeRole::LabelSmall)
                            .text_color(hsla(t.color_text_muted))
                            .child(self.status.clone())
                            .launcher_tooltip(self.status),
                    ),
            )
    }
}

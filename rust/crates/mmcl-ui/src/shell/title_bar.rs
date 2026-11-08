// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The custom title bar drawn on every OS (`WindowsTitleBarControls`, the macOS hidden-inset
//! title bar, GPUI client decorations on Linux).
//!
//! It is transparent, so it blends with the window fill (or Mica) and the screen's own navbar
//! below reads as one surface. Left: the app icon and the window title (inset for the macOS
//! traffic lights). Right: the help button (when the screen draws none of its own), then the
//! window controls gpui-component's `TitleBar` draws (Windows caption buttons with native hit
//! areas and snap layouts, Linux client-side buttons). The whole strip is a drag region.

use gpui::{
    App, ClickEvent, ElementId, InteractiveElement as _, IntoElement, ParentElement, RenderOnce,
    SharedString, StatefulInteractiveElement as _, Styled, Window, div, img,
    prelude::FluentBuilder, px,
};
use gpui_component::TitleBar;
use mmcl_ui_kit::theme::{ThemeExt as _, hsla};
use mmcl_ui_kit::{HelpTopic, Icon, TooltipExt as _};

use super::hooks;

/// The title bar's height (native size; not scaled with the interface scale so the caption
/// buttons keep their OS size).
pub fn height() -> gpui::Pixels {
    gpui_component::TITLE_BAR_HEIGHT
}

/// The main window's title bar.
#[derive(IntoElement)]
pub struct AppTitleBar {
    title: SharedString,
    help: Option<HelpTopic>,
    brand: bool,
}

impl AppTitleBar {
    /// A title bar showing `title`; `help` adds the help button for that topic.
    pub fn new(title: impl Into<SharedString>, help: Option<HelpTopic>) -> Self {
        AppTitleBar {
            title: title.into(),
            help,
            brand: true,
        }
    }

    /// Whether the app icon and title show (default). Off under a screen whose navbar already
    /// draws the brand lockup and the screen name (Java's `hideRedundantBranding`), so the
    /// strip keeps only the drag area and the window controls.
    pub fn brand(mut self, brand: bool) -> Self {
        self.brand = brand;
        self
    }
}

/// The title-bar help button ("?"): opens help on `topic`.
pub fn help_button(id: impl Into<ElementId>, topic: HelpTopic, cx: &App) -> impl IntoElement {
    let t = cx.tokens();
    let glyph = hsla(t.md_on_surface_variant);
    let hover = hsla(t.md_state_hover_on_surface);
    let pressed = hsla(t.md_state_pressed_on_surface);
    div()
        .id(id.into())
        // The title bar behind is a caption (drag) area: without this a press on Windows
        // reaches the OS as a caption drag and the click never arrives.
        .occlude()
        .flex()
        .items_center()
        .justify_center()
        .w(px(30.0))
        .h(px(26.0))
        .rounded(px(mmcl_theme::shape::SMALL))
        .cursor_pointer()
        .hover(move |s| s.bg(hover))
        .active(move |s| s.bg(pressed))
        .child(Icon::Help.svg(16.0).text_color(glyph))
        .launcher_tooltip(mmcl_i18n::t!("menu.help.title").into_owned())
        .on_click(move |_: &ClickEvent, _, cx| hooks::show_help(cx, topic))
}

impl RenderOnce for AppTitleBar {
    fn render(self, _window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.tokens();
        let text = hsla(t.color_text_muted);
        TitleBar::new()
            .bg(gpui::transparent_black())
            .border_color(gpui::transparent_black())
            .on_close_window(|_, window, cx| super::controller::on_close_requested(window, cx))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(8.0))
                    .min_w_0()
                    .overflow_hidden()
                    .when(self.brand, |el| {
                        el.child(
                            img(mmcl_ui_kit::assets::APP_ICON)
                                .size(px(18.0))
                                .flex_shrink_0(),
                        )
                        .child(
                            div()
                                .text_size(px(12.0))
                                .text_color(text)
                                .truncate()
                                .child(self.title),
                        )
                    }),
            )
            .child(
                div()
                    .flex()
                    .items_center()
                    .pr(px(4.0))
                    .when_some(self.help, |el, topic| {
                        el.child(help_button("mmcl-titlebar-help", topic, cx))
                    }),
            )
    }
}

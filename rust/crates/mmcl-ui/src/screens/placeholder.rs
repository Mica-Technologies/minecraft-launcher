// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! A stand-in screen with the real screen's identity (id, title, help topic, navigation rules,
//! minimum and preferred size), registered for each launcher screen until its module replaces it
//! with the real view. It shows the screen's nav bar and links to the other main screens, so the
//! shell can be exercised end to end.

use gpui::{
    ClickEvent, Context, IntoElement, ParentElement, Pixels, Render, SharedString, Size, Styled,
    Window, div, px, size,
};
use gpui_component::button::{Button, ButtonVariants as _};
use mmcl_i18n::t;
use mmcl_ui_kit::theme::{ThemeExt as _, hsla};
use mmcl_ui_kit::{HelpTopic, Icon, Navigator, Screen, ScreenId, ScreenRegistry, ScreenRequest};

use crate::scale::u;
use crate::widgets::{NavBar, empty_state_badge};

/// The identity a placeholder takes over from the real screen.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ScreenSpec {
    /// The screen id.
    pub id: ScreenId,
    /// The title key (window title suffix and nav bar heading).
    pub title_key: &'static str,
    /// F1 / help button topic.
    pub help: HelpTopic,
    /// Whether toolbar navigation may leave it.
    pub allows_navigation: bool,
    /// Minimum size (layout px at 100 %).
    pub min: (f32, f32),
    /// Size the window grows to when shown.
    pub pref: Option<(f32, f32)>,
}

/// A placeholder screen.
pub struct PlaceholderScreen {
    spec: ScreenSpec,
}

impl PlaceholderScreen {
    /// A placeholder for `spec`.
    pub fn new(spec: ScreenSpec) -> Self {
        PlaceholderScreen { spec }
    }

    /// Registers a placeholder factory for `spec.id`.
    pub fn register(cx: &mut gpui::App, spec: ScreenSpec) {
        ScreenRegistry::register_view(cx, spec.id, move |_req, _window, _cx| {
            PlaceholderScreen::new(spec)
        });
    }
}

fn link(id: &'static str, label: SharedString, target: ScreenId) -> Button {
    Button::new(id)
        .label(label)
        .ghost()
        .on_click(move |_: &ClickEvent, _, cx| {
            crate::shell::navigate_gated(cx, ScreenRequest::new(target));
        })
}

impl Screen for PlaceholderScreen {
    fn id(&self) -> ScreenId {
        self.spec.id
    }

    fn title(&self, _cx: &gpui::App) -> SharedString {
        t!(self.spec.title_key).into_owned().into()
    }

    fn help_topic(&self) -> HelpTopic {
        self.spec.help
    }

    fn allows_toolbar_navigation(&self) -> bool {
        self.spec.allows_navigation
    }

    fn min_size(&self) -> Size<Pixels> {
        size(px(self.spec.min.0), px(self.spec.min.1))
    }

    fn pref_size(&self) -> Option<Size<Pixels>> {
        self.spec.pref.map(|(w, h)| size(px(w), px(h)))
    }

    fn has_own_help_button(&self) -> bool {
        false
    }
}

impl Render for PlaceholderScreen {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let current = self.spec.id;
        let mut bar = NavBar::new(t!(self.spec.title_key).into_owned());
        if self.spec.allows_navigation {
            if current != ScreenId::HOME {
                bar = bar.action(link(
                    "ph-home",
                    t!("menu.modpacks.home").into_owned().into(),
                    ScreenId::HOME,
                ));
            }
            if current != ScreenId::BROWSE {
                bar = bar.action(link(
                    "ph-browse",
                    t!("main.navbar.browse").into_owned().into(),
                    ScreenId::BROWSE,
                ));
            }
            if current != ScreenId::SETTINGS {
                bar = bar.action(link(
                    "ph-settings",
                    t!("main.navbar.settings").into_owned().into(),
                    ScreenId::SETTINGS,
                ));
            }
        } else if current != ScreenId::LOGIN && Navigator::current(cx).is_some() {
            bar = bar.action(link(
                "ph-home",
                t!("menu.modpacks.home").into_owned().into(),
                ScreenId::HOME,
            ));
        }
        div().size_full().flex().flex_col().child(bar).child(
            div()
                .flex_1()
                .flex()
                .flex_col()
                .items_center()
                .justify_center()
                .gap(u(12.0))
                .child(empty_state_badge(Icon::EmptyBox, cx))
                .child(
                    div()
                        .text_color(hsla(t.color_text_muted))
                        .child(t!("ui.placeholder.body").into_owned()),
                ),
        )
    }
}

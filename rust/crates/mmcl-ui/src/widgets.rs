// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Shared controls for the screens: the kit's widgets re-exported, plus the screen nav bar.
//!
//! | Java | Use |
//! |---|---|
//! | `TooltipManager.install` | `.launcher_tooltip(text)` ([`TooltipExt`]) on any element with an id |
//! | `IconButtons.decorate` | [`IconButton`] |
//! | `FilterChip` | [`FilterChip`] |
//! | `SearchFields.decorate` | [`SearchField`] |
//! | `EmptyStates.badge` | [`empty_state_badge`] |
//! | `AnnouncementBanners.show` | [`AnnouncementBanner`] |
//! | `OfflineIndicator.applyTo` | [`OfflineChip`] |
//! | `MaterialSwitchSkin` | [`Switch`] |
//! | `WavyProgressBar`, `LoadingIndicator`, `StepBadge` | [`WavyProgressBar`], [`LoadingIndicator`], [`StepBadge`] |
//! | `ImageFadeIn`, `AvatarImages` | [`FadeImage`], [`Avatar`] / [`Avatars`] |
//! | the per-screen `navBar` row | [`NavBar`] |

use gpui::{
    AnyElement, App, IntoElement, ParentElement, RenderOnce, SharedString, Styled, Window, div, img,
};
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::theme::{ThemeExt as _, TypeStyleExt as _, hsla};

pub use mmcl_ui_kit::{
    AnnouncementBanner, Avatar, Avatars, FadeImage, FilterChip, Icon, IconButton, LauncherTooltip,
    LoadingIndicator, OfflineChip, SearchField, StepBadge, StepState, Switch, TooltipExt,
    WavyProgressBar, empty_state_badge, tooltip,
};

use crate::scale::u;

/// The nav bar's height (px at 100 %).
pub const NAV_BAR_HEIGHT: f32 = 52.0;

/// A screen's top bar (`.navBar`): brand mark, the screen name, then right-aligned actions
/// (refresh, Browse, Settings, the account switcher, ...).
#[derive(IntoElement)]
pub struct NavBar {
    title: SharedString,
    actions: Vec<AnyElement>,
    leading: Vec<AnyElement>,
}

impl NavBar {
    /// A nav bar headed `title` (the screen name, e.g. `main.navbar.library`).
    pub fn new(title: impl Into<SharedString>) -> Self {
        NavBar {
            title: title.into(),
            actions: Vec::new(),
            leading: Vec::new(),
        }
    }

    /// Adds a right-aligned action (in order, left to right).
    pub fn action(mut self, el: impl IntoElement) -> Self {
        self.actions.push(el.into_any_element());
        self
    }

    /// Adds an element after the title (a back button, a status chip).
    pub fn leading(mut self, el: impl IntoElement) -> Self {
        self.leading.push(el.into_any_element());
        self
    }
}

impl RenderOnce for NavBar {
    fn render(self, _window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.tokens();
        div()
            .flex_none()
            .h(u(NAV_BAR_HEIGHT))
            .w_full()
            .flex()
            .items_center()
            .gap(u(12.0))
            .pl(u(14.0))
            .pr(u(10.0))
            .bg(hsla(t.color_bg_soft))
            .border_b_1()
            .border_color(hsla(t.color_border))
            .child(
                img(mmcl_ui_kit::assets::APP_ICON)
                    .size(u(36.0))
                    .flex_shrink_0(),
            )
            .child(
                div()
                    .type_role(TypeRole::TitleMedium)
                    .text_color(hsla(t.color_text))
                    .truncate()
                    .child(self.title),
            )
            .children(self.leading)
            .child(div().flex_1())
            .children(self.actions)
    }
}

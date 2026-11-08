// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `TooltipManager`: every launcher tooltip is styled `mcl-tooltip` (popup surface, 8 px corners,
//! 12 px text, padding 6/10), wraps at 300 px, shows after 400 ms and stays up to 10 s.
//!
//! ```ignore
//! div().id("refresh").tooltip(tooltip(t!("tooltip.main.refresh"))).tooltip_show_delay(SHOW_DELAY)
//! ```
//! or simply `.launcher_tooltip(text)` via [`TooltipExt`].

use std::time::{Duration, Instant};

use gpui::{
    AnyView, App, AppContext, Context, IntoElement, ParentElement, Render, SharedString,
    StatefulInteractiveElement, Styled, Window, div,
};

use crate::scale::u;
use crate::theme::{ThemeExt, hsla};

/// Delay before a tooltip shows.
pub const SHOW_DELAY: Duration = Duration::from_millis(400);
/// How long a tooltip stays visible.
pub const VISIBLE_FOR: Duration = Duration::from_secs(10);
/// Maximum tooltip width before wrapping (px).
pub const MAX_WIDTH: f32 = 300.0;

/// The tooltip view.
pub struct LauncherTooltip {
    text: SharedString,
    shown_at: Instant,
    expiry_scheduled: bool,
}

impl LauncherTooltip {
    /// A tooltip showing `text`.
    pub fn new(text: impl Into<SharedString>) -> Self {
        LauncherTooltip {
            text: text.into(),
            shown_at: Instant::now(),
            expiry_scheduled: false,
        }
    }

    /// Whether the visible time is used up at `now`.
    pub fn expired_at(&self, now: Instant) -> bool {
        now.duration_since(self.shown_at) >= VISIBLE_FOR
    }
}

impl Render for LauncherTooltip {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let expired = self.expired_at(Instant::now());
        if !expired && !self.expiry_scheduled {
            self.expiry_scheduled = true;
            // Re-check once the visible time has passed (only while it is on screen).
            let remaining = VISIBLE_FOR.saturating_sub(self.shown_at.elapsed());
            let timer = cx.background_executor().timer(remaining);
            cx.spawn_in(window, async move |this, cx| {
                timer.await;
                let _ = this.update(cx, |_, cx| cx.notify());
            })
            .detach();
        }
        div()
            .when_expired(expired)
            .max_w(u(MAX_WIDTH))
            .bg(hsla(t.color_popup))
            .text_color(hsla(t.color_text))
            .border_1()
            .border_color(hsla(t.color_popup_border))
            .rounded(u(mmcl_theme::shape::SMALL))
            .px(u(10.0))
            .py(u(6.0))
            .text_size(u(12.0))
            .font_family(crate::fonts::UI_FAMILY)
            .child(self.text.clone())
    }
}

trait WhenExpired {
    fn when_expired(self, expired: bool) -> Self;
}

impl WhenExpired for gpui::Div {
    fn when_expired(self, expired: bool) -> Self {
        if expired { self.invisible() } else { self }
    }
}

/// A tooltip builder for `.tooltip(..)`.
pub fn tooltip(
    text: impl Into<SharedString>,
) -> impl Fn(&mut Window, &mut App) -> AnyView + 'static {
    let text = text.into();
    move |_, cx| cx.new(|_| LauncherTooltip::new(text.clone())).into()
}

/// `.launcher_tooltip(text)` on any element with an id.
pub trait TooltipExt: StatefulInteractiveElement + Sized {
    /// Installs a launcher tooltip with the 400 ms show delay.
    fn launcher_tooltip(self, text: impl Into<SharedString>) -> Self {
        self.tooltip(tooltip(text)).tooltip_show_delay(SHOW_DELAY)
    }
}

impl<E: StatefulInteractiveElement> TooltipExt for E {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn expires_after_ten_seconds() {
        let t = LauncherTooltip::new("x");
        assert!(!t.expired_at(t.shown_at + Duration::from_secs(9)));
        assert!(t.expired_at(t.shown_at + Duration::from_secs(10)));
        assert_eq!(SHOW_DELAY.as_millis(), 400);
        assert_eq!(MAX_WIDTH, 300.0);
    }
}

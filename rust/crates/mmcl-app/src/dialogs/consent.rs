// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The MCP consent dialog (Java `FxConsentPrompt`) as a [`ConsentPresenter`] for
//! [`DeadlineConsentPrompt`].
//!
//! Each question opens its own small window over the launcher: who asks, which tool, what it
//! does, the arguments, the tool's extra warning, and Allow once / Allow for this session (when
//! offered) / Cancel. A countdown shows how long the answer still counts. When the deadline
//! passes the prompt denies and calls [`ConsentPresenter::dismiss`], which closes exactly that
//! window (the Java dialog stayed on screen after its answer stopped mattering); the window
//! also closes itself at the deadline in case the dismiss is lost. Closing the window denies.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui::{
    App, AppContext as _, Context, FontWeight, Global, InteractiveElement as _, IntoElement,
    ParentElement, Render, StatefulInteractiveElement as _, Styled, Task, Window, WindowHandle,
    div, prelude::FluentBuilder as _, px,
};
use gpui_component::Root;
use mmcl_i18n::{t, tf};
use mmcl_mcp::approval::{
    Answer as McpAnswer, CONSENT_TIMEOUT, ConsentPresenter, ConsentTicket, DeadlineConsentPrompt,
};
use mmcl_ui::GuiHandle;
use mmcl_ui_kit::focus::nav_scope;
use mmcl_ui_kit::scale::u;
use mmcl_ui_kit::{ThemeExt as _, hsla};

use super::widgets::{Pill, Tone, body, hint, mono, title};

/// The window size (px at 100 %).
pub const SIZE: (f32, f32) = (560.0, 440.0);

/// Time left before `deadline` (zero once passed).
pub fn remaining(deadline: Instant, now: Instant) -> Duration {
    deadline.saturating_duration_since(now)
}

/// `m:ss`, rounding partial seconds up so the label reaches `0:00` only at the deadline.
pub fn countdown(left: Duration) -> String {
    let secs = left.as_secs() + u64::from(left.subsec_nanos() > 0);
    format!("{}:{:02}", secs / 60, secs % 60)
}

/// The buttons, in display order, with the answer each gives.
pub fn buttons(offer_session_grant: bool) -> Vec<(String, McpAnswer, Tone)> {
    let mut out = vec![(
        t!("dialog.button.cancel").into_owned(),
        McpAnswer::Deny,
        Tone::Text,
    )];
    if offer_session_grant {
        out.push((
            t!("dialog.mcp.consent.button.allowSession").into_owned(),
            McpAnswer::AllowForSession,
            Tone::Tonal,
        ));
    }
    out.push((
        t!("dialog.mcp.consent.button.allowOnce").into_owned(),
        McpAnswer::AllowOnce,
        Tone::Primary,
    ));
    out
}

/// The open consent windows by ticket id (UI thread only).
#[derive(Default)]
struct ConsentWindows(HashMap<u64, WindowHandle<Root>>);

impl Global for ConsentWindows {}

/// One question's window.
pub struct ConsentView {
    id: u64,
    ticket: Option<ConsentTicket>,
    deadline: Instant,
    _ticker: Task<()>,
}

impl ConsentView {
    fn new(
        ticket: ConsentTicket,
        deadline: Instant,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let ticker = cx.spawn_in(window, async move |this, cx| {
            loop {
                cx.background_executor()
                    .timer(Duration::from_millis(500))
                    .await;
                let keep = this
                    .update_in(cx, |view, window, cx| {
                        let expired = view.ticket.as_ref().is_none_or(|t| t.is_expired())
                            || remaining(view.deadline, Instant::now()).is_zero();
                        if expired {
                            view.ticket = None;
                            cx.default_global::<ConsentWindows>().0.remove(&view.id);
                            window.remove_window();
                            false
                        } else {
                            cx.notify();
                            true
                        }
                    })
                    .unwrap_or(false);
                if !keep {
                    break;
                }
            }
        });
        ConsentView {
            id: ticket.id(),
            ticket: Some(ticket),
            deadline,
            _ticker: ticker,
        }
    }

    fn answer(&mut self, answer: McpAnswer, window: &mut Window, cx: &mut App) {
        if let Some(ticket) = self.ticket.take() {
            ticket.answer(answer);
        }
        cx.default_global::<ConsentWindows>().0.remove(&self.id);
        window.remove_window();
    }

    /// The window closes without a button: deny.
    fn deny(&mut self) {
        if let Some(ticket) = self.ticket.take() {
            ticket.answer(McpAnswer::Deny);
        }
    }
}

impl Render for ConsentView {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let Some(ticket) = self.ticket.as_ref() else {
            return div().size_full().bg(mmcl_ui::theme::secondary_fill(cx));
        };
        let r = ticket.request().clone();
        let left = countdown(remaining(self.deadline, Instant::now()));
        let row = buttons(r.offer_session_grant).into_iter().enumerate().map(
            |(i, (label, answer, tone))| {
                let weak = cx.entity().downgrade();
                Pill::new(("consent-button", i), label, tone).on_click(move |window, cx| {
                    let _ = weak.update(cx, |this, cx| this.answer(answer, window, cx));
                })
            },
        );
        div()
            .size_full()
            .flex()
            .flex_col()
            .bg(mmcl_ui::theme::secondary_fill(cx))
            .text_color(hsla(t.color_text))
            .font_family(mmcl_ui_kit::fonts::UI_FAMILY)
            .child(
                gpui_component::TitleBar::new()
                    .bg(gpui::transparent_black())
                    .border_color(gpui::transparent_black())
                    .child(
                        div()
                            .text_size(px(12.0))
                            .text_color(hsla(t.color_text_muted))
                            .child(r.title()),
                    ),
            )
            .child(
                nav_scope("mcp-consent")
                    .flex_1()
                    .min_h_0()
                    .flex()
                    .flex_col()
                    .gap(u(8.0))
                    .p(u(20.0))
                    .child(
                        div()
                            .text_size(u(17.0))
                            .font_weight(FontWeight::BOLD)
                            .text_color(hsla(t.md_on_surface))
                            .child(r.header()),
                    )
                    .child(hint(
                        tf!("dialog.mcp.consent.client", r.client_name.as_str()),
                        cx,
                    ))
                    .child(
                        div()
                            .id("consent-details")
                            .flex_1()
                            .min_h_0()
                            .overflow_y_scroll()
                            .flex()
                            .flex_col()
                            .gap(u(8.0))
                            .child(title(r.tool_title.clone(), cx))
                            .child(mono(r.tool_name.clone(), cx))
                            .when(!r.tool_description.trim().is_empty(), |el| {
                                el.child(body(r.tool_description.clone(), cx))
                            })
                            .child(hint(t!("dialog.mcp.consent.arguments").into_owned(), cx))
                            .child(
                                div()
                                    .rounded(u(8.0))
                                    .p(u(8.0))
                                    .bg(hsla(t.md_surface_container_low))
                                    .border_1()
                                    .border_color(hsla(t.md_outline_variant))
                                    .child(mono(r.arguments_summary.clone(), cx)),
                            )
                            .when_some(r.consent_detail.clone(), |el, detail| {
                                el.child(
                                    div()
                                        .rounded(u(8.0))
                                        .p(u(10.0))
                                        .bg(hsla(t.md_warning_container))
                                        .text_color(hsla(t.md_on_warning_container))
                                        .text_size(u(13.0))
                                        .child(detail),
                                )
                            }),
                    )
                    .child(
                        div()
                            .pt(u(8.0))
                            .flex()
                            .items_center()
                            .gap(u(8.0))
                            .child(hint(tf!("dialog.mcp.consent.closesIn", left), cx))
                            .child(div().flex_1())
                            .children(row),
                    ),
            )
    }
}

fn open_consent_window(ticket: ConsentTicket, deadline: Instant, cx: &mut App) {
    let id = ticket.id();
    let mut view = None;
    let opened = super::open_secondary_window(
        super::WindowSpec {
            title: ticket.request().title(),
            size: SIZE,
            min_size: None,
            resizable: false,
            modal: false,
            bounds_key: None,
        },
        // Unowned: the question must show even while the main window is minimised or in the
        // tray (an owned window would minimise with it).
        super::Owner::Unowned,
        cx,
        |window, cx| {
            let v = cx.new(|cx| ConsentView::new(ticket, deadline, window, cx));
            view = Some(v.clone());
            v.into()
        },
    );
    // A window that did not open dropped the ticket with it: the waiting side denies.
    let Some(handle) = opened else {
        return;
    };
    if let Some(view) = view {
        let _ = handle.update(cx, |_, window, cx| {
            window.activate_window();
            window.on_window_should_close(cx, move |_, cx| {
                view.update(cx, |this, _| this.deny());
                cx.default_global::<ConsentWindows>().0.remove(&id);
                true
            });
        });
    }
    cx.default_global::<ConsentWindows>().0.insert(id, handle);
}

fn close_consent_window(id: u64, cx: &mut App) {
    let Some(handle) = cx.default_global::<ConsentWindows>().0.remove(&id) else {
        return;
    };
    let _ = handle.update(cx, |_, window, _| window.remove_window());
}

/// The GPUI consent presenter: one window per question, closed when its deadline passes.
pub struct GpuiConsentPresenter {
    gui: GuiHandle,
    timeout: Duration,
}

impl GpuiConsentPresenter {
    /// The presenter for the default [`CONSENT_TIMEOUT`].
    pub fn new(gui: GuiHandle) -> Self {
        GpuiConsentPresenter {
            gui,
            timeout: CONSENT_TIMEOUT,
        }
    }

    /// The consent prompt the MCP host uses.
    pub fn prompt(gui: GuiHandle) -> Arc<DeadlineConsentPrompt> {
        Arc::new(DeadlineConsentPrompt::new(Arc::new(
            GpuiConsentPresenter::new(gui),
        )))
    }
}

impl ConsentPresenter for GpuiConsentPresenter {
    fn is_available(&self) -> bool {
        self.gui.is_started()
    }

    fn present(&self, ticket: ConsentTicket) {
        let deadline = Instant::now() + self.timeout;
        // When the UI is gone the job (and the ticket) is dropped: the waiting side denies.
        let _ = self
            .gui
            .run(move |cx| open_consent_window(ticket, deadline, cx));
    }

    fn dismiss(&self, ticket_id: u64) {
        let _ = self.gui.run(move |cx| close_consent_window(ticket_id, cx));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn countdown_rounds_up_and_pads() {
        assert_eq!(countdown(Duration::from_secs(120)), "2:00");
        assert_eq!(countdown(Duration::from_millis(119_100)), "2:00");
        assert_eq!(countdown(Duration::from_secs(61)), "1:01");
        assert_eq!(countdown(Duration::from_millis(400)), "0:01");
        assert_eq!(countdown(Duration::ZERO), "0:00");
    }

    #[test]
    fn remaining_saturates_after_the_deadline() {
        let now = Instant::now();
        assert_eq!(remaining(now, now + Duration::from_secs(3)), Duration::ZERO);
        assert_eq!(
            remaining(now + Duration::from_secs(5), now),
            Duration::from_secs(5)
        );
    }

    #[test]
    fn buttons_follow_java_and_cancel_denies() {
        let with = buttons(true);
        assert_eq!(with.len(), 3);
        assert_eq!(with[0].1, McpAnswer::Deny);
        assert_eq!(with[1].1, McpAnswer::AllowForSession);
        assert_eq!(with[2].1, McpAnswer::AllowOnce);
        assert_eq!(with[2].2, Tone::Primary);
        let without = buttons(false);
        assert_eq!(without.len(), 2);
        assert!(without.iter().all(|b| b.1 != McpAnswer::AllowForSession));
    }
}

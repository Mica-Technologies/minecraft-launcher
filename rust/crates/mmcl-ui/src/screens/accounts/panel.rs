// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `SignInPanel`: the shared sign-in card of the Login screen and the Add-account window.
//!
//! Left, the aside (brand row, heading, subtitle, the three points, "Stay signed in", the
//! failure line, the dismiss button). Right, the frame. Java embedded Microsoft's page in a
//! WebView there; in Rust the page runs in the `--msa-signin` helper window (started by the
//! engine), so the frame offers the sign-in ways and then shows the running flow:
//!
//! - web view / Windows account: a "finish in the Microsoft window" note and Cancel,
//! - device code: the code (copy button), the `microsoft.com/link` address (open button), a QR
//!   code with the code pre-filled, and a live countdown to the code's expiry,
//! - redeeming: the Xbox / Minecraft chain step the engine reports.
//!
//! The host listens for [`SignInPanelEvent`].

use std::time::{Duration, SystemTime};

use gpui::{
    AnyElement, App, ClickEvent, ClipboardItem, Context, EventEmitter, InteractiveElement as _,
    IntoElement, ParentElement, Render, SharedString, Styled, Subscription, Task, Window, div, img,
    prelude::FluentBuilder as _, px,
};
use gpui_component::button::{Button, ButtonVariants as _};
use mmcl_engine::{
    AccountView, EngineEvent, EngineResult, SignInEvent, SignInKind, SignInRequest, TaskAborted,
};
use mmcl_i18n::{t, tf};
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::theme::{ThemeExt as _, TypeStyleExt as _, hsla};
use mmcl_ui_kit::{Icon, LoadingIndicator, Switch};

use super::model::{self, Finished, FlowEnd, PanelState, Phase};
use super::qr::QrCodeView;
use crate::scale::u;
use crate::services::{self, UiEvent};

/// Which host the panel sits in (the Java constructor's keys plus the account behaviour).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SignInHost {
    /// The heading key.
    pub heading_key: &'static str,
    /// The subtitle key.
    pub subtitle_key: &'static str,
    /// The dismiss button's key.
    pub dismiss_key: &'static str,
    /// The login screen makes the new account the default; Add-account does not.
    pub make_default: bool,
}

impl SignInHost {
    /// The Login screen.
    pub const LOGIN: SignInHost = SignInHost {
        heading_key: "signIn.login.heading",
        subtitle_key: "signIn.login.subtitle",
        dismiss_key: "login.exitBtn",
        make_default: true,
    };

    /// The Add-account window.
    pub const ADD_ACCOUNT: SignInHost = SignInHost {
        heading_key: "account.add.title",
        subtitle_key: "account.add.hint",
        dismiss_key: "dialog.button.cancel",
        make_default: false,
    };
}

/// What the panel tells its host.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SignInPanelEvent {
    /// An account signed in (and was saved; the default too for the Login host).
    SignedIn(AccountView),
    /// The dismiss button was pressed (any running flow was cancelled first).
    Dismissed,
}

/// The sign-in card.
pub struct SignInPanel {
    host: SignInHost,
    state: PanelState,
    run: Option<Task<()>>,
    tick: Option<Task<()>>,
    _events: Option<Subscription>,
}

impl EventEmitter<SignInPanelEvent> for SignInPanel {}

/// The localization key of a sign-in way's button and its hint.
pub fn method_keys(kind: SignInKind) -> (&'static str, &'static str) {
    match kind {
        SignInKind::WebView => ("signIn.method.webView", "signIn.method.webView.hint"),
        SignInKind::DeviceCode => ("signIn.method.deviceCode", "signIn.method.deviceCode.hint"),
        SignInKind::Wam => ("signIn.method.wam", "signIn.method.wam.hint"),
    }
}

impl SignInPanel {
    /// A panel for `host`, offering the engine's sign-in ways (device code first in gamepad
    /// mode).
    pub fn new(host: SignInHost, cx: &mut Context<Self>) -> Self {
        let flows = services::try_engine(cx)
            .map(|e| e.sign_in_flows())
            .unwrap_or_default();
        let events = services::events(cx).map(|events| {
            cx.subscribe(&events, |this, _, ev: &UiEvent, cx| {
                this.on_engine_event(ev, cx)
            })
        });
        SignInPanel {
            host,
            state: PanelState::new(flows),
            run: None,
            tick: None,
            _events: events,
        }
    }

    /// The panel's state (read-only).
    pub fn state(&self) -> &PanelState {
        &self.state
    }

    /// "Stay signed in".
    pub fn remember(&self) -> bool {
        self.state.remember
    }

    /// Whether a flow runs (or is being cancelled).
    pub fn is_busy(&self) -> bool {
        self.state.is_busy()
    }

    /// Starts the default sign-in way (the Login screen does this on show in gamepad mode,
    /// where the device code is the default).
    pub fn start_default(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if let Some(kind) = self.state.default_flow() {
            self.start(kind, window, cx);
        }
    }

    /// Starts `kind` (ignored while a flow runs).
    pub fn start(&mut self, kind: SignInKind, window: &mut Window, cx: &mut Context<Self>) {
        let Some(generation) = self.state.start(kind) else {
            return;
        };
        let request = SignInRequest {
            kind,
            remember: self.state.remember,
            make_default: self.host.make_default,
            window_handle: if kind == SignInKind::Wam {
                native_window_handle(window)
            } else {
                None
            },
        };
        let make_default = self.host.make_default;
        let task = services::run(cx, move |engine| async move {
            let result = engine.sign_in(request).await;
            // Add-account: the toast goes out even when the window was closed while redeeming
            // (the account is added either way).
            if let Ok(account) = &result
                && !make_default
            {
                let name = if account.name.trim().is_empty() {
                    account.uuid.clone()
                } else {
                    account.name.clone()
                };
                mmcl_ui_kit::notify::success(
                    &t!("account.add.done.title"),
                    &tf!("account.add.done.body", name),
                );
            }
            result
        });
        let Some(task) = task else {
            self.finish(generation, Err(TaskAborted), cx);
            return;
        };
        self.run = Some(cx.spawn(async move |this, cx| {
            let outcome = task.await;
            let _ = this.update(cx, |this, cx| this.finish(generation, outcome, cx));
        }));
        cx.notify();
    }

    /// Cancels the running flow (Cancel button, dismiss, window closed, screen left).
    pub fn cancel(&mut self, cx: &mut Context<Self>) {
        if self.state.cancel() {
            if let Some(engine) = services::try_engine(cx) {
                engine.cancel_sign_in();
            }
            cx.notify();
        }
    }

    /// The dismiss button: cancels a waiting flow, then tells the host.
    pub fn dismiss(&mut self, cx: &mut Context<Self>) {
        self.cancel(cx);
        cx.emit(SignInPanelEvent::Dismissed);
    }

    fn finish(
        &mut self,
        generation: u64,
        outcome: Result<EngineResult<AccountView>, TaskAborted>,
        cx: &mut Context<Self>,
    ) {
        let end = match outcome {
            Ok(Ok(account)) => FlowEnd::SignedIn(account),
            Ok(Err(e)) if e.is_cancelled() => {
                tracing::debug!("{}", t!("log.login.userCancelled"));
                FlowEnd::Cancelled
            }
            Ok(Err(e)) => {
                let message = e.message();
                tracing::error!("{}", tf!("log.login.msLoginError", message.as_str()));
                tracing::error!("{}", t!("log.login.failed"));
                FlowEnd::Failed(message)
            }
            Err(TaskAborted) => {
                tracing::error!("{}", t!("log.login.failed"));
                FlowEnd::Failed(t!("signIn.failed").into_owned())
            }
        };
        self.run = None;
        self.tick = None;
        if let Some(Finished::SignedIn(account)) = self.state.finish(generation, end) {
            cx.emit(SignInPanelEvent::SignedIn(account));
        }
        cx.notify();
    }

    fn on_engine_event(&mut self, ev: &UiEvent, cx: &mut Context<Self>) {
        let UiEvent::Engine(EngineEvent::SignIn(ev)) = ev else {
            return;
        };
        let changed = match ev {
            SignInEvent::DeviceCode(prompt) => {
                let landed = self.state.on_device_code(prompt.clone());
                if landed {
                    self.start_countdown(cx);
                }
                landed
            }
            SignInEvent::Status { section, detail } => {
                let changed = self.state.on_status(section, detail);
                if changed {
                    self.tick = None;
                }
                changed
            }
        };
        if changed {
            cx.notify();
        }
    }

    /// Repaints once a second while a device code shows (the countdown).
    fn start_countdown(&mut self, cx: &mut Context<Self>) {
        self.tick = Some(cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(Duration::from_secs(1)).await;
                let showing = this
                    .update(cx, |this, cx| {
                        let showing = matches!(
                            this.state.phase,
                            Phase::Waiting {
                                kind: SignInKind::DeviceCode,
                                prompt: Some(_)
                            }
                        );
                        if showing {
                            cx.notify();
                        }
                        showing
                    })
                    .unwrap_or(false);
                if !showing {
                    break;
                }
            }
        }));
    }
}

/// The launcher window's native handle (HWND on Windows) for the account broker.
fn native_window_handle(window: &Window) -> Option<isize> {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    let handle = HasWindowHandle::window_handle(window).ok()?;
    match handle.as_raw() {
        RawWindowHandle::Win32(h) => Some(h.hwnd.get()),
        _ => None,
    }
}

// ---------------------------------------------------------------------------------------------
// Rendering

fn point_row(icon: Icon, title: &str, body: &str, cx: &App) -> impl IntoElement {
    let t = cx.tokens();
    div()
        .flex()
        .items_start()
        .gap(u(12.0))
        .child(
            div()
                .flex_none()
                .size(u(36.0))
                .rounded_full()
                .flex()
                .items_center()
                .justify_center()
                .bg(hsla(t.md_primary_container))
                .child(icon.svg(24.0).text_color(hsla(t.md_on_primary_container))),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(u(2.0))
                .child(
                    div()
                        .type_role(TypeRole::TitleSmall)
                        .text_color(hsla(t.color_text))
                        .child(title.to_string()),
                )
                .child(
                    div()
                        .type_role(TypeRole::BodySmall)
                        .text_color(hsla(t.color_text_muted))
                        .child(body.to_string()),
                ),
        )
}

impl SignInPanel {
    fn render_aside(&self, cx: &mut Context<Self>) -> AnyElement {
        let t = cx.tokens();
        let weak = cx.entity().downgrade();
        let remember = Switch::new("signin-remember")
            .label(t!("signIn.remember").into_owned())
            .checked(self.state.remember)
            .disabled(!self.state.remember_enabled())
            .on_change(move |on, _, cx| {
                let _ = weak.update(cx, |this, cx| {
                    if this.state.remember_enabled() {
                        this.state.remember = on;
                        cx.notify();
                    }
                });
            });
        let dismiss = Button::new("signin-dismiss")
            .label(t!(self.host.dismiss_key).into_owned())
            .min_w(u(96.0))
            .h(u(32.0))
            .on_click(cx.listener(|this, _: &ClickEvent, _, cx| this.dismiss(cx)));
        div()
            .flex_none()
            .w(u(330.0))
            .min_w(u(300.0))
            .max_w(u(360.0))
            .flex()
            .flex_col()
            .gap(u(14.0))
            .pt(u(32.0))
            .pr(u(28.0))
            .pb(u(28.0))
            .pl(u(32.0))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(u(10.0))
                    .child(img(mmcl_ui_kit::assets::APP_ICON).size(u(40.0)).flex_none())
                    .child(
                        div()
                            .type_role(TypeRole::LabelMedium)
                            .text_color(hsla(t.color_primary))
                            .child(t!("signIn.eyebrow").into_owned()),
                    ),
            )
            .child(
                div()
                    .type_role(TypeRole::HeadingH2)
                    .text_color(hsla(t.color_text))
                    .child(t!(self.host.heading_key).into_owned()),
            )
            .child(
                div()
                    .type_role(TypeRole::BodyMedium)
                    .text_color(hsla(t.color_text_muted))
                    .child(t!(self.host.subtitle_key).into_owned()),
            )
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(u(16.0))
                    .pt(u(8.0))
                    .child(point_row(
                        Icon::Security,
                        &t!("signIn.point.password.title"),
                        &t!("signIn.point.password.body"),
                        cx,
                    ))
                    .child(point_row(
                        Icon::Lock,
                        &t!("signIn.point.device.title"),
                        &t!("signIn.point.device.body"),
                        cx,
                    ))
                    .child(point_row(
                        Icon::People,
                        &t!("signIn.point.accounts.title"),
                        &t!("signIn.point.accounts.body"),
                        cx,
                    )),
            )
            .child(div().flex_1().min_h(u(12.0)))
            .child(remember)
            .child(
                div()
                    .type_role(TypeRole::BodySmall)
                    .text_color(hsla(t.color_text_subtle))
                    .child(t!("signIn.remember.hint").into_owned()),
            )
            .when_some(self.state.status.clone(), |el, status| {
                el.child(
                    div()
                        .id("signin-status")
                        .type_role(TypeRole::BodySmall)
                        .text_color(hsla(t.color_danger))
                        .child(status),
                )
            })
            .child(div().flex().child(dismiss))
            .into_any_element()
    }

    fn render_frame(&self, cx: &mut Context<Self>) -> AnyElement {
        match self.state.phase.clone() {
            Phase::Choose => self.render_chooser(cx),
            Phase::Waiting {
                kind: SignInKind::DeviceCode,
                prompt: Some(prompt),
            } => self.render_device_code(&prompt, cx),
            Phase::Waiting {
                kind: SignInKind::DeviceCode,
                prompt: None,
            } => busy_view("signIn.deviceCode.requesting", None, true, cx),
            Phase::Waiting {
                kind: SignInKind::WebView,
                ..
            } => busy_view("signIn.webView.waiting", None, true, cx),
            Phase::Waiting {
                kind: SignInKind::Wam,
                ..
            } => busy_view("signIn.wam.waiting", None, true, cx),
            Phase::Redeeming { detail } => busy_view("signIn.redeeming", detail, false, cx),
            Phase::Cancelling => busy_view("signIn.cancelling", None, false, cx),
        }
    }

    fn render_chooser(&self, cx: &mut Context<Self>) -> AnyElement {
        let t = cx.tokens();
        let mut col = centered_column().child(
            div()
                .type_role(TypeRole::TitleLarge)
                .text_color(hsla(t.color_text))
                .text_center()
                .child(t!("signIn.method.title").into_owned()),
        );
        if self.state.flows.is_empty() {
            return col
                .child(
                    div()
                        .max_w(u(320.0))
                        .text_center()
                        .text_color(hsla(t.color_text_muted))
                        .child(t!("signIn.method.none").into_owned()),
                )
                .into_any_element();
        }
        for (i, kind) in self.state.flows.iter().copied().enumerate() {
            let (label, hint) = method_keys(kind);
            let icon = match kind {
                SignInKind::WebView => Icon::OpenExternal,
                SignInKind::DeviceCode => Icon::Lock,
                SignInKind::Wam => Icon::Account,
            };
            let mut button = Button::new(SharedString::from(format!("signin-method-{i}")))
                .icon(icon)
                .label(t!(label).into_owned())
                .w(u(320.0))
                .h(u(40.0))
                .on_click(cx.listener(move |this, _: &ClickEvent, window, cx| {
                    this.start(kind, window, cx)
                }));
            if i == 0 {
                button = button.primary();
            }
            col = col.child(
                div()
                    .flex()
                    .flex_col()
                    .items_center()
                    .gap(u(4.0))
                    .child(button)
                    .child(
                        div()
                            .max_w(u(320.0))
                            .text_center()
                            .type_role(TypeRole::BodySmall)
                            .text_color(hsla(t.color_text_muted))
                            .child(t!(hint).into_owned()),
                    ),
            );
        }
        if let (Some(_), Some(kind)) = (&self.state.status, self.state.last_kind) {
            col = col.child(
                Button::new("signin-retry")
                    .primary()
                    .h(u(32.0))
                    .label(t!("signIn.retry").into_owned())
                    .on_click(cx.listener(move |this, _: &ClickEvent, window, cx| {
                        this.state.clear_status();
                        this.start(kind, window, cx);
                    })),
            );
        }
        col.into_any_element()
    }

    fn render_device_code(
        &self,
        prompt: &mmcl_engine::DeviceCodePrompt,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let t = cx.tokens();
        let left = model::remaining(prompt, SystemTime::now());
        let link = model::prefilled_link(prompt);
        let shown_link = model::display_link(&prompt.verification_uri);
        let code = prompt.user_code.clone();
        let open_link = link.clone();
        div()
            .size_full()
            .flex()
            .items_center()
            .justify_center()
            .gap(u(32.0))
            .p(u(24.0))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(u(14.0))
                    .max_w(u(340.0))
                    .child(
                        div()
                            .type_role(TypeRole::BodyLarge)
                            .text_color(hsla(t.color_text))
                            .child(tf!("signIn.deviceCode.instructions", shown_link.as_str())),
                    )
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(u(10.0))
                            .child(
                                div()
                                    .id("signin-device-code")
                                    .px(u(16.0))
                                    .py(u(10.0))
                                    .rounded(px(mmcl_theme::shape::MEDIUM))
                                    .bg(hsla(t.md_surface_container_high))
                                    .font_family(mmcl_ui_kit::fonts::MONO_FAMILY)
                                    .text_size(u(30.0))
                                    .font_weight(gpui::FontWeight::BOLD)
                                    .text_color(hsla(t.color_text))
                                    .child(code.clone()),
                            )
                            .child(
                                Button::new("signin-copy-code")
                                    .icon(Icon::Copy)
                                    .ghost()
                                    .tooltip(t!("signIn.deviceCode.copy").into_owned())
                                    .on_click(move |_: &ClickEvent, _, cx| {
                                        cx.write_to_clipboard(ClipboardItem::new_string(
                                            code.clone(),
                                        ));
                                    }),
                            ),
                    )
                    .child(
                        Button::new("signin-open-link")
                            .primary()
                            .icon(Icon::OpenExternal)
                            .h(u(36.0))
                            .label(t!("signIn.deviceCode.open").into_owned())
                            .on_click(move |_: &ClickEvent, _, cx| {
                                mmcl_ui_kit::open_external_url(cx, &open_link);
                            }),
                    )
                    .child(
                        div()
                            .id("signin-countdown")
                            .type_role(TypeRole::BodyMedium)
                            .text_color(hsla(if left.is_zero() {
                                t.color_danger
                            } else {
                                t.color_text_muted
                            }))
                            .child(tf!(
                                "signIn.deviceCode.expires",
                                model::format_countdown(left)
                            )),
                    )
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(u(8.0))
                            .child(LoadingIndicator::new("signin-device-wait").size(20.0))
                            .child(
                                div()
                                    .type_role(TypeRole::BodySmall)
                                    .text_color(hsla(t.color_text_muted))
                                    .child(t!("signIn.deviceCode.waiting").into_owned()),
                            ),
                    )
                    .child(div().flex().child(cancel_button(cx))),
            )
            .child(
                div()
                    .flex()
                    .flex_col()
                    .items_center()
                    .gap(u(8.0))
                    .child(QrCodeView::new(
                        &link,
                        200.0,
                        t!("signIn.deviceCode.qr.a11y").into_owned(),
                    ))
                    .child(
                        div()
                            .max_w(u(200.0))
                            .text_center()
                            .type_role(TypeRole::BodySmall)
                            .text_color(hsla(t.color_text_muted))
                            .child(t!("signIn.deviceCode.scan").into_owned()),
                    ),
            )
            .into_any_element()
    }
}

fn centered_column() -> gpui::Div {
    div()
        .size_full()
        .flex()
        .flex_col()
        .items_center()
        .justify_center()
        .gap(u(14.0))
        .p(u(24.0))
}

fn cancel_button(cx: &mut Context<SignInPanel>) -> Button {
    Button::new("signin-cancel")
        .h(u(32.0))
        .label(t!("dialog.button.cancel").into_owned())
        .on_click(cx.listener(|this, _: &ClickEvent, _, cx| this.cancel(cx)))
}

/// The overlay of a running step: indicator, text (wraps at 320), optional detail, Cancel.
fn busy_view(
    key: &str,
    detail: Option<String>,
    cancellable: bool,
    cx: &mut Context<SignInPanel>,
) -> AnyElement {
    let t = cx.tokens();
    centered_column()
        .child(LoadingIndicator::new("signin-busy").size(36.0))
        .child(
            div()
                .max_w(u(320.0))
                .text_center()
                .text_color(hsla(t.color_text_muted))
                .child(t!(key).into_owned()),
        )
        .when_some(detail, |el, detail| {
            el.child(
                div()
                    .max_w(u(320.0))
                    .text_center()
                    .type_role(TypeRole::BodySmall)
                    .text_color(hsla(t.color_text_subtle))
                    .child(detail),
            )
        })
        .when(cancellable, |el| el.child(cancel_button(cx)))
        .into_any_element()
}

impl Render for SignInPanel {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let aside = self.render_aside(cx);
        let frame = self.render_frame(cx);
        div()
            .id("signin-card")
            .w_full()
            .max_w(u(1040.0))
            .min_h(u(520.0))
            .flex()
            .rounded(px(mmcl_theme::shape::LARGE))
            .bg(hsla(t.color_surface_elevated))
            .border_1()
            .border_color(hsla(t.color_border))
            .shadow_lg()
            .child(aside)
            .child(
                div()
                    .flex_1()
                    .min_w(u(440.0))
                    .m(u(12.0))
                    .ml_0()
                    .rounded(px(mmcl_theme::shape::MEDIUM))
                    .bg(hsla(t.color_surface))
                    .overflow_hidden()
                    .child(frame),
            )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_panel_key_exists() {
        let mut keys = vec![
            "signIn.eyebrow",
            "signIn.remember",
            "signIn.remember.hint",
            "signIn.retry",
            "signIn.redeeming",
            "signIn.failed",
            "signIn.point.password.title",
            "signIn.point.password.body",
            "signIn.point.device.title",
            "signIn.point.device.body",
            "signIn.point.accounts.title",
            "signIn.point.accounts.body",
            "signIn.method.title",
            "signIn.method.none",
            "signIn.webView.waiting",
            "signIn.wam.waiting",
            "signIn.cancelling",
            "signIn.deviceCode.requesting",
            "signIn.deviceCode.instructions",
            "signIn.deviceCode.copy",
            "signIn.deviceCode.open",
            "signIn.deviceCode.expires",
            "signIn.deviceCode.waiting",
            "signIn.deviceCode.scan",
            "signIn.deviceCode.qr.a11y",
            "account.add.done.title",
            "account.add.done.body",
            "log.login.userCancelled",
            "log.login.msLoginError",
            "log.login.failed",
            "dialog.button.cancel",
        ];
        for host in [SignInHost::LOGIN, SignInHost::ADD_ACCOUNT] {
            keys.extend([host.heading_key, host.subtitle_key, host.dismiss_key]);
        }
        for kind in [SignInKind::WebView, SignInKind::DeviceCode, SignInKind::Wam] {
            let (a, b) = method_keys(kind);
            keys.extend([a, b]);
        }
        for key in keys {
            assert!(mmcl_i18n::english(key).is_some(), "{key}");
        }
    }

    #[test]
    fn hosts_differ_in_default_behaviour() {
        let (login, add) = (SignInHost::LOGIN, SignInHost::ADD_ACCOUNT);
        assert_ne!(login.make_default, add.make_default);
        assert!(std::hint::black_box(login).make_default);
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Add-account window (`AddAccountDialog.show(owner)`): a second window over the launcher
//! with the shared sign-in panel. One at a time: showing it again focuses the open one.
//!
//! The new account is added alongside the others and never becomes the default unless there
//! is none (the engine's `make_default = false`); a success toast follows from the engine side
//! even when the window was closed while redeeming.

use gpui::{
    AnyWindowHandle, App, AppContext as _, Bounds, Context, Entity, Global,
    InteractiveElement as _, IntoElement, ParentElement, Render, StatefulInteractiveElement as _,
    Styled, Subscription, Window, WindowBounds, WindowDecorations, WindowHandle, WindowKind,
    WindowOptions, div, px, size,
};
use gpui_component::{Root, TitleBar};
use mmcl_i18n::t;
use mmcl_ui_kit::theme::{ThemeExt as _, hsla};

use super::panel::{SignInHost, SignInPanel, SignInPanelEvent};
use crate::scale::u;

/// The window's size (px at 100 %).
pub const SIZE: (f32, f32) = (1000.0, 800.0);
/// The window's minimum size (px at 100 %).
pub const MIN_SIZE: (f32, f32) = (860.0, 680.0);

/// The open window, if any.
#[derive(Default)]
struct OpenAddAccount(Option<WindowHandle<Root>>);

impl Global for OpenAddAccount {}

/// The Add-account window's root view.
pub struct AddAccountWindow {
    panel: Entity<SignInPanel>,
    _panel_events: Subscription,
}

impl AddAccountWindow {
    fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let panel = cx.new(|cx| SignInPanel::new(SignInHost::ADD_ACCOUNT, cx));
        let sub = cx.subscribe_in(
            &panel,
            window,
            // Java: success, Cancel, and `callback.userCancelled()` (cancelled on Microsoft's
            // page) all close the window.
            |_, _, ev: &SignInPanelEvent, window, _| match ev {
                SignInPanelEvent::SignedIn(_)
                | SignInPanelEvent::Dismissed
                | SignInPanelEvent::CancelledOutside => window.remove_window(),
            },
        );
        // Java loaded the sign-in on show. The device code needs no extra window, so it starts at
        // once when it is the default (gamepad / handheld mode); the web view helper waits for a
        // click so no second window pops up unasked.
        let auto = panel.clone();
        cx.defer_in(window, move |_, window, cx| {
            auto.update(cx, |panel, cx| {
                if panel.state().default_flow() == Some(mmcl_engine::SignInKind::DeviceCode) {
                    panel.start_default(window, cx);
                }
            });
        });
        AddAccountWindow {
            panel,
            _panel_events: sub,
        }
    }

    /// Cancels a waiting flow (the window is closing).
    fn cancel(&self, cx: &mut App) {
        self.panel.update(cx, |panel, cx| panel.cancel(cx));
    }
}

impl Render for AddAccountWindow {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let entity = cx.entity().downgrade();
        div()
            .size_full()
            .flex()
            .flex_col()
            .bg(crate::theme::secondary_fill(cx))
            .text_color(hsla(t.color_text))
            .font_family(mmcl_ui_kit::fonts::UI_FAMILY)
            .child(
                TitleBar::new()
                    .bg(gpui::transparent_black())
                    .border_color(gpui::transparent_black())
                    .on_close_window(move |_, window, cx| {
                        let _ = entity.update(cx, |this, cx| this.cancel(cx));
                        window.remove_window();
                    })
                    .child(
                        div()
                            .text_size(px(12.0))
                            .text_color(hsla(t.color_text_muted))
                            .child(t!("account.add.title").into_owned()),
                    ),
            )
            .child(
                div()
                    .id("add-account-host")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .flex()
                    .items_center()
                    .justify_center()
                    .p(u(20.0))
                    .child(self.panel.clone()),
            )
    }
}

/// Shows the Add-account window over `owner` (the main window when `None`), or focuses the one
/// already open.
pub fn show_add_account(owner: Option<AnyWindowHandle>, cx: &mut App) {
    if let Some(open) = cx.try_global::<OpenAddAccount>().and_then(|o| o.0)
        && open
            .update(cx, |_, window, _| window.activate_window())
            .is_ok()
    {
        return;
    }
    let owner = owner.or_else(|| crate::shell::main_window_handle(cx));
    let display = owner.and_then(|h| {
        h.update(cx, |_, window, cx| window.display(cx).map(|d| d.id()))
            .ok()
            .flatten()
    });
    let scale = crate::scale::factor(cx);
    let window_size = size(px(SIZE.0 * scale), px(SIZE.1 * scale));
    let options = WindowOptions {
        window_bounds: Some(WindowBounds::Windowed(Bounds::centered(
            display,
            window_size,
            cx,
        ))),
        display_id: display,
        kind: WindowKind::Normal,
        is_resizable: true,
        window_min_size: Some(size(px(MIN_SIZE.0 * scale), px(MIN_SIZE.1 * scale))),
        window_background: crate::theme::secondary_window_background(),
        app_id: Some(
            mmcl_core::AppIdentity::current()
                .linux_package_name
                .to_string(),
        ),
        window_decorations: Some(WindowDecorations::Client),
        focus: true,
        show: true,
        ..TitleBar::window_options()
    };
    let mut view = None;
    let opened = cx.open_window(options, |window, cx| {
        let v = cx.new(|cx| AddAccountWindow::new(window, cx));
        view = Some(v.clone());
        cx.new(|cx| Root::new(v, window, cx))
    });
    let handle = match opened {
        Ok(h) => h,
        Err(e) => {
            tracing::error!(
                "{}",
                mmcl_i18n::tf!("log.accounts.addWindowFailed", e.to_string())
            );
            return;
        }
    };
    let _ = handle.update(cx, |_, window, cx| {
        window.set_window_title(&t!("account.add.title"));
        crate::theme::install_theme_on(window, cx);
        if let Some(view) = view.clone() {
            window.on_window_should_close(cx, move |_, cx| {
                view.update(cx, |this, cx| this.cancel(cx));
                true
            });
        }
    });
    cx.set_global(OpenAddAccount(Some(handle)));
}

#[cfg(test)]
mod tests {
    #[test]
    fn keys_exist() {
        for key in ["account.add.title", "log.accounts.addWindowFailed"] {
            assert!(mmcl_i18n::english(key).is_some(), "{key}");
        }
    }

    #[test]
    fn min_fits_inside_the_default_size() {
        assert!(super::MIN_SIZE.0 <= super::SIZE.0 && super::MIN_SIZE.1 <= super::SIZE.1);
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Login screen (`MCLauncherLoginGui`) and [`LoginHandle`] (`waitForLoginSuccess`).

use std::sync::Arc;

use futures::channel::oneshot;
use gpui::{
    App, AppContext as _, Context, Entity, Global, InteractiveElement as _, IntoElement,
    ParentElement, Pixels, Render, SharedString, Size, StatefulInteractiveElement as _, Styled,
    Subscription, Window, div, px, size,
};
use mmcl_engine::SignInKind;
use mmcl_i18n::t;
use mmcl_ui_kit::{AnnouncementBanner, CloseDecision, HelpTopic, Screen, ScreenId, ScreenRegistry};
use parking_lot::Mutex;

use super::SPECS;
use super::panel::{SignInHost, SignInPanel, SignInPanelEvent};
use crate::scale::u;
use crate::services;
use crate::shell::GuiHandle;

// ---------------------------------------------------------------------------------------------
// LoginHandle

#[derive(Default)]
struct Latch {
    released: bool,
    waiters: Vec<oneshot::Sender<()>>,
}

/// A one-shot latch released when the Login screen signs an account in. `Send + Sync + Clone`.
#[derive(Clone, Default)]
pub struct LoginHandle(Arc<Mutex<Latch>>);

impl LoginHandle {
    /// An unreleased latch.
    pub fn new() -> Self {
        LoginHandle::default()
    }

    /// Releases every waiter (now and later).
    pub fn release(&self) {
        let waiters = {
            let mut latch = self.0.lock();
            latch.released = true;
            std::mem::take(&mut latch.waiters)
        };
        for w in waiters {
            let _ = w.send(());
        }
    }

    /// Whether the latch was released.
    pub fn is_released(&self) -> bool {
        self.0.lock().released
    }

    /// Resolves once an account signed in on the Login screen this handle belongs to.
    pub async fn wait_for_login_success(&self) {
        let rx = {
            let mut latch = self.0.lock();
            if latch.released {
                return;
            }
            let (tx, rx) = oneshot::channel();
            latch.waiters.push(tx);
            rx
        };
        // A dropped sender (never: the latch keeps it until release) would also end the wait.
        let _ = rx.await;
    }
}

/// The handle of the Login screen built last.
#[derive(Default)]
struct CurrentLogin(Option<LoginHandle>);

impl Global for CurrentLogin {}

/// The latch of the Login screen showing (or built last).
pub fn current_login_handle(cx: &App) -> Option<LoginHandle> {
    cx.try_global::<CurrentLogin>().and_then(|c| c.0.clone())
}

/// `goToLoginGui`: shows the Login screen and returns its latch (`None` when the GUI is gone).
pub async fn go_to_login(gui: &GuiHandle) -> Option<LoginHandle> {
    if !gui.go_to(ScreenId::LOGIN).await {
        return None;
    }
    gui.run_with(|cx| current_login_handle(cx)).await.flatten()
}

// ---------------------------------------------------------------------------------------------
// The screen

/// The Login screen.
pub struct LoginScreen {
    panel: Entity<SignInPanel>,
    latch: LoginHandle,
    announcement: Option<SharedString>,
    _panel_events: Subscription,
}

impl LoginScreen {
    /// Builds the screen and makes its latch the current one.
    pub fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let panel = cx.new(|cx| SignInPanel::new(SignInHost::LOGIN, cx));
        let latch = LoginHandle::new();
        cx.set_global(CurrentLogin(Some(latch.clone())));
        let sub = cx.subscribe_in(&panel, window, |this, _, ev: &SignInPanelEvent, _, cx| {
            match ev {
                SignInPanelEvent::SignedIn(_) => this.latch.release(),
                // Exit: close the launcher (no confirmation, like Java's `closeApp`).
                SignInPanelEvent::Dismissed => cx.defer(crate::shell::close_app),
            }
        });
        let announcement = services::try_engine(cx)
            .map(|e| e.announcements().login)
            .filter(|s| !s.trim().is_empty())
            .map(SharedString::from);
        LoginScreen {
            panel,
            latch,
            announcement,
            _panel_events: sub,
        }
    }

    /// The latch released when an account signs in here.
    pub fn handle(&self) -> LoginHandle {
        self.latch.clone()
    }

    /// The sign-in panel.
    pub fn panel(&self) -> &Entity<SignInPanel> {
        &self.panel
    }
}

/// Registers the Login screen.
pub fn register(cx: &mut App) {
    ScreenRegistry::register_view(cx, ScreenId::LOGIN, |_req, window, cx| {
        LoginScreen::new(window, cx)
    });
}

impl Screen for LoginScreen {
    fn id(&self) -> ScreenId {
        ScreenId::LOGIN
    }

    fn title(&self, _cx: &App) -> SharedString {
        t!(SPECS[0].title_key).into_owned().into()
    }

    fn help_topic(&self) -> HelpTopic {
        HelpTopic::Login
    }

    fn allows_toolbar_navigation(&self) -> bool {
        false
    }

    fn min_size(&self) -> Size<Pixels> {
        size(px(SPECS[0].min.0), px(SPECS[0].min.1))
    }

    fn pref_size(&self) -> Option<Size<Pixels>> {
        SPECS[0].pref.map(|(w, h)| size(px(w), px(h)))
    }

    fn has_own_help_button(&self) -> bool {
        false
    }

    fn after_show(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        services::backend(cx).set_menu_presence(&t!("login.discord.loggingIn"));
        // The device code needs no extra window, so it starts at once when it is the default
        // (gamepad / handheld mode). The web view helper opens only on a click, so a window
        // never pops up over the launcher unasked.
        self.panel.update(cx, |panel, cx| {
            if panel.state().default_flow() == Some(SignInKind::DeviceCode) {
                panel.start_default(window, cx);
            }
        });
    }

    fn cleanup(&mut self, cx: &mut Context<Self>) {
        // Leave nothing running once the screen is gone (Java loaded about:blank).
        self.panel.update(cx, |panel, cx| panel.cancel(cx));
    }

    fn on_close_requested(
        &mut self,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) -> CloseDecision {
        // Java: close fires the dismiss button (Exit). Cancel the flow; the shell closes.
        self.panel.update(cx, |panel, cx| panel.cancel(cx));
        CloseDecision::Proceed
    }
}

impl Render for LoginScreen {
    fn render(&mut self, _window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        div()
            .size_full()
            .flex()
            .flex_col()
            .child(AnnouncementBanner::new(
                "login-announcement",
                self.announcement.clone(),
            ))
            .child(
                div()
                    .id("login-host")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .flex()
                    .items_center()
                    .justify_center()
                    .pt(u(28.0))
                    .px(u(32.0))
                    .pb(u(32.0))
                    .child(self.panel.clone()),
            )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn latch_releases_waiters_before_and_after() {
        let h = LoginHandle::new();
        let waiter = {
            let h = h.clone();
            std::thread::spawn(move || futures::executor::block_on(h.wait_for_login_success()))
        };
        // Give the waiter a moment to register (it also works if it registers late).
        std::thread::sleep(std::time::Duration::from_millis(20));
        assert!(!h.is_released());
        h.release();
        waiter.join().unwrap();
        assert!(h.is_released());
        futures::executor::block_on(h.wait_for_login_success());
    }

    #[test]
    fn handle_is_send_sync() {
        fn check<T: Send + Sync + Clone>() {}
        check::<LoginHandle>();
    }

    #[test]
    fn login_keys_exist() {
        for key in [
            "window.title.login",
            "login.discord.loggingIn",
            "login.exitBtn",
        ] {
            assert!(mmcl_i18n::english(key).is_some(), "{key}");
        }
    }
}

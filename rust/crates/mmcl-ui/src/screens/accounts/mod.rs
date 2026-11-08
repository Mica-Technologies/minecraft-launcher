// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Sign-in and accounts (gui-shell spec section 5).
//!
//! | Module | Java origin | What |
//! |---|---|---|
//! | [`model`] | `AccountListModel`, `SignInPanel` state | account rows, switcher actions, device code countdown, the panel's state machine (pure) |
//! | [`panel`] | `SignInPanel`, `MicrosoftSignIn` | the shared sign-in card: web view helper, device code (code, link, QR, countdown), Windows account |
//! | [`qr`] | (new) | the device code QR code |
//! | [`login`] | `MCLauncherLoginGui` | the Login screen, [`LoginHandle`] |
//! | [`add_account`] | `AddAccountDialog` | the Add-account window, [`show_add_account`] |
//! | [`switcher`] | `AccountSwitcherMenu` | the navbar account chip and menu, make default, confirmed sign-out |
//!
//! Avatars are the kit's [`mmcl_ui_kit::Avatar`] / [`mmcl_ui_kit::Avatars`] (`AvatarImages`).
//! The callback parsing (`MicrosoftSignIn.parseCallback`) lives in `mmcl-auth`, which the
//! web view helper flow uses.

use gpui::App;
use mmcl_ui_kit::{HelpTopic, ScreenId};

use super::placeholder::ScreenSpec;

pub mod add_account;
pub mod login;
pub mod model;
pub mod panel;
pub mod qr;
pub mod switcher;

pub use add_account::show_add_account;
pub use login::{LoginHandle, LoginScreen, current_login_handle, go_to_login};
pub use model::{AccountRow, SwitcherAction, account_rows};
pub use panel::{SignInHost, SignInPanel, SignInPanelEvent};
pub use switcher::{
    account_chip, confirm_sign_out, current_rows, make_default, open_account_settings,
    show_switcher,
};

/// The screens this module provides.
pub const SPECS: [ScreenSpec; 1] = [ScreenSpec {
    id: ScreenId::LOGIN,
    title_key: "window.title.login",
    help: HelpTopic::Login,
    allows_navigation: false,
    min: (900.0, 760.0),
    pref: Some((1120.0, 860.0)),
}];

/// Registers the Login screen and opens the account switcher from Home's avatar / name chip
/// (`MCLauncherMainGui` -> `AccountSwitcherMenu.show`). `mmcl-app` may install its own
/// switcher later; the last installed wins.
pub fn register(cx: &mut App) {
    login::register(cx);
    super::home_browse::set_account_switcher_handler(cx, switcher::show_switcher);
}

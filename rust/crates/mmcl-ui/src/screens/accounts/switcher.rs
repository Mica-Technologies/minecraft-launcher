// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The account switcher (`AccountSwitcherMenu`): the navbar's account chip (avatar + name of
//! the default account) opening a menu of every account, plus the account actions the Settings
//! saved-accounts list shares (make default, sign out with its confirmation).

use gpui::{
    App, AsyncApp, ClickEvent, IntoElement, ParentElement, SharedString, Styled, Window, div,
};
use gpui_component::button::{Button, ButtonVariants as _};
use gpui_component::menu::{DropdownMenu as _, PopupMenu, PopupMenuItem};
use mmcl_i18n::{t, tf};
use mmcl_ui_kit::theme::{ThemeExt as _, hsla};
use mmcl_ui_kit::{Avatar, ScreenId, ScreenRequest};

use super::add_account::show_add_account;
use super::model::{AccountRow, SwitcherAction, account_rows};
use crate::dialogs::{self, Answer, Question};
use crate::scale::u;
use crate::services;

/// The rows the switcher and the Settings list show (no engine: none).
pub fn current_rows(cx: &App) -> Vec<AccountRow> {
    services::try_engine(cx)
        .map(|e| account_rows(&e.accounts()))
        .unwrap_or_default()
}

/// Opens Settings on the Account category ("Manage accounts", the chip's default action).
pub fn open_account_settings(cx: &mut App) {
    crate::shell::navigate_gated(cx, ScreenRequest::with_payload(ScreenId::SETTINGS, 0usize));
}

/// Makes `uuid` the default account (written on the engine's runtime; listeners repaint).
pub fn make_default(cx: &mut App, uuid: String) {
    let Some(task) = services::run(cx, move |e| async move { e.set_default_account(&uuid) }) else {
        return;
    };
    cx.spawn(async move |_| {
        if let Ok(Err(e)) = task.await {
            tracing::warn!("{}", tf!("log.accounts.makeDefaultFailed", e.message()));
        }
    })
    .detach();
}

/// `confirmSignOut`: asks, then signs `uuid` out. Signing out the last account asks first when
/// games run, then restarts into the Login screen.
pub fn confirm_sign_out(cx: &mut App, uuid: String, name: String) {
    cx.spawn(async move |cx: &mut AsyncApp| {
        let q = Question::new(
            t!("settings.accounts.confirmSignOut.title"),
            tf!("settings.accounts.confirmSignOut.header", name.as_str()),
            t!("settings.accounts.confirmSignOut.body"),
            t!("settings.accounts.signOut"),
            t!("dialog.button.cancel"),
        );
        if dialogs::ask(cx, &q, None).await != Answer::Primary {
            return;
        }
        let Some(engine) = cx.update(|cx| services::try_engine(cx)) else {
            return;
        };
        let last = engine.accounts().len() <= 1;
        if last && !crate::shell::confirm_restart_while_games_run(cx).await {
            return;
        }
        let task = {
            let engine = engine.clone();
            let uuid = uuid.clone();
            engine
                .clone()
                .spawn(async move { engine.sign_out(&uuid).await })
        };
        match task.await {
            Ok(Ok(())) => {}
            Ok(Err(e)) => {
                tracing::warn!("{}", tf!("log.accounts.signOutFailed", e.message()));
                return;
            }
            Err(_) => return,
        }
        if engine.accounts().is_empty() {
            cx.update(crate::shell::restart_app);
        }
    })
    .detach();
}

fn account_item(row: AccountRow) -> PopupMenuItem {
    let label: SharedString = row.switcher_label().into();
    let uuid = row.uuid.clone();
    let is_default = row.is_default;
    let item = PopupMenuItem::element(move |_, cx| {
        let t = cx.tokens();
        div()
            .flex()
            .items_center()
            .gap(u(6.0))
            .child(
                div()
                    .min_w(u(14.0))
                    .text_color(hsla(t.color_primary))
                    .child(if is_default { "\u{2713}" } else { "" }),
            )
            .child(Avatar::new(
                SharedString::from(format!("switcher-avatar-{uuid}")),
                Some(uuid.clone()),
                20.0,
            ))
            .child(label.clone())
    });
    match row.switcher_action() {
        SwitcherAction::None => item,
        SwitcherAction::SignInAgain => item.on_click(|_: &ClickEvent, window: &mut Window, cx| {
            show_add_account(Some(window.window_handle()), cx)
        }),
        SwitcherAction::MakeDefault(uuid) => {
            item.on_click(move |_: &ClickEvent, _, cx| make_default(cx, uuid.clone()))
        }
    }
}

/// Fills the switcher menu: header, one item per account, separator, add, manage.
pub fn build_menu(menu: PopupMenu, cx: &App) -> PopupMenu {
    let mut menu = menu.item(PopupMenuItem::label(
        t!("account.switcher.header").into_owned(),
    ));
    for row in current_rows(cx) {
        menu = menu.item(account_item(row));
    }
    menu.separator()
        .item(
            PopupMenuItem::new(t!("account.switcher.addAccount").into_owned()).on_click(
                |_: &ClickEvent, window: &mut Window, cx| {
                    show_add_account(Some(window.window_handle()), cx)
                },
            ),
        )
        .item(
            PopupMenuItem::new(t!("account.switcher.manage").into_owned())
                .on_click(|_: &ClickEvent, _, cx| open_account_settings(cx)),
        )
}

/// The navbar's account chip: the default account's 28 px avatar and name (`Player` without
/// one); clicking opens the switcher menu below it.
pub fn account_chip(id: &'static str, cx: &App) -> impl IntoElement {
    let account = services::try_engine(cx).and_then(|e| e.default_account());
    let name = account
        .as_ref()
        .map(|a| {
            if a.name.trim().is_empty() {
                a.uuid.clone()
            } else {
                a.name.clone()
            }
        })
        .unwrap_or_else(|| t!("main.navbar.player").into_owned());
    let uuid = account.map(|a| a.uuid);
    Button::new(id)
        .ghost()
        .tooltip(t!("account.switcher.tooltip").into_owned())
        .child(
            div()
                .flex()
                .items_center()
                .gap(u(8.0))
                .child(Avatar::new(
                    SharedString::from(format!("{id}-avatar")),
                    uuid,
                    28.0,
                ))
                .child(div().max_w(u(160.0)).truncate().child(name)),
        )
        .dropdown_menu(|menu, _, cx| build_menu(menu, cx))
}

#[cfg(test)]
mod tests {
    #[test]
    fn keys_exist() {
        for key in [
            "settings.accounts.confirmSignOut.title",
            "settings.accounts.confirmSignOut.header",
            "settings.accounts.confirmSignOut.body",
            "settings.accounts.signOut",
            "main.navbar.player",
            "main.navbar.avatar.a11y",
            "account.switcher.tooltip",
            "log.accounts.makeDefaultFailed",
            "log.accounts.signOutFailed",
        ] {
            assert!(mmcl_i18n::english(key).is_some(), "{key}");
        }
    }
}

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The account switcher (`AccountSwitcherMenu`): the navbar's account chip (avatar + name of
//! the default account) opening a menu of every account, plus the account actions the Settings
//! saved-accounts list shares (make default, sign out with its confirmation).

use gpui::{
    App, AsyncApp, ClickEvent, InteractiveElement as _, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement as _, Styled, Window, div, prelude::FluentBuilder as _,
};
use gpui_component::WindowExt as _;
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

/// The switcher popup's width (px at 100 %).
pub const SWITCHER_WIDTH: f32 = 340.0;
/// How far below the window top the popup sits (px at 100 %): just under the nav bar, where
/// the Home chip that opens it lives.
pub const SWITCHER_TOP: f32 = 56.0;

/// What a switcher entry does when picked.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Pick {
    Account(SwitcherAction),
    AddAccount,
    Manage,
}

fn run_pick(pick: Pick, window: &mut Window, cx: &mut App) {
    window.close_dialog(cx);
    match pick {
        Pick::Account(SwitcherAction::None) => {}
        Pick::Account(SwitcherAction::MakeDefault(uuid)) => make_default(cx, uuid),
        Pick::Account(SwitcherAction::SignInAgain) | Pick::AddAccount => {
            show_add_account(Some(window.window_handle()), cx)
        }
        Pick::Manage => open_account_settings(cx),
    }
}

fn pick_row(
    id: gpui::ElementId,
    pick: Pick,
    leading: gpui::AnyElement,
    text: SharedString,
    cx: &App,
) -> gpui::AnyElement {
    let t = cx.tokens();
    let hover = hsla(t.md_state_hover_on_surface);
    let active = pick != Pick::Account(SwitcherAction::None);
    div()
        .id(id)
        .w_full()
        .flex()
        .items_center()
        .gap(u(8.0))
        .px(u(10.0))
        .py(u(6.0))
        .rounded(u(8.0))
        .text_color(hsla(t.color_text))
        .when(active, |d| d.cursor_pointer().hover(move |s| s.bg(hover)))
        .child(leading)
        .child(div().flex_1().min_w_0().truncate().child(text))
        .when(active, |d| {
            d.on_click(move |_: &ClickEvent, window, cx| run_pick(pick.clone(), window, cx))
        })
        .into_any_element()
}

/// The switcher's content: one entry per account (tick on the default, avatar, name or the
/// "sign in again" hint), a separator, Add another account, Manage accounts.
fn switcher_list(rows: Vec<AccountRow>, cx: &App) -> impl IntoElement {
    let t = cx.tokens();
    let mut col = div().w_full().flex().flex_col().gap(u(2.0));
    for (i, row) in rows.into_iter().enumerate() {
        let tick = div()
            .min_w(u(14.0))
            .text_color(hsla(t.color_primary))
            .child(if row.is_default { "\u{2713}" } else { "" });
        let leading = div()
            .flex()
            .items_center()
            .gap(u(6.0))
            .child(tick)
            .child(Avatar::new(
                SharedString::from(format!("switcher-dlg-avatar-{}", row.uuid)),
                Some(row.uuid.clone()),
                24.0,
            ))
            .into_any_element();
        let text: SharedString = row.switcher_label().into();
        col = col.child(pick_row(
            ("switcher-account", i).into(),
            Pick::Account(row.switcher_action()),
            leading,
            text,
            cx,
        ));
    }
    let spacer = || div().min_w(u(14.0)).into_any_element();
    col.child(
        div()
            .my(u(4.0))
            .h(gpui::px(1.0))
            .w_full()
            .bg(hsla(t.color_border)),
    )
    .child(pick_row(
        "switcher-add".into(),
        Pick::AddAccount,
        spacer(),
        t!("account.switcher.addAccount").into_owned().into(),
        cx,
    ))
    .child(pick_row(
        "switcher-manage".into(),
        Pick::Manage,
        spacer(),
        t!("account.switcher.manage").into_owned().into(),
        cx,
    ))
}

/// `AccountSwitcherMenu.show`: the switcher over `window`, opened from Home's avatar / name
/// chip. The Home chip belongs to another module and hands over only the window, so the menu
/// is a light popup just below the nav bar (dismissed by a click outside or Escape) rather
/// than a dropdown anchored to the chip. Without an in-window root it opens Manage accounts.
pub fn show_switcher(window: &mut Window, cx: &mut App) {
    if !matches!(window.root::<gpui_component::Root>(), Some(Some(_))) {
        open_account_settings(cx);
        return;
    }
    window.open_dialog(cx, |dialog, _, cx| {
        // Re-read on every rebuild, so a change while open (a renewal) shows at once.
        let rows = current_rows(cx);
        dialog
            .title(SharedString::from(
                t!("account.switcher.header").into_owned(),
            ))
            .w(crate::scale::scaled_px(cx, SWITCHER_WIDTH))
            .margin_top(crate::scale::scaled_px(cx, SWITCHER_TOP))
            .keyboard(true)
            .overlay_closable(true)
            .child(switcher_list(rows, cx))
    });
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

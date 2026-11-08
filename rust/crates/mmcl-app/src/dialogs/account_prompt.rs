// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The account questions before a launch (Java `LauncherCore.resolveLaunchUser`) and the whole
//! GUI Play flow built on them.
//!
//! - The pack's chosen account was signed out: "Play as {default}?" Yes clears the pack's
//!   override (it can never apply again) and goes on with the default account.
//! - The account must sign in again: "Sign in again" opens the Add-account window; the launch
//!   stops either way.
//! - No account at all: an error dialog.
//!
//! [`play`] runs these, then the mod-conflict dialog, then the engine's launch, and opens the
//! new game in the Running Games window. A launch for one account only (`account_uuid`, the MCP
//! path) is refused rather than asked about, as in Java.

use gpui::{AnyWindowHandle, App, AsyncApp};
use mmcl_auth::Problem;
use mmcl_engine::{
    AccountStatus, AccountView, Engine, EngineError, LaunchOptions, LaunchRefusal, PackId,
};
use mmcl_i18n::{t, tf};
use mmcl_ui::Question;
use mmcl_ui_kit::Answer;

/// Whether the launch goes on after the account check.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AccountStep {
    Go,
    Stop,
}

/// The question (or message) a blocked launch needs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AccountPrompt {
    /// The pack's account is gone: offer the default account.
    UseDefault {
        pack_name: String,
        default_name: String,
    },
    /// The account has to sign in again.
    SignInAgain { account_name: String },
    /// Nothing is signed in (or the forced account is gone): an error.
    NoAccount,
    /// Not an account problem: report the error as it is.
    Other,
}

/// Plans the prompt for `err` (Java's switch on the resolver's problem). `forced` marks a
/// launch for one named account, which is refused instead of asked about.
pub fn plan(
    err: &EngineError,
    forced: bool,
    pack_name: &str,
    default_account: Option<&AccountView>,
    blocked_account_name: &str,
) -> AccountPrompt {
    match err {
        EngineError::Account(Problem::OverrideMissing) => match default_account {
            Some(d) if !forced => AccountPrompt::UseDefault {
                pack_name: pack_name.to_string(),
                default_name: display(d),
            },
            _ => AccountPrompt::NoAccount,
        },
        EngineError::Account(Problem::NeedsSignIn) => AccountPrompt::SignInAgain {
            account_name: blocked_account_name.to_string(),
        },
        EngineError::Account(Problem::NoAccount) | EngineError::AccountNotFound(_) => {
            AccountPrompt::NoAccount
        }
        _ => AccountPrompt::Other,
    }
}

fn display(a: &AccountView) -> String {
    if a.name.trim().is_empty() {
        a.uuid.clone()
    } else {
        a.name.clone()
    }
}

/// The name of the account a launch of `id` would use that has to sign in again: the pack's
/// override when set, else the default.
pub fn blocked_account_name(
    accounts: &[AccountView],
    override_uuid: Option<&str>,
    forced: Option<&str>,
) -> String {
    let wanted = forced.or(override_uuid);
    let pick = match wanted {
        Some(u) => accounts.iter().find(|a| a.uuid == u),
        None => accounts.iter().find(|a| a.is_default),
    };
    pick.or_else(|| {
        accounts
            .iter()
            .find(|a| a.status == AccountStatus::NeedsSignIn)
    })
    .map(display)
    .unwrap_or_default()
}

/// The questions as dialog text: `(title, header, body, primary, secondary)`.
pub fn question_for(prompt: &AccountPrompt) -> Option<Question> {
    match prompt {
        AccountPrompt::UseDefault {
            pack_name,
            default_name,
        } => Some(Question::new(
            t!("launch.account.overrideMissing.title"),
            t!("launch.account.overrideMissing.header"),
            tf!("launch.account.overrideMissing.body", pack_name.as_str()),
            tf!(
                "launch.account.overrideMissing.useDefault",
                default_name.as_str()
            ),
            t!("dialog.button.cancel"),
        )),
        AccountPrompt::SignInAgain { account_name } => Some(Question::new(
            t!("launch.account.needsSignIn.title"),
            tf!("launch.account.needsSignIn.header", account_name.as_str()),
            t!("launch.account.needsSignIn.body"),
            t!("settings.accounts.signInAgain"),
            t!("dialog.button.cancel"),
        )),
        AccountPrompt::NoAccount | AccountPrompt::Other => None,
    }
}

/// Shows a refused launch: a pack that already runs opens its Running Games tab, anything else
/// is an error dialog.
pub async fn report_launch_error(
    engine: &Engine,
    id: &PackId,
    err: EngineError,
    owner: Option<AnyWindowHandle>,
    cx: &mut AsyncApp,
) {
    if let EngineError::LaunchRefused(LaunchRefusal::PackAlreadyRunning { .. }) = err
        && let Some(session) = engine.active_session_for_pack(id)
    {
        cx.update(|cx| mmcl_running_games::show_session(cx, session.id));
        return;
    }
    if matches!(
        err,
        EngineError::Account(Problem::NoAccount) | EngineError::AccountNotFound(_)
    ) {
        tracing::error!("{}", t!("log.launcherCore.noLaunchAccount"));
        mmcl_ui::dialogs::error(cx, &t!("launch.account.none"), owner).await;
        return;
    }
    mmcl_ui::dialogs::error(cx, &err.message(), owner).await;
}

/// The account check before a launch: asks what Java asked, fixes what the user agreed to, and
/// says whether to go on.
pub async fn resolve_launch_account(
    engine: &Engine,
    id: &PackId,
    forced: Option<&str>,
    owner: Option<AnyWindowHandle>,
    cx: &mut AsyncApp,
) -> AccountStep {
    let Some(err) = engine.why_launch_blocked(id, forced) else {
        return AccountStep::Go;
    };
    let pack_name = engine.pack(id).map(|p| p.friendly_name).unwrap_or_default();
    let accounts = engine.accounts();
    let default = engine.default_account();
    let override_uuid = engine.config().account_override_for_pack(id.as_str());
    let blocked = blocked_account_name(&accounts, override_uuid.as_deref(), forced);
    let prompt = plan(
        &err,
        forced.is_some(),
        &pack_name,
        default.as_ref(),
        &blocked,
    );
    match &prompt {
        AccountPrompt::UseDefault { .. } => {
            tracing::info!("{}", t!("log.launcherCore.overrideAccountMissing"));
            let Some(q) = question_for(&prompt) else {
                return AccountStep::Stop;
            };
            if mmcl_ui::dialogs::ask(cx, &q, owner).await != Answer::Primary {
                return AccountStep::Stop;
            }
            if let Err(e) = engine.set_pack_account_override(id, None) {
                mmcl_ui::dialogs::error(cx, &e.message(), owner).await;
                return AccountStep::Stop;
            }
            // Anything else still in the way (a running pack, a busy account) is reported.
            match engine.why_launch_blocked(id, None) {
                None => AccountStep::Go,
                Some(e) => {
                    report_launch_error(engine, id, e, owner, cx).await;
                    AccountStep::Stop
                }
            }
        }
        AccountPrompt::SignInAgain { .. } => {
            tracing::info!("{}", t!("log.launcherCore.launchAccountNeedsSignIn"));
            if let Some(q) = question_for(&prompt)
                && mmcl_ui::dialogs::ask(cx, &q, owner).await == Answer::Primary
            {
                cx.update(|cx| mmcl_ui::screens::accounts::show_add_account(owner, cx));
            }
            AccountStep::Stop
        }
        AccountPrompt::NoAccount | AccountPrompt::Other => {
            report_launch_error(engine, id, err, owner, cx).await;
            AccountStep::Stop
        }
    }
}

/// The whole GUI Play: account check, mod-conflict dialog, the engine's launch, then the new
/// session in the Running Games window.
pub async fn play(engine: Engine, id: PackId, options: LaunchOptions, cx: &mut AsyncApp) {
    let owner = cx.update(|cx| mmcl_ui::shell::main_window_handle(cx));
    let forced = options.account_uuid.clone();
    if resolve_launch_account(&engine, &id, forced.as_deref(), owner, cx).await == AccountStep::Stop
    {
        return;
    }
    if !super::mod_conflict::confirm_mod_conflicts(&engine, &id, owner, cx).await {
        return;
    }
    let e = engine.clone();
    let launch_id = id.clone();
    let result = engine
        .spawn(async move { e.launch(&launch_id, options).await })
        .await;
    match result {
        Ok(Ok(session)) => cx.update(|cx| mmcl_running_games::show_session(cx, session)),
        Ok(Err(err)) => report_launch_error(&engine, &id, err, owner, cx).await,
        Err(_) => {}
    }
}

/// [`play`] on the GUI executor (the `set_play_handler` hook shape).
pub fn start_play(engine: Engine, id: PackId, options: LaunchOptions, cx: &mut App) {
    cx.spawn(async move |cx| play(engine, id, options, cx).await)
        .detach();
}

#[cfg(test)]
mod tests {
    use super::*;

    fn account(uuid: &str, name: &str, default: bool, status: AccountStatus) -> AccountView {
        AccountView {
            uuid: uuid.into(),
            name: name.into(),
            status,
            is_default: default,
            remembered: true,
            last_used_ms: 0,
        }
    }

    #[test]
    fn a_missing_override_offers_the_default() {
        let d = account("u1", "Alex", true, AccountStatus::Ready);
        let p = plan(
            &EngineError::Account(Problem::OverrideMissing),
            false,
            "Pack",
            Some(&d),
            "",
        );
        assert_eq!(
            p,
            AccountPrompt::UseDefault {
                pack_name: "Pack".into(),
                default_name: "Alex".into()
            }
        );
        let q = question_for(&p).unwrap();
        assert_eq!(q.primary, "Play as Alex");
    }

    #[test]
    fn a_missing_override_without_default_or_when_forced_is_refused() {
        let d = account("u1", "", true, AccountStatus::Ready);
        let err = EngineError::Account(Problem::OverrideMissing);
        assert_eq!(plan(&err, false, "P", None, ""), AccountPrompt::NoAccount);
        assert_eq!(
            plan(&err, true, "P", Some(&d), ""),
            AccountPrompt::NoAccount
        );
        // A nameless default shows its UUID.
        assert_eq!(
            plan(&err, false, "P", Some(&d), ""),
            AccountPrompt::UseDefault {
                pack_name: "P".into(),
                default_name: "u1".into()
            }
        );
    }

    #[test]
    fn needs_sign_in_names_the_account_and_uses_sign_in_again() {
        let p = plan(
            &EngineError::Account(Problem::NeedsSignIn),
            false,
            "P",
            None,
            "Steve",
        );
        assert_eq!(
            p,
            AccountPrompt::SignInAgain {
                account_name: "Steve".into()
            }
        );
        let q = question_for(&p).unwrap();
        assert_eq!(q.primary, t!("settings.accounts.signInAgain"));
        assert!(q.header.contains("Steve"));
    }

    #[test]
    fn other_errors_are_reported_not_asked() {
        let p = plan(&EngineError::Cancelled, false, "P", None, "");
        assert_eq!(p, AccountPrompt::Other);
        assert!(question_for(&p).is_none());
        assert_eq!(
            plan(
                &EngineError::Account(Problem::NoAccount),
                false,
                "P",
                None,
                ""
            ),
            AccountPrompt::NoAccount
        );
    }

    #[test]
    fn the_blocked_account_is_the_override_then_the_default() {
        let accounts = vec![
            account("a", "Alex", true, AccountStatus::Ready),
            account("b", "Steve", false, AccountStatus::NeedsSignIn),
        ];
        assert_eq!(blocked_account_name(&accounts, Some("b"), None), "Steve");
        assert_eq!(blocked_account_name(&accounts, None, None), "Alex");
        assert_eq!(blocked_account_name(&accounts, None, Some("b")), "Steve");
        assert_eq!(blocked_account_name(&accounts, Some("gone"), None), "Steve");
        assert_eq!(blocked_account_name(&[], None, None), "");
    }
}

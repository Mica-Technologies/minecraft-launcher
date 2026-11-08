// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The GUI's Play (core-config-i18n §6.5, Java `LauncherCore.play`): account questions, the
//! mod-conflict dialog, then the engine's launch; the new session opens in the Running Games
//! window. The engine owns admission, the RAM gate, the steps and the game's life.

use gpui::{App, AsyncApp};
use mmcl_auth::Problem;
use mmcl_engine::{Engine, EngineError, LaunchOptions, LaunchRefusal, ModConflictView, PackId};
use mmcl_i18n::{t, tf};
use mmcl_ui::Question;
use mmcl_ui_kit::dialogs::Answer;

/// The Play hook: runs [`play`] on the GUI executor.
pub fn start(engine: Engine, id: PackId, cx: &mut App) {
    cx.spawn(async move |cx| play(engine, id, cx).await)
        .detach();
}

/// The body of the mod-conflict dialog: `• title\n  description`, blank lines between.
pub fn conflict_body(conflicts: &[ModConflictView]) -> String {
    conflicts
        .iter()
        .map(|c| format!("\u{2022} {}\n  {}", c.title, c.description))
        .collect::<Vec<_>>()
        .join("\n\n")
}

/// What to do after the account check.
enum AccountStep {
    Go,
    Stop,
}

async fn check_account(engine: &Engine, id: &PackId, cx: &mut AsyncApp) -> AccountStep {
    let Some(err) = engine.why_launch_blocked(id, None) else {
        return AccountStep::Go;
    };
    match err {
        EngineError::Account(Problem::OverrideMissing) => {
            let Some(default) = engine.default_account() else {
                mmcl_ui::dialogs::error(cx, &t!("launch.account.none"), None).await;
                return AccountStep::Stop;
            };
            let pack_name = engine.pack(id).map(|p| p.friendly_name).unwrap_or_default();
            let q = Question::new(
                t!("launch.account.overrideMissing.title"),
                t!("launch.account.overrideMissing.header"),
                tf!("launch.account.overrideMissing.body", pack_name.as_str()),
                tf!(
                    "launch.account.overrideMissing.useDefault",
                    default.name.as_str()
                ),
                t!("dialog.button.cancel"),
            );
            if mmcl_ui::dialogs::ask(cx, &q, None).await != Answer::Primary {
                return AccountStep::Stop;
            }
            if let Err(e) = engine.set_pack_account_override(id, None) {
                mmcl_ui::dialogs::error(cx, &e.message(), None).await;
                return AccountStep::Stop;
            }
            // Anything else in the way (a running pack, a busy account) shows below.
            match engine.why_launch_blocked(id, None) {
                None => AccountStep::Go,
                Some(e) => {
                    report(engine, id, e, cx).await;
                    AccountStep::Stop
                }
            }
        }
        EngineError::Account(Problem::NeedsSignIn) => {
            let name = engine.default_account().map(|a| a.name).unwrap_or_default();
            let q = Question::new(
                t!("launch.account.needsSignIn.title"),
                tf!("launch.account.needsSignIn.header", name.as_str()),
                t!("launch.account.needsSignIn.body"),
                t!("launch.account.needsSignIn.title"),
                t!("dialog.button.cancel"),
            );
            if mmcl_ui::dialogs::ask(cx, &q, None).await == Answer::Primary {
                cx.update(|cx| mmcl_ui::screens::accounts::show_add_account(None, cx));
            }
            AccountStep::Stop
        }
        other => {
            report(engine, id, other, cx).await;
            AccountStep::Stop
        }
    }
}

/// Shows a refusal: a pack that already runs opens its tab, anything else is an error dialog.
async fn report(engine: &Engine, id: &PackId, err: EngineError, cx: &mut AsyncApp) {
    if let EngineError::LaunchRefused(LaunchRefusal::PackAlreadyRunning { .. }) = err
        && let Some(session) = engine.active_session_for_pack(id)
    {
        cx.update(|cx| mmcl_running_games::show_session(cx, session.id));
        return;
    }
    if let EngineError::Account(Problem::NoAccount) = err {
        mmcl_ui::dialogs::error(cx, &t!("launch.account.none"), None).await;
        return;
    }
    mmcl_ui::dialogs::error(cx, &err.message(), None).await;
}

/// `true` to go on with the launch.
async fn check_conflicts(engine: &Engine, id: &PackId, cx: &mut AsyncApp) -> bool {
    let conflicts = match engine.mod_conflicts(id) {
        Ok(c) if !c.is_empty() => c,
        _ => return true,
    };
    let first_jar = conflicts[0].first_jar.clone();
    let q = Question::new(
        t!("launch.modConflict.dialog.title"),
        t!("launch.modConflict.dialog.header"),
        conflict_body(&conflicts),
        tf!(
            "launch.modConflict.dialog.button.disable",
            first_jar.as_str()
        ),
        t!("launch.modConflict.dialog.button.launchAnyway"),
    );
    match mmcl_ui::dialogs::ask_multiline(cx, &q, None).await {
        Answer::Primary => match engine.disable_mod_jar(id, &first_jar) {
            Ok(()) => true,
            Err(_) => {
                mmcl_ui_kit::notify::warn(
                    &tf!(
                        "notification.launch.disableModFailed.title",
                        first_jar.as_str()
                    ),
                    &t!("notification.launch.disableModFailed.body"),
                );
                false
            }
        },
        Answer::Secondary => true,
        Answer::Closed => false,
    }
}

/// The whole Play flow.
pub async fn play(engine: Engine, id: PackId, cx: &mut AsyncApp) {
    if let AccountStep::Stop = check_account(&engine, &id, cx).await {
        return;
    }
    if !check_conflicts(&engine, &id, cx).await {
        return;
    }
    let e = engine.clone();
    let launch_id = id.clone();
    let result = engine
        .spawn(async move { e.launch(&launch_id, LaunchOptions::default()).await })
        .await;
    match result {
        Ok(Ok(session)) => cx.update(|cx| mmcl_running_games::show_session(cx, session)),
        Ok(Err(err)) => report(&engine, &id, err, cx).await,
        Err(_) => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn conflict_lines_are_bulleted_and_separated() {
        let c = |t: &str, d: &str| ModConflictView {
            title: t.into(),
            description: d.into(),
            first_jar: "a.jar".into(),
            second_jar: "b.jar".into(),
        };
        assert_eq!(
            conflict_body(&[c("A vs B", "They crash"), c("C vs D", "Broken")]),
            "\u{2022} A vs B\n  They crash\n\n\u{2022} C vs D\n  Broken"
        );
    }

    #[test]
    fn every_key_used_here_exists() {
        for k in [
            "launch.account.none",
            "launch.account.overrideMissing.title",
            "launch.account.overrideMissing.header",
            "launch.account.overrideMissing.body",
            "launch.account.overrideMissing.useDefault",
            "launch.account.needsSignIn.title",
            "launch.account.needsSignIn.header",
            "launch.account.needsSignIn.body",
            "launch.modConflict.dialog.title",
            "launch.modConflict.dialog.header",
            "launch.modConflict.dialog.button.disable",
            "launch.modConflict.dialog.button.launchAnyway",
            "notification.launch.disableModFailed.title",
            "notification.launch.disableModFailed.body",
        ] {
            assert!(mmcl_i18n::contains(k), "{k}");
        }
    }
}

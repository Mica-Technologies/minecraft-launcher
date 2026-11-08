// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Engine operations shown on a progress screen, then back where they started:
//!
//! - verifies (`VerifyAction` + `goToLaunchProgressGui`) on the Launch Progress screen: one row
//!   per pipeline step from the engine's [`mmcl_engine::EngineEvent::OperationSteps`], and a
//!   Cancel button ([`mmcl_engine::Engine::cancel_operation`]; the call then returns
//!   `EngineError::Cancelled`),
//! - runtime installs (`RuntimeManager`'s `goToProgressGui`) on the Progress screen, following
//!   [`mmcl_engine::EngineEvent::Progress`] (title, current step, bar, taskbar).
//!
//! Callers (Settings > Verify all, a card's Verify now, verify-before-export, runtime installs):
//!
//! ```ignore
//! let back = ScreenRequest::new(ScreenId::SETTINGS);
//! let task = mmcl_ui::screens::misc::flows::verify_all(cx, back);
//! cx.spawn(async move |cx| if let Some(Err(e)) = task.await { /* show e.message() */ }).detach();
//! ```

use std::future::Future;
use std::sync::Arc;

use gpui::{App, Task};
use mmcl_engine::{Engine, EngineResult, OperationId, PackId};
use mmcl_i18n::tf;
use mmcl_ui_kit::{ScreenId, ScreenRequest};

use super::launch_progress::{LaunchProgressHandle, show_launch_progress};
use super::progress::{CancelHandler, show_progress};
use crate::services;
use crate::shell;

/// A Cancel handler that cancels engine operation `op`.
pub fn cancel_operation_handler(engine: Engine, op: OperationId) -> CancelHandler {
    Arc::new(move || {
        let _ = engine.cancel_operation(op);
    })
}

/// Points the Launch Progress screen at operation `op`: title, its step rows (whatever the
/// engine already has), and a Cancel button for it.
pub fn follow_with_steps(
    handle: &LaunchProgressHandle,
    engine: &Engine,
    op: OperationId,
    title: String,
    subtitle: Option<String>,
) {
    handle.set_title(Some(title));
    handle.set_subtitle(subtitle);
    handle.follow_operation(Some(op), engine.operation_steps(op));
    handle.set_cancel_handler(Some(cancel_operation_handler(engine.clone(), op)));
}

/// Shows the Launch Progress screen titled `title` with operation `op`'s step rows and Cancel,
/// while `run(engine, op)` runs on the engine; then shows `back` (the screen that was showing
/// when `None`). Resolves to the operation's result, or `None` without an engine or when the
/// engine went away.
pub fn run_with_steps<T, F, Fut>(
    cx: &mut App,
    title: String,
    back: Option<ScreenRequest>,
    run: F,
) -> Task<Option<T>>
where
    T: Send + 'static,
    F: FnOnce(Engine, OperationId) -> Fut,
    Fut: Future<Output = T> + Send + 'static,
{
    let Some(engine) = services::try_engine(cx) else {
        return Task::ready(None);
    };
    let back = return_to(back, shell::current_screen(cx).map(|s| s.id(cx)));
    let op = engine.next_operation_id();
    if let Some(handle) = show_launch_progress(cx) {
        follow_with_steps(&handle, &engine, op, title, None);
    }
    let task = engine.spawn(run(engine.clone(), op));
    cx.spawn(async move |cx| {
        let result = task.await.ok();
        cx.update(|cx| shell::go_to(cx, back));
        result
    })
}

/// Where a flow returns: `back`, else the screen showing when it started, else Home.
pub fn return_to(back: Option<ScreenRequest>, showing: Option<ScreenId>) -> ScreenRequest {
    back.unwrap_or_else(|| ScreenRequest::new(showing.unwrap_or(ScreenId::HOME)))
}

/// Shows the Progress screen titled `title`, following operation `op`, while `run(engine, op)`
/// runs on the engine; then shows `back` (the screen that was showing when `None`). Resolves to
/// the operation's result, or `None` without an engine or when the engine went away.
pub fn run_with_progress<T, F, Fut>(
    cx: &mut App,
    title: String,
    back: Option<ScreenRequest>,
    run: F,
) -> Task<Option<T>>
where
    T: Send + 'static,
    F: FnOnce(Engine, OperationId) -> Fut,
    Fut: Future<Output = T> + Send + 'static,
{
    let Some(engine) = services::try_engine(cx) else {
        return Task::ready(None);
    };
    let back = return_to(back, shell::current_screen(cx).map(|s| s.id(cx)));
    let op = engine.next_operation_id();
    if let Some(handle) = show_progress(cx) {
        handle.set_upper(title);
        handle.follow_operation(Some(op));
    }
    let task = engine.spawn(run(engine.clone(), op));
    cx.spawn(async move |cx| {
        let result = task.await.ok();
        cx.update(|cx| shell::go_to(cx, back));
        result
    })
}

/// "Verify now" on one pack: the Launch Progress screen with the verify's step rows and
/// Cancel (a cancelled verify resolves to `Some(Err(EngineError::Cancelled))`).
pub fn verify_pack(
    cx: &mut App,
    pack: PackId,
    display_name: &str,
    back: Option<ScreenRequest>,
) -> Task<Option<EngineResult<OperationId>>> {
    let title = tf!("verify.progress.title", display_name);
    run_with_steps(cx, title, back, move |engine, op| async move {
        engine.verify_pack_with(&pack, op).await
    })
}

/// Verify all game files in one engine operation (the rows follow each pack in turn). Settings
/// runs its own per-pack loop with counted titles; this is for other callers.
pub fn verify_all(
    cx: &mut App,
    back: Option<ScreenRequest>,
) -> Task<Option<EngineResult<OperationId>>> {
    let title = mmcl_i18n::t!("progress.title.justAMoment").into_owned();
    run_with_steps(cx, title, back, |engine, op| async move {
        engine.verify_all_packs_with(op).await
    })
}

/// A Java runtime download (`RuntimeManager.verifyRuntime`'s progress screen).
pub fn install_runtime(
    cx: &mut App,
    component: String,
    back: Option<ScreenRequest>,
) -> Task<Option<EngineResult<OperationId>>> {
    let title = mmcl_i18n::t!("progress.title.justAMoment").into_owned();
    run_with_progress(cx, title, back, move |engine, op| async move {
        engine.install_runtime_with(&component, op).await
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_flow_returns_to_the_screen_it_started_on() {
        assert_eq!(
            return_to(None, Some(ScreenId::SETTINGS)).id,
            ScreenId::SETTINGS
        );
        assert_eq!(return_to(None, None).id, ScreenId::HOME);
        assert_eq!(
            return_to(
                Some(ScreenRequest::new(ScreenId::BROWSE)),
                Some(ScreenId::HOME)
            )
            .id,
            ScreenId::BROWSE
        );
    }

    #[test]
    fn flow_strings_exist() {
        for k in ["verify.progress.title", "progress.title.justAMoment"] {
            assert!(mmcl_i18n::english(k).is_some(), "{k}");
        }
    }
}

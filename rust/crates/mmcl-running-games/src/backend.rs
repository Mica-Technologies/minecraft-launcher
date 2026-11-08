// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`GamesBackend`]: everything the Running Games view needs from the launcher, as one seam.
//!
//! The app passes an [`mmcl_engine::Engine`] (which implements it); tests pass a fake. Futures are
//! runtime-agnostic, so GPUI's executor awaits them; the engine runs the work on its own runtime.

use std::rc::Rc;

use futures::FutureExt as _;
use futures::future::LocalBoxFuture;
use mmcl_engine::{
    CrashAction, CrashActionResult, CrashView, Engine, EngineEvent, LogSnapshot, LogSubscription,
    PackId, SessionId, SessionInfo, Setting,
};
use tokio::sync::broadcast;

/// What the Running Games view reads and does.
pub trait GamesBackend: 'static {
    /// Every session, oldest first.
    fn sessions(&self) -> Vec<SessionInfo>;
    /// One session.
    fn session(&self, id: SessionId) -> Option<SessionInfo>;
    /// A receiver on the event bus (`None` when there is none, e.g. a test that drives `sync`).
    fn events(&self) -> Option<broadcast::Receiver<EngineEvent>>;
    /// A lossless live log: the snapshot cut to `max_lines` (0 = all) plus every later batch.
    fn subscribe_log(&self, id: SessionId, max_lines: usize) -> Option<LogSubscription>;
    /// The in-memory log cut to `max_lines`.
    fn log_tail(&self, id: SessionId, max_lines: usize) -> Option<LogSnapshot>;
    /// The whole log (from the session log file when the in-memory copy lost its head).
    fn full_log(&self, id: SessionId) -> LocalBoxFuture<'static, Option<String>>;
    /// The session's crash diagnosis; `report` is the text it read.
    fn crash(&self, id: SessionId) -> LocalBoxFuture<'static, Option<CrashView>>;
    /// Cancels a preparing launch.
    fn cancel(&self, id: SessionId);
    /// Stops a running game (kills it when `force`). The error is user-readable.
    fn stop(&self, id: SessionId, force: bool) -> LocalBoxFuture<'static, Result<(), String>>;
    /// Removes an ended session; `false` when it is still active.
    fn dismiss(&self, id: SessionId) -> bool;
    /// Runs a crash suggestion's action.
    fn crash_action(
        &self,
        action: CrashAction,
        pack: Option<PackId>,
    ) -> LocalBoxFuture<'static, Result<CrashActionResult, String>>;
    /// `runningGamesDocked`.
    fn docked(&self) -> bool;
    /// Saves `runningGamesDocked`.
    fn set_docked(&self, docked: bool);
    /// `runningGamesDockHeight` (per-mille of the main window).
    fn dock_height(&self) -> i32;
    /// Saves `runningGamesDockHeight`.
    fn set_dock_height(&self, per_mille: i32);
    /// `consoleLogMaxLines` (0 = unlimited).
    fn console_max_lines(&self) -> i32;
}

/// A shared backend handle.
pub type Backend = Rc<dyn GamesBackend>;

fn save(engine: &Engine, setting: Setting) {
    let key = setting.key();
    if let Err(e) = engine.set(setting) {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.runningGames.settingSaveFailed", key, e.to_string())
        );
    }
}

impl GamesBackend for Engine {
    fn sessions(&self) -> Vec<SessionInfo> {
        Engine::sessions(self)
    }

    fn session(&self, id: SessionId) -> Option<SessionInfo> {
        Engine::session(self, id)
    }

    fn events(&self) -> Option<broadcast::Receiver<EngineEvent>> {
        Some(self.subscribe())
    }

    fn subscribe_log(&self, id: SessionId, max_lines: usize) -> Option<LogSubscription> {
        Engine::subscribe_log(self, id, max_lines).ok()
    }

    fn log_tail(&self, id: SessionId, max_lines: usize) -> Option<LogSnapshot> {
        self.session_log(id, max_lines).ok()
    }

    fn full_log(&self, id: SessionId) -> LocalBoxFuture<'static, Option<String>> {
        let engine = self.clone();
        self.spawn(async move { engine.session_full_log(id).await.ok() })
            .map(|r| r.ok().flatten())
            .boxed_local()
    }

    fn crash(&self, id: SessionId) -> LocalBoxFuture<'static, Option<CrashView>> {
        let engine = self.clone();
        self.spawn(async move { engine.session_crash(id).await.ok() })
            .map(|r| r.ok().flatten())
            .boxed_local()
    }

    fn cancel(&self, id: SessionId) {
        if let Err(e) = self.cancel_launch(id) {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!(
                    "log.runningGames.cancelFailed",
                    id.to_string(),
                    e.to_string()
                )
            );
        }
    }

    fn stop(&self, id: SessionId, force: bool) -> LocalBoxFuture<'static, Result<(), String>> {
        let engine = self.clone();
        self.spawn(async move { engine.stop_game(id, force).await.map_err(|e| e.to_string()) })
            .map(|r| r.unwrap_or_else(|e| Err(e.to_string())))
            .boxed_local()
    }

    fn dismiss(&self, id: SessionId) -> bool {
        self.dismiss_session(id)
    }

    fn crash_action(
        &self,
        action: CrashAction,
        pack: Option<PackId>,
    ) -> LocalBoxFuture<'static, Result<CrashActionResult, String>> {
        let engine = self.clone();
        self.spawn(async move {
            engine
                .apply_crash_action(&action, pack.as_ref())
                .await
                .map_err(|e| e.to_string())
        })
        .map(|r| r.unwrap_or_else(|e| Err(e.to_string())))
        .boxed_local()
    }

    fn docked(&self) -> bool {
        self.config().running_games_docked()
    }

    fn set_docked(&self, docked: bool) {
        save(self, Setting::RunningGamesDocked(docked));
    }

    fn dock_height(&self) -> i32 {
        self.config().running_games_dock_height()
    }

    fn set_dock_height(&self, per_mille: i32) {
        save(self, Setting::RunningGamesDockHeight(per_mille));
    }

    fn console_max_lines(&self) -> i32 {
        self.config().console_log_max_lines()
    }
}

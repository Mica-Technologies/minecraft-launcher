// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launch orchestration (Java `LauncherCore.play` / `doModpackSelection` server loop):
//! admission, account resolution, the RAM gate, the step tracker, the pipeline, the game log,
//! the process watcher and the exit bookkeeping. Also the verify and update operations, which
//! run the same pipeline without starting a game.

use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use parking_lot::Mutex;

use mmcl_core::GameMode;
use mmcl_launch::{LaunchError, LaunchRequest};
use mmcl_modpack::GameModPack;
use mmcl_net::CancelToken;
use mmcl_progress::{LaunchProgressTracker, StepId, StepSnapshot, StepState, TrackerBridge};
use mmcl_session::{ChildProcess, GameLog, ProcessHandle, log_listener};

use super::{Engine, LaunchOptions, now_ms};
use crate::error::{EngineError, EngineResult, LaunchRefusal};
use crate::event::{EngineEvent, Notification, NotificationKind, OperationId, OperationProgress};
use crate::model::PackId;
use crate::session::{LaunchStepView, SessionId, SessionPhase};

/// How many times server mode restarts a crashed server.
pub(crate) const SERVER_MAX_RESTARTS: u32 = 3;
/// The wait before a server restart.
pub(crate) const SERVER_RESTART_DELAY: Duration = Duration::from_secs(5);
/// The "ready" toast only shows for launches that took longer than this.
const READY_TOAST_AFTER: Duration = Duration::from_secs(10);
/// Progress-only step updates are forwarded at most this often per session.
const STEP_EMIT_INTERVAL: Duration = Duration::from_millis(120);
/// Headless step sub-text is logged at most this often per step.
const HEADLESS_DETAIL_INTERVAL: Duration = Duration::from_millis(3000);

/// What the engine holds for an active session beyond its snapshot.
pub(crate) struct LiveSession {
    pub(crate) cancel: CancelToken,
    pub(crate) process: Option<Arc<dyn ProcessHandle>>,
    pub(crate) stop_requested: bool,
}

/// Marks a pack busy (files being rewritten) until dropped.
pub(crate) struct BusyGuard {
    engine: Engine,
    id: PackId,
}

impl BusyGuard {
    /// Marks the pack busy. Refused (`PackInUse`) when it is already busy or has an active
    /// session. The session check runs under the busy lock, and admission registers a session
    /// under the same lock, so a launch and a file rewrite can never both start
    /// (lock order: busy, then sessions).
    pub(crate) fn take(engine: &Engine, id: &PackId, name: &str) -> EngineResult<BusyGuard> {
        let mut busy = engine.inner.busy.lock();
        if busy.contains(id) || engine.inner.sessions.active_for_pack(id).is_some() {
            return Err(EngineError::PackInUse(name.to_string()));
        }
        busy.insert(id.clone());
        Ok(BusyGuard {
            engine: engine.clone(),
            id: id.clone(),
        })
    }
}

impl Drop for BusyGuard {
    fn drop(&mut self) {
        self.engine.inner.busy.lock().remove(&self.id);
    }
}

/// Maps a pipeline failure onto the engine's error.
pub(crate) fn launch_error(e: LaunchError) -> EngineError {
    if e.is_cancelled() {
        return EngineError::Cancelled;
    }
    match e {
        LaunchError::Modpack(m) => EngineError::Modpack(m),
        LaunchError::Runtime(r) => EngineError::Runtime(r),
        other => EngineError::Other(other.to_string()),
    }
}

/// The row label of a step (falls back to English when the key is missing).
pub(crate) fn step_label(id: StepId) -> String {
    if mmcl_i18n::contains(id.label_key()) {
        mmcl_i18n::get(id.label_key()).into_owned()
    } else {
        id.english_label().to_string()
    }
}

/// The view of one tracker row.
pub(crate) fn step_view(s: &StepSnapshot) -> LaunchStepView {
    LaunchStepView {
        id: s.id.name().to_string(),
        label: step_label(s.id),
        state: s.state.into(),
        progress: s.progress,
        sub_text: s.sub_text.clone(),
        error: s.error.clone(),
    }
}

fn is_terminal(s: StepState) -> bool {
    matches!(s, StepState::Done | StepState::Failed | StepState::Skipped)
}

fn state_name(s: StepState) -> &'static str {
    match s {
        StepState::Pending => "PENDING",
        StepState::Running => "RUNNING",
        StepState::Done => "DONE",
        StepState::Failed => "FAILED",
        StepState::Skipped => "SKIPPED",
    }
}

/// `true` when the pack needs more memory than the max RAM setting allows (Java's RAM gate).
pub(crate) fn below_min_ram(min_ram_gb: f64, max_ram_mb: i64) -> bool {
    min_ram_gb > 0.0 && min_ram_gb > max_ram_mb as f64 / 1024.0
}

/// Formats a minimum RAM figure the way the Java message did (`4.0` -> `4`, `3.5` -> `3.5`).
fn format_gb(gb: f64) -> String {
    if (gb.fract()).abs() < f64::EPSILON {
        format!("{gb:.0}")
    } else {
        format!("{gb}")
    }
}

/// The launch settings with "always verify on launch" forced on (`LaunchOptions::force_verify`).
struct ForceVerify(mmcl_config::Config);

impl mmcl_launch::LaunchConfig for ForceVerify {
    fn min_ram_mb(&self) -> i64 {
        self.0.min_ram_mb()
    }
    fn max_ram_mb(&self) -> i64 {
        self.0.max_ram_mb()
    }
    fn custom_jvm_args(&self) -> String {
        self.0.custom_jvm_args()
    }
    fn lwjgl_arm_patch_enabled(&self) -> bool {
        self.0.lwjgl_arm_patch()
    }
    fn always_verify_on_launch(&self, _manifest_url: &str) -> bool {
        true
    }
    fn scan_frequency_for(&self, manifest_url: Option<&str>) -> mmcl_modpack::ScanFrequency {
        mmcl_launch::LaunchConfig::scan_frequency_for(&self.0, manifest_url)
    }
}

#[derive(Default)]
struct ListenerState {
    last_emit: Option<Instant>,
    states: HashMap<StepId, StepState>,
    detail_logged: HashMap<StepId, Instant>,
    ready: bool,
}

impl Engine {
    // -----------------------------------------------------------------------------------------
    // Admission
    // -----------------------------------------------------------------------------------------

    /// The pack's launch account: `(uuid, name)` of the forced account, else the override,
    /// else the default. Server mode plays as nobody.
    fn launch_account(
        &self,
        id: &PackId,
        forced: Option<&str>,
    ) -> EngineResult<Option<(String, String)>> {
        if self.mode() == GameMode::Server {
            return Ok(None);
        }
        let accounts = self.inner.auth.accounts();
        match forced {
            Some(u) => match accounts.account(u) {
                Some(a) if a.status == mmcl_auth::Status::NeedsSignIn => {
                    Err(EngineError::Account(mmcl_auth::Problem::NeedsSignIn))
                }
                Some(a) => Ok(Some((a.uuid, a.display_name))),
                None => Err(EngineError::AccountNotFound(u.to_string())),
            },
            None => {
                let over = self.inner.config.account_override_for_pack(id.as_str());
                let r = mmcl_auth::resolve(over.as_deref(), &accounts.accounts());
                match (r.problem, r.uuid) {
                    (Some(p), _) => Err(EngineError::Account(p)),
                    (None, Some(u)) => Ok(Some((u, r.account_name.unwrap_or_default()))),
                    (None, None) => Err(EngineError::Account(mmcl_auth::Problem::NoAccount)),
                }
            }
        }
    }

    /// The RAM gate: refuses a pack whose minimum exceeds the max RAM setting (or the
    /// server-mode `--xmx` override).
    fn check_ram(&self, pack: &GameModPack) -> EngineResult<()> {
        let max_mb = match self.inner.launch_env.heap_override.xmx_mb {
            Some(x) if self.mode() == GameMode::Server => i64::try_from(x).unwrap_or(i64::MAX),
            _ => self.inner.config.max_ram_mb(),
        };
        let min = pack.min_ram_gb();
        if below_min_ram(min, max_mb) {
            let name = pack.friendly_name().unwrap_or_else(|| pack.name());
            let msg = mmcl_i18n::tf!("engine.error.notEnoughRam", name, format_gb(min));
            mmcl_log::error_silent(&msg);
            return Err(EngineError::Other(msg));
        }
        Ok(())
    }

    /// Admits a launch of `pack`: gameplay switch, loaded manifest, account, RAM, then the
    /// atomic one-per-pack / one-per-account registration. Returns the new session and its
    /// cancel token.
    fn admit(
        &self,
        pack: &Arc<GameModPack>,
        options: &LaunchOptions,
    ) -> EngineResult<(SessionId, Option<String>, CancelToken)> {
        if self.is_shut_down() {
            return Err(EngineError::ShutDown);
        }
        if mmcl_net::Announcements::global().disable_gameplay() {
            return Err(EngineError::LaunchRefused(LaunchRefusal::GameplayDisabled));
        }
        mmcl_launch::pipeline::guard_loaded(pack).map_err(launch_error)?;
        let id = PackId::of(pack);
        let account = self.launch_account(&id, options.account_uuid.as_deref())?;
        self.check_ram(pack)?;
        let display = pack.friendly_name().unwrap_or_else(|| pack.name());
        let (uuid, name) = match account {
            Some((u, n)) => (Some(u), Some(n)),
            None => (None, None),
        };
        let sid = {
            // Held across the registration (see `BusyGuard::take`).
            let busy = self.inner.busy.lock();
            if busy.contains(&id) {
                return Err(EngineError::PackInUse(pack.name()));
            }
            self.inner
                .sessions
                .try_begin(id, display, uuid.clone(), name)
                .map_err(EngineError::LaunchRefused)?
        };
        self.report_pruned();
        let cancel = CancelToken::new();
        self.inner.live.lock().insert(
            sid,
            LiveSession {
                cancel: cancel.clone(),
                process: None,
                stop_requested: false,
            },
        );
        if let Some(info) = self.inner.sessions.get(sid) {
            self.emit(EngineEvent::SessionStarted(Arc::new(info)));
        }
        Ok((sid, uuid, cancel))
    }

    /// [`Engine::launch`]: admit, then run the pipeline on the engine runtime.
    pub(super) async fn launch_impl(
        &self,
        id: &PackId,
        options: LaunchOptions,
    ) -> EngineResult<SessionId> {
        let pack = self.require_installed(id)?;
        let (sid, uuid, cancel) = self.admit(&pack, &options)?;
        let me = self.clone();
        self.inner.handle.spawn(async move {
            let _ = me.drive(sid, pack, options, uuid, cancel).await;
        });
        Ok(sid)
    }

    // -----------------------------------------------------------------------------------------
    // The pipeline
    // -----------------------------------------------------------------------------------------

    /// Runs one admitted session to a started game (or a failed / cancelled session). The
    /// process is returned so server mode can wait on it.
    async fn drive(
        &self,
        sid: SessionId,
        pack: Arc<GameModPack>,
        options: LaunchOptions,
        account_uuid: Option<String>,
        cancel: CancelToken,
    ) -> EngineResult<Arc<dyn ProcessHandle>> {
        let res = self
            .prepare_and_start(sid, &pack, &options, account_uuid, &cancel)
            .await;
        match res {
            Ok(p) => Ok(p),
            Err(e) => {
                let cancelled = cancel.is_cancelled() || e.is_cancelled();
                let message = e.message();
                if cancelled {
                    mmcl_log::std(&mmcl_i18n::t!("log.launcherCore.launchCancelled"));
                } else {
                    mmcl_log::error_silent(&mmcl_i18n::t!("UNABLE_START_GAME_EXCEPTION_TEXT"));
                    mmcl_log::error_multiline(&message);
                    self.inner.sessions.push_lines(sid, vec![message.clone()]);
                    let mut n = Notification::new(
                        NotificationKind::Error,
                        mmcl_i18n::t!("UNABLE_START_GAME_EXCEPTION_TEXT"),
                        message,
                    );
                    n.pack = Some(PackId::of(&pack));
                    n.session = Some(sid);
                    self.notify(n);
                }
                self.end_without_game(sid, cancelled);
                Err(e)
            }
        }
    }

    /// Ends a session that never got a game (Java `GameSession.endWithoutGame`).
    fn end_without_game(&self, sid: SessionId, cancelled: bool) {
        self.inner.live.lock().remove(&sid);
        self.inner.sessions.close_log(sid);
        let info = self.inner.sessions.update(sid, |s| {
            if s.phase == SessionPhase::Preparing {
                s.phase = if cancelled || s.cancelled {
                    SessionPhase::Cancelled
                } else {
                    SessionPhase::Failed
                };
                s.ended_ms = now_ms();
            }
        });
        if let Some(info) = info {
            self.emit(EngineEvent::SessionChanged(Arc::new(info)));
        }
        self.report_pruned();
    }

    /// Sends [`EngineEvent::SessionRemoved`] for the ended sessions the 20-entry limit dropped.
    pub(super) fn report_pruned(&self) {
        for id in self.inner.sessions.take_pruned() {
            self.emit(EngineEvent::SessionRemoved(id));
        }
    }

    fn tracker_listener(
        &self,
        sid: SessionId,
        ids: Vec<StepId>,
        friendly: String,
    ) -> mmcl_progress::Listener {
        let weak = Arc::downgrade(&self.inner);
        let headless = self.mode() == GameMode::Server;
        let state = Mutex::new(ListenerState::default());
        let started = Instant::now();
        Arc::new(move |snap: &StepSnapshot| {
            let Some(inner) = weak.upgrade() else { return };
            let engine = Engine { inner };
            let view = step_view(snap);
            let info = engine.inner.sessions.update(sid, |i| {
                match i.steps.iter_mut().find(|s| s.id == view.id) {
                    Some(s) => *s = view.clone(),
                    None => i.steps.push(view.clone()),
                }
            });
            let mut st = state.lock();
            let changed = st.states.insert(snap.id, snap.state) != Some(snap.state);
            if headless {
                if changed {
                    if snap.state == StepState::Failed {
                        mmcl_log::error_silent(&mmcl_i18n::tf!(
                            "log.launch.headless.stepFailed",
                            view.label.as_str(),
                            snap.error.clone().unwrap_or_default()
                        ));
                    } else {
                        mmcl_log::std(&mmcl_i18n::tf!(
                            "log.launch.headless.stepState",
                            view.label.as_str(),
                            state_name(snap.state)
                        ));
                    }
                } else if !snap.sub_text.is_empty() {
                    let due = st
                        .detail_logged
                        .get(&snap.id)
                        .is_none_or(|t| t.elapsed() >= HEADLESS_DETAIL_INTERVAL);
                    if due {
                        st.detail_logged.insert(snap.id, Instant::now());
                        mmcl_log::std(&mmcl_i18n::tf!(
                            "log.launch.headless.stepDetail",
                            view.label.as_str(),
                            snap.sub_text.as_str()
                        ));
                    }
                }
            }
            let all_terminal = ids
                .iter()
                .all(|id| st.states.get(id).is_some_and(|s| is_terminal(*s)));
            let any_failed = st.states.values().any(|s| *s == StepState::Failed);
            let fire_ready = all_terminal && !st.ready;
            if fire_ready {
                st.ready = true;
            }
            let due = changed
                || st
                    .last_emit
                    .is_none_or(|t| t.elapsed() >= STEP_EMIT_INTERVAL);
            if due {
                st.last_emit = Some(Instant::now());
            }
            drop(st);
            if due && let Some(info) = info {
                engine.emit(EngineEvent::SessionChanged(Arc::new(info)));
            }
            if fire_ready && !any_failed && started.elapsed() > READY_TOAST_AFTER {
                let body = if friendly.is_empty() {
                    mmcl_i18n::t!("notification.launch.ready.body").into_owned()
                } else {
                    mmcl_i18n::tf!("notification.launch.ready.bodyNamed", friendly.as_str())
                };
                let mut n = Notification::new(
                    NotificationKind::Success,
                    mmcl_i18n::t!("notification.launch.ready.title"),
                    body,
                );
                n.session = Some(sid);
                engine.notify(n);
            }
        })
    }

    async fn prepare_and_start(
        &self,
        sid: SessionId,
        pack: &Arc<GameModPack>,
        options: &LaunchOptions,
        account_uuid: Option<String>,
        cancel: &CancelToken,
    ) -> EngineResult<Arc<dyn ProcessHandle>> {
        let ids = StepId::launch_steps(pack.is_vanilla(), pack.uses_post_install_steps());
        let tracker = LaunchProgressTracker::for_steps(&ids);
        let initial: Vec<LaunchStepView> = tracker.steps().iter().map(step_view).collect();
        if let Some(info) = self.inner.sessions.update(sid, |s| s.steps = initial) {
            self.emit(EngineEvent::SessionChanged(Arc::new(info)));
        }
        let friendly = pack.friendly_name().unwrap_or_default();
        tracker.add_listener(self.tracker_listener(sid, ids, friendly));
        let bridge = Arc::new(TrackerBridge::new(tracker.clone()));

        // The account (refreshed when due; a transient failure launches on the cached token).
        let account = if self.mode() == GameMode::Client {
            let user = self
                .inner
                .auth
                .user_for_launch(account_uuid.as_deref())
                .await
                .map_err(|b| {
                    EngineError::Account(b.0.problem.unwrap_or(mmcl_auth::Problem::NeedsSignIn))
                })?;
            Some(mmcl_launch::Account::from(&user))
        } else {
            None
        };
        if cancel.is_cancelled() {
            return Err(EngineError::Cancelled);
        }
        if let Some(q) = options
            .quick_join
            .as_deref()
            .filter(|q| !q.trim().is_empty())
        {
            pack.set_quick_join_server(mmcl_manifest::ServerFavorite::parse(None, Some(q.trim())));
        }
        let mut env = self.inner.launch_env.clone();
        if options.force_verify {
            // A FULL run: the per-pack "always verify" answer is forced on for this launch.
            env.config = Arc::new(ForceVerify(self.inner.config.clone()));
        }
        let started = mmcl_launch::launch(
            &env,
            LaunchRequest {
                pack: pack.clone(),
                account,
                sink: bridge.clone(),
                bridge: Some(bridge),
                cancel: cancel.clone(),
            },
        )
        .await
        .map_err(launch_error)?;
        let mut child = started.child;
        let lease = started.runtime_lease;
        if cancel.is_cancelled() {
            let _ = child.start_kill();
            mmcl_log::std(&mmcl_i18n::t!("log.launcherCore.launchCancelledAfterSpawn"));
            lease.release();
            return Err(EngineError::Cancelled);
        }
        pack.save_installed_version();
        pack.record_launch_start();

        // Output capture (client mode pipes; server mode inherits the terminal).
        let mut log_file = None;
        if self.mode() == GameMode::Client {
            let dir = self.inner.paths.logs_dir();
            let _ = std::fs::create_dir_all(&dir);
            let file = GameLog::file_for(&dir, Some(&pack.name()), &GameLog::timestamp_now());
            log_file = Some(file.clone());
            let log = GameLog::new(Some(file));
            let store = self.inner.sessions.clone();
            let tx = self.inner.events.clone();
            let store2 = self.inner.sessions.clone();
            log.add_listener(log_listener(
                move |lines: &[String]| {
                    store.push_lines(sid, lines.to_vec());
                    let _ = tx.send(EngineEvent::SessionOutput {
                        session: sid,
                        lines: lines.to_vec().into(),
                    });
                },
                move || store2.close_log(sid),
            ));
            let out = child.stdout.take();
            let err = child.stderr.take();
            match (out, err) {
                (Some(o), Some(e)) => log.attach_async(o, e, &self.inner.handle),
                (Some(o), None) => log.attach_async(o, tokio::io::empty(), &self.inner.handle),
                (None, Some(e)) => log.attach_async(tokio::io::empty(), e, &self.inner.handle),
                (None, None) => {
                    log.attach_async(tokio::io::empty(), tokio::io::empty(), &self.inner.handle)
                }
            }
        }
        let process: Arc<dyn ProcessHandle> = ChildProcess::watch_on(child, &self.inner.handle);
        let started_ms = now_ms();
        let truncated_file = log_file.clone();
        let info = self.inner.sessions.update(sid, |s| {
            s.phase = SessionPhase::Running;
            s.started_ms = started_ms;
            s.log_file = truncated_file;
        });
        if let Some(l) = self.inner.live.lock().get_mut(&sid) {
            l.process = Some(process.clone());
        }
        if let Some(info) = info {
            self.emit(EngineEvent::SessionChanged(Arc::new(info)));
        }
        self.emit(EngineEvent::PackChanged(PackId::of(pack)));
        self.follow_running_games();

        let weak = Arc::downgrade(&self.inner);
        let pack2 = pack.clone();
        process.on_exit(Box::new(move |code| {
            lease.release();
            if let Some(inner) = weak.upgrade() {
                Engine { inner }.on_game_exited(sid, &pack2, code, started_ms);
            }
        }));
        Ok(process)
    }

    /// Exit bookkeeping (Java `onGameExited`): play time, phase, crash report, toast.
    fn on_game_exited(&self, sid: SessionId, pack: &Arc<GameModPack>, code: i32, started_ms: i64) {
        let ended = now_ms();
        pack.record_session_end((ended - started_ms).max(0));
        let stop_requested = self
            .inner
            .live
            .lock()
            .remove(&sid)
            .is_some_and(|l| l.stop_requested);
        let crashed = code != 0 && !stop_requested;
        if crashed {
            mmcl_log::error_silent(&mmcl_i18n::tf!(
                "log.launcherCore.gameCrashedExitCode",
                code.to_string()
            ));
            self.inner
                .sessions
                .set_crash_report(sid, pack.latest_crash_report());
        }
        let info = self.inner.sessions.update(sid, |s| {
            s.phase = if crashed {
                SessionPhase::Crashed
            } else {
                SessionPhase::Exited
            };
            s.exit_code = Some(code);
            s.ended_ms = ended;
        });
        if let Some(info) = info {
            if crashed {
                let mut n = Notification::new(
                    NotificationKind::Error,
                    mmcl_i18n::t!("notification.launch.gameCrashed.title"),
                    mmcl_i18n::tf!(
                        "notification.launch.gameCrashed.body",
                        info.pack_name.as_str(),
                        code.to_string()
                    ),
                );
                n.pack = Some(info.pack.clone());
                n.session = Some(sid);
                self.notify(n);
            }
            self.emit(EngineEvent::SessionChanged(Arc::new(info)));
        }
        self.report_pruned();
        self.emit(EngineEvent::PackChanged(PackId::of(pack)));
        self.follow_running_games();
    }

    // -----------------------------------------------------------------------------------------
    // Stop / cancel
    // -----------------------------------------------------------------------------------------

    /// Cancels the preparing launch's pipeline.
    pub(super) fn cancel_token_of(&self, sid: SessionId) {
        if let Some(l) = self.inner.live.lock().get(&sid) {
            l.cancel.cancel();
        }
    }

    /// Asks the running game to exit (or kills it).
    pub(super) fn stop_running(&self, sid: SessionId, force: bool) -> EngineResult<()> {
        let process = {
            let mut live = self.inner.live.lock();
            let Some(l) = live.get_mut(&sid) else {
                return Ok(());
            };
            l.stop_requested = true;
            l.process.clone()
        };
        match process {
            Some(p) if p.is_alive() => {
                if force {
                    p.kill();
                } else {
                    p.terminate();
                }
                Ok(())
            }
            Some(_) => Ok(()),
            None => {
                // Between the pipeline's end and the process watch: cancel instead.
                self.cancel_token_of(sid);
                Ok(())
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Server mode
    // -----------------------------------------------------------------------------------------

    /// [`Engine::run_server`]: the crash-restart loop with Ctrl-C handling. The first Ctrl-C
    /// reaches the server too (it shares the terminal) and lets it save and stop; the engine
    /// only stops restarting it. A second Ctrl-C kills it.
    pub(super) async fn run_server_impl(&self, id: &PackId) -> EngineResult<i32> {
        if self.mode() != GameMode::Server {
            return Err(EngineError::Other(
                mmcl_i18n::t!("engine.error.serverModeOnly").into_owned(),
            ));
        }
        let mut pack = self.require_installed(id)?;
        // A headless server starts the engine without the GUI's background work; RGB and
        // Discord are bootstrapped here instead (Java bootstraps RGB in every mode).
        self.start_presence();
        let name = pack.name();
        let stopping = Arc::new(AtomicBool::new(false));
        let current: Arc<Mutex<Option<SessionId>>> = Arc::new(Mutex::new(None));
        let ctrl_c = {
            let stopping = stopping.clone();
            let current = current.clone();
            let me = self.clone();
            self.inner.handle.spawn(async move {
                loop {
                    if tokio::signal::ctrl_c().await.is_err() {
                        return;
                    }
                    let second = stopping.swap(true, Ordering::SeqCst);
                    mmcl_log::std(&mmcl_i18n::t!("log.engine.server.stopRequested"));
                    let sid = *current.lock();
                    if let Some(sid) = sid {
                        match me.session(sid).map(|s| s.phase) {
                            Some(SessionPhase::Preparing) => {
                                let _ = me.cancel_launch(sid);
                            }
                            Some(SessionPhase::Running) if second => {
                                let _ = me.stop_running(sid, true);
                            }
                            Some(SessionPhase::Running) => {
                                if let Some(l) = me.inner.live.lock().get_mut(&sid) {
                                    l.stop_requested = true;
                                }
                            }
                            _ => {}
                        }
                    }
                }
            })
        };

        let mut restarts = 0u32;
        let result = loop {
            if restarts > 0 {
                self.inner.packs.fetch_installed(true).await;
                match self.inner.packs.installed_by_name(&name) {
                    Some(p) if !p.is_failed_load() && !p.is_stub() => pack = p,
                    Some(_) => mmcl_log::warn_silent(&mmcl_i18n::tf!(
                        "log.launcherCore.restartPackNotLoaded",
                        name.as_str()
                    )),
                    None => mmcl_log::warn_silent(&mmcl_i18n::t!(
                        "log.launcherCore.restartRefreshFailed"
                    )),
                }
                mmcl_log::std(&mmcl_i18n::tf!(
                    "log.launcherCore.startingServerRestart",
                    restarts.to_string(),
                    SERVER_MAX_RESTARTS.to_string()
                ));
            } else {
                mmcl_log::std(&mmcl_i18n::t!("log.launcherCore.startingServer"));
            }
            mmcl_log::std(&mmcl_i18n::tf!(
                "log.launcherCore.launchingPackVersion",
                name.as_str(),
                pack.version()
            ));
            let options = LaunchOptions::default();
            let (sid, uuid, cancel) = match self.admit(&pack, &options) {
                Ok(x) => x,
                Err(e) => break Err(e),
            };
            *current.lock() = Some(sid);
            let process = match self.drive(sid, pack.clone(), options, uuid, cancel).await {
                Ok(p) => p,
                Err(e) => break Err(e),
            };
            let (tx, rx) = futures::channel::oneshot::channel::<i32>();
            process.on_exit(Box::new(move |code| {
                let _ = tx.send(code);
            }));
            let code = rx.await.unwrap_or(-1);
            *current.lock() = None;
            mmcl_log::std(&mmcl_i18n::tf!(
                "log.launcherCore.serverExited",
                code.to_string()
            ));
            if code == 0 || stopping.load(Ordering::SeqCst) {
                break Ok(code);
            }
            if restarts < SERVER_MAX_RESTARTS {
                restarts += 1;
                mmcl_log::std(&mmcl_i18n::t!("log.launcherCore.serverCrashedRestarting"));
                let wait = CancelToken::new();
                let s2 = stopping.clone();
                let w2 = wait.clone();
                let watcher = self.inner.handle.spawn(async move {
                    while !s2.load(Ordering::SeqCst) {
                        tokio::time::sleep(Duration::from_millis(100)).await;
                    }
                    w2.cancel();
                });
                // `sleep` is `true` when Ctrl-C cut the wait short.
                let interrupted = wait.sleep(SERVER_RESTART_DELAY).await;
                watcher.abort();
                if interrupted || stopping.load(Ordering::SeqCst) {
                    break Ok(code);
                }
                continue;
            }
            mmcl_log::error_silent(&mmcl_i18n::tf!(
                "log.launcherCore.serverCrashedGivingUp",
                SERVER_MAX_RESTARTS.to_string()
            ));
            break Ok(code);
        };
        ctrl_c.abort();
        result
    }

    // -----------------------------------------------------------------------------------------
    // Verify and update
    // -----------------------------------------------------------------------------------------

    /// A tracker whose rows report as [`EngineEvent::Progress`] of `op`.
    fn progress_tracker(&self, pack: &GameModPack, op: OperationId) -> Arc<TrackerBridge> {
        let ids = StepId::launch_steps(pack.is_vanilla(), pack.uses_post_install_steps());
        let tracker = LaunchProgressTracker::for_steps(&ids);
        let tx = self.inner.events.clone();
        let label = mmcl_i18n::tf!(
            "engine.progress.verify",
            pack.friendly_name().unwrap_or_else(|| pack.name())
        );
        let weak = Arc::downgrade(&tracker);
        let last = Mutex::new(None::<Instant>);
        tracker.add_listener(Arc::new(move |snap: &StepSnapshot| {
            let fraction = weak.upgrade().map_or(-1.0, |t| t.overall_fraction());
            let mut l = last.lock();
            let terminal = is_terminal(snap.state) || snap.state == StepState::Running;
            if !terminal && l.is_some_and(|t| t.elapsed() < STEP_EMIT_INTERVAL) {
                return;
            }
            *l = Some(Instant::now());
            drop(l);
            let step = step_label(snap.id);
            let text = if snap.sub_text.is_empty() {
                step
            } else {
                format!("{step}: {}", snap.sub_text)
            };
            let _ = tx.send(EngineEvent::Progress(OperationProgress {
                operation: op,
                label: label.clone(),
                text,
                fraction,
            }));
        }));
        Arc::new(TrackerBridge::new(tracker))
    }

    /// Runs the forced-FULL pipeline over one installed pack.
    pub(super) async fn verify_one(
        &self,
        pack: &Arc<GameModPack>,
        op: OperationId,
    ) -> EngineResult<()> {
        let _busy = BusyGuard::take(self, &PackId::of(pack), &pack.name())?;
        self.verify_held(pack, op).await
    }

    /// [`Engine::verify_one`] for a caller that already holds the pack's [`BusyGuard`].
    async fn verify_held(&self, pack: &Arc<GameModPack>, op: OperationId) -> EngineResult<()> {
        let id = PackId::of(pack);
        let bridge = self.progress_tracker(pack, op);
        let env = self.inner.launch_env.clone();
        mmcl_launch::verify_all_files_now(
            &env,
            pack,
            bridge.clone(),
            Some(bridge),
            &CancelToken::new(),
        )
        .await
        .map_err(launch_error)?;
        self.emit(EngineEvent::PackChanged(id));
        Ok(())
    }

    /// [`Engine::verify_pack`].
    pub(super) async fn verify_pack_impl(
        &self,
        id: &PackId,
        op: OperationId,
    ) -> EngineResult<OperationId> {
        let pack = match self.require_installed(id) {
            Ok(p) => p,
            Err(e) => {
                self.emit(EngineEvent::OperationFinished(op));
                return Err(e);
            }
        };
        let res = self.verify_one(&pack, op).await;
        self.emit(EngineEvent::OperationFinished(op));
        res.map(|_| op)
    }

    /// [`Engine::verify_all_packs`]: every loaded, idle pack in turn; the first error is
    /// reported after the rest have run.
    pub(super) async fn verify_all_impl(&self, op: OperationId) -> EngineResult<OperationId> {
        let packs = self.inner.packs.installed_snapshot();
        if packs.is_empty() {
            self.notify(Notification::new(
                NotificationKind::Info,
                mmcl_i18n::t!("notification.settings.nothingToVerify.title"),
                mmcl_i18n::t!("notification.settings.nothingToVerify.body"),
            ));
        }
        let mut first_err = None;
        for p in packs.iter() {
            if p.is_failed_load() || p.is_stub() {
                continue;
            }
            // Java `VerifyAction`: a pack that is launching or running is skipped with a
            // warning rather than repaired under the game, and is not an error.
            if self
                .inner
                .sessions
                .active_for_pack(&PackId::of(p))
                .is_some()
            {
                let mut n = Notification::new(
                    NotificationKind::Warning,
                    mmcl_i18n::t!("notification.pack.running.title"),
                    mmcl_i18n::tf!(
                        "notification.pack.running.verify",
                        p.friendly_name().unwrap_or_else(|| p.name())
                    ),
                );
                n.pack = Some(PackId::of(p));
                self.notify(n);
                continue;
            }
            if let Err(e) = self.verify_one(p, op).await
                && first_err.is_none()
            {
                first_err = Some(e);
            }
        }
        self.emit(EngineEvent::OperationFinished(op));
        match first_err {
            Some(e) => Err(e),
            None => Ok(op),
        }
    }

    /// [`Engine::update_pack`]: re-install from the manifest URL (which backs up first when
    /// enabled and replaces the entry in place), then a FULL sync, then record the version.
    pub(super) async fn update_pack_impl(
        &self,
        id: &PackId,
        op: OperationId,
    ) -> EngineResult<OperationId> {
        let finished = |e: EngineError| {
            self.emit(EngineEvent::OperationFinished(op));
            e
        };
        let pack = self.require_installed(id).map_err(finished)?;
        // Busy for the whole update, so no launch can start between the re-install and the sync.
        let _busy = BusyGuard::take(self, id, &pack.name()).map_err(finished)?;
        let fresh = match pack.manifest_url() {
            Some(url) if !pack.is_vanilla() => {
                let url = url.to_string();
                match self.inner.packs.install_by_url(&url).await {
                    Ok(p) => p,
                    Err(e) => {
                        self.emit(EngineEvent::OperationFinished(op));
                        return Err(e.into());
                    }
                }
            }
            _ => pack.clone(),
        };
        let res = self.verify_held(&fresh, op).await;
        if res.is_ok() {
            fresh.save_installed_version();
        }
        self.emit(EngineEvent::OperationFinished(op));
        self.emit(EngineEvent::PacksChanged);
        res.map(|_| op)
    }

    /// The crash card's "reinstall": unregister (files kept, so worlds survive), install from
    /// the URL again and re-verify every file.
    pub(super) async fn reinstall_impl(&self, id: &PackId) -> EngineResult<()> {
        let pack = self.require_installed(id)?;
        mmcl_log::std(&mmcl_i18n::tf!(
            "log.crashAnalyzer.reinstallRequested",
            pack.name()
        ));
        let url = pack
            .manifest_url()
            .filter(|u| !u.trim().is_empty())
            .map(str::to_string)
            .ok_or_else(|| EngineError::PackNotFound(id.clone()))?;
        let _busy = BusyGuard::take(self, id, &pack.name())?;
        if !self.inner.packs.uninstall(&pack, false).await {
            return Err(EngineError::PackInUse(pack.name()));
        }
        let fresh = match self.inner.packs.install_by_url(&url).await {
            Ok(p) => p,
            Err(e) => {
                mmcl_log::error_silent(&mmcl_i18n::tf!(
                    "log.crashAnalyzer.reinstallFailed",
                    e.to_string()
                ));
                return Err(e.into());
            }
        };
        self.emit(EngineEvent::PacksChanged);
        let op = self.next_operation_id();
        let res = self.verify_held(&fresh, op).await;
        self.emit(EngineEvent::OperationFinished(op));
        res
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_ram_gate_compares_gigabytes() {
        assert!(!below_min_ram(0.0, 1024));
        assert!(!below_min_ram(4.0, 4096));
        assert!(below_min_ram(4.5, 4096));
        assert!(!below_min_ram(2.0, 8192));
    }

    #[test]
    fn minimum_ram_formats_like_java() {
        assert_eq!(format_gb(4.0), "4");
        assert_eq!(format_gb(3.5), "3.5");
    }

    #[test]
    fn step_rows_are_labelled() {
        for id in StepId::ALL {
            assert!(!step_label(id).is_empty());
        }
        let snap = StepSnapshot {
            id: StepId::JreInstall,
            state: StepState::Running,
            progress: 0.5,
            sub_text: "x".into(),
            error: None,
        };
        let v = step_view(&snap);
        assert_eq!(v.id, "JRE_INSTALL");
        assert_eq!(v.progress, 0.5);
    }

    #[test]
    fn cancelled_pipeline_errors_are_cancelled() {
        assert!(matches!(
            launch_error(LaunchError::Cancelled),
            EngineError::Cancelled
        ));
        assert!(matches!(
            launch_error(LaunchError::PreLaunchCancelled),
            EngineError::Cancelled
        ));
    }
}

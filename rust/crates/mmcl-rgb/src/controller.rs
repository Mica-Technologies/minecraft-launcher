// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`RgbController`]: owns the backends, the frame queue, the `mica-rgb` worker and the effect
//! engine (Java `RgbController`).

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, SyncSender};
use std::sync::{Arc, OnceLock, Weak};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use mmcl_i18n::tf;
use parking_lot::{Mutex, RwLock};

use crate::backend::{BackendKind, RgbBackend, guarded, safe_kind, safe_name, safe_shutdown};
use crate::color::RgbColor;
use crate::effect::{RgbEffect, SolidEffect};
use crate::engine::{EffectEngine, FrameSink};
use crate::frame::RgbFrame;
use crate::health::{BackendHealth, HealthState};
use crate::settings::RgbSettings;

/// Frames waiting for the worker; more are dropped (a stuck backend is already out of sync, and
/// queueing stale frames only amplifies the glitch on recovery).
pub const FRAME_QUEUE_CAPACITY: usize = 4;
/// How long the worker waits for a frame before re-checking for shutdown.
pub const QUEUE_POLL_TIMEOUT_MS: u64 = 500;
/// How long `stop()` waits for the worker.
pub const WORKER_JOIN_TIMEOUT_MS: u64 = 2000;
/// Controller-level dedup: a slot that already shows a frame is not sent the identical frame
/// again until this much time has passed (Java re-sent static frames 30 times a second).
pub const RESEND_INTERVAL_MS: u64 = 1000;
/// Length of the Settings "Test" flash.
pub const CONNECTION_TEST_MS: u64 = 2000;
/// The Settings test colour: fixed magenta so it reads the same in every theme.
pub const CONNECTION_TEST_COLOR: RgbColor = RgbColor::new(255, 0, 255);

/// A millisecond clock for the circuit breakers (monotonic in production, manual in tests).
pub trait Clock: Send + Sync {
    fn now_ms(&self) -> u64;
}

/// Milliseconds since the clock was made.
pub struct MonotonicClock {
    origin: Instant,
}

impl MonotonicClock {
    pub fn new() -> MonotonicClock {
        MonotonicClock {
            origin: Instant::now(),
        }
    }
}

impl Default for MonotonicClock {
    fn default() -> Self {
        MonotonicClock::new()
    }
}

impl Clock for MonotonicClock {
    fn now_ms(&self) -> u64 {
        self.origin.elapsed().as_millis() as u64
    }
}

/// One backend's row in [`Status`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BackendStatus {
    pub name: String,
    pub kind: BackendKind,
    pub health: HealthState,
    pub consecutive_failures: u32,
}

/// A snapshot for the Settings status chip, in dispatch order.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct Status {
    pub backends: Vec<BackendStatus>,
    pub running: bool,
}

impl Status {
    /// The first backend's name, or `"None"`.
    pub fn primary_name(&self) -> &str {
        self.backends.first().map_or("None", |b| b.name.as_str())
    }

    /// Dead if any is Dead, else Degraded if any is Degraded, else Healthy (also when empty).
    pub fn worst_health(&self) -> HealthState {
        if self.backends.iter().any(|b| b.health == HealthState::Dead) {
            HealthState::Dead
        } else if self
            .backends
            .iter()
            .any(|b| b.health == HealthState::Degraded)
        {
            HealthState::Degraded
        } else {
            HealthState::Healthy
        }
    }

    /// The chip label: the primary name plus `" +N"` for the other backends.
    pub fn label(&self) -> String {
        match self.backends.len() {
            0 | 1 => self.primary_name().to_string(),
            n => format!("{} +{}", self.primary_name(), n - 1),
        }
    }

    /// The Settings status chip (localised text and tone) for this status.
    pub fn chip(&self, rgb_enabled: bool) -> StatusChip {
        use mmcl_i18n::t;
        if !rgb_enabled {
            return StatusChip {
                text: t!("settings.rgb.status.disabled").into_owned(),
                tone: ChipTone::Muted,
            };
        }
        if !self.running || self.backends.is_empty() {
            return StatusChip {
                text: t!("settings.rgb.status.notDetected").into_owned(),
                tone: ChipTone::Warn,
            };
        }
        let label = self.label();
        let (key, tone) = match self.worst_health() {
            HealthState::Healthy => ("settings.rgb.status.connected", ChipTone::Success),
            HealthState::Degraded => ("settings.rgb.status.degraded", ChipTone::Warn),
            HealthState::Dead => ("settings.rgb.status.unavailable", ChipTone::Danger),
        };
        StatusChip {
            text: tf!(key, label),
            tone,
        }
    }
}

/// How the Settings status chip is styled.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum ChipTone {
    Muted,
    Success,
    Warn,
    Danger,
}

/// The Settings status chip.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StatusChip {
    pub text: String,
    pub tone: ChipTone,
}

/// Identifies a status listener.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub struct ListenerId(u64);

type Listener = Arc<dyn Fn() + Send + Sync>;

struct SlotView {
    name: String,
    kind: BackendKind,
    health: Arc<Mutex<BackendHealth>>,
}

struct Slot {
    backend: Box<dyn RgbBackend>,
    name: String,
    health: Arc<Mutex<BackendHealth>>,
    /// The last frame this slot accepted and when.
    last_sent: Option<(RgbFrame, u64)>,
}

struct Worker {
    stop: Arc<AtomicBool>,
    slots_back: Receiver<Vec<Slot>>,
    join: JoinHandle<()>,
}

#[derive(Default)]
struct Lifecycle {
    last_requested: Vec<BackendKind>,
    worker: Option<Worker>,
    engine: Option<EffectEngine>,
}

struct Inner {
    clock: Arc<dyn Clock>,
    lifecycle: Mutex<Lifecycle>,
    running: AtomicBool,
    frame_tx: Mutex<Option<SyncSender<RgbFrame>>>,
    views: RwLock<Vec<SlotView>>,
    listeners: Mutex<Vec<(ListenerId, Listener)>>,
    next_listener: Mutex<u64>,
    resend_interval_ms: u64,
}

/// The RGB controller. Cheap to clone (all clones share one controller); the launcher uses the
/// process-wide [`RgbController::global`].
#[derive(Clone)]
pub struct RgbController {
    inner: Arc<Inner>,
}

impl RgbController {
    /// The process-wide controller.
    pub fn global() -> &'static RgbController {
        static GLOBAL: OnceLock<RgbController> = OnceLock::new();
        GLOBAL.get_or_init(|| RgbController::with_clock(Arc::new(MonotonicClock::new())))
    }

    /// A separate controller on `clock` (tests, tools).
    pub fn with_clock(clock: Arc<dyn Clock>) -> RgbController {
        RgbController::with_options(clock, RESEND_INTERVAL_MS)
    }

    /// A controller with a custom dedup re-send interval (0 disables dedup).
    pub fn with_options(clock: Arc<dyn Clock>, resend_interval_ms: u64) -> RgbController {
        RgbController {
            inner: Arc::new(Inner {
                clock,
                lifecycle: Mutex::new(Lifecycle::default()),
                running: AtomicBool::new(false),
                frame_tx: Mutex::new(None),
                views: RwLock::new(Vec::new()),
                listeners: Mutex::new(Vec::new()),
                next_listener: Mutex::new(0),
                resend_interval_ms,
            }),
        }
    }

    /// Whether at least one backend is running.
    pub fn is_running(&self) -> bool {
        self.inner.running.load(Ordering::Acquire)
    }

    /// Starts on every backend that is available and starts cleanly, in order. Re-requesting
    /// the same kinds (in the same order) while running and no slot is Dead is a no-op, so
    /// unrelated Settings toggles do not churn vendor sessions (Razer Synapse dislikes that).
    pub fn start(&self, backends: Vec<Box<dyn RgbBackend>>) {
        let inner = &self.inner;
        let mut lc = inner.lifecycle.lock();
        let real: Vec<Box<dyn RgbBackend>> = backends
            .into_iter()
            .filter(|b| safe_kind(&**b) != BackendKind::NoOp)
            .collect();
        let kinds: Vec<BackendKind> = real.iter().map(|b| safe_kind(&**b)).collect();

        if self.is_running() && kinds == lc.last_requested && self.all_slots_alive() {
            let names: Vec<String> = real.iter().map(|b| safe_name(&**b)).collect();
            tracing::debug!(
                "{}",
                tf!("log.rgb.controller.sameBackendNoOp", names.join(", "))
            );
            return;
        }

        if self.is_running() {
            self.stop_worker_and_shutdown_slots(&mut lc);
        }
        lc.last_requested = kinds;

        let mut slots = Vec::new();
        for mut backend in real {
            let name = safe_name(&*backend);
            match guarded(|| Ok(backend.is_available())) {
                Err(e) => {
                    tracing::warn!(
                        "{}: {e}",
                        tf!("log.rgb.controller.backendIsAvailableThrew", name.as_str())
                    );
                    continue;
                }
                Ok(false) => {
                    tracing::debug!(
                        "{}",
                        tf!("log.rgb.controller.backendNotAvailable", name.as_str())
                    );
                    continue;
                }
                Ok(true) => {}
            }
            if let Err(e) = guarded(|| backend.start()) {
                tracing::warn!(
                    "{}: {e}",
                    tf!("log.rgb.controller.backendStartThrew", name.as_str())
                );
                // A failed start may have half-initialised a session.
                safe_shutdown(&mut *backend);
                continue;
            }
            slots.push(Slot {
                backend,
                name,
                health: Arc::new(Mutex::new(BackendHealth::new())),
                last_sent: None,
            });
        }

        if slots.is_empty() {
            inner.running.store(false, Ordering::Release);
            if let Some(engine) = lc.engine.take() {
                engine.shutdown();
            }
            tracing::debug!("{}", mmcl_i18n::t!("log.rgb.controller.noBackendsStarted"));
            drop(lc);
            self.notify();
            return;
        }

        *inner.views.write() = slots
            .iter()
            .map(|s| SlotView {
                name: s.name.clone(),
                kind: safe_kind(&*s.backend),
                health: s.health.clone(),
            })
            .collect();
        let names: Vec<String> = slots.iter().map(|s| s.name.clone()).collect();

        let (tx, rx) = mpsc::sync_channel::<RgbFrame>(FRAME_QUEUE_CAPACITY);
        let (back_tx, back_rx) = mpsc::channel::<Vec<Slot>>();
        let stop = Arc::new(AtomicBool::new(false));
        let worker_ctx = WorkerCtx {
            inner: Arc::downgrade(inner),
            clock: inner.clock.clone(),
            stop: stop.clone(),
            resend_interval_ms: inner.resend_interval_ms,
        };
        *inner.frame_tx.lock() = Some(tx);
        inner.running.store(true, Ordering::Release);
        let spawn = std::thread::Builder::new()
            .name("mica-rgb".into())
            .spawn(move || worker_ctx.run(rx, slots, back_tx));
        match spawn {
            Ok(join) => {
                lc.worker = Some(Worker {
                    stop,
                    slots_back: back_rx,
                    join,
                });
                tracing::info!(
                    "{}",
                    tf!("log.rgb.controller.subsystemStarted", names.join(", "))
                );
            }
            Err(e) => {
                // The closure (and the slots in it) were dropped without a shutdown; nothing
                // else can be done without a thread.
                inner.running.store(false, Ordering::Release);
                *inner.frame_tx.lock() = None;
                inner.views.write().clear();
                tracing::warn!(
                    "{}: {e}",
                    mmcl_i18n::t!("log.rgb.controller.noBackendsStarted")
                );
            }
        }
        drop(lc);
        self.notify();
    }

    /// [`RgbController::start`] with zero or one backend (`None`/NoOp idles the controller).
    pub fn start_single(&self, backend: Option<Box<dyn RgbBackend>>) {
        self.start(backend.into_iter().collect());
    }

    /// Stops the effect engine, the worker and every backend. Idempotent.
    pub fn stop(&self) {
        let mut lc = self.inner.lifecycle.lock();
        if !self.is_running() {
            return;
        }
        if let Some(engine) = lc.engine.take() {
            engine.shutdown();
        }
        self.stop_worker_and_shutdown_slots(&mut lc);
        lc.last_requested.clear();
        drop(lc);
        self.notify();
    }

    /// Settings restart: `stop()`, then (if enabled) start on the configured backends.
    pub fn restart_from_settings(&self, settings: &RgbSettings) {
        self.stop();
        if settings.enabled {
            self.start(crate::registry::resolve_backends(settings));
        }
    }

    /// Queues a frame for the backends; dropped when not running or the queue is full.
    pub fn submit_frame(&self, frame: RgbFrame) {
        self.inner.submit_frame(frame);
    }

    /// Activates an effect (`None` idles it with one black frame). Does nothing while not
    /// running, and never creates an engine just to idle it.
    pub fn set_effect(&self, effect: Option<Arc<dyn RgbEffect>>) {
        if !self.is_running() && effect.is_some() {
            return;
        }
        let engine = {
            let mut lc = self.inner.lifecycle.lock();
            if lc.engine.is_none() {
                if effect.is_none() {
                    return;
                }
                let weak: Weak<Inner> = Arc::downgrade(&self.inner);
                let sink: FrameSink = Arc::new(move |f| {
                    if let Some(inner) = weak.upgrade() {
                        inner.submit_frame(f);
                    }
                });
                lc.engine = Some(EffectEngine::new(sink));
            }
            lc.engine.clone()
        };
        if let Some(engine) = engine {
            engine.set_effect(effect);
        }
    }

    /// `set_effect(None)`.
    pub fn stop_effect(&self) {
        self.set_effect(None);
    }

    /// The active effect, if any.
    pub fn active_effect(&self) -> Option<Arc<dyn RgbEffect>> {
        let lc = self.inner.lifecycle.lock();
        lc.engine.as_ref().and_then(EffectEngine::active)
    }

    /// The backends in dispatch order with their breaker state.
    pub fn status(&self) -> Status {
        let views = self.inner.views.read();
        Status {
            backends: views
                .iter()
                .map(|v| {
                    let h = v.health.lock();
                    BackendStatus {
                        name: v.name.clone(),
                        kind: v.kind,
                        health: h.state(),
                        consecutive_failures: h.consecutive_failures(),
                    }
                })
                .collect(),
            running: self.is_running(),
        }
    }

    /// Settings "Test": flashes magenta for 2 s, then restores the previous effect (`None`
    /// stops it with a black frame). Blocks; call it off the UI thread. Does nothing visible
    /// when no backend runs.
    pub fn run_connection_test(&self) {
        self.run_connection_test_for(Duration::from_millis(CONNECTION_TEST_MS));
    }

    pub(crate) fn run_connection_test_for(&self, flash: Duration) {
        let prior = self.active_effect();
        self.set_effect(Some(Arc::new(SolidEffect::new(
            "Connection Test",
            CONNECTION_TEST_COLOR,
        ))));
        std::thread::sleep(flash);
        self.set_effect(prior);
    }

    /// Calls `f` whenever the status may have changed (start, stop, a breaker state change).
    /// `f` runs on whichever thread made the change; keep it short.
    pub fn on_status_changed(&self, f: impl Fn() + Send + Sync + 'static) -> ListenerId {
        let id = {
            let mut n = self.inner.next_listener.lock();
            *n += 1;
            ListenerId(*n)
        };
        self.inner.listeners.lock().push((id, Arc::new(f)));
        id
    }

    /// Removes a status listener.
    pub fn remove_status_listener(&self, id: ListenerId) {
        self.inner.listeners.lock().retain(|(i, _)| *i != id);
    }

    fn notify(&self) {
        self.inner.notify();
    }

    #[cfg(test)]
    pub(crate) fn has_engine(&self) -> bool {
        self.inner.lifecycle.lock().engine.is_some()
    }

    fn all_slots_alive(&self) -> bool {
        self.inner
            .views
            .read()
            .iter()
            .all(|v| v.health.lock().state() != HealthState::Dead)
    }

    fn stop_worker_and_shutdown_slots(&self, lc: &mut Lifecycle) {
        let inner = &self.inner;
        inner.running.store(false, Ordering::Release);
        // Dropping the only sender wakes the worker at once.
        *inner.frame_tx.lock() = None;
        if let Some(worker) = lc.worker.take() {
            worker.stop.store(true, Ordering::Release);
            match worker
                .slots_back
                .recv_timeout(Duration::from_millis(WORKER_JOIN_TIMEOUT_MS))
            {
                Ok(mut slots) => {
                    for s in &mut slots {
                        safe_shutdown(&mut *s.backend);
                    }
                    let _ = worker.join.join();
                }
                Err(_) => {
                    // The worker is stuck inside a backend call. It shuts its slots down itself
                    // once that call returns (the receiver is gone by then).
                    tracing::warn!(
                        "{}",
                        tf!("log.rgb.controller.opThrew", "worker join (timeout)")
                    );
                }
            }
        }
        inner.views.write().clear();
    }
}

impl Inner {
    fn submit_frame(&self, frame: RgbFrame) {
        if !self.running.load(Ordering::Acquire) {
            return;
        }
        if let Some(tx) = self.frame_tx.lock().as_ref() {
            let _ = tx.try_send(frame);
        }
    }

    fn notify(&self) {
        let listeners: Vec<Listener> = self
            .listeners
            .lock()
            .iter()
            .map(|(_, l)| l.clone())
            .collect();
        for l in listeners {
            let _ = catch_unwind(AssertUnwindSafe(|| l()));
        }
    }
}

struct WorkerCtx {
    inner: Weak<Inner>,
    clock: Arc<dyn Clock>,
    stop: Arc<AtomicBool>,
    resend_interval_ms: u64,
}

impl WorkerCtx {
    fn run(self, rx: Receiver<RgbFrame>, mut slots: Vec<Slot>, back: mpsc::Sender<Vec<Slot>>) {
        tracing::debug!("{}", mmcl_i18n::t!("log.rgb.controller.workerStarted"));
        loop {
            let frame = match rx.recv_timeout(Duration::from_millis(QUEUE_POLL_TIMEOUT_MS)) {
                Ok(f) => f,
                Err(RecvTimeoutError::Timeout) => {
                    if self.stop.load(Ordering::Acquire) {
                        break;
                    }
                    continue;
                }
                Err(RecvTimeoutError::Disconnected) => break,
            };
            if self.stop.load(Ordering::Acquire) {
                break;
            }
            let now = self.clock.now_ms();
            let mut changed = false;
            for slot in slots.iter_mut() {
                if slot.health.lock().can_call(now) {
                    changed |= self.render_to(slot, &frame, now);
                }
            }
            if changed {
                if slots
                    .iter()
                    .all(|s| s.health.lock().state() == HealthState::Dead)
                {
                    tracing::warn!("{}", mmcl_i18n::t!("log.rgb.controller.allBackendsFailed"));
                }
                if let Some(inner) = self.inner.upgrade() {
                    inner.notify();
                }
            }
        }
        tracing::debug!("{}", mmcl_i18n::t!("log.rgb.controller.workerExiting"));
        if let Err(mpsc::SendError(mut slots)) = back.send(slots) {
            for s in &mut slots {
                safe_shutdown(&mut *s.backend);
            }
        }
    }

    /// Renders one frame on one slot; returns `true` when the breaker state changed.
    fn render_to(&self, slot: &mut Slot, frame: &RgbFrame, now: u64) -> bool {
        if self.resend_interval_ms > 0
            && let Some((last, at)) = slot.last_sent
            && last == *frame
            && now.saturating_sub(at) < self.resend_interval_ms
        {
            return false;
        }
        let result = guarded(|| slot.backend.render_frame(frame));
        let mut h = slot.health.lock();
        let before = h.state();
        match result {
            Ok(()) => {
                h.record_success();
                slot.last_sent = Some((*frame, now));
            }
            Err(e) => {
                h.record_failure(now);
                slot.last_sent = None;
                let state = h.state();
                tracing::warn!(
                    "{}: {e}",
                    tf!(
                        "log.rgb.controller.renderFrameThrew",
                        slot.name.as_str(),
                        format!("{state:?}"),
                        h.consecutive_failures().to_string()
                    )
                );
                if state == HealthState::Dead && before != HealthState::Dead {
                    tracing::error!(
                        "{}",
                        tf!("log.rgb.controller.backendMarkedDead", slot.name.as_str())
                    );
                    drop(h);
                    // Keep the slot (so status and the chip still show it), but release the
                    // vendor session now.
                    safe_shutdown(&mut *slot.backend);
                    return true;
                }
            }
        }
        h.state() != before
    }
}

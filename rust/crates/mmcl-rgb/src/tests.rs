// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Controller and integration tests with stub backends, a manual clock and a fake host.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use parking_lot::Mutex;

use crate::backend::{BackendKind, RgbBackend};
use crate::color::RgbColor;
use crate::controller::{Clock, RgbController};
use crate::effect::{RgbEffect, SolidEffect};
use crate::error::{RgbError, RgbResult};
use crate::frame::RgbFrame;
use crate::health::{BackendHealth, HealthState};
use crate::integration::{
    DEFAULT_IN_GAME_HIGHLIGHT, DEFAULT_IN_GAME_PRIMARY, PackRef, RgbHost, RgbIntegration,
};
use crate::settings::{EffectStyle, RgbSettings};

struct ManualClock(AtomicU64);

impl Clock for ManualClock {
    fn now_ms(&self) -> u64 {
        self.0.load(Ordering::SeqCst)
    }
}

#[derive(Default)]
struct Counters {
    starts: AtomicUsize,
    renders: AtomicUsize,
    shutdowns: AtomicUsize,
    frames: Mutex<Vec<RgbFrame>>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Mode {
    Ok,
    Fail,
    /// Blocks in render until the gate opens.
    Block,
}

struct Stub {
    kind: BackendKind,
    available: bool,
    mode: Mode,
    gate: Arc<AtomicBool>,
    c: Arc<Counters>,
}

impl RgbBackend for Stub {
    fn name(&self) -> &str {
        self.kind.label()
    }
    fn is_available(&mut self) -> bool {
        self.available
    }
    fn start(&mut self) -> RgbResult<()> {
        self.c.starts.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }
    fn render_frame(&mut self, f: &RgbFrame) -> RgbResult<()> {
        if self.mode == Mode::Block {
            while !self.gate.load(Ordering::SeqCst) {
                std::thread::sleep(Duration::from_millis(2));
            }
        }
        self.c.frames.lock().push(*f);
        self.c.renders.fetch_add(1, Ordering::SeqCst);
        match self.mode {
            Mode::Fail => Err(RgbError::backend("nope")),
            _ => Ok(()),
        }
    }
    fn shutdown(&mut self) {
        self.c.shutdowns.fetch_add(1, Ordering::SeqCst);
    }
    fn kind(&self) -> BackendKind {
        self.kind
    }
}

fn stub(kind: BackendKind, mode: Mode, c: &Arc<Counters>) -> Box<dyn RgbBackend> {
    Box::new(Stub {
        kind,
        available: true,
        mode,
        gate: Arc::new(AtomicBool::new(false)),
        c: c.clone(),
    })
}

fn controller() -> (RgbController, Arc<ManualClock>) {
    let clock = Arc::new(ManualClock(AtomicU64::new(1_000_000)));
    (RgbController::with_clock(clock.clone()), clock)
}

fn wait_until(what: &str, f: impl Fn() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(3);
    while !f() {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(2));
    }
}

fn frame(n: u8) -> RgbFrame {
    RgbFrame::solid(RgbColor::new(n, 0, 0))
}

#[test]
fn same_request_is_a_no_op() {
    let (ctl, _) = controller();
    let c1 = Arc::new(Counters::default());
    ctl.start(vec![
        stub(BackendKind::ChromaNative, Mode::Ok, &c1),
        stub(BackendKind::OpenRgb, Mode::Ok, &c1),
    ]);
    assert!(ctl.is_running());
    assert_eq!(c1.starts.load(Ordering::SeqCst), 2);

    let c2 = Arc::new(Counters::default());
    ctl.start(vec![
        stub(BackendKind::ChromaNative, Mode::Ok, &c2),
        stub(BackendKind::OpenRgb, Mode::Ok, &c2),
    ]);
    assert_eq!(
        c1.shutdowns.load(Ordering::SeqCst),
        0,
        "running session untouched"
    );
    assert_eq!(
        c2.starts.load(Ordering::SeqCst),
        0,
        "new instances never started"
    );

    // A different order is a different request.
    ctl.start(vec![
        stub(BackendKind::OpenRgb, Mode::Ok, &c2),
        stub(BackendKind::ChromaNative, Mode::Ok, &c2),
    ]);
    assert_eq!(c1.shutdowns.load(Ordering::SeqCst), 2);
    assert_eq!(c2.starts.load(Ordering::SeqCst), 2);
    let st = ctl.status();
    assert_eq!(st.primary_name(), "OpenRGB");
    assert_eq!(st.label(), "OpenRGB +1");
    ctl.stop();
    assert!(!ctl.is_running());
    ctl.stop(); // idempotent
}

#[test]
fn dead_slot_is_kept_and_reported() {
    let (ctl, clock) = controller();
    let bad = Arc::new(Counters::default());
    let good = Arc::new(Counters::default());
    ctl.start(vec![
        stub(BackendKind::OpenRgb, Mode::Fail, &bad),
        stub(BackendKind::Corsair, Mode::Ok, &good),
    ]);
    for i in 0..BackendHealth::DEAD_THRESHOLD {
        // Past any back-off window, and a fresh frame each time.
        clock
            .0
            .fetch_add(BackendHealth::MAX_BACKOFF_MS + 1, Ordering::SeqCst);
        ctl.submit_frame(frame(i as u8));
        wait_until("render", || bad.renders.load(Ordering::SeqCst) > i as usize);
    }
    wait_until("dead shutdown", || {
        bad.shutdowns.load(Ordering::SeqCst) == 1
    });
    let st = ctl.status();
    assert_eq!(st.backends.len(), 2, "the dead slot stays visible");
    assert_eq!(st.backends[0].health, HealthState::Dead);
    assert_eq!(
        st.backends[0].consecutive_failures,
        BackendHealth::DEAD_THRESHOLD
    );
    assert_eq!(st.worst_health(), HealthState::Dead);

    // Dead slots are never called again.
    clock
        .0
        .fetch_add(BackendHealth::MAX_BACKOFF_MS + 1, Ordering::SeqCst);
    ctl.submit_frame(frame(200));
    wait_until("good render", || good.frames.lock().contains(&frame(200)));
    assert_eq!(
        bad.renders.load(Ordering::SeqCst),
        BackendHealth::DEAD_THRESHOLD as usize
    );

    // A request matching the previous one is NOT a no-op while a slot is dead.
    let fresh = Arc::new(Counters::default());
    ctl.start(vec![
        stub(BackendKind::OpenRgb, Mode::Ok, &fresh),
        stub(BackendKind::Corsair, Mode::Ok, &fresh),
    ]);
    assert_eq!(fresh.starts.load(Ordering::SeqCst), 2);
    assert_eq!(ctl.status().worst_health(), HealthState::Healthy);
    ctl.stop();
}

#[test]
fn zero_survivors_tear_down_the_engine() {
    let (ctl, _) = controller();
    let c = Arc::new(Counters::default());
    ctl.start(vec![stub(BackendKind::OpenRgb, Mode::Ok, &c)]);
    ctl.set_effect(Some(Arc::new(SolidEffect::new("s", RgbColor::WHITE))));
    assert!(ctl.active_effect().is_some());
    wait_until("ticks", || c.renders.load(Ordering::SeqCst) > 0);

    let mut gone = Stub {
        kind: BackendKind::Corsair,
        available: false,
        mode: Mode::Ok,
        gate: Arc::new(AtomicBool::new(false)),
        c: c.clone(),
    };
    assert!(!gone.is_available());
    ctl.start(vec![Box::new(gone)]);
    assert!(!ctl.is_running());
    assert!(ctl.active_effect().is_none());
    assert!(!ctl.has_engine());
    assert_eq!(c.shutdowns.load(Ordering::SeqCst), 1);
}

#[test]
fn set_effect_none_never_creates_an_engine_and_idle_set_is_ignored() {
    let (ctl, _) = controller();
    ctl.set_effect(Some(Arc::new(SolidEffect::new("s", RgbColor::WHITE))));
    assert!(!ctl.has_engine(), "not running: ignored");
    let c = Arc::new(Counters::default());
    ctl.start(vec![stub(BackendKind::OpenRgb, Mode::Ok, &c)]);
    ctl.set_effect(None);
    assert!(!ctl.has_engine());
    ctl.stop();
}

#[test]
fn submit_drops_when_the_queue_is_full() {
    let (ctl, _) = controller();
    let c = Arc::new(Counters::default());
    let gate = Arc::new(AtomicBool::new(false));
    ctl.start(vec![Box::new(Stub {
        kind: BackendKind::OpenRgb,
        available: true,
        mode: Mode::Block,
        gate: gate.clone(),
        c: c.clone(),
    })]);
    ctl.submit_frame(frame(0));
    // Let the worker take frame 0 and block in render.
    std::thread::sleep(Duration::from_millis(50));
    for i in 1..20 {
        ctl.submit_frame(frame(i));
    }
    gate.store(true, Ordering::SeqCst);
    wait_until("drain", || c.renders.load(Ordering::SeqCst) >= 5);
    std::thread::sleep(Duration::from_millis(100));
    let frames = c.frames.lock().clone();
    assert_eq!(
        frames.len(),
        1 + crate::controller::FRAME_QUEUE_CAPACITY,
        "{frames:?}"
    );
    assert_eq!(
        frames[1..],
        [frame(1), frame(2), frame(3), frame(4)],
        "oldest kept, newest dropped"
    );
    ctl.stop();
}

#[test]
fn identical_frames_are_deduped_but_resent_each_second() {
    let (ctl, clock) = controller();
    let c = Arc::new(Counters::default());
    ctl.start(vec![stub(BackendKind::OpenRgb, Mode::Ok, &c)]);
    ctl.submit_frame(frame(7));
    wait_until("first", || c.renders.load(Ordering::SeqCst) == 1);
    ctl.submit_frame(frame(7));
    ctl.submit_frame(frame(8));
    wait_until("changed frame", || c.renders.load(Ordering::SeqCst) == 2);
    assert_eq!(*c.frames.lock().last().unwrap(), frame(8));
    clock
        .0
        .fetch_add(crate::controller::RESEND_INTERVAL_MS, Ordering::SeqCst);
    ctl.submit_frame(frame(8));
    wait_until("resend", || c.renders.load(Ordering::SeqCst) == 3);
    ctl.stop();
}

#[test]
fn status_listener_and_connection_test_restore() {
    let (ctl, _) = controller();
    let hits = Arc::new(AtomicUsize::new(0));
    let h2 = hits.clone();
    let id = ctl.on_status_changed(move || {
        h2.fetch_add(1, Ordering::SeqCst);
    });
    let c = Arc::new(Counters::default());
    ctl.start(vec![stub(BackendKind::OpenRgb, Mode::Ok, &c)]);
    assert!(hits.load(Ordering::SeqCst) >= 1);
    let prior: Arc<dyn RgbEffect> = Arc::new(SolidEffect::new("prior", RgbColor::new(1, 1, 1)));
    ctl.set_effect(Some(prior.clone()));
    ctl.run_connection_test_for(Duration::from_millis(80));
    assert!(Arc::ptr_eq(&ctl.active_effect().unwrap(), &prior));
    assert!(
        c.frames
            .lock()
            .contains(&RgbFrame::solid(crate::controller::CONNECTION_TEST_COLOR))
    );
    ctl.remove_status_listener(id);
    let before = hits.load(Ordering::SeqCst);
    ctl.stop();
    assert_eq!(hits.load(Ordering::SeqCst), before);
}

// ---- integration --------------------------------------------------------------------------

struct FakeHost {
    settings: Mutex<RgbSettings>,
    running: Mutex<Option<PackRef>>,
    palette: Mutex<Option<Vec<RgbColor>>>,
}

impl RgbHost for FakeHost {
    fn settings(&self) -> RgbSettings {
        self.settings.lock().clone()
    }
    fn os_is_dark(&self) -> bool {
        true
    }
    fn newest_running_pack(&self) -> Option<PackRef> {
        self.running.lock().clone()
    }
    fn pack_palette(&self, _: &PackRef, count: usize) -> Option<Vec<RgbColor>> {
        self.palette
            .lock()
            .clone()
            .map(|p| p.into_iter().take(count).collect())
    }
}

fn host() -> Arc<FakeHost> {
    Arc::new(FakeHost {
        settings: Mutex::new(RgbSettings {
            enabled: true,
            theme: "Dark".into(),
            ..RgbSettings::default()
        }),
        running: Mutex::new(None),
        palette: Mutex::new(None),
    })
}

fn pack() -> PackRef {
    PackRef {
        key: "k".into(),
        friendly_name: "Alto".into(),
        logo_path: None,
        vanilla: false,
    }
}

#[test]
fn menu_effect_per_style() {
    let h = host();
    let i = RgbIntegration::new(h.clone(), controller().0);
    let mut s = h.settings();
    let cases = [
        (EffectStyle::Rainbow, "Menu (rainbow)"),
        (EffectStyle::Solid, "Menu (solid · theme accent)"),
        (EffectStyle::Pulse, "Menu (pulse · theme accent)"),
        (EffectStyle::Cycle, "Menu (cycle · theme accent)"),
        (EffectStyle::Breathe, "Menu (breathe · theme accent)"),
    ];
    for (style, name) in cases {
        s.effect_style = style;
        assert_eq!(i.menu_effect(&s, None).unwrap().name(), name);
    }
    s.effect_style = EffectStyle::Solid;
    let solid = i.menu_effect(&s, None).unwrap();
    assert_eq!(
        solid.frame_at(0).unwrap().background(),
        crate::theme::ACCENT_DARK
    );
    *h.palette.lock() = Some(vec![RgbColor::new(200, 10, 10)]);
    let p = pack();
    let e = i.menu_effect(&s, Some(&p)).unwrap();
    assert_eq!(e.name(), "Menu (solid · Alto)");
    assert_eq!(
        e.frame_at(0).unwrap().background(),
        RgbColor::new(200, 10, 10)
    );
    // Cycle with one sampled colour is widened to three.
    s.effect_style = EffectStyle::Cycle;
    let cyc = i.menu_effect(&s, Some(&p)).unwrap();
    assert_eq!(
        cyc.frame_at(0).unwrap().background(),
        crate::theme::derive_palette(RgbColor::new(200, 10, 10), 3)[0]
    );
    s.menu_effect_enabled = false;
    assert!(i.menu_effect(&s, None).is_none());
}

#[test]
fn in_game_colours() {
    let h = host();
    let i = RgbIntegration::new(h.clone(), controller().0);
    let s = h.settings();
    let p = pack();
    assert_eq!(
        i.in_game_colors(&s, &p),
        (DEFAULT_IN_GAME_PRIMARY, DEFAULT_IN_GAME_HIGHLIGHT)
    );
    let red = RgbColor::new(200, 0, 0);
    *h.palette.lock() = Some(vec![red, RgbColor::new(0, 0, 200)]);
    assert_eq!(i.in_game_colors(&s, &p), (red, RgbColor::new(0, 0, 200)));
    *h.palette.lock() = Some(vec![red, RgbColor::new(210, 10, 0)]);
    assert_eq!(
        i.in_game_colors(&s, &p),
        (red, red.complement()),
        "too close: complement"
    );
    *h.palette.lock() = Some(vec![red]);
    assert_eq!(i.in_game_colors(&s, &p).1, red.complement());
    let mut off = s.clone();
    off.use_pack_colors = false;
    assert_eq!(
        i.in_game_colors(&off, &p),
        (DEFAULT_IN_GAME_PRIMARY, DEFAULT_IN_GAME_HIGHLIGHT)
    );
}

#[test]
fn on_menu_is_ignored_while_a_game_runs_and_play_flow() {
    let h = host();
    let (ctl, _) = controller();
    let c = Arc::new(Counters::default());
    ctl.start(vec![stub(BackendKind::OpenRgb, Mode::Ok, &c)]);
    let i = RgbIntegration::new(h.clone(), ctl.clone());
    i.on_menu(None);
    assert_eq!(
        ctl.active_effect().unwrap().name(),
        "Menu (breathe · theme accent)"
    );
    i.on_play_started(&pack());
    assert_eq!(ctl.active_effect().unwrap().name(), "In-Game (Alto)");
    *h.running.lock() = Some(pack());
    i.on_menu(Some(&pack()));
    assert_eq!(
        ctl.active_effect().unwrap().name(),
        "In-Game (Alto)",
        "game keeps the devices"
    );
    *h.running.lock() = None;
    i.on_play_ended();
    assert_eq!(
        ctl.active_effect().unwrap().name(),
        "Menu (breathe · theme accent)"
    );

    // Disabled: everything is a no-op.
    h.settings.lock().enabled = false;
    i.on_play_started(&pack());
    assert_eq!(
        ctl.active_effect().unwrap().name(),
        "Menu (breathe · theme accent)"
    );
    assert!(!i.run_connection_test());
    i.shutdown();
    assert!(!ctl.is_running());
}

#[test]
fn every_localisation_key_exists() {
    let keys = [
        "log.rgb.bootstrapThrew",
        "log.rgb.onMenuThrew",
        "log.rgb.onPlayStartedThrew",
        "log.rgb.onPlayEndedThrew",
        "log.rgb.shutdownThrew",
        "log.rgb.effectThrewAtTick",
        "log.rgb.controller.allBackendsFailed",
        "log.rgb.controller.backendIsAvailableThrew",
        "log.rgb.controller.backendMarkedDead",
        "log.rgb.controller.backendNotAvailable",
        "log.rgb.controller.backendStartThrew",
        "log.rgb.controller.noBackendsStarted",
        "log.rgb.controller.opThrew",
        "log.rgb.controller.renderFrameThrew",
        "log.rgb.controller.sameBackendNoOp",
        "log.rgb.controller.subsystemStarted",
        "log.rgb.controller.workerExiting",
        "log.rgb.controller.workerStarted",
        "log.rgb.registry.probeIncluding",
        "log.rgb.registry.probeIsAvailableThrew",
        "log.rgb.registry.probeNoneAvailable",
        "log.rgb.registry.probeSkippedDisabled",
        "log.rgb.registry.unrecognizedChoiceAuto",
        "log.rgb.registry.unrecognizedChoiceNoOp",
        "log.rgb.openrgb.mappedKeysSuffix",
        "log.rgb.openrgb.noUsableDevices",
        "log.rgb.openrgb.parseDeviceFailed",
        "log.rgb.openrgb.registeredDevice",
        "log.rgb.chroma.endpointFirstFrameSucceeded",
        "log.rgb.chroma.endpointPushFailed",
        "log.rgb.chroma.heartbeatFailed",
        "log.rgb.chroma.sessionOpened",
        "log.rgb.chroma.nativeFirstFrameSucceeded",
        "log.rgb.chroma.nativeGivingUpOnFamily",
        "log.rgb.chroma.nativeOpReturnedResult",
        "log.rgb.chroma.nativeSdkInitialized",
        "log.rgb.corsair.deviceRegistered",
        "log.rgb.corsair.devicesReady",
        "log.rgb.corsair.getDevicesError",
        "log.rgb.corsair.getLedPositionsError",
        "log.rgb.corsair.givingUpOnDevice",
        "log.rgb.corsair.sessionConnected",
        "log.rgb.corsair.setLedColorsThrew",
        "log.rgb.asusAura.createControllerThrew",
        "log.rgb.asusAura.familiesConnected",
        "log.rgb.asusAura.familyConnected",
        "log.rgb.asusAura.givingUpOnFamily",
        "log.rgb.asusAura.noController",
        "log.rgb.asusAura.releaseThrew",
        "log.rgb.asusAura.setColorThrew",
        "log.rgb.asusAura.zeroLeds",
        "log.rgb.dynamicLighting.deviceSelector",
        "log.rgb.dynamicLighting.devicesReady",
        "log.rgb.dynamicLighting.devicesReported",
        "log.rgb.dynamicLighting.fromIdAsyncFailed",
        "log.rgb.dynamicLighting.openLampArrayFailed",
        "log.rgb.dynamicLighting.openedLampArray",
        "log.rgb.dynamicLighting.setColorThrew",
        "log.rgb.helper.exited",
        "log.rgb.helper.respawning",
        "log.rgb.helper.spawnFailed",
        "log.rgb.helper.stderr",
        "log.rgb.helper.timeout",
        "settings.rgb.backend.auto",
        "settings.rgb.backend.none",
        "settings.rgb.status.connected",
        "settings.rgb.status.degraded",
        "settings.rgb.status.disabled",
        "settings.rgb.status.notDetected",
        "settings.rgb.status.unavailable",
        "notification.settings.rgbDisabled.title",
        "notification.settings.rgbDisabled.body",
    ];
    for k in keys
        .iter()
        .chain(crate::backends::chroma::rest::HINT_126_KEYS.iter())
    {
        if !k.is_empty() {
            assert!(mmcl_i18n::english(k).is_some(), "missing key {k}");
        }
    }
}

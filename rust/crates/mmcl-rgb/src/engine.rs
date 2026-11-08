// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The effect ticker (Java `RgbEffectEngine`): one thread, `mica-rgb-effects`, that asks the
//! active effect for a frame every 33 ms (fixed rate) and hands it to the controller.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use parking_lot::{Condvar, Mutex};

use crate::color::RgbColor;
use crate::effect::RgbEffect;
use crate::frame::RgbFrame;

/// The tick interval (about 30 fps).
pub const TICK_INTERVAL_MS: u64 = 33;
const TICK: Duration = Duration::from_millis(TICK_INTERVAL_MS);

pub(crate) type FrameSink = Arc<dyn Fn(RgbFrame) + Send + Sync>;

struct State {
    effect: Option<Arc<dyn RgbEffect>>,
    started: Instant,
    /// When the next tick is due; `None` while idle.
    next_tick: Option<Instant>,
    shutdown: bool,
}

struct Shared {
    state: Mutex<State>,
    wake: Condvar,
    sink: FrameSink,
}

/// A handle to the engine thread. Cloning shares the same engine.
#[derive(Clone)]
pub(crate) struct EffectEngine {
    shared: Arc<Shared>,
    thread: Arc<Mutex<Option<JoinHandle<()>>>>,
}

impl EffectEngine {
    /// Starts the (idle) engine thread. Frames go to `sink`.
    pub(crate) fn new(sink: FrameSink) -> EffectEngine {
        let shared = Arc::new(Shared {
            state: Mutex::new(State {
                effect: None,
                started: Instant::now(),
                next_tick: None,
                shutdown: false,
            }),
            wake: Condvar::new(),
            sink,
        });
        let worker = shared.clone();
        let handle = std::thread::Builder::new()
            .name("mica-rgb-effects".into())
            .spawn(move || run(worker))
            .ok();
        EffectEngine {
            shared,
            thread: Arc::new(Mutex::new(handle)),
        }
    }

    /// Activates `effect` (or idles with `None`, which also sends one black frame). Setting the
    /// instance that is already active does nothing, so its clock keeps running.
    pub(crate) fn set_effect(&self, effect: Option<Arc<dyn RgbEffect>>) {
        let went_idle = {
            let mut st = self.shared.state.lock();
            if st.shutdown {
                return;
            }
            let same = match (&st.effect, &effect) {
                (None, None) => true,
                (Some(a), Some(b)) => Arc::ptr_eq(a, b),
                _ => false,
            };
            if same {
                return;
            }
            let now = Instant::now();
            st.started = now;
            let idle = effect.is_none();
            if idle {
                st.next_tick = None;
            } else if st.next_tick.is_none() {
                st.next_tick = Some(now);
            }
            st.effect = effect;
            self.shared.wake.notify_all();
            idle
        };
        if went_idle {
            (self.shared.sink)(RgbFrame::solid(RgbColor::BLACK));
        }
    }

    /// The active effect.
    pub(crate) fn active(&self) -> Option<Arc<dyn RgbEffect>> {
        self.shared.state.lock().effect.clone()
    }

    /// Clears the effect and ends the thread (no black frame: backends paint their own on
    /// shutdown). The engine is unusable afterwards.
    pub(crate) fn shutdown(&self) {
        {
            let mut st = self.shared.state.lock();
            st.effect = None;
            st.next_tick = None;
            st.shutdown = true;
            self.shared.wake.notify_all();
        }
        let handle = self.thread.lock().take();
        if let Some(h) = handle
            && h.thread().id() != std::thread::current().id()
        {
            let _ = h.join();
        }
    }
}

fn run(shared: Arc<Shared>) {
    let mut st = shared.state.lock();
    loop {
        if st.shutdown {
            return;
        }
        let (Some(effect), Some(due)) = (st.effect.clone(), st.next_tick) else {
            shared.wake.wait(&mut st);
            continue;
        };
        let now = Instant::now();
        if now < due {
            shared.wake.wait_until(&mut st, due);
            continue;
        }
        let elapsed = now.saturating_duration_since(st.started).as_millis() as u64;
        // Fixed rate: the next tick is due one interval after this one was due, skipping
        // (not bursting) any ticks missed while the machine was busy.
        let mut next = due + TICK;
        while next <= now {
            next += TICK;
        }
        st.next_tick = Some(next);
        drop(st);
        tick(&shared, &*effect, elapsed);
        st = shared.state.lock();
    }
}

fn tick(shared: &Shared, effect: &dyn RgbEffect, elapsed_ms: u64) {
    match catch_unwind(AssertUnwindSafe(|| effect.frame_at(elapsed_ms))) {
        Ok(Some(frame)) => (shared.sink)(frame),
        Ok(None) => {}
        Err(_) => {
            let name = catch_unwind(AssertUnwindSafe(|| effect.name())).unwrap_or_default();
            tracing::warn!("{}", mmcl_i18n::tf!("log.rgb.effectThrewAtTick", name));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::effect::SolidEffect;

    fn collecting() -> (FrameSink, Arc<Mutex<Vec<RgbFrame>>>) {
        let frames = Arc::new(Mutex::new(Vec::new()));
        let f2 = frames.clone();
        (Arc::new(move |f| f2.lock().push(f)), frames)
    }

    struct Panicky;
    impl RgbEffect for Panicky {
        fn name(&self) -> String {
            "boom".into()
        }
        fn frame_at(&self, _: u64) -> Option<RgbFrame> {
            panic!("effect failure")
        }
    }

    #[test]
    fn ticks_while_active_and_sends_black_when_idled() {
        let (sink, frames) = collecting();
        let e = EffectEngine::new(sink);
        let red = RgbColor::new(255, 0, 0);
        e.set_effect(Some(Arc::new(SolidEffect::new("r", red))));
        std::thread::sleep(Duration::from_millis(150));
        e.set_effect(None);
        let got = frames.lock().clone();
        assert!(got.len() >= 2, "expected several ticks, got {}", got.len());
        assert_eq!(got[0], RgbFrame::solid(red));
        assert_eq!(*got.last().unwrap(), RgbFrame::solid(RgbColor::BLACK));
        assert!(e.active().is_none());
        // Idle: no more frames.
        let n = frames.lock().len();
        std::thread::sleep(Duration::from_millis(100));
        assert_eq!(frames.lock().len(), n);
        e.shutdown();
    }

    #[test]
    fn same_instance_is_a_no_op_and_panics_are_contained() {
        let (sink, frames) = collecting();
        let e = EffectEngine::new(sink);
        let effect: Arc<dyn RgbEffect> = Arc::new(Panicky);
        e.set_effect(Some(effect.clone()));
        e.set_effect(Some(effect.clone()));
        std::thread::sleep(Duration::from_millis(80));
        assert!(frames.lock().is_empty());
        assert!(
            Arc::ptr_eq(&e.active().unwrap(), &effect),
            "a panicking effect stays active"
        );
        // None -> None sends nothing either.
        e.shutdown();
        e.set_effect(None);
        assert!(frames.lock().is_empty());
    }
}

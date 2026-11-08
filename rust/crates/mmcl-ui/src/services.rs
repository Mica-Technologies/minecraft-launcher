// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! What the shell needs from the rest of the launcher, and how screens reach the engine.
//!
//! - [`ShellBackend`]: the few operations the shell itself performs (game-session checks for the
//!   quit guard, saved window bounds, the appearance settings). [`mmcl_engine::Engine`]
//!   implements it; tests pass a fake.
//! - [`engine`] / [`try_engine`]: the [`Engine`] for screen code (`UiEngine` global).
//! - [`EngineEvents`]: the engine's event bus delivered on the GPUI foreground thread. Screens
//!   subscribe with `cx.subscribe(&mmcl_ui::events(cx)?, |this, _, ev: &UiEvent, cx| ..)`.

use std::rc::Rc;

use gpui::{App, AppContext as _, Entity, EventEmitter, Global, Task};
use mmcl_engine::{Engine, EngineEvent, EngineTask, Setting, WindowBounds};

/// The appearance settings the shell applies (a slice of [`mmcl_engine::Settings`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ShellSettings {
    /// The theme config string (`"Native (Mica)"`, `"Dark"`, ...).
    pub theme: String,
    /// The interface scale percent.
    pub ui_scale_percent: i32,
    /// Reduce motion.
    pub reduce_motion: bool,
    /// The wavy progress style.
    pub wavy_progress: bool,
    /// Whether windows may be resized.
    pub resizable_windows: bool,
}

impl Default for ShellSettings {
    fn default() -> Self {
        ShellSettings {
            theme: mmcl_theme::ThemeId::DEFAULT.config_value().to_string(),
            ui_scale_percent: mmcl_ui_kit::scale::DEFAULT_PERCENT,
            reduce_motion: false,
            wavy_progress: true,
            resizable_windows: true,
        }
    }
}

/// The operations the shell performs on the launcher's services.
pub trait ShellBackend: 'static {
    /// How many game sessions are preparing or running.
    fn active_session_count(&self) -> usize;
    /// Cancels and stops every active session (the "Stop games and quit" answer). Must not block.
    fn stop_active_sessions(&self);
    /// The saved main-window placement.
    fn window_bounds(&self) -> WindowBounds;
    /// Saves the main-window placement.
    fn set_window_bounds(&self, bounds: WindowBounds);
    /// The appearance settings.
    fn shell_settings(&self) -> ShellSettings;
    /// Discord "in the menus" presence for a screen (no-op when Discord is off).
    fn set_menu_presence(&self, _screen: &str) {}
}

impl ShellBackend for Engine {
    fn active_session_count(&self) -> usize {
        self.active_sessions().len()
    }

    fn stop_active_sessions(&self) {
        for session in self.active_sessions() {
            // Java: `cancel()` then `stop(false)`. Cancel ends a preparing launch at once; stop
            // asks a running game to exit.
            let _ = self.cancel_launch(session.id);
            let engine = self.clone();
            let id = session.id;
            drop(self.spawn(async move {
                if let Err(e) = engine.stop_game(id, false).await {
                    tracing::warn!(
                        "{}",
                        mmcl_i18n::tf!("log.ui.stopSessionFailed", e.message())
                    );
                }
            }));
        }
    }

    fn window_bounds(&self) -> WindowBounds {
        Engine::window_bounds(self)
    }

    fn set_window_bounds(&self, bounds: WindowBounds) {
        Engine::set_window_bounds(self, bounds);
    }

    fn shell_settings(&self) -> ShellSettings {
        let s = self.settings();
        ShellSettings {
            theme: s.theme,
            ui_scale_percent: s.ui_scale_percent,
            reduce_motion: s.reduce_motion,
            wavy_progress: s.wavy_progress,
            resizable_windows: s.resizable_windows,
        }
    }

    fn set_menu_presence(&self, screen: &str) {
        self.set_discord_menu_presence(screen);
    }
}

/// A backend for sessions without services (tests, previews): no games, nothing saved.
#[derive(Debug, Default)]
pub struct NullBackend;

impl ShellBackend for NullBackend {
    fn active_session_count(&self) -> usize {
        0
    }
    fn stop_active_sessions(&self) {}
    fn window_bounds(&self) -> WindowBounds {
        WindowBounds {
            x: f64::NAN,
            y: f64::NAN,
            width: f64::NAN,
            height: f64::NAN,
            maximized: false,
        }
    }
    fn set_window_bounds(&self, _bounds: WindowBounds) {}
    fn shell_settings(&self) -> ShellSettings {
        ShellSettings::default()
    }
}

/// The installed backend.
#[derive(Clone)]
pub struct Backend(pub Rc<dyn ShellBackend>);

impl Global for Backend {}

/// The shell backend ([`NullBackend`] when none is installed).
pub fn backend(cx: &App) -> Rc<dyn ShellBackend> {
    cx.try_global::<Backend>()
        .map(|b| b.0.clone())
        .unwrap_or_else(|| Rc::new(NullBackend))
}

/// The engine, for screen code.
#[derive(Clone)]
pub struct UiEngine(pub Engine);

impl Global for UiEngine {}

/// The engine, when this session has one (GUI sessions always do; some tests do not).
pub fn try_engine(cx: &App) -> Option<Engine> {
    cx.try_global::<UiEngine>().map(|e| e.0.clone())
}

/// The engine. Panics when [`crate::init`] ran without one; screen code in a real session
/// can rely on it, code that may run in tests should use [`try_engine`].
pub fn engine(cx: &App) -> Engine {
    try_engine(cx).expect("mmcl-ui: no engine installed (call mmcl_ui::init with an Engine)")
}

/// Runs an engine operation on the engine's runtime: `run(cx, |e| async move { e.op().await })`.
/// Await the returned task from a GPUI `cx.spawn`; `None` when there is no engine.
pub fn run<T, F, Fut>(cx: &App, op: F) -> Option<EngineTask<T>>
where
    T: Send + 'static,
    F: FnOnce(Engine) -> Fut,
    Fut: std::future::Future<Output = T> + Send + 'static,
{
    let engine = try_engine(cx)?;
    let fut = op(engine.clone());
    Some(engine.spawn(fut))
}

/// What [`EngineEvents`] emits.
#[derive(Clone, Debug)]
pub enum UiEvent {
    /// An engine event.
    Engine(EngineEvent),
    /// The receiver fell behind and events were dropped: re-read whatever you show.
    Lagged,
}

impl UiEvent {
    /// Whether this is a [`EngineEvent::SettingChanged`] for `setting`'s key.
    pub fn is_setting(&self, setting: &Setting) -> bool {
        matches!(self, UiEvent::Engine(EngineEvent::SettingChanged(k)) if *k == setting.key())
    }
}

/// The engine bus on the foreground thread.
pub struct EngineEvents {
    _pump: Task<()>,
}

impl EventEmitter<UiEvent> for EngineEvents {}

/// The [`EngineEvents`] entity of this session.
#[derive(Clone)]
pub struct EventsGlobal(pub Entity<EngineEvents>);

impl Global for EventsGlobal {}

/// The session's event entity (none without an engine).
pub fn events(cx: &App) -> Option<Entity<EngineEvents>> {
    cx.try_global::<EventsGlobal>().map(|g| g.0.clone())
}

impl EngineEvents {
    /// Starts pumping `engine`'s bus onto the foreground and installs the global.
    pub fn install(cx: &mut App, engine: &Engine) -> Entity<EngineEvents> {
        let mut rx = engine.subscribe();
        let entity = cx.new(|cx| {
            let pump = cx.spawn(async move |this, cx| {
                use tokio::sync::broadcast::error::RecvError;
                loop {
                    // tokio's broadcast channel needs no tokio runtime to be awaited.
                    let event = match rx.recv().await {
                        Ok(ev) => UiEvent::Engine(ev),
                        Err(RecvError::Lagged(_)) => UiEvent::Lagged,
                        Err(RecvError::Closed) => break,
                    };
                    let alive = this
                        .update(cx, |_, cx| {
                            if let UiEvent::Engine(EngineEvent::OfflineChanged(off)) = &event {
                                mmcl_ui_kit::controls::set_offline(cx, *off);
                            }
                            cx.emit(event);
                        })
                        .is_ok();
                    if !alive {
                        break;
                    }
                }
            });
            EngineEvents { _pump: pump }
        });
        cx.set_global(EventsGlobal(entity.clone()));
        entity
    }
}

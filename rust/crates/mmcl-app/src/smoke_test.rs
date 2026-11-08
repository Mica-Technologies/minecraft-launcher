// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Startup smoke test: the whole GUI wiring on GPUI's test platform, so a startup panic (a
//! key binding gpui-pre rejects, such as a `secondary-` chord, a hook that panics, a screen
//! that fails to build or render) fails `cargo test` instead of the first real launch.
//!
//! It runs [`crate::gui::setup_ui`] over a network-free engine in a temp data folder: every UI
//! crate's init, every screen registered, every key binding bound, every hook, dialog, menu and
//! service installed. Then it opens the main window and visits each registered screen, painting
//! each one, and fails if GPUI logged an error while doing so (GPUI reports paint problems such
//! as an SVG drawn at zero size only through its `log` records, once per frame).
//! [`crate::gui::setup_os`] (MCP port, tray, jump list, gamepad thread) is left out: it reaches
//! the real OS.

use std::sync::Mutex;

use gpui::TestAppContext;
use mmcl_core::{DataPaths, GameMode, LauncherArgs};
use mmcl_engine::{Engine, EngineOptions};
use mmcl_ui::{ScreenId, ScreenRegistry, ScreenRequest};

/// GPUI's error-level `log` records seen while the smoke test paints.
static GPUI_ERRORS: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// The error GPUI's `()` asset source gives for every embedded asset.
const TEST_PLATFORM_NO_ASSETS: &str = "Embedded resource not found";

/// Collects GPUI's error-level `log` records. Installed only if no other test in this binary
/// installed a logger first (then the check is skipped rather than flaky).
struct GpuiErrors;

impl log::Log for GpuiErrors {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        metadata.level() == log::Level::Error
    }

    fn log(&self, record: &log::Record) {
        if record.level() == log::Level::Error && record.target().starts_with("gpui") {
            let line = format!("{}: {}", record.target(), record.args());
            // GPUI's test app has no asset source (`TestAppContext` fixes it to `()`), so the
            // bundled images and icons are missing here by construction, not by a bug.
            if line.contains(TEST_PLATFORM_NO_ASSETS) {
                return;
            }
            if let Ok(mut seen) = GPUI_ERRORS.lock() {
                seen.push(line);
            }
        }
    }

    fn flush(&self) {}
}

fn engine() -> (Engine, tempfile::TempDir) {
    let dir = tempfile::tempdir().expect("temp dir");
    // Leaked on purpose: the app's globals hold engine clones until the test app drops, after
    // this function's scope, and a runtime must outlive every handle that may still use it.
    let rt: &'static tokio::runtime::Runtime = Box::leak(Box::new(
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("runtime"),
    ));
    let paths = DataPaths::at_root(dir.path().join("data"), GameMode::Client);
    let engine = Engine::new(EngineOptions::offline_for_tests(paths, rt.handle().clone()))
        .expect("engine starts");
    (engine, dir)
}

/// Repaints every open window.
fn repaint_all(cx: &mut TestAppContext) {
    for window in cx.windows() {
        let _ = cx.update(|cx| window.update(cx, |_, window, _| window.refresh()));
    }
    cx.run_until_parked();
}

#[gpui::test]
fn the_gui_starts_and_every_screen_opens(cx: &mut TestAppContext) {
    let capturing = log::set_logger(&GpuiErrors).is_ok();
    if capturing {
        log::set_max_level(log::LevelFilter::Error);
    }
    // The engine answers on its own tokio threads, which the deterministic test scheduler
    // would otherwise report as foreign activity.
    cx.executor().allow_parking();
    let (engine, _dir) = engine();
    let args = LauncherArgs::parse(&[], false);
    cx.update(|cx| {
        let _mcp = crate::gui::setup_ui(cx, engine.clone(), args);
    });
    cx.run_until_parked();

    let ids = cx.update(|cx| ScreenRegistry::ids(cx));
    for id in [
        ScreenId::HOME,
        ScreenId::BROWSE,
        ScreenId::SETTINGS,
        ScreenId::RUNTIME,
        ScreenId::LOGIN,
        ScreenId::PROGRESS,
        ScreenId::LAUNCH_PROGRESS,
        ScreenId::EDITOR,
    ] {
        assert!(ids.contains(&id), "{id:?} is not registered");
    }

    cx.update(|cx| {
        mmcl_ui::shell::start_gui(cx).expect("the main window opens");
        if let Some(dock) = mmcl_running_games::dock_host(cx) {
            mmcl_ui::shell::set_bottom_dock(cx, Some(dock.into()));
        }
    });
    cx.run_until_parked();

    for id in ids.iter().copied().chain([ScreenId::HOME]) {
        cx.update(|cx| mmcl_ui::shell::go_to(cx, ScreenRequest::new(id)));
        cx.run_until_parked();
        repaint_all(cx);
        let gui = cx.update(|cx| mmcl_ui::gui_handle(cx).expect("controller"));
        assert_eq!(gui.current_screen(), Some(id), "navigating to {id:?}");
        if capturing {
            let errors = std::mem::take(&mut *GPUI_ERRORS.lock().expect("error list"));
            assert!(
                errors.is_empty(),
                "GPUI logged errors while painting {id:?}: {errors:#?}"
            );
        }
    }

    // The menu bar and dock menu rebuild from real engine state.
    cx.update(mmcl_ui::shell::menu_bar::refresh_now);
    cx.run_until_parked();

    // An in-process restart (Save & Restart) rebuilds the main window over the same wiring.
    cx.update(|cx| {
        mmcl_ui::shell::exit(cx);
        mmcl_ui::shell::start_gui(cx).expect("the main window reopens");
        mmcl_ui::shell::go_to(cx, ScreenRequest::new(ScreenId::HOME));
    });
    cx.run_until_parked();
}

#[gpui::test]
fn every_key_binding_builds(cx: &mut TestAppContext) {
    // Each crate's bind call, once more in isolation, so the failing crate is obvious.
    cx.update(|cx| {
        mmcl_editor::screen::bind_keys(cx);
        mmcl_ui::actions::bind_global_keys(cx);
    });
}

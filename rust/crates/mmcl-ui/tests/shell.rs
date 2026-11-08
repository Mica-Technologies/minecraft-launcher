// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Shell behaviour in a GPUI test app: the screen lifecycle, the navigation gate, the close /
//! quit flow, the thread-safe handle, bounds persistence on exit, theme and scale hookup.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use gpui::{App, Context, IntoElement, Render, SharedString, Task, TestAppContext, Window, div};
use mmcl_engine::WindowBounds;
use mmcl_theme::TokenSheet;
use mmcl_ui::services::{ShellBackend, ShellSettings};
use mmcl_ui::{Screen, ScreenId, ScreenRegistry, ScreenRequest, UiOptions, shell};
use mmcl_ui_kit::{ActiveTheme, Navigator};

type Log = Rc<RefCell<Vec<String>>>;

#[derive(Default)]
struct FakeBackend {
    sessions: Cell<usize>,
    stopped: Cell<usize>,
    theme: RefCell<String>,
    saved: RefCell<Vec<WindowBounds>>,
    initial: Cell<Option<(f64, f64, f64, f64)>>,
}

impl ShellBackend for FakeBackend {
    fn active_session_count(&self) -> usize {
        self.sessions.get()
    }
    fn stop_active_sessions(&self) {
        self.stopped.set(self.stopped.get() + 1);
    }
    fn window_bounds(&self) -> WindowBounds {
        let (x, y, width, height) =
            self.initial
                .get()
                .unwrap_or((f64::NAN, f64::NAN, f64::NAN, f64::NAN));
        WindowBounds {
            x,
            y,
            width,
            height,
            maximized: false,
        }
    }
    fn set_window_bounds(&self, bounds: WindowBounds) {
        self.saved.borrow_mut().push(bounds);
    }
    fn shell_settings(&self) -> ShellSettings {
        ShellSettings {
            theme: self.theme.borrow().clone(),
            ..ShellSettings::default()
        }
    }
}

struct TestScreen {
    id: ScreenId,
    log: Log,
    allows: bool,
    agree: bool,
    panic_on_cleanup: bool,
}

impl Render for TestScreen {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        div()
    }
}

impl Screen for TestScreen {
    fn id(&self) -> ScreenId {
        self.id
    }
    fn title(&self, _: &App) -> SharedString {
        self.id.as_str().to_string().into()
    }
    fn allows_toolbar_navigation(&self) -> bool {
        self.allows
    }
    fn confirm_navigate_away(&mut self, _: &mut Window, _: &mut Context<Self>) -> Task<bool> {
        self.log
            .borrow_mut()
            .push(format!("{}.confirm", self.id.as_str()));
        Task::ready(self.agree)
    }
    fn setup(&mut self, _: &mut Window, _: &mut Context<Self>) {
        self.log
            .borrow_mut()
            .push(format!("{}.setup", self.id.as_str()));
    }
    fn after_show(&mut self, _: &mut Window, _: &mut Context<Self>) {
        self.log
            .borrow_mut()
            .push(format!("{}.after_show", self.id.as_str()));
    }
    fn cleanup(&mut self, _: &mut Context<Self>) {
        self.log
            .borrow_mut()
            .push(format!("{}.cleanup", self.id.as_str()));
        if self.panic_on_cleanup {
            panic!("cleanup exploded");
        }
    }
}

#[derive(Clone, Copy)]
struct Behaviour {
    allows: bool,
    agree: bool,
    panic_on_cleanup: bool,
}

const NORMAL: Behaviour = Behaviour {
    allows: true,
    agree: true,
    panic_on_cleanup: false,
};

fn register(cx: &mut App, id: ScreenId, log: &Log, b: Behaviour) {
    let log = log.clone();
    ScreenRegistry::register_view(cx, id, move |_, _, _| TestScreen {
        id,
        log: log.clone(),
        allows: b.allows,
        agree: b.agree,
        panic_on_cleanup: b.panic_on_cleanup,
    });
}

fn setup(cx: &mut TestAppContext, backend: Rc<FakeBackend>) -> Log {
    let log: Log = Rc::default();
    cx.update(|cx| {
        if backend.theme.borrow().is_empty() {
            *backend.theme.borrow_mut() = "Dark".into();
        }
        mmcl_ui::init_with(cx, UiOptions::with_backend(backend.clone()));
        shell::start_gui(cx).expect("window opens");
    });
    cx.run_until_parked();
    log
}

fn go(cx: &mut TestAppContext, id: ScreenId) {
    cx.update(|cx| shell::go_to(cx, ScreenRequest::new(id)));
    cx.run_until_parked();
}

fn current(cx: &mut TestAppContext) -> Option<ScreenId> {
    cx.update(|cx| Navigator::current(cx))
}

#[gpui::test]
fn navigation_runs_the_lifecycle_in_order(cx: &mut TestAppContext) {
    let backend = Rc::new(FakeBackend::default());
    let log = setup(cx, backend);
    cx.update(|cx| {
        register(cx, ScreenId::HOME, &log, NORMAL);
        register(cx, ScreenId::BROWSE, &log, NORMAL);
    });
    go(cx, ScreenId::HOME);
    go(cx, ScreenId::BROWSE);
    assert_eq!(
        *log.borrow(),
        vec![
            "home.setup",
            "home.after_show",
            "home.cleanup",
            "browse.setup",
            "browse.after_show"
        ]
    );
    assert_eq!(current(cx), Some(ScreenId::BROWSE));
    let handle = cx.update(|cx| mmcl_ui::gui_handle(cx)).unwrap();
    assert_eq!(handle.current_screen(), Some(ScreenId::BROWSE));
    assert!(handle.is_started());
    assert!(handle.may_leave_for_launch());
}

#[gpui::test]
fn a_failing_cleanup_never_blocks_the_next_screen(cx: &mut TestAppContext) {
    let backend = Rc::new(FakeBackend::default());
    let log = setup(cx, backend);
    cx.update(|cx| {
        register(
            cx,
            ScreenId::HOME,
            &log,
            Behaviour {
                panic_on_cleanup: true,
                ..NORMAL
            },
        );
        register(cx, ScreenId::SETTINGS, &log, NORMAL);
    });
    go(cx, ScreenId::HOME);
    go(cx, ScreenId::SETTINGS);
    assert_eq!(current(cx), Some(ScreenId::SETTINGS));
    assert!(log.borrow().contains(&"settings.after_show".to_string()));
}

#[gpui::test]
fn the_gate_keeps_screens_that_refuse(cx: &mut TestAppContext) {
    let backend = Rc::new(FakeBackend::default());
    let log = setup(cx, backend);
    cx.update(|cx| {
        register(
            cx,
            ScreenId::LOGIN,
            &log,
            Behaviour {
                allows: false,
                ..NORMAL
            },
        );
        register(
            cx,
            ScreenId::SETTINGS,
            &log,
            Behaviour {
                agree: false,
                ..NORMAL
            },
        );
        register(cx, ScreenId::HOME, &log, NORMAL);
    });

    // Login disallows toolbar navigation: a gated request is ignored without asking.
    go(cx, ScreenId::LOGIN);
    cx.update(|cx| shell::navigate_gated(cx, ScreenRequest::new(ScreenId::HOME)));
    cx.run_until_parked();
    assert_eq!(current(cx), Some(ScreenId::LOGIN));
    assert!(!cx.update(|cx| shell::may_leave_for_launch(cx)));
    let handle = cx.update(|cx| mmcl_ui::gui_handle(cx)).unwrap();
    assert!(!handle.may_leave_for_launch());

    // Settings is asked and says no.
    go(cx, ScreenId::SETTINGS);
    cx.update(|cx| shell::navigate_gated(cx, ScreenRequest::new(ScreenId::HOME)));
    cx.run_until_parked();
    assert_eq!(current(cx), Some(ScreenId::SETTINGS));
    assert!(log.borrow().contains(&"settings.confirm".to_string()));

    // The session flow (ungated) still goes through.
    go(cx, ScreenId::HOME);
    assert_eq!(current(cx), Some(ScreenId::HOME));
}

#[gpui::test]
fn unregistered_screens_leave_the_current_one(cx: &mut TestAppContext) {
    let backend = Rc::new(FakeBackend::default());
    let log = setup(cx, backend);
    cx.update(|cx| register(cx, ScreenId::HOME, &log, NORMAL));
    go(cx, ScreenId::HOME);
    go(cx, ScreenId("does-not-exist"));
    assert_eq!(current(cx), Some(ScreenId::HOME));
}

#[gpui::test]
fn quit_without_games_closes_at_once(cx: &mut TestAppContext) {
    let backend = Rc::new(FakeBackend::default());
    let _log = setup(cx, backend.clone());
    let closed = Rc::new(Cell::new(0));
    let c = closed.clone();
    cx.update(|cx| {
        shell::set_close_app_handler(cx, move |_| c.set(c.get() + 1));
        shell::request_quit(cx);
    });
    cx.run_until_parked();
    assert_eq!(closed.get(), 1);
    assert_eq!(backend.stopped.get(), 0);
}

#[gpui::test]
fn quit_with_games_waits_for_an_answer(cx: &mut TestAppContext) {
    let backend = Rc::new(FakeBackend::default());
    backend.sessions.set(2);
    let _log = setup(cx, backend.clone());
    let closed = Rc::new(Cell::new(0));
    let c = closed.clone();
    cx.update(|cx| {
        shell::set_close_app_handler(cx, move |_| c.set(c.get() + 1));
        shell::request_quit(cx);
    });
    cx.run_until_parked();
    assert_eq!(closed.get(), 0, "the question is open; nothing closed yet");
    assert_eq!(backend.stopped.get(), 0);
}

#[gpui::test]
fn the_handle_reaches_the_foreground(cx: &mut TestAppContext) {
    let backend = Rc::new(FakeBackend::default());
    let log = setup(cx, backend);
    cx.update(|cx| register(cx, ScreenId::RUNTIME, &log, NORMAL));
    let handle = cx.update(|cx| mmcl_ui::gui_handle(cx)).unwrap();
    let shown = Rc::new(Cell::new(None));
    let s = shown.clone();
    let h = handle.clone();
    cx.update(|cx| {
        cx.spawn(async move |_| {
            let ok = h.go_to(ScreenId::RUNTIME).await;
            let value = h.run_with(|cx| Navigator::current(cx)).await;
            s.set(Some((ok, value.flatten())));
        })
        .detach();
    });
    cx.run_until_parked();
    assert_eq!(shown.get(), Some((true, Some(ScreenId::RUNTIME))));
    // Without games, the quit confirmation answers yes without a dialog.
    let answer = Rc::new(Cell::new(None));
    let a = answer.clone();
    cx.update(|cx| {
        cx.spawn(async move |_| a.set(Some(handle.confirm_quit_while_games_run().await)))
            .detach();
    });
    cx.run_until_parked();
    assert_eq!(answer.get(), Some(true));
}

#[gpui::test]
fn exit_cleans_up_and_saves_bounds(cx: &mut TestAppContext) {
    let backend = Rc::new(FakeBackend::default());
    backend.initial.set(Some((40.0, 50.0, 1000.0, 800.0)));
    let log = setup(cx, backend.clone());
    cx.update(|cx| register(cx, ScreenId::HOME, &log, NORMAL));
    go(cx, ScreenId::HOME);
    let exited = Rc::new(Cell::new(false));
    let e = exited.clone();
    cx.update(|cx| {
        shell::on_exit(cx, move |_| e.set(true));
        shell::exit(cx);
    });
    cx.run_until_parked();
    assert!(exited.get());
    assert!(log.borrow().contains(&"home.cleanup".to_string()));
    assert!(!cx.update(|cx| shell::is_started(cx)));
    assert_eq!(current(cx), None);
    let saved = backend.saved.borrow();
    let last = saved.last().expect("bounds flushed on exit");
    assert_eq!((last.width, last.height), (1000.0, 800.0));
    drop(saved);

    // An in-process restart opens a fresh window.
    cx.update(|cx| shell::start_gui(cx).expect("reopens"));
    go(cx, ScreenId::HOME);
    assert_eq!(current(cx), Some(ScreenId::HOME));
}

#[gpui::test]
fn theme_follows_the_configured_value(cx: &mut TestAppContext) {
    let backend = Rc::new(FakeBackend::default());
    *backend.theme.borrow_mut() = "Light".into();
    let _log = setup(cx, backend.clone());
    let sheet = |cx: &mut TestAppContext| cx.update(|cx| cx.global::<ActiveTheme>().sheet());
    assert_eq!(sheet(cx), TokenSheet::Light);
    let refreshed = Rc::new(Cell::new(0));
    let r = refreshed.clone();
    cx.update(|cx| shell::on_theme_changed(cx, move |_| r.set(r.get() + 1)));
    *backend.theme.borrow_mut() = "Creeper".into();
    cx.update(shell::force_theme_refresh);
    assert_eq!(sheet(cx), TokenSheet::Creeper);
    assert_eq!(refreshed.get(), 1);
}

#[gpui::test]
fn scale_changes_apply(cx: &mut TestAppContext) {
    let backend = Rc::new(FakeBackend::default());
    let _log = setup(cx, backend);
    cx.update(|cx| mmcl_ui::scale::set_ui_scale(cx, 130));
    assert_eq!(cx.update(|cx| mmcl_ui::scale::percent(cx)), 125);
}

#[gpui::test]
fn crate_screens_register_with_their_identities(cx: &mut TestAppContext) {
    let backend = Rc::new(FakeBackend::default());
    let _log = setup(cx, backend);
    cx.update(mmcl_ui::screens::register_all);
    for id in [
        ScreenId::HOME,
        ScreenId::BROWSE,
        ScreenId::SETTINGS,
        ScreenId::RUNTIME,
        ScreenId::LOGIN,
        ScreenId::PROGRESS,
        ScreenId::LAUNCH_PROGRESS,
    ] {
        assert!(cx.update(|cx| ScreenRegistry::contains(cx, id)), "{id:?}");
        go(cx, id);
        assert_eq!(current(cx), Some(id));
        let allows = cx.update(|cx| {
            shell::current_screen(cx)
                .unwrap()
                .allows_toolbar_navigation(cx)
        });
        let expected = matches!(id, ScreenId::HOME | ScreenId::BROWSE | ScreenId::SETTINGS);
        assert_eq!(allows, expected, "{id:?}");
    }
}

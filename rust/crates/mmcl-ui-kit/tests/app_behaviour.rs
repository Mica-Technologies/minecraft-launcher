// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Behaviour of the services that need a GPUI app: headless dialogs, the navigator, the screen
//! registry and lifecycle, the theme global and the offline state.

use std::cell::RefCell;
use std::rc::Rc;

use gpui::{App, Context, IntoElement, Render, SharedString, TestAppContext, Window, div};
use mmcl_theme::{Platform, Theme, ThemeId};
use mmcl_ui_kit::controls::{is_offline, set_offline};
use mmcl_ui_kit::dialogs::{self, Answer};
use mmcl_ui_kit::{
    ActiveTheme, HelpTopic, Navigator, Screen, ScreenId, ScreenRegistry, ScreenRequest, ThemeExt,
};

#[gpui::test]
async fn dialogs_answer_at_once_without_a_gui(cx: &mut TestAppContext) {
    // Server / TUI mode: `dialogs::init` never ran.
    let async_cx = cx.to_async();
    assert!(!cx.update(|cx| dialogs::is_enabled(cx)));
    let answer = dialogs::ask(&async_cx, "t", "h", "b", "Yes", "No", None).await;
    assert_eq!(answer, Answer::Closed);
    assert!(!dialogs::error_retry(&async_cx, "x", None, "Retry").await);
    assert_eq!(
        dialogs::input(&async_cx, "t", "", "l", "v", None).await,
        None
    );
    assert_eq!(
        dialogs::choose(&async_cx, "t", "", "l", vec!["a".into()], None).await,
        None
    );
    dialogs::error(&async_cx, "boom", None).await;
}

#[gpui::test]
async fn dialogs_with_no_window_return_the_default(cx: &mut TestAppContext) {
    cx.update(dialogs::init);
    let async_cx = cx.to_async();
    assert_eq!(
        dialogs::ask(&async_cx, "t", "h", "b", "Yes", "No", None).await,
        Answer::Closed
    );
}

#[gpui::test]
fn navigator_without_a_shell_ignores_requests(cx: &mut TestAppContext) {
    cx.update(|cx| {
        assert!(!Navigator::go_to(cx, ScreenRequest::new(ScreenId::HOME)));
        assert_eq!(Navigator::current(cx), None);
    });
}

#[gpui::test]
fn navigator_defers_to_the_shell(cx: &mut TestAppContext) {
    type Seen = Vec<(ScreenId, Option<usize>)>;
    let seen: Rc<RefCell<Seen>> = Rc::default();
    let sink = seen.clone();
    cx.update(|cx| {
        Navigator::set_handler(
            cx,
            Rc::new(move |mut req, cx: &mut App| {
                let category = req.take_payload::<usize>();
                sink.borrow_mut().push((req.id, category));
                Navigator::set_current(cx, Some(req.id));
            }),
        );
        assert!(Navigator::go_to(
            cx,
            ScreenRequest::with_payload(ScreenId::SETTINGS, 2usize)
        ));
    });
    cx.run_until_parked();
    assert_eq!(*seen.borrow(), vec![(ScreenId::SETTINGS, Some(2))]);
    cx.update(|cx| assert_eq!(Navigator::current(cx), Some(ScreenId::SETTINGS)));
}

struct TestScreen {
    log: Rc<RefCell<Vec<&'static str>>>,
    dirty: bool,
}

impl Render for TestScreen {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        div()
    }
}

impl Screen for TestScreen {
    fn id(&self) -> ScreenId {
        ScreenId::EDITOR
    }
    fn title(&self, _: &App) -> SharedString {
        "Editor".into()
    }
    fn help_topic(&self) -> HelpTopic {
        HelpTopic::ModpackEditor
    }
    fn setup(&mut self, _: &mut Window, _: &mut Context<Self>) {
        self.log.borrow_mut().push("setup");
    }
    fn after_show(&mut self, _: &mut Window, _: &mut Context<Self>) {
        self.log.borrow_mut().push("after_show");
    }
    fn cleanup(&mut self, _: &mut Context<Self>) {
        self.log.borrow_mut().push("cleanup");
    }
    fn confirm_navigate_away(&mut self, _: &mut Window, _: &mut Context<Self>) -> gpui::Task<bool> {
        gpui::Task::ready(!self.dirty)
    }
}

struct Root;

impl Render for Root {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        div()
    }
}

#[gpui::test]
async fn registry_builds_screens_and_runs_their_lifecycle(cx: &mut TestAppContext) {
    let log: Rc<RefCell<Vec<&'static str>>> = Rc::default();
    let factory_log = log.clone();
    cx.update(|cx| {
        ScreenRegistry::register_view(cx, ScreenId::EDITOR, move |mut req, _, _| TestScreen {
            log: factory_log.clone(),
            dirty: req.take_payload::<bool>().unwrap_or(false),
        });
        assert!(ScreenRegistry::contains(cx, ScreenId::EDITOR));
        assert!(!ScreenRegistry::contains(cx, ScreenId::HOME));
        assert_eq!(ScreenRegistry::ids(cx), vec![ScreenId::EDITOR]);
    });
    let (_root, vcx) = cx.add_window_view(|_, _| Root);
    let handle = vcx.update(|window, cx| {
        let handle = ScreenRegistry::build(
            ScreenRequest::with_payload(ScreenId::EDITOR, true),
            window,
            cx,
        )
        .expect("registered");
        assert!(ScreenRegistry::build(ScreenRequest::new(ScreenId::HOME), window, cx).is_none());
        handle.setup(window, cx);
        handle.after_show(window, cx);
        assert_eq!(handle.id(cx), ScreenId::EDITOR);
        assert_eq!(handle.help_topic(cx), HelpTopic::ModpackEditor);
        assert_eq!(handle.window_title("Mica", cx).as_ref(), "Mica | Editor");
        assert!(handle.allows_toolbar_navigation(cx));
        assert!(handle.downcast::<TestScreen>().is_some());
        handle
    });
    let stay = vcx.update(|window, cx| handle.confirm_navigate_away(window, cx));
    assert!(!stay.await, "a dirty screen refuses to be left");
    vcx.update(|_, cx| handle.cleanup(cx));
    assert_eq!(*log.borrow(), vec!["setup", "after_show", "cleanup"]);
}

#[gpui::test]
fn theme_and_offline_globals(cx: &mut TestAppContext) {
    cx.update(|cx| {
        // Default before anything is installed.
        assert_eq!(cx.launcher_theme().theme.id, ThemeId::DEFAULT);
        let light = ActiveTheme {
            theme: Theme::new(ThemeId::Light, true, Platform::Linux),
            mica_supported: false,
        };
        mmcl_ui_kit::set_theme(cx, light);
        assert_eq!(cx.launcher_theme(), light);
        assert_eq!(cx.tokens().md_primary, light.tokens().md_primary);

        assert!(!is_offline(cx));
        set_offline(cx, true);
        assert!(is_offline(cx));
        set_offline(cx, false);
        assert!(!is_offline(cx));
    });
}

#[gpui::test]
fn avatars_without_a_fetcher_fail_and_retry(cx: &mut TestAppContext) {
    use mmcl_ui_kit::images::AvatarState;
    cx.update(|cx| {
        assert!(mmcl_ui_kit::Avatars::get(cx, "  ").is_none());
        assert!(matches!(
            mmcl_ui_kit::Avatars::get(cx, "abc"),
            Some(AvatarState::Failed)
        ));
    });
    // With a fetcher, a failed avatar is fetched again on the next request.
    let counter = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let seen = counter.clone();
    cx.update(|cx| {
        mmcl_ui_kit::Avatars::set_fetcher(
            cx,
            std::sync::Arc::new(move |_url| {
                seen.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Box::pin(async { Err::<Vec<u8>, String>("offline".into()) })
            }),
        );
        assert!(matches!(
            mmcl_ui_kit::Avatars::get(cx, "abc"),
            Some(AvatarState::Loading)
        ));
    });
    cx.run_until_parked();
    cx.update(|cx| {
        assert!(matches!(
            mmcl_ui_kit::Avatars::get(cx, "abc"),
            Some(AvatarState::Loading)
        ));
    });
    cx.run_until_parked();
    assert_eq!(counter.load(std::sync::atomic::Ordering::SeqCst), 2);
}

#[gpui::test]
async fn themed_dialogs_are_queued_and_cancel_on_close(cx: &mut TestAppContext) {
    use gpui::AppContext as _;
    use gpui_component::WindowExt as _;

    cx.update(|cx| {
        mmcl_ui_kit::init(cx, ActiveTheme::default());
    });
    let window = cx.update(|cx| {
        cx.open_window(Default::default(), |window, cx| {
            let content = cx.new(|_| Root);
            cx.new(|cx| gpui_component::Root::new(content, window, cx))
        })
        .unwrap()
    });
    let any: gpui::AnyWindowHandle = window.into();
    let async_cx = cx.to_async();
    let first = cx.spawn({
        let async_cx = async_cx.clone();
        async move |_| dialogs::ask(&async_cx, "One", "", "first", "Yes", "No", Some(any)).await
    });
    let second = cx.spawn({
        let async_cx = async_cx.clone();
        async move |_| dialogs::ask(&async_cx, "Two", "", "second", "Yes", "No", Some(any)).await
    });
    cx.run_until_parked();
    let open = |cx: &mut TestAppContext| {
        any.update(cx, |_, window, cx| window.has_active_dialog(cx))
            .unwrap()
    };
    assert!(open(cx), "the first dialog shows");
    // Closing it (Esc / overlay) answers Closed and lets the queued one show.
    any.update(cx, |_, window, cx| window.close_dialog(cx))
        .unwrap();
    cx.run_until_parked();
    assert_eq!(first.await, Answer::Closed);
    assert!(open(cx), "the queued dialog shows next");
    any.update(cx, |_, window, cx| window.close_dialog(cx))
        .unwrap();
    cx.run_until_parked();
    assert_eq!(second.await, Answer::Closed);
}

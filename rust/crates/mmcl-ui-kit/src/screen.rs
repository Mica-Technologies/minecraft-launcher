// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Screens (`MCLauncherAbstractGui`), the [`ScreenRegistry`] and the [`Navigator`].
//!
//! `mmcl-ui`, `mmcl-editor` and `mmcl-running-games` do not depend on each other. Each provides
//! screens by implementing [`Screen`] and registering a factory with [`ScreenRegistry`] (done by
//! `mmcl-app`); the shell in `mmcl-ui` installs the [`Navigator`] handler and swaps screens.
//! Anything can ask for a screen with [`Navigator::go_to`] without knowing the screen's crate.
//!
//! Lifecycle, driven by the shell on every swap: the previous screen's
//! [`Screen::cleanup`], then the new screen's [`Screen::setup`], window title / min size / help
//! button from the trait, show (fade-through), then [`Screen::after_show`]. Dropping the view drops
//! its `Subscription`s and `Task`s, so most Java "remove every listener" work is automatic.

use std::any::Any;
use std::collections::HashMap;
use std::rc::Rc;

use gpui::{
    AnyView, App, AppContext as _, Context, Entity, Global, Pixels, Render, SharedString, Size,
    Task, Window, px, size,
};
use mmcl_i18n::t;

/// A screen's identity. Known launcher screens have constants; a crate can define more.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ScreenId(pub &'static str);

impl ScreenId {
    /// Home (installed packs).
    pub const HOME: ScreenId = ScreenId("home");
    /// Browse / game library.
    pub const BROWSE: ScreenId = ScreenId("browse");
    /// Settings.
    pub const SETTINGS: ScreenId = ScreenId("settings");
    /// Runtime management.
    pub const RUNTIME: ScreenId = ScreenId("runtime");
    /// Sign-in.
    pub const LOGIN: ScreenId = ScreenId("login");
    /// Three-label progress.
    pub const PROGRESS: ScreenId = ScreenId("progress");
    /// Launch step progress.
    pub const LAUNCH_PROGRESS: ScreenId = ScreenId("launch-progress");
    /// Modpack editor.
    pub const EDITOR: ScreenId = ScreenId("editor");

    /// The id string.
    pub const fn as_str(self) -> &'static str {
        self.0
    }
}

/// The help topics (`HelpTopic`): display-name key and bundled page path.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum HelpTopic {
    /// Getting started.
    GettingStarted,
    /// Main screen.
    MainScreen,
    /// Login.
    Login,
    /// Settings.
    Settings,
    /// Browse / library.
    BrowseLibrary,
    /// Modpack editor.
    ModpackEditor,
    /// Game console.
    GameConsole,
    /// Vanilla versions.
    VanillaVersions,
    /// Runtime management.
    RuntimeManagement,
    /// Keyboard shortcuts.
    KeyboardShortcuts,
    /// JVM presets.
    JvmPresets,
    /// Action reference.
    ActionReference,
    /// Language & localization.
    Language,
    /// Troubleshooting.
    Troubleshooting,
}

impl HelpTopic {
    /// Every topic, in Java declaration order.
    pub const ALL: [HelpTopic; 14] = [
        HelpTopic::GettingStarted,
        HelpTopic::MainScreen,
        HelpTopic::Login,
        HelpTopic::Settings,
        HelpTopic::BrowseLibrary,
        HelpTopic::ModpackEditor,
        HelpTopic::GameConsole,
        HelpTopic::VanillaVersions,
        HelpTopic::RuntimeManagement,
        HelpTopic::KeyboardShortcuts,
        HelpTopic::JvmPresets,
        HelpTopic::ActionReference,
        HelpTopic::Language,
        HelpTopic::Troubleshooting,
    ];

    /// The display-name localization key.
    pub const fn name_key(self) -> &'static str {
        match self {
            HelpTopic::GettingStarted => "help.topic.name.gettingStarted",
            HelpTopic::MainScreen => "help.topic.name.mainScreen",
            HelpTopic::Login => "help.topic.name.login",
            HelpTopic::Settings => "help.topic.name.settings",
            HelpTopic::BrowseLibrary => "help.topic.name.browse",
            HelpTopic::ModpackEditor => "help.topic.name.modpackEditor",
            HelpTopic::GameConsole => "help.topic.name.gameConsole",
            HelpTopic::VanillaVersions => "help.topic.name.vanillaVersions",
            HelpTopic::RuntimeManagement => "help.topic.name.runtimeManagement",
            HelpTopic::KeyboardShortcuts => "help.topic.name.keyboardShortcuts",
            HelpTopic::JvmPresets => "help.topic.name.jvmPresets",
            HelpTopic::ActionReference => "help.topic.name.actionReference",
            HelpTopic::Language => "help.topic.name.language",
            HelpTopic::Troubleshooting => "help.topic.name.troubleshooting",
        }
    }

    /// The bundled page (`help/<page>.html`, English; the help window localizes the folder).
    pub const fn resource_path(self) -> &'static str {
        match self {
            HelpTopic::GettingStarted => "help/getting-started.html",
            HelpTopic::MainScreen => "help/main-screen.html",
            HelpTopic::Login => "help/login.html",
            HelpTopic::Settings => "help/settings.html",
            HelpTopic::BrowseLibrary => "help/browse-library.html",
            HelpTopic::ModpackEditor => "help/modpack-editor.html",
            HelpTopic::GameConsole => "help/game-console.html",
            HelpTopic::VanillaVersions => "help/vanilla-versions.html",
            HelpTopic::RuntimeManagement => "help/runtime-management.html",
            HelpTopic::KeyboardShortcuts => "help/keyboard-shortcuts.html",
            HelpTopic::JvmPresets => "help/jvm-presets.html",
            HelpTopic::ActionReference => "help/action-reference.html",
            HelpTopic::Language => "help/language.html",
            HelpTopic::Troubleshooting => "help/troubleshooting.html",
        }
    }

    /// The localized display name.
    pub fn display_name(self) -> SharedString {
        t!(self.name_key()).into_owned().into()
    }
}

/// What a screen's own close handler decided when the window close button was pressed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum CloseDecision {
    /// Let the shell close (after its quit-while-games-run check).
    #[default]
    Proceed,
    /// Keep the window open (the screen handled it).
    Keep,
}

/// The main window's default minimum size (layout px at 100 %).
pub const DEFAULT_MIN_SIZE: (f32, f32) = (750.0, 600.0);

/// One screen of the main window.
pub trait Screen: Render + 'static {
    /// The screen's id.
    fn id(&self) -> ScreenId;

    /// The window-title suffix (`getSceneName`).
    fn title(&self, cx: &App) -> SharedString;

    /// The help topic F1 opens.
    fn help_topic(&self) -> HelpTopic {
        HelpTopic::GettingStarted
    }

    /// Whether toolbar / shortcut / menu navigation may leave this screen.
    fn allows_toolbar_navigation(&self) -> bool {
        true
    }

    /// Asked before navigating away (unsaved work); may show a dialog. Resolve `false` to stay.
    fn confirm_navigate_away(
        &mut self,
        _window: &mut Window,
        _cx: &mut Context<Self>,
    ) -> Task<bool> {
        Task::ready(true)
    }

    /// Wire controls and listeners (before the screen is shown).
    fn setup(&mut self, _window: &mut Window, _cx: &mut Context<Self>) {}

    /// After the screen is shown: focus, loads, measurements.
    fn after_show(&mut self, _window: &mut Window, _cx: &mut Context<Self>) {}

    /// When replaced by the next screen, or at exit/restart. Drop subscriptions and tasks and
    /// set any `disposed` flag first so late callbacks bail out.
    fn cleanup(&mut self, _cx: &mut Context<Self>) {}

    /// The minimum window size (layout px at 100 %).
    fn min_size(&self) -> Size<Pixels> {
        size(px(DEFAULT_MIN_SIZE.0), px(DEFAULT_MIN_SIZE.1))
    }

    /// A size the window grows to when this screen shows (login), if any.
    fn pref_size(&self) -> Option<Size<Pixels>> {
        None
    }

    /// `false` makes the shell add its corner help button.
    fn has_own_help_button(&self) -> bool {
        true
    }

    /// The window close button was pressed while this screen showed.
    fn on_close_requested(
        &mut self,
        _window: &mut Window,
        _cx: &mut Context<Self>,
    ) -> CloseDecision {
        CloseDecision::Proceed
    }

    /// Whether global navigation shortcuts apply (Home, Browse, Settings, Runtime install them;
    /// Login and the progress screens do not).
    fn installs_global_shortcuts(&self) -> bool {
        self.allows_toolbar_navigation()
    }
}

/// The object-safe operations the shell needs on any screen.
trait ErasedScreen {
    fn view(&self) -> AnyView;
    fn id(&self, cx: &App) -> ScreenId;
    fn title(&self, cx: &App) -> SharedString;
    fn help_topic(&self, cx: &App) -> HelpTopic;
    fn allows_toolbar_navigation(&self, cx: &App) -> bool;
    fn installs_global_shortcuts(&self, cx: &App) -> bool;
    fn min_size(&self, cx: &App) -> Size<Pixels>;
    fn pref_size(&self, cx: &App) -> Option<Size<Pixels>>;
    fn has_own_help_button(&self, cx: &App) -> bool;
    fn setup(&self, window: &mut Window, cx: &mut App);
    fn after_show(&self, window: &mut Window, cx: &mut App);
    fn cleanup(&self, cx: &mut App);
    fn confirm_navigate_away(&self, window: &mut Window, cx: &mut App) -> Task<bool>;
    fn on_close_requested(&self, window: &mut Window, cx: &mut App) -> CloseDecision;
    fn as_any(&self) -> &dyn Any;
}

impl<S: Screen> ErasedScreen for Entity<S> {
    fn view(&self) -> AnyView {
        self.clone().into()
    }
    fn id(&self, cx: &App) -> ScreenId {
        self.read(cx).id()
    }
    fn title(&self, cx: &App) -> SharedString {
        self.read(cx).title(cx)
    }
    fn help_topic(&self, cx: &App) -> HelpTopic {
        self.read(cx).help_topic()
    }
    fn allows_toolbar_navigation(&self, cx: &App) -> bool {
        self.read(cx).allows_toolbar_navigation()
    }
    fn installs_global_shortcuts(&self, cx: &App) -> bool {
        self.read(cx).installs_global_shortcuts()
    }
    fn min_size(&self, cx: &App) -> Size<Pixels> {
        self.read(cx).min_size()
    }
    fn pref_size(&self, cx: &App) -> Option<Size<Pixels>> {
        self.read(cx).pref_size()
    }
    fn has_own_help_button(&self, cx: &App) -> bool {
        self.read(cx).has_own_help_button()
    }
    fn setup(&self, window: &mut Window, cx: &mut App) {
        self.update(cx, |s, cx| s.setup(window, cx));
    }
    fn after_show(&self, window: &mut Window, cx: &mut App) {
        self.update(cx, |s, cx| s.after_show(window, cx));
    }
    fn cleanup(&self, cx: &mut App) {
        self.update(cx, |s, cx| s.cleanup(cx));
    }
    fn confirm_navigate_away(&self, window: &mut Window, cx: &mut App) -> Task<bool> {
        self.update(cx, |s, cx| s.confirm_navigate_away(window, cx))
    }
    fn on_close_requested(&self, window: &mut Window, cx: &mut App) -> CloseDecision {
        self.update(cx, |s, cx| s.on_close_requested(window, cx))
    }
    fn as_any(&self) -> &dyn Any {
        self
    }
}

/// A type-erased handle to a screen view, what the shell holds as "the current screen".
#[derive(Clone)]
pub struct ScreenHandle {
    inner: Rc<dyn ErasedScreen>,
}

impl ScreenHandle {
    /// Wraps a screen entity.
    pub fn new<S: Screen>(entity: Entity<S>) -> Self {
        ScreenHandle {
            inner: Rc::new(entity),
        }
    }

    /// The view to render.
    pub fn view(&self) -> AnyView {
        self.inner.view()
    }

    /// The screen's id.
    pub fn id(&self, cx: &App) -> ScreenId {
        self.inner.id(cx)
    }

    /// The window-title suffix.
    pub fn title(&self, cx: &App) -> SharedString {
        self.inner.title(cx)
    }

    /// The full window title: `<app name> | <screen title>`.
    pub fn window_title(&self, app_name: &str, cx: &App) -> SharedString {
        window_title(app_name, &self.title(cx))
    }

    /// The help topic.
    pub fn help_topic(&self, cx: &App) -> HelpTopic {
        self.inner.help_topic(cx)
    }

    /// Whether toolbar navigation may leave it.
    pub fn allows_toolbar_navigation(&self, cx: &App) -> bool {
        self.inner.allows_toolbar_navigation(cx)
    }

    /// Whether global shortcuts apply.
    pub fn installs_global_shortcuts(&self, cx: &App) -> bool {
        self.inner.installs_global_shortcuts(cx)
    }

    /// Minimum window size.
    pub fn min_size(&self, cx: &App) -> Size<Pixels> {
        self.inner.min_size(cx)
    }

    /// Preferred window size.
    pub fn pref_size(&self, cx: &App) -> Option<Size<Pixels>> {
        self.inner.pref_size(cx)
    }

    /// Whether it draws its own help button.
    pub fn has_own_help_button(&self, cx: &App) -> bool {
        self.inner.has_own_help_button(cx)
    }

    /// Runs `setup`.
    pub fn setup(&self, window: &mut Window, cx: &mut App) {
        self.inner.setup(window, cx);
    }

    /// Runs `after_show`.
    pub fn after_show(&self, window: &mut Window, cx: &mut App) {
        self.inner.after_show(window, cx);
    }

    /// Runs `cleanup`.
    pub fn cleanup(&self, cx: &mut App) {
        self.inner.cleanup(cx);
    }

    /// Asks the screen whether it may be left.
    pub fn confirm_navigate_away(&self, window: &mut Window, cx: &mut App) -> Task<bool> {
        self.inner.confirm_navigate_away(window, cx)
    }

    /// Forwards a window close request.
    pub fn on_close_requested(&self, window: &mut Window, cx: &mut App) -> CloseDecision {
        self.inner.on_close_requested(window, cx)
    }

    /// The concrete entity, if the screen is an `S`.
    pub fn downcast<S: Screen>(&self) -> Option<Entity<S>> {
        self.inner.as_any().downcast_ref::<Entity<S>>().cloned()
    }
}

/// `LAUNCHER_APPLICATION_NAME + " | " + title`.
pub fn window_title(app_name: &str, screen_title: &str) -> SharedString {
    if screen_title.is_empty() {
        app_name.to_string().into()
    } else {
        format!("{app_name}{TITLE_SPLIT}{screen_title}").into()
    }
}

/// The window-title separator (`GUIConstants.TITLE_SPLIT_CHAR`).
pub const TITLE_SPLIT: &str = " | ";

/// A request to show a screen, with an optional payload its factory understands (for example
/// the editor's initial pack, or the Settings category index).
pub struct ScreenRequest {
    /// Which screen.
    pub id: ScreenId,
    /// Factory-specific argument.
    pub payload: Option<Box<dyn Any>>,
}

impl ScreenRequest {
    /// A request with no payload.
    pub fn new(id: ScreenId) -> Self {
        ScreenRequest { id, payload: None }
    }

    /// A request carrying a payload.
    pub fn with_payload(id: ScreenId, payload: impl Any) -> Self {
        ScreenRequest {
            id,
            payload: Some(Box::new(payload)),
        }
    }

    /// Takes the payload as a `T` (`None` when absent or of another type).
    pub fn take_payload<T: Any>(&mut self) -> Option<T> {
        let payload = self.payload.take()?;
        match payload.downcast::<T>() {
            Ok(b) => Some(*b),
            Err(other) => {
                self.payload = Some(other);
                None
            }
        }
    }
}

/// Builds a screen for a request.
pub type ScreenFactory = Rc<dyn Fn(ScreenRequest, &mut Window, &mut App) -> ScreenHandle>;

/// The screens every UI crate registered (`mmcl-app` fills it at start-up).
#[derive(Default, Clone)]
pub struct ScreenRegistry {
    factories: HashMap<ScreenId, ScreenFactory>,
}

impl Global for ScreenRegistry {}

impl ScreenRegistry {
    /// Registers (or replaces) the factory for `id`.
    pub fn register(cx: &mut App, id: ScreenId, factory: ScreenFactory) {
        cx.default_global::<ScreenRegistry>()
            .factories
            .insert(id, factory);
    }

    /// Registers a screen built by a closure returning the entity.
    pub fn register_view<S: Screen>(
        cx: &mut App,
        id: ScreenId,
        build: impl Fn(ScreenRequest, &mut Window, &mut Context<S>) -> S + 'static,
    ) {
        let build = Rc::new(build);
        Self::register(
            cx,
            id,
            Rc::new(move |req, window, cx| {
                let build = build.clone();
                ScreenHandle::new(cx.new(|cx| build(req, window, cx)))
            }),
        );
    }

    /// Whether `id` is registered.
    pub fn contains(cx: &App, id: ScreenId) -> bool {
        cx.try_global::<ScreenRegistry>()
            .is_some_and(|r| r.factories.contains_key(&id))
    }

    /// The registered ids, sorted.
    pub fn ids(cx: &App) -> Vec<ScreenId> {
        let mut ids: Vec<_> = cx
            .try_global::<ScreenRegistry>()
            .map(|r| r.factories.keys().copied().collect())
            .unwrap_or_default();
        ids.sort();
        ids
    }

    /// Builds the screen for a request, or `None` when nothing registered it.
    pub fn build(req: ScreenRequest, window: &mut Window, cx: &mut App) -> Option<ScreenHandle> {
        let factory = cx
            .try_global::<ScreenRegistry>()
            .and_then(|r| r.factories.get(&req.id).cloned())?;
        Some(factory(req, window, cx))
    }
}

/// Performs a navigation (installed by the shell): swap screens, run the lifecycle.
pub type NavigateHandler = Rc<dyn Fn(ScreenRequest, &mut App)>;

/// Navigation without knowing the shell: `Navigator::go_to(cx, ScreenRequest::new(ScreenId::HOME))`.
#[derive(Default, Clone)]
pub struct Navigator {
    handler: Option<NavigateHandler>,
    current: Option<ScreenId>,
}

impl Global for Navigator {}

impl Navigator {
    /// Installs the shell's handler.
    pub fn set_handler(cx: &mut App, handler: NavigateHandler) {
        cx.default_global::<Navigator>().handler = Some(handler);
    }

    /// Records the screen now showing (the shell calls this after each swap).
    pub fn set_current(cx: &mut App, id: Option<ScreenId>) {
        cx.default_global::<Navigator>().current = id;
    }

    /// The screen now showing.
    pub fn current(cx: &App) -> Option<ScreenId> {
        cx.try_global::<Navigator>().and_then(|n| n.current)
    }

    /// Asks the shell to show a screen. Deferred to the end of the current effect cycle, so it
    /// is safe from inside a render or an event handler. Returns `false` when no shell is
    /// installed (headless modes), in which case nothing happens.
    pub fn go_to(cx: &mut App, req: ScreenRequest) -> bool {
        let Some(handler) = cx.try_global::<Navigator>().and_then(|n| n.handler.clone()) else {
            tracing::debug!(
                "{}",
                mmcl_i18n::tf!("log.uiKit.navigationIgnored", req.id.as_str())
            );
            return false;
        };
        cx.defer(move |cx| handler(req, cx));
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Port of `HelpTopicNamesTest`: every topic name key resolves in the bundle.
    #[test]
    fn every_help_topic_name_resolves() {
        for topic in HelpTopic::ALL {
            assert!(
                mmcl_i18n::english(topic.name_key()).is_some(),
                "{topic:?} -> {}",
                topic.name_key()
            );
            assert!(topic.resource_path().starts_with("help/"));
            assert!(topic.resource_path().ends_with(".html"));
        }
    }

    #[test]
    fn help_pages_exist_in_the_java_resources() {
        let root =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../src/main/resources");
        let mut present = 0;
        for topic in HelpTopic::ALL {
            if root.join(topic.resource_path()).exists() {
                present += 1;
            }
        }
        assert!(
            present >= 10,
            "only {present} help pages found under {}",
            root.display()
        );
    }

    #[test]
    fn window_titles() {
        assert_eq!(window_title("Mica", "Settings").as_ref(), "Mica | Settings");
        assert_eq!(window_title("Mica", "").as_ref(), "Mica");
    }

    #[test]
    fn payloads_downcast() {
        let mut req = ScreenRequest::with_payload(ScreenId::SETTINGS, 3usize);
        assert_eq!(req.take_payload::<String>(), None);
        assert_eq!(req.take_payload::<usize>(), Some(3));
        assert_eq!(req.take_payload::<usize>(), None);
        assert!(ScreenRequest::new(ScreenId::HOME).payload.is_none());
    }
}

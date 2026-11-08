// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Help window (`MCLauncherHelpWindow`, gui-shell spec 9): a singleton secondary window with
//! the topic list on the left and the topic's page on the right.
//!
//! The pages are the Java launcher's bundled HTML ([`pages`]), converted to Markdown
//! ([`markdown`]) and rendered with gpui-component's `TextView` (spec option (a)): no web engine,
//! and the theme comes from the GPUI theme instead of the help CSS. `help://topic/X` links
//! navigate in-window; every other link is blocked, so a page can never leave bundled content.
//!
//! The window is per session: [`cleanup`] (on GUI exit / restart) closes it and drops its state,
//! so a restarted session never reuses a window owned by the old one.
//!
//! Like Java's help stage it carries its own OS title (taskbar, Alt-Tab, Mission Control),
//! opens centred on the launcher window, is owned by it on Windows (stays above it;
//! `initOwner`), keeps its minimum size in step with the interface scale
//! (`ScaledMinSize.follow`), and under the Native theme shows the same Mica / vibrancy backdrop
//! as the main window.

pub mod markdown;
pub mod pages;

use gpui::{
    AnyWindowHandle, App, AppContext as _, Bounds, ClickEvent, Context, FocusHandle, Global, Hsla,
    InteractiveElement, IntoElement, KeyDownEvent, ParentElement, Pixels, Render, SharedString,
    StatefulInteractiveElement as _, Styled, Subscription, Window, WindowBackgroundAppearance,
    WindowBounds, WindowHandle, WindowKind, WindowOptions, div, prelude::FluentBuilder, px, size,
};
use gpui_component::text::TextView;
use gpui_component::{Root, TitleBar};
use mmcl_i18n::{t, tf};
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::HelpTopic;
use mmcl_ui_kit::theme::{ActiveTheme, ThemeExt as _, TypeStyleExt as _, hsla};
use mmcl_ui_kit::windowing::{self, OwnerLink, SharedOwnerLink};

use crate::scale::u;

/// The window's size at 100 % (`scene 800x600 x scale`).
pub const PREF_SIZE: (f32, f32) = (800.0, 600.0);
/// The minimum size.
pub const MIN_SIZE: (f32, f32) = (600.0, 400.0);
/// The sidebar width.
pub const SIDEBAR_WIDTH: f32 = 180.0;

/// A loaded topic, ready to render.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LoadedTopic {
    /// The topic.
    pub topic: HelpTopic,
    /// The page as Markdown.
    pub markdown: SharedString,
    /// The page resource used.
    pub path: String,
    /// Laid out right to left.
    pub rtl: bool,
}

/// `loadTopic`'s content step (pure): resolve the page for the locale, or the inline "not
/// available" page with the escaped topic name, and convert it.
pub fn load_topic(topic: HelpTopic, language: &str, country: &str) -> LoadedTopic {
    let resolved = pages::resolve(topic, language, country);
    let html = match resolved.html {
        Some(html) => html.to_string(),
        None => {
            tracing::warn!("{}", tf!("log.help.loadTopicFailed", resolved.path.clone()));
            format!(
                "<h1>{}</h1>\n<p>{}</p>",
                markdown::escape_html(&t!("help.topic.unavailable.heading")),
                markdown::escape_html(&tf!(
                    "help.topic.unavailable.body",
                    topic.display_name().to_string()
                )),
            )
        }
    };
    let md = std::panic::catch_unwind(|| markdown::html_to_markdown(&html))
        .unwrap_or_else(|_| format!("{}\n", t!("help.topic.loadError")));
    LoadedTopic {
        topic,
        markdown: md.into(),
        path: resolved.path,
        rtl: resolved.rtl,
    }
}

/// What a click on a page link does.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LinkAction {
    /// Show this topic.
    Open(HelpTopic),
    /// A `help://topic/` link to no known topic (logged, ignored).
    Unknown(String),
    /// Anything else (blocked: only bundled content).
    Blocked(String),
}

/// Classifies a clicked link.
pub fn link_action(href: &str) -> LinkAction {
    match pages::topic_from_link(href) {
        Some(Ok(topic)) => LinkAction::Open(topic),
        Some(Err(name)) => LinkAction::Unknown(name),
        None => LinkAction::Blocked(href.to_string()),
    }
}

fn current_language() -> (String, String) {
    let locale = mmcl_i18n::current_locale();
    (locale.language().to_string(), locale.region().to_string())
}

/// The topic a sidebar key moves to from `current` (`up` / `down` / `home` / `end`; page keys
/// jump to the ends). `None` for any other key or when the selection would not move.
pub fn topic_for_key(current: HelpTopic, key: &str) -> Option<HelpTopic> {
    let all = HelpTopic::ALL;
    let i = all.iter().position(|t| *t == current)?;
    let next = match key {
        "up" => i.checked_sub(1)?,
        "down" => i + 1,
        "home" | "pageup" => 0,
        "end" | "pagedown" => all.len() - 1,
        _ => return None,
    };
    all.get(next).copied().filter(|t| *t != current)
}

/// The help view (sidebar + content).
pub struct HelpView {
    loaded: LoadedTopic,
    /// The language the page was loaded for (a language change reloads it).
    language: (String, String),
    /// The topic list's focus (arrow keys move the selection).
    list_focus: FocusHandle,
    /// Follows the interface scale (the minimum size).
    _scale: Option<Subscription>,
}

impl HelpView {
    fn new(topic: HelpTopic, cx: &mut Context<Self>) -> Self {
        let language = current_language();
        HelpView {
            loaded: load_topic(topic, &language.0, &language.1),
            language,
            list_focus: cx.focus_handle().tab_stop(true),
            _scale: None,
        }
    }

    fn on_list_key(&mut self, event: &KeyDownEvent, cx: &mut Context<Self>) {
        if event.keystroke.modifiers.modified() {
            return;
        }
        if let Some(topic) = topic_for_key(self.loaded.topic, event.keystroke.key.as_str()) {
            cx.stop_propagation();
            self.load(topic, cx);
        }
    }

    /// The topic showing.
    pub fn topic(&self) -> HelpTopic {
        self.loaded.topic
    }

    /// `loadTopic`: shows `topic` and selects it in the list (no re-fire). Reloads only when
    /// the topic or the language changed, so the scroll position survives repeated shows.
    pub fn load(&mut self, topic: HelpTopic, cx: &mut Context<Self>) {
        let language = current_language();
        if self.loaded.topic == topic && self.language == language {
            return;
        }
        self.loaded = load_topic(topic, &language.0, &language.1);
        self.language = language;
        cx.notify();
    }

    fn on_link(&mut self, href: &str, cx: &mut Context<Self>) {
        match link_action(href) {
            LinkAction::Open(topic) => self.load(topic, cx),
            LinkAction::Unknown(name) => {
                tracing::warn!("{}", tf!("log.help.unknownTopicLink", name))
            }
            LinkAction::Blocked(href) => {
                tracing::warn!("{}", tf!("log.help.blockedNavigation", href))
            }
        }
    }
}

impl Render for HelpView {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.launcher_theme();
        let t = theme.tokens();
        let current = self.loaded.topic;
        let entity = cx.entity().downgrade();
        let topics = HelpTopic::ALL.into_iter().enumerate().map(|(i, topic)| {
            let selected = topic == current;
            let hover = hsla(t.md_state_hover_on_surface);
            div()
                .id(("mmcl-help-topic", i))
                .px(u(12.0))
                .py(u(8.0))
                .rounded(u(mmcl_theme::shape::SMALL))
                .cursor_pointer()
                .type_role(if selected {
                    TypeRole::LabelLarge
                } else {
                    TypeRole::BodyMedium
                })
                .text_color(hsla(if selected {
                    t.md_on_secondary_container
                } else {
                    t.md_on_surface_variant
                }))
                .when(selected, |el| el.bg(hsla(t.md_secondary_container)))
                .when(!selected, |el| el.hover(move |s| s.bg(hover)))
                .child(topic.display_name())
                .on_click(cx.listener(move |this, _: &ClickEvent, window, cx| {
                    window.focus(&this.list_focus, cx);
                    this.load(topic, cx)
                }))
        });
        let id = SharedString::from(format!("mmcl-help-page-{}", self.loaded.path));
        let (_, fill) = help_background(&theme);
        let page = TextView::markdown(id, self.loaded.markdown.clone())
            .selectable(true)
            .scrollable(true)
            .on_link_click(move |href, _, _, cx| {
                let href = href.to_string();
                let _ = entity.update(cx, |this, cx| this.on_link(&href, cx));
            })
            .size_full();
        div()
            .size_full()
            .flex()
            .flex_col()
            .when_some(fill, |el, fill| el.bg(fill))
            .text_color(hsla(t.color_text))
            .font_family(mmcl_ui_kit::fonts::UI_FAMILY)
            .child(
                // The custom title bar (every OS): app icon and title over the window fill,
                // with gpui-component's caption buttons on the right.
                TitleBar::new()
                    .bg(gpui::transparent_black())
                    .border_color(gpui::transparent_black())
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(px(8.0))
                            .min_w_0()
                            .overflow_hidden()
                            .child(
                                gpui::img(mmcl_ui_kit::assets::APP_ICON)
                                    .size(px(18.0))
                                    .flex_shrink_0(),
                            )
                            .child(
                                div()
                                    .text_size(px(12.0))
                                    .text_color(hsla(t.color_text_muted))
                                    .truncate()
                                    .child(t!("window.help.title").into_owned()),
                            ),
                    ),
            )
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .flex()
                    .child(
                        div()
                            .flex_none()
                            .w(u(SIDEBAR_WIDTH))
                            .min_w(u(160.0))
                            .h_full()
                            .flex()
                            .flex_col()
                            .bg(hsla(t.color_bg_soft))
                            .border_r_1()
                            .border_color(hsla(t.color_border))
                            .child(
                                div()
                                    .pt(u(14.0))
                                    .px(u(14.0))
                                    .pb(u(8.0))
                                    .type_role(TypeRole::HeadingH3)
                                    .child(t!("help.sidebar.header").into_owned()),
                            )
                            .child(
                                div()
                                    .id("mmcl-help-topics")
                                    .track_focus(&self.list_focus)
                                    .on_key_down(cx.listener(|this, ev: &KeyDownEvent, _, cx| {
                                        this.on_list_key(ev, cx)
                                    }))
                                    .flex_1()
                                    .min_h_0()
                                    .overflow_y_scroll()
                                    .px(u(6.0))
                                    .pb(u(8.0))
                                    .flex()
                                    .flex_col()
                                    .gap(u(2.0))
                                    .children(topics),
                            ),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .h_full()
                            .px(u(24.0))
                            .py(u(16.0))
                            .when(self.loaded.rtl, |el| el.text_right())
                            .child(page),
                    ),
            )
    }
}

/// The session's help window (dropped on exit / restart).
#[derive(Default)]
struct HelpState {
    window: Option<WindowHandle<Root>>,
    view: Option<gpui::Entity<HelpView>>,
    /// The theme the window was last themed for (`theme + ":" + osDark`).
    theme_key: Option<ActiveTheme>,
    /// The tie to the launcher window (owned, not modal: `initOwner`).
    link: Option<SharedOwnerLink>,
}

impl Global for HelpState {}

/// What the help window paints: the main window's backdrop and fill under a backdrop theme
/// (Native: Mica on Windows, vibrancy on macOS), else the opaque secondary-window floor.
pub fn help_background(theme: &ActiveTheme) -> (WindowBackgroundAppearance, Option<Hsla>) {
    match theme.window_background(false) {
        WindowBackgroundAppearance::Opaque => (
            WindowBackgroundAppearance::Opaque,
            Some(theme.solid_bg(true)),
        ),
        backdrop => (backdrop, theme.window_fill(false)),
    }
}

/// The minimum size at `percent` interface scale.
pub fn min_size_at(percent: i32) -> gpui::Size<Pixels> {
    let scale = percent as f32 / 100.0;
    size(px(MIN_SIZE.0 * scale), px(MIN_SIZE.1 * scale))
}

/// The window's bounds: the preferred size centred on `launcher` (the main window's bounds),
/// else on the primary display.
pub fn initial_bounds(launcher: Option<Bounds<Pixels>>, percent: i32, cx: &App) -> Bounds<Pixels> {
    let scale = percent as f32 / 100.0;
    let wanted = size(px(PREF_SIZE.0 * scale), px(PREF_SIZE.1 * scale));
    match launcher {
        Some(main) => Bounds::new(
            main.center() - gpui::point(wanted.width / 2.0, wanted.height / 2.0),
            wanted,
        ),
        None => Bounds::centered(None, wanted, cx),
    }
}

/// Re-applies the OS minimum for the current interface scale (the window grows when it is
/// smaller); GPUI alone fixes the minimum when the window opens.
fn follow_scale(window: &mut Window, cx: &mut App) {
    if !windowing::apply_scaled_min_size(window, min_size_at(100), cx) {
        // No native minimum here (Wayland): at least grow to it.
        let min = min_size_at(mmcl_ui_kit::scale::percent(cx));
        let now = window.viewport_size();
        if now.width < min.width || now.height < min.height {
            window.resize(size(now.width.max(min.width), now.height.max(min.height)));
        }
    }
}

fn options(cx: &App, launcher: Option<Bounds<Pixels>>) -> WindowOptions {
    let percent = mmcl_ui_kit::scale::percent(cx);
    let bounds = initial_bounds(launcher, percent, cx);
    let (background, _) = help_background(&cx.launcher_theme());
    WindowOptions {
        window_bounds: Some(WindowBounds::Windowed(bounds)),
        window_min_size: Some(min_size_at(percent)),
        kind: WindowKind::Normal,
        window_background: background,
        app_id: Some(
            mmcl_core::AppIdentity::current()
                .linux_package_name
                .to_string(),
        ),
        window_decorations: Some(gpui::WindowDecorations::Client),
        focus: true,
        show: true,
        ..TitleBar::window_options()
    }
}

/// `show(topic)`: builds the window if needed, loads the topic and brings it forward.
pub fn show(topic: HelpTopic, cx: &mut App) {
    let state = cx.default_global::<HelpState>();
    if let (Some(window), Some(view)) = (state.window, state.view.clone()) {
        let shown = window.update(cx, |_, window, cx| {
            view.update(cx, |v, cx| v.load(topic, cx));
            window.activate_window();
        });
        if shown.is_ok() {
            refresh_theme(cx);
            return;
        }
        // The window went away behind our back: rebuild.
        cx.set_global(HelpState::default());
    }
    // The launcher window: where to centre, and the owner.
    let launcher = crate::shell::main_window_handle(cx).and_then(|h| {
        h.update(cx, |_, w, _| (w.bounds(), windowing::native(w)))
            .ok()
    });
    let mut built = None;
    let mut owner_link = None;
    let opened = cx.open_window(options(cx, launcher.map(|l| l.0)), |window, cx| {
        // Owned by the launcher (stays above it, minimises with it), not modal: Java's
        // `initOwner` without a modality.
        let link = match launcher {
            Some((_, owner)) => OwnerLink::attach(window, owner, false),
            None => OwnerLink::none(),
        }
        .shared();
        owner_link = Some(link.clone());
        let view = cx.new(|cx| {
            let mut view = HelpView::new(topic, cx);
            view._scale = Some(
                cx.observe_global_in::<mmcl_ui_kit::scale::UiScale>(window, |_, window, cx| {
                    follow_scale(window, cx)
                }),
            );
            view
        });
        built = Some(view.clone());
        follow_scale(window, cx);
        window.on_window_should_close(cx, move |_, cx| {
            // Closing the window ends it; the next show builds a fresh one.
            link.release();
            cx.set_global(HelpState::default());
            true
        });
        cx.new(|cx| Root::new(view, window, cx))
    });
    match opened {
        Ok(handle) => {
            let _ = handle.update(cx, |_, window, _| {
                window.set_window_title(&t!("window.help.title"));
            });
            let state = cx.global_mut::<HelpState>();
            state.window = Some(handle);
            state.view = built;
            state.theme_key = None;
            state.link = owner_link;
            refresh_theme(cx);
        }
        Err(e) => tracing::warn!("{}", tf!("log.help.windowOpenFailed", e.to_string())),
    }
}

/// `hide()`: closes the window (it is rebuilt lazily on the next show).
pub fn hide(cx: &mut App) {
    cleanup(cx);
}

/// `refreshTheme()`: re-applies the title-bar mode only when the theme really changed (every
/// main-window theme pass calls it). The content re-themes itself at render time.
pub fn refresh_theme(cx: &mut App) {
    let theme = cx.launcher_theme();
    let Some(state) = cx.try_global::<HelpState>() else {
        return;
    };
    let Some(window) = state.window else { return };
    let previous = state.theme_key;
    if previous == Some(theme) {
        return;
    }
    let (background, _) = help_background(&theme);
    let was = previous.map(|p| help_background(&p).0);
    let applied = window
        .update(cx, |_, window, cx| {
            if was.is_some() && background == WindowBackgroundAppearance::MicaBackdrop {
                // DWM ignores a repeated Mica request, so a dark -> light flip kept the tint.
                window.set_background_appearance(WindowBackgroundAppearance::Opaque);
            }
            if was != Some(background) || background == WindowBackgroundAppearance::MicaBackdrop {
                window.set_background_appearance(background);
            }
            crate::theme::install_theme_on(window, cx);
            window.refresh();
        })
        .is_ok();
    if applied {
        cx.global_mut::<HelpState>().theme_key = Some(theme);
    }
}

/// `cleanup()`: closes the window and drops all of its state (session end / restart).
pub fn cleanup(cx: &mut App) {
    let Some(state) = cx.try_global::<HelpState>() else {
        return;
    };
    let window: Option<AnyWindowHandle> = state.window.map(Into::into);
    // Hand the activation back to the launcher before the window goes.
    if let Some(link) = &state.link {
        link.release();
    }
    cx.set_global(HelpState::default());
    if let Some(window) = window {
        let _ = window.update(cx, |_, window, _| window.remove_window());
    }
}

/// Whether the help window is open (tests, diagnostics).
pub fn is_open(cx: &App) -> bool {
    cx.try_global::<HelpState>()
        .is_some_and(|s| s.window.is_some())
}

/// The topic the help window shows, if open.
pub fn current_topic(cx: &App) -> Option<HelpTopic> {
    cx.try_global::<HelpState>()
        .and_then(|s| s.view.as_ref())
        .map(|v| v.read(cx).topic())
}

/// Installs the help window as the shell's help opener, and its theme and exit hooks. Once per
/// app (the hooks table survives in-process restarts).
pub fn install(cx: &mut App) {
    #[derive(Default)]
    struct Installed;
    impl Global for Installed {}
    if cx.has_global::<Installed>() {
        return;
    }
    cx.set_global(Installed);
    crate::shell::set_help_handler(cx, show);
    crate::shell::on_theme_changed(cx, refresh_theme);
    crate::shell::on_exit(cx, cleanup);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_topic_loads_in_english_and_translated() {
        for topic in HelpTopic::ALL {
            let en = load_topic(topic, "en", "US");
            assert_eq!(en.path, topic.resource_path());
            assert!(en.markdown.starts_with('#'), "{topic:?}");
            assert!(!en.rtl);
            let ar = load_topic(topic, "ar", "SA");
            assert!(ar.rtl && ar.path.starts_with("help/ar/"));
        }
    }

    #[test]
    fn links_open_topics_and_block_the_rest() {
        assert_eq!(
            link_action("help://topic/GAME_CONSOLE"),
            LinkAction::Open(HelpTopic::GameConsole)
        );
        assert_eq!(
            link_action("help://topic/nothing"),
            LinkAction::Unknown("nothing".into())
        );
        assert_eq!(
            link_action("https://example.com/x"),
            LinkAction::Blocked("https://example.com/x".into())
        );
    }

    #[test]
    fn sidebar_keys_move_the_selection() {
        let first = HelpTopic::ALL[0];
        let last = HelpTopic::ALL[HelpTopic::ALL.len() - 1];
        assert_eq!(topic_for_key(first, "up"), None);
        assert_eq!(topic_for_key(first, "down"), Some(HelpTopic::ALL[1]));
        assert_eq!(topic_for_key(last, "down"), None);
        assert_eq!(topic_for_key(HelpTopic::ALL[3], "home"), Some(first));
        assert_eq!(topic_for_key(HelpTopic::ALL[3], "end"), Some(last));
        assert_eq!(topic_for_key(last, "end"), None);
        assert_eq!(topic_for_key(first, "a"), None);
    }

    #[test]
    fn help_strings_exist() {
        for k in [
            "window.help.title",
            "help.sidebar.header",
            "help.topic.loadError",
            "help.topic.unavailable.heading",
            "help.topic.unavailable.body",
            "log.help.unknownTopicLink",
            "log.help.blockedNavigation",
            "log.help.loadTopicFailed",
            "log.help.windowOpenFailed",
        ] {
            assert!(mmcl_i18n::english(k).is_some(), "{k}");
        }
    }

    #[test]
    fn the_backdrop_follows_the_main_window_only_under_backdrop_themes() {
        use mmcl_theme::{Platform, Theme, ThemeId};
        let dark = ActiveTheme {
            theme: Theme::new(ThemeId::Dark, true, Platform::Windows),
            mica_supported: true,
        };
        let (bg, fill) = help_background(&dark);
        assert_eq!(bg, WindowBackgroundAppearance::Opaque);
        assert_eq!(fill, Some(dark.solid_bg(true)));
        let native = ActiveTheme {
            theme: Theme::new(ThemeId::Native, true, Platform::Windows),
            mica_supported: true,
        };
        assert_eq!(help_background(&native).0, native.window_background(false));
        let refused = ActiveTheme {
            mica_supported: false,
            ..native
        };
        assert_eq!(
            help_background(&refused).0,
            WindowBackgroundAppearance::Opaque,
            "no backdrop where Mica is refused (it would paint black)"
        );
    }

    #[test]
    fn minimum_size_scales() {
        assert_eq!(min_size_at(100), size(px(600.0), px(400.0)));
        assert_eq!(min_size_at(150), size(px(900.0), px(600.0)));
    }

    #[gpui::test]
    fn help_opens_centred_on_the_launcher(cx: &mut gpui::TestAppContext) {
        cx.update(|cx| {
            let main = Bounds::new(
                gpui::point(px(1000.0), px(200.0)),
                size(px(1200.0), px(800.0)),
            );
            let b = initial_bounds(Some(main), 100, cx);
            assert_eq!(b.size, size(px(800.0), px(600.0)));
            assert_eq!(b.center(), main.center());
        });
    }

    #[gpui::test]
    fn help_view_loads_and_follows_links(cx: &mut gpui::TestAppContext) {
        let view = cx.new(|cx| HelpView::new(HelpTopic::Settings, cx));
        view.update(cx, |v, cx| {
            assert_eq!(v.topic(), HelpTopic::Settings);
            v.on_link("help://topic/JVM_PRESETS", cx);
            assert_eq!(v.topic(), HelpTopic::JvmPresets);
            v.on_link("https://example.com", cx);
            assert_eq!(
                v.topic(),
                HelpTopic::JvmPresets,
                "external links are blocked"
            );
        });
    }
}

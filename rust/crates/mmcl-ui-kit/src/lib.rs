// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Shared GPUI building blocks for the Mica Minecraft Launcher's UI crates.
//!
//! `mmcl-ui`, `mmcl-editor` and `mmcl-running-games` all build on this crate and never on each
//! other; `mmcl-app` wires them together.
//!
//! | Module | Java origin | What |
//! |---|---|---|
//! | [`theme`] | theme sheets, `GUIUtilities.isLightChrome` | `ActiveTheme` global, token -> GPUI colours, gpui-component palette bridge, type roles |
//! | [`icons`], [`assets`] | `LauncherIcons`, resources | the launcher glyphs as SVG assets, the app `AssetSource` |
//! | [`fonts`] | `BundledFonts` | Inter + JetBrains Mono registration |
//! | [`motion`] | `Motion`, `AnimationGate` | enter / exit / fade-through, reduce motion, frame gate |
//! | [`loading`] | `LoadingIndicator` | M3 Expressive morphing loading shape |
//! | [`wavy`] | `WavyProgressBar` | M3 Expressive wavy progress |
//! | [`step_badge`] | `StepBadge` | launch step state badge |
//! | [`controls`] | `FilterChip`, `IconButtons`, `SearchFields`, `EmptyStates`, `AnnouncementBanners`, `OfflineIndicator` | small controls |
//! | [`switch`] | `MaterialSwitchSkin` | M3 switch |
//! | [`tooltip`] | `TooltipManager` | launcher tooltip |
//! | [`images`] | `ImageFadeIn`, `AvatarImages` | fade-in image, avatar cache |
//! | [`scale`] | `UiScale`, `SmoothScroll` | rem scaling, eased wheel scrolling |
//! | [`dialogs`] | `GUIUtilities` dialogs | async, queued, themed dialogs |
//! | [`notify`] | `NotificationManager` | OS notifications |
//! | [`sys`] | `GUIUtilities` helpers | open URL / folder, redaction, light chrome |
//! | [`screen`] | `MCLauncherAbstractGui`, `HelpTopic` | `Screen` trait, `ScreenRegistry`, `Navigator` |
//! | [`focus`] | (new) | gamepad / keyboard focus navigation |
//!
//! Start-up (GUI mode), after `gpui_platform::application().with_assets(mmcl_ui_kit::Assets)`:
//! `mmcl_ui_kit::init(cx, theme)` once.

pub mod assets;
pub mod controls;
pub mod dialogs;
pub mod focus;
pub mod fonts;
pub mod icons;
pub mod images;
pub mod loading;
pub mod motion;
pub mod notify;
pub mod scale;
pub mod screen;
pub mod step_badge;
pub mod switch;
pub mod sys;
pub mod theme;
pub mod tooltip;
pub mod wavy;

pub use assets::Assets;
pub use controls::{
    AnnouncementBanner, FilterChip, IconButton, OfflineChip, SearchField, empty_state_badge,
};
pub use dialogs::{Answer, ButtonRole, DialogButton, DialogLevel, DialogSpec};
pub use fonts::register_fonts;
pub use icons::Icon;
pub use images::{Avatar, Avatars, FadeImage};
pub use loading::LoadingIndicator;
pub use motion::MotionExt;
pub use screen::{
    CloseDecision, HelpTopic, Navigator, Screen, ScreenHandle, ScreenId, ScreenRegistry,
    ScreenRequest,
};
pub use step_badge::{StepBadge, StepState};
pub use switch::Switch;
pub use sys::{is_light_chrome, open_external_url, open_folder};
pub use theme::{ActiveTheme, ThemeExt, TypeStyleExt, hsla, set_theme};
pub use tooltip::{LauncherTooltip, TooltipExt, tooltip};
pub use wavy::WavyProgressBar;

/// One-time GUI initialisation: gpui-component, the bundled fonts, navigation key bindings, the
/// dialogs service, and the launcher theme (pushed into gpui-component).
///
/// Not for server / TUI sessions: there the dialogs service stays uninstalled, so every dialog
/// answers "no answer" at once.
pub fn init(cx: &mut gpui::App, theme: ActiveTheme) {
    gpui_component::init(cx);
    for failure in fonts::register_fonts(cx) {
        tracing::debug!("{failure}");
    }
    focus::bind_nav_keys(cx);
    dialogs::init(cx);
    theme::set_theme(cx, theme);
}

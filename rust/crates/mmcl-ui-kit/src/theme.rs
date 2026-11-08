// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The bridge from `mmcl-theme` tokens to GPUI and gpui-component.
//!
//! - [`ActiveTheme`] is the launcher's theme `Global`. Every window observes it
//!   (`cx.observe_global::<ActiveTheme>(..)`) and re-renders; there is no per-window sheet
//!   juggling.
//! - [`set_theme`] swaps it and pushes the matching palette into gpui-component's `Theme`, so
//!   its inputs, selects, tooltips and dialogs follow the launcher theme.
//! - [`hsla`] / [`rgba`] convert a token colour; [`ThemeExt`] reads tokens off any `App`.
//! - [`TypeStyleExt::type_role`] applies a type-scale role (family, size, weight) to an element.

use gpui::{App, FontWeight, Global, Hsla, Pixels, Styled, WindowBackgroundAppearance, px};
use gpui_component::theme::{Theme as ComponentTheme, ThemeColor, ThemeMode};
use mmcl_theme::typography::{Family, TypeRole, Weight};
use mmcl_theme::{Backdrop, Platform, Rgba, Theme, ThemeId, TokenSheet, Tokens};

/// A token colour as GPUI `Hsla`.
pub fn hsla(color: Rgba) -> Hsla {
    let [h, s, l, a] = color.to_hsla();
    Hsla { h, s, l, a }
}

/// A token colour as GPUI `Rgba`.
pub fn rgba(color: Rgba) -> gpui::Rgba {
    let [r, g, b, a] = color.to_f32();
    gpui::Rgba { r, g, b, a }
}

/// The launcher theme global.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ActiveTheme {
    /// The resolved theme (configured id, token sheet, OS dark reading, platform).
    pub theme: Theme,
    /// Whether this Windows build supports Mica (only meaningful on Windows).
    pub mica_supported: bool,
}

impl Global for ActiveTheme {}

impl Default for ActiveTheme {
    fn default() -> Self {
        ActiveTheme {
            theme: Theme::new(ThemeId::DEFAULT, true, Platform::current()),
            mica_supported: false,
        }
    }
}

impl ActiveTheme {
    /// The colour tokens.
    pub fn tokens(&self) -> &'static Tokens {
        self.theme.tokens()
    }

    /// The token sheet.
    pub fn sheet(&self) -> TokenSheet {
        self.theme.sheet
    }

    /// Whether title bars should be light.
    pub fn is_light_chrome(&self) -> bool {
        self.theme.is_light_chrome()
    }

    /// The opaque floor to paint (secondary windows and dialogs always use it).
    pub fn solid_bg(&self, secondary_window: bool) -> Hsla {
        hsla(self.theme.solid_bg(secondary_window))
    }

    /// What a window paints behind its content: `None` when the OS backdrop shows through.
    pub fn window_fill(&self, secondary_window: bool) -> Option<Hsla> {
        self.theme
            .window_fill(self.mica_supported, secondary_window)
            .map(hsla)
    }

    /// The GPUI background appearance for a window (secondary windows are always opaque).
    pub fn window_background(&self, secondary_window: bool) -> WindowBackgroundAppearance {
        if secondary_window {
            return WindowBackgroundAppearance::Opaque;
        }
        background_appearance(self.theme.backdrop(self.mica_supported))
    }
}

/// Maps a theme backdrop to GPUI's window background appearance.
pub fn background_appearance(backdrop: Backdrop) -> WindowBackgroundAppearance {
    match backdrop {
        Backdrop::None => WindowBackgroundAppearance::Opaque,
        Backdrop::Mica => WindowBackgroundAppearance::MicaBackdrop,
        Backdrop::Vibrancy => WindowBackgroundAppearance::Blurred,
    }
}

/// Token access on any app context.
pub trait ThemeExt {
    /// The launcher theme (the default theme when none was installed yet).
    fn launcher_theme(&self) -> ActiveTheme;
    /// The current colour tokens.
    fn tokens(&self) -> &'static Tokens;
}

impl ThemeExt for App {
    fn launcher_theme(&self) -> ActiveTheme {
        self.try_global::<ActiveTheme>()
            .copied()
            .unwrap_or_default()
    }

    fn tokens(&self) -> &'static Tokens {
        self.launcher_theme().tokens()
    }
}

/// Installs or replaces the launcher theme and re-themes gpui-component; refreshes every window.
///
/// Native chrome (backdrop, title-bar mode) is the platform slice's: callers re-run it only when
/// [`mmcl_theme::chrome_changed`] says the sheet changed.
pub fn set_theme(cx: &mut App, theme: ActiveTheme) {
    let previous = cx.try_global::<ActiveTheme>().copied();
    cx.set_global(theme);
    if !cx.has_global::<ComponentTheme>() {
        // `gpui_component::init` not run (tests, headless): nothing else to sync.
        cx.refresh_windows();
        return;
    }
    let mode = if theme.theme.sheet.is_light() {
        ThemeMode::Light
    } else {
        ThemeMode::Dark
    };
    if previous.is_none_or(|p| p.theme.sheet.is_light() != theme.theme.sheet.is_light())
        || ComponentTheme::global(cx).mode != mode
    {
        ComponentTheme::change(mode, None, cx);
    }
    let tokens = theme.tokens();
    let floor = theme.theme.solid_bg(false);
    // gpui-component's Root sets the window rem size from `font_size` every frame, so it carries
    // the interface scale (16 px at 100 %); the launcher's own text sizes come from the type scale.
    let rem = crate::scale::rem_size(crate::scale::percent(cx));
    let factor = crate::scale::percent(cx) as f32 / 100.0;
    ComponentTheme::update(cx, |t| {
        fill_component_colors(&mut t.colors, tokens, floor);
        t.font_family = crate::fonts::UI_FAMILY.into();
        t.mono_font_family = crate::fonts::MONO_FAMILY.into();
        t.font_size = rem;
        t.mono_font_size = px(12.0 * factor);
        t.radius = px(mmcl_theme::shape::SMALL);
        t.radius_lg = px(mmcl_theme::shape::EXTRA_LARGE);
    });
}

/// Fills gpui-component's palette from the launcher tokens.
///
/// `floor` is the opaque colour translucent (native) tokens are composited over, so popups,
/// inputs and dialogs drawn by gpui-component stay opaque (they are not composited over Mica).
pub fn fill_component_colors(c: &mut ThemeColor, t: &Tokens, floor: Rgba) {
    let solid = |color: Rgba| hsla(color.over(floor));
    let raw = hsla;

    c.background = raw(t.color_bg);
    c.foreground = raw(t.color_text);
    c.border = raw(t.color_border);
    c.input = raw(t.md_outline);
    c.ring = raw(t.md_primary);
    c.caret = raw(t.md_primary);
    c.selection = raw(t.color_primary_soft_hov);
    c.overlay = raw(t.color_overlay);
    c.window_border = raw(t.color_border);

    c.accent = solid(t.md_surface_container_highest);
    c.accent_foreground = raw(t.md_on_surface);
    c.muted = solid(t.md_surface_container_high);
    c.muted_foreground = raw(t.color_text_muted);
    c.popover = solid(t.color_popup);
    c.popover_foreground = raw(t.color_text);

    c.primary = raw(t.md_primary);
    c.primary_hover = raw(t.color_primary_hover);
    c.primary_active = raw(t.color_primary_pressed);
    c.primary_foreground = raw(t.md_on_primary);
    c.secondary = solid(t.md_surface_container_highest);
    c.secondary_hover = solid(
        t.md_state_hover_on_surface
            .over(t.md_surface_container_highest.over(floor)),
    );
    c.secondary_active = solid(
        t.md_state_pressed_on_surface
            .over(t.md_surface_container_highest.over(floor)),
    );
    c.secondary_foreground = raw(t.md_on_surface);

    c.button = c.secondary;
    c.button_hover = c.secondary_hover;
    c.button_active = c.secondary_active;
    c.button_foreground = c.secondary_foreground;
    c.button_primary = c.primary;
    c.button_primary_hover = c.primary_hover;
    c.button_primary_active = c.primary_active;
    c.button_primary_foreground = c.primary_foreground;
    c.button_secondary = solid(t.md_secondary_container);
    c.button_secondary_hover = c.button_secondary;
    c.button_secondary_active = c.button_secondary;
    c.button_secondary_foreground = raw(t.md_on_secondary_container);
    c.button_danger = raw(t.md_error);
    c.button_danger_hover = raw(t.color_danger_hover);
    c.button_danger_active = raw(t.color_danger_hover);
    c.button_danger_foreground = raw(t.md_on_error);
    c.button_success = raw(t.md_success);
    c.button_success_hover = raw(t.md_success);
    c.button_success_active = raw(t.md_success);
    c.button_success_foreground = raw(t.md_on_success);
    c.button_warning = raw(t.md_warning);
    c.button_warning_hover = raw(t.md_warning);
    c.button_warning_active = raw(t.md_warning);
    c.button_warning_foreground = raw(t.md_on_warning);
    c.button_info = raw(t.md_tertiary);
    c.button_info_hover = raw(t.md_tertiary);
    c.button_info_active = raw(t.md_tertiary);
    c.button_info_foreground = raw(t.md_on_tertiary);

    c.danger = raw(t.md_error);
    c.danger_hover = raw(t.color_danger_hover);
    c.danger_active = raw(t.color_danger_hover);
    c.danger_foreground = raw(t.md_on_error);
    c.success = raw(t.md_success);
    c.success_hover = raw(t.md_success);
    c.success_active = raw(t.md_success);
    c.success_foreground = raw(t.md_on_success);
    c.warning = raw(t.md_warning);
    c.warning_hover = raw(t.md_warning);
    c.warning_active = raw(t.md_warning);
    c.warning_foreground = raw(t.md_on_warning);
    c.info = raw(t.md_tertiary);
    c.info_hover = raw(t.md_tertiary);
    c.info_active = raw(t.md_tertiary);
    c.info_foreground = raw(t.md_on_tertiary);

    c.link = raw(t.md_text_primary);
    c.link_hover = raw(t.color_primary_hover);
    c.link_active = raw(t.color_primary_pressed);

    c.list = raw(Rgba::TRANSPARENT);
    c.list_even = raw(Rgba::TRANSPARENT);
    c.list_head = solid(t.md_surface_container);
    c.list_hover = raw(t.md_state_hover_on_surface);
    c.list_active = raw(t.md_active_indicator);
    c.list_active_border = raw(t.md_primary);
    c.table = raw(Rgba::TRANSPARENT);
    c.table_even = raw(t.md_surface_container_lowest);
    c.table_head = solid(t.md_surface_container);
    c.table_head_foreground = raw(t.md_on_surface_variant);
    c.table_foot = solid(t.md_surface_container);
    c.table_foot_foreground = raw(t.md_on_surface_variant);
    c.table_hover = raw(t.md_state_hover_on_surface);
    c.table_active = raw(t.md_active_indicator);
    c.table_active_border = raw(t.md_primary);
    c.table_row_border = raw(t.color_divider);

    c.tab = raw(Rgba::TRANSPARENT);
    c.tab_active = solid(t.md_active_indicator);
    c.tab_active_foreground = raw(t.md_on_active_indicator);
    c.tab_bar = raw(t.color_bg_soft);
    c.tab_bar_segmented = solid(t.md_surface_container_high);
    c.tab_foreground = raw(t.md_on_surface_variant);

    c.title_bar = raw(t.color_bg_soft);
    c.title_bar_border = raw(t.color_border);
    c.status_bar = raw(t.color_bg_soft);
    c.status_bar_border = raw(t.color_border);
    c.sidebar = raw(t.color_bg_soft);
    c.sidebar_foreground = raw(t.color_text);
    c.sidebar_border = raw(t.color_border);
    c.sidebar_accent = raw(t.md_active_indicator);
    c.sidebar_accent_foreground = raw(t.md_on_active_indicator);
    c.sidebar_primary = raw(t.md_primary);
    c.sidebar_primary_foreground = raw(t.md_on_primary);

    c.scrollbar = raw(Rgba::TRANSPARENT);
    c.scrollbar_thumb = raw(t.color_border_strong);
    c.scrollbar_thumb_hover = raw(t.md_outline);
    c.progress_bar = raw(t.md_primary);
    c.slider_bar = raw(t.md_primary);
    c.slider_thumb = raw(t.md_primary);
    c.switch = solid(t.md_surface_container_highest);
    c.switch_thumb = raw(t.md_outline);
    c.skeleton = solid(t.md_surface_container_high);
    c.accordion = solid(t.md_surface_container);
    c.group_box = solid(t.md_surface_container);
    c.group_box_foreground = raw(t.color_text);
    c.description_list_label = solid(t.md_surface_container);
    c.description_list_label_foreground = raw(t.color_text_muted);
    c.drag_border = raw(t.md_primary);
    c.drop_target = raw(t.color_primary_soft);
    c.chart_grid = raw(t.color_divider);
    c.chart_bullish = raw(t.color_success);
    c.chart_bearish = raw(t.color_danger);

    c.red = raw(t.color_danger);
    c.red_light = raw(t.color_danger_soft);
    c.green = raw(t.color_success);
    c.green_light = raw(t.color_primary_soft);
    c.yellow = raw(t.color_warning);
    c.yellow_light = raw(t.color_warning_soft);
    c.blue = raw(t.md_icon_blue);
    c.blue_light = raw(t.md_on_icon_blue);
    c.cyan = raw(t.md_icon_cyan);
    c.cyan_light = raw(t.md_on_icon_cyan);
    c.magenta = raw(t.md_icon_pink);
    c.magenta_light = raw(t.md_on_icon_pink);
}

/// The GPUI font weight for a type-scale weight.
pub fn font_weight(weight: Weight) -> FontWeight {
    match weight {
        Weight::Regular => FontWeight::NORMAL,
        Weight::SemiBold => FontWeight::SEMIBOLD,
        Weight::Bold => FontWeight::BOLD,
    }
}

/// The GPUI family for a type-scale family (GPUI resolves 600 inside "Inter").
pub fn font_family(family: Family) -> &'static str {
    match family {
        Family::Ui | Family::UiSemiBold => crate::fonts::UI_FAMILY,
        Family::Mono => crate::fonts::MONO_FAMILY,
    }
}

/// The font size of a role in px.
pub fn role_size(role: TypeRole) -> Pixels {
    px(role.style().size)
}

/// Applies type-scale roles to any styled element.
pub trait TypeStyleExt: Styled + Sized {
    /// Sets family, size and weight from a type-scale role.
    fn type_role(self, role: TypeRole) -> Self {
        let style = role.style();
        self.font_family(font_family(style.family))
            .text_size(px(style.size))
            .font_weight(font_weight(style.weight))
    }
}

impl<E: Styled> TypeStyleExt for E {}

#[cfg(test)]
mod tests {
    use super::*;
    use gpui::hsla as gpui_hsla;

    #[test]
    fn colour_conversions() {
        let white = hsla(Rgba::WHITE);
        assert_eq!(white, gpui_hsla(0.0, 0.0, 1.0, 1.0));
        let c = rgba(Rgba::opaque(0x6FCF3D));
        assert!((c.r - 0x6F as f32 / 255.0).abs() < 1e-6);
        assert!((c.b - 0x3D as f32 / 255.0).abs() < 1e-6);
        assert_eq!(c.a, 1.0);
        assert_eq!(hsla(Rgba::TRANSPARENT).a, 0.0);
    }

    #[test]
    fn every_sheet_fills_component_colours() {
        for sheet in TokenSheet::ALL {
            let mut colors = ThemeColor::default();
            let floor = sheet.solid_bg(false);
            fill_component_colors(&mut colors, sheet.tokens(), floor);
            assert_eq!(colors.primary, hsla(sheet.tokens().md_primary), "{sheet:?}");
            // Popups are always opaque, even over the translucent native sheets.
            assert_eq!(colors.popover.a, 1.0, "{sheet:?} popover is translucent");
            assert_eq!(colors.accent.a, 1.0, "{sheet:?} accent is translucent");
            assert_eq!(
                colors.secondary.a, 1.0,
                "{sheet:?} secondary is translucent"
            );
        }
    }

    #[test]
    fn backgrounds_follow_the_backdrop() {
        let native = ActiveTheme {
            theme: Theme::new(ThemeId::Native, true, Platform::Windows),
            mica_supported: true,
        };
        assert_eq!(
            native.window_background(false),
            WindowBackgroundAppearance::MicaBackdrop
        );
        assert_eq!(
            native.window_background(true),
            WindowBackgroundAppearance::Opaque
        );
        assert!(native.window_fill(false).is_none());
        assert!(native.window_fill(true).is_some());
        let dark = ActiveTheme {
            theme: Theme::new(ThemeId::Dark, true, Platform::MacOs),
            mica_supported: false,
        };
        assert_eq!(
            dark.window_background(false),
            WindowBackgroundAppearance::Opaque
        );
        let mac_native = ActiveTheme {
            theme: Theme::new(ThemeId::Native, false, Platform::MacOs),
            mica_supported: false,
        };
        assert_eq!(
            mac_native.window_background(false),
            WindowBackgroundAppearance::Blurred
        );
        assert!(mac_native.is_light_chrome());
    }

    #[test]
    fn weights_and_families() {
        assert_eq!(font_weight(Weight::SemiBold), FontWeight::SEMIBOLD);
        assert_eq!(font_family(Family::UiSemiBold), "Inter");
        assert_eq!(font_family(Family::Mono), "JetBrains Mono");
        assert_eq!(role_size(TypeRole::HeadingH1), px(28.0));
    }
}

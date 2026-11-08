// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launcher's glyphs (`LauncherIcons`), generated at build time from `LauncherIcons.java`.
//!
//! GPUI's `svg()` element loads SVG files through the app's `AssetSource` and tints them with
//! `text_color`; [`crate::assets::Assets`] serves each icon at [`Icon::asset_path`].

use gpui::{App, IntoElement, RenderOnce, SharedString, Styled, Svg, Window, svg};

include!(concat!(env!("OUT_DIR"), "/icons.rs"));

/// The folder the icons are served from in the asset source.
pub const ASSET_DIR: &str = "mmcl/icons/";

impl Icon {
    /// The asset path, e.g. `mmcl/icons/arrow_up.svg`.
    pub fn asset_path(self) -> SharedString {
        SharedString::from(format!("{ASSET_DIR}{}.svg", self.file_stem()))
    }

    /// Looks an icon up from its asset path (the inverse of [`Icon::asset_path`]).
    pub fn from_asset_path(path: &str) -> Option<Icon> {
        let stem = path.strip_prefix(ASSET_DIR)?.strip_suffix(".svg")?;
        Icon::ALL.into_iter().find(|i| i.file_stem() == stem)
    }

    /// Looks an icon up from its Java constant name (`"ARROW_UP"`).
    pub fn from_java_name(name: &str) -> Option<Icon> {
        Icon::ALL.into_iter().find(|i| i.java_name() == name)
    }

    /// An `svg()` element for this icon at `size` px, ready for `.text_color(..)`.
    pub fn svg(self, size: f32) -> Svg {
        svg()
            .path(self.asset_path())
            .flex_shrink_0()
            .size(crate::scale::u(size))
    }
}

/// Lets launcher icons go wherever gpui-component takes an icon (`Button::icon`, `Icon::new`).
impl gpui_component::IconNamed for Icon {
    fn path(self) -> SharedString {
        self.asset_path()
    }
}

/// Renders the icon at the current text size and colour, like gpui-component's `IconName`.
impl RenderOnce for Icon {
    fn render(self, window: &mut Window, _: &mut App) -> impl IntoElement {
        let style = window.text_style();
        svg()
            .path(self.asset_path())
            .flex_shrink_0()
            .size(style.font_size.to_pixels(window.rem_size()))
            .text_color(style.color)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The spec's icon list (§10.16): every name must exist in the generated set.
    const SPEC_ICONS: [&str; 35] = [
        "ACCOUNT",
        "GAME",
        "APPEARANCE",
        "ADVANCED",
        "NETWORK",
        "SECURITY",
        "SYSTEM",
        "DISCORD",
        "RGB",
        "ABOUT",
        "REFRESH",
        "DOWNLOAD",
        "CHECK",
        "CLOSE",
        "REMOVE",
        "DOT",
        "LOCK",
        "PEOPLE",
        "SEARCH",
        "WARNING",
        "PLAY",
        "PREVIOUS",
        "NEXT",
        "ARROW_UP",
        "ARROW_DOWN",
        "COPY",
        "STOP",
        "EXPAND",
        "ANNOUNCEMENT",
        "DELETE",
        "HELP",
        "BROWSE",
        "OPEN_EXTERNAL",
        "EMPTY_BOX",
        "RUNTIME",
    ];

    #[test]
    fn every_spec_icon_is_generated() {
        for name in SPEC_ICONS {
            assert!(Icon::from_java_name(name).is_some(), "missing icon {name}");
        }
        assert!(Icon::ALL.len() >= SPEC_ICONS.len());
    }

    #[test]
    fn asset_paths_round_trip_and_are_unique() {
        let mut seen = std::collections::HashSet::new();
        for icon in Icon::ALL {
            let path = icon.asset_path();
            assert!(seen.insert(path.clone()), "duplicate {path}");
            assert_eq!(Icon::from_asset_path(&path), Some(icon));
        }
        assert_eq!(
            Icon::ArrowUp.asset_path().as_ref(),
            "mmcl/icons/arrow_up.svg"
        );
        assert_eq!(Icon::from_asset_path("icons/arrow_up.svg"), None);
    }

    #[test]
    fn documents_are_svg_with_the_path() {
        for icon in Icon::ALL {
            let doc = icon.svg_document();
            assert!(
                doc.starts_with("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">")
            );
            assert!(doc.contains(icon.path()));
            assert!(
                icon.path().starts_with('M'),
                "{icon:?} path starts with a move"
            );
        }
    }
}

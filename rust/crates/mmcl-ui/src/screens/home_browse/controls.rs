// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Small controls of the two screens: the option combo (a button with a checked dropdown menu),
//! a muted field label, and the shared images-of-a-pack resolution.

use std::rc::Rc;

use gpui::{App, ElementId, IntoElement, ParentElement, SharedString, Styled, Window, div};
use gpui_component::button::Button;
use gpui_component::menu::{DropdownMenu as _, PopupMenuItem};
use mmcl_engine::PackSummary;
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::theme::{ThemeExt as _, TypeStyleExt as _, hsla};

use super::backend::{LibraryBackend, PackImages};
use super::cycle;
use super::visuals::LogoSource;
use crate::scale::u;

/// A pick handler.
pub type Pick = Rc<dyn Fn(String, &mut Window, &mut App)>;

/// A combo box: shows the selected label, opens a checked list.
pub fn combo(
    id: impl Into<ElementId>,
    options: Vec<(String, String)>,
    selected: &str,
    min_w: f32,
    tooltip: impl Into<SharedString>,
    on_pick: Pick,
) -> impl IntoElement {
    let current = options
        .iter()
        .find(|(id, _)| id == selected)
        .map(|(_, l)| l.clone())
        .unwrap_or_default();
    let selected = selected.to_string();
    Button::new(id)
        .label(current)
        .outline()
        .dropdown_caret(true)
        .min_w(u(min_w))
        .h(u(32.0))
        .tooltip(tooltip)
        .dropdown_menu(move |mut menu, _, _| {
            for (oid, label) in &options {
                let pick = on_pick.clone();
                let oid2 = oid.clone();
                menu = menu.item(
                    PopupMenuItem::new(label.clone())
                        .checked(*oid == selected)
                        .on_click(move |_, window, cx| pick(oid2.clone(), window, cx)),
                );
            }
            menu
        })
}

/// A muted inline label (`Type:`, `Sort:` ...).
pub fn field_label(text: impl Into<SharedString>, cx: &App) -> impl IntoElement {
    div()
        .type_role(TypeRole::LabelLarge)
        .text_color(hsla(cx.tokens().color_text_muted))
        .child(text.into())
}

/// A vertical divider in a tool row.
pub fn divider(cx: &App) -> impl IntoElement {
    div()
        .w(u(1.0))
        .h(u(24.0))
        .bg(hsla(cx.tokens().color_divider))
}

/// The logo and background a card shows now (cycling through every cached image).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PackSources {
    pub logo: LogoSource,
    pub background: Option<LogoSource>,
    /// More than one image to cycle through.
    pub cycles: bool,
    /// Declared images are missing from the cache (start `cache_images`).
    pub needs_cache: bool,
}

/// Resolves a pack's images (`ModpackImageResolver`) at clock `tick`.
pub fn pack_sources(
    p: &PackSummary,
    images: &PackImages,
    show_backgrounds: bool,
    shuffle: bool,
    tick: u64,
) -> PackSources {
    let order = |v: &[std::path::PathBuf]| {
        if shuffle {
            cycle::shuffled(v, p.id.as_str())
        } else {
            v.to_vec()
        }
    };
    let logos = order(&images.logos);
    let bgs = order(&images.backgrounds);
    let logo = if p.is_vanilla {
        LogoSource::Vanilla
    } else if let Some(f) = cycle::pick(&logos, tick) {
        LogoSource::File(f)
    } else if let Some(url) = p.logo_url.as_ref().filter(|u| !u.trim().is_empty()) {
        LogoSource::Url(url.clone())
    } else {
        LogoSource::None
    };
    let background = if show_backgrounds && !p.is_vanilla {
        cycle::pick(&bgs, tick).map(LogoSource::File).or_else(|| {
            p.background_url
                .as_ref()
                .filter(|u| !u.trim().is_empty())
                .map(|u| LogoSource::Url(u.clone()))
        })
    } else {
        None
    };
    let cycles = cycle::can_cycle(logos.len(), p.logo_count > 0, bgs.len(), show_backgrounds);
    let needs_cache = p.installed
        && ((p.logo_count > 0 && images.logos.len() < p.logo_count)
            || (show_backgrounds
                && p.background_count > 0
                && images.backgrounds.len() < p.background_count));
    PackSources {
        logo,
        background,
        cycles,
        needs_cache,
    }
}

/// [`pack_sources`] read through the backend.
pub fn sources_for(
    cx: &App,
    backend: &Rc<dyn LibraryBackend>,
    p: &PackSummary,
    show_bg: bool,
    shuffle: bool,
) -> PackSources {
    pack_sources(
        p,
        &backend.pack_images(&p.id),
        show_bg,
        shuffle,
        cycle::tick(cx),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::screens::home_browse::home_logic::{tests::pack, vanilla_summary};
    use std::path::PathBuf;

    #[test]
    fn sources_prefer_files_then_urls() {
        let mut p = pack("A");
        p.installed = true;
        p.logo_url = Some("https://x/logo.png".into());
        p.logo_count = 2;
        let none = PackImages::default();
        let s = pack_sources(&p, &none, true, false, 0);
        assert_eq!(s.logo, LogoSource::Url("https://x/logo.png".into()));
        assert!(s.needs_cache);
        assert!(!s.cycles);
        let imgs = PackImages {
            logos: vec![PathBuf::from("a.png"), PathBuf::from("b.png")],
            backgrounds: vec![],
        };
        let s = pack_sources(&p, &imgs, true, false, 1);
        assert_eq!(s.logo, LogoSource::File(PathBuf::from("b.png")));
        assert!(s.cycles);
        assert!(!s.needs_cache);
        assert_eq!(
            pack_sources(&vanilla_summary("1.20"), &none, true, false, 0).logo,
            LogoSource::Vanilla
        );
        let mut q = pack("B");
        q.logo_url = None;
        assert_eq!(
            pack_sources(&q, &none, false, false, 0).logo,
            LogoSource::None
        );
    }
}

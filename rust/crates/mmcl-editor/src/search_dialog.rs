// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The "Search Modrinth" dialog of the Mods tab.
//!
//! Content: query field + Search, an optional game-version filter (pre-filled from a Forge
//! installer URL), an optional loader filter (pre-selected from the pack's loader), an info line
//! and a multi-select result list with icons. Enter in either field searches and never closes
//! the dialog. Right-click on a result opens its Modrinth page.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use gpui::{
    App, AppContext as _, Context, Entity, Image, InteractiveElement as _, IntoElement, ObjectFit,
    ParentElement as _, Render, SharedString, StatefulInteractiveElement as _, Styled as _,
    StyledImage as _, Subscription, Window, div, img, prelude::FluentBuilder as _, px,
    uniform_list,
};
use gpui_component::button::{Button, ButtonVariants as _};
use gpui_component::checkbox::Checkbox;
use gpui_component::input::{Input, InputEvent, InputState};
use gpui_component::menu::ContextMenuExt as _;
use gpui_component::{Disableable as _, Sizable as _};
use mmcl_i18n::{t, tf};
use mmcl_ui_kit::scale::u;
use mmcl_ui_kit::{ThemeExt as _, hsla};

use crate::backend::EditorBackend;
use crate::modrinth::{self, LOADER_FILTERS, SearchHit};

/// The filters as they stand when "Add selected" is pressed.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Filters {
    pub game_version: Option<String>,
    pub loader: Option<String>,
}

/// The dialog's view.
pub struct ModrinthSearch {
    backend: Arc<dyn EditorBackend>,
    query: Entity<InputState>,
    version: Entity<InputState>,
    version_on: bool,
    loader_on: bool,
    loader: usize,
    info: SharedString,
    hits: Vec<SearchHit>,
    selected: HashSet<usize>,
    icons: HashMap<String, Arc<Image>>,
    requested: HashSet<String>,
    generation: u64,
    searching: bool,
    _subs: Vec<Subscription>,
}

impl ModrinthSearch {
    /// A dialog pre-filled from the pack's loader URL and loader name.
    pub fn new(
        backend: Arc<dyn EditorBackend>,
        loader_url: &str,
        pack_loader: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let query = cx.new(|cx| {
            InputState::new(window, cx)
                .placeholder(t!("dialog.editor.searchModrinth.modNamePlaceholder").into_owned())
        });
        let prefill = crate::form::game_version_from_loader_url(loader_url);
        let version = cx.new(|cx| {
            InputState::new(window, cx).default_value(prefill.clone().unwrap_or_default())
        });
        let wanted = pack_loader.to_lowercase();
        let loader_idx = LOADER_FILTERS.iter().position(|l| *l == wanted);
        let mut subs = Vec::new();
        for input in [&query, &version] {
            subs.push(
                cx.subscribe_in(input, window, |this, _, ev: &InputEvent, _w, cx| {
                    if let InputEvent::PressEnter { .. } = ev {
                        this.search(cx);
                    }
                }),
            );
        }
        query.update(cx, |s, cx| s.focus(window, cx));
        ModrinthSearch {
            backend,
            query,
            version,
            version_on: prefill.is_some(),
            loader_on: loader_idx.is_some(),
            loader: loader_idx.unwrap_or(0),
            info: t!("editor.search.prompt").into_owned().into(),
            hits: Vec::new(),
            selected: HashSet::new(),
            icons: HashMap::new(),
            requested: HashSet::new(),
            generation: 0,
            searching: false,
            _subs: subs,
        }
    }

    /// The filters now.
    pub fn filters(&self, cx: &App) -> Filters {
        let gv = self.version.read(cx).value().trim().to_string();
        Filters {
            game_version: (self.version_on && !gv.is_empty()).then_some(gv),
            loader: self
                .loader_on
                .then(|| LOADER_FILTERS[self.loader].to_string()),
        }
    }

    /// The selected hits, in result order.
    pub fn selected_hits(&self) -> Vec<SearchHit> {
        let mut idx: Vec<usize> = self.selected.iter().copied().collect();
        idx.sort_unstable();
        idx.into_iter()
            .filter_map(|i| self.hits.get(i).cloned())
            .collect()
    }

    /// Runs the search (blank query: nothing).
    pub fn search(&mut self, cx: &mut Context<Self>) {
        let query = self.query.read(cx).value().trim().to_string();
        if query.is_empty() {
            return;
        }
        let filters = self.filters(cx);
        self.info = t!("editor.modSearch.searching").into_owned().into();
        self.hits.clear();
        self.selected.clear();
        self.generation += 1;
        self.searching = true;
        let generation = self.generation;
        let backend = self.backend.clone();
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_spawn(async move {
                    modrinth::search(
                        backend.as_ref(),
                        &query,
                        filters.game_version.as_deref(),
                        filters.loader.as_deref(),
                    )
                    .await
                })
                .await;
            let _ = this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.searching = false;
                match result {
                    Ok(hits) => {
                        this.info =
                            tf!("editor.modSearch.resultsFound", hits.len().to_string()).into();
                        this.hits = hits;
                        this.load_icons(cx);
                    }
                    Err(e) => {
                        this.info = tf!("editor.modSearch.failed", e.to_string()).into();
                    }
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn load_icons(&mut self, cx: &mut Context<Self>) {
        for hit in self.hits.clone() {
            let key = if hit.slug.is_empty() {
                hit.icon_url.clone()
            } else {
                hit.slug.clone()
            };
            if key.is_empty()
                || self.icons.contains_key(&key)
                || !self.requested.insert(key.clone())
            {
                continue;
            }
            if let Some(img) = crate::icons::cached_icon(&hit.slug) {
                self.icons.insert(key, img);
                continue;
            }
            let backend = self.backend.clone();
            cx.spawn(async move |this, cx| {
                let slug = hit.slug.clone();
                let url = hit.icon_url.clone();
                let image = cx
                    .background_spawn(async move {
                        crate::icons::load_icon(backend.as_ref(), &slug, &url).await
                    })
                    .await;
                let _ = this.update(cx, |this, cx| {
                    this.requested.remove(&key);
                    if let Some(image) = image {
                        this.icons.insert(key, image);
                        cx.notify();
                    }
                });
            })
            .detach();
        }
    }

    fn toggle(&mut self, ix: usize, cx: &mut Context<Self>) {
        if !self.selected.remove(&ix) {
            self.selected.insert(ix);
        }
        cx.notify();
    }

    fn render_hit(&self, ix: usize, cx: &mut Context<Self>) -> gpui::AnyElement {
        let t = cx.tokens();
        let hit = self.hits[ix].clone();
        let key = if hit.slug.is_empty() {
            hit.icon_url.clone()
        } else {
            hit.slug.clone()
        };
        let icon = self.icons.get(&key).cloned();
        let selected = self.selected.contains(&ix);
        let page = hit.page_url();
        let entity = cx.entity().downgrade();
        let row = div()
            .id(("modrinth-hit", ix))
            .flex()
            .items_center()
            .gap(u(10.0))
            .p(u(4.0))
            .h(px(52.0))
            .rounded(u(mmcl_theme::shape::SMALL))
            .cursor_pointer()
            .when(selected, |el| el.bg(hsla(t.md_secondary_container)))
            .hover(|s| s.bg(hsla(t.md_state_hover_on_surface)))
            .on_click(move |_, _w, cx| {
                let _ = entity.update(cx, |this, cx| this.toggle(ix, cx));
            })
            .child(
                Checkbox::new(("modrinth-hit-check", ix))
                    .checked(selected)
                    .tab_stop(false),
            )
            .child(
                div()
                    .size(px(40.0))
                    .flex_none()
                    .when_some(icon, |el, icon| {
                        el.child(img(icon).size(px(40.0)).object_fit(ObjectFit::Contain))
                    }),
            )
            .child(
                div()
                    .flex()
                    .flex_col()
                    .flex_1()
                    .overflow_hidden()
                    .child(
                        div()
                            .text_color(hsla(t.md_on_surface))
                            .font_weight(gpui::FontWeight::SEMIBOLD)
                            .truncate()
                            .child(hit.title_line()),
                    )
                    .child(
                        div()
                            .text_size(px(12.0))
                            .line_clamp(2)
                            .max_h(px(32.0))
                            .text_color(hsla(t.md_on_surface_variant))
                            .child(hit.short_description()),
                    ),
            );
        match page {
            Some(page) => row
                .context_menu(move |menu, _w, _cx| {
                    menu.link(t!("editor.modSearch.openPage").into_owned(), page.clone())
                })
                .into_any_element(),
            None => row.into_any_element(),
        }
    }
}

impl Render for ModrinthSearch {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let entity = cx.entity().downgrade();
        let e_search = entity.clone();
        let e_version = entity.clone();
        let e_loader = entity.clone();
        let count = self.hits.len();
        let list = uniform_list(
            "modrinth-results",
            count,
            cx.processor(|this, range: std::ops::Range<usize>, _w, cx| {
                range.map(|ix| this.render_hit(ix, cx)).collect::<Vec<_>>()
            }),
        )
        .h(px(350.0));
        div()
            .flex()
            .flex_col()
            .gap(u(8.0))
            .w_full()
            .child(
                div()
                    .text_color(hsla(t.md_on_surface_variant))
                    .child(t!("dialog.editor.searchModrinth.header").into_owned()),
            )
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(u(8.0))
                    .child(div().w(px(400.0)).child(Input::new(&self.query)))
                    .child(
                        Button::new("modrinth-search-btn")
                            .primary()
                            .loading(self.searching)
                            .label(t!("dialog.editor.searchModrinth.searchBtn").into_owned())
                            .on_click(move |_, _w, cx| {
                                let _ = e_search.update(cx, |this, cx| this.search(cx));
                            }),
                    ),
            )
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(u(8.0))
                    .child(
                        Checkbox::new("modrinth-version-filter")
                            .label(t!("dialog.editor.searchModrinth.versionFilter").into_owned())
                            .checked(self.version_on)
                            .on_click(move |checked, _w, cx| {
                                let checked = *checked;
                                let _ = e_version.update(cx, |this, cx| {
                                    this.version_on = checked;
                                    cx.notify();
                                });
                            }),
                    )
                    .child(
                        div()
                            .w(px(100.0))
                            .child(Input::new(&self.version).disabled(!self.version_on)),
                    ),
            )
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(u(8.0))
                    .child(
                        Checkbox::new("modrinth-loader-filter")
                            .label(t!("dialog.editor.searchModrinth.loaderFilter").into_owned())
                            .checked(self.loader_on)
                            .on_click({
                                let e = e_loader.clone();
                                move |checked, _w, cx| {
                                    let checked = *checked;
                                    let _ = e.update(cx, |this, cx| {
                                        this.loader_on = checked;
                                        cx.notify();
                                    });
                                }
                            }),
                    )
                    .children(LOADER_FILTERS.iter().enumerate().map(|(i, l)| {
                        let e = e_loader.clone();
                        let b = Button::new(("modrinth-loader", i))
                            .small()
                            .label(*l)
                            .disabled(!self.loader_on)
                            .on_click(move |_, _w, cx| {
                                let _ = e.update(cx, |this, cx| {
                                    this.loader = i;
                                    cx.notify();
                                });
                            });
                        if i == self.loader {
                            b.primary()
                        } else {
                            b.ghost()
                        }
                    })),
            )
            .child(
                div()
                    .text_color(hsla(t.md_on_surface_variant))
                    .child(self.info.clone()),
            )
            .child(
                div()
                    .border_1()
                    .border_color(hsla(t.md_outline_variant))
                    .rounded(u(mmcl_theme::shape::SMALL))
                    .child(list),
            )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn filters_default_to_none() {
        let f = Filters::default();
        assert!(f.game_version.is_none() && f.loader.is_none());
    }
}

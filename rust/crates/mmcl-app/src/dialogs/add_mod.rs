// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! "Add a Mod from Modrinth" (Java `ModrinthAddModDialog`), opened by the pack detail modal's
//! Add mod button: a search box, the compatibility note, a status line and up to 25 results,
//! each with an Add button that downloads the newest file compatible with the pack's Minecraft
//! version and loader into the pack's `mods/` folder. Vanilla packs have no loader and get a
//! warning instead of the window.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use gpui::{
    AnyWindowHandle, App, AppContext as _, Context, Entity, InteractiveElement as _, IntoElement,
    ParentElement, Render, SharedString, StatefulInteractiveElement as _, Styled, Subscription,
    Window, div, px,
};
use gpui_component::input::{Input, InputEvent, InputState};
use mmcl_engine::{Engine, ModSearchHit, PackId};
use mmcl_i18n::{t, tf};
use mmcl_ui_kit::focus::nav_scope;
use mmcl_ui_kit::scale::u;
use mmcl_ui_kit::{ThemeExt as _, hsla, notify};

use super::widgets::{Pill, Tone, hint, muted, title};

/// The window size (px at 100 %), Java's 560 x 520 scene.
pub const SIZE: (f32, f32) = (560.0, 520.0);
/// The smallest the window may get.
pub const MIN_SIZE: (f32, f32) = (420.0, 360.0);
/// How many results one search asks for.
pub const RESULT_LIMIT: usize = 25;

/// A compact download count (`formatDownloads`): `1.2M`, `45.0K`, else the number.
pub fn format_downloads(downloads: i64) -> String {
    // Java's `%.1f` rounds half up; Rust's `{:.1}` rounds half to even.
    let one_decimal = |v: f64| (v * 10.0).round() / 10.0;
    if downloads >= 1_000_000 {
        format!("{:.1}M", one_decimal(downloads as f64 / 1_000_000.0))
    } else if downloads >= 1_000 {
        format!("{:.1}K", one_decimal(downloads as f64 / 1_000.0))
    } else {
        downloads.to_string()
    }
}

/// The name a hit is shown and reported under: its title, else its slug, else `?`.
pub fn hit_name(hit: &ModSearchHit) -> String {
    [hit.title.as_deref(), hit.slug.as_deref()]
        .into_iter()
        .flatten()
        .map(str::trim)
        .find(|s| !s.is_empty())
        .unwrap_or("?")
        .to_string()
}

/// The file name a download is saved under: Modrinth's file name, else the URL's last path
/// segment. `None` when neither is a plain file name (a separator or `..` would leave the
/// `mods/` folder).
pub fn target_file_name(filename: Option<&str>, url: &str) -> Option<String> {
    let from_url = url
        .split(['?', '#'])
        .next()
        .unwrap_or("")
        .rsplit('/')
        .next()
        .unwrap_or("");
    let name = filename
        .map(str::trim)
        .filter(|f| !f.is_empty())
        .unwrap_or(from_url)
        .trim();
    let plain = !name.is_empty()
        && !name.contains(['/', '\\', ':'])
        && name != "."
        && name != ".."
        && !name.starts_with("..");
    plain.then(|| name.to_string())
}

/// Why adding a mod did not work.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AddError {
    /// Modrinth has no file for this pack's Minecraft version and loader.
    NoCompatible,
    /// The download (or the file name) failed.
    Failed(String),
}

/// Resolves the newest compatible file of `project_id` and downloads it into `mods_dir`
/// (through a temp file, so a failed download leaves nothing behind). Returns the file name.
async fn add_mod(
    engine: Engine,
    project_id: String,
    mc_version: Option<String>,
    loader: String,
    mods_dir: PathBuf,
) -> Result<String, AddError> {
    let file = engine
        .resolve_modrinth_file(&project_id, mc_version.as_deref(), Some(&loader))
        .await
        .filter(|f| !f.url.trim().is_empty())
        .ok_or(AddError::NoCompatible)?;
    if !file.url.trim().to_ascii_lowercase().starts_with("https://") {
        return Err(AddError::Failed(tf!(
            "managedFile.error.nonHttps",
            file.url.as_str()
        )));
    }
    let name = target_file_name(file.filename.as_deref(), &file.url)
        .ok_or_else(|| AddError::Failed(file.url.clone()))?;
    tokio::fs::create_dir_all(&mods_dir)
        .await
        .map_err(|e| AddError::Failed(e.to_string()))?;
    let tmp = temp_file(&mods_dir, &name);
    if let Err(e) =
        mmcl_net::download_file_url(&file.url, &tmp, &mmcl_net::DownloadOpts::new()).await
    {
        let _ = tokio::fs::remove_file(&tmp).await;
        return Err(AddError::Failed(e.to_string()));
    }
    if let Err(e) = tokio::fs::rename(&tmp, mods_dir.join(&name)).await {
        let _ = tokio::fs::remove_file(&tmp).await;
        return Err(AddError::Failed(e.to_string()));
    }
    Ok(name)
}

/// The temp file a download goes to before it is moved into place.
pub fn temp_file(mods_dir: &Path, name: &str) -> PathBuf {
    mods_dir.join(format!(".{name}.add.tmp"))
}

/// One result's Add button.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RowState {
    Idle,
    Adding,
    Added,
}

/// The dialog's root view.
pub struct AddModView {
    engine: Engine,
    loader: String,
    mc_version: Option<String>,
    mods_dir: PathBuf,
    query: Entity<InputState>,
    status: SharedString,
    hits: Vec<ModSearchHit>,
    rows: HashMap<usize, RowState>,
    searching: bool,
    generation: u64,
    _subs: Vec<Subscription>,
}

impl AddModView {
    fn new(
        engine: Engine,
        loader: String,
        mc_version: Option<String>,
        mods_dir: PathBuf,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let query = cx.new(|cx| {
            InputState::new(window, cx).placeholder(t!("addMod.search.prompt").into_owned())
        });
        let subs =
            vec![
                cx.subscribe_in(&query, window, |this, _, ev: &InputEvent, _window, cx| {
                    if let InputEvent::PressEnter { .. } = ev {
                        this.search(cx);
                    }
                }),
            ];
        query.update(cx, |s, cx| s.focus(window, cx));
        AddModView {
            engine,
            loader,
            mc_version,
            mods_dir,
            query,
            status: SharedString::default(),
            hits: Vec::new(),
            rows: HashMap::new(),
            searching: false,
            generation: 0,
            _subs: subs,
        }
    }

    /// Runs the search (a blank query does nothing).
    fn search(&mut self, cx: &mut Context<Self>) {
        let query = self.query.read(cx).value().trim().to_string();
        if query.is_empty() || self.searching {
            return;
        }
        self.searching = true;
        self.hits.clear();
        self.rows.clear();
        self.status = t!("addMod.searching").into_owned().into();
        self.generation += 1;
        let generation = self.generation;
        let engine = self.engine.clone();
        let mc = self.mc_version.clone();
        let loader = self.loader.clone();
        let task = self.engine.spawn(async move {
            engine
                .search_modrinth(&query, mc.as_deref(), Some(&loader), RESULT_LIMIT)
                .await
        });
        cx.notify();
        cx.spawn(async move |this, cx| {
            let hits = task.await.unwrap_or_default();
            let _ = this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.searching = false;
                this.status = if hits.is_empty() {
                    t!("addMod.noResults").into_owned().into()
                } else {
                    tf!("addMod.resultCount", hits.len() as i64).into()
                };
                this.hits = hits;
                cx.notify();
            });
        })
        .detach();
    }

    /// Adds result `index` to the pack's `mods/` folder.
    fn add(&mut self, index: usize, cx: &mut Context<Self>) {
        let Some(hit) = self.hits.get(index) else {
            return;
        };
        if self.rows.get(&index).copied().unwrap_or(RowState::Idle) != RowState::Idle {
            return;
        }
        let name = hit_name(hit);
        let Some(project) = hit.project_id.clone().or_else(|| hit.slug.clone()) else {
            return;
        };
        let slug = hit.slug.clone().unwrap_or_else(|| project.clone());
        self.rows.insert(index, RowState::Adding);
        cx.notify();
        let generation = self.generation;
        let task = self.engine.spawn(add_mod(
            self.engine.clone(),
            project,
            self.mc_version.clone(),
            self.loader.clone(),
            self.mods_dir.clone(),
        ));
        cx.spawn(async move |this, cx| {
            let result = task
                .await
                .unwrap_or_else(|e| Err(AddError::Failed(e.to_string())));
            let done = match result {
                Ok(_) => {
                    notify::success(
                        &t!("addMod.success.title"),
                        &tf!("addMod.success.body", name.as_str()),
                    );
                    RowState::Added
                }
                Err(AddError::NoCompatible) => {
                    notify::warn(
                        &t!("addMod.noCompatible.title"),
                        &tf!("addMod.noCompatible.body", name.as_str()),
                    );
                    RowState::Idle
                }
                Err(AddError::Failed(message)) => {
                    tracing::warn!(
                        "{}",
                        tf!("log.addMod.downloadFailed", slug.as_str(), message.as_str())
                    );
                    notify::error(
                        &t!("addMod.fail.title"),
                        &tf!("addMod.fail.body", name.as_str()),
                    );
                    RowState::Idle
                }
            };
            let _ = this.update(cx, |this, cx| {
                if this.generation == generation {
                    this.rows.insert(index, done);
                    cx.notify();
                }
            });
        })
        .detach();
    }

    fn render_row(&self, index: usize, hit: &ModSearchHit, cx: &mut Context<Self>) -> gpui::Div {
        let t = cx.tokens();
        let state = self.rows.get(&index).copied().unwrap_or(RowState::Idle);
        let label = match state {
            RowState::Idle => t!("addMod.add"),
            RowState::Adding => t!("addMod.adding"),
            RowState::Added => t!("addMod.added"),
        };
        let meta = tf!(
            "addMod.result.meta",
            hit.author.as_deref().unwrap_or("?"),
            format_downloads(hit.downloads)
        );
        let view = cx.entity().downgrade();
        div()
            .flex()
            .items_center()
            .gap(u(10.0))
            .py(u(6.0))
            .px(u(4.0))
            .border_b_1()
            .border_color(hsla(t.md_outline_variant))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .gap(u(2.0))
                    .child(title(hit_name(hit), cx))
                    .child(muted(meta, cx))
                    .child(hint(hit.description.clone().unwrap_or_default(), cx)),
            )
            .child(
                Pill::new(("add-mod-row", index), label.into_owned(), Tone::Tonal)
                    .height(28.0)
                    .disabled(state != RowState::Idle)
                    .on_click(move |_, cx| {
                        let _ = view.update(cx, |this, cx| this.add(index, cx));
                    }),
            )
    }
}

impl Render for AddModView {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let search = cx.entity().downgrade();
        let rows: Vec<gpui::AnyElement> = self
            .hits
            .clone()
            .iter()
            .enumerate()
            .map(|(i, hit)| self.render_row(i, hit, cx).into_any_element())
            .collect();
        nav_scope("add-mod")
            .size_full()
            .flex()
            .flex_col()
            .bg(mmcl_ui::theme::secondary_fill(cx))
            .text_color(hsla(t.color_text))
            .font_family(mmcl_ui_kit::fonts::UI_FAMILY)
            .child(
                gpui_component::TitleBar::new()
                    .bg(gpui::transparent_black())
                    .border_color(gpui::transparent_black())
                    .child(
                        div()
                            .text_size(px(12.0))
                            .text_color(hsla(t.color_text_muted))
                            .child(t!("addMod.title").into_owned()),
                    ),
            )
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .flex()
                    .flex_col()
                    .gap(u(10.0))
                    .p(u(16.0))
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(u(8.0))
                            .child(div().flex_1().child(Input::new(&self.query)))
                            .child(
                                Pill::new(
                                    "add-mod-search",
                                    t!("addMod.search.button").into_owned(),
                                    Tone::Primary,
                                )
                                .height(32.0)
                                .disabled(self.searching)
                                .on_click(move |_, cx| {
                                    let _ = search.update(cx, |this, cx| this.search(cx));
                                }),
                            ),
                    )
                    .child(muted(tf!("addMod.compatNote", self.loader.as_str()), cx))
                    .child(muted(self.status.clone(), cx))
                    .child(
                        div()
                            .id("add-mod-results")
                            .flex_1()
                            .min_h_0()
                            .overflow_y_scroll()
                            .flex()
                            .flex_col()
                            .children(rows),
                    ),
            )
    }
}

/// Opens the dialog for pack `id`, downloading into `mods_dir`. A vanilla pack (no loader)
/// gets the `addMod.vanilla` warning instead.
pub fn show_add_mod(
    engine: Engine,
    id: PackId,
    mods_dir: PathBuf,
    owner: Option<AnyWindowHandle>,
    cx: &mut App,
) {
    let Some(summary) = engine.pack(&id) else {
        return;
    };
    let Some(loader) = summary
        .mod_loader
        .clone()
        .filter(|l| !summary.is_vanilla && !l.trim().is_empty())
    else {
        notify::warn(&t!("addMod.vanilla.title"), &t!("addMod.vanilla.body"));
        return;
    };
    let mc_version = summary.minecraft_version.clone();
    super::open_secondary_window(
        super::WindowSpec {
            title: t!("addMod.title").into_owned(),
            size: SIZE,
            min_size: Some(MIN_SIZE),
            resizable: true,
        },
        owner,
        cx,
        move |window, cx| {
            cx.new(|cx| AddModView::new(engine, loader, mc_version, mods_dir, window, cx))
                .into()
        },
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn downloads_read_like_java() {
        assert_eq!(format_downloads(0), "0");
        assert_eq!(format_downloads(999), "999");
        assert_eq!(format_downloads(1_000), "1.0K");
        assert_eq!(format_downloads(45_040), "45.0K");
        assert_eq!(format_downloads(1_250_000), "1.3M");
    }

    #[test]
    fn hits_are_named_by_title_then_slug() {
        let mut h = ModSearchHit {
            slug: Some("sodium".into()),
            ..Default::default()
        };
        assert_eq!(hit_name(&h), "sodium");
        h.title = Some("Sodium".into());
        assert_eq!(hit_name(&h), "Sodium");
        h.title = Some("  ".into());
        assert_eq!(hit_name(&h), "sodium");
        assert_eq!(hit_name(&ModSearchHit::default()), "?");
    }

    #[test]
    fn the_file_name_stays_inside_the_mods_folder() {
        let url = "https://cdn.modrinth.com/data/x/versions/y/sodium-0.5.jar?a=1";
        assert_eq!(
            target_file_name(Some("sodium.jar"), url).as_deref(),
            Some("sodium.jar")
        );
        assert_eq!(
            target_file_name(None, url).as_deref(),
            Some("sodium-0.5.jar")
        );
        assert_eq!(
            target_file_name(Some(" "), url).as_deref(),
            Some("sodium-0.5.jar")
        );
        assert_eq!(target_file_name(Some("../evil.jar"), url), None);
        assert_eq!(target_file_name(Some("a\\b.jar"), url), None);
        assert_eq!(target_file_name(Some("C:x.jar"), url), None);
        assert_eq!(target_file_name(None, "https://x/"), None);
    }

    #[test]
    fn downloads_land_in_a_temp_file_first() {
        assert_eq!(
            temp_file(Path::new("mods"), "a.jar"),
            Path::new("mods").join(".a.jar.add.tmp")
        );
    }
}

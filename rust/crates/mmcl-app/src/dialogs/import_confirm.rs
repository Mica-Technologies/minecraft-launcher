// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The import confirmation (Java `MCLauncherImportConfirmDialog`, gui-editor-games §11.2):
//! shown between preparing an import ([`Engine::prepare_import`] / [`Engine::prepare_modrinth`]:
//! the archive is downloaded and read, nothing installed) and installing it. A 600 x 520
//! window, modal over its owner: the pack name, a `Version · Minecraft · loader` line, the totals
//! (mods, configs, resource packs, shader packs, other files, total size), the scrolling mod list
//! (`[size]  file name`), a note, and Cancel / Import.
//!
//! [`confirm_preview`] serves the Browse screen's prepared-import hook. The screen commits the
//! import ([`Engine::commit_import`]) when it resolves `true`, and discards it
//! ([`Engine::discard_import`], which deletes the download) on Cancel or close.
//!
//! A source the engine reads only on confirm (a Mica URL, zip, manifest file, instance folder)
//! has no file list yet: the list is replaced by a line saying so, and the title names the
//! source. [`confirm_import`] serves the Browse screen's older hook, which passes only a name
//! and version.

use std::cell::RefCell;
use std::rc::Rc;

use futures::channel::oneshot;
use gpui::{
    App, AppContext as _, Context, InteractiveElement as _, IntoElement, ParentElement, Render,
    StatefulInteractiveElement as _, Styled, Task, Window, div, prelude::FluentBuilder as _, px,
};
#[cfg(doc)]
use mmcl_engine::Engine;
use mmcl_engine::{ImportFileView, ImportPreview, ImportTotals};
use mmcl_i18n::{t, tf};
use mmcl_ui::screens::home_browse::ImportConfirm;
use mmcl_ui_kit::focus::nav_scope;
use mmcl_ui_kit::scale::u;
use mmcl_ui_kit::{ThemeExt as _, hsla};

use super::Owner;
use super::widgets::{Pill, Tone, heading, hint, mono, muted, subtitle};

/// The window size (px at 100 %).
pub const SIZE: (f32, f32) = (600.0, 520.0);

/// What the dialog shows, already rendered to text.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Shown {
    /// The heading: the pack name, else the source, else `dialog.importConfirm.fallbackName`.
    pub title: String,
    /// `Version · Minecraft · loader` (blank parts left out).
    pub subtitle: String,
    /// The totals line; `None` when the file list is not known yet.
    pub totals: Option<String>,
    /// One row per mod; `None` when the file list is not known yet.
    pub rows: Option<Vec<String>>,
}

impl Shown {
    /// From a prepared import.
    pub fn of_preview(p: &ImportPreview) -> Shown {
        let details = p.has_details;
        Shown {
            title: title_text(p.pack_name.as_deref(), &p.source_label),
            subtitle: subtitle_text(
                p.pack_version.as_deref(),
                p.minecraft_version.as_deref(),
                p.loader_type.as_deref(),
                p.loader_version.as_deref(),
            ),
            totals: details.then(|| totals_text(&p.totals)),
            rows: details.then(|| mod_rows(p.mods())),
        }
    }

    /// From the Browse screen's hook input (name and version only, no file list).
    pub fn of_hook(c: &ImportConfirm) -> Shown {
        Shown {
            title: title_text(Some(&c.name), ""),
            subtitle: subtitle_text(c.version.as_deref(), None, None, None),
            totals: None,
            rows: None,
        }
    }
}

fn present(s: Option<&str>) -> Option<&str> {
    s.filter(|v| !v.trim().is_empty())
}

/// The heading: the name, else the source label, else the localized fallback.
pub fn title_text(name: Option<&str>, source_label: &str) -> String {
    present(name)
        .or(present(Some(source_label)))
        .map(str::to_string)
        .unwrap_or_else(|| t!("dialog.importConfirm.fallbackName").into_owned())
}

/// The display name of a loader (`forge` keeps Java's own `Forge {0}` key).
fn loader_label(loader: &str, version: &str) -> String {
    match loader.to_ascii_lowercase().as_str() {
        "forge" => tf!("importConfirm.meta.forge", version),
        "neoforge" => tf!("importConfirm.meta.loader", "NeoForge", version),
        "fabric" | "fabric-loader" => tf!("importConfirm.meta.loader", "Fabric", version),
        "quilt" | "quilt-loader" => tf!("importConfirm.meta.loader", "Quilt", version),
        _ => tf!("importConfirm.meta.loader", loader, version),
    }
}

/// The subtitle: version, Minecraft and loader (each when present) joined by ` · `.
pub fn subtitle_text(
    version: Option<&str>,
    minecraft: Option<&str>,
    loader: Option<&str>,
    loader_version: Option<&str>,
) -> String {
    let mut parts = Vec::new();
    if let Some(v) = present(version) {
        parts.push(tf!("importConfirm.meta.version", v));
    }
    if let Some(mc) = present(minecraft) {
        parts.push(tf!("importConfirm.meta.minecraft", mc));
    }
    if let (Some(l), Some(v)) = (present(loader), present(loader_version)) {
        parts.push(loader_label(l, v));
    }
    parts.join(" \u{00B7} ")
}

/// The human size, or `None` for 0 (Java skipped the `[size]` prefix then).
pub fn format_bytes(bytes: u64) -> Option<String> {
    (bytes > 0).then(|| mmcl_util::units::bytes(i64::try_from(bytes).unwrap_or(i64::MAX)))
}

/// The totals line (`buildTotalsText`): mods always, then each non-zero count joined with
/// `importConfirm.totals.join`, then the size with `importConfirm.totals.withSize`.
pub fn totals_text(c: &ImportTotals) -> String {
    let n = |v: usize| i64::try_from(v).unwrap_or(i64::MAX);
    let mut text = tf!("importConfirm.totals.mods", n(c.mods));
    for (key, count) in [
        ("importConfirm.totals.configs", c.configs),
        ("importConfirm.totals.resourcePacks", c.resource_packs),
        ("importConfirm.totals.shaderPacks", c.shader_packs),
        ("importConfirm.totals.otherFiles", c.other),
    ] {
        if count > 0 {
            text = tf!("importConfirm.totals.join", text, tf!(key, n(count)));
        }
    }
    if let Some(size) = format_bytes(c.total_bytes) {
        text = tf!(
            "importConfirm.totals.withSize",
            text,
            tf!("importConfirm.totals.size", size)
        );
    }
    text
}

/// One row per mod: `[size]  name` (size left out when unknown); a server-only file (one the
/// launcher skips) says so.
pub fn mod_rows<'a>(mods: impl Iterator<Item = &'a ImportFileView>) -> Vec<String> {
    mods.map(|f| {
        let row = match format_bytes(f.size) {
            Some(size) => format!("[{size}]  {}", f.file_name),
            None => f.file_name.clone(),
        };
        if f.client_supported {
            row
        } else {
            tf!("importConfirm.row.serverOnly", row)
        }
    })
    .collect()
}

type Reply = Rc<RefCell<Option<oneshot::Sender<bool>>>>;

fn reply(slot: &Reply, value: bool) {
    if let Some(tx) = slot.borrow_mut().take() {
        let _ = tx.send(value);
    }
}

/// The dialog's root view.
pub struct ImportConfirmView {
    shown: Shown,
    slot: Reply,
}

impl ImportConfirmView {
    fn new(shown: Shown, slot: Reply) -> Self {
        ImportConfirmView { shown, slot }
    }

    fn answer(&self, value: bool, window: &mut Window, cx: &mut App) {
        reply(&self.slot, value);
        super::close_window(window, cx);
    }
}

impl Render for ImportConfirmView {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let shown = &self.shown;
        let cancel = cx.entity().downgrade();
        let import = cx.entity().downgrade();
        let list: Vec<gpui::AnyElement> = match &shown.rows {
            None => {
                vec![muted(t!("importConfirm.unavailable").into_owned(), cx).into_any_element()]
            }
            Some(rows) if rows.is_empty() => {
                vec![hint(t!("importConfirm.empty").into_owned(), cx).into_any_element()]
            }
            Some(rows) => rows
                .iter()
                .map(|r| mono(r.clone(), cx).into_any_element())
                .collect(),
        };
        nav_scope("import-confirm")
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
                            .child(t!("dialog.importConfirm.title").into_owned()),
                    ),
            )
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .flex()
                    .flex_col()
                    .gap(u(8.0))
                    .p(u(16.0))
                    .child(heading(shown.title.clone(), cx))
                    .when(!shown.subtitle.is_empty(), |el| {
                        el.child(subtitle(shown.subtitle.clone(), cx))
                    })
                    .when_some(shown.totals.clone(), |el, totals| {
                        el.child(
                            div()
                                .text_size(u(12.0))
                                .text_color(hsla(t.md_on_surface))
                                .child(totals),
                        )
                    })
                    .child(
                        div()
                            .id("import-mod-list")
                            .flex_1()
                            .min_h(u(120.0))
                            .max_h(u(360.0))
                            .overflow_y_scroll()
                            .rounded(u(8.0))
                            .bg(hsla(t.md_surface_container_low))
                            .border_1()
                            .border_color(hsla(t.md_outline_variant))
                            .py(u(4.0))
                            .px(u(8.0))
                            .flex()
                            .flex_col()
                            .gap(u(2.0))
                            .children(list),
                    )
                    .child(hint(t!("importConfirm.note").into_owned(), cx))
                    .child(
                        div()
                            .pt(u(12.0))
                            .flex()
                            .justify_end()
                            .gap(u(8.0))
                            .child(
                                Pill::new(
                                    "import-cancel",
                                    t!("common.button.cancel").into_owned(),
                                    Tone::Tonal,
                                )
                                .on_click(move |window, cx| {
                                    let _ = cancel
                                        .update(cx, |this, cx| this.answer(false, window, cx));
                                }),
                            )
                            .child(
                                Pill::new(
                                    "import-ok",
                                    t!("common.button.import").into_owned(),
                                    Tone::Primary,
                                )
                                .on_click(move |window, cx| {
                                    let _ =
                                        import.update(cx, |this, cx| this.answer(true, window, cx));
                                }),
                            ),
                    ),
            )
    }
}

/// Shows the confirmation, modal over `owner`; resolves `true` only on Import. Closing the
/// window cancels, and so does a window that could not open.
pub fn show_confirm(shown: Shown, owner: impl Into<Owner>, cx: &mut App) -> Task<bool> {
    let (tx, rx) = oneshot::channel::<bool>();
    let slot: Reply = Rc::new(RefCell::new(Some(tx)));
    let close_slot = slot.clone();
    let opened = super::open_secondary_window(
        super::WindowSpec {
            title: t!("dialog.importConfirm.title").into_owned(),
            size: SIZE,
            min_size: None,
            resizable: false,
            modal: true,
            bounds_key: None,
        },
        owner,
        cx,
        move |_, cx| cx.new(|_| ImportConfirmView::new(shown, slot)).into(),
    );
    match opened {
        Some(handle) => {
            let _ = handle.update(cx, |_, window, cx| {
                window.on_window_should_close(cx, move |window, cx| {
                    reply(&close_slot, false);
                    super::release_owner(window, cx);
                    true
                });
            });
        }
        None => reply(&close_slot, false),
    }
    cx.spawn(async move |_| rx.await.unwrap_or(false))
}

/// The Browse screen's hook (name and version only): resolves `true` to import.
pub fn confirm_import(request: ImportConfirm, owner: impl Into<Owner>, cx: &mut App) -> Task<bool> {
    show_confirm(Shown::of_hook(&request), owner, cx)
}

/// The Browse screen's prepared-import hook: shows the file list and totals of `preview`;
/// resolves `true` to install it. The screen commits or discards the prepared import itself.
pub fn confirm_preview(
    preview: ImportPreview,
    owner: impl Into<Owner>,
    cx: &mut App,
) -> Task<bool> {
    show_confirm(Shown::of_preview(&preview), owner, cx)
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_engine::{ImportFileCategory, ImportPreviewSource, ImportToken};

    fn f(path: &str, size: u64, client: bool) -> ImportFileView {
        ImportFileView {
            path: path.into(),
            file_name: path.rsplit(['/', '\\']).next().unwrap_or(path).into(),
            size,
            category: ImportFileCategory::of_path(path),
            client_supported: client,
        }
    }

    fn preview(files: Vec<ImportFileView>, details: bool) -> ImportPreview {
        ImportPreview {
            token: ImportToken(7),
            source: ImportPreviewSource::ModrinthProject,
            has_details: details,
            pack_name: Some("Fabulously Optimized".into()),
            pack_version: Some("5.4.1".into()),
            minecraft_version: Some("1.20.1".into()),
            loader_type: Some("fabric".into()),
            loader_version: Some("0.15.0".into()),
            summary: None,
            icon_url: None,
            totals: ImportTotals::of(&files),
            files,
            source_label: "fabulously-optimized".into(),
        }
    }

    #[test]
    fn totals_list_only_non_zero_counts() {
        let only_mods = ImportTotals {
            mods: 1,
            ..Default::default()
        };
        assert_eq!(totals_text(&only_mods), "1 mod");
        assert_eq!(totals_text(&ImportTotals::default()), "0 mods");
        let mixed = ImportTotals {
            mods: 3,
            configs: 1,
            shader_packs: 2,
            ..Default::default()
        };
        assert_eq!(totals_text(&mixed), "3 mods, 1 config, 2 shader packs");
        let sized = ImportTotals {
            mods: 2,
            total_bytes: 2048,
            ..Default::default()
        };
        let text = totals_text(&sized);
        assert!(text.starts_with("2 mods  \u{00B7}  "), "{text}");
        assert!(text.ends_with(" total"), "{text}");
    }

    #[test]
    fn rows_list_mods_with_size_and_mark_server_only_files() {
        let p = preview(
            vec![
                f("mods/a.jar", 0, true),
                f("config/x.toml", 10, true),
                f("mods/b.jar", 1536, true),
                f("mods/server.jar", 0, false),
            ],
            true,
        );
        let shown = Shown::of_preview(&p);
        let rows = shown.rows.expect("a prepared .mrpack has its file list");
        assert_eq!(rows.len(), 3, "only mods/ entries are listed");
        assert_eq!(rows[0], "a.jar");
        assert!(
            rows[1].starts_with('[') && rows[1].ends_with("]  b.jar"),
            "{}",
            rows[1]
        );
        assert_eq!(rows[2], tf!("importConfirm.row.serverOnly", "server.jar"));
        assert_eq!(
            shown.totals.as_deref(),
            Some(totals_text(&p.totals).as_str())
        );
        assert!(shown.totals.unwrap().starts_with("3 mods, 1 config"));
    }

    #[test]
    fn heading_and_subtitle_follow_the_preview() {
        let p = preview(Vec::new(), true);
        let shown = Shown::of_preview(&p);
        assert_eq!(shown.title, "Fabulously Optimized");
        assert_eq!(
            shown.subtitle,
            "Version 5.4.1 \u{00B7} Minecraft 1.20.1 \u{00B7} Fabric 0.15.0"
        );
        assert_eq!(
            shown.rows,
            Some(Vec::new()),
            "an empty list shows the empty line"
        );
        assert_eq!(
            subtitle_text(Some(" "), Some("1.12.2"), Some("forge"), Some("14.23.5")),
            "Minecraft 1.12.2 \u{00B7} Forge 14.23.5"
        );
        assert_eq!(
            subtitle_text(None, None, Some("neoforge"), None),
            "",
            "a loader without a version is left out"
        );
    }

    #[test]
    fn a_source_read_on_confirm_has_no_list_and_is_named_by_source() {
        let mut p = preview(Vec::new(), false);
        p.pack_name = None;
        p.source_label = "My Pack.zip".into();
        let shown = Shown::of_preview(&p);
        assert_eq!(shown.title, "My Pack.zip");
        assert_eq!(shown.rows, None);
        assert_eq!(shown.totals, None);
        assert_eq!(
            title_text(None, " "),
            t!("dialog.importConfirm.fallbackName")
        );
    }

    #[test]
    fn hook_input_has_no_file_index() {
        let shown = Shown::of_hook(&ImportConfirm {
            name: "X".into(),
            version: Some("2".into()),
        });
        assert_eq!(shown.title, "X");
        assert_eq!(shown.subtitle, "Version 2");
        assert!(shown.rows.is_none() && shown.totals.is_none());
    }

    #[test]
    fn sizes_skip_zero() {
        assert_eq!(format_bytes(0), None);
        assert!(format_bytes(1).is_some());
        assert!(format_bytes(u64::MAX).is_some());
    }
}

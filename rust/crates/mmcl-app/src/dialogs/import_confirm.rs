// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The import confirmation (Java `MCLauncherImportConfirmDialog`, gui-editor-games §11.2):
//! shown between translating a Modrinth pack (`.mrpack` index) and installing it. A 600 x 520
//! window: the pack name, a `Version · Minecraft · loader` line, the totals (mods, configs,
//! resource packs, shader packs, other files, total size), the scrolling mod list, a note, and
//! Cancel / Import. It resolves `true` only on Import; closing the window cancels.
//!
//! When the caller has no file index yet (the engine imports a URL in one step today), the list
//! is replaced by a line saying the files are read when the import starts.

use std::cell::RefCell;
use std::rc::Rc;

use futures::channel::oneshot;
use gpui::{
    AnyWindowHandle, App, AppContext as _, Context, InteractiveElement as _, IntoElement,
    ParentElement, Render, StatefulInteractiveElement as _, Styled, Task, Window, div,
    prelude::FluentBuilder as _, px,
};
use mmcl_i18n::{t, tf};
use mmcl_ui::screens::home_browse::ImportConfirm;
use mmcl_ui_kit::focus::nav_scope;
use mmcl_ui_kit::scale::u;
use mmcl_ui_kit::{ThemeExt as _, hsla};

use super::widgets::{Pill, Tone, heading, hint, mono, muted, subtitle};

/// The window size (px at 100 %).
pub const SIZE: (f32, f32) = (600.0, 520.0);

/// One file of the pack index (`ModrinthIndex.File`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportFile {
    /// The destination path inside the pack (`mods/x.jar`).
    pub path: String,
    /// Its size in bytes (0 when unknown).
    pub size: i64,
}

/// What the dialog shows.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ImportPreview {
    /// The pack name (blank shows `dialog.importConfirm.fallbackName`).
    pub name: String,
    /// The pack version.
    pub version: Option<String>,
    /// `dependencies.minecraft`.
    pub minecraft: Option<String>,
    /// The loader dependencies, `(id, version)`: `forge`, `neoforge`, `fabric-loader`,
    /// `quilt-loader`.
    pub loaders: Vec<(String, String)>,
    /// The index files; `None` when the caller has not read the index yet.
    pub files: Option<Vec<ImportFile>>,
}

impl ImportPreview {
    /// From the Browse screen's hook input (name and version only).
    pub fn from_hook(c: &ImportConfirm) -> Self {
        ImportPreview {
            name: c.name.clone(),
            version: c.version.clone(),
            ..Default::default()
        }
    }
}

/// Where an index file goes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Category {
    Mod,
    Config,
    ResourcePack,
    ShaderPack,
    Other,
}

/// The path, lower-cased with `\` turned into `/`.
fn normalized(path: &str) -> String {
    path.to_lowercase().replace('\\', "/")
}

/// The category of an index path.
pub fn categorize(path: &str) -> Category {
    let p = normalized(path);
    if p.starts_with("mods/") {
        Category::Mod
    } else if p.starts_with("config/") {
        Category::Config
    } else if p.starts_with("resourcepacks/") {
        Category::ResourcePack
    } else if p.starts_with("shaderpacks/") {
        Category::ShaderPack
    } else {
        Category::Other
    }
}

/// The counts and total size of an index.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Counts {
    pub mods: usize,
    pub configs: usize,
    pub resource_packs: usize,
    pub shader_packs: usize,
    pub other: usize,
    pub total_bytes: i64,
}

/// Counts the files (entries with a blank path are skipped, as Java skipped null paths).
pub fn counts(files: &[ImportFile]) -> Counts {
    let mut c = Counts::default();
    for f in files.iter().filter(|f| !f.path.trim().is_empty()) {
        c.total_bytes += f.size.max(0);
        match categorize(&f.path) {
            Category::Mod => c.mods += 1,
            Category::Config => c.configs += 1,
            Category::ResourcePack => c.resource_packs += 1,
            Category::ShaderPack => c.shader_packs += 1,
            Category::Other => c.other += 1,
        }
    }
    c
}

/// The human size, or `None` for 0 and below.
pub fn format_bytes(bytes: i64) -> Option<String> {
    (bytes > 0).then(|| mmcl_util::units::bytes(bytes))
}

/// The totals line (`buildTotalsText`): mods always, then each non-zero count joined with
/// `importConfirm.totals.join`, then the size with `importConfirm.totals.withSize`.
pub fn totals_text(c: &Counts) -> String {
    let mut text = tf!("importConfirm.totals.mods", c.mods as i64);
    for (key, n) in [
        ("importConfirm.totals.configs", c.configs),
        ("importConfirm.totals.resourcePacks", c.resource_packs),
        ("importConfirm.totals.shaderPacks", c.shader_packs),
        ("importConfirm.totals.otherFiles", c.other),
    ] {
        if n > 0 {
            text = tf!("importConfirm.totals.join", text, tf!(key, n as i64));
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

/// The text after the last `/` (after `\` -> `/`).
pub fn basename(path: &str) -> String {
    let p = path.replace('\\', "/");
    match p.rfind('/') {
        Some(i) => p[i + 1..].to_string(),
        None => p,
    }
}

/// One row per `mods/` entry: `[size]  name` (size omitted when unknown).
pub fn mod_rows(files: &[ImportFile]) -> Vec<String> {
    files
        .iter()
        .filter(|f| !f.path.trim().is_empty() && categorize(&f.path) == Category::Mod)
        .map(|f| match format_bytes(f.size) {
            Some(size) => format!("[{size}]  {}", basename(&f.path)),
            None => basename(&f.path),
        })
        .collect()
}

/// The display name of a loader dependency id.
fn loader_label(id: &str, version: &str) -> String {
    match id {
        "forge" => tf!("importConfirm.meta.forge", version),
        "neoforge" => tf!("importConfirm.meta.loader", "NeoForge", version),
        "fabric-loader" => tf!("importConfirm.meta.loader", "Fabric", version),
        "quilt-loader" => tf!("importConfirm.meta.loader", "Quilt", version),
        other => tf!("importConfirm.meta.loader", other, version),
    }
}

/// The subtitle: version, Minecraft and loaders (each when present) joined by ` · `.
pub fn subtitle_text(p: &ImportPreview) -> String {
    let mut parts = Vec::new();
    if let Some(v) = p.version.as_deref().filter(|v| !v.trim().is_empty()) {
        parts.push(tf!("importConfirm.meta.version", v));
    }
    if let Some(mc) = p.minecraft.as_deref().filter(|v| !v.trim().is_empty()) {
        parts.push(tf!("importConfirm.meta.minecraft", mc));
    }
    for (id, v) in &p.loaders {
        if !v.trim().is_empty() {
            parts.push(loader_label(id, v));
        }
    }
    parts.join(" \u{00B7} ")
}

/// The title: the name, or the fallback when blank.
pub fn title_text(p: &ImportPreview) -> String {
    if p.name.trim().is_empty() {
        t!("dialog.importConfirm.fallbackName").into_owned()
    } else {
        p.name.clone()
    }
}

type Reply = Rc<RefCell<Option<oneshot::Sender<bool>>>>;

fn reply(slot: &Reply, value: bool) {
    if let Some(tx) = slot.borrow_mut().take() {
        let _ = tx.send(value);
    }
}

/// The dialog's root view.
pub struct ImportConfirmView {
    preview: ImportPreview,
    rows: Vec<String>,
    totals: Option<String>,
    slot: Reply,
}

impl ImportConfirmView {
    fn new(preview: ImportPreview, slot: Reply) -> Self {
        let (rows, totals) = match &preview.files {
            Some(files) => (mod_rows(files), Some(totals_text(&counts(files)))),
            None => (Vec::new(), None),
        };
        ImportConfirmView {
            preview,
            rows,
            totals,
            slot,
        }
    }

    fn answer(&self, value: bool, window: &mut Window) {
        reply(&self.slot, value);
        window.remove_window();
    }
}

impl Render for ImportConfirmView {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let sub = subtitle_text(&self.preview);
        let cancel = cx.entity().downgrade();
        let import = cx.entity().downgrade();
        let list: Vec<gpui::AnyElement> = match (&self.preview.files, self.rows.is_empty()) {
            (None, _) => {
                vec![muted(t!("importConfirm.unavailable").into_owned(), cx).into_any_element()]
            }
            (Some(_), true) => {
                vec![hint(t!("importConfirm.empty").into_owned(), cx).into_any_element()]
            }
            (Some(_), false) => self
                .rows
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
                    .child(heading(title_text(&self.preview), cx))
                    .when(!sub.is_empty(), |el| el.child(subtitle(sub, cx)))
                    .when_some(self.totals.clone(), |el, totals| {
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
                                    let _ = cancel.update(cx, |this, _| this.answer(false, window));
                                }),
                            )
                            .child(
                                Pill::new(
                                    "import-ok",
                                    t!("common.button.import").into_owned(),
                                    Tone::Primary,
                                )
                                .on_click(move |window, cx| {
                                    let _ = import.update(cx, |this, _| this.answer(true, window));
                                }),
                            ),
                    ),
            )
    }
}

/// Shows the confirmation over `owner` (the main window when `None`); resolves `true` only on
/// Import. Without a window (it could not open) it resolves `false`.
pub fn confirm_import(
    preview: ImportPreview,
    owner: Option<AnyWindowHandle>,
    cx: &mut App,
) -> Task<bool> {
    let (tx, rx) = oneshot::channel::<bool>();
    let slot: Reply = Rc::new(RefCell::new(Some(tx)));
    let close_slot = slot.clone();
    let opened = super::open_secondary_window(
        super::WindowSpec {
            title: t!("dialog.importConfirm.title").into_owned(),
            size: SIZE,
            min_size: None,
            resizable: false,
        },
        owner,
        cx,
        move |_, cx| cx.new(|_| ImportConfirmView::new(preview, slot)).into(),
    );
    match opened {
        Some(handle) => {
            let _ = handle.update(cx, |_, window, cx| {
                window.on_window_should_close(cx, move |_, _| {
                    reply(&close_slot, false);
                    true
                });
            });
        }
        None => reply(&close_slot, false),
    }
    cx.spawn(async move |_| rx.await.unwrap_or(false))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn f(path: &str, size: i64) -> ImportFile {
        ImportFile {
            path: path.into(),
            size,
        }
    }

    #[test]
    fn paths_are_categorized_case_and_slash_insensitively() {
        assert_eq!(categorize("mods/a.jar"), Category::Mod);
        assert_eq!(categorize("MODS\\b.jar"), Category::Mod);
        assert_eq!(categorize("config/x.toml"), Category::Config);
        assert_eq!(categorize("resourcepacks/r.zip"), Category::ResourcePack);
        assert_eq!(categorize("shaderpacks/s.zip"), Category::ShaderPack);
        assert_eq!(categorize("options.txt"), Category::Other);
        assert_eq!(categorize("modsextra/a.jar"), Category::Other);
    }

    #[test]
    fn counting_skips_blank_paths_and_sums_sizes() {
        let files = [
            f("mods/a.jar", 100),
            f("mods/b.jar", 0),
            f("config/c.cfg", 5),
            f("", 999),
            f("x.txt", -3),
        ];
        let c = counts(&files);
        assert_eq!(c.mods, 2);
        assert_eq!(c.configs, 1);
        assert_eq!(c.other, 1);
        assert_eq!(c.total_bytes, 105);
    }

    #[test]
    fn totals_list_only_non_zero_counts() {
        let only_mods = Counts {
            mods: 1,
            ..Default::default()
        };
        assert_eq!(totals_text(&only_mods), "1 mod");
        let none = Counts::default();
        assert_eq!(totals_text(&none), "0 mods");
        let mixed = Counts {
            mods: 3,
            configs: 1,
            shader_packs: 2,
            ..Default::default()
        };
        assert_eq!(totals_text(&mixed), "3 mods, 1 config, 2 shader packs");
        let sized = Counts {
            mods: 2,
            total_bytes: 2048,
            ..Default::default()
        };
        let text = totals_text(&sized);
        assert!(text.starts_with("2 mods  \u{00B7}  "), "{text}");
        assert!(text.ends_with(" total"), "{text}");
    }

    #[test]
    fn mod_rows_show_size_and_basename() {
        let rows = mod_rows(&[
            f("mods\\sub\\a.jar", 0),
            f("config/x", 10),
            f("mods/b.jar", 1536),
        ]);
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0], "a.jar");
        assert!(rows[1].starts_with('[') && rows[1].ends_with("]  b.jar"));
    }

    #[test]
    fn basenames() {
        assert_eq!(basename("a/b/c.jar"), "c.jar");
        assert_eq!(basename("a\\b.jar"), "b.jar");
        assert_eq!(basename("plain"), "plain");
        assert_eq!(basename("dir/"), "");
    }

    #[test]
    fn subtitle_joins_present_parts() {
        let mut p = ImportPreview {
            name: "".into(),
            version: Some("1.2".into()),
            minecraft: Some("1.20.1".into()),
            loaders: vec![("forge".into(), "47.2.0".into())],
            files: None,
        };
        assert_eq!(
            subtitle_text(&p),
            "Version 1.2 \u{00B7} Minecraft 1.20.1 \u{00B7} Forge 47.2.0"
        );
        p.version = Some(" ".into());
        p.loaders = vec![("fabric-loader".into(), "0.15".into())];
        assert_eq!(subtitle_text(&p), "Minecraft 1.20.1 \u{00B7} Fabric 0.15");
        assert_eq!(title_text(&p), t!("dialog.importConfirm.fallbackName"));
        p.name = "Pack".into();
        assert_eq!(title_text(&p), "Pack");
    }

    #[test]
    fn hook_input_has_no_file_index() {
        let p = ImportPreview::from_hook(&ImportConfirm {
            name: "X".into(),
            version: Some("2".into()),
        });
        assert_eq!(p.name, "X");
        assert!(p.files.is_none());
    }
}

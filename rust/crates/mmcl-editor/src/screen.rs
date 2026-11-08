// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The modpack editor screen.
//!
//! Layout (from `modpackEditorGUI.fxml`): navbar (logo, title, offline chip, help), announcement
//! banner, toolbar (New, Open File, Open URL | Validate, Diff), tabs (Metadata + the five file
//! lists), bottom bar (status, Save, Return). Every network, disk and hashing step runs on the
//! background executor; the view only applies results.

use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;

use gpui::{
    App, AppContext as _, Bounds, ClickEvent, Context, CursorStyle, DragMoveEvent, EmptyView,
    Entity, FocusHandle, Focusable, Image, InteractiveElement as _, IntoElement, KeyBinding,
    MouseButton, MouseDownEvent, ObjectFit, ParentElement as _, PathPromptOptions, Pixels, Render,
    ScrollStrategy, SharedString, StatefulInteractiveElement as _, Styled as _, StyledImage as _,
    Subscription, Task, UniformListScrollHandle, WeakEntity, Window, actions, div, img,
    prelude::FluentBuilder as _, px, uniform_list,
};
use gpui_component::WindowExt as _;
use gpui_component::button::{Button, ButtonVariants as _};
use gpui_component::checkbox::Checkbox;
use gpui_component::input::{Input, InputEvent, InputState, Textarea, TextareaState};
use gpui_component::tab::{Tab, TabBar};
use gpui_component::{Disableable as _, Sizable as _};
use mmcl_engine::PackSummary;
use mmcl_i18n::{t, tf};
use mmcl_manifest::document::FileList;
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::dialogs::{self, Answer, ButtonRole, DialogButton, DialogLevel, DialogSpec};
use mmcl_ui_kit::scale::u;
use mmcl_ui_kit::{
    AnnouncementBanner, CloseDecision, HelpTopic, Icon, IconButton, Navigator, OfflineChip, Screen,
    ScreenId, ScreenRequest, Switch, ThemeExt as _, TooltipExt as _, TypeStyleExt as _, hsla,
};

use crate::EditorServices;
use crate::form::Loader;
use crate::hashing::{FileHashes, short};
use crate::loaders::{self, VersionChoice};
use crate::model::{self, EditorModel};
use crate::modrinth::{self, AddOutcome, AddTotals};
use crate::row::RowId;
use crate::search_dialog::{Filters, ModrinthSearch};
use crate::table::{Column, ColumnLayout, FileTable, SortDir};

actions!(
    mmcl_editor,
    [
        /// Save (Ctrl/Cmd+S).
        SaveDocument,
        /// New document (Ctrl/Cmd+N).
        NewDocument,
        /// Select every shown row of the file table (Ctrl/Cmd+A).
        SelectAllRows,
        /// Focus row up (Up).
        SelectPrevRow,
        /// Focus row down (Down).
        SelectNextRow,
        /// Extend the selection up (Shift+Up).
        ExtendPrevRow,
        /// Extend the selection down (Shift+Down).
        ExtendNextRow,
        /// A page of rows up (Page Up).
        SelectPageUp,
        /// A page of rows down (Page Down).
        SelectPageDown,
        /// The first row (Home).
        SelectFirstRow,
        /// The last row (End).
        SelectLastRow,
        /// Extend the selection to the first row (Shift+Home).
        ExtendFirstRow,
        /// Extend the selection to the last row (Shift+End).
        ExtendLastRow,
        /// Edit the focus row's first text cell (F2 or Enter).
        EditFocusedCell,
        /// Commit the open cell and edit the next one (Tab).
        NextCell,
        /// Commit the open cell and edit the previous one (Shift+Tab).
        PrevCell,
    ]
);

/// The editor's key context.
pub const KEY_CONTEXT: &str = "ModpackEditor";
/// The file table's key context (row navigation, select-all, start editing). Text inputs inside
/// it keep their own Ctrl/Cmd+A and arrow keys: their deeper context wins.
pub const TABLE_KEY_CONTEXT: &str = "ModpackEditorTable";
/// The open cell editor's key context (Tab / Shift+Tab move to the next / previous cell).
pub const CELL_KEY_CONTEXT: &str = "ModpackEditorCell";

/// Rows Page Up / Page Down move.
const PAGE_ROWS: isize = 10;

/// Binds Ctrl/Cmd+S and Ctrl/Cmd+N inside the editor (Alt/Shift variants do not match), and the
/// file table's keys: Ctrl/Cmd+A, Up/Down (Shift extends), Page Up/Down, Home/End (Shift
/// extends), F2 or Enter to edit, Tab / Shift+Tab between cells.
pub fn bind_keys(cx: &mut App) {
    // KeyBinding::new does not resolve the "secondary" modifier in gpui-pre 0.3.11 (it panics
    // on parse), so name the platform's modifier directly.
    #[cfg(target_os = "macos")]
    const SAVE: &str = "cmd-s";
    #[cfg(target_os = "macos")]
    const NEW: &str = "cmd-n";
    #[cfg(target_os = "macos")]
    const SELECT_ALL: &str = "cmd-a";
    #[cfg(not(target_os = "macos"))]
    const SAVE: &str = "ctrl-s";
    #[cfg(not(target_os = "macos"))]
    const NEW: &str = "ctrl-n";
    #[cfg(not(target_os = "macos"))]
    const SELECT_ALL: &str = "ctrl-a";
    let table = Some(TABLE_KEY_CONTEXT);
    let cell = Some(CELL_KEY_CONTEXT);
    cx.bind_keys([
        KeyBinding::new(SAVE, SaveDocument, Some(KEY_CONTEXT)),
        KeyBinding::new(NEW, NewDocument, Some(KEY_CONTEXT)),
        KeyBinding::new(SELECT_ALL, SelectAllRows, table),
        KeyBinding::new("up", SelectPrevRow, table),
        KeyBinding::new("down", SelectNextRow, table),
        KeyBinding::new("shift-up", ExtendPrevRow, table),
        KeyBinding::new("shift-down", ExtendNextRow, table),
        KeyBinding::new("pageup", SelectPageUp, table),
        KeyBinding::new("pagedown", SelectPageDown, table),
        KeyBinding::new("home", SelectFirstRow, table),
        KeyBinding::new("end", SelectLastRow, table),
        KeyBinding::new("shift-home", ExtendFirstRow, table),
        KeyBinding::new("shift-end", ExtendLastRow, table),
        KeyBinding::new("f2", EditFocusedCell, table),
        KeyBinding::new("enter", EditFocusedCell, table),
        KeyBinding::new("tab", NextCell, cell),
        KeyBinding::new("shift-tab", PrevCell, cell),
    ]);
}

/// The drag payload of a header's resize edge.
#[derive(Debug, Clone, Copy)]
struct ColumnResize;

/// The drag payload of a header being moved onto another.
#[derive(Debug, Clone)]
struct ColumnMove {
    list: FileList,
    col: Column,
    label: SharedString,
}

impl Render for ColumnMove {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        div()
            .px(u(8.0))
            .py(u(4.0))
            .rounded(u(mmcl_theme::shape::SMALL))
            .bg(hsla(t.md_surface_container_high))
            .text_color(hsla(t.md_on_surface))
            .text_size(px(12.0))
            .font_weight(gpui::FontWeight::SEMIBOLD)
            .child(self.label.clone())
    }
}

/// Where a column resize started: the column, the pointer x and the column's width then (layout
/// px).
#[derive(Debug, Clone, Copy)]
struct ResizeStart {
    list: FileList,
    col: Column,
    x: Pixels,
    width: f32,
}

/// Tab labels, Metadata first, then the lists in `FileList::ALL` order.
const TAB_KEYS: [&str; 6] = [
    "editor.tab.metadata",
    "editor.tab.mods",
    "editor.tab.configs",
    "editor.tab.resources",
    "editor.tab.shaders",
    "editor.tab.initialFiles",
];

/// Row height of the file tables (px).
const ROW_HEIGHT: f32 = 32.0;

struct Inputs {
    name: Entity<InputState>,
    version: Entity<InputState>,
    url: Entity<InputState>,
    ram: Entity<InputState>,
    loader_url: Entity<InputState>,
    loader_hash: Entity<InputState>,
    mc: Entity<InputState>,
    logo_sha1: Entity<InputState>,
    bg_sha1: Entity<InputState>,
    logo_urls: Entity<TextareaState>,
    bg_urls: Entity<TextareaState>,
    scan: Entity<TextareaState>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct CellEdit {
    list: FileList,
    row: RowId,
    col: Column,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Preview {
    Logo,
    Background,
}

/// The modpack editor view.
pub struct ModpackEditor {
    services: EditorServices,
    model: EditorModel,
    initial: Option<PackSummary>,
    status: SharedString,
    tab: usize,
    inputs: Inputs,
    filters: Vec<Entity<InputState>>,
    cell_input: Entity<InputState>,
    editing: Option<CellEdit>,
    scrolls: Vec<UniformListScrollHandle>,
    /// Per list: the user's column order and widths.
    layouts: Vec<ColumnLayout>,
    /// The shown table's header cells as last laid out (window px), for resize starts.
    header_bounds: Rc<RefCell<Vec<Bounds<Pixels>>>>,
    resize: Option<ResizeStart>,
    logo_preview: Option<Arc<Image>>,
    bg_preview: Option<Arc<Image>>,
    preview_generation: [u64; 2],
    announcement: SharedString,
    focus: FocusHandle,
    /// The file table's focus (its key context handles row keys while it has focus).
    table_focus: FocusHandle,
    /// The user already agreed to discard (Return), so the shell's own leave check passes.
    leave_confirmed: bool,
    set_up: bool,
    disposed: bool,
    _subs: Vec<Subscription>,
}

fn list_index(list: FileList) -> usize {
    FileList::ALL.iter().position(|l| *l == list).unwrap_or(0)
}

fn tab_name(list: FileList) -> String {
    t!(TAB_KEYS[list_index(list) + 1]).into_owned()
}

fn input(window: &mut Window, cx: &mut App, placeholder: &str) -> Entity<InputState> {
    let p = t!(placeholder).into_owned();
    cx.new(|cx| InputState::new(window, cx).placeholder(p))
}

fn textarea(window: &mut Window, cx: &mut App, placeholder: &str) -> Entity<TextareaState> {
    let p = t!(placeholder).into_owned();
    cx.new(|cx| TextareaState::new(window, cx).placeholder(p))
}

impl ModpackEditor {
    /// A new editor; `initial` is loaded once the screen is set up.
    pub fn new(
        services: EditorServices,
        initial: Option<PackSummary>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let inputs = Inputs {
            name: input(window, cx, "editor.field.packName"),
            version: input(window, cx, "editor.field.packVersion"),
            url: input(window, cx, "editor.field.packUrl"),
            ram: input(window, cx, "editor.field.minRam"),
            loader_url: input(window, cx, "editor.field.modloaderUrl"),
            loader_hash: input(window, cx, "editor.field.modloaderSha1"),
            mc: input(window, cx, "editor.field.minecraftVersion"),
            logo_sha1: input(window, cx, "editor.field.logoSha1"),
            bg_sha1: input(window, cx, "editor.field.backgroundSha1"),
            logo_urls: textarea(window, cx, "editor.logoUrls.prompt"),
            bg_urls: textarea(window, cx, "editor.backgroundUrls.prompt"),
            scan: textarea(window, cx, "editor.filePath.prompt"),
        };
        let filters: Vec<_> = FileList::ALL
            .iter()
            .map(|_| input(window, cx, "editor.filter.prompt"))
            .collect();
        let cell_input = cx.new(|cx| InputState::new(window, cx));

        let mut subs = Vec::new();
        for (i, f) in filters.iter().enumerate() {
            subs.push(
                cx.subscribe_in(f, window, move |this, state, ev: &InputEvent, _w, cx| {
                    if let InputEvent::Change = ev {
                        let text = state.read(cx).value().to_string();
                        this.model.table_mut(FileList::ALL[i]).set_filter(&text);
                        cx.notify();
                    }
                }),
            );
        }
        subs.push(cx.subscribe_in(
            &cell_input,
            window,
            |this, _, ev: &InputEvent, w, cx| match ev {
                InputEvent::PressEnter { .. } => {
                    this.commit_cell(cx);
                    // Back to the table, so the arrow keys move rows again.
                    this.table_focus.focus(w, cx);
                }
                InputEvent::Blur => this.commit_cell(cx),
                _ => {}
            },
        ));
        subs.push(cx.subscribe_in(
            &inputs.logo_urls,
            window,
            |this, _, ev: &InputEvent, _w, cx| {
                if let InputEvent::Blur = ev {
                    this.refresh_preview(Preview::Logo, cx);
                }
            },
        ));
        subs.push(cx.subscribe_in(
            &inputs.bg_urls,
            window,
            |this, _, ev: &InputEvent, _w, cx| {
                if let InputEvent::Blur = ev {
                    this.refresh_preview(Preview::Background, cx);
                }
            },
        ));

        let announcement = services.backend.announcement();
        ModpackEditor {
            services,
            model: EditorModel::new(),
            initial,
            status: t!("editor.status.noFile").into_owned().into(),
            tab: 0,
            inputs,
            filters,
            cell_input,
            editing: None,
            scrolls: FileList::ALL
                .iter()
                .map(|_| UniformListScrollHandle::new())
                .collect(),
            layouts: FileList::ALL
                .iter()
                .map(|l| ColumnLayout::new(*l))
                .collect(),
            header_bounds: Rc::default(),
            resize: None,
            logo_preview: None,
            bg_preview: None,
            preview_generation: [0; 2],
            announcement: announcement.into(),
            focus: cx.focus_handle(),
            table_focus: cx.focus_handle(),
            leave_confirmed: false,
            set_up: false,
            disposed: false,
            _subs: subs,
        }
    }

    /// The model (tests, MCP-style inspection).
    pub fn model(&self) -> &EditorModel {
        &self.model
    }

    /// The status line.
    pub fn status(&self) -> &SharedString {
        &self.status
    }

    // -----------------------------------------------------------------------------------------
    // Form <-> inputs

    fn sync_form(&mut self, cx: &App) {
        let i = &self.inputs;
        let v = |s: &Entity<InputState>| s.read(cx).value().to_string();
        let a = |s: &Entity<TextareaState>| s.read(cx).value().to_string();
        let f = &mut self.model.form;
        f.name = v(&i.name);
        f.version = v(&i.version);
        f.url = v(&i.url);
        f.min_ram = v(&i.ram);
        f.loader_url = v(&i.loader_url);
        f.loader_hash = v(&i.loader_hash);
        f.mc_version = v(&i.mc);
        f.logo_sha1 = v(&i.logo_sha1);
        f.bg_sha1 = v(&i.bg_sha1);
        f.logo_urls = a(&i.logo_urls);
        f.bg_urls = a(&i.bg_urls);
        f.scan_exclusions = a(&i.scan);
    }

    fn push_form(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let f = self.model.form.clone();
        let set = |s: &Entity<InputState>, v: &str, window: &mut Window, cx: &mut Context<Self>| {
            let v = v.to_string();
            s.update(cx, |s, cx| s.set_value(v, window, cx));
        };
        let seta =
            |s: &Entity<TextareaState>, v: &str, window: &mut Window, cx: &mut Context<Self>| {
                let v = v.to_string();
                s.update(cx, |s, cx| s.set_value(v, window, cx));
            };
        let i = &self.inputs;
        let (name, version, url, ram) = (
            i.name.clone(),
            i.version.clone(),
            i.url.clone(),
            i.ram.clone(),
        );
        let (lu, lh, mc) = (i.loader_url.clone(), i.loader_hash.clone(), i.mc.clone());
        let (ls, bs) = (i.logo_sha1.clone(), i.bg_sha1.clone());
        let (la, ba, sa) = (i.logo_urls.clone(), i.bg_urls.clone(), i.scan.clone());
        set(&name, &f.name, window, cx);
        set(&version, &f.version, window, cx);
        set(&url, &f.url, window, cx);
        set(&ram, &f.min_ram, window, cx);
        set(&lu, &f.loader_url, window, cx);
        set(&lh, &f.loader_hash, window, cx);
        set(&mc, &f.mc_version, window, cx);
        set(&ls, &f.logo_sha1, window, cx);
        set(&bs, &f.bg_sha1, window, cx);
        seta(&la, &f.logo_urls, window, cx);
        seta(&ba, &f.bg_urls, window, cx);
        seta(&sa, &f.scan_exclusions, window, cx);
    }

    /// After the model was populated: inputs, previews, no cell editing.
    fn show_model(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.editing = None;
        self.push_form(window, cx);
        // Filters keep their text; re-apply it to the fresh tables.
        for (i, f) in self.filters.iter().enumerate() {
            let text = f.read(cx).value().to_string();
            self.model.table_mut(FileList::ALL[i]).set_filter(&text);
        }
        self.refresh_preview(Preview::Logo, cx);
        self.refresh_preview(Preview::Background, cx);
        cx.notify();
    }

    fn set_status(&mut self, msg: impl Into<SharedString>, cx: &mut Context<Self>) {
        self.status = msg.into();
        cx.notify();
    }

    /// Whether there are unsaved edits.
    pub fn is_dirty(&mut self, cx: &App) -> bool {
        self.commit_cell_value(cx);
        self.sync_form(cx);
        self.model.is_dirty()
    }

    fn collect(&mut self, cx: &App) {
        self.commit_cell_value(cx);
        self.sync_form(cx);
        self.model.collect();
    }

    /// Asks the discard question; `true` only for "Discard".
    async fn ask_discard(cx: &gpui::AsyncApp, owner: Option<gpui::AnyWindowHandle>) -> bool {
        dialogs::ask(
            cx,
            &t!("dialog.editor.discardChanges.title"),
            &t!("dialog.editor.discardChanges.header"),
            &t!("dialog.editor.discardChanges.body"),
            &t!("dialog.editor.discardChanges.button.discard"),
            &t!("dialog.button.cancel"),
            owner,
        )
        .await
            == Answer::Primary
    }

    /// Runs `then` now when clean, or after the user agreed to discard.
    fn when_discard_ok(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
        then: impl FnOnce(&mut Self, &mut Window, &mut Context<Self>) + 'static,
    ) {
        if !self.is_dirty(cx) {
            then(self, window, cx);
            return;
        }
        let owner = Some(window.window_handle());
        cx.spawn_in(window, async move |this, cx| {
            if Self::ask_discard(cx, owner).await {
                let _ = this.update_in(cx, |this, window, cx| then(this, window, cx));
            }
        })
        .detach();
    }

    // -----------------------------------------------------------------------------------------
    // Document operations

    /// New (asks first when there are unsaved edits; Java discarded silently).
    pub fn new_document(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.when_discard_ok(window, cx, |this, window, cx| {
            this.model.new_document();
            this.show_model(window, cx);
            this.set_status(t!("editor.status.newCreated").into_owned(), cx);
        });
    }

    /// Open File (asks first when there are unsaved edits).
    pub fn open_from_file(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.when_discard_ok(window, cx, |_this, window, cx| {
            let rx = cx.prompt_for_paths(PathPromptOptions {
                files: true,
                directories: false,
                multiple: false,
                prompt: Some(
                    t!("dialog.fileChooser.openModpack.title")
                        .into_owned()
                        .into(),
                ),
            });
            cx.spawn_in(window, async move |this, cx| {
                let Ok(Ok(Some(paths))) = rx.await else {
                    return;
                };
                let Some(path) = paths.into_iter().next() else {
                    return;
                };
                let p = path.clone();
                let parsed = cx
                    .background_spawn(async move {
                        let text = std::fs::read_to_string(&p).map_err(|e| e.to_string())?;
                        model::parse_document(&text)
                    })
                    .await;
                let _ = this.update_in(cx, |this, window, cx| match parsed {
                    Ok(doc) => {
                        this.model.adopt(doc, Some(path.clone()));
                        this.show_model(window, cx);
                        this.set_status(
                            tf!("editor.status.loaded", model::display_name(&path)),
                            cx,
                        );
                    }
                    Err(e) => {
                        tracing::error!("{} {e}", t!("log.editor.openFileFailed"));
                        this.set_status(tf!("editor.status.errorLoadingFile", e), cx);
                    }
                });
            })
            .detach();
        });
    }

    /// Open URL: one themed prompt for the URL, then download and parse.
    pub fn open_from_url(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.when_discard_ok(window, cx, |this, window, cx| {
            let owner = Some(window.window_handle());
            let backend = this.services_backend();
            cx.spawn_in(window, async move |this, cx| {
                let Some(url) = dialogs::input(
                    cx,
                    &t!("dialog.editor.urlInput.title"),
                    &t!("dialog.editor.urlInput.header"),
                    &t!("dialog.editor.urlInput.contentLabel"),
                    "",
                    owner,
                )
                .await
                else {
                    return;
                };
                let url = url.trim().to_string();
                if url.is_empty() {
                    return;
                }
                let _ = this.update(cx, |this, cx| {
                    this.set_status(t!("editor.status.downloading").into_owned(), cx)
                });
                let parsed = cx
                    .background_spawn(async move {
                        let text = backend.get_text(&url).await.map_err(|e| e.to_string())?;
                        model::parse_document(&text)
                    })
                    .await;
                let _ = this.update_in(cx, |this, window, cx| match parsed {
                    Ok(doc) => {
                        this.model.adopt(doc, None);
                        this.show_model(window, cx);
                        this.set_status(t!("editor.status.loadedFromUrl").into_owned(), cx);
                    }
                    Err(e) => {
                        tracing::error!("{} {e}", t!("log.editor.downloadUrlFailed"));
                        this.set_status(tf!("editor.status.errorLoadingUrl", e), cx);
                    }
                });
            })
            .detach();
        });
    }

    fn services_backend(&self) -> Arc<dyn crate::EditorBackend> {
        self.services.backend.clone()
    }

    /// Pre-loads a library pack. A `file:` manifest becomes the backing file, so Save writes
    /// back to the file the launcher reads at start-up.
    pub fn load_from_pack(
        &mut self,
        pack: PackSummary,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let name = if pack.friendly_name.is_empty() {
            pack.name.clone()
        } else {
            pack.friendly_name.clone()
        };
        let Some(url) = pack.manifest_url.clone().filter(|u| !u.trim().is_empty()) else {
            self.set_status(tf!("editor.status.noManifestSource", name.as_str()), cx);
            return;
        };
        let backend = self.services_backend();
        cx.spawn_in(window, async move |this, cx| {
            let text = cx
                .background_spawn(async move { backend.pack_manifest_text(&pack).await })
                .await;
            let _ = this.update_in(cx, |this, window, cx| {
                let Some(text) = text.filter(|t| !t.trim().is_empty()) else {
                    tracing::error!("{}", tf!("log.editor.manifestLoadFailed", name.as_str()));
                    this.set_status(tf!("editor.status.manifestLoadFailed", name.as_str()), cx);
                    return;
                };
                match model::parse_document(&text) {
                    Ok(doc) => {
                        this.model.adopt(doc, model::file_url_path(&url));
                        this.show_model(window, cx);
                        this.set_status(tf!("editor.status.loaded", name.as_str()), cx);
                    }
                    Err(_) => {
                        this.set_status(tf!("editor.status.manifestUnreadable", name.as_str()), cx);
                    }
                }
            });
        })
        .detach();
    }

    /// Save: collect, ask where (pre-filled), write pretty JSON.
    pub fn save_to_file(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.collect(cx);
        let name = self.model.suggested_file_name();
        let dir = self
            .model
            .suggested_dir()
            .or_else(dirs_fallback)
            .unwrap_or_else(|| PathBuf::from("."));
        let rx = cx.prompt_for_new_path(&dir, name.as_deref());
        let text = self.model.document.to_pretty_json();
        cx.spawn_in(window, async move |this, cx| {
            let Ok(Ok(Some(path))) = rx.await else { return };
            let path = model::with_default_extension(path);
            let p = path.clone();
            let t2 = text.clone();
            let written = cx
                .background_spawn(async move { std::fs::write(&p, t2.as_bytes()) })
                .await;
            let _ = this.update(cx, |this, cx| match written {
                Ok(()) => {
                    let shown = model::display_name(&path);
                    this.model.mark_saved(path, text);
                    this.set_status(tf!("editor.status.saved", shown), cx);
                }
                Err(e) => {
                    tracing::error!("{} {e}", t!("log.editor.saveFailed"));
                    this.set_status(tf!("editor.status.errorSaving", e.to_string()), cx);
                }
            });
        })
        .detach();
    }

    /// Validate: issues in a dialog.
    pub fn validate(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.commit_cell_value(cx);
        self.sync_form(cx);
        let issues = self.model.validate();
        let spec = if issues.is_empty() {
            DialogSpec {
                level: DialogLevel::Info,
                title: t!("dialog.editor.validate.title").into_owned().into(),
                header: Some(t!("dialog.editor.validate.headerValid").into_owned().into()),
                body: t!("dialog.editor.validate.bodyValid").into_owned().into(),
                multiline: false,
                buttons: vec![ok_button()],
            }
        } else {
            DialogSpec {
                level: DialogLevel::Warning,
                title: t!("dialog.editor.validate.title").into_owned().into(),
                header: Some(
                    t!("dialog.editor.validate.headerIssues")
                        .into_owned()
                        .into(),
                ),
                body: dialogs::sanitize_multiline(&model::validation_body(&issues)).into(),
                multiline: true,
                buttons: vec![ok_button()],
            }
        };
        let owner = Some(window.window_handle());
        cx.spawn(async move |_, cx| {
            dialogs::show(cx, spec, owner).await;
        })
        .detach();
    }

    /// Diff: changes since the last load/save, in a read-only monospace view.
    pub fn show_diff(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.commit_cell_value(cx);
        self.sync_form(cx);
        let Some(diff) = self.model.diff() else {
            let spec = DialogSpec {
                level: DialogLevel::Info,
                title: t!("dialog.editor.diff.title").into_owned().into(),
                header: Some(t!("dialog.editor.diff.noChangesHeader").into_owned().into()),
                body: t!("dialog.editor.diff.noChangesBody").into_owned().into(),
                multiline: false,
                buttons: vec![ok_button()],
            };
            let owner = Some(window.window_handle());
            cx.spawn(async move |_, cx| {
                dialogs::show(cx, spec, owner).await;
            })
            .detach();
            return;
        };
        let state = cx.new(|cx| TextareaState::new(window, cx).default_value(diff));
        window.open_dialog(cx, move |dialog, _w, cx| {
            let t = cx.tokens();
            dialog
                .title(t!("dialog.editor.diff.title").into_owned())
                .bg(cx.launcher_theme().solid_bg(true))
                .w(px(640.0))
                .keyboard(true)
                .child(
                    div()
                        .flex()
                        .flex_col()
                        .gap(u(8.0))
                        .child(
                            div()
                                .type_role(TypeRole::TitleMedium)
                                .text_color(hsla(t.md_on_surface))
                                .child(t!("dialog.editor.diff.changesHeader").into_owned()),
                        )
                        .child(
                            div()
                                .h(px(400.0))
                                .font_family(mmcl_ui_kit::theme::font_family(
                                    mmcl_theme::typography::Family::Mono,
                                ))
                                .text_size(px(12.0))
                                .child(Textarea::new(&state).readonly(true).h(px(400.0))),
                        ),
                )
                .footer(
                    div().flex().justify_end().child(
                        Button::new("editor-diff-ok")
                            .primary()
                            .label(t!("dialog.button.ok").into_owned())
                            .on_click(|_, window, cx| window.close_dialog(cx)),
                    ),
                )
        });
    }

    /// Major / Minor / Patch.
    pub fn bump_version(&mut self, position: usize, window: &mut Window, cx: &mut Context<Self>) {
        self.sync_form(cx);
        let v = self.model.bump(position);
        let state = self.inputs.version.clone();
        let v2 = v.clone();
        state.update(cx, |s, cx| s.set_value(v2, window, cx));
        self.set_status(tf!("editor.status.versionBumped", v), cx);
    }

    /// Return to the Game Library (asks first when there are unsaved edits).
    pub fn go_back(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let dirty = self.is_dirty(cx);
        let leave = |this: &mut Self, cx: &mut Context<Self>| {
            this.leave_confirmed = true;
            if !Navigator::go_to(cx, ScreenRequest::new(ScreenId::BROWSE)) {
                tracing::warn!("{}", t!("log.editor.returnLibraryFailed"));
                this.leave_confirmed = false;
            }
        };
        if !dirty {
            leave(self, cx);
            return;
        }
        let owner = Some(window.window_handle());
        cx.spawn(async move |this, cx| {
            if Self::ask_discard(cx, owner).await {
                let _ = this.update(cx, |this, cx| leave(this, cx));
            }
        })
        .detach();
    }

    // -----------------------------------------------------------------------------------------
    // Loader pickers

    fn set_loader(&mut self, loader: Loader, cx: &mut Context<Self>) {
        self.model.form.loader = loader;
        cx.notify();
    }

    /// Picks a loader version from the feed of the selected loader.
    pub fn pick_loader_version(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let loader = self.model.form.loader;
        let prefilter = self.inputs.mc.read(cx).value().to_string();
        let backend = self.services_backend();
        let owner = Some(window.window_handle());
        let (fetching, failed_status, failed_log, title, header, label) = match loader {
            Loader::Forge => (
                "editor.status.fetchingForge",
                "editor.status.fetchForgeFailed",
                "log.editor.fetchForgeFailed",
                "dialog.editor.pickForge.title",
                "dialog.editor.pickForge.header",
                "dialog.editor.pickForge.contentLabel",
            ),
            Loader::NeoForge => (
                "editor.status.fetchingNeoForge",
                "editor.status.fetchNeoForgeFailed",
                "log.editor.fetchNeoForgeFailed",
                "dialog.editor.pickNeoForge.title",
                "dialog.editor.pickNeoForge.header",
                "dialog.editor.pickNeoForge.contentLabel",
            ),
            Loader::Fabric => (
                "editor.status.fetchingFabric",
                "editor.status.fetchFabricFailed",
                "log.editor.fetchFabricFailed",
                "dialog.editor.pickFabric.title",
                "dialog.editor.pickFabric.header",
                "dialog.editor.pickFabric.contentLabel",
            ),
        };
        self.set_status(t!(fetching).into_owned(), cx);
        cx.spawn_in(window, async move |this, cx| {
            let b = backend.clone();
            let entries = cx
                .background_spawn(async move {
                    match loader {
                        Loader::Forge => loaders::fetch_forge(b.as_ref(), &prefilter).await,
                        Loader::NeoForge => loaders::fetch_neoforge(b.as_ref()).await,
                        Loader::Fabric => loaders::fetch_fabric(b.as_ref()).await,
                    }
                })
                .await;
            let entries: Vec<VersionChoice> = match entries {
                Ok(e) => e,
                Err(loaders::LoaderFeedError::NoFabricLoaders) => {
                    let _ = this.update(cx, |this, cx| {
                        this.set_status(t!("editor.status.fabricNoLoaders").into_owned(), cx)
                    });
                    return;
                }
                Err(e) => {
                    tracing::error!("{} {e}", t!(failed_log));
                    let _ = this.update(cx, |this, cx| {
                        this.set_status(tf!(failed_status, e.to_string()), cx)
                    });
                    return;
                }
            };
            let labels: Vec<SharedString> =
                entries.iter().map(|e| e.label.clone().into()).collect();
            let Some(ix) =
                dialogs::choose(cx, &t!(title), &t!(header), &t!(label), labels, owner).await
            else {
                return;
            };
            let Some(choice) = entries.get(ix).cloned() else {
                return;
            };
            let set = this.update_in(cx, |this, window, cx| {
                let url = choice.url.clone();
                this.inputs
                    .loader_url
                    .update(cx, |s, cx| s.set_value(url, window, cx));
                this.inputs
                    .loader_hash
                    .update(cx, |s, cx| s.set_value("", window, cx));
                let key = match loader {
                    Loader::Forge => "editor.status.forgeUrlSet",
                    Loader::NeoForge => "editor.status.neoForgeUrlSet",
                    Loader::Fabric => "editor.status.fabricUrlSet",
                };
                this.set_status(t!(key).into_owned(), cx);
            });
            if set.is_err() || loader == Loader::Fabric {
                // Fabric profile JSON is generated per request: no hash pinning.
                return;
            }
            let label = loader.label();
            let url = choice.url.clone();
            let b = backend.clone();
            let hash = cx
                .background_spawn(
                    async move { loaders::installer_sha1(b.as_ref(), &url, label).await },
                )
                .await;
            let _ = this.update_in(cx, |this, window, cx| match hash {
                Ok(h) => {
                    let shown = short(&h);
                    this.inputs
                        .loader_hash
                        .update(cx, |s, cx| s.set_value(h, window, cx));
                    this.set_status(tf!("editor.status.hashComputedLoader", label, shown), cx);
                }
                Err(e) => {
                    this.set_status(
                        tf!(
                            "editor.status.hashComputeLoaderFailed",
                            label,
                            e.to_string()
                        ),
                        cx,
                    );
                }
            });
        })
        .detach();
    }

    // -----------------------------------------------------------------------------------------
    // Previews

    fn refresh_preview(&mut self, which: Preview, cx: &mut Context<Self>) {
        let area = match which {
            Preview::Logo => &self.inputs.logo_urls,
            Preview::Background => &self.inputs.bg_urls,
        };
        let text = area.read(cx).value().to_string();
        let url = mmcl_manifest::document::first_line(Some(&text));
        let slot = which as usize;
        self.preview_generation[slot] += 1;
        let generation = self.preview_generation[slot];
        let set = move |this: &mut Self, img: Option<Arc<Image>>| match which {
            Preview::Logo => this.logo_preview = img,
            Preview::Background => this.bg_preview = img,
        };
        if url.is_empty() {
            set(self, None);
            cx.notify();
            return;
        }
        let backend = self.services_backend();
        cx.spawn(async move |this, cx| {
            let image = cx
                .background_spawn(
                    async move { crate::icons::load_image(backend.as_ref(), &url).await },
                )
                .await
                .ok();
            let _ = this.update(cx, |this, cx| {
                if this.preview_generation[slot] == generation {
                    set(this, image);
                    cx.notify();
                }
            });
        })
        .detach();
    }

    // -----------------------------------------------------------------------------------------
    // File tables

    fn start_edit(&mut self, edit: CellEdit, window: &mut Window, cx: &mut Context<Self>) {
        self.commit_cell(cx);
        let Some(value) = self
            .model
            .table(edit.list)
            .row(edit.row)
            .map(|r| edit.col.text_of(r).to_string())
        else {
            return;
        };
        self.editing = Some(edit);
        let input = self.cell_input.clone();
        input.update(cx, |s, cx| {
            s.set_value(value, window, cx);
            s.focus(window, cx);
        });
        cx.notify();
    }

    fn commit_cell_value(&mut self, cx: &App) {
        let Some(edit) = self.editing else { return };
        let value = self.cell_input.read(cx).value().to_string();
        self.model
            .table_mut(edit.list)
            .set_text(edit.row, edit.col, &value);
    }

    /// Commits the cell being edited (Enter or focus loss; Java lost edits on blur).
    fn commit_cell(&mut self, cx: &mut Context<Self>) {
        if self.editing.is_some() {
            self.commit_cell_value(cx);
            self.editing = None;
            cx.notify();
        }
    }

    fn cancel_cell(&mut self, cx: &mut Context<Self>) {
        if self.editing.take().is_some() {
            cx.notify();
        }
    }

    fn add_row(&mut self, list: FileList, cx: &mut Context<Self>) {
        self.commit_cell(cx);
        let t = self.model.table_mut(list);
        let id = t.add_row();
        t.click_row(id, false, false);
        if let Some(pos) = t.view_order().iter().position(|r| *r == id) {
            self.scrolls[list_index(list)].scroll_to_item(pos, ScrollStrategy::Bottom);
        }
        cx.notify();
    }

    fn remove_selected(&mut self, list: FileList, cx: &mut Context<Self>) {
        self.commit_cell(cx);
        self.model.table_mut(list).remove_selected();
        cx.notify();
    }

    /// The file list of the tab showing (`None` on Metadata).
    fn shown_list(&self) -> Option<FileList> {
        self.tab
            .checked_sub(1)
            .and_then(|i| FileList::ALL.get(i).copied())
    }

    /// A row key over the shown table: applies `f` and scrolls its result into view. While a
    /// cell is open the key belongs to the cell editor, so it propagates.
    fn table_key(
        &mut self,
        cx: &mut Context<Self>,
        f: impl FnOnce(&mut FileTable) -> Option<crate::row::RowId>,
    ) {
        let Some(list) = self.shown_list().filter(|_| self.editing.is_none()) else {
            cx.propagate();
            return;
        };
        let table = self.model.table_mut(list);
        if let Some(id) = f(table)
            && let Some(pos) = table.view_order().iter().position(|r| *r == id)
        {
            self.scrolls[list_index(list)].scroll_to_item(pos, ScrollStrategy::Nearest);
        }
        cx.notify();
    }

    /// F2 / Enter: edits the focus row's first text cell (in the shown column order).
    fn edit_focused_cell(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(list) = self.shown_list().filter(|_| self.editing.is_none()) else {
            cx.propagate();
            return;
        };
        let table = self.model.table(list);
        let Some(row) = table.lead().or_else(|| table.view_order().first().copied()) else {
            return;
        };
        let Some(col) = self.layouts[list_index(list)]
            .order()
            .iter()
            .copied()
            .find(|c| c.is_text())
        else {
            return;
        };
        if table.lead() != Some(row) {
            self.model.table_mut(list).click_row(row, false, false);
        }
        self.start_edit(CellEdit { list, row, col }, window, cx);
    }

    /// Tab / Shift+Tab in an open cell: commit it and edit the next / previous text cell (the
    /// next row's first after the last column). Past either end the edit just commits.
    fn step_cell(&mut self, forward: bool, window: &mut Window, cx: &mut Context<Self>) {
        let Some(edit) = self.editing else {
            cx.propagate();
            return;
        };
        let cols = self.layouts[list_index(edit.list)].order().to_vec();
        let next = self
            .model
            .table(edit.list)
            .neighbor_cell(&cols, edit.row, edit.col, forward);
        match next {
            Some((row, col)) => {
                if row != edit.row {
                    let table = self.model.table_mut(edit.list);
                    table.click_row(row, false, false);
                    if let Some(pos) = table.view_order().iter().position(|r| *r == row) {
                        self.scrolls[list_index(edit.list)]
                            .scroll_to_item(pos, ScrollStrategy::Nearest);
                    }
                }
                self.start_edit(
                    CellEdit {
                        list: edit.list,
                        row,
                        col,
                    },
                    window,
                    cx,
                );
            }
            None => {
                self.commit_cell(cx);
                self.table_focus.focus(window, cx);
            }
        }
    }

    /// A press on a header's resize edge: remember where the drag starts from.
    fn begin_resize(&mut self, list: FileList, col: Column, x: Pixels, cx: &App) {
        let pos = self.layouts[list_index(list)]
            .order()
            .iter()
            .position(|c| *c == col);
        let factor = (mmcl_ui_kit::scale::percent(cx) as f32 / 100.0).max(0.01);
        let measured = pos
            .and_then(|p| self.header_bounds.borrow().get(p).copied())
            .map(|b| b.size.width.as_f32() / factor);
        let width = self.layouts[list_index(list)]
            .width(col)
            .or(measured)
            .unwrap_or(col.widths().0);
        self.resize = Some(ResizeStart {
            list,
            col,
            x,
            width,
        });
    }

    /// The resize edge moved to `x`: the column follows the pointer within its bounds.
    fn resize_to(&mut self, x: Pixels, cx: &mut Context<Self>) {
        let Some(start) = self.resize else { return };
        let factor = (mmcl_ui_kit::scale::percent(cx) as f32 / 100.0).max(0.01);
        let dx = (x - start.x).as_f32() / factor;
        self.layouts[list_index(start.list)].set_width(start.col, start.width + dx);
        cx.notify();
    }

    /// A header dropped on another: the column moves there.
    fn move_column(&mut self, list: FileList, col: Column, target: Column, cx: &mut Context<Self>) {
        self.commit_cell(cx);
        if self.layouts[list_index(list)].move_column(col, target) {
            cx.notify();
        }
    }

    /// Calc: downloads the row's remote and writes SHA-256 (primary), SHA-1 and MD5.
    fn calc_row(&mut self, list: FileList, id: RowId, cx: &mut Context<Self>) {
        self.commit_cell(cx);
        let Some(row) = self.model.table(list).row(id).cloned() else {
            return;
        };
        if row.remote.trim().is_empty() {
            return;
        }
        self.set_status(
            tf!("editor.status.computingHashFor", row.local.as_str()),
            cx,
        );
        let backend = self.services_backend();
        let url = row.remote.clone();
        cx.spawn(async move |this, cx| {
            let r: Result<FileHashes, _> = cx
                .background_spawn(async move {
                    crate::hashing::remote_hashes(
                        backend.as_ref(),
                        &url,
                        "mclauncher_hash_",
                        ".tmp",
                    )
                    .await
                })
                .await;
            let _ = this.update(cx, |this, cx| match r {
                Ok(h) => {
                    if let Some(row) = this.model.table_mut(list).row_mut(id) {
                        h.apply_to(row);
                    }
                    this.set_status(tf!("editor.status.hashComputed", short(&h.sha256)), cx);
                }
                Err(e) => {
                    tracing::warn!("{}", tf!("log.editor.hashCalcFailed", e.to_string()));
                    this.set_status(tf!("editor.status.hashCalcFailed", e.to_string()), cx);
                }
            });
        })
        .detach();
    }

    /// Check URLs over a snapshot of the tab's rows; results land row by row.
    fn check_urls(&mut self, list: FileList, cx: &mut Context<Self>) {
        self.commit_cell(cx);
        let snapshot: Vec<(RowId, String)> = self
            .model
            .table(list)
            .entries()
            .iter()
            .map(|(id, r)| (*id, r.remote.clone()))
            .collect();
        let ids: Vec<RowId> = snapshot.iter().map(|(id, _)| *id).collect();
        self.model.table_mut(list).clear_url_status(&ids);
        self.set_status(tf!("editor.status.checkingUrls", tab_name(list)), cx);
        let backend = self.services_backend();
        cx.spawn(async move |this, cx| {
            let mut checked = 0usize;
            let mut broken = 0usize;
            for (id, remote) in snapshot {
                if remote.trim().is_empty() {
                    continue;
                }
                checked += 1;
                let b = backend.clone();
                let status = cx
                    .background_spawn(async move {
                        crate::url_check::check_url(b.as_ref(), &remote).await
                    })
                    .await;
                if !status.ok {
                    broken += 1;
                }
                if this
                    .update(cx, |this, cx| {
                        this.model.table_mut(list).set_url_status(id, status);
                        cx.notify();
                    })
                    .is_err()
                {
                    return;
                }
            }
            let _ = this.update(cx, |this, cx| {
                if let Some(first) = this.model.table_mut(list).select_broken(&ids)
                    && let Some(pos) = this
                        .model
                        .table(list)
                        .view_order()
                        .iter()
                        .position(|r| *r == first)
                {
                    this.scrolls[list_index(list)].scroll_to_item(pos, ScrollStrategy::Top);
                }
                this.set_status(
                    tf!(
                        "editor.status.urlCheck",
                        checked.to_string(),
                        broken.to_string()
                    ),
                    cx,
                );
            });
        })
        .detach();
    }

    /// Opens the Modrinth search dialog.
    pub fn search_modrinth(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.commit_cell(cx);
        self.sync_form(cx);
        let backend = self.services_backend();
        let loader_url = self.model.form.loader_url.clone();
        let loader = self.model.form.loader.label().to_string();
        let search = cx.new(|cx| ModrinthSearch::new(backend, &loader_url, &loader, window, cx));
        let editor = cx.entity().downgrade();
        window.open_dialog(cx, move |dialog, _w, cx| {
            let s_add = search.clone();
            let ed = editor.clone();
            dialog
                .title(t!("dialog.editor.searchModrinth.title").into_owned())
                .bg(cx.launcher_theme().solid_bg(true))
                .w(px(640.0))
                .keyboard(true)
                .overlay_closable(false)
                .child(search.clone())
                .footer(
                    div()
                        .flex()
                        .justify_end()
                        .gap(u(8.0))
                        .child(
                            Button::new("modrinth-cancel")
                                .ghost()
                                .label(t!("dialog.button.cancel").into_owned())
                                .on_click(|_, window, cx| window.close_dialog(cx)),
                        )
                        .child(
                            Button::new("modrinth-add")
                                .primary()
                                .label(t!("dialog.editor.searchModrinth.addSelected").into_owned())
                                .on_click(move |_, window, cx| {
                                    let (hits, filters) = {
                                        let s = s_add.read(cx);
                                        (s.selected_hits(), s.filters(cx))
                                    };
                                    window.close_dialog(cx);
                                    let _ =
                                        ed.update(cx, |ed, cx| ed.add_modrinth(hits, filters, cx));
                                }),
                        ),
                )
        });
    }

    /// Resolves, downloads and verifies each picked mod in turn, then appends them.
    fn add_modrinth(
        &mut self,
        hits: Vec<modrinth::SearchHit>,
        filters: Filters,
        cx: &mut Context<Self>,
    ) {
        if hits.is_empty() {
            return;
        }
        let existing: Vec<String> = self
            .model
            .table(FileList::Mods)
            .rows()
            .map(|r| r.local.trim().to_lowercase())
            .collect();
        let backend = self.services_backend();
        self.set_status(t!("editor.modSearch.resolving").into_owned(), cx);
        cx.spawn(async move |this, cx| {
            let outcomes = cx
                .background_spawn(async move {
                    let mut taken = existing;
                    let mut out = Vec::new();
                    for hit in &hits {
                        let snapshot = taken.clone();
                        let has = move |l: &str| {
                            let l = l.trim().to_lowercase();
                            !l.is_empty() && snapshot.contains(&l)
                        };
                        let o = modrinth::resolve_hit(
                            backend.as_ref(),
                            hit,
                            filters.game_version.as_deref(),
                            filters.loader.as_deref(),
                            &has,
                        )
                        .await;
                        if let Some(r) = o.row() {
                            taken.push(r.local.trim().to_lowercase());
                        }
                        out.push(o);
                    }
                    out
                })
                .await;
            let _ = this.update(cx, |this, cx| {
                let totals = AddTotals::of(&outcomes);
                let rows: Vec<_> = outcomes
                    .into_iter()
                    .filter_map(|o| match o {
                        AddOutcome::Verified(r) | AddOutcome::Unverified(r) => Some(r),
                        _ => None,
                    })
                    .collect();
                this.model.table_mut(FileList::Mods).append(rows);
                this.set_status(totals.status(), cx);
            });
        })
        .detach();
    }

    // -----------------------------------------------------------------------------------------
    // Rendering

    fn render_navbar(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let help = self.services.open_help.clone().map(|open| {
            IconButton::new(
                "editor-help",
                Icon::Help,
                t!("tooltip.common.help").into_owned(),
            )
            .compact()
            .on_click(move |window, cx| open(HelpTopic::ModpackEditor, window, cx))
        });
        div()
            .flex()
            .flex_none()
            .items_center()
            .h(u(52.0))
            .pl(u(14.0))
            .pr(u(10.0))
            .gap(u(12.0))
            .child(
                img(mmcl_ui_kit::assets::APP_ICON)
                    .size(u(36.0))
                    .object_fit(ObjectFit::Contain),
            )
            .child(
                div()
                    .type_role(TypeRole::TitleLarge)
                    .text_color(hsla(t.md_on_surface))
                    .child(t!("editor.navbar.title").into_owned()),
            )
            .child(div().flex_1())
            .child(OfflineChip::new("editor-offline"))
            .children(help)
    }

    fn render_toolbar(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let btn = |id: &'static str, key: &str| Button::new(id).small().label(t!(key).into_owned());
        div()
            .flex()
            .flex_none()
            .items_center()
            .h(u(44.0))
            .px(u(16.0))
            .py(u(6.0))
            .gap(u(8.0))
            .child(
                btn("editor-new", "editor.toolbar.new")
                    .on_click(cx.listener(|this, _: &ClickEvent, w, cx| this.new_document(w, cx))),
            )
            .child(
                btn("editor-open-file", "editor.toolbar.openFile").on_click(
                    cx.listener(|this, _: &ClickEvent, w, cx| this.open_from_file(w, cx)),
                ),
            )
            .child(
                btn("editor-open-url", "editor.toolbar.openUrl")
                    .on_click(cx.listener(|this, _: &ClickEvent, w, cx| this.open_from_url(w, cx))),
            )
            .child(div().flex_1().min_w(u(16.0)))
            .child(
                btn("editor-validate", "editor.toolbar.validate")
                    .on_click(cx.listener(|this, _: &ClickEvent, w, cx| this.validate(w, cx))),
            )
            .child(
                btn("editor-diff", "editor.toolbar.diff")
                    .on_click(cx.listener(|this, _: &ClickEvent, w, cx| this.show_diff(w, cx))),
            )
    }

    fn section(title_key: &str, cx: &App, body: impl IntoElement) -> impl IntoElement {
        let t = cx.tokens();
        div()
            .flex()
            .flex_col()
            .gap(u(8.0))
            .child(
                div()
                    .type_role(TypeRole::TitleSmall)
                    .text_color(hsla(t.md_on_surface_variant))
                    .child(t!(title_key).into_owned()),
            )
            .child(
                div()
                    .p(u(16.0))
                    .rounded(u(mmcl_theme::shape::MEDIUM))
                    .bg(hsla(t.md_surface_container))
                    .child(body),
            )
    }

    fn field(label_key: &str, cx: &App, width: f32, field: impl IntoElement) -> impl IntoElement {
        let t = cx.tokens();
        div()
            .flex()
            .flex_col()
            .gap(u(4.0))
            .w(u(width))
            .max_w_full()
            .child(
                div()
                    .type_role(TypeRole::LabelMedium)
                    .text_color(hsla(t.md_on_surface_variant))
                    .child(t!(label_key).into_owned()),
            )
            .child(field)
    }

    fn render_metadata(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let i = &self.inputs;
        let t = cx.tokens();
        let f = &self.model.form;
        let entity = cx.entity().downgrade();

        let bump = |id: &'static str, key: &str, pos: usize, cx: &mut Context<Self>| {
            Button::new(id)
                .small()
                .label(t!(key).into_owned())
                .on_click(
                    cx.listener(move |this, _: &ClickEvent, w, cx| this.bump_version(pos, w, cx)),
                )
        };
        let identity = div()
            .flex()
            .flex_col()
            .gap(u(12.0))
            .child(Self::field(
                "editor.field.packName",
                cx,
                500.0,
                Input::new(&i.name),
            ))
            .child(
                div()
                    .flex()
                    .items_end()
                    .gap(u(8.0))
                    .child(Self::field(
                        "editor.field.packVersion",
                        cx,
                        300.0,
                        Input::new(&i.version),
                    ))
                    .child(
                        div()
                            .flex()
                            .gap(u(2.0))
                            .child(bump("editor-bump-major", "editor.bumpBtn.major", 0, cx))
                            .child(bump("editor-bump-minor", "editor.bumpBtn.minor", 1, cx))
                            .child(bump("editor-bump-patch", "editor.bumpBtn.patch", 2, cx)),
                    ),
            )
            .child(Self::field(
                "editor.field.packUrl",
                cx,
                500.0,
                Input::new(&i.url),
            ))
            .child(Self::field(
                "editor.field.minRam",
                cx,
                500.0,
                Input::new(&i.ram),
            ));

        let e1 = entity.clone();
        let e2 = entity.clone();
        let flags = div()
            .flex()
            .flex_col()
            .gap(u(8.0))
            .child(
                Switch::new("editor-unstable")
                    .label(t!("editor.toggle.unstable").into_owned())
                    .checked(f.unstable)
                    .on_change(move |v, _w, cx| {
                        let _ = e1.update(cx, |this, cx| {
                            this.model.form.unstable = v;
                            cx.notify();
                        });
                    }),
            )
            .child(
                Switch::new("editor-custom-rpc")
                    .label(t!("editor.toggle.customDiscordRpc").into_owned())
                    .checked(f.custom_rpc)
                    .on_change(move |v, _w, cx| {
                        let _ = e2.update(cx, |this, cx| {
                            this.model.form.custom_rpc = v;
                            cx.notify();
                        });
                    }),
            );

        let loader = f.loader;
        let modloader = div()
            .flex()
            .flex_col()
            .gap(u(12.0))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(u(8.0))
                    .child(
                        div()
                            .flex()
                            .gap(u(2.0))
                            .children(Loader::ALL.iter().map(|l| {
                                let l = *l;
                                let b = Button::new(("editor-loader", list_loader_ix(l)))
                                    .small()
                                    .label(l.label())
                                    .on_click(cx.listener(move |this, _: &ClickEvent, _w, cx| {
                                        this.set_loader(l, cx)
                                    }));
                                if l == loader { b.primary() } else { b.ghost() }
                            })),
                    )
                    .child(
                        Button::new("editor-pick-loader")
                            .small()
                            .label(tf!("editor.pickerBtn.label", loader.label()))
                            .on_click(cx.listener(|this, _: &ClickEvent, w, cx| {
                                this.pick_loader_version(w, cx)
                            })),
                    ),
            )
            .child(
                div()
                    .text_size(px(12.0))
                    .text_color(hsla(t.md_on_surface_variant))
                    .child(t!(loader.hint_key()).into_owned()),
            )
            .child(Self::field(
                "editor.field.modloaderUrl",
                cx,
                500.0,
                Input::new(&i.loader_url),
            ))
            .child(Self::field(
                "editor.field.modloaderSha1",
                cx,
                500.0,
                Input::new(&i.loader_hash),
            ))
            .child(Self::field(
                "editor.field.minecraftVersion",
                cx,
                500.0,
                Input::new(&i.mc),
            ));

        // `accessibleText` of the FXML previews: the screen-reader label, and the tooltip.
        let preview = |image: Option<Arc<Image>>, w: f32, h: f32, id: &str, label_key: &str| {
            let label = t!(label_key).into_owned();
            div()
                .flex_none()
                .w(u(w))
                .h(u(h))
                .rounded(u(mmcl_theme::shape::SMALL))
                .bg(hsla(t.md_surface_container_high))
                .id(SharedString::from(id.to_string()))
                .role(gpui::Role::Image)
                .aria_label(label.clone())
                .launcher_tooltip(label)
                .when_some(image, |el, image| {
                    el.child(img(image).w(u(w)).h(u(h)).object_fit(ObjectFit::Contain))
                })
        };
        let images = div()
            .flex()
            .flex_col()
            .gap(u(16.0))
            .child(
                div()
                    .flex()
                    .gap(u(16.0))
                    .items_start()
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap(u(8.0))
                            .w(u(400.0))
                            .child(Textarea::new(&i.logo_urls).h(u(58.0)))
                            .child(Self::field(
                                "editor.field.logoSha1",
                                cx,
                                400.0,
                                Input::new(&i.logo_sha1),
                            )),
                    )
                    .child(preview(
                        self.logo_preview.clone(),
                        64.0,
                        64.0,
                        "editor-logo-preview",
                        "editor.preview.logo",
                    )),
            )
            .child(
                div()
                    .flex()
                    .gap(u(16.0))
                    .items_start()
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap(u(8.0))
                            .w(u(400.0))
                            .child(Textarea::new(&i.bg_urls).h(u(58.0)))
                            .child(Self::field(
                                "editor.field.backgroundSha1",
                                cx,
                                400.0,
                                Input::new(&i.bg_sha1),
                            )),
                    )
                    .child(preview(
                        self.bg_preview.clone(),
                        128.0,
                        64.0,
                        "editor-bg-preview",
                        "editor.preview.background",
                    )),
            );

        let scan = div()
            .w(u(500.0))
            .max_w_full()
            .child(Textarea::new(&i.scan).h(u(80.0)));

        div()
            .id("editor-metadata")
            .size_full()
            .overflow_y_scroll()
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(u(8.0))
                    .px(u(24.0))
                    .py(u(16.0))
                    .child(Self::section("editor.section.packIdentity", cx, identity))
                    .child(Self::section("editor.section.flags", cx, flags))
                    .child(Self::section("editor.section.modloader", cx, modloader))
                    .child(Self::section("editor.section.images", cx, images))
                    .child(Self::section("editor.section.scanExclusions", cx, scan)),
            )
    }

    fn render_table(&self, list: FileList, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let li = list_index(list);
        let table = self.model.table(list);
        let layout = &self.layouts[li];
        let cols: Vec<(Column, Option<f32>)> = layout
            .order()
            .iter()
            .map(|c| (*c, layout.width(*c)))
            .collect();
        let sort = table.sort();
        let count = table.view_order().len();

        let toolbar = div()
            .flex()
            .flex_none()
            .items_center()
            .p(u(8.0))
            .gap(u(8.0))
            .child(div().w(u(200.0)).child(Input::new(&self.filters[li])))
            .child(
                Button::new(("editor-add", li))
                    .small()
                    .label(t!("common.button.add").into_owned())
                    .on_click(
                        cx.listener(move |this, _: &ClickEvent, _w, cx| this.add_row(list, cx)),
                    ),
            )
            .child(
                Button::new(("editor-remove", li))
                    .small()
                    .danger()
                    .label(t!("common.button.remove").into_owned())
                    .disabled(table.selection().is_empty())
                    .on_click(cx.listener(move |this, _: &ClickEvent, _w, cx| {
                        this.remove_selected(list, cx)
                    })),
            )
            .child(
                Button::new(("editor-check-urls", li))
                    .small()
                    .label(t!("editor.checkUrls").into_owned())
                    .on_click(
                        cx.listener(move |this, _: &ClickEvent, _w, cx| this.check_urls(list, cx)),
                    ),
            )
            .when(list == FileList::Mods, |el| {
                el.child(
                    Button::new("editor-search-modrinth")
                        .small()
                        .label(t!("editor.searchModrinth").into_owned())
                        .on_click(
                            cx.listener(|this, _: &ClickEvent, w, cx| this.search_modrinth(w, cx)),
                        ),
                )
            })
            .child(
                div()
                    .text_color(hsla(t.md_on_surface_variant))
                    .child(model::entry_count(table.len())),
            );

        let bounds_sink = self.header_bounds.clone();
        let drop_bg = hsla(t.md_state_hover_on_surface);
        let edge = hsla(t.md_outline_variant);
        let header = div()
            .flex()
            .flex_none()
            .h(u(ROW_HEIGHT))
            .items_center()
            .border_b_1()
            .border_color(hsla(t.md_outline_variant))
            .bg(hsla(t.md_surface_container_high))
            .on_children_prepainted(move |bounds, _w, _cx| *bounds_sink.borrow_mut() = bounds)
            .on_drag_move(
                cx.listener(|this, ev: &DragMoveEvent<ColumnResize>, _w, cx| {
                    this.resize_to(ev.event.position.x, cx)
                }),
            )
            .children(cols.iter().map(|(c, width)| {
                let c = *c;
                let label = c
                    .header_key()
                    .map(|k| t!(k).into_owned())
                    .unwrap_or_default();
                let arrow = match sort {
                    Some((sc, SortDir::Ascending)) if sc == c => " \u{25B2}",
                    Some((sc, SortDir::Descending)) if sc == c => " \u{25BC}",
                    _ => "",
                };
                let payload = ColumnMove {
                    list,
                    col: c,
                    label: label.clone().into(),
                };
                // The right edge: drag to resize, double-click for the default width.
                let resize_edge = div()
                    .id(("editor-col-resize", li * 16 + col_ix(c)))
                    .absolute()
                    .top_0()
                    .bottom_0()
                    .right_0()
                    .w(u(6.0))
                    .cursor(CursorStyle::ResizeLeftRight)
                    .border_r_1()
                    .border_color(edge)
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, ev: &MouseDownEvent, _w, cx| {
                            cx.stop_propagation();
                            this.begin_resize(list, c, ev.position.x, cx);
                        }),
                    )
                    .on_click(cx.listener(move |this, ev: &ClickEvent, _w, cx| {
                        cx.stop_propagation();
                        if ev.click_count() >= 2 {
                            this.layouts[li].reset_width(c);
                            cx.notify();
                        }
                    }))
                    .on_drag(ColumnResize, |_, _, _, cx| cx.new(|_| EmptyView));
                cell_box(c, *width)
                    .relative()
                    .id(("editor-header", li * 16 + col_ix(c)))
                    .font_weight(gpui::FontWeight::SEMIBOLD)
                    .text_size(px(12.0))
                    .text_color(hsla(t.md_on_surface_variant))
                    .when(c.sortable(), |el| el.cursor_pointer())
                    .on_click(cx.listener(move |this, _: &ClickEvent, _w, cx| {
                        this.commit_cell(cx);
                        this.model.table_mut(list).click_header(c);
                        cx.notify();
                    }))
                    .on_drag(payload, |p, _, _, cx| cx.new(|_| p.clone()))
                    .drag_over::<ColumnMove>(move |s, _, _, _| s.bg(drop_bg))
                    .on_drop(cx.listener(move |this, m: &ColumnMove, _w, cx| {
                        if m.list == list {
                            this.move_column(list, m.col, c, cx);
                        }
                    }))
                    .child(div().truncate().child(format!("{label}{arrow}")))
                    .child(resize_edge)
            }));

        let cols_for_rows = cols.clone();
        let rows = uniform_list(
            ("editor-rows", li),
            count,
            cx.processor(move |this, range: std::ops::Range<usize>, _w, cx| {
                let order = this.model.table(list).view_order();
                range
                    .filter_map(|ix| order.get(ix).copied())
                    .map(|id| this.render_row(list, id, &cols_for_rows, cx))
                    .collect::<Vec<_>>()
            }),
        )
        .track_scroll(&self.scrolls[li])
        .flex_1();

        let empty = (count == 0).then(|| {
            div()
                .flex_1()
                .flex()
                .items_center()
                .justify_center()
                .text_color(hsla(t.md_on_surface_variant))
                .child(t!("editor.table.empty").into_owned())
        });
        div().flex().flex_col().size_full().child(toolbar).child(
            div()
                .id(("editor-table", li))
                .key_context(TABLE_KEY_CONTEXT)
                .track_focus(&self.table_focus)
                .aria_label(tab_name(list))
                .on_action(cx.listener(|this, _: &SelectAllRows, _w, cx| {
                    this.table_key(cx, |t| {
                        t.select_all_shown();
                        None
                    })
                }))
                .on_action(cx.listener(|this, _: &SelectPrevRow, _w, cx| {
                    this.table_key(cx, |t| t.move_lead(-1, false))
                }))
                .on_action(cx.listener(|this, _: &SelectNextRow, _w, cx| {
                    this.table_key(cx, |t| t.move_lead(1, false))
                }))
                .on_action(cx.listener(|this, _: &ExtendPrevRow, _w, cx| {
                    this.table_key(cx, |t| t.move_lead(-1, true))
                }))
                .on_action(cx.listener(|this, _: &ExtendNextRow, _w, cx| {
                    this.table_key(cx, |t| t.move_lead(1, true))
                }))
                .on_action(cx.listener(|this, _: &SelectPageUp, _w, cx| {
                    this.table_key(cx, |t| t.move_lead(-PAGE_ROWS, false))
                }))
                .on_action(cx.listener(|this, _: &SelectPageDown, _w, cx| {
                    this.table_key(cx, |t| t.move_lead(PAGE_ROWS, false))
                }))
                .on_action(cx.listener(|this, _: &SelectFirstRow, _w, cx| {
                    this.table_key(cx, |t| t.move_lead_to_edge(false, false))
                }))
                .on_action(cx.listener(|this, _: &SelectLastRow, _w, cx| {
                    this.table_key(cx, |t| t.move_lead_to_edge(true, false))
                }))
                .on_action(cx.listener(|this, _: &ExtendFirstRow, _w, cx| {
                    this.table_key(cx, |t| t.move_lead_to_edge(false, true))
                }))
                .on_action(cx.listener(|this, _: &ExtendLastRow, _w, cx| {
                    this.table_key(cx, |t| t.move_lead_to_edge(true, true))
                }))
                .on_action(
                    cx.listener(|this, _: &EditFocusedCell, w, cx| this.edit_focused_cell(w, cx)),
                )
                .on_action(cx.listener(|this, _: &NextCell, w, cx| this.step_cell(true, w, cx)))
                .on_action(cx.listener(|this, _: &PrevCell, w, cx| this.step_cell(false, w, cx)))
                .flex()
                .flex_col()
                .flex_1()
                .mx(u(8.0))
                .mb(u(8.0))
                .border_1()
                .border_color(hsla(t.md_outline_variant))
                .rounded(u(mmcl_theme::shape::SMALL))
                .overflow_hidden()
                .child(header)
                .children(empty)
                .when(count > 0, |el| el.child(rows)),
        )
    }

    fn render_row(
        &self,
        list: FileList,
        id: RowId,
        cols: &[(Column, Option<f32>)],
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let t = cx.tokens();
        let table = self.model.table(list);
        let Some(row) = table.row(id) else {
            return div().into_any_element();
        };
        let selected = table.is_selected(id);
        let rid = id.0 as usize;
        div()
            .id(("editor-row", rid))
            .flex()
            .items_center()
            .h(u(ROW_HEIGHT))
            .border_b_1()
            .border_color(hsla(t.md_outline_variant))
            .aria_selected(selected)
            .when(selected, |el| el.bg(hsla(t.md_secondary_container)))
            .when(!selected, |el| {
                el.hover(|s| s.bg(hsla(t.md_state_hover_on_surface)))
            })
            .children(cols.iter().map(|(c, width)| {
                let c = *c;
                let base = cell_box(c, *width).id(("editor-cell", rid * 16 + col_ix(c)));
                let editing = self.editing
                    == Some(CellEdit {
                        list,
                        row: id,
                        col: c,
                    });
                if editing {
                    return base
                        .key_context(CELL_KEY_CONTEXT)
                        .capture_key_down(cx.listener(|this, ev: &gpui::KeyDownEvent, w, cx| {
                            if ev.keystroke.key == "escape" {
                                this.cancel_cell(cx);
                                this.table_focus.focus(w, cx);
                                cx.stop_propagation();
                            }
                        }))
                        .child(Input::new(&self.cell_input).small())
                        .into_any_element();
                }
                match c {
                    Column::Client | Column::Server => {
                        let value = if c == Column::Client {
                            row.client_req
                        } else {
                            row.server_req
                        };
                        base.child(
                            Checkbox::new(("editor-check", rid * 16 + col_ix(c)))
                                .checked(value)
                                .on_click(cx.listener(move |this, v: &bool, _w, cx| {
                                    this.model.table_mut(list).set_check(id, c, *v);
                                    cx.notify();
                                })),
                        )
                        .into_any_element()
                    }
                    Column::Calc => base
                        .child(
                            Button::new(("editor-calc", rid))
                                .xsmall()
                                .label(t!("editor.calc").into_owned())
                                .on_click(cx.listener(move |this, _: &ClickEvent, _w, cx| {
                                    this.calc_row(list, id, cx)
                                })),
                        )
                        .into_any_element(),
                    Column::UrlStatus => {
                        let status = row.url_status.clone();
                        base.when_some(status, |el, s| {
                            el.font_weight(gpui::FontWeight::SEMIBOLD)
                                .text_color(hsla(if s.ok {
                                    t.color_success
                                } else {
                                    t.color_danger
                                }))
                                .child(s.label)
                        })
                        .on_click(cx.listener(move |this, ev: &ClickEvent, w, cx| {
                            this.row_clicked(list, id, ev, w, cx)
                        }))
                        .into_any_element()
                    }
                    _ => {
                        let text = c.text_of(row).to_string();
                        base.text_color(hsla(t.md_on_surface))
                            .text_size(px(13.0))
                            // The whole value on hover: long URLs and SHA-256 hashes truncate.
                            .when(!text.is_empty(), |el| el.launcher_tooltip(text.clone()))
                            .child(div().truncate().child(text))
                            .on_click(cx.listener(move |this, ev: &ClickEvent, w, cx| {
                                if ev.click_count() >= 2 {
                                    this.start_edit(
                                        CellEdit {
                                            list,
                                            row: id,
                                            col: c,
                                        },
                                        w,
                                        cx,
                                    );
                                } else {
                                    this.row_clicked(list, id, ev, w, cx);
                                }
                            }))
                            .into_any_element()
                    }
                }
            }))
            .into_any_element()
    }

    fn row_clicked(
        &mut self,
        list: FileList,
        id: RowId,
        ev: &ClickEvent,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.commit_cell(cx);
        self.table_focus.focus(window, cx);
        let m = ev.modifiers();
        let toggle = m.secondary(cx);
        self.model.table_mut(list).click_row(id, toggle, m.shift);
        cx.notify();
    }

    fn render_bottom_bar(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        div()
            .flex()
            .flex_none()
            .items_center()
            .h(u(44.0))
            .px(u(14.0))
            .gap(u(10.0))
            .child(
                div()
                    .flex_1()
                    .truncate()
                    .text_color(hsla(t.md_on_surface_variant))
                    .child(self.status.clone()),
            )
            .child(
                Button::new("editor-save")
                    .primary()
                    .label(t!("editor.saveBtn").into_owned())
                    .on_click(cx.listener(|this, _: &ClickEvent, w, cx| this.save_to_file(w, cx))),
            )
            .child(
                Button::new("editor-return")
                    .label(t!("editor.returnBtn").into_owned())
                    .on_click(cx.listener(|this, _: &ClickEvent, w, cx| this.go_back(w, cx))),
            )
    }
}

fn dirs_fallback() -> Option<PathBuf> {
    std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" }).map(PathBuf::from)
}

fn ok_button() -> DialogButton {
    DialogButton::new(t!("dialog.button.ok").into_owned(), ButtonRole::Primary)
}

fn col_ix(c: Column) -> usize {
    Column::ALL.iter().position(|x| *x == c).unwrap_or(0)
}

fn list_loader_ix(l: Loader) -> usize {
    Loader::ALL.iter().position(|x| *x == l).unwrap_or(0)
}

/// A cell sized by the column's width policy: Remote URL and Local Path grow (Remote absorbs
/// the most), the rest keep their preferred width.
fn cell_box(c: Column, width: Option<f32>) -> gpui::Div {
    let (pref, min, _max) = c.widths();
    let base = div()
        .flex()
        .items_center()
        .px(u(6.0))
        .h_full()
        .overflow_hidden();
    if let Some(w) = width {
        // A width the user dragged to.
        return base.flex_none().w(u(w));
    }
    match c {
        Column::Remote => base.flex_grow(2.0).flex_basis(u(pref)).min_w(u(min)),
        Column::Local | Column::Hash => base
            .flex_grow(1.0)
            .flex_basis(u(pref))
            .flex_shrink(1.0)
            .min_w(u(min)),
        _ => base.flex_none().w(u(pref)),
    }
}

impl Focusable for ModpackEditor {
    fn focus_handle(&self, _cx: &App) -> FocusHandle {
        self.focus.clone()
    }
}

impl Render for ModpackEditor {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let tabs = TabBar::new("editor-tabs")
            .underline()
            .selected_index(self.tab)
            .on_click(cx.listener(|this, ix: &usize, _w, cx| {
                this.commit_cell(cx);
                this.tab = *ix;
                cx.notify();
            }))
            .children(
                TAB_KEYS
                    .iter()
                    .map(|k| Tab::new().label(t!(*k).into_owned())),
            );
        let body = if self.tab == 0 {
            self.render_metadata(cx).into_any_element()
        } else {
            self.render_table(FileList::ALL[self.tab - 1], cx)
                .into_any_element()
        };
        let banner = Some(self.announcement.clone()).filter(|a| !a.trim().is_empty());
        div()
            .id("modpack-editor")
            .key_context(KEY_CONTEXT)
            .track_focus(&self.focus)
            .on_action(cx.listener(|this, _: &SaveDocument, w, cx| this.save_to_file(w, cx)))
            .on_action(cx.listener(|this, _: &NewDocument, w, cx| this.new_document(w, cx)))
            .flex()
            .flex_col()
            .size_full()
            .min_w(u(750.0))
            .min_h(u(475.0))
            .text_color(hsla(t.md_on_surface))
            .child(self.render_navbar(cx))
            .child(AnnouncementBanner::new("editor-announcement", banner))
            .child(self.render_toolbar(cx))
            .child(div().flex_none().px(u(8.0)).child(tabs))
            .child(div().flex_1().min_h(px(0.0)).child(body))
            .child(self.render_bottom_bar(cx))
    }
}

impl Screen for ModpackEditor {
    fn id(&self) -> ScreenId {
        ScreenId::EDITOR
    }

    fn title(&self, _cx: &App) -> SharedString {
        t!("editor.navbar.title").into_owned().into()
    }

    fn help_topic(&self) -> HelpTopic {
        HelpTopic::ModpackEditor
    }

    fn has_own_help_button(&self) -> bool {
        self.services.open_help.is_some()
    }

    fn min_size(&self) -> gpui::Size<gpui::Pixels> {
        gpui::size(px(750.0), px(475.0))
    }

    fn setup(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.set_up {
            return;
        }
        self.set_up = true;
        self.disposed = false;
        self.announcement = self.services.backend.announcement().into();
        self.model.new_document();
        self.show_model(window, cx);
        // Java's setup ran newDocument(), which announced the blank document.
        self.set_status(t!("editor.status.newCreated").into_owned(), cx);
        if let Some(pack) = self.initial.take() {
            self.load_from_pack(pack, window, cx);
        }
    }

    fn after_show(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.focus.focus(window, cx);
    }

    fn cleanup(&mut self, _cx: &mut Context<Self>) {
        self.disposed = true;
    }

    fn confirm_navigate_away(&mut self, window: &mut Window, cx: &mut Context<Self>) -> Task<bool> {
        if std::mem::take(&mut self.leave_confirmed) || !self.is_dirty(cx) {
            return Task::ready(true);
        }
        let owner = Some(window.window_handle());
        cx.spawn(async move |_, cx| Self::ask_discard(cx, owner).await)
    }

    fn on_close_requested(&mut self, window: &mut Window, cx: &mut Context<Self>) -> CloseDecision {
        if !self.is_dirty(cx) {
            return CloseDecision::Proceed;
        }
        let owner = Some(window.window_handle());
        let quit = self.services.request_quit.clone();
        cx.spawn(async move |_, cx| {
            if Self::ask_discard(cx, owner).await {
                cx.update(|cx| match quit {
                    Some(q) => q(cx),
                    None => cx.quit(),
                });
            }
        })
        .detach();
        CloseDecision::Keep
    }
}

/// A weak handle helper for callers holding the view.
pub fn downgrade(view: &Entity<ModpackEditor>) -> WeakEntity<ModpackEditor> {
    view.downgrade()
}

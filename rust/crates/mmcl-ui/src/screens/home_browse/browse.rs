// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Browse screen (`MCLauncherGameLibraryGui`, gui-shell spec section 4): installed and
//! available modpacks, vanilla versions and loader versions as library cards; install /
//! uninstall; add by URL (Mica, Modrinth, CurseForge, Technic); import ZIP and Prism / MultiMC;
//! the hosting manifest.

use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::rc::Rc;
use std::time::Duration;

use gpui::{
    App, AppContext as _, AsyncWindowContext, ClickEvent, Context, Entity, FocusHandle, Focusable,
    InteractiveElement, IntoElement, ParentElement, Pixels, Render, ScrollHandle, SharedString,
    Size, StatefulInteractiveElement, Styled, Subscription, Task, WeakEntity, Window, div,
    prelude::FluentBuilder as _, px, size,
};
use gpui_component::Disableable as _;
use gpui_component::button::{Button, ButtonVariants as _};
use gpui_component::input::{Input, InputEvent, InputState};
use mmcl_engine::{
    EngineEvent, ImportPreview, ImportRequest, ImportSourceView, LoaderVersionView, PackId,
    VanillaVersionView,
};
use mmcl_i18n::{t, tf};
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::theme::{ThemeExt as _, TypeStyleExt as _, hsla};
use mmcl_ui_kit::{
    AnnouncementBanner, FileFilter, HelpTopic, Icon, IconButton, LoadingIndicator, OfflineChip,
    OpenDialog, SaveDialog, Screen, ScreenId, ScreenRequest, SearchField, empty_state_badge,
    notify,
};

use super::backend::{
    Classified, ImportFailure, LibraryBackend, PackExtras, PreviewResult, backend,
};
use super::browse_logic::{
    self, BrowseSources, EntryAction, EntryKind, LibraryEntry, collect_entries, filter_search,
    hosting_manifest, loader_for, pack_mods_match, sort_entries, vanilla_kinds_for,
};
use super::card::{ButtonStyle, CardButton, CardGeometry, ImportPlaceholder, PackCard};
use super::controls::{Pick, combo, field_label, sources_for};
use super::cycle::{self, CycleSubscription};
use super::home_logic::pagination_labels;
use super::hooks;
use super::model::{
    BROWSE_DEFAULT_PAGE_SIZE, BROWSE_PAGE_SIZES, BROWSE_SORTS, BROWSE_STATUSES, BROWSE_TYPES,
    Change, FILTER_STATUS, FILTER_TYPE, FilterValue, LibraryViewModel, PageBounds, SORT_PREFIX,
    STATUS_PREFIX, TYPE_PREFIX, label,
};
use super::pack_actions::{self, Done, StatusSink, home_dir, with_extension};
use super::visuals::{self, Brand, LogoSource};
use crate::actions::{BROWSE_CONTEXT, FocusSearch};
use crate::dialogs::{self, Answer, Question};
use crate::scale::u;
use crate::services::{UiEvent, events};
use crate::widgets::NavBar;

/// The delay before a rebuild shows its loading indicator (warm rebuilds never flash).
pub const LOADING_DELAY: Duration = Duration::from_millis(150);

/// The `.mrpack` type of the ZIP import dialog.
fn mrpack_filter() -> FileFilter {
    FileFilter::new(t!("dialog.fileChooser.mrpack.filter"), &["mrpack"])
}

/// Whether `path` is a Modrinth `.mrpack` (extension, case-insensitive).
pub fn is_mrpack(path: &Path) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .is_some_and(|e| e.eq_ignore_ascii_case("mrpack"))
}

/// How a prepared import ended.
enum PreparedOutcome {
    /// Confirmed and installed.
    Installed,
    /// Cancelled in the confirmation (the download was deleted).
    Discarded,
    /// Preparing or installing failed.
    Failed(ImportFailure),
}

/// Shows the import confirmation for a prepared import: the installed dialog hook, else a
/// question listing the same facts. `true` to install.
async fn confirm_prepared_import(preview: ImportPreview, cx: &mut AsyncWindowContext) -> bool {
    let (header, body) = browse_logic::import_confirm_text(&preview);
    match cx.update(|_, cx| hooks::confirm_prepared(preview, cx)) {
        Ok(Some(task)) => task.await,
        Ok(None) => {
            let q = Question::new(
                t!("dialog.importConfirm.title"),
                header,
                body,
                t!("common.button.import"),
                t!("dialog.button.cancel"),
            );
            dialogs::ask_multiline(cx, &q, None).await == Answer::Primary
        }
        Err(_) => false,
    }
}

/// prepare -> confirm -> commit (or discard) for `request`, with the placeholder card's status
/// line following each step.
async fn prepare_confirm_commit(
    this: WeakEntity<BrowseScreen>,
    request: ImportRequest,
    cx: &mut AsyncWindowContext,
) -> PreparedOutcome {
    let status =
        |this: &WeakEntity<BrowseScreen>, key: &'static str, cx: &mut AsyncWindowContext| {
            let _ = this.update(cx, |this, cx| {
                this.update_import_status(t!(key).into_owned(), cx)
            });
        };
    status(&this, "library.import.preparing", cx);
    let Ok(fut) = cx.update(|_, cx| backend(cx).prepare_import(request)) else {
        return PreparedOutcome::Discarded;
    };
    let preview = match fut.await {
        Ok(p) => p,
        Err(f) => return PreparedOutcome::Failed(f),
    };
    let token = preview.token;
    status(&this, "library.import.waitingConfirmation", cx);
    if !confirm_prepared_import(preview, cx).await {
        let _ = cx.update(|_, cx| backend(cx).discard_import(token));
        return PreparedOutcome::Discarded;
    }
    status(&this, "library.import.addingToLibrary", cx);
    let Ok(fut) = cx.update(|_, cx| backend(cx).commit_import(token)) else {
        return PreparedOutcome::Discarded;
    };
    match fut.await {
        Ok(_) => PreparedOutcome::Installed,
        Err(f) => PreparedOutcome::Failed(f),
    }
}

/// The Browse screen.
pub struct BrowseScreen {
    backend: Rc<dyn LibraryBackend>,
    focus: FocusHandle,
    vm: LibraryViewModel,
    search: Entity<InputState>,
    url: Entity<InputState>,
    entries: Vec<LibraryEntry>,
    bounds: PageBounds,
    generation: u64,
    loading: bool,
    status: Option<String>,
    import: Option<(String, String)>,
    url_busy: Option<String>,
    importing_zip: bool,
    importing_prism: bool,
    hosting_saved: bool,
    extras_pending: HashSet<PackId>,
    vanilla: HashMap<String, Vec<VanillaVersionView>>,
    loaders: HashMap<String, Vec<LoaderVersionView>>,
    extras: HashMap<PackId, PackExtras>,
    disposed: bool,
    debounce: Option<Task<()>>,
    rebuild_task: Option<Task<()>>,
    loading_timer: Option<Task<()>>,
    scroll: ScrollHandle,
    cycle_sub: Option<CycleSubscription>,
    subs: Vec<Subscription>,
    tasks: Vec<Task<()>>,
}

impl BrowseScreen {
    /// A Browse screen.
    pub fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let search = cx.new(|cx| {
            InputState::new(window, cx).placeholder(t!("filter.search.prompt").into_owned())
        });
        let url = cx.new(|cx| {
            InputState::new(window, cx).placeholder(t!("browse.addByUrl.prompt").into_owned())
        });
        let mut vm = LibraryViewModel::new(
            BROWSE_DEFAULT_PAGE_SIZE,
            super::model::DEFAULT_SEARCH_DEBOUNCE_MS,
        )
        .expect("positive page size");
        vm.set_filter(FILTER_TYPE, FilterValue::Str("all".into()));
        vm.set_filter(FILTER_STATUS, FilterValue::Str("installed".into()));
        vm.set_sort_key(Some("default"));
        BrowseScreen {
            backend: backend(cx),
            focus: cx.focus_handle(),
            vm,
            search,
            url,
            entries: Vec::new(),
            bounds: PageBounds {
                total_items: 0,
                total_pages: 1,
                start: 0,
                end: 0,
            },
            generation: 0,
            loading: false,
            status: None,
            import: None,
            url_busy: None,
            importing_zip: false,
            importing_prism: false,
            hosting_saved: false,
            extras_pending: HashSet::new(),
            vanilla: HashMap::new(),
            loaders: HashMap::new(),
            extras: HashMap::new(),
            disposed: false,
            debounce: None,
            rebuild_task: None,
            loading_timer: None,
            scroll: ScrollHandle::new(),
            cycle_sub: None,
            subs: Vec::new(),
            tasks: Vec::new(),
        }
    }

    fn kind(&self) -> String {
        self.vm.get_string_filter(FILTER_TYPE, "all").to_string()
    }

    fn status_filter(&self) -> String {
        self.vm
            .get_string_filter(FILTER_STATUS, "installed")
            .to_string()
    }

    fn apply(&mut self, change: Change, cx: &mut Context<Self>) {
        match change {
            Change::None => {}
            Change::Rebuild => self.rebuild(cx),
            Change::Debounced => {
                let ms = self.vm.debounce_ms();
                self.debounce = Some(cx.spawn(async move |this, cx| {
                    cx.background_executor()
                        .timer(Duration::from_millis(ms))
                        .await;
                    let _ = this.update(cx, |this, cx| this.rebuild(cx));
                }));
            }
        }
    }

    /// Rebuilds the entry list off the UI thread (generation-guarded; `rebuildCards`).
    pub fn rebuild(&mut self, cx: &mut Context<Self>) {
        if self.disposed {
            return;
        }
        self.generation += 1;
        let generation = self.generation;
        let kind = self.kind();
        let status = self.status_filter();
        let search = self.vm.search_query().trim().to_lowercase();
        let sort = self.vm.sort_key().to_string();
        let backend = self.backend.clone();
        let vanilla_ids = backend.installed_vanilla_ids();
        let mut src = BrowseSources {
            installed: backend.installed_packs(),
            available: backend.available_packs_if_ready(),
            vanilla_installed: vanilla_ids.clone(),
            vanilla: self.vanilla.clone(),
            loaders: self.loaders.clone(),
        };
        let needs_available = status != "installed" && (kind == "all" || kind == "modpacks");
        let missing_vanilla: Vec<&'static str> = vanilla_kinds_for(&kind)
            .iter()
            .copied()
            .filter(|k| !src.vanilla.contains_key(*k))
            .collect();
        let missing_loader =
            loader_for(&kind).filter(|l| !src.loaders.contains_key(*l) && status != "installed");
        let missing_roots: Vec<PackId> = if search.is_empty() {
            Vec::new()
        } else {
            src.installed
                .iter()
                .filter(|p| !self.extras.contains_key(&p.id))
                .map(|p| p.id.clone())
                .collect()
        };
        let mut roots: HashMap<PackId, std::path::PathBuf> = self
            .extras
            .iter()
            .filter_map(|(k, v)| v.root.clone().map(|r| (k.clone(), r)))
            .collect();
        let update_ms: HashMap<PackId, i64> = self
            .extras
            .iter()
            .filter_map(|(k, v)| v.newest_update_ms.map(|m| (k.clone(), m)))
            .collect();

        self.loading_timer = Some(cx.spawn(async move |this, cx| {
            cx.background_executor().timer(LOADING_DELAY).await;
            let _ = this.update(cx, |this, cx| {
                if this.generation == generation {
                    this.loading = true;
                    cx.notify();
                }
            });
        }));
        self.rebuild_task = Some(cx.spawn(async move |this, cx| {
            if needs_available && src.available.is_none() {
                src.available = Some(backend.available_packs().await);
            }
            for k in missing_vanilla {
                let list = backend.vanilla_versions(k).await;
                src.vanilla.insert(k.to_string(), list);
            }
            if let Some(l) = missing_loader {
                let list = backend.loader_versions(l).await;
                src.loaders.insert(l.to_string(), list);
            }
            let mut new_extras = Vec::new();
            for id in missing_roots {
                if let Some(e) = backend.pack_extras(&id).await {
                    if let Some(r) = &e.root {
                        roots.insert(id.clone(), r.clone());
                    }
                    new_extras.push((id, e));
                }
            }
            for list in src.vanilla.values_mut() {
                for v in list.iter_mut() {
                    v.installed = vanilla_ids.iter().any(|i| i == &v.id);
                }
            }
            let cache = (src.vanilla.clone(), src.loaders.clone());
            let now = chrono::Utc::now().timestamp_millis();
            let entries = cx
                .background_spawn(async move {
                    let entries = collect_entries(&src, &kind, &status, now);
                    let mut entries = filter_search(entries, &search, |p, needle| {
                        roots.get(&p.id).is_some_and(|r| pack_mods_match(r, needle))
                    });
                    sort_entries(&mut entries, &sort, |id| update_ms.get(id).copied());
                    entries
                })
                .await;
            let _ = this.update(cx, |this, cx| {
                this.vanilla = cache.0;
                this.loaders = cache.1;
                for (id, e) in new_extras {
                    this.extras.insert(id, e);
                }
                if this.generation != generation || this.disposed {
                    return;
                }
                this.loading_timer = None;
                this.loading = false;
                this.entries = entries;
                this.bounds = this.vm.clamp_and_slice(this.entries.len());
                this.update_cycling(cx);
                this.request_extras(cx);
                cx.notify();
            });
        }));
    }

    /// Loads the folder / loader facts of every installed pack shown that has none yet (a pack
    /// installed or changed while Browse is open), so its context menu's folder, export and
    /// official-launcher items work without a search first. The menu reads them at open time.
    fn request_extras(&mut self, cx: &mut Context<Self>) {
        let missing: Vec<PackId> = self
            .entries
            .iter()
            .filter(|e| e.kind == EntryKind::ModpackInstalled)
            .filter_map(|e| e.pack.as_ref().map(|p| p.id.clone()))
            .filter(|id| !self.extras.contains_key(id))
            .filter(|id| self.extras_pending.insert(id.clone()))
            .collect();
        for id in missing {
            let fut = self.backend.pack_extras(&id);
            self.tasks.push(cx.spawn(async move |this, cx| {
                let extras = fut.await;
                let _ = this.update(cx, |this, cx| {
                    this.extras_pending.remove(&id);
                    if let Some(e) = extras
                        && !this.disposed
                    {
                        this.extras.insert(id, e);
                        cx.notify();
                    }
                });
            }));
        }
    }

    fn update_cycling(&mut self, cx: &mut Context<Self>) {
        let s = self.backend.settings();
        cycle::configure(cx, &s.image_cycle_interval);
        let any = self.entries[self.bounds.start..self.bounds.end]
            .iter()
            .any(|e| {
                e.pack.as_ref().is_some_and(|p| {
                    sources_for(
                        cx,
                        &self.backend,
                        p,
                        s.show_pack_backgrounds,
                        s.image_cycle_shuffle,
                    )
                    .cycles
                })
            });
        self.cycle_sub = if any && !self.disposed {
            self.cycle_sub.take().or_else(|| Some(cycle::subscribe(cx)))
        } else {
            None
        };
    }

    // Both sinks defer: actions call them synchronously from a card's click handler, which is
    // still inside this screen's update, and a nested update of the same entity panics.
    fn status_sink(cx: &mut Context<Self>) -> StatusSink {
        let weak = cx.entity().downgrade();
        Rc::new(move |text, cx| {
            let weak = weak.clone();
            cx.defer(move |cx| {
                let _ = weak.update(cx, |this, cx| {
                    this.status = text;
                    cx.notify();
                });
            });
        })
    }

    fn done(cx: &mut Context<Self>) -> Done {
        let weak = cx.entity().downgrade();
        Rc::new(move |cx| {
            let weak = weak.clone();
            cx.defer(move |cx| {
                let _ = weak.update(cx, |this, cx| this.rebuild(cx));
            });
        })
    }

    // ---- imports ----

    fn begin_import(&mut self, name: Option<String>, cx: &mut Context<Self>) {
        self.import = Some((
            name.filter(|n| !n.trim().is_empty())
                .unwrap_or_else(|| t!("library.import.defaultName").into_owned()),
            t!("library.import.preparing").into_owned(),
        ));
        self.rebuild(cx);
    }

    fn update_import_status(&mut self, text: String, cx: &mut Context<Self>) {
        if text.trim().is_empty() {
            return;
        }
        if let Some((_, s)) = &mut self.import {
            *s = text;
            cx.notify();
        }
    }

    fn end_import(&mut self, cx: &mut Context<Self>) {
        self.import = None;
        self.rebuild(cx);
    }

    fn reset_url(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.url_busy = None;
        self.url.update(cx, |s, cx| {
            s.set_value("", window, cx);
            s.set_disabled(false, cx);
        });
        cx.notify();
    }

    /// Add from URL: classify, then the Modrinth / CurseForge / Technic / Mica flow.
    pub fn add_from_url(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let url = self.url.read(cx).value().trim().to_string();
        if url.is_empty()
            || self.url_busy.is_some()
            || self.backend.announcements().disable_modpacks_edit
        {
            return;
        }
        self.url.update(cx, |s, cx| s.set_disabled(true, cx));
        let classified = self.backend.classify(&url);
        match classified.source {
            ImportSourceView::Modrinth => {
                self.url_busy = Some(t!("browse.urlAdd.checking").into_owned());
                self.modrinth_flow(url, classified, window, cx);
            }
            ImportSourceView::CurseForge => {
                self.url_busy = Some(t!("browse.urlAdd.checking").into_owned());
                self.reset_url(window, cx);
                let preview = [
                    t!("preview.curseforge.detected").into_owned(),
                    String::new(),
                    format!(
                        "{} ({})",
                        t!("preview.curseforge.sourceLabel"),
                        classified.slug.clone().unwrap_or_default()
                    ),
                    classified
                        .version_id
                        .as_deref()
                        .map(|f| tf!("preview.curseforge.fileIdLabel", f))
                        .unwrap_or_default(),
                    String::new(),
                    t!("preview.curseforge.note").into_owned(),
                    String::new(),
                    tf!("preview.urlLabel", url.as_str()),
                ]
                .join("\n");
                cx.spawn(async move |_, cx| {
                    let q = Question::new(
                        t!("dialog.import.curseforge.title"),
                        t!("dialog.import.curseforge.header"),
                        preview,
                        t!("dialog.import.curseforge.button.openPage"),
                        t!("dialog.button.cancel"),
                    );
                    if dialogs::ask_multiline(cx, &q, None).await == Answer::Primary {
                        cx.update(|cx| mmcl_ui_kit::open_external_url(cx, &url));
                    }
                })
                .detach();
            }
            ImportSourceView::Technic => {
                self.reset_url(window, cx);
                let slug = classified.slug.clone().unwrap_or_default();
                let preview = [
                    t!("preview.technic.detected").into_owned(),
                    String::new(),
                    format!("{} ({slug})", t!("preview.technic.sourceLabel")),
                    String::new(),
                    t!("preview.technic.note").into_owned(),
                    String::new(),
                    t!("preview.technic.step1").into_owned(),
                    t!("preview.technic.step2").into_owned(),
                    t!("preview.technic.step3").into_owned(),
                    String::new(),
                    tf!("preview.urlLabel", url.as_str()),
                ]
                .join("\n");
                cx.spawn(async move |_, cx| {
                    let q = Question::new(
                        t!("dialog.import.technic.title"),
                        t!("dialog.import.technic.header"),
                        preview,
                        t!("dialog.import.technic.button.openPage"),
                        t!("dialog.button.cancel"),
                    );
                    if dialogs::ask_multiline(cx, &q, None).await == Answer::Primary
                        && !slug.is_empty()
                    {
                        // The site page, never the API host (that one dumps JSON).
                        let page = format!("https://www.technicpack.net/modpack/{slug}");
                        cx.update(|cx| mmcl_ui_kit::open_external_url(cx, &page));
                    }
                })
                .detach();
            }
            ImportSourceView::Mica | ImportSourceView::Unknown => {
                self.url_busy = Some(t!("browse.urlAdd.adding").into_owned());
                cx.notify();
                let fut = self.backend.install_by_url(&url);
                cx.spawn_in(window, async move |this, cx| {
                    let r = fut.await;
                    let _ = this.update_in(cx, |this, window, cx| {
                        this.reset_url(window, cx);
                        this.rebuild(cx);
                    });
                    if let Err(e) = r {
                        notify::error(
                            &t!("notification.install.failed.title"),
                            &tf!("notification.install.failed.body", url.as_str(), e),
                        );
                    }
                })
                .detach();
            }
        }
        cx.notify();
    }

    fn modrinth_flow(
        &mut self,
        url: String,
        classified: Classified,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let preview = self.backend.modrinth_preview(&url);
        cx.spawn_in(window, async move |this, cx| {
            let result = preview.await;
            let _ = this.update_in(cx, |this, window, cx| this.reset_url(window, cx));
            let slug = classified.slug.clone().unwrap_or_default();
            let (title, body) = match &result {
                PreviewResult::NotFound => {
                    notify::error(
                        &t!("notification.import.modrinth.failed.title"),
                        &t!("notification.import.modrinth.failed.body"),
                    );
                    return;
                }
                PreviewResult::Failed(message) => {
                    notify::error(&t!("notification.import.modrinth.failed.title"), message);
                    return;
                }
                PreviewResult::Found(p) => browse_logic::modrinth_preview_text(p, &url),
                PreviewResult::Unsupported => {
                    let body = [
                        format!("{} ({slug})", t!("preview.modrinth.sourceLabel")),
                        tf!("preview.urlLabel", url.as_str()),
                    ]
                    .join("\n");
                    (slug.clone(), body)
                }
            };
            let q = Question::new(
                t!("dialog.import.modrinth.title"),
                t!("dialog.import.modrinth.header"),
                body,
                t!("dialog.import.modrinth.button.continue"),
                t!("dialog.button.cancel"),
            );
            if dialogs::ask_multiline(cx, &q, None).await != Answer::Primary {
                return;
            }
            // The placeholder card shows each step (Java's order): the download and read of
            // the archive (`prepare`, nothing installed yet), the import-confirm dialog with the
            // file list, then adding the pack to the library (`commit`).
            let _ = this.update(cx, |this, cx| this.begin_import(Some(title.clone()), cx));
            notify::info(
                &t!("notification.import.preparing.title"),
                &t!("notification.import.preparing.body"),
            );
            let _ = this.update(cx, |this, cx| {
                this.update_import_status(
                    t!("library.import.modrinth.downloading").into_owned(),
                    cx,
                );
            });
            let Ok(fut) = cx.update(|_, cx| match &result {
                PreviewResult::Found(p) => backend(cx).prepare_modrinth(p),
                _ => backend(cx).prepare_import(ImportRequest::Url(url.clone())),
            }) else {
                return;
            };
            let prepared = match fut.await {
                Ok(p) => p,
                Err(f) => {
                    notify::error(&t!("notification.import.failed.title"), &f.message);
                    let _ = this.update(cx, |this, cx| this.end_import(cx));
                    return;
                }
            };
            let token = prepared.token;
            let _ = this.update(cx, |this, cx| {
                this.update_import_status(t!("library.import.waitingConfirmation").into_owned(), cx)
            });
            let confirmed = confirm_prepared_import(prepared, cx).await;
            if !confirmed {
                // Cancelled: the download is deleted and nothing was installed.
                let _ = cx.update(|_, cx| backend(cx).discard_import(token));
                let _ = this.update(cx, |this, cx| this.end_import(cx));
                return;
            }
            notify::info(
                &t!("notification.browse.importStarting.title"),
                &t!("notification.browse.importStarting.body"),
            );
            let _ = this.update(cx, |this, cx| {
                this.update_import_status(t!("library.import.addingToLibrary").into_owned(), cx)
            });
            let Ok(fut) = cx.update(|_, cx| backend(cx).commit_import(token)) else {
                return;
            };
            match fut.await {
                Ok(_) => notify::success(
                    &t!("notification.browse.importComplete.title"),
                    &t!("notification.browse.importComplete.body"),
                ),
                Err(f) => {
                    tracing::error!(
                        "{}",
                        tf!("log.gameLibrary.modrinthInstallFailed", f.message.as_str())
                    );
                    notify::error(&t!("notification.browse.installFailed.title"), &f.message)
                }
            }
            let _ = this.update(cx, |this, cx| this.end_import(cx));
        })
        .detach();
    }

    /// Import ZIP (a Mica export or Technic server zip; a `.mrpack` picked here goes through the
    /// import confirmation with its file list).
    pub fn import_zip(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.importing_zip || self.backend.announcements().disable_modpacks_edit {
            return;
        }
        let pick = OpenDialog::new(t!("dialog.fileChooser.importModpackZip.title"))
            .directory(home_dir())
            .filter(FileFilter::mica_modpack_zip())
            .filter(mrpack_filter())
            .pick_file(Some(window));
        cx.spawn_in(window, async move |this, cx| {
            let Some(file) = pick.await else {
                return;
            };
            let name = file.file_stem().map(|s| s.to_string_lossy().into_owned());
            if is_mrpack(&file) {
                let _ = this.update(cx, |this, cx| {
                    this.importing_zip = true;
                    this.begin_import(name, cx);
                });
                let outcome =
                    prepare_confirm_commit(this.clone(), ImportRequest::MrpackFile(file), cx).await;
                match outcome {
                    PreparedOutcome::Installed => notify::success(
                        &t!("notification.import.complete.title"),
                        &t!("notification.import.complete.body"),
                    ),
                    PreparedOutcome::Failed(f) if f.expected => {
                        notify::error(&t!("notification.import.failed.title"), &f.message)
                    }
                    PreparedOutcome::Failed(_) => notify::error(
                        &t!("notification.import.unexpected.title"),
                        &t!("notification.import.unexpected.body"),
                    ),
                    PreparedOutcome::Discarded => {}
                }
                let _ = this.update(cx, |this, cx| {
                    this.importing_zip = false;
                    this.end_import(cx);
                });
                return;
            }
            let fut = this.update(cx, |this, cx| {
                this.importing_zip = true;
                this.begin_import(name, cx);
                this.backend.import(ImportRequest::ZipFile(file))
            });
            let Ok(fut) = fut else { return };
            match fut.await {
                Ok(out) if out.pack.as_str().contains("/technic-server-") => notify::success(
                    &t!("notification.import.technicServer.title"),
                    &t!("notification.import.technicServer.body"),
                ),
                Ok(_) => notify::success(
                    &t!("notification.import.complete.title"),
                    &t!("notification.import.complete.body"),
                ),
                Err(f) if f.expected => {
                    notify::error(&t!("notification.import.failed.title"), &f.message)
                }
                Err(_) => notify::error(
                    &t!("notification.import.unexpected.title"),
                    &t!("notification.import.unexpected.body"),
                ),
            }
            let _ = this.update(cx, |this, cx| {
                this.importing_zip = false;
                this.end_import(cx);
            });
        })
        .detach();
    }

    /// Import MultiMC / Prism instance.
    pub fn import_prism(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.importing_prism || self.backend.announcements().disable_modpacks_edit {
            return;
        }
        let pick = OpenDialog::new(t!("dialog.dirChooser.importPrism.title"))
            .directory(home_dir())
            .pick_folder(Some(window));
        cx.spawn_in(window, async move |this, cx| {
            let Some(dir) = pick.await else {
                return;
            };
            let name = dir.file_name().map(|s| s.to_string_lossy().into_owned());
            let shown = name.clone().unwrap_or_default();
            let fut = this.update(cx, |this, cx| {
                this.importing_prism = true;
                this.begin_import(name, cx);
                this.backend.import(ImportRequest::PrismInstance(dir))
            });
            let Ok(fut) = fut else { return };
            match fut.await {
                Ok(_) => notify::success(
                    &t!("notification.import.prism.success.title"),
                    &tf!("notification.import.prism.success.body", shown.as_str()),
                ),
                Err(f) if f.expected => {
                    notify::warn(&t!("notification.import.prism.failed.title"), &f.message)
                }
                Err(_) => notify::warn(
                    &t!("notification.import.prism.failed.title"),
                    &t!("notification.import.unexpected.body"),
                ),
            }
            let _ = this.update(cx, |this, cx| {
                this.importing_prism = false;
                this.end_import(cx);
            });
        })
        .detach();
    }

    /// Generate Hosting Manifest: `{ "available": [ installed pack URLs ] }`.
    pub fn save_hosting_manifest(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let urls: Vec<String> = self
            .backend
            .installed_packs()
            .into_iter()
            .filter(|p| !p.is_vanilla)
            .filter_map(|p| p.manifest_url)
            .collect();
        let pick = SaveDialog::new(t!("dialog.fileChooser.saveHostingManifest.title"))
            .directory(home_dir())
            .file_name("installable.json")
            .filter(FileFilter::json())
            .save(Some(window));
        cx.spawn_in(window, async move |this, cx| {
            let Some(dest) = pick.await else { return };
            let dest = with_extension(dest, "json");
            let text = hosting_manifest(&urls);
            let written = cx
                .background_spawn(async move { std::fs::write(&dest, text.as_bytes()) })
                .await;
            match written {
                Ok(()) => {
                    let _ = this.update(cx, |this, cx| {
                        this.hosting_saved = true;
                        cx.notify();
                    });
                    cx.background_executor().timer(Duration::from_secs(2)).await;
                    let _ = this.update(cx, |this, cx| {
                        this.hosting_saved = false;
                        cx.notify();
                    });
                }
                Err(e) => {
                    // Java's Logger.logError: logged and shown to the user.
                    let message = tf!("log.gameLibrary.hostingManifestFailed", e.to_string());
                    tracing::error!("{message}");
                    dialogs::error(cx, &message, None).await;
                }
            }
        })
        .detach();
    }

    fn entry_action(&mut self, entry: LibraryEntry, action: EntryAction, cx: &mut Context<Self>) {
        let status = Self::status_sink(cx);
        let done = Self::done(cx);
        match (action, entry.kind) {
            (EntryAction::Edit, _) => {
                if let Some(p) = &entry.pack {
                    crate::shell::navigate_gated(
                        cx,
                        ScreenRequest::with_payload(ScreenId::EDITOR, p.id.clone()),
                    );
                }
            }
            (EntryAction::Uninstall, EntryKind::ModpackInstalled) => {
                if let Some(p) = &entry.pack {
                    pack_actions::confirm_and_uninstall_modpack(cx, p, Some(status), Some(done));
                }
            }
            (EntryAction::Uninstall, EntryKind::VanillaInstalled) => {
                if let Some(id) = &entry.vanilla_id {
                    pack_actions::uninstall_vanilla(cx, id, status, done);
                }
            }
            (EntryAction::Install, EntryKind::ModpackAvailable) => {
                if let Some(p) = &entry.pack {
                    pack_actions::install_available(cx, p, status, done);
                }
            }
            (EntryAction::Install, EntryKind::VanillaAvailable) => {
                if let Some(id) = &entry.vanilla_id {
                    pack_actions::install_vanilla(cx, id, status, done);
                }
            }
            (EntryAction::Install, EntryKind::LoaderAvailable) => {
                if let Some(l) = &entry.loader {
                    pack_actions::install_loader(cx, l, &entry.display_name, status, done);
                }
            }
            _ => {}
        }
    }

    fn on_event(&mut self, event: &UiEvent, cx: &mut Context<Self>) {
        if self.disposed {
            return;
        }
        match event {
            UiEvent::Engine(EngineEvent::PacksChanged) | UiEvent::Lagged => {
                self.loaders.clear();
                self.rebuild(cx);
            }
            UiEvent::Engine(EngineEvent::PackChanged(id)) => {
                self.extras.remove(id);
                self.extras_pending.remove(id);
                self.rebuild(cx);
            }
            UiEvent::Engine(EngineEvent::AnnouncementsChanged | EngineEvent::OfflineChanged(_)) => {
                cx.notify()
            }
            _ => {}
        }
    }
}

impl Focusable for BrowseScreen {
    fn focus_handle(&self, _cx: &App) -> FocusHandle {
        self.focus.clone()
    }
}

impl Screen for BrowseScreen {
    fn id(&self) -> ScreenId {
        ScreenId::BROWSE
    }

    fn title(&self, _cx: &App) -> SharedString {
        t!("browse.navbar.title").into_owned().into()
    }

    fn help_topic(&self) -> HelpTopic {
        HelpTopic::BrowseLibrary
    }

    fn min_size(&self) -> Size<Pixels> {
        size(px(750.0), px(475.0))
    }

    fn setup(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.backend = backend(cx);
        self.backend.set_presence(&t!("discordRpc.screen.browsing"));
        if let Some(ev) = events(cx) {
            self.subs
                .push(cx.subscribe(&ev, |this, _, e: &UiEvent, cx| this.on_event(e, cx)));
        }
        self.subs.push(cx.subscribe_in(
            &self.search,
            window,
            |this, state, ev: &InputEvent, _w, cx| {
                if let InputEvent::Change = ev {
                    let text = state.read(cx).value().to_string();
                    let c = this.vm.set_search_query(Some(&text));
                    this.apply(c, cx);
                }
            },
        ));
        self.subs.push(cx.subscribe_in(
            &self.url,
            window,
            |this, _, ev: &InputEvent, window, cx| {
                if let InputEvent::PressEnter { .. } = ev {
                    this.add_from_url(window, cx);
                }
            },
        ));
        // Prewarm the mod-name search cache off the UI thread.
        let backend = self.backend.clone();
        let ids: Vec<PackId> = backend
            .installed_packs()
            .into_iter()
            .filter(|p| !p.is_vanilla)
            .map(|p| p.id)
            .collect();
        self.tasks.push(cx.spawn(async move |this, cx| {
            let mut found = Vec::new();
            for id in ids {
                if let Some(e) = backend.pack_extras(&id).await {
                    found.push((id, e));
                }
            }
            let roots: Vec<_> = found.iter().filter_map(|(_, e)| e.root.clone()).collect();
            cx.background_spawn(async move { browse_logic::prewarm_mod_names(&roots) })
                .await;
            let _ = this.update(cx, |this, _| {
                for (id, e) in found {
                    this.extras.insert(id, e);
                }
            });
        }));
    }

    fn after_show(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        window.focus(&self.focus, cx);
        // Gathers off the UI thread, so the chrome paints at once.
        self.rebuild(cx);
        match self.backend.available_packs_if_ready() {
            Some(list) => visuals::prefetch_available(cx, &list, &self.backend),
            None => {
                self.status = Some(t!("main.fetchLabel.loading").into_owned());
                let fut = self.backend.available_packs();
                self.tasks.push(cx.spawn(async move |this, cx| {
                    let list = fut.await;
                    let _ = this.update(cx, |this, cx| {
                        this.status = None;
                        visuals::prefetch_available(cx, &list, &this.backend);
                        this.rebuild(cx);
                    });
                }));
            }
        }
    }

    fn cleanup(&mut self, _cx: &mut Context<Self>) {
        self.disposed = true;
        self.subs.clear();
        self.tasks.clear();
        self.debounce = None;
        self.rebuild_task = None;
        self.loading_timer = None;
        self.cycle_sub = None;
        self.vm.dispose();
    }
}

fn weak_pick(
    this: WeakEntity<BrowseScreen>,
    f: impl Fn(&mut BrowseScreen, String, &mut Context<BrowseScreen>) + 'static,
) -> Pick {
    Rc::new(move |id, _w, cx| {
        let _ = this.update(cx, |this, cx| f(this, id, cx));
    })
}

impl BrowseScreen {
    fn render_filters(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let weak = cx.entity().downgrade();
        let opts = |ids: &[&str], prefix: &str| -> Vec<(String, String)> {
            ids.iter()
                .map(|id| (id.to_string(), label(prefix, Some(id))))
                .collect()
        };
        div()
            .flex_none()
            .h(u(48.0))
            .px(u(16.0))
            .flex()
            .items_center()
            .gap(u(10.0))
            .child(div().w(u(200.0)).child(SearchField::new(&self.search)))
            .child(field_label(t!("main.filter.typeLabel").into_owned(), cx))
            .child(combo(
                "browse-type",
                opts(&BROWSE_TYPES, TYPE_PREFIX),
                &self.kind(),
                150.0,
                t!("tooltip.main.typeFilter").into_owned(),
                weak_pick(weak.clone(), |this, id, cx| {
                    let c = this.vm.set_filter(FILTER_TYPE, FilterValue::Str(id));
                    this.apply(c, cx);
                }),
            ))
            .child(field_label(
                t!("browse.filter.statusLabel").into_owned(),
                cx,
            ))
            .child(combo(
                "browse-status",
                opts(&BROWSE_STATUSES, STATUS_PREFIX),
                &self.status_filter(),
                120.0,
                t!("browse.filter.statusLabel").into_owned(),
                weak_pick(weak.clone(), |this, id, cx| {
                    let c = this.vm.set_filter(FILTER_STATUS, FilterValue::Str(id));
                    this.apply(c, cx);
                }),
            ))
            .child(field_label(t!("main.filter.sortLabel").into_owned(), cx))
            .child(combo(
                "browse-sort",
                opts(&BROWSE_SORTS, SORT_PREFIX),
                self.vm.sort_key(),
                150.0,
                t!("tooltip.main.sortFilter").into_owned(),
                weak_pick(weak, |this, id, cx| {
                    let c = this.vm.set_sort_key(Some(&id));
                    this.apply(c, cx);
                }),
            ))
    }

    fn render_pagination(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let weak = cx.entity().downgrade();
        let (range, page) = pagination_labels(
            &self.bounds,
            self.vm.current_page(),
            "browse.pagination.empty",
        );
        let (w1, w2) = (weak.clone(), weak.clone());
        let page_no = self.vm.current_page();
        div()
            .flex_none()
            .h(u(48.0))
            .px(u(16.0))
            .flex()
            .items_center()
            .gap(u(8.0))
            .border_t_1()
            .border_color(hsla(t.color_divider))
            .bg(hsla(t.color_bg_soft))
            .child(
                div()
                    .type_role(TypeRole::BodySmall)
                    .text_color(hsla(t.color_text_muted))
                    .child(range),
            )
            .when(self.loading, |el| {
                el.child(LoadingIndicator::new("browse-loading").size(20.0))
                    .child(
                        div()
                            .type_role(TypeRole::LabelSmall)
                            .child(t!("browse.loading").into_owned()),
                    )
            })
            .children(self.status.clone().map(|s| {
                div()
                    .type_role(TypeRole::LabelSmall)
                    .text_color(hsla(t.color_text_muted))
                    .child(s)
            }))
            .child(div().flex_1())
            .child(
                IconButton::new(
                    "browse-prev",
                    Icon::Previous,
                    t!("main.pagination.prevPage").into_owned(),
                )
                .compact()
                .disabled(page_no <= 1)
                .on_click(move |_, cx| {
                    let _ = w1.update(cx, |this, cx| {
                        let c = this.vm.prev_page();
                        this.apply(c, cx);
                    });
                }),
            )
            .child(div().type_role(TypeRole::LabelLarge).child(page))
            .child(
                IconButton::new(
                    "browse-next",
                    Icon::Next,
                    t!("main.pagination.nextPage").into_owned(),
                )
                .compact()
                .disabled(page_no >= self.bounds.total_pages)
                .on_click(move |_, cx| {
                    let _ = w2.update(cx, |this, cx| {
                        let c = this.vm.next_page();
                        this.apply(c, cx);
                    });
                }),
            )
            .child(div().min_w(u(20.0)))
            .child(field_label(
                t!("main.pagination.perPageLabel").into_owned(),
                cx,
            ))
            .child(combo(
                "browse-page-size",
                BROWSE_PAGE_SIZES
                    .iter()
                    .map(|n| (n.to_string(), n.to_string()))
                    .collect(),
                &self.vm.page_size().to_string(),
                80.0,
                t!("tooltip.main.pageSize").into_owned(),
                weak_pick(weak, |this, id, cx| {
                    if let Ok(n) = id.parse::<usize>() {
                        let c = this.vm.set_page_size(n);
                        this.apply(c, cx);
                    }
                }),
            ))
    }

    fn render_card(&self, entry: &LibraryEntry, cx: &mut Context<Self>) -> PackCard {
        let s = self.backend.settings();
        let (logo, background) = match entry.kind {
            EntryKind::ModpackInstalled | EntryKind::ModpackAvailable => {
                let p = entry.pack.as_ref().expect("modpack entry has a pack");
                let src = sources_for(
                    cx,
                    &self.backend,
                    p,
                    s.show_pack_backgrounds,
                    s.image_cycle_shuffle,
                );
                if src.needs_cache {
                    visuals::ensure_cached(cx, &p.id, &self.backend);
                }
                (src.logo, src.background)
            }
            EntryKind::VanillaInstalled | EntryKind::VanillaAvailable => {
                (LogoSource::Brand(Brand::Vanilla), None)
            }
            EntryKind::LoaderAvailable => (
                entry
                    .loader
                    .as_ref()
                    .and_then(|l| Brand::of_loader(&l.loader_type))
                    .map_or(LogoSource::None, LogoSource::Brand),
                None,
            ),
        };
        let visual = visuals::card_visual(cx, &logo, &self.backend);
        let background = background.and_then(|b| visuals::background_source(cx, &b, &self.backend));
        let weak = cx.entity().downgrade();
        let edit_disabled = self.backend.announcements().disable_modpacks_edit;
        let buttons = entry
            .actions()
            .iter()
            .map(|&action| {
                let w = weak.clone();
                let e = entry.clone();
                let (label, style, grows) = match action {
                    EntryAction::Install => {
                        (t!("library.action.install"), ButtonStyle::Primary, true)
                    }
                    EntryAction::Uninstall => {
                        (t!("library.action.uninstall"), ButtonStyle::Danger, true)
                    }
                    EntryAction::Edit => (t!("library.action.edit"), ButtonStyle::Secondary, false),
                };
                CardButton {
                    label: label.into_owned().into(),
                    style,
                    disabled: edit_disabled && action != EntryAction::Edit,
                    grows,
                    tooltip: None,
                    on_click: Rc::new(move |_, cx| {
                        let e = e.clone();
                        let _ = w.update(cx, |this, cx| this.entry_action(e, action, cx));
                    }),
                }
            })
            .collect();
        let menu: Option<super::card::MenuBuilder> = match (entry.kind, &entry.pack) {
            (EntryKind::ModpackInstalled, Some(p)) => {
                let p = p.clone();
                let w = weak.clone();
                Some(Rc::new(move |menu, _, cx| {
                    // Read at open time: extras that arrived after this render still count.
                    let extras = w
                        .upgrade()
                        .and_then(|this| this.read(cx).extras.get(&p.id).cloned());
                    let (wd, ws) = (w.clone(), w.clone());
                    let done: Done = Rc::new(move |cx| {
                        let _ = wd.update(cx, |this, cx| this.rebuild(cx));
                    });
                    let status: StatusSink = Rc::new(move |text, cx| {
                        let _ = ws.update(cx, |this, cx| {
                            this.status = text;
                            cx.notify();
                        });
                    });
                    pack_actions::build_pack_context_menu(
                        menu,
                        &p,
                        extras.as_ref(),
                        Some(status),
                        done,
                        cx,
                    )
                }))
            }
            _ => None,
        };
        PackCard {
            id: SharedString::from(format!("lib-{}", entry.key())).into(),
            geometry: CardGeometry::LIBRARY,
            visual,
            background,
            badges: entry.badge().into_iter().collect(),
            name: entry.display_name.clone().into(),
            chips: entry.chips.clone(),
            status: entry.status_text.clone().into(),
            buttons,
            on_click: None,
            menu,
            focused: false,
        }
    }

    fn render_empty(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let search = self.vm.search_query().trim().to_string();
        let (icon, heading, sub) = browse_logic::empty_state(
            &search,
            &label(TYPE_PREFIX, Some(&self.kind())),
            &label(STATUS_PREFIX, Some(&self.status_filter())),
        );
        div()
            .w_full()
            .h(u(320.0))
            .flex()
            .flex_col()
            .items_center()
            .justify_center()
            .gap(u(12.0))
            .child(empty_state_badge(icon, cx))
            .child(
                div()
                    .type_role(TypeRole::TitleLarge)
                    .text_color(hsla(t.color_text))
                    .child(heading),
            )
            .child(
                div()
                    .max_w(u(480.0))
                    .text_center()
                    .type_role(TypeRole::BodyMedium)
                    .text_color(hsla(t.color_text_muted))
                    .child(sub),
            )
    }

    fn render_actions(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let weak = cx.entity().downgrade();
        let edit_disabled = self.backend.announcements().disable_modpacks_edit;
        let busy = self.url_busy.is_some();
        let (w1, w2, w3, w4) = (weak.clone(), weak.clone(), weak.clone(), weak.clone());
        div()
            .flex_none()
            .h(u(52.0))
            .px(u(16.0))
            .flex()
            .items_center()
            .gap(u(8.0))
            .border_t_1()
            .border_color(hsla(t.color_divider))
            .child(field_label(
                t!("browse.bottomBar.urlLabel").into_owned(),
                cx,
            ))
            .child(
                div()
                    .flex_1()
                    .min_w(u(200.0))
                    .max_w(u(480.0))
                    .child(Input::new(&self.url).disabled(edit_disabled || busy)),
            )
            .child(
                Button::new("browse-add-url")
                    .label(
                        self.url_busy
                            .clone()
                            .unwrap_or_else(|| t!("browse.bottomBar.urlAddBtn").into_owned()),
                    )
                    .primary()
                    .disabled(edit_disabled || busy)
                    .on_click(move |_: &ClickEvent, window, cx| {
                        let _ = w1.update(cx, |this, cx| this.add_from_url(window, cx));
                    }),
            )
            .child(
                Button::new("browse-import-zip")
                    .label(if self.importing_zip {
                        t!("browse.urlAdd.checking").into_owned()
                    } else {
                        t!("browse.bottomBar.importZipBtn").into_owned()
                    })
                    .outline()
                    .disabled(edit_disabled || self.importing_zip)
                    .on_click(move |_: &ClickEvent, window, cx| {
                        let _ = w2.update(cx, |this, cx| this.import_zip(window, cx));
                    }),
            )
            .child(
                Button::new("browse-import-prism")
                    .label(if self.importing_prism {
                        t!("browse.urlAdd.checking").into_owned()
                    } else {
                        t!("browse.bottomBar.importPrismBtn").into_owned()
                    })
                    .outline()
                    .disabled(edit_disabled || self.importing_prism)
                    .on_click(move |_: &ClickEvent, window, cx| {
                        let _ = w3.update(cx, |this, cx| this.import_prism(window, cx));
                    }),
            )
            .child(div().flex_1())
            .child(
                Button::new("browse-editor")
                    .label(t!("browse.bottomBar.editorBtn").into_owned())
                    .ghost()
                    .on_click(|_: &ClickEvent, _, cx| {
                        crate::shell::navigate_gated(cx, ScreenRequest::new(ScreenId::EDITOR));
                    }),
            )
            .child(
                Button::new("browse-hosting")
                    .label(if self.hosting_saved {
                        t!("browse.hostingManifest.saved").into_owned()
                    } else {
                        t!("browse.hostingManifest.label").into_owned()
                    })
                    .ghost()
                    .on_click(move |_: &ClickEvent, window, cx| {
                        let _ = w4.update(cx, |this, cx| this.save_hosting_manifest(window, cx));
                    }),
            )
            .child(
                Button::new("browse-return")
                    .label(t!("browse.bottomBar.returnBtn").into_owned())
                    .outline()
                    .on_click(|_: &ClickEvent, _, cx| {
                        crate::shell::navigate_gated(cx, ScreenRequest::new(ScreenId::HOME));
                    }),
            )
    }
}

impl Render for BrowseScreen {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let page: Vec<LibraryEntry> = self.entries[self.bounds.start..self.bounds.end].to_vec();
        let mut cards: Vec<gpui::AnyElement> = Vec::new();
        if let Some((name, status)) = &self.import {
            let gradient = visuals::card_visual(cx, &LogoSource::None, &self.backend).gradient;
            cards.push(
                ImportPlaceholder {
                    name: name.clone().into(),
                    status: status.clone().into(),
                    gradient,
                }
                .into_any_element(),
            );
        }
        if page.is_empty() && self.import.is_none() {
            cards.push(self.render_empty(cx).into_any_element());
        } else {
            for e in &page {
                cards.push(self.render_card(e, cx).into_any_element());
            }
        }
        let banner_text = Some(self.backend.announcements().modpacks_edit)
            .filter(|s| !s.trim().is_empty())
            .map(SharedString::from);
        let nav = NavBar::new(t!("browse.navbar.title").into_owned())
            .action(OfflineChip::new("browse-offline"))
            .action(
                IconButton::new(
                    "browse-help",
                    Icon::Help,
                    t!("tooltip.common.help").into_owned(),
                )
                .compact()
                .on_click(|_, cx| crate::shell::show_help(cx, HelpTopic::BrowseLibrary)),
            );
        div()
            .id("browse-root")
            .key_context(BROWSE_CONTEXT)
            .track_focus(&self.focus)
            .size_full()
            .flex()
            .flex_col()
            .bg(hsla(t.color_bg))
            .on_action(cx.listener(|this, _: &FocusSearch, window, cx| {
                this.search.update(cx, |s, cx| s.focus(window, cx));
            }))
            .child(nav)
            .child(AnnouncementBanner::new("browse-banner", banner_text))
            .child(self.render_filters(cx))
            .child(self.render_pagination(cx))
            .child(
                div()
                    .id("browse-grid")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .track_scroll(&self.scroll)
                    .pt(u(20.0))
                    .px(u(28.0))
                    .pb(u(28.0))
                    .flex()
                    .flex_wrap()
                    .content_start()
                    .justify_center()
                    .gap(u(16.0))
                    .children(cards),
            )
            .child(self.render_actions(cx))
    }
}

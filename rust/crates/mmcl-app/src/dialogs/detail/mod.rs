// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pack detail modal (Java `MCLauncherModpackDetailModal` + `ModpackContentBrowser`,
//! gui-editor-games §9), opened by a single click on a pack card.
//!
//! An in-window modal over the main window: a dimmed backdrop (click it, press Esc or the ✕
//! to close) and a card at 90 % of the window (600 x 480 to 1400 x 1000) with
//!
//! 1. the **hero**: pack art (background or gradient from the logo), Beta / Recently updated
//!    badges, the logo, name and last-played line, ◀ ▶ through the showcase images (and a click
//!    on the logo), and the close button;
//! 2. the **chips** (plays as, Vanilla, Minecraft, loader, pack version, minimum RAM, mod
//!    count) and the problems banner;
//! 3. the **tabs**: Overview (news, quick actions, stats), Problems, Content (worlds, servers,
//!    mods, screenshots, shader packs, resource packs), Activity (update log, crash history),
//!    Advanced (launch account, always verify, scan frequency, verify now, official launcher);
//! 4. the **action row**: Play and Website.
//!
//! Tab bodies are built when first shown; each section box collapses, and folder listings run
//! on the background executor the first time their section is open. The screenshot viewer and
//! the crash viewer open over the card.

pub mod content;
pub mod hooks;
pub mod model;

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::rc::Rc;

use gpui::{
    AnyElement, App, AppContext as _, ClipboardItem, Context, Entity, FontWeight, Image,
    ImageSource, InteractiveElement as _, IntoElement, ObjectFit, ParentElement, Render,
    ScrollHandle, SharedString, StatefulInteractiveElement as _, Styled, StyledImage as _, Window,
    div, img, point, prelude::FluentBuilder as _, px,
};
use gpui_component::WindowExt as _;
use gpui_component::checkbox::Checkbox;
use mmcl_engine::{
    AccountView, CrashActionResult, CrashView, Engine, ExportRequest, LaunchOptions, PackDetails,
    PackId, PackSummary, ScanFrequency, ServerView,
};
use mmcl_i18n::{t, tf};
use mmcl_ui::screens::home_browse::{self as hb, backend::backend, controls, cycle, visuals};
use mmcl_ui_kit::dialogs::{ButtonRole, DialogButton, DialogLevel, DialogSpec};
use mmcl_ui_kit::focus::nav_scope;
use mmcl_ui_kit::scale::{scaled_px, u};
use mmcl_ui_kit::{
    Icon, IconButton, ScreenId, ScreenRequest, Switch, ThemeExt as _, TooltipExt as _, hsla, notify,
};

use self::content::{ContentKind, Entry, ToggleError};
use self::hooks::{ModUpdate, ProblemView, UpdateStatus};
use self::model::{ExportChoice, QuickAction, Section, Tab, UpdateLogDisplay};
use super::widgets::{
    Pill, Tone, body, card, chip, hint, loading_row, mono, muted, section_box, tinted_chip, title,
};

/// A value that loads in the background.
#[derive(Debug, Clone, PartialEq)]
enum Load<T> {
    Loading,
    Ready(T),
}

/// The crash viewer over the card.
struct CrashViewer {
    entry: Entry,
    text: Load<Result<String, String>>,
    diagnosis: Load<Option<CrashView>>,
    copied: bool,
}

/// What is open over the card.
enum Overlay {
    Crash(Box<CrashViewer>),
    Image { path: PathBuf, copied: bool },
}

/// Per-mod state of the Modrinth update check.
#[derive(Debug, Clone, PartialEq, Eq)]
enum ModRowState {
    Checked(ModUpdate),
    Updating,
    Updated(String),
    UpdateFailed,
}

/// The modal's view.
pub struct DetailModal {
    engine: Engine,
    id: PackId,
    summary: PackSummary,
    details: Load<Option<PackDetails>>,
    accounts: Vec<AccountView>,
    account_override: Option<String>,
    always_verify: bool,
    scan_frequency: String,
    problems: Vec<ProblemView>,
    tab: Tab,
    expanded: HashSet<Section>,
    lists: HashMap<ContentKind, Load<Vec<Entry>>>,
    sizes: HashMap<PathBuf, u64>,
    mod_busy: HashSet<PathBuf>,
    mod_rows: HashMap<String, ModRowState>,
    mod_check: Option<String>,
    mod_checking: bool,
    mod_count: usize,
    hero_offset: i64,
    overlay: Option<Overlay>,
    news_marked: bool,
    scroll: ScrollHandle,
    _cycle: cycle::CycleSubscription,
}

impl DetailModal {
    fn new(engine: Engine, summary: PackSummary, cx: &mut Context<Self>) -> Self {
        let id = summary.id.clone();
        let account_override = engine.config().account_override_for_pack(id.as_str());
        let accounts = engine.accounts();
        let mut this = DetailModal {
            engine,
            id,
            summary,
            details: Load::Loading,
            accounts,
            account_override,
            always_verify: false,
            scan_frequency: String::new(),
            problems: Vec::new(),
            tab: Tab::Overview,
            expanded: HashSet::new(),
            lists: HashMap::new(),
            sizes: HashMap::new(),
            mod_busy: HashSet::new(),
            mod_rows: HashMap::new(),
            mod_check: None,
            mod_checking: false,
            mod_count: 0,
            hero_offset: 0,
            overlay: None,
            news_marked: false,
            scroll: ScrollHandle::new(),
            _cycle: cycle::subscribe(cx),
        };
        for tab in [Tab::Overview, Tab::Content, Tab::Activity, Tab::Advanced] {
            for s in model::sections(tab, true) {
                if s.starts_expanded() {
                    this.expanded.insert(s);
                }
            }
        }
        this.expanded.insert(Section::Problems);
        this.load_details(cx);
        this
    }

    fn load_details(&mut self, cx: &mut Context<Self>) {
        if self.summary.is_vanilla && !self.summary.installed {
            self.details = Load::Ready(None);
            return;
        }
        let e = self.engine.clone();
        let id = self.id.clone();
        let task = self.engine.spawn(async move { e.pack_details(&id).await });
        cx.spawn(async move |this, cx| {
            let details = match task.await {
                Ok(Ok(d)) => Some(d),
                Ok(Err(err)) => {
                    tracing::debug!("{}", tf!("log.detailModal.detailsFailed", err.message()));
                    None
                }
                Err(_) => None,
            };
            let root = details.as_ref().map(|d| d.root.clone());
            let _ = this.update(cx, |v, cx| {
                if let Some(d) = &details {
                    v.always_verify = d.always_verify_on_launch;
                    v.scan_frequency = model::scan_selected(d.scan_frequency.as_deref());
                    if v.account_override.is_none() {
                        v.account_override = d.account_override.clone();
                    }
                    if let Some(f) = hooks::read(cx).problems {
                        v.problems = f(&v.id, &d.root);
                    }
                }
                v.details = Load::Ready(details);
                cx.notify();
            });
            if let Some(root) = root {
                let count = cx
                    .background_spawn(async move { content::enabled_mod_count(&root) })
                    .await;
                let _ = this.update(cx, |v, cx| {
                    v.mod_count = count;
                    cx.notify();
                });
            }
        })
        .detach();
    }

    fn details(&self) -> Option<&PackDetails> {
        match &self.details {
            Load::Ready(Some(d)) => Some(d),
            _ => None,
        }
    }

    fn root(&self) -> Option<PathBuf> {
        self.details().map(|d| d.root.clone())
    }

    fn tabs(&self) -> Vec<Tab> {
        model::tabs(
            self.problems.len(),
            self.root().is_some(),
            model::has_settings_key(&self.id) && self.summary.installed,
        )
    }

    fn select_tab(&mut self, tab: Tab, cx: &mut Context<Self>) {
        self.tab = tab;
        self.scroll.set_offset(point(px(0.0), px(0.0)));
        let has_news = self.details().is_some_and(|d| !d.news.is_empty());
        for s in model::sections(tab, has_news) {
            if self.expanded.contains(&s) {
                self.ensure_section(s, cx);
            }
        }
        cx.notify();
    }

    fn toggle_section(&mut self, s: Section, cx: &mut Context<Self>) {
        if !self.expanded.remove(&s) {
            self.expanded.insert(s);
            self.ensure_section(s, cx);
        }
        cx.notify();
    }

    fn kind_of(s: Section) -> Option<ContentKind> {
        Some(match s {
            Section::Worlds => ContentKind::Worlds,
            Section::Mods => ContentKind::Mods,
            Section::Screenshots => ContentKind::Screenshots,
            Section::ShaderPacks => ContentKind::ShaderPacks,
            Section::ResourcePacks => ContentKind::ResourcePacks,
            Section::CrashHistory => ContentKind::CrashReports,
            _ => return None,
        })
    }

    fn ensure_section(&mut self, s: Section, cx: &mut Context<Self>) {
        if s == Section::News {
            self.mark_news_seen();
        }
        if let Some(kind) = Self::kind_of(s)
            && !self.lists.contains_key(&kind)
        {
            self.reload(kind, cx);
        }
    }

    fn mark_news_seen(&mut self) {
        if self.news_marked {
            return;
        }
        self.news_marked = true;
        let e = self.engine.clone();
        let id = self.id.clone();
        // Fire and forget: the write runs on the engine's runtime.
        drop(self.engine.spawn(async move { e.mark_news_seen(&id) }));
    }

    fn reload(&mut self, kind: ContentKind, cx: &mut Context<Self>) {
        let Some(root) = self.root() else {
            return;
        };
        self.lists.insert(kind, Load::Loading);
        cx.spawn(async move |this, cx| {
            let entries = cx
                .background_spawn(async move { content::scan(&root, kind) })
                .await;
            let dirs: Vec<PathBuf> = if kind == ContentKind::Worlds {
                entries
                    .iter()
                    .filter(|e| e.is_dir)
                    .map(|e| e.path.clone())
                    .collect()
            } else {
                Vec::new()
            };
            if kind == ContentKind::Mods {
                let count = entries
                    .iter()
                    .filter(|e| !content::is_disabled_jar(&e.name))
                    .count();
                let _ = this.update(cx, |v, _| v.mod_count = count);
            }
            let _ = this.update(cx, |v, cx| {
                v.lists.insert(kind, Load::Ready(entries));
                cx.notify();
            });
            for d in dirs {
                let path = d.clone();
                let size = cx
                    .background_spawn(async move { content::directory_size(&path) })
                    .await;
                if this
                    .update(cx, |v, cx| {
                        v.sizes.insert(d, size);
                        cx.notify();
                    })
                    .is_err()
                {
                    break;
                }
            }
        })
        .detach();
    }

    fn close(window: &mut Window, cx: &mut App) {
        window.close_dialog(cx);
    }

    // -----------------------------------------------------------------------------------------
    // Hero

    fn render_hero(&mut self, hero_h: f32, cx: &mut Context<Self>) -> AnyElement {
        let settings = self.engine.settings();
        let b = backend(cx);
        let images = b.pack_images(&self.id);
        let period = model::cycle_period(images.logos.len(), images.backgrounds.len());
        let tick = model::hero_tick(cycle::tick(cx), self.hero_offset, period);
        let sources = controls::pack_sources(
            &self.summary,
            &images,
            settings.show_pack_backgrounds,
            settings.image_cycle_shuffle,
            tick,
        );
        if sources.needs_cache {
            visuals::ensure_cached(cx, &self.id, &b);
        }
        let visual = visuals::card_visual(cx, &sources.logo, &b);
        let background = sources
            .background
            .as_ref()
            .and_then(|s| visuals::background_source(cx, s, &b));
        let t = cx.tokens();
        let name = model::display_name(&self.summary);
        let played =
            hb::home_logic::played_line(&self.summary, chrono::Utc::now().timestamp_millis());
        let badges = model::badges(&self.summary);
        let cycles = sources.cycles;
        let logo_step = cx.entity().downgrade();
        let prev = cx.entity().downgrade();
        let next = cx.entity().downgrade();
        div()
            .relative()
            .flex_none()
            .w_full()
            .h(u(hero_h))
            .overflow_hidden()
            .bg(hsla(t.md_surface_container_highest))
            .when_some(visual.gradient.clone(), |el, g| {
                el.child(
                    img(ImageSource::Image(g))
                        .absolute()
                        .size_full()
                        .object_fit(ObjectFit::Cover),
                )
            })
            .when_some(background, |el, bg| {
                el.child(img(bg).absolute().size_full().object_fit(ObjectFit::Cover))
            })
            .child(
                div()
                    .absolute()
                    .size_full()
                    .bg(gpui::hsla(0.0, 0.0, 0.0, 0.38)),
            )
            .child(
                div()
                    .absolute()
                    .top(u(14.0))
                    .left(u(20.0))
                    .flex()
                    .gap(u(6.0))
                    .children(badges.into_iter().map(|(text, warn)| {
                        if warn {
                            tinted_chip(
                                text,
                                hsla(t.md_warning_container),
                                hsla(t.md_on_warning_container),
                            )
                        } else {
                            tinted_chip(
                                text,
                                hsla(t.md_success_container),
                                hsla(t.md_on_success_container),
                            )
                        }
                    })),
            )
            .child(
                div()
                    .absolute()
                    .left(u(24.0))
                    .right(u(24.0))
                    .bottom(u(18.0))
                    .flex()
                    .items_end()
                    .gap(u(16.0))
                    .child(
                        div()
                            .id("detail-logo")
                            .flex_none()
                            .size(u(96.0))
                            .rounded(u(20.0))
                            .overflow_hidden()
                            .when(!visual.logo_transparent, |el| {
                                el.bg(hsla(t.md_surface_container)).p(u(6.0))
                            })
                            .when(cycles, |el| {
                                el.cursor_pointer().on_click(move |_, _, cx| {
                                    let _ = logo_step.update(cx, |v, cx| {
                                        v.hero_offset += 1;
                                        cx.notify();
                                    });
                                })
                            })
                            .child(
                                img(visual.logo.clone())
                                    .size_full()
                                    .object_fit(ObjectFit::Contain),
                            ),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .gap(u(4.0))
                            .child(
                                div()
                                    .text_size(u(28.0))
                                    .font_weight(FontWeight::BOLD)
                                    .text_color(gpui::white())
                                    .child(name),
                            )
                            .child(
                                div()
                                    .text_size(u(14.0))
                                    .text_color(gpui::hsla(0.0, 0.0, 1.0, 0.82))
                                    .child(played),
                            ),
                    )
                    .when(cycles, |el| {
                        el.child(
                            div()
                                .flex()
                                .gap(u(6.0))
                                .mb(u(10.0))
                                .child(
                                    IconButton::new(
                                        "detail-prev",
                                        Icon::Previous,
                                        t!("detailModal.hero.previous").into_owned(),
                                    )
                                    .on_click(move |_, cx| {
                                        let _ = prev.update(cx, |v, cx| {
                                            v.hero_offset -= 1;
                                            cx.notify();
                                        });
                                    }),
                                )
                                .child(
                                    IconButton::new(
                                        "detail-next",
                                        Icon::Next,
                                        t!("detailModal.hero.next").into_owned(),
                                    )
                                    .on_click(move |_, cx| {
                                        let _ = next.update(cx, |v, cx| {
                                            v.hero_offset += 1;
                                            cx.notify();
                                        });
                                    }),
                                ),
                        )
                    }),
            )
            .child(
                div().absolute().top(u(10.0)).right(u(10.0)).child(
                    IconButton::new(
                        "detail-close",
                        Icon::Close,
                        t!("dialog.button.close").into_owned(),
                    )
                    .on_click(Self::close),
                ),
            )
            .into_any_element()
    }

    // -----------------------------------------------------------------------------------------
    // Header, tabs, actions

    fn render_header(&self, cx: &mut Context<Self>) -> AnyElement {
        let chips = model::chips(
            &self.summary,
            self.details(),
            self.account_override.as_deref(),
            &self.accounts,
            self.mod_count,
        );
        let t = cx.tokens();
        let review = cx.entity().downgrade();
        div()
            .flex_none()
            .flex()
            .flex_col()
            .gap(u(10.0))
            .pt(u(14.0))
            .px(u(24.0))
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .gap(u(6.0))
                    .children(chips.into_iter().map(|c| chip(c, cx))),
            )
            .when(!self.problems.is_empty(), |el| {
                el.child(
                    div()
                        .flex()
                        .items_center()
                        .gap(u(10.0))
                        .px(u(12.0))
                        .py(u(6.0))
                        .rounded(u(10.0))
                        .bg(hsla(t.md_warning_container))
                        .text_color(hsla(t.md_on_warning_container))
                        .child(
                            Icon::Warning
                                .svg(18.0)
                                .text_color(hsla(t.md_on_warning_container)),
                        )
                        .child(div().flex_1().text_size(u(13.0)).child(tf!(
                            "detailModal.problems.banner",
                            self.problems.len() as i64
                        )))
                        .child(
                            Pill::new(
                                "detail-review",
                                t!("detailModal.problems.review").into_owned(),
                                Tone::Text,
                            )
                            .height(28.0)
                            .on_click(move |_, cx| {
                                let _ = review.update(cx, |v, cx| v.select_tab(Tab::Problems, cx));
                            }),
                        ),
                )
            })
            .into_any_element()
    }

    fn render_tabs(&self, cx: &mut Context<Self>) -> AnyElement {
        let t = cx.tokens();
        let problems = self.problems.len();
        div()
            .flex_none()
            .flex()
            .flex_wrap()
            .gap(u(4.0))
            .py(u(12.0))
            .px(u(24.0))
            .children(self.tabs().into_iter().enumerate().map(|(i, tab)| {
                let weak = cx.entity().downgrade();
                let mut pill = Pill::new(
                    ("detail-tab", i),
                    t!(tab.label_key()).into_owned(),
                    Tone::Outline,
                )
                .height(34.0)
                .selected(tab == self.tab)
                .on_click(move |_, cx| {
                    let _ = weak.update(cx, |v, cx| v.select_tab(tab, cx));
                });
                if tab == Tab::Problems {
                    pill = pill.leading(
                        div()
                            .px(u(6.0))
                            .rounded_full()
                            .bg(hsla(t.md_error))
                            .text_color(hsla(t.md_on_error))
                            .text_size(u(11.0))
                            .child(problems.to_string()),
                    );
                }
                pill
            }))
            .into_any_element()
    }

    fn render_actions(&self, cx: &mut Context<Self>) -> AnyElement {
        let t = cx.tokens();
        let gameplay_disabled = self.engine.announcements().disable_gameplay;
        let website = self
            .summary
            .website
            .clone()
            .or_else(|| hooks::read(cx).website.and_then(|f| f(&self.id)))
            .filter(|u| !u.trim().is_empty());
        let summary = self.summary.clone();
        div()
            .flex_none()
            .flex()
            .items_center()
            .gap(u(10.0))
            .pt(u(14.0))
            .px(u(24.0))
            .pb(u(18.0))
            .border_t_1()
            .border_color(hsla(t.md_outline_variant))
            .child(
                Pill::new(
                    "detail-play",
                    t!("common.button.play").into_owned(),
                    Tone::Primary,
                )
                .icon(Icon::Play)
                .height(42.0)
                .grow()
                .disabled(gameplay_disabled)
                .on_click(move |window, cx| {
                    Self::close(window, cx);
                    hb::start_play(cx, &summary);
                }),
            )
            .child({
                let pill = Pill::new(
                    "detail-website",
                    t!("common.button.website").into_owned(),
                    Tone::Tonal,
                )
                .height(42.0)
                .disabled(website.is_none());
                match website {
                    Some(url) => {
                        pill.on_click(move |_, cx| mmcl_ui_kit::open_external_url(cx, &url))
                    }
                    None => pill,
                }
            })
            .into_any_element()
    }

    // -----------------------------------------------------------------------------------------
    // Sections

    fn render_body(&mut self, window: &mut Window, cx: &mut Context<Self>) -> AnyElement {
        let has_news = self.details().is_some_and(|d| !d.news.is_empty());
        let sections = model::sections(self.tab, has_news);
        let mut children: Vec<AnyElement> = Vec::new();
        if matches!(self.details, Load::Loading) && self.tab != Tab::Overview {
            children.push(loading_row("detail-loading", cx).into_any_element());
        }
        for s in sections {
            if s == Section::News {
                self.mark_news_seen();
            }
            let expanded = self.expanded.contains(&s);
            let weak = cx.entity().downgrade();
            let content = if expanded {
                self.section_content(s, window, cx)
            } else {
                Vec::new()
            };
            children.push(
                section_box(
                    ("detail-section", s.ordinal()),
                    t!(s.heading_key()).into_owned(),
                    expanded,
                    move |_, cx| {
                        let _ = weak.update(cx, |v, cx| v.toggle_section(s, cx));
                    },
                    move || content,
                    cx,
                )
                .into_any_element(),
            );
        }
        if children.is_empty() {
            children.push(muted(t!("detailModal.tab.empty").into_owned(), cx).into_any_element());
        }
        div()
            .id("detail-body")
            .flex_1()
            .min_h_0()
            .overflow_y_scroll()
            .track_scroll(&self.scroll)
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(u(16.0))
                    .pt(u(4.0))
                    .px(u(24.0))
                    .pb(u(20.0))
                    .children(children),
            )
            .into_any_element()
    }

    fn section_content(
        &mut self,
        s: Section,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Vec<AnyElement> {
        match s {
            Section::News => self.news(cx),
            Section::QuickActions => self.quick_actions(cx),
            Section::Stats => self.stats(cx),
            Section::Worlds => self.file_list(ContentKind::Worlds, true, cx),
            Section::Servers => self.servers(cx),
            Section::Mods => self.mods(window, cx),
            Section::Screenshots => self.screenshots(cx),
            Section::ShaderPacks => self.file_list(ContentKind::ShaderPacks, false, cx),
            Section::ResourcePacks => self.file_list(ContentKind::ResourcePacks, false, cx),
            Section::UpdateLog => self.update_log(cx),
            Section::CrashHistory => self.crash_history(cx),
            Section::Advanced => self.advanced(cx),
            Section::Problems => self.problems_list(cx),
        }
    }

    fn news(&self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let Some(d) = self.details() else {
            return vec![loading_row("news-loading", cx).into_any_element()];
        };
        d.news
            .iter()
            .enumerate()
            .map(|(i, n)| {
                card(cx)
                    .child(title(n.title.clone().unwrap_or_default(), cx))
                    .when_some(n.date.clone(), |el, date| el.child(hint(date, cx)))
                    .when_some(n.body.clone(), |el, b| el.child(body(b, cx)))
                    .when_some(n.url.clone(), |el, url| {
                        el.child(
                            div().flex().child(
                                Pill::new(
                                    ("news-more", i),
                                    t!("modal.news.readMore").into_owned(),
                                    Tone::Text,
                                )
                                .icon(Icon::OpenExternal)
                                .height(30.0)
                                .on_click(move |_, cx| mmcl_ui_kit::open_external_url(cx, &url)),
                            ),
                        )
                    })
                    .into_any_element()
            })
            .collect()
    }

    fn quick_actions(&self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let root = self.root();
        let invite = hb::pack_actions::invite_link(&self.summary);
        let buttons = model::quick_actions(self.summary.is_vanilla)
            .into_iter()
            .enumerate()
            .map(|(i, action)| {
                let label = t!(action.label_key()).into_owned();
                let pill = Pill::new(("quick-action", i), label, Tone::Tonal).height(36.0);
                match action {
                    QuickAction::CopyInvite => match invite.clone() {
                        Some(link) => pill.on_click(move |_, cx| {
                            cx.write_to_clipboard(ClipboardItem::new_string(link.clone()));
                            notify::success(
                                &t!("notification.invite.copied.title"),
                                &t!("notification.invite.copied.body"),
                            );
                        }),
                        None => pill
                            .disabled(true)
                            .tooltip(t!("tooltip.modal.invite").into_owned()),
                    },
                    QuickAction::DesktopShortcut => {
                        let name = self.summary.name.clone();
                        let id = self.id.clone();
                        pill.on_click(move |_, cx| create_shortcut(&id, &name, cx))
                    }
                    QuickAction::Export => {
                        let weak = cx.entity().downgrade();
                        pill.disabled(root.is_none()).on_click(move |window, cx| {
                            let _ = weak.update(cx, |v, cx| v.smart_export(window, cx));
                        })
                    }
                    folder_action => {
                        let dir = match (&root, folder_action.folder()) {
                            (Some(r), Some("")) => Some(r.clone()),
                            (Some(r), Some(sub)) => Some(r.join(sub)),
                            _ => None,
                        };
                        pill.disabled(dir.is_none()).on_click(move |_, cx| {
                            if let Some(d) = &dir {
                                hb::pack_actions::open_pack_folder(cx, d);
                            }
                        })
                    }
                }
            });
        vec![
            div()
                .flex()
                .flex_wrap()
                .gap(u(8.0))
                .children(buttons)
                .into_any_element(),
        ]
    }

    fn stats(&self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let total = hb::home_logic::total_play_time(self.summary.total_play_time_ms);
        let t = cx.tokens();
        model::stats_rows(&self.summary, total)
            .into_iter()
            .map(|(label, value)| {
                div()
                    .flex()
                    .gap(u(12.0))
                    .child(
                        div()
                            .w(u(160.0))
                            .flex_none()
                            .text_size(u(13.0))
                            .text_color(hsla(t.color_text_muted))
                            .child(label),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .text_size(u(13.0))
                            .text_color(hsla(t.md_on_surface))
                            .child(value),
                    )
                    .into_any_element()
            })
            .collect()
    }

    fn list_state(
        &self,
        kind: ContentKind,
        cx: &mut Context<Self>,
    ) -> Result<Vec<Entry>, AnyElement> {
        if self.root().is_none() {
            return Err(match self.details {
                Load::Loading => {
                    loading_row(("list-loading", kind as usize), cx).into_any_element()
                }
                Load::Ready(_) => {
                    muted(t!("detailModal.content.empty").into_owned(), cx).into_any_element()
                }
            });
        }
        match self.lists.get(&kind) {
            None | Some(Load::Loading) => {
                Err(loading_row(("list-loading", kind as usize), cx).into_any_element())
            }
            Some(Load::Ready(v)) if v.is_empty() => {
                Err(muted(t!("detailModal.content.empty").into_owned(), cx).into_any_element())
            }
            Some(Load::Ready(v)) => Ok(v.clone()),
        }
    }

    fn meta_line(&self, e: &Entry) -> String {
        let modified = tf!(
            "detailModal.content.lastModified",
            model::format_time(e.modified_ms)
        );
        let size = e.size.or_else(|| self.sizes.get(&e.path).copied());
        match size {
            Some(s) => format!("{} \u{00B7} {modified}", mmcl_util::units::bytes(s as i64)),
            None => modified,
        }
    }

    fn file_list(
        &self,
        kind: ContentKind,
        deletable: bool,
        cx: &mut Context<Self>,
    ) -> Vec<AnyElement> {
        let entries = match self.list_state(kind, cx) {
            Ok(v) => v,
            Err(el) => return vec![el],
        };
        entries
            .into_iter()
            .enumerate()
            .map(|(i, e)| {
                let open = e.clone();
                let del = e.clone();
                let weak = cx.entity().downgrade();
                let row = content_row(e.name.clone(), self.meta_line(&e), cx).child(
                    Pill::new(
                        ("open", kind as usize * 10_000 + i),
                        t!("detailModal.content.openFolder").into_owned(),
                        Tone::Tonal,
                    )
                    .height(28.0)
                    .on_click(move |_, cx| open_entry(&open, cx)),
                );
                if deletable {
                    row.child(
                        Pill::new(
                            ("delete", kind as usize * 10_000 + i),
                            t!("detailModal.content.delete").into_owned(),
                            Tone::Danger,
                        )
                        .height(28.0)
                        .on_click(move |_, cx| {
                            let _ =
                                weak.update(cx, |v, cx| v.confirm_delete(kind, del.clone(), cx));
                        }),
                    )
                    .into_any_element()
                } else {
                    row.into_any_element()
                }
            })
            .collect()
    }

    fn confirm_delete(&mut self, kind: ContentKind, e: Entry, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let q = mmcl_ui::Question::new(
                t!("detailModal.content.deleteConfirm.title"),
                tf!("detailModal.content.deleteConfirm.body", e.name.as_str()),
                e.path.display().to_string(),
                t!("detailModal.content.delete"),
                t!("dialog.button.cancel"),
            );
            if mmcl_ui::dialogs::ask(cx, &q, None).await != mmcl_ui::Answer::Primary {
                return;
            }
            let path = e.path.clone();
            let result = cx
                .background_spawn(async move { content::delete_entry(&path) })
                .await;
            match result {
                Ok(()) => notify::success(&e.name, &t!("notification.content.fileDeleted.body")),
                Err(err) => {
                    tracing::warn!(
                        "{}",
                        tf!(
                            "log.detailModal.deleteFailed",
                            e.path.display().to_string(),
                            err.to_string()
                        )
                    );
                    notify::warn(
                        &t!("notification.content.deleteFailed.title"),
                        &tf!("notification.content.deleteFailed.body", e.name.as_str()),
                    );
                }
            }
            let _ = this.update(cx, |v, cx| v.reload(kind, cx));
        })
        .detach();
    }

    fn servers(&self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let Some(d) = self.details() else {
            return vec![loading_row("servers-loading", cx).into_any_element()];
        };
        let rows = model::server_rows(d);
        if rows.is_empty() {
            return vec![
                muted(t!("detailModal.content.empty").into_owned(), cx).into_any_element(),
            ];
        }
        let t = cx.tokens();
        rows.into_iter()
            .enumerate()
            .map(|(i, (s, is_default))| {
                let address = model::server_address(&s);
                let copy = address.clone();
                let engine = self.engine.clone();
                let id = self.id.clone();
                let join = address.clone();
                div()
                    .flex()
                    .items_center()
                    .gap(u(10.0))
                    .py(u(4.0))
                    .when(is_default, |el| {
                        el.child(tinted_chip(
                            t!("detailModal.servers.packDefaultLabel").into_owned(),
                            hsla(t.md_secondary_container),
                            hsla(t.md_on_secondary_container),
                        ))
                    })
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .text_size(u(14.0))
                            .font_weight(FontWeight::MEDIUM)
                            .text_color(hsla(t.md_on_surface))
                            .child(server_name(&s)),
                    )
                    .child(muted(address, cx))
                    .child(
                        Pill::new(
                            ("server-connect", i),
                            t!("detailModal.servers.connectBtn").into_owned(),
                            Tone::Primary,
                        )
                        .height(28.0)
                        .disabled(self.engine.announcements().disable_gameplay)
                        .on_click(move |window, cx| {
                            Self::close(window, cx);
                            let options = LaunchOptions {
                                quick_join: Some(join.clone()),
                                ..Default::default()
                            };
                            super::account_prompt::start_play(
                                engine.clone(),
                                id.clone(),
                                options,
                                cx,
                            );
                        }),
                    )
                    .child(
                        Pill::new(
                            ("server-copy", i),
                            t!("detailModal.servers.copyBtn").into_owned(),
                            Tone::Tonal,
                        )
                        .icon(Icon::Copy)
                        .height(28.0)
                        .on_click(move |_, cx| {
                            cx.write_to_clipboard(ClipboardItem::new_string(copy.clone()));
                            notify::success(&t!("detailModal.servers.copiedTitle"), &copy);
                        }),
                    )
                    .into_any_element()
            })
            .collect()
    }

    fn mods(&mut self, window: &mut Window, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let _ = window;
        let h = hooks::read(cx);
        let mods_dir = self.root().map(|r| r.join("mods"));
        let check = cx.entity().downgrade();
        let mut header = div()
            .flex()
            .items_center()
            .gap(u(10.0))
            .child({
                let pill = Pill::new(
                    "mods-check",
                    t!("detailModal.mods.checkUpdates").into_owned(),
                    Tone::Tonal,
                )
                .height(28.0)
                .disabled(self.mod_checking || h.check_updates.is_none() || mods_dir.is_none());
                if h.check_updates.is_none() {
                    pill.tooltip(t!("library.action.unavailable").into_owned())
                } else {
                    pill.on_click(move |_, cx| {
                        let _ = check.update(cx, |v, cx| v.check_mod_updates(cx));
                    })
                }
            })
            .when_some(self.mod_check.clone(), |el, s| el.child(muted(s, cx)));
        if !self.summary.is_vanilla {
            let add = h.add_mod.clone();
            let id = self.id.clone();
            let dir = mods_dir.clone();
            header = header.child(div().flex_1()).child({
                let pill = Pill::new(
                    "mods-add",
                    t!("detailModal.mods.addMod").into_owned(),
                    Tone::Tonal,
                )
                .height(28.0)
                .disabled(add.is_none() || dir.is_none());
                match (add, dir) {
                    (Some(f), Some(d)) => {
                        pill.on_click(move |window, cx| f(id.clone(), d.clone(), window, cx))
                    }
                    _ => pill.tooltip(t!("library.action.unavailable").into_owned()),
                }
            });
        }
        let mut out = vec![header.into_any_element()];
        let entries = match self.list_state(ContentKind::Mods, cx) {
            Ok(v) => v,
            Err(el) => {
                out.push(el);
                return out;
            }
        };
        let t = cx.tokens();
        for (i, e) in entries.into_iter().enumerate() {
            let disabled = content::is_disabled_jar(&e.name);
            let busy = self.mod_busy.contains(&e.path);
            let state = self.mod_rows.get(&e.name).cloned();
            let update_text = match &state {
                Some(ModRowState::Checked(u)) => Some(match u.status {
                    UpdateStatus::UpdateAvailable => tf!(
                        "detailModal.mods.updateAvailable",
                        u.current_version.clone().unwrap_or_else(|| "?".into()),
                        u.latest_version.clone().unwrap_or_else(|| "?".into())
                    ),
                    UpdateStatus::UpToDate => t!("detailModal.mods.upToDate").into_owned(),
                    UpdateStatus::NotOnModrinth => {
                        t!("detailModal.mods.notOnModrinth").into_owned()
                    }
                }),
                Some(ModRowState::Updating) => Some(t!("detailModal.mods.updating").into_owned()),
                Some(ModRowState::Updated(v)) => Some(tf!("detailModal.mods.updated", v.as_str())),
                Some(ModRowState::UpdateFailed) => {
                    Some(t!("detailModal.mods.updateFailed").into_owned())
                }
                None => None,
            };
            let can_update = matches!(&state, Some(ModRowState::Checked(u))
                if u.status == UpdateStatus::UpdateAvailable && u.latest_download_url.is_some())
                && h.apply_update.is_some();
            // After an update the row still names the old file: its toggle would miss.
            let toggle_off = busy
                || matches!(
                    state,
                    Some(ModRowState::Updated(_)) | Some(ModRowState::Updating)
                );
            let meta = if disabled {
                t!("detailModal.mods.disabled").into_owned()
            } else {
                e.size
                    .map(|s| mmcl_util::units::bytes(s as i64))
                    .unwrap_or_default()
            };
            let toggle = cx.entity().downgrade();
            let upd = cx.entity().downgrade();
            let path = e.path.clone();
            let name_for_update = e.name.clone();
            out.push(
                div()
                    .flex()
                    .items_center()
                    .gap(u(10.0))
                    .py(u(4.0))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .text_size(u(14.0))
                            .font_weight(FontWeight::MEDIUM)
                            .text_color(if disabled {
                                hsla(t.color_text_muted)
                            } else {
                                hsla(t.md_on_surface)
                            })
                            .child(content::mod_display_name(&e.name).to_string()),
                    )
                    .child(muted(meta, cx))
                    .when_some(update_text, |el, s| el.child(muted(s, cx)))
                    .when(can_update, |el| {
                        el.child(
                            Pill::new(
                                ("mod-update", i),
                                t!("detailModal.mods.update").into_owned(),
                                Tone::Primary,
                            )
                            .height(28.0)
                            .on_click(move |_, cx| {
                                let _ = upd.update(cx, |v, cx| {
                                    v.apply_mod_update(name_for_update.clone(), cx)
                                });
                            }),
                        )
                    })
                    .child(
                        Pill::new(
                            ("mod-toggle", i),
                            if disabled {
                                t!("detailModal.mods.enable")
                            } else {
                                t!("detailModal.mods.disable")
                            }
                            .into_owned(),
                            Tone::Tonal,
                        )
                        .height(28.0)
                        .disabled(toggle_off)
                        .on_click(move |_, cx| {
                            let _ = toggle.update(cx, |v, cx| v.toggle_mod(path.clone(), cx));
                        }),
                    )
                    .into_any_element(),
            );
        }
        out
    }

    fn toggle_mod(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        if !self.mod_busy.insert(path.clone()) {
            return;
        }
        cx.notify();
        cx.spawn(async move |this, cx| {
            let p = path.clone();
            let result = cx
                .background_spawn(async move { content::toggle_mod(&p) })
                .await;
            match result {
                Ok(_) => {}
                Err(ToggleError::TargetExists(name)) => notify::warn(
                    &t!("notification.content.toggleModConflict.title"),
                    &tf!("notification.content.toggleModConflict.body", name),
                ),
                Err(ToggleError::Io(e)) => {
                    let name = path
                        .file_name()
                        .map(|n| n.to_string_lossy().into_owned())
                        .unwrap_or_default();
                    tracing::warn!(
                        "{}",
                        tf!("log.contentBrowser.modToggleFailed", name, e.to_string())
                    );
                    notify::warn(
                        &t!("notification.content.toggleModFailed.title"),
                        &t!("notification.content.toggleModFailed.body"),
                    );
                }
            }
            let _ = this.update(cx, |v, cx| {
                v.mod_busy.remove(&path);
                v.reload(ContentKind::Mods, cx);
            });
        })
        .detach();
    }

    fn check_mod_updates(&mut self, cx: &mut Context<Self>) {
        let (Some(f), Some(root)) = (hooks::read(cx).check_updates, self.root()) else {
            return;
        };
        self.mod_checking = true;
        self.mod_check = Some(t!("detailModal.mods.checking").into_owned());
        cx.notify();
        let task = f(root.join("mods"), cx);
        cx.spawn(async move |this, cx| {
            let result = task.await;
            let _ = this.update(cx, |v, cx| {
                v.mod_checking = false;
                match result {
                    Ok(updates) => {
                        let on_modrinth = updates
                            .iter()
                            .filter(|u| u.status != UpdateStatus::NotOnModrinth)
                            .count();
                        let available = updates
                            .iter()
                            .filter(|u| u.status == UpdateStatus::UpdateAvailable)
                            .count();
                        v.mod_check = Some(tf!(
                            "detailModal.mods.checkSummary",
                            on_modrinth as i64,
                            updates.len() as i64,
                            available as i64
                        ));
                        for u in updates {
                            v.mod_rows
                                .insert(u.file_name.clone(), ModRowState::Checked(u));
                        }
                    }
                    Err(e) => {
                        tracing::warn!("{}", tf!("log.contentBrowser.modUpdateScanThrew", e));
                        v.mod_check = Some(t!("detailModal.mods.checkFailed").into_owned());
                    }
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn apply_mod_update(&mut self, old: String, cx: &mut Context<Self>) {
        let (Some(f), Some(root)) = (hooks::read(cx).apply_update, self.root()) else {
            return;
        };
        let Some(ModRowState::Checked(u)) = self.mod_rows.get(&old).cloned() else {
            return;
        };
        let Some(url) = u.latest_download_url.clone() else {
            return;
        };
        self.mod_rows.insert(old.clone(), ModRowState::Updating);
        cx.notify();
        let task = f(root.join("mods"), old.clone(), url, cx);
        cx.spawn(async move |this, cx| {
            let result = task.await;
            let _ = this.update(cx, |v, cx| {
                match result {
                    Ok(_) => {
                        let version = u.latest_version.clone().unwrap_or_else(|| "?".into());
                        v.mod_rows
                            .insert(old.clone(), ModRowState::Updated(version));
                    }
                    Err(e) => {
                        tracing::warn!(
                            "{}",
                            tf!("log.contentBrowser.modUpdateFailed", old.as_str(), e)
                        );
                        notify::warn(
                            &t!("notification.content.modUpdateFailed.title"),
                            &tf!("notification.content.modUpdateFailed.body", old.as_str()),
                        );
                        v.mod_rows.insert(old.clone(), ModRowState::UpdateFailed);
                    }
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn screenshots(&self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let entries = match self.list_state(ContentKind::Screenshots, cx) {
            Ok(v) => v,
            Err(el) => return vec![el],
        };
        let t = cx.tokens();
        let tiles = entries.into_iter().enumerate().map(|(i, e)| {
            let weak = cx.entity().downgrade();
            let path = e.path.clone();
            div()
                .id(("shot", i))
                .size(u(model::THUMB_SIZE))
                .rounded(u(10.0))
                .overflow_hidden()
                .bg(hsla(t.md_surface_container_high))
                .cursor_pointer()
                .launcher_tooltip(e.name.clone())
                .child(img(e.path.clone()).size_full().object_fit(ObjectFit::Cover))
                .on_click(move |_, _, cx| {
                    let _ = weak.update(cx, |v, cx| {
                        v.overlay = Some(Overlay::Image {
                            path: path.clone(),
                            copied: false,
                        });
                        cx.notify();
                    });
                })
        });
        vec![
            div()
                .flex()
                .flex_wrap()
                .gap(u(model::THUMB_GAP))
                .children(tiles)
                .into_any_element(),
        ]
    }

    fn update_log(&self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let Some(d) = self.details() else {
            return vec![match self.details {
                Load::Loading => loading_row("log-loading", cx).into_any_element(),
                Load::Ready(_) => muted(
                    model::update_log(&[], self.summary.is_vanilla).empty_text(),
                    cx,
                )
                .into_any_element(),
            }];
        };
        let t = cx.tokens();
        match model::update_log(&d.update_log, self.summary.is_vanilla) {
            UpdateLogDisplay::Empty(text) => vec![muted(text, cx).into_any_element()],
            UpdateLogDisplay::Entries(entries, more) => {
                let mut out: Vec<AnyElement> = entries
                    .into_iter()
                    .map(|e| {
                        div()
                            .flex()
                            .gap(u(10.0))
                            .child(
                                div()
                                    .min_w(u(96.0))
                                    .text_size(u(13.0))
                                    .text_color(hsla(t.color_text_muted))
                                    .child(e.when),
                            )
                            .child(
                                div()
                                    .text_size(u(13.0))
                                    .font_weight(FontWeight::MEDIUM)
                                    .text_color(hsla(t.md_on_surface))
                                    .child(tf!("detailModal.updateLog.version", e.version)),
                            )
                            .into_any_element()
                    })
                    .collect();
                if let Some(m) = more {
                    out.push(muted(m, cx).into_any_element());
                }
                out
            }
        }
    }

    fn crash_history(&self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let entries = match self.list_state(ContentKind::CrashReports, cx) {
            Ok(v) => v,
            Err(el) => return vec![el],
        };
        entries
            .into_iter()
            .enumerate()
            .map(|(i, e)| {
                let weak = cx.entity().downgrade();
                let view = e.clone();
                let open = e.clone();
                content_row(
                    e.name.clone(),
                    tf!(
                        "detailModal.content.lastModified",
                        model::format_time(e.modified_ms)
                    ),
                    cx,
                )
                .child(
                    Pill::new(
                        ("crash-view", i),
                        t!("detailModal.crash.viewBtn").into_owned(),
                        Tone::Tonal,
                    )
                    .height(28.0)
                    .on_click(move |_, cx| {
                        let _ = weak.update(cx, |v, cx| v.open_crash(view.clone(), cx));
                    }),
                )
                .child(
                    Pill::new(
                        ("crash-open", i),
                        t!("detailModal.content.openFolder").into_owned(),
                        Tone::Tonal,
                    )
                    .height(28.0)
                    .on_click(move |_, cx| open_entry(&open, cx)),
                )
                .into_any_element()
            })
            .collect()
    }

    fn open_crash(&mut self, entry: Entry, cx: &mut Context<Self>) {
        let path = entry.path.clone();
        self.overlay = Some(Overlay::Crash(Box::new(CrashViewer {
            entry,
            text: Load::Loading,
            diagnosis: Load::Loading,
            copied: false,
        })));
        cx.notify();
        let engine = self.engine.clone();
        let id = self.id.clone();
        cx.spawn(async move |this, cx| {
            let p = path.clone();
            let text = cx
                .background_spawn(async move { content::read_report(&p) })
                .await
                .map_err(|e| {
                    tf!(
                        "detailModal.crash.viewer.readFailed",
                        path.display().to_string(),
                        e.to_string()
                    )
                });
            let report = text.clone().ok();
            let _ = this.update(cx, |v, cx| {
                if let Some(Overlay::Crash(c)) = &mut v.overlay
                    && c.entry.path == path
                {
                    c.text = Load::Ready(text);
                }
                cx.notify();
            });
            let diagnosis = match report {
                Some(r) => {
                    let e = engine.clone();
                    let task = engine.spawn(async move { e.diagnose_crash(&r, Some(&id), 1) });
                    task.await.ok()
                }
                None => None,
            };
            let _ = this.update(cx, |v, cx| {
                if let Some(Overlay::Crash(c)) = &mut v.overlay
                    && c.entry.path == path
                {
                    c.diagnosis = Load::Ready(diagnosis);
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn advanced(&self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let mut out: Vec<AnyElement> =
            vec![hint(t!("modal.advanced.hint").into_owned(), cx).into_any_element()];
        // Launch as.
        let weak = cx.entity().downgrade();
        let options = model::account_options(&self.accounts, self.account_override.as_deref());
        let selected = self.account_override.clone().unwrap_or_default();
        out.push(body(t!("modal.advanced.account.label").into_owned(), cx).into_any_element());
        out.push(
            div()
                .flex()
                .child(controls::combo(
                    "detail-account",
                    options,
                    &selected,
                    280.0,
                    t!("modal.advanced.account.label").into_owned(),
                    Rc::new(move |picked: String, _, cx| {
                        let _ = weak.update(cx, |v, cx| v.set_account(picked, cx));
                    }),
                ))
                .into_any_element(),
        );
        out.push(hint(t!("modal.advanced.account.hint").into_owned(), cx).into_any_element());
        let manifest = self
            .summary
            .manifest_url
            .clone()
            .filter(|u| !u.trim().is_empty());
        if manifest.is_some() {
            let weak = cx.entity().downgrade();
            out.push(
                Switch::new("detail-always-verify")
                    .label(t!("modal.advanced.alwaysVerify").into_owned())
                    .checked(self.always_verify)
                    .on_change(move |v, _, cx| {
                        let _ = weak.update(cx, |this, cx| this.set_always_verify(v, cx));
                    })
                    .into_any_element(),
            );
            out.push(
                hint(t!("modal.advanced.alwaysVerify.hint").into_owned(), cx).into_any_element(),
            );
            let weak = cx.entity().downgrade();
            out.push(body(t!("modal.advanced.scanFreq.label").into_owned(), cx).into_any_element());
            out.push(
                div()
                    .flex()
                    .child(controls::combo(
                        "detail-scan-freq",
                        model::scan_options(),
                        &self.scan_frequency,
                        280.0,
                        t!("modal.advanced.scanFreq.label").into_owned(),
                        Rc::new(move |picked: String, _, cx| {
                            let _ = weak.update(cx, |v, cx| v.set_scan_frequency(picked, cx));
                        }),
                    ))
                    .into_any_element(),
            );
            out.push(hint(t!("modal.advanced.scanFreq.hint").into_owned(), cx).into_any_element());
            let summary = self.summary.clone();
            out.push(
                div()
                    .flex()
                    .child(
                        Pill::new(
                            "detail-verify",
                            t!("modal.advanced.verifyNow").into_owned(),
                            Tone::Tonal,
                        )
                        .height(32.0)
                        .on_click(move |window, cx| {
                            Self::close(window, cx);
                            verify_now(&summary, cx);
                        }),
                    )
                    .into_any_element(),
            );
            out.push(hint(t!("modal.advanced.verifyNow.hint").into_owned(), cx).into_any_element());
        }
        if let Some(root) = self.root() {
            let summary = self.summary.clone();
            let (label, hint_key, remove) = if self.engine.has_official_export(&self.id) {
                (
                    "officialExport.remove.menuItem",
                    "modal.advanced.removeOfficial.hint",
                    true,
                )
            } else {
                (
                    "officialExport.menuItem",
                    "modal.advanced.addOfficial.hint",
                    false,
                )
            };
            out.push(
                div()
                    .flex()
                    .child(
                        Pill::new("detail-official", t!(label).into_owned(), Tone::Tonal)
                            .height(32.0)
                            .on_click(move |window, cx| {
                                Self::close(window, cx);
                                if remove {
                                    hb::pack_actions::official_remove(
                                        cx,
                                        &summary,
                                        Some(root.clone()),
                                    );
                                } else {
                                    hb::pack_actions::official_export(cx, &summary);
                                }
                            }),
                    )
                    .into_any_element(),
            );
            out.push(hint(t!(hint_key).into_owned(), cx).into_any_element());
        }
        out
    }

    fn set_account(&mut self, picked: String, cx: &mut Context<Self>) {
        let uuid = (!picked.is_empty()).then_some(picked);
        match self
            .engine
            .set_pack_account_override(&self.id, uuid.as_deref())
        {
            Ok(()) => self.account_override = uuid,
            Err(e) => notify::warn(&t!("dialog.alert.warning.title"), &e.message()),
        }
        cx.notify();
    }

    fn set_always_verify(&mut self, value: bool, cx: &mut Context<Self>) {
        match self.engine.set_pack_always_verify(&self.id, value) {
            Ok(()) => self.always_verify = value,
            Err(e) => notify::warn(&t!("dialog.alert.warning.title"), &e.message()),
        }
        cx.notify();
    }

    fn set_scan_frequency(&mut self, picked: String, cx: &mut Context<Self>) {
        let freq = ScanFrequency::from_name(&picked);
        match self.engine.set_pack_scan_frequency(&self.id, freq) {
            Ok(()) => self.scan_frequency = freq.map(|f| f.name().to_string()).unwrap_or_default(),
            Err(e) => notify::warn(&t!("dialog.alert.warning.title"), &e.message()),
        }
        cx.notify();
    }

    fn problems_list(&self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        self.problems
            .iter()
            .map(|p| {
                card(cx)
                    .child(title(p.title.clone(), cx))
                    .child(hint(p.detail.clone(), cx))
                    .into_any_element()
            })
            .collect()
    }

    // -----------------------------------------------------------------------------------------
    // Export

    fn smart_export(&mut self, _window: &mut Window, cx: &mut Context<Self>) {
        let Some(d) = self.details().cloned() else {
            return;
        };
        let summary = self.summary.clone();
        let engine = self.engine.clone();
        let mode = d.export_mode;
        cx.spawn(async move |_, cx| {
            let choices = model::export_choices(mode);
            let mut buttons: Vec<DialogButton> = choices
                .iter()
                .enumerate()
                .map(|(i, c)| {
                    DialogButton::new(
                        t!(c.label_key()).into_owned(),
                        if i == 0 {
                            ButtonRole::Primary
                        } else {
                            ButtonRole::Secondary
                        },
                    )
                })
                .collect();
            buttons.push(DialogButton::new(
                t!("dialog.button.cancel").into_owned(),
                ButtonRole::Cancel,
            ));
            let spec = DialogSpec {
                level: DialogLevel::Question,
                title: t!("detailModal.exportPack.label").into_owned().into(),
                header: Some(model::display_name(&summary).into()),
                body: model::export_body(mode).into(),
                multiline: true,
                buttons,
            };
            let picked = mmcl_ui_kit::dialogs::show(cx, spec, None).await;
            let Some(choice) = picked.and_then(|i| choices.get(i).copied()) else {
                return;
            };
            match choice {
                ExportChoice::ShareUrl => share_url(&summary, cx),
                ExportChoice::ShareManifest => save_manifest(&engine, &summary, cx).await,
                ExportChoice::Zip => export_zip(&engine, &summary, d.root.clone(), cx).await,
            }
        })
        .detach();
    }

    // -----------------------------------------------------------------------------------------
    // Overlays

    fn render_overlay(&self, cx: &mut Context<Self>) -> Option<AnyElement> {
        let overlay = self.overlay.as_ref()?;
        let t = cx.tokens();
        let close = cx.entity().downgrade();
        let close_button = move |id: &'static str, key: &'static str| {
            let close = close.clone();
            Pill::new(id, t!(key).into_owned(), Tone::Tonal).on_click(move |_, cx| {
                let _ = close.update(cx, |v, cx| {
                    v.overlay = None;
                    cx.notify();
                });
            })
        };
        let inner: AnyElement = match overlay {
            Overlay::Image { path, copied } => {
                let copy_path = path.clone();
                let folder = path.clone();
                let weak = cx.entity().downgrade();
                div()
                    .size_full()
                    .flex()
                    .flex_col()
                    .gap(u(10.0))
                    .child(
                        div()
                            .flex_1()
                            .min_h_0()
                            .child(img(path.clone()).size_full().object_fit(ObjectFit::Contain)),
                    )
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(u(8.0))
                            .child(muted(
                                path.file_name()
                                    .map(|n| n.to_string_lossy().into_owned())
                                    .unwrap_or_default(),
                                cx,
                            ))
                            .child(div().flex_1())
                            .when(*copied, |el| {
                                el.child(muted(
                                    t!("detailModal.imageViewer.copied").into_owned(),
                                    cx,
                                ))
                            })
                            .child(
                                Pill::new(
                                    "image-copy",
                                    t!("detailModal.imageViewer.copyImage").into_owned(),
                                    Tone::Tonal,
                                )
                                .icon(Icon::Copy)
                                .on_click(move |_, cx| {
                                    let p = copy_path.clone();
                                    let weak = weak.clone();
                                    cx.spawn(async move |cx| {
                                        let read = p.clone();
                                        let bytes = cx
                                            .background_spawn(async move { std::fs::read(read) })
                                            .await;
                                        let (Ok(bytes), Some(format)) =
                                            (bytes, content::image_format(&p))
                                        else {
                                            return;
                                        };
                                        cx.update(|cx| {
                                            cx.write_to_clipboard(ClipboardItem::new_image(
                                                &Image::from_bytes(format, bytes),
                                            ));
                                            let _ = weak.update(cx, |v, cx| {
                                                if let Some(Overlay::Image { copied, .. }) =
                                                    &mut v.overlay
                                                {
                                                    *copied = true;
                                                }
                                                cx.notify();
                                            });
                                        });
                                    })
                                    .detach();
                                }),
                            )
                            .child(
                                Pill::new(
                                    "image-folder",
                                    t!("detailModal.imageViewer.showInFolder").into_owned(),
                                    Tone::Tonal,
                                )
                                .on_click(move |_, cx| {
                                    if let Some(dir) = folder.parent() {
                                        mmcl_ui_kit::open_folder(cx, dir);
                                    }
                                }),
                            )
                            .child(close_button("image-close", "detailModal.imageViewer.close")),
                    )
                    .into_any_element()
            }
            Overlay::Crash(c) => {
                let text = match &c.text {
                    Load::Loading => None,
                    Load::Ready(r) => Some(r.clone()),
                };
                let copy_text = text.clone().and_then(|r| r.ok());
                let folder = c.entry.path.clone();
                let weak = cx.entity().downgrade();
                let diagnosis: AnyElement = match &c.diagnosis {
                    Load::Loading => {
                        muted(t!("detailModal.crash.viewer.analyzing").into_owned(), cx)
                            .into_any_element()
                    }
                    Load::Ready(None) => muted(
                        t!("detailModal.crash.viewer.analyzeFailed").into_owned(),
                        cx,
                    )
                    .into_any_element(),
                    Load::Ready(Some(d)) => self.diagnosis_card(d, cx),
                };
                div()
                    .size_full()
                    .flex()
                    .flex_col()
                    .gap(u(10.0))
                    .child(title(c.entry.name.clone(), cx))
                    .child(diagnosis)
                    .child(
                        div()
                            .id("crash-text")
                            .flex_1()
                            .min_h_0()
                            .overflow_y_scroll()
                            .rounded(u(8.0))
                            .p(u(8.0))
                            .bg(hsla(t.md_surface_container_lowest))
                            .border_1()
                            .border_color(hsla(t.md_outline_variant))
                            .child(match text {
                                None => {
                                    muted(t!("detailModal.crash.viewer.loading").into_owned(), cx)
                                }
                                Some(Ok(s)) => mono(s, cx),
                                Some(Err(e)) => body(e, cx),
                            }),
                    )
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(u(8.0))
                            .child(div().flex_1())
                            .when(c.copied, |el| {
                                el.child(muted(
                                    t!("detailModal.crash.viewer.copied").into_owned(),
                                    cx,
                                ))
                            })
                            .child(
                                Pill::new(
                                    "crash-copy",
                                    t!("detailModal.crash.viewer.copy").into_owned(),
                                    Tone::Tonal,
                                )
                                .icon(Icon::Copy)
                                .disabled(copy_text.is_none())
                                .on_click(move |_, cx| {
                                    if let Some(s) = &copy_text {
                                        cx.write_to_clipboard(ClipboardItem::new_string(s.clone()));
                                        let _ = weak.update(cx, |v, cx| {
                                            if let Some(Overlay::Crash(c)) = &mut v.overlay {
                                                c.copied = true;
                                            }
                                            cx.notify();
                                        });
                                    }
                                }),
                            )
                            .child(
                                Pill::new(
                                    "crash-folder",
                                    t!("detailModal.crash.viewer.openFolder").into_owned(),
                                    Tone::Tonal,
                                )
                                .on_click(move |_, cx| {
                                    if let Some(dir) = folder.parent() {
                                        mmcl_ui_kit::open_folder(cx, dir);
                                    }
                                }),
                            )
                            .child(close_button(
                                "crash-close",
                                "detailModal.crash.viewer.close",
                            )),
                    )
                    .into_any_element()
            }
        };
        Some(
            div()
                .absolute()
                .top_0()
                .left_0()
                .size_full()
                .bg(hsla(t.md_scrim).opacity(0.55))
                .p(u(24.0))
                .child(
                    nav_scope("detail-overlay")
                        .size_full()
                        .rounded(u(16.0))
                        .p(u(16.0))
                        .bg(cx.launcher_theme().solid_bg(true))
                        .child(inner),
                )
                .into_any_element(),
        )
    }

    fn diagnosis_card(&self, d: &CrashView, cx: &mut Context<Self>) -> AnyElement {
        let engine = self.engine.clone();
        let id = self.id.clone();
        card(cx)
            .child(title(d.title.clone(), cx))
            .child(body(d.summary.clone(), cx))
            .child(div().flex().flex_wrap().gap(u(6.0)).children(
                d.suggestions.iter().enumerate().map(|(i, s)| {
                    let tone = if s.primary {
                        Tone::Primary
                    } else {
                        Tone::Tonal
                    };
                    let pill = Pill::new(("crash-fix", i), s.label.clone(), tone).height(30.0);
                    match s.action.clone() {
                        Some(action) => {
                            let engine = engine.clone();
                            let id = id.clone();
                            pill.on_click(move |window, cx| {
                                run_crash_action(
                                    engine.clone(),
                                    id.clone(),
                                    action.clone(),
                                    window,
                                    cx,
                                )
                            })
                        }
                        None => pill.disabled(true),
                    }
                }),
            ))
            .into_any_element()
    }
}

impl model::UpdateLogDisplay {
    fn empty_text(self) -> String {
        match self {
            UpdateLogDisplay::Empty(s) => s,
            UpdateLogDisplay::Entries(..) => String::new(),
        }
    }
}

impl Render for DetailModal {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let scale = mmcl_ui::scale::factor(cx).max(0.01);
        let vp = window.viewport_size();
        let (_, card_h) =
            model::card_size((f32::from(vp.width) / scale, f32::from(vp.height) / scale));
        let hero_h = model::hero_height(card_h);
        let hero = self.render_hero(hero_h, cx);
        let header = self.render_header(cx);
        let tabs = self.render_tabs(cx);
        let body = self.render_body(window, cx);
        let actions = self.render_actions(cx);
        let overlay = self.render_overlay(cx);
        let t = cx.tokens();
        nav_scope("pack-detail")
            .w_full()
            .h(u(card_h))
            .flex()
            .flex_col()
            .overflow_hidden()
            .rounded(u(20.0))
            .text_color(hsla(t.color_text))
            .font_family(mmcl_ui_kit::fonts::UI_FAMILY)
            .child(hero)
            .child(header)
            .child(tabs)
            .child(body)
            .child(actions)
            .children(overlay)
    }
}

/// One content row: name (growing) and meta; callers append the buttons.
fn content_row(name: String, meta: String, cx: &App) -> gpui::Div {
    let t = cx.tokens();
    div()
        .flex()
        .items_center()
        .gap(u(10.0))
        .py(u(4.0))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(u(14.0))
                .font_weight(FontWeight::MEDIUM)
                .text_color(hsla(t.md_on_surface))
                .child(name),
        )
        .child(muted(meta, cx))
}

fn server_name(s: &ServerView) -> SharedString {
    if s.name.trim().is_empty() {
        s.host.clone().into()
    } else {
        s.name.clone().into()
    }
}

/// Opens a folder entry, or the folder holding a file.
fn open_entry(e: &Entry, cx: &App) {
    if e.is_dir {
        mmcl_ui_kit::open_folder(cx, &e.path);
    } else if let Some(dir) = e.path.parent() {
        mmcl_ui_kit::open_folder(cx, dir);
    }
}

struct ShortcutTarget {
    name: String,
    logo: Option<PathBuf>,
}

impl mmcl_platform::shortcut::ShortcutPack for ShortcutTarget {
    fn pack_name(&self) -> &str {
        &self.name
    }
    fn logo_path(&self) -> Option<PathBuf> {
        self.logo.clone()
    }
}

fn create_shortcut(id: &PackId, name: &str, cx: &mut App) {
    let target = ShortcutTarget {
        name: name.to_string(),
        logo: backend(cx).pack_images(id).logos.into_iter().next(),
    };
    match mmcl_platform::shortcut::create_shortcut(&target) {
        Ok(_) => notify::success(
            &t!("dialog.shortcut.created.title"),
            &tf!("dialog.shortcut.created.body", name),
        ),
        Err(e) => {
            tracing::warn!("{}", tf!("log.modpack.createShortcutFailed", e.localized()));
            let body = tf!("dialog.shortcut.failed.body", e.localized());
            cx.spawn(async move |cx| mmcl_ui::dialogs::error(cx, &body, None).await)
                .detach();
        }
    }
}

/// `VerifyAction.runForPacks(List.of(pack))`: a pack whose game is running is skipped with a
/// warning; otherwise the Progress screen follows the verify, then the summary toast and the
/// screen the modal was opened over.
fn verify_now(summary: &PackSummary, cx: &mut App) {
    let id = summary.id.clone();
    let name = model::display_name(summary);
    if mmcl_ui::try_engine(cx).is_some_and(|e| e.active_session_for_pack(&id).is_some()) {
        notify::warn(
            &t!("notification.pack.running.title"),
            &tf!("notification.pack.running.verify", name.as_str()),
        );
        return;
    }
    let task = mmcl_ui::screens::misc::flows::verify_pack(cx, id, &name, None);
    cx.spawn(async move |_| match task.await {
        Some(Ok(_)) => notify::success(
            &t!("notification.verify.complete.title"),
            &tf!("notification.verify.complete.bodyOne", 1i64),
        ),
        Some(Err(e)) => notify::error(
            &t!("notification.verify.completedWithErrors.title"),
            &tf!("detailModal.verify.failed", name, e.message()),
        ),
        None => {}
    })
    .detach();
}

fn share_url(summary: &PackSummary, cx: &mut gpui::AsyncApp) {
    match summary
        .manifest_url
        .as_deref()
        .filter(|u| !u.trim().is_empty())
    {
        None => notify::error(
            &t!("notification.detail.shareUrlFailed.title"),
            &t!("notification.detail.shareUrlFailed.body"),
        ),
        Some(url) => {
            let url = url.to_string();
            cx.update(|cx| cx.write_to_clipboard(ClipboardItem::new_string(url.clone())));
            notify::success(
                &t!("notification.detail.urlCopied.title"),
                &tf!("notification.detail.urlCopied.body", url),
            );
        }
    }
}

async fn save_manifest(engine: &Engine, summary: &PackSummary, cx: &mut gpui::AsyncApp) {
    let e = engine.clone();
    let id = summary.id.clone();
    let text = engine
        .spawn(async move { e.pack_manifest_text(&id) })
        .await
        .ok()
        .flatten()
        .filter(|s| !s.trim().is_empty());
    let Some(text) = text else {
        notify::error(
            &t!("detailModal.manifest.couldNotRead.title"),
            &t!("detailModal.manifest.couldNotRead.body"),
        );
        return;
    };
    let name = model::manifest_file_name(&summary.name);
    let rx = cx.update(|cx| cx.prompt_for_new_path(&hb::pack_actions::home_dir(), Some(&name)));
    let Ok(Ok(Some(dest))) = rx.await else {
        return;
    };
    let dest = hb::pack_actions::with_extension(dest, "json");
    let path = dest.clone();
    let result = cx
        .background_spawn(async move { std::fs::write(&path, text.as_bytes()) })
        .await;
    match result {
        Ok(()) => notify::success(
            &t!("detailModal.manifest.saved.title"),
            &dest.display().to_string(),
        ),
        Err(err) => {
            tracing::error!(
                "{}",
                tf!("log.modpackDetail.saveManifestJsonFailed", err.to_string())
            );
            notify::error(
                &t!("detailModal.manifest.couldNotSave.title"),
                &err.to_string(),
            );
        }
    }
}

/// The ZIP export: confirm (with "include worlds"), pick the file, verify modded packs first,
/// then write the zip.
async fn export_zip(
    engine: &Engine,
    summary: &PackSummary,
    root: PathBuf,
    cx: &mut gpui::AsyncApp,
) {
    let _ = root;
    let Some(include_saves) = ask_include_worlds(cx).await else {
        return;
    };
    let name =
        hb::pack_actions::export_file_name(&summary.name, chrono::Utc::now().timestamp_millis());
    let rx = cx.update(|cx| cx.prompt_for_new_path(&hb::pack_actions::home_dir(), Some(&name)));
    let Ok(Ok(Some(dest))) = rx.await else {
        return;
    };
    let dest = hb::pack_actions::with_extension(dest, "zip");
    let display = model::display_name(summary);
    notify::info(
        &t!("detailModal.exportPack.label"),
        &tf!("detailModal.exportPack.starting", display.as_str()),
    );
    if !summary.is_vanilla {
        notify::info(
            &t!("detailModal.exportPack.label"),
            &t!("detailModal.exportPack.verifying"),
        );
        let id = summary.id.clone();
        if let hb::backend::VerifyOutcome::Failed(e) = cx.update(|cx| backend(cx).verify(&id)).await
        {
            notify::error(
                &t!("detailModal.exportPack.failed"),
                &tf!("detailModal.exportPack.failedBody", e),
            );
            return;
        }
    }
    let e = engine.clone();
    let id = summary.id.clone();
    let target = dest.clone();
    let result = engine
        .spawn(async move {
            e.export_pack(
                &id,
                ExportRequest::Zip {
                    dest: target,
                    include_saves,
                },
            )
            .await
        })
        .await;
    match result {
        Ok(Ok(_)) => notify::success(
            &tf!("detailModal.exportPack.success", display.as_str()),
            &tf!(
                "detailModal.exportPack.successBody",
                dest.display().to_string()
            ),
        ),
        Ok(Err(err)) => notify::error(
            &t!("detailModal.exportPack.failed"),
            &tf!("detailModal.exportPack.failedBody", err.message()),
        ),
        Err(_) => {}
    }
}

/// The export confirmation with its "Include worlds" check box; `None` when cancelled.
async fn ask_include_worlds(cx: &mut gpui::AsyncApp) -> Option<bool> {
    use futures::channel::oneshot;
    use std::cell::RefCell;
    let (tx, rx) = oneshot::channel::<Option<bool>>();
    let slot = Rc::new(RefCell::new(Some(tx)));
    let send = |slot: &Rc<RefCell<Option<oneshot::Sender<Option<bool>>>>>, v: Option<bool>| {
        if let Some(tx) = slot.borrow_mut().take() {
            let _ = tx.send(v);
        }
    };
    let presented = cx.update(|cx| {
        let Some(host) = super::host_window(cx, None) else {
            return false;
        };
        host.update(cx, |_, window, cx| {
            if !super::has_root(window) {
                return false;
            }
            let include: Entity<bool> = cx.new(|_| false);
            let slot = slot.clone();
            window.open_dialog(cx, move |dialog, _, cx| {
                let checked = *include.read(cx);
                let toggle = include.clone();
                let ok_slot = slot.clone();
                let cancel_slot = slot.clone();
                let close_slot = slot.clone();
                let ok_include = include.clone();
                dialog
                    .title(t!("detailModal.exportPack.dialogTitle").into_owned())
                    .bg(cx.launcher_theme().solid_bg(true))
                    .w(scaled_px(cx, 480.0))
                    .keyboard(true)
                    .overlay_closable(false)
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap(u(12.0))
                            .child(body(
                                t!("detailModal.exportPack.dialogPrompt").into_owned(),
                                cx,
                            ))
                            .child(
                                Checkbox::new("export-include-worlds")
                                    .label(t!("detailModal.exportPack.includeWorlds").into_owned())
                                    .checked(checked)
                                    .on_click(move |v: &bool, _, cx| {
                                        toggle.update(cx, |s, cx| {
                                            *s = *v;
                                            cx.notify();
                                        });
                                    }),
                            ),
                    )
                    .footer(
                        div()
                            .flex()
                            .justify_end()
                            .gap(u(8.0))
                            .child(
                                Pill::new(
                                    "export-cancel",
                                    t!("dialog.button.cancel").into_owned(),
                                    Tone::Text,
                                )
                                .on_click(move |window, cx| {
                                    send(&cancel_slot, None);
                                    window.close_dialog(cx);
                                }),
                            )
                            .child(
                                Pill::new(
                                    "export-ok",
                                    t!("modal.share.button.exportZip").into_owned(),
                                    Tone::Primary,
                                )
                                .on_click(move |window, cx| {
                                    let v = *ok_include.read(cx);
                                    send(&ok_slot, Some(v));
                                    window.close_dialog(cx);
                                }),
                            ),
                    )
                    .on_close(move |_, _, _| send(&close_slot, None))
            });
            true
        })
        .unwrap_or(false)
    });
    if !presented {
        return None;
    }
    rx.await.ok().flatten()
}

fn run_crash_action(
    engine: Engine,
    id: PackId,
    action: mmcl_engine::CrashAction,
    window: &mut Window,
    cx: &mut App,
) {
    let _ = window;
    cx.spawn(async move |cx| {
        let e = engine.clone();
        let pack = id.clone();
        let result = engine
            .spawn(async move { e.apply_crash_action(&action, Some(&pack)).await })
            .await;
        match result {
            Ok(Ok(CrashActionResult::Done)) => notify::success(
                &t!("detailModal.crash.fixApplied.title"),
                &t!("detailModal.crash.fixApplied.body"),
            ),
            Ok(Ok(CrashActionResult::OpenSettings)) => cx.update(|cx| {
                mmcl_ui::shell::navigate_gated(cx, ScreenRequest::new(ScreenId::SETTINGS))
            }),
            Ok(Ok(CrashActionResult::OpenRuntime)) => cx.update(|cx| {
                mmcl_ui::shell::navigate_gated(cx, ScreenRequest::new(ScreenId::RUNTIME))
            }),
            Ok(Ok(CrashActionResult::OpenPath(p))) => {
                cx.update(|cx| mmcl_ui_kit::open_folder(cx, &p))
            }
            Ok(Ok(CrashActionResult::OpenUrl(u))) => {
                cx.update(|cx| mmcl_ui_kit::open_external_url(cx, &u))
            }
            Ok(Err(err)) => {
                tracing::warn!(
                    "{}",
                    tf!("log.contentBrowser.crashSuggestionFailed", err.message())
                );
                notify::warn(&t!("dialog.alert.warning.title"), &err.message());
            }
            Err(_) => {}
        }
    })
    .detach();
}

/// Opens the detail modal for `id` in `window` (the card click hook). Packs the engine does not
/// know (an uninstalled vanilla version) open from the vanilla summary.
pub fn show_pack_detail(engine: Engine, id: PackId, window: &mut Window, cx: &mut App) {
    let summary = match engine.pack(&id) {
        Some(s) => s,
        None => match id.vanilla_version() {
            Some(v) => hb::home_logic::vanilla_summary(v),
            None => {
                tracing::warn!("{}", tf!("log.detailModal.unknownPack", id.as_str()));
                return;
            }
        },
    };
    if !super::has_root(window) {
        tracing::warn!(
            "{}",
            tf!("log.uiKit.dialogNeedsRoot", t!("detailModal.tab.overview"))
        );
        return;
    }
    let view = cx.new(|cx| DetailModal::new(engine, summary, cx));
    window.open_dialog(cx, move |dialog, window, cx| {
        let scale = mmcl_ui::scale::factor(cx).max(0.01);
        let vp = window.viewport_size();
        let (w, _) = model::card_size((f32::from(vp.width) / scale, f32::from(vp.height) / scale));
        dialog
            .w(scaled_px(cx, w))
            .max_w(scaled_px(cx, model::CARD_MAX.0))
            .p_0()
            .close_button(false)
            .keyboard(true)
            .overlay_closable(true)
            .bg(cx.launcher_theme().solid_bg(true))
            .child(view.clone())
    });
}

#[cfg(test)]
mod tests {
    #[test]
    fn content_sections_map_to_listings() {
        use super::model::Section;
        use super::{ContentKind, DetailModal};
        assert_eq!(
            DetailModal::kind_of(Section::Worlds),
            Some(ContentKind::Worlds)
        );
        assert_eq!(
            DetailModal::kind_of(Section::CrashHistory),
            Some(ContentKind::CrashReports)
        );
        assert_eq!(DetailModal::kind_of(Section::Stats), None);
    }
}

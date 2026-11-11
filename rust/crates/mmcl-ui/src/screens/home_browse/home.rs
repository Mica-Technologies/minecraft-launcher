// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Home screen (`MCLauncherMainGui`, gui-shell spec section 3): the installed packs as hero
//! cards with filter / sort / pagination, the account header, announcements, the Running Games
//! button, refresh (F5), play on Enter and the pack context menu.

use std::collections::{HashMap, HashSet};
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use gpui::{
    Animation, AnimationExt as _, App, AppContext as _, ClickEvent, Context, Entity, FocusHandle,
    Focusable, InteractiveElement, IntoElement, ParentElement, Pixels, Render, ScrollHandle,
    SharedString, Size, StatefulInteractiveElement, Styled, Subscription, Task, WeakEntity, Window,
    div, prelude::FluentBuilder as _, px, size,
};
use gpui_component::Disableable as _;
use gpui_component::button::{Button, ButtonVariants as _};
use gpui_component::input::{InputEvent, InputState};
use mmcl_engine::{AccountStatus, AccountView, EngineEvent, PackId, Setting};
use mmcl_i18n::{t, tf};
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::theme::{ThemeExt as _, TypeStyleExt as _, hsla};
use mmcl_ui_kit::{
    AnnouncementBanner, Avatar, CloseDecision, FilterChip, HelpTopic, Icon, IconButton,
    OfflineChip, Screen, ScreenId, ScreenRequest, SearchField, TooltipExt as _, empty_state_badge,
};

use super::backend::{LibraryBackend, UpdateAvailable, backend};
use super::card::{ButtonStyle, CardButton, CardGeometry, PackCard};
use super::controls::{combo, divider, field_label, sources_for};
use super::cycle::{self, CycleSubscription};
use super::home_logic::{
    self, HomeFilter, HomeItem, announcement_height, announcement_text, badges,
    build_filtered_sorted, chips, empty_state, find_by_name, merge_vanilla, pagination_labels,
    play_label, play_tooltip, played_line,
};
use super::hooks;
use super::model::{
    Change, FILTER_TYPE, FILTER_UPDATES_ONLY, FilterValue, HOME_DEFAULT_PAGE_SIZE, HOME_PAGE_SIZES,
    HOME_SORTS, HOME_TYPES, LibraryViewModel, PageBounds, SORT_PREFIX, TYPE_PREFIX, label,
};
use super::pack_actions;
use super::visuals;
use crate::actions::{FocusSearch, HOME_CONTEXT, PlayLast, Refresh};
use crate::dialogs::{self, Answer, Question};
use crate::scale::u;
use crate::services::{UiEvent, events};
use crate::widgets::NavBar;
use mmcl_ui_kit::notify;

/// The single-click delay before the detail modal opens (a double click plays instead).
pub const SINGLE_CLICK_DELAY: Duration = Duration::from_millis(130);

/// The first Home of the process fades in once.
static SHOWN_ONCE: AtomicBool = AtomicBool::new(false);

/// The "update available" toast shows once per session.
static UPDATE_TOAST_SHOWN: AtomicBool = AtomicBool::new(false);

/// The last-played pack's `client.json` is warmed once per session.
static CLIENT_JSON_PREFETCHED: AtomicBool = AtomicBool::new(false);

/// How long Home must stay up before the `client.json` prefetch starts (Java fetched it while
/// the main menu sat idle, so the first Play click does not wait for piston-meta).
pub const PREFETCH_IDLE_DELAY: Duration = Duration::from_secs(3);

/// Claims the session's one idle prefetch; `false` once it was claimed.
fn claim_prefetch(flag: &AtomicBool) -> bool {
    !flag.swap(true, Ordering::SeqCst)
}

/// The Home screen.
pub struct HomeScreen {
    backend: Rc<dyn LibraryBackend>,
    focus: FocusHandle,
    vm: LibraryViewModel,
    search: Entity<InputState>,
    items: Vec<HomeItem>,
    extras: HashMap<PackId, super::backend::PackExtras>,
    extras_pending: HashSet<PackId>,
    order: Vec<usize>,
    bounds: PageBounds,
    status: Option<String>,
    loading_available: bool,
    refreshing: bool,
    announcement: Option<String>,
    account: Option<AccountView>,
    accounts: Vec<AccountView>,
    running: usize,
    update: Option<UpdateAvailable>,
    disposed: bool,
    pending_click: Option<Task<()>>,
    debounce: Option<Task<()>>,
    focused_pack: Option<PackId>,
    scroll: ScrollHandle,
    cycle_sub: Option<CycleSubscription>,
    fade_in: bool,
    select_after_setup: Option<String>,
    subs: Vec<Subscription>,
    tasks: Vec<Task<()>>,
}

impl HomeScreen {
    /// A Home screen; `select` is a pack to bring into view (the `-c <pack>` path).
    pub fn new(select: Option<String>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let search = cx.new(|cx| {
            InputState::new(window, cx).placeholder(t!("filter.search.prompt").into_owned())
        });
        let mut vm = LibraryViewModel::new(
            HOME_DEFAULT_PAGE_SIZE,
            super::model::DEFAULT_SEARCH_DEBOUNCE_MS,
        )
        .expect("positive page size");
        // Initial values before any change handling is wired: setup rebuilds once, explicitly.
        vm.set_filter(FILTER_TYPE, FilterValue::Str("all".into()));
        vm.set_filter(FILTER_UPDATES_ONLY, FilterValue::Bool(false));
        vm.set_sort_key(Some("lastPlayed"));
        HomeScreen {
            backend: backend(cx),
            focus: cx.focus_handle(),
            vm,
            search,
            items: Vec::new(),
            extras: HashMap::new(),
            extras_pending: HashSet::new(),
            order: Vec::new(),
            bounds: PageBounds {
                total_items: 0,
                total_pages: 1,
                start: 0,
                end: 0,
            },
            status: None,
            loading_available: false,
            refreshing: false,
            announcement: None,
            account: None,
            accounts: Vec::new(),
            running: 0,
            update: None,
            disposed: false,
            pending_click: None,
            debounce: None,
            focused_pack: None,
            scroll: ScrollHandle::new(),
            cycle_sub: None,
            fade_in: false,
            select_after_setup: select,
            subs: Vec::new(),
            tasks: Vec::new(),
        }
    }

    fn filter(&self) -> HomeFilter {
        HomeFilter {
            kind: self.vm.get_string_filter(FILTER_TYPE, "all").to_string(),
            updates_only: self.vm.get_bool_filter(FILTER_UPDATES_ONLY, false),
            search: self.vm.search_query().to_string(),
            sort: self.vm.sort_key().to_string(),
            last_mod_pack: self.backend.settings().last_mod_pack,
        }
    }

    fn apply(&mut self, change: Change, cx: &mut Context<Self>) {
        // Any filter / sort / page change ends the `-c` selection ring.
        if change != Change::None {
            self.focused_pack = None;
        }
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

    /// Reloads the pack list and rebuilds the page (`rebuildCards`).
    pub fn rebuild(&mut self, cx: &mut Context<Self>) {
        if self.disposed {
            return;
        }
        self.pending_click = None;
        let packs = merge_vanilla(
            self.backend.installed_packs(),
            &self.backend.installed_vanilla_ids(),
        );
        self.items = packs
            .into_iter()
            .map(|p| {
                let extras = self.extras.get(&p.id).cloned();
                HomeItem { summary: p, extras }
            })
            .collect();
        self.order = build_filtered_sorted(&self.items, &self.filter());
        self.bounds = self.vm.clamp_and_slice(self.order.len());
        self.request_extras(cx);
        self.update_cycling(cx);
        cx.notify();
    }

    fn request_extras(&mut self, cx: &mut Context<Self>) {
        for item in &self.items {
            let id = item.summary.id.clone();
            // Vanilla packs too: their install folder backs the folder, export and
            // official-launcher items of the context menu.
            if self.extras.contains_key(&id) || !self.extras_pending.insert(id.clone()) {
                continue;
            }
            let fut = self.backend.pack_extras(&id);
            self.tasks.push(cx.spawn(async move |this, cx| {
                let extras = fut.await;
                let _ = this.update(cx, |this, cx| {
                    if this.disposed {
                        return;
                    }
                    if let Some(e) = extras {
                        this.extras.insert(id, e);
                        this.rebuild(cx);
                    }
                });
            }));
        }
    }

    fn page_items(&self) -> Vec<&HomeItem> {
        self.order[self.bounds.start..self.bounds.end]
            .iter()
            .map(|&i| &self.items[i])
            .collect()
    }

    fn update_cycling(&mut self, cx: &mut Context<Self>) {
        let s = self.backend.settings();
        cycle::configure(cx, &s.image_cycle_interval);
        let any = self.page_items().iter().any(|it| {
            sources_for(
                cx,
                &self.backend,
                &it.summary,
                s.show_pack_backgrounds,
                s.image_cycle_shuffle,
            )
            .cycles
        });
        if any && !self.disposed {
            if self.cycle_sub.is_none() {
                self.cycle_sub = Some(cycle::subscribe(cx));
            }
        } else {
            self.cycle_sub = None;
        }
    }

    fn bind_account_header(&mut self, cx: &mut Context<Self>) {
        if self.disposed {
            return;
        }
        self.account = self.backend.default_account();
        self.accounts = self.backend.accounts();
        cx.notify();
    }

    fn refresh_running_games(&mut self, cx: &mut Context<Self>) {
        if self.disposed {
            return;
        }
        self.running = self.backend.active_session_count();
        // Play labels read the pack's `active` flag: re-read the summaries.
        self.rebuild(cx);
    }

    fn set_announcement_row(&mut self, cx: &mut Context<Self>) {
        let extra = self
            .backend
            .is_dev()
            .then(|| t!("main.announcement.devMode").into_owned());
        let home = self.backend.announcements().home;
        self.announcement = announcement_text(&home, extra.as_deref(), super::is_dismissed);
        cx.notify();
    }

    fn dismiss_announcement(&mut self, cx: &mut Context<Self>) {
        if let Some(text) = self.announcement.take() {
            super::dismiss(&text);
            self.backend.dismiss_announcement(&text);
            // The home text alone may also be dismissed by the engine; record both.
            let home = self.backend.announcements().home;
            if text.ends_with(&home) && !home.is_empty() {
                super::dismiss(&home);
                self.backend.dismiss_announcement(&home);
            }
        }
        self.set_announcement_row(cx);
    }

    /// F5 / the refresh glyph: re-probe, re-fetch, rebuild in place (`refreshAvailablePacks`).
    pub fn refresh(&mut self, cx: &mut Context<Self>) {
        if self.refreshing {
            return;
        }
        self.refreshing = true;
        self.status = Some(t!("main.fetchLabel.refreshing").into_owned());
        cx.notify();
        let fut = self.backend.refresh();
        self.tasks.push(cx.spawn(async move |this, cx| {
            fut.await;
            let _ = this.update(cx, |this, cx| {
                this.refreshing = false;
                this.status = None;
                this.extras.clear();
                this.extras_pending.clear();
                this.set_announcement_row(cx);
                this.rebuild(cx);
            });
        }));
    }

    /// Enter: play the first card whose Play is enabled.
    pub fn play_first(&mut self, cx: &mut Context<Self>) {
        if self.backend.announcements().disable_gameplay {
            return;
        }
        if let Some(item) = self.page_items().first() {
            let p = item.summary.clone();
            pack_actions::start_play(cx, &p);
        }
    }

    /// Brings a pack into view: clears filters, pages to it, focuses and scrolls to its card
    /// (`selectModpack`).
    pub fn select_modpack(&mut self, name: &str, window: &mut Window, cx: &mut Context<Self>) {
        self.search.update(cx, |s, cx| s.set_value("", window, cx));
        let _ = self.vm.set_search_query(Some(""));
        let _ = self
            .vm
            .set_filter(FILTER_TYPE, FilterValue::Str("all".into()));
        let _ = self
            .vm
            .set_filter(FILTER_UPDATES_ONLY, FilterValue::Bool(false));
        self.rebuild(cx);
        let Some(index) = find_by_name(&self.items, &self.order, name) else {
            return;
        };
        self.vm.set_current_page(index / self.vm.page_size() + 1);
        self.focused_pack = Some(self.items[self.order[index]].summary.id.clone());
        self.rebuild(cx);
        let in_page = index - self.bounds.start;
        // Bounds are stale in this pass: scroll on the next frame.
        cx.on_next_frame(window, move |this, _, cx| {
            this.scroll.scroll_to_item(in_page);
            cx.notify();
        });
    }

    fn card_clicked(
        &mut self,
        id: PackId,
        count: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.focused_pack.take().is_some() {
            cx.notify();
        }
        if count >= 2 {
            self.pending_click = None;
            if self.backend.announcements().disable_gameplay {
                return;
            }
            if let Some(it) = self.items.iter().find(|i| i.summary.id == id) {
                let p = it.summary.clone();
                pack_actions::start_play(cx, &p);
            }
            return;
        }
        self.pending_click = Some(cx.spawn_in(window, async move |this, cx| {
            cx.background_executor().timer(SINGLE_CLICK_DELAY).await;
            let _ = this.update_in(cx, |this, window, cx| {
                this.pending_click = None;
                if this.disposed {
                    return;
                }
                // Reads the current pack at fire time.
                if this.items.iter().any(|i| i.summary.id == id)
                    && let Some(f) = hooks::read(cx).open_detail
                {
                    f(id.clone(), window, cx);
                }
            });
        }));
    }

    /// The avatar / name click with an installed switcher hook (without one, the shared
    /// account chip opens its own switcher menu).
    fn open_account_switcher(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if let Some(f) = hooks::read(cx).account_switcher {
            f(window, cx);
        }
    }

    /// The update glyph (`promptAndOpenUpdate`): "Update now / Later", then the release page
    /// (http(s) only).
    fn prompt_update(&mut self, url: String, cx: &mut Context<Self>) {
        cx.spawn(async move |_, cx| {
            let q = Question::new(
                t!("dialog.update.available.title"),
                t!("dialog.update.available.header"),
                t!("dialog.update.available.body"),
                t!("dialog.update.available.button.now"),
                t!("dialog.update.available.button.later"),
            );
            if dialogs::ask(cx, &q, None).await != Answer::Primary {
                return;
            }
            if home_logic::is_openable_update_url(&url) {
                cx.update(|cx| mmcl_ui_kit::open_external_url(cx, &url));
            } else {
                tracing::error!("{}", tf!("log.updateCheck.refuseNonHttpUrl", url.as_str()));
            }
        })
        .detach();
    }

    /// A newer release was found: the glyph, the red taskbar cue and (once per session) the
    /// "update available" toast (`UpdateCheckManager.checkAndConfigureUI`).
    fn on_update_found(&mut self, info: Option<UpdateAvailable>, cx: &mut Context<Self>) {
        if let Some(u) = &info {
            mmcl_platform::progress::show_full_error();
            if UPDATE_TOAST_SHOWN
                .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
            {
                notify::info(
                    &t!("notification.update.available.title"),
                    &tf!("notification.update.available.body", u.version.as_str()),
                );
            }
        }
        self.update = info;
        cx.notify();
    }

    fn on_event(&mut self, event: &UiEvent, cx: &mut Context<Self>) {
        if self.disposed {
            return;
        }
        match event {
            UiEvent::Engine(EngineEvent::PacksChanged | EngineEvent::PackChanged(_)) => {
                if let UiEvent::Engine(EngineEvent::PackChanged(id)) = event {
                    self.extras.remove(id);
                    self.extras_pending.remove(id);
                }
                self.rebuild(cx);
            }
            UiEvent::Engine(EngineEvent::AccountsChanged) => {
                self.bind_account_header(cx);
                self.rebuild(cx);
            }
            UiEvent::Engine(
                EngineEvent::SessionStarted(_)
                | EngineEvent::SessionChanged(_)
                | EngineEvent::SessionRemoved(_),
            ) => self.refresh_running_games(cx),
            UiEvent::Engine(EngineEvent::AnnouncementsChanged) => {
                self.set_announcement_row(cx);
                cx.notify();
            }
            UiEvent::Engine(EngineEvent::OfflineChanged(_)) => cx.notify(),
            UiEvent::Lagged => {
                self.bind_account_header(cx);
                self.set_announcement_row(cx);
                self.refresh_running_games(cx);
            }
            e if e.is_setting(&Setting::ShowPackBackgrounds(false))
                || e.is_setting(&Setting::ImageCycleInterval(String::new()))
                || e.is_setting(&Setting::ImageCycleShuffle(false))
                || e.is_setting(&Setting::LastModPack(String::new())) =>
            {
                self.rebuild(cx)
            }
            _ => {}
        }
    }

    fn status_text(&self) -> Option<String> {
        if let Some(s) = &self.status {
            return Some(s.clone());
        }
        if self
            .account
            .as_ref()
            .is_some_and(|a| a.status == AccountStatus::Refreshing)
        {
            return Some(t!("main.fetchLabel.signingIn").into_owned());
        }
        self.loading_available
            .then(|| t!("main.fetchLabel.loading").into_owned())
    }
}

impl Focusable for HomeScreen {
    fn focus_handle(&self, _cx: &App) -> FocusHandle {
        self.focus.clone()
    }
}

impl Screen for HomeScreen {
    fn id(&self) -> ScreenId {
        ScreenId::HOME
    }

    fn title(&self, _cx: &App) -> SharedString {
        t!("menu.modpacks.home").into_owned().into()
    }

    fn help_topic(&self) -> HelpTopic {
        HelpTopic::MainScreen
    }

    fn min_size(&self) -> Size<Pixels> {
        size(px(750.0), px(475.0))
    }

    fn setup(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.backend = backend(cx);
        self.backend
            .set_presence(&t!("discordRpc.screen.selectingPack"));
        self.set_announcement_row(cx);
        self.bind_account_header(cx);
        self.running = self.backend.active_session_count();

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
                    let change = this.vm.set_search_query(Some(&text));
                    this.apply(change, cx);
                }
            },
        ));

        // Update check (the navbar download glyph), unless turned off in Settings.
        if self.backend.settings().launcher_update_check {
            let upd = self.backend.check_update();
            self.tasks.push(cx.spawn(async move |this, cx| {
                let info = upd.await;
                let _ = this.update(cx, |this, cx| this.on_update_found(info, cx));
            }));
        }
        // The available-packs fetch: show "Loading available packs…" until it completes, then
        // warm every available pack's logo palette for Browse.
        match self.backend.available_packs_if_ready() {
            Some(list) => visuals::prefetch_available(cx, &list, &self.backend),
            None => {
                self.loading_available = true;
                let fut = self.backend.available_packs();
                self.tasks.push(cx.spawn(async move |this, cx| {
                    let list = fut.await;
                    let _ = this.update(cx, |this, cx| {
                        this.loading_available = false;
                        visuals::prefetch_available(cx, &list, &this.backend);
                        cx.notify();
                    });
                }));
            }
        }
        self.rebuild(cx);
        if let Some(name) = self.select_after_setup.take() {
            self.select_modpack(&name, window, cx);
        }
    }

    fn after_show(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        window.focus(&self.focus, cx);
        self.fade_in = !SHOWN_ONCE.swap(true, Ordering::SeqCst);
        if !self.backend.settings().quick_start_completed
            && let Some(f) = hooks::read(cx).quick_start
        {
            // One frame later, so it never races the first show.
            cx.on_next_frame(window, move |_, window, cx| f(window, cx));
        }
        // Warm the image cache of what is on screen.
        let s = self.backend.settings();
        let sources: Vec<_> = self
            .page_items()
            .iter()
            .map(|it| {
                sources_for(
                    cx,
                    &self.backend,
                    &it.summary,
                    s.show_pack_backgrounds,
                    false,
                )
                .logo
            })
            .collect();
        visuals::prefetch(cx, &sources, &self.backend);
        // Once Home has sat idle a moment, warm the last-played pack's `client.json`. Leaving
        // Home first drops the timer (cleanup clears the tasks); the next visit tries again.
        if !CLIENT_JSON_PREFETCHED.load(Ordering::SeqCst) {
            let backend = self.backend.clone();
            self.tasks.push(cx.spawn(async move |_, cx| {
                cx.background_executor().timer(PREFETCH_IDLE_DELAY).await;
                if claim_prefetch(&CLIENT_JSON_PREFETCHED) {
                    backend.prefetch_last_played().await;
                }
            }));
        }
    }

    fn cleanup(&mut self, _cx: &mut Context<Self>) {
        self.disposed = true;
        mmcl_platform::progress::stop();
        self.subs.clear();
        self.tasks.clear();
        self.pending_click = None;
        self.debounce = None;
        self.cycle_sub = None;
        self.vm.dispose();
    }

    fn on_close_requested(
        &mut self,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) -> CloseDecision {
        crate::shell::request_quit(cx);
        CloseDecision::Keep
    }
}

fn weak_pick(
    this: WeakEntity<HomeScreen>,
    f: impl Fn(&mut HomeScreen, String, &mut Context<HomeScreen>) + 'static,
) -> super::controls::Pick {
    Rc::new(move |id, _w, cx| {
        let _ = this.update(cx, |this, cx| f(this, id, cx));
    })
}

impl HomeScreen {
    fn render_nav(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let weak = cx.entity().downgrade();
        let edit_disabled = self.backend.announcements().disable_modpacks_edit;
        let w = weak.clone();
        let refresh = IconButton::new(
            "home-refresh",
            Icon::Refresh,
            t!("tooltip.main.refresh").into_owned(),
        )
        .compact()
        .disabled(self.refreshing)
        .on_click(move |_, cx| {
            let _ = w.update(cx, |this, cx| this.refresh(cx));
        });
        let browse_presence = self.backend.clone();
        let browse = Button::new("home-browse")
            .label(t!("main.navbar.browse").into_owned())
            .icon(Icon::Browse)
            .ghost()
            .tooltip(t!("tooltip.main.library").into_owned())
            .disabled(edit_disabled)
            .on_click(move |_: &ClickEvent, _, cx| {
                browse_presence.set_presence(&t!("discordRpc.screen.browsing"));
                crate::shell::navigate_gated(cx, ScreenRequest::new(ScreenId::BROWSE));
            });
        let settings_presence = self.backend.clone();
        let settings = Button::new("home-settings")
            .label(t!("main.navbar.settings").into_owned())
            .icon(Icon::System)
            .ghost()
            .tooltip(t!("tooltip.main.settings").into_owned())
            .on_click(move |_: &ClickEvent, _, cx| {
                settings_presence.set_presence(&t!("discordRpc.screen.settings"));
                crate::shell::navigate_gated(cx, ScreenRequest::new(ScreenId::SETTINGS));
            });
        let wu = weak.clone();
        let update = self.update.clone().map(|u| {
            IconButton::new(
                "home-update",
                Icon::Download,
                tf!("tooltip.update.available", u.version.as_str()),
            )
            .compact()
            .on_click(move |_, cx| {
                let url = u.url.clone();
                let _ = wu.update(cx, |this, cx| this.prompt_update(url, cx));
            })
        });
        let help = IconButton::new(
            "home-help",
            Icon::Help,
            t!("tooltip.common.help").into_owned(),
        )
        .compact()
        .on_click(|_, cx| crate::shell::show_help(cx, HelpTopic::MainScreen));
        let chip = if hooks::read(cx).account_switcher.is_some() {
            let name = self
                .account
                .as_ref()
                .map(|a| a.name.clone())
                .unwrap_or_default();
            let uuid = self.account.as_ref().map(|a| a.uuid.clone());
            let w = weak.clone();
            div()
                .id("home-account")
                .flex()
                .items_center()
                .gap(u(8.0))
                .px(u(8.0))
                .py(u(4.0))
                .rounded_full()
                .cursor_pointer()
                .hover(|s| s.bg(hsla(t.md_state_hover_on_surface)))
                .child(Avatar::new("home-avatar", uuid, 28.0))
                .child(
                    div()
                        .type_role(TypeRole::LabelLarge)
                        .text_color(hsla(t.color_text))
                        .child(if name.is_empty() {
                            t!("main.navbar.player").into_owned()
                        } else {
                            name
                        }),
                )
                .launcher_tooltip(t!("main.navbar.avatar.a11y").into_owned())
                .on_click(move |_: &ClickEvent, window, cx| {
                    let _ = w.update(cx, |this, cx| this.open_account_switcher(window, cx));
                })
                .into_any_element()
        } else {
            // The shared chip: switch the default account, add one, or manage accounts
            // (`AccountSwitcherMenu`).
            crate::screens::accounts::switcher::account_chip("home-account", cx).into_any_element()
        };
        NavBar::new(t!("main.navbar.library").into_owned())
            .action(refresh)
            .action(browse)
            .action(settings)
            .action(div().children(update))
            .action(help)
            .action(divider(cx))
            .action(chip)
    }

    fn render_filters(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let weak = cx.entity().downgrade();
        let types = HOME_TYPES
            .iter()
            .map(|id| (id.to_string(), label(TYPE_PREFIX, Some(id))))
            .collect();
        let sorts = HOME_SORTS
            .iter()
            .map(|id| (id.to_string(), label(SORT_PREFIX, Some(id))))
            .collect();
        let updates = self.vm.get_bool_filter(FILTER_UPDATES_ONLY, false);
        let w = weak.clone();
        div()
            .flex_none()
            .h(u(48.0))
            .px(u(16.0))
            .flex()
            .items_center()
            .gap(u(10.0))
            .child(div().w(u(220.0)).child(
                SearchField::new(&self.search).tooltip(t!("tooltip.main.search").into_owned()),
            ))
            .child(field_label(t!("main.filter.typeLabel").into_owned(), cx))
            .child(combo(
                "home-type",
                types,
                self.vm.get_string_filter(FILTER_TYPE, "all"),
                120.0,
                t!("tooltip.main.typeFilter").into_owned(),
                weak_pick(weak.clone(), |this, id, cx| {
                    let c = this.vm.set_filter(FILTER_TYPE, FilterValue::Str(id));
                    this.apply(c, cx);
                }),
            ))
            .child(field_label(t!("main.filter.sortLabel").into_owned(), cx))
            .child(combo(
                "home-sort",
                sorts,
                self.vm.sort_key(),
                150.0,
                t!("tooltip.main.sortFilter").into_owned(),
                weak_pick(weak.clone(), |this, id, cx| {
                    let c = this.vm.set_sort_key(Some(&id));
                    this.apply(c, cx);
                }),
            ))
            .child(div().flex_1())
            .child(
                FilterChip::new(
                    "home-updates",
                    t!("main.filter.recentlyUpdatedOnly").into_owned(),
                )
                .selected(updates)
                .tooltip(t!("tooltip.main.recentlyUpdated").into_owned())
                .on_change(move |on, _, cx| {
                    let _ = w.update(cx, |this, cx| {
                        let c = this
                            .vm
                            .set_filter(FILTER_UPDATES_ONLY, FilterValue::Bool(on));
                        this.apply(c, cx);
                    });
                }),
            )
    }

    fn render_pagination(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let weak = cx.entity().downgrade();
        let (range, page) = pagination_labels(
            &self.bounds,
            self.vm.current_page(),
            "main.pagination.empty",
        );
        let sizes = HOME_PAGE_SIZES
            .iter()
            .map(|n| (n.to_string(), n.to_string()))
            .collect();
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
            .child(div().flex_1())
            .child(
                IconButton::new(
                    "home-prev",
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
                    "home-next",
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
            .child(div().min_w(u(20.0)).flex_1())
            .child(field_label(
                t!("main.pagination.perPageLabel").into_owned(),
                cx,
            ))
            .child(combo(
                "home-page-size",
                sizes,
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

    fn render_card(&self, item: &HomeItem, cx: &mut Context<Self>) -> PackCard {
        let p = &item.summary;
        let s = self.backend.settings();
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
        let visual = visuals::card_visual(cx, &src.logo, &self.backend);
        let background = src
            .background
            .as_ref()
            .and_then(|b| visuals::background_source(cx, b, &self.backend));
        let weak = cx.entity().downgrade();
        let gameplay_off = self.backend.announcements().disable_gameplay;
        let play_pack = p.clone();
        let website = pack_website(p);
        let id = p.id.clone();
        let w = weak.clone();
        let extras = item.extras.clone();
        let menu_pack = p.clone();
        let wm = weak.clone();
        PackCard {
            id: SharedString::from(format!("hero-{}", p.id.as_str())).into(),
            geometry: CardGeometry::HERO,
            visual,
            background,
            badges: badges(p),
            name: home_logic::display_name(p).into(),
            chips: chips(item),
            status: played_line(p, chrono::Utc::now().timestamp_millis()).into(),
            buttons: vec![
                CardButton {
                    label: play_label(p.active).into(),
                    style: ButtonStyle::Primary,
                    disabled: gameplay_off,
                    grows: true,
                    tooltip: play_tooltip(
                        self.backend.account_override(&p.id).as_deref(),
                        &self.accounts,
                    )
                    .map(Into::into),
                    on_click: Rc::new(move |_, cx| pack_actions::start_play(cx, &play_pack)),
                },
                CardButton {
                    label: t!("common.button.website").into_owned().into(),
                    style: ButtonStyle::Secondary,
                    disabled: website.is_none(),
                    grows: false,
                    tooltip: None,
                    on_click: Rc::new(move |_, cx| {
                        if let Some(url) = &website {
                            mmcl_ui_kit::open_external_url(cx, url);
                        }
                    }),
                },
            ],
            on_click: Some(Rc::new(move |count, window, cx| {
                let id = id.clone();
                let _ = w.update(cx, |this, cx| this.card_clicked(id, count, window, cx));
            })),
            menu: Some({
                let builder: super::card::MenuBuilder = Rc::new(move |menu, _, cx| {
                    // Read at open time: extras that arrived after this render still count.
                    let extras = wm
                        .upgrade()
                        .and_then(|this| this.read(cx).extras.get(&menu_pack.id).cloned())
                        .or_else(|| extras.clone());
                    let (wd, ws) = (wm.clone(), wm.clone());
                    // Deferred: menu actions call these synchronously, possibly while this
                    // screen is still being updated (a nested update panics).
                    let done: pack_actions::Done = Rc::new(move |cx| {
                        let wd = wd.clone();
                        cx.defer(move |cx| {
                            let _ = wd.update(cx, |this, cx| this.rebuild(cx));
                        });
                    });
                    let status: pack_actions::StatusSink = Rc::new(move |text, cx| {
                        let ws = ws.clone();
                        cx.defer(move |cx| {
                            let _ = ws.update(cx, |this, cx| {
                                this.status = text;
                                cx.notify();
                            });
                        });
                    });
                    pack_actions::build_pack_context_menu(
                        menu,
                        &menu_pack,
                        extras.as_ref(),
                        Some(status),
                        done,
                        cx,
                    )
                });
                builder
            }),
            focused: self.focused_pack.as_ref() == Some(&p.id),
        }
    }

    fn render_empty(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let state = empty_state(
            !self.items.is_empty(),
            self.filter().active(),
            !self.vm.search_query().trim().is_empty(),
        );
        div()
            .w_full()
            .h(u(320.0))
            .flex()
            .flex_col()
            .items_center()
            .justify_center()
            .gap(u(12.0))
            .child(empty_state_badge(state.icon, cx))
            .child(
                div()
                    .type_role(TypeRole::TitleLarge)
                    .text_color(hsla(t.color_text))
                    .child(t!(state.heading).into_owned()),
            )
            .child(
                div()
                    .max_w(u(480.0))
                    .text_center()
                    .type_role(TypeRole::BodyMedium)
                    .text_color(hsla(t.color_text_muted))
                    .child(t!(state.sub).into_owned()),
            )
            .when(state.browse_button, |el| {
                // The same gate as the navbar's Browse button (`libraryBtn.fire()`).
                let edit_disabled = self.backend.announcements().disable_modpacks_edit;
                let presence = self.backend.clone();
                el.child(
                    Button::new("home-empty-browse")
                        .label(t!("main.navbar.browse").into_owned())
                        .icon(Icon::Browse)
                        .primary()
                        .disabled(edit_disabled)
                        .on_click(move |_: &ClickEvent, _, cx| {
                            presence.set_presence(&t!("discordRpc.screen.browsing"));
                            crate::shell::navigate_gated(cx, ScreenRequest::new(ScreenId::BROWSE));
                        }),
                )
            })
    }

    fn render_status(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let status = self.status_text();
        let running = self.running;
        div()
            .flex_none()
            .h(u(40.0))
            .px(u(16.0))
            .flex()
            .items_center()
            .gap(u(10.0))
            .border_t_1()
            .border_color(hsla(t.color_divider))
            .child(
                div()
                    .type_role(TypeRole::LabelSmall)
                    .text_color(hsla(t.color_text_muted))
                    .child(tf!("main.versionLabel", self.backend.version())),
            )
            .child(OfflineChip::new("home-offline"))
            .children(status.map(|s| {
                div()
                    .type_role(TypeRole::LabelSmall)
                    .text_color(hsla(t.color_text_muted))
                    .child(s)
            }))
            .child(div().flex_1())
            .when(running > 0, |el| {
                el.child(
                    Button::new("home-running")
                        .label(tf!("main.runningGames", running))
                        .primary()
                        .on_click(|_: &ClickEvent, _, cx| {
                            if let Some(f) = hooks::read(cx).show_running_games {
                                f(cx);
                            }
                        }),
                )
            })
            .child(
                Button::new("home-exit")
                    .label(t!("main.navbar.exit").into_owned())
                    .outline()
                    .on_click(|_: &ClickEvent, _, cx| crate::shell::request_quit(cx)),
            )
    }
}

/// The pack's website (`packURL`), when it is an http(s) URL (else the button is disabled).
pub fn pack_website(p: &mmcl_engine::PackSummary) -> Option<String> {
    p.website
        .as_deref()
        .map(str::trim)
        .filter(|w| home_logic::is_openable_update_url(w))
        .map(str::to_string)
}

impl Render for HomeScreen {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let weak = cx.entity().downgrade();
        let banner_h = announcement_height(self.announcement.as_deref());
        let page: Vec<HomeItem> = self.page_items().into_iter().cloned().collect();
        let cards: Vec<gpui::AnyElement> = if page.is_empty() {
            vec![self.render_empty(cx).into_any_element()]
        } else {
            page.iter()
                .map(|it| self.render_card(it, cx).into_any_element())
                .collect()
        };
        let banner = AnnouncementBanner::new(
            "home-banner",
            self.announcement.clone().map(SharedString::from),
        )
        .on_dismiss(move |_, cx| {
            let _ = weak.update(cx, |this, cx| this.dismiss_announcement(cx));
        });
        let content = div()
            .flex()
            .flex_col()
            .size_full()
            .child(self.render_nav(cx))
            .child(div().h(u(banner_h)).flex_none().child(banner))
            .child(self.render_filters(cx))
            .child(self.render_pagination(cx))
            .child(
                div()
                    .id("home-grid")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .track_scroll(&self.scroll)
                    .pt(u(24.0))
                    .px(u(32.0))
                    .pb(u(32.0))
                    .flex()
                    .flex_wrap()
                    .content_start()
                    .justify_center()
                    .gap(u(20.0))
                    .children(cards),
            )
            .child(self.render_status(cx));
        let root = div()
            .id("home-root")
            .key_context(HOME_CONTEXT)
            .track_focus(&self.focus)
            .size_full()
            .bg(hsla(t.color_bg))
            .on_action(cx.listener(|this, _: &Refresh, _, cx| this.refresh(cx)))
            .on_action(cx.listener(|this, _: &PlayLast, _, cx| this.play_first(cx)))
            .on_action(cx.listener(|this, _: &FocusSearch, window, cx| {
                this.search.update(cx, |s, cx| s.focus(window, cx));
            }));
        if self.fade_in && !mmcl_ui_kit::motion::is_reduced() {
            root.child(content.with_animation(
                "home-first-show",
                Animation::new(Duration::from_millis(180)),
                |el, t| el.opacity(t),
            ))
            .into_any_element()
        } else {
            root.child(content).into_any_element()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_idle_prefetch_runs_once_per_session() {
        let flag = AtomicBool::new(false);
        assert!(claim_prefetch(&flag));
        assert!(!claim_prefetch(&flag));
        assert!(PREFETCH_IDLE_DELAY >= Duration::from_secs(1));
    }
}

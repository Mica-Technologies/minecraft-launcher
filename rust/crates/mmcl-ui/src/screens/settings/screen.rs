// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Settings screen (`MCLauncherSettingsGui`, gui-shell spec section 6).
//!
//! Layout: nav bar, announcement banner, a sidebar of ten grouped category tiles beside the
//! selected category's scrolling pane, and the bottom bar (version, Save, Save & Restart while
//! a language change is pending, Return).
//!
//! Two kinds of settings, as in Java: "Save" fields live in a [`SaveForm`] and are written by
//! Save, Save & Restart, or the unsaved-changes prompts; "live" fields are written through
//! the engine the moment they change and are never dirty.

use std::path::PathBuf;
use std::time::Duration;

use gpui::{
    AnimationExt as _, AnyElement, App, AppContext as _, ClickEvent, ClipboardItem, Context,
    Entity, InteractiveElement as _, IntoElement, ParentElement, PathPromptOptions, Pixels, Render,
    SharedString, Size, StatefulInteractiveElement as _, Styled, Subscription, Task, Window, div,
    prelude::FluentBuilder as _, px, size,
};
use gpui_component::Disableable as _;
use gpui_component::button::{Button, ButtonVariants as _};
use gpui_component::input::{Input, InputEvent, InputState, NumberInput};
use gpui_component::scroll::ScrollableElement as _;
use gpui_component::select::{Select, SelectEvent};
use mmcl_engine::settings::{
    CONSOLE_LOG_MAX_LINES_PRESETS, IMAGE_CYCLE_INTERVALS, RGB_BACKENDS, RGB_EFFECT_STYLES, THEMES,
    UI_SCALE_STEPS,
};
use mmcl_engine::{
    AccountStatus, AccountView, ChipTone, EngineEvent, Notification, NotificationKind,
    RgbStatusView, ScanFrequency, Setting, Settings,
};
use mmcl_i18n::{SUPPORTED, t, tf};
use mmcl_theme::shape;
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::theme::{ThemeExt as _, TypeStyleExt as _, hsla};
use mmcl_ui_kit::{
    Avatar, CloseDecision, HelpTopic, Screen, ScreenId, ScreenRequest, Switch, TooltipExt as _,
    WavyProgressBar,
};

use super::logic::{self, SaveForm, TilePosition};
use super::mcp::{self, ACTIVITY_ROWS_SHOWN, McpClient, McpPolicy};
use super::widgets::{
    OptSelect, bottom_bar, button_row, category_header, group, hint, labeled_row, new_select,
    section_title, select_index, stacked_row,
};
use super::{CATEGORIES, NAV_GROUPS, category, open_add_account};
use crate::dialogs::{self, Answer, Question};
use crate::scale::u;
use crate::services::{self, UiEvent};
use crate::widgets::{AnnouncementBanner, NavBar, OfflineChip};

/// `https://www.minecraft.net/en-us/profile`.
pub const MINECRAFT_PROFILE_URL: &str = "https://www.minecraft.net/en-us/profile";
/// `https://account.microsoft.com/`.
pub const MICROSOFT_ACCOUNT_URL: &str = "https://account.microsoft.com/";
/// The website.
pub const WEBSITE_URL: &str = "https://micatechnologies.com";
/// The source repository.
pub const SOURCE_URL: &str = "https://github.com/MicaTechnologies/Mica-Minecraft-Launcher";
/// How long "Saved" shows on the Save button.
const SAVED_FLASH: Duration = Duration::from_secs(5);
/// How long export / import results show.
const RESULT_FLASH: Duration = Duration::from_secs(2);
/// How long the RGB connection test lasts before the chip is re-read.
const RGB_TEST: Duration = Duration::from_secs(2);

/// The dropdowns of the screen (for the shared selection handler).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Sel {
    Jvm,
    Theme,
    Language,
    Cycle,
    ConsoleLines,
    ScanFrequency,
    ProxyType,
    RgbBackend,
    RgbStyle,
    McpClient,
}

/// The malware scan panel.
struct ScanUi {
    folder: PathBuf,
    running: bool,
    output: Option<SharedString>,
    bar: Entity<WavyProgressBar>,
}

/// The MCP panel's view state.
#[derive(Default)]
struct McpUi {
    token_revealed: bool,
    copied: bool,
    client: usize,
    snippet: Option<String>,
    tools: Vec<(String, SharedString, Entity<OptSelect>)>,
    tool_subs: Vec<Subscription>,
}

/// The inputs of the screen.
struct Inputs {
    min_ram: Entity<InputState>,
    max_ram: Entity<InputState>,
    proxy_host: Entity<InputState>,
    proxy_port: Entity<InputState>,
    curseforge: Entity<InputState>,
    backup_count: Entity<InputState>,
    backup_age: Entity<InputState>,
}

/// The dropdowns of the screen.
struct Selects {
    jvm: Entity<OptSelect>,
    theme: Entity<OptSelect>,
    language: Entity<OptSelect>,
    cycle: Entity<OptSelect>,
    console_lines: Entity<OptSelect>,
    scan_frequency: Entity<OptSelect>,
    proxy_type: Entity<OptSelect>,
    rgb_backend: Entity<OptSelect>,
    rgb_style: Entity<OptSelect>,
    mcp_client: Entity<OptSelect>,
}

/// The Settings screen.
pub struct SettingsScreen {
    category: usize,
    saved: Settings,
    form: SaveForm,
    inputs: Inputs,
    selects: Selects,
    accounts: Vec<AccountView>,
    default_account: Option<AccountView>,
    rgb_status: Option<RgbStatusView>,
    announcement: Option<SharedString>,
    sys_ram: Option<SharedString>,
    total_ram_bytes: Option<u64>,
    has_curseforge_key: bool,
    saved_flash: bool,
    export_flash: Option<SharedString>,
    import_flash: Option<SharedString>,
    busy: bool,
    scan: ScanUi,
    mcp: McpUi,
    is_dev: bool,
    version: SharedString,
    app_name: SharedString,
    root: PathBuf,
    disposed: bool,
    subs: Vec<Subscription>,
    tasks: Vec<Task<()>>,
}

fn engine_settings(cx: &App) -> Settings {
    match services::try_engine(cx) {
        Some(e) => e.settings(),
        None => {
            use mmcl_config::store::{ConfigStore, first_run_defaults};
            Settings::read(&mmcl_config::Config::new(ConfigStore::in_memory(
                first_run_defaults(),
            )))
        }
    }
}

fn notify(cx: &App, kind: NotificationKind, title_key: &str, body_key: &str) {
    if let Some(e) = services::try_engine(cx) {
        e.notify(Notification::new(kind, t!(title_key), t!(body_key)));
    }
}

/// Writes settings one by one (an invalid one is logged and skipped, the rest still apply).
fn apply_writes(engine: &mmcl_engine::Engine, writes: Vec<Setting>) {
    for w in writes {
        let key = w.key();
        if let Err(e) = engine.set(w) {
            tracing::warn!("{}", tf!("log.settings.saveFailed", key, e.message()));
        }
    }
}

/// The OS-reported total and used memory, in GB (fallback when the engine has no probe).
fn probe_memory() -> Option<(f64, f64, u64)> {
    let mut sys = sysinfo::System::new();
    sys.refresh_memory();
    let total = sys.total_memory();
    if total == 0 {
        return None;
    }
    let gb = |b: u64| b as f64 / (1024.0 * 1024.0 * 1024.0);
    Some((gb(total), gb(sys.used_memory()), total))
}

impl SettingsScreen {
    /// A Settings screen showing `category`.
    pub fn new(category: usize, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let saved = engine_settings(cx);
        let form = SaveForm::from_settings(&saved);
        let engine = services::try_engine(cx);
        let build = mmcl_core::build::info();
        let root = engine
            .as_ref()
            .map(|e| e.paths().root().to_path_buf())
            .unwrap_or_default();

        let (min_lo, min_hi) = logic::min_ram_range(form.max_ram_gb);
        let (max_lo, max_hi) = logic::max_ram_range(form.min_ram_gb);
        let num = |text: String,
                   step: f64,
                   lo: f64,
                   hi: f64,
                   window: &mut Window,
                   cx: &mut Context<Self>| {
            cx.new(|cx| {
                InputState::new(window, cx)
                    .default_value(text)
                    .step(step)
                    .min(lo)
                    .max(hi)
            })
        };
        let inputs = Inputs {
            min_ram: num(
                logic::format_gb(form.min_ram_gb),
                logic::RAM_STEP_GB,
                min_lo,
                min_hi,
                window,
                cx,
            ),
            max_ram: num(
                logic::format_gb(form.max_ram_gb),
                logic::RAM_STEP_GB,
                max_lo,
                max_hi,
                window,
                cx,
            ),
            proxy_port: num(form.proxy_port.to_string(), 1.0, 1.0, 65535.0, window, cx),
            backup_count: num(
                saved.backup_max_count_per_pack.to_string(),
                1.0,
                0.0,
                100.0,
                window,
                cx,
            ),
            backup_age: num(
                saved.backup_max_age_days.to_string(),
                1.0,
                0.0,
                365.0,
                window,
                cx,
            ),
            proxy_host: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(t!("settings.proxyHost.prompt").into_owned())
                    .default_value(form.proxy_host.clone())
            }),
            curseforge: cx.new(|cx| InputState::new(window, cx).masked(true)),
        };

        let os_name = logic::os_language_name(&mmcl_i18n::locale::os_locale().to_tag());
        let mut languages = vec![tf!("settings.language.osDefault", os_name.as_str())];
        languages.extend(SUPPORTED.iter().map(|(_, name)| name.to_string()));
        let selects = Selects {
            jvm: new_select(
                (0..=logic::PRESET_COUNT)
                    .map(logic::jvm_preset_label)
                    .collect(),
                Some(form.jvm_preset.unwrap_or(logic::PRESET_COUNT)),
                window,
                cx,
            ),
            theme: new_select(
                THEMES.iter().map(|t| logic::theme_label(t)).collect(),
                form.theme_index,
                window,
                cx,
            ),
            language: new_select(languages, Some(form.language_index), window, cx),
            cycle: new_select(
                IMAGE_CYCLE_INTERVALS
                    .iter()
                    .map(|t| logic::cycle_label(t))
                    .collect(),
                Some(logic::cycle_index(&saved.image_cycle_interval)),
                window,
                cx,
            ),
            console_lines: new_select(
                CONSOLE_LOG_MAX_LINES_PRESETS
                    .iter()
                    .map(|n| logic::console_lines_label(*n))
                    .collect(),
                Some(form.console_lines_index),
                window,
                cx,
            ),
            scan_frequency: new_select(
                ScanFrequency::ALL.iter().map(|f| f.label()).collect(),
                ScanFrequency::ALL
                    .iter()
                    .position(|f| f.name() == saved.default_scan_frequency),
                window,
                cx,
            ),
            proxy_type: new_select(
                mmcl_config::keys::PROXY_TYPES
                    .iter()
                    .map(|s| s.to_string())
                    .collect(),
                Some(form.proxy_type_index),
                window,
                cx,
            ),
            rgb_backend: new_select(
                RGB_BACKENDS
                    .iter()
                    .map(|b| logic::rgb_backend_label(b))
                    .collect(),
                Some(logic::rgb_backend_index(&saved.rgb_backend)),
                window,
                cx,
            ),
            rgb_style: new_select(
                RGB_EFFECT_STYLES
                    .iter()
                    .map(|s| logic::rgb_style_label(s))
                    .collect(),
                Some(logic::rgb_style_index(&saved.rgb_effect_style)),
                window,
                cx,
            ),
            mcp_client: new_select(
                McpClient::ALL
                    .iter()
                    .map(|c| t!(c.label_key()).into_owned())
                    .collect(),
                Some(0),
                window,
                cx,
            ),
        };

        let mut subs = Vec::new();
        for (state, which) in [
            (&selects.jvm, Sel::Jvm),
            (&selects.theme, Sel::Theme),
            (&selects.language, Sel::Language),
            (&selects.cycle, Sel::Cycle),
            (&selects.console_lines, Sel::ConsoleLines),
            (&selects.scan_frequency, Sel::ScanFrequency),
            (&selects.proxy_type, Sel::ProxyType),
            (&selects.rgb_backend, Sel::RgbBackend),
            (&selects.rgb_style, Sel::RgbStyle),
            (&selects.mcp_client, Sel::McpClient),
        ] {
            subs.push(cx.subscribe_in(
                state,
                window,
                move |this, _, ev: &SelectEvent<Vec<super::widgets::Opt>>, window, cx| {
                    let SelectEvent::Confirm(Some(index)) = ev else {
                        return;
                    };
                    this.on_select(which, *index, window, cx);
                },
            ));
        }
        subs.push(cx.subscribe_in(
            &inputs.min_ram,
            window,
            |this, input, ev: &InputEvent, window, cx| {
                this.on_ram_input(true, input, ev, window, cx);
            },
        ));
        subs.push(cx.subscribe_in(
            &inputs.max_ram,
            window,
            |this, input, ev: &InputEvent, window, cx| {
                this.on_ram_input(false, input, ev, window, cx);
            },
        ));
        subs.push(
            cx.subscribe(&inputs.proxy_host, |this, input, ev: &InputEvent, cx| {
                if matches!(ev, InputEvent::Change) {
                    this.form.proxy_host = input.read(cx).value().to_string();
                    cx.notify();
                }
            }),
        );
        subs.push(
            cx.subscribe(&inputs.proxy_port, |this, input, ev: &InputEvent, cx| {
                if matches!(ev, InputEvent::Change)
                    && let Some(p) = logic::parse_int_clamped(&input.read(cx).value(), 1, 65535)
                {
                    this.form.proxy_port = p;
                    cx.notify();
                }
            }),
        );
        subs.push(
            cx.subscribe(&inputs.backup_count, |this, input, ev: &InputEvent, cx| {
                if matches!(ev, InputEvent::Change | InputEvent::Blur)
                    && let Some(n) = logic::parse_int_clamped(&input.read(cx).value(), 0, 100)
                    && n != this.saved.backup_max_count_per_pack
                {
                    this.set_live(Setting::BackupMaxCountPerPack(n), cx);
                }
            }),
        );
        subs.push(
            cx.subscribe(&inputs.backup_age, |this, input, ev: &InputEvent, cx| {
                if matches!(ev, InputEvent::Change | InputEvent::Blur)
                    && let Some(n) = logic::parse_int_clamped(&input.read(cx).value(), 0, 365)
                    && n != this.saved.backup_max_age_days
                {
                    this.set_live(Setting::BackupMaxAgeDays(n), cx);
                }
            }),
        );
        if let Some(events) = services::events(cx) {
            subs.push(cx.subscribe(&events, |this, _, ev: &UiEvent, cx| {
                this.on_engine_event(ev, cx)
            }));
        }

        let bar = cx.new(|_| WavyProgressBar::indeterminate());
        SettingsScreen {
            category: category.min(CATEGORIES.len() - 1),
            accounts: engine.as_ref().map(|e| e.accounts()).unwrap_or_default(),
            default_account: engine.as_ref().and_then(|e| e.default_account()),
            rgb_status: engine.as_ref().map(|e| e.rgb_status()),
            announcement: engine
                .as_ref()
                .map(|e| e.announcements().settings)
                .filter(|s| !s.trim().is_empty())
                .map(Into::into),
            has_curseforge_key: saved.has_curseforge_api_key,
            saved,
            form,
            inputs,
            selects,
            sys_ram: None,
            total_ram_bytes: None,
            saved_flash: false,
            export_flash: None,
            import_flash: None,
            busy: false,
            scan: ScanUi {
                folder: root.clone(),
                running: false,
                output: None,
                bar,
            },
            mcp: McpUi::default(),
            is_dev: build.is_dev,
            version: build.version.into(),
            app_name: build.display_name.into(),
            root,
            disposed: false,
            subs,
            tasks: Vec::new(),
        }
    }

    /// `showCategory(i)`: shows pane `i` and marks its tile.
    pub fn show_category(&mut self, index: usize, cx: &mut Context<Self>) {
        let index = index.min(CATEGORIES.len() - 1);
        if index != self.category {
            self.category = index;
            cx.notify();
        }
    }

    /// The category shown.
    pub fn category(&self) -> usize {
        self.category
    }

    /// Whether Save would write anything.
    pub fn has_unsaved_changes(&self) -> bool {
        self.form.has_unsaved_changes(&self.saved)
    }

    // ---- engine glue ---------------------------------------------------------------------

    fn reread(&mut self, cx: &mut Context<Self>) {
        self.saved = engine_settings(cx);
        if let Some(e) = services::try_engine(cx) {
            self.has_curseforge_key = e.has_curseforge_api_key();
        }
    }

    /// Writes a live setting now and re-reads the stored values.
    fn set_live(&mut self, setting: Setting, cx: &mut Context<Self>) {
        if let Some(e) = services::try_engine(cx) {
            let key = setting.key();
            if let Err(err) = e.set(setting) {
                tracing::warn!("{}", tf!("log.settings.saveFailed", key, err.message()));
            }
        }
        self.reread(cx);
        cx.notify();
    }

    fn on_engine_event(&mut self, ev: &UiEvent, cx: &mut Context<Self>) {
        if self.disposed {
            return;
        }
        match ev {
            UiEvent::Engine(EngineEvent::AccountsChanged) => self.reload_accounts(cx),
            UiEvent::Engine(EngineEvent::RgbStatusChanged(s)) => {
                self.rgb_status = Some(s.clone());
                cx.notify();
            }
            UiEvent::Engine(EngineEvent::AnnouncementsChanged) => {
                self.announcement = services::try_engine(cx)
                    .map(|e| e.announcements().settings)
                    .filter(|s| !s.trim().is_empty())
                    .map(Into::into);
                cx.notify();
            }
            UiEvent::Engine(EngineEvent::Progress(p))
                if self.scan.running && p.label == t!("settings.fxml.malwareScanning") =>
            {
                let line = logic::strip_ansi(&p.text);
                if !line.trim().is_empty() {
                    self.scan.output = Some(line.into());
                }
                let fraction = p.fraction;
                self.scan
                    .bar
                    .update(cx, |b, cx| b.set_progress(fraction, cx));
                cx.notify();
            }
            UiEvent::Engine(EngineEvent::SettingChanged(_)) => {
                // Another front end (MCP, the menu bar) changed a setting: refresh the live
                // values; the Save form keeps the user's pending edits.
                self.reread(cx);
                cx.notify();
            }
            UiEvent::Lagged => {
                self.reread(cx);
                self.reload_accounts(cx);
            }
            _ => {}
        }
    }

    fn reload_accounts(&mut self, cx: &mut Context<Self>) {
        if let Some(e) = services::try_engine(cx) {
            self.accounts = e.accounts();
            self.default_account = e.default_account();
        }
        cx.notify();
    }

    // ---- control handlers ----------------------------------------------------------------

    fn on_select(&mut self, which: Sel, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        match which {
            Sel::Jvm => {
                self.form.jvm_preset = (index < logic::PRESET_COUNT).then_some(index);
            }
            Sel::Theme => self.form.theme_index = Some(index),
            Sel::Language => self.form.language_index = index,
            Sel::Cycle => {
                if let Some(token) = IMAGE_CYCLE_INTERVALS.get(index)
                    && *token != self.saved.image_cycle_interval
                {
                    self.set_live(Setting::ImageCycleInterval(token.to_string()), cx);
                }
            }
            Sel::ConsoleLines => self.form.console_lines_index = index,
            Sel::ScanFrequency => {
                if let Some(f) = ScanFrequency::ALL.get(index)
                    && f.name() != self.saved.default_scan_frequency
                {
                    self.set_live(Setting::DefaultScanFrequency(*f), cx);
                }
            }
            Sel::ProxyType => self.form.proxy_type_index = index,
            Sel::RgbBackend => {
                let token = logic::backend_for_index(index);
                if !token.eq_ignore_ascii_case(&self.saved.rgb_backend) {
                    self.set_live(Setting::RgbBackend(token.to_string()), cx);
                    self.refresh_rgb_status(cx);
                }
            }
            Sel::RgbStyle => {
                if let Some(style) = RGB_EFFECT_STYLES.get(index)
                    && !style.eq_ignore_ascii_case(&self.saved.rgb_effect_style)
                {
                    self.set_live(Setting::RgbEffectStyle(style.to_string()), cx);
                }
            }
            Sel::McpClient => {
                self.mcp.client = index;
                self.mcp.copied = false;
                self.render_snippet(cx);
            }
        }
        let _ = window;
        cx.notify();
    }

    fn on_ram_input(
        &mut self,
        is_min: bool,
        input: &Entity<InputState>,
        ev: &InputEvent,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let range = if is_min {
            logic::min_ram_range(self.form.max_ram_gb)
        } else {
            logic::max_ram_range(self.form.min_ram_gb)
        };
        match ev {
            InputEvent::Change => {
                let Some(v) = logic::parse_clamped(&input.read(cx).value(), range) else {
                    return;
                };
                if is_min {
                    self.form.min_ram_gb = v;
                } else {
                    self.form.max_ram_gb = v;
                }
                // Each spinner's range tracks the other's value.
                let (lo, hi) = logic::max_ram_range(self.form.min_ram_gb);
                self.inputs.max_ram.update(cx, |s, cx| {
                    s.set_min(Some(lo), window, cx);
                    s.set_max(Some(hi), window, cx);
                });
                let (lo, hi) = logic::min_ram_range(self.form.max_ram_gb);
                self.inputs.min_ram.update(cx, |s, cx| {
                    s.set_min(Some(lo), window, cx);
                    s.set_max(Some(hi), window, cx);
                });
                cx.notify();
            }
            InputEvent::Blur => {
                let v = if is_min {
                    self.form.min_ram_gb
                } else {
                    self.form.max_ram_gb
                };
                let text = logic::format_gb(v.clamp(range.0, range.1));
                input.update(cx, |s, cx| s.set_value(text, window, cx));
            }
            _ => {}
        }
    }

    fn live_switch(
        &self,
        id: &'static str,
        checked: bool,
        make: fn(bool) -> Setting,
        cx: &mut Context<Self>,
    ) -> Switch {
        let this = cx.entity().downgrade();
        Switch::new(id).checked(checked).on_change(move |v, _, cx| {
            let _ = this.update(cx, |s, cx| {
                s.set_live(make(v), cx);
                s.after_live(make(v), cx);
            });
        })
    }

    fn form_switch(
        &self,
        id: &'static str,
        checked: bool,
        apply: fn(&mut SaveForm, bool),
        cx: &mut Context<Self>,
    ) -> Switch {
        let this = cx.entity().downgrade();
        Switch::new(id).checked(checked).on_change(move |v, _, cx| {
            let _ = this.update(cx, |s, cx| {
                apply(&mut s.form, v);
                cx.notify();
            });
        })
    }

    /// Side effects of live switches the engine does not run itself.
    fn after_live(&mut self, setting: Setting, cx: &mut Context<Self>) {
        match setting {
            Setting::McpServerEnabled(_) | Setting::McpAllowStateChanges(_) => {
                if let Some(panel) = mcp::mcp_panel(cx) {
                    panel.refresh();
                }
                self.render_snippet(cx);
            }
            Setting::RgbEnable(_)
            | Setting::RgbEnableOpenRgb(_)
            | Setting::RgbEnableChromaNative(_)
            | Setting::RgbEnableChromaRest(_)
            | Setting::RgbEnableWindowsDl(_)
            | Setting::RgbEnableCorsair(_)
            | Setting::RgbEnableAsusAura(_) => self.refresh_rgb_status(cx),
            _ => {}
        }
    }

    fn refresh_rgb_status(&mut self, cx: &mut Context<Self>) {
        self.rgb_status = services::try_engine(cx).map(|e| e.rgb_status());
        cx.notify();
    }

    /// Persists the Save form synchronously (the unsaved-changes prompts).
    fn persist_now(&mut self, cx: &mut Context<Self>) -> logic::PersistPlan {
        let plan = self.form.persist_plan(&self.saved);
        if let Some(e) = services::try_engine(cx) {
            apply_writes(&e, plan.writes.clone());
        }
        self.after_persist(&plan, cx);
        plan
    }

    fn after_persist(&mut self, plan: &logic::PersistPlan, cx: &mut Context<Self>) {
        self.reread(cx);
        if plan.proxy_host_missing {
            self.form.proxy_enable = false;
            cx.spawn(async move |_, cx| {
                dialogs::warning(cx, &t!("settings.proxy.hostRequired"), None).await;
            })
            .detach();
        }
        cx.notify();
    }

    /// Save (background writes, then the "Saved" flash).
    fn save(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let plan = self.form.persist_plan(&self.saved);
        let writes = plan.writes.clone();
        let task = services::run(cx, move |e| async move { apply_writes(&e, writes) });
        self.busy = true;
        cx.notify();
        self.tasks.push(cx.spawn(async move |this, cx| {
            if let Some(task) = task {
                let _ = task.await;
            }
            let ok = this
                .update(cx, |s, cx| {
                    s.busy = false;
                    s.saved_flash = true;
                    s.after_persist(&plan, cx);
                })
                .is_ok();
            if !ok {
                return;
            }
            cx.background_executor().timer(SAVED_FLASH).await;
            let _ = this.update(cx, |s, cx| {
                s.saved_flash = false;
                cx.notify();
            });
        }));
    }

    /// Save & Restart: a relaunch ends the process, so ask about running games first.
    fn save_and_restart(&mut self, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            if !crate::shell::confirm_quit_while_games_run(cx).await {
                return;
            }
            if this.update(cx, |s, cx| s.persist_now(cx)).is_ok() {
                cx.update(crate::shell::restart_app);
            }
        })
        .detach();
    }

    fn unsaved_question(prefix: &str, primary: &str, secondary: &str) -> Question {
        Question::new(
            t!(&format!("{prefix}.title")),
            t!(&format!("{prefix}.header")),
            t!(&format!("{prefix}.body")),
            t!(&format!("{prefix}.button.{primary}")),
            t!(&format!("{prefix}.button.{secondary}")),
        )
    }

    /// Return: the unsaved-changes prompt, then Home.
    fn return_home(&mut self, cx: &mut Context<Self>) {
        if !self.has_unsaved_changes() {
            crate::shell::go_to(cx, ScreenRequest::new(ScreenId::HOME));
            return;
        }
        cx.spawn(async move |this, cx| {
            let q = Self::unsaved_question("dialog.settings.unsavedOnReturn", "save", "return");
            match dialogs::ask(cx, &q, None).await {
                Answer::Primary => {
                    let _ = this.update(cx, |s, cx| s.persist_now(cx));
                }
                Answer::Secondary => {}
                Answer::Closed => return,
            }
            cx.update(|cx| crate::shell::go_to(cx, ScreenRequest::new(ScreenId::HOME)));
        })
        .detach();
    }

    // ---- accounts ------------------------------------------------------------------------

    fn confirm_sign_out(&mut self, uuid: String, name: String, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let q = Question::new(
                t!("settings.accounts.confirmSignOut.title"),
                tf!("settings.accounts.confirmSignOut.header", name.as_str()),
                t!("settings.accounts.confirmSignOut.body"),
                t!("settings.accounts.signOut"),
                t!("dialog.button.cancel"),
            );
            if dialogs::ask(cx, &q, None).await != Answer::Primary {
                return;
            }
            let last =
                cx.update(|cx| services::try_engine(cx).is_some_and(|e| e.accounts().len() <= 1));
            if last && !crate::shell::confirm_restart_while_games_run(cx).await {
                return;
            }
            let id = uuid.clone();
            let Some(task) =
                cx.update(|cx| services::run(cx, move |e| async move { e.sign_out(&id).await }))
            else {
                return;
            };
            match task.await {
                Ok(Ok(())) => {}
                Ok(Err(e)) => {
                    dialogs::error(cx, &e.message(), None).await;
                    return;
                }
                Err(_) => return,
            }
            let none_left =
                cx.update(|cx| services::try_engine(cx).is_some_and(|e| e.accounts().is_empty()));
            if none_left {
                cx.update(crate::shell::restart_app);
            } else {
                let _ = this.update(cx, |s, cx| s.reload_accounts(cx));
            }
        })
        .detach();
    }

    // ---- game ----------------------------------------------------------------------------

    fn load_system_memory(&mut self, cx: &mut Context<Self>) {
        let engine_task = services::run(cx, |e| async move { e.system_memory().await });
        let bg = cx.background_executor().clone();
        self.tasks.push(cx.spawn(async move |this, cx| {
            let from_engine = match engine_task {
                Some(t) => t.await.ok().and_then(Result::ok),
                None => None,
            };
            let probed = bg.spawn(async { probe_memory() }).await;
            let line = match (from_engine, probed) {
                (Some(m), _) => Some(logic::sys_ram_text(m.total_gb, m.used_gb)),
                (None, Some((total, used, _))) => Some(logic::sys_ram_text(total, used)),
                (None, None) => {
                    tracing::warn!("{}", t!("log.settings.queryMemoryFailed"));
                    None
                }
            };
            let total_bytes = probed.map(|p| p.2);
            let _ = this.update(cx, |s, cx| {
                if s.disposed {
                    return;
                }
                s.sys_ram = line.map(Into::into);
                s.total_ram_bytes = total_bytes;
                cx.notify();
            });
        }));
    }

    fn jvm_hint(&self) -> Option<String> {
        let total = self.total_ram_bytes?;
        Some(mmcl_util::jvm_args::summary_for(
            (self.form.max_ram_gb as i32).max(1),
            mmcl_util::jvm_args::available_cores(),
            total,
        ))
    }

    fn generate_jvm_args(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let max_gb = self.form.max_ram_gb;
        let args = services::try_engine(cx)
            .and_then(|e| e.hardware_tuned_jvm_args(max_gb).ok())
            .unwrap_or_else(|| mmcl_util::jvm_args::generate((max_gb as i32).max(1)));
        tracing::info!("{}", tf!("log.settings.generatedJvmArgs", args.as_str()));
        self.set_live(Setting::JvmArgs(args.clone()), cx);
        self.form.jvm_preset = logic::preset_index_for_args(&args);
        select_index(
            &self.selects.jvm,
            Some(self.form.jvm_preset.unwrap_or(logic::PRESET_COUNT)),
            window,
            cx,
        );
        notify(
            cx,
            NotificationKind::Success,
            "notification.settings.jvmArgsGenerated.title",
            "notification.settings.jvmArgsGenerated.body",
        );
        cx.notify();
    }

    // ---- advanced ------------------------------------------------------------------------

    fn verify_all(&mut self, cx: &mut Context<Self>) {
        let Some(engine) = services::try_engine(cx) else {
            return;
        };
        if engine.installed_packs().is_empty() {
            notify(
                cx,
                NotificationKind::Info,
                "notification.settings.nothingToVerify.title",
                "notification.settings.nothingToVerify.body",
            );
            return;
        }
        let task = engine.spawn({
            let e = engine.clone();
            async move { e.verify_all_packs().await }
        });
        cx.spawn(async move |_, cx| {
            if let Ok(Err(e)) = task.await {
                dialogs::error(cx, &e.message(), None).await;
            }
        })
        .detach();
    }

    fn save_curseforge_key(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let key = self.inputs.curseforge.read(cx).value().trim().to_string();
        if key.is_empty() {
            return;
        }
        if let Some(e) = services::try_engine(cx) {
            e.set_curseforge_api_key(Some(&key));
        }
        // The field never keeps or shows the stored key.
        self.inputs
            .curseforge
            .update(cx, |s, cx| s.set_value("", window, cx));
        notify(
            cx,
            NotificationKind::Success,
            "notification.settings.curseforgeKeySaved.title",
            "notification.settings.curseforgeKeySaved.body",
        );
        self.reread(cx);
        cx.notify();
    }

    fn clear_curseforge_key(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if let Some(e) = services::try_engine(cx) {
            e.set_curseforge_api_key(None);
        }
        self.inputs
            .curseforge
            .update(cx, |s, cx| s.set_value("", window, cx));
        self.reread(cx);
        cx.notify();
    }

    // ---- security ------------------------------------------------------------------------

    fn change_scan_folder(&mut self, cx: &mut Context<Self>) {
        let rx = cx.prompt_for_paths(PathPromptOptions {
            files: false,
            directories: true,
            multiple: false,
            prompt: Some(t!("settings.scanFolderDialog.title").into_owned().into()),
        });
        cx.spawn(async move |this, cx| match rx.await {
            Ok(Ok(Some(paths))) if !paths.is_empty() => {
                let folder = paths[0].clone();
                let _ = this.update(cx, |s, cx| {
                    s.scan.folder = folder;
                    cx.notify();
                });
            }
            _ => tracing::debug!("{}", t!("log.settings.noFolderSelected")),
        })
        .detach();
    }

    fn toggle_scan(&mut self, cx: &mut Context<Self>) {
        if self.scan.running {
            if let Some(e) = services::try_engine(cx) {
                e.cancel_scan();
            }
            return;
        }
        let folder = self.scan.folder.clone();
        let Some(task) = services::run(cx, move |e| async move { e.scan_folder(&folder).await })
        else {
            return;
        };
        self.scan.running = true;
        self.scan.output = None;
        self.scan.bar.update(cx, |b, cx| b.set_progress(-1.0, cx));
        cx.notify();
        self.tasks.push(cx.spawn(async move |this, cx| {
            let result = task.await;
            let _ = this.update(cx, |s, cx| {
                s.scan.running = false;
                s.scan.bar.update(cx, |b, cx| b.set_progress(1.0, cx));
                s.scan.output = Some(
                    match result {
                        Ok(Ok(summary)) => logic::scan_summary_text(&summary),
                        Ok(Err(e)) => {
                            tracing::warn!("{}", tf!("log.settings.scanFailed", e.message()));
                            t!("settings.scanOutputLabel.failed").into_owned()
                        }
                        Err(_) => t!("settings.scanOutputLabel.failed").into_owned(),
                    }
                    .into(),
                );
                cx.notify();
            });
        }));
    }

    fn rebuild_mcp_tools(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.mcp.tools.clear();
        self.mcp.tool_subs.clear();
        let Some(panel) = mcp::mcp_panel(cx) else {
            return;
        };
        let labels: Vec<String> = McpPolicy::ALL
            .iter()
            .map(|p| t!(p.label_key()).into_owned())
            .collect();
        for tool in panel.tools() {
            let state = new_select(labels.clone(), Some(tool.policy.index()), window, cx);
            let name = tool.name.clone();
            let panel = panel.clone();
            self.mcp.tool_subs.push(cx.subscribe_in(
                &state,
                window,
                move |_, _, ev: &SelectEvent<Vec<super::widgets::Opt>>, _, _| {
                    if let SelectEvent::Confirm(Some(i)) = ev {
                        panel.set_policy(&name, McpPolicy::ALL[*i % McpPolicy::ALL.len()]);
                    }
                },
            ));
            let label = tf!(
                "settings.mcp.tool.label",
                tool.title.as_str(),
                t!(&tool.risk_key).as_ref()
            );
            self.mcp.tools.push((tool.name, label.into(), state));
        }
    }

    fn render_snippet(&mut self, cx: &mut Context<Self>) {
        self.mcp.snippet = mcp::mcp_panel(cx).and_then(|panel| {
            let token = panel.ensure_token();
            let client = McpClient::ALL[self.mcp.client.min(McpClient::ALL.len() - 1)];
            let snippet = panel.setup_snippet(client, &token);
            if snippet.is_none() {
                tracing::warn!("{}", t!("log.settings.mcpSnippetFailed"));
            }
            snippet
        });
        cx.notify();
    }

    fn regenerate_mcp_token(&mut self, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let q = Question::new(
                t!("dialog.mcp.regenToken.title"),
                t!("dialog.mcp.regenToken.header"),
                t!("dialog.mcp.regenToken.body"),
                t!("dialog.mcp.regenToken.button.regenerate"),
                t!("dialog.button.cancel"),
            );
            if dialogs::ask(cx, &q, None).await != Answer::Primary {
                return;
            }
            let _ = this.update(cx, |s, cx| {
                if let Some(panel) = mcp::mcp_panel(cx) {
                    panel.regenerate_token();
                }
                s.mcp.copied = false;
                s.render_snippet(cx);
            });
        })
        .detach();
    }

    // ---- system --------------------------------------------------------------------------

    fn reset_launcher(&mut self, cx: &mut Context<Self>) {
        cx.spawn(async move |_, cx| {
            let running =
                cx.update(|cx| services::try_engine(cx).is_some_and(|e| e.has_active_sessions()));
            if running {
                dialogs::warning(cx, &t!("dialog.settings.resetLauncher.gamesRunning"), None).await;
                return;
            }
            let q = Question::new(
                t!("dialog.settings.resetLauncher.title"),
                t!("dialog.settings.resetLauncher.header"),
                t!("dialog.settings.resetLauncher.body"),
                t!("dialog.settings.resetLauncher.button.reset"),
                t!("dialog.settings.resetLauncher.button.back"),
            );
            if dialogs::ask(cx, &q, None).await != Answer::Primary {
                return;
            }
            let Some(task) =
                cx.update(|cx| services::run(cx, |e| async move { e.reset_launcher().await }))
            else {
                return;
            };
            match task.await {
                Ok(Ok(())) => cx.update(crate::shell::restart_app),
                Ok(Err(e)) => dialogs::error(cx, &e.message(), None).await,
                Err(_) => {}
            }
        })
        .detach();
    }

    fn reset_runtimes(&mut self, cx: &mut Context<Self>) {
        let dirty = self.has_unsaved_changes();
        let category = self.category;
        cx.spawn(async move |this, cx| {
            if dirty {
                let q = Self::unsaved_question(
                    "dialog.settings.unsavedOnResetRuntime",
                    "saveAndReset",
                    "resetOnly",
                );
                match dialogs::ask(cx, &q, None).await {
                    Answer::Primary => {
                        let _ = this.update(cx, |s, cx| s.persist_now(cx));
                    }
                    Answer::Secondary => {}
                    Answer::Closed => return,
                }
            }
            let q = Question::new(
                t!("dialog.settings.resetRuntime.title"),
                t!("dialog.settings.resetRuntime.header"),
                t!("dialog.settings.resetRuntime.body"),
                t!("dialog.settings.resetRuntime.button.reset"),
                t!("dialog.settings.resetRuntime.button.back"),
            );
            if dialogs::ask(cx, &q, None).await != Answer::Primary {
                return;
            }
            let Some(task) =
                cx.update(|cx| services::run(cx, |e| async move { e.remove_all_runtimes().await }))
            else {
                return;
            };
            for failed in task.await.unwrap_or_default() {
                tracing::warn!(
                    "{}",
                    tf!("log.settings.deleteRuntimeFailed", failed.as_str())
                );
            }
            tracing::info!("{}", t!("log.settings.runtimesCleared"));
            cx.update(|cx| {
                crate::shell::go_to(
                    cx,
                    ScreenRequest::with_payload(ScreenId::SETTINGS, category),
                )
            });
        })
        .detach();
    }

    fn flash(&mut self, export: bool, text: String, cx: &mut Context<Self>) {
        if export {
            self.export_flash = Some(text.into());
        } else {
            self.import_flash = Some(text.into());
        }
        cx.notify();
        self.tasks.push(cx.spawn(async move |this, cx| {
            cx.background_executor().timer(RESULT_FLASH).await;
            let _ = this.update(cx, |s, cx| {
                if export {
                    s.export_flash = None;
                } else {
                    s.import_flash = None;
                }
                cx.notify();
            });
        }));
    }

    fn export_settings(&mut self, cx: &mut Context<Self>) {
        let dir = self.root.clone();
        let name = t!("dialog.settings.exportSettings.defaultFilename").into_owned();
        let rx = cx.prompt_for_new_path(&dir, Some(&name));
        cx.spawn(async move |this, cx| {
            let Ok(Ok(Some(path))) = rx.await else {
                return;
            };
            let task = cx.update(|cx| {
                services::run(cx, move |e| async move { e.export_settings(&path).await })
            });
            let ok = match task {
                Some(t) => matches!(t.await, Ok(Ok(()))),
                None => false,
            };
            let text = if ok {
                t!("settings.exportBtn.success")
            } else {
                t!("settings.exportBtn.failed")
            }
            .into_owned();
            let _ = this.update(cx, |s, cx| s.flash(true, text, cx));
        })
        .detach();
    }

    fn import_settings(&mut self, cx: &mut Context<Self>) {
        let rx = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(t!("settings.fileChooser.importTitle").into_owned().into()),
        });
        let category = self.category;
        cx.spawn(async move |this, cx| {
            let Ok(Ok(Some(paths))) = rx.await else {
                return;
            };
            let Some(path) = paths.into_iter().next() else {
                return;
            };
            let task = cx.update(|cx| {
                services::run(cx, move |e| async move { e.import_settings(&path).await })
            });
            let ok = match task {
                Some(t) => matches!(t.await, Ok(Ok(()))),
                None => false,
            };
            if ok {
                // Reload Settings so every control shows the imported values.
                cx.update(|cx| {
                    crate::shell::go_to(
                        cx,
                        ScreenRequest::with_payload(ScreenId::SETTINGS, category),
                    )
                });
            } else {
                let _ = this.update(cx, |s, cx| {
                    s.flash(false, t!("settings.importBtn.failed").into_owned(), cx)
                });
            }
        })
        .detach();
    }

    fn open_launcher_folder(&self, cx: &mut Context<Self>) {
        if let Err(e) = std::fs::create_dir_all(&self.root) {
            tracing::warn!("{}: {e}", t!("log.settings.openLauncherFolderFailed"));
        }
        mmcl_ui_kit::open_folder(cx, &self.root);
    }

    // ---- RGB -----------------------------------------------------------------------------

    fn rgb_test(&mut self, cx: &mut Context<Self>) {
        let ran = services::try_engine(cx).is_some_and(|e| e.rgb_test());
        if !ran {
            notify(
                cx,
                NotificationKind::Warning,
                "notification.settings.rgbDisabled.title",
                "notification.settings.rgbDisabled.body",
            );
            return;
        }
        self.tasks.push(cx.spawn(async move |this, cx| {
            cx.background_executor().timer(RGB_TEST).await;
            let _ = this.update(cx, |s, cx| s.refresh_rgb_status(cx));
        }));
    }
}

// =============================================================================================
// Rendering

fn label(key: &str) -> SharedString {
    t!(key).into_owned().into()
}

fn some_label(key: &str) -> Option<SharedString> {
    Some(label(key))
}

fn link_button(id: &'static str, key: &str, url: &'static str) -> AnyElement {
    Button::new(id)
        .label(label(key))
        .outline()
        .on_click(move |_: &ClickEvent, _, cx| mmcl_ui_kit::open_external_url(cx, url))
        .into_any_element()
}

/// A label carrying a tooltip.
fn tip_label(id: &'static str, key: &str, tooltip_key: &str) -> impl IntoElement {
    div()
        .id(id)
        .child(label(key))
        .launcher_tooltip(label(tooltip_key))
}

impl SettingsScreen {
    fn render_sidebar(&self, cx: &mut Context<Self>) -> AnyElement {
        let t = cx.tokens();
        let mut col = div().flex().flex_col().gap(u(10.0)).p(u(10.0));
        for group_ids in NAV_GROUPS {
            let mut g = div().flex().flex_col().gap(u(2.0));
            for (pos_i, &i) in group_ids.iter().enumerate() {
                let c = CATEGORIES[i];
                let pos = logic::tile_position(pos_i, group_ids.len());
                let (top, bottom) = logic::tile_radii(pos, shape::LARGE, shape::EXTRA_SMALL);
                let selected = i == self.category;
                let (disc, glyph) = c.hue.colors(t);
                let hover = hsla(t.md_state_hover_on_surface);
                let summary = t!(&c.summary_key()).into_owned();
                g = g.child(
                    div()
                        .id(("settings-nav", i))
                        .w_full()
                        .flex()
                        .items_center()
                        .gap(u(10.0))
                        .px(u(10.0))
                        .py(u(8.0))
                        .rounded_t(u(top))
                        .rounded_b(u(bottom))
                        .bg(hsla(if selected {
                            t.md_secondary_container
                        } else {
                            t.md_surface_container_low
                        }))
                        .when(!selected, |d| d.hover(move |s| s.bg(hover)))
                        .when(pos == TilePosition::Only, |d| d)
                        .cursor_pointer()
                        .on_click(
                            cx.listener(move |s, _: &ClickEvent, _, cx| s.show_category(i, cx)),
                        )
                        .child(
                            div()
                                .flex_none()
                                .size(u(36.0))
                                .rounded_full()
                                .bg(hsla(disc))
                                .flex()
                                .items_center()
                                .justify_center()
                                .child(c.icon.svg(20.0).text_color(hsla(glyph))),
                        )
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .flex()
                                .flex_col()
                                .child(
                                    div()
                                        .type_role(TypeRole::TitleSmall)
                                        .text_color(hsla(t.color_text))
                                        .truncate()
                                        .child(label(c.title_key)),
                                )
                                .child(
                                    div()
                                        .type_role(TypeRole::BodySmall)
                                        .text_color(hsla(t.color_text_muted))
                                        .truncate()
                                        .child(summary),
                                ),
                        ),
                );
            }
            col = col.child(g);
        }
        div()
            .id("settings-sidebar")
            .flex_none()
            .w(u(260.0))
            .h_full()
            .overflow_y_scrollbar()
            .border_r_1()
            .border_color(hsla(t.color_border))
            .child(col)
            .into_any_element()
    }

    fn render_account(&mut self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let t = cx.tokens();
        let default = self.default_account.clone();
        let header = div()
            .flex()
            .items_center()
            .gap(u(12.0))
            .child(Avatar::new(
                "settings-avatar",
                default.as_ref().map(|a| a.uuid.clone()),
                48.0,
            ))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .type_role(TypeRole::TitleMedium)
                            .text_color(hsla(t.color_text))
                            .child(
                                default
                                    .as_ref()
                                    .map(|a| {
                                        if a.name.is_empty() {
                                            a.uuid.clone()
                                        } else {
                                            a.name.clone()
                                        }
                                    })
                                    .unwrap_or_else(|| t!("settings.fxml.player").into_owned()),
                            ),
                    )
                    .child(hint(
                        default
                            .as_ref()
                            .map(|a| tf!("settings.accountUuidLabel", a.uuid.as_str()))
                            .unwrap_or_else(|| t!("settings.fxml.uuid").into_owned()),
                        cx,
                    )),
            )
            .into_any_element();

        let mut account_rows: Vec<AnyElement> =
            vec![hint(label("settings.accounts.hint"), cx).into_any_element()];
        for (i, a) in self.accounts.clone().into_iter().enumerate() {
            account_rows.push(self.render_account_row(i, a, cx));
        }
        account_rows.push(
            Button::new("settings-add-account")
                .label(label("settings.accounts.add"))
                .primary()
                .on_click(|_: &ClickEvent, window, cx| open_add_account(window, cx))
                .into_any_element(),
        );

        let sign_out = default.map(|a| {
            let (uuid, name) = (a.uuid.clone(), a.name.clone());
            stacked_row(
                Button::new("settings-logout")
                    .label(label("settings.accounts.signOutDefault"))
                    .danger()
                    .on_click(cx.listener(move |s, _: &ClickEvent, _, cx| {
                        s.confirm_sign_out(uuid.clone(), name.clone(), cx)
                    })),
                some_label("settings.accounts.signOutHint"),
                cx,
            )
        });

        vec![
            group(vec![header], false, cx),
            section_title(label("settings.fxml.helpfulLinks"), cx).into_any_element(),
            group(
                vec![button_row(vec![
                    link_button(
                        "settings-mcnet",
                        "settings.fxml.minecraftNet",
                        MINECRAFT_PROFILE_URL,
                    ),
                    link_button(
                        "settings-msa",
                        "settings.fxml.microsoftAccount",
                        MICROSOFT_ACCOUNT_URL,
                    ),
                ])],
                false,
                cx,
            ),
            section_title(label("settings.accounts.title"), cx).into_any_element(),
            group(account_rows, false, cx),
            section_title(label("settings.fxml.signOut"), cx).into_any_element(),
            group(sign_out.into_iter().collect(), true, cx),
        ]
    }

    fn render_account_row(&self, i: usize, a: AccountView, cx: &mut Context<Self>) -> AnyElement {
        let t = cx.tokens();
        let needs_sign_in = a.status == AccountStatus::NeedsSignIn;
        let mut status = t!(a.status.l10n_key()).into_owned();
        if !a.remembered {
            status = tf!("account.status.withSessionOnly", status.as_str());
        }
        let can_make_default = !a.is_default && a.status == AccountStatus::Ready;
        let name = if a.name.is_empty() {
            a.uuid.clone()
        } else {
            a.name.clone()
        };
        let mut buttons: Vec<AnyElement> = Vec::new();
        if needs_sign_in {
            buttons.push(
                Button::new(("acct-signin", i))
                    .label(label("settings.accounts.signInAgain"))
                    .primary()
                    .on_click(|_: &ClickEvent, window, cx| open_add_account(window, cx))
                    .into_any_element(),
            );
        }
        if can_make_default {
            let uuid = a.uuid.clone();
            buttons.push(
                Button::new(("acct-default", i))
                    .label(label("settings.accounts.makeDefault"))
                    .outline()
                    .on_click(cx.listener(move |s, _: &ClickEvent, _, cx| {
                        if let Some(e) = services::try_engine(cx)
                            && let Err(err) = e.set_default_account(&uuid)
                        {
                            tracing::warn!("{}", err.message());
                        }
                        s.reload_accounts(cx);
                    }))
                    .into_any_element(),
            );
        }
        let (uuid, n) = (a.uuid.clone(), name.clone());
        buttons.push(
            Button::new(("acct-signout", i))
                .label(label("settings.accounts.signOut"))
                .danger()
                .on_click(cx.listener(move |s, _: &ClickEvent, _, cx| {
                    s.confirm_sign_out(uuid.clone(), n.clone(), cx)
                }))
                .into_any_element(),
        );
        div()
            .flex()
            .items_center()
            .gap(u(10.0))
            .w_full()
            .child(Avatar::new(("acct-avatar", i), Some(a.uuid.clone()), 32.0))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .font_weight(gpui::FontWeight::BOLD)
                            .text_color(hsla(t.color_text))
                            .truncate()
                            .child(name),
                    )
                    .child(
                        div()
                            .type_role(TypeRole::BodySmall)
                            .text_color(hsla(if needs_sign_in {
                                t.color_warning
                            } else {
                                t.color_text_muted
                            }))
                            .child(status),
                    ),
            )
            .children(buttons)
            .into_any_element()
    }

    fn render_game(&mut self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let jvm_hint = self.jvm_hint();
        vec![
            group(
                vec![
                    labeled_row(
                        tip_label(
                            "lbl-minram",
                            "settings.fxml.minimumRamGb",
                            "tooltip.settings.minRam",
                        ),
                        None,
                        div()
                            .w(u(160.0))
                            .child(NumberInput::new(&self.inputs.min_ram)),
                        cx,
                    ),
                    labeled_row(
                        tip_label(
                            "lbl-maxram",
                            "settings.fxml.maximumRamGb",
                            "tooltip.settings.maxRam",
                        ),
                        self.sys_ram.clone(),
                        div()
                            .w(u(160.0))
                            .child(NumberInput::new(&self.inputs.max_ram)),
                        cx,
                    ),
                ],
                false,
                cx,
            ),
            group(
                vec![
                    labeled_row(
                        tip_label(
                            "lbl-jvm",
                            "settings.fxml.jvmPreset",
                            "tooltip.settings.jvmPreset",
                        ),
                        None,
                        div().w(u(260.0)).child(Select::new(&self.selects.jvm)),
                        cx,
                    ),
                    stacked_row(
                        Button::new("settings-generate-jvm")
                            .label(label("settings.fxml.generateJvmArgs"))
                            .outline()
                            .tooltip(label("tooltip.settings.generateJvmArgs"))
                            .on_click(cx.listener(|s, _: &ClickEvent, window, cx| {
                                s.generate_jvm_args(window, cx)
                            })),
                        jvm_hint.map(Into::into),
                        cx,
                    ),
                ],
                false,
                cx,
            ),
        ]
    }

    fn render_appearance(&mut self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let t = cx.tokens();
        let current_scale = mmcl_ui_kit::scale::nearest(self.saved.ui_scale_percent);
        let sym = mmcl_i18n::number::NumberSymbols::for_locale(&mmcl_i18n::current_locale());
        let scale_buttons = div()
            .flex()
            .gap(u(2.0))
            .children(UI_SCALE_STEPS.iter().map(|&p| {
                let selected = p == current_scale;
                let mut b = Button::new(("ui-scale", p as usize))
                    .label(mmcl_i18n::number::format_percent_integer(p as i128, &sym))
                    .on_click(cx.listener(move |s, _: &ClickEvent, _, cx| {
                        if s.saved.ui_scale_percent != p {
                            s.set_live(Setting::UiScalePercent(p), cx);
                        }
                    }));
                b = if selected { b.primary() } else { b.outline() };
                b.into_any_element()
            }));
        let s = &self.saved;
        let rows_main = vec![
            labeled_row(
                tip_label("lbl-theme", "settings.fxml.theme", "tooltip.settings.theme"),
                None,
                div().w(u(220.0)).child(Select::new(&self.selects.theme)),
                cx,
            ),
            labeled_row(
                label("settings.appearance.uiScale"),
                some_label("settings.appearance.uiScale.hint"),
                scale_buttons,
                cx,
            ),
            labeled_row(
                label("settings.appearance.wavyProgress"),
                some_label("settings.appearance.wavyProgress.hint"),
                self.live_switch("sw-wavy", s.wavy_progress, Setting::WavyProgress, cx),
                cx,
            ),
            labeled_row(
                label("settings.appearance.reduceMotion"),
                some_label("settings.appearance.reduceMotion.hint"),
                self.live_switch(
                    "sw-reduce-motion",
                    s.reduce_motion,
                    Setting::ReduceMotion,
                    cx,
                ),
                cx,
            ),
        ];
        let rows_lang = vec![labeled_row(
            hint(label("settings.fxml.defaultsToYour"), cx),
            None,
            div().w(u(240.0)).child(Select::new(&self.selects.language)),
            cx,
        )];
        let rows_img = vec![
            labeled_row(
                label("settings.fxml.showPackBackground"),
                some_label("settings.fxml.whenOnModpack"),
                self.live_switch(
                    "sw-pack-bg",
                    s.show_pack_backgrounds,
                    Setting::ShowPackBackgrounds,
                    cx,
                ),
                cx,
            ),
            labeled_row(
                label("settings.appearance.packColors"),
                some_label("settings.appearance.packColors.hint"),
                self.live_switch("sw-pack-colors", s.pack_colors, Setting::PackColors, cx),
                cx,
            ),
            labeled_row(
                div()
                    .text_color(hsla(t.color_text))
                    .child(label("settings.fxml.imageCycleInterval")),
                None,
                div().w(u(200.0)).child(Select::new(&self.selects.cycle)),
                cx,
            ),
            labeled_row(
                label("settings.fxml.imageCycleShuffle"),
                None,
                self.live_switch(
                    "sw-shuffle",
                    s.image_cycle_shuffle,
                    Setting::ImageCycleShuffle,
                    cx,
                ),
                cx,
            ),
        ];
        vec![
            group(rows_main, false, cx),
            section_title(label("settings.fxml.language"), cx).into_any_element(),
            group(rows_lang, false, cx),
            section_title(label("settings.fxml.packImagery"), cx).into_any_element(),
            group(rows_img, false, cx),
        ]
    }

    fn render_advanced(&mut self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let f = self.form.clone();
        let s = self.saved.clone();
        let is_dev = self.is_dev;
        let main = vec![
            labeled_row(
                tip_label(
                    "lbl-debug",
                    "settings.fxml.debugMode",
                    "tooltip.settings.debug",
                ),
                None,
                self.form_switch("sw-debug", f.debug || is_dev, |f, v| f.debug = v, cx)
                    .disabled(is_dev),
                cx,
            ),
            labeled_row(
                tip_label(
                    "lbl-resize",
                    "settings.fxml.windowResizing",
                    "tooltip.settings.windowResize",
                ),
                None,
                self.form_switch(
                    "sw-resize",
                    f.resizable_windows,
                    |f, v| f.resizable_windows = v,
                    cx,
                ),
                cx,
            ),
            labeled_row(
                tip_label(
                    "lbl-enhlog",
                    "settings.fxml.enhancedLogging",
                    "tooltip.settings.enhancedLogging",
                ),
                None,
                self.form_switch(
                    "sw-enhlog",
                    f.enhanced_logging,
                    |f, v| f.enhanced_logging = v,
                    cx,
                ),
                cx,
            ),
            labeled_row(
                tip_label(
                    "lbl-console",
                    "settings.runningGames.keepOpen",
                    "tooltip.settings.inGameConsole",
                ),
                None,
                self.form_switch(
                    "sw-console",
                    f.in_game_console,
                    |f, v| f.in_game_console = v,
                    cx,
                ),
                cx,
            ),
            labeled_row(
                label("settings.fxml.consoleLogMaxLines"),
                None,
                div()
                    .w(u(200.0))
                    .child(Select::new(&self.selects.console_lines)),
                cx,
            ),
            labeled_row(
                tip_label(
                    "lbl-battery",
                    "settings.fxml.throttleDownloadsOn",
                    "tooltip.settings.batteryThrottle",
                ),
                None,
                self.form_switch(
                    "sw-battery",
                    f.battery_throttle,
                    |f, v| f.battery_throttle = v,
                    cx,
                ),
                cx,
            ),
            labeled_row(
                label("settings.fxml.lwjglArm64Patching"),
                some_label("settings.fxml.replacesX86_64Lwjgl2"),
                self.form_switch(
                    "sw-lwjgl",
                    f.lwjgl_arm_patch,
                    |f, v| f.lwjgl_arm_patch = v,
                    cx,
                ),
                cx,
            ),
        ];
        let scan = vec![labeled_row(
            hint(label("settings.fxml.howOftenThe"), cx),
            None,
            div()
                .w(u(220.0))
                .child(Select::new(&self.selects.scan_frequency)),
            cx,
        )];
        let backups = vec![
            hint(label("settings.fxml.backupBlurb"), cx).into_any_element(),
            labeled_row(
                label("settings.fxml.autoBackupBeforeUpdate"),
                None,
                self.live_switch(
                    "sw-autobackup",
                    s.backup_auto_before_update,
                    Setting::BackupAutoBeforeUpdate,
                    cx,
                ),
                cx,
            ),
            labeled_row(
                label("settings.fxml.backupIncludeSaves"),
                some_label("settings.fxml.backupIncludeSavesHint"),
                self.live_switch(
                    "sw-backup-saves",
                    s.backup_include_saves,
                    Setting::BackupIncludeSaves,
                    cx,
                ),
                cx,
            ),
            labeled_row(
                label("settings.fxml.backupMaxCount"),
                None,
                div()
                    .w(u(140.0))
                    .child(NumberInput::new(&self.inputs.backup_count)),
                cx,
            ),
            labeled_row(
                label("settings.fxml.backupMaxAgeDays"),
                some_label("settings.fxml.backupCountAgeHint"),
                div()
                    .w(u(140.0))
                    .child(NumberInput::new(&self.inputs.backup_age)),
                cx,
            ),
        ];
        let integrity = vec![stacked_row(
            Button::new("settings-verify-all")
                .label(label("settings.fxml.verifyAllGame"))
                .outline()
                .on_click(cx.listener(|s, _: &ClickEvent, _, cx| s.verify_all(cx))),
            some_label("settings.fxml.reHashesEvery"),
            cx,
        )];
        let cf_status = if self.has_curseforge_key {
            "settings.curseforge.keyConfigured"
        } else {
            "settings.curseforge.keyNotConfigured"
        };
        let third_party = vec![
            hint(label("settings.fxml.curseforgeCoreApi"), cx).into_any_element(),
            div()
                .flex()
                .items_center()
                .gap(u(8.0))
                .child(
                    div()
                        .flex_1()
                        .child(Input::new(&self.inputs.curseforge).mask_toggle()),
                )
                .child(
                    Button::new("cf-save")
                        .label(label("settings.fxml.save"))
                        .primary()
                        .on_click(cx.listener(|s, _: &ClickEvent, window, cx| {
                            s.save_curseforge_key(window, cx)
                        })),
                )
                .child(
                    Button::new("cf-clear")
                        .label(label("settings.fxml.clear"))
                        .outline()
                        .disabled(!self.has_curseforge_key)
                        .on_click(cx.listener(|s, _: &ClickEvent, window, cx| {
                            s.clear_curseforge_key(window, cx)
                        })),
                )
                .into_any_element(),
            hint(label(cf_status), cx).into_any_element(),
        ];
        vec![
            group(main, false, cx),
            section_title(label("settings.fxml.securityScanFrequency"), cx).into_any_element(),
            group(scan, false, cx),
            section_title(label("settings.fxml.backupHeader"), cx).into_any_element(),
            group(backups, false, cx),
            section_title(label("settings.fxml.gameFileIntegrity"), cx).into_any_element(),
            group(integrity, false, cx),
            section_title(label("settings.fxml.thirdPartyIntegrations"), cx).into_any_element(),
            group(third_party, false, cx),
        ]
    }

    fn render_network(&mut self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let f = self.form.clone();
        vec![group(
            vec![
                labeled_row(
                    tip_label(
                        "lbl-proxy-enable",
                        "settings.fxml.enableProxy",
                        "tooltip.settings.proxyEnable",
                    ),
                    None,
                    self.form_switch("sw-proxy", f.proxy_enable, |f, v| f.proxy_enable = v, cx),
                    cx,
                ),
                labeled_row(
                    tip_label(
                        "lbl-proxy-type",
                        "settings.fxml.proxyType",
                        "tooltip.settings.proxyType",
                    ),
                    None,
                    div()
                        .w(u(160.0))
                        .child(Select::new(&self.selects.proxy_type)),
                    cx,
                ),
                labeled_row(
                    tip_label(
                        "lbl-proxy-host",
                        "settings.fxml.proxyHost",
                        "tooltip.settings.proxyHost",
                    ),
                    None,
                    div().w(u(260.0)).child(Input::new(&self.inputs.proxy_host)),
                    cx,
                ),
                labeled_row(
                    tip_label(
                        "lbl-proxy-port",
                        "settings.fxml.proxyPort",
                        "tooltip.settings.proxyPort",
                    ),
                    None,
                    div()
                        .w(u(160.0))
                        .child(NumberInput::new(&self.inputs.proxy_port)),
                    cx,
                ),
            ],
            false,
            cx,
        )]
    }

    fn render_security(&mut self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let t = cx.tokens();
        let running = self.scan.running;
        let mut scan_rows = vec![
            hint(
                tf!(
                    "settings.scanFolderLabel",
                    self.scan.folder.display().to_string()
                ),
                cx,
            )
            .into_any_element(),
            button_row(vec![
                Button::new("scan-start")
                    .label(label(if running {
                        "settings.scanBtn.cancel"
                    } else {
                        "settings.scanBtn.label"
                    }))
                    .primary()
                    .on_click(cx.listener(|s, _: &ClickEvent, _, cx| s.toggle_scan(cx)))
                    .into_any_element(),
                Button::new("scan-folder")
                    .label(label("settings.fxml.changeFolder"))
                    .outline()
                    .disabled(running)
                    .when(running, |b| b.invisible())
                    .on_click(cx.listener(|s, _: &ClickEvent, _, cx| s.change_scan_folder(cx)))
                    .into_any_element(),
            ]),
        ];
        if running || self.scan.output.is_some() {
            if running {
                scan_rows.push(
                    div()
                        .h(u(16.0))
                        .w_full()
                        .child(self.scan.bar.clone())
                        .into_any_element(),
                );
            }
            if let Some(out) = self.scan.output.clone() {
                scan_rows.push(
                    div()
                        .type_role(TypeRole::BodySmall)
                        .text_color(hsla(t.color_text))
                        .child(out)
                        .into_any_element(),
                );
            }
        }
        let s = self.saved.clone();
        let deep_links = vec![labeled_row(
            label("settings.fxml.allowMmclDeep"),
            some_label("settings.fxml.whenEnabledQuot"),
            self.live_switch(
                "sw-uri",
                s.uri_handler_enabled,
                Setting::UriHandlerEnabled,
                cx,
            ),
            cx,
        )];
        let mut out = vec![
            section_title(label("settings.fxml.malwareScanning"), cx).into_any_element(),
            group(scan_rows, false, cx),
            section_title(label("settings.fxml.deepLinksMmcl"), cx).into_any_element(),
            group(deep_links, false, cx),
            section_title(label("settings.mcp.section.title"), cx).into_any_element(),
        ];
        out.extend(self.render_mcp(cx));
        out
    }

    fn render_mcp(&mut self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let t = cx.tokens();
        let s = self.saved.clone();
        let panel = mcp::mcp_panel(cx);
        let running = panel.as_ref().is_some_and(|p| p.is_running());
        let sessions = panel.as_ref().map(|p| p.sessions()).unwrap_or_default();
        let status = if running {
            format!(
                "{}  \u{2022}  {}",
                tf!("settings.mcp.status.running", s.mcp_port.to_string()),
                tf!("settings.mcp.status.sessions", sessions.len() as i64)
            )
        } else {
            t!("settings.mcp.status.stopped").into_owned()
        };
        let toggles = vec![
            labeled_row(
                label("settings.mcp.enable.label"),
                some_label("settings.mcp.enable.description"),
                self.live_switch(
                    "sw-mcp",
                    s.mcp_server_enabled,
                    Setting::McpServerEnabled,
                    cx,
                ),
                cx,
            ),
            labeled_row(
                label("settings.mcp.autoApproveReadOnly.label"),
                some_label("settings.mcp.autoApproveReadOnly.description"),
                self.live_switch(
                    "sw-mcp-ro",
                    s.mcp_auto_approve_read_only,
                    Setting::McpAutoApproveReadOnly,
                    cx,
                ),
                cx,
            ),
            labeled_row(
                label("settings.mcp.allowStateChanges.label"),
                some_label("settings.mcp.allowStateChanges.description"),
                self.live_switch(
                    "sw-mcp-rw",
                    s.mcp_allow_state_changes,
                    Setting::McpAllowStateChanges,
                    cx,
                ),
                cx,
            ),
            hint(status, cx).into_any_element(),
        ];
        let mut out = vec![group(toggles, false, cx)];
        let Some(panel) = panel else {
            return out;
        };

        // Connecting a client.
        let token_text = if self.mcp.token_revealed {
            panel.ensure_token()
        } else {
            panel.masked_token()
        };
        let mono = mmcl_ui_kit::theme::font_family(mmcl_theme::typography::Family::Mono);
        let snippet = self.mcp.snippet.clone().unwrap_or_default();
        let connect = vec![
            hint(label("settings.mcp.connect.description"), cx).into_any_element(),
            labeled_row(
                label("settings.mcp.connect.endpoint"),
                None,
                div().font_family(mono).child(panel.endpoint_url()),
                cx,
            ),
            labeled_row(
                label("settings.mcp.connect.token"),
                None,
                div()
                    .flex()
                    .items_center()
                    .gap(u(8.0))
                    .child(div().font_family(mono).child(token_text))
                    .child(
                        Button::new("mcp-reveal")
                            .label(label(if self.mcp.token_revealed {
                                "settings.mcp.connect.token.hide"
                            } else {
                                "settings.mcp.connect.token.reveal"
                            }))
                            .ghost()
                            .on_click(cx.listener(|s, _: &ClickEvent, _, cx| {
                                s.mcp.token_revealed = !s.mcp.token_revealed;
                                cx.notify();
                            })),
                    )
                    .child(
                        Button::new("mcp-copy-token")
                            .label(label("settings.mcp.connect.token.copy"))
                            .ghost()
                            .on_click(cx.listener(|_, _: &ClickEvent, _, cx| {
                                if let Some(p) = mcp::mcp_panel(cx) {
                                    cx.write_to_clipboard(ClipboardItem::new_string(
                                        p.ensure_token(),
                                    ));
                                }
                            })),
                    )
                    .child(
                        Button::new("mcp-regen")
                            .label(label("settings.mcp.connect.token.regenerate"))
                            .ghost()
                            .on_click(
                                cx.listener(|s, _: &ClickEvent, _, cx| s.regenerate_mcp_token(cx)),
                            ),
                    ),
                cx,
            ),
            div()
                .flex()
                .items_center()
                .gap(u(8.0))
                .child(
                    div()
                        .w(u(220.0))
                        .child(Select::new(&self.selects.mcp_client)),
                )
                .child(
                    Button::new("mcp-copy-setup")
                        .label(label(if self.mcp.copied {
                            "settings.mcp.connect.copied"
                        } else {
                            "settings.mcp.connect.copy"
                        }))
                        .outline()
                        .disabled(snippet.is_empty())
                        .on_click(cx.listener(|s, _: &ClickEvent, _, cx| {
                            if let Some(text) = s.mcp.snippet.clone() {
                                cx.write_to_clipboard(ClipboardItem::new_string(text));
                                s.mcp.copied = true;
                                cx.notify();
                            }
                        })),
                )
                .into_any_element(),
            div()
                .id("mcp-snippet")
                .w_full()
                .max_h(u(200.0))
                .overflow_y_scrollbar()
                .p(u(10.0))
                .rounded(u(shape::SMALL))
                .bg(hsla(t.md_surface_container_highest))
                .font_family(mono)
                .text_size(u(12.0))
                .text_color(hsla(t.color_text))
                .child(snippet)
                .into_any_element(),
        ];
        out.push(section_title(label("settings.mcp.connect.title"), cx).into_any_element());
        out.push(group(connect, false, cx));

        // Tool permissions.
        let mut perms =
            vec![hint(label("settings.mcp.permissions.description"), cx).into_any_element()];
        for (_, tool_label, state) in &self.mcp.tools {
            perms.push(labeled_row(
                div().min_w(u(240.0)).child(tool_label.clone()),
                None,
                div().w(u(180.0)).child(Select::new(state)),
                cx,
            ));
        }
        out.push(section_title(label("settings.mcp.permissions.title"), cx).into_any_element());
        out.push(group(perms, false, cx));

        // Sessions and activity.
        let mut session_rows: Vec<AnyElement> = sessions
            .iter()
            .map(|s| {
                let name = s
                    .name
                    .clone()
                    .unwrap_or_else(|| t!("settings.mcp.sessions.unnamed").into_owned());
                hint(
                    tf!(
                        "settings.mcp.sessions.entry",
                        name.as_str(),
                        logic::elapsed_text(s.age_ms).as_str(),
                        s.calls as i64
                    ),
                    cx,
                )
                .into_any_element()
            })
            .collect();
        if session_rows.is_empty() {
            session_rows.push(hint(label("settings.mcp.sessions.none"), cx).into_any_element());
        }
        let mut activity_rows: Vec<AnyElement> = panel
            .activity(ACTIVITY_ROWS_SHOWN)
            .iter()
            .map(|a| {
                let decision =
                    t!(&format!("settings.mcp.activity.decision.{}", a.decision)).into_owned();
                hint(
                    tf!(
                        "settings.mcp.activity.entry",
                        logic::elapsed_text(a.age_ms).as_str(),
                        a.tool.as_str(),
                        decision.as_str()
                    ),
                    cx,
                )
                .into_any_element()
            })
            .collect();
        if activity_rows.is_empty() {
            activity_rows.push(hint(label("settings.mcp.activity.none"), cx).into_any_element());
        }
        activity_rows.push(button_row(vec![
            Button::new("mcp-refresh")
                .label(label("settings.mcp.refresh.label"))
                .icon(mmcl_ui_kit::Icon::Refresh)
                .outline()
                .on_click(cx.listener(|s, _: &ClickEvent, window, cx| {
                    s.rebuild_mcp_tools(window, cx);
                    s.render_snippet(cx);
                }))
                .into_any_element(),
            Button::new("mcp-clear-activity")
                .label(label("settings.mcp.activity.clear"))
                .outline()
                .on_click(cx.listener(|_, _: &ClickEvent, _, cx| {
                    if let Some(p) = mcp::mcp_panel(cx) {
                        p.clear_activity();
                    }
                    cx.notify();
                }))
                .into_any_element(),
        ]));
        let grants = vec![
            hint(label("settings.mcp.revokeGrants.description"), cx).into_any_element(),
            labeled_row(
                tf!("settings.mcp.grants.count", panel.grant_count() as i64),
                None,
                Button::new("mcp-revoke")
                    .label(label("settings.mcp.revokeGrants.label"))
                    .danger()
                    .on_click(cx.listener(|_, _: &ClickEvent, _, cx| {
                        if let Some(p) = mcp::mcp_panel(cx) {
                            p.revoke_grants();
                        }
                        cx.notify();
                    })),
                cx,
            ),
        ];
        out.push(section_title(label("settings.mcp.sessions.title"), cx).into_any_element());
        out.push(group(session_rows, false, cx));
        out.push(section_title(label("settings.mcp.activity.title"), cx).into_any_element());
        out.push(group(activity_rows, false, cx));
        out.push(group(grants, false, cx));
        out
    }

    fn render_system(&mut self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let reset = vec![button_row(vec![
            Button::new("sys-reset-launcher")
                .label(label("settings.fxml.resetLauncher"))
                .danger()
                .tooltip(label("tooltip.settings.resetLauncher"))
                .on_click(cx.listener(|s, _: &ClickEvent, _, cx| s.reset_launcher(cx)))
                .into_any_element(),
            Button::new("sys-reset-runtimes")
                .label(label("settings.fxml.resetRuntimes"))
                .danger()
                .outline()
                .tooltip(label("tooltip.settings.resetRuntime"))
                .on_click(cx.listener(|s, _: &ClickEvent, _, cx| s.reset_runtimes(cx)))
                .into_any_element(),
            Button::new("sys-manage-runtimes")
                .label(label("settings.fxml.manageRuntimes"))
                .icon(mmcl_ui_kit::Icon::Runtime)
                .outline()
                .on_click(|_: &ClickEvent, _, cx| {
                    crate::shell::navigate_gated(cx, ScreenRequest::new(ScreenId::RUNTIME))
                })
                .into_any_element(),
        ])];
        let export_label = self
            .export_flash
            .clone()
            .unwrap_or_else(|| label("settings.exportBtn.label"));
        let import_label = self
            .import_flash
            .clone()
            .unwrap_or_else(|| label("settings.importBtn.label"));
        let io = vec![button_row(vec![
            Button::new("sys-export")
                .label(export_label)
                .outline()
                .tooltip(label("tooltip.settings.exportSettings"))
                .on_click(cx.listener(|s, _: &ClickEvent, _, cx| s.export_settings(cx)))
                .into_any_element(),
            Button::new("sys-import")
                .label(import_label)
                .outline()
                .tooltip(label("tooltip.settings.importSettings"))
                .on_click(cx.listener(|s, _: &ClickEvent, _, cx| s.import_settings(cx)))
                .into_any_element(),
            Button::new("sys-open-folder")
                .label(label("settings.fxml.openLauncherFolder"))
                .outline()
                .on_click(cx.listener(|s, _: &ClickEvent, _, cx| s.open_launcher_folder(cx)))
                .into_any_element(),
        ])];
        vec![
            section_title(label("settings.fxml.resetAmpRuntime"), cx).into_any_element(),
            group(reset, true, cx),
            section_title(label("settings.fxml.importExport"), cx).into_any_element(),
            group(io, false, cx),
            hint(self.version_label(), cx).into_any_element(),
        ]
    }

    fn version_label(&self) -> String {
        if self.is_dev {
            t!("settings.versionLabel.devMode").into_owned()
        } else {
            tf!("settings.versionLabel.normal", self.version.as_ref())
        }
    }

    fn render_discord(&mut self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let f = self.form.clone();
        vec![
            section_title(label("settings.fxml.richPresence"), cx).into_any_element(),
            group(
                vec![labeled_row(
                    tip_label(
                        "lbl-discord",
                        "settings.fxml.discordIntegration",
                        "tooltip.settings.discord",
                    ),
                    some_label("settings.fxml.showWhatYou"),
                    self.form_switch("sw-discord", f.discord_rpc, |f, v| f.discord_rpc = v, cx),
                    cx,
                )],
                false,
                cx,
            ),
            section_title(label("settings.fxml.friendInvites"), cx).into_any_element(),
            group(
                vec![
                    labeled_row(
                        tip_label(
                            "lbl-invites",
                            "settings.fxml.allowDiscordInvites",
                            "tooltip.settings.discordInvites",
                        ),
                        some_label("settings.fxml.whenEnabledFriends"),
                        self.form_switch(
                            "sw-invites",
                            f.discord_invites,
                            |f, v| f.discord_invites = v,
                            cx,
                        ),
                        cx,
                    ),
                    hint(label("settings.fxml.youCanAlso"), cx).into_any_element(),
                ],
                false,
                cx,
            ),
        ]
    }

    fn render_rgb(&mut self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let t = cx.tokens();
        let s = self.saved.clone();
        let chip = {
            let (text, bg, fg) = match &self.rgb_status {
                Some(st) if st.enabled => {
                    let (bg, fg) = match st.tone {
                        ChipTone::Success => (t.md_success_container, t.md_on_success_container),
                        ChipTone::Warn => (t.md_warning_container, t.md_on_warning_container),
                        ChipTone::Danger => (t.md_error_container, t.md_on_error_container),
                        ChipTone::Muted => (t.md_surface_container_high, t.md_on_surface_variant),
                    };
                    (st.text.clone(), bg, fg)
                }
                _ => (
                    t!("settings.rgb.status.disabled").into_owned(),
                    t.md_surface_container_high,
                    t.md_on_surface_variant,
                ),
            };
            div()
                .px(u(10.0))
                .py(u(2.0))
                .rounded_full()
                .bg(hsla(bg))
                .text_color(hsla(fg))
                .type_role(TypeRole::LabelMedium)
                .child(text)
        };
        let mut backend_rows = vec![
            labeled_row(
                label("settings.fxml.enableRgbLighting"),
                some_label("settings.fxml.whenEnabledThe"),
                self.live_switch("sw-rgb", s.rgb_enable, Setting::RgbEnable, cx),
                cx,
            ),
            labeled_row(
                label("settings.fxml.backend"),
                some_label("settings.fxml.autoRunsEvery"),
                div()
                    .flex()
                    .items_center()
                    .gap(u(8.0))
                    .child(
                        div()
                            .w(u(240.0))
                            .child(Select::new(&self.selects.rgb_backend)),
                    )
                    .child(chip),
                cx,
            ),
        ];
        let auto = s.rgb_enable && logic::rgb_backend_index(&s.rgb_backend) == 0;
        if auto {
            backend_rows.push(
                div()
                    .type_role(TypeRole::TitleSmall)
                    .text_color(hsla(t.color_text))
                    .child(label("settings.fxml.autoModeIntegrations"))
                    .into_any_element(),
            );
            let toggles: [AutoToggle; 6] = [
                (
                    "sw-rgb-openrgb",
                    "settings.fxml.openrgb",
                    s.rgb_enable_open_rgb,
                    Setting::RgbEnableOpenRgb,
                ),
                (
                    "sw-rgb-chroma-native",
                    "settings.fxml.razerChromaNative",
                    s.rgb_enable_chroma_native,
                    Setting::RgbEnableChromaNative,
                ),
                (
                    "sw-rgb-windows-dl",
                    "settings.fxml.windowsDynamicLighting",
                    s.rgb_enable_windows_dl,
                    Setting::RgbEnableWindowsDl,
                ),
                (
                    "sw-rgb-corsair",
                    "settings.fxml.corsairIcue",
                    s.rgb_enable_corsair,
                    Setting::RgbEnableCorsair,
                ),
                (
                    "sw-rgb-asus",
                    "settings.fxml.asusAuraLegacy",
                    s.rgb_enable_asus_aura,
                    Setting::RgbEnableAsusAura,
                ),
                (
                    "sw-rgb-chroma-rest",
                    "settings.fxml.razerChromaRest",
                    s.rgb_enable_chroma_rest,
                    Setting::RgbEnableChromaRest,
                ),
            ];
            for (id, key, checked, make) in toggles {
                backend_rows.push(labeled_row(
                    label(key),
                    None,
                    self.live_switch(id, checked, make, cx),
                    cx,
                ));
            }
            backend_rows.push(hint(label("settings.fxml.turnOffThe"), cx).into_any_element());
        }
        vec![
            section_title(label("settings.fxml.lightingBackend"), cx).into_any_element(),
            group(backend_rows, false, cx),
            section_title(label("settings.fxml.menuEffect"), cx).into_any_element(),
            group(
                vec![
                    labeled_row(
                        label("settings.fxml.lightUpDevices"),
                        some_label("settings.fxml.whenOnYour"),
                        self.live_switch(
                            "sw-rgb-menu",
                            s.rgb_menu_effect_enable,
                            Setting::RgbMenuEffectEnable,
                            cx,
                        ),
                        cx,
                    ),
                    labeled_row(
                        label("settings.fxml.animationStyle"),
                        some_label("settings.fxml.solidSingleSteady"),
                        div()
                            .w(u(180.0))
                            .child(Select::new(&self.selects.rgb_style)),
                        cx,
                    ),
                ],
                false,
                cx,
            ),
            section_title(label("settings.fxml.inGameEffects"), cx).into_any_element(),
            group(
                vec![
                    labeled_row(
                        label("settings.fxml.useModpackColors"),
                        some_label("settings.fxml.lightUpThe"),
                        self.live_switch(
                            "sw-rgb-pack",
                            s.rgb_use_pack_colors,
                            Setting::RgbUsePackColors,
                            cx,
                        ),
                        cx,
                    ),
                    labeled_row(
                        label("settings.fxml.highlightMinecraftKeys"),
                        some_label("settings.fxml.overlayWasdE"),
                        self.live_switch(
                            "sw-rgb-keys",
                            s.rgb_highlight_keys,
                            Setting::RgbHighlightKeys,
                            cx,
                        ),
                        cx,
                    ),
                ],
                false,
                cx,
            ),
            section_title(label("settings.fxml.test"), cx).into_any_element(),
            group(
                vec![stacked_row(
                    Button::new("rgb-test")
                        .label(label("settings.fxml.testConnection"))
                        .outline()
                        .on_click(cx.listener(|s, _: &ClickEvent, _, cx| s.rgb_test(cx))),
                    some_label("settings.fxml.flashesSolidMagenta"),
                    cx,
                )],
                false,
                cx,
            ),
        ]
    }

    fn render_about(&mut self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let t = cx.tokens();
        let s = self.saved.clone();
        let header = div()
            .flex()
            .flex_col()
            .gap(u(2.0))
            .child(
                div()
                    .type_role(TypeRole::TitleLarge)
                    .text_color(hsla(t.color_text))
                    .child(self.app_name.clone()),
            )
            .child(hint(
                tf!("settings.aboutVersionLabel", self.version.as_ref()),
                cx,
            ))
            .child(hint(label("settings.fxml.copyright"), cx))
            .into_any_element();
        let text_block = |key: &str, cx: &App| {
            div()
                .type_role(TypeRole::BodySmall)
                .text_color(hsla(cx.tokens().color_text_muted))
                .whitespace_normal()
                .children(
                    t!(key)
                        .lines()
                        .map(|l| div().child(l.to_string()))
                        .collect::<Vec<_>>(),
                )
                .into_any_element()
        };
        vec![
            group(
                vec![
                    header,
                    labeled_row(
                        label("settings.fxml.checkForLauncher"),
                        some_label("settings.fxml.whenOffThe"),
                        self.live_switch(
                            "sw-update-check",
                            s.launcher_update_check,
                            Setting::LauncherUpdateCheck,
                            cx,
                        ),
                        cx,
                    ),
                    button_row(vec![
                        link_button("about-website", "settings.fxml.visitWebsite", WEBSITE_URL),
                        link_button("about-source", "settings.fxml.viewSource", SOURCE_URL),
                    ]),
                ],
                false,
                cx,
            ),
            section_title(label("settings.fxml.minecraftTrademark"), cx).into_any_element(),
            group(
                vec![text_block("settings.fxml.minecraftIsA", cx)],
                false,
                cx,
            ),
            section_title(label("settings.fxml.openSourceAcknowledgments"), cx).into_any_element(),
            group(
                vec![
                    text_block("settings.fxml.theLauncherUses", cx),
                    text_block("settings.about.ossLibraryList", cx),
                    text_block("settings.fxml.fullLicenseTexts", cx),
                ],
                false,
                cx,
            ),
            section_title(label("settings.fxml.javaRuntimes"), cx).into_any_element(),
            group(
                vec![text_block("settings.fxml.modernMinecraftVersions", cx)],
                false,
                cx,
            ),
            section_title(label("settings.fxml.arm64NativeLibraries"), cx).into_any_element(),
            group(
                vec![
                    text_block("settings.fxml.whenLwjglArm", cx),
                    text_block("settings.fxml.arm64LibraryList", cx),
                ],
                false,
                cx,
            ),
        ]
    }

    fn render_pane(&mut self, cx: &mut Context<Self>) -> AnyElement {
        let c = CATEGORIES[self.category];
        let body = match self.category {
            category::ACCOUNT => self.render_account(cx),
            category::GAME => self.render_game(cx),
            category::APPEARANCE => self.render_appearance(cx),
            category::ADVANCED => self.render_advanced(cx),
            category::NETWORK => self.render_network(cx),
            category::SECURITY => self.render_security(cx),
            category::SYSTEM => self.render_system(cx),
            category::DISCORD => self.render_discord(cx),
            category::RGB => self.render_rgb(cx),
            _ => self.render_about(cx),
        };
        div()
            .id(("settings-pane", self.category))
            .flex_1()
            .min_w_0()
            .h_full()
            .overflow_y_scrollbar()
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(u(8.0))
                    .px(u(24.0))
                    .py(u(16.0))
                    .max_w(u(900.0))
                    .child(category_header(label(c.title_key), cx))
                    .children(body),
            )
            .with_animation(
                ("settings-pane-fade", self.category),
                gpui::Animation::new(
                    mmcl_ui_kit::motion::exit_delay().max(Duration::from_millis(1)),
                ),
                |el, delta| el.opacity(delta),
            )
            .into_any_element()
    }
}

impl Screen for SettingsScreen {
    fn id(&self) -> ScreenId {
        ScreenId::SETTINGS
    }

    fn title(&self, _cx: &App) -> SharedString {
        label("main.navbar.settings")
    }

    fn help_topic(&self) -> HelpTopic {
        HelpTopic::Settings
    }

    fn min_size(&self) -> Size<Pixels> {
        size(px(750.0), px(475.0))
    }

    fn has_own_help_button(&self) -> bool {
        false
    }

    fn after_show(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.load_system_memory(cx);
        self.refresh_rgb_status(cx);
        self.rebuild_mcp_tools(window, cx);
        self.render_snippet(cx);
    }

    fn cleanup(&mut self, _cx: &mut Context<Self>) {
        self.disposed = true;
        self.subs.clear();
        self.mcp.tool_subs.clear();
        self.tasks.clear();
    }

    fn confirm_navigate_away(
        &mut self,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Task<bool> {
        if !self.has_unsaved_changes() {
            return Task::ready(true);
        }
        cx.spawn(async move |this, cx| {
            let q = Self::unsaved_question("dialog.settings.unsavedOnReturn", "save", "return");
            match dialogs::ask(cx, &q, None).await {
                Answer::Primary => {
                    // Persist synchronously before the screen goes away.
                    let _ = this.update(cx, |s, cx| s.persist_now(cx));
                    true
                }
                Answer::Secondary => true,
                Answer::Closed => false,
            }
        })
    }

    fn on_close_requested(
        &mut self,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) -> CloseDecision {
        if !self.has_unsaved_changes() {
            return CloseDecision::Proceed;
        }
        cx.spawn(async move |this, cx| {
            let q = Self::unsaved_question(
                "dialog.settings.unsavedOnClose",
                "saveAndClose",
                "closeWithoutSaving",
            );
            match dialogs::ask(cx, &q, None).await {
                Answer::Primary => {
                    let _ = this.update(cx, |s, cx| s.persist_now(cx));
                }
                Answer::Secondary => {}
                Answer::Closed => return,
            }
            cx.update(crate::shell::close_app);
        })
        .detach();
        CloseDecision::Keep
    }
}

impl Render for SettingsScreen {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let pane = self.render_pane(cx);
        let sidebar = self.render_sidebar(cx);
        let restart_visible = self.form.language_change_pending(&self.saved);
        let busy = self.busy || self.scan.running;
        let save_label = if self.saved_flash {
            label("settings.saveBtn.saved")
        } else {
            label("settings.saveBtn.label")
        };
        let mut trailing = vec![
            Button::new("settings-save")
                .label(save_label)
                .primary()
                .disabled(busy)
                .on_click(cx.listener(|s, _: &ClickEvent, _, cx| s.save(cx)))
                .into_any_element(),
        ];
        if restart_visible {
            trailing.push(
                Button::new("settings-save-restart")
                    .label(label("settings.fxml.saveAndRestart"))
                    .outline()
                    .disabled(busy)
                    .on_click(cx.listener(|s, _: &ClickEvent, _, cx| s.save_and_restart(cx)))
                    .into_any_element(),
            );
        }
        trailing.push(
            Button::new("settings-return")
                .label(label("settings.fxml.return"))
                .outline()
                .disabled(busy)
                .on_click(cx.listener(|s, _: &ClickEvent, _, cx| s.return_home(cx)))
                .into_any_element(),
        );
        let announcement = self.announcement.clone();
        let this = cx.entity().downgrade();
        div()
            .size_full()
            .flex()
            .flex_col()
            .child(
                NavBar::new(label("settings.fxml.settings"))
                    .action(OfflineChip::new("settings-offline")),
            )
            .child(
                AnnouncementBanner::new("settings-announcement", announcement).on_dismiss(
                    move |_, cx| {
                        let _ = this.update(cx, |s, cx| {
                            if let (Some(e), Some(text)) =
                                (services::try_engine(cx), s.announcement.take())
                            {
                                e.dismiss_announcement(&text);
                            }
                            cx.notify();
                        });
                    },
                ),
            )
            .child(div().flex_1().min_h_0().flex().child(sidebar).child(pane))
            .child(bottom_bar(
                hint(self.version_label(), cx).into_any_element(),
                trailing,
                cx,
            ))
    }
}

/// One RGB auto-mode integration switch: id, label key, state, and the setting it writes.
type AutoToggle = (&'static str, &'static str, bool, fn(bool) -> Setting);

// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pure rules of the Settings and Runtime Management screens (no GPUI): JVM presets,
//! language override, proxy and compressed-oops validation, option labels, the sidebar tile
//! positions, the Save form with its unsaved-changes check and persist plan, MCP elapsed-time
//! text, scan output clean-up and the runtime list text.
//!
//! Java origins: `MCLauncherSettingsGui` (static helpers), `SettingsOptionLabels`,
//! `SettingsNavTile`, `MCLauncherRuntimeGui`.

use mmcl_config::keys::{JVM_PRESETS, PROXY_TYPES};
use mmcl_engine::settings::{
    CONSOLE_LOG_MAX_LINES_PRESETS, IMAGE_CYCLE_INTERVALS, RGB_BACKENDS, RGB_EFFECT_STYLES, THEMES,
    canonical_theme,
};
use mmcl_engine::{
    PackId, PackLoadState, PackSummary, RuntimeView, ScanSummary, Setting, Settings,
};
use mmcl_i18n::{SUPPORTED, t, tf};

// ---------------------------------------------------------------------------------------------
// JVM presets (`MCLauncherSettingsJvmPresetLogicTest`)

/// The preset ids as stored (the Java display names double as ids).
pub const PRESET_NAMES: [&str; 4] = ["Performance (Aikar's Flags)", "Low Memory", "Debug", "None"];

/// The preset arguments, in [`PRESET_NAMES`] order.
pub fn preset_args(i: usize) -> Option<&'static str> {
    JVM_PRESETS.get(i).map(|p| p.args)
}

/// The number of presets (the "Custom" entry comes after them).
pub const PRESET_COUNT: usize = JVM_PRESETS.len();

/// `presetIndexForArgs`: the first preset whose arguments equal `args`, else `None` ("Custom").
pub fn preset_index_for_args(args: &str) -> Option<usize> {
    JVM_PRESETS.iter().position(|p| p.args == args)
}

/// `presetIndexOf`: the index of a preset id, else `None`.
pub fn preset_index_of(name: &str) -> Option<usize> {
    PRESET_NAMES.iter().position(|n| *n == name)
}

/// `jvmArgsToPersist`: what Save writes for the selected preset. "Custom" (`None` or out of
/// range) never writes, so generated or hand-edited arguments survive (guarded bug 18); an
/// unchanged preset writes nothing.
pub fn jvm_args_to_persist(selected: Option<usize>, saved: &str) -> Option<String> {
    let args = preset_args(selected?)?;
    (args != saved).then(|| args.to_string())
}

/// The label of a JVM preset menu entry (`index == PRESET_COUNT` is "Custom").
pub fn jvm_preset_label(index: usize) -> String {
    match JVM_PRESETS.get(index) {
        Some(p) => p.label(),
        None => t!("settings.jvmPreset.custom").into_owned(),
    }
}

// ---------------------------------------------------------------------------------------------
// Language (`MCLauncherSettingsLanguageLogicTest`)

/// `overrideTagForIndex`: menu index -> stored BCP-47 tag. Index 0 ("use the OS language") and
/// anything out of range map to `""`.
pub fn override_tag_for_index(i: usize) -> &'static str {
    if (1..=SUPPORTED.len()).contains(&i) {
        SUPPORTED[i - 1].0
    } else {
        ""
    }
}

/// The menu index of a stored override (case-insensitive); unknown or blank -> 0.
pub fn index_for_override_tag(tag: &str) -> usize {
    let tag = tag.trim();
    if tag.is_empty() {
        return 0;
    }
    SUPPORTED
        .iter()
        .position(|(t, _)| t.eq_ignore_ascii_case(tag))
        .map_or(0, |i| i + 1)
}

/// `isLanguageChangePending`: the selection differs from the saved override (case-insensitive,
/// a missing override counts as `""`).
pub fn is_language_change_pending(i: usize, saved: Option<&str>) -> bool {
    !override_tag_for_index(i).eq_ignore_ascii_case(saved.unwrap_or("").trim())
}

/// The display name of the OS language, for the "use the OS language" entry: the endonym when
/// the language is offered, else the tag.
pub fn os_language_name(os_tag: &str) -> String {
    if let Some(name) = mmcl_i18n::display_name(os_tag) {
        return name.to_string();
    }
    let language = os_tag.split(['-', '_']).next().unwrap_or(os_tag);
    if language.eq_ignore_ascii_case("en") {
        return t!("settings.language.english").into_owned();
    }
    mmcl_i18n::display_name(language)
        .map(str::to_string)
        .unwrap_or_else(|| os_tag.to_string())
}

// ---------------------------------------------------------------------------------------------
// Validation (`MCLauncherSettingsValidationLogicTest`)

/// An enabled proxy needs a host: `true` when Save must store the proxy as off and warn.
pub fn proxy_host_missing(enabled: bool, host: &str) -> bool {
    enabled && host.trim().is_empty()
}

/// The compressed-oops limit (MB).
pub const COMPRESSED_OOPS_LIMIT_MB: i64 = mmcl_engine::settings::COMPRESSED_OOPS_LIMIT_MB;

/// Warn only on the first crossing above the compressed-oops limit.
pub fn crosses_compressed_oops_limit(previous_mb: i64, new_mb: i64) -> bool {
    mmcl_engine::settings::crosses_compressed_oops_limit(previous_mb, new_mb)
}

// ---------------------------------------------------------------------------------------------
// RAM spinners

/// Lowest minimum RAM (GB).
pub const MIN_RAM_FLOOR_GB: f64 = 0.2;
/// Highest minimum RAM (GB).
pub const MIN_RAM_CEIL_GB: f64 = 32.0;
/// Lowest maximum RAM (GB).
pub const MAX_RAM_FLOOR_GB: f64 = 1.0;
/// Highest maximum RAM (GB).
pub const MAX_RAM_CEIL_GB: f64 = 64.0;
/// Spinner step (GB).
pub const RAM_STEP_GB: f64 = 0.1;

/// `(long)(gb * 1024)`: the stored MB of a spinner value (truncating, like Java).
pub fn gb_to_mb(gb: f64) -> i64 {
    (gb * 1024.0) as i64
}

/// MB -> the spinner's GB value, rounded to the spinner step.
pub fn mb_to_gb(mb: i64) -> f64 {
    ((mb as f64 / 1024.0) * 10.0).round() / 10.0
}

/// The minimum spinner's range for a maximum of `max_gb`: `0.2 .. min(32, max)`.
pub fn min_ram_range(max_gb: f64) -> (f64, f64) {
    (
        MIN_RAM_FLOOR_GB,
        MIN_RAM_CEIL_GB.min(max_gb).max(MIN_RAM_FLOOR_GB),
    )
}

/// The maximum spinner's range for a minimum of `min_gb`: `max(1, min) .. 64`.
pub fn max_ram_range(min_gb: f64) -> (f64, f64) {
    (
        MAX_RAM_FLOOR_GB.max(min_gb).min(MAX_RAM_CEIL_GB),
        MAX_RAM_CEIL_GB,
    )
}

/// Parses a spinner's text and clamps it to `range`; `None` when it is not a number.
pub fn parse_clamped(text: &str, range: (f64, f64)) -> Option<f64> {
    let v = text.trim().replace(',', ".").parse::<f64>().ok()?;
    v.is_finite().then(|| v.clamp(range.0, range.1))
}

/// Parses an integer spinner's text and clamps it to `lo..=hi`.
pub fn parse_int_clamped(text: &str, lo: i32, hi: i32) -> Option<i32> {
    let v = text.trim().parse::<i64>().ok()?;
    Some(v.clamp(lo as i64, hi as i64) as i32)
}

/// A spinner's GB text (one decimal).
pub fn format_gb(gb: f64) -> String {
    format!("{gb:.1}")
}

// ---------------------------------------------------------------------------------------------
// Option labels (`SettingsOptionLabelsTest`)

/// The label key of a canonical theme, `None` for an unknown id.
pub fn theme_label_key(theme: &str) -> Option<&'static str> {
    use mmcl_config::keys::*;
    Some(match theme {
        THEME_NATIVE => "settings.theme.option.native",
        THEME_AUTOMATIC => "settings.theme.option.automatic",
        THEME_DARK => "settings.theme.option.dark",
        THEME_LIGHT => "settings.theme.option.light",
        THEME_BLUE_GRAY => "settings.theme.option.blueGray",
        THEME_ORANGE_PURPLE => "settings.theme.option.orangePurple",
        THEME_CREEPER => "settings.theme.option.creeper",
        _ => return None,
    })
}

/// A theme's label; unknown ids show as they are.
pub fn theme_label(theme: &str) -> String {
    theme_label_key(theme).map_or_else(|| theme.to_string(), |k| t!(k).into_owned())
}

/// The menu index of the saved theme (matched case-insensitively; no match selects
/// `Automatic`, guarded bug 20).
pub fn theme_index(saved: &str) -> usize {
    let canon = canonical_theme(saved);
    THEMES.iter().position(|t| *t == canon).unwrap_or(0)
}

/// The label of an image-cycle token (`settings.cycle.*`); unknown tokens show as they are.
pub fn cycle_label(token: &str) -> String {
    match token {
        "never" => return t!("settings.cycle.never").into_owned(),
        "1m" => return t!("settings.cycle.minute").into_owned(),
        "1h" => return t!("settings.cycle.hour").into_owned(),
        "1d" => return t!("settings.cycle.daily").into_owned(),
        "7d" => return t!("settings.cycle.weekly").into_owned(),
        _ => {}
    }
    let (num, unit) = token.split_at(token.len().saturating_sub(1));
    match (num.parse::<i64>(), unit) {
        (Ok(n), "s") => tf!("settings.cycle.seconds", n.to_string()),
        (Ok(n), "m") => tf!("settings.cycle.minutes", n.to_string()),
        (Ok(n), "h") => tf!("settings.cycle.hours", n.to_string()),
        _ => token.to_string(),
    }
}

/// The menu index of a saved image-cycle token; unknown tokens show the default.
pub fn cycle_index(saved: &str) -> usize {
    IMAGE_CYCLE_INTERVALS
        .iter()
        .position(|t| *t == saved)
        .or_else(|| {
            IMAGE_CYCLE_INTERVALS
                .iter()
                .position(|t| *t == mmcl_config::keys::IMAGE_CYCLE_INTERVAL_DEFAULT)
        })
        .unwrap_or(0)
}

/// The label of a console line preset (0 = unlimited).
pub fn console_lines_label(n: i32) -> String {
    if n == 0 {
        t!("settings.consoleLogMaxLines.unlimited").into_owned()
    } else {
        tf!("settings.consoleLogMaxLines.lines", n as i64)
    }
}

/// The menu index of the saved console line limit; an unmatched value selects index 0.
pub fn console_lines_index(saved: i32) -> usize {
    CONSOLE_LOG_MAX_LINES_PRESETS
        .iter()
        .position(|n| *n == saved)
        .unwrap_or(0)
}

/// `rgbBackendIndex`: token -> menu index (unknown -> 0, auto).
pub fn rgb_backend_index(token: &str) -> usize {
    RGB_BACKENDS
        .iter()
        .position(|b| b.eq_ignore_ascii_case(token.trim()))
        .unwrap_or(0)
}

/// `backendForIndex`: menu index -> token (out of range -> auto).
pub fn backend_for_index(i: usize) -> &'static str {
    RGB_BACKENDS.get(i).copied().unwrap_or(RGB_BACKENDS[0])
}

/// The label of an RGB backend token (localized; unknown tokens show as they are).
pub fn rgb_backend_label(token: &str) -> String {
    let key = match token {
        "auto" => "settings.rgb.backend.auto",
        "openrgb" => "settings.rgb.backend.openrgb",
        "chroma-native" => "settings.rgb.backend.chromaNative",
        "chroma" => "settings.rgb.backend.chromaRest",
        "windows-dl" => "settings.rgb.backend.windowsDl",
        "corsair" => "settings.rgb.backend.corsair",
        "asus-aura" => "settings.rgb.backend.asusAura",
        "none" => "settings.rgb.backend.none",
        _ => return token.to_string(),
    };
    t!(key).into_owned()
}

/// The menu index of an RGB effect style token (unknown -> breathe).
pub fn rgb_style_index(token: &str) -> usize {
    RGB_EFFECT_STYLES
        .iter()
        .position(|s| s.eq_ignore_ascii_case(token.trim()))
        .unwrap_or(1)
}

/// The label of an RGB effect style token (Java hard-coded these in English).
pub fn rgb_style_label(token: &str) -> String {
    match token {
        "solid" | "breathe" | "pulse" | "cycle" | "rainbow" => {
            t!(&format!("settings.rgb.effect.{token}")).into_owned()
        }
        _ => token.to_string(),
    }
}

/// The menu index of a proxy type (case-insensitive; unknown -> HTTP).
pub fn proxy_type_index(saved: &str) -> usize {
    PROXY_TYPES
        .iter()
        .position(|t| t.eq_ignore_ascii_case(saved.trim()))
        .unwrap_or(0)
}

// ---------------------------------------------------------------------------------------------
// Sidebar (`SettingsNavTileTest`)

/// A tile's position in its group (`tileOnly`, `tileFirst`, `tileMiddle`, `tileLast`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TilePosition {
    Only,
    First,
    Middle,
    Last,
}

/// `markGroupPositions`: the position of tile `index` in a group of `len` visible tiles.
pub fn tile_position(index: usize, len: usize) -> TilePosition {
    match (index, len) {
        (_, 0 | 1) => TilePosition::Only,
        (0, _) => TilePosition::First,
        (i, n) if i + 1 >= n => TilePosition::Last,
        _ => TilePosition::Middle,
    }
}

/// `(top, bottom)` corner radii of a tile at `pos` (outer `large`, inner `small`), so a group
/// reads as one rounded card.
pub fn tile_radii(pos: TilePosition, large: f32, small: f32) -> (f32, f32) {
    match pos {
        TilePosition::Only => (large, large),
        TilePosition::First => (large, small),
        TilePosition::Middle => (small, small),
        TilePosition::Last => (small, large),
    }
}

// ---------------------------------------------------------------------------------------------
// The Save form (`hasUnsavedChanges`, `persistSettings`)

/// The Save-written fields of the screen, snapshotted on the foreground.
#[derive(Debug, Clone, PartialEq)]
pub struct SaveForm {
    pub min_ram_gb: f64,
    pub max_ram_gb: f64,
    pub debug: bool,
    pub discord_rpc: bool,
    pub discord_invites: bool,
    pub resizable_windows: bool,
    pub enhanced_logging: bool,
    pub in_game_console: bool,
    pub battery_throttle: bool,
    pub lwjgl_arm_patch: bool,
    /// Index into the canonical theme list; `None` when nothing is selected.
    pub theme_index: Option<usize>,
    pub proxy_enable: bool,
    pub proxy_host: String,
    pub proxy_port: i32,
    pub proxy_type_index: usize,
    pub console_lines_index: usize,
    /// The JVM preset; `None` = Custom.
    pub jvm_preset: Option<usize>,
    pub language_index: usize,
}

impl SaveForm {
    /// The form as the saved settings fill it when the screen opens.
    pub fn from_settings(s: &Settings) -> SaveForm {
        SaveForm {
            min_ram_gb: mb_to_gb(s.min_ram_mb),
            max_ram_gb: mb_to_gb(s.max_ram_mb),
            debug: s.debug,
            discord_rpc: s.discord_rpc,
            discord_invites: s.discord_invites,
            resizable_windows: s.resizable_windows,
            enhanced_logging: s.enhanced_logging,
            in_game_console: s.in_game_console,
            battery_throttle: s.battery_throttle,
            lwjgl_arm_patch: s.lwjgl_arm_patch,
            theme_index: Some(theme_index(&s.theme)),
            proxy_enable: s.proxy_enable,
            proxy_host: s.proxy_host.clone(),
            proxy_port: s.proxy_port,
            proxy_type_index: proxy_type_index(&s.proxy_type),
            console_lines_index: console_lines_index(s.console_log_max_lines),
            jvm_preset: preset_index_for_args(&s.jvm_args),
            language_index: index_for_override_tag(&s.locale_override),
        }
    }

    /// The selected console line limit.
    pub fn console_lines(&self) -> i32 {
        CONSOLE_LOG_MAX_LINES_PRESETS
            .get(self.console_lines_index)
            .copied()
            .unwrap_or(CONSOLE_LOG_MAX_LINES_PRESETS[0])
    }

    /// The selected proxy type.
    pub fn proxy_type(&self) -> &'static str {
        PROXY_TYPES
            .get(self.proxy_type_index)
            .copied()
            .unwrap_or(PROXY_TYPES[0])
    }

    /// The selected canonical theme.
    pub fn theme(&self) -> Option<&'static str> {
        self.theme_index.and_then(|i| THEMES.get(i).copied())
    }

    /// Whether Save-and-Restart shows (a language change is pending).
    pub fn language_change_pending(&self, saved: &Settings) -> bool {
        is_language_change_pending(self.language_index, Some(&saved.locale_override))
    }

    /// `hasUnsavedChanges`: only the Save-written fields count; live fields are never dirty.
    pub fn has_unsaved_changes(&self, s: &Settings) -> bool {
        gb_to_mb(self.min_ram_gb) != s.min_ram_mb
            || gb_to_mb(self.max_ram_gb) != s.max_ram_mb
            || self.debug != s.debug
            || self.discord_rpc != s.discord_rpc
            || self.discord_invites != s.discord_invites
            || self.resizable_windows != s.resizable_windows
            || self.enhanced_logging != s.enhanced_logging
            || self.in_game_console != s.in_game_console
            || self.battery_throttle != s.battery_throttle
            || self.lwjgl_arm_patch != s.lwjgl_arm_patch
            || self
                .theme()
                .is_some_and(|t| !t.eq_ignore_ascii_case(s.theme.trim()))
            || self.proxy_enable != s.proxy_enable
            || self.proxy_host.trim() != s.proxy_host.trim()
            || self.proxy_port != s.proxy_port
            || !self.proxy_type().eq_ignore_ascii_case(&s.proxy_type)
            || self.console_lines() != s.console_log_max_lines
            || jvm_args_to_persist(self.jvm_preset, &s.jvm_args).is_some()
            || self.language_change_pending(s)
    }

    /// `persistSettings` as a plan: the writes in Java's order, plus what the screen must do
    /// afterwards.
    pub fn persist_plan(&self, s: &Settings) -> PersistPlan {
        let mut w = vec![
            Setting::MinRamMb(gb_to_mb(self.min_ram_gb)),
            Setting::MaxRamMb(gb_to_mb(self.max_ram_gb)),
            Setting::Debug(self.debug),
            Setting::DiscordRpc(self.discord_rpc),
            Setting::DiscordInvites(self.discord_invites),
            Setting::ResizableWindows(self.resizable_windows),
            Setting::EnhancedLogging(self.enhanced_logging),
            Setting::InGameConsole(self.in_game_console),
            Setting::ConsoleLogMaxLines(self.console_lines()),
            Setting::BatteryThrottle(self.battery_throttle),
            Setting::LwjglArmPatch(self.lwjgl_arm_patch),
        ];
        let theme_changed = self
            .theme()
            .is_some_and(|t| !t.eq_ignore_ascii_case(s.theme.trim()));
        if let Some(theme) = self.theme() {
            w.push(Setting::Theme(theme.to_string()));
        }
        w.push(Setting::LocaleOverride(
            override_tag_for_index(self.language_index).to_string(),
        ));
        let host_missing = proxy_host_missing(self.proxy_enable, &self.proxy_host);
        // The host goes in before the enable flag: the engine stores "enabled" only with a host.
        w.push(Setting::ProxyType(self.proxy_type().to_string()));
        w.push(Setting::ProxyHost(self.proxy_host.trim().to_string()));
        w.push(Setting::ProxyPort(self.proxy_port.clamp(1, 65535)));
        w.push(Setting::ProxyEnable(self.proxy_enable && !host_missing));
        if let Some(args) = jvm_args_to_persist(self.jvm_preset, &s.jvm_args) {
            w.push(Setting::JvmArgs(args));
        }
        PersistPlan {
            writes: w,
            proxy_host_missing: host_missing,
            theme_changed,
            language_changed: self.language_change_pending(s),
        }
    }
}

/// What [`SaveForm::persist_plan`] produced.
#[derive(Debug, Clone, PartialEq)]
pub struct PersistPlan {
    /// The setting writes, in order.
    pub writes: Vec<Setting>,
    /// The proxy was enabled without a host: it is saved as off; untick it and warn.
    pub proxy_host_missing: bool,
    /// The theme changes with this save.
    pub theme_changed: bool,
    /// The language changes (applies after a restart).
    pub language_changed: bool,
}

// ---------------------------------------------------------------------------------------------
// MCP panel text

/// `settings.mcp.sessions.{justNow, minutesAgo, hoursAgo}` for an age in milliseconds.
pub fn elapsed_text(age_ms: u64) -> String {
    let minutes = age_ms / 60_000;
    if minutes < 1 {
        t!("settings.mcp.sessions.justNow").into_owned()
    } else if minutes < 60 {
        tf!("settings.mcp.sessions.minutesAgo", minutes as i64)
    } else {
        tf!("settings.mcp.sessions.hoursAgo", (minutes / 60) as i64)
    }
}

// ---------------------------------------------------------------------------------------------
// Malware scan and memory

/// Strips ANSI escape sequences (the scanner's red/green/white/reset codes) from a line.
pub fn strip_ansi(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\u{1b}' {
            if chars.peek() == Some(&'[') {
                chars.next();
                for d in chars.by_ref() {
                    if d.is_ascii_alphabetic() {
                        break;
                    }
                }
            }
            continue;
        }
        out.push(c);
    }
    out
}

/// The summary line of a finished scan.
pub fn scan_summary_text(s: &ScanSummary) -> String {
    let total = s.stage1 + s.stage2 + s.high + s.medium;
    if total > 0 {
        tf!(
            "settings.scan.infectionsFound",
            s.stage1 as i64,
            s.stage2 as i64,
            s.high as i64,
            s.medium as i64
        )
    } else if s.cancelled {
        t!("settings.scan.canceled").into_owned()
    } else {
        t!("settings.scan.noInfections").into_owned()
    }
}

/// `settings.sysRamLabel(total GB 2dp, used GB 2dp)`.
pub fn sys_ram_text(total_gb: f64, used_gb: f64) -> String {
    tf!(
        "settings.sysRamLabel",
        format!("{total_gb:.2}"),
        format!("{used_gb:.2}")
    )
}

// ---------------------------------------------------------------------------------------------
// Runtime Management

/// A runtime row's title: `Java <version>` when the version is known, else the component.
pub fn runtime_title(r: &RuntimeView) -> String {
    match r.version.as_deref().filter(|v| !v.trim().is_empty()) {
        Some(v) => tf!("runtime.list.title", v),
        None => r.component.clone(),
    }
}

/// A runtime row's detail line: component and size when the version is known, else the size.
pub fn runtime_detail(r: &RuntimeView) -> String {
    if r.version.as_deref().is_some_and(|v| !v.trim().is_empty()) {
        tf!(
            "runtime.list.detail",
            r.component.as_str(),
            r.size_mb as i64
        )
    } else {
        tf!("runtime.list.size", r.size_mb as i64)
    }
}

/// The status line after a (re)load.
pub fn runtime_count_status(n: usize) -> String {
    if n == 0 {
        t!("runtime.status.noneInstalled").into_owned()
    } else {
        tf!("runtime.status.countInstalled", n as i64)
    }
}

/// Resolves a selection against one snapshot of the list (guarded bug 21): the runtime at
/// `selected` in `snapshot`, never in a list a refresh replaced meanwhile.
pub fn resolve_selection(
    snapshot: &[RuntimeView],
    selected: Option<usize>,
) -> Option<&RuntimeView> {
    snapshot.get(selected?)
}

// ---------------------------------------------------------------------------------------------
// Verify all game files (`VerifyAction.runForPacks`)

/// One pack to verify (or skipped because a game of it runs).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerifyItem {
    pub id: PackId,
    pub name: String,
}

/// What "Verify all game files" does with the installed packs.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct VerifyPlan {
    /// Verified in this order.
    pub verify: Vec<VerifyItem>,
    /// Launching or running: skipped with a warning, not repaired under the game.
    pub running: Vec<VerifyItem>,
}

/// `safePackName`: the friendly name, else the name, else `verify.packName.fallback`.
pub fn verify_pack_name(p: &PackSummary) -> String {
    [p.friendly_name.trim(), p.name.trim()]
        .into_iter()
        .find(|s| !s.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| t!("verify.packName.fallback").into_owned())
}

/// Splits the installed packs: packs whose manifest failed to load (or is not loaded yet) are
/// left out, as the engine's verify-all does; packs with an active game are skipped.
pub fn verify_plan(packs: &[PackSummary], is_active: impl Fn(&PackSummary) -> bool) -> VerifyPlan {
    let mut plan = VerifyPlan::default();
    for p in packs {
        if p.load_state != PackLoadState::Loaded {
            continue;
        }
        let item = VerifyItem {
            id: p.id.clone(),
            name: verify_pack_name(p),
        };
        if p.active || is_active(p) {
            plan.running.push(item);
        } else {
            plan.verify.push(item);
        }
    }
    plan
}

/// The progress title: `verify.progress.title` for one pack, `titleCounted` (1-based) for more.
pub fn verify_title(name: &str, index: usize, total: usize) -> String {
    if total > 1 {
        tf!(
            "verify.progress.titleCounted",
            name,
            (index + 1) as i64,
            total as i64
        )
    } else {
        tf!("verify.progress.title", name)
    }
}

/// The closing notification `(is_error, title, body)` after `ok` packs verified and `failed`
/// failed.
pub fn verify_outcome(ok: usize, failed: usize) -> (bool, String, String) {
    if failed == 0 {
        let key = if ok == 1 {
            "notification.verify.complete.bodyOne"
        } else {
            "notification.verify.complete.bodyMany"
        };
        (
            false,
            t!("notification.verify.complete.title").into_owned(),
            tf!(key, ok as i64),
        )
    } else {
        (
            true,
            t!("notification.verify.completedWithErrors.title").into_owned(),
            tf!(
                "notification.verify.completedWithErrors.body",
                ok as i64,
                failed as i64
            ),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Settings export / import (the `*.json` file chooser filter)

/// The export target: `.json` is appended when the chosen name has no extension (the Java
/// chooser's `*.json` filter did the same).
pub fn export_path(chosen: &std::path::Path) -> std::path::PathBuf {
    if chosen.extension().is_some() {
        chosen.to_path_buf()
    } else {
        chosen.with_extension("json")
    }
}

/// Whether an import pick passes the `*.json` filter (the platform picker has no filter).
pub fn is_json_file(path: &std::path::Path) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .is_some_and(|e| e.eq_ignore_ascii_case("json"))
}

// ---------------------------------------------------------------------------------------------
// MCP status

/// The port of an endpoint URL such as `http://127.0.0.1:47833/mcp` (the port the server
/// actually bound, which can differ from the configured one after a fallback).
pub fn endpoint_port(url: &str) -> Option<u16> {
    let rest = url.split_once("://").map_or(url, |(_, r)| r);
    let authority = rest.split('/').next()?;
    let (_, port) = authority.rsplit_once(':')?;
    port.parse().ok().filter(|p| *p != 0)
}

/// The port the status line shows: the bound one when the endpoint names it, else `configured`.
pub fn status_port(endpoint: Option<&str>, configured: i32) -> String {
    endpoint
        .and_then(endpoint_port)
        .map(|p| p.to_string())
        .unwrap_or_else(|| configured.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_config::keys::{JVM_ARGS_VALUE_DEFAULT, JVM_ARGS_VALUE_DEFAULT_PRE_2026_10};

    fn config() -> mmcl_config::Config {
        use mmcl_config::store::{ConfigStore, first_run_defaults};
        mmcl_config::Config::new(ConfigStore::in_memory(first_run_defaults()))
    }

    fn settings() -> Settings {
        Settings::read(&config())
    }

    // MCLauncherSettingsJvmPresetLogicTest

    #[test]
    fn preset_args_select_their_preset() {
        for i in 0..PRESET_COUNT {
            assert_eq!(preset_index_for_args(preset_args(i).unwrap()), Some(i));
        }
        assert_eq!(preset_index_of("Low Memory"), Some(1));
        assert_eq!(preset_index_of("Turbo"), None);
    }

    #[test]
    fn unmatched_args_select_custom() {
        assert_eq!(preset_index_for_args("-Xss4m -XX:+UseZGC"), None);
        assert_eq!(
            jvm_preset_label(PRESET_COUNT),
            t!("settings.jvmPreset.custom")
        );
    }

    #[test]
    fn migrated_default_selects_performance() {
        assert_ne!(JVM_ARGS_VALUE_DEFAULT, JVM_ARGS_VALUE_DEFAULT_PRE_2026_10);
        assert_eq!(preset_index_for_args(JVM_ARGS_VALUE_DEFAULT), Some(0));
        assert_eq!(settings().jvm_args, JVM_ARGS_VALUE_DEFAULT);
        assert_eq!(SaveForm::from_settings(&settings()).jvm_preset, Some(0));
    }

    #[test]
    fn custom_never_overwrites_saved_args() {
        assert_eq!(jvm_args_to_persist(None, "-Xss2m"), None);
        assert_eq!(jvm_args_to_persist(Some(99), "-Xss2m"), None);
    }

    #[test]
    fn unchanged_preset_writes_nothing() {
        assert_eq!(jvm_args_to_persist(Some(0), JVM_ARGS_VALUE_DEFAULT), None);
    }

    #[test]
    fn picking_a_preset_writes_its_args() {
        assert_eq!(
            jvm_args_to_persist(Some(1), JVM_ARGS_VALUE_DEFAULT).as_deref(),
            preset_args(1)
        );
        assert_eq!(jvm_args_to_persist(Some(3), "-Xss2m").as_deref(), Some(""));
    }

    // MCLauncherSettingsLanguageLogicTest

    #[test]
    fn tag_for_every_index() {
        for (i, (tag, _)) in SUPPORTED.iter().enumerate() {
            assert_eq!(override_tag_for_index(i + 1), *tag);
            assert_eq!(index_for_override_tag(tag), i + 1);
        }
    }

    #[test]
    fn os_default_and_out_of_range_map_to_blank() {
        assert_eq!(override_tag_for_index(0), "");
        assert_eq!(override_tag_for_index(SUPPORTED.len() + 1), "");
        assert_eq!(override_tag_for_index(usize::MAX), "");
        assert_eq!(index_for_override_tag(""), 0);
        assert_eq!(index_for_override_tag("xx-YY"), 0);
    }

    #[test]
    fn no_change_when_selection_matches() {
        assert!(!is_language_change_pending(0, Some("")));
        assert!(!is_language_change_pending(0, None));
        let de = index_for_override_tag("de");
        assert!(!is_language_change_pending(de, Some("de")));
    }

    #[test]
    fn change_when_it_differs() {
        let de = index_for_override_tag("de");
        assert!(is_language_change_pending(de, Some("")));
        assert!(is_language_change_pending(0, Some("de")));
        assert!(is_language_change_pending(de, None));
    }

    #[test]
    fn language_comparison_is_case_insensitive_and_null_safe() {
        let pt = index_for_override_tag("pt-br");
        assert_eq!(override_tag_for_index(pt), "pt-BR");
        assert!(!is_language_change_pending(pt, Some("PT-br")));
        assert!(!is_language_change_pending(0, Some("  ")));
    }

    #[test]
    fn os_language_names() {
        assert_eq!(os_language_name("de-DE"), "Deutsch");
        assert_eq!(os_language_name("pt-BR"), "Português (Brasil)");
        assert_eq!(os_language_name("en-US"), t!("settings.language.english"));
        assert_eq!(os_language_name("xx-YY"), "xx-YY");
    }

    // MCLauncherSettingsValidationLogicTest

    #[test]
    fn enabled_proxy_needs_a_host() {
        assert!(proxy_host_missing(true, ""));
        assert!(proxy_host_missing(true, "   "));
        assert!(!proxy_host_missing(true, "proxy.local"));
        assert!(!proxy_host_missing(false, ""));
    }

    #[test]
    fn oops_warning_only_on_first_crossing() {
        assert!(crosses_compressed_oops_limit(31_744, 31_745));
        assert!(!crosses_compressed_oops_limit(31_745, 40_000));
        assert!(!crosses_compressed_oops_limit(8_192, 31_744));
    }

    // RAM

    #[test]
    fn ram_conversions_truncate_like_java() {
        assert_eq!(gb_to_mb(0.2), 204);
        assert_eq!(gb_to_mb(2.0), 2048);
        assert_eq!(gb_to_mb(2.5), 2560);
        assert_eq!(mb_to_gb(2048), 2.0);
        assert_eq!(mb_to_gb(512), 0.5);
        assert_eq!(format_gb(2.0), "2.0");
    }

    #[test]
    fn ram_spinners_track_each_other() {
        assert_eq!(min_ram_range(4.0), (0.2, 4.0));
        assert_eq!(min_ram_range(48.0), (0.2, 32.0));
        assert_eq!(max_ram_range(0.5), (1.0, 64.0));
        assert_eq!(max_ram_range(6.0), (6.0, 64.0));
        assert_eq!(parse_clamped("100", max_ram_range(2.0)), Some(64.0));
        assert_eq!(parse_clamped("1,5", (0.2, 4.0)), Some(1.5));
        assert_eq!(parse_clamped("abc", (0.2, 4.0)), None);
        assert_eq!(parse_int_clamped("70000", 1, 65535), Some(65535));
        assert_eq!(parse_int_clamped("-3", 0, 100), Some(0));
        assert_eq!(parse_int_clamped("x", 0, 100), None);
    }

    #[test]
    fn sys_ram_line_uses_two_decimals() {
        let s = sys_ram_text(31.9, 12.5);
        assert!(s.contains("31.90"), "{s}");
        assert!(s.contains("12.50"), "{s}");
    }

    // SettingsOptionLabelsTest

    #[test]
    fn every_theme_and_preset_has_a_label() {
        for theme in THEMES {
            let key = theme_label_key(theme).expect(theme);
            assert!(mmcl_i18n::english(key).is_some(), "{key}");
        }
        for p in &JVM_PRESETS {
            assert!(mmcl_i18n::english(p.label_key).is_some(), "{}", p.label_key);
        }
    }

    #[test]
    fn unknown_ids_show_as_is() {
        assert_eq!(theme_label("Neon"), "Neon");
        assert_eq!(cycle_label("3w"), "3w");
        assert_eq!(rgb_backend_label("razer"), "razer");
        assert_eq!(rgb_style_label("strobe"), "strobe");
    }

    #[test]
    fn saved_theme_matches_case_insensitively() {
        assert_eq!(THEMES[theme_index("native (mica)")], "Native (Mica)");
        assert_eq!(THEMES[theme_index("garbage")], "Automatic");
    }

    #[test]
    fn every_cycle_token_has_a_label() {
        for token in IMAGE_CYCLE_INTERVALS {
            let label = cycle_label(token);
            assert_ne!(label, token, "{token}");
            assert!(!label.contains('{'), "{token}: {label}");
        }
        assert_eq!(IMAGE_CYCLE_INTERVALS[cycle_index("bogus")], "30s");
        assert_eq!(IMAGE_CYCLE_INTERVALS[cycle_index("6h")], "6h");
    }

    #[test]
    fn console_line_presets() {
        assert_eq!(console_lines_index(25_000), 3);
        assert_eq!(console_lines_index(1234), 0);
        assert_eq!(
            console_lines_label(0),
            t!("settings.consoleLogMaxLines.unlimited")
        );
        assert!(console_lines_label(10_000).contains("10"));
    }

    #[test]
    fn rgb_backends_and_styles_round_trip() {
        for (i, token) in RGB_BACKENDS.iter().enumerate() {
            assert_eq!(rgb_backend_index(token), i);
            assert_eq!(backend_for_index(i), *token);
            assert_ne!(rgb_backend_label(token), *token);
        }
        assert_eq!(rgb_backend_index("bogus"), 0);
        assert_eq!(backend_for_index(42), "auto");
        for (i, token) in RGB_EFFECT_STYLES.iter().enumerate() {
            assert_eq!(rgb_style_index(token), i);
            assert_ne!(rgb_style_label(token), *token);
        }
    }

    // SettingsNavTileTest

    #[test]
    fn lone_tile_is_only_and_groups_get_positions() {
        assert_eq!(tile_position(0, 1), TilePosition::Only);
        assert_eq!(tile_position(0, 3), TilePosition::First);
        assert_eq!(tile_position(1, 3), TilePosition::Middle);
        assert_eq!(tile_position(2, 3), TilePosition::Last);
        assert_eq!(tile_position(0, 2), TilePosition::First);
        assert_eq!(tile_position(1, 2), TilePosition::Last);
        assert_eq!(tile_radii(TilePosition::Only, 16.0, 4.0), (16.0, 16.0));
        assert_eq!(tile_radii(TilePosition::Middle, 16.0, 4.0), (4.0, 4.0));
    }

    // has_unsaved_changes over a form snapshot

    #[test]
    fn a_fresh_form_is_clean() {
        let s = settings();
        let form = SaveForm::from_settings(&s);
        assert!(!form.has_unsaved_changes(&s));
        assert!(!form.language_change_pending(&s));
    }

    #[test]
    fn every_save_field_makes_the_form_dirty() {
        let s = settings();
        let base = SaveForm::from_settings(&s);
        type Edit = Box<dyn Fn(&mut SaveForm)>;
        let edits: Vec<Edit> = vec![
            Box::new(|f| f.min_ram_gb += 0.1),
            Box::new(|f| f.max_ram_gb += 1.0),
            Box::new(|f| f.debug = !f.debug),
            Box::new(|f| f.discord_rpc = !f.discord_rpc),
            Box::new(|f| f.discord_invites = !f.discord_invites),
            Box::new(|f| f.resizable_windows = !f.resizable_windows),
            Box::new(|f| f.enhanced_logging = !f.enhanced_logging),
            Box::new(|f| f.in_game_console = !f.in_game_console),
            Box::new(|f| f.battery_throttle = !f.battery_throttle),
            Box::new(|f| f.lwjgl_arm_patch = !f.lwjgl_arm_patch),
            Box::new(|f| f.theme_index = Some((f.theme_index.unwrap() + 1) % THEMES.len())),
            Box::new(|f| f.proxy_enable = !f.proxy_enable),
            Box::new(|f| f.proxy_host = "proxy.example".into()),
            Box::new(|f| f.proxy_port += 1),
            Box::new(|f| f.proxy_type_index = 1 - f.proxy_type_index),
            Box::new(|f| f.console_lines_index = (f.console_lines_index + 1) % 6),
            Box::new(|f| f.jvm_preset = Some(1)),
            Box::new(|f| f.language_index = 3),
        ];
        for (i, edit) in edits.iter().enumerate() {
            let mut f = base.clone();
            edit(&mut f);
            assert!(f.has_unsaved_changes(&s), "edit {i} was not dirty");
        }
        let mut f = base.clone();
        f.jvm_preset = None;
        assert!(!f.has_unsaved_changes(&s));
    }

    #[test]
    fn persist_plan_keeps_java_order_and_the_proxy_rule() {
        let s = settings();
        let mut f = SaveForm::from_settings(&s);
        f.proxy_enable = true;
        f.proxy_host = "  ".into();
        f.jvm_preset = Some(2);
        let plan = f.persist_plan(&s);
        assert!(plan.proxy_host_missing);
        assert!(matches!(plan.writes[0], Setting::MinRamMb(_)));
        assert!(matches!(plan.writes[1], Setting::MaxRamMb(_)));
        let host = plan
            .writes
            .iter()
            .position(|w| matches!(w, Setting::ProxyHost(_)))
            .unwrap();
        let enable = plan
            .writes
            .iter()
            .position(|w| matches!(w, Setting::ProxyEnable(_)))
            .unwrap();
        assert!(host < enable);
        assert!(plan.writes.contains(&Setting::ProxyEnable(false)));
        assert!(matches!(plan.writes.last(), Some(Setting::JvmArgs(_))));
        let c = config();
        for w in &plan.writes {
            w.apply(&c).unwrap_or_else(|e| panic!("{w:?}: {e}"));
        }
        assert!(!c.proxy_enable());
    }

    #[test]
    fn persisted_form_reads_back_clean() {
        let c = config();
        let s = Settings::read(&c);
        let mut f = SaveForm::from_settings(&s);
        f.max_ram_gb = 6.5;
        f.min_ram_gb = 1.2;
        f.theme_index = Some(2);
        f.proxy_host = "proxy.local".into();
        f.proxy_enable = true;
        f.language_index = index_for_override_tag("fr");
        let plan = f.persist_plan(&s);
        assert!(plan.theme_changed);
        assert!(plan.language_changed);
        for w in &plan.writes {
            w.apply(&c).unwrap();
        }
        let after = Settings::read(&c);
        assert!(!f.has_unsaved_changes(&after));
        assert!(!f.language_change_pending(&after));
    }

    // MCP and scan text

    #[test]
    fn elapsed_text_buckets() {
        assert_eq!(elapsed_text(30_000), t!("settings.mcp.sessions.justNow"));
        assert_eq!(
            elapsed_text(5 * 60_000),
            tf!("settings.mcp.sessions.minutesAgo", 5i64)
        );
        assert_eq!(
            elapsed_text(3 * 3_600_000 + 1),
            tf!("settings.mcp.sessions.hoursAgo", 3i64)
        );
    }

    #[test]
    fn ansi_codes_are_stripped() {
        assert_eq!(
            strip_ansi("\u{1b}[31mInfected\u{1b}[0m: a.jar"),
            "Infected: a.jar"
        );
        assert_eq!(strip_ansi("\u{1b}[1;37mok"), "ok");
        assert_eq!(strip_ansi("plain"), "plain");
    }

    #[test]
    fn scan_summaries() {
        let clean = ScanSummary {
            stage1: 0,
            stage2: 0,
            high: 0,
            medium: 0,
            cancelled: false,
        };
        assert_eq!(scan_summary_text(&clean), t!("settings.scan.noInfections"));
        let cancelled = ScanSummary {
            cancelled: true,
            ..clean.clone()
        };
        assert_eq!(scan_summary_text(&cancelled), t!("settings.scan.canceled"));
        let found = ScanSummary { high: 2, ..clean };
        assert_eq!(
            scan_summary_text(&found),
            tf!("settings.scan.infectionsFound", 0i64, 0i64, 2i64, 0i64)
        );
    }

    // Runtimes

    fn runtime(component: &str, version: Option<&str>, size: u64) -> RuntimeView {
        RuntimeView {
            component: component.into(),
            version: version.map(str::to_string),
            path: std::path::PathBuf::from(component),
            size_mb: size,
            in_use: false,
        }
    }

    #[test]
    fn runtime_rows() {
        let known = runtime("java-runtime-delta", Some("21.0.3"), 190);
        assert_eq!(runtime_title(&known), tf!("runtime.list.title", "21.0.3"));
        assert_eq!(
            runtime_detail(&known),
            tf!("runtime.list.detail", "java-runtime-delta", 190i64)
        );
        let unknown = runtime("jre-legacy", None, 80);
        assert_eq!(runtime_title(&unknown), "jre-legacy");
        assert_eq!(runtime_detail(&unknown), tf!("runtime.list.size", 80i64));
        assert_eq!(runtime_count_status(0), t!("runtime.status.noneInstalled"));
        assert_eq!(
            runtime_count_status(2),
            tf!("runtime.status.countInstalled", 2i64)
        );
    }

    #[test]
    fn selection_resolves_against_its_snapshot() {
        let snapshot = vec![runtime("a", None, 1), runtime("b", None, 2)];
        assert_eq!(
            resolve_selection(&snapshot, Some(1)).map(|r| r.component.as_str()),
            Some("b")
        );
        assert!(resolve_selection(&snapshot, Some(2)).is_none());
        assert!(resolve_selection(&snapshot, None).is_none());
    }

    fn pack(id: &str, friendly: &str, name: &str) -> PackSummary {
        PackSummary {
            id: PackId::new(id),
            friendly_name: friendly.into(),
            name: name.into(),
            installed: true,
            ..Default::default()
        }
    }

    #[test]
    fn verify_pack_name_falls_back_like_safe_pack_name() {
        assert_eq!(verify_pack_name(&pack("a", "Friendly", "raw")), "Friendly");
        assert_eq!(verify_pack_name(&pack("a", "  ", "raw")), "raw");
        assert_eq!(
            verify_pack_name(&pack("a", "", "")),
            t!("verify.packName.fallback")
        );
    }

    #[test]
    fn verify_plan_skips_running_and_unloaded_packs() {
        let mut failed = pack("failed", "Failed", "f");
        failed.load_state = PackLoadState::Failed;
        let mut stub = pack("stub", "Stub", "s");
        stub.load_state = PackLoadState::Stub;
        let mut active = pack("active", "Active", "a");
        active.active = true;
        let packs = vec![
            pack("one", "One", "1"),
            failed,
            stub,
            active,
            pack("vanilla:1.21", "", "1.21"),
            pack("busy", "Busy", "b"),
        ];
        let plan = verify_plan(&packs, |p| p.id.0 == "busy");
        let ids = |v: &[VerifyItem]| v.iter().map(|i| i.id.0.clone()).collect::<Vec<_>>();
        assert_eq!(ids(&plan.verify), ["one", "vanilla:1.21"]);
        assert_eq!(ids(&plan.running), ["active", "busy"]);
        assert_eq!(plan.verify[1].name, "1.21");
        assert_eq!(verify_plan(&[], |_| false), VerifyPlan::default());
    }

    #[test]
    fn verify_titles_count_only_when_several() {
        assert_eq!(verify_title("P", 0, 1), tf!("verify.progress.title", "P"));
        assert_eq!(
            verify_title("P", 1, 3),
            tf!("verify.progress.titleCounted", "P", 2i64, 3i64)
        );
    }

    #[test]
    fn verify_outcome_picks_singular_plural_and_error() {
        let (err, title, body) = verify_outcome(1, 0);
        assert!(!err);
        assert_eq!(title, t!("notification.verify.complete.title"));
        assert_eq!(body, tf!("notification.verify.complete.bodyOne", 1i64));
        let (_, _, body) = verify_outcome(3, 0);
        assert_eq!(body, tf!("notification.verify.complete.bodyMany", 3i64));
        let (err, title, body) = verify_outcome(2, 1);
        assert!(err);
        assert_eq!(title, t!("notification.verify.completedWithErrors.title"));
        assert_eq!(
            body,
            tf!("notification.verify.completedWithErrors.body", 2i64, 1i64)
        );
    }

    #[test]
    fn export_path_adds_json_only_without_an_extension() {
        use std::path::Path;
        assert_eq!(
            export_path(Path::new("dir/settings")),
            Path::new("dir/settings.json")
        );
        assert_eq!(
            export_path(Path::new("dir/settings.json")),
            Path::new("dir/settings.json")
        );
        assert_eq!(
            export_path(Path::new("dir/backup.txt")),
            Path::new("dir/backup.txt")
        );
        assert!(is_json_file(Path::new("a/b.JSON")));
        assert!(is_json_file(Path::new("b.json")));
        assert!(!is_json_file(Path::new("b.txt")));
        assert!(!is_json_file(Path::new("json")));
    }

    #[test]
    fn mcp_status_shows_the_bound_port() {
        assert_eq!(endpoint_port("http://127.0.0.1:47834/mcp"), Some(47834));
        assert_eq!(endpoint_port("http://127.0.0.1:47834"), Some(47834));
        assert_eq!(endpoint_port("http://localhost/mcp"), None);
        assert_eq!(endpoint_port("http://127.0.0.1:0/mcp"), None);
        assert_eq!(endpoint_port("garbage"), None);
        assert_eq!(
            status_port(Some("http://127.0.0.1:47834/mcp"), 47833),
            "47834"
        );
        assert_eq!(status_port(Some("nope"), 47833), "47833");
        assert_eq!(status_port(None, 47833), "47833");
    }
}

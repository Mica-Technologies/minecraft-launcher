// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Settings get/set over [`mmcl_config::Config`]: a [`Settings`] snapshot with every
//! user-facing setting, and a [`Setting`] value per key for writes. Writes validate the value
//! (the ranges the Settings screen enforces), store it, and report which side effects the
//! engine must run ([`SideEffects`]).

use mmcl_config::keys::*;
use mmcl_config::{Config, ScanFrequency};
use serde::Serialize;

use crate::error::{EngineError, EngineResult};

/// The UI scale steps the Appearance pane offers.
pub const UI_SCALE_STEPS: [i32; 5] = [50, 75, 100, 125, 150];
/// The console line presets (0 = unlimited).
pub const CONSOLE_LOG_MAX_LINES_PRESETS: [i32; 6] = [1000, 5000, 10000, 25000, 50000, 0];
/// The canonical themes, in menu order.
pub const THEMES: [&str; 7] = [
    THEME_NATIVE,
    THEME_AUTOMATIC,
    THEME_DARK,
    THEME_LIGHT,
    THEME_BLUE_GRAY,
    THEME_ORANGE_PURPLE,
    THEME_CREEPER,
];
/// The image-cycle interval tokens.
pub const IMAGE_CYCLE_INTERVALS: [&str; 13] = IMAGE_CYCLE_INTERVAL_OPTIONS;
/// The RGB backend tokens, in menu order.
pub const RGB_BACKENDS: [&str; 8] = [
    RGB_BACKEND_AUTO,
    RGB_BACKEND_OPENRGB,
    RGB_BACKEND_CHROMA_NATIVE,
    RGB_BACKEND_CHROMA,
    RGB_BACKEND_WINDOWS_DL,
    RGB_BACKEND_CORSAIR,
    RGB_BACKEND_ASUS_AURA,
    RGB_BACKEND_NONE,
];
/// The RGB effect style tokens.
pub const RGB_EFFECT_STYLES: [&str; 5] = [
    RGB_EFFECT_STYLE_SOLID,
    RGB_EFFECT_STYLE_BREATHE,
    RGB_EFFECT_STYLE_PULSE,
    RGB_EFFECT_STYLE_CYCLE,
    RGB_EFFECT_STYLE_RAINBOW,
];
/// Max RAM above this (MB) loses compressed oops.
pub const COMPRESSED_OOPS_LIMIT_MB: i64 = 31_744;

/// `true` on the first crossing above the compressed-oops limit (warn once).
pub fn crosses_compressed_oops_limit(previous_mb: i64, new_mb: i64) -> bool {
    new_mb > COMPRESSED_OOPS_LIMIT_MB && previous_mb <= COMPRESSED_OOPS_LIMIT_MB
}

/// The canonical spelling of a saved theme (case-insensitive), else `Automatic` (an old
/// capitalisation bug saved variants that no longer match).
pub fn canonical_theme(saved: &str) -> &'static str {
    THEMES
        .iter()
        .find(|t| t.eq_ignore_ascii_case(saved.trim()))
        .copied()
        .unwrap_or(THEME_AUTOMATIC)
}

/// Every user-facing setting, as stored.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Settings {
    // Game
    pub min_ram_mb: i64,
    pub max_ram_mb: i64,
    pub jvm_args: String,
    // Appearance
    pub theme: String,
    pub ui_scale_percent: i32,
    pub wavy_progress: bool,
    pub reduce_motion: bool,
    /// BCP-47 tag; `""` = follow the OS.
    pub locale_override: String,
    pub show_pack_backgrounds: bool,
    pub pack_colors: bool,
    pub image_cycle_interval: String,
    pub image_cycle_shuffle: bool,
    // Advanced
    pub debug: bool,
    pub resizable_windows: bool,
    pub enhanced_logging: bool,
    pub in_game_console: bool,
    pub console_log_max_lines: i32,
    pub battery_throttle: bool,
    pub lwjgl_arm_patch: bool,
    pub default_scan_frequency: String,
    pub backup_auto_before_update: bool,
    pub backup_include_saves: bool,
    pub backup_max_count_per_pack: i32,
    pub backup_max_age_days: i32,
    pub has_curseforge_api_key: bool,
    // Network
    pub proxy_enable: bool,
    pub proxy_type: String,
    pub proxy_host: String,
    pub proxy_port: i32,
    // Security
    pub uri_handler_enabled: bool,
    pub mcp_server_enabled: bool,
    pub mcp_auto_approve_read_only: bool,
    pub mcp_allow_state_changes: bool,
    pub mcp_tool_policies: String,
    pub mcp_port: i32,
    // Discord
    pub discord_rpc: bool,
    pub discord_invites: bool,
    // RGB
    pub rgb_enable: bool,
    pub rgb_backend: String,
    pub rgb_enable_open_rgb: bool,
    pub rgb_enable_chroma_native: bool,
    pub rgb_enable_chroma_rest: bool,
    pub rgb_enable_windows_dl: bool,
    pub rgb_enable_corsair: bool,
    pub rgb_enable_asus_aura: bool,
    pub rgb_menu_effect_enable: bool,
    pub rgb_effect_style: String,
    pub rgb_use_pack_colors: bool,
    pub rgb_highlight_keys: bool,
    // About
    pub launcher_update_check: bool,
    // Shell state
    pub running_games_docked: bool,
    pub running_games_dock_height: i32,
    pub quick_start_completed: bool,
    /// `None` = detect (handheld or not).
    pub gamepad_mode: Option<bool>,
    pub auth_wam: bool,
    pub auth_client_id: String,
    pub last_mod_pack: String,
}

impl Settings {
    /// Reads every setting.
    pub fn read(c: &Config) -> Settings {
        Settings {
            min_ram_mb: c.min_ram_mb(),
            max_ram_mb: c.max_ram_mb(),
            jvm_args: c.custom_jvm_args(),
            theme: c.theme(),
            ui_scale_percent: c.ui_scale_percent(),
            wavy_progress: c.wavy_progress(),
            reduce_motion: c.reduce_motion(),
            locale_override: c.locale_override(),
            show_pack_backgrounds: c.show_pack_backgrounds(),
            pack_colors: c.pack_colors(),
            image_cycle_interval: c.image_cycle_interval(),
            image_cycle_shuffle: c.image_cycle_shuffle(),
            debug: c.debug_logging(),
            resizable_windows: c.resizable_windows(),
            enhanced_logging: c.enhanced_logging(),
            in_game_console: c.in_game_console(),
            console_log_max_lines: c.console_log_max_lines(),
            battery_throttle: c.battery_throttle(),
            lwjgl_arm_patch: c.lwjgl_arm_patch(),
            default_scan_frequency: c.default_scan_frequency().name().to_string(),
            backup_auto_before_update: c.backup_auto_before_update(),
            backup_include_saves: c.backup_include_saves(),
            backup_max_count_per_pack: c.backup_max_count_per_pack(),
            backup_max_age_days: c.backup_max_age_days(),
            has_curseforge_api_key: c.has_curseforge_api_key(),
            proxy_enable: c.proxy_enable(),
            proxy_type: c.proxy_type(),
            proxy_host: c.proxy_host(),
            proxy_port: c.proxy_port(),
            uri_handler_enabled: c.uri_handler_enabled(),
            mcp_server_enabled: c.mcp_server_enabled(),
            mcp_auto_approve_read_only: c.mcp_auto_approve_read_only(),
            mcp_allow_state_changes: c.mcp_allow_state_changes(),
            mcp_tool_policies: c.mcp_tool_policies(),
            mcp_port: c.mcp_port(),
            discord_rpc: c.discord_rpc(),
            discord_invites: c.discord_invites(),
            rgb_enable: c.rgb_enable(),
            rgb_backend: c.rgb_backend(),
            rgb_enable_open_rgb: c.rgb_enable_open_rgb(),
            rgb_enable_chroma_native: c.rgb_enable_chroma_native(),
            rgb_enable_chroma_rest: c.rgb_enable_chroma_rest(),
            rgb_enable_windows_dl: c.rgb_enable_windows_dl(),
            rgb_enable_corsair: c.rgb_enable_corsair(),
            rgb_enable_asus_aura: c.rgb_enable_asus_aura(),
            rgb_menu_effect_enable: c.rgb_menu_effect_enable(),
            rgb_effect_style: c.rgb_effect_style(),
            rgb_use_pack_colors: c.rgb_use_pack_colors(),
            rgb_highlight_keys: c.rgb_highlight_keys(),
            launcher_update_check: c.launcher_update_check(),
            running_games_docked: c.running_games_docked(),
            running_games_dock_height: c.running_games_dock_height(),
            quick_start_completed: c.quick_start_completed(),
            gamepad_mode: c.gamepad_mode(),
            auth_wam: c.auth_wam(),
            auth_client_id: c.auth_client_id(),
            last_mod_pack: c.last_mod_pack(),
        }
    }
}

/// One setting write.
#[derive(Clone, Debug, PartialEq)]
pub enum Setting {
    MinRamMb(i64),
    MaxRamMb(i64),
    JvmArgs(String),
    Theme(String),
    UiScalePercent(i32),
    WavyProgress(bool),
    ReduceMotion(bool),
    LocaleOverride(String),
    ShowPackBackgrounds(bool),
    PackColors(bool),
    ImageCycleInterval(String),
    ImageCycleShuffle(bool),
    Debug(bool),
    ResizableWindows(bool),
    EnhancedLogging(bool),
    InGameConsole(bool),
    ConsoleLogMaxLines(i32),
    BatteryThrottle(bool),
    LwjglArmPatch(bool),
    DefaultScanFrequency(ScanFrequency),
    BackupAutoBeforeUpdate(bool),
    BackupIncludeSaves(bool),
    BackupMaxCountPerPack(i32),
    BackupMaxAgeDays(i32),
    ProxyEnable(bool),
    ProxyType(String),
    ProxyHost(String),
    ProxyPort(i32),
    UriHandlerEnabled(bool),
    McpServerEnabled(bool),
    McpAutoApproveReadOnly(bool),
    McpAllowStateChanges(bool),
    McpToolPolicies(String),
    McpPort(i32),
    DiscordRpc(bool),
    DiscordInvites(bool),
    RgbEnable(bool),
    RgbBackend(String),
    RgbEnableOpenRgb(bool),
    RgbEnableChromaNative(bool),
    RgbEnableChromaRest(bool),
    RgbEnableWindowsDl(bool),
    RgbEnableCorsair(bool),
    RgbEnableAsusAura(bool),
    RgbMenuEffectEnable(bool),
    RgbEffectStyle(String),
    RgbUsePackColors(bool),
    RgbHighlightKeys(bool),
    LauncherUpdateCheck(bool),
    RunningGamesDocked(bool),
    RunningGamesDockHeight(i32),
    QuickStartCompleted(bool),
    GamepadMode(Option<bool>),
    AuthWam(bool),
    AuthClientId(String),
    LastModPack(String),
}

/// What the engine must do after a write.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct SideEffects {
    /// Reload the HTTP proxy.
    pub reload_proxy: bool,
    /// Restart the RGB controller from the settings.
    pub restart_rgb: bool,
    /// Repaint the RGB menu effect.
    pub rgb_on_menu: bool,
    /// Discord presence was turned off: close it.
    pub discord_exit: bool,
    /// A restart is needed for it to apply (language).
    pub needs_restart: bool,
    /// MCP server enable / tool registry changed.
    pub refresh_mcp: bool,
    /// Max RAM crossed the compressed-oops limit: warn.
    pub warn_compressed_oops: bool,
}

fn invalid(key: &'static str, reason: impl Into<String>) -> EngineError {
    EngineError::InvalidSetting {
        key,
        reason: reason.into(),
    }
}

impl Setting {
    /// The config key it writes.
    pub fn key(&self) -> &'static str {
        use Setting::*;
        match self {
            MinRamMb(_) => MIN_RAM_KEY,
            MaxRamMb(_) => MAX_RAM_KEY,
            JvmArgs(_) => JVM_ARGS_KEY,
            Theme(_) => THEME_KEY,
            UiScalePercent(_) => UI_SCALE_KEY,
            WavyProgress(_) => WAVY_PROGRESS_KEY,
            ReduceMotion(_) => REDUCE_MOTION_KEY,
            LocaleOverride(_) => LOCALE_OVERRIDE_KEY,
            ShowPackBackgrounds(_) => SHOW_PACK_BACKGROUNDS_KEY,
            PackColors(_) => PACK_COLORS_KEY,
            ImageCycleInterval(_) => IMAGE_CYCLE_INTERVAL_KEY,
            ImageCycleShuffle(_) => IMAGE_CYCLE_SHUFFLE_KEY,
            Debug(_) => LOG_DEBUG_ENABLE_KEY,
            ResizableWindows(_) => RESIZE_WINDOWS_ENABLE_KEY,
            EnhancedLogging(_) => LOG_ENHANCED_ENABLE_KEY,
            InGameConsole(_) => INGAME_CONSOLE_ENABLE_KEY,
            ConsoleLogMaxLines(_) => CONSOLE_LOG_MAX_LINES_KEY,
            BatteryThrottle(_) => BATTERY_THROTTLE_ENABLE_KEY,
            LwjglArmPatch(_) => LWJGL_ARM_PATCH_ENABLE_KEY,
            DefaultScanFrequency(_) => DEFAULT_SCAN_FREQUENCY_KEY,
            BackupAutoBeforeUpdate(_) => BACKUP_AUTO_KEY,
            BackupIncludeSaves(_) => BACKUP_INCLUDE_SAVES_KEY,
            BackupMaxCountPerPack(_) => BACKUP_MAX_COUNT_KEY,
            BackupMaxAgeDays(_) => BACKUP_MAX_AGE_DAYS_KEY,
            ProxyEnable(_) => PROXY_ENABLE_KEY,
            ProxyType(_) => PROXY_TYPE_KEY,
            ProxyHost(_) => PROXY_HOST_KEY,
            ProxyPort(_) => PROXY_PORT_KEY,
            UriHandlerEnabled(_) => URI_HANDLER_ENABLED_KEY,
            McpServerEnabled(_) => MCP_SERVER_ENABLED_KEY,
            McpAutoApproveReadOnly(_) => MCP_AUTO_APPROVE_READ_ONLY_KEY,
            McpAllowStateChanges(_) => MCP_ALLOW_STATE_CHANGES_KEY,
            McpToolPolicies(_) => MCP_TOOL_POLICIES_KEY,
            McpPort(_) => MCP_PORT_KEY,
            DiscordRpc(_) => DISCORD_RPC_ENABLE_KEY,
            DiscordInvites(_) => DISCORD_INVITES_ENABLE_KEY,
            RgbEnable(_) => RGB_ENABLE_KEY,
            RgbBackend(_) => RGB_BACKEND_KEY,
            RgbEnableOpenRgb(_) => RGB_ENABLE_OPENRGB_KEY,
            RgbEnableChromaNative(_) => RGB_ENABLE_CHROMA_NATIVE_KEY,
            RgbEnableChromaRest(_) => RGB_ENABLE_CHROMA_REST_KEY,
            RgbEnableWindowsDl(_) => RGB_ENABLE_WINDOWS_DL_KEY,
            RgbEnableCorsair(_) => RGB_ENABLE_CORSAIR_KEY,
            RgbEnableAsusAura(_) => RGB_ENABLE_ASUS_AURA_KEY,
            RgbMenuEffectEnable(_) => RGB_MENU_EFFECT_ENABLE_KEY,
            RgbEffectStyle(_) => RGB_EFFECT_STYLE_KEY,
            RgbUsePackColors(_) => RGB_USE_PACK_COLORS_KEY,
            RgbHighlightKeys(_) => RGB_HIGHLIGHT_KEYS_KEY,
            LauncherUpdateCheck(_) => LAUNCHER_UPDATE_CHECK_KEY,
            RunningGamesDocked(_) => RUNNING_GAMES_DOCKED_KEY,
            RunningGamesDockHeight(_) => RUNNING_GAMES_DOCK_HEIGHT_KEY,
            QuickStartCompleted(_) => QUICK_START_COMPLETED_KEY,
            GamepadMode(_) => UI_GAMEPAD_MODE_KEY,
            AuthWam(_) => AUTH_WAM_KEY,
            AuthClientId(_) => AUTH_CLIENT_ID_KEY,
            LastModPack(_) => LAST_MP_KEY,
        }
    }

    /// Validates and stores the value; returns the side effects to run.
    pub fn apply(&self, c: &Config) -> EngineResult<SideEffects> {
        use Setting::*;
        let mut fx = SideEffects::default();
        let key = self.key();
        match self {
            MinRamMb(mb) => {
                if !(204..=32 * 1024).contains(mb) {
                    return Err(invalid(key, mb.to_string()));
                }
                c.set_min_ram_mb(*mb);
            }
            MaxRamMb(mb) => {
                if !(1024..=64 * 1024).contains(mb) {
                    return Err(invalid(key, mb.to_string()));
                }
                fx.warn_compressed_oops = crosses_compressed_oops_limit(c.max_ram_mb(), *mb);
                c.set_max_ram_mb(*mb);
            }
            JvmArgs(a) => c
                .set_custom_jvm_args(a)
                .map_err(|e| invalid(key, e.to_string()))?,
            Theme(t) => {
                let canon = THEMES
                    .iter()
                    .find(|x| x.eq_ignore_ascii_case(t.trim()))
                    .ok_or_else(|| invalid(key, t.clone()))?;
                c.set_theme(canon);
                fx.rgb_on_menu = true;
            }
            UiScalePercent(p) => {
                if !UI_SCALE_STEPS.contains(p) {
                    return Err(invalid(key, p.to_string()));
                }
                c.set_ui_scale_percent(*p);
            }
            WavyProgress(v) => c.set_wavy_progress(*v),
            ReduceMotion(v) => c.set_reduce_motion(*v),
            LocaleOverride(tag) => {
                let tag = tag.trim();
                if !tag.is_empty()
                    && !mmcl_i18n::SUPPORTED
                        .iter()
                        .any(|(t, _)| t.eq_ignore_ascii_case(tag))
                {
                    return Err(invalid(key, tag.to_string()));
                }
                fx.needs_restart = !c.locale_override().eq_ignore_ascii_case(tag);
                c.set_locale_override(tag);
            }
            ShowPackBackgrounds(v) => c.set_show_pack_backgrounds(*v),
            PackColors(v) => c.set_pack_colors(*v),
            ImageCycleInterval(s) => {
                if !IMAGE_CYCLE_INTERVALS.contains(&s.as_str()) {
                    return Err(invalid(key, s.clone()));
                }
                c.set_image_cycle_interval(s);
            }
            ImageCycleShuffle(v) => c.set_image_cycle_shuffle(*v),
            Debug(v) => c.set_debug_logging(*v),
            ResizableWindows(v) => c.set_resizable_windows(*v),
            EnhancedLogging(v) => c.set_enhanced_logging(*v),
            InGameConsole(v) => c.set_in_game_console(*v),
            ConsoleLogMaxLines(n) => {
                if *n < 0 {
                    return Err(invalid(key, n.to_string()));
                }
                c.set_console_log_max_lines(*n);
            }
            BatteryThrottle(v) => c.set_battery_throttle(*v),
            LwjglArmPatch(v) => c.set_lwjgl_arm_patch(*v),
            DefaultScanFrequency(f) => c.set_default_scan_frequency(*f),
            BackupAutoBeforeUpdate(v) => c.set_backup_auto_before_update(*v),
            BackupIncludeSaves(v) => c.set_backup_include_saves(*v),
            BackupMaxCountPerPack(n) => {
                if !(0..=100).contains(n) {
                    return Err(invalid(key, n.to_string()));
                }
                c.set_backup_max_count_per_pack(*n);
            }
            BackupMaxAgeDays(n) => {
                if !(0..=365).contains(n) {
                    return Err(invalid(key, n.to_string()));
                }
                c.set_backup_max_age_days(*n);
            }
            ProxyEnable(v) => {
                // Enabled with a blank host is stored as off (the Settings screen warns).
                c.set_proxy_enable(*v && !c.proxy_host().trim().is_empty());
                fx.reload_proxy = true;
            }
            ProxyType(t) => {
                let canon = PROXY_TYPES
                    .iter()
                    .find(|x| x.eq_ignore_ascii_case(t.trim()))
                    .ok_or_else(|| invalid(key, t.clone()))?;
                c.set_proxy_type(canon);
                fx.reload_proxy = true;
            }
            ProxyHost(h) => {
                c.set_proxy_host(h.trim());
                fx.reload_proxy = true;
            }
            ProxyPort(p) => {
                if !(1..=65535).contains(p) {
                    return Err(invalid(key, p.to_string()));
                }
                c.set_proxy_port(*p);
                fx.reload_proxy = true;
            }
            UriHandlerEnabled(v) => c.set_uri_handler_enabled(*v),
            McpServerEnabled(v) => {
                c.set_mcp_server_enabled(*v);
                fx.refresh_mcp = true;
            }
            McpAutoApproveReadOnly(v) => c.set_mcp_auto_approve_read_only(*v),
            McpAllowStateChanges(v) => {
                c.set_mcp_allow_state_changes(*v);
                fx.refresh_mcp = true;
            }
            McpToolPolicies(s) => c.set_mcp_tool_policies(s),
            McpPort(p) => {
                if !(0..=65535).contains(p) {
                    return Err(invalid(key, p.to_string()));
                }
                c.set_mcp_port(*p);
                fx.refresh_mcp = true;
            }
            DiscordRpc(v) => {
                c.set_discord_rpc(*v);
                fx.discord_exit = !*v;
            }
            DiscordInvites(v) => c.set_discord_invites(*v),
            RgbEnable(v) => {
                c.set_rgb_enable(*v);
                fx.restart_rgb = true;
            }
            RgbBackend(b) => {
                let canon = RGB_BACKENDS
                    .iter()
                    .find(|x| x.eq_ignore_ascii_case(b.trim()))
                    .ok_or_else(|| invalid(key, b.clone()))?;
                c.set_rgb_backend(canon);
                fx.restart_rgb = true;
            }
            RgbEnableOpenRgb(v) => {
                c.set_rgb_enable_open_rgb(*v);
                fx.restart_rgb = true;
            }
            RgbEnableChromaNative(v) => {
                c.set_rgb_enable_chroma_native(*v);
                fx.restart_rgb = true;
            }
            RgbEnableChromaRest(v) => {
                c.set_rgb_enable_chroma_rest(*v);
                fx.restart_rgb = true;
            }
            RgbEnableWindowsDl(v) => {
                c.set_rgb_enable_windows_dl(*v);
                fx.restart_rgb = true;
            }
            RgbEnableCorsair(v) => {
                c.set_rgb_enable_corsair(*v);
                fx.restart_rgb = true;
            }
            RgbEnableAsusAura(v) => {
                c.set_rgb_enable_asus_aura(*v);
                fx.restart_rgb = true;
            }
            RgbMenuEffectEnable(v) => {
                c.set_rgb_menu_effect_enable(*v);
                fx.rgb_on_menu = true;
            }
            RgbEffectStyle(s) => {
                let canon = RGB_EFFECT_STYLES
                    .iter()
                    .find(|x| x.eq_ignore_ascii_case(s.trim()))
                    .ok_or_else(|| invalid(key, s.clone()))?;
                c.set_rgb_effect_style(canon);
                fx.rgb_on_menu = true;
            }
            RgbUsePackColors(v) => c.set_rgb_use_pack_colors(*v),
            RgbHighlightKeys(v) => c.set_rgb_highlight_keys(*v),
            LauncherUpdateCheck(v) => c.set_launcher_update_check(*v),
            RunningGamesDocked(v) => c.set_running_games_docked(*v),
            RunningGamesDockHeight(h) => {
                if !(0..=1000).contains(h) {
                    return Err(invalid(key, h.to_string()));
                }
                c.set_running_games_dock_height(*h);
            }
            QuickStartCompleted(v) => c.set_quick_start_completed(*v),
            GamepadMode(m) => c.set_gamepad_mode(*m),
            AuthWam(v) => c.set_auth_wam(*v),
            AuthClientId(id) => c.set_auth_client_id(id.trim()),
            LastModPack(s) => c.set_last_mod_pack(s),
        }
        Ok(fx)
    }
}

/// Builds the RGB settings from the config.
pub fn rgb_settings(c: &Config) -> mmcl_rgb::RgbSettings {
    mmcl_rgb::RgbSettings {
        enabled: c.rgb_enable(),
        backend: mmcl_rgb::BackendChoice::from_config_or_auto(&c.rgb_backend()),
        auto_enabled: mmcl_rgb::AutoToggles {
            openrgb: c.rgb_enable_open_rgb(),
            chroma_native: c.rgb_enable_chroma_native(),
            chroma_rest: c.rgb_enable_chroma_rest(),
            windows_dl: c.rgb_enable_windows_dl(),
            corsair: c.rgb_enable_corsair(),
            asus_aura: c.rgb_enable_asus_aura(),
        },
        menu_effect_enabled: c.rgb_menu_effect_enable(),
        effect_style: mmcl_rgb::EffectStyle::from_config(&c.rgb_effect_style()),
        use_pack_colors: c.rgb_use_pack_colors(),
        highlight_keys: c.rgb_highlight_keys(),
        theme: c.theme(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_config::store::{ConfigStore, first_run_defaults};

    fn config() -> Config {
        Config::new(ConfigStore::in_memory(first_run_defaults()))
    }

    #[test]
    fn reading_every_setting_works_on_a_fresh_config() {
        let s = Settings::read(&config());
        assert_eq!(s.ui_scale_percent, 100);
        assert_eq!(s.proxy_type, "HTTP");
        assert!(!s.rgb_enable);
    }

    #[test]
    fn a_write_reads_back() {
        let c = config();
        Setting::UiScalePercent(125).apply(&c).unwrap();
        Setting::ProxyPort(3128).apply(&c).unwrap();
        Setting::Theme("dark".into()).apply(&c).unwrap();
        let s = Settings::read(&c);
        assert_eq!(s.ui_scale_percent, 125);
        assert_eq!(s.proxy_port, 3128);
        assert_eq!(s.theme, THEME_DARK);
    }

    #[test]
    fn out_of_range_values_are_rejected() {
        let c = config();
        assert!(Setting::UiScalePercent(110).apply(&c).is_err());
        assert!(Setting::ProxyPort(0).apply(&c).is_err());
        assert!(Setting::BackupMaxAgeDays(400).apply(&c).is_err());
        assert!(Setting::Theme("Neon".into()).apply(&c).is_err());
        assert!(Setting::RgbBackend("razer".into()).apply(&c).is_err());
        assert!(Setting::LocaleOverride("xx-YY".into()).apply(&c).is_err());
    }

    #[test]
    fn enabling_the_proxy_without_a_host_stores_off() {
        let c = config();
        let fx = Setting::ProxyEnable(true).apply(&c).unwrap();
        assert!(fx.reload_proxy);
        assert!(!c.proxy_enable());
        Setting::ProxyHost("proxy.local".into()).apply(&c).unwrap();
        Setting::ProxyEnable(true).apply(&c).unwrap();
        assert!(c.proxy_enable());
    }

    #[test]
    fn compressed_oops_warns_on_the_first_crossing_only() {
        assert!(crosses_compressed_oops_limit(31_744, 32_768));
        assert!(!crosses_compressed_oops_limit(32_768, 40_000));
        assert!(!crosses_compressed_oops_limit(8_192, 16_384));
        let c = config();
        let fx = Setting::MaxRamMb(40 * 1024).apply(&c).unwrap();
        assert!(fx.warn_compressed_oops);
    }

    #[test]
    fn side_effects_follow_the_key() {
        let c = config();
        assert!(Setting::DiscordRpc(false).apply(&c).unwrap().discord_exit);
        assert!(Setting::RgbEnable(true).apply(&c).unwrap().restart_rgb);
        assert!(
            Setting::LocaleOverride("de".into())
                .apply(&c)
                .unwrap()
                .needs_restart
        );
    }

    #[test]
    fn saved_themes_match_case_insensitively() {
        assert_eq!(canonical_theme("native (mica)"), THEME_NATIVE);
        assert_eq!(canonical_theme("bogus"), THEME_AUTOMATIC);
    }

    #[test]
    fn rgb_settings_mirror_the_config() {
        let c = config();
        Setting::RgbEnable(true).apply(&c).unwrap();
        assert!(rgb_settings(&c).enabled);
    }
}

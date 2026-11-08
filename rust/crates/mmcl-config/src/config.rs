// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `Config`: the typed facade over [`ConfigStore`] (Java `ConfigManager` and its slices
//! `AppConfig`, `RuntimeConfig`, `NetworkConfig`, `RgbConfig`, `ModPackConfig`,
//! `AuthTokenStore`). One getter/setter pair per key; the defaults live in [`crate::keys`].

use std::path::Path;

use mmcl_i18n::{t, tf};
use mmcl_util::jvm_args::{self, JvmArgsError};
use serde_json::{Map, Value};

use crate::error::ConfigError;
use crate::keys::*;
use crate::scan_frequency::ScanFrequency;
use crate::secrets::{self, SecretCipher};
use crate::store::{self, ConfigStore, StoreHooks};

/// Typed access to every setting.
#[derive(Clone, Debug)]
pub struct Config {
    store: ConfigStore,
}

/// Get-or-init boolean (persists the default on first read).
macro_rules! init_bool {
    ($(#[$doc:meta])* $get:ident, $set:ident, $key:expr, $default:expr) => {
        $(#[$doc])*
        pub fn $get(&self) -> bool {
            self.store.get_or_init_bool($key, $default)
        }
        #[doc = concat!("Sets [`", stringify!($get), "`](Self::", stringify!($get), ").")]
        pub fn $set(&self, value: bool) {
            self.store.set_value($key, Value::Bool(value));
        }
    };
}

/// Plain boolean (the default is never written).
macro_rules! plain_bool {
    ($(#[$doc:meta])* $get:ident, $set:ident, $key:expr, $default:expr) => {
        $(#[$doc])*
        pub fn $get(&self) -> bool {
            self.store.get_bool($key, $default)
        }
        #[doc = concat!("Sets [`", stringify!($get), "`](Self::", stringify!($get), ").")]
        pub fn $set(&self, value: bool) {
            self.store.set_value($key, Value::Bool(value));
        }
    };
}

/// Get-or-init string.
macro_rules! init_string {
    ($(#[$doc:meta])* $get:ident, $set:ident, $key:expr, $default:expr) => {
        $(#[$doc])*
        pub fn $get(&self) -> String {
            self.store.get_or_init_string($key, $default)
        }
        #[doc = concat!("Sets [`", stringify!($get), "`](Self::", stringify!($get), ").")]
        pub fn $set(&self, value: &str) {
            self.store.set_value($key, Value::from(value));
        }
    };
}

/// Get-or-init `i32`.
macro_rules! init_i32 {
    ($(#[$doc:meta])* $get:ident, $set:ident, $key:expr, $default:expr) => {
        $(#[$doc])*
        pub fn $get(&self) -> i32 {
            self.store.get_or_init_i32($key, $default)
        }
        #[doc = concat!("Sets [`", stringify!($get), "`](Self::", stringify!($get), ").")]
        pub fn $set(&self, value: i32) {
            self.store.set_value($key, Value::from(value));
        }
    };
}

/// A window position or size from the config (`NaN` when unset).
#[derive(Copy, Clone, Debug, PartialEq)]
pub struct WindowBounds {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
    pub maximized: bool,
}

impl WindowBounds {
    /// `true` when all four numbers are finite (a saved placement exists).
    pub fn is_complete(&self) -> bool {
        [self.x, self.y, self.width, self.height]
            .iter()
            .all(|v| v.is_finite())
    }
}

fn f64_value(v: f64) -> Value {
    // Non-finite values become `null`, which reads back as absent (Gson threw instead and lost
    // the whole write).
    serde_json::Number::from_f64(v).map_or(Value::Null, Value::Number)
}

fn string_list(v: Option<&Value>) -> Vec<String> {
    match v {
        Some(Value::Array(items)) => items
            .iter()
            .filter_map(|el| match el {
                // Gson's String adapter reads numbers and booleans as their text.
                Value::String(s) => Some(s.clone()),
                Value::Number(n) => Some(n.to_string()),
                Value::Bool(b) => Some(b.to_string()),
                _ => None,
            })
            .collect(),
        _ => Vec::new(),
    }
}

fn is_blank(s: &str) -> bool {
    s.trim().is_empty()
}

impl Config {
    /// Wraps an opened store.
    pub fn new(store: ConfigStore) -> Config {
        Config { store }
    }

    /// Opens the config of these data paths with the default hooks.
    pub fn open(paths: &mmcl_core::DataPaths) -> Config {
        Config::new(ConfigStore::open_for(paths, StoreHooks::default()))
    }

    /// The underlying store.
    pub fn store(&self) -> &ConfigStore {
        &self.store
    }

    /// Writes any pending change now. Call on every exit path.
    pub fn flush(&self) {
        self.store.flush();
    }

    // ---- runtime -------------------------------------------------------------------------

    /// `minRAM` in MB (`-Xms`).
    pub fn min_ram_mb(&self) -> i64 {
        self.store
            .get_or_init_i64(MIN_RAM_KEY, MIN_RAM_MEGABYTES_DEFAULT)
    }

    /// Sets `minRAM`.
    pub fn set_min_ram_mb(&self, mb: i64) {
        self.store.set_value(MIN_RAM_KEY, Value::from(mb));
    }

    /// `maxRAM` in MB (`-Xmx`).
    pub fn max_ram_mb(&self) -> i64 {
        self.store
            .get_or_init_i64(MAX_RAM_KEY, MAX_RAM_MEGABYTES_DEFAULT)
    }

    /// `maxRAM / 1024.0`.
    pub fn max_ram_gb(&self) -> f64 {
        self.max_ram_mb() as f64 / 1024.0
    }

    /// Sets `maxRAM`.
    pub fn set_max_ram_mb(&self, mb: i64) {
        self.store.set_value(MAX_RAM_KEY, Value::from(mb));
    }

    /// The custom JVM args. A stored value that fails validation (control characters or
    /// `${`) is ignored with a warning and the default returned.
    pub fn custom_jvm_args(&self) -> String {
        let stored = self
            .store
            .get_or_init_string(JVM_ARGS_KEY, JVM_ARGS_VALUE_DEFAULT);
        if jvm_args::is_clean(Some(&stored)) {
            stored
        } else {
            mmcl_log::warn_silent(&t!("log.configManager.jvmArgsValidationFailed"));
            JVM_ARGS_VALUE_DEFAULT.to_string()
        }
    }

    /// Validates and saves the custom JVM args durably (written before returning).
    pub fn set_custom_jvm_args(&self, args: &str) -> Result<(), JvmArgsError> {
        let clean = jvm_args::require_clean(Some(args))?;
        self.store
            .set_value_durable(JVM_ARGS_KEY, Value::from(clean));
        Ok(())
    }

    // ---- pack list and selection ---------------------------------------------------------

    /// `lastModPack`: an opaque string (a manifest URL, or a pack name from CLI auto-launch).
    pub fn last_mod_pack(&self) -> String {
        self.store.get_or_init_string(LAST_MP_KEY, "")
    }

    /// Sets `lastModPack`.
    pub fn set_last_mod_pack(&self, value: &str) {
        self.store.set_value(LAST_MP_KEY, Value::from(value));
    }

    fn string_list_key(&self, key: &str) -> Vec<String> {
        self.store.mutate_quiet(|doc| {
            if !doc.contains_key(key) {
                // Inserted without scheduling a write, like Java.
                doc.insert(key.to_string(), Value::Array(Vec::new()));
            }
            string_list(doc.get(key))
        })
    }

    /// `modpacks`: installed manifest URLs, in order.
    pub fn installed_mod_packs(&self) -> Vec<String> {
        self.string_list_key(MOD_PACKS_INSTALLED_KEY)
    }

    /// Sets `modpacks`.
    pub fn set_installed_mod_packs<S: AsRef<str>>(&self, urls: &[S]) {
        let arr = urls.iter().map(|s| Value::from(s.as_ref())).collect();
        self.store
            .set_value(MOD_PACKS_INSTALLED_KEY, Value::Array(arr));
    }

    /// `vanillaVersions`: installed vanilla version ids.
    pub fn installed_vanilla_versions(&self) -> Vec<String> {
        self.string_list_key(VANILLA_VERSIONS_INSTALLED_KEY)
    }

    /// Sets `vanillaVersions`.
    pub fn set_installed_vanilla_versions<S: AsRef<str>>(&self, ids: &[S]) {
        let arr = ids.iter().map(|s| Value::from(s.as_ref())).collect();
        self.store
            .set_value(VANILLA_VERSIONS_INSTALLED_KEY, Value::Array(arr));
    }

    // ---- appearance and locale -----------------------------------------------------------

    init_string!(
        /// `theme`, not validated here (the GUI validates against [`ALLOWED_THEMES`]).
        theme, set_theme, THEME_KEY, THEME_DEFAULT
    );

    /// `localeOverride`: a BCP-47 tag, blank meaning "detect from the OS". Never
    /// default-written.
    pub fn locale_override(&self) -> String {
        self.store.get_string(LOCALE_OVERRIDE_KEY, "")
    }

    /// Sets `localeOverride` (`""` clears it).
    pub fn set_locale_override(&self, tag: &str) {
        self.store.set_value(LOCALE_OVERRIDE_KEY, Value::from(tag));
    }

    init_bool!(
        /// `wavyProgress`.
        wavy_progress, set_wavy_progress, WAVY_PROGRESS_KEY, WAVY_PROGRESS_DEFAULT
    );
    init_bool!(
        /// `reduceMotion`.
        reduce_motion, set_reduce_motion, REDUCE_MOTION_KEY, REDUCE_MOTION_DEFAULT
    );
    init_bool!(
        /// `packColors`.
        pack_colors, set_pack_colors, PACK_COLORS_KEY, PACK_COLORS_DEFAULT
    );
    init_i32!(
        /// `uiScalePercent` (the UI offers [`UI_SCALE_OPTIONS`]).
        ui_scale_percent, set_ui_scale_percent, UI_SCALE_KEY, UI_SCALE_DEFAULT
    );
    plain_bool!(
        /// `showPackBackgrounds`.
        show_pack_backgrounds, set_show_pack_backgrounds, SHOW_PACK_BACKGROUNDS_KEY,
        SHOW_PACK_BACKGROUNDS_DEFAULT
    );

    /// `imageCycleInterval`: one of [`IMAGE_CYCLE_INTERVAL_OPTIONS`], else the default.
    pub fn image_cycle_interval(&self) -> String {
        let v = self
            .store
            .get_string(IMAGE_CYCLE_INTERVAL_KEY, IMAGE_CYCLE_INTERVAL_DEFAULT);
        if IMAGE_CYCLE_INTERVAL_OPTIONS.contains(&v.as_str()) {
            v
        } else {
            IMAGE_CYCLE_INTERVAL_DEFAULT.to_string()
        }
    }

    /// Sets `imageCycleInterval` (stored as given, validated on read).
    pub fn set_image_cycle_interval(&self, interval: &str) {
        self.store
            .set_value(IMAGE_CYCLE_INTERVAL_KEY, Value::from(interval));
    }

    plain_bool!(
        /// `imageCycleShuffle`.
        image_cycle_shuffle, set_image_cycle_shuffle, IMAGE_CYCLE_SHUFFLE_KEY,
        IMAGE_CYCLE_SHUFFLE_DEFAULT
    );

    // ---- logging -------------------------------------------------------------------------

    /// `debug`; always true in dev builds.
    pub fn debug_logging(&self) -> bool {
        let stored = self
            .store
            .get_or_init_bool(LOG_DEBUG_ENABLE_KEY, LOG_DEBUG_ENABLE_DEFAULT);
        self.store.options().is_dev || stored
    }

    /// Sets `debug` and tells the logger.
    pub fn set_debug_logging(&self, enabled: bool) {
        self.store
            .set_value(LOG_DEBUG_ENABLE_KEY, Value::Bool(enabled));
        (self.store.hooks().on_debug_changed)(enabled);
    }

    init_bool!(
        /// `enhancedLogging`.
        enhanced_logging, set_enhanced_logging, LOG_ENHANCED_ENABLE_KEY,
        LOG_ENHANCED_ENABLE_DEFAULT
    );

    // ---- Discord and windows -------------------------------------------------------------

    init_bool!(
        /// `discordRpc`.
        discord_rpc, set_discord_rpc, DISCORD_RPC_ENABLE_KEY, DISCORD_RPC_ENABLE_DEFAULT
    );
    init_bool!(
        /// `discordInvites`.
        discord_invites, set_discord_invites, DISCORD_INVITES_ENABLE_KEY,
        DISCORD_INVITES_ENABLE_DEFAULT
    );
    init_bool!(
        /// `resizableWindows`.
        resizable_windows, set_resizable_windows, RESIZE_WINDOWS_ENABLE_KEY,
        RESIZE_WINDOWS_ENABLE_DEFAULT
    );
    init_bool!(
        /// `inGameConsole` ("show console on launch").
        in_game_console, set_in_game_console, INGAME_CONSOLE_ENABLE_KEY,
        INGAME_CONSOLE_ENABLE_DEFAULT
    );
    init_bool!(
        /// `runningGamesDocked`.
        running_games_docked, set_running_games_docked, RUNNING_GAMES_DOCKED_KEY,
        RUNNING_GAMES_DOCKED_DEFAULT
    );
    init_i32!(
        /// `runningGamesDockHeight` in permille of the window.
        running_games_dock_height, set_running_games_dock_height,
        RUNNING_GAMES_DOCK_HEIGHT_KEY, RUNNING_GAMES_DOCK_HEIGHT_DEFAULT
    );

    /// `consoleLogMaxLines`; 0 means unlimited.
    pub fn console_log_max_lines(&self) -> i32 {
        self.store
            .get_i32(CONSOLE_LOG_MAX_LINES_KEY, CONSOLE_LOG_MAX_LINES_DEFAULT)
    }

    /// Sets `consoleLogMaxLines`, clamped to 0 or more.
    pub fn set_console_log_max_lines(&self, max: i32) {
        self.store
            .set_value(CONSOLE_LOG_MAX_LINES_KEY, Value::from(max.max(0)));
    }

    /// `windowX`, `windowY`, `windowWidth`, `windowHeight` (`NaN` when unset) and
    /// `windowMaximized`.
    pub fn window_bounds(&self) -> WindowBounds {
        WindowBounds {
            x: self.store.get_f64(WINDOW_X_KEY, f64::NAN),
            y: self.store.get_f64(WINDOW_Y_KEY, f64::NAN),
            width: self.store.get_f64(WINDOW_WIDTH_KEY, f64::NAN),
            height: self.store.get_f64(WINDOW_HEIGHT_KEY, f64::NAN),
            maximized: self
                .store
                .get_bool(WINDOW_MAXIMIZED_KEY, WINDOW_MAXIMIZED_DEFAULT),
        }
    }

    /// Saves all five window values in one change.
    pub fn set_window_bounds(&self, b: WindowBounds) {
        self.store.mutate(|doc| {
            doc.insert(WINDOW_X_KEY.into(), f64_value(b.x));
            doc.insert(WINDOW_Y_KEY.into(), f64_value(b.y));
            doc.insert(WINDOW_WIDTH_KEY.into(), f64_value(b.width));
            doc.insert(WINDOW_HEIGHT_KEY.into(), f64_value(b.height));
            doc.insert(WINDOW_MAXIMIZED_KEY.into(), Value::Bool(b.maximized));
        });
    }

    // ---- app lifecycle and MCP -----------------------------------------------------------

    init_bool!(
        /// `launcherUpdateCheck`.
        launcher_update_check, set_launcher_update_check, LAUNCHER_UPDATE_CHECK_KEY,
        LAUNCHER_UPDATE_CHECK_DEFAULT
    );
    init_bool!(
        /// `uriHandlerEnabled`: when false, neither argv nor IPC URIs are dispatched.
        uri_handler_enabled, set_uri_handler_enabled, URI_HANDLER_ENABLED_KEY,
        URI_HANDLER_ENABLED_DEFAULT
    );
    init_bool!(
        /// `mcpServerEnabled`.
        mcp_server_enabled, set_mcp_server_enabled, MCP_SERVER_ENABLED_KEY,
        MCP_SERVER_ENABLED_DEFAULT
    );
    init_bool!(
        /// `mcpAutoApproveReadOnly`.
        mcp_auto_approve_read_only, set_mcp_auto_approve_read_only,
        MCP_AUTO_APPROVE_READ_ONLY_KEY, MCP_AUTO_APPROVE_READ_ONLY_DEFAULT
    );
    init_string!(
        /// `mcpToolPolicies`: a JSON object as a string, `{tool: ALWAYS_ALLOW|ASK|DISABLED}`.
        /// An unreadable value means no policies (the MCP layer fails closed).
        mcp_tool_policies, set_mcp_tool_policies, MCP_TOOL_POLICIES_KEY,
        MCP_TOOL_POLICIES_DEFAULT
    );
    init_bool!(
        /// `mcpAllowStateChanges`.
        mcp_allow_state_changes, set_mcp_allow_state_changes, MCP_ALLOW_STATE_CHANGES_KEY,
        MCP_ALLOW_STATE_CHANGES_DEFAULT
    );

    /// `mcpPort`; the default is the identity's MCP port.
    pub fn mcp_port(&self) -> i32 {
        let def = i32::from(self.store.options().default_mcp_port);
        self.store.get_or_init_i32(MCP_PORT_KEY, def)
    }

    /// Sets `mcpPort`; values outside `1..=65535` store the default instead.
    pub fn set_mcp_port(&self, port: i32) {
        let safe = if (1..=65535).contains(&port) {
            port
        } else {
            i32::from(self.store.options().default_mcp_port)
        };
        self.store.set_value(MCP_PORT_KEY, Value::from(safe));
    }

    init_string!(
        /// `mcpToken`: the MCP bearer token (the reason the file is owner-only).
        mcp_token, set_mcp_token, MCP_TOKEN_KEY, MCP_TOKEN_DEFAULT
    );
    init_string!(
        /// `defaultAccountUuid`.
        default_account_uuid, set_default_account_uuid, DEFAULT_ACCOUNT_UUID_KEY, ""
    );
    init_bool!(
        /// `quickStartCompleted`.
        quick_start_completed, set_quick_start_completed, QUICK_START_COMPLETED_KEY, false
    );
    init_bool!(
        /// `batteryThrottleEnable`.
        battery_throttle, set_battery_throttle, BATTERY_THROTTLE_ENABLE_KEY,
        BATTERY_THROTTLE_ENABLE_DEFAULT
    );
    init_bool!(
        /// `lwjglArmPatch`.
        lwjgl_arm_patch, set_lwjgl_arm_patch, LWJGL_ARM_PATCH_ENABLE_KEY,
        LWJGL_ARM_PATCH_ENABLE_DEFAULT
    );

    // ---- RGB -----------------------------------------------------------------------------

    init_bool!(
        /// `rgbEnable`.
        rgb_enable, set_rgb_enable, RGB_ENABLE_KEY, RGB_ENABLE_DEFAULT
    );

    /// `rgbBackend`: one of [`RGB_BACKENDS_ACCEPTED`], else `auto`.
    pub fn rgb_backend(&self) -> String {
        let v = self
            .store
            .get_or_init_string(RGB_BACKEND_KEY, RGB_BACKEND_DEFAULT);
        if RGB_BACKENDS_ACCEPTED.contains(&v.as_str()) {
            v
        } else {
            RGB_BACKEND_DEFAULT.to_string()
        }
    }

    /// Sets `rgbBackend`; an unaccepted value stores `auto`.
    pub fn set_rgb_backend(&self, backend: &str) {
        let v = if RGB_BACKENDS_ACCEPTED.contains(&backend) {
            backend
        } else {
            RGB_BACKEND_DEFAULT
        };
        self.store.set_value(RGB_BACKEND_KEY, Value::from(v));
    }

    init_bool!(
        /// `rgbUsePackColors`.
        rgb_use_pack_colors, set_rgb_use_pack_colors, RGB_USE_PACK_COLORS_KEY,
        RGB_USE_PACK_COLORS_DEFAULT
    );
    init_bool!(
        /// `rgbHighlightKeys`.
        rgb_highlight_keys, set_rgb_highlight_keys, RGB_HIGHLIGHT_KEYS_KEY,
        RGB_HIGHLIGHT_KEYS_DEFAULT
    );
    init_bool!(
        /// `rgbEnableOpenRGB`.
        rgb_enable_open_rgb, set_rgb_enable_open_rgb, RGB_ENABLE_OPENRGB_KEY,
        RGB_ENABLE_OPENRGB_DEFAULT
    );
    init_bool!(
        /// `rgbEnableChromaNative`.
        rgb_enable_chroma_native, set_rgb_enable_chroma_native, RGB_ENABLE_CHROMA_NATIVE_KEY,
        RGB_ENABLE_CHROMA_NATIVE_DEFAULT
    );
    init_bool!(
        /// `rgbEnableChromaRest`.
        rgb_enable_chroma_rest, set_rgb_enable_chroma_rest, RGB_ENABLE_CHROMA_REST_KEY,
        RGB_ENABLE_CHROMA_REST_DEFAULT
    );
    init_bool!(
        /// `rgbEnableWindowsDL`.
        rgb_enable_windows_dl, set_rgb_enable_windows_dl, RGB_ENABLE_WINDOWS_DL_KEY,
        RGB_ENABLE_WINDOWS_DL_DEFAULT
    );
    init_bool!(
        /// `rgbEnableCorsair`.
        rgb_enable_corsair, set_rgb_enable_corsair, RGB_ENABLE_CORSAIR_KEY,
        RGB_ENABLE_CORSAIR_DEFAULT
    );
    init_bool!(
        /// `rgbEnableAsusAura`.
        rgb_enable_asus_aura, set_rgb_enable_asus_aura, RGB_ENABLE_ASUS_AURA_KEY,
        RGB_ENABLE_ASUS_AURA_DEFAULT
    );
    init_bool!(
        /// `rgbMenuEffectEnable`.
        rgb_menu_effect_enable, set_rgb_menu_effect_enable, RGB_MENU_EFFECT_ENABLE_KEY,
        RGB_MENU_EFFECT_ENABLE_DEFAULT
    );

    /// `rgbEffectStyle`: one of [`RGB_EFFECT_STYLES`], else the default.
    pub fn rgb_effect_style(&self) -> String {
        let v = self
            .store
            .get_or_init_string(RGB_EFFECT_STYLE_KEY, RGB_EFFECT_STYLE_DEFAULT);
        if RGB_EFFECT_STYLES.contains(&v.as_str()) {
            v
        } else {
            RGB_EFFECT_STYLE_DEFAULT.to_string()
        }
    }

    /// Sets `rgbEffectStyle`; an unknown style is ignored (nothing stored).
    pub fn set_rgb_effect_style(&self, style: &str) {
        if RGB_EFFECT_STYLES.contains(&style) {
            self.store
                .set_value(RGB_EFFECT_STYLE_KEY, Value::from(style));
        }
    }

    // ---- proxy ---------------------------------------------------------------------------

    init_bool!(
        /// `proxyEnable`.
        proxy_enable, set_proxy_enable, PROXY_ENABLE_KEY, PROXY_ENABLE_DEFAULT
    );
    init_string!(
        /// `proxyHost`.
        proxy_host, set_proxy_host, PROXY_HOST_KEY, PROXY_HOST_DEFAULT
    );
    init_i32!(
        /// `proxyPort` (not range-checked, like Java).
        proxy_port, set_proxy_port, PROXY_PORT_KEY, PROXY_PORT_DEFAULT
    );
    init_string!(
        /// `proxyType`: `HTTP` or `SOCKS` (not validated, like Java).
        proxy_type, set_proxy_type, PROXY_TYPE_KEY, PROXY_TYPE_DEFAULT
    );

    // ---- backups -------------------------------------------------------------------------

    plain_bool!(
        /// `backupAutoBeforeUpdate`.
        backup_auto_before_update, set_backup_auto_before_update, BACKUP_AUTO_KEY,
        BACKUP_AUTO_DEFAULT
    );

    /// `backupMaxCountPerPack`; 0 means no count cap.
    pub fn backup_max_count_per_pack(&self) -> i32 {
        self.store
            .get_i32(BACKUP_MAX_COUNT_KEY, BACKUP_MAX_COUNT_DEFAULT)
    }

    /// Sets `backupMaxCountPerPack`, clamped to 0 or more.
    pub fn set_backup_max_count_per_pack(&self, max: i32) {
        self.store
            .set_value(BACKUP_MAX_COUNT_KEY, Value::from(max.max(0)));
    }

    /// `backupMaxAgeDays`; 0 means no age cap.
    pub fn backup_max_age_days(&self) -> i32 {
        self.store
            .get_i32(BACKUP_MAX_AGE_DAYS_KEY, BACKUP_MAX_AGE_DAYS_DEFAULT)
    }

    /// Sets `backupMaxAgeDays`, clamped to 0 or more.
    pub fn set_backup_max_age_days(&self, days: i32) {
        self.store
            .set_value(BACKUP_MAX_AGE_DAYS_KEY, Value::from(days.max(0)));
    }

    plain_bool!(
        /// `backupIncludeSaves`.
        backup_include_saves, set_backup_include_saves, BACKUP_INCLUDE_SAVES_KEY,
        BACKUP_INCLUDE_SAVES_DEFAULT
    );

    // ---- per-pack maps -------------------------------------------------------------------

    /// Sets `section[entry] = value` (or removes it when `None`), replacing a section that is
    /// not an object.
    fn set_in_section(&self, section: &str, entry: &str, value: Option<Value>) {
        self.store.mutate(|doc| {
            if !matches!(doc.get(section), Some(Value::Object(_))) {
                doc.insert(section.to_string(), Value::Object(Map::new()));
            }
            if let Some(Value::Object(map)) = doc.get_mut(section) {
                match value {
                    Some(v) => {
                        map.insert(entry.to_string(), v);
                    }
                    None => {
                        map.shift_remove(entry);
                    }
                }
            }
        });
    }

    /// `alwaysVerifyOnLaunchByPack[url]`, false by default or for a blank URL.
    pub fn always_verify_on_launch(&self, pack_url: &str) -> bool {
        if is_blank(pack_url) {
            return ALWAYS_VERIFY_ON_LAUNCH_DEFAULT;
        }
        let read = self
            .store
            .read(|doc| match doc.get(ALWAYS_VERIFY_BY_PACK_KEY) {
                None => Ok(None),
                Some(Value::Object(map)) => match map.get(pack_url) {
                    None => Ok(None),
                    Some(v) => crate::coerce::as_bool(v).map(Some).ok_or(()),
                },
                Some(_) => Err(()),
            });
        match read {
            Ok(v) => v.unwrap_or(ALWAYS_VERIFY_ON_LAUNCH_DEFAULT),
            Err(()) => {
                mmcl_log::warn_silent(&tf!("log.modPackConfig.alwaysVerifyReadFailed", pack_url));
                ALWAYS_VERIFY_ON_LAUNCH_DEFAULT
            }
        }
    }

    /// Sets `alwaysVerifyOnLaunchByPack[url]`; a blank URL is a no-op.
    pub fn set_always_verify_on_launch(&self, pack_url: &str, value: bool) {
        if is_blank(pack_url) {
            return;
        }
        self.set_in_section(
            ALWAYS_VERIFY_BY_PACK_KEY,
            pack_url,
            Some(Value::Bool(value)),
        );
    }

    /// `defaultScanFrequency`; unknown or absent gives `DAILY`.
    pub fn default_scan_frequency(&self) -> ScanFrequency {
        let name = self.store.read(|doc| {
            doc.get(DEFAULT_SCAN_FREQUENCY_KEY)
                .and_then(crate::coerce::as_string)
        });
        ScanFrequency::from_name_safe(name.as_deref())
    }

    /// Sets `defaultScanFrequency`.
    pub fn set_default_scan_frequency(&self, frequency: ScanFrequency) {
        self.store
            .set_value(DEFAULT_SCAN_FREQUENCY_KEY, Value::from(frequency.name()));
    }

    /// `scanFrequencyByPack[url]`: `None` (use the global default) when absent, blank or
    /// unknown.
    pub fn scan_frequency_for_pack(&self, pack_url: &str) -> Option<ScanFrequency> {
        if is_blank(pack_url) {
            return None;
        }
        let read = self
            .store
            .read(|doc| match doc.get(SCAN_FREQUENCY_BY_PACK_KEY) {
                None => Ok(None),
                Some(Value::Object(map)) => match map.get(pack_url) {
                    None => Ok(None),
                    Some(v) => crate::coerce::as_string(v).map(Some).ok_or(()),
                },
                Some(_) => Err(()),
            });
        match read {
            Ok(name) => name
                .filter(|n| !is_blank(n))
                .and_then(|n| ScanFrequency::from_name(&n)),
            Err(()) => {
                mmcl_log::warn_silent(&tf!("log.modPackConfig.scanFrequencyReadFailed", pack_url));
                None
            }
        }
    }

    /// Sets `scanFrequencyByPack[url]`; `None` removes the override. A blank URL is a no-op.
    pub fn set_scan_frequency_for_pack(&self, pack_url: &str, frequency: Option<ScanFrequency>) {
        if is_blank(pack_url) {
            return;
        }
        self.set_in_section(
            SCAN_FREQUENCY_BY_PACK_KEY,
            pack_url,
            frequency.map(|f| Value::from(f.name())),
        );
    }

    /// The pack's override, else the global default.
    pub fn effective_scan_frequency_for_pack(&self, pack_url: &str) -> ScanFrequency {
        self.scan_frequency_for_pack(pack_url)
            .unwrap_or_else(|| self.default_scan_frequency())
    }

    /// `accountOverrideByPack[key]`: the account UUID a pack launches with, `None` for the
    /// default account (absent, `null`, blank or malformed).
    pub fn account_override_for_pack(&self, pack_key: &str) -> Option<String> {
        if is_blank(pack_key) {
            return None;
        }
        self.store
            .read(|doc| match doc.get(ACCOUNT_OVERRIDE_BY_PACK_KEY) {
                Some(Value::Object(map)) => map
                    .get(pack_key)
                    .filter(|v| !v.is_null())
                    .and_then(crate::coerce::as_string)
                    .filter(|s| !is_blank(s)),
                _ => None,
            })
    }

    /// Sets `accountOverrideByPack[key]`; `None` or blank removes the override. A blank key
    /// is a no-op.
    pub fn set_account_override_for_pack(&self, pack_key: &str, uuid: Option<&str>) {
        if is_blank(pack_key) {
            return;
        }
        let value = uuid.filter(|u| !is_blank(u)).map(Value::from);
        self.set_in_section(ACCOUNT_OVERRIDE_BY_PACK_KEY, pack_key, value);
    }

    // ---- secrets -------------------------------------------------------------------------

    fn stored_curseforge_envelope(&self) -> Option<String> {
        self.store.read(|doc| {
            doc.get(CURSEFORGE_API_KEY_KEY)
                .and_then(|v| v.as_str())
                .filter(|s| !is_blank(s))
                .map(str::to_string)
        })
    }

    /// The decrypted CurseForge API key; `None` when absent, blank, not a string, or not
    /// decryptable on this machine (logged).
    pub fn curseforge_api_key(&self, cipher: &dyn SecretCipher) -> Option<String> {
        let envelope = self.stored_curseforge_envelope()?;
        match cipher.decrypt(&envelope) {
            Ok(key) => Some(key),
            Err(e) => {
                mmcl_log::warn_silent(&tf!(
                    "log.authTokenStore.cfKeyDecryptFailed",
                    secrets::describe(e.as_ref())
                ));
                None
            }
        }
    }

    /// Saves the CurseForge API key encrypted, durably. `None` or blank removes it. On an
    /// encryption failure nothing is stored and a warning is logged.
    pub fn set_curseforge_api_key(&self, cipher: &dyn SecretCipher, key: Option<&str>) {
        match key.filter(|k| !is_blank(k)) {
            None => {
                self.store.mutate_durable(|doc| {
                    doc.shift_remove(CURSEFORGE_API_KEY_KEY);
                });
            }
            Some(k) => match cipher.encrypt(k) {
                Ok(envelope) => self
                    .store
                    .set_value_durable(CURSEFORGE_API_KEY_KEY, Value::from(envelope)),
                Err(e) => mmcl_log::warn_silent(&tf!(
                    "log.authTokenStore.cfKeyEncryptFailed",
                    secrets::describe(e.as_ref())
                )),
            },
        }
    }

    /// `true` when a non-blank CurseForge key envelope is stored (not decrypted).
    pub fn has_curseforge_api_key(&self) -> bool {
        self.stored_curseforge_envelope().is_some()
    }

    // ---- Rust-only: sign-in and gamepad --------------------------------------------------

    /// `auth.clientId`: the Microsoft OAuth client ID; blank or absent gives the official
    /// launcher ID. Never default-written.
    pub fn auth_client_id(&self) -> String {
        let v = self
            .store
            .get_string(AUTH_CLIENT_ID_KEY, AUTH_CLIENT_ID_DEFAULT);
        if is_blank(&v) {
            AUTH_CLIENT_ID_DEFAULT.to_string()
        } else {
            v.trim().to_string()
        }
    }

    /// Sets `auth.clientId`; blank removes it (back to the official ID).
    pub fn set_auth_client_id(&self, client_id: &str) {
        if is_blank(client_id) {
            self.store.remove(AUTH_CLIENT_ID_KEY);
        } else {
            self.store
                .set_value(AUTH_CLIENT_ID_KEY, Value::from(client_id.trim()));
        }
    }

    /// `true` when `auth.clientId` is our own (not the official launcher ID).
    pub fn has_custom_auth_client_id(&self) -> bool {
        !self
            .auth_client_id()
            .eq_ignore_ascii_case(AUTH_CLIENT_ID_DEFAULT)
    }

    plain_bool!(
        /// `auth.wam`: the user's opt-in for the Windows account broker.
        auth_wam, set_auth_wam, AUTH_WAM_KEY, AUTH_WAM_DEFAULT
    );

    /// Whether WAM sign-in may be offered: opted in **and** a client ID of our own is set
    /// (WAM cannot use the official launcher ID).
    pub fn wam_active(&self) -> bool {
        self.auth_wam() && self.has_custom_auth_client_id()
    }

    /// `ui.gamepadMode`: `Some(on/off)` when the user chose, `None` for auto-detection.
    pub fn gamepad_mode(&self) -> Option<bool> {
        self.store.read(|doc| {
            doc.get(UI_GAMEPAD_MODE_KEY)
                .filter(|v| !v.is_null())
                .and_then(crate::coerce::as_bool)
        })
    }

    /// Sets `ui.gamepadMode`; `None` removes it (back to auto-detection).
    pub fn set_gamepad_mode(&self, mode: Option<bool>) {
        match mode {
            Some(v) => self.store.set_value(UI_GAMEPAD_MODE_KEY, Value::Bool(v)),
            None => {
                self.store.remove(UI_GAMEPAD_MODE_KEY);
            }
        }
    }

    // ---- import / export -----------------------------------------------------------------

    /// Writes the current settings to `dest` as compact JSON (owner-only, atomic).
    pub fn export_to(&self, dest: &Path) -> Result<(), ConfigError> {
        let bytes = store::serialize_document(&self.store.snapshot());
        mmcl_core::fsx::atomic_write(dest, &bytes, true)
            .map(|_| ())
            .map_err(|e| {
                mmcl_log::error_silent(&tf!("log.configManager.exportFailed", e.to_string()));
                ConfigError::Export(e)
            })
    }

    /// Replaces the settings with the document in `src`, migrates it, and writes it
    /// immediately (Java only scheduled the write).
    pub fn import_from(&self, src: &Path) -> Result<(), ConfigError> {
        let doc = std::fs::read(src)
            .map_err(store::ParseError::from)
            .and_then(|b| store::parse_document(&b));
        match doc {
            Ok(doc) => {
                self.store.replace(doc);
                Ok(())
            }
            Err(e) => {
                mmcl_log::error_silent(&tf!("log.configManager.importFailed", e.to_string()));
                Err(ConfigError::Import(e))
            }
        }
    }
}

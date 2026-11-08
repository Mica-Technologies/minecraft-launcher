// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Every `configuration.json` key and its default (Java `ConfigConstants`), plus the Rust-only
//! keys. Key strings are part of the on-disk format shared with the Java launcher: never rename
//! one.

/// Schema version of the document. Bump in lockstep with Java's `ConfigConstants.CONFIG_VERSION`.
pub const CONFIG_VERSION: i64 = 7;
pub const CONFIG_VERSION_KEY: &str = "configVersion";

// ---- runtime -------------------------------------------------------------------------------

pub const MIN_RAM_KEY: &str = "minRAM";
pub const MIN_RAM_MEGABYTES_DEFAULT: i64 = 512;
pub const MAX_RAM_KEY: &str = "maxRAM";
pub const MAX_RAM_MEGABYTES_DEFAULT: i64 = 2048;
pub const JVM_ARGS_KEY: &str = "jvmArgs";

/// The default custom JVM args (Aikar's flags with `ExplicitGCInvokesConcurrent`).
pub const JVM_ARGS_VALUE_DEFAULT: &str = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+ExplicitGCInvokesConcurrent -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=16M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true";

/// The pre-2026.10 default, recognised only by the v7 migration. **Never hand it to a JVM**:
/// `DisableExplicitGC` stops direct buffers from being freed.
pub const JVM_ARGS_VALUE_DEFAULT_PRE_2026_10: &str = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=16M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true";

pub const JVM_PRESET_PERFORMANCE_ARGS: &str = JVM_ARGS_VALUE_DEFAULT;
pub const JVM_PRESET_LOW_MEMORY_ARGS: &str =
    "-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=1M";
pub const JVM_PRESET_DEBUG_ARGS: &str = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -ea -verbose:gc -Xlog:gc*:file=gc.log:time,uptime,level,tags";
pub const JVM_PRESET_NONE_ARGS: &str = "";

/// A JVM argument preset offered in Settings.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub struct JvmPreset {
    /// Stable internal id.
    pub id: &'static str,
    /// The Java launcher's (English) display name.
    pub english_name: &'static str,
    /// Localization key of the display name.
    pub label_key: &'static str,
    /// The arguments.
    pub args: &'static str,
}

impl JvmPreset {
    /// The localized display name.
    pub fn label(&self) -> String {
        mmcl_i18n::get_or(self.label_key, self.english_name).into_owned()
    }
}

/// The JVM presets, in Java's order.
pub const JVM_PRESETS: [JvmPreset; 4] = [
    JvmPreset {
        id: "performance",
        english_name: "Performance (Aikar's Flags)",
        label_key: "settings.jvmPreset.option.performance",
        args: JVM_PRESET_PERFORMANCE_ARGS,
    },
    JvmPreset {
        id: "lowMemory",
        english_name: "Low Memory",
        label_key: "settings.jvmPreset.option.lowMemory",
        args: JVM_PRESET_LOW_MEMORY_ARGS,
    },
    JvmPreset {
        id: "debug",
        english_name: "Debug",
        label_key: "settings.jvmPreset.option.debug",
        args: JVM_PRESET_DEBUG_ARGS,
    },
    JvmPreset {
        id: "none",
        english_name: "None",
        label_key: "settings.jvmPreset.option.none",
        args: JVM_PRESET_NONE_ARGS,
    },
];

/// The preset whose args equal `args` exactly, if any (Settings shows "Custom" otherwise).
pub fn jvm_preset_for_args(args: &str) -> Option<&'static JvmPreset> {
    JVM_PRESETS.iter().find(|p| p.args == args)
}

// ---- pack list and selection ---------------------------------------------------------------

pub const LAST_MP_KEY: &str = "lastModPack";
pub const MOD_PACKS_INSTALLED_KEY: &str = "modpacks";
pub const VANILLA_VERSIONS_INSTALLED_KEY: &str = "vanillaVersions";

// ---- appearance and locale -----------------------------------------------------------------

pub const THEME_KEY: &str = "theme";
pub const THEME_DARK: &str = "Dark";
pub const THEME_LIGHT: &str = "Light";
pub const THEME_AUTOMATIC: &str = "Automatic";
pub const THEME_BLUE_GRAY: &str = "Blue+gray";
pub const THEME_ORANGE_PURPLE: &str = "Orange+purple";
pub const THEME_CREEPER: &str = "Creeper";
pub const THEME_NATIVE: &str = "Native (Mica)";
pub const THEME_DEFAULT: &str = THEME_NATIVE;
pub const ALLOWED_THEMES: [&str; 7] = [
    THEME_AUTOMATIC,
    THEME_DARK,
    THEME_LIGHT,
    THEME_BLUE_GRAY,
    THEME_ORANGE_PURPLE,
    THEME_CREEPER,
    THEME_NATIVE,
];
pub const LOCALE_OVERRIDE_KEY: &str = "localeOverride";
pub const WAVY_PROGRESS_KEY: &str = "wavyProgress";
pub const WAVY_PROGRESS_DEFAULT: bool = true;
pub const REDUCE_MOTION_KEY: &str = "reduceMotion";
pub const REDUCE_MOTION_DEFAULT: bool = false;
pub const PACK_COLORS_KEY: &str = "packColors";
pub const PACK_COLORS_DEFAULT: bool = true;
pub const UI_SCALE_KEY: &str = "uiScalePercent";
pub const UI_SCALE_DEFAULT: i32 = 100;
pub const UI_SCALE_OPTIONS: [i32; 5] = [50, 75, 100, 125, 150];
pub const SHOW_PACK_BACKGROUNDS_KEY: &str = "showPackBackgrounds";
pub const SHOW_PACK_BACKGROUNDS_DEFAULT: bool = true;
pub const IMAGE_CYCLE_INTERVAL_KEY: &str = "imageCycleInterval";
pub const IMAGE_CYCLE_INTERVAL_DEFAULT: &str = "30s";
pub const IMAGE_CYCLE_INTERVAL_OPTIONS: [&str; 13] = [
    "5s", "15s", "30s", "1m", "5m", "15m", "30m", "1h", "6h", "12h", "1d", "7d", "never",
];
pub const IMAGE_CYCLE_SHUFFLE_KEY: &str = "imageCycleShuffle";
pub const IMAGE_CYCLE_SHUFFLE_DEFAULT: bool = false;

// ---- logging -------------------------------------------------------------------------------

pub const LOG_DEBUG_ENABLE_KEY: &str = "debug";
pub const LOG_DEBUG_ENABLE_DEFAULT: bool = false;
pub const LOG_ENHANCED_ENABLE_KEY: &str = "enhancedLogging";
pub const LOG_ENHANCED_ENABLE_DEFAULT: bool = true;

// ---- Discord and windows -------------------------------------------------------------------

pub const DISCORD_RPC_ENABLE_KEY: &str = "discordRpc";
pub const DISCORD_RPC_ENABLE_DEFAULT: bool = true;
pub const DISCORD_INVITES_ENABLE_KEY: &str = "discordInvites";
pub const DISCORD_INVITES_ENABLE_DEFAULT: bool = true;
pub const RESIZE_WINDOWS_ENABLE_KEY: &str = "resizableWindows";
pub const RESIZE_WINDOWS_ENABLE_DEFAULT: bool = true;
pub const INGAME_CONSOLE_ENABLE_KEY: &str = "inGameConsole";
pub const INGAME_CONSOLE_ENABLE_DEFAULT: bool = false;
pub const RUNNING_GAMES_DOCKED_KEY: &str = "runningGamesDocked";
pub const RUNNING_GAMES_DOCKED_DEFAULT: bool = false;
pub const RUNNING_GAMES_DOCK_HEIGHT_KEY: &str = "runningGamesDockHeight";
pub const RUNNING_GAMES_DOCK_HEIGHT_DEFAULT: i32 = 420;
pub const CONSOLE_LOG_MAX_LINES_KEY: &str = "consoleLogMaxLines";
pub const CONSOLE_LOG_MAX_LINES_DEFAULT: i32 = 10_000;
pub const CONSOLE_LOG_MAX_LINES_PRESETS: [i32; 6] = [1_000, 5_000, 10_000, 25_000, 50_000, 0];
pub const WINDOW_X_KEY: &str = "windowX";
pub const WINDOW_Y_KEY: &str = "windowY";
pub const WINDOW_WIDTH_KEY: &str = "windowWidth";
pub const WINDOW_HEIGHT_KEY: &str = "windowHeight";
pub const WINDOW_MAXIMIZED_KEY: &str = "windowMaximized";
pub const WINDOW_MAXIMIZED_DEFAULT: bool = false;

// ---- app lifecycle and MCP -----------------------------------------------------------------

pub const LAUNCHER_UPDATE_CHECK_KEY: &str = "launcherUpdateCheck";
pub const LAUNCHER_UPDATE_CHECK_DEFAULT: bool = true;
pub const URI_HANDLER_ENABLED_KEY: &str = "uriHandlerEnabled";
pub const URI_HANDLER_ENABLED_DEFAULT: bool = true;
pub const MCP_SERVER_ENABLED_KEY: &str = "mcpServerEnabled";
pub const MCP_SERVER_ENABLED_DEFAULT: bool = false;
pub const MCP_AUTO_APPROVE_READ_ONLY_KEY: &str = "mcpAutoApproveReadOnly";
pub const MCP_AUTO_APPROVE_READ_ONLY_DEFAULT: bool = true;
pub const MCP_TOOL_POLICIES_KEY: &str = "mcpToolPolicies";
pub const MCP_TOOL_POLICIES_DEFAULT: &str = "";
pub const MCP_ALLOW_STATE_CHANGES_KEY: &str = "mcpAllowStateChanges";
pub const MCP_ALLOW_STATE_CHANGES_DEFAULT: bool = false;
/// The MCP port. Its default comes from the active `AppIdentity` (release/dev, Rust/Java).
pub const MCP_PORT_KEY: &str = "mcpPort";
pub const MCP_TOKEN_KEY: &str = "mcpToken";
pub const MCP_TOKEN_DEFAULT: &str = "";
pub const DEFAULT_ACCOUNT_UUID_KEY: &str = "defaultAccountUuid";
pub const QUICK_START_COMPLETED_KEY: &str = "quickStartCompleted";
pub const BATTERY_THROTTLE_ENABLE_KEY: &str = "batteryThrottleEnable";
pub const BATTERY_THROTTLE_ENABLE_DEFAULT: bool = true;
/// Download cap while on battery below the threshold.
pub const BATTERY_THROTTLE_BYTES_PER_SEC: i64 = 512 * 1024;
/// Battery percentage at or below which downloads are throttled.
pub const BATTERY_THROTTLE_PCT_THRESHOLD: i32 = 30;
pub const LWJGL_ARM_PATCH_ENABLE_KEY: &str = "lwjglArmPatch";
pub const LWJGL_ARM_PATCH_ENABLE_DEFAULT: bool = true;

// ---- RGB -----------------------------------------------------------------------------------

pub const RGB_ENABLE_KEY: &str = "rgbEnable";
pub const RGB_ENABLE_DEFAULT: bool = false;
pub const RGB_BACKEND_KEY: &str = "rgbBackend";
pub const RGB_BACKEND_AUTO: &str = "auto";
pub const RGB_BACKEND_OPENRGB: &str = "openrgb";
pub const RGB_BACKEND_CHROMA: &str = "chroma";
pub const RGB_BACKEND_CHROMA_NATIVE: &str = "chroma-native";
pub const RGB_BACKEND_WINDOWS_DL: &str = "windows-dl";
pub const RGB_BACKEND_CORSAIR: &str = "corsair";
pub const RGB_BACKEND_ASUS_AURA: &str = "asus-aura";
pub const RGB_BACKEND_NONE: &str = "none";
pub const RGB_BACKEND_DEFAULT: &str = RGB_BACKEND_AUTO;
/// The backend values the getter and setter accept (Java parity: `corsair` and `asus-aura` are
/// not in it, so they read back as `auto`).
pub const RGB_BACKENDS_ACCEPTED: [&str; 6] = [
    RGB_BACKEND_AUTO,
    RGB_BACKEND_OPENRGB,
    RGB_BACKEND_CHROMA,
    RGB_BACKEND_CHROMA_NATIVE,
    RGB_BACKEND_WINDOWS_DL,
    RGB_BACKEND_NONE,
];
pub const RGB_USE_PACK_COLORS_KEY: &str = "rgbUsePackColors";
pub const RGB_USE_PACK_COLORS_DEFAULT: bool = true;
pub const RGB_HIGHLIGHT_KEYS_KEY: &str = "rgbHighlightKeys";
pub const RGB_HIGHLIGHT_KEYS_DEFAULT: bool = true;
pub const RGB_ENABLE_OPENRGB_KEY: &str = "rgbEnableOpenRGB";
pub const RGB_ENABLE_OPENRGB_DEFAULT: bool = true;
pub const RGB_ENABLE_CHROMA_NATIVE_KEY: &str = "rgbEnableChromaNative";
pub const RGB_ENABLE_CHROMA_NATIVE_DEFAULT: bool = true;
pub const RGB_ENABLE_CHROMA_REST_KEY: &str = "rgbEnableChromaRest";
pub const RGB_ENABLE_CHROMA_REST_DEFAULT: bool = false;
pub const RGB_ENABLE_WINDOWS_DL_KEY: &str = "rgbEnableWindowsDL";
pub const RGB_ENABLE_WINDOWS_DL_DEFAULT: bool = true;
pub const RGB_ENABLE_CORSAIR_KEY: &str = "rgbEnableCorsair";
pub const RGB_ENABLE_CORSAIR_DEFAULT: bool = true;
pub const RGB_ENABLE_ASUS_AURA_KEY: &str = "rgbEnableAsusAura";
pub const RGB_ENABLE_ASUS_AURA_DEFAULT: bool = true;
pub const RGB_MENU_EFFECT_ENABLE_KEY: &str = "rgbMenuEffectEnable";
pub const RGB_MENU_EFFECT_ENABLE_DEFAULT: bool = true;
pub const RGB_EFFECT_STYLE_KEY: &str = "rgbEffectStyle";
pub const RGB_EFFECT_STYLE_SOLID: &str = "solid";
pub const RGB_EFFECT_STYLE_BREATHE: &str = "breathe";
pub const RGB_EFFECT_STYLE_PULSE: &str = "pulse";
pub const RGB_EFFECT_STYLE_CYCLE: &str = "cycle";
pub const RGB_EFFECT_STYLE_RAINBOW: &str = "rainbow";
pub const RGB_EFFECT_STYLE_DEFAULT: &str = RGB_EFFECT_STYLE_BREATHE;
pub const RGB_EFFECT_STYLES: [&str; 5] = [
    RGB_EFFECT_STYLE_SOLID,
    RGB_EFFECT_STYLE_BREATHE,
    RGB_EFFECT_STYLE_PULSE,
    RGB_EFFECT_STYLE_CYCLE,
    RGB_EFFECT_STYLE_RAINBOW,
];

// ---- proxy ---------------------------------------------------------------------------------

pub const PROXY_ENABLE_KEY: &str = "proxyEnable";
pub const PROXY_ENABLE_DEFAULT: bool = false;
pub const PROXY_HOST_KEY: &str = "proxyHost";
pub const PROXY_HOST_DEFAULT: &str = "";
pub const PROXY_PORT_KEY: &str = "proxyPort";
pub const PROXY_PORT_DEFAULT: i32 = 8080;
pub const PROXY_TYPE_KEY: &str = "proxyType";
pub const PROXY_TYPE_DEFAULT: &str = "HTTP";
pub const PROXY_TYPES: [&str; 2] = ["HTTP", "SOCKS"];

// ---- backups -------------------------------------------------------------------------------

pub const BACKUP_AUTO_KEY: &str = "backupAutoBeforeUpdate";
pub const BACKUP_AUTO_DEFAULT: bool = true;
pub const BACKUP_MAX_COUNT_KEY: &str = "backupMaxCountPerPack";
pub const BACKUP_MAX_COUNT_DEFAULT: i32 = 3;
pub const BACKUP_MAX_AGE_DAYS_KEY: &str = "backupMaxAgeDays";
pub const BACKUP_MAX_AGE_DAYS_DEFAULT: i32 = 14;
pub const BACKUP_INCLUDE_SAVES_KEY: &str = "backupIncludeSaves";
pub const BACKUP_INCLUDE_SAVES_DEFAULT: bool = false;

// ---- per-pack maps and secrets -------------------------------------------------------------

pub const ALWAYS_VERIFY_BY_PACK_KEY: &str = "alwaysVerifyOnLaunchByPack";
pub const ALWAYS_VERIFY_ON_LAUNCH_DEFAULT: bool = false;
pub const DEFAULT_SCAN_FREQUENCY_KEY: &str = "defaultScanFrequency";
pub const SCAN_FREQUENCY_BY_PACK_KEY: &str = "scanFrequencyByPack";
pub const ACCOUNT_OVERRIDE_BY_PACK_KEY: &str = "accountOverrideByPack";
pub const CURSEFORGE_API_KEY_KEY: &str = "curseForgeApiKey";

// ---- Rust-only keys (Java ignores them) ----------------------------------------------------

/// The Microsoft OAuth client ID sign-in uses.
pub const AUTH_CLIENT_ID_KEY: &str = "auth.clientId";
/// The official Minecraft launcher client ID (web view and device code flows).
pub const AUTH_CLIENT_ID_DEFAULT: &str = "00000000402b5328";
/// Opt-in for the Windows account broker (WAM). Only takes effect with a client ID of our own.
pub const AUTH_WAM_KEY: &str = "auth.wam";
pub const AUTH_WAM_DEFAULT: bool = false;
/// Saved gamepad/handheld mode: absent means "auto-detect".
pub const UI_GAMEPAD_MODE_KEY: &str = "ui.gamepadMode";

/// Every key the launcher reads or writes, Java keys first (in `ConfigConstants` order), then
/// the Rust-only ones.
pub const ALL_KEYS: &[&str] = &[
    CONFIG_VERSION_KEY,
    MIN_RAM_KEY,
    MAX_RAM_KEY,
    JVM_ARGS_KEY,
    LAST_MP_KEY,
    THEME_KEY,
    LOCALE_OVERRIDE_KEY,
    LOG_DEBUG_ENABLE_KEY,
    LOG_ENHANCED_ENABLE_KEY,
    LAUNCHER_UPDATE_CHECK_KEY,
    URI_HANDLER_ENABLED_KEY,
    MCP_SERVER_ENABLED_KEY,
    MCP_AUTO_APPROVE_READ_ONLY_KEY,
    MCP_TOOL_POLICIES_KEY,
    MCP_ALLOW_STATE_CHANGES_KEY,
    MCP_PORT_KEY,
    MCP_TOKEN_KEY,
    DEFAULT_ACCOUNT_UUID_KEY,
    QUICK_START_COMPLETED_KEY,
    DISCORD_RPC_ENABLE_KEY,
    DISCORD_INVITES_ENABLE_KEY,
    RESIZE_WINDOWS_ENABLE_KEY,
    INGAME_CONSOLE_ENABLE_KEY,
    RUNNING_GAMES_DOCKED_KEY,
    RUNNING_GAMES_DOCK_HEIGHT_KEY,
    CONSOLE_LOG_MAX_LINES_KEY,
    RGB_ENABLE_KEY,
    RGB_BACKEND_KEY,
    RGB_ENABLE_OPENRGB_KEY,
    RGB_ENABLE_CHROMA_NATIVE_KEY,
    RGB_ENABLE_CHROMA_REST_KEY,
    RGB_ENABLE_WINDOWS_DL_KEY,
    RGB_ENABLE_CORSAIR_KEY,
    RGB_ENABLE_ASUS_AURA_KEY,
    RGB_MENU_EFFECT_ENABLE_KEY,
    RGB_EFFECT_STYLE_KEY,
    RGB_USE_PACK_COLORS_KEY,
    RGB_HIGHLIGHT_KEYS_KEY,
    PROXY_ENABLE_KEY,
    PROXY_HOST_KEY,
    PROXY_PORT_KEY,
    PROXY_TYPE_KEY,
    VANILLA_VERSIONS_INSTALLED_KEY,
    MOD_PACKS_INSTALLED_KEY,
    BACKUP_AUTO_KEY,
    BACKUP_MAX_COUNT_KEY,
    BACKUP_MAX_AGE_DAYS_KEY,
    BACKUP_INCLUDE_SAVES_KEY,
    WINDOW_X_KEY,
    WINDOW_Y_KEY,
    WINDOW_WIDTH_KEY,
    WINDOW_HEIGHT_KEY,
    WINDOW_MAXIMIZED_KEY,
    BATTERY_THROTTLE_ENABLE_KEY,
    LWJGL_ARM_PATCH_ENABLE_KEY,
    ALWAYS_VERIFY_BY_PACK_KEY,
    DEFAULT_SCAN_FREQUENCY_KEY,
    SCAN_FREQUENCY_BY_PACK_KEY,
    ACCOUNT_OVERRIDE_BY_PACK_KEY,
    CURSEFORGE_API_KEY_KEY,
    SHOW_PACK_BACKGROUNDS_KEY,
    WAVY_PROGRESS_KEY,
    REDUCE_MOTION_KEY,
    PACK_COLORS_KEY,
    UI_SCALE_KEY,
    IMAGE_CYCLE_INTERVAL_KEY,
    IMAGE_CYCLE_SHUFFLE_KEY,
    AUTH_CLIENT_ID_KEY,
    AUTH_WAM_KEY,
    UI_GAMEPAD_MODE_KEY,
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_are_unique() {
        let mut seen = std::collections::HashSet::new();
        for k in ALL_KEYS {
            assert!(seen.insert(*k), "duplicate key {k}");
        }
    }

    #[test]
    fn presets_match_java_order_and_args() {
        assert_eq!(JVM_PRESETS[0].args, JVM_ARGS_VALUE_DEFAULT);
        assert_eq!(JVM_PRESETS[3].args, "");
        assert_eq!(
            jvm_preset_for_args(JVM_PRESET_LOW_MEMORY_ARGS).unwrap().id,
            "lowMemory"
        );
        assert!(jvm_preset_for_args("-Xss2m").is_none());
        for p in &JVM_PRESETS {
            assert!(mmcl_i18n::english(p.label_key).is_some(), "{}", p.label_key);
        }
    }
}

/*
 * Copyright (c) 2021 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.micatechnologies.minecraft.launcher.consts;

import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Class containing constants that are used in the launcher configuration package.
 * <p>
 * NOTE: This class should NOT contain display strings that are visible to the end-user. All localizable strings MUST be
 * stored and retrieved using {@link com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager}.
 *
 * @author Mica Technologies
 * @version 1.1
 * @since 2.0
 */
public class ConfigConstants
{

    /**
     * The name of the configuration file when stored on disk.
     *
     * @since 1.0
     */
    public static final String CONFIG_FILE_NAME = File.separator + "configuration.json";

    /**
     * Key for the configuration schema version.
     *
     * @since 3.0
     */
    public static final String CONFIG_VERSION_KEY = "configVersion";

    /**
     * Current configuration schema version. Increment when adding new config keys so that existing configs get
     * migrated with defaults for the new keys.
     *
     * @since 3.0
     */
    public static final int CONFIG_VERSION = 6;

    /**
     * The default value for minimum RAM.
     *
     * @since 1.0
     */
    public static final long MIN_RAM_MEGABYTES_DEFAULT = 512;

    /**
     * The default value for maximum RAM.
     *
     * @since 1.0
     */
    public static final long MAX_RAM_MEGABYTES_DEFAULT = 2048;

    /**
     * The default value for debug logging being enabled.
     *
     * @since 1.0
     */
    public static final boolean LOG_DEBUG_ENABLE_DEFAULT = false;

    /**
     * The default value for Discord RPC being enabled.
     *
     * @since 1.0
     */
    public static final boolean DISCORD_RPC_ENABLE_DEFAULT = true;

    /**
     * The default value for the Discord invites feature being enabled. When enabled, an
     * "In Game" rich presence includes a {@code joinSecret} and party info so Discord friends
     * see a "Join Game" button that auto-installs and launches the same modpack on their end.
     * Only takes effect when Discord RPC itself is enabled.
     *
     * @since 3.4
     */
    public static final boolean DISCORD_INVITES_ENABLE_DEFAULT = true;

    /**
     * The default value for resizable windows being enabled.
     *
     * @since 1.0
     */
    public static final boolean RESIZE_WINDOWS_ENABLE_DEFAULT = true;

    /**
     * The default value for the installed mod packs list.
     *
     * @since 1.0
     */
    public static final List< String > MOD_PACKS_INSTALLED_DEFAULT = new ArrayList<>();

    /**
     * Key for accessing the value of minimum RAM.
     *
     * @since 1.0
     */
    public static final String MIN_RAM_KEY = "minRAM";

    /**
     * Key for accessing the value of maximum RAM.
     *
     * @since 1.0
     */
    public static final String MAX_RAM_KEY = "maxRAM";

    /**
     * Key for accessing the value of custom JVM arguments.
     *
     * @since 1.1
     */
    public static final String JVM_ARGS_KEY = "jvmArgs";

    /**
     * Default value for the custom JVM arguments (Aikar's Performance flags).
     *
     * @since 1.1
     */
    public static final String JVM_ARGS_VALUE_DEFAULT =
            "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 " +
                    "-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch " +
                    "-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=16M " +
                    "-XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 " +
                    "-XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 " +
                    "-XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 " +
                    "-XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 " +
                    "-Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true";

    // region JVM Argument Presets

    /**
     * Preset name for the performance profile (Aikar's flags). This is the default preset.
     *
     * @since 2.0
     */
    public static final String JVM_PRESET_PERFORMANCE = "Performance (Aikar's Flags)";

    /**
     * JVM args for the performance profile — same as the default.
     *
     * @since 2.0
     */
    public static final String JVM_PRESET_PERFORMANCE_ARGS = JVM_ARGS_VALUE_DEFAULT;

    /**
     * Preset name for the low memory profile (lightweight flags for constrained systems).
     *
     * @since 2.0
     */
    public static final String JVM_PRESET_LOW_MEMORY = "Low Memory";

    /**
     * JVM args for the low memory profile — minimal GC tuning.
     *
     * @since 2.0
     */
    public static final String JVM_PRESET_LOW_MEMORY_ARGS =
            "-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=1M";

    /**
     * Preset name for the debug profile.
     *
     * @since 2.0
     */
    public static final String JVM_PRESET_DEBUG = "Debug";

    /**
     * JVM args for the debug profile — GC logging and assertions enabled.
     *
     * @since 2.0
     */
    public static final String JVM_PRESET_DEBUG_ARGS =
            "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 " +
                    "-ea -verbose:gc -Xlog:gc*:file=gc.log:time,uptime,level,tags";

    /**
     * Preset name for no custom JVM flags.
     *
     * @since 2.0
     */
    public static final String JVM_PRESET_NONE = "None";

    /**
     * JVM args for the "none" preset — empty string.
     *
     * @since 2.0
     */
    public static final String JVM_PRESET_NONE_ARGS = "";

    /**
     * Ordered list of preset display names for use in combo boxes.
     *
     * @since 2.0
     */
    public static final String[] JVM_PRESET_NAMES = {
            JVM_PRESET_PERFORMANCE,
            JVM_PRESET_LOW_MEMORY,
            JVM_PRESET_DEBUG,
            JVM_PRESET_NONE
    };

    /**
     * Ordered list of preset JVM args, parallel to {@link #JVM_PRESET_NAMES}.
     *
     * @since 2.0
     */
    public static final String[] JVM_PRESET_ARGS = {
            JVM_PRESET_PERFORMANCE_ARGS,
            JVM_PRESET_LOW_MEMORY_ARGS,
            JVM_PRESET_DEBUG_ARGS,
            JVM_PRESET_NONE_ARGS
    };

    // endregion

    /**
     * Key for accessing the value of the last mod pack.
     *
     * @since 1.1
     */
    public static final String LAST_MP_KEY = "lastModPack";

    /**
     * Key for accessing the value of the theme.
     *
     * @since 1.1
     */
    public static final String THEME_KEY = "theme";

    /**
     * Key for the user-supplied locale override. Stored as a BCP-47 tag
     * (e.g. {@code "fr-FR"}, {@code "es"}) so it round-trips cleanly
     * through {@link java.util.Locale#forLanguageTag} and
     * {@link java.util.Locale#toLanguageTag}. Blank / missing value means
     * "use OS-detected locale" — see {@code LocaleBootstrap.detectOsLocale}.
     *
     * @since 2026.5
     */
    public static final String LOCALE_OVERRIDE_KEY = "localeOverride";

    /**
     * Key for accessing the debug logging enable value.
     *
     * @since 1.0
     */
    public static final String LOG_DEBUG_ENABLE_KEY = "debug";

    /**
     * Key for accessing the enhanced logging enable value.
     *
     * @since 1.0
     */
    public static final String LOG_ENHANCED_ENABLE_KEY = "enhancedLogging";

    /**
     * Key for accessing the launcher update-check enable value. When false, the
     * launcher won't reach out to the GitHub releases API on startup and the
     * update-available navbar icon will stay hidden.
     *
     * @since 3.4
     */
    public static final String LAUNCHER_UPDATE_CHECK_KEY = "launcherUpdateCheck";

    /**
     * Key for the {@code mmcl://} deep-link handler enable flag. When false,
     * {@code mmcl://add}, {@code play}, {@code join}, and {@code open} URIs are all
     * ignored — both at cold start (via argv) and at runtime (via the single-instance
     * IPC forward). Defaults true to match the website-driven install flow, but lets
     * privacy-conscious users or shared-workstation users disable the entire URI
     * attack surface at the source — the install-URL gate in {@code LauncherUriHandler}
     * already filters non-https / untrusted-host links, this turns the whole feature off.
     *
     * @since 2026.2
     */
    public static final String URI_HANDLER_ENABLED_KEY = "uriHandlerEnabled";

    /**
     * Default for {@link #URI_HANDLER_ENABLED_KEY} — true so the website-driven
     * "Install in launcher" links work out of the box.
     *
     * @since 2026.2
     */
    public static final boolean URI_HANDLER_ENABLED_DEFAULT = true;

    /**
     * Key for whether the user has completed (or explicitly skipped) the first-
     * launch quick-start wizard. Defaults to false so existing installs see the
     * wizard once on upgrade, then never again. The wizard also flips this true
     * when the user clicks "Skip" so they don't get badgered.
     *
     * @since 3.4
     */
    public static final String QUICK_START_COMPLETED_KEY = "quickStartCompleted";

    /**
     * Key for accessing the Discord RPC enable value.
     *
     * @since 1.0
     */
    public static final String DISCORD_RPC_ENABLE_KEY = "discordRpc";

    /**
     * Key for accessing the Discord invites enable value.
     *
     * @since 3.4
     */
    public static final String DISCORD_INVITES_ENABLE_KEY = "discordInvites";

    /**
     * Key for accessing the resizable windows enable value.
     *
     * @since 1.0
     */
    public static final String RESIZE_WINDOWS_ENABLE_KEY = "resizableWindows";

    /**
     * Key for accessing the in-game console enable value.
     *
     * @since 3.0
     */
    public static final String INGAME_CONSOLE_ENABLE_KEY = "inGameConsole";

    /**
     * The default value for in-game console being enabled.
     *
     * @since 3.0
     */
    public static final boolean INGAME_CONSOLE_ENABLE_DEFAULT = false;

    /**
     * Maximum number of log lines retained in the in-game console's
     * visible TextArea. When the running log exceeds this count the
     * oldest lines are dropped from the view and a "showing last N
     * lines" notice appears with a link to the full log file. The
     * on-disk log file is never truncated regardless of this value.
     * 0 means unlimited (the launcher won't drop any lines from
     * memory — use with caution on long sessions; large log buffers
     * push JavaFX's TextArea past its happy path).
     *
     * @since 2026.5
     */
    public static final String CONSOLE_LOG_MAX_LINES_KEY = "consoleLogMaxLines";

    /** Default cap on console TextArea lines. Matches the historical
     *  hard-coded MAX_DISPLAY_LINES value so existing users see the
     *  same behavior. */
    public static final int CONSOLE_LOG_MAX_LINES_DEFAULT = 10_000;

    /** Preset values offered in Settings → Game Console → Log buffer
     *  size. Zero is the "Unlimited" sentinel — the dropdown label
     *  shows "Unlimited" and the console never trims. */
    public static final int[] CONSOLE_LOG_MAX_LINES_PRESETS = { 1_000, 5_000, 10_000, 25_000, 50_000, 0 };

    // region RGB Integration

    /** Master enable for the RGB-integration subsystem. When false, the
     *  RgbController stays inert — no backend probes, no worker thread,
     *  no socket / DLL activity. Default off so users opt in deliberately
     *  before the launcher touches any peripheral devices. */
    public static final String RGB_ENABLE_KEY = "rgbEnable";
    public static final boolean RGB_ENABLE_DEFAULT = false;

    /** Selected RGB backend identifier. One of:
     *  <ul>
     *    <li>{@code "auto"} — probe each known backend in priority order
     *        (OpenRGB first, then Razer Chroma) and pick the first one
     *        that reports available.</li>
     *    <li>{@code "openrgb"} — force OpenRGB.</li>
     *    <li>{@code "chroma"} — force Razer Chroma REST.</li>
     *    <li>{@code "none"} — explicit no-op; same effect as disabling
     *        the master toggle but kept distinct for users who want the
     *        Settings tab visible without touching their keyboard.</li>
     *  </ul> */
    public static final String RGB_BACKEND_KEY = "rgbBackend";
    public static final String RGB_BACKEND_DEFAULT = "auto";

    /** Per-backend enable flags. When the master {@link #RGB_ENABLE_KEY}
     *  is on, the controller starts every backend whose enable flag is
     *  true AND whose {@code isAvailable} probe returns true. Lets a
     *  user with mixed-vendor hardware (e.g. Razer fans + a Logitech
     *  Windows-Dynamic-Lighting keyboard) drive all of it at once. */
    public static final String RGB_ENABLE_OPENRGB_KEY        = "rgbEnableOpenRGB";
    public static final boolean RGB_ENABLE_OPENRGB_DEFAULT    = true;

    public static final String RGB_ENABLE_CHROMA_NATIVE_KEY  = "rgbEnableChromaNative";
    public static final boolean RGB_ENABLE_CHROMA_NATIVE_DEFAULT = true;

    public static final String RGB_ENABLE_CHROMA_REST_KEY    = "rgbEnableChromaRest";
    /** REST backend defaults OFF — the native backend supersedes it on
     *  any working Synapse install, and the REST API is effectively
     *  deprecated. Users who specifically want REST can flip this on. */
    public static final boolean RGB_ENABLE_CHROMA_REST_DEFAULT = false;

    public static final String RGB_ENABLE_WINDOWS_DL_KEY     = "rgbEnableWindowsDL";
    public static final boolean RGB_ENABLE_WINDOWS_DL_DEFAULT = true;

    public static final String RGB_ENABLE_CORSAIR_KEY     = "rgbEnableCorsair";
    public static final boolean RGB_ENABLE_CORSAIR_DEFAULT = true;

    public static final String RGB_ENABLE_ASUS_AURA_KEY     = "rgbEnableAsusAura";
    public static final boolean RGB_ENABLE_ASUS_AURA_DEFAULT = true;

    /** Master toggle for the idle "menu" effect — when on, the launcher
     *  paints a slow breathe in the current theme's accent color while
     *  the user is in the menus (between game launches). Off means
     *  devices stay dark in menus, only lighting up during a game. */
    public static final String RGB_MENU_EFFECT_ENABLE_KEY     = "rgbMenuEffectEnable";
    public static final boolean RGB_MENU_EFFECT_ENABLE_DEFAULT = true;

    /** Animation style for the menu / detail-modal effect. Values are
     *  stable identifiers; the Settings dropdown maps them to user-
     *  facing display names. */
    public static final String RGB_EFFECT_STYLE_KEY     = "rgbEffectStyle";
    public static final String RGB_EFFECT_STYLE_SOLID   = "solid";
    public static final String RGB_EFFECT_STYLE_BREATHE = "breathe";
    public static final String RGB_EFFECT_STYLE_PULSE   = "pulse";
    public static final String RGB_EFFECT_STYLE_CYCLE   = "cycle";
    public static final String RGB_EFFECT_STYLE_RAINBOW = "rainbow";
    /** Default = breathe = current behaviour, so existing users see no
     *  change when the option ships. */
    public static final String RGB_EFFECT_STYLE_DEFAULT = RGB_EFFECT_STYLE_BREATHE;

    public static final List< String > RGB_EFFECT_STYLES = Arrays.asList(
            RGB_EFFECT_STYLE_SOLID, RGB_EFFECT_STYLE_BREATHE, RGB_EFFECT_STYLE_PULSE,
            RGB_EFFECT_STYLE_CYCLE, RGB_EFFECT_STYLE_RAINBOW );

    public static final String RGB_BACKEND_AUTO = "auto";
    public static final String RGB_BACKEND_OPENRGB = "openrgb";
    public static final String RGB_BACKEND_CHROMA = "chroma";
    /** Razer Chroma via the native {@code RzChromaSDK64.dll} (the path
     *  Fortnite / Overwatch use). Distinct from RGB_BACKEND_CHROMA which
     *  speaks the deprecated REST API at localhost:54235 — kept as a
     *  separate option so users on older Synapse can fall back to the
     *  REST one if needed. */
    public static final String RGB_BACKEND_CHROMA_NATIVE = "chroma-native";
    /** Windows 11 Dynamic Lighting via the LampArray WinRT API. Windows-
     *  only, narrow device coverage today (specific Logitech / HP /
     *  Lenovo / ASUS hardware that implements the HID Lighting And
     *  Illumination usage page). Razer hardware doesn't participate yet. */
    public static final String RGB_BACKEND_WINDOWS_DL = "windows-dl";
    /** Corsair iCUE via the v3 SDK ({@code iCUESDK.x64_2017.dll} /
     *  {@code iCUESDK.x64_2019.dll}). Requires iCUE installed and its
     *  service running. */
    public static final String RGB_BACKEND_CORSAIR = "corsair";
    /** ASUS Aura via the legacy {@code AURA_SDK.dll}. Covers
     *  motherboard / GPU / RAM / Claymore-family keyboards. Users on
     *  newer Armoury Crate hardware should keep OpenRGB in the rotation
     *  for broader ASUS coverage. */
    public static final String RGB_BACKEND_ASUS_AURA = "asus-aura";
    public static final String RGB_BACKEND_NONE = "none";

    /** When true, in-game effects paint a gradient using the running
     *  modpack's logo dominant colors. When false, effects use the
     *  launcher theme's accent palette. Default true — the modpack tie-in
     *  is the headline feature. */
    public static final String RGB_USE_PACK_COLORS_KEY = "rgbUsePackColors";
    public static final boolean RGB_USE_PACK_COLORS_DEFAULT = true;

    /** When true, the in-game effect highlights WASD / E / Space / Shift
     *  in a contrasting accent over the pack-color background. */
    public static final String RGB_HIGHLIGHT_KEYS_KEY = "rgbHighlightKeys";
    public static final boolean RGB_HIGHLIGHT_KEYS_DEFAULT = true;

    // region Proxy Configuration

    /**
     * Key for the proxy enable flag.
     *
     * @since 3.0
     */
    public static final String PROXY_ENABLE_KEY = "proxyEnable";

    /**
     * Key for the proxy host.
     *
     * @since 3.0
     */
    public static final String PROXY_HOST_KEY = "proxyHost";

    /**
     * Key for the proxy port.
     *
     * @since 3.0
     */
    public static final String PROXY_PORT_KEY = "proxyPort";

    /**
     * Key for the proxy type (HTTP or SOCKS).
     *
     * @since 3.0
     */
    public static final String PROXY_TYPE_KEY = "proxyType";

    /**
     * Default proxy enable value.
     */
    public static final boolean PROXY_ENABLE_DEFAULT = false;

    /**
     * Default proxy host value.
     */
    public static final String PROXY_HOST_DEFAULT = "";

    /**
     * Default proxy port value.
     */
    public static final int PROXY_PORT_DEFAULT = 8080;

    /**
     * Default proxy type value.
     */
    public static final String PROXY_TYPE_DEFAULT = "HTTP";

    /**
     * Allowed proxy type values.
     */
    public static final String[] PROXY_TYPES = { "HTTP", "SOCKS" };

    // endregion

    /**
     * Key for accessing the list of installed vanilla Minecraft versions.
     *
     * @since 3.0
     */
    public static final String VANILLA_VERSIONS_INSTALLED_KEY = "vanillaVersions";

    /**
     * The default value for the installed vanilla versions list.
     *
     * @since 3.0
     */
    public static final List< String > VANILLA_VERSIONS_INSTALLED_DEFAULT = new ArrayList<>();

    /**
     * Key for accessing the list of installed mod packs.
     *
     * @since 1.0
     */
    public static final String MOD_PACKS_INSTALLED_KEY = "modpacks";

    /**
     * Type token for installed mod packs list.
     *
     * @since 1.0
     */
    public static final Type modPacksListType = new TypeToken< List< String > >()
    {
    }.getType();

    // -- Backup-before-update policy ----------------------------------------

    /** Key for the auto-backup-before-update toggle. */
    public static final String BACKUP_AUTO_KEY = "backupAutoBeforeUpdate";
    /** Default for the auto-backup toggle — on, so users get a safety net by default. */
    public static final boolean BACKUP_AUTO_DEFAULT = true;

    /** Key for the per-pack backup-retention count. */
    public static final String BACKUP_MAX_COUNT_KEY = "backupMaxCountPerPack";
    /** Default backup count — three keeps disk usage bounded (~1-2 GB
     *  per pack worst-case) while preserving enough history to roll back
     *  across a few updates. Set 0 to keep no count limit. */
    public static final int BACKUP_MAX_COUNT_DEFAULT = 3;

    /** Key for the backup-age cap in days. */
    public static final String BACKUP_MAX_AGE_DAYS_KEY = "backupMaxAgeDays";
    /** Default backup age cap in days — two weeks balances "I'm still
     *  using this old save" against "this is years-stale clutter." Set 0
     *  to disable the age cap. */
    public static final int BACKUP_MAX_AGE_DAYS_DEFAULT = 14;

    /** Key for whether saves/ should be included in the backup zip. */
    public static final String BACKUP_INCLUDE_SAVES_KEY = "backupIncludeSaves";
    /** Default for include-saves — opt-in (false) because save folders can
     *  be huge (several GB on heavily-explored worlds) and the user should
     *  consciously choose to spend disk on save snapshots. config/ alone
     *  covers most "the update broke my mod settings" recovery cases. */
    public static final boolean BACKUP_INCLUDE_SAVES_DEFAULT = false;

    /**
     * Key for accessing the persisted launcher window X coordinate.
     *
     * @since 3.0
     */
    public static final String WINDOW_X_KEY = "windowX";

    /**
     * Key for accessing the persisted launcher window Y coordinate.
     *
     * @since 3.0
     */
    public static final String WINDOW_Y_KEY = "windowY";

    /**
     * Key for accessing the persisted launcher window width.
     *
     * @since 3.0
     */
    public static final String WINDOW_WIDTH_KEY = "windowWidth";

    /**
     * Key for accessing the persisted launcher window height.
     *
     * @since 3.0
     */
    public static final String WINDOW_HEIGHT_KEY = "windowHeight";

    /**
     * Key for accessing the persisted launcher window maximized state.
     *
     * @since 3.0
     */
    public static final String WINDOW_MAXIMIZED_KEY = "windowMaximized";

    /**
     * Default value for the launcher window maximized state.
     *
     * @since 3.0
     */
    public static final boolean WINDOW_MAXIMIZED_DEFAULT = false;

    /**
     * Key for accessing the "throttle downloads while on battery" toggle.
     *
     * @since 3.1
     */
    public static final String BATTERY_THROTTLE_ENABLE_KEY = "batteryThrottleEnable";

    /**
     * Default value for "throttle downloads while on battery". On by default — laptops are the
     * common case and a half-full battery dropped during a fresh modpack install is a poor
     * first impression. Desktops have no battery, so this never engages there.
     *
     * @since 3.1
     */
    public static final boolean BATTERY_THROTTLE_ENABLE_DEFAULT = true;

    /**
     * Per-download-stream cap (in bytes per second) when battery throttling is engaged. With the
     * library/asset/manifest pipeline running ~4-8 parallel downloads during a modpack install,
     * 512 KiB/s × 4-8 = 2-4 MiB/s aggregate — fast enough that an install in a coffee-shop trip
     * still completes, slow enough that the user's laptop fan doesn't spin up.
     *
     * @since 3.1
     */
    public static final long BATTERY_THROTTLE_BYTES_PER_SEC = 512L * 1024L;

    /**
     * Battery-level (percent, 0-100) below which the saver actually engages. Above this, the
     * launcher behaves as if on AC even if the OS reports "on battery" — at high charge there's
     * plenty of juice for an install and strangling downloads is just an annoyance the user
     * didn't ask for. Picked low enough that there's still meaningful runtime left when saver
     * kicks in, high enough that fresh installs after a couple hours unplugged still benefit.
     *
     * @since 3.1
     */
    public static final int BATTERY_THROTTLE_PCT_THRESHOLD = 30;

    public static final String THEME_DARK          = "Dark";
    public static final String THEME_LIGHT         = "Light";
    public static final String THEME_AUTOMATIC     = "Automatic";
    public static final String THEME_BLUE_GRAY     = "Blue+gray";
    public static final String THEME_ORANGE_PURPLE = "Orange+purple";
    public static final String THEME_CREEPER       = "Creeper";
    /** Lets the OS-native window material (Windows Mica) show through the app
     *  background. Falls back to a translucent dark look on macOS/Linux until
     *  the corresponding native bridges are implemented. */
    public static final String THEME_NATIVE        = "Native (Mica)";

    /** Default theme for fresh installs. The Mica/native look is the brand
     *  showpiece — first-time users see the launcher as the design team
     *  intends rather than the more generic Automatic light/dark variant. */
    public static final String THEME_DEFAULT       = THEME_NATIVE;

    public static final List< String > ALLOWED_THEMES = Arrays.asList( THEME_AUTOMATIC, THEME_DARK, THEME_LIGHT,
                                                                       THEME_BLUE_GRAY, THEME_ORANGE_PURPLE,
                                                                       THEME_CREEPER, THEME_NATIVE );

    // region LWJGL ARM64 Patching

    /**
     * Key for the LWJGL ARM64 patching enable flag. When enabled on ARM64 macOS/Linux, the launcher replaces
     * LWJGL2 x86_64 native libraries with ARM64-compatible builds for Minecraft 1.12.2 and below.
     *
     * @since 3.0
     */
    public static final String LWJGL_ARM_PATCH_ENABLE_KEY = "lwjglArmPatch";

    /**
     * Default value for LWJGL ARM64 patching. Enabled by default so ARM64 users get working older MC versions
     * out of the box.
     *
     * @since 3.0
     */
    public static final boolean LWJGL_ARM_PATCH_ENABLE_DEFAULT = true;

    // endregion

    // region Experimental Windows custom title bar (frameless Window Controls Overlay)

    /**
     * Key for the experimental Windows custom-chrome (frameless title bar) flag. When enabled
     * on Windows, the launcher creates an undecorated window and draws its own title bar with
     * content extending to the top edge, keeping native-feeling min/max/close controls top-right.
     *
     * @since 3.5
     */
    public static final String WINDOWS_CUSTOM_CHROME_ENABLE_KEY = "windowsCustomChrome";

    /**
     * Default value for the experimental Windows custom title bar. OFF by default — it is an
     * experimental, Windows-only feature that requires a restart and degrades to the standard
     * title bar if the native subclass can't be installed.
     *
     * @since 3.5
     */
    public static final boolean WINDOWS_CUSTOM_CHROME_ENABLE_DEFAULT = false;

    // endregion

    // region Verify-mode controls (3.3 fast-path skip on unchanged packs)

    /**
     * Per-pack-URL map of {@code packUrl → boolean} for the "Always verify
     * game files on launch" opt-out toggle. Packs whose URL maps to
     * {@code true} skip the fast-path verify shortcut and always re-hash
     * every file on launch. Stored as a JsonObject so URLs with weird chars
     * round-trip cleanly without needing key encoding.
     *
     * @since 2026.3
     */
    public static final String ALWAYS_VERIFY_BY_PACK_KEY = "alwaysVerifyOnLaunchByPack";

    /**
     * Default value for any pack not present in
     * {@link #ALWAYS_VERIFY_BY_PACK_KEY}. False means "fast-path eligible
     * by default" — the user explicitly chose this default during 3.3's
     * design phase since the fast-path is the whole point of the feature.
     *
     * @since 2026.3
     */
    public static final boolean ALWAYS_VERIFY_ON_LAUNCH_DEFAULT = false;

    // endregion

    // region Scan frequency controls (3.4 user-tunable security-scan cadence)

    /**
     * Key for the launcher-wide default security-scan frequency. Value is a
     * {@code ScanFrequency.name()} string ("EVERY_TIME", "DAILY",
     * "ON_CHANGES_ONLY", "DISABLED"). Stored as the enum name rather than
     * the display label so renames of user-facing copy don't break configs.
     *
     * @since 2026.3
     */
    public static final String DEFAULT_SCAN_FREQUENCY_KEY = "defaultScanFrequency";

    /**
     * Per-pack-URL map of {@code packUrl → ScanFrequency.name()} for pack-
     * specific overrides of the global default. Absent / null entries fall
     * back to {@link #DEFAULT_SCAN_FREQUENCY_KEY}. Stored as a JsonObject
     * so URLs with weird chars round-trip cleanly without needing key
     * encoding (parallel to {@link #ALWAYS_VERIFY_BY_PACK_KEY}).
     *
     * @since 2026.3
     */
    public static final String SCAN_FREQUENCY_BY_PACK_KEY = "scanFrequencyByPack";

    // endregion

    // region CurseForge API key

    /**
     * JSON key for the user-supplied CurseForge Core API key, stored in the
     * launcher config in the Base64 envelope produced by
     * {@link com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher#encrypt}
     * — machine-bound AES-256-GCM, same primitive that protects the cached
     * Minecraft auth tokens. The key never sits on disk in plaintext and
     * isn't trivially exfiltrated by a stolen disk image / mis-synced cloud
     * backup.
     *
     * <p>Used by the CurseForge import path (Add by URL → CurseForge modpack
     * detected → fetch project metadata). Without a key, the launcher can
     * detect a CurseForge URL but can't fetch metadata; with one, the same
     * preview-then-confirm flow we already do for Modrinth becomes available
     * for CurseForge too.</p>
     *
     * @since 2026.3
     */
    public static final String CURSEFORGE_API_KEY_KEY = "curseForgeApiKey";

    // endregion

    // region Card background-image display toggle

    /**
     * JSON key controlling whether modpack / version cards on the main menu
     * and Library view overlay the pack's real background image on top of
     * the procedural gradient. When false, only the gradient is shown
     * regardless of whether the image is cached. Some users prefer the
     * cleaner, more uniform look of gradient-only cards; others want the
     * pack imagery for instant visual recognition. Defaults to true (image
     * overlay enabled).
     *
     * @since 2026.3
     */
    public static final String SHOW_PACK_BACKGROUNDS_KEY = "showPackBackgrounds";

    /**
     * Default for {@link #SHOW_PACK_BACKGROUNDS_KEY}. True so fresh installs
     * see the imagery the modpack authors curated; users who prefer the
     * gradient-only look opt out via Settings → Appearance.
     *
     * @since 2026.3
     */
    public static final boolean SHOW_PACK_BACKGROUNDS_DEFAULT = true;

    // endregion
}

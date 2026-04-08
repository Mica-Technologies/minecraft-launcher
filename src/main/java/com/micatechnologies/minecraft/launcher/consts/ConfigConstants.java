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
    public static final int CONFIG_VERSION = 2;

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
     * The default value for resizable windows being enabled.
     *
     * @since 1.0
     */
    public static final boolean RESIZE_WINDOWS_ENABLE_DEFAULT = false;

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
     * Key for accessing the Discord RPC enable value.
     *
     * @since 1.0
     */
    public static final String DISCORD_RPC_ENABLE_KEY = "discordRpc";

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

    public static final String THEME_DARK          = "Dark";
    public static final String THEME_LIGHT         = "Light";
    public static final String THEME_AUTOMATIC     = "Automatic";
    public static final String THEME_BLUE_GRAY     = "Blue+gray";
    public static final String THEME_ORANGE_PURPLE = "Orange+purple";

    public static final List< String > ALLOWED_THEMES = Arrays.asList( THEME_AUTOMATIC, THEME_DARK, THEME_LIGHT,
                                                                       THEME_BLUE_GRAY, THEME_ORANGE_PURPLE );
}

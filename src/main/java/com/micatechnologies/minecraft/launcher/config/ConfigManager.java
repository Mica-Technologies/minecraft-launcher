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

package com.micatechnologies.minecraft.launcher.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;
import com.micatechnologies.minecraft.launcher.files.Logger;

import java.io.File;
import java.util.List;

/**
 * Class that manages the configuration and persistence of the configuration for the application.
 *
 * @author Mica Technologies
 * @version 3.0
 * @since 1.0
 */
public class ConfigManager
{
    /**
     * Configuration object. Must be loaded from disk and saved to disk on change.
     *
     * @see #readConfigurationFromDisk()
     * @see #writeConfigurationToDisk()
     * @since 3.0
     */
    private static JsonObject configObject = null;

    /**
     * Gets the configured minimum RAM for the Minecraft game.
     *
     * @return Minecraft starting/min RAM
     *
     * @since 1.0
     */
    public synchronized static long getMinRam() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Add default if missing
        if ( !configObject.has( ConfigConstants.MIN_RAM_KEY ) ) {
            configObject.addProperty( ConfigConstants.MIN_RAM_KEY, ConfigConstants.MIN_RAM_MEGABYTES_DEFAULT );
        }

        // Get and return value of min RAM
        return configObject.get( ConfigConstants.MIN_RAM_KEY ).getAsLong();
    }

    /**
     * Sets the configured minimum RAM for the Minecraft game.
     *
     * @param minRam Minecraft starting/min RAM
     *
     * @since 1.0
     */
    public synchronized static void setMinRam( long minRam ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of min RAM
        configObject.addProperty( ConfigConstants.MIN_RAM_KEY, minRam );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Gets the configured maximum RAM for the Minecraft game.
     *
     * @return Minecraft maximum RAM
     *
     * @since 1.0
     */
    public synchronized static long getMaxRam() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Add default if missing
        if ( !configObject.has( ConfigConstants.MAX_RAM_KEY ) ) {
            configObject.addProperty( ConfigConstants.MAX_RAM_KEY, ConfigConstants.MAX_RAM_MEGABYTES_DEFAULT );
        }

        // Get and return value of max RAM
        return configObject.get( ConfigConstants.MAX_RAM_KEY ).getAsLong();
    }

    /**
     * Gets the configured maximum RAM (in GB) for the Minecraft game.
     *
     * @return Minecraft maximum RAM (in GB)
     *
     * @since 1.0
     */
    public synchronized static double getMaxRamInGb() {
        return getMaxRam() / 1024.0;
    }

    /**
     * Sets the configured maximum RAM for the Minecraft game.
     *
     * @param maxRam Minecraft maximum RAM
     *
     * @since 1.0
     */
    public synchronized static void setMaxRam( long maxRam ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of max RAM
        configObject.addProperty( ConfigConstants.MAX_RAM_KEY, maxRam );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Gets the configured state of debug logging for the application.
     *
     * @return true if debug logging enabled, otherwise false. Always returns true if development mode is enabled.
     *
     * @since 2.0
     */
    public synchronized static boolean getDebugLogging() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Add default if missing
        if ( !configObject.has( ConfigConstants.LOG_DEBUG_ENABLE_KEY ) ) {
            configObject.addProperty( ConfigConstants.LOG_DEBUG_ENABLE_KEY,
                                      ConfigConstants.LOG_DEBUG_ENABLE_DEFAULT );
        }

        // Get and return value of debug logging (or true if dev mode)
        return LauncherConstants.LAUNCHER_IS_DEV ||
                configObject.get( ConfigConstants.LOG_DEBUG_ENABLE_KEY ).getAsBoolean();
    }

    /**
     * Sets the configured state of debug logging for the application.
     *
     * @param debugLogging true to enable application debug logging, otherwise false
     *
     * @since 2.0
     */
    public synchronized static void setDebugLogging( boolean debugLogging ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of debug logging
        configObject.addProperty( ConfigConstants.LOG_DEBUG_ENABLE_KEY, debugLogging );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Gets the configured state of Discord RPC for the application.
     *
     * @return true if Discord RPC enabled, otherwise false. Always returns false if development mode is enabled.
     *
     * @since 2.0
     */
    public synchronized static boolean getDiscordRpcEnable() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        if ( !configObject.has( ConfigConstants.DISCORD_RPC_ENABLE_KEY ) ) {
            configObject.addProperty( ConfigConstants.DISCORD_RPC_ENABLE_KEY,
                                      ConfigConstants.DISCORD_RPC_ENABLE_DEFAULT );
        }

        // Honor the user's setting in all environments — historically dev builds
        // hard-disabled RPC here to keep dev sessions out of the prod Discord app,
        // but the matching setDisable() on the settings checkbox is gone now too
        // (see MCLauncherSettingsGui), so contributors can verify rich-presence
        // and the new invite plumbing end-to-end without a release build.
        return configObject.get( ConfigConstants.DISCORD_RPC_ENABLE_KEY ).getAsBoolean();
    }

    /**
     * Sets the configured state of Discord RPC for the application.
     *
     * @param discordRpcEnable true to enable Discord RPC, otherwise false
     *
     * @since 2.0
     */
    public synchronized static void setDiscordRpcEnable( boolean discordRpcEnable ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of debug logging
        configObject.addProperty( ConfigConstants.DISCORD_RPC_ENABLE_KEY, discordRpcEnable );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Returns whether Discord rich-presence "Join Game" invites are enabled. When true (and
     * Discord RPC itself is enabled), an in-game presence carries a {@code joinSecret} so
     * friends see a "Join Game" button that auto-installs and launches the same modpack on
     * their end.
     *
     * @return true if Discord invites are enabled, otherwise false
     *
     * @since 3.4
     */
    public synchronized static boolean getDiscordInvitesEnable() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.DISCORD_INVITES_ENABLE_KEY ) ) {
            configObject.addProperty( ConfigConstants.DISCORD_INVITES_ENABLE_KEY,
                                      ConfigConstants.DISCORD_INVITES_ENABLE_DEFAULT );
        }
        return configObject.get( ConfigConstants.DISCORD_INVITES_ENABLE_KEY ).getAsBoolean();
    }

    /**
     * Sets whether Discord rich-presence "Join Game" invites are enabled.
     *
     * @param discordInvitesEnable true to enable Discord invites, otherwise false
     *
     * @since 3.4
     */
    public synchronized static void setDiscordInvitesEnable( boolean discordInvitesEnable ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.DISCORD_INVITES_ENABLE_KEY, discordInvitesEnable );
        writeConfigurationToDisk();
    }

    /**
     * Gets the configured state of resizable windows for the application.
     *
     * @return true if resizable windows enabled, otherwise false
     *
     * @since 2.0
     */
    public synchronized static boolean getResizableWindows() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Add default if missing
        if ( !configObject.has( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY ) ) {
            configObject.addProperty( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY,
                                      ConfigConstants.RESIZE_WINDOWS_ENABLE_DEFAULT );
        }

        // Get and return value of resizable windows
        return configObject.get( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY ).getAsBoolean();
    }

    /**
     * Sets the configured state of resizable windows for the application.
     *
     * @param resizableWindows true to enable resizable windows, otherwise false
     *
     * @since 2.0
     */
    public synchronized static void setResizableWindows( boolean resizableWindows ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of resizable windows
        configObject.addProperty( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY, resizableWindows );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Gets whether downloads should throttle while running on battery power. Engaged only when the
     * host actually has a battery (laptops); on desktops {@code PowerStateManager.isOnBattery()}
     * always reports false, so the flag is dormant regardless of its value.
     *
     * @return true if battery throttling is enabled
     *
     * @since 3.1
     */
    public synchronized static boolean getBatteryThrottleEnable() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.BATTERY_THROTTLE_ENABLE_KEY ) ) {
            configObject.addProperty( ConfigConstants.BATTERY_THROTTLE_ENABLE_KEY,
                                      ConfigConstants.BATTERY_THROTTLE_ENABLE_DEFAULT );
        }
        return configObject.get( ConfigConstants.BATTERY_THROTTLE_ENABLE_KEY ).getAsBoolean();
    }

    /**
     * Sets whether downloads should throttle while running on battery power.
     *
     * @param enable true to throttle downloads on battery
     *
     * @since 3.1
     */
    public synchronized static void setBatteryThrottleEnable( boolean enable ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.BATTERY_THROTTLE_ENABLE_KEY, enable );
        writeConfigurationToDisk();
    }

    /**
     * Gets the configured state of enhanced logging for the application.
     *
     * @return true if enhanced logging enabled, otherwise false
     *
     * @since 2.0
     */
    public synchronized static boolean getEnhancedLogging() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Check for presence of field, and create default if does not exist
        if ( !configObject.has( ConfigConstants.LOG_ENHANCED_ENABLE_KEY ) ) {
            // Add property with default value
            configObject.addProperty( ConfigConstants.LOG_ENHANCED_ENABLE_KEY, true );

            // Save configuration to disk
            writeConfigurationToDisk();
        }

        // Get and return value
        return configObject.get( ConfigConstants.LOG_ENHANCED_ENABLE_KEY ).getAsBoolean();
    }

    /**
     * Gets whether the launcher should check for its own updates on startup.
     * Defaults to true — users who don't want the launcher reaching out to the
     * GitHub releases API (offline-first installs, restricted networks, etc.)
     * can flip this off in Settings.
     *
     * @return true if the update check is enabled
     *
     * @since 3.4
     */
    public synchronized static boolean getLauncherUpdateCheckEnabled() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.LAUNCHER_UPDATE_CHECK_KEY ) ) {
            configObject.addProperty( ConfigConstants.LAUNCHER_UPDATE_CHECK_KEY, true );
            writeConfigurationToDisk();
        }
        return configObject.get( ConfigConstants.LAUNCHER_UPDATE_CHECK_KEY ).getAsBoolean();
    }

    /**
     * Sets whether the launcher should check for its own updates on startup.
     *
     * @param enabled true to enable the update check
     *
     * @since 3.4
     */
    public synchronized static void setLauncherUpdateCheckEnabled( boolean enabled ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.LAUNCHER_UPDATE_CHECK_KEY, enabled );
        writeConfigurationToDisk();
    }

    /**
     * Returns whether the {@code mmcl://} URI handler is enabled. Defaults true.
     * When false, neither cold-start argv-delivered URIs nor runtime IPC-delivered
     * URIs are dispatched — the entire deep-link surface is disabled. Useful as a
     * kill switch on shared-workstation installs or for users who never use
     * website-driven modpack installs.
     *
     * @since 2026.2
     */
    public synchronized static boolean getUriHandlerEnabled() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.URI_HANDLER_ENABLED_KEY ) ) {
            configObject.addProperty( ConfigConstants.URI_HANDLER_ENABLED_KEY,
                                      ConfigConstants.URI_HANDLER_ENABLED_DEFAULT );
            writeConfigurationToDisk();
        }
        return configObject.get( ConfigConstants.URI_HANDLER_ENABLED_KEY ).getAsBoolean();
    }

    /**
     * Toggles {@link #getUriHandlerEnabled()} and persists the change.
     *
     * @since 2026.2
     */
    public synchronized static void setUriHandlerEnabled( boolean enabled ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.URI_HANDLER_ENABLED_KEY, enabled );
        writeConfigurationToDisk();
    }

    /**
     * Returns true once the user has completed (or skipped) the first-launch
     * quick-start wizard. Defaults to false so the wizard fires once for existing
     * installs that upgrade to this version.
     *
     * @since 3.4
     */
    public synchronized static boolean getQuickStartCompleted() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.QUICK_START_COMPLETED_KEY ) ) {
            configObject.addProperty( ConfigConstants.QUICK_START_COMPLETED_KEY, false );
            writeConfigurationToDisk();
        }
        return configObject.get( ConfigConstants.QUICK_START_COMPLETED_KEY ).getAsBoolean();
    }

    /**
     * Marks the first-launch quick-start wizard as completed (or skipped).
     * Once true, the wizard won't show again on subsequent launches.
     *
     * @since 3.4
     */
    public synchronized static void setQuickStartCompleted( boolean completed ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.QUICK_START_COMPLETED_KEY, completed );
        writeConfigurationToDisk();
    }

    /**
     * Sets the configured state of enhanced logging for the application.
     *
     * @param enhancedLogging true to enable enhanced logging, otherwise false
     *
     * @since 2.0
     */
    public synchronized static void setEnhancedLogging( boolean enhancedLogging ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value
        configObject.addProperty( ConfigConstants.LOG_ENHANCED_ENABLE_KEY, enhancedLogging );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Gets the configured custom JVM launch arguments for the application.
     *
     * @return custom JVM launch arguments
     *
     * @since 3.0
     */
    public synchronized static String getCustomJvmArgs() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Check for presence of field, and create default if does not exist
        if ( !configObject.has( ConfigConstants.JVM_ARGS_KEY ) ) {
            // Add property with default value
            configObject.addProperty( ConfigConstants.JVM_ARGS_KEY, ConfigConstants.JVM_ARGS_VALUE_DEFAULT );

            // Save configuration to disk
            writeConfigurationToDisk();
        }

        // Get and return value of custom JVM args
        return configObject.get( ConfigConstants.JVM_ARGS_KEY ).getAsString();
    }

    /**
     * Sets the custom JVM launch arguments. Validates the string against argument-injection
     * patterns — these args are appended into the launch command
     * line that gets split via {@code ProcessUtilities.splitCommandLine} and is also subject
     * to {@code ${placeholder}} substitution downstream. Embedded newlines, NULs, control
     * characters, or {@code ${...}} sequences are rejected outright so that:
     * <ul>
     *   <li>A future code path that copies JVM args from an attacker-controlled source
     *       (modpack JSON field, URI parameter, etc.) cannot inject extra arguments
     *       through this choke point.</li>
     *   <li>{@code ${auth_access_token}} or similar placeholders cannot be smuggled into
     *       custom args to leak the live token onto the command line in extra positions.</li>
     * </ul>
     *
     * <p>Pre-existing usages (settings UI presets, user-typed args) stay valid because none
     * of them contain the rejected metacharacters.
     *
     * @param jvmArgs the JVM arguments string
     *
     * @throws IllegalArgumentException if the string contains rejected metacharacters
     * @since 3.0
     */
    public synchronized static void setCustomJvmArgs( String jvmArgs ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        String sanitized = validateCustomJvmArgs( jvmArgs );
        configObject.addProperty( ConfigConstants.JVM_ARGS_KEY, sanitized );
        writeConfigurationToDisk();
    }

    /** Validates custom JVM args input against the rejection list. Returns the input
     *  unchanged on success; throws on first offending character.
     *
     *  @see #setCustomJvmArgs(String) for the full rationale. */
    private static String validateCustomJvmArgs( String jvmArgs ) {
        if ( jvmArgs == null ) {
            return "";
        }
        for ( int i = 0; i < jvmArgs.length(); i++ ) {
            char c = jvmArgs.charAt( i );
            // Control chars and DEL (0x7F). Newlines / TAB included so a multi-line paste
            // can't smuggle command-line args separated by a line break — splitCommandLine
            // splits on Character.isWhitespace which includes them.
            if ( c < 0x20 || c == 0x7F ) {
                throw new IllegalArgumentException(
                        "Custom JVM args contain a control character at position " + i );
            }
        }
        // Reject ${...} placeholder syntax — the launch pipeline runs templating after
        // appending custom args, so a literal "${auth_access_token}" here would expand.
        if ( jvmArgs.indexOf( "${" ) >= 0 ) {
            throw new IllegalArgumentException(
                    "Custom JVM args may not contain '${...}' placeholder syntax." );
        }
        return jvmArgs;
    }

    /**
     * Gets the last mod pack selected.
     *
     * @return last mod pack selected
     *
     * @since 3.0
     */
    public synchronized static String getLastModPackSelected() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Check for presence of field, and create default if does not exist
        if ( !configObject.has( ConfigConstants.LAST_MP_KEY ) ) {
            // Add property with default value
            configObject.addProperty( ConfigConstants.LAST_MP_KEY, "" );

            // Save configuration to disk
            writeConfigurationToDisk();
        }

        // Get and return value of custom JVM args
        return configObject.get( ConfigConstants.LAST_MP_KEY ).getAsString();
    }

    /**
     * Sets the last mod pack selected.
     *
     * @param lastModPackSelected last mod pack selected
     *
     * @since 2.0
     */
    public synchronized static void setLastModPackSelected( String lastModPackSelected ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of last mod pack selected
        configObject.addProperty( ConfigConstants.LAST_MP_KEY, lastModPackSelected );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Gets the theme.
     *
     * @return theme
     *
     * @since 3.0
     */
    public synchronized static String getTheme() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Check for presence of field, and create default if does not exist
        if ( !configObject.has( ConfigConstants.THEME_KEY ) ) {
            // Add property with default value
            configObject.addProperty( ConfigConstants.THEME_KEY, ConfigConstants.THEME_DEFAULT );

            // Save configuration to disk
            writeConfigurationToDisk();
        }

        // Get and return value of custom JVM args
        return configObject.get( ConfigConstants.THEME_KEY ).getAsString();
    }

    /**
     * Sets the theme.
     *
     * @param theme theme
     *
     * @since 2.0
     */
    public synchronized static void setTheme( String theme ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of last mod pack selected
        configObject.addProperty( ConfigConstants.THEME_KEY, theme );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Gets the configured list of installed mod packs by their manifest URLs.
     *
     * @return list of installed mod packs' manifest URLs
     *
     * @since 1.0
     */
    public synchronized static List< String > getInstalledModPacks() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Add default if missing
        if ( !configObject.has( ConfigConstants.MOD_PACKS_INSTALLED_KEY ) ) {
            JsonArray defaultArray = ( JsonArray ) JSONUtilities.getGson().toJsonTree(
                    ConfigConstants.MOD_PACKS_INSTALLED_DEFAULT, ConfigConstants.modPacksListType );
            configObject.add( ConfigConstants.MOD_PACKS_INSTALLED_KEY, defaultArray );
        }

        // Get and return value of installed mod packs
        JsonArray installedModPacksArray = configObject.get( ConfigConstants.MOD_PACKS_INSTALLED_KEY ).getAsJsonArray();
        return JSONUtilities.getGson().fromJson( installedModPacksArray, ConfigConstants.modPacksListType );
    }

    /**
     * Sets the configured list of installed mod packs' manifest URLs.
     *
     * @param installedModPacks list of installed mod packs' manifest URLs
     *
     * @since 1.0
     */
    public synchronized static void setInstalledModPacks( List< String > installedModPacks ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of min RAM
        JsonArray installedModPacksArray = ( JsonArray ) JSONUtilities.getGson().toJsonTree( installedModPacks,
                                                                                ConfigConstants.modPacksListType );
        configObject.add( ConfigConstants.MOD_PACKS_INSTALLED_KEY, installedModPacksArray );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Gets the configured in-game console enabled value.
     *
     * @return true if in-game console is enabled
     *
     * @since 3.0
     */
    public synchronized static boolean getInGameConsoleEnable() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.INGAME_CONSOLE_ENABLE_KEY ) ) {
            configObject.addProperty( ConfigConstants.INGAME_CONSOLE_ENABLE_KEY,
                                       ConfigConstants.INGAME_CONSOLE_ENABLE_DEFAULT );
        }
        return configObject.get( ConfigConstants.INGAME_CONSOLE_ENABLE_KEY ).getAsBoolean();
    }

    /**
     * Sets the configured in-game console enabled value.
     *
     * @param enable true to enable in-game console
     *
     * @since 3.0
     */
    public synchronized static void setInGameConsoleEnable( boolean enable ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.INGAME_CONSOLE_ENABLE_KEY, enable );
        writeConfigurationToDisk();
    }

    // region RGB Integration

    /**
     * Master enable for the RGB-integration subsystem. When false, the
     * {@code RgbController} stays inert — no backend probes, no worker
     * thread, no socket or DLL activity. See
     * {@link ConfigConstants#RGB_ENABLE_KEY} for default-off rationale.
     *
     * @since 2026.5
     */
    public synchronized static boolean getRgbEnable()
    {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.RGB_ENABLE_KEY ) ) {
            configObject.addProperty( ConfigConstants.RGB_ENABLE_KEY,
                                       ConfigConstants.RGB_ENABLE_DEFAULT );
        }
        return configObject.get( ConfigConstants.RGB_ENABLE_KEY ).getAsBoolean();
    }

    public synchronized static void setRgbEnable( boolean enable )
    {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.RGB_ENABLE_KEY, enable );
        writeConfigurationToDisk();
    }

    /**
     * Selected RGB backend identifier — see {@link ConfigConstants#RGB_BACKEND_KEY}
     * for the valid set ({@code "auto"} / {@code "openrgb"} / {@code "chroma"}
     * / {@code "none"}). Reads that don't match a known identifier fall back
     * to {@code "auto"}.
     *
     * @since 2026.5
     */
    public synchronized static String getRgbBackend()
    {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.RGB_BACKEND_KEY ) ) {
            configObject.addProperty( ConfigConstants.RGB_BACKEND_KEY,
                                       ConfigConstants.RGB_BACKEND_DEFAULT );
        }
        String value = configObject.get( ConfigConstants.RGB_BACKEND_KEY ).getAsString();
        return switch ( value ) {
            case ConfigConstants.RGB_BACKEND_AUTO,
                 ConfigConstants.RGB_BACKEND_OPENRGB,
                 ConfigConstants.RGB_BACKEND_CHROMA,
                 ConfigConstants.RGB_BACKEND_CHROMA_NATIVE,
                 ConfigConstants.RGB_BACKEND_WINDOWS_DL,
                 ConfigConstants.RGB_BACKEND_NONE -> value;
            default -> ConfigConstants.RGB_BACKEND_DEFAULT;
        };
    }

    public synchronized static void setRgbBackend( String backend )
    {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        // Defensive: only accept known identifiers — same set as getRgbBackend
        // validates. Unknown strings would silently fall back to "auto" on
        // read, but it's cleaner to refuse them at the setter so the on-disk
        // config stays predictable.
        String normalized = switch ( backend ) {
            case ConfigConstants.RGB_BACKEND_AUTO,
                 ConfigConstants.RGB_BACKEND_OPENRGB,
                 ConfigConstants.RGB_BACKEND_CHROMA,
                 ConfigConstants.RGB_BACKEND_CHROMA_NATIVE,
                 ConfigConstants.RGB_BACKEND_WINDOWS_DL,
                 ConfigConstants.RGB_BACKEND_NONE -> backend;
            case null, default -> ConfigConstants.RGB_BACKEND_DEFAULT;
        };
        configObject.addProperty( ConfigConstants.RGB_BACKEND_KEY, normalized );
        writeConfigurationToDisk();
    }

    /**
     * Whether in-game effects use the running modpack's logo dominant
     * colors. When false, effects use the launcher theme's accent palette.
     *
     * @since 2026.5
     */
    public synchronized static boolean getRgbUsePackColors()
    {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.RGB_USE_PACK_COLORS_KEY ) ) {
            configObject.addProperty( ConfigConstants.RGB_USE_PACK_COLORS_KEY,
                                       ConfigConstants.RGB_USE_PACK_COLORS_DEFAULT );
        }
        return configObject.get( ConfigConstants.RGB_USE_PACK_COLORS_KEY ).getAsBoolean();
    }

    public synchronized static void setRgbUsePackColors( boolean usePackColors )
    {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.RGB_USE_PACK_COLORS_KEY, usePackColors );
        writeConfigurationToDisk();
    }

    /**
     * Whether the in-game effect highlights WASD / E / Space / Shift in
     * a contrasting accent over the pack-color background.
     *
     * @since 2026.5
     */
    public synchronized static boolean getRgbHighlightKeys()
    {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.RGB_HIGHLIGHT_KEYS_KEY ) ) {
            configObject.addProperty( ConfigConstants.RGB_HIGHLIGHT_KEYS_KEY,
                                       ConfigConstants.RGB_HIGHLIGHT_KEYS_DEFAULT );
        }
        return configObject.get( ConfigConstants.RGB_HIGHLIGHT_KEYS_KEY ).getAsBoolean();
    }

    public synchronized static void setRgbHighlightKeys( boolean highlight )
    {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.RGB_HIGHLIGHT_KEYS_KEY, highlight );
        writeConfigurationToDisk();
    }

    /**
     * Per-backend enable flags. When the master {@link ConfigConstants#RGB_ENABLE_KEY}
     * is on the controller starts every backend whose enable flag is true
     * AND whose {@code isAvailable} probe returns true at runtime — so a
     * user with mixed-vendor hardware (Razer + Logitech-WinDL + etc.) can
     * drive all of it at once instead of having to pick a single backend.
     *
     * @since 2026.5
     */
    public synchronized static boolean getRgbEnableOpenRgb()
    {
        return readBooleanWithDefault( ConfigConstants.RGB_ENABLE_OPENRGB_KEY,
                                        ConfigConstants.RGB_ENABLE_OPENRGB_DEFAULT );
    }

    public synchronized static void setRgbEnableOpenRgb( boolean enable )
    {
        writeBoolean( ConfigConstants.RGB_ENABLE_OPENRGB_KEY, enable );
    }

    public synchronized static boolean getRgbEnableChromaNative()
    {
        return readBooleanWithDefault( ConfigConstants.RGB_ENABLE_CHROMA_NATIVE_KEY,
                                        ConfigConstants.RGB_ENABLE_CHROMA_NATIVE_DEFAULT );
    }

    public synchronized static void setRgbEnableChromaNative( boolean enable )
    {
        writeBoolean( ConfigConstants.RGB_ENABLE_CHROMA_NATIVE_KEY, enable );
    }

    public synchronized static boolean getRgbEnableChromaRest()
    {
        return readBooleanWithDefault( ConfigConstants.RGB_ENABLE_CHROMA_REST_KEY,
                                        ConfigConstants.RGB_ENABLE_CHROMA_REST_DEFAULT );
    }

    public synchronized static void setRgbEnableChromaRest( boolean enable )
    {
        writeBoolean( ConfigConstants.RGB_ENABLE_CHROMA_REST_KEY, enable );
    }

    public synchronized static boolean getRgbEnableWindowsDl()
    {
        return readBooleanWithDefault( ConfigConstants.RGB_ENABLE_WINDOWS_DL_KEY,
                                        ConfigConstants.RGB_ENABLE_WINDOWS_DL_DEFAULT );
    }

    public synchronized static void setRgbEnableWindowsDl( boolean enable )
    {
        writeBoolean( ConfigConstants.RGB_ENABLE_WINDOWS_DL_KEY, enable );
    }

    public synchronized static boolean getRgbMenuEffectEnable()
    {
        return readBooleanWithDefault( ConfigConstants.RGB_MENU_EFFECT_ENABLE_KEY,
                                        ConfigConstants.RGB_MENU_EFFECT_ENABLE_DEFAULT );
    }

    public synchronized static void setRgbMenuEffectEnable( boolean enable )
    {
        writeBoolean( ConfigConstants.RGB_MENU_EFFECT_ENABLE_KEY, enable );
    }

    /** Lazy-default boolean read shared by the per-backend enable
     *  getters above. Mirrors the lazy-init pattern of the older
     *  getX methods but factored out so we don't duplicate the
     *  read-or-add boilerplate four times. */
    private synchronized static boolean readBooleanWithDefault( String key, boolean dflt )
    {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( key ) ) {
            configObject.addProperty( key, dflt );
        }
        return configObject.get( key ).getAsBoolean();
    }

    private synchronized static void writeBoolean( String key, boolean value )
    {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( key, value );
        writeConfigurationToDisk();
    }

    // region LWJGL ARM64 Patching

    /**
     * Gets whether LWJGL ARM64 native patching is enabled. When enabled, the launcher replaces LWJGL2 x86_64 native
     * libraries with ARM64-compatible builds for older Minecraft versions on ARM64 macOS/Linux.
     *
     * @return true if LWJGL ARM64 patching is enabled
     *
     * @since 3.0
     */
    public synchronized static boolean getLwjglArmPatchEnable() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.LWJGL_ARM_PATCH_ENABLE_KEY ) ) {
            configObject.addProperty( ConfigConstants.LWJGL_ARM_PATCH_ENABLE_KEY,
                                       ConfigConstants.LWJGL_ARM_PATCH_ENABLE_DEFAULT );
        }
        return configObject.get( ConfigConstants.LWJGL_ARM_PATCH_ENABLE_KEY ).getAsBoolean();
    }

    /**
     * Sets whether LWJGL ARM64 native patching is enabled.
     *
     * @param enable true to enable LWJGL ARM64 patching
     *
     * @since 3.0
     */
    public synchronized static void setLwjglArmPatchEnable( boolean enable ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.LWJGL_ARM_PATCH_ENABLE_KEY, enable );
        writeConfigurationToDisk();
    }

    // endregion

    // region Proxy Configuration

    /**
     * Gets whether proxy is enabled.
     *
     * @return true if proxy is enabled
     *
     * @since 3.0
     */
    public synchronized static boolean getProxyEnable() {
        if ( configObject == null ) readConfigurationFromDisk();
        if ( !configObject.has( ConfigConstants.PROXY_ENABLE_KEY ) ) {
            configObject.addProperty( ConfigConstants.PROXY_ENABLE_KEY, ConfigConstants.PROXY_ENABLE_DEFAULT );
            writeConfigurationToDisk();
        }
        return configObject.get( ConfigConstants.PROXY_ENABLE_KEY ).getAsBoolean();
    }

    public synchronized static void setProxyEnable( boolean enable ) {
        if ( configObject == null ) readConfigurationFromDisk();
        configObject.addProperty( ConfigConstants.PROXY_ENABLE_KEY, enable );
        writeConfigurationToDisk();
    }

    /**
     * Gets the proxy host.
     *
     * @return proxy host string
     *
     * @since 3.0
     */
    public synchronized static String getProxyHost() {
        if ( configObject == null ) readConfigurationFromDisk();
        if ( !configObject.has( ConfigConstants.PROXY_HOST_KEY ) ) {
            configObject.addProperty( ConfigConstants.PROXY_HOST_KEY, ConfigConstants.PROXY_HOST_DEFAULT );
            writeConfigurationToDisk();
        }
        return configObject.get( ConfigConstants.PROXY_HOST_KEY ).getAsString();
    }

    public synchronized static void setProxyHost( String host ) {
        if ( configObject == null ) readConfigurationFromDisk();
        configObject.addProperty( ConfigConstants.PROXY_HOST_KEY, host != null ? host : "" );
        writeConfigurationToDisk();
    }

    /**
     * Gets the proxy port.
     *
     * @return proxy port
     *
     * @since 3.0
     */
    public synchronized static int getProxyPort() {
        if ( configObject == null ) readConfigurationFromDisk();
        if ( !configObject.has( ConfigConstants.PROXY_PORT_KEY ) ) {
            configObject.addProperty( ConfigConstants.PROXY_PORT_KEY, ConfigConstants.PROXY_PORT_DEFAULT );
            writeConfigurationToDisk();
        }
        return configObject.get( ConfigConstants.PROXY_PORT_KEY ).getAsInt();
    }

    public synchronized static void setProxyPort( int port ) {
        if ( configObject == null ) readConfigurationFromDisk();
        configObject.addProperty( ConfigConstants.PROXY_PORT_KEY, port );
        writeConfigurationToDisk();
    }

    /**
     * Gets the proxy type ("HTTP" or "SOCKS").
     *
     * @return proxy type string
     *
     * @since 3.0
     */
    public synchronized static String getProxyType() {
        if ( configObject == null ) readConfigurationFromDisk();
        if ( !configObject.has( ConfigConstants.PROXY_TYPE_KEY ) ) {
            configObject.addProperty( ConfigConstants.PROXY_TYPE_KEY, ConfigConstants.PROXY_TYPE_DEFAULT );
            writeConfigurationToDisk();
        }
        return configObject.get( ConfigConstants.PROXY_TYPE_KEY ).getAsString();
    }

    public synchronized static void setProxyType( String type ) {
        if ( configObject == null ) readConfigurationFromDisk();
        configObject.addProperty( ConfigConstants.PROXY_TYPE_KEY, type != null ? type : "HTTP" );
        writeConfigurationToDisk();
    }

    // endregion

    // region Window Bounds Persistence

    /**
     * Gets the persisted launcher window X coordinate.
     *
     * @return saved X coordinate, or {@link Double#NaN} if not previously saved
     *
     * @since 3.0
     */
    public synchronized static double getWindowX() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.WINDOW_X_KEY ) ) {
            return Double.NaN;
        }
        return configObject.get( ConfigConstants.WINDOW_X_KEY ).getAsDouble();
    }

    /**
     * Gets the persisted launcher window Y coordinate.
     *
     * @return saved Y coordinate, or {@link Double#NaN} if not previously saved
     *
     * @since 3.0
     */
    public synchronized static double getWindowY() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.WINDOW_Y_KEY ) ) {
            return Double.NaN;
        }
        return configObject.get( ConfigConstants.WINDOW_Y_KEY ).getAsDouble();
    }

    /**
     * Gets the persisted launcher window width.
     *
     * @return saved width, or {@link Double#NaN} if not previously saved
     *
     * @since 3.0
     */
    public synchronized static double getWindowWidth() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.WINDOW_WIDTH_KEY ) ) {
            return Double.NaN;
        }
        return configObject.get( ConfigConstants.WINDOW_WIDTH_KEY ).getAsDouble();
    }

    /**
     * Gets the persisted launcher window height.
     *
     * @return saved height, or {@link Double#NaN} if not previously saved
     *
     * @since 3.0
     */
    public synchronized static double getWindowHeight() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.WINDOW_HEIGHT_KEY ) ) {
            return Double.NaN;
        }
        return configObject.get( ConfigConstants.WINDOW_HEIGHT_KEY ).getAsDouble();
    }

    /**
     * Gets the persisted launcher window maximized state.
     *
     * @return true if the window was maximized when last saved
     *
     * @since 3.0
     */
    public synchronized static boolean getWindowMaximized() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.WINDOW_MAXIMIZED_KEY ) ) {
            return ConfigConstants.WINDOW_MAXIMIZED_DEFAULT;
        }
        return configObject.get( ConfigConstants.WINDOW_MAXIMIZED_KEY ).getAsBoolean();
    }

    /**
     * Persists the launcher window bounds (position, size, and maximized state) in a single atomic write.
     *
     * @param x         window X coordinate (last unmaximized position)
     * @param y         window Y coordinate (last unmaximized position)
     * @param width     window width (last unmaximized size)
     * @param height    window height (last unmaximized size)
     * @param maximized true if the window was maximized at save time
     *
     * @since 3.0
     */
    public synchronized static void setWindowBounds( double x, double y, double width, double height,
                                                     boolean maximized ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.WINDOW_X_KEY, x );
        configObject.addProperty( ConfigConstants.WINDOW_Y_KEY, y );
        configObject.addProperty( ConfigConstants.WINDOW_WIDTH_KEY, width );
        configObject.addProperty( ConfigConstants.WINDOW_HEIGHT_KEY, height );
        configObject.addProperty( ConfigConstants.WINDOW_MAXIMIZED_KEY, maximized );
        writeConfigurationToDisk();
    }

    // endregion

    /**
     * Gets the configured list of installed vanilla Minecraft version IDs.
     *
     * @return list of installed vanilla version IDs (e.g. ["1.20.4", "1.12.2"])
     *
     * @since 3.0
     */
    public synchronized static List< String > getInstalledVanillaVersions() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.VANILLA_VERSIONS_INSTALLED_KEY ) ) {
            configObject.add( ConfigConstants.VANILLA_VERSIONS_INSTALLED_KEY,
                              JSONUtilities.getGson().toJsonTree( ConfigConstants.VANILLA_VERSIONS_INSTALLED_DEFAULT,
                                                      ConfigConstants.modPacksListType ) );
        }
        JsonArray arr = configObject.get( ConfigConstants.VANILLA_VERSIONS_INSTALLED_KEY ).getAsJsonArray();
        return JSONUtilities.getGson().fromJson( arr, ConfigConstants.modPacksListType );
    }

    /**
     * Sets the configured list of installed vanilla Minecraft version IDs.
     *
     * @param versions list of version IDs
     *
     * @since 3.0
     */
    public synchronized static void setInstalledVanillaVersions( List< String > versions ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.add( ConfigConstants.VANILLA_VERSIONS_INSTALLED_KEY,
                          JSONUtilities.getGson().toJsonTree( versions, ConfigConstants.modPacksListType ) );
        writeConfigurationToDisk();
    }

    /**
     * Gets the per-pack "always verify game files on launch" opt-out toggle.
     * Returns {@link ConfigConstants#ALWAYS_VERIFY_ON_LAUNCH_DEFAULT} (false)
     * when the pack has no explicit setting — making fast-path eligibility
     * the default per the 3.3 design choice.
     *
     * @param packUrl the manifest URL identifying the pack
     *
     * @since 2026.3
     */
    public synchronized static boolean getAlwaysVerifyOnLaunch( String packUrl ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( packUrl == null || packUrl.isBlank() ) {
            return ConfigConstants.ALWAYS_VERIFY_ON_LAUNCH_DEFAULT;
        }
        if ( !configObject.has( ConfigConstants.ALWAYS_VERIFY_BY_PACK_KEY ) ) {
            return ConfigConstants.ALWAYS_VERIFY_ON_LAUNCH_DEFAULT;
        }
        try {
            com.google.gson.JsonObject map = configObject.get(
                    ConfigConstants.ALWAYS_VERIFY_BY_PACK_KEY ).getAsJsonObject();
            if ( !map.has( packUrl ) ) {
                return ConfigConstants.ALWAYS_VERIFY_ON_LAUNCH_DEFAULT;
            }
            return map.get( packUrl ).getAsBoolean();
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Could not read alwaysVerifyOnLaunch for "
                                             + packUrl + ", defaulting." );
            return ConfigConstants.ALWAYS_VERIFY_ON_LAUNCH_DEFAULT;
        }
    }

    /**
     * Sets the per-pack "always verify game files on launch" opt-out toggle.
     * Wired from the modpack-detail-modal Advanced section in step 4 of 3.3.
     *
     * @param packUrl the manifest URL identifying the pack
     * @param value   {@code true} to force FULL verify on every launch of this
     *                pack; {@code false} (default) to allow fast-path
     *
     * @since 2026.3
     */
    public synchronized static void setAlwaysVerifyOnLaunch( String packUrl, boolean value ) {
        if ( packUrl == null || packUrl.isBlank() ) return;
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        com.google.gson.JsonObject map;
        if ( configObject.has( ConfigConstants.ALWAYS_VERIFY_BY_PACK_KEY ) ) {
            map = configObject.get( ConfigConstants.ALWAYS_VERIFY_BY_PACK_KEY ).getAsJsonObject();
        }
        else {
            map = new com.google.gson.JsonObject();
        }
        map.addProperty( packUrl, value );
        configObject.add( ConfigConstants.ALWAYS_VERIFY_BY_PACK_KEY, map );
        writeConfigurationToDisk();
    }

    /**
     * Gets the launcher-wide default security-scan frequency. Falls back to
     * {@link com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency#DEFAULT}
     * when the key is missing / unparseable, so a config from a launcher version that
     * didn't have this key keeps working with the safe default.
     *
     * @since 2026.3
     */
    public synchronized static com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency getDefaultScanFrequency() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.DEFAULT_SCAN_FREQUENCY_KEY ) ) {
            return com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency.DEFAULT;
        }
        try {
            String name = configObject.get( ConfigConstants.DEFAULT_SCAN_FREQUENCY_KEY ).getAsString();
            return com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency.fromNameSafe( name );
        }
        catch ( Exception e ) {
            return com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency.DEFAULT;
        }
    }

    /**
     * Sets the launcher-wide default security-scan frequency. Wired from the
     * Settings → Advanced "Security Scan Frequency" combo.
     *
     * @since 2026.3
     */
    public synchronized static void setDefaultScanFrequency(
            com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency frequency ) {
        if ( frequency == null ) frequency = com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency.DEFAULT;
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.DEFAULT_SCAN_FREQUENCY_KEY, frequency.name() );
        writeConfigurationToDisk();
    }

    /**
     * Returns the per-pack scan-frequency override for the given pack URL, or
     * {@code null} when no override is set (i.e. "Use global default"). The
     * three-state return — null / explicit enum — lets callers distinguish
     * "user picked the global default knowingly" from "user picked EVERY_TIME
     * which happens to match the current default."
     *
     * @since 2026.3
     */
    public synchronized static com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency
            getScanFrequencyForPack( String packUrl ) {
        if ( packUrl == null || packUrl.isBlank() ) return null;
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.SCAN_FREQUENCY_BY_PACK_KEY ) ) return null;
        try {
            com.google.gson.JsonObject map = configObject.get(
                    ConfigConstants.SCAN_FREQUENCY_BY_PACK_KEY ).getAsJsonObject();
            if ( !map.has( packUrl ) ) return null;
            String name = map.get( packUrl ).getAsString();
            if ( name == null || name.isBlank() ) return null;
            // Use raw valueOf so unknown values fall through to null (== use global default)
            // rather than silently downgrading to DEFAULT — that would mask config corruption.
            try {
                return com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency.valueOf( name );
            }
            catch ( IllegalArgumentException e ) {
                return null;
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Could not read scanFrequency for "
                                             + packUrl + ", falling back to default." );
            return null;
        }
    }

    /**
     * Sets (or clears) the per-pack scan-frequency override. Passing
     * {@code null} for {@code frequency} removes the override so the pack
     * follows the global default again.
     *
     * @since 2026.3
     */
    public synchronized static void setScanFrequencyForPack(
            String packUrl,
            com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency frequency ) {
        if ( packUrl == null || packUrl.isBlank() ) return;
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        com.google.gson.JsonObject map;
        if ( configObject.has( ConfigConstants.SCAN_FREQUENCY_BY_PACK_KEY ) ) {
            map = configObject.get( ConfigConstants.SCAN_FREQUENCY_BY_PACK_KEY ).getAsJsonObject();
        }
        else {
            map = new com.google.gson.JsonObject();
        }
        if ( frequency == null ) {
            map.remove( packUrl );
        }
        else {
            map.addProperty( packUrl, frequency.name() );
        }
        configObject.add( ConfigConstants.SCAN_FREQUENCY_BY_PACK_KEY, map );
        writeConfigurationToDisk();
    }

    /**
     * Resolves the effective scan frequency for a pack: per-pack override if
     * one is set, else the launcher-wide default. The decision callers
     * ({@code ScanFrequency.shouldScan}) only need a single value, so this
     * hides the override-with-fallback dance.
     *
     * @since 2026.3
     */
    public synchronized static com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency
            effectiveScanFrequencyForPack( String packUrl ) {
        com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency override =
                getScanFrequencyForPack( packUrl );
        return override != null ? override : getDefaultScanFrequency();
    }

    /**
     * Decrypts and returns the user-supplied CurseForge Core API key, or
     * {@code null} when none is configured (or the on-disk envelope can't
     * be decrypted on this machine — wrong host, tampered file, etc.).
     * The key is stored under {@link ConfigConstants#CURSEFORGE_API_KEY_KEY}
     * as a Base64 envelope of machine-bound AES-256-GCM ciphertext, same
     * primitive that protects the cached Minecraft auth tokens.
     *
     * <p>A decryption failure returns {@code null} (not an exception) so
     * the CurseForge import path can simply degrade to "no key available
     * → show manual-workaround preview" rather than failing the entire
     * settings load.</p>
     *
     * @since 2026.3
     */
    public synchronized static String getCurseForgeApiKey() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.CURSEFORGE_API_KEY_KEY ) ) return null;
        String encoded;
        try {
            encoded = configObject.get( ConfigConstants.CURSEFORGE_API_KEY_KEY ).getAsString();
        }
        catch ( Exception e ) {
            return null;
        }
        if ( encoded == null || encoded.isBlank() ) return null;
        try {
            return com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher.decrypt( encoded );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "CurseForge API key could not be decrypted on this machine: "
                                             + t.getClass().getSimpleName() );
            return null;
        }
    }

    /**
     * Encrypts and persists the user-supplied CurseForge API key. Passing
     * {@code null} or a blank string clears the stored value — useful when
     * the user wants to revoke launcher access to their CF account.
     *
     * <p>The encrypted envelope is bound to this machine; a copy of the
     * config file on another machine will see the field but won't be able
     * to decrypt it. If encryption itself fails (vanishingly rare —
     * cipher init failures on this JRE), the call returns silently
     * without persisting; the previous on-disk value is left intact.</p>
     *
     * @since 2026.3
     */
    public synchronized static void setCurseForgeApiKey( String apiKey ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( apiKey == null || apiKey.isBlank() ) {
            configObject.remove( ConfigConstants.CURSEFORGE_API_KEY_KEY );
            writeConfigurationToDisk();
            return;
        }
        try {
            String envelope = com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher
                    .encrypt( apiKey );
            configObject.addProperty( ConfigConstants.CURSEFORGE_API_KEY_KEY, envelope );
            writeConfigurationToDisk();
        }
        catch ( Throwable t ) {
            Logger.logErrorSilent( "Could not encrypt CurseForge API key for storage: "
                                           + t.getClass().getSimpleName() );
        }
    }

    /**
     * Returns {@code true} when a non-empty encrypted CurseForge API key is
     * stored in the config. Doesn't actually decrypt — useful for the
     * Settings UI to show "configured" / "not set" without holding the
     * cleartext key in memory longer than necessary.
     *
     * @since 2026.3
     */
    /**
     * Toggle: should modpack / version cards on the main menu and Library
     * view overlay the pack's real background image on top of the procedural
     * gradient? Default true. Reads through the standard
     * {@link ConfigConstants#SHOW_PACK_BACKGROUNDS_KEY} key.
     *
     * @since 2026.3
     */
    public synchronized static boolean getShowPackBackgrounds() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.SHOW_PACK_BACKGROUNDS_KEY ) ) {
            return ConfigConstants.SHOW_PACK_BACKGROUNDS_DEFAULT;
        }
        try {
            return configObject.get( ConfigConstants.SHOW_PACK_BACKGROUNDS_KEY ).getAsBoolean();
        }
        catch ( Exception e ) {
            return ConfigConstants.SHOW_PACK_BACKGROUNDS_DEFAULT;
        }
    }

    /**
     * Sets the {@link #getShowPackBackgrounds} toggle. Persists to disk
     * immediately; the next card-grid rebuild on the main menu / Library
     * view picks up the new value.
     *
     * @since 2026.3
     */
    public synchronized static void setShowPackBackgrounds( boolean show ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.SHOW_PACK_BACKGROUNDS_KEY, show );
        writeConfigurationToDisk();
    }

    public synchronized static boolean hasCurseForgeApiKey() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.CURSEFORGE_API_KEY_KEY ) ) return false;
        try {
            String v = configObject.get( ConfigConstants.CURSEFORGE_API_KEY_KEY ).getAsString();
            return v != null && !v.isBlank();
        }
        catch ( Exception e ) {
            return false;
        }
    }

    /**
     * Reads the application configuration from its file on persistent storage. In the event that a file does not exist,
     * or an error occurred with the file, a new default configuration file will be created.
     *
     * @since 1.0
     */
    private synchronized static void readConfigurationFromDisk() {
        // Get file path and file object for config file
        String configFilePath = LocalPathManager.getLauncherConfigFolderPath() + ConfigConstants.CONFIG_FILE_NAME;
        File configFile = SynchronizedFileManager.getSynchronizedFile( configFilePath );

        // Check if file exists (and is file), and attempt to read
        boolean read = configFile.isFile();
        if ( read ) {
            try {
                configObject = FileUtilities.readAsJsonObject( configFile );
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.CONFIG_EXISTS_CORRUPT_RESET_ERROR_TEXT );
                Logger.logThrowable( e );
                read = false;
            }
        }

        // If configuration not read or failed to read, use default configuration
        if ( !read ) {
            configObject = new JsonObject();
            setMinRam( ConfigConstants.MIN_RAM_MEGABYTES_DEFAULT );
            setMaxRam( ConfigConstants.MAX_RAM_MEGABYTES_DEFAULT );
            setDebugLogging( ConfigConstants.LOG_DEBUG_ENABLE_DEFAULT );
            setDiscordRpcEnable( ConfigConstants.DISCORD_RPC_ENABLE_DEFAULT );
            setResizableWindows( ConfigConstants.RESIZE_WINDOWS_ENABLE_DEFAULT );
            setInstalledModPacks( ConfigConstants.MOD_PACKS_INSTALLED_DEFAULT );
            configObject.addProperty( ConfigConstants.CONFIG_VERSION_KEY, ConfigConstants.CONFIG_VERSION );
            Logger.logStd( LocalizationManager.CONFIG_RESET_SUCCESS_TEXT );
        }

        // Migrate existing config if schema version is outdated or missing
        migrateConfigIfNeeded();
    }

    /**
     * Migrates the configuration to the current schema version by ensuring all expected keys have default values. This
     * is called after loading the config from disk. If the stored version is already current, this is a no-op.
     *
     * @since 3.0
     */
    private synchronized static void migrateConfigIfNeeded()
    {
        int storedVersion = 0;
        if ( configObject.has( ConfigConstants.CONFIG_VERSION_KEY ) ) {
            storedVersion = configObject.get( ConfigConstants.CONFIG_VERSION_KEY ).getAsInt();
        }

        if ( storedVersion >= ConfigConstants.CONFIG_VERSION ) {
            return;
        }

        Logger.logStd( "Migrating config from version " + storedVersion + " to " + ConfigConstants.CONFIG_VERSION );

        // Ensure all keys exist with defaults (each getter already does this individually,
        // but calling them here ensures a complete config on disk after migration)
        getMinRam();
        getMaxRam();
        getDebugLogging();
        getDiscordRpcEnable();
        getResizableWindows();
        getEnhancedLogging();
        getCustomJvmArgs();
        getLastModPackSelected();
        getTheme();
        getInstalledModPacks();
        getInGameConsoleEnable();
        getProxyEnable();
        getProxyHost();
        getProxyPort();
        getProxyType();
        getInstalledVanillaVersions();
        getLwjglArmPatchEnable();
        getDiscordInvitesEnable();

        // Stamp the current version and persist
        configObject.addProperty( ConfigConstants.CONFIG_VERSION_KEY, ConfigConstants.CONFIG_VERSION );
        writeConfigurationToDisk();
    }

    /**
     * Writes the application configuration to its file on persistent storage.
     *
     * @since 1.0
     */
    /**
     * Exports the current launcher configuration to the specified file as pretty-printed JSON.
     *
     * @param destination the file to write the exported config to
     *
     * @return true if export succeeded
     *
     * @since 3.0
     */
    public synchronized static boolean exportConfig( File destination ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        try {
            FileUtilities.writeFromJson( configObject, destination );
            return true;
        }
        catch ( Exception e ) {
            Logger.logErrorSilent( "Failed to export settings: " + e.getMessage() );
            return false;
        }
    }

    /**
     * Imports launcher configuration from the specified file, replacing the current config. The imported config is
     * migrated to the current schema version after loading.
     *
     * @param source the file to read the config from
     *
     * @return true if import succeeded
     *
     * @since 3.0
     */
    public synchronized static boolean importConfig( File source ) {
        try {
            JsonObject imported = FileUtilities.readAsJsonObject( source );
            configObject = imported;
            migrateConfigIfNeeded();
            writeConfigurationToDisk();
            return true;
        }
        catch ( Exception e ) {
            Logger.logErrorSilent( "Failed to import settings: " + e.getMessage() );
            return false;
        }
    }

    private synchronized static void writeConfigurationToDisk() {
        // Check if configuration is loaded, return if not
        if ( configObject == null ) {
            Logger.logError( LocalizationManager.CONFIG_NOT_LOADED_CANT_SAVE_ERROR_TEXT );
            return;
        }

        // Get file path and file object for config file
        String configFilePath = LocalPathManager.getLauncherConfigFolderPath() + ConfigConstants.CONFIG_FILE_NAME;
        File configFile = SynchronizedFileManager.getSynchronizedFile( configFilePath );

        try {
            FileUtilities.writeFromJson( configObject, configFile );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.CONFIG_SAVE_ERROR_TEXT );
            Logger.logThrowable( e );
        }
    }
}

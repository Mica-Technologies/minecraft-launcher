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

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
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
        return RuntimeConfig.getMinRam();
    }

    /**
     * Sets the configured minimum RAM for the Minecraft game.
     *
     * @param minRam Minecraft starting/min RAM
     *
     * @since 1.0
     */
    public synchronized static void setMinRam( long minRam ) {
        RuntimeConfig.setMinRam( minRam );
    }

    /**
     * Gets the configured maximum RAM for the Minecraft game.
     *
     * @return Minecraft maximum RAM
     *
     * @since 1.0
     */
    public synchronized static long getMaxRam() {
        return RuntimeConfig.getMaxRam();
    }

    /**
     * Gets the configured maximum RAM (in GB) for the Minecraft game.
     *
     * @return Minecraft maximum RAM (in GB)
     *
     * @since 1.0
     */
    public synchronized static double getMaxRamInGb() {
        return RuntimeConfig.getMaxRamInGb();
    }

    /**
     * Sets the configured maximum RAM for the Minecraft game.
     *
     * @param maxRam Minecraft maximum RAM
     *
     * @since 1.0
     */
    public synchronized static void setMaxRam( long maxRam ) {
        RuntimeConfig.setMaxRam( maxRam );
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
        return RuntimeConfig.getCustomJvmArgs();
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
        // Keep the security validation here (not in RuntimeConfig) — the
        // reject-on-injection-characters check is a public-API contract
        // of ConfigManager's setter and shouldn't be bypassable by
        // calling RuntimeConfig.setCustomJvmArgs directly. RuntimeConfig
        // is for trusted internal use; this entry point is what
        // user-facing code (Settings GUI, etc.) calls.
        String sanitized = validateCustomJvmArgs( jvmArgs );
        RuntimeConfig.setCustomJvmArgs( sanitized );
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
        return ModPackConfig.getLastModPackSelected();
    }

    /**
     * Sets the last mod pack selected.
     *
     * @param lastModPackSelected last mod pack selected
     *
     * @since 2.0
     */
    public synchronized static void setLastModPackSelected( String lastModPackSelected ) {
        ModPackConfig.setLastModPackSelected( lastModPackSelected );
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
     * Returns the user-supplied locale override as a BCP-47 tag (e.g.
     * {@code "fr-FR"}), or an empty string when no override is set — in
     * which case {@code LocaleBootstrap.detectOsLocale} picks the OS
     * default at startup.
     *
     * <p>No default is written to disk on first read: a blank value is the
     * canonical "use OS detection" signal and writing a concrete locale
     * here would override the OS detection silently.</p>
     *
     * @since 2026.5
     */
    public synchronized static String getLocaleOverride() {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        if ( !configObject.has( ConfigConstants.LOCALE_OVERRIDE_KEY ) ) {
            return "";
        }
        com.google.gson.JsonElement el = configObject.get( ConfigConstants.LOCALE_OVERRIDE_KEY );
        if ( el == null || el.isJsonNull() ) return "";
        return el.getAsString();
    }

    /**
     * Sets the user-supplied locale override. Persists immediately. The
     * new value takes effect at the next launcher startup (via
     * {@code LocaleBootstrap.apply}) — calling this mid-session doesn't
     * reload the existing scenes' translations.
     *
     * @param tag BCP-47 tag (e.g. {@code "fr-FR"}); null / empty clears
     *            the override and falls back to OS detection
     * @since 2026.5
     */
    public synchronized static void setLocaleOverride( String tag ) {
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }
        configObject.addProperty( ConfigConstants.LOCALE_OVERRIDE_KEY,
                                   tag == null ? "" : tag );
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
        return ModPackConfig.getInstalledModPacks();
    }

    /**
     * Sets the configured list of installed mod packs' manifest URLs.
     *
     * @param installedModPacks list of installed mod packs' manifest URLs
     *
     * @since 1.0
     */
    public synchronized static void setInstalledModPacks( List< String > installedModPacks ) {
        ModPackConfig.setInstalledModPacks( installedModPacks );
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
        return RgbConfig.getRgbEnable();
    }

    public synchronized static void setRgbEnable( boolean enable )
    {
        RgbConfig.setRgbEnable( enable );
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
        return RgbConfig.getRgbBackend();
    }

    public synchronized static void setRgbBackend( String backend )
    {
        RgbConfig.setRgbBackend( backend );
    }

    /**
     * Whether in-game effects use the running modpack's logo dominant
     * colors. When false, effects use the launcher theme's accent palette.
     *
     * @since 2026.5
     */
    public synchronized static boolean getRgbUsePackColors()
    {
        return RgbConfig.getRgbUsePackColors();
    }

    public synchronized static void setRgbUsePackColors( boolean usePackColors )
    {
        RgbConfig.setRgbUsePackColors( usePackColors );
    }

    /**
     * Whether the in-game effect highlights WASD / E / Space / Shift in
     * a contrasting accent over the pack-color background.
     *
     * @since 2026.5
     */
    public synchronized static boolean getRgbHighlightKeys()
    {
        return RgbConfig.getRgbHighlightKeys();
    }

    public synchronized static void setRgbHighlightKeys( boolean highlight )
    {
        RgbConfig.setRgbHighlightKeys( highlight );
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
    public synchronized static boolean getRgbEnableOpenRgb()  { return RgbConfig.getRgbEnableOpenRgb(); }
    public synchronized static void    setRgbEnableOpenRgb( boolean enable ) { RgbConfig.setRgbEnableOpenRgb( enable ); }
    public synchronized static boolean getRgbEnableChromaNative() { return RgbConfig.getRgbEnableChromaNative(); }
    public synchronized static void    setRgbEnableChromaNative( boolean enable ) { RgbConfig.setRgbEnableChromaNative( enable ); }
    public synchronized static boolean getRgbEnableChromaRest()   { return RgbConfig.getRgbEnableChromaRest(); }
    public synchronized static void    setRgbEnableChromaRest( boolean enable ) { RgbConfig.setRgbEnableChromaRest( enable ); }
    public synchronized static boolean getRgbEnableWindowsDl()    { return RgbConfig.getRgbEnableWindowsDl(); }
    public synchronized static void    setRgbEnableWindowsDl( boolean enable ) { RgbConfig.setRgbEnableWindowsDl( enable ); }
    public synchronized static boolean getRgbEnableCorsair()      { return RgbConfig.getRgbEnableCorsair(); }
    public synchronized static void    setRgbEnableCorsair( boolean enable ) { RgbConfig.setRgbEnableCorsair( enable ); }
    public synchronized static boolean getRgbEnableAsusAura()     { return RgbConfig.getRgbEnableAsusAura(); }
    public synchronized static void    setRgbEnableAsusAura( boolean enable ) { RgbConfig.setRgbEnableAsusAura( enable ); }

    public synchronized static boolean getRgbMenuEffectEnable()
    {
        return RgbConfig.getRgbMenuEffectEnable();
    }

    public synchronized static void setRgbMenuEffectEnable( boolean enable )
    {
        RgbConfig.setRgbMenuEffectEnable( enable );
    }

    public synchronized static String getRgbEffectStyle()
    {
        return RgbConfig.getRgbEffectStyle();
    }

    public synchronized static void setRgbEffectStyle( String style )
    {
        RgbConfig.setRgbEffectStyle( style );
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
        return NetworkConfig.getProxyEnable();
    }

    public synchronized static void setProxyEnable( boolean enable ) {
        NetworkConfig.setProxyEnable( enable );
    }

    /**
     * Gets the proxy host.
     *
     * @return proxy host string
     *
     * @since 3.0
     */
    public synchronized static String getProxyHost() {
        return NetworkConfig.getProxyHost();
    }

    public synchronized static void setProxyHost( String host ) {
        NetworkConfig.setProxyHost( host );
    }

    /**
     * Gets the proxy port.
     *
     * @return proxy port
     *
     * @since 3.0
     */
    public synchronized static int getProxyPort() {
        return NetworkConfig.getProxyPort();
    }

    public synchronized static void setProxyPort( int port ) {
        NetworkConfig.setProxyPort( port );
    }

    /**
     * Gets the proxy type ("HTTP" or "SOCKS").
     *
     * @return proxy type string
     *
     * @since 3.0
     */
    public synchronized static String getProxyType() {
        return NetworkConfig.getProxyType();
    }

    public synchronized static void setProxyType( String type ) {
        NetworkConfig.setProxyType( type );
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
        return ModPackConfig.getInstalledVanillaVersions();
    }

    /**
     * Sets the configured list of installed vanilla Minecraft version IDs.
     *
     * @param versions list of version IDs
     *
     * @since 3.0
     */
    public synchronized static void setInstalledVanillaVersions( List< String > versions ) {
        ModPackConfig.setInstalledVanillaVersions( versions );
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
        return ModPackConfig.getAlwaysVerifyOnLaunch( packUrl );
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
        ModPackConfig.setAlwaysVerifyOnLaunch( packUrl, value );
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
        return ModPackConfig.getDefaultScanFrequency();
    }

    /**
     * Sets the launcher-wide default security-scan frequency. Wired from the
     * Settings → Advanced "Security Scan Frequency" combo.
     *
     * @since 2026.3
     */
    public synchronized static void setDefaultScanFrequency(
            com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency frequency ) {
        ModPackConfig.setDefaultScanFrequency( frequency );
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
        return ModPackConfig.getScanFrequencyForPack( packUrl );
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
        ModPackConfig.setScanFrequencyForPack( packUrl, frequency );
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
        return ModPackConfig.effectiveScanFrequencyForPack( packUrl );
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
        return AuthTokenStore.getCurseForgeApiKey();
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
        AuthTokenStore.setCurseForgeApiKey( apiKey );
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
        return ModPackConfig.getShowPackBackgrounds();
    }

    /**
     * Sets the {@link #getShowPackBackgrounds} toggle. Persists to disk
     * immediately; the next card-grid rebuild on the main menu / Library
     * view picks up the new value.
     *
     * @since 2026.3
     */
    public synchronized static void setShowPackBackgrounds( boolean show ) {
        ModPackConfig.setShowPackBackgrounds( show );
    }

    public synchronized static boolean hasCurseForgeApiKey() {
        return AuthTokenStore.hasCurseForgeApiKey();
    }

    /**
     * Reads the application configuration from its file on persistent storage. In the event that a file does not exist,
     * or an error occurred with the file, a new default configuration file will be created.
     *
     * @since 1.0
     */
    /** Public for {@link ConfigStore} to call back into ConfigManager
     *  after a fresh-default JsonObject is created on first launch.
     *  Populates the defaults that the rest of the launcher expects
     *  to be present so the first cold-start doesn't surface as a
     *  cascade of "key not found" branches.
     *
     *  <p>Sets {@link #configObject} from {@link ConfigStore#peek}
     *  first so the {@code setX} calls below find a non-null state
     *  and don't re-enter {@code ConfigStore.ensureLoaded()} (which
     *  is currently holding its synchronized lock as the caller of
     *  this method).</p> */
    static void populateFirstRunDefaults() {
        configObject = ConfigStore.peek();
        setMinRam( ConfigConstants.MIN_RAM_MEGABYTES_DEFAULT );
        setMaxRam( ConfigConstants.MAX_RAM_MEGABYTES_DEFAULT );
        setDebugLogging( ConfigConstants.LOG_DEBUG_ENABLE_DEFAULT );
        setDiscordRpcEnable( ConfigConstants.DISCORD_RPC_ENABLE_DEFAULT );
        setResizableWindows( ConfigConstants.RESIZE_WINDOWS_ENABLE_DEFAULT );
        setInstalledModPacks( ConfigConstants.MOD_PACKS_INSTALLED_DEFAULT );
    }

    /** Lazily loads the configuration via {@link ConfigStore} and
     *  caches the shared JsonObject reference in {@link #configObject}
     *  so the ~80 setter / getter pairs in this file continue to use
     *  the same field-style access pattern without each having to
     *  call into ConfigStore directly. The actual disk I/O lives in
     *  ConfigStore.loadFromDisk; ConfigManager just hands ConfigStore
     *  a callback for first-run defaulting via
     *  {@link #populateFirstRunDefaults}. */
    private synchronized static void readConfigurationFromDisk() {
        configObject = ConfigStore.ensureLoaded();
    }

    /**
     * Migrates the configuration to the current schema version by ensuring all expected keys have default values. This
     * is called after loading the config from disk. If the stored version is already current, this is a no-op.
     *
     * @since 3.0
     */
    // Package-private so ConfigStore.loadFromDisk can call back into the
    // migration after replacing the JsonObject on a fresh load.
    static synchronized void migrateConfigIfNeeded()
    {
        if ( configObject == null ) {
            configObject = ConfigStore.peek();
        }
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
            // Plumb the swapped-in JSON through ConfigStore so the
            // store's view of the live config stays in sync with this
            // class's cached configObject reference — otherwise the
            // debounced write would still flush the pre-import object.
            ConfigStore.setJson( imported );
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

    /** Schedules a debounced disk write via {@link ConfigStore}. All
     *  the public {@code setX} methods in this file call this; the
     *  store coalesces a burst of setter calls into one disk write
     *  through its 500 ms debounce window. See the
     *  {@link ConfigStore} class docs for the threading + shutdown
     *  semantics.
     *
     *  <p>Kept as a thin wrapper rather than inlined at every setter
     *  call site so a future change to the persistence model (sync
     *  writes, append-only journal, etc.) lands in one place.</p> */
    private static void writeConfigurationToDisk() {
        ConfigStore.scheduleWrite();
    }

    /** Public flush hook — call before exiting a code path that needs
     *  the on-disk state to match the in-memory configObject right
     *  now (e.g. after a settings import + before returning to the
     *  caller). The {@link ConfigStore} shutdown hook covers normal
     *  process exit. */
    public static void flushPendingWrite() {
        ConfigStore.flushNow();
    }
}

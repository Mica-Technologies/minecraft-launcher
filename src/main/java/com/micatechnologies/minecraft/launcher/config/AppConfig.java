/*
 * Copyright (c) 2026 Mica Technologies
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

/**
 * App-level preferences and launcher behavior toggles — the residue
 * the A6-deep split's first five slices (RuntimeConfig, AuthTokenStore,
 * ModPackConfig, RgbConfig, NetworkConfig) didn't claim. Lives here so
 * {@link ConfigManager} can be a true thin facade rather than a god class
 * with a handful of leftover methods. Sixth and final slice of the A6
 * refactor.
 *
 * <p>Covers:
 * <ul>
 *   <li><b>Theme + locale</b> — visual appearance preferences.</li>
 *   <li><b>Logging</b> — debug + enhanced toggles.</li>
 *   <li><b>Discord integration</b> — rich-presence enable + invite enable.</li>
 *   <li><b>UI behavior</b> — resizable windows, in-game console.</li>
 *   <li><b>App lifecycle</b> — quick-start-completed marker,
 *       launcher-update-check enable, mmcl:// URI handler enable,
 *       battery-throttle enable.</li>
 *   <li><b>LWJGL ARM patch</b> — game-runtime toggle for older MC on
 *       Apple Silicon / ARM Linux.</li>
 *   <li><b>Window bounds</b> — last-saved position + size +
 *       maximized state for the launcher window.</li>
 * </ul>
 *
 * <p>State is read through {@link ConfigStore#ensureLoaded()} so this
 * class never holds its own copy of the JsonObject — every call sees
 * the live state, and writes are routed through the debounced disk-flush
 * queue.</p>
 *
 * @since 2026.5
 */
public final class AppConfig
{
    /** Not instantiable; this is a static-only utility holder. */
    private AppConfig() { /* static-only */ }

    // ====================================================================
    // Theme + locale
    // ====================================================================

    /** Active launcher theme identifier. */
    public static synchronized String getTheme() {
        return ConfigStore.getOrInitString( ConfigConstants.THEME_KEY, ConfigConstants.THEME_DEFAULT );
    }

    /**
     * Sets the active launcher theme identifier and schedules a debounced disk flush.
     *
     * @param theme the theme identifier to persist
     */
    public static synchronized void setTheme( String theme ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.THEME_KEY, theme );
        ConfigStore.scheduleWrite();
    }

    /** User-supplied locale override as a BCP-47 tag (e.g. {@code "fr-FR"}),
     *  or an empty string when no override is set — in which case
     *  {@code LocaleBootstrap.detectOsLocale} picks the OS default at
     *  startup.
     *
     *  <p>No default is written to disk on first read: a blank value is
     *  the canonical "use OS detection" signal and writing a concrete
     *  locale here would override the OS detection silently.</p> */
    public static synchronized String getLocaleOverride() {
        // No default written: a blank value is the "use OS detection" signal, so
        // getString (no-write) returns "" for absent / JSON-null without persisting.
        return ConfigStore.getString( ConfigConstants.LOCALE_OVERRIDE_KEY, "" );
    }

    /**
     * Sets the user locale override and schedules a debounced disk flush. A {@code null} or empty tag clears the
     * override, restoring OS-locale detection at the next startup.
     *
     * @param tag the BCP-47 locale tag to persist (e.g. {@code "fr-FR"}), or {@code null}/empty to clear the override
     */
    public static synchronized void setLocaleOverride( String tag ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.LOCALE_OVERRIDE_KEY,
                                                  tag == null ? "" : tag );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // Logging
    // ====================================================================

    /** Whether debug logging is enabled. Dev builds always log debug
     *  regardless of this flag (the {@link LauncherConstants#LAUNCHER_IS_DEV}
     *  short-circuit lives in the getter so the on-disk value remains
     *  the user's actual preference). */
    public static synchronized boolean getDebugLogging() {
        return LauncherConstants.LAUNCHER_IS_DEV
                || ConfigStore.getOrInitBoolean( ConfigConstants.LOG_DEBUG_ENABLE_KEY,
                                                 ConfigConstants.LOG_DEBUG_ENABLE_DEFAULT );
    }

    /**
     * Sets the persisted debug-logging preference and schedules a debounced disk flush. Note that dev builds always
     * log debug regardless of this value; only the user's on-disk preference is changed here.
     *
     * @param enable {@code true} to enable debug logging, {@code false} to disable it
     */
    public static synchronized void setDebugLogging( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.LOG_DEBUG_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Whether enhanced (more verbose) logging is enabled. */
    public static synchronized boolean getEnhancedLogging() {
        return ConfigStore.getOrInitBoolean( ConfigConstants.LOG_ENHANCED_ENABLE_KEY, true );
    }

    /**
     * Sets the enhanced (more verbose) logging preference and schedules a debounced disk flush.
     *
     * @param enable {@code true} to enable enhanced logging, {@code false} to disable it
     */
    public static synchronized void setEnhancedLogging( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.LOG_ENHANCED_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // Discord integration
    // ====================================================================

    /** Whether Discord rich-presence is enabled. */
    public static synchronized boolean getDiscordRpcEnable() {
        // The historical "force-disable in dev" path was removed
        // intentionally — dev builds need to validate the RPC + invite
        // surface end-to-end. The user's choice is now honoured in all
        // environments; the matching dev-mode setDisable on the
        // Settings checkbox is gone too.
        return ConfigStore.getOrInitBoolean( ConfigConstants.DISCORD_RPC_ENABLE_KEY,
                                             ConfigConstants.DISCORD_RPC_ENABLE_DEFAULT );
    }

    /**
     * Sets the Discord rich-presence enable preference and schedules a debounced disk flush.
     *
     * @param enable {@code true} to enable Discord rich-presence, {@code false} to disable it
     */
    public static synchronized void setDiscordRpcEnable( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.DISCORD_RPC_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Whether Discord "Join Game" invites are enabled. Independent
     *  of {@link #getDiscordRpcEnable} so a user can have presence on
     *  but invites off (e.g. doesn't want strangers joining). */
    public static synchronized boolean getDiscordInvitesEnable() {
        return ConfigStore.getOrInitBoolean( ConfigConstants.DISCORD_INVITES_ENABLE_KEY,
                                             ConfigConstants.DISCORD_INVITES_ENABLE_DEFAULT );
    }

    /**
     * Sets the Discord "Join Game" invites enable preference and schedules a debounced disk flush.
     *
     * @param enable {@code true} to enable Discord invites, {@code false} to disable them
     */
    public static synchronized void setDiscordInvitesEnable( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.DISCORD_INVITES_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // UI behavior
    // ====================================================================

    /** Whether launcher windows can be resized. */
    public static synchronized boolean getResizableWindows() {
        return ConfigStore.getOrInitBoolean( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY,
                                             ConfigConstants.RESIZE_WINDOWS_ENABLE_DEFAULT );
    }

    /**
     * Sets whether launcher windows can be resized and schedules a debounced disk flush.
     *
     * @param enable {@code true} to allow window resizing, {@code false} to disallow it
     */
    public static synchronized void setResizableWindows( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Whether the in-game console window is enabled when launching
     *  a modpack. */
    public static synchronized boolean getInGameConsoleEnable() {
        return ConfigStore.getOrInitBoolean( ConfigConstants.INGAME_CONSOLE_ENABLE_KEY,
                                             ConfigConstants.INGAME_CONSOLE_ENABLE_DEFAULT );
    }

    /**
     * Sets whether the in-game console window is shown when launching a modpack and schedules a debounced disk flush.
     *
     * @param enable {@code true} to enable the in-game console, {@code false} to disable it
     */
    public static synchronized void setInGameConsoleEnable( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.INGAME_CONSOLE_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Visible-log-line cap for the in-game console TextArea. 0 means
     *  unlimited (no trimming). Read on each line-batch flush so the
     *  user can change the setting mid-session without restarting. */
    public static synchronized int getConsoleLogMaxLines() {
        return ConfigStore.getInt( ConfigConstants.CONSOLE_LOG_MAX_LINES_KEY,
                                   ConfigConstants.CONSOLE_LOG_MAX_LINES_DEFAULT );
    }

    /**
     * Sets the visible-log-line cap for the in-game console and schedules a debounced disk flush. Negative values are
     * clamped to {@code 0} (unlimited).
     *
     * @param max the maximum number of visible console lines, or {@code 0} for unlimited
     */
    public static synchronized void setConsoleLogMaxLines( int max ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.CONSOLE_LOG_MAX_LINES_KEY,
                                                 Math.max( 0, max ) );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // App lifecycle
    // ====================================================================

    /** Whether the launcher should check for its own updates on startup. */
    public static synchronized boolean getLauncherUpdateCheckEnabled() {
        return ConfigStore.getOrInitBoolean( ConfigConstants.LAUNCHER_UPDATE_CHECK_KEY, true );
    }

    /**
     * Sets whether the launcher checks for its own updates on startup and schedules a debounced disk flush.
     *
     * @param enable {@code true} to enable the startup update check, {@code false} to disable it
     */
    public static synchronized void setLauncherUpdateCheckEnabled( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.LAUNCHER_UPDATE_CHECK_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Whether the {@code mmcl://} URI handler is enabled. When false,
     *  neither cold-start argv-delivered URIs nor runtime IPC-delivered
     *  URIs are dispatched. */
    public static synchronized boolean getUriHandlerEnabled() {
        return ConfigStore.getOrInitBoolean( ConfigConstants.URI_HANDLER_ENABLED_KEY,
                                             ConfigConstants.URI_HANDLER_ENABLED_DEFAULT );
    }

    /**
     * Sets whether the {@code mmcl://} URI handler is enabled and schedules a debounced disk flush. When disabled,
     * neither cold-start argv-delivered URIs nor runtime IPC-delivered URIs are dispatched.
     *
     * @param enable {@code true} to enable the URI handler, {@code false} to disable it
     */
    public static synchronized void setUriHandlerEnabled( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.URI_HANDLER_ENABLED_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Whether the user has completed or skipped the first-launch
     *  quick-start wizard. Defaults to false so the wizard fires once
     *  for existing installs that upgrade. */
    public static synchronized boolean getQuickStartCompleted() {
        return ConfigStore.getOrInitBoolean( ConfigConstants.QUICK_START_COMPLETED_KEY, false );
    }

    /**
     * Sets whether the first-launch quick-start wizard has been completed or skipped and schedules a debounced disk
     * flush.
     *
     * @param completed {@code true} once the quick-start wizard has been completed or skipped
     */
    public static synchronized void setQuickStartCompleted( boolean completed ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.QUICK_START_COMPLETED_KEY, completed );
        ConfigStore.scheduleWrite();
    }

    /** Whether downloads should throttle while running on battery
     *  power. Dormant on desktops where the battery probe always
     *  returns false. */
    public static synchronized boolean getBatteryThrottleEnable() {
        return ConfigStore.getOrInitBoolean( ConfigConstants.BATTERY_THROTTLE_ENABLE_KEY,
                                             ConfigConstants.BATTERY_THROTTLE_ENABLE_DEFAULT );
    }

    /**
     * Sets whether downloads throttle while running on battery power and schedules a debounced disk flush.
     *
     * @param enable {@code true} to throttle downloads on battery, {@code false} to download at full speed
     */
    public static synchronized void setBatteryThrottleEnable( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.BATTERY_THROTTLE_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // LWJGL ARM64 patch
    // ====================================================================

    /** Whether LWJGL ARM64 native patching is enabled. When on, the
     *  launcher replaces LWJGL2 x86_64 native libraries with
     *  ARM64-compatible builds for older Minecraft versions on
     *  ARM64 macOS / Linux. */
    public static synchronized boolean getLwjglArmPatchEnable() {
        return ConfigStore.getOrInitBoolean( ConfigConstants.LWJGL_ARM_PATCH_ENABLE_KEY,
                                             ConfigConstants.LWJGL_ARM_PATCH_ENABLE_DEFAULT );
    }

    /**
     * Sets whether LWJGL ARM64 native patching is enabled and schedules a debounced disk flush. When on, the launcher
     * substitutes ARM64-compatible LWJGL2 natives for older Minecraft versions on ARM64 macOS / Linux.
     *
     * @param enable {@code true} to enable LWJGL ARM64 patching, {@code false} to disable it
     */
    public static synchronized void setLwjglArmPatchEnable( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.LWJGL_ARM_PATCH_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // Window bounds
    //
    // Each component is read independently — they default to NaN when
    // never persisted so the calling window-restore logic can fall back
    // to "open at the centered default" cleanly. The set method writes
    // all five components in one shot so a debounced flush captures
    // them atomically.
    // ====================================================================

    /**
     * Returns the last-persisted launcher window X position.
     *
     * @return the saved X coordinate, or {@link Double#NaN} when none has ever been persisted (caller should fall
     *         back to a centered default)
     */
    public static synchronized double getWindowX() {
        return ConfigStore.getDouble( ConfigConstants.WINDOW_X_KEY, Double.NaN );
    }

    /**
     * Returns the last-persisted launcher window Y position.
     *
     * @return the saved Y coordinate, or {@link Double#NaN} when none has ever been persisted (caller should fall
     *         back to a centered default)
     */
    public static synchronized double getWindowY() {
        return ConfigStore.getDouble( ConfigConstants.WINDOW_Y_KEY, Double.NaN );
    }

    /**
     * Returns the last-persisted launcher window width.
     *
     * @return the saved width, or {@link Double#NaN} when none has ever been persisted (caller should fall back to a
     *         default size)
     */
    public static synchronized double getWindowWidth() {
        return ConfigStore.getDouble( ConfigConstants.WINDOW_WIDTH_KEY, Double.NaN );
    }

    /**
     * Returns the last-persisted launcher window height.
     *
     * @return the saved height, or {@link Double#NaN} when none has ever been persisted (caller should fall back to a
     *         default size)
     */
    public static synchronized double getWindowHeight() {
        return ConfigStore.getDouble( ConfigConstants.WINDOW_HEIGHT_KEY, Double.NaN );
    }

    /**
     * Returns whether the launcher window was maximized when last persisted.
     *
     * @return the saved maximized state, or {@link ConfigConstants#WINDOW_MAXIMIZED_DEFAULT} when none has been
     *         persisted
     */
    public static synchronized boolean getWindowMaximized() {
        return ConfigStore.getBoolean( ConfigConstants.WINDOW_MAXIMIZED_KEY,
                                       ConfigConstants.WINDOW_MAXIMIZED_DEFAULT );
    }

    /**
     * Persists all five window-bounds components in a single shot so a debounced flush captures them atomically.
     *
     * @param x         the window X position to persist
     * @param y         the window Y position to persist
     * @param width     the window width to persist
     * @param height    the window height to persist
     * @param maximized the maximized state to persist
     */
    public static synchronized void setWindowBounds( double x, double y, double width, double height,
                                                       boolean maximized ) {
        JsonObject json = ConfigStore.ensureLoaded();
        json.addProperty( ConfigConstants.WINDOW_X_KEY, x );
        json.addProperty( ConfigConstants.WINDOW_Y_KEY, y );
        json.addProperty( ConfigConstants.WINDOW_WIDTH_KEY, width );
        json.addProperty( ConfigConstants.WINDOW_HEIGHT_KEY, height );
        json.addProperty( ConfigConstants.WINDOW_MAXIMIZED_KEY, maximized );
        ConfigStore.scheduleWrite();
    }
}

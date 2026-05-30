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

import com.google.gson.JsonElement;
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
    private AppConfig() { /* static-only */ }

    // ====================================================================
    // Theme + locale
    // ====================================================================

    /** Active launcher theme identifier. */
    public static synchronized String getTheme() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.THEME_KEY ) ) {
            json.addProperty( ConfigConstants.THEME_KEY, ConfigConstants.THEME_DEFAULT );
            ConfigStore.scheduleWrite();
        }
        return json.get( ConfigConstants.THEME_KEY ).getAsString();
    }

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
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.LOCALE_OVERRIDE_KEY ) ) {
            return "";
        }
        JsonElement el = json.get( ConfigConstants.LOCALE_OVERRIDE_KEY );
        if ( el == null || el.isJsonNull() ) return "";
        return el.getAsString();
    }

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
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.LOG_DEBUG_ENABLE_KEY ) ) {
            json.addProperty( ConfigConstants.LOG_DEBUG_ENABLE_KEY,
                              ConfigConstants.LOG_DEBUG_ENABLE_DEFAULT );
        }
        return LauncherConstants.LAUNCHER_IS_DEV
                || json.get( ConfigConstants.LOG_DEBUG_ENABLE_KEY ).getAsBoolean();
    }

    public static synchronized void setDebugLogging( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.LOG_DEBUG_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Whether enhanced (more verbose) logging is enabled. */
    public static synchronized boolean getEnhancedLogging() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.LOG_ENHANCED_ENABLE_KEY ) ) {
            json.addProperty( ConfigConstants.LOG_ENHANCED_ENABLE_KEY, true );
            ConfigStore.scheduleWrite();
        }
        return json.get( ConfigConstants.LOG_ENHANCED_ENABLE_KEY ).getAsBoolean();
    }

    public static synchronized void setEnhancedLogging( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.LOG_ENHANCED_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // Discord integration
    // ====================================================================

    /** Whether Discord rich-presence is enabled. */
    public static synchronized boolean getDiscordRpcEnable() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.DISCORD_RPC_ENABLE_KEY ) ) {
            json.addProperty( ConfigConstants.DISCORD_RPC_ENABLE_KEY,
                              ConfigConstants.DISCORD_RPC_ENABLE_DEFAULT );
        }
        // The historical "force-disable in dev" path was removed
        // intentionally — dev builds need to validate the RPC + invite
        // surface end-to-end. The user's choice is now honoured in all
        // environments; the matching dev-mode setDisable on the
        // Settings checkbox is gone too.
        return json.get( ConfigConstants.DISCORD_RPC_ENABLE_KEY ).getAsBoolean();
    }

    public static synchronized void setDiscordRpcEnable( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.DISCORD_RPC_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Whether Discord "Join Game" invites are enabled. Independent
     *  of {@link #getDiscordRpcEnable} so a user can have presence on
     *  but invites off (e.g. doesn't want strangers joining). */
    public static synchronized boolean getDiscordInvitesEnable() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.DISCORD_INVITES_ENABLE_KEY ) ) {
            json.addProperty( ConfigConstants.DISCORD_INVITES_ENABLE_KEY,
                              ConfigConstants.DISCORD_INVITES_ENABLE_DEFAULT );
        }
        return json.get( ConfigConstants.DISCORD_INVITES_ENABLE_KEY ).getAsBoolean();
    }

    public static synchronized void setDiscordInvitesEnable( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.DISCORD_INVITES_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // UI behavior
    // ====================================================================

    /** Whether launcher windows can be resized. */
    public static synchronized boolean getResizableWindows() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY ) ) {
            json.addProperty( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY,
                              ConfigConstants.RESIZE_WINDOWS_ENABLE_DEFAULT );
        }
        return json.get( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY ).getAsBoolean();
    }

    public static synchronized void setResizableWindows( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Whether the in-game console window is enabled when launching
     *  a modpack. */
    public static synchronized boolean getInGameConsoleEnable() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.INGAME_CONSOLE_ENABLE_KEY ) ) {
            json.addProperty( ConfigConstants.INGAME_CONSOLE_ENABLE_KEY,
                              ConfigConstants.INGAME_CONSOLE_ENABLE_DEFAULT );
        }
        return json.get( ConfigConstants.INGAME_CONSOLE_ENABLE_KEY ).getAsBoolean();
    }

    public static synchronized void setInGameConsoleEnable( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.INGAME_CONSOLE_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Visible-log-line cap for the in-game console TextArea. 0 means
     *  unlimited (no trimming). Read on each line-batch flush so the
     *  user can change the setting mid-session without restarting. */
    public static synchronized int getConsoleLogMaxLines() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.CONSOLE_LOG_MAX_LINES_KEY ) ) {
            return ConfigConstants.CONSOLE_LOG_MAX_LINES_DEFAULT;
        }
        return json.get( ConfigConstants.CONSOLE_LOG_MAX_LINES_KEY ).getAsInt();
    }

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
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.LAUNCHER_UPDATE_CHECK_KEY ) ) {
            json.addProperty( ConfigConstants.LAUNCHER_UPDATE_CHECK_KEY, true );
            ConfigStore.scheduleWrite();
        }
        return json.get( ConfigConstants.LAUNCHER_UPDATE_CHECK_KEY ).getAsBoolean();
    }

    public static synchronized void setLauncherUpdateCheckEnabled( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.LAUNCHER_UPDATE_CHECK_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Whether the {@code mmcl://} URI handler is enabled. When false,
     *  neither cold-start argv-delivered URIs nor runtime IPC-delivered
     *  URIs are dispatched. */
    public static synchronized boolean getUriHandlerEnabled() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.URI_HANDLER_ENABLED_KEY ) ) {
            json.addProperty( ConfigConstants.URI_HANDLER_ENABLED_KEY,
                              ConfigConstants.URI_HANDLER_ENABLED_DEFAULT );
            ConfigStore.scheduleWrite();
        }
        return json.get( ConfigConstants.URI_HANDLER_ENABLED_KEY ).getAsBoolean();
    }

    public static synchronized void setUriHandlerEnabled( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.URI_HANDLER_ENABLED_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Whether the user has completed or skipped the first-launch
     *  quick-start wizard. Defaults to false so the wizard fires once
     *  for existing installs that upgrade. */
    public static synchronized boolean getQuickStartCompleted() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.QUICK_START_COMPLETED_KEY ) ) {
            json.addProperty( ConfigConstants.QUICK_START_COMPLETED_KEY, false );
            ConfigStore.scheduleWrite();
        }
        return json.get( ConfigConstants.QUICK_START_COMPLETED_KEY ).getAsBoolean();
    }

    public static synchronized void setQuickStartCompleted( boolean completed ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.QUICK_START_COMPLETED_KEY, completed );
        ConfigStore.scheduleWrite();
    }

    /** Whether downloads should throttle while running on battery
     *  power. Dormant on desktops where the battery probe always
     *  returns false. */
    public static synchronized boolean getBatteryThrottleEnable() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.BATTERY_THROTTLE_ENABLE_KEY ) ) {
            json.addProperty( ConfigConstants.BATTERY_THROTTLE_ENABLE_KEY,
                              ConfigConstants.BATTERY_THROTTLE_ENABLE_DEFAULT );
        }
        return json.get( ConfigConstants.BATTERY_THROTTLE_ENABLE_KEY ).getAsBoolean();
    }

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
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.LWJGL_ARM_PATCH_ENABLE_KEY ) ) {
            json.addProperty( ConfigConstants.LWJGL_ARM_PATCH_ENABLE_KEY,
                              ConfigConstants.LWJGL_ARM_PATCH_ENABLE_DEFAULT );
        }
        return json.get( ConfigConstants.LWJGL_ARM_PATCH_ENABLE_KEY ).getAsBoolean();
    }

    public static synchronized void setLwjglArmPatchEnable( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.LWJGL_ARM_PATCH_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Whether the experimental Windows custom title bar (frameless Window Controls Overlay)
     *  is enabled. Windows-only, OFF by default, applied at window creation so a change needs
     *  a restart. */
    public static synchronized boolean getWindowsCustomChromeEnabled() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.WINDOWS_CUSTOM_CHROME_ENABLE_KEY ) ) {
            json.addProperty( ConfigConstants.WINDOWS_CUSTOM_CHROME_ENABLE_KEY,
                              ConfigConstants.WINDOWS_CUSTOM_CHROME_ENABLE_DEFAULT );
        }
        return json.get( ConfigConstants.WINDOWS_CUSTOM_CHROME_ENABLE_KEY ).getAsBoolean();
    }

    public static synchronized void setWindowsCustomChromeEnabled( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.WINDOWS_CUSTOM_CHROME_ENABLE_KEY, enable );
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

    public static synchronized double getWindowX() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.WINDOW_X_KEY ) ) return Double.NaN;
        return json.get( ConfigConstants.WINDOW_X_KEY ).getAsDouble();
    }

    public static synchronized double getWindowY() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.WINDOW_Y_KEY ) ) return Double.NaN;
        return json.get( ConfigConstants.WINDOW_Y_KEY ).getAsDouble();
    }

    public static synchronized double getWindowWidth() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.WINDOW_WIDTH_KEY ) ) return Double.NaN;
        return json.get( ConfigConstants.WINDOW_WIDTH_KEY ).getAsDouble();
    }

    public static synchronized double getWindowHeight() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.WINDOW_HEIGHT_KEY ) ) return Double.NaN;
        return json.get( ConfigConstants.WINDOW_HEIGHT_KEY ).getAsDouble();
    }

    public static synchronized boolean getWindowMaximized() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.WINDOW_MAXIMIZED_KEY ) ) {
            return ConfigConstants.WINDOW_MAXIMIZED_DEFAULT;
        }
        return json.get( ConfigConstants.WINDOW_MAXIMIZED_KEY ).getAsBoolean();
    }

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

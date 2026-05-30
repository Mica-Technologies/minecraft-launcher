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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;

import java.io.File;
import java.util.List;

/**
 * Thin facade over the six domain-named config slices. Historical
 * 1700+ line god class; now a delegating front-end so existing call
 * sites keep working through the {@code ConfigManager.getX} /
 * {@code setX} API while the actual storage + validation lives with
 * each domain.
 *
 * <h3>Slices (A6-deep split)</h3>
 * <ul>
 *   <li>{@link RuntimeConfig} — RAM (min/max), custom JVM args.</li>
 *   <li>{@link ModPackConfig} — installed packs, vanilla versions,
 *       last-selected, per-pack verify/scan overrides,
 *       show-pack-backgrounds toggle.</li>
 *   <li>{@link AuthTokenStore} — machine-encrypted third-party API
 *       tokens (currently the CurseForge key).</li>
 *   <li>{@link RgbConfig} — master enable, backend selection,
 *       per-backend flags, in-game effect toggles.</li>
 *   <li>{@link NetworkConfig} — proxy enable / host / port / type.</li>
 *   <li>{@link AppConfig} — theme + locale, logging toggles, Discord
 *       integration, UI behavior, app-lifecycle prefs, LWJGL ARM
 *       patch, window bounds.</li>
 * </ul>
 *
 * <p>{@link ConfigStore} owns the underlying {@link JsonObject} +
 * debounced disk-flush pipeline; every slice reads/writes through it
 * so a burst of setter calls coalesces into one disk write.</p>
 *
 * @author Mica Technologies
 * @version 3.1
 * @since 1.0
 */
public class ConfigManager
{
    /** Cached reference to the shared {@link JsonObject} owned by
     *  {@link ConfigStore}. Populated lazily on first read and reused
     *  for the few code paths in this file that still touch the JSON
     *  directly (migration scaffolding + import/export). New per-key
     *  getters/setters live in their domain slices and read through
     *  {@link ConfigStore#ensureLoaded()} on every call. */
    private static JsonObject configObject = null;

    // ====================================================================
    // RuntimeConfig delegates — RAM + JVM args
    // ====================================================================

    public synchronized static long getMinRam() {
        return RuntimeConfig.getMinRam();
    }

    public synchronized static void setMinRam( long minRam ) {
        RuntimeConfig.setMinRam( minRam );
    }

    public synchronized static long getMaxRam() {
        return RuntimeConfig.getMaxRam();
    }

    public synchronized static double getMaxRamInGb() {
        return RuntimeConfig.getMaxRamInGb();
    }

    public synchronized static void setMaxRam( long maxRam ) {
        RuntimeConfig.setMaxRam( maxRam );
    }

    public synchronized static String getCustomJvmArgs() {
        return RuntimeConfig.getCustomJvmArgs();
    }

    // -- Backup-before-update policy (delegates to ModPackConfig) ----------

    public synchronized static boolean getAutoBackupBeforeUpdate() {
        return ModPackConfig.getAutoBackupBeforeUpdate();
    }

    public synchronized static void setAutoBackupBeforeUpdate( boolean enable ) {
        ModPackConfig.setAutoBackupBeforeUpdate( enable );
    }

    public synchronized static int getMaxBackupsPerPack() {
        return ModPackConfig.getMaxBackupsPerPack();
    }

    public synchronized static void setMaxBackupsPerPack( int max ) {
        ModPackConfig.setMaxBackupsPerPack( max );
    }

    public synchronized static int getMaxBackupAgeDays() {
        return ModPackConfig.getMaxBackupAgeDays();
    }

    public synchronized static void setMaxBackupAgeDays( int days ) {
        ModPackConfig.setMaxBackupAgeDays( days );
    }

    public synchronized static boolean getBackupIncludeSaves() {
        return ModPackConfig.getBackupIncludeSaves();
    }

    public synchronized static void setBackupIncludeSaves( boolean include ) {
        ModPackConfig.setBackupIncludeSaves( include );
    }

    /**
     * Validates the custom JVM arguments string against argument-injection
     * patterns before persisting. Embedded newlines, NULs, control chars,
     * or {@code ${...}} sequences are rejected outright so:
     * <ul>
     *   <li>A future code path that copies JVM args from an
     *       attacker-controlled source (modpack JSON field, URI parameter,
     *       etc.) cannot inject extra arguments through this choke
     *       point.</li>
     *   <li>{@code ${auth_access_token}} or similar placeholders cannot
     *       be smuggled into custom args to leak the live token onto
     *       the command line in extra positions.</li>
     * </ul>
     *
     * <p>The security validation lives here (in the facade) rather than
     * in {@link RuntimeConfig} so the reject-on-injection contract is
     * part of the public API consumers call. {@link RuntimeConfig} is
     * for trusted internal use and skips the validation.</p>
     *
     * @throws IllegalArgumentException if the string contains rejected
     *                                  metacharacters
     */
    public synchronized static void setCustomJvmArgs( String jvmArgs ) {
        RuntimeConfig.setCustomJvmArgs( validateCustomJvmArgs( jvmArgs ) );
    }

    private static String validateCustomJvmArgs( String jvmArgs ) {
        if ( jvmArgs == null ) {
            return "";
        }
        for ( int i = 0; i < jvmArgs.length(); i++ ) {
            char c = jvmArgs.charAt( i );
            // Control chars and DEL (0x7F). Newlines / TAB included so a
            // multi-line paste can't smuggle command-line args separated
            // by a line break — splitCommandLine splits on
            // Character.isWhitespace which includes them.
            if ( c < 0x20 || c == 0x7F ) {
                throw new IllegalArgumentException(
                        "Custom JVM args contain a control character at position " + i );
            }
        }
        // Reject ${...} placeholder syntax — the launch pipeline runs
        // templating after appending custom args, so a literal
        // "${auth_access_token}" here would expand.
        if ( jvmArgs.indexOf( "${" ) >= 0 ) {
            throw new IllegalArgumentException(
                    "Custom JVM args may not contain '${...}' placeholder syntax." );
        }
        return jvmArgs;
    }

    // ====================================================================
    // ModPackConfig delegates
    // ====================================================================

    public synchronized static String getLastModPackSelected() {
        return ModPackConfig.getLastModPackSelected();
    }

    public synchronized static void setLastModPackSelected( String lastModPackSelected ) {
        ModPackConfig.setLastModPackSelected( lastModPackSelected );
    }

    public synchronized static List< String > getInstalledModPacks() {
        return ModPackConfig.getInstalledModPacks();
    }

    public synchronized static void setInstalledModPacks( List< String > installedModPacks ) {
        ModPackConfig.setInstalledModPacks( installedModPacks );
    }

    public synchronized static List< String > getInstalledVanillaVersions() {
        return ModPackConfig.getInstalledVanillaVersions();
    }

    public synchronized static void setInstalledVanillaVersions( List< String > versions ) {
        ModPackConfig.setInstalledVanillaVersions( versions );
    }

    public synchronized static boolean getAlwaysVerifyOnLaunch( String packUrl ) {
        return ModPackConfig.getAlwaysVerifyOnLaunch( packUrl );
    }

    public synchronized static void setAlwaysVerifyOnLaunch( String packUrl, boolean value ) {
        ModPackConfig.setAlwaysVerifyOnLaunch( packUrl, value );
    }

    public synchronized static com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency
            getDefaultScanFrequency() {
        return ModPackConfig.getDefaultScanFrequency();
    }

    public synchronized static void setDefaultScanFrequency(
            com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency frequency ) {
        ModPackConfig.setDefaultScanFrequency( frequency );
    }

    public synchronized static com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency
            getScanFrequencyForPack( String packUrl ) {
        return ModPackConfig.getScanFrequencyForPack( packUrl );
    }

    public synchronized static void setScanFrequencyForPack(
            String packUrl,
            com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency frequency ) {
        ModPackConfig.setScanFrequencyForPack( packUrl, frequency );
    }

    public synchronized static com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency
            effectiveScanFrequencyForPack( String packUrl ) {
        return ModPackConfig.effectiveScanFrequencyForPack( packUrl );
    }

    public synchronized static boolean getShowPackBackgrounds() {
        return ModPackConfig.getShowPackBackgrounds();
    }

    public synchronized static void setShowPackBackgrounds( boolean show ) {
        ModPackConfig.setShowPackBackgrounds( show );
    }

    // ====================================================================
    // AuthTokenStore delegates
    // ====================================================================

    public synchronized static String getCurseForgeApiKey() {
        return AuthTokenStore.getCurseForgeApiKey();
    }

    public synchronized static void setCurseForgeApiKey( String apiKey ) {
        AuthTokenStore.setCurseForgeApiKey( apiKey );
    }

    public synchronized static boolean hasCurseForgeApiKey() {
        return AuthTokenStore.hasCurseForgeApiKey();
    }

    // ====================================================================
    // RgbConfig delegates
    // ====================================================================

    public synchronized static boolean getRgbEnable()              { return RgbConfig.getRgbEnable(); }
    public synchronized static void    setRgbEnable( boolean v )   { RgbConfig.setRgbEnable( v ); }
    public synchronized static String  getRgbBackend()             { return RgbConfig.getRgbBackend(); }
    public synchronized static void    setRgbBackend( String v )   { RgbConfig.setRgbBackend( v ); }
    public synchronized static boolean getRgbUsePackColors()       { return RgbConfig.getRgbUsePackColors(); }
    public synchronized static void    setRgbUsePackColors( boolean v ) { RgbConfig.setRgbUsePackColors( v ); }
    public synchronized static boolean getRgbHighlightKeys()       { return RgbConfig.getRgbHighlightKeys(); }
    public synchronized static void    setRgbHighlightKeys( boolean v ) { RgbConfig.setRgbHighlightKeys( v ); }
    public synchronized static boolean getRgbEnableOpenRgb()       { return RgbConfig.getRgbEnableOpenRgb(); }
    public synchronized static void    setRgbEnableOpenRgb( boolean v ) { RgbConfig.setRgbEnableOpenRgb( v ); }
    public synchronized static boolean getRgbEnableChromaNative()  { return RgbConfig.getRgbEnableChromaNative(); }
    public synchronized static void    setRgbEnableChromaNative( boolean v ) { RgbConfig.setRgbEnableChromaNative( v ); }
    public synchronized static boolean getRgbEnableChromaRest()    { return RgbConfig.getRgbEnableChromaRest(); }
    public synchronized static void    setRgbEnableChromaRest( boolean v ) { RgbConfig.setRgbEnableChromaRest( v ); }
    public synchronized static boolean getRgbEnableWindowsDl()     { return RgbConfig.getRgbEnableWindowsDl(); }
    public synchronized static void    setRgbEnableWindowsDl( boolean v ) { RgbConfig.setRgbEnableWindowsDl( v ); }
    public synchronized static boolean getRgbEnableCorsair()       { return RgbConfig.getRgbEnableCorsair(); }
    public synchronized static void    setRgbEnableCorsair( boolean v ) { RgbConfig.setRgbEnableCorsair( v ); }
    public synchronized static boolean getRgbEnableAsusAura()      { return RgbConfig.getRgbEnableAsusAura(); }
    public synchronized static void    setRgbEnableAsusAura( boolean v ) { RgbConfig.setRgbEnableAsusAura( v ); }
    public synchronized static boolean getRgbMenuEffectEnable()    { return RgbConfig.getRgbMenuEffectEnable(); }
    public synchronized static void    setRgbMenuEffectEnable( boolean v ) { RgbConfig.setRgbMenuEffectEnable( v ); }
    public synchronized static String  getRgbEffectStyle()         { return RgbConfig.getRgbEffectStyle(); }
    public synchronized static void    setRgbEffectStyle( String v ) { RgbConfig.setRgbEffectStyle( v ); }

    // ====================================================================
    // NetworkConfig delegates — proxy
    // ====================================================================

    public synchronized static boolean getProxyEnable()         { return NetworkConfig.getProxyEnable(); }
    public synchronized static void    setProxyEnable( boolean v ) { NetworkConfig.setProxyEnable( v ); }
    public synchronized static String  getProxyHost()           { return NetworkConfig.getProxyHost(); }
    public synchronized static void    setProxyHost( String v ) { NetworkConfig.setProxyHost( v ); }
    public synchronized static int     getProxyPort()           { return NetworkConfig.getProxyPort(); }
    public synchronized static void    setProxyPort( int v )    { NetworkConfig.setProxyPort( v ); }
    public synchronized static String  getProxyType()           { return NetworkConfig.getProxyType(); }
    public synchronized static void    setProxyType( String v ) { NetworkConfig.setProxyType( v ); }

    // ====================================================================
    // AppConfig delegates — theme, locale, logging, Discord, UI behavior,
    // app lifecycle, LWJGL ARM, window bounds
    // ====================================================================

    public synchronized static String  getTheme()                       { return AppConfig.getTheme(); }
    public synchronized static void    setTheme( String v )             { AppConfig.setTheme( v ); }
    public synchronized static String  getLocaleOverride()              { return AppConfig.getLocaleOverride(); }
    public synchronized static void    setLocaleOverride( String v )    { AppConfig.setLocaleOverride( v ); }

    public synchronized static boolean getDebugLogging()                { return AppConfig.getDebugLogging(); }
    public synchronized static void    setDebugLogging( boolean v )     { AppConfig.setDebugLogging( v ); }
    public synchronized static boolean getEnhancedLogging()             { return AppConfig.getEnhancedLogging(); }
    public synchronized static void    setEnhancedLogging( boolean v )  { AppConfig.setEnhancedLogging( v ); }

    public synchronized static boolean getDiscordRpcEnable()            { return AppConfig.getDiscordRpcEnable(); }
    public synchronized static void    setDiscordRpcEnable( boolean v ) { AppConfig.setDiscordRpcEnable( v ); }
    public synchronized static boolean getDiscordInvitesEnable()        { return AppConfig.getDiscordInvitesEnable(); }
    public synchronized static void    setDiscordInvitesEnable( boolean v ) { AppConfig.setDiscordInvitesEnable( v ); }

    public synchronized static boolean getResizableWindows()            { return AppConfig.getResizableWindows(); }
    public synchronized static void    setResizableWindows( boolean v ) { AppConfig.setResizableWindows( v ); }
    public synchronized static boolean getInGameConsoleEnable()         { return AppConfig.getInGameConsoleEnable(); }
    public synchronized static void    setInGameConsoleEnable( boolean v ) { AppConfig.setInGameConsoleEnable( v ); }
    public synchronized static int     getConsoleLogMaxLines()          { return AppConfig.getConsoleLogMaxLines(); }
    public synchronized static void    setConsoleLogMaxLines( int v )   { AppConfig.setConsoleLogMaxLines( v ); }

    public synchronized static boolean getLauncherUpdateCheckEnabled()  { return AppConfig.getLauncherUpdateCheckEnabled(); }
    public synchronized static void    setLauncherUpdateCheckEnabled( boolean v ) { AppConfig.setLauncherUpdateCheckEnabled( v ); }
    public synchronized static boolean getUriHandlerEnabled()           { return AppConfig.getUriHandlerEnabled(); }
    public synchronized static void    setUriHandlerEnabled( boolean v ) { AppConfig.setUriHandlerEnabled( v ); }
    public synchronized static boolean getQuickStartCompleted()         { return AppConfig.getQuickStartCompleted(); }
    public synchronized static void    setQuickStartCompleted( boolean v ) { AppConfig.setQuickStartCompleted( v ); }
    public synchronized static boolean getBatteryThrottleEnable()       { return AppConfig.getBatteryThrottleEnable(); }
    public synchronized static void    setBatteryThrottleEnable( boolean v ) { AppConfig.setBatteryThrottleEnable( v ); }

    public synchronized static boolean getLwjglArmPatchEnable()         { return AppConfig.getLwjglArmPatchEnable(); }
    public synchronized static void    setLwjglArmPatchEnable( boolean v ) { AppConfig.setLwjglArmPatchEnable( v ); }

    public synchronized static boolean getWindowsCustomChromeEnabled()  { return AppConfig.getWindowsCustomChromeEnabled(); }
    public synchronized static void    setWindowsCustomChromeEnabled( boolean v ) { AppConfig.setWindowsCustomChromeEnabled( v ); }

    public synchronized static double  getWindowX()                     { return AppConfig.getWindowX(); }
    public synchronized static double  getWindowY()                     { return AppConfig.getWindowY(); }
    public synchronized static double  getWindowWidth()                 { return AppConfig.getWindowWidth(); }
    public synchronized static double  getWindowHeight()                { return AppConfig.getWindowHeight(); }
    public synchronized static boolean getWindowMaximized()             { return AppConfig.getWindowMaximized(); }
    public synchronized static void    setWindowBounds( double x, double y, double width, double height,
                                                          boolean maximized ) {
        AppConfig.setWindowBounds( x, y, width, height, maximized );
    }

    // ====================================================================
    // Migration / first-run scaffolding (called back from ConfigStore)
    // ====================================================================

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

    /**
     * Migrates the configuration to the current schema version by
     * ensuring all expected keys have default values. Called after
     * loading the config from disk. No-op when stored version is
     * already current.
     *
     * @since 3.0
     */
    static synchronized void migrateConfigIfNeeded() {
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

        // -----------------------------------------------------------------
        // Version-bracketed corrections — apply BEFORE the touch-every-key
        // pass below, so a corrected value lands in the JSON before its
        // getter would (a) read the stale on-disk value or (b) hit the
        // missing-key branch and write the default.
        // -----------------------------------------------------------------

        if ( storedVersion < 5 ) {
            // v4 → v5: RESIZE_WINDOWS_ENABLE_DEFAULT flipped from false to true
            // in commit d4c65e7 (2026-05-13). Existing installs were preserved
            // at their persisted value at the time, but the intent was always
            // "resize on by default everywhere" — the old default was a
            // historical accident from a long-since-fixed JavaFX layout bug.
            // Flip any lingering explicit `false` to `true` so the launcher
            // matches the stated default everywhere; users who deliberately
            // want it off can re-disable in Settings (one click).
            if ( configObject.has( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY )
                    && !configObject.get( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY ).getAsBoolean() ) {
                configObject.addProperty( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY, true );
                Logger.logStd( "Config migration v4→v5: flipped resizableWindows to true "
                                       + "(it was persisted as false from a pre-2026-05-13 install)." );
            }
        }

        if ( storedVersion < 6 ) {
            // v5 → v6: Mica-hosted modpack manifests moved from /manifest.json
            // to /manifest.mmcjson on 2026-05-21 to match the launcher's
            // mmcjson file association. Existing configs still carry pre-rename
            // URLs in the installed-packs list (and lastModPack when the user's
            // last-played pack is Mica-hosted); without rewriting them here
            // they 404 against the CDN once the old blobs are retired and the
            // pack renders as a failed-load card on every cold start.
            //
            // Host gate: only URLs under the official Mica blob are touched.
            // Third-party manifest.json URLs (custom hosts, file: imports) are
            // left alone — only we know our blob layout renamed, and rewriting
            // a foreign URL to a non-existent .mmcjson would itself break the
            // pack.
            //
            // Per-manifest cache files under modpacks/manifest_cache/ are keyed
            // by sha256(url) so the rename strands them as orphans; that's
            // intentional — they self-heal on the next online launch when the
            // .mmcjson URL misses the cache and a fresh fetch repopulates
            // everything. Costs one extra GET per migrated pack on first
            // post-upgrade launch, which is cheap. No reason to migrate
            // cache filenames in lockstep.
            final String hostPrefix = "https://micauseaststorage.blob.core.windows.net/mc-launcher-api/";
            final String oldSuffix = ".json";
            final String newSuffix = ".mmcjson";
            int migrated = 0;

            if ( configObject.has( ConfigConstants.MOD_PACKS_INSTALLED_KEY )
                    && configObject.get( ConfigConstants.MOD_PACKS_INSTALLED_KEY ).isJsonArray() ) {
                JsonArray oldArr = configObject.getAsJsonArray( ConfigConstants.MOD_PACKS_INSTALLED_KEY );
                JsonArray newArr = new JsonArray( oldArr.size() );
                for ( JsonElement el : oldArr ) {
                    if ( el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ) {
                        String url = el.getAsString();
                        if ( url.startsWith( hostPrefix ) && url.endsWith( oldSuffix ) ) {
                            newArr.add( new JsonPrimitive(
                                    url.substring( 0, url.length() - oldSuffix.length() ) + newSuffix ) );
                            migrated++;
                            continue;
                        }
                    }
                    newArr.add( el );
                }
                configObject.add( ConfigConstants.MOD_PACKS_INSTALLED_KEY, newArr );
            }

            if ( configObject.has( ConfigConstants.LAST_MP_KEY )
                    && configObject.get( ConfigConstants.LAST_MP_KEY ).isJsonPrimitive() ) {
                String last = configObject.get( ConfigConstants.LAST_MP_KEY ).getAsString();
                if ( last.startsWith( hostPrefix ) && last.endsWith( oldSuffix ) ) {
                    String rewritten = last.substring( 0, last.length() - oldSuffix.length() ) + newSuffix;
                    configObject.addProperty( ConfigConstants.LAST_MP_KEY, rewritten );
                    migrated++;
                }
            }

            if ( migrated > 0 ) {
                Logger.logStd( "Config migration v5→v6: rewrote " + migrated
                                       + " Mica-hosted modpack URL(s) from .json to .mmcjson "
                                       + "(manifests moved to the new .mmcjson extension)." );
            }
        }

        // Touch every key so the default-write path fires for anything
        // a config from an older launcher version is missing.
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
        getWindowsCustomChromeEnabled();
        getDiscordInvitesEnable();

        // Stamp the current version and persist
        configObject.addProperty( ConfigConstants.CONFIG_VERSION_KEY, ConfigConstants.CONFIG_VERSION );
        writeConfigurationToDisk();
    }

    // ====================================================================
    // Import / export
    // ====================================================================

    /**
     * Exports the current launcher configuration to the specified file
     * as pretty-printed JSON.
     *
     * @return true if export succeeded
     * @since 3.0
     */
    public synchronized static boolean exportConfig( File destination ) {
        if ( configObject == null ) {
            configObject = ConfigStore.ensureLoaded();
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
     * Imports launcher configuration from the specified file, replacing
     * the current config. The imported config is migrated to the
     * current schema version after loading.
     *
     * @return true if import succeeded
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

    /** Schedules a debounced disk write via {@link ConfigStore}. */
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

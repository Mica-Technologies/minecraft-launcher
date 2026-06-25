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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.JvmArgsValidator;
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

    /**
     * Returns the configured minimum game JVM heap size in megabytes.
     *
     * @return the minimum RAM in megabytes
     * @see RuntimeConfig#getMinRam()
     * @since 1.0
     */
    public synchronized static long getMinRam() {
        return RuntimeConfig.getMinRam();
    }

    /**
     * Sets the minimum game JVM heap size in megabytes.
     *
     * @param minRam the minimum RAM in megabytes to persist
     * @see RuntimeConfig#setMinRam(long)
     * @since 1.0
     */
    public synchronized static void setMinRam( long minRam ) {
        RuntimeConfig.setMinRam( minRam );
    }

    /**
     * Returns the configured maximum game JVM heap size in megabytes.
     *
     * @return the maximum RAM in megabytes
     * @see RuntimeConfig#getMaxRam()
     * @since 1.0
     */
    public synchronized static long getMaxRam() {
        return RuntimeConfig.getMaxRam();
    }

    /**
     * Returns the configured maximum game JVM heap size in gigabytes.
     *
     * @return the maximum RAM in gigabytes
     * @see RuntimeConfig#getMaxRamInGb()
     * @since 1.0
     */
    public synchronized static double getMaxRamInGb() {
        return RuntimeConfig.getMaxRamInGb();
    }

    /**
     * Sets the maximum game JVM heap size in megabytes.
     *
     * @param maxRam the maximum RAM in megabytes to persist
     * @see RuntimeConfig#setMaxRam(long)
     * @since 1.0
     */
    public synchronized static void setMaxRam( long maxRam ) {
        RuntimeConfig.setMaxRam( maxRam );
    }

    /**
     * Returns the user-configured custom game JVM arguments, re-validating the stored value before returning it. A
     * value that fails {@link JvmArgsValidator#isClean(String)} (e.g. tampered in via a hand edit or sync-restored
     * file) is dropped in favour of {@link ConfigConstants#JVM_ARGS_VALUE_DEFAULT} rather than being tokenised into
     * the game JVM argv.
     *
     * @return the validated custom JVM argument string, or the default when the stored value fails validation
     * @see RuntimeConfig#getCustomJvmArgs()
     * @since 1.0
     */
    public synchronized static String getCustomJvmArgs() {
        // Validate on READ as well as write: setCustomJvmArgs rejects control
        // chars + ${...} placeholders, but the value can also reach the config
        // file out-of-band (hand edit, sync-restored tamper, same-user malware),
        // and from there it would be tokenised straight into the game JVM argv —
        // including -javaagent: or a literal ${auth_access_token} that the launch
        // templating would expand. A tampered value is dropped in favour of the
        // default rather than executed.
        String stored = RuntimeConfig.getCustomJvmArgs();
        if ( !JvmArgsValidator.isClean( stored ) ) {
            Logger.logWarningSilent( LocalizationManager.get( "log.configManager.jvmArgsValidationFailed" ) );
            return ConfigConstants.JVM_ARGS_VALUE_DEFAULT;
        }
        return stored;
    }

    // -- Backup-before-update policy (delegates to ModPackConfig) ----------

    /**
     * Returns whether the launcher automatically backs up a modpack before applying an update.
     *
     * @return {@code true} when auto-backup-before-update is enabled
     * @see ModPackConfig#getAutoBackupBeforeUpdate()
     * @since 1.0
     */
    public synchronized static boolean getAutoBackupBeforeUpdate() {
        return ModPackConfig.getAutoBackupBeforeUpdate();
    }

    /**
     * Sets whether the launcher automatically backs up a modpack before applying an update.
     *
     * @param enable {@code true} to enable auto-backup-before-update, {@code false} to disable it
     * @see ModPackConfig#setAutoBackupBeforeUpdate(boolean)
     * @since 1.0
     */
    public synchronized static void setAutoBackupBeforeUpdate( boolean enable ) {
        ModPackConfig.setAutoBackupBeforeUpdate( enable );
    }

    /**
     * Returns the maximum number of backups retained per modpack.
     *
     * @return the per-pack backup retention count
     * @see ModPackConfig#getMaxBackupsPerPack()
     * @since 1.0
     */
    public synchronized static int getMaxBackupsPerPack() {
        return ModPackConfig.getMaxBackupsPerPack();
    }

    /**
     * Sets the maximum number of backups retained per modpack.
     *
     * @param max the per-pack backup retention count to persist
     * @see ModPackConfig#setMaxBackupsPerPack(int)
     * @since 1.0
     */
    public synchronized static void setMaxBackupsPerPack( int max ) {
        ModPackConfig.setMaxBackupsPerPack( max );
    }

    /**
     * Returns the maximum age in days before a modpack backup is eligible for pruning.
     *
     * @return the maximum backup age in days
     * @see ModPackConfig#getMaxBackupAgeDays()
     * @since 1.0
     */
    public synchronized static int getMaxBackupAgeDays() {
        return ModPackConfig.getMaxBackupAgeDays();
    }

    /**
     * Sets the maximum age in days before a modpack backup is eligible for pruning.
     *
     * @param days the maximum backup age in days to persist
     * @see ModPackConfig#setMaxBackupAgeDays(int)
     * @since 1.0
     */
    public synchronized static void setMaxBackupAgeDays( int days ) {
        ModPackConfig.setMaxBackupAgeDays( days );
    }

    /**
     * Returns whether modpack backups include the world saves directory.
     *
     * @return {@code true} when saves are included in backups
     * @see ModPackConfig#getBackupIncludeSaves()
     * @since 1.0
     */
    public synchronized static boolean getBackupIncludeSaves() {
        return ModPackConfig.getBackupIncludeSaves();
    }

    /**
     * Sets whether modpack backups include the world saves directory.
     *
     * @param include {@code true} to include saves in backups, {@code false} to exclude them
     * @see ModPackConfig#setBackupIncludeSaves(boolean)
     * @since 1.0
     */
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
        RuntimeConfig.setCustomJvmArgs( JvmArgsValidator.requireClean( jvmArgs ) );
    }

    // ====================================================================
    // ModPackConfig delegates
    // ====================================================================

    /**
     * Returns the URL of the modpack the user last selected.
     *
     * @return the last-selected modpack URL
     * @see ModPackConfig#getLastModPackSelected()
     * @since 1.0
     */
    public synchronized static String getLastModPackSelected() {
        return ModPackConfig.getLastModPackSelected();
    }

    /**
     * Sets the URL of the modpack the user last selected.
     *
     * @param lastModPackSelected the modpack URL to persist as last-selected
     * @see ModPackConfig#setLastModPackSelected(String)
     * @since 1.0
     */
    public synchronized static void setLastModPackSelected( String lastModPackSelected ) {
        ModPackConfig.setLastModPackSelected( lastModPackSelected );
    }

    /**
     * Returns the list of installed modpack manifest URLs.
     *
     * @return the installed-modpack URL list
     * @see ModPackConfig#getInstalledModPacks()
     * @since 1.0
     */
    public synchronized static List< String > getInstalledModPacks() {
        return ModPackConfig.getInstalledModPacks();
    }

    /**
     * Sets the list of installed modpack manifest URLs.
     *
     * @param installedModPacks the installed-modpack URL list to persist
     * @see ModPackConfig#setInstalledModPacks(List)
     * @since 1.0
     */
    public synchronized static void setInstalledModPacks( List< String > installedModPacks ) {
        ModPackConfig.setInstalledModPacks( installedModPacks );
    }

    /**
     * Returns the list of installed vanilla (non-modpack) Minecraft versions.
     *
     * @return the installed vanilla version list
     * @see ModPackConfig#getInstalledVanillaVersions()
     * @since 1.0
     */
    public synchronized static List< String > getInstalledVanillaVersions() {
        return ModPackConfig.getInstalledVanillaVersions();
    }

    /**
     * Sets the list of installed vanilla (non-modpack) Minecraft versions.
     *
     * @param versions the installed vanilla version list to persist
     * @see ModPackConfig#setInstalledVanillaVersions(List)
     * @since 1.0
     */
    public synchronized static void setInstalledVanillaVersions( List< String > versions ) {
        ModPackConfig.setInstalledVanillaVersions( versions );
    }

    /**
     * Returns whether the given modpack is set to always re-verify its files on launch.
     *
     * @param packUrl the modpack manifest URL to query
     * @return {@code true} when always-verify-on-launch is enabled for the pack
     * @see ModPackConfig#getAlwaysVerifyOnLaunch(String)
     * @since 1.0
     */
    public synchronized static boolean getAlwaysVerifyOnLaunch( String packUrl ) {
        return ModPackConfig.getAlwaysVerifyOnLaunch( packUrl );
    }

    /**
     * Sets whether the given modpack always re-verifies its files on launch.
     *
     * @param packUrl the modpack manifest URL to configure
     * @param value   {@code true} to enable always-verify-on-launch for the pack, {@code false} to disable it
     * @see ModPackConfig#setAlwaysVerifyOnLaunch(String, boolean)
     * @since 1.0
     */
    public synchronized static void setAlwaysVerifyOnLaunch( String packUrl, boolean value ) {
        ModPackConfig.setAlwaysVerifyOnLaunch( packUrl, value );
    }

    /**
     * Returns the default security-scan frequency applied to packs without a per-pack override.
     *
     * @return the default scan frequency
     * @see ModPackConfig#getDefaultScanFrequency()
     * @since 1.0
     */
    public synchronized static com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency
            getDefaultScanFrequency() {
        return ModPackConfig.getDefaultScanFrequency();
    }

    /**
     * Sets the default security-scan frequency applied to packs without a per-pack override.
     *
     * @param frequency the default scan frequency to persist
     * @see ModPackConfig#setDefaultScanFrequency(com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency)
     * @since 1.0
     */
    public synchronized static void setDefaultScanFrequency(
            com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency frequency ) {
        ModPackConfig.setDefaultScanFrequency( frequency );
    }

    /**
     * Returns the per-pack security-scan frequency override for the given modpack, if any.
     *
     * @param packUrl the modpack manifest URL to query
     * @return the pack's scan-frequency override
     * @see ModPackConfig#getScanFrequencyForPack(String)
     * @since 1.0
     */
    public synchronized static com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency
            getScanFrequencyForPack( String packUrl ) {
        return ModPackConfig.getScanFrequencyForPack( packUrl );
    }

    /**
     * Sets the per-pack security-scan frequency override for the given modpack.
     *
     * @param packUrl   the modpack manifest URL to configure
     * @param frequency the scan-frequency override to persist for the pack
     * @see ModPackConfig#setScanFrequencyForPack(String, com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency)
     * @since 1.0
     */
    public synchronized static void setScanFrequencyForPack(
            String packUrl,
            com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency frequency ) {
        ModPackConfig.setScanFrequencyForPack( packUrl, frequency );
    }

    /**
     * Returns the effective security-scan frequency for the given modpack, resolving the per-pack override against the
     * default.
     *
     * @param packUrl the modpack manifest URL to resolve
     * @return the effective scan frequency for the pack
     * @see ModPackConfig#effectiveScanFrequencyForPack(String)
     * @since 1.0
     */
    public synchronized static com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency
            effectiveScanFrequencyForPack( String packUrl ) {
        return ModPackConfig.effectiveScanFrequencyForPack( packUrl );
    }

    /**
     * Returns whether modpack background images are shown in the UI.
     *
     * @return {@code true} when pack backgrounds are shown
     * @see ModPackConfig#getShowPackBackgrounds()
     * @since 1.0
     */
    public synchronized static boolean getShowPackBackgrounds() {
        return ModPackConfig.getShowPackBackgrounds();
    }

    /**
     * Sets whether modpack background images are shown in the UI.
     *
     * @param show {@code true} to show pack backgrounds, {@code false} to hide them
     * @see ModPackConfig#setShowPackBackgrounds(boolean)
     * @since 1.0
     */
    public synchronized static void setShowPackBackgrounds( boolean show ) {
        ModPackConfig.setShowPackBackgrounds( show );
    }

    /**
     * Returns the configured interval token for cycling multi-image pack showcases.
     *
     * @return the image-cycle interval setting
     * @see ModPackConfig#getImageCycleInterval()
     * @since 1.0
     */
    public synchronized static String getImageCycleInterval() {
        return ModPackConfig.getImageCycleInterval();
    }

    /**
     * Sets the interval token for cycling multi-image pack showcases.
     *
     * @param interval the image-cycle interval setting to persist
     * @see ModPackConfig#setImageCycleInterval(String)
     * @since 1.0
     */
    public synchronized static void setImageCycleInterval( String interval ) {
        ModPackConfig.setImageCycleInterval( interval );
    }

    /**
     * Returns whether multi-image pack showcases are cycled in shuffled order.
     *
     * @return {@code true} when image cycling is shuffled
     * @see ModPackConfig#getImageCycleShuffle()
     * @since 1.0
     */
    public synchronized static boolean getImageCycleShuffle() {
        return ModPackConfig.getImageCycleShuffle();
    }

    /**
     * Sets whether multi-image pack showcases are cycled in shuffled order.
     *
     * @param shuffle {@code true} to shuffle image cycling, {@code false} for sequential order
     * @see ModPackConfig#setImageCycleShuffle(boolean)
     * @since 1.0
     */
    public synchronized static void setImageCycleShuffle( boolean shuffle ) {
        ModPackConfig.setImageCycleShuffle( shuffle );
    }

    // ====================================================================
    // AuthTokenStore delegates
    // ====================================================================

    /**
     * Decrypts and returns the user-supplied CurseForge Core API key, or {@code null} when none is configured or the
     * stored envelope can't be decrypted on this machine.
     *
     * @return the decrypted CurseForge API key, or {@code null} when unavailable
     * @see AuthTokenStore#getCurseForgeApiKey()
     * @since 1.0
     */
    public synchronized static String getCurseForgeApiKey() {
        return AuthTokenStore.getCurseForgeApiKey();
    }

    /**
     * Encrypts and persists the user-supplied CurseForge API key. A {@code null} or blank key clears the stored value.
     *
     * @param apiKey the CurseForge API key to encrypt and persist, or {@code null}/blank to clear it
     * @see AuthTokenStore#setCurseForgeApiKey(String)
     * @since 1.0
     */
    public synchronized static void setCurseForgeApiKey( String apiKey ) {
        AuthTokenStore.setCurseForgeApiKey( apiKey );
    }

    /**
     * Returns whether an encrypted CurseForge API key is present on disk. Does not attempt decryption.
     *
     * @return {@code true} when a non-blank CurseForge key envelope is stored
     * @see AuthTokenStore#hasCurseForgeApiKey()
     * @since 1.0
     */
    public synchronized static boolean hasCurseForgeApiKey() {
        return AuthTokenStore.hasCurseForgeApiKey();
    }

    // ====================================================================
    // RgbConfig delegates
    // ====================================================================

    /** @return whether the RGB integration subsystem is enabled. @see RgbConfig#getRgbEnable() @since 1.0 */
    public synchronized static boolean getRgbEnable()              { return RgbConfig.getRgbEnable(); }
    /** Sets whether the RGB integration subsystem is enabled. @param v the new enable flag. @see RgbConfig#setRgbEnable(boolean) @since 1.0 */
    public synchronized static void    setRgbEnable( boolean v )   { RgbConfig.setRgbEnable( v ); }
    /** @return the selected RGB backend identifier. @see RgbConfig#getRgbBackend() @since 1.0 */
    public synchronized static String  getRgbBackend()             { return RgbConfig.getRgbBackend(); }
    /** Sets the selected RGB backend identifier. @param v the backend identifier. @see RgbConfig#setRgbBackend(String) @since 1.0 */
    public synchronized static void    setRgbBackend( String v )   { RgbConfig.setRgbBackend( v ); }
    /** @return whether RGB effects derive their colors from the active pack. @see RgbConfig#getRgbUsePackColors() @since 1.0 */
    public synchronized static boolean getRgbUsePackColors()       { return RgbConfig.getRgbUsePackColors(); }
    /** Sets whether RGB effects derive their colors from the active pack. @param v the new flag. @see RgbConfig#setRgbUsePackColors(boolean) @since 1.0 */
    public synchronized static void    setRgbUsePackColors( boolean v ) { RgbConfig.setRgbUsePackColors( v ); }
    /** @return whether RGB effects highlight relevant keyboard keys. @see RgbConfig#getRgbHighlightKeys() @since 1.0 */
    public synchronized static boolean getRgbHighlightKeys()       { return RgbConfig.getRgbHighlightKeys(); }
    /** Sets whether RGB effects highlight relevant keyboard keys. @param v the new flag. @see RgbConfig#setRgbHighlightKeys(boolean) @since 1.0 */
    public synchronized static void    setRgbHighlightKeys( boolean v ) { RgbConfig.setRgbHighlightKeys( v ); }
    /** @return whether the OpenRGB backend is enabled. @see RgbConfig#getRgbEnableOpenRgb() @since 1.0 */
    public synchronized static boolean getRgbEnableOpenRgb()       { return RgbConfig.getRgbEnableOpenRgb(); }
    /** Sets whether the OpenRGB backend is enabled. @param v the new flag. @see RgbConfig#setRgbEnableOpenRgb(boolean) @since 1.0 */
    public synchronized static void    setRgbEnableOpenRgb( boolean v ) { RgbConfig.setRgbEnableOpenRgb( v ); }
    /** @return whether the Razer Chroma native backend is enabled. @see RgbConfig#getRgbEnableChromaNative() @since 1.0 */
    public synchronized static boolean getRgbEnableChromaNative()  { return RgbConfig.getRgbEnableChromaNative(); }
    /** Sets whether the Razer Chroma native backend is enabled. @param v the new flag. @see RgbConfig#setRgbEnableChromaNative(boolean) @since 1.0 */
    public synchronized static void    setRgbEnableChromaNative( boolean v ) { RgbConfig.setRgbEnableChromaNative( v ); }
    /** @return whether the Razer Chroma REST backend is enabled. @see RgbConfig#getRgbEnableChromaRest() @since 1.0 */
    public synchronized static boolean getRgbEnableChromaRest()    { return RgbConfig.getRgbEnableChromaRest(); }
    /** Sets whether the Razer Chroma REST backend is enabled. @param v the new flag. @see RgbConfig#setRgbEnableChromaRest(boolean) @since 1.0 */
    public synchronized static void    setRgbEnableChromaRest( boolean v ) { RgbConfig.setRgbEnableChromaRest( v ); }
    /** @return whether the Windows Dynamic Lighting backend is enabled. @see RgbConfig#getRgbEnableWindowsDl() @since 1.0 */
    public synchronized static boolean getRgbEnableWindowsDl()     { return RgbConfig.getRgbEnableWindowsDl(); }
    /** Sets whether the Windows Dynamic Lighting backend is enabled. @param v the new flag. @see RgbConfig#setRgbEnableWindowsDl(boolean) @since 1.0 */
    public synchronized static void    setRgbEnableWindowsDl( boolean v ) { RgbConfig.setRgbEnableWindowsDl( v ); }
    /** @return whether the Corsair iCUE backend is enabled. @see RgbConfig#getRgbEnableCorsair() @since 1.0 */
    public synchronized static boolean getRgbEnableCorsair()       { return RgbConfig.getRgbEnableCorsair(); }
    /** Sets whether the Corsair iCUE backend is enabled. @param v the new flag. @see RgbConfig#setRgbEnableCorsair(boolean) @since 1.0 */
    public synchronized static void    setRgbEnableCorsair( boolean v ) { RgbConfig.setRgbEnableCorsair( v ); }
    /** @return whether the ASUS Aura backend is enabled. @see RgbConfig#getRgbEnableAsusAura() @since 1.0 */
    public synchronized static boolean getRgbEnableAsusAura()      { return RgbConfig.getRgbEnableAsusAura(); }
    /** Sets whether the ASUS Aura backend is enabled. @param v the new flag. @see RgbConfig#setRgbEnableAsusAura(boolean) @since 1.0 */
    public synchronized static void    setRgbEnableAsusAura( boolean v ) { RgbConfig.setRgbEnableAsusAura( v ); }
    /** @return whether the in-menu RGB effect is enabled. @see RgbConfig#getRgbMenuEffectEnable() @since 1.0 */
    public synchronized static boolean getRgbMenuEffectEnable()    { return RgbConfig.getRgbMenuEffectEnable(); }
    /** Sets whether the in-menu RGB effect is enabled. @param v the new flag. @see RgbConfig#setRgbMenuEffectEnable(boolean) @since 1.0 */
    public synchronized static void    setRgbMenuEffectEnable( boolean v ) { RgbConfig.setRgbMenuEffectEnable( v ); }
    /** @return the selected RGB effect style identifier. @see RgbConfig#getRgbEffectStyle() @since 1.0 */
    public synchronized static String  getRgbEffectStyle()         { return RgbConfig.getRgbEffectStyle(); }
    /** Sets the selected RGB effect style identifier. @param v the effect style identifier. @see RgbConfig#setRgbEffectStyle(String) @since 1.0 */
    public synchronized static void    setRgbEffectStyle( String v ) { RgbConfig.setRgbEffectStyle( v ); }

    // ====================================================================
    // NetworkConfig delegates — proxy
    // ====================================================================

    /** @return whether the launcher routes network traffic through a configured proxy. @see NetworkConfig#getProxyEnable() @since 1.0 */
    public synchronized static boolean getProxyEnable()         { return NetworkConfig.getProxyEnable(); }
    /** Sets whether the launcher routes network traffic through a configured proxy. @param v the new flag. @see NetworkConfig#setProxyEnable(boolean) @since 1.0 */
    public synchronized static void    setProxyEnable( boolean v ) { NetworkConfig.setProxyEnable( v ); }
    /** @return the configured proxy host. @see NetworkConfig#getProxyHost() @since 1.0 */
    public synchronized static String  getProxyHost()           { return NetworkConfig.getProxyHost(); }
    /** Sets the configured proxy host. @param v the proxy host. @see NetworkConfig#setProxyHost(String) @since 1.0 */
    public synchronized static void    setProxyHost( String v ) { NetworkConfig.setProxyHost( v ); }
    /** @return the configured proxy port. @see NetworkConfig#getProxyPort() @since 1.0 */
    public synchronized static int     getProxyPort()           { return NetworkConfig.getProxyPort(); }
    /** Sets the configured proxy port. @param v the proxy port. @see NetworkConfig#setProxyPort(int) @since 1.0 */
    public synchronized static void    setProxyPort( int v )    { NetworkConfig.setProxyPort( v ); }
    /** @return the configured proxy type (e.g. HTTP / SOCKS). @see NetworkConfig#getProxyType() @since 1.0 */
    public synchronized static String  getProxyType()           { return NetworkConfig.getProxyType(); }
    /** Sets the configured proxy type (e.g. HTTP / SOCKS). @param v the proxy type. @see NetworkConfig#setProxyType(String) @since 1.0 */
    public synchronized static void    setProxyType( String v ) { NetworkConfig.setProxyType( v ); }

    // ====================================================================
    // AppConfig delegates — theme, locale, logging, Discord, UI behavior,
    // app lifecycle, LWJGL ARM, window bounds
    // ====================================================================

    /** @return the active launcher theme identifier. @see AppConfig#getTheme() @since 1.0 */
    public synchronized static String  getTheme()                       { return AppConfig.getTheme(); }
    /** Sets the active launcher theme identifier. @param v the theme identifier. @see AppConfig#setTheme(String) @since 1.0 */
    public synchronized static void    setTheme( String v )             { AppConfig.setTheme( v ); }
    /** @return the user locale override (BCP-47 tag), or empty for OS detection. @see AppConfig#getLocaleOverride() @since 1.0 */
    public synchronized static String  getLocaleOverride()              { return AppConfig.getLocaleOverride(); }
    /** Sets the user locale override. @param v the BCP-47 tag, or {@code null}/empty to clear. @see AppConfig#setLocaleOverride(String) @since 1.0 */
    public synchronized static void    setLocaleOverride( String v )    { AppConfig.setLocaleOverride( v ); }

    /** @return whether debug logging is enabled (always true in dev builds). @see AppConfig#getDebugLogging() @since 1.0 */
    public synchronized static boolean getDebugLogging()                { return AppConfig.getDebugLogging(); }
    /** Sets the debug-logging preference. @param v the new flag. @see AppConfig#setDebugLogging(boolean) @since 1.0 */
    public synchronized static void    setDebugLogging( boolean v )     { AppConfig.setDebugLogging( v ); }
    /** @return whether enhanced (verbose) logging is enabled. @see AppConfig#getEnhancedLogging() @since 1.0 */
    public synchronized static boolean getEnhancedLogging()             { return AppConfig.getEnhancedLogging(); }
    /** Sets the enhanced-logging preference. @param v the new flag. @see AppConfig#setEnhancedLogging(boolean) @since 1.0 */
    public synchronized static void    setEnhancedLogging( boolean v )  { AppConfig.setEnhancedLogging( v ); }

    /** @return whether Discord rich-presence is enabled. @see AppConfig#getDiscordRpcEnable() @since 1.0 */
    public synchronized static boolean getDiscordRpcEnable()            { return AppConfig.getDiscordRpcEnable(); }
    /** Sets whether Discord rich-presence is enabled. @param v the new flag. @see AppConfig#setDiscordRpcEnable(boolean) @since 1.0 */
    public synchronized static void    setDiscordRpcEnable( boolean v ) { AppConfig.setDiscordRpcEnable( v ); }
    /** @return whether Discord "Join Game" invites are enabled. @see AppConfig#getDiscordInvitesEnable() @since 1.0 */
    public synchronized static boolean getDiscordInvitesEnable()        { return AppConfig.getDiscordInvitesEnable(); }
    /** Sets whether Discord "Join Game" invites are enabled. @param v the new flag. @see AppConfig#setDiscordInvitesEnable(boolean) @since 1.0 */
    public synchronized static void    setDiscordInvitesEnable( boolean v ) { AppConfig.setDiscordInvitesEnable( v ); }

    /** @return whether launcher windows can be resized. @see AppConfig#getResizableWindows() @since 1.0 */
    public synchronized static boolean getResizableWindows()            { return AppConfig.getResizableWindows(); }
    /** Sets whether launcher windows can be resized. @param v the new flag. @see AppConfig#setResizableWindows(boolean) @since 1.0 */
    public synchronized static void    setResizableWindows( boolean v ) { AppConfig.setResizableWindows( v ); }
    /** @return whether the in-game console is shown on launch. @see AppConfig#getInGameConsoleEnable() @since 1.0 */
    public synchronized static boolean getInGameConsoleEnable()         { return AppConfig.getInGameConsoleEnable(); }
    /** Sets whether the in-game console is shown on launch. @param v the new flag. @see AppConfig#setInGameConsoleEnable(boolean) @since 1.0 */
    public synchronized static void    setInGameConsoleEnable( boolean v ) { AppConfig.setInGameConsoleEnable( v ); }
    /** @return the in-game console visible-line cap (0 = unlimited). @see AppConfig#getConsoleLogMaxLines() @since 1.0 */
    public synchronized static int     getConsoleLogMaxLines()          { return AppConfig.getConsoleLogMaxLines(); }
    /** Sets the in-game console visible-line cap. @param v the line cap (0 = unlimited). @see AppConfig#setConsoleLogMaxLines(int) @since 1.0 */
    public synchronized static void    setConsoleLogMaxLines( int v )   { AppConfig.setConsoleLogMaxLines( v ); }

    /** @return whether the launcher checks for its own updates on startup. @see AppConfig#getLauncherUpdateCheckEnabled() @since 1.0 */
    public synchronized static boolean getLauncherUpdateCheckEnabled()  { return AppConfig.getLauncherUpdateCheckEnabled(); }
    /** Sets whether the launcher checks for its own updates on startup. @param v the new flag. @see AppConfig#setLauncherUpdateCheckEnabled(boolean) @since 1.0 */
    public synchronized static void    setLauncherUpdateCheckEnabled( boolean v ) { AppConfig.setLauncherUpdateCheckEnabled( v ); }
    /** @return whether the {@code mmcl://} URI handler is enabled. @see AppConfig#getUriHandlerEnabled() @since 1.0 */
    public synchronized static boolean getUriHandlerEnabled()           { return AppConfig.getUriHandlerEnabled(); }
    /** Sets whether the {@code mmcl://} URI handler is enabled. @param v the new flag. @see AppConfig#setUriHandlerEnabled(boolean) @since 1.0 */
    public synchronized static void    setUriHandlerEnabled( boolean v ) { AppConfig.setUriHandlerEnabled( v ); }
    /** @return whether the first-launch quick-start wizard has been completed. @see AppConfig#getQuickStartCompleted() @since 1.0 */
    public synchronized static boolean getQuickStartCompleted()         { return AppConfig.getQuickStartCompleted(); }
    /** Sets whether the first-launch quick-start wizard has been completed. @param v the new flag. @see AppConfig#setQuickStartCompleted(boolean) @since 1.0 */
    public synchronized static void    setQuickStartCompleted( boolean v ) { AppConfig.setQuickStartCompleted( v ); }
    /** @return whether downloads throttle while on battery power. @see AppConfig#getBatteryThrottleEnable() @since 1.0 */
    public synchronized static boolean getBatteryThrottleEnable()       { return AppConfig.getBatteryThrottleEnable(); }
    /** Sets whether downloads throttle while on battery power. @param v the new flag. @see AppConfig#setBatteryThrottleEnable(boolean) @since 1.0 */
    public synchronized static void    setBatteryThrottleEnable( boolean v ) { AppConfig.setBatteryThrottleEnable( v ); }

    /** @return whether LWJGL ARM64 native patching is enabled. @see AppConfig#getLwjglArmPatchEnable() @since 1.0 */
    public synchronized static boolean getLwjglArmPatchEnable()         { return AppConfig.getLwjglArmPatchEnable(); }
    /** Sets whether LWJGL ARM64 native patching is enabled. @param v the new flag. @see AppConfig#setLwjglArmPatchEnable(boolean) @since 1.0 */
    public synchronized static void    setLwjglArmPatchEnable( boolean v ) { AppConfig.setLwjglArmPatchEnable( v ); }

    /** @return the last-persisted window X position, or {@link Double#NaN} when unset. @see AppConfig#getWindowX() @since 1.0 */
    public synchronized static double  getWindowX()                     { return AppConfig.getWindowX(); }
    /** @return the last-persisted window Y position, or {@link Double#NaN} when unset. @see AppConfig#getWindowY() @since 1.0 */
    public synchronized static double  getWindowY()                     { return AppConfig.getWindowY(); }
    /** @return the last-persisted window width, or {@link Double#NaN} when unset. @see AppConfig#getWindowWidth() @since 1.0 */
    public synchronized static double  getWindowWidth()                 { return AppConfig.getWindowWidth(); }
    /** @return the last-persisted window height, or {@link Double#NaN} when unset. @see AppConfig#getWindowHeight() @since 1.0 */
    public synchronized static double  getWindowHeight()                { return AppConfig.getWindowHeight(); }
    /** @return whether the window was maximized when last persisted. @see AppConfig#getWindowMaximized() @since 1.0 */
    public synchronized static boolean getWindowMaximized()             { return AppConfig.getWindowMaximized(); }
    /**
     * Persists all five launcher-window bounds components atomically.
     *
     * @param x         the window X position to persist
     * @param y         the window Y position to persist
     * @param width     the window width to persist
     * @param height    the window height to persist
     * @param maximized the maximized state to persist
     * @see AppConfig#setWindowBounds(double, double, double, double, boolean)
     * @since 1.0
     */
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

        Logger.logStd( LocalizationManager.format( "log.configManager.migrating", storedVersion,
                                                   ConfigConstants.CONFIG_VERSION ) );

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
                Logger.logStd( LocalizationManager.get( "log.configManager.migrateV5ResizeWindows" ) );
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
                Logger.logStd( LocalizationManager.format( "log.configManager.migrateV6Urls", migrated ) );
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
            // Serialize a monitor-held deep-copy snapshot, not the live object:
            // typed setters mutate it under their own class monitors, so handing
            // the live JsonObject to Gson can throw ConcurrentModificationException.
            FileUtilities.writeFromJson( ConfigStore.snapshot(), destination );
            return true;
        }
        catch ( Exception e ) {
            Logger.logErrorSilent( LocalizationManager.format( "log.configManager.exportFailed",
                                                               e.getMessage() ) );
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
            Logger.logErrorSilent( LocalizationManager.format( "log.configManager.importFailed",
                                                               e.getMessage() ) );
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

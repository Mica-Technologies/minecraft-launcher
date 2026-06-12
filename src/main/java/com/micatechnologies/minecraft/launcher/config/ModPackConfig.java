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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.util.List;

/**
 * Modpack-related configuration domain — installed-pack lists, the
 * "last selected" pointer, per-pack security-scan and verify-on-launch
 * overrides, and the show-pack-backgrounds toggle. Fourth slice of the
 * A6-deep ConfigManager domain split tracked in the 2026-05-14 review
 * plan.
 *
 * <p>The per-pack maps ({@code alwaysVerifyOnLaunch} +
 * {@code scanFrequencyForPack}) are nested {@link JsonObject} children
 * keyed by manifest URL. Reads gracefully fall back to the launcher
 * default when the URL isn't present, when the nested object is
 * malformed, or when an unknown enum name is stored — same behaviour
 * the original ConfigManager methods had.</p>
 *
 * @since 2026.5
 */
public final class ModPackConfig
{
    private ModPackConfig() { /* static-only */ }

    // ====================================================================
    // Installed-pack lists
    // ====================================================================

    /** The user's installed modpack manifest URLs. The launcher reads
     *  this to populate the home-screen card grid + the install-index
     *  cache; setters fire on add / remove. */
    public static synchronized List< String > getInstalledModPacks() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.MOD_PACKS_INSTALLED_KEY ) ) {
            JsonArray defaultArray = ( JsonArray ) JSONUtilities.getGson().toJsonTree(
                    ConfigConstants.MOD_PACKS_INSTALLED_DEFAULT, ConfigConstants.modPacksListType );
            json.add( ConfigConstants.MOD_PACKS_INSTALLED_KEY, defaultArray );
        }
        JsonArray arr = json.get( ConfigConstants.MOD_PACKS_INSTALLED_KEY ).getAsJsonArray();
        return JSONUtilities.getGson().fromJson( arr, ConfigConstants.modPacksListType );
    }

    public static synchronized void setInstalledModPacks( List< String > installedModPacks ) {
        JsonArray arr = ( JsonArray ) JSONUtilities.getGson().toJsonTree(
                installedModPacks, ConfigConstants.modPacksListType );
        ConfigStore.ensureLoaded().add( ConfigConstants.MOD_PACKS_INSTALLED_KEY, arr );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // Backup-before-update policy
    // ====================================================================

    /** Whether the launcher auto-snapshots a pack's config (and optionally
     *  saves) before applying an update. Default on. */
    public static synchronized boolean getAutoBackupBeforeUpdate() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.BACKUP_AUTO_KEY ) ) {
            return ConfigConstants.BACKUP_AUTO_DEFAULT;
        }
        return json.get( ConfigConstants.BACKUP_AUTO_KEY ).getAsBoolean();
    }

    public static synchronized void setAutoBackupBeforeUpdate( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.BACKUP_AUTO_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Maximum number of backup zips retained per pack. 0 disables the
     *  count cap (age cap still applies). */
    public static synchronized int getMaxBackupsPerPack() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.BACKUP_MAX_COUNT_KEY ) ) {
            return ConfigConstants.BACKUP_MAX_COUNT_DEFAULT;
        }
        return json.get( ConfigConstants.BACKUP_MAX_COUNT_KEY ).getAsInt();
    }

    public static synchronized void setMaxBackupsPerPack( int max ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.BACKUP_MAX_COUNT_KEY,
                                                  Math.max( 0, max ) );
        ConfigStore.scheduleWrite();
    }

    /** Maximum age in days for retained backup zips. 0 disables the age
     *  cap (count cap still applies). */
    public static synchronized int getMaxBackupAgeDays() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.BACKUP_MAX_AGE_DAYS_KEY ) ) {
            return ConfigConstants.BACKUP_MAX_AGE_DAYS_DEFAULT;
        }
        return json.get( ConfigConstants.BACKUP_MAX_AGE_DAYS_KEY ).getAsInt();
    }

    public static synchronized void setMaxBackupAgeDays( int days ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.BACKUP_MAX_AGE_DAYS_KEY,
                                                  Math.max( 0, days ) );
        ConfigStore.scheduleWrite();
    }

    /** Whether saves/ is included in the backup zip. Off by default
     *  because save folders can be several GB. */
    public static synchronized boolean getBackupIncludeSaves() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.BACKUP_INCLUDE_SAVES_KEY ) ) {
            return ConfigConstants.BACKUP_INCLUDE_SAVES_DEFAULT;
        }
        return json.get( ConfigConstants.BACKUP_INCLUDE_SAVES_KEY ).getAsBoolean();
    }

    public static synchronized void setBackupIncludeSaves( boolean include ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.BACKUP_INCLUDE_SAVES_KEY, include );
        ConfigStore.scheduleWrite();
    }

    /** The user's installed Mojang vanilla version IDs (e.g.
     *  {@code "1.20.4"}, {@code "1.12.2"}). Same semantics as
     *  {@link #getInstalledModPacks} but for the vanilla cards on the
     *  Browse screen. */
    public static synchronized List< String > getInstalledVanillaVersions() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.VANILLA_VERSIONS_INSTALLED_KEY ) ) {
            json.add( ConfigConstants.VANILLA_VERSIONS_INSTALLED_KEY,
                      JSONUtilities.getGson().toJsonTree(
                              ConfigConstants.VANILLA_VERSIONS_INSTALLED_DEFAULT,
                              ConfigConstants.modPacksListType ) );
        }
        JsonArray arr = json.get( ConfigConstants.VANILLA_VERSIONS_INSTALLED_KEY ).getAsJsonArray();
        return JSONUtilities.getGson().fromJson( arr, ConfigConstants.modPacksListType );
    }

    public static synchronized void setInstalledVanillaVersions( List< String > versions ) {
        ConfigStore.ensureLoaded().add( ConfigConstants.VANILLA_VERSIONS_INSTALLED_KEY,
                                          JSONUtilities.getGson().toJsonTree(
                                                  versions, ConfigConstants.modPacksListType ) );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // Last selected
    // ====================================================================

    /** Manifest URL of the most recently launched pack, or empty when
     *  the launcher has never launched a pack. Used to float the
     *  last-played pack to the top of the home-screen carousel and as
     *  the cold-start auto-select target. */
    public static synchronized String getLastModPackSelected() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.LAST_MP_KEY ) ) {
            json.addProperty( ConfigConstants.LAST_MP_KEY, "" );
            ConfigStore.scheduleWrite();
        }
        return json.get( ConfigConstants.LAST_MP_KEY ).getAsString();
    }

    public static synchronized void setLastModPackSelected( String lastModPackSelected ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.LAST_MP_KEY, lastModPackSelected );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // Per-pack: always verify on launch (FAST_PATH opt-out)
    // ====================================================================

    /** Per-pack "always verify game files on launch" opt-out toggle.
     *  Defaults to {@link ConfigConstants#ALWAYS_VERIFY_ON_LAUNCH_DEFAULT}
     *  (false) — fast-path eligibility is the design choice from 3.3.
     *  Surfaced in the modpack-detail-modal Advanced section. */
    public static synchronized boolean getAlwaysVerifyOnLaunch( String packUrl ) {
        if ( packUrl == null || packUrl.isBlank() ) {
            return ConfigConstants.ALWAYS_VERIFY_ON_LAUNCH_DEFAULT;
        }
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.ALWAYS_VERIFY_BY_PACK_KEY ) ) {
            return ConfigConstants.ALWAYS_VERIFY_ON_LAUNCH_DEFAULT;
        }
        try {
            JsonObject map = json.get( ConfigConstants.ALWAYS_VERIFY_BY_PACK_KEY ).getAsJsonObject();
            if ( !map.has( packUrl ) ) {
                return ConfigConstants.ALWAYS_VERIFY_ON_LAUNCH_DEFAULT;
            }
            return map.get( packUrl ).getAsBoolean();
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.modPackConfig.alwaysVerifyReadFailed",
                                                                 packUrl ) );
            return ConfigConstants.ALWAYS_VERIFY_ON_LAUNCH_DEFAULT;
        }
    }

    public static synchronized void setAlwaysVerifyOnLaunch( String packUrl, boolean value ) {
        if ( packUrl == null || packUrl.isBlank() ) return;
        JsonObject json = ConfigStore.ensureLoaded();
        JsonObject map = json.has( ConfigConstants.ALWAYS_VERIFY_BY_PACK_KEY )
                         ? json.get( ConfigConstants.ALWAYS_VERIFY_BY_PACK_KEY ).getAsJsonObject()
                         : new JsonObject();
        map.addProperty( packUrl, value );
        json.add( ConfigConstants.ALWAYS_VERIFY_BY_PACK_KEY, map );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // Scan-frequency: launcher-wide default + per-pack override
    // ====================================================================

    /** Launcher-wide default security-scan frequency. Falls back to
     *  {@link ScanFrequency#DEFAULT} when the key is missing or carries
     *  a value the current build doesn't recognise — keeps a config
     *  written by an older / forked launcher version usable. */
    public static synchronized ScanFrequency getDefaultScanFrequency() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.DEFAULT_SCAN_FREQUENCY_KEY ) ) {
            return ScanFrequency.DEFAULT;
        }
        try {
            String name = json.get( ConfigConstants.DEFAULT_SCAN_FREQUENCY_KEY ).getAsString();
            return ScanFrequency.fromNameSafe( name );
        }
        catch ( Exception e ) {
            return ScanFrequency.DEFAULT;
        }
    }

    public static synchronized void setDefaultScanFrequency( ScanFrequency frequency ) {
        if ( frequency == null ) frequency = ScanFrequency.DEFAULT;
        ConfigStore.ensureLoaded().addProperty(
                ConfigConstants.DEFAULT_SCAN_FREQUENCY_KEY, frequency.name() );
        ConfigStore.scheduleWrite();
    }

    /** Per-pack scan-frequency override, or {@code null} when no
     *  override is set (meaning "use the global default"). The
     *  three-state return — null vs. explicit enum — distinguishes
     *  "user picked global default knowingly" from "user picked
     *  EVERY_TIME which happens to match the current default." */
    public static synchronized ScanFrequency getScanFrequencyForPack( String packUrl ) {
        if ( packUrl == null || packUrl.isBlank() ) return null;
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.SCAN_FREQUENCY_BY_PACK_KEY ) ) return null;
        try {
            JsonObject map = json.get( ConfigConstants.SCAN_FREQUENCY_BY_PACK_KEY ).getAsJsonObject();
            if ( !map.has( packUrl ) ) return null;
            String name = map.get( packUrl ).getAsString();
            if ( name == null || name.isBlank() ) return null;
            // valueOf (not fromNameSafe) — unknown enum name should fall
            // through to null (== use global default) rather than silently
            // downgrading to DEFAULT, which would mask config corruption.
            try {
                return ScanFrequency.valueOf( name );
            }
            catch ( IllegalArgumentException e ) {
                return null;
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.modPackConfig.scanFrequencyReadFailed",
                                                                 packUrl ) );
            return null;
        }
    }

    public static synchronized void setScanFrequencyForPack( String packUrl, ScanFrequency frequency ) {
        if ( packUrl == null || packUrl.isBlank() ) return;
        JsonObject json = ConfigStore.ensureLoaded();
        JsonObject map = json.has( ConfigConstants.SCAN_FREQUENCY_BY_PACK_KEY )
                         ? json.get( ConfigConstants.SCAN_FREQUENCY_BY_PACK_KEY ).getAsJsonObject()
                         : new JsonObject();
        if ( frequency == null ) {
            map.remove( packUrl );
        }
        else {
            map.addProperty( packUrl, frequency.name() );
        }
        json.add( ConfigConstants.SCAN_FREQUENCY_BY_PACK_KEY, map );
        ConfigStore.scheduleWrite();
    }

    /** Resolves the effective scan frequency for a pack: per-pack override
     *  if set, else launcher-wide default. {@code ScanFrequency.shouldScan}
     *  consumers only need a single value, so this hides the override-
     *  with-fallback dance. */
    public static synchronized ScanFrequency effectiveScanFrequencyForPack( String packUrl ) {
        ScanFrequency override = getScanFrequencyForPack( packUrl );
        return override != null ? override : getDefaultScanFrequency();
    }

    // ====================================================================
    // Card-grid display toggle
    // ====================================================================

    /** Whether modpack / version cards on the home screen + Browse view
     *  overlay the pack's real background image on top of the procedural
     *  gradient. Default true; the procedural gradient renders alone
     *  when this is off. */
    public static synchronized boolean getShowPackBackgrounds() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.SHOW_PACK_BACKGROUNDS_KEY ) ) {
            return ConfigConstants.SHOW_PACK_BACKGROUNDS_DEFAULT;
        }
        try {
            return json.get( ConfigConstants.SHOW_PACK_BACKGROUNDS_KEY ).getAsBoolean();
        }
        catch ( Exception e ) {
            return ConfigConstants.SHOW_PACK_BACKGROUNDS_DEFAULT;
        }
    }

    public static synchronized void setShowPackBackgrounds( boolean show ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.SHOW_PACK_BACKGROUNDS_KEY, show );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // Multi-image cycle (issue #43)
    // ====================================================================

    /** How often a pack's logo + background cycle advances when the manifest
     *  declares multiple images. One of
     *  {@link ConfigConstants#IMAGE_CYCLE_INTERVAL_OPTIONS}; {@code "never"}
     *  disables cycling. Unknown / malformed stored values fall back to the
     *  default rather than breaking the cycler. */
    public static synchronized String getImageCycleInterval() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.IMAGE_CYCLE_INTERVAL_KEY ) ) {
            return ConfigConstants.IMAGE_CYCLE_INTERVAL_DEFAULT;
        }
        try {
            String v = json.get( ConfigConstants.IMAGE_CYCLE_INTERVAL_KEY ).getAsString();
            if ( v == null || !ConfigConstants.IMAGE_CYCLE_INTERVAL_OPTIONS.contains( v ) ) {
                return ConfigConstants.IMAGE_CYCLE_INTERVAL_DEFAULT;
            }
            return v;
        }
        catch ( Exception e ) {
            return ConfigConstants.IMAGE_CYCLE_INTERVAL_DEFAULT;
        }
    }

    public static synchronized void setImageCycleInterval( String interval ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.IMAGE_CYCLE_INTERVAL_KEY, interval );
        ConfigStore.scheduleWrite();
    }

    /** Whether the per-pack image cycle visits images in a one-time shuffled
     *  order. Default false (manifest order). */
    public static synchronized boolean getImageCycleShuffle() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.IMAGE_CYCLE_SHUFFLE_KEY ) ) {
            return ConfigConstants.IMAGE_CYCLE_SHUFFLE_DEFAULT;
        }
        try {
            return json.get( ConfigConstants.IMAGE_CYCLE_SHUFFLE_KEY ).getAsBoolean();
        }
        catch ( Exception e ) {
            return ConfigConstants.IMAGE_CYCLE_SHUFFLE_DEFAULT;
        }
    }

    public static synchronized void setImageCycleShuffle( boolean shuffle ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.IMAGE_CYCLE_SHUFFLE_KEY, shuffle );
        ConfigStore.scheduleWrite();
    }
}

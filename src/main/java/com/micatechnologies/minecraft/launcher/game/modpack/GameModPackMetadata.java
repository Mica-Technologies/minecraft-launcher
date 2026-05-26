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

package com.micatechnologies.minecraft.launcher.game.modpack;

import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.launcher.utilities.VersionUtilities;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class containing all GSON-deserialized metadata fields for a mod pack. This class holds the JSON schema fields
 * and their simple accessors. {@link GameModPack} extends this class and adds game lifecycle behavior (downloading,
 * launching, scanning, etc.).
 * <p>
 * GSON walks the class hierarchy when deserializing, so all fields declared here are populated automatically when
 * deserializing a {@link GameModPack} instance.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public abstract class GameModPackMetadata
{
    // region GSON-deserialized fields (must match JSON keys exactly)

    /**
     * Mod pack name. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packName;

    /**
     * Mod pack version. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packVersion;

    /**
     * Mod pack website URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packURL;

    /**
     * Mod pack unstable flag. Value read from manifest JSON.
     *
     * @since 1.2
     */
    @SuppressWarnings( "unused" )
    protected boolean packUnstable;

    /**
     * Mod pack custom Discord RPC flag. Value read from manifest JSON.
     *
     * @since 1.2
     */
    @SuppressWarnings( "unused" )
    protected boolean packCustomDiscordRpc;

    /**
     * Mod pack logo URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packLogoURL;

    /**
     * Mod pack logo SHA-1. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packLogoSha1;

    /**
     * Mod pack background URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packBackgroundURL;

    /**
     * Mod pack background SHA-1. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packBackgroundSha1;

    /**
     * Mod pack minimum RAM (GB). Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packMinRAMGB;

    /**
     * Mod pack Forge download URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packForgeURL;

    /**
     * Mod pack Forge download SHA-1 hash. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packForgeHash;

    /**
     * Modloader type — one of {@code forge}, {@code neoforge},
     * {@code fabric}. Optional in the manifest; absence defaults to
     * Forge for backward-compat with every existing pack. Read by
     * {@link GameModPack#getModLoaderType()}.
     *
     * @since 2026.5
     */
    @SuppressWarnings( "unused" )
    protected String packModLoader;

    /**
     * Modloader installer / profile URL. Generalised replacement for
     * {@link #packForgeURL}. When absent, the launcher falls back to
     * {@code packForgeURL} so legacy Forge manifests keep working
     * without modification. Interpretation depends on the loader
     * (Forge / NeoForge: installer jar URL; Fabric: meta-profile JSON
     * URL).
     *
     * @since 2026.5
     */
    @SuppressWarnings( "unused" )
    protected String packModLoaderURL;

    /**
     * SHA-1 of {@link #packModLoaderURL}. Generalised replacement for
     * {@link #packForgeHash}. When absent, falls back to
     * {@code packForgeHash}. May be empty for loaders that don't
     * hash-verify their meta artifact (e.g. Fabric profile JSON).
     *
     * @since 2026.5
     */
    @SuppressWarnings( "unused" )
    protected String packModLoaderHash;

    /**
     * Optional default server host for the pack. When set (non-null,
     * non-blank), the launcher treats this as a "this pack was built
     * for this server" hint and routes through the quick-join argv
     * (Minecraft {@code --server}) on every launch, unless the user
     * has explicitly disabled the auto-join via the per-pack toggle
     * in the modpack detail modal's Servers section. Read by
     * {@link GameModPack#getDefaultServer()} which packages
     * host/port/name into a {@link ServerFavorite}.
     *
     * @since 2026.5
     */
    @SuppressWarnings( "unused" )
    protected String packDefaultServerHost;

    /**
     * Optional default server port. Defaults to Minecraft's well-known
     * 25565 when omitted. Ignored when {@link #packDefaultServerHost}
     * is unset.
     *
     * @since 2026.5
     */
    @SuppressWarnings( "unused" )
    protected Integer packDefaultServerPort;

    /**
     * Optional human-readable name for the default server (shown in
     * the modal's "Pack default" row). Defaults to the host when
     * omitted.
     *
     * @since 2026.5
     */
    @SuppressWarnings( "unused" )
    protected String packDefaultServerName;

    /**
     * Marks a manifest as "imported, user-owned content" — set when a
     * pack was generated by the Prism / MultiMC instance importer (or
     * similar import path) rather than authored as a Mica manifest. When
     * true, {@code GameModPackFileSync.clearFloatingMods} is skipped so
     * the imported {@code mods/}, {@code config/}, {@code saves/}, etc.
     * survive launches without being wiped because the manifest's
     * {@code packMods} list is empty. The user is expected to manage
     * these files manually (the launcher's Mods section UI still works,
     * and the Modrinth update-check button can still tell them what's
     * outdated).
     *
     * @since 2026.5
     */
    @SuppressWarnings( "unused" )
    protected boolean packImportedSkipSync;

    /**
     * Mod pack scan exclusions (file or folder names, relative to mod pack root). Value read from manifest JSON.
     *
     * @since 1.3
     */
    @SuppressWarnings( "unused" )
    protected List< String > packScanExclusions;

    /**
     * Per-finding acknowledgements that silence specific known-OK security-scan hits without disabling the scan
     * entirely or excluding the file outright. Each entry carries a substring {@code match} plus a free-form
     * {@code reason}; findings whose text contains the match are dropped from the blocking list and logged as
     * acknowledged. Loaded from manifest JSON; see {@link ScanAcknowledgement} for the manifest format and
     * matching semantics. Defaults to an empty list when the manifest omits the field.
     *
     * @since 2026.3
     */
    @SuppressWarnings( "unused" )
    protected List< ScanAcknowledgement > packScanAcknowledgements;

    /**
     * List of mod pack Forge mods. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    protected List< GameMod > packMods;

    /**
     * List of mod pack Forge configs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    protected List< GameAsset > packConfigs;

    /**
     * List of mod pack resource packs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    protected List< ManagedGameFile > packResourcePacks;

    /**
     * List of mod pack shader packs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    protected List< ManagedGameFile > packShaderPacks;

    /**
     * List of initial files for mod pack. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    protected List< GameAsset > packInitialFiles;

    // endregion

    // region Simple accessors

    /**
     * Get the mod pack name.
     *
     * @return modpack name
     *
     * @since 1.0
     */
    public String getPackName()
    {
        return packName;
    }

    /**
     * Get the mod pack version.
     *
     * @return modpack version string
     *
     * @since 1.0
     */
    public String getPackVersion()
    {
        return packVersion;
    }

    /**
     * Get the mod pack website URL.
     *
     * @return modpack URL
     *
     * @since 1.0
     */
    public String getPackURL()
    {
        return packURL;
    }

    /**
     * Get the mod pack unstable flag.
     *
     * @return true if the modpack is marked as unstable (beta)
     *
     * @since 1.2
     */
    public boolean getPackUnstable()
    {
        return packUnstable;
    }

    /**
     * Get the mod pack custom Discord RPC flag.
     *
     * @return true if the modpack uses custom Discord RPC
     *
     * @since 1.2
     */
    public boolean getCustomDiscordRpc()
    {
        return packCustomDiscordRpc;
    }

    /**
     * Get the mod pack minimum RAM in gigabytes.
     *
     * @return minimum RAM in GB
     *
     * @since 1.0
     */
    public double getPackMinRAMGB()
    {
        return Double.parseDouble( packMinRAMGB );
    }

    /**
     * Get the mod pack scan exclusions.
     *
     * @return list of scan exclusion paths, never null
     *
     * @since 1.3
     */
    public List< String > getPackScanExclusions()
    {
        if ( packScanExclusions == null )
        {
            packScanExclusions = new ArrayList<>();
        }
        return packScanExclusions;
    }

    /**
     * Get the mod pack scan acknowledgements — per-finding silencers for known-OK detections.
     *
     * @return list of acknowledgements, never null
     *
     * @since 2026.3
     */
    public List< ScanAcknowledgement > getPackScanAcknowledgements()
    {
        if ( packScanAcknowledgements == null )
        {
            packScanAcknowledgements = new ArrayList<>();
        }
        return packScanAcknowledgements;
    }

    /**
     * Get the sanitized pack name (alphanumeric only, suitable for folder names).
     *
     * @return sanitized pack name
     *
     * @since 1.0
     */
    public String getPackSanitizedName()
    {
        return getPackName().replaceAll( "[^a-zA-Z0-9]", "" );
    }

    /**
     * Get the user-friendly display name (name + version).
     *
     * @return friendly name string, or null if pack name is null
     *
     * @since 1.0
     */
    public String getFriendlyName()
    {
        return getPackName() != null ?
               String.format( ModPackConstants.MODPACK_FRIENDLY_NAME_TEMPLATE, getPackName(), getPackVersion() ) :
               null;
    }

    // endregion

    // region Path helpers

    /**
     * Get the installation folder of this mod pack.
     *
     * @return installation folder path
     *
     * @since 1.0
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getPackRootFolder()
    {
        return LocalPathManager.getLauncherModpackFolderPath() + File.separator + getPackSanitizedName();
    }

    /**
     * Returns true when this pack was registered through the
     * MultiMC / Prism instance importer (or another "user-owned mods"
     * import path). Consumed by file-sync to skip floating-mod cleanup
     * — see field-level Javadoc on {@link #packImportedSkipSync}.
     *
     * @since 2026.5
     */
    public boolean isImportedSkipSync()
    {
        return packImportedSkipSync;
    }

    /**
     * Returns the pack's manifest-declared default server as a
     * {@link ServerFavorite}, or {@code null} when no default server is
     * configured. The returned favorite is suitable for
     * {@link GameModPack#setQuickJoinServer(ServerFavorite)} — packing
     * the host, the default-or-declared port, and the friendly name
     * (falling back to the host when unset).
     *
     * @since 2026.5
     */
    public ServerFavorite getDefaultServer()
    {
        if ( packDefaultServerHost == null || packDefaultServerHost.isBlank() ) return null;
        int port = ( packDefaultServerPort == null
                || packDefaultServerPort < 1 || packDefaultServerPort > 65535 )
                ? ServerFavorite.DEFAULT_PORT
                : packDefaultServerPort;
        String name = ( packDefaultServerName == null || packDefaultServerName.isBlank() )
                ? packDefaultServerHost
                : packDefaultServerName;
        return new ServerFavorite( name, packDefaultServerHost.trim(), port );
    }

    /**
     * Get the path to this mod pack's bin folder.
     *
     * @return bin folder path
     *
     * @since 1.0
     */
    public String getPackBinFolder()
    {
        return SystemUtilities.buildFilePath( getPackRootFolder(), LocalPathConstants.MOD_PACK_BIN_FOLDER_NAME );
    }

    // endregion

    // region Version tracking (installed vs remote)

    /**
     * Name of the file that stores the last-launched version in each modpack's root folder.
     */
    private static final String INSTALLED_VERSION_FILE = ".installed_version";

    /**
     * Cached result of the update check (transient, not serialized).
     */
    private transient Boolean updateAvailable = null;

    /**
     * Gets the locally installed (last-launched) version string for this modpack, or null if no version file exists
     * (i.e. the pack has never been launched).
     *
     * @return installed version string, or null
     *
     * @since 2.0
     */
    public String getInstalledVersion()
    {
        Path versionFile = Path.of( getPackRootFolder(), INSTALLED_VERSION_FILE );
        if ( Files.exists( versionFile ) ) {
            try {
                return Files.readString( versionFile, StandardCharsets.UTF_8 ).trim();
            }
            catch ( IOException e ) {
                Logger.logWarningSilent( "Unable to read installed version for " + getPackName() );
            }
        }
        return null;
    }

    /**
     * Saves the current remote version as the installed version. Call after a successful game launch.
     *
     * @since 2.0
     */
    public void saveInstalledVersion()
    {
        Path versionFile = Path.of( getPackRootFolder(), INSTALLED_VERSION_FILE );
        try {
            //noinspection ResultOfMethodCallIgnored
            versionFile.getParent().toFile().mkdirs();
            Files.writeString( versionFile, getPackVersion(), StandardCharsets.UTF_8 );
            updateAvailable = null; // Reset cached check
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Unable to save installed version for " + getPackName() );
        }
    }

    /**
     * Returns true if the remote pack version is newer than the locally installed version. Returns false if the pack
     * has never been launched (no installed version file), since there's nothing to "update" yet.
     *
     * @return true if an update is available
     *
     * @since 2.0
     */
    public boolean isUpdateAvailable()
    {
        if ( updateAvailable != null ) {
            return updateAvailable;
        }
        String installed = getInstalledVersion();
        if ( installed == null || installed.isEmpty() ) {
            updateAvailable = false;
            return false;
        }
        String remote = getPackVersion();
        if ( remote == null || remote.isEmpty() ) {
            updateAvailable = false;
            return false;
        }
        // Belt-and-suspenders against any future weirdness in the
        // version string format. compareVersionNumbers was hardened
        // not to throw on Forge-style "_buildmeta" suffixes, but this
        // method runs on the FX thread during card rebuilds and any
        // throwable here would kill the main-menu render — better to
        // log + assume no update than crash the menu.
        try {
            updateAvailable = VersionUtilities.compareVersionNumbers( installed, remote ) == -1;
        }
        catch ( Throwable t ) {
            com.micatechnologies.minecraft.launcher.files.Logger.logWarningSilent(
                    "isUpdateAvailable: comparing \"" + installed + "\" vs \""
                            + remote + "\" for " + getPackName() + " — assuming no update", t );
            updateAvailable = false;
        }
        return updateAvailable;
    }

    // endregion

    // region Launch history

    /**
     * Name of the file that stores launch history in each modpack's root folder.
     */
    private static final String LAUNCH_HISTORY_FILE = ".launch_history";

    /**
     * Cached launch history values (transient, not serialized).
     */
    private transient long cachedLastPlayedMs = -1;
    private transient long cachedTotalPlayTimeMs = -1;
    private transient int  cachedLaunchCount = -1;

    /**
     * Records the start of a game launch. Call when the game process starts.
     *
     * @since 2.0
     */
    public void recordLaunchStart()
    {
        Path historyFile = Path.of( getPackRootFolder(), LAUNCH_HISTORY_FILE );
        long now = System.currentTimeMillis();
        long totalPlayTime = 0;
        int launchCount = 0;

        // Read existing history
        if ( Files.exists( historyFile ) ) {
            try {
                String content = Files.readString( historyFile, StandardCharsets.UTF_8 );
                String[] parts = content.trim().split( "\n" );
                if ( parts.length >= 2 ) {
                    totalPlayTime = Long.parseLong( parts[1].trim() );
                }
                if ( parts.length >= 3 ) {
                    launchCount = Integer.parseInt( parts[2].trim() );
                }
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Unable to read launch history for " + getPackName() );
            }
        }

        // Write updated history (last played, total play time, launch count)
        try {
            //noinspection ResultOfMethodCallIgnored
            historyFile.getParent().toFile().mkdirs();
            String content = now + "\n" + totalPlayTime + "\n" + ( launchCount + 1 ) + "\n";
            Files.writeString( historyFile, content, StandardCharsets.UTF_8 );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Unable to save launch history for " + getPackName() );
        }

        // Update cache
        cachedLastPlayedMs = now;
        cachedTotalPlayTimeMs = totalPlayTime;
        cachedLaunchCount = launchCount + 1;
    }

    /**
     * Records the end of a game session. Call when the game process exits.
     *
     * @param sessionDurationMs the duration of the game session in milliseconds
     *
     * @since 2.0
     */
    public void recordSessionEnd( long sessionDurationMs )
    {
        Path historyFile = Path.of( getPackRootFolder(), LAUNCH_HISTORY_FILE );
        long lastPlayed = System.currentTimeMillis();
        long totalPlayTime = sessionDurationMs;
        int launchCount = 1;

        // Read existing history
        if ( Files.exists( historyFile ) ) {
            try {
                String content = Files.readString( historyFile, StandardCharsets.UTF_8 );
                String[] parts = content.trim().split( "\n" );
                if ( parts.length >= 1 ) {
                    lastPlayed = Long.parseLong( parts[0].trim() );
                }
                if ( parts.length >= 2 ) {
                    totalPlayTime += Long.parseLong( parts[1].trim() );
                }
                if ( parts.length >= 3 ) {
                    launchCount = Integer.parseInt( parts[2].trim() );
                }
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Unable to read launch history for " + getPackName() );
            }
        }

        // Write updated history with accumulated play time
        try {
            String content = lastPlayed + "\n" + totalPlayTime + "\n" + launchCount + "\n";
            Files.writeString( historyFile, content, StandardCharsets.UTF_8 );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Unable to save launch history for " + getPackName() );
        }

        // Update cache
        cachedLastPlayedMs = lastPlayed;
        cachedTotalPlayTimeMs = totalPlayTime;
        cachedLaunchCount = launchCount;
    }

    /**
     * Loads launch history from disk if not already cached.
     */
    private void ensureHistoryLoaded()
    {
        if ( cachedLastPlayedMs >= 0 ) {
            return;
        }
        Path historyFile = Path.of( getPackRootFolder(), LAUNCH_HISTORY_FILE );
        if ( Files.exists( historyFile ) ) {
            try {
                String content = Files.readString( historyFile, StandardCharsets.UTF_8 );
                String[] parts = content.trim().split( "\n" );
                cachedLastPlayedMs = parts.length >= 1 ? Long.parseLong( parts[0].trim() ) : 0;
                cachedTotalPlayTimeMs = parts.length >= 2 ? Long.parseLong( parts[1].trim() ) : 0;
                cachedLaunchCount = parts.length >= 3 ? Integer.parseInt( parts[2].trim() ) : 0;
            }
            catch ( Exception e ) {
                cachedLastPlayedMs = 0;
                cachedTotalPlayTimeMs = 0;
                cachedLaunchCount = 0;
            }
        }
        else {
            cachedLastPlayedMs = 0;
            cachedTotalPlayTimeMs = 0;
            cachedLaunchCount = 0;
        }
    }

    /**
     * Returns the timestamp (epoch ms) of the last time this modpack was played, or 0 if never.
     *
     * @return last played timestamp
     *
     * @since 2.0
     */
    public long getLastPlayedMs()
    {
        ensureHistoryLoaded();
        return cachedLastPlayedMs;
    }

    /**
     * Returns a human-readable "last played" string (e.g. "2 hours ago", "3 days ago", or "Never").
     *
     * @return formatted last played string
     *
     * @since 2.0
     */
    public String getLastPlayedFormatted()
    {
        long lastPlayed = getLastPlayedMs();
        if ( lastPlayed == 0 ) {
            return com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager
                    .get( "metadata.lastPlayed.never" );
        }
        long elapsed = System.currentTimeMillis() - lastPlayed;
        if ( elapsed < 60_000 ) {
            return com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager
                    .get( "metadata.lastPlayed.justNow" );
        }
        else if ( elapsed < 3_600_000 ) {
            long mins = elapsed / 60_000;
            return com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.format(
                    mins == 1 ? "metadata.lastPlayed.minuteAgo" : "metadata.lastPlayed.minutesAgo",
                    mins );
        }
        else if ( elapsed < 86_400_000 ) {
            long hours = elapsed / 3_600_000;
            return com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.format(
                    hours == 1 ? "metadata.lastPlayed.hourAgo" : "metadata.lastPlayed.hoursAgo",
                    hours );
        }
        else {
            long days = elapsed / 86_400_000;
            return com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.format(
                    days == 1 ? "metadata.lastPlayed.dayAgo" : "metadata.lastPlayed.daysAgo",
                    days );
        }
    }

    /**
     * Returns true when the pack has never been launched (i.e.
     * {@link #getLastPlayedMs()} is 0). Use this instead of
     * string-comparing the result of {@link #getLastPlayedFormatted()}
     * against the literal "Never played" — that string is now
     * localized, so a string-compare would only work in English.
     *
     * @return true if the pack has no recorded play history
     * @since 2026.5
     */
    public boolean isNeverPlayed()
    {
        return getLastPlayedMs() == 0;
    }

    /**
     * Returns the total play time in milliseconds across all sessions.
     *
     * @return total play time in ms
     *
     * @since 2.0
     */
    public long getTotalPlayTimeMs()
    {
        ensureHistoryLoaded();
        return cachedTotalPlayTimeMs;
    }

    /**
     * Returns a formatted total play time string (e.g. "12.5 hours", "45 minutes").
     *
     * @return formatted total play time
     *
     * @since 2.0
     */
    public String getTotalPlayTimeFormatted()
    {
        long totalMs = getTotalPlayTimeMs();
        if ( totalMs == 0 ) {
            return "0 minutes";
        }
        long totalMinutes = totalMs / 60_000;
        if ( totalMinutes < 60 ) {
            return totalMinutes + ( totalMinutes == 1 ? " minute" : " minutes" );
        }
        double hours = totalMinutes / 60.0;
        if ( hours < 24 ) {
            return String.format( "%.1f hours", hours );
        }
        double days = hours / 24.0;
        return String.format( "%.1f days", days );
    }

    /**
     * Returns the number of times this modpack has been launched.
     *
     * @return launch count
     *
     * @since 2.0
     */
    public int getLaunchCount()
    {
        ensureHistoryLoaded();
        return cachedLaunchCount;
    }

    // endregion

    /**
     * Returns a string representation of this mod pack (its friendly name).
     *
     * @return friendly name
     */
    @Override
    public String toString()
    {
        return getFriendlyName();
    }
}

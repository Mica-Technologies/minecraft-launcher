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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
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
     * Mod pack logo URL(s). Value read from manifest JSON — accepts a single string or an array of
     * strings (fallback / mirror URLs, tried in order). See {@link com.micatechnologies.minecraft.launcher.utilities.StringOrArray}.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected com.micatechnologies.minecraft.launcher.utilities.StringOrArray packLogoURL;

    /**
     * Mod pack logo SHA-1. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packLogoSha1;

    /**
     * Mod pack background URL(s). Value read from manifest JSON — accepts a single string or an array
     * of strings (fallback / mirror URLs, tried in order). See {@link com.micatechnologies.minecraft.launcher.utilities.StringOrArray}.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected com.micatechnologies.minecraft.launcher.utilities.StringOrArray packBackgroundURL;

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
     * Per-pack news entries shown in the modpack detail modal's News section.
     * Authored directly in the manifest JSON so news rides along on the
     * already-fetched, already-verified pack definition — no separate feed or
     * server. Defaults to an empty list when the manifest omits the field. See
     * {@link NewsItem} for the entry shape and defensive accessors.
     *
     * @since 2026.6
     */
    @SuppressWarnings( "unused" )
    protected List< NewsItem > packNews;

    /**
     * Per-pack links shown in the modpack detail modal's Links section (wiki,
     * Discord, store page, issue tracker, …). Authored directly in the manifest
     * JSON, alongside {@link #packNews}; no separate infrastructure. Defaults to
     * an empty list when the manifest omits the field. See {@link LinkItem} for
     * the entry shape and defensive accessors.
     *
     * @since 2026.6
     */
    @SuppressWarnings( "unused" )
    protected List< LinkItem > packLinks;

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
        // Defensive parse: packMinRAMGB is a raw manifest string. A malformed value
        // ("lots", "") would otherwise throw NumberFormatException out of a simple
        // accessor and break the launch / RAM check. Fall back to 0 (no minimum) and
        // clamp to a sane ceiling so a hostile manifest can't demand absurd RAM.
        if ( packMinRAMGB == null || packMinRAMGB.isBlank() ) {
            return 0.0;
        }
        try {
            double parsed = Double.parseDouble( packMinRAMGB.trim() );
            if ( Double.isNaN( parsed ) || parsed < 0.0 ) {
                return 0.0;
            }
            return Math.min( parsed, 1024.0 );  // clamp well beyond any real pack
        }
        catch ( NumberFormatException e ) {
            return 0.0;
        }
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
     * Get this pack's news entries, in manifest order. Never null.
     *
     * @return list of news items, possibly empty
     *
     * @since 2026.6
     */
    public List< NewsItem > getPackNews()
    {
        if ( packNews == null )
        {
            packNews = new ArrayList<>();
        }
        return packNews;
    }

    /**
     * Returns this pack's news items that should be shown right now — renderable
     * (non-blank title) and not past their expiry — sorted pinned-first then
     * newest-date-first. The list is freshly computed and safe to mutate.
     *
     * @return visible, sorted news items; never null
     *
     * @since 2026.6
     */
    public List< NewsItem > getVisibleNews()
    {
        java.time.LocalDate today = java.time.LocalDate.now();
        List< NewsItem > visible = new ArrayList<>();
        for ( NewsItem item : getPackNews() ) {
            if ( item != null && item.isRenderable() && !item.isExpired( today ) ) {
                visible.add( item );
            }
        }
        visible.sort( ( a, b ) -> {
            if ( a.isPinned() != b.isPinned() ) {
                return a.isPinned() ? -1 : 1;
            }
            return Long.compare( b.getSortEpoch(), a.getSortEpoch() );
        } );
        return visible;
    }

    /**
     * Get this pack's links, in manifest order. Never null.
     *
     * @return list of links, possibly empty
     *
     * @since 2026.6
     */
    public List< LinkItem > getPackLinks()
    {
        if ( packLinks == null )
        {
            packLinks = new ArrayList<>();
        }
        return packLinks;
    }

    /**
     * Returns this pack's links that should be shown — renderable ones (non-blank
     * title and a valid http(s) URL) in manifest order. The list is freshly
     * computed and safe to mutate.
     *
     * @return renderable links in manifest order; never null
     *
     * @since 2026.6
     */
    public List< LinkItem > getVisibleLinks()
    {
        List< LinkItem > visible = new ArrayList<>();
        for ( LinkItem link : getPackLinks() ) {
            if ( link != null && link.isRenderable() ) {
                visible.add( link );
            }
        }
        return visible;
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
                Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackMetadata.unableToReadInstalledVersion", getPackName() ) );
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
            Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackMetadata.unableToSaveInstalledVersion", getPackName() ) );
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
                    LocalizationManager.format( "log.gameModPackMetadata.isUpdateAvailableCompareFailed",
                            installed, remote, getPackName() ), t );
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

        // Serialize the whole read-modify-write on the canonical per-path monitor so a
        // concurrent recordSessionEnd / second launch of the same pack can't lose
        // accumulated play time or launch count (both touch this .launch_history file).
        synchronized ( SynchronizedFileManager.getSynchronizedFile( historyFile ) ) {
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
                    Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackMetadata.unableToReadLaunchHistory", getPackName() ) );
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
                Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackMetadata.unableToSaveLaunchHistory", getPackName() ) );
            }

            // Update cache
            cachedLastPlayedMs = now;
            cachedTotalPlayTimeMs = totalPlayTime;
            cachedLaunchCount = launchCount + 1;
        }
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

        // Serialize the whole read-modify-write on the canonical per-path monitor so a
        // concurrent recordLaunchStart / second session of the same pack can't lose
        // accumulated play time or launch count (both touch this .launch_history file).
        synchronized ( SynchronizedFileManager.getSynchronizedFile( historyFile ) ) {
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
                    Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackMetadata.unableToReadLaunchHistory", getPackName() ) );
                }
            }

            // Write updated history with accumulated play time
            try {
                String content = lastPlayed + "\n" + totalPlayTime + "\n" + launchCount + "\n";
                Files.writeString( historyFile, content, StandardCharsets.UTF_8 );
            }
            catch ( IOException e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackMetadata.unableToSaveLaunchHistory", getPackName() ) );
            }

            // Update cache
            cachedLastPlayedMs = lastPlayed;
            cachedTotalPlayTimeMs = totalPlayTime;
            cachedLaunchCount = launchCount;
        }
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
        // Read under the same per-path monitor the record* writers use so we never
        // parse a half-written file mid-update.
        synchronized ( SynchronizedFileManager.getSynchronizedFile( historyFile ) ) {
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
            return LocalizationManager.get( "gameModPackMetadata.totalPlayTime.zero" );
        }
        long totalMinutes = totalMs / 60_000;
        if ( totalMinutes < 60 ) {
            return LocalizationManager.format(
                    totalMinutes == 1 ? "gameModPackMetadata.totalPlayTime.minute"
                                      : "gameModPackMetadata.totalPlayTime.minutes",
                    totalMinutes );
        }
        double hours = totalMinutes / 60.0;
        if ( hours < 24 ) {
            return LocalizationManager.format( "gameModPackMetadata.totalPlayTime.hours", hours );
        }
        double days = hours / 24.0;
        return LocalizationManager.format( "gameModPackMetadata.totalPlayTime.days", days );
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

    // region News read/unread tracking

    /**
     * Name of the file that stores already-seen news IDs in each modpack's root
     * folder — one id per line. Local-only, like {@code .installed_version} and
     * {@code .launch_history}; no infrastructure beyond the pack folder.
     */
    private static final String SEEN_NEWS_FILE = ".seen_news";

    /**
     * Reads the set of news IDs the user has already seen for this pack. Returns
     * an empty set when the file is absent or unreadable.
     *
     * @return mutable set of seen news IDs, never null
     *
     * @since 2026.6
     */
    public java.util.Set< String > getSeenNewsIds()
    {
        java.util.Set< String > seen = new java.util.HashSet<>();
        Path seenFile = Path.of( getPackRootFolder(), SEEN_NEWS_FILE );
        synchronized ( SynchronizedFileManager.getSynchronizedFile( seenFile ) ) {
            if ( Files.exists( seenFile ) ) {
                try {
                    for ( String line : Files.readAllLines( seenFile, StandardCharsets.UTF_8 ) ) {
                        String id = line.trim();
                        if ( !id.isEmpty() ) {
                            seen.add( id );
                        }
                    }
                }
                catch ( IOException e ) {
                    Logger.logWarningSilent( LocalizationManager.format(
                            "log.gameModPackMetadata.unableToReadSeenNews", getPackName() ) );
                }
            }
        }
        return seen;
    }

    /**
     * Returns the number of currently-visible news items carrying a stable id
     * that the user has not yet seen. Items without an id can't be tracked and
     * are excluded so the badge can always be cleared.
     *
     * @return count of unread, trackable news items
     *
     * @since 2026.6
     */
    public int getUnreadNewsCount()
    {
        List< NewsItem > visible = getVisibleNews();
        if ( visible.isEmpty() ) {
            return 0;
        }
        java.util.Set< String > seen = getSeenNewsIds();
        int unread = 0;
        for ( NewsItem item : visible ) {
            String id = item.getId();
            if ( id != null && !seen.contains( id ) ) {
                unread++;
            }
        }
        return unread;
    }

    /**
     * Marks every currently-visible news item with an id as seen, clearing the
     * unread badge. No-op when there are no trackable items. Best-effort: a
     * write failure is logged, not thrown.
     *
     * @since 2026.6
     */
    public void markAllNewsSeen()
    {
        List< NewsItem > visible = getVisibleNews();
        java.util.Set< String > ids = new java.util.LinkedHashSet<>();
        for ( NewsItem item : visible ) {
            String id = item.getId();
            if ( id != null ) {
                ids.add( id );
            }
        }
        if ( ids.isEmpty() ) {
            return;
        }
        Path seenFile = Path.of( getPackRootFolder(), SEEN_NEWS_FILE );
        synchronized ( SynchronizedFileManager.getSynchronizedFile( seenFile ) ) {
            // Union with whatever was already seen so expired/rotated-out items
            // stay marked and never resurface if an author re-adds them.
            ids.addAll( getSeenNewsIds() );
            try {
                //noinspection ResultOfMethodCallIgnored
                seenFile.getParent().toFile().mkdirs();
                Files.writeString( seenFile, String.join( "\n", ids ) + "\n", StandardCharsets.UTF_8 );
            }
            catch ( IOException e ) {
                Logger.logWarningSilent( LocalizationManager.format(
                        "log.gameModPackMetadata.unableToSaveSeenNews", getPackName() ) );
            }
        }
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

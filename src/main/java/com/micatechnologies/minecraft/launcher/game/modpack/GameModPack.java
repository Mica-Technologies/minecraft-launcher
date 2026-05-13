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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackScanDetectionException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameLibraryManifest;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameVersionManifest;
import com.micatechnologies.minecraft.launcher.security.SupplementalScanner;
import me.cortex.jarscanner.Constants;
import me.cortex.jarscanner.Main;
import me.cortex.jarscanner.Results;

import java.util.List;

/**
 * Class representation of a Forge mod pack with functionality to update mods, game libraries and start the game.
 *
 * @version 1.3
 */
public class GameModPack extends GameModPackMetadata
{
    private transient GameModPackProgressProvider progressProvider  = null;
    private transient GameModPackEnvironment      environment       = null;
    private transient GameModPackLauncher         launcher          = null;

    /**
     * Returns the lazily-initialized environment handler for this modpack.
     */
    private GameModPackEnvironment getEnvironment()
    {
        if ( environment == null ) {
            environment = new GameModPackEnvironment( this );
        }
        return environment;
    }

    /**
     * Returns the lazily-initialized launcher for this modpack.
     */
    private GameModPackLauncher getLauncher()
    {
        if ( launcher == null ) {
            launcher = new GameModPackLauncher( this, progressProvider );
        }
        return launcher;
    }

    /**
     * Cached Forge mod-loader instance for this pack. Constructing a
     * {@link GameModLoaderForge} eagerly verifies the on-disk Forge installer JAR
     * (and re-downloads it on hash mismatch), so we cache the instance to avoid
     * paying that verification cost — and emitting "FILE FAILED VERIFICATION"
     * console spam — on every UI card render that asks for the pack's MC or
     * Forge version. The cache is {@code transient} so a re-fetch of the
     * modpack list still produces a fresh verification on the new instance.
     *
     * @since 3.3
     */
    private transient GameModLoaderForge cachedForgeApp;

    /**
     * Get a Forge application object referencing the Forge application jar in this modpack.
     * Cached per pack instance so repeat callers (card rendering, classpath build,
     * launch sequence) all share the same verification result.
     *
     * @return mod pack MCForgeApp
     *
     * @throws ModpackException if unable to get Forge app object
     */
    GameModLoaderForge getForgeApp() throws ModpackException {
        if ( cachedForgeApp == null ) {
            cachedForgeApp = new GameModLoaderForge( packForgeURL, packForgeHash, this );
        }
        return cachedForgeApp;
    }

    /**
     * Get the Minecraft version of this modpack.
     *
     * @return modpack Minecraft version
     *
     * @throws ModpackException if unable to get Minecraft version
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getMinecraftVersion() throws ModpackException {
        if ( vanillaVersion && vanillaMinecraftVersion != null ) {
            return vanillaMinecraftVersion;
        }
        return getForgeApp().getMinecraftVersion();
    }

    /**
     * Get the Forge version of this modpack. Vanilla packs have no Forge loader,
     * so this returns {@code null} for them instead of attempting to construct a
     * {@link GameModLoaderForge} against null URL/hash fields (which previously
     * triggered a misleading "FILE FAILED VERIFICATION" message every time the
     * main-menu card builder asked vanilla packs for a Forge version).
     *
     * @return modpack Forge version, or {@code null} for vanilla packs
     *
     * @throws ModpackException if unable to get Forge version
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getForgeVersion() throws ModpackException {
        if ( vanillaVersion ) {
            return null;
        }
        return getForgeApp().getForgeVersion();
    }

    /**
     * Get the Minecraft library manifest for this modpack.
     *
     * @return Minecraft library manifest
     *
     * @throws ModpackException if unable to get manifest
     */
    public GameLibraryManifest getMinecraftLibraryManifest() throws ModpackException {
        return GameVersionManifest.getMinecraftLibraryManifest( getMinecraftVersion(), this );
    }

    /**
     * Verify all local modpack files and downloads files if necessary, then compiles the classpath String for running
     * this modpack. Delegates to {@link GameModPackLauncher#buildClasspath()}.
     *
     * @return modpack classpath String
     *
     * @throws ModpackException if unable to update modpack or build classpath
     * @since 1.0
     */
    public String buildModpackClasspath() throws ModpackException {
        return getLauncher().buildClasspath();
    }

    /**
     * Scans the modpack root folder for any infections identified by the {@link me.cortex.jarscanner.Detector} class.
     *
     * @throws ModpackException     if any infections are found
     * @throws IOException          if any I/O errors occur while scanning
     * @throws InterruptedException if the current thread is interrupted while scanning
     */
    public void scanModPackRootFolder() throws ModpackException, IOException, InterruptedException {
        int halfCoreCount = Runtime.getRuntime().availableProcessors() / 2;
        int scanCoreCount = Math.max( 1, halfCoreCount );
        boolean emitWalkErrors = true;
        Function< String, String > logOutput = ( out ) -> {
            if ( progressProvider != null ) {
                String processedOut = out.replace( Constants.ANSI_RED, "" )
                                         .replace( Constants.ANSI_GREEN, "" )
                                         .replace( Constants.ANSI_WHITE, "" )
                                         .replace( Constants.ANSI_RESET, "" );
                progressProvider.setCurrText( processedOut );
            }
            return out;
        };

        Results scanResults = Main.run( scanCoreCount, Path.of( getPackRootFolder() ), emitWalkErrors,
                                        getPackScanExclusions(), logOutput, null );
        if ( scanResults.getStage1Detections() != null && !scanResults.getStage1Detections().isEmpty() ) {
            logOutput.apply( "Stage 1 infections found: " + scanResults.getStage1Detections().size() );
            throw new ModpackScanDetectionException( scanResults );
        }
        else if ( scanResults.getStage2Detections() != null && !scanResults.getStage2Detections().isEmpty() ) {
            logOutput.apply( "Stage 2 infections found: " + scanResults.getStage2Detections().size() );
            throw new ModpackScanDetectionException( scanResults );
        }

        // Supplemental, non-Fractureiser-specific heuristics. The Nekodetector
        // checks above are narrowly targeted at one malware family; this layer
        // adds high-signal checks for embedded executables, Discord-webhook
        // exfil endpoints, paste-host stage-2 fetchers, and a couple of
        // adjacent IoCs. HIGH-severity findings are treated as Stage 1 hits;
        // MEDIUM findings are logged but don't block launch (false-positive
        // floor is too uncertain to fail-closed on them).
        try {
            List< SupplementalScanner.Finding > extras =
                    SupplementalScanner.scanFolder( Path.of( getPackRootFolder() ),
                                                    getPackScanExclusions(), scanCoreCount );
            List< String > highHits = new java.util.ArrayList<>();
            for ( SupplementalScanner.Finding f : extras ) {
                if ( f.severity() == SupplementalScanner.Severity.HIGH ) {
                    highHits.add( f.toString() );
                    logOutput.apply( "Supplemental infection signal: " + f );
                }
                else {
                    logOutput.apply( "Supplemental warning: " + f );
                    Logger.logWarning( "Supplemental modpack scan flagged: " + f );
                }
            }
            if ( !highHits.isEmpty() ) {
                logOutput.apply( "Supplemental infections found: " + highHits.size() );
                List< String > merged = new java.util.ArrayList<>();
                if ( scanResults.getStage1Detections() != null ) {
                    merged.addAll( scanResults.getStage1Detections() );
                }
                merged.addAll( highHits );
                throw new ModpackScanDetectionException(
                        new Results( merged, scanResults.getStage2Detections() ) );
            }
        }
        catch ( IOException e ) {
            // I/O failure during the supplemental walk shouldn't abort the
            // launch on its own — the upstream Nekodetector already completed.
            Logger.logWarningSilent( "Supplemental scan I/O error: "
                                             + e.getClass().getSimpleName() );
        }
    }

    /**
     * Builds the full launch command, replaces all placeholders, and starts the game process. Delegates to
     * {@link GameModPackLauncher#launch()}.
     *
     * @throws ModpackException if unable to launch the game
     *
     * @since 1.0
     */
    public void startGame() throws ModpackException
    {
        getLauncher().launch();
    }

    /**
     * Runs a force-FULL verify across every mod, library, asset, and runtime
     * file this pack ships, then re-writes the verify-state sidecar so the
     * next launch is fast-path eligible again. Does <em>not</em> spawn the
     * game JVM — purely the integrity sweep half of the pre-launch pipeline.
     *
     * <p>Used by the per-pack "Verify this pack now" button in the modpack
     * detail modal and by the launcher-wide "Verify all game files" action
     * in Settings → Security (which iterates installed packs and calls this
     * on each).</p>
     *
     * @throws ModpackException if any verify-or-repair step fails
     * @since 2026.3
     */
    public void verifyAllFilesNow() throws ModpackException
    {
        getLauncher().verifyAllFilesNow();
    }

    /**
     * Returns the last launched game process, if available and the in-game console is enabled.
     *
     * @return the game Process, or null
     *
     * @since 3.0
     */
    public Process getLastLaunchedProcess() {
        return launcher != null ? launcher.getLastLaunchedProcess() : null;
    }

    /**
     * Finds and returns the content of the most recent Minecraft crash report in this modpack's crash-reports folder.
     *
     * @return the crash report content, or null if no crash report found
     *
     * @since 3.0
     */
    public String getLatestCrashReport() {
        File crashReportsDir = new File( getPackRootFolder(), "crash-reports" );
        if ( !crashReportsDir.exists() || !crashReportsDir.isDirectory() ) {
            return null;
        }

        File[] reports = crashReportsDir.listFiles( ( dir, name ) -> name.endsWith( ".txt" ) );
        if ( reports == null || reports.length == 0 ) {
            return null;
        }

        // Find the most recent crash report (by last modified time)
        File latest = reports[ 0 ];
        for ( File report : reports ) {
            if ( report.lastModified() > latest.lastModified() ) {
                latest = report;
            }
        }

        // Only return if the crash report was created recently (within last 60 seconds)
        if ( System.currentTimeMillis() - latest.lastModified() > 60_000 ) {
            return null;
        }

        try {
            return org.apache.commons.io.FileUtils.readFileToString( latest, "UTF-8" );
        }
        catch ( IOException e ) {
            Logger.logError( "Failed to read crash report: " + latest.getName() );
            return null;
        }
    }



    public synchronized String getPackLogoFilepath()
    {
        return getEnvironment().getPackLogoFilepath();
    }

    public synchronized String getPackBackgroundFilepath()
    {
        return getEnvironment().getPackBackgroundFilepath();
    }

    /**
     * Returns true if this pack ships its own background image (i.e. the manifest specified a
     * {@code packBackgroundURL} other than the bundled default). Vanilla versions and any
     * modded pack whose manifest left the field blank both return false here — both end up
     * with {@link ModPackConstants#MODPACK_DEFAULT_BG_URL} as their backing URL, which the
     * environment dutifully downloads into a local cached file. That cache file isn't useful
     * for hero-card rendering (the bundled-default PNG path doesn't render cleanly through
     * the {@code -fx-background-image} pipeline), so the UI layer asks this method first and
     * switches to a procedural background when it returns false.
     *
     * @return true iff the pack has its own custom background image
     *
     * @since 3.2
     */
    public boolean hasCustomBackground()
    {
        return packBackgroundURL != null
                && !packBackgroundURL.equals( ModPackConstants.MODPACK_DEFAULT_BG_URL );
    }

    /**
     * Returns true if this pack ships its own logo image (i.e. the manifest specified a
     * {@code packLogoURL} other than the bundled default). Symmetric to {@link #hasCustomBackground()}.
     *
     * @return true iff the pack has its own custom logo image
     *
     * @since 3.2
     */
    public boolean hasCustomLogo()
    {
        return packLogoURL != null
                && !packLogoURL.equals( ModPackConstants.MODPACK_DEFAULT_LOGO_URL );
    }

    /** Source URL of the pack's logo image as declared in the manifest. Distinct from
     *  {@link #getPackLogoFilepath()} — the latter triggers a download into a local cache the
     *  first time it's called, which is fine for installed packs but expensive when we have
     *  dozens of available-but-not-installed packs to render. Use this URL with JavaFX's
     *  {@code Image(url, true)} to fetch asynchronously without touching the cache machinery.
     *
     *  @since 3.2 */
    public String getPackLogoURL()
    {
        return packLogoURL;
    }

    /** Source URL of the pack's background image as declared in the manifest. Same rationale
     *  as {@link #getPackLogoURL()} — use directly with {@code Image(url, true)} to avoid the
     *  file-cache trigger that {@link #getPackBackgroundFilepath()} would cause.
     *
     *  @since 3.2 */
    public String getPackBackgroundURL()
    {
        return packBackgroundURL;
    }

    public void setProgressProvider( GameModPackProgressProvider progressProvider )
    {
        this.progressProvider = progressProvider;
        // Reset the launcher so it picks up the new progress provider
        this.launcher = null;
    }

    public synchronized void cacheImages()
    {
        getEnvironment().cacheImages();
    }

    public void prepareEnvironment()
    {
        getEnvironment().prepareEnvironment();
    }

    String manifestUrl;

    public String getManifestUrl() {
        return manifestUrl;
    }

    /** Hex SHA-256 of the manifest JSON body as it existed when this pack
     *  was loaded. Used by {@link VerifyState} to detect manifest changes
     *  between launches. Set by {@link GameModPackFetcher} after parsing;
     *  transient because it's a runtime fingerprint of the source bytes,
     *  not part of the pack content itself. May be {@code null} on packs
     *  built via the createVanillaModPack / createFailedModPack factory
     *  paths since those don't have a real manifest body. */
    private transient String manifestContentSha256;

    public String getManifestContentSha256() { return manifestContentSha256; }

    /** Package-private setter — only {@link GameModPackFetcher} populates this. */
    void setManifestContentSha256( String sha256 ) {
        this.manifestContentSha256 = sha256;
    }

    /**
     * Flag indicating this is a vanilla (non-Forge) Minecraft version.
     */
    private transient boolean vanillaVersion = false;

    /**
     * For vanilla versions, the Minecraft version ID (e.g. "1.20.4").
     */
    private transient String vanillaMinecraftVersion = null;

    /**
     * Returns true if this is a vanilla (non-Forge) Minecraft version.
     */
    public boolean isVanillaVersion() {
        return vanillaVersion;
    }

    /**
     * Creates a GameModPack representing a vanilla (non-Forge) Minecraft version.
     *
     * @param versionId the Minecraft version ID (e.g. "1.20.4")
     *
     * @return a GameModPack configured for vanilla launch
     *
     * @since 3.0
     */
    public static GameModPack createVanillaModPack( String versionId ) {
        GameModPack pack = new GameModPack();
        pack.packName = "Minecraft " + versionId;
        pack.packVersion = versionId;
        pack.packURL = "https://minecraft.net";
        pack.packLogoURL = ModPackConstants.MODPACK_DEFAULT_LOGO_URL;
        pack.packBackgroundURL = ModPackConstants.MODPACK_DEFAULT_BG_URL;
        pack.packMinRAMGB = "2";
        pack.packMods = new ArrayList<>();
        pack.vanillaVersion = true;
        pack.vanillaMinecraftVersion = versionId;
        return pack;
    }

    /**
     * Creates a GameModPack representing a failed load attempt. The pack name indicates the failure so the user can
     * see that something went wrong in the pack list.
     *
     * @param manifestUrl  the URL that failed to load
     * @param errorMessage the error message, or null
     *
     * @return a GameModPack with a descriptive error name
     *
     * @since 3.0
     */
    public static GameModPack createFailedModPack( String manifestUrl, String errorMessage )
    {
        GameModPack pack = new GameModPack();
        // Extract a short identifier from the URL for display
        String shortUrl = manifestUrl;
        if ( shortUrl.length() > 40 ) {
            shortUrl = "..." + shortUrl.substring( shortUrl.length() - 37 );
        }
        pack.packName = "[Failed to load] " + shortUrl;
        pack.packVersion = "Error";
        pack.packMinRAMGB = "2";
        pack.packLogoURL = ModPackConstants.MODPACK_DEFAULT_LOGO_URL;
        pack.packBackgroundURL = ModPackConstants.MODPACK_DEFAULT_BG_URL;
        return pack;
    }

    public static GameModPack NULL_MODPACK() {
        GameModPack nullModPack = new GameModPack();
        nullModPack.packName = "No mod packs installed!";
        nullModPack.packVersion = "N/A";
        nullModPack.packLogoURL = GUIConstants.URL_MINECRAFT_NO_MOD_PACK_IMAGE;
        return nullModPack;
    }
}

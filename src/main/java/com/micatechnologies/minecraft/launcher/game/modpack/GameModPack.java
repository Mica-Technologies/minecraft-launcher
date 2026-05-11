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
import me.cortex.jarscanner.Constants;
import me.cortex.jarscanner.Main;
import me.cortex.jarscanner.Results;

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
     * Get a Forge application object referencing the Forge application jar in this modpack.
     *
     * @return mod pack MCForgeApp
     *
     * @throws ModpackException if unable to get Forge app object
     */
    GameModLoaderForge getForgeApp() throws ModpackException {
        return new GameModLoaderForge( packForgeURL, packForgeHash, this );
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
     * Get the Forge version of this modpack.
     *
     * @return modpack Forge version
     *
     * @throws ModpackException if unable to get Forge version
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getForgeVersion() throws ModpackException {
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

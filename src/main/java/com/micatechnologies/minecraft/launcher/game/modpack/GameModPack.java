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
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Function;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackScanDetectionException;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.RuntimeManager;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameLibraryManifest;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameVersionManifest;
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import me.cortex.jarscanner.Constants;
import me.cortex.jarscanner.Main;
import me.cortex.jarscanner.Results;
import org.apache.commons.io.FilenameUtils;

/**
 * Class representation of a Forge mod pack with functionality to update mods, game libraries and start the game.
 *
 * @version 1.3
 */
public class GameModPack
{

    /**
     * Mod pack name. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packName;

    /**
     * Mod pack version. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packVersion;

    /**
     * Mod pack website URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packURL;

    /**
     * Mod pack unstable flag. Value read from manifest JSON.
     *
     * @since 1.2
     */
    @SuppressWarnings( "unused" )
    private boolean packUnstable;

    /**
     * Mod pack custom Discord RPC flag. Value read from manifest JSON.
     *
     * @since 1.2
     */
    @SuppressWarnings( "unused" )
    private boolean packCustomDiscordRpc;

    /**
     * Mod pack logo URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packLogoURL;

    /**
     * Mod pack logo SHA-1. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packLogoSha1;

    /**
     * Mod pack background URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packBackgroundURL;

    /**
     * Mod pack background SHA-1. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packBackgroundSha1;

    /**
     * Mod pack minimum RAM (GB). Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packMinRAMGB;

    /**
     * Mod pack Forge download URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packForgeURL;

    /**
     * Mod pack Forge download SHA-1 hash. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packForgeHash;

    /**
     * Mod pack scan exclusions (file or folder names, relative to mod pack root). Value read from manifest JSON.
     *
     * @since 1.3
     */
    @SuppressWarnings( "unused" )
    private List< String > packScanExclusions;

    /**
     * List of mod pack Forge mods. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    private List< GameMod > packMods;

    /**
     * List of mod pack Forge configs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    private List< GameAsset > packConfigs;

    /**
     * List of mod pack resource packs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    private List< ManagedGameFile > packResourcePacks;

    /**
     * List of mod pack shader packs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    private List< ManagedGameFile > packShaderPacks;

    /**
     * List of initial files for mod pack. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    private List< GameAsset > packInitialFiles;

    private transient          GameModPackProgressProvider progressProvider = null;
    private transient volatile boolean                     didCacheImages   = false;

    /**
     * Get the installation folder of this mod pack.
     *
     * @return installation folder Path
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getPackRootFolder() {
        return LocalPathManager.getLauncherModpackFolderPath() + File.separator + getPackSanitizedName();
    }

    public String getPackSanitizedName() {
        return getPackName().replaceAll( "[^a-zA-Z0-9]", "" );
    }

    public String getPackBinFolder() {
        return SystemUtilities.buildFilePath( getPackRootFolder(), LocalPathConstants.MOD_PACK_BIN_FOLDER_NAME );
    }

    /**
     * Get a Forge application object referencing the Forge application jar in this modpack.
     *
     * @return mod pack MCForgeApp
     *
     * @throws ModpackException if unable to get Forge app object
     */
    private GameModLoaderForge getForgeApp() throws ModpackException {
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

    public double getPackMinRAMGB() {
        return Double.parseDouble( packMinRAMGB );
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
     * Get the mod pack scan exclusions (file or folder names, relative to mod pack root) for this modpack.
     *
     * @return list of scan exclusions
     *
     * @since 1.3
     */
    public List< String > getPackScanExclusions() {
        if ( packScanExclusions == null ) {
            packScanExclusions = new ArrayList<>();
        }
        return packScanExclusions;
    }

    /**
     * Verify all local modpack files and downloads files if necessary, then compiles the classpath String for running
     * this modpack.
     *
     * @return modpack classpath String
     *
     * @throws ModpackException if unable to update modpack or build classpath
     * @since 1.0
     */
    public String buildModpackClasspath() throws ModpackException {
        if ( progressProvider != null ) {
            progressProvider.setCurrText( "Preparing modpack..." );
        }

        String forgeAssetClasspath = "";

        if ( !vanillaVersion ) {
            // --- Step 1: Download modpack content (mods, configs, resources) ---
            if ( progressProvider != null ) {
                progressProvider.startProgressSection( "Downloading mods...", 15.0 );
            }
            fetchLatestMods();
            if ( progressProvider != null ) {
                progressProvider.endProgressSection( "Mods ready" );
            }

            if ( progressProvider != null ) {
                progressProvider.startProgressSection( "Downloading configs and resources...", 5.0 );
            }
            fetchLatestConfigs();
            fetchLatestResourcePacks();
            fetchLatestShaderPacks();
            fetchLatestInitialFiles();
            cacheImages();
            if ( progressProvider != null ) {
                progressProvider.endProgressSection( "Configs and resources ready" );
            }

            // --- Step 2: Download Forge libraries ---
            if ( progressProvider != null ) {
                progressProvider.startProgressSection( "Downloading Forge libraries...", 15.0 );
            }
            forgeAssetClasspath = getForgeApp().buildForgeClasspath( GameModeManager.getCurrentGameMode(),
                                                                     progressProvider );
            if ( progressProvider != null ) {
                progressProvider.endProgressSection( "Forge libraries ready" );
            }
        }

        // --- Step 3: Download Minecraft libraries and assets ---
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Downloading Minecraft libraries and assets...",
                                                    vanillaVersion ? 40.0 : 20.0 );
        }
        GameLibraryManifest libraryManifest = GameVersionManifest.getMinecraftLibraryManifest( getMinecraftVersion(),
                                                                                               this );
        String minecraftAssetClasspath = libraryManifest.buildMinecraftClasspath( GameModeManager.getCurrentGameMode(),
                                                                                  progressProvider );
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Minecraft libraries and assets ready" );
        }

        // --- Step 4: Install Java runtime ---
        String procRuntimeComponent = libraryManifest.getRequiredRuntimeComponent();
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Installing Java runtime (" + procRuntimeComponent + ")...",
                                                    vanillaVersion ? 20.0 : 15.0 );
        }
        RuntimeManager.verifyRuntime( procRuntimeComponent, false,
                                       progressProvider != null ? progressProvider::setCurrText : null );
        if ( progressProvider != null ) {
            progressProvider.submitProgress( "Java runtime ready", 100.0 );
            progressProvider.endProgressSection( "Java runtime ready" );
        }

        // --- Step 5: Run Forge install processors (if needed, Forge only) ---
        if ( !vanillaVersion ) {
            if ( progressProvider != null ) {
                progressProvider.startProgressSection( "Patching game files...", 10.0 );
            }
            getForgeApp().runForgeProcessors( GameModeManager.getCurrentGameMode(), progressProvider,
                                              procRuntimeComponent );
            if ( progressProvider != null ) {
                progressProvider.endProgressSection( "Game files patched" );
            }
        }

        // --- Step 6: Security scan ---
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Scanning for malware...", 10.0 );
        }
        try {
            scanModPackRootFolder();
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to scan downloaded files due to an exception!", e );
        }
        catch ( InterruptedException e ) {
            throw new ModpackException( "Unable to scan downloaded files due to an interruption!", e );
        }
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Security scan complete" );
        }

        // Combine classpaths
        if ( !forgeAssetClasspath.isEmpty() && !forgeAssetClasspath.endsWith( File.pathSeparator ) ) {
            forgeAssetClasspath += File.pathSeparator;
        }

        return forgeAssetClasspath + minecraftAssetClasspath;
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

    public void startGame() throws ModpackException
    {
        // Get classpath, main class and Minecraft args
        String cp = buildModpackClasspath();

        // Determine the required Java version from the Minecraft version manifest
        GameLibraryManifest libraryManifest = getMinecraftLibraryManifest();

        String minecraftMainClass;
        String minecraftArgs = "";

        if ( vanillaVersion ) {
            // Vanilla launch: use mainClass and args from the vanilla manifest
            try {
                minecraftMainClass = libraryManifest.getVanillaMainClass();
                if ( minecraftMainClass == null ) {
                    minecraftMainClass = "net.minecraft.client.main.Main";
                }
            }
            catch ( Exception e ) {
                minecraftMainClass = "net.minecraft.client.main.Main";
            }

            if ( GameModeManager.isClient() ) {
                minecraftArgs = libraryManifest.getGameArguments();
            }
        }
        else {
            // Forge launch
            minecraftMainClass = GameModeManager.isClient() ?
                                 getForgeApp().getMinecraftMainClass() :
                                 "net.minecraftforge.fml.relauncher.ServerLaunchWrapper";

            // Build game arguments: combine vanilla game args with Forge-specific game args
            if ( GameModeManager.isClient() ) {
                String vanillaGameArgs = libraryManifest.getGameArguments();
                String forgeGameArgs = getForgeApp().getMinecraftArguments();

                if ( !forgeGameArgs.isEmpty() && !vanillaGameArgs.isEmpty() &&
                        !forgeGameArgs.contains( "${auth_player_name}" ) ) {
                    minecraftArgs = vanillaGameArgs + " " + forgeGameArgs;
                }
                else if ( !forgeGameArgs.isEmpty() ) {
                    minecraftArgs = forgeGameArgs;
                }
                else {
                    minecraftArgs = vanillaGameArgs;
                }
            }
        }
        String runtimeComponent = libraryManifest.getRequiredRuntimeComponent();
        int requiredJavaMajorVersion = libraryManifest.getRequiredJavaMajorVersion();
        Logger.logStd( "Minecraft version " + getMinecraftVersion() + " requires runtime " + runtimeComponent +
                               " (Java " + requiredJavaMajorVersion + ")" );

        // Ensure the required Java runtime is available (should already be verified by buildModpackClasspath,
        // but this call is cheap if already cached)
        RuntimeManager.verifyRuntime( runtimeComponent, false );

        if ( progressProvider != null ) {
            progressProvider.setCurrText( "Preparing launch command..." );
        }

        // Build JVM arguments string
        StringBuilder jvmArgs = new StringBuilder();

        // Add custom user JVM args first
        String customJvmArgs = ConfigManager.getCustomJvmArgs();
        if ( customJvmArgs != null && !customJvmArgs.isBlank() ) {
            jvmArgs.append( customJvmArgs ).append( " " );
        }

        // Add min and max RAM
        long minRAMMB = ConfigManager.getMinRam();
        long maxRAMMB = ConfigManager.getMaxRam();
        if ( GameModeManager.isServer() ) {
            RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
            List< String > aList = bean.getInputArguments();
            for ( String s : aList ) {
                if ( s.contains( "Xms" ) ) {
                    minRAMMB = Integer.parseInt( s.replaceAll( "\\D+", "" ) );
                    Logger.logDebug( "Configuring min RAM from provided " + s );
                }
                if ( s.contains( "Xmx" ) ) {
                    maxRAMMB = Integer.parseInt( s.replaceAll( "\\D+", "" ) );
                    Logger.logDebug( "Configuring max RAM from provided " + s );
                }
            }
        }
        jvmArgs.append( "-Xms" ).append( minRAMMB ).append( "m " );
        jvmArgs.append( "-Xmx" ).append( maxRAMMB ).append( "m " );

        // Handle logging configuration using Mojang's security-patched log4j configs
        // (CVE-2021-44228 / Log4Shell mitigation). These also use PatternLayout instead of XMLLayout
        // for the console appender, preventing XML clutter in stdout.
        // See: https://www.minecraft.net/en-us/article/important-message--security-vulnerability-java-edition
        applyLog4jSecurityConfig( jvmArgs, getMinecraftVersion() );

        // Add natives path
        String nativesFolder = getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER;
        File nativesFolderFile = SynchronizedFileManager.getSynchronizedFile( nativesFolder );
        if ( nativesFolderFile.exists() ) {
            nativesFolderFile.setExecutable( true );
            nativesFolderFile.setReadable( true );
            nativesFolderFile.setWritable( true );
            File[] nativeFiles = nativesFolderFile.listFiles();
            if ( nativeFiles != null ) {
                for ( File f : nativeFiles ) {
                    f.setExecutable( true );
                    f.setReadable( true );
                    f.setWritable( true );
                }
            }
        }

        // Add manifest JVM arguments (from modern arguments.jvm if available)
        String manifestJvmArgs = libraryManifest.getJvmArguments();
        if ( !manifestJvmArgs.isEmpty() ) {
            jvmArgs.append( manifestJvmArgs ).append( " " );
        }

        // Add Forge-specific JVM arguments (e.g. module system flags for modern Forge)
        if ( !vanillaVersion && GameModeManager.isClient() ) {
            String forgeJvmArgs = getForgeApp().getForgeJvmArguments();
            if ( !forgeJvmArgs.isEmpty() ) {
                jvmArgs.append( forgeJvmArgs ).append( " " );
            }
        }

        if ( manifestJvmArgs.isEmpty() ) {
            // Legacy versions don't specify JVM args in the manifest; add essential ones manually
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
                jvmArgs.append( "-Djava.library.path=\"" ).append( nativesFolder ).append( "\" " );
                jvmArgs.append( "-cp \"" ).append( cp ).append( "\" " );
            }
            else {
                jvmArgs.append( "-Djava.library.path=" ).append( nativesFolder ).append( " " );
                jvmArgs.append( "-cp " ).append( cp ).append( " " );
            }
        }

        // Add main class
        jvmArgs.append( minecraftMainClass ).append( " " );

        // Add game arguments
        jvmArgs.append( minecraftArgs );

        String fullArgs = jvmArgs.toString();

        // Replace JVM placeholders (from modern arguments.jvm)
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            fullArgs = fullArgs.replace( "${natives_directory}", "\"" + nativesFolder + "\"" );
            fullArgs = fullArgs.replace( "${classpath}", "\"" + cp + "\"" );
        }
        else {
            fullArgs = fullArgs.replace( "${natives_directory}", nativesFolder );
            fullArgs = fullArgs.replace( "${classpath}", cp );
        }
        fullArgs = fullArgs.replace( "${launcher_name}", "MicaMinecraftLauncher" );
        fullArgs = fullArgs.replace( "${launcher_version}", "2025.1" );
        fullArgs = fullArgs.replace( "${version_type}", "release" );
        fullArgs = fullArgs.replace( "${classpath_separator}", File.pathSeparator );
        fullArgs = fullArgs.replace( "${library_directory}", getPackRootFolder() + File.separator +
                ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );

        // Replace game argument placeholders
        if ( GameModeManager.isClient() ) {
            fullArgs = fullArgs.replace( "${auth_player_name}",
                                          MCLauncherAuthManager.getLoggedInUser().name() );
            fullArgs = fullArgs.replace( "${version_name}",
                                          vanillaVersion ? getMinecraftVersion() : getForgeVersion() );
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
                fullArgs = fullArgs.replace( "${game_directory}", "\"" + getPackRootFolder() + "\"" );
                fullArgs = fullArgs.replace( "${assets_root}", "\"" +
                        getPackRootFolder() +
                        File.separator +
                        ModPackConstants.MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER +
                        "\"" );
            }
            else {
                fullArgs = fullArgs.replace( "${game_directory}", getPackRootFolder() );
                fullArgs = fullArgs.replace( "${assets_root}", getPackRootFolder() +
                        File.separator +
                        ModPackConstants.MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER );
            }

            fullArgs = fullArgs.replace( "${assets_index_name}", libraryManifest.getAssetIndexVersion() );
            fullArgs = fullArgs.replace( "${auth_uuid}", MCLauncherAuthManager.getLoggedInUser().uuid() );
            fullArgs = fullArgs.replace( "${auth_access_token}",
                                          MCLauncherAuthManager.getLoggedInUser().accessToken() );
            fullArgs = fullArgs.replace( "${user_type}", "mojang" );
            fullArgs = fullArgs.replace( "${clientid}", "" );
            fullArgs = fullArgs.replace( "${auth_xuid}", "" );
            fullArgs = fullArgs.replace( "${user_properties}", "{}" );

            // Add title and icon to arguments
            fullArgs += " --title \"" + packName + "\"";
            fullArgs += " --icon \"" + getPackLogoFilepath() + "\"";

            // Set dock name and icon for macOS
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
                fullArgs += " -Xdock:icon=\"" + getPackLogoFilepath() + "\"";
                fullArgs += " -Xdock:name=\"" + getPackName() + "\" ";
                fullArgs += "-Dapple.laf.useScreenMenuBar=true ";
                fullArgs += "-Djdk.lang.Process.launchMechanism=vfork ";
            }
        }

        // Add java executable path to front of args
        fullArgs = RuntimeManager.getJavaPath( runtimeComponent ) + " " + fullArgs;

        // Signal completion to trigger the progress window hide
        if ( progressProvider != null ) {
            progressProvider.signalComplete( "Starting Minecraft..." );
        }

        // Start game (always non-blocking -- LauncherCore.play() handles the process lifecycle)
        try {
            Logger.logDebug( "Launching game with command: " + fullArgs );
            lastLaunchedProcess = SystemUtilities.launchCommand( fullArgs, getPackRootFolder() );
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to execute mod pack game.", e );
        }
    }

    /**
     * Returns the last launched game process, if available and the in-game console is enabled.
     *
     * @return the game Process, or null
     *
     * @since 3.0
     */
    public Process getLastLaunchedProcess() {
        return lastLaunchedProcess;
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

    private transient Process lastLaunchedProcess = null;

    /**
     * Remove all mods from this modpack mods folder that are not in the modpack.
     *
     * @since 1.0
     */
    /**
     * Mojang security-patched log4j config for MC 1.12-1.16.5. Uses PatternLayout with {@code %msg{nolookups}} to
     * mitigate CVE-2021-44228 (Log4Shell).
     */
    private static final String LOG4J_CONFIG_112_116_URL
            = "https://launcher.mojang.com/v1/objects/02937d122c86ce73319ef9975b58896fc1b491d1/log4j2_112-116.xml";
    private static final String LOG4J_CONFIG_112_116_SHA1 = "02937d122c86ce73319ef9975b58896fc1b491d1";
    private static final String LOG4J_CONFIG_112_116_NAME = "log4j2_112-116.xml";

    /**
     * Mojang security-patched log4j config for MC 1.7-1.11.2. Uses RegexFilter to deny {@code ${...}} patterns
     * since older Log4j doesn't support {@code {nolookups}}.
     */
    private static final String LOG4J_CONFIG_17_111_URL
            = "https://launcher.mojang.com/v1/objects/4bb89a97a66f350bc9f73b3ca8509632682aea2e/log4j2_17-111.xml";
    private static final String LOG4J_CONFIG_17_111_SHA1 = "4bb89a97a66f350bc9f73b3ca8509632682aea2e";
    private static final String LOG4J_CONFIG_17_111_NAME = "log4j2_17-111.xml";

    /**
     * Applies the correct Mojang security-patched log4j configuration based on the Minecraft version.
     * <ul>
     *   <li>MC 1.7 - 1.11.2: Uses {@code log4j2_17-111.xml} with RegexFilter</li>
     *   <li>MC 1.12 - 1.16.5: Uses {@code log4j2_112-116.xml} with {@code %msg{nolookups}}</li>
     *   <li>MC 1.17+: Uses {@code -Dlog4j2.formatMsgNoLookups=true} JVM flag (built-in support)</li>
     * </ul>
     */
    private void applyLog4jSecurityConfig( StringBuilder jvmArgs, String mcVersion ) {
        // Parse the major and minor version numbers from the MC version string (e.g. "1.12.2" -> major=1, minor=12)
        int minor = 0;
        try {
            String[] parts = mcVersion.split( "\\." );
            if ( parts.length >= 2 ) {
                minor = Integer.parseInt( parts[ 1 ] );
            }
        }
        catch ( NumberFormatException e ) {
            Logger.logWarningSilent( "Could not parse Minecraft version for log4j config: " + mcVersion );
        }

        // Always add the safety flag as a baseline (no-op if the config file is also applied)
        jvmArgs.append( "-Dlog4j2.formatMsgNoLookups=true " );

        if ( minor >= 17 ) {
            // MC 1.17+: The JVM flag above is sufficient, no config file needed
            Logger.logDebug( "MC " + mcVersion + ": Using log4j2.formatMsgNoLookups=true (1.17+ built-in support)" );
        }
        else if ( minor >= 12 ) {
            // MC 1.12 - 1.16.5: Download and apply the security-patched config
            applyLog4jConfigFile( jvmArgs, LOG4J_CONFIG_112_116_URL, LOG4J_CONFIG_112_116_SHA1,
                                  LOG4J_CONFIG_112_116_NAME );
        }
        else if ( minor >= 7 ) {
            // MC 1.7 - 1.11.2: Download and apply the older security-patched config
            applyLog4jConfigFile( jvmArgs, LOG4J_CONFIG_17_111_URL, LOG4J_CONFIG_17_111_SHA1,
                                  LOG4J_CONFIG_17_111_NAME );
        }
        // MC < 1.7: Not affected by Log4Shell
    }

    /**
     * Downloads a log4j config file and adds the JVM argument to use it.
     */
    private void applyLog4jConfigFile( StringBuilder jvmArgs, String url, String sha1, String fileName ) {
        String logConfigPath = getPackRootFolder() + File.separator + "bin" + File.separator + fileName;
        try {
            ManagedGameFile logConfigFile = new ManagedGameFile( url, logConfigPath, sha1,
                                                                 ManagedGameFile.ManagedGameFileHashType.SHA1 );
            logConfigFile.updateLocalFile();
            jvmArgs.append( "-Dlog4j.configurationFile=" ).append( logConfigPath ).append( " " );
            Logger.logDebug( "Applied Mojang security log4j config: " + fileName );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Failed to download Mojang log4j config " + fileName +
                                             ", relying on formatMsgNoLookups flag." );
        }
    }

    private void clearFloatingMods() {
        // Get full path to modpack mods folder
        String modLocalPathPrefix = getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Build list of valid mods
        ArrayList< String > validModPaths = new ArrayList<>();
        for ( GameMod mod : packMods ) {
            if ( ( GameModeManager.isClient() && mod.clientReq ) || ( GameModeManager.isServer() && mod.serverReq ) ) {
                mod.setLocalPathPrefix( modLocalPathPrefix );
                validModPaths.add( mod.getFullLocalFilePath() );
            }
        }

        // Loop through files in mods folder and remove unwanted
        File modpackModsFolderFile = SynchronizedFileManager.getSynchronizedFile( modLocalPathPrefix );
        File[] modsFolderFiles = modpackModsFolderFile.listFiles();
        if ( modsFolderFiles != null ) {
            for ( File modFile : modsFolderFiles ) {
                if ( !validModPaths.contains( modFile.getPath() ) && modFile.isFile() ) {
                    boolean delete = modFile.delete();
                    if ( !delete ) {
                        Logger.logError( "Unable to delete file during mod folder sanitization." );
                    }
                }
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's mods and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest mods
     */
    private void fetchLatestMods() throws ModpackException {
        // Cleanup mods that don't belong
        clearFloatingMods();
        if ( progressProvider != null ) {
            progressProvider.submitProgress( "Removed floating mods", 30.0 );
        }

        // Check if mods supplied
        if ( packMods == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No mods to handle", 100 );
            }
            return;
        }

        // Get full path to mods folder
        String modLocalPathPrefix = getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Build list of mod download threads
        if ( packMods.size() > 1 ) {
            ExecutorService threadPool = Executors.newFixedThreadPool( Math.min( packMods.size(), 16 ) );
            List< Future< Boolean > > threadPoolFutures = new ArrayList<>();
            for ( GameMod mod : packMods ) {
                Callable< Boolean > updateFileCallable = () -> {
                    mod.setLocalPathPrefix( modLocalPathPrefix );

                    if ( progressProvider != null ) {
                        progressProvider.submitProgress( "Verifying " + mod.name,
                                                         ( 70.0 / ( double ) packMods.size() ) );
                    }

                    boolean ret = mod.updateLocalFile( GameModeManager.getCurrentGameMode() );

                    if ( progressProvider != null ) {
                        progressProvider.setCurrText( "Verified " + mod.name );
                    }
                    return ret;
                };
                Future< Boolean > future = threadPool.submit( updateFileCallable );
                threadPoolFutures.add( future );
            }
            threadPool.shutdown();
            try {
                if ( !threadPool.awaitTermination( 30, TimeUnit.MINUTES ) ) {
                    threadPool.shutdownNow();
                    throw new ModpackException(
                            "Mod downloads did not complete within 30 minutes. Check your network connection." );
                }
            }
            catch ( InterruptedException e ) {
                threadPool.shutdownNow();
                throw new ModpackException( "The download of Minecraft mods was interrupted before completion!", e );
            }

            // Parse list of futures
            for ( Future< Boolean > threadPoolFuture : threadPoolFutures ) {
                try {
                    threadPoolFuture.get();
                }
                catch ( InterruptedException e ) {
                    throw new ModpackException( "The download of Minecraft mods was interrupted before completion!",
                                                e );
                }
                catch ( ExecutionException e ) {
                    throw new ModpackException( "Unable to execute runner to retrieve Minecraft mods!", e );
                }
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's configs and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest configs
     */
    private void fetchLatestConfigs() throws ModpackException {
        // Get full path to configs folder
        String configLocalPathPrefix = getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_CONFIGS_LOCAL_FOLDER;

        // Check if configs supplied
        if ( packConfigs == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No configs to handle", 100 );
            }
            return;
        }

        // Update each config if necessary
        for ( GameAsset config : packConfigs ) {
            config.setLocalPathPrefix( configLocalPathPrefix );
            config.updateLocalFile( GameModeManager.getCurrentGameMode() );

            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified " + FilenameUtils.getName( config.getFullLocalFilePath() ),
                                                 ( 100.0 / ( double ) packConfigs.size() ) );
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's resource packs and repair/download/update as
     * necessary.
     *
     * @throws ModpackException if unable to fetch latest resource packs
     */
    private void fetchLatestResourcePacks() throws ModpackException {
        // Only download resource packs on client (not server)
        if ( GameModeManager.isServer() ) {
            return;
        }

        // Check if resource packs supplied
        if ( packResourcePacks == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No resource packs to handle", 100 );
            }
            return;
        }

        // Get full path to resource pack folder
        String resPackLocalPathPrefix = getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_RESOURCEPACKS_LOCAL_FOLDER;

        // Update each resource pack if necessary
        for ( ManagedGameFile resourcePack : packResourcePacks ) {
            resourcePack.setLocalPathPrefix( resPackLocalPathPrefix );
            resourcePack.updateLocalFile();
            if ( progressProvider != null ) {
                progressProvider.submitProgress(
                        "Verified " + FilenameUtils.getName( resourcePack.getFullLocalFilePath() ),
                        ( 100.0 / ( double ) packResourcePacks.size() ) );
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's shader packs and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest shader packs
     */
    private void fetchLatestShaderPacks() throws ModpackException {
        // Only download shader packs on client (not server)
        if ( GameModeManager.isServer() ) {
            return;
        }

        // Check if shader packs supplied
        if ( packShaderPacks == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No shader packs to handle", 100 );
            }
            return;
        }

        // Get full path to shader pack folder
        String shaderPackLocalPathPrefix = getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_SHADERPACKS_LOCAL_FOLDER;

        // Update each shader pack if necessary
        for ( ManagedGameFile shaderPack : packShaderPacks ) {
            shaderPack.setLocalPathPrefix( shaderPackLocalPathPrefix );
            shaderPack.updateLocalFile();
            if ( progressProvider != null ) {
                progressProvider.submitProgress(
                        "Verified " + FilenameUtils.getName( shaderPack.getFullLocalFilePath() ),
                        ( 100.0 / ( double ) packShaderPacks.size() ) );
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's initial files and repair/download/updated as
     * necessary.
     *
     * @throws ModpackException if unable to fetch latest initial files
     */
    private void fetchLatestInitialFiles() throws ModpackException {
        // Get full path to configs folder
        String initFilesLocalPathPrefix = getPackRootFolder();

        // Note: log4j security configs are now downloaded and applied in applyLog4jSecurityConfig()
        // during startGame(), using the correct version-specific config for the Minecraft version.

        // Check if initial files supplied
        if ( packInitialFiles == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No initial files to handle", 100 );
            }
            return;
        }

        // Update each initial file if necessary
        for ( GameAsset initFile : packInitialFiles ) {
            initFile.setLocalPathPrefix( initFilesLocalPathPrefix );
            initFile.updateLocalFile( GameModeManager.getCurrentGameMode() );

            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified " + FilenameUtils.getName( initFile.getFullLocalFilePath() ),
                                                 ( 100.0 / ( double ) packInitialFiles.size() ) );
            }
        }
    }

    public synchronized String getPackLogoFilepath() {
        if ( !didCacheImages ) {
            cacheImages();
        }
        return getRawLogoFilePath();
    }

    public synchronized String getPackBackgroundFilepath() {
        if ( !didCacheImages ) {
            cacheImages();
        }
        return getRawBackgroundFilePath();
    }

    public void setProgressProvider( GameModPackProgressProvider progressProvider ) {
        this.progressProvider = progressProvider;
    }

    private String getRawLogoFilePath() {
        String filename;
        if ( packLogoSha1 != null ) {
            filename = packLogoSha1 + ".png";
        }
        else {
            filename = "logo_" + getPackSanitizedName() + ".png";
        }
        return LocalPathManager.getLauncherMetadataFolderPath() + File.separator + filename;
    }

    private String getRawBackgroundFilePath() {
        String filename;
        if ( packBackgroundSha1 != null ) {
            filename = packBackgroundSha1 + ".png";
        }
        else {
            filename = "background_" + getPackSanitizedName() + ".png";
        }
        return LocalPathManager.getLauncherMetadataFolderPath() + File.separator + filename;
    }

    /**
     * Download the mod pack's logo from the URL specified in the manifest
     *
     * @throws ModpackException if unable to download/fetch logo
     */
    private synchronized void fetchLatestModpackLogo() throws ModpackException {
        // Download latest logo
        try {
            File syncFile = SynchronizedFileManager.getSynchronizedFile( getRawLogoFilePath() );
            boolean redownload = false;
            if ( packLogoSha1 == null ) {
                redownload = true;
            }
            else if ( !HashUtilities.verifySHA1( syncFile, packLogoSha1 ) ) {
                redownload = true;
            }
            if ( redownload ) {
                NetworkUtilities.downloadFileFromURL(
                        new URL( Objects.requireNonNullElse( packLogoURL, ModPackConstants.MODPACK_DEFAULT_LOGO_URL ) ),
                        syncFile );
            }
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to download/fetch mod pack logo.", e );
        }
    }

    /**
     * Download the mod pack's logo from the URL specified in the manifest
     *
     * @throws ModpackException if unable to download/fetch logo
     */
    private synchronized void fetchLatestModpackBackground() throws ModpackException {
        // Download latest background
        try {
            File syncFile = SynchronizedFileManager.getSynchronizedFile( getRawBackgroundFilePath() );
            boolean redownload = false;
            if ( packBackgroundSha1 == null ) {
                redownload = true;
            }
            else if ( !HashUtilities.verifySHA1( syncFile, packBackgroundSha1 ) ) {
                redownload = true;
            }
            if ( redownload ) {
                NetworkUtilities.downloadFileFromURL( new URL(
                                                              Objects.requireNonNullElse( packBackgroundURL, ModPackConstants.MODPACK_DEFAULT_BG_URL ) ),
                                                      syncFile );
            }
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to download/fetch mod pack background.", e );
        }
    }

    public synchronized void cacheImages() {
        try {
            fetchLatestModpackLogo();
            fetchLatestModpackBackground();
            didCacheImages = true;
        }
        catch ( Exception e ) {
            didCacheImages = false;
            Logger.logError( "Unable to download image assets for mod pack: " + getFriendlyName() );
            Logger.logThrowable( e );
        }
    }

    /**
     * Get and return the name of this modpack
     *
     * @return modpack name
     */
    public String getPackName() {
        return packName;
    }

    public void prepareEnvironment() {
        // Ensure local paths exist
        File binPath = SynchronizedFileManager.getSynchronizedFile( getPackRootFolder() + File.separator + "bin" );
        File modsPath = SynchronizedFileManager.getSynchronizedFile( getPackRootFolder() + File.separator + "mods" );
        File configPath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "config" );
        File nativePath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "bin" + File.separator + "natives" );
        File resPackPath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "resourcepacks" );
        File shaderPackPath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "shaderpacks" );

        //noinspection ResultOfMethodCallIgnored
        binPath.getParentFile().mkdirs();

        if ( !binPath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            binPath.mkdir();
        }
        if ( !modsPath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            modsPath.mkdir();
        }
        if ( !configPath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            configPath.mkdir();
        }
        if ( !nativePath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            nativePath.mkdir();
        }
        if ( !resPackPath.exists() && GameModeManager.isClient() ) {
            //noinspection ResultOfMethodCallIgnored
            resPackPath.mkdir();
        }
        if ( !shaderPackPath.exists() && GameModeManager.isClient() ) {
            //noinspection ResultOfMethodCallIgnored
            shaderPackPath.mkdir();
        }
    }

    String manifestUrl;

    public String getManifestUrl() {
        return manifestUrl;
    }

    public String getPackVersion() {
        return packVersion;
    }

    public boolean getPackUnstable() {
        return packUnstable;
    }

    public boolean getCustomDiscordRpc() {
        return packCustomDiscordRpc;
    }

    public String getPackURL() {
        return packURL;
    }

    public String getFriendlyName() {
        return getPackName() != null ?
               String.format( ModPackConstants.MODPACK_FRIENDLY_NAME_TEMPLATE, getPackName(), getPackVersion() ) :
               null;
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
        pack.didCacheImages = false;
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

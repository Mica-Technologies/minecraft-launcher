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
import java.util.List;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.RuntimeManager;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameLibraryManifest;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameVersionManifest;
import com.micatechnologies.minecraft.launcher.utilities.ProcessUtilities;

/**
 * Encapsulates the game launching logic for a {@link GameModPack}, including classpath assembly, JVM argument
 * construction, log4j security configuration, and process creation.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
class GameModPackLauncher
{
    /**
     * The modpack instance this launcher operates on.
     */
    private final GameModPack pack;

    /**
     * Optional progress provider for reporting launch progress to the UI.
     */
    private final GameModPackProgressProvider progressProvider;

    /**
     * The last launched game process, if any.
     */
    private Process lastLaunchedProcess = null;

    // region Log4j security config constants

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

    // endregion

    /**
     * Constructs a new launcher for the given modpack.
     *
     * @param pack             the modpack to launch
     * @param progressProvider optional progress provider, may be null
     *
     * @since 3.0
     */
    GameModPackLauncher( GameModPack pack, GameModPackProgressProvider progressProvider )
    {
        this.pack = pack;
        this.progressProvider = progressProvider;
    }

    /**
     * Returns the last launched game process, if available.
     *
     * @return the game Process, or null
     *
     * @since 3.0
     */
    Process getLastLaunchedProcess()
    {
        return lastLaunchedProcess;
    }

    /**
     * Verify all local modpack files, download files if necessary, and compile the classpath String for running this
     * modpack.
     *
     * @return modpack classpath String
     *
     * @throws ModpackException if unable to update modpack or build classpath
     *
     * @since 3.0
     */
    String buildClasspath() throws ModpackException
    {
        if ( progressProvider != null ) {
            progressProvider.setCurrText( "Preparing modpack..." );
        }

        String forgeAssetClasspath = "";

        if ( !pack.isVanillaVersion() ) {
            // --- Step 1: Download modpack content (mods, configs, resources) ---
            GameModPackFileSync fileSync = new GameModPackFileSync( pack, progressProvider );
            if ( progressProvider != null ) {
                progressProvider.startProgressSection( "Downloading mods...", 15.0 );
            }
            fileSync.fetchLatestMods();

            // Disable mods known to be incompatible with ARM64 (missing native libraries)
            if ( Lwjgl2ArmPatcher.isNeeded( pack.getMinecraftVersion() ) ) {
                Lwjgl2ArmPatcher.disableIncompatibleMods( pack.getPackRootFolder() + File.separator + "mods" );
            }

            if ( progressProvider != null ) {
                progressProvider.endProgressSection( "Mods ready" );
            }

            if ( progressProvider != null ) {
                progressProvider.startProgressSection( "Downloading configs and resources...", 5.0 );
            }
            fileSync.fetchLatestConfigs();
            fileSync.fetchLatestResourcePacks();
            fileSync.fetchLatestShaderPacks();
            fileSync.fetchLatestInitialFiles();
            pack.cacheImages();
            if ( progressProvider != null ) {
                progressProvider.endProgressSection( "Configs and resources ready" );
            }

            // --- Step 2: Download Forge libraries ---
            if ( progressProvider != null ) {
                progressProvider.startProgressSection( "Downloading Forge libraries...", 15.0 );
            }
            forgeAssetClasspath = pack.getForgeApp().buildForgeClasspath( GameModeManager.getCurrentGameMode(),
                                                                          progressProvider );
            if ( progressProvider != null ) {
                progressProvider.endProgressSection( "Forge libraries ready" );
            }
        }

        // --- Step 3: Download Minecraft libraries and assets ---
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Downloading Minecraft libraries and assets...",
                                                    pack.isVanillaVersion() ? 40.0 : 20.0 );
        }
        GameLibraryManifest libraryManifest = GameVersionManifest.getMinecraftLibraryManifest(
                pack.getMinecraftVersion(), pack );
        String minecraftAssetClasspath = libraryManifest.buildMinecraftClasspath(
                GameModeManager.getCurrentGameMode(), progressProvider );
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Minecraft libraries and assets ready" );
        }

        // --- Step 4: Install Java runtime ---
        String procRuntimeComponent = libraryManifest.getRequiredRuntimeComponent();
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Installing Java runtime (" + procRuntimeComponent + ")...",
                                                    pack.isVanillaVersion() ? 20.0 : 15.0 );
        }
        RuntimeManager.verifyRuntime( procRuntimeComponent, false,
                                       progressProvider != null ? progressProvider::setCurrText : null );
        if ( progressProvider != null ) {
            progressProvider.submitProgress( "Java runtime ready", 100.0 );
            progressProvider.endProgressSection( "Java runtime ready" );
        }

        // --- Step 5: Run Forge install processors (if needed, Forge only) ---
        if ( !pack.isVanillaVersion() ) {
            if ( progressProvider != null ) {
                progressProvider.startProgressSection( "Patching game files...", 10.0 );
            }
            pack.getForgeApp().runForgeProcessors( GameModeManager.getCurrentGameMode(), progressProvider,
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
            pack.scanModPackRootFolder();
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
     * Builds the full launch command, replaces all placeholders, and starts the game process.
     *
     * @throws ModpackException if unable to launch the game
     *
     * @since 3.0
     */
    void launch() throws ModpackException
    {
        // Get classpath, main class and Minecraft args
        String cp = buildClasspath();

        // Determine the required Java version from the Minecraft version manifest
        GameLibraryManifest libraryManifest = pack.getMinecraftLibraryManifest();

        String minecraftMainClass;
        String minecraftArgs = "";

        if ( pack.isVanillaVersion() ) {
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
                                 pack.getForgeApp().getMinecraftMainClass() :
                                 "net.minecraftforge.fml.relauncher.ServerLaunchWrapper";

            // Build game arguments: combine vanilla game args with Forge-specific game args
            if ( GameModeManager.isClient() ) {
                String vanillaGameArgs = libraryManifest.getGameArguments();
                String forgeGameArgs = pack.getForgeApp().getMinecraftArguments();

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
        Logger.logStd( "Minecraft version " + pack.getMinecraftVersion() + " requires runtime " + runtimeComponent +
                               " (Java " + requiredJavaMajorVersion + ")" );

        // Ensure the required Java runtime is available (should already be verified by buildClasspath,
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
        applyLog4jSecurityConfig( jvmArgs, pack.getMinecraftVersion() );

        // Add natives path
        String nativesFolder = pack.getPackRootFolder() +
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

        // On ARM64 with LWJGL2 patching, tell LWJGL and jinput to load natives from our patched
        // natives folder instead of extracting x86_64 binaries from the classpath JARs.
        // Also disable the narrator (text2speech) which depends on JNA 4.4 that lacks ARM64 natives,
        // and force JNA to use the system-installed library if available.
        if ( Lwjgl2ArmPatcher.isNeeded( pack.getMinecraftVersion() ) ) {
            jvmArgs.append( "-Dorg.lwjgl.librarypath=" ).append( nativesFolder ).append( " " );
            jvmArgs.append( "-Dnet.java.games.input.librarypath=" ).append( nativesFolder ).append( " " );
            jvmArgs.append( "-Djna.nosys=false " );
            jvmArgs.append( "-Djna.boot.library.path= " );
            jvmArgs.append( "-Dmojang.text2speech.enabled=false " );
        }

        // Add manifest JVM arguments (from modern arguments.jvm if available)
        String manifestJvmArgs = libraryManifest.getJvmArguments();
        if ( !manifestJvmArgs.isEmpty() ) {
            jvmArgs.append( manifestJvmArgs ).append( " " );
        }

        // Add Forge-specific JVM arguments (e.g. module system flags for modern Forge)
        if ( !pack.isVanillaVersion() && GameModeManager.isClient() ) {
            String forgeJvmArgs = pack.getForgeApp().getForgeJvmArguments();
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
        fullArgs = fullArgs.replace( "${library_directory}", pack.getPackRootFolder() + File.separator +
                ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );

        // Replace game argument placeholders
        if ( GameModeManager.isClient() ) {
            fullArgs = fullArgs.replace( "${auth_player_name}",
                                          MCLauncherAuthManager.getLoggedInUser().name() );
            fullArgs = fullArgs.replace( "${version_name}",
                                          pack.isVanillaVersion() ? pack.getMinecraftVersion() :
                                          pack.getForgeVersion() );
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
                fullArgs = fullArgs.replace( "${game_directory}", "\"" + pack.getPackRootFolder() + "\"" );
                fullArgs = fullArgs.replace( "${assets_root}", "\"" +
                        pack.getPackRootFolder() +
                        File.separator +
                        ModPackConstants.MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER +
                        "\"" );
            }
            else {
                fullArgs = fullArgs.replace( "${game_directory}", pack.getPackRootFolder() );
                fullArgs = fullArgs.replace( "${assets_root}", pack.getPackRootFolder() +
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
            fullArgs += " --title \"" + pack.getPackName() + "\"";
            fullArgs += " --icon \"" + pack.getPackLogoFilepath() + "\"";

            // Set dock name and icon for macOS
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
                fullArgs += " -Xdock:icon=\"" + pack.getPackLogoFilepath() + "\"";
                fullArgs += " -Xdock:name=\"" + pack.getPackName() + "\" ";
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
            lastLaunchedProcess = ProcessUtilities.launchCommand( fullArgs, pack.getPackRootFolder() );
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to execute mod pack game.", e );
        }
    }

    /**
     * Applies the correct Mojang security-patched log4j configuration based on the Minecraft version.
     * <ul>
     *   <li>MC 1.7 - 1.11.2: Uses {@code log4j2_17-111.xml} with RegexFilter</li>
     *   <li>MC 1.12 - 1.16.5: Uses {@code log4j2_112-116.xml} with {@code %msg{nolookups}}</li>
     *   <li>MC 1.17+: Uses {@code -Dlog4j2.formatMsgNoLookups=true} JVM flag (built-in support)</li>
     * </ul>
     *
     * @param jvmArgs   the JVM arguments builder to append to
     * @param mcVersion the Minecraft version string
     *
     * @since 3.0
     */
    private void applyLog4jSecurityConfig( StringBuilder jvmArgs, String mcVersion )
    {
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
            Logger.logDebug( "MC " + mcVersion +
                                     ": Using log4j2.formatMsgNoLookups=true (1.17+ built-in support)" );
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
     *
     * @param jvmArgs  the JVM arguments builder to append to
     * @param url      the download URL for the config file
     * @param sha1     the expected SHA-1 hash
     * @param fileName the local file name
     *
     * @since 3.0
     */
    private void applyLog4jConfigFile( StringBuilder jvmArgs, String url, String sha1, String fileName )
    {
        String logConfigPath = pack.getPackRootFolder() + File.separator + "bin" + File.separator + fileName;
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
}

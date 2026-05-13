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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.JsonHelper;
import com.micatechnologies.minecraft.launcher.consts.ForgeConstants;
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.RuntimeManager;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Class representation of a modpack Forge jar application
 *
 * @author Mica Technologies
 * @version 1.0.1
 * @since 1.0
 */
class GameModLoaderForge extends ManagedGameFile
{

    /**
     * Forge jar version
     *
     * @since 1.0
     */
    private final String forgeVersion;

    /**
     * Forge jar Minecraft version
     *
     * @since 1.0
     */
    private final String minecraftVersion;

    /**
     * Forge jar Minecraft arguments
     *
     * @since 1.0
     */
    private final String minecraftArguments;

    /**
     * Forge jar Minecraft main class
     *
     * @since 1.0
     */
    private final String minecraftMainClass;

    /**
     * Parent mod pack
     *
     * @since 1.0
     */
    private final GameModPack parentModPack;

    /**
     * Constructor for {@link GameModLoaderForge}. Creates a {@link GameModLoaderForge} object with the specified remote
     * URL, SHA-1 hash and associated mod pack.
     *
     * @param remoteURL     URL of Forge
     * @param sha1Hash      hash of Forge
     * @param parentModPack parent mod pack
     *
     * @throws ModpackException if unable to download or update
     * @since 1.0
     */
    public GameModLoaderForge( String remoteURL, String sha1Hash, GameModPack parentModPack ) throws ModpackException
    {
        // Populate remote file information/configuration
        super( remoteURL, SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                         ModPackConstants.MODPACK_FORGE_JAR_LOCAL_PATH ), sha1Hash, ManagedGameFileHashType.SHA1 );

        // Store parent mod pack
        this.parentModPack = parentModPack;

        // Download Forge app
        updateLocalFile();

        // Store Forge/MC information
        JsonObject forgeVersionManifest = getForgeVersionManifest();
        if ( !forgeVersionManifest.has( ForgeConstants.FORGE_VERSION_MANIFEST_ID_KEY ) ) {
            throw new ModpackException( "Forge version manifest is missing required field: " +
                                                ForgeConstants.FORGE_VERSION_MANIFEST_ID_KEY );
        }
        if ( !forgeVersionManifest.has( ForgeConstants.FORGE_VERSION_MANIFEST_MAIN_CLASS_KEY ) ) {
            throw new ModpackException( "Forge version manifest is missing required field: " +
                                                ForgeConstants.FORGE_VERSION_MANIFEST_MAIN_CLASS_KEY );
        }
        forgeVersion = forgeVersionManifest.get( ForgeConstants.FORGE_VERSION_MANIFEST_ID_KEY ).getAsString();
        minecraftVersion = parseMinecraftVersion( forgeVersionManifest, forgeVersion );
        minecraftArguments = parseMinecraftArguments( forgeVersionManifest );
        minecraftMainClass = forgeVersionManifest.get( ForgeConstants.FORGE_VERSION_MANIFEST_MAIN_CLASS_KEY )
                                                 .getAsString();
    }

    private static String parseMinecraftVersion( JsonObject forgeVersionManifest, String fallbackVersionId ) {
        if ( forgeVersionManifest.has( ForgeConstants.FORGE_VERSION_MANIFEST_INHERITS_FROM_KEY ) ) {
            return forgeVersionManifest.get( ForgeConstants.FORGE_VERSION_MANIFEST_INHERITS_FROM_KEY ).getAsString();
        }

        if ( fallbackVersionId != null && fallbackVersionId.contains( "-forge" ) ) {
            return fallbackVersionId.substring( 0, fallbackVersionId.indexOf( "-forge" ) );
        }
        return fallbackVersionId;
    }

    private static String parseMinecraftArguments( JsonObject forgeVersionManifest ) {
        if ( forgeVersionManifest.has( ForgeConstants.FORGE_VERSION_MANIFEST_MINECRAFT_ARGS_KEY ) ) {
            return forgeVersionManifest.get( ForgeConstants.FORGE_VERSION_MANIFEST_MINECRAFT_ARGS_KEY ).getAsString();
        }

        if ( forgeVersionManifest.has( "arguments" ) ) {
            JsonObject argumentsObject = forgeVersionManifest.getAsJsonObject( "arguments" );
            if ( argumentsObject.has( "game" ) ) {
                return ManifestRuleUtilities.flattenArguments( argumentsObject.getAsJsonArray( "game" ) );
            }
        }

        return "";
    }

    /**
     * Gets the version of Forge for this mod loader instance.
     *
     * @return Forge version
     *
     * @since 1.0
     */
    public String getForgeVersion() {
        return forgeVersion;
    }

    /**
     * Gets the version of Minecraft for this Forge mod loader instance.
     *
     * @return Forge Minecraft version
     *
     * @since 1.0
     */
    public String getMinecraftVersion() {
        return minecraftVersion;
    }

    /**
     * Gets the arguments for this Forge mod loader instance.
     *
     * @return Minecraft Forge arguments
     *
     * @since 1.0
     */
    public String getMinecraftArguments() {
        return minecraftArguments;
    }

    /**
     * Gets the JVM arguments from this Forge mod loader's version manifest, if present. Modern Forge versions
     * (1.13+) may specify JVM arguments like module system flags in {@code arguments.jvm}.
     *
     * @return Forge JVM arguments string, or empty string if none
     *
     * @throws ModpackException if unable to read the Forge manifest
     *
     * @since 2.0
     */
    public String getForgeJvmArguments() throws ModpackException {
        JsonObject forgeVersionManifest = getForgeVersionManifest();
        if ( forgeVersionManifest.has( "arguments" ) ) {
            JsonObject argumentsObject = forgeVersionManifest.getAsJsonObject( "arguments" );
            if ( argumentsObject.has( "jvm" ) ) {
                return ManifestRuleUtilities.flattenArguments( argumentsObject.getAsJsonArray( "jvm" ) );
            }
        }
        return "";
    }

    /**
     * Gets the main class for this Forge mod loader instance.
     *
     * @return Minecraft Forge main class
     *
     * @since 1.0
     */
    public String getMinecraftMainClass() {
        return minecraftMainClass;
    }

    /**
     * Gets the {@link JarFile} for this Forge mod loader instance.
     *
     * @return Forge {@link JarFile}
     *
     * @throws ModpackException if unable to get Forge {@link JarFile}
     * @since 1.0
     */
    private JarFile getForgeJarFile() throws ModpackException {
        try {
            return new JarFile( getFullLocalFilePath() );
        }
        catch ( IOException e ) {
            throw new ModpackException( LocalizationManager.UNABLE_ACCESS_FORGE_JAR_TEXT, e );
        }
    }

    /**
     * Gets the list of libraries for this Forge mod loader instance.
     *
     * @return Forge library list
     *
     * @throws ModpackException if unable to load Forge version manifest
     * @since 1.0
     */
    private ArrayList< GameAsset > getForgeLibrariesList() throws ModpackException {
        // Get Forge version manifest libraries array
        JsonObject forgeVersionManifest = getForgeVersionManifest();
        JsonArray forgeAssetsArray = forgeVersionManifest.getAsJsonArray(
                ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARIES_KEY );

        // Create list for storing processed libraries
        ArrayList< GameAsset > forgeAssets = new ArrayList<>();

        // Loop Through Each Asset and Process
        for ( JsonElement forgeAsset : forgeAssetsArray ) {
            // Get Asset Object and Information
            JsonObject forgeAssetObj = forgeAsset.getAsJsonObject();
            String forgeAssetName = JsonHelper.getRequiredString( forgeAssetObj,
                    ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_NAME_KEY );

            // Get Asset Downloads Information
            JsonObject forgeAssetDownloadsObj;
            JsonObject forgeAssetDownloadsArtifactObj = null;
            if ( forgeAssetObj.has( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_DOWNLOADS_KEY ) ) {

                forgeAssetDownloadsObj = forgeAssetObj.getAsJsonObject(
                        ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_DOWNLOADS_KEY );
                forgeAssetDownloadsArtifactObj = forgeAssetDownloadsObj.getAsJsonObject(
                        ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_ARTIFACT_KEY );
            }

            // Get Repo Path
            String forgeAssetRepoPath;
            boolean isSpecifiedRepoPath = false;
            String inferredForgeAssetRepoPath = forgeAssetName.substring( forgeAssetName.indexOf( ":" ) + 1 )
                                                              .replace( ":", "-" );
            if ( forgeAssetDownloadsArtifactObj != null &&
                    forgeAssetDownloadsArtifactObj.has( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_PATH_KEY ) ) {
                forgeAssetRepoPath = forgeAssetDownloadsArtifactObj.get(
                        ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_PATH_KEY ).getAsString();
                isSpecifiedRepoPath = true;
            }
            else {
                forgeAssetRepoPath = inferredForgeAssetRepoPath;
            }

            // Get SHA-1, if present
            String sha1 = null;
            if ( forgeAssetDownloadsArtifactObj != null &&
                    forgeAssetDownloadsArtifactObj.has( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_SHA1_KEY ) ) {
                sha1 = forgeAssetDownloadsArtifactObj.get( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_SHA1_KEY )
                                                     .getAsString();
            }

            // Modern Forge installers (1.13+) embed both "forge-<ver>.jar" (containing launch target services like
            // fmlclient) and "forge-<ver>-universal.jar" (containing main Forge code). Both must be on the classpath.
            // Legacy Forge (1.7-1.12) only has the universal JAR.
            boolean addUniversalAsExtra = false;
            String universalRepoPath = null;
            if ( isSpecifiedRepoPath && forgeAssetName.startsWith( "net.minecraftforge:forge:" ) &&
                    !forgeAssetRepoPath.contains( "-universal" ) ) {
                universalRepoPath = forgeAssetRepoPath.replace( ".jar", "-universal.jar" );
                if ( hasEmbeddedMavenEntry( universalRepoPath ) && hasEmbeddedMavenEntry( forgeAssetRepoPath ) ) {
                    // Modern Forge: both JARs exist. Keep the base JAR as-is and add universal separately.
                    addUniversalAsExtra = true;
                }
                else if ( hasEmbeddedMavenEntry( universalRepoPath ) ) {
                    // Legacy Forge: only universal exists. Replace the base path.
                    forgeAssetRepoPath = universalRepoPath;
                    sha1 = null;
                }
            }

            // Build Full Repo URL and Path
            String forgeAssetURL;
            if ( forgeAssetDownloadsArtifactObj != null &&
                    forgeAssetDownloadsArtifactObj.has( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_URL_KEY ) &&
                    forgeAssetDownloadsArtifactObj.get( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_URL_KEY )
                                                  .getAsString()
                                                  .trim()
                                                  .length() > 0 ) {
                forgeAssetURL = forgeAssetDownloadsArtifactObj.get(
                        ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_URL_KEY ).getAsString();
            }
            else if ( isSpecifiedRepoPath && hasEmbeddedMavenEntry( forgeAssetRepoPath ) ) {
                forgeAssetURL = getEmbeddedMavenEntryURL( forgeAssetRepoPath );
            }
            else {
                // Determine the base repository URL. Legacy Forge manifests (1.7-1.12) provide a top-level
                // "url" field on each library entry specifying the Maven repository base. Modern Forge (1.13+)
                // uses the downloads.artifact.url path instead (handled above). If no top-level "url" is
                // provided, fall back to well-known repositories based on the group ID.
                String repoURL = JsonHelper.getString( forgeAssetObj, "url", null );
                if ( repoURL == null || repoURL.isBlank() ) {
                    repoURL = "https://repo1.maven.org/maven2/";
                    if ( forgeAssetName.contains( "net.minecraft:" ) ) {
                        repoURL = "https://libraries.minecraft.net/";
                    }
                    else if ( forgeAssetName.contains( "net.minecraftforge:" ) ) {
                        repoURL = "https://maven.minecraftforge.net/";
                    }
                }
                // Ensure trailing slash
                if ( !repoURL.endsWith( "/" ) ) {
                    repoURL += "/";
                }

                if ( isSpecifiedRepoPath ) {
                    forgeAssetURL = repoURL + forgeAssetRepoPath;
                }
                else {
                    forgeAssetURL = repoURL +
                            forgeAssetName.substring( 0, forgeAssetName.indexOf( ":" ) ).replace( ".", "/" ) +
                            "/" +
                            forgeAssetName.substring( forgeAssetName.indexOf( ":" ) + 1 ).replace( ":", "/" ) +
                            "/" +
                            forgeAssetRepoPath +
                            ".jar";
                }

                // Fallback for lzma:lzma:0.0.1 which is not hosted on Maven Central or Forge Maven.
                // This artifact is required by Forge 1.7-1.12 and is only available from SpongePowered.
                if ( forgeAssetURL.contains( "lzma/lzma/0.0.1" ) && !forgeAssetURL.contains( "spongepowered" ) ) {
                    forgeAssetURL = "https://repo.spongepowered.org/maven/lzma/lzma/0.0.1/lzma-0.0.1.jar";
                }
            }

            // Build Local File Path
            String localForgeAssetFilePath;
            if ( isSpecifiedRepoPath ) {
                localForgeAssetFilePath = forgeAssetRepoPath.replace( "/", File.separator );
            }
            else {
                localForgeAssetFilePath = forgeAssetName.substring( 0, forgeAssetName.indexOf( ":" ) )
                                               .replace( ".", File.separator ) +
                        File.separator +
                        forgeAssetName.substring( forgeAssetName.indexOf( ":" ) + 1 ).replace( ":", File.separator ) +
                        File.separator +
                        inferredForgeAssetRepoPath +
                        LocalPathConstants.JAR_FILE_EXTENSION;
            }

            // Get Forge Asset Requirements
            boolean clientReq = true;
            if ( forgeAssetObj.has( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_CLIENT_REQ_KEY ) ) {
                clientReq = forgeAssetObj.get( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_CLIENT_REQ_KEY )
                                         .getAsBoolean();
            }
            boolean serverReq = true;
            if ( forgeAssetObj.has( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_SERVER_REQ_KEY ) ) {
                serverReq = forgeAssetObj.get( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_SERVER_REQ_KEY )
                                         .getAsBoolean();
            }

            // Build Forge Asset Object and Add to List of Assets
            if ( sha1 != null ) {
                forgeAssets.add( new GameAsset( forgeAssetURL, localForgeAssetFilePath, sha1,
                                                ManagedGameFileHashType.SHA1, clientReq,
                                                serverReq ) );
            }
            else {
                forgeAssets.add( new GameAsset( forgeAssetURL, localForgeAssetFilePath, clientReq, serverReq ) );
            }

            // For modern Forge: also add the universal JAR as a separate classpath entry
            if ( addUniversalAsExtra && universalRepoPath != null ) {
                String universalURL = getEmbeddedMavenEntryURL( universalRepoPath );
                String universalLocalPath = universalRepoPath.replace( "/", File.separator );
                forgeAssets.add( new GameAsset( universalURL, universalLocalPath, clientReq, serverReq ) );
            }
        }

        // Return resulting list of Forge Assets
        return forgeAssets;
    }

    private boolean hasEmbeddedMavenEntry( String repoPath ) {
        try {
            JarFile forgeJarFile = getForgeJarFile();
            boolean exists = forgeJarFile.getEntry( "maven/" + repoPath ) != null;
            forgeJarFile.close();
            return exists;
        }
        catch ( IOException | ModpackException e ) {
            return false;
        }
    }

    private String getEmbeddedMavenEntryURL( String repoPath ) {
        File forgeInstaller = SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() );
        return "jar:" + forgeInstaller.toURI() + "!/maven/" + repoPath;
    }

    private void downloadForgeAssets( GameMode gameAppMode, GameModPackProgressProvider progressProvider )
    throws ModpackException
    {
        // Get list of Forge Assets
        ArrayList< GameAsset > forgeAssetsList = getForgeLibrariesList();

        // For each asset, verify and download as necessary
        for ( GameAsset forgeAsset : forgeAssetsList ) {
            String localPathPrefix = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                                    ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );
            forgeAsset.setLocalPathPrefix( localPathPrefix );
            forgeAsset.updateLocalFile( gameAppMode );

            // Update progress provider if present
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified asset " +
                                                         SynchronizedFileManager.getSynchronizedFile(
                                                                 forgeAsset.getFullLocalFilePath() ).getName(),
                                                 ( 60.0 / ( double ) forgeAssetsList.size() ) );
            }
        }
    }

    /**
     * Runs the Forge install processors defined in install_profile.json. Modern Forge (1.13+) requires a multi-step
     * patching process to produce the patched client JAR from the vanilla Minecraft client. This method downloads
     * processor libraries, resolves data variables, and runs each processor in sequence.
     *
     * @param gameAppMode      client or server mode
     * @param progressProvider progress provider for UI feedback
     * @param javaMajorVersion the Java major version to use for running processors
     *
     * @throws ModpackException if processors fail
     *
     * @since 2.0
     */
    void runForgeProcessors( GameMode gameAppMode, GameModPackProgressProvider progressProvider, String runtimeComponent )
    throws ModpackException
    {
        String side = gameAppMode == GameMode.CLIENT ? "client" : "server";
        String libsFolder = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                            ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );

        // Read install_profile.json from the Forge installer
        JsonObject installProfile;
        try ( JarFile forgeJar = getForgeJarFile() ) {
            JarEntry profileEntry = forgeJar.getJarEntry( "install_profile.json" );
            if ( profileEntry == null ) {
                Logger.logDebug( "No install_profile.json found -- legacy Forge, skipping processors." );
                return;
            }
            try ( InputStream is = forgeJar.getInputStream( profileEntry );
                  InputStreamReader reader = new InputStreamReader( is ) ) {
                installProfile = JSONUtilities.getGson().fromJson( reader, JsonObject.class );
            }
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to read install_profile.json from Forge installer.", e );
        }

        if ( !installProfile.has( "processors" ) || !installProfile.has( "data" ) ) {
            Logger.logDebug( "install_profile.json has no processors -- skipping." );
            return;
        }

        // Check if the patched output already exists
        JsonObject data = installProfile.getAsJsonObject( "data" );
        JsonObject patchedObj = JsonHelper.getJsonObject( data, "PATCHED" );
        if ( patchedObj != null ) {
            String patchedCoord = JsonHelper.getString( patchedObj, side, null );
            if ( patchedCoord != null ) {
                String patchedPath = mavenCoordToPath( patchedCoord );
                File patchedFile = new File( libsFolder, patchedPath );
                if ( patchedFile.exists() && patchedFile.length() > 0 ) {
                    Logger.logStd( "Forge patched client already exists, skipping processors." );
                    return;
                }
            }
        }

        Logger.logStd( "Running Forge install processors for " + side + "..." );

        // Download install_profile libraries
        JsonArray profileLibs = installProfile.getAsJsonArray( "libraries" );
        for ( JsonElement libEl : profileLibs ) {
            JsonObject lib = libEl.getAsJsonObject();
            JsonObject downloads = lib.has( "downloads" ) ? lib.getAsJsonObject( "downloads" ) : null;
            JsonObject artifact = downloads != null && downloads.has( "artifact" ) ?
                                  downloads.getAsJsonObject( "artifact" ) : null;
            if ( artifact == null ) {
                continue;
            }

            String path = JsonHelper.getRequiredString( artifact, "path" );
            String url = JsonHelper.getString( artifact, "url", "" );
            File localFile = new File( libsFolder, path.replace( "/", File.separator ) );

            if ( localFile.exists() ) {
                continue;
            }

            if ( url.isEmpty() ) {
                // Embedded in installer JAR
                if ( hasEmbeddedMavenEntry( path ) ) {
                    try {
                        extractEmbeddedMavenEntry( path, localFile );
                    }
                    catch ( IOException e ) {
                        throw new ModpackException( "Failed to extract embedded library: " + path, e );
                    }
                }
                continue;
            }

            localFile.getParentFile().mkdirs();
            try {
                NetworkUtilities.downloadFileFromURL( url, localFile );
            }
            catch ( IOException e ) {
                throw new ModpackException( "Failed to download processor library: " + url, e );
            }

            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Downloaded processor lib: " + localFile.getName(), 1.0 );
            }
        }

        // Get the vanilla Minecraft JAR path
        String minecraftJarPath = parentModPack.getPackRootFolder() + File.separator +
                ModPackConstants.MODPACK_MINECRAFT_JAR_LOCAL_PATH;

        // Ensure the Java runtime is available for running processors
        String javaExec = RuntimeManager.getJavaPath( runtimeComponent );

        // Run each processor
        JsonArray processors = installProfile.getAsJsonArray( "processors" );
        for ( int i = 0; i < processors.size(); i++ ) {
            JsonObject proc = processors.get( i ).getAsJsonObject();

            // Check side filter
            if ( proc.has( "sides" ) ) {
                JsonArray sides = proc.getAsJsonArray( "sides" );
                boolean sideMatch = false;
                for ( JsonElement s : sides ) {
                    if ( s.getAsString().equals( side ) ) {
                        sideMatch = true;
                        break;
                    }
                }
                if ( !sideMatch ) {
                    continue;
                }
            }

            String processorJar = JsonHelper.getRequiredString( proc, "jar" );
            Logger.logStd( "Running Forge processor " + ( i + 1 ) + "/" + processors.size() + ": " + processorJar );

            // Build classpath for this processor
            StringBuilder procClasspath = new StringBuilder();
            procClasspath.append( new File( libsFolder, mavenCoordToPath( processorJar ) ).getAbsolutePath() );

            if ( proc.has( "classpath" ) ) {
                for ( JsonElement cpEl : proc.getAsJsonArray( "classpath" ) ) {
                    procClasspath.append( File.pathSeparator );
                    procClasspath.append(
                            new File( libsFolder, mavenCoordToPath( cpEl.getAsString() ) ).getAbsolutePath() );
                }
            }

            // Find main class from processor JAR manifest
            String mainClass;
            try ( JarFile procJarFile = new JarFile(
                    new File( libsFolder, mavenCoordToPath( processorJar ) ) ) ) {
                mainClass = procJarFile.getManifest().getMainAttributes().getValue( "Main-Class" );
            }
            catch ( IOException e ) {
                throw new ModpackException( "Cannot read processor JAR manifest: " + processorJar, e );
            }

            if ( mainClass == null ) {
                throw new ModpackException( "Processor JAR has no Main-Class: " + processorJar );
            }

            // Resolve args
            JsonArray argsArray = JsonHelper.getRequiredJsonArray( proc, "args" );
            List< String > resolvedArgs = new ArrayList<>();
            for ( JsonElement argEl : argsArray ) {
                String arg = argEl.getAsString();
                arg = resolveProcessorArg( arg, data, side, libsFolder, minecraftJarPath );
                resolvedArgs.add( arg );
            }

            // Build the command
            List< String > command = new ArrayList<>();
            command.add( javaExec );
            command.add( "-cp" );
            command.add( procClasspath.toString() );
            command.add( mainClass );
            command.addAll( resolvedArgs );

            // Run the processor (10-minute timeout to prevent indefinite hangs)
            try {
                ProcessBuilder pb = new ProcessBuilder( command );
                pb.directory( new File( parentModPack.getPackRootFolder() ) );
                pb.inheritIO();
                Process process = pb.start();
                boolean completed = process.waitFor( 10, java.util.concurrent.TimeUnit.MINUTES );
                if ( !completed ) {
                    process.destroyForcibly();
                    throw new ModpackException(
                            "Forge processor timed out after 10 minutes: " + processorJar );
                }
                int exitCode = process.exitValue();
                if ( exitCode != 0 ) {
                    process.destroyForcibly();
                    throw new ModpackException(
                            "Forge processor failed (exit code " + exitCode + "): " + processorJar );
                }
            }
            catch ( IOException | InterruptedException e ) {
                throw new ModpackException( "Failed to run Forge processor: " + processorJar, e );
            }

            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Completed processor: " + processorJar,
                                                 10.0 / processors.size() );
            }
        }

        Logger.logStd( "Forge install processors completed successfully." );
    }

    /**
     * Converts a Maven coordinate (e.g. "net.minecraftforge:forge:1.15.2-31.2.50:client") to a local path
     * (e.g. "net/minecraftforge/forge/1.15.2-31.2.50/forge-1.15.2-31.2.50-client.jar").
     */
    private static String mavenCoordToPath( String coord ) throws ModpackException {
        // Strip brackets if present (e.g. "[group:artifact:version]")
        if ( coord.startsWith( "[" ) && coord.endsWith( "]" ) ) {
            coord = coord.substring( 1, coord.length() - 1 );
        }

        // Handle @ext suffix
        String ext = "jar";
        if ( coord.contains( "@" ) ) {
            ext = coord.substring( coord.indexOf( "@" ) + 1 );
            coord = coord.substring( 0, coord.indexOf( "@" ) );
        }

        String[] parts = coord.split( ":" );
        if ( parts.length < 3 ) {
            throw new ModpackException( "Invalid Maven coordinate (expected group:artifact:version): " + coord );
        }
        String group = parts[ 0 ].replace( ".", "/" );
        String artifact = parts[ 1 ];
        String version = parts[ 2 ];
        String classifier = parts.length > 3 ? parts[ 3 ] : null;

        // Validate no path traversal in coordinate components. Classifier is included
        // in the assembled filename verbatim, so the same checks have to cover it —
        // otherwise an attacker-controlled coordinate could smuggle "../" through the
        // classifier slot to write outside the libs folder.
        if ( group.contains( ".." ) || artifact.contains( ".." ) || version.contains( ".." ) ) {
            throw new ModpackException( "Path traversal detected in Maven coordinate: " + coord );
        }
        if ( classifier != null && ( classifier.contains( ".." )
                || classifier.indexOf( '/' ) >= 0
                || classifier.indexOf( '\\' ) >= 0 ) ) {
            throw new ModpackException( "Path traversal detected in Maven classifier: " + coord );
        }
        // ext lands in the same path component too; reject separators / .. there as well.
        if ( ext.contains( ".." ) || ext.indexOf( '/' ) >= 0 || ext.indexOf( '\\' ) >= 0 ) {
            throw new ModpackException( "Path traversal detected in Maven extension: " + coord );
        }

        String fileName = artifact + "-" + version + ( classifier != null ? "-" + classifier : "" ) + "." + ext;
        return group + "/" + artifact + "/" + version + "/" + fileName;
    }

    /**
     * Resolves a processor argument by substituting data variables and special tokens.
     */
    private String resolveProcessorArg( String arg, JsonObject data, String side, String libsFolder,
                                         String minecraftJarPath )
    throws ModpackException
    {
        // {VARIABLE} -> resolved from data section
        if ( arg.startsWith( "{" ) && arg.endsWith( "}" ) ) {
            String key = arg.substring( 1, arg.length() - 1 );
            if ( key.equals( "MINECRAFT_JAR" ) ) {
                return minecraftJarPath;
            }
            JsonObject dataEntry = JsonHelper.getJsonObject( data, key );
            if ( dataEntry != null ) {
                String value = JsonHelper.getString( dataEntry, side, null );
                if ( value != null ) {
                    return resolveDataValue( value, libsFolder );
                }
            }
            return arg;
        }

        // [maven:coord] -> path to library
        if ( arg.startsWith( "[" ) && arg.endsWith( "]" ) ) {
            return new File( libsFolder, mavenCoordToPath( arg ) ).getAbsolutePath();
        }

        return arg;
    }

    /**
     * Resolves a data value which can be a Maven coordinate [group:artifact:version], a path inside the installer JAR
     * (/data/file.lzma), or a literal string ('value').
     */
    private String resolveDataValue( String value, String libsFolder ) throws ModpackException {
        // Literal string in single quotes
        if ( value.startsWith( "'" ) && value.endsWith( "'" ) ) {
            return value.substring( 1, value.length() - 1 );
        }

        // Maven coordinate in brackets
        if ( value.startsWith( "[" ) && value.endsWith( "]" ) ) {
            return new File( libsFolder, mavenCoordToPath( value ) ).getAbsolutePath();
        }

        // Path inside the installer JAR (e.g. /data/client.lzma)
        if ( value.startsWith( "/" ) ) {
            String entryName = value.substring( 1 ); // remove leading /
            File extractedFile = new File( libsFolder, "forge-installer-data" + File.separator +
                    entryName.replace( "/", File.separator ) );
            if ( !extractedFile.exists() ) {
                try ( JarFile forgeJar = getForgeJarFile() ) {
                    JarEntry entry = forgeJar.getJarEntry( entryName );
                    if ( entry == null ) {
                        throw new ModpackException( "Missing entry in Forge installer: " + entryName );
                    }
                    extractedFile.getParentFile().mkdirs();
                    try ( InputStream entryStream = forgeJar.getInputStream( entry ) ) {
                        Files.copy( entryStream, extractedFile.toPath(), StandardCopyOption.REPLACE_EXISTING );
                    }
                }
                catch ( IOException e ) {
                    throw new ModpackException( "Failed to extract from Forge installer: " + entryName, e );
                }
            }
            return extractedFile.getAbsolutePath();
        }

        return value;
    }

    /**
     * Extracts an embedded maven entry from the Forge installer JAR to a local file.
     */
    private void extractEmbeddedMavenEntry( String repoPath, File destination ) throws IOException, ModpackException {
        try ( JarFile forgeJar = getForgeJarFile() ) {
            JarEntry entry = forgeJar.getJarEntry( "maven/" + repoPath );
            if ( entry == null ) {
                throw new IOException( "Embedded maven entry not found: maven/" + repoPath );
            }
            destination.getParentFile().mkdirs();
            try ( InputStream entryStream = forgeJar.getInputStream( entry ) ) {
                Files.copy( entryStream, destination.toPath(), StandardCopyOption.REPLACE_EXISTING );
            }
        }
    }

    String buildForgeClasspath( GameMode gameAppMode, GameModPackProgressProvider progressProvider )
    throws ModpackException
    {
        // Get list of Forge Assets
        ArrayList< GameAsset > forgeAssetsList = getForgeLibrariesList();
        // Update progress provider if present
        if ( progressProvider != null ) {
            progressProvider.submitProgress( "Got Forge asset list", 20.0 );
        }

        // Download the assets
        downloadForgeAssets( gameAppMode, progressProvider );

        // For each asset, add to classpath
        StringBuilder classpath = new StringBuilder();
        LinkedHashSet< String > classpathEntries = new LinkedHashSet<>();
        for ( GameAsset forgeAsset : forgeAssetsList ) {
            String localPathPrefix = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                                    ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );
            forgeAsset.setLocalPathPrefix( localPathPrefix );
            classpathEntries.add( forgeAsset.getFullLocalFilePath() );

            // Update progress provider if present
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Added to classpath: " + forgeAsset.getLocalFilePath(),
                                                 ( 20.0 / ( double ) forgeAssetsList.size() ) );
            }
        }

        // Add the patched client JAR from Forge processors if it exists (modern Forge 1.13+)
        String libsFolder = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                            ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );
        try ( JarFile forgeJar = getForgeJarFile() ) {
            JarEntry profileEntry = forgeJar.getJarEntry( "install_profile.json" );
            if ( profileEntry != null ) {
                JsonObject installProfile;
                try ( InputStream is = forgeJar.getInputStream( profileEntry );
                      InputStreamReader reader = new InputStreamReader( is ) ) {
                    installProfile = JSONUtilities.getGson().fromJson( reader, JsonObject.class );
                }
                if ( installProfile.has( "data" ) ) {
                    JsonObject data = installProfile.getAsJsonObject( "data" );
                    String side = gameAppMode == GameMode.CLIENT ? "client" : "server";
                    if ( data.has( "PATCHED" ) && data.getAsJsonObject( "PATCHED" ).has( side ) ) {
                        String patchedCoord = data.getAsJsonObject( "PATCHED" ).get( side ).getAsString();
                        String patchedPath = mavenCoordToPath( patchedCoord );
                        File patchedFile = new File( libsFolder, patchedPath );
                        if ( patchedFile.exists() ) {
                            classpathEntries.add( patchedFile.getAbsolutePath() );
                            Logger.logDebug( "Added patched Forge client to classpath: " + patchedFile.getName() );
                        }
                    }
                    // Also add MC_EXTRA (contains resources split from the vanilla JAR)
                    if ( data.has( "MC_EXTRA" ) && data.getAsJsonObject( "MC_EXTRA" ).has( side ) ) {
                        String extraCoord = data.getAsJsonObject( "MC_EXTRA" ).get( side ).getAsString();
                        String extraPath = mavenCoordToPath( extraCoord );
                        File extraFile = new File( libsFolder, extraPath );
                        if ( extraFile.exists() ) {
                            classpathEntries.add( extraFile.getAbsolutePath() );
                            Logger.logDebug( "Added MC extra to classpath: " + extraFile.getName() );
                        }
                    }
                }
            }
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Could not check for Forge patched client: " + e.getMessage() );
        }

        for ( String cpEntry : classpathEntries ) {
            if ( classpath.length() > 0 ) {
                classpath.append( File.pathSeparator );
            }
            classpath.append( cpEntry );
        }

        return classpath.toString();
    }

    private JsonObject getForgeVersionManifest() throws ModpackException {
        try ( JarFile forgeJarFile = getForgeJarFile() ) {
            Enumeration< JarEntry > enumeration = forgeJarFile.entries();
            while ( enumeration.hasMoreElements() ) {
                JarEntry jarEntry = enumeration.nextElement();
                if ( jarEntry.getName().equals( ForgeConstants.FORGE_JAR_VERSION_FILE_NAME ) ) {
                    try ( InputStream inputStream = forgeJarFile.getInputStream( jarEntry );
                          InputStreamReader inputStreamReader = new InputStreamReader( inputStream ) ) {
                        return JSONUtilities.getGson().fromJson( inputStreamReader, JsonObject.class );
                    }
                    catch ( IOException e ) {
                        throw new ModpackException(
                                LocalizationManager.UNABLE_OPEN_FORGE_VERSION_MANIFEST_PARSING_TEXT, e );
                    }
                }
            }
        }
        catch ( IOException e ) {
            throw new ModpackException( LocalizationManager.UNABLE_CLOSE_STREAMS_TEXT, e );
        }

        throw new ModpackException( LocalizationManager.UNABLE_FIND_FORGE_VERSION_FILE_TEXT );
    }
}

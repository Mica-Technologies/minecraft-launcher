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

package com.micatechnologies.minecraft.launcher.game.modpack.manifests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.ManifestConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.RuntimeConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameLibrary;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackProgressProvider;
import com.micatechnologies.minecraft.launcher.game.modpack.Lwjgl2ArmPatcher;
import com.micatechnologies.minecraft.launcher.game.modpack.ManagedGameFile;
import com.micatechnologies.minecraft.launcher.utilities.DownloadExecutor;
import com.micatechnologies.minecraft.launcher.utilities.JsonHelper;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.*;
import java.util.jar.JarFile;

/**
 * Class representing a library manifest for a specific Minecraft version, based on specified URL.
 *
 * @author Mica Technologies
 * @version 1.1
 * @since 1.0
 */
public class GameLibraryManifest extends ManagedGameFile
{

    /**
     * The mod pack to which this {@link GameLibraryManifest} belongs to.
     *
     * @since 1.1
     */
    private final GameModPack parentModPack;

    /**
     * Creates a Minecraft library manifest object for the specified mod pack. Instances do not require a local path
     * prefix to be defined for download.
     *
     * @param remote        library manifest URL
     * @param parentModPack parent mod pack
     *
     * @since 1.0
     */
    public GameLibraryManifest( String remote, GameModPack parentModPack ) {
        super( remote, SystemUtilities.buildFilePath( parentModPack.getPackBinFolder(),
                                                      LocalPathConstants.MINECRAFT_LIBRARY_MANIFEST_FILE_NAME ) );
        this.parentModPack = parentModPack;
    }

    /**
     * Get the list of all applicable libraries for the current system from this Minecraft library manifest.
     *
     * @return list of {@link GameLibrary} objects
     *
     * @throws ModpackException if download or processing fails
     * @since 1.0
     */
    private ArrayList< GameLibrary > getLibraries() throws ModpackException {
        // Create list to return
        ArrayList< GameLibrary > libraries = new ArrayList<>();

        // Get JsonObject of manifest
        JsonObject libManifest = readToJsonObject();

        // Loop through each library in manifest
        JsonArray libManifestLibraries = libManifest.getAsJsonArray(
                ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARIES_KEY );
        for ( JsonElement libManifestLib : libManifestLibraries ) {
            // Get element as a JsonObject
            JsonObject libManifestLibObj = libManifestLib.getAsJsonObject();

            // Check for library entry format
            // name : String
            // downloads: Object
            if ( libManifestLibObj.has( ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY ) &&
                    libManifestLibObj.has( ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_NAME_KEY ) ) {
                // Library information variables
                Path path = null;
                String sha1 = null;
                String url = null;

                // Check for and process library information
                JsonObject downloadsObj = libManifestLibObj.getAsJsonObject(
                        ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY );
                JsonObject artifactObj = JsonHelper.getJsonObject( downloadsObj,
                        ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_ARTIFACT_KEY );
                if ( artifactObj != null ) {
                    // Store library path, sha1 and url
                    path = Paths.get( JsonHelper.getRequiredString( artifactObj, "path" ) );
                    sha1 = JsonHelper.getRequiredString( artifactObj, "sha1" );
                    url = JsonHelper.getRequiredString( artifactObj, "url" );
                }

                String currentPlatform = ManifestRuleUtilities.getCurrentPlatformName();
                boolean libraryAllowed = ManifestRuleUtilities.evaluateRules(
                        libManifestLibObj.has( "rules" ) ? libManifestLibObj.getAsJsonArray( "rules" ) : null );

                ArrayList< String > rulesOS = new ArrayList<>();
                if ( libraryAllowed ) {
                    rulesOS.add( currentPlatform );
                }
                boolean useStrictRules = true;

                // Check for and process library classifiers
                // Note: classifiers are additional libraries or
                // sub-libraries associated with the parent library
                // Note: logging classifiers are ignored.
                if ( libManifestLibObj.getAsJsonObject(
                        ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY ).has( "classifiers" ) ) {
                    JsonObject classifiersObj = libManifestLibObj.getAsJsonObject(
                                                               ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                                           .getAsJsonObject( "classifiers" );

                    // Check for and process windows native libraries
                    String windowsNativeKey = resolveNativeClassifierKey( libManifestLibObj, "windows" );
                    if ( libraryAllowed &&
                            org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS &&
                            windowsNativeKey != null &&
                            classifiersObj.has( windowsNativeKey ) ) {
                        // Create list to store applicable OSes for native
                        ArrayList< String > nativeValidOS = new ArrayList<>();

                        // Add Windows as an applicable OS if library supports Windows
                        if ( libraryAllowed ) {
                            nativeValidOS.add( ModPackConstants.PLATFORM_WINDOWS );
                        }

                        // Initialize new native library object
                        JsonObject nativeObj = classifiersObj.getAsJsonObject( windowsNativeKey );
                        String localLibPath = JsonHelper.getRequiredString( nativeObj, "path" );
                        GameLibrary nativeLib = new GameLibrary( JsonHelper.getRequiredString( nativeObj, "url" ),
                                                     localLibPath,
                                                     JsonHelper.getRequiredString( nativeObj, "sha1" ),
                                                     ManagedGameFileHashType.SHA1,
                                                     true, nativeValidOS, true );

                        // Add native library to applicable libraries list
                        libraries.add( nativeLib );
                    }

                    // Check for and process macOS native libraries
                    String macNativeKey = resolveNativeClassifierKey( libManifestLibObj, "osx" );
                    if ( libraryAllowed &&
                            org.apache.commons.lang3.SystemUtils.IS_OS_MAC &&
                            macNativeKey != null &&
                            classifiersObj.has( macNativeKey ) ) {
                        // Create list to store applicable OSes for native
                        ArrayList< String > nativeValidOS = new ArrayList<>();

                        // Add macOS as an applicable OS if library supports macOS
                        if ( libraryAllowed ) {
                            nativeValidOS.add( ModPackConstants.PLATFORM_MACOS );
                        }

                        // Initialize new native library object
                        JsonObject nativeObj = classifiersObj.getAsJsonObject( macNativeKey );
                        String localLibPath = JsonHelper.getRequiredString( nativeObj, "path" );
                        GameLibrary nativeLib = new GameLibrary( JsonHelper.getRequiredString( nativeObj, "url" ),
                                                     localLibPath,
                                                     JsonHelper.getRequiredString( nativeObj, "sha1" ),
                                                     ManagedGameFileHashType.SHA1,
                                                     true, nativeValidOS, true );

                        // Add native library to applicable libraries list
                        libraries.add( nativeLib );
                    }

                    // Check for and process Linux native libraries if on Linux
                    String linuxNativeKey = resolveNativeClassifierKey( libManifestLibObj, "linux" );
                    if ( libraryAllowed &&
                            org.apache.commons.lang3.SystemUtils.IS_OS_LINUX &&
                            linuxNativeKey != null &&
                            classifiersObj.has( linuxNativeKey ) ) {
                        // Create list to store applicable OSes for native
                        ArrayList< String > nativeValidOS = new ArrayList<>();

                        // Add Linux as an applicable OS if library supports Linux
                        if ( libraryAllowed ) {
                            nativeValidOS.add( ModPackConstants.PLATFORM_UNIX );
                        }

                        // Initialize new native library object
                        JsonObject nativeObj = classifiersObj.getAsJsonObject( linuxNativeKey );
                        String localLibPath = JsonHelper.getRequiredString( nativeObj, "path" );
                        GameLibrary nativeLib = new GameLibrary( JsonHelper.getRequiredString( nativeObj, "url" ),
                                                     localLibPath,
                                                     JsonHelper.getRequiredString( nativeObj, "sha1" ),
                                                     ManagedGameFileHashType.SHA1,
                                                     true, nativeValidOS, true );

                        // Add native library to applicable libraries list
                        libraries.add( nativeLib );
                    }
                }

                // Create final library object and add to applicable list
                if ( path != null && url != null && sha1 != null ) {
                    GameLibrary thisLib = new GameLibrary( url, path.toString(), sha1, ManagedGameFileHashType.SHA1,
                                                           useStrictRules, rulesOS, false );

                    if ( thisLib.getApplicableOSes().contains( currentPlatform ) ) {

                        libraries.add( thisLib );
                    }
                }
            }
            // Output failure to decode library JSON
            else {
                Logger.logWarningSilent( LocalizationManager.format( "log.libraryManifest.skippedUnknownType",
                                                                     libManifestLibObj ) );
            }
        }
        return libraries;
    }

    /**
     * Resolves the classifier key used to look up a native sub-library for the given OS within a library entry's
     * {@code downloads.classifiers} object.
     * <p>
     * When the library declares a {@code natives} mapping, the value for the requested OS is used directly, with any
     * {@code ${arch}} placeholder substituted for the current process architecture ({@code "64"} or {@code "32"}). When
     * no such mapping is present, the conventional {@code "natives-<osKey>"} key is returned as a fallback.
     *
     * @param libraryObject the library entry JSON object from the manifest
     * @param osKey          the Mojang OS key to resolve a native classifier for (e.g. {@code "windows"}, {@code "osx"},
     *                       {@code "linux"})
     *
     * @return the resolved classifier key for the requested OS
     *
     * @since 1.0
     */
    private String resolveNativeClassifierKey( JsonObject libraryObject, String osKey ) {
        if ( libraryObject.has( "natives" ) ) {
            JsonObject nativesObject = libraryObject.getAsJsonObject( "natives" );
            if ( nativesObject.has( osKey ) ) {
                String key = nativesObject.get( osKey ).getAsString();
                String arch = System.getProperty( "os.arch", "" ).contains( "64" ) ? "64" : "32";
                return key.replace( "${arch}", arch );
            }
        }
        return "natives-" + osKey;
    }

    /**
     * Verify each library's local copy and download/update if necessary
     *
     * @param progressProvider progress provider
     *
     * @throws ModpackException if update or download fails
     * @since 1.0
     */
    private void downloadVerifyLibraries( GameModPackProgressProvider progressProvider )
    throws ModpackException, InterruptedException, ExecutionException
    {
        // Build full path to libraries and natives folders
        String localLibPath = parentModPack.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER;
        String localNativePath = parentModPack.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER;

        // Get list of libraries
        ArrayList< GameLibrary > libraries = getLibraries();
        if ( libraries.isEmpty() ) {
            return;
        }

        // Build list of library download tasks on the shared bounded download pool.
        List< Future< Boolean > > threadPoolFutures = new ArrayList<>();
        for ( GameLibrary library : libraries ) {
            Callable< Boolean > updateFileCallable = () -> {
                library.setLocalPathPrefix( localLibPath );
                boolean didChange = library.updateLocalFile();
                if ( library.isNativeLib() ) {
                    // Extract every launch, not just when the JAR was re-downloaded.
                    // The previous "didChange" gate left the launcher unable to recover
                    // from any state where bin/natives was wiped but the source JARs
                    // still verify intact (manual cleanup, OS reset, FAST_PATH skipping
                    // re-download): the extracted .dll/.so/.dylib files would never come
                    // back, and LWJGL would crash with UnsatisfiedLinkError on launch.
                    try ( JarFile nativeJar = new JarFile( library.getFullLocalFilePath() ) ) {
                        SystemUtilities.extractJarFile( nativeJar, localNativePath );
                    }
                    catch ( IOException e ) {
                        throw new ModpackException( "Unable to extract native library.", e );
                    }
                }

                // Update progress provider if present
                if ( progressProvider != null ) {
                    progressProvider.submitProgress( LocalizationManager.format( "libraryManifest.verifiedLibrary",
                                                                                 library.getFileName() ),
                                                     ( 25.0 / ( double ) libraries.size() ) );
                }
                return didChange;
            };
            Future< Boolean > future = DownloadExecutor.submit( updateFileCallable );
            threadPoolFutures.add( future );
        }
        // Drain the futures on the shared pool, bounded at 30 minutes. awaitAll cancels
        // any still-pending siblings on interrupt/timeout/failure and restores the
        // interrupt flag, so we never leak in-flight downloads against the shared pool.
        try {
            DownloadExecutor.awaitAll( threadPoolFutures, 30 * 60 * 1000L );
        }
        catch ( TimeoutException e ) {
            throw new ModpackException( "Library downloads did not complete within 30 minutes." );
        }
    }

    /**
     * Verify and download all applicable libraries from this manifest and return a classpath String with reference to
     * each library.
     *
     * @return Minecraft libraries classpath
     *
     * @throws ModpackException if update or download fails
     */
    public String buildMinecraftClasspath( GameMode appGameMode, GameModPackProgressProvider progressProvider )
    throws ModpackException
    {
        // Get list of libraries
        ArrayList< GameLibrary > minecraftLibsList = getLibraries();
        if ( progressProvider != null ) {
            progressProvider.submitProgress( LocalizationManager.get( "libraryManifest.gotLibraryList" ), 5 );
        }

        // Download the libraries
        try {
            downloadVerifyLibraries( progressProvider );
        }
        catch ( InterruptedException e ) {
            throw new ModpackException( "The download of Minecraft libraries was interrupted before completion!", e );
        }
        catch ( ExecutionException e ) {
            throw new ModpackException( "Unable to execute runner to retrieve Minecraft libraries!", e );
        }

        // Patch LWJGL2 natives for ARM64 if needed (replaces x86_64 dylibs/JARs with ARM64 builds)
        String mcVersion = parentModPack.getMinecraftVersion();
        if ( Lwjgl2ArmPatcher.isNeeded( mcVersion ) ) {
            String nativesPath = parentModPack.getPackRootFolder() +
                    File.separator +
                    ModPackConstants.MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER;
            String librariesPath = parentModPack.getPackRootFolder() +
                    File.separator +
                    ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER;
            String cachePath = librariesPath + File.separator + "arm64-natives";
            Lwjgl2ArmPatcher.patchNatives( nativesPath, librariesPath, cachePath, progressProvider );
        }

        // Download the Minecraft jar
        downloadMinecraftApp( appGameMode, progressProvider );

        // Download Minecraft assets
        try {
            downloadMinecraftAssets( progressProvider );
        }
        catch ( InterruptedException e ) {
            throw new ModpackException( "The download of Minecraft assets was interrupted before completion!", e );
        }
        catch ( ExecutionException e ) {
            throw new ModpackException( "Unable to execute runner to retrieve Minecraft assets!", e );
        }

        // For each asset, add to classpath
        StringBuilder classpath = new StringBuilder();
        LinkedHashSet< String > classpathEntries = new LinkedHashSet<>();
        for ( GameLibrary library : minecraftLibsList ) {
            String localLibPath = parentModPack.getPackRootFolder() +
                    File.separator +
                    ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER;
            library.setLocalPathPrefix( localLibPath );
            classpathEntries.add( library.getFullLocalFilePath() );

            // Update progress provider if present
            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.format( "libraryManifest.addedToClasspath",
                                                                             library.getLocalFilePath() ),
                                                 ( 10.0 / ( double ) minecraftLibsList.size() ) );
            }
        }

        // Add Minecraft jar to classpath and flatten
        classpathEntries.add( getMinecraftApp( appGameMode ).getFullLocalFilePath() );
        for ( String cpEntry : classpathEntries ) {
            if ( classpath.length() > 0 ) {
                classpath.append( File.pathSeparator );
            }
            classpath.append( cpEntry );
        }

        return classpath.toString();
    }

    /**
     * Downloads and verifies all Minecraft game assets referenced by this manifest's asset index, then materializes a
     * flat virtual asset tree for legacy index formats.
     * <p>
     * For pre-1.7 indexes ({@code virtual: true}) and 1.6.x indexes ({@code map_to_resources: true}), the game expects
     * the assets laid out as a flat directory tree at the path passed via {@code --assetsDir}; this method performs that
     * materialization. The materialization step is a no-op for modern (hashed) asset indexes.
     *
     * @param progressProvider progress provider to report asset verification progress to, or {@code null} for none
     *
     * @throws ModpackException     if asset download, verification, or materialization fails
     * @throws InterruptedException if the asset download is interrupted before completion
     * @throws ExecutionException   if an asset download task fails during execution
     */
    void downloadMinecraftAssets( final GameModPackProgressProvider progressProvider )
    throws ModpackException, InterruptedException, ExecutionException
    {
        GameAssetManifest assetManifest = getAssetManifest();
        assetManifest.downloadAssets( progressProvider );
        // For pre-1.7 (virtual: true) and 1.6.x (map_to_resources: true) indexes, the game
        // expects a flat tree at the path passed in --assetsDir. No-op on modern indexes.
        assetManifest.materializeVirtualTree();
    }

    /**
     * Get the asset index version for Minecraft
     *
     * @return asset index version
     *
     * @throws ModpackException if unable to read manifest
     */
    public String getAssetIndexVersion() throws ModpackException {
        JsonObject manifest = readToJsonObject();
        JsonObject assetIndex = JsonHelper.getRequiredJsonObject( manifest, "assetIndex" );
        return JsonHelper.getRequiredString( assetIndex, "id" );
    }

    /**
     * Resolves and constructs the {@link GameAssetManifest} for this Minecraft version using the {@code assetIndex}
     * entry of this library manifest. The returned manifest is bound to this manifest's parent mod pack and asset index
     * version but has not yet been downloaded.
     *
     * @return the asset manifest for this Minecraft version
     *
     * @throws ModpackException if unable to read this manifest or its {@code assetIndex} entry
     */
    public GameAssetManifest getAssetManifest() throws ModpackException {
        JsonObject manifest = readToJsonObject();
        JsonObject assetIndex = JsonHelper.getRequiredJsonObject( manifest, "assetIndex" );
        String remote = JsonHelper.getRequiredString( assetIndex, "url" );
        return new GameAssetManifest( remote, parentModPack, getAssetIndexVersion() );
    }

    /**
     * Get the Minecraft game app information using the specified gameAppMode selection of client/server and modpack
     * root folder.
     *
     * @param gameAppMode client/server selection
     *
     * @return Minecraft game app as remote file object
     *
     * @throws ModpackException if unable to get Minecraft app information
     * @since 1.0
     */
    private ManagedGameFile getMinecraftApp( GameMode gameAppMode ) throws ModpackException {
        // Get download information for client or server Minecraft app
        JsonObject manifest = readToJsonObject();
        JsonObject downloads = JsonHelper.getRequiredJsonObject( manifest,
                ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY );
        JsonObject appObj;
        if ( gameAppMode == GameMode.CLIENT ) {
            appObj = JsonHelper.getRequiredJsonObject( downloads, "client" );
        }
        else if ( gameAppMode == GameMode.SERVER ) {
            appObj = JsonHelper.getRequiredJsonObject( downloads, "server" );
        }
        else {
            throw new ModpackException( "An invalid game app mode was specified." );
        }

        // Build RemoteFile with download information and return
        String localMinecraftAppPath = parentModPack.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_MINECRAFT_JAR_LOCAL_PATH;
        return new ManagedGameFile( JsonHelper.getRequiredString( appObj, "url" ), localMinecraftAppPath,
                                    JsonHelper.getRequiredString( appObj, "sha1" ), ManagedGameFileHashType.SHA1 );
    }

    /**
     * Download the Minecraft game app to the modpack at the specified root folder. Fetches client/server based on
     * supplied gameAppMode value.
     *
     * @param gameAppMode      client/server selection
     * @param progressProvider progress provider
     *
     * @throws ModpackException if download fails
     * @since 1.0
     */
    void downloadMinecraftApp( GameMode gameAppMode, GameModPackProgressProvider progressProvider )
    throws ModpackException
    {
        // Get access to Minecraft app as remote file
        ManagedGameFile mcAppRemoteFile = getMinecraftApp( gameAppMode );

        // "We've already verified (and, if needed, stripped) this jar" marker. Once a jar has
        // been stripped, its SHA-1 no longer matches the manifest, so re-running
        // updateLocalFile() would re-download (signed) and we'd loop. The marker short-circuits
        // that. Bumped from ".unsigned" to ".verified" so installs that were corrupted by the
        // old always-strip behavior get re-downloaded once on next launch.
        String mcJarPath = parentModPack.getPackRootFolder() + java.io.File.separator +
                com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MODPACK_MINECRAFT_JAR_LOCAL_PATH;
        java.io.File mcJar = new java.io.File( mcJarPath );
        java.io.File verifiedMarker = new java.io.File( mcJarPath + ".verified" );

        if ( !verifiedMarker.isFile() ) {
            // Verify and download as necessary (signed Mojang jar)
            mcAppRemoteFile.updateLocalFile();

            // Strip Mojang's META-INF signing only for pre-1.6 packs. There launchwrapper's
            // class transformer trips JarVerifier and breaks subsequent resource lookups
            // (StringTranslate /lang/*.lang loads return null). On 1.6+, Forge's
            // FMLSanityChecker validates the Mojang fingerprint and aborts with
            // "CRITICAL TAMPERING WITH MINECRAFT" if we strip — so we leave the jar alone.
            String mcVersion = parentModPack.getMinecraftVersion();
            if ( com.micatechnologies.minecraft.launcher.utilities.JarSigningStripper
                    .isStripRequiredFor( mcVersion ) ) {
                try {
                    boolean stripped = com.micatechnologies.minecraft.launcher.utilities.JarSigningStripper
                            .stripSigning( mcJar );
                    if ( stripped ) {
                        com.micatechnologies.minecraft.launcher.files.Logger.logStd(
                                LocalizationManager.get( "log.libraryManifest.strippedSigning" ) );
                    }
                }
                catch ( java.io.IOException e ) {
                    com.micatechnologies.minecraft.launcher.files.Logger.logWarningSilent(
                            LocalizationManager.format( "log.libraryManifest.stripSigningFailed",
                                                        e.getMessage() ) );
                }
            }

            // Always create the marker after a successful verification cycle so we don't
            // re-verify a stable jar on every launch. Stripped or not, the jar on disk is now
            // the one we want.
            try {
                if ( !verifiedMarker.createNewFile() && !verifiedMarker.isFile() ) {
                    com.micatechnologies.minecraft.launcher.files.Logger.logWarningSilent(
                            LocalizationManager.get( "log.libraryManifest.verifiedMarkerCreateFailed" ) );
                }
            }
            catch ( java.io.IOException e ) {
                com.micatechnologies.minecraft.launcher.files.Logger.logWarningSilent(
                        LocalizationManager.format( "log.libraryManifest.verifiedMarkerCreateFailedDetail",
                                                    e.getMessage() ) );
            }
        }

        // Update progress provider if present
        if ( progressProvider != null ) {
            progressProvider.submitProgress( LocalizationManager.get( "libraryManifest.verifiedMinecraftApp" ), 10 );
        }
    }

    // --- Modern manifest parsing (client.json) ---

    /**
     * Returns the required Java major version from this manifest's {@code javaVersion.majorVersion} field. Returns
     * {@link RuntimeConstants#DEFAULT_JAVA_MAJOR_VERSION} if the field is absent (pre-1.7 versions).
     *
     * @return the required Java major version
     *
     * @throws ModpackException if unable to read the manifest
     *
     * @since 2.0
     */
    public int getRequiredJavaMajorVersion() throws ModpackException {
        JsonObject manifest = readToJsonObject();
        if ( manifest.has( "javaVersion" ) ) {
            JsonObject javaVersion = manifest.getAsJsonObject( "javaVersion" );
            if ( javaVersion.has( "majorVersion" ) ) {
                return javaVersion.get( "majorVersion" ).getAsInt();
            }
        }
        return RuntimeConstants.DEFAULT_JAVA_MAJOR_VERSION;
    }

    /**
     * Returns the Mojang runtime component name from this manifest's {@code javaVersion.component} field.
     *
     * @return the runtime component name (e.g. "jre-legacy", "java-runtime-gamma")
     *
     * @throws ModpackException if unable to read the manifest
     *
     * @since 3.0
     */
    public String getRequiredRuntimeComponent() throws ModpackException {
        JsonObject manifest = readToJsonObject();
        if ( manifest.has( "javaVersion" ) ) {
            JsonObject javaVersion = manifest.getAsJsonObject( "javaVersion" );
            if ( javaVersion.has( "component" ) ) {
                return javaVersion.get( "component" ).getAsString();
            }
        }
        return RuntimeConstants.DEFAULT_RUNTIME_COMPONENT;
    }

    /**
     * Returns the vanilla main class from this manifest's {@code mainClass} field.
     *
     * @return the main class, or null if not present
     *
     * @throws ModpackException if unable to read the manifest
     *
     * @since 2.0
     */
    public String getVanillaMainClass() throws ModpackException {
        JsonObject manifest = readToJsonObject();
        if ( manifest.has( "mainClass" ) ) {
            return manifest.get( "mainClass" ).getAsString();
        }
        return null;
    }

    /**
     * Returns whether this manifest uses the modern {@code arguments} format (1.13+) rather than the legacy
     * {@code minecraftArguments} string.
     *
     * @return true if modern arguments format is available
     *
     * @throws ModpackException if unable to read the manifest
     *
     * @since 2.0
     */
    public boolean hasModernArguments() throws ModpackException {
        return readToJsonObject().has( "arguments" );
    }

    /**
     * Gets the flattened JVM arguments from the modern {@code arguments.jvm} array, evaluating rules for the current
     * platform. Returns an empty string if the manifest uses the legacy format or has no JVM arguments.
     *
     * @return space-separated JVM arguments string
     *
     * @throws ModpackException if unable to read the manifest
     *
     * @since 2.0
     */
    public String getJvmArguments() throws ModpackException {
        JsonObject manifest = readToJsonObject();
        if ( manifest.has( "arguments" ) ) {
            JsonObject arguments = manifest.getAsJsonObject( "arguments" );
            if ( arguments.has( "jvm" ) ) {
                return ManifestRuleUtilities.flattenArguments( arguments.getAsJsonArray( "jvm" ) );
            }
        }
        return "";
    }

    /**
     * Gets the flattened game arguments from the modern {@code arguments.game} array, evaluating rules for the current
     * platform. Returns an empty string if the manifest uses the legacy format or has no game arguments.
     *
     * @return space-separated game arguments string
     *
     * @throws ModpackException if unable to read the manifest
     *
     * @since 2.0
     */
    public String getGameArguments() throws ModpackException {
        JsonObject manifest = readToJsonObject();
        if ( manifest.has( "arguments" ) ) {
            JsonObject arguments = manifest.getAsJsonObject( "arguments" );
            if ( arguments.has( "game" ) ) {
                return ManifestRuleUtilities.flattenArguments( arguments.getAsJsonArray( "game" ) );
            }
        }

        // Fall back to legacy minecraftArguments if present
        if ( manifest.has( "minecraftArguments" ) ) {
            return manifest.get( "minecraftArguments" ).getAsString();
        }

        return "";
    }

    /**
     * Gets the logging configuration from this manifest, if present. Returns the log4j argument template (with
     * {@code ${path}} placeholder) and the log config file download information.
     *
     * @return a two-element array [argumentTemplate, logConfigUrl] or null if no logging config
     *
     * @throws ModpackException if unable to read the manifest
     *
     * @since 2.0
     */
    public String[] getLoggingConfig() throws ModpackException {
        JsonObject manifest = readToJsonObject();
        if ( manifest.has( "logging" ) ) {
            JsonObject logging = manifest.getAsJsonObject( "logging" );
            if ( logging.has( "client" ) ) {
                JsonObject clientLogging = logging.getAsJsonObject( "client" );
                String argument = clientLogging.has( "argument" ) ?
                                  clientLogging.get( "argument" ).getAsString() : null;
                String fileUrl = null;
                String fileSha1 = null;
                String fileId = null;
                if ( clientLogging.has( "file" ) ) {
                    JsonObject file = clientLogging.getAsJsonObject( "file" );
                    fileUrl = file.has( "url" ) ? file.get( "url" ).getAsString() : null;
                    fileSha1 = file.has( "sha1" ) ? file.get( "sha1" ).getAsString() : null;
                    fileId = file.has( "id" ) ? file.get( "id" ).getAsString() : null;
                }
                if ( argument != null && fileUrl != null ) {
                    return new String[]{ argument, fileUrl, fileSha1, fileId };
                }
            }
        }
        return null;
    }
}

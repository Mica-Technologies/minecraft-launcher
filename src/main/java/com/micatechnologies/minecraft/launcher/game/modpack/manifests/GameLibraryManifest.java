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
import com.micatechnologies.minecraft.launcher.game.modpack.LaunchPrepareContext;
import com.micatechnologies.minecraft.launcher.game.modpack.Lwjgl2ArmPatcher;
import com.micatechnologies.minecraft.launcher.game.modpack.ManagedGameFile;
import com.micatechnologies.minecraft.launcher.game.modpack.MavenArtifactPath;
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
        this.standaloneManifest = null;
    }

    /**
     * A loader's standalone version profile (Cleanroom's {@code version.json}), used in place of
     * the vanilla client.json; {@code null} for Mojang's client.json.
     *
     * @since 2026.11
     */
    private final JsonObject standaloneManifest;

    /**
     * Creates a manifest backed by a loader's standalone version profile instead of a downloaded
     * client.json. Nothing is downloaded or read from {@code bin/libraries.manifest}; every getter
     * (libraries and natives, Java runtime, asset index, client and server jar, arguments) reads
     * the given profile.
     *
     * @param manifest      the standalone profile; owned by this instance from now on
     * @param parentModPack parent mod pack
     *
     * @since 2026.11
     */
    private GameLibraryManifest( JsonObject manifest, GameModPack parentModPack ) {
        super( "", SystemUtilities.buildFilePath( parentModPack.getPackBinFolder(),
                                                  LocalPathConstants.MINECRAFT_LIBRARY_MANIFEST_FILE_NAME ) );
        this.parentModPack = parentModPack;
        this.standaloneManifest = manifest;
    }

    /**
     * Creates a manifest backed by a loader's standalone version profile (see
     * {@link com.micatechnologies.minecraft.launcher.game.modpack.GameModLoader#getStandaloneVersionManifest()}).
     *
     * @param manifest      the standalone profile; owned by the returned instance
     * @param parentModPack parent mod pack
     *
     * @return the manifest
     *
     * @since 2026.11
     */
    public static GameLibraryManifest standalone( JsonObject manifest, GameModPack parentModPack ) {
        return new GameLibraryManifest( manifest, parentModPack );
    }

    /**
     * Returns whether this manifest is a loader's standalone version profile rather than Mojang's
     * client.json. Such a profile brings its own LWJGL, so nothing keyed on vanilla's libraries
     * (the LWJGL 2 ARM64 natives patch) applies, and the natives folder is emptied before its
     * natives are extracted.
     *
     * @return {@code true} for a standalone profile
     *
     * @since 2026.11
     */
    public boolean isStandalone() {
        return standaloneManifest != null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A standalone profile is returned as given: there is no file to download or read.</p>
     */
    @Override
    public JsonObject readToJsonObject() throws ModpackException {
        return standaloneManifest != null ? standaloneManifest : super.readToJsonObject();
    }

    /**
     * {@inheritDoc}
     *
     * <p>A standalone profile has no file: nothing to check or download.</p>
     */
    @Override
    public boolean updateLocalFile( LaunchPrepareContext context ) throws ModpackException {
        return standaloneManifest == null && super.updateLocalFile( context );
    }

    /**
     * Checks this manifest and the files it lists under the pack's current prepare run.
     *
     * @return the parent pack's prepare context
     */
    @Override
    protected LaunchPrepareContext prepareContext() {
        return parentModPack != null ? parentModPack.getPrepareContext() : LaunchPrepareContext.NONE;
    }

    /**
     * Get the list of all applicable libraries for the current system from this Minecraft library manifest.
     *
     * @param side the game side the libraries are for; an entry marked for the other side is left out
     *
     * @return list of {@link GameLibrary} objects
     *
     * @throws ModpackException if download or processing fails
     * @since 1.0
     */
    private ArrayList< GameLibrary > getLibraries( GameMode side ) throws ModpackException {
        return extractLibraries( readToJsonObject(), ManifestRuleUtilities.Host.current(), side );
    }

    /**
     * Scans an already-parsed client.json's {@code libraries} array and returns the list of
     * {@link GameLibrary} objects applicable to the current system, for either side: resolving
     * per-platform native classifiers, evaluating each library's {@code rules} array, and filtering
     * to libraries whose applicable-OS list contains the current platform.
     *
     * @param libManifest the parsed client.json to scan
     *
     * @return list of applicable {@link GameLibrary} objects
     *
     * @throws ModpackException if a library entry is missing a required field
     *
     * @since 3.0
     */
    // Package-private and static (extracted from getLibraries()) so this platform-selection
    // logic -- which libraries and natives actually end up on the classpath -- can be tested
    // directly against a hand-built manifest, without a real GameModPack/downloaded file backing
    // this instance. Reads no instance state (parentModPack is never referenced in the body).
    static ArrayList< GameLibrary > extractLibraries( JsonObject libManifest ) throws ModpackException {
        return extractLibraries( libManifest, ManifestRuleUtilities.Host.current(), null );
    }

    /**
     * Scans an already-parsed client.json (or a loader's standalone version profile) and returns
     * the {@link GameLibrary} objects that apply to {@code host} on {@code side}.
     *
     * <p>Beyond Mojang's own format this reads two things Cleanroom's standalone
     * {@code version.json} relies on, neither of which appears in a Mojang client.json:</p>
     * <ul>
     *   <li>An entry marked {@code "side": "client"} or {@code "side": "server"} is left out on
     *       the other side (Cleanroom marks every natives entry client-only, and its installer
     *       leaves them off a server). An entry without {@code side} applies to both.</li>
     *   <li>A download without a {@code path} (Cleanroom's legacy natives classifiers carry only
     *       {@code url} and {@code sha1}) gets the Maven path of the library name plus the
     *       classifier. A name that is no usable coordinate fails rather than inventing a
     *       path.</li>
     * </ul>
     *
     * @param libManifest the parsed manifest to scan
     * @param host        the host the libraries are resolved for
     * @param side        the game side, or {@code null} to keep every entry regardless of its
     *                    {@code side}
     *
     * @return the applicable libraries, in manifest order
     *
     * @throws ModpackException if a library entry is missing a required field
     *
     * @since 2026.11
     */
    public static ArrayList< GameLibrary > extractLibraries( JsonObject libManifest,
                                                             ManifestRuleUtilities.Host host,
                                                             GameMode side ) throws ModpackException
    {
        // Create list to return
        ArrayList< GameLibrary > libraries = new ArrayList<>();
        String currentPlatform = host.platformName();

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
                // An entry for the other side is left out entirely (not malformed, so no warning).
                if ( !appliesToSide( libManifestLibObj, side ) ) {
                    continue;
                }
                String libName = JsonHelper.getString( libManifestLibObj,
                        ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_NAME_KEY, "" );

                // Library information variables
                String path = null;
                String sha1 = null;
                String url = null;

                // Check for and process library information
                JsonObject downloadsObj = libManifestLibObj.getAsJsonObject(
                        ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY );
                JsonObject artifactObj = JsonHelper.getJsonObject( downloadsObj,
                        ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_ARTIFACT_KEY );
                if ( artifactObj != null ) {
                    // Store library path, sha1 and url
                    path = Paths.get( pathOrDerived( artifactObj, libName, null ) ).toString();
                    sha1 = JsonHelper.getRequiredString( artifactObj, "sha1" );
                    url = JsonHelper.getRequiredString( artifactObj, "url" );
                }

                boolean libraryAllowed = ManifestRuleUtilities.evaluateRules(
                        libManifestLibObj.has( "rules" ) ? libManifestLibObj.getAsJsonArray( "rules" ) : null,
                        host );

                ArrayList< String > rulesOS = new ArrayList<>();
                if ( libraryAllowed ) {
                    rulesOS.add( currentPlatform );
                }
                boolean useStrictRules = true;

                // Check for and process library classifiers
                // Note: classifiers are additional libraries or
                // sub-libraries associated with the parent library
                // Note: logging classifiers are ignored.
                JsonObject classifiersObj = JsonHelper.getJsonObject( downloadsObj, "classifiers" );
                String nativeOsKey = switch ( host.os() ) {
                    case WINDOWS -> "windows";
                    case MACOS -> "osx";
                    case LINUX -> "linux";
                    default -> null;
                };
                if ( classifiersObj != null && libraryAllowed && nativeOsKey != null ) {
                    String nativeKey = resolveNativeClassifierKey( libManifestLibObj, nativeOsKey, host );
                    if ( nativeKey != null && classifiersObj.has( nativeKey ) ) {
                        // The native applies to the host's platform only
                        ArrayList< String > nativeValidOS = new ArrayList<>();
                        nativeValidOS.add( currentPlatform );

                        // Initialize new native library object
                        JsonObject nativeObj = classifiersObj.getAsJsonObject( nativeKey );
                        String localLibPath = pathOrDerived( nativeObj, libName, nativeKey );
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
                    GameLibrary thisLib = new GameLibrary( url, path, sha1, ManagedGameFileHashType.SHA1,
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
     * Returns whether a library entry applies to the given side. Only an entry whose {@code side}
     * names the other side is left out; an entry without {@code side} (every Mojang entry) applies
     * to both, as does every entry when no side is given.
     *
     * @param libraryObject the library entry
     * @param side          the game side, or {@code null} for either
     *
     * @return {@code true} if the entry applies to {@code side}
     *
     * @since 2026.11
     */
    static boolean appliesToSide( JsonObject libraryObject, GameMode side ) {
        if ( side == null ) {
            return true;
        }
        String librarySide = JsonHelper.getString( libraryObject, "side", null );
        if ( librarySide == null || librarySide.isBlank() ) {
            return true;
        }
        String wanted = side == GameMode.SERVER ? "server" : "client";
        return librarySide.trim().equalsIgnoreCase( wanted );
    }

    /**
     * Returns a download's {@code path}, or, when it is absent or blank, the Maven path of the
     * library {@code name} with {@code classifier} appended (Cleanroom's legacy natives
     * classifiers carry only {@code url} and {@code sha1}). Mojang's downloads always carry a
     * path, so for them this returns it unchanged.
     *
     * @param download   the {@code artifact} or classifier download object
     * @param name       the library's Maven coordinate ({@code group:artifact:version})
     * @param classifier the classifier key, or {@code null} for the main artifact
     *
     * @return the download's repository-relative path, with forward slashes
     *
     * @throws IllegalArgumentException if the path is missing and {@code name} is no usable
     *                                  coordinate (or the classifier would leave the libraries
     *                                  folder): the same failure as before paths were derived
     *
     * @since 2026.11
     */
    static String pathOrDerived( JsonObject download, String name, String classifier ) {
        String path = JsonHelper.getString( download, "path", null );
        if ( path != null && !path.isBlank() ) {
            return path;
        }
        String coordinate = classifier == null ? name : name + ":" + classifier;
        try {
            return MavenArtifactPath.toRelativePathStrict( coordinate );
        }
        catch ( ModpackException e ) {
            // Not a coordinate: fail on the missing path exactly as an underived read did.
            return JsonHelper.getRequiredString( download, "path" );
        }
    }

    /**
     * Resolves the classifier key used to look up a native sub-library for the given OS within a library entry's
     * {@code downloads.classifiers} object, for the current host.
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
    // Package-private and static (was private, instance) so the classifier-resolution
    // rules can be tested directly. The method reads no instance state -- only its
    // arguments and the os.arch system property -- so making it static is behaviour-
    // preserving, and it avoids needing a fully-constructed GameLibraryManifest (whose
    // constructor requires a real GameModPack and resolves launcher paths) just to
    // exercise a pure string rule.
    static String resolveNativeClassifierKey( JsonObject libraryObject, String osKey ) {
        return resolveNativeClassifierKey( libraryObject, osKey, ManifestRuleUtilities.Host.current() );
    }

    /**
     * {@link #resolveNativeClassifierKey(JsonObject, String)} for the given host.
     *
     * @param libraryObject the library entry JSON object from the manifest
     * @param osKey         the Mojang OS key to resolve a native classifier for
     * @param host          the host whose architecture fills {@code ${arch}}
     *
     * @return the resolved classifier key for the requested OS
     *
     * @since 2026.11
     */
    static String resolveNativeClassifierKey( JsonObject libraryObject, String osKey,
                                              ManifestRuleUtilities.Host host ) {
        if ( libraryObject.has( "natives" ) ) {
            JsonObject nativesObject = libraryObject.getAsJsonObject( "natives" );
            if ( nativesObject.has( osKey ) ) {
                String key = nativesObject.get( osKey ).getAsString();
                String arch = host.is64Bit() ? "64" : "32";
                return key.replace( "${arch}", arch );
            }
        }
        return "natives-" + osKey;
    }

    /**
     * Verify each library's local copy and download/update if necessary
     *
     * @param side             the game side the libraries are for
     * @param progressProvider progress provider
     *
     * @throws ModpackException if update or download fails
     * @since 1.0
     */
    private void downloadVerifyLibraries( GameMode side, GameModPackProgressProvider progressProvider )
    throws ModpackException, InterruptedException, ExecutionException
    {
        // Build full path to libraries and natives folders
        String localLibPath = parentModPack.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER;
        String localNativePath = parentModPack.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER;

        // A standalone profile brings its own LWJGL: natives an earlier vanilla or Forge launch of
        // this pack left behind (LWJGL 2's lwjgl64.dll, OpenAL64.dll) must not be found, so the
        // folder is emptied once, before any of this profile's natives are extracted into it.
        if ( isStandalone() ) {
            clearNativesFolder( Path.of( localNativePath ) );
        }

        // Get list of libraries
        ArrayList< GameLibrary > libraries = getLibraries( side );
        if ( libraries.isEmpty() ) {
            return;
        }

        // Build list of library download tasks on the shared bounded download pool. Every
        // library is checked under the same prepare run, captured once here.
        final LaunchPrepareContext context = prepareContext();
        List< Future< Boolean > > threadPoolFutures = new ArrayList<>();
        for ( GameLibrary library : libraries ) {
            Callable< Boolean > updateFileCallable = () -> {
                library.setLocalPathPrefix( localLibPath );
                boolean didChange = library.updateLocalFile( context );
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
                        throw new ModpackException( LocalizationManager.get( "minecraft.error.nativeExtractFailed" ), e );
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
            throw new ModpackException( LocalizationManager.get( "minecraft.error.libsTimedOut" ) );
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
        ArrayList< GameLibrary > minecraftLibsList = getLibraries( appGameMode );
        if ( progressProvider != null ) {
            progressProvider.submitProgress( LocalizationManager.get( "libraryManifest.gotLibraryList" ), 5 );
        }

        // Download the libraries
        try {
            downloadVerifyLibraries( appGameMode, progressProvider );
        }
        catch ( InterruptedException e ) {
            throw new ModpackException( LocalizationManager.get( "minecraft.error.libsInterrupted" ), e );
        }
        catch ( ExecutionException e ) {
            throw new ModpackException( LocalizationManager.format( "minecraft.error.libsFailed",
                                                                    String.valueOf( causeMessage( e ) ) ), e );
        }

        // Patch LWJGL2 natives for ARM64 if needed (replaces x86_64 dylibs/JARs with ARM64 builds).
        // Only vanilla's LWJGL 2 natives: a standalone profile (Cleanroom) runs LWJGL 3 with its
        // own ARM64 natives.
        String mcVersion = parentModPack.getMinecraftVersion();
        if ( !isStandalone() && Lwjgl2ArmPatcher.isNeeded( mcVersion ) ) {
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
            throw new ModpackException( LocalizationManager.get( "minecraft.error.assetsInterrupted" ), e );
        }
        catch ( ExecutionException e ) {
            throw new ModpackException( LocalizationManager.format( "minecraft.error.assetsFailed",
                                                                    String.valueOf( causeMessage( e ) ) ), e );
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
        return extractAssetIndexVersion( readToJsonObject() );
    }

    /**
     * Reads the asset index version (id) from an already-parsed client.json's
     * {@code assetIndex.id} field.
     *
     * @param manifest the parsed client.json to read
     *
     * @return the asset index version
     *
     * @throws ModpackException if the {@code assetIndex} object or its {@code id} field is missing
     *
     * @since 3.0
     */
    // Package-private and static, same rationale as extractRequiredJavaMajorVersion above.
    static String extractAssetIndexVersion( JsonObject manifest ) throws ModpackException {
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
            mcAppRemoteFile.updateLocalFile( prepareContext() );

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
        return extractRequiredJavaMajorVersion( readToJsonObject() );
    }

    /**
     * Reads the required Java major version from an already-parsed client.json's
     * {@code javaVersion.majorVersion} field. Returns
     * {@link RuntimeConstants#DEFAULT_JAVA_MAJOR_VERSION} if the field is absent.
     *
     * @param manifest the parsed client.json to read
     *
     * @return the required Java major version
     *
     * @since 3.0
     */
    // Package-private and static (extracted from getRequiredJavaMajorVersion) so the pure
    // JSON-field read can be tested directly against a hand-built manifest, without a real
    // GameModPack / downloaded file backing this instance.
    static int extractRequiredJavaMajorVersion( JsonObject manifest ) {
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
        return extractRequiredRuntimeComponent( readToJsonObject() );
    }

    /**
     * Reads the required Mojang runtime component name from an already-parsed client.json's
     * {@code javaVersion.component} field. Returns {@link RuntimeConstants#DEFAULT_RUNTIME_COMPONENT}
     * if the field is absent.
     *
     * @param manifest the parsed client.json to read
     *
     * @return the runtime component name
     *
     * @since 3.0
     */
    // Package-private and static, same rationale as extractRequiredJavaMajorVersion above.
    static String extractRequiredRuntimeComponent( JsonObject manifest ) {
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
        return extractVanillaMainClass( readToJsonObject() );
    }

    /**
     * Reads the vanilla main class from an already-parsed client.json's {@code mainClass} field.
     *
     * @param manifest the parsed client.json to read
     *
     * @return the main class, or null if not present
     *
     * @since 3.0
     */
    // Package-private and static, same rationale as extractRequiredJavaMajorVersion above.
    static String extractVanillaMainClass( JsonObject manifest ) {
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
        return extractJvmArguments( readToJsonObject() );
    }

    /**
     * Reads and flattens the modern {@code arguments.jvm} array from an already-parsed client.json.
     * Returns an empty string when the manifest uses the legacy format or has no JVM arguments.
     *
     * @param manifest the parsed client.json to read
     *
     * @return space-separated JVM arguments string
     *
     * @since 3.0
     */
    // Package-private and static, same rationale as extractRequiredJavaMajorVersion above.
    static String extractJvmArguments( JsonObject manifest ) {
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
        return extractGameArguments( readToJsonObject() );
    }

    /**
     * Reads and flattens the modern {@code arguments.game} array from an already-parsed client.json,
     * falling back to the legacy {@code minecraftArguments} string when the modern array is absent.
     *
     * @param manifest the parsed client.json to read
     *
     * @return space-separated game arguments string, or an empty string if neither form is present
     *
     * @since 3.0
     */
    // Package-private and static, same rationale as extractRequiredJavaMajorVersion above.
    static String extractGameArguments( JsonObject manifest ) {
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
        return extractLoggingConfig( readToJsonObject() );
    }

    /**
     * Reads the logging configuration from an already-parsed client.json, if present. Returns the
     * log4j argument template (with {@code ${path}} placeholder) and the log config file download
     * information.
     *
     * @param manifest the parsed client.json to read
     *
     * @return a four-element array {@code [argumentTemplate, fileUrl, fileSha1, fileId]}, or
     *         {@code null} if no complete logging config is present
     *
     * @since 3.0
     */
    // Package-private and static, same rationale as extractRequiredJavaMajorVersion above.
    static String[] extractLoggingConfig( JsonObject manifest ) {
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

    /**
     * Returns the message of the failure behind a download runner's {@link ExecutionException}, for
     * inclusion in the user-facing error.
     *
     * @param e the exception thrown while waiting on the runner
     *
     * @return the underlying cause's message, or the exception's own when there is no cause
     *
     * @since 2026.10
     */
    private static String causeMessage( ExecutionException e )
    {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /**
     * Empties a pack's natives folder (and creates it when absent), so natives an earlier launch
     * of the pack extracted there cannot be loaded by the next one.
     *
     * @param nativesFolder the pack's {@code bin/natives} folder
     *
     * @throws ModpackException if the folder or something in it cannot be deleted
     *
     * @since 2026.11
     */
    static void clearNativesFolder( Path nativesFolder ) throws ModpackException
    {
        try {
            File folder = nativesFolder.toFile();
            if ( folder.exists() ) {
                org.apache.commons.io.FileUtils.deleteDirectory( folder );
            }
            java.nio.file.Files.createDirectories( nativesFolder );
        }
        catch ( IOException e ) {
            throw new ModpackException( LocalizationManager.get( "minecraft.error.nativeExtractFailed" ), e );
        }
    }
}

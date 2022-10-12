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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.game.modpack.GameLibrary;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackProgressProvider;
import com.micatechnologies.minecraft.launcher.game.modpack.ManagedGameFile;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
                if ( libManifestLibObj.getAsJsonObject(
                                              ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                      .has( ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_ARTIFACT_KEY ) ) {
                    // Store library path, sha1 and url
                    path = Paths.get( libManifestLibObj.getAsJsonObject(
                                                               ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                                       .getAsJsonObject(
                                                               ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_ARTIFACT_KEY )
                                                       .get( "path" )
                                                       .getAsString() );
                    sha1 = libManifestLibObj.getAsJsonObject(
                                                    ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                            .getAsJsonObject(
                                                    ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_ARTIFACT_KEY )
                                            .get( "sha1" )
                                            .getAsString();
                    url = libManifestLibObj.getAsJsonObject(
                                                   ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                           .getAsJsonObject(
                                                   ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_ARTIFACT_KEY )
                                           .get( "url" )
                                           .getAsString();
                }

                // Create list for operating system rules
                ArrayList< String > rulesOS = new ArrayList<>();

                // Check for and process library operating system rules
                boolean useStrictRules = libManifestLibObj.has( "rules" );
                if ( useStrictRules ) {
                    // Loop through each rule in rules list
                    JsonArray rules = libManifestLibObj.getAsJsonArray( "rules" );
                    for ( JsonElement rule : rules ) {
                        // Get array element as JSON object
                        JsonObject ruleObj = rule.getAsJsonObject();

                        // Check for generic allow rule
                        if ( ruleObj.has( "action" ) &&
                                ruleObj.get( "action" ).getAsString().equalsIgnoreCase( "allow" ) &&
                                !ruleObj.has( "os" ) ) {
                            // Allow all OS
                            rulesOS.add( ModPackConstants.PLATFORM_WINDOWS );
                            rulesOS.add( ModPackConstants.PLATFORM_MACOS );
                            rulesOS.add( ModPackConstants.PLATFORM_UNIX );
                        }
                        // Check for operating system specific allow rule
                        else if ( ruleObj.has( "action" ) &&
                                ruleObj.get( "action" ).getAsString().equalsIgnoreCase( "allow" ) &&
                                ruleObj.has( "os" ) ) {
                            rulesOS.add( ruleObj.getAsJsonObject( "os" ).get( "name" ).getAsString() );
                        }
                        // Check for operating system specific disallow rule
                        else if ( ruleObj.has( "action" ) &&
                                ruleObj.get( "action" ).getAsString().equalsIgnoreCase( "disallow" ) &&
                                ruleObj.has( "os" ) ) {
                            rulesOS.remove( ruleObj.getAsJsonObject( "os" ).get( "name" ).getAsString() );
                        }
                        // Handle unidentified rule type
                        else {
                            System.err.println( "MISS AT RULES" );
                        }
                    }
                }

                // Check for and process library classifiers
                // Note: classifiers are additional libraries or
                // sub-libraries associated with the parent library
                // Note: logging classifiers are ignored.
                if ( libManifestLibObj.getAsJsonObject(
                        ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY ).has( "classifiers" ) ) {
                    // Create new library object to temporarily hold classifiers
                    GameLibrary nativeLib;

                    // Check for and process windows native libraries
                    if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS &&
                            libManifestLibObj.getAsJsonObject(
                                                     ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                             .getAsJsonObject( "classifiers" )
                                             .has( "natives-windows" ) ) {
                        // Create list to store applicable OSes for native
                        ArrayList< String > nativeValidOS = new ArrayList<>();

                        // Add Windows as an applicable OS if library supports Windows
                        if ( rulesOS.contains( "windows" ) ) {
                            nativeValidOS.add( "windows" );
                        }

                        // Initialize new native library object
                        String localLibPath = libManifestLibObj.getAsJsonObject(
                                                                       ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                                               .getAsJsonObject( "classifiers" )
                                                               .getAsJsonObject( "natives-windows" )
                                                               .get( "path" )
                                                               .getAsString();
                        nativeLib = new GameLibrary( libManifestLibObj.getAsJsonObject(
                                                                              ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                                                      .getAsJsonObject( "classifiers" )
                                                                      .getAsJsonObject( "natives-windows" )
                                                                      .get( "url" )
                                                                      .getAsString(), localLibPath,
                                                     libManifestLibObj.getAsJsonObject(
                                                                              ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                                                      .getAsJsonObject( "classifiers" )
                                                                      .getAsJsonObject( "natives-windows" )
                                                                      .get( "sha1" )
                                                                      .getAsString(), ManagedGameFileHashType.MD5, true,
                                                     nativeValidOS, true );

                        // Add native library to applicable libraries list
                        libraries.add( nativeLib );
                    }

                    // Check for and process macOS native libraries
                    if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC &&
                            libManifestLibObj.getAsJsonObject(
                                                     ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                             .getAsJsonObject( "classifiers" )
                                             .has( "natives-osx" ) ) {
                        // Create list to store applicable OSes for native
                        ArrayList< String > nativeValidOS = new ArrayList<>();

                        // Add macOS as an applicable OS if library supports macOS
                        if ( rulesOS.contains( "osx" ) ) {
                            nativeValidOS.add( "osx" );
                        }

                        // Initialize new native library object
                        String localLibPath = libManifestLibObj.getAsJsonObject(
                                                                       ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                                               .getAsJsonObject( "classifiers" )
                                                               .getAsJsonObject( "natives-osx" )
                                                               .get( "path" )
                                                               .getAsString();
                        nativeLib = new GameLibrary( libManifestLibObj.getAsJsonObject(
                                                                              ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                                                      .getAsJsonObject( "classifiers" )
                                                                      .getAsJsonObject( "natives-osx" )
                                                                      .get( "url" )
                                                                      .getAsString(), localLibPath,
                                                     libManifestLibObj.getAsJsonObject(
                                                                              ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                                                      .getAsJsonObject( "classifiers" )
                                                                      .getAsJsonObject( "natives-osx" )
                                                                      .get( "sha1" )
                                                                      .getAsString(), ManagedGameFileHashType.MD5, true,
                                                     nativeValidOS, true );

                        // Add native library to applicable libraries list
                        libraries.add( nativeLib );
                    }

                    // Check for and process Linux native libraries if on Linux
                    if ( org.apache.commons.lang3.SystemUtils.IS_OS_LINUX &&
                            libManifestLibObj.getAsJsonObject(
                                                     ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                             .getAsJsonObject( "classifiers" )
                                             .has( "natives-linux" ) ) {
                        // Create list to store applicable OSes for native
                        ArrayList< String > nativeValidOS = new ArrayList<>();

                        // Add Linux as an applicable OS if library supports Linux
                        if ( rulesOS.contains( "linux" ) ) {
                            nativeValidOS.add( "linux" );
                        }

                        // Initialize new native library object
                        String localLibPath = libManifestLibObj.getAsJsonObject(
                                                                       ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                                               .getAsJsonObject( "classifiers" )
                                                               .getAsJsonObject( "natives-linux" )
                                                               .get( "path" )
                                                               .getAsString();
                        nativeLib = new GameLibrary( libManifestLibObj.getAsJsonObject(
                                                                              ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                                                      .getAsJsonObject( "classifiers" )
                                                                      .getAsJsonObject( "natives-linux" )
                                                                      .get( "url" )
                                                                      .getAsString(), localLibPath,
                                                     libManifestLibObj.getAsJsonObject(
                                                                              ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY )
                                                                      .getAsJsonObject( "classifiers" )
                                                                      .getAsJsonObject( "natives-linux" )
                                                                      .get( "sha1" )
                                                                      .getAsString(), ManagedGameFileHashType.MD5, true,
                                                     nativeValidOS, true );

                        // Add native library to applicable libraries list
                        libraries.add( nativeLib );
                    }
                }

                // Create final library object and add to applicable list
                if ( path != null && url != null && sha1 != null ) {
                    GameLibrary thisLib = new GameLibrary( url, path.toString(), sha1, ManagedGameFileHashType.MD5,
                                                           useStrictRules, rulesOS, false );

                    if ( ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS &&
                            thisLib.getApplicableOSes().contains( ModPackConstants.PLATFORM_WINDOWS ) ) ||
                            ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC &&
                                    thisLib.getApplicableOSes().contains( ModPackConstants.PLATFORM_MACOS ) ) ||
                            ( org.apache.commons.lang3.SystemUtils.IS_OS_LINUX &&
                                    thisLib.getApplicableOSes().contains( ModPackConstants.PLATFORM_UNIX ) ) ) {

                        libraries.add( thisLib );
                    }
                }
            }
            // Output failure to decode library JSON
            else {
                System.err.println( "SKIPPED A LIBRARY - UNKNOWN TYPE" );
                System.err.println( libManifestLibObj );
            }
        }
        return libraries;
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

        // Build list of library download threads
        ExecutorService threadPool = Executors.newFixedThreadPool( libraries.size() );
        List< Future< Boolean > > threadPoolFutures = new ArrayList<>();
        for ( GameLibrary library : libraries ) {
            Callable< Boolean > updateFileCallable = () -> {
                library.setLocalPathPrefix( localLibPath );
                boolean didChange = library.updateLocalFile();
                if ( library.isNativeLib() && didChange ) {
                    try {
                        SystemUtilities.extractJarFile( new JarFile( library.getFullLocalFilePath() ),
                                                        localNativePath );
                    }
                    catch ( IOException e ) {
                        throw new ModpackException( "Unable to extract native library.", e );
                    }
                }

                // Update progress provider if present
                if ( progressProvider != null ) {
                    progressProvider.submitProgress( "Verified library " + library.getFileName(),
                                                     ( 25.0 / ( double ) libraries.size() ) );
                }
                return didChange;
            };
            Future< Boolean > future = threadPool.submit( updateFileCallable );
            threadPoolFutures.add( future );
        }
        threadPool.shutdown();
        threadPool.awaitTermination( Long.MAX_VALUE, TimeUnit.MILLISECONDS );

        // Parse list of futures
        for ( Future< Boolean > threadPoolFuture : threadPoolFutures ) {
            threadPoolFuture.get();
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
            progressProvider.submitProgress( "Got Minecraft library list", 5 );
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
        for ( GameLibrary library : minecraftLibsList ) {
            // Add separator to string if necessary
            if ( ( classpath.length() > 0 ) && !classpath.toString().endsWith( File.pathSeparator ) ) {
                classpath.append( File.pathSeparator );
            }

            // Add library to classpath
            String localLibPath = parentModPack.getPackRootFolder() +
                    File.separator +
                    ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER;
            library.setLocalPathPrefix( localLibPath );
            classpath.append( library.getFullLocalFilePath() );

            // Update progress provider if present
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Added to classpath: " + library.getLocalFilePath(),
                                                 ( 10.0 / ( double ) minecraftLibsList.size() ) );
            }
        }

        // Add separator to string if necessary
        if ( ( classpath.length() > 0 ) && !classpath.toString().endsWith( File.pathSeparator ) ) {
            classpath.append( File.pathSeparator );
        }

        // Add Minecraft jar to classpath
        classpath.append( getMinecraftApp( appGameMode ).getFullLocalFilePath() );

        return classpath.toString();
    }

    void downloadMinecraftAssets( final GameModPackProgressProvider progressProvider )
    throws ModpackException, InterruptedException, ExecutionException
    {
        getAssetManifest().downloadAssets( progressProvider );
    }

    /**
     * Get the asset index version for Minecraft
     *
     * @return asset index version
     *
     * @throws ModpackException if unable to read manifest
     */
    public String getAssetIndexVersion() throws ModpackException {
        return readToJsonObject().get( "assetIndex" ).getAsJsonObject().get( "id" ).getAsString();
    }

    GameAssetManifest getAssetManifest() throws ModpackException {
        String remote = readToJsonObject().get( "assetIndex" ).getAsJsonObject().get( "url" ).getAsString();
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
        JsonObject appObj;
        if ( gameAppMode == GameMode.CLIENT ) {
            appObj = readToJsonObject().getAsJsonObject(
                    ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY ).getAsJsonObject( "client" );
        }
        else if ( gameAppMode == GameMode.SERVER ) {
            appObj = readToJsonObject().getAsJsonObject(
                    ManifestConstants.MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY ).getAsJsonObject( "server" );
        }
        else {
            throw new ModpackException( "An invalid game app mode was specified." );
        }

        // Build RemoteFile with download information and return
        String localMinecraftAppPath = parentModPack.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_MINECRAFT_JAR_LOCAL_PATH;
        return new ManagedGameFile( appObj.get( "url" ).getAsString(), localMinecraftAppPath,
                                    appObj.get( "sha1" ).getAsString(), ManagedGameFileHashType.SHA1 );
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

        // Verify Minecraft app and update or download as necessary
        mcAppRemoteFile.updateLocalFile();

        // Update progress provider if present
        if ( progressProvider != null ) {
            progressProvider.submitProgress( "Verified Minecraft application", 10 );
        }
    }
}

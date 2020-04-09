package com.micatechnologies.minecraft.forgemodpacklib;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.forgelauncher.exceptions.FLModpackException;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLSystemUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.jar.JarFile;

/**
 * Class representing a library manifest for a specific Minecraft version, based on specified URL.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
class MCLibraryManifest extends MCRemoteFile {

    /**
     * Local path of Minecraft library manifest, relative to modpack root folder
     */
    private static final String MINECRAFT_LIBRARY_MANIFEST_LOCAL_MODPACK_PATH =
        "bin" + FLSystemUtils.getFileSeparator() + "minecraft-libraries.manifest";

    private final        String modpackRootFolder;

    /**
     * Create a Minecraft library manifest object for modpack at modpackRootFolder. Instances do not
     * require a local path prefix to be defined for download.
     *
     * @param remote            library manifest URL
     * @param modpackRootFolder root folder of modpack
     *
     * @since 1.0
     */
    MCLibraryManifest( String remote, String modpackRootFolder ) {
        super( remote, modpackRootFolder + FLSystemUtils.getFileSeparator()
            + MINECRAFT_LIBRARY_MANIFEST_LOCAL_MODPACK_PATH );

        // stor emodpack install directory
        this.modpackRootFolder = modpackRootFolder;
    }

    /**
     * Get the list of all applicable libraries for the current system from this Minecraft library
     * manifest.
     *
     * @return list of {@link MCLibrary} objects
     *
     * @throws FLModpackException if download or processing fails
     * @since 1.0
     */
    private ArrayList< MCLibrary > getLibraries() throws FLModpackException {
        // Create list to return
        ArrayList< MCLibrary > libraries = new ArrayList<>();

        // Get JsonObject of manifest
        JsonObject libManifest = readToJsonObject();

        // Loop through each library in manifest
        JsonArray libManifestLibraries = libManifest.getAsJsonArray( "libraries" );
        for ( JsonElement libManifestLib : libManifestLibraries ) {
            // Get element as a JsonObject
            JsonObject libManifestLibObj = libManifestLib.getAsJsonObject();

            // Check for library entry format
            // name : String
            // downloads: Object
            if ( libManifestLibObj.has( "downloads" ) && libManifestLibObj.has( "name" ) ) {
                // Library information variables
                Path path = null;
                String sha1 = null;
                String url = null;

                // Check for and process library information
                if ( libManifestLibObj.getAsJsonObject( "downloads" ).has( "artifact" ) ) {
                    // Store library path, sha1 and url
                    path = Paths.get( libManifestLibObj.getAsJsonObject( "downloads" )
                                                       .getAsJsonObject( "artifact" ).get( "path" )
                                                       .getAsString() );
                    sha1 = libManifestLibObj.getAsJsonObject( "downloads" ).getAsJsonObject(
                        "artifact" ).get( "sha1" ).getAsString();
                    url = libManifestLibObj.getAsJsonObject( "downloads" ).getAsJsonObject(
                        "artifact" ).get( "url" ).getAsString();
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
                        if ( ruleObj.has( "action" ) && ruleObj.get( "action" ).getAsString()
                                                               .toLowerCase().equals( "allow" )
                            && !ruleObj.has( "os" ) ) {
                            // Allow all OS
                            rulesOS.add( MCForgeModpackConsts.PLATFORM_WINDOWS );
                            rulesOS.add( MCForgeModpackConsts.PLATFORM_MACOS );
                            rulesOS.add( MCForgeModpackConsts.PLATFORM_UNIX );
                        }
                        // Check for operating system specific allow rule
                        else if ( ruleObj.has( "action" ) && ruleObj.get( "action" ).getAsString()
                                                                    .toLowerCase().equals( "allow" )
                            && ruleObj.has( "os" ) ) {
                            rulesOS.add( ruleObj.getAsJsonObject( "os" ).get( "name" )
                                                .getAsString() );
                        }
                        // Check for operating system specific disallow rule
                        else if ( ruleObj.has( "action" ) && ruleObj.get( "action" ).getAsString()
                                                                    .toLowerCase().equals(
                                "disallow" ) && ruleObj.has( "os" ) ) {
                            rulesOS.remove( ruleObj.getAsJsonObject( "os" ).get( "name" )
                                                   .getAsString() );
                        }
                        // Handle unidentified rule type
                        else {
                            System.err.println( "MISS AT RULES" );
                        }
                    }
                }

                // Check for and process library classifiers
                // Note: classifiers are additional libaries or
                // sub-libraries associated with the parent library
                // Note: logging classifiers are ignored.
                // Please report to github.com/hawka97 if this causes issues.
                if ( libManifestLibObj.getAsJsonObject( "downloads" ).has( "classifiers" ) ) {
                    // Create new library object to temporarily hold classifiers
                    MCLibrary nativeLib;

                    // Check for and process windows native libraries
                    if ( FLSystemUtils.isWindows() && libManifestLibObj.getAsJsonObject(
                        "downloads" ).getAsJsonObject( "classifiers" ).has( "natives-windows" ) ) {
                        // Create list to store applicable OSes for native
                        ArrayList< String > nativeValidOS = new ArrayList<>();

                        // Add Windows as an applicable OS if library supports Windows
                        if ( rulesOS.contains( "windows" ) ) {
                            nativeValidOS.add( "windows" );
                        }

                        // Initialize new native library object
                        String localLibPath = libManifestLibObj.getAsJsonObject( "downloads" )
                                                               .getAsJsonObject( "classifiers" )
                                                               .getAsJsonObject( "natives-windows" )
                                                               .get( "path" ).getAsString();
                        nativeLib = new MCLibrary( libManifestLibObj.getAsJsonObject( "downloads" )
                                                                    .getAsJsonObject(
                                                                        "classifiers" )
                                                                    .getAsJsonObject(
                                                                        "natives-windows" )
                                                                    .get( "url" ).getAsString(),
                                                   localLibPath, libManifestLibObj.getAsJsonObject(
                            "downloads" ).getAsJsonObject( "classifiers" ).getAsJsonObject(
                            "natives-windows" ).get( "sha1" ).getAsString(), true, nativeValidOS,
                                                   true );

                        // Add native library to applicable libraries list
                        libraries.add( nativeLib );
                    }

                    // Check for and process macOS native libraries
                    if ( FLSystemUtils.isMac() && libManifestLibObj.getAsJsonObject(
                        "downloads" ).getAsJsonObject( "classifiers" ).has( "natives-osx" ) ) {
                        // Create list to store applicable OSes for native
                        ArrayList< String > nativeValidOS = new ArrayList<>();

                        // Add macOS as an applicable OS if library supports macOS
                        if ( rulesOS.contains( "osx" ) ) {
                            nativeValidOS.add( "osx" );
                        }

                        // Initialize new native library object
                        String localLibPath = libManifestLibObj.getAsJsonObject( "downloads" )
                                                               .getAsJsonObject( "classifiers" )
                                                               .getAsJsonObject( "natives-osx" )
                                                               .get( "path" ).getAsString();
                        nativeLib = new MCLibrary( libManifestLibObj.getAsJsonObject( "downloads" )
                                                                    .getAsJsonObject(
                                                                        "classifiers" )
                                                                    .getAsJsonObject(
                                                                        "natives-osx" ).get( "url" )
                                                                    .getAsString(), localLibPath,
                                                   libManifestLibObj.getAsJsonObject( "downloads" )
                                                                    .getAsJsonObject(
                                                                        "classifiers" )
                                                                    .getAsJsonObject(
                                                                        "natives-osx" )
                                                                    .get( "sha1" ).getAsString(),
                                                   true, nativeValidOS, true );

                        // Add native library to applicable libraries list
                        libraries.add( nativeLib );
                    }

                    // Check for and process Linux native libraries if on Linux
                    if ( FLSystemUtils.isUnix() && libManifestLibObj.getAsJsonObject(
                        "downloads" ).getAsJsonObject( "classifiers" ).has( "natives-linux" ) ) {
                        // Create list to store applicable OSes for native
                        ArrayList< String > nativeValidOS = new ArrayList<>();

                        // Add Linux as an applicable OS if library supports Linux
                        if ( rulesOS.contains( "linux" ) ) {
                            nativeValidOS.add( "linux" );
                        }

                        // Initialize new native library object
                        String localLibPath = libManifestLibObj.getAsJsonObject( "downloads" )
                                                               .getAsJsonObject( "classifiers" )
                                                               .getAsJsonObject( "natives-linux" )
                                                               .get( "path" ).getAsString();
                        nativeLib = new MCLibrary( libManifestLibObj.getAsJsonObject( "downloads" )
                                                                    .getAsJsonObject(
                                                                        "classifiers" )
                                                                    .getAsJsonObject(
                                                                        "natives-linux" )
                                                                    .get( "url" ).getAsString(),
                                                   localLibPath, libManifestLibObj.getAsJsonObject(
                            "downloads" ).getAsJsonObject( "classifiers" ).getAsJsonObject(
                            "natives-linux" ).get( "sha1" ).getAsString(), true, nativeValidOS,
                                                   true );

                        // Add native library to applicable libraries list
                        libraries.add( nativeLib );
                    }
                }

                // Create final library object and add to applicable list
                if ( path != null && url != null && sha1 != null ) {
                    MCLibrary thisLib = new MCLibrary( url, path.toString(), sha1, useStrictRules,
                                                       rulesOS, false );

                    if ( ( FLSystemUtils.isWindows() && thisLib.getApplicableOSes().contains(
                        MCForgeModpackConsts.PLATFORM_WINDOWS ) ) || ( FLSystemUtils.isMac()
                        && thisLib.getApplicableOSes().contains(
                        MCForgeModpackConsts.PLATFORM_MACOS ) ) || ( FLSystemUtils.isUnix()
                        && thisLib.getApplicableOSes().contains(
                        MCForgeModpackConsts.PLATFORM_UNIX ) ) ) {

                        libraries.add( thisLib );
                    }
                }
            }
            // Output failure to decode library JSON
            else {
                System.err.println( "SKIPPED A LIBRARY - UNKNOWN TYPE" );
                System.err.println( libManifestLibObj.toString() );
            }
        }
        return libraries;
    }

    /**
     * Verify each library's local copy and download/update if necessary
     *
     * @param progressProvider
     *
     * @throws FLModpackException if update or download fails
     * @since 1.0
     */
    private void downloadVerifyLibraries( MCForgeModpackProgressProvider progressProvider )
        throws FLModpackException {
        // Build full path to libraries and natives folders
        String localLibPath = modpackRootFolder + FLSystemUtils.getFileSeparator()
            + MCForgeModpackConsts.MODPACK_FORGE_LIBS_LOCAL_FOLDER;
        String localNativePath = modpackRootFolder + FLSystemUtils.getFileSeparator()
            + MCForgeModpackConsts.MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER;

        // Get list of libraries
        ArrayList< MCLibrary > libraries = getLibraries();

        // For each library, verify local file and download as necessary
        for ( MCLibrary lib : libraries ) {
            lib.setLocalPathPrefix( localLibPath );
            boolean didChange = lib.updateLocalFile();
            if ( lib.isNativeLib() && didChange ) {
                try {
                    FLSystemUtils.extractJarFile( new JarFile( lib.getFullLocalFilePath() ),
                                                  localNativePath );
                }
                catch ( IOException e ) {
                    throw new FLModpackException( "Unable to extract native library.", e );
                }
            }

            // Update progress provider if present
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified library " + lib.getFileName(),
                                                 ( 25.0 / ( double ) libraries.size() ) );
            }
        }
    }

    /**
     * Verify and download all applicable libraries from this manifest and return a classpath String
     * with reference to each library.
     *
     * @return Minecraft libraries classpath
     *
     * @throws FLModpackException if update or download fails
     */
    String buildMinecraftClasspath( int appGameMode,
                                    MCForgeModpackProgressProvider progressProvider )
        throws FLModpackException {
        // Get list of libraries
        ArrayList< MCLibrary > minecraftLibsList = getLibraries();
        if ( progressProvider != null ) {
            progressProvider.submitProgress( "Got Minecraft library list", 5 );
        }

        // Download the libraries
        downloadVerifyLibraries( progressProvider );

        // Download the Minecraft jar
        downloadMinecraftApp( appGameMode, progressProvider );

        // Download Minecraft assets
        downloadMinecraftAssets( progressProvider );

        // For each asset, add to classpath
        StringBuilder classpath = new StringBuilder();
        for ( MCLibrary library : minecraftLibsList ) {
            // Add separator to string if necessary
            if ( ( classpath.length() > 0 ) && !classpath.toString().endsWith(
                    FLSystemUtils.getClasspathSeparator() ) ) {
                classpath.append( FLSystemUtils.getClasspathSeparator() );
            }

            // Add library to classpath
            String localLibPath = modpackRootFolder + FLSystemUtils.getFileSeparator()
                + MCForgeModpackConsts.MODPACK_FORGE_LIBS_LOCAL_FOLDER;
            library.setLocalPathPrefix( localLibPath );
            classpath.append( library.getFullLocalFilePath() );

            // Update progress provider if present
            if ( progressProvider != null ) {
                progressProvider.submitProgress(
                    "Added to classpath: " + library.getLocalFilePath(),
                    ( 10.0 / ( double ) minecraftLibsList.size() ) );
            }
        }

        // Add separator to string if necessary
        if ( ( classpath.length() > 0 ) && !classpath.toString().endsWith(
                FLSystemUtils.getClasspathSeparator() ) ) {
            classpath.append( FLSystemUtils.getClasspathSeparator() );
        }

        // Add Minecraft jar to classpath
        classpath.append( getMinecraftApp( appGameMode ).getFullLocalFilePath() );

        return classpath.toString();
    }

    void downloadMinecraftAssets( final MCForgeModpackProgressProvider progressProvider )
        throws FLModpackException {
        getAssetManifest().downloadAssets(progressProvider);
    }

    /**
     * Get the asset index version for Minecraft
     *
     * @return asset index version
     *
     * @throws FLModpackException if unable to read manifest
     */
    String getAssetIndexVersion() throws FLModpackException {
        return readToJsonObject().get( "assetIndex" ).getAsJsonObject().get( "id" ).getAsString();
    }

    MCAssetManifest getAssetManifest() throws FLModpackException {
        String remote = readToJsonObject().get( "assetIndex" ).getAsJsonObject().get( "url" )
                                          .getAsString();
        return new MCAssetManifest( remote, modpackRootFolder, getAssetIndexVersion() );
    }

    /**
     * Get the Minecraft game app information using the specified gameAppMode selection of
     * client/server and modpack root folder.
     *
     * @param gameAppMode client/server selection
     *
     * @return Minecraft game app as remote file object
     *
     * @throws FLModpackException if unable to get Minecraft app information
     * @since 1.0
     */
    private MCRemoteFile getMinecraftApp( int gameAppMode ) throws FLModpackException {
        // Get download information for client or server Minecraft app
        JsonObject appObj;
        if ( gameAppMode == MCForgeModpackConsts.MINECRAFT_CLIENT_MODE ) {
            appObj = readToJsonObject().getAsJsonObject( "downloads" ).getAsJsonObject( "client" );
        }
        else if ( gameAppMode == MCForgeModpackConsts.MINECRAFT_SERVER_MODE ) {
            appObj = readToJsonObject().getAsJsonObject( "downloads" ).getAsJsonObject( "server" );
        }
        else {
            throw new FLModpackException( "An invalid game app mode was specified." );
        }

        // Build RemoteFile with download information and return
        String localMinecraftAppPath = modpackRootFolder + FLSystemUtils.getFileSeparator()
            + MCForgeModpackConsts.MODPACK_MINECRAFT_JAR_LOCAL_PATH;
        return new MCRemoteFile( appObj.get( "url" ).getAsString(), localMinecraftAppPath,
                                 appObj.get( "sha1" ).getAsString() );
    }

    /**
     * Download the Minecraft game app to the modpack at the specified root folder. Fetches
     * client/server based on supplied gameAppMode value.
     *
     * @param gameAppMode      client/server selection
     * @param progressProvider
     *
     * @throws FLModpackException if download fails
     * @since 1.0
     */
    void downloadMinecraftApp( int gameAppMode, MCForgeModpackProgressProvider progressProvider )
        throws FLModpackException {
        // Get access to Minecraft app as remote file
        MCRemoteFile mcAppRemoteFile = getMinecraftApp( gameAppMode );

        // Verify Minecraft app and update or download as necessary
        mcAppRemoteFile.updateLocalFile();

        // Update progress provider if present
        if ( progressProvider != null ) {
            progressProvider.submitProgress( "Verified Minecraft application", 10 );
        }
    }
}

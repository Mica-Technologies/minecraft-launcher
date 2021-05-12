/*
 * Copyright (c) 2020 Mica Technologies
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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ForgeConstants;
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Class representation of a modpack Forge jar application
 *
 * @author Mica Technologies
 * @version 1.0.1
 * @editors hawka97
 * @creator hawka97
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
                                                         ModPackConstants.MODPACK_FORGE_JAR_LOCAL_PATH ), sha1Hash );

        // Store parent mod pack
        this.parentModPack = parentModPack;

        // Download Forge app
        updateLocalFile();

        // Store Forge/MC information
        JsonObject forgeVersionManifest = getForgeVersionManifest();
        forgeVersion = forgeVersionManifest.get( ForgeConstants.FORGE_VERSION_MANIFEST_ID_KEY ).getAsString();
        minecraftVersion = forgeVersionManifest.get( ForgeConstants.FORGE_VERSION_MANIFEST_INHERITS_FROM_KEY )
                                               .getAsString();
        minecraftArguments = forgeVersionManifest.get( ForgeConstants.FORGE_VERSION_MANIFEST_MINECRAFT_ARGS_KEY )
                                                 .getAsString();
        minecraftMainClass = forgeVersionManifest.get( ForgeConstants.FORGE_VERSION_MANIFEST_MAIN_CLASS_KEY )
                                                 .getAsString();
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
            String forgeAssetName = forgeAssetObj.get( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_NAME_KEY )
                                                 .getAsString();

            // Get Asset Downloads Information
            JsonObject forgeAssetDownloadsObj = null;
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
            else {
                // Get Repo URL
                String repoURL = "https://repo1.maven.org/maven2/";
                if ( forgeAssetName.contains( "net.minecraft:" ) ) {
                    repoURL = "https://libraries.minecraft.net/";
                }
                else if ( forgeAssetName.contains( "net.minecraftforge:forge:" ) ) {
                    repoURL = "https://maven.minecraftforge.net/";
                    if ( !isSpecifiedRepoPath ) {
                        forgeAssetRepoPath += "-universal";
                    }
                    else if ( !forgeAssetRepoPath.contains( "-universal" ) ) {
                        String originalForgeAssetRepoPath = forgeAssetRepoPath;
                        forgeAssetRepoPath = originalForgeAssetRepoPath.substring( 0,
                                                                                   originalForgeAssetRepoPath.lastIndexOf(
                                                                                           ".jar" ) ) +
                                "-universal.jar";
                    }
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

                // Override special libraries
                if ( forgeAssetURL.contains( "scala-parser-combinators" ) ) {
                    forgeAssetURL
                            = "https://repo1.maven.org/maven2/org/scala-lang/scala-parser-combinators/2.11.0-M4/scala-parser-combinators-2.11.0-M4.jar";
                }
                if ( forgeAssetURL.contains( "scala-swing" ) ) {
                    forgeAssetURL
                            = "https://repo1.maven.org/maven2/org/scala-lang/scala-swing/2.11.0-M7/scala-swing-2.11.0-M7.jar";
                }
                if ( forgeAssetURL.contains( "scala-xml" ) ) {
                    forgeAssetURL
                            = "https://repo1.maven.org/maven2/org/scala-lang/scala-xml/2.11.0-M4/scala-xml-2.11.0-M4.jar";
                }
                if ( forgeAssetURL.contains( "lzma/lzma" ) ) {
                    forgeAssetURL = "https://repo.spongepowered.org/maven/lzma/lzma/0.0.1/lzma-0.0.1.jar";
                }
                if ( forgeAssetURL.contains( "vecmath" ) ) {
                    forgeAssetURL = "https://repo1.maven.org/maven2/javax/vecmath/vecmath/1.5.2/vecmath-1.5.2.jar";
                }
            }

            // Build Local File Path
            String localForgeAssetFilePath = forgeAssetName.substring( 0, forgeAssetName.indexOf( ":" ) )
                                                           .replace( ".", File.separator ) +
                    File.separator +
                    inferredForgeAssetRepoPath +
                    LocalPathConstants.JAR_FILE_EXTENSION;

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
            // Note: hash checking not supported on forge assets yet
            forgeAssets.add( new GameAsset( forgeAssetURL, localForgeAssetFilePath, clientReq, serverReq ) );
        }

        // Return resulting list of Forge Assets
        return forgeAssets;
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
        for ( GameAsset forgeAsset : forgeAssetsList ) {
            // Add separator to string if necessary
            if ( ( classpath.length() > 0 ) && !classpath.toString().endsWith( File.pathSeparator ) ) {
                classpath.append( File.pathSeparator );
            }

            // Add asset to classpath
            String localPathPrefix = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                                    ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );
            forgeAsset.setLocalPathPrefix( localPathPrefix );
            classpath.append( forgeAsset.getFullLocalFilePath() );

            // Update progress provider if present
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Added to classpath: " + forgeAsset.getLocalFilePath(),
                                                 ( 20.0 / ( double ) forgeAssetsList.size() ) );
            }
        }

        return classpath.toString();
    }

    private JsonObject getForgeVersionManifest() throws ModpackException {
        // Get access to Forge jar
        JarFile forgeJarFile = getForgeJarFile();

        // Loop through each element in the jar
        Enumeration< JarEntry > enumeration = forgeJarFile.entries();
        while ( enumeration.hasMoreElements() ) {
            // Check if element is version.json
            JarEntry jarEntry = enumeration.nextElement();
            if ( jarEntry.getName().equals( ForgeConstants.FORGE_JAR_VERSION_FILE_NAME ) ) {
                // Read version.json via input stream
                InputStream inputStream;
                try {
                    inputStream = forgeJarFile.getInputStream( jarEntry );
                }
                catch ( IOException e ) {
                    throw new ModpackException( LocalizationManager.UNABLE_OPEN_FORGE_VERSION_MANIFEST_PARSING_TEXT,
                                                e );
                }
                InputStreamReader inputStreamReader = new InputStreamReader( inputStream );
                JsonObject jsonObject = new Gson().fromJson( inputStreamReader, JsonObject.class );

                // Close streams and return object
                try {
                    inputStream.close();
                    inputStreamReader.close();
                    forgeJarFile.close();
                }
                catch ( IOException e ) {
                    Logger.logError( LocalizationManager.UNABLE_CLOSE_STREAMS_TEXT );
                }
                return jsonObject;
            }
        }

        // Throw exception if not able to locate version.json
        throw new ModpackException( LocalizationManager.UNABLE_FIND_FORGE_VERSION_FILE_TEXT );
    }
}

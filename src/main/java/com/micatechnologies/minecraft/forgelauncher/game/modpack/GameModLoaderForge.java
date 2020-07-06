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

package com.micatechnologies.minecraft.forgelauncher.game.modpack;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.forgelauncher.consts.ModPackConstants;
import com.micatechnologies.minecraft.forgelauncher.exceptions.ModpackException;
import com.micatechnologies.minecraft.forgelauncher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.GameMode;

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
 * @version 1.0
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

    GameModLoaderForge( String remoteURL, String sha1Hash, GameModPack parentModPack )
    throws ModpackException
    {
        // Populate remote file information/configuration

        super( remoteURL,
               SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                              ModPackConstants.MODPACK_FORGE_JAR_LOCAL_PATH ),
               sha1Hash );

        // Store modpack root folder
        this.parentModPack = parentModPack;

        // Download Forge app
        updateLocalFile();

        // Store Forge/MC information
        JsonObject forgeVersionManifest = getForgeVersionManifest();
        forgeVersion = forgeVersionManifest.get( "id" ).getAsString();
        minecraftVersion = forgeVersionManifest.get( "inheritsFrom" ).getAsString();
        minecraftArguments = forgeVersionManifest.get( "minecraftArguments" ).getAsString();
        minecraftMainClass = forgeVersionManifest.get( "mainClass" ).getAsString();
    }

    String getForgeVersion() {
        return forgeVersion;
    }

    String getMinecraftVersion() {
        return minecraftVersion;
    }

    String getMinecraftArguments() {
        return minecraftArguments;
    }

    String getMinecraftMainClass() {
        return minecraftMainClass;
    }

    private JarFile getForgeJarFile() throws ModpackException {
        try {
            return new JarFile( getFullLocalFilePath() );
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to access Forge jar file.", e );
        }
    }

    private ArrayList< GameAsset > getForgeAssetList() throws ModpackException {
        // Get Forge Version Manifest Information
        JsonObject forgeVersionManifest = getForgeVersionManifest();
        JsonArray forgeAssetsArray = forgeVersionManifest.getAsJsonArray( "libraries" );

        // Create list for storing processed libraries
        ArrayList< GameAsset > forgeAssets = new ArrayList<>();

        // Loop Through Each Asset and Process
        for ( JsonElement forgeAsset : forgeAssetsArray ) {
            // Get Asset Object and Information
            JsonObject forgeAssetObj = forgeAsset.getAsJsonObject();
            String forgeAssetName = forgeAssetObj.get( "name" ).getAsString();

            // Build Repo Path from URL
            String forgeAssetRepoPath = forgeAssetName.substring(
                    forgeAssetName.indexOf( ":" ) + 1 ).replace( ":", "-" );

            // Get Repo URL
            String repoURL = "https://repo1.maven.org/maven2/";
            if ( forgeAssetName.contains( "net.minecraft:" ) ) {
                repoURL = "https://libraries.minecraft.net/";
            }
            else if ( forgeAssetName.contains( "net.minecraftforge:" ) ) {
                repoURL = "https://files.minecraftforge.net/maven/";
                forgeAssetRepoPath += "-universal";
            }

            // Build Full Repo URL and Path
            String forgeAssetURL = repoURL + forgeAssetName.substring( 0, forgeAssetName
                    .indexOf( ":" ) ).replace( ".", "/" ) + "/" + forgeAssetName.substring(
                    forgeAssetName.indexOf( ":" ) + 1 ).replace( ":", "/" ) + "/" + forgeAssetRepoPath
                    + ".jar";

            // Override special libraries
            if ( forgeAssetURL.contains( "scala-parser-combinators" ) ) {
                forgeAssetURL =
                        "https://repo1.maven.org/maven2/org/scala-lang/scala-parser-combinators/2.11.0-M4/scala-parser-combinators-2.11.0-M4.jar";
            }
            if ( forgeAssetURL.contains( "scala-swing" ) ) {
                forgeAssetURL =
                        "https://repo1.maven.org/maven2/org/scala-lang/scala-swing/2.11.0-M7/scala-swing-2.11.0-M7.jar";
            }
            if ( forgeAssetURL.contains( "scala-xml" ) ) {
                forgeAssetURL =
                        "https://repo1.maven.org/maven2/org/scala-lang/scala-xml/2.11.0-M4/scala-xml-2.11.0-M4.jar";
            }
            if ( forgeAssetURL.contains( "lzma/lzma" ) ) {
                forgeAssetURL = "https://repo.spongepowered.org/maven/lzma/lzma/0.0.1/lzma-0.0.1.jar";
            }
            if ( forgeAssetURL.contains( "vecmath" ) ) {
                forgeAssetURL = "https://repo1.maven.org/maven2/javax/vecmath/vecmath/1.5.2/vecmath-1.5.2.jar";
            }

            // Build Local File Path
            String localForgeAssetFilePath = forgeAssetName.substring( 0, forgeAssetName
                    .indexOf( ":" ) ).replace( ".", File.separator )
                    + File.separator + forgeAssetRepoPath + ".jar";

            // Get Forge Asset Requirements
            boolean clientReq = true;
            if ( forgeAssetObj.has( "clientreq" ) ) {
                clientReq = forgeAssetObj.get( "clientreq" ).getAsBoolean();
            }
            boolean serverReq = true;
            if ( forgeAssetObj.has( "serverreq" ) ) {
                serverReq = forgeAssetObj.get( "serverreq" ).getAsBoolean();
            }

            // Build Forge Asset Object and Add to List of Assets
            // Note: hash checking not supported on forge assets yet
            forgeAssets.add(
                    new GameAsset( forgeAssetURL, localForgeAssetFilePath, clientReq, serverReq ) );
        }

        // Return resulting list of Forge Assets
        return forgeAssets;
    }

    private void downloadForgeAssets( GameMode gameAppMode,
                                      GameModPackProgressProvider progressProvider )
    throws ModpackException
    {
        // Get list of Forge Assets
        ArrayList< GameAsset > forgeAssetsList = getForgeAssetList();

        // For each asset, verify and download as necessary
        for ( GameAsset forgeAsset : forgeAssetsList ) {
            String localPathPrefix = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                                    ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );
            forgeAsset.setLocalPathPrefix( localPathPrefix );
            forgeAsset.updateLocalFile( gameAppMode );

            // Update progress provider if present
            if ( progressProvider != null ) {
                progressProvider
                        .submitProgress( "Verified asset " + SynchronizedFileManager
                                                 .getSynchronizedFile( forgeAsset.getFullLocalFilePath() ).getName(),
                                         ( 60.0 / ( double ) forgeAssetsList.size() ) );
            }
        }
    }

    String buildForgeClasspath( GameMode gameAppMode, GameModPackProgressProvider progressProvider )
    throws ModpackException
    {
        // Get list of Forge Assets
        ArrayList< GameAsset > forgeAssetsList = getForgeAssetList();
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
            if ( ( classpath.length() > 0 ) && !classpath.toString().endsWith(
                    File.pathSeparator ) ) {
                classpath.append( File.pathSeparator );
            }

            // Add asset to classpath
            String localPathPrefix = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                                    ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );
            forgeAsset.setLocalPathPrefix( localPathPrefix );
            classpath.append( forgeAsset.getFullLocalFilePath() );

            // Update progress provider if present
            if ( progressProvider != null ) {
                progressProvider.submitProgress(
                        "Added to classpath: " + forgeAsset.getLocalFilePath(),
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
            if ( jarEntry.getName().equals( "version.json" ) ) {
                // Read version.json via input stream
                InputStream inputStream;
                try {
                    inputStream = forgeJarFile.getInputStream( jarEntry );
                }
                catch ( IOException e ) {
                    throw new ModpackException(
                            "Unable to open Forge version manifest for parsing.", e );
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
                    System.err.println( "unable to close streams" );
                }
                return jsonObject;
            }
        }

        // Throw exception if not able to locate version.json
        throw new ModpackException( "Unable to find Forge version file." );
    }
}

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
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.RuntimeConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class representing the Mojang Minecraft version manifest (v2) and providing functionality to resolve version-specific
 * client JSON data, library manifests, and Java version requirements.
 *
 * @author Mica Technologies
 * @version 3.0
 */
public class GameVersionManifest
{
    /**
     * The JSON object containing the contents of the Minecraft version manifest after being downloaded.
     *
     * @since 2.0
     */
    private static volatile JsonObject versionManifest = null;

    /**
     * Cache of downloaded and parsed client.json objects keyed by Minecraft version ID.
     *
     * @since 3.0
     */
    private static final Map< String, JsonObject > clientJsonCache = new ConcurrentHashMap<>();

    /**
     * Downloads the contents of the Minecraft version manifest (v2) and stores it.
     *
     * @throws IOException if unable to download to file or read file
     *
     * @since 2.0
     */
    private static void download() throws IOException {
        File versionManifestFile =
                SynchronizedFileManager.getSynchronizedFile( LocalPathManager.getMinecraftVersionManifestFilePath() );
        NetworkUtilities.downloadFileFromURL( ModPackConstants.MINECRAFT_VERSION_MANIFEST_URL, versionManifestFile );
        versionManifest = FileUtilities.readAsJsonObject( versionManifestFile );
    }

    /**
     * Ensures the version manifest is downloaded.
     *
     * @throws ModpackException if unable to download
     *
     * @since 3.0
     */
    /**
     * Ensures the Mojang version manifest has been downloaded and cached. This method is safe to call multiple times;
     * the download only occurs on the first call.
     *
     * @throws ModpackException if unable to download the manifest
     *
     * @since 3.0
     */
    public static void ensureManifestDownloaded() throws ModpackException {
        // Double-checked locking over the volatile field: concurrent launch
        // threads (LAUNCH_IO_POOL workers, background revalidation) could
        // otherwise both download and one clobber the other's result.
        if ( versionManifest == null ) {
            synchronized ( GameVersionManifest.class ) {
                if ( versionManifest == null ) {
                    try {
                        Logger.logDebug( LocalizationManager.get( "log.versionManifest.notDownloadedGettingNow" ) );
                        download();
                        Logger.logDebug( LocalizationManager.get( "log.versionManifest.downloaded" ) );
                    }
                    catch ( IOException e ) {
                        Logger.logError( LocalizationManager.get( "log.versionManifest.downloadReadFailed" ) );
                        Logger.logThrowable( e );
                        throw new ModpackException( "Unable to download Minecraft version manifest.", e );
                    }
                }
            }
        }
    }

    /**
     * Get the URL of the Minecraft client JSON (library manifest) for the specified Minecraft version.
     *
     * @param minecraftVersion minecraft version
     *
     * @return URL of Minecraft version's client JSON
     *
     * @throws ModpackException if unable to get URL
     *
     * @since 1.0
     */
    private static String getMinecraftLibraryManifestURL( String minecraftVersion )
    throws ModpackException
    {
        ensureManifestDownloaded();

        JsonArray minecraftVersions = versionManifest.getAsJsonArray( "versions" );
        for ( JsonElement version : minecraftVersions ) {
            if ( version.getAsJsonObject().get( "id" ).getAsString().equals( minecraftVersion ) ) {
                return version.getAsJsonObject().get( "url" ).getAsString();
            }
        }

        throw new ModpackException( "Unable to find specified Minecraft version: " + minecraftVersion );
    }

    /**
     * Downloads and caches the client.json for the specified Minecraft version, then returns it.
     *
     * @param minecraftVersion the Minecraft version
     *
     * @return the parsed client.json as a JsonObject
     *
     * @throws ModpackException if unable to download or parse
     *
     * @since 3.0
     */
    public static JsonObject getClientJson( String minecraftVersion ) throws ModpackException {
        // computeIfAbsent guarantees the download/parse happens once per version
        // even under concurrent launches; checked exceptions from the mapping
        // function are tunnelled through an unchecked wrapper and unwrapped below.
        try {
            return clientJsonCache.computeIfAbsent( minecraftVersion, version -> {
                try {
                    String url = getMinecraftLibraryManifestURL( version );
                    String localPath = LocalPathManager.getLauncherMetadataFolderPath() + File.separator +
                            "client-" + version + ".json";
                    File localFile = SynchronizedFileManager.getSynchronizedFile( localPath );
                    NetworkUtilities.downloadFileFromURL( url, localFile );
                    return FileUtilities.readAsJsonObject( localFile );
                }
                catch ( ModpackException | IOException e ) {
                    throw new ClientJsonFetchException( e );
                }
            } );
        }
        catch ( ClientJsonFetchException e ) {
            if ( e.getCause() instanceof ModpackException modpackException ) {
                throw modpackException;
            }
            throw new ModpackException( "Unable to download client.json for Minecraft " + minecraftVersion,
                                        e.getCause() );
        }
    }

    /**
     * Unchecked carrier used to tunnel checked exceptions out of the
     * {@link #getClientJson(String)} {@code computeIfAbsent} mapping function.
     *
     * @since 3.0
     */
    private static final class ClientJsonFetchException extends RuntimeException
    {
        private ClientJsonFetchException( Throwable cause ) {
            super( cause );
        }
    }

    /**
     * Gets the required Java major version for the specified Minecraft version. Reads the {@code javaVersion.majorVersion}
     * field from the client.json. Returns {@link RuntimeConstants#DEFAULT_JAVA_MAJOR_VERSION} if the field is absent
     * (very old Minecraft versions).
     *
     * @param minecraftVersion the Minecraft version
     *
     * @return the required Java major version (e.g. 8, 17, 21, 25)
     *
     * @throws ModpackException if unable to fetch version info
     *
     * @since 3.0
     */
    public static int getRequiredJavaMajorVersion( String minecraftVersion ) throws ModpackException {
        JsonObject clientJson = getClientJson( minecraftVersion );
        if ( clientJson.has( "javaVersion" ) ) {
            JsonObject javaVersion = clientJson.getAsJsonObject( "javaVersion" );
            if ( javaVersion.has( "majorVersion" ) ) {
                return javaVersion.get( "majorVersion" ).getAsInt();
            }
        }
        return RuntimeConstants.DEFAULT_JAVA_MAJOR_VERSION;
    }

    /**
     * Gets the required Mojang runtime component name for the specified Minecraft version. Reads the
     * {@code javaVersion.component} field from the client.json. Returns {@link RuntimeConstants#DEFAULT_RUNTIME_COMPONENT}
     * if the field is absent.
     *
     * @param minecraftVersion the Minecraft version
     *
     * @return the runtime component name (e.g. "jre-legacy", "java-runtime-gamma")
     *
     * @throws ModpackException if unable to fetch version info
     *
     * @since 3.0
     */
    public static String getRequiredRuntimeComponent( String minecraftVersion ) throws ModpackException {
        JsonObject clientJson = getClientJson( minecraftVersion );
        if ( clientJson.has( "javaVersion" ) ) {
            JsonObject javaVersion = clientJson.getAsJsonObject( "javaVersion" );
            if ( javaVersion.has( "component" ) ) {
                return javaVersion.get( "component" ).getAsString();
            }
        }
        return RuntimeConstants.DEFAULT_RUNTIME_COMPONENT;
    }

    /**
     * Get the Minecraft library manifest for the specified Minecraft version.
     *
     * @param minecraftVersion Minecraft version
     * @param parent           parent mod pack
     *
     * @return Minecraft version's library manifest
     *
     * @throws ModpackException if unable to get library manifest
     *
     * @since 1.0
     */
    public static GameLibraryManifest getMinecraftLibraryManifest( String minecraftVersion, GameModPack parent )
    throws ModpackException
    {
        return new GameLibraryManifest( getMinecraftLibraryManifestURL( minecraftVersion ), parent );
    }
}

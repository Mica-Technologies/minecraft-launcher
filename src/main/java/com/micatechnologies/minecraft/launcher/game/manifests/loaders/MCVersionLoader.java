/*
 * Copyright (c) 2022 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.game.manifests.loaders;

import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.ManagedRemoteFile;
import com.micatechnologies.minecraft.launcher.files.hash.FileChecksum;
import com.micatechnologies.minecraft.launcher.files.hash.FileChecksumSHA1;
import com.micatechnologies.minecraft.launcher.game.manifests.MCVersionManifest;

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for loading the downloading information of the applicable Minecraft version for a specified {@link
 * MCVersionManifest}.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class MCVersionLoader
{
    /**
     * The relative (to the installation folder location) file path of the Minecraft Jar file.
     *
     * @since 1.0
     */
    public static final String MINECRAFT_RELATIVE_FILE_PATH = "bin" + File.separator + "minecraft.jar";

    /**
     * The relative (to the installation folder location) file path of the Minecraft mappings Jar file.
     *
     * @since 1.0
     */
    public static final String MINECRAFT_MAPPINGS_RELATIVE_FILE_PATH = "bin" +
            File.separator +
            "minecraft-mappings.jar";

    /**
     * Builds a list of {@link ManagedRemoteFile} objects for the Minecraft client of the specified {@link
     * MCVersionManifest} in the specified installation folder location.
     *
     * @param installFolder   installation folder location
     * @param versionManifest the {@link MCVersionManifest} to download client {@link ManagedRemoteFile} information
     *                        for
     *
     * @return a list of {@link ManagedRemoteFile} objects for the specified {@link MCVersionManifest} in the specified
     *         installation folder location
     *
     * @throws MalformedURLException if a remote download URL in Minecraft client file download information does not
     *                               have a properly formed URL and/or its URL is missing and could not be interpreted
     * @since 1.0
     */
    public List< ManagedRemoteFile > getMinecraftClientFiles( String installFolder, MCVersionManifest versionManifest )
    throws MalformedURLException
    {
        // Create list for storing created managed remote file objects
        List< ManagedRemoteFile > managedRemoteFiles = new ArrayList<>();

        // Ensure that downloads object is not null
        MCVersionManifest.Downloads downloads = versionManifest.getDownloads();
        if ( downloads != null ) {

            // Get client download information objects
            MCVersionManifest.Downloads.Download downloadsClient = downloads.getClient();
            MCVersionManifest.Downloads.Download downloadsClient_mappings = downloads.getClient_mappings();

            // Create and add managed file for client (if client download info not null)
            if ( downloadsClient != null ) {
                ManagedRemoteFile clientManagedRemoteFile = createManagedRemoteFileFromDownload( installFolder,
                                                                                                 MINECRAFT_RELATIVE_FILE_PATH,
                                                                                                 downloadsClient );
                managedRemoteFiles.add( clientManagedRemoteFile );

                // Create and add managed file for client mappings (if client mappings download info not null)
                if ( downloadsClient_mappings != null ) {
                    ManagedRemoteFile clientMappingsManagedRemoteFile = createManagedRemoteFileFromDownload(
                            installFolder, MINECRAFT_MAPPINGS_RELATIVE_FILE_PATH, downloadsClient_mappings );
                    managedRemoteFiles.add( clientMappingsManagedRemoteFile );
                }
            }
        }
        else {
            // Output to log that the downloads object was null (this shouldn't really happen)
            Logger.logDebug(
                    "No Minecraft downloads could be located for the version [" + versionManifest.getId() + "]" );
        }

        return managedRemoteFiles;
    }

    /**
     * Creates a {@link ManagedRemoteFile} object for the specified {@link MCVersionManifest.Downloads.Download} object
     * in the specified <code>installFolder</code> with the specified <code>filePath</code>.
     *
     * @param installFolder installation folder location
     * @param filePath      file path (relative to installation folder location)
     * @param download      version manifest object containing remote file download information
     *
     * @return {@link ManagedRemoteFile} object for the specified {@link MCVersionManifest.Downloads.Download} object in
     *         the specified <code>installFolder</code> with the specified <code>filePath</code>
     *
     * @throws MalformedURLException if a remote download URL in the specified {@link MCVersionManifest.Downloads.Download}
     *                               object does not have a properly formed URL and/or its URL is missing and could not
     *                               be interpreted
     * @since 1.0
     */
    private ManagedRemoteFile createManagedRemoteFileFromDownload( String installFolder,
                                                                   String filePath,
                                                                   MCVersionManifest.Downloads.Download download )
    throws MalformedURLException
    {
        String localFilePath = ( installFolder.endsWith( File.separator ) ?
                                 installFolder :
                                 ( installFolder + File.separator ) ) + filePath;
        String remoteFilePath = download.getUrl();
        FileChecksum fileChecksum = new FileChecksumSHA1( download.getSha1() );
        return new ManagedRemoteFile( localFilePath, remoteFilePath, fileChecksum );
    }

    /**
     * Builds a list of {@link ManagedRemoteFile} objects for the Minecraft server of the specified {@link
     * MCVersionManifest} in the specified installation folder location.
     *
     * @param installFolder   installation folder location
     * @param versionManifest the {@link MCVersionManifest} to download client {@link ManagedRemoteFile} information
     *                        for
     *
     * @return a list of {@link ManagedRemoteFile} objects for the specified {@link MCVersionManifest} in the specified
     *         installation folder location
     *
     * @throws MalformedURLException if a remote download URL in Minecraft client file download information does not
     *                               have a properly formed URL and/or its URL is missing and could not be interpreted
     * @since 1.0
     */
    public List< ManagedRemoteFile > getMinecraftServerFiles( String installFolder, MCVersionManifest versionManifest )
    throws MalformedURLException
    {
        // Create list for storing created managed remote file objects
        List< ManagedRemoteFile > managedRemoteFiles = new ArrayList<>();

        // Ensure that downloads object is not null
        MCVersionManifest.Downloads downloads = versionManifest.getDownloads();
        if ( downloads != null ) {

            // Get client download information objects
            MCVersionManifest.Downloads.Download downloadsServer = downloads.getServer();
            MCVersionManifest.Downloads.Download downloadsServer_mappings = downloads.getServer_mappings();

            // Create and add managed file for client (if client download info not null)
            if ( downloadsServer != null ) {
                ManagedRemoteFile clientManagedRemoteFile = createManagedRemoteFileFromDownload( installFolder,
                                                                                                 MINECRAFT_RELATIVE_FILE_PATH,
                                                                                                 downloadsServer );
                managedRemoteFiles.add( clientManagedRemoteFile );

                // Create and add managed file for client mappings (if client mappings download info not null)
                if ( downloadsServer_mappings != null ) {
                    ManagedRemoteFile serverMappingsManagedRemoteFile = createManagedRemoteFileFromDownload(
                            installFolder, MINECRAFT_MAPPINGS_RELATIVE_FILE_PATH, downloadsServer_mappings );
                    managedRemoteFiles.add( serverMappingsManagedRemoteFile );
                }
            }
        }
        else {
            // Output to log that the downloads object was null (this shouldn't really happen)
            Logger.logDebug(
                    "No Minecraft downloads could be located for the version [" + versionManifest.getId() + "]" );
        }

        return managedRemoteFiles;
    }
}

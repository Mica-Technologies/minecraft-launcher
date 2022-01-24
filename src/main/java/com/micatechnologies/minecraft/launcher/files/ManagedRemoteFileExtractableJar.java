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

package com.micatechnologies.minecraft.launcher.files;

import com.micatechnologies.minecraft.launcher.files.hash.FileChecksum;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.List;

/**
 * Utility class for representing and managing an extractable Minecraft resource file (library, asset, etc.) by its
 * local file path, remote URL path, and hash to verify the integrity, with a list of files and folder paths to exclude
 * during extraction.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class ManagedRemoteFileExtractableJar extends ManagedRemoteFile
{
    /**
     * List of files and folder paths to exclude during extraction.
     *
     * @since 1.0
     */
    private final List< String > extractionExclude;

    /**
     * Folder where files and folders are extracted to.
     *
     * @since 1.0
     */
    private final String extractionFolder;

    /**
     * Constructor for creating a {@link ManagedRemoteFileExtractableJar} with the specified local file path,
     * remote download URL path, file hash, and extraction exclusion list
     *
     * @param localPath         local file path for the downloaded file
     * @param remotePath        remote URL path for downloading the file
     * @param hash              hash (SHA-1, MD5, NONE, etc.) used to verify the integrity of the existing game file on
     *                          the filesystem
     * @param extractionExclude list of files and folder paths to exclude during extraction
     * @param extractionFolder  folder where files and folders are extracted to
     *
     * @throws MalformedURLException if unable to parse remote path as a URL
     * @since 1.0
     */
    public ManagedRemoteFileExtractableJar( String localPath,
                                            String remotePath,
                                            FileChecksum hash,
                                            List< String > extractionExclude,
                                            String extractionFolder ) throws MalformedURLException
    {
        super( localPath, remotePath, hash );
        this.extractionExclude = extractionExclude;
        this.extractionFolder = extractionFolder;
    }

    /**
     * Downloads the {@link ManagedRemoteFile} to its local file from the remote URL path and extracts it to
     * the required location.
     *
     * @throws IOException if unable to download file from remote URL path to local file
     * @since 1.0
     */
    @Override
    protected void download() throws IOException {
        super.download();

        if ( extractionExclude != null && extractionFolder != null ) {
            SystemUtilities.extractJarFile( getLocalPath(), extractionFolder, extractionExclude );
        }
        else if ( extractionExclude != null || extractionFolder != null ) {
            Logger.logDebug( "Skipping extraction of the library at [" +
                                     getLocalPath() +
                                     "] because the extraction " +
                                     "exclusion list was null or the desired extraction folder was null. [EXCLUDE: " +
                                     extractionExclude +
                                     ", FOLDER: " +
                                     extractionFolder +
                                     "]" );
        }
    }
}

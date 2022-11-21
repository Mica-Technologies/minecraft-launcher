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
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class for representing and managing a Minecraft resource file (library, asset, etc.) by its local file path,
 * remote URL path, and hash to verify the integrity.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class ManagedRemoteFile
{
    /**
     * The local file path for the downloaded file.
     *
     * @since 1.0
     */
    private final Path localPath;

    /**
     * The remote URL path for downloading the file.
     *
     * @since 1.0
     */
    private final URL remotePath;

    /**
     * The hash (SHA-1, MD5, NONE, etc.) used to verify the integrity of the existing file on the filesystem.
     *
     * @since 1.0
     */
    private final FileChecksum hash;

    /**
     * Constructor for creating a {@link ManagedRemoteFile} with the specified local file path, remote download URL
     * path, and file hash.
     *
     * @param localPath  local file path for the downloaded file
     * @param remotePath remote URL path for downloading the file
     * @param hash       hash (SHA-1, MD5, NONE, etc.) used to verify the integrity of the existing game file on the
     *                   filesystem
     *
     * @throws MalformedURLException if unable to parse remote path as a URL
     * @since 1.0
     */
    public ManagedRemoteFile( String localPath, String remotePath, FileChecksum hash ) throws MalformedURLException
    {
        this.localPath = Path.of( localPath.replaceAll( "/", File.separator ) );
        this.remotePath = new URL( remotePath );
        this.hash = hash;
    }

    /**
     * Verifies that the hash of the file matches the expected hash value stored in {@link #hash}, and downloads from
     * the remote URL path if required.
     *
     * @return true if the file was downloaded or replaced
     *
     * @throws IOException              if unable to download the file from remote URL path to local file or unable to
     *                                  read local file during hash verification
     * @throws NoSuchAlgorithmException if unable to find hash algorithm
     * @since 1.0
     */
    public boolean verifyAndDownload() throws IOException, NoSuchAlgorithmException {
        if ( !verify() ) {
            download();
            return true;
        }
        return false;
    }

    /**
     * Downloads the {@link ManagedRemoteFile} to its local file from the remote URL path.
     *
     * @throws IOException if unable to download file from remote URL path to local file
     * @since 1.0
     */
    protected void download() throws IOException {
        // Get local file instance
        File localFile = SynchronizedFileManager.getSynchronizedFile( localPath );

        // Download file and return validation result
        localFile.getParentFile().mkdirs();
        NetworkUtilities.downloadFileFromURL( remotePath, localFile );
    }

    /**
     * Verifies that the hash of the file matches the expected hash value stored in {@link #hash}.
     *
     * @return true if file matches expected hash
     *
     * @since 1.0
     */
    protected boolean verify() throws NoSuchAlgorithmException, IOException {
        // Get local file instance
        File localFile = SynchronizedFileManager.getSynchronizedFile( localPath );

        // Verify against hash
        return hash.verifyFile( localFile );
    }

    /**
     * Gets the local file path for the downloaded file.
     *
     * @return local file path for the downloaded file
     *
     * @since 1.0
     */
    public Path getLocalPath() {
        return localPath;
    }

    /**
     * Gets the remote URL path for downloading the file.
     *
     * @return remote URL path for downloading the file
     *
     * @since 1.0
     */
    public URL getRemotePath() {
        return remotePath;
    }

    /**
     * Gets the hash (SHA-1, MD5, NONE, etc.) used to verify the integrity of the existing game file on the filesystem.
     *
     * @return hash (SHA-1, MD5, NONE, etc.) used to verify the integrity of the existing game file on the filesystem
     *
     * @since 1.0
     */
    public FileChecksum getHash() {
        return hash;
    }
}

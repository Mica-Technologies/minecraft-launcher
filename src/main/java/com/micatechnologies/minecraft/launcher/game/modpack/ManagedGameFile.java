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

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

/**
 * A Java class representation of a remote file that should be kept locally in sync.
 *
 * @author Mica Technologies
 * @version 1.1
 * @creator hawka97
 * @editors hawka97
 * @since 1.0
 */
public class ManagedGameFile
{

    /**
     * The URL of the remote file
     *
     * @since 1.0
     */
    private final String remote;

    /**
     * The path of the local file
     *
     * @since 1.0
     */
    private final String local;

    /**
     * The SHA-1 hash of the file
     *
     * @since 1.0
     */
    private final String sha1;

    /**
     * The prefix added to the local file path in {@link #local}.
     *
     * @since 1.0
     */
    private transient String localPathPrefix = "";

    /**
     * Create an {@link ManagedGameFile} object with hash checking disabled, using the specified remote URL and local
     * file path.
     *
     * @param remote remote file url
     * @param local  local file path
     *
     * @since 1.0
     */
    public ManagedGameFile( String remote, String local ) {
        String localTemp;
        try {
            localTemp = local.replaceAll( "/", File.separator );
        }
        catch ( Exception e ) {
            localTemp = local;
        }

        this.local = localTemp;
        this.sha1 = "-1";
        this.remote = remote;
    }

    /**
     * Create an MCRemoteFile object with hash checking enabled, using the specified remote URL, local file path and
     * SHA-1 hash.
     *
     * @param remote remote file URL
     * @param local  local file path
     * @param sha1   file SHA-1 hash
     *
     * @since 1.0
     */
    public ManagedGameFile( String remote, String local, String sha1 ) {
        String localTemp;
        try {
            localTemp = local.replaceAll( "/", File.separator );
        }
        catch ( Exception e ) {
            localTemp = local;
        }

        this.local = localTemp;
        this.sha1 = sha1;
        this.remote = remote;
    }

    /**
     * Set the local file path prefix of this remote file
     *
     * @param localPathPrefix local file path prefix
     *
     * @since 1.0
     */
    public void setLocalPathPrefix( String localPathPrefix ) {
        this.localPathPrefix = localPathPrefix;
    }

    /**
     * Verify the integrity of the local copy of this remote file
     *
     * @return true if local copy is valid
     *
     * @since 1.0
     */
    private boolean verifyLocalFile() {
        // Create File instance
        File localFile = SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() );

        // Hash Checking Disabled: Return true if file exists and is file (not folder)
        if ( this.sha1.equals( "-1" ) ) {
            return localFile.exists() && localFile.isFile();
        }
        // Hash Checking Enabled: Return true if file exists, is not a folder, and hashes match
        else {
            return HashUtilities.verifySHA1( localFile, sha1 );
        }
    }

    /**
     * Download a copy of the remote file to the configured local file path
     *
     * @throws ModpackException if unable to download file
     * @since 1.0
     */
    private void downloadLocalFile() throws ModpackException {
        // Create File instance
        File localFile = SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() );

        // Download file and return validation result
        try {
            //noinspection ResultOfMethodCallIgnored
            localFile.getParentFile().mkdirs();
            NetworkUtilities.downloadFileFromURL( new URL( remote ), localFile );
        }
        catch ( IOException e ) {
            throw new ModpackException(
                    LocalizationManager.UNABLE_DOWNLOAD_FILE_LOCALLY_TO_TEXT + " " + getFullLocalFilePath(), e );
        }
    }

    /**
     * Check for and download any new update(s) to the local file copy.
     *
     * @return true if changed
     *
     * @throws ModpackException if file cannot verify or download
     * @since 1.0
     */
    public boolean updateLocalFile() throws ModpackException {
        if ( !verifyLocalFile() ) {
            downloadLocalFile();
            return true;
        }
        return false;
    }

    /**
     * Get the local file path of this file.
     *
     * @return local file path
     *
     * @since 1.0
     */
    public String getLocalFilePath() {
        return local;
    }

    /**
     * Get the file name of this file.
     *
     * @return file name
     *
     * @since 1.1
     */
    public String getFileName() {
        return SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() ).getName();
    }

    /**
     * Get the full local file path of this file, including local file path prefix.
     *
     * @return full local file path
     *
     * @since 1.0
     */
    public String getFullLocalFilePath() {
        if ( !localPathPrefix.isEmpty() ) {
            if ( localPathPrefix.endsWith( File.separator ) ) {
                return localPathPrefix + local;
            }
            else {
                return localPathPrefix + File.separator + local;
            }
        }
        else {
            return local;
        }
    }

    /**
     * Read this file into a JsonObject.
     *
     * @return JsonObject of this file
     *
     * @throws ModpackException if reading fails
     * @since 1.0
     */
    public JsonObject readToJsonObject() throws ModpackException {
        // Verify file is locally downloaded
        updateLocalFile();

        // Return file contents as JSON object
        File localFileObject = SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() );
        try {
            return FileUtilities.readAsJsonObject( localFileObject );
        }
        catch ( IOException e ) {
            throw new ModpackException( LocalizationManager.UNABLE_READ_LOCAL_FILE_TO_JSON_EXCEPTION_TEXT, e );
        }
    }
}

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
 * @version 2.1
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
     * The MD5 hash of the file
     *
     * @since 2.0
     */
    private final String md5;

    /**
     * The SHA-256 hash of the file
     *
     * @since 2.1
     */
    private final String sha256;

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
        this.remote = remote;

        this.sha1 = "-1";
        this.md5 = "-1";
        this.sha256 = "-1";
    }

    /**
     * Create an MCRemoteFile object with hash checking enabled, using the specified remote URL, local file path and
     * hash configuration.
     *
     * @param remote   remote file URL
     * @param local    local file path
     * @param hash     file hash
     * @param hashType file hash type
     *
     * @since 1.0
     */
    public ManagedGameFile( String remote, String local, String hash, ManagedGameFileHashType hashType ) {
        String localTemp;
        try {
            localTemp = local.replaceAll( "/", File.separator );
        }
        catch ( Exception e ) {
            localTemp = local;
        }

        this.local = localTemp;
        this.remote = remote;

        if ( hashType == ManagedGameFileHashType.SHA1 ) {
            this.sha1 = hash;
            this.md5 = "-1";
            this.sha256 = "-1";
        }
        else if ( hashType == ManagedGameFileHashType.MD5 ) {
            this.sha1 = "-1";
            this.md5 = hash;
            this.sha256 = "-1";
        }
        else if ( hashType == ManagedGameFileHashType.SHA256 ) {
            this.sha1 = "-1";
            this.md5 = "-1";
            this.sha256 = hash;
        }
        else {
            this.sha1 = "-1";
            this.md5 = "-1";
            this.sha256 = "-1";
        }
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

        // Hash Checking Enabled (SHA1): Return true if file exists, is not a folder, and hashes match
        if ( this.sha1 != null && !this.sha1.equals( "-1" ) ) {
            return HashUtilities.verifySHA1( localFile, sha1 );
        }
        // Hash Checking Enabled (MD5): Return true if file exists, is not a folder, and hashes match
        else if ( this.md5 != null && !this.md5.equals( "-1" ) ) {
            return HashUtilities.verifyMD5( localFile, md5 );
        }
        // Hash Checking Enabled (SHA256): Return true if file exists, is not a folder, and hashes match
        else if ( this.sha256 != null && !this.sha256.equals( "-1" ) ) {
            return HashUtilities.verifySHA256( localFile, sha256 );
        }
        // Hash Checking Disabled: Return true if file exists and is file (not folder)
        else {
            return localFile.exists() && localFile.isFile();
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
            System.err.println( "FILE FAILED VERIFICATION, RE-DOWNLOADING: " + getFullLocalFilePath() );
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

    /**
     * The enum with values indicating the type of hash supplied to the {@link ManagedGameFile}.
     *
     * @since 2.0
     */
    public enum ManagedGameFileHashType
    {
        SHA1, MD5, SHA256
    }
}

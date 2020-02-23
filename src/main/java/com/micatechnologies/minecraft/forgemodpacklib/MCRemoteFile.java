package com.micatechnologies.minecraft.forgemodpacklib;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.NoSuchAlgorithmException;

import org.apache.commons.io.FileUtils;

/**
 * A Java class representation of a remote file that should be kept locally in sync.
 *
 * @author Mica Technologies/hawka97
 * @version 1.1
 */
class MCRemoteFile {

    /**
     * The URL of the remote file
     */
    private final String remote;

    /**
     * The path of the local file
     */
    private final String local;

    /**
     * The SHA-1 hash of the file
     */
    private final String sha1;

    /**
     * The prefix added to the local file path in {@link #local}. Configured using {@link }
     */
    private transient String localPathPrefix = "";

    /**
     * Create an MCRemoteFile object with hash checking disabled, using the specified remote URL and
     * local file path.
     *
     * @param remote remote file url
     * @param local  local file path
     *
     * @since 1.0
     */
    MCRemoteFile( String remote, String local ) {
        this.remote = remote;
        this.local = local.replaceAll( "/", File.separator );
        this.sha1 = "-1";
    }

    /**
     * Create an MCRemoteFile object with hash checking enabled, using the specified remote URL,
     * local file path and SHA-1 hash.
     *
     * @param remote remote file URL
     * @param local  local file path
     * @param sha1   file SHA-1 hash
     *
     * @since 1.0
     */
    MCRemoteFile( String remote, String local, String sha1 ) {
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
    void setLocalPathPrefix( String localPathPrefix ) {
        this.localPathPrefix = localPathPrefix;
    }

    /**
     * Verify the integrity of the local copy of this remote file
     *
     * @return true if local copy is valid
     *
     * @throws MCForgeModpackException if unable to verify file
     * @since 1.0
     */
    private boolean verifyLocalFile() throws MCForgeModpackException {
        // Create File instance
        File localFile = new File( getFullLocalFilePath() );

        // Hash Checking Disabled: Return true if file exists and is file (not folder)
        if ( this.sha1.equals( "-1" ) ) {
            return localFile.exists() && localFile.isFile();
        }
        // Hash Checking Enabled: Return true if file exists, is not a folder, and hashes match
        else {
            try {
                return MCModpackOSUtils.verifySHA( localFile.toPath(), sha1 );
            }
            catch ( NoSuchAlgorithmException | IOException e ) {
                throw new MCForgeModpackException( "Unable to verify local file hash.", e );
            }
        }
    }

    /**
     * Download a copy of the remote file to the configured local file path
     *
     * @throws MCForgeModpackException if unable to download file
     * @since 1.0
     */
    private void downloadLocalFile() throws MCForgeModpackException {
        // Create File instance
        File localFile = new File( getFullLocalFilePath() );

        // Download file and return validation result
        try {
            MCModpackOSUtils.downloadFileFromURL( new URL( remote ), localFile );
        }
        catch ( IOException e ) {
            throw new MCForgeModpackException(
                    "Unable to download file locally to " + getFullLocalFilePath(), e );
        }
    }

    /**
     * Check for and download any new update(s) to the local file copy.
     *
     * @return true if changed
     *
     * @throws MCForgeModpackException if file cannot verify or download
     * @since 1.0
     */
    boolean updateLocalFile() throws MCForgeModpackException {
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
    String getLocalFilePath() {
        return local;
    }

    /**
     * Get the file name of this file.
     *
     * @return file name
     *
     * @since 1.1
     */
    String getFileName() {
        return new File( getFullLocalFilePath() ).getName();
    }

    /**
     * Get the full local file path of this file, including local file path prefix.
     *
     * @return full local file path
     *
     * @since 1.0
     */
    String getFullLocalFilePath() {
        if ( !localPathPrefix.isEmpty() ) {
            if ( localPathPrefix.endsWith( MCModpackOSUtils.getFileSeparator() ) ) {
                return localPathPrefix + local;
            }
            else {
                return localPathPrefix + MCModpackOSUtils.getFileSeparator() + local;
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
     * @throws MCForgeModpackException if reading fails
     * @since 1.0
     */
    JsonObject readToJsonObject() throws MCForgeModpackException {
        // Verify file is locally downloaded
        updateLocalFile();

        // Read in to string from connection
        BufferedReader bufferedReader;
        try {
            bufferedReader = new BufferedReader( new InputStreamReader(
                    new FileInputStream( new File( getFullLocalFilePath() ) ) ) );
        }
        catch ( IOException e ) {
            throw new MCForgeModpackException( "Unable to create buffer for file reading.", e );
        }
        StringBuilder jsonStr = new StringBuilder();
        String tempLine;
        try {
            while ( ( tempLine = bufferedReader.readLine() ) != null ) {
                jsonStr.append( tempLine );
            }
        }
        catch ( IOException e ) {
            throw new MCForgeModpackException( "An error occurred while reading file.", e );
        }

        // Return read Json Object
        return new Gson().fromJson( jsonStr.toString(), JsonObject.class );
    }
}

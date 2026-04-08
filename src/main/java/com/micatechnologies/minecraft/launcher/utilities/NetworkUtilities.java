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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Class containing utility methods and other functionality that pertains to the network and/or network connections in
 * the launcher.
 *
 * @author Mica Technologies
 * @version 3.0
 * @since 1.0
 */
public class NetworkUtilities
{
    /**
     * Default connect timeout in milliseconds (10 seconds).
     */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;

    /**
     * Default read timeout in milliseconds (30 seconds).
     */
    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    /**
     * Maximum number of retry attempts for transient network failures.
     */
    private static final int MAX_RETRIES = 3;

    /**
     * Base delay in milliseconds for exponential backoff between retries.
     */
    private static final long RETRY_BASE_DELAY_MS = 1_000;

    /**
     * User-Agent string identifying this launcher to remote servers.
     */
    private static final String USER_AGENT = "MicaMinecraftLauncher/" +
            ( LauncherConstants.LAUNCHER_APPLICATION_VERSION != null
                    ? LauncherConstants.LAUNCHER_APPLICATION_VERSION : "dev" );

    /**
     * Applies default timeouts and headers to a URLConnection.
     */
    private static void applyDefaults( URLConnection connection ) {
        connection.setConnectTimeout( DEFAULT_CONNECT_TIMEOUT_MS );
        connection.setReadTimeout( DEFAULT_READ_TIMEOUT_MS );
        connection.setUseCaches( false );
        connection.setRequestProperty( "User-Agent", USER_AGENT );
    }

    /**
     * Downloads the file from the specified URL (as string) to the specified file.
     *
     * @param source      source URL (as string)
     * @param destination destination file
     *
     * @throws IOException if unable to download or save file
     * @since 1.1
     */
    public static void downloadFileFromURL( String source, File destination ) throws IOException {
        downloadFileFromURL( new URL( source ), destination );
    }

    /**
     * Downloads the file from the specified URL to the specified file. Uses a temporary file and atomic rename to
     * prevent partial downloads from being treated as complete on subsequent runs.
     *
     * @param source      source URL
     * @param destination destination file
     *
     * @throws IOException if unable to download or save file after all retry attempts
     * @since 1.1
     */
    public static void downloadFileFromURL( URL source, File destination ) throws IOException {
        synchronized ( destination ) {
            IOException lastException = null;
            for ( int attempt = 1; attempt <= MAX_RETRIES; attempt++ ) {
                try {
                    File tempFile = new File( destination.getAbsolutePath() + ".tmp" );
                    URLConnection connection = source.openConnection();
                    applyDefaults( connection );
                    try ( InputStream is = connection.getInputStream() ) {
                        FileUtils.copyInputStreamToFile( is, tempFile );
                    }
                    Files.move( tempFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING );
                    return;
                }
                catch ( IOException e ) {
                    lastException = e;
                    if ( attempt < MAX_RETRIES ) {
                        Logger.logWarningSilent( "Download attempt " + attempt + " failed for " + source +
                                                         ", retrying in " + ( RETRY_BASE_DELAY_MS * attempt ) + "ms" );
                        try {
                            Thread.sleep( RETRY_BASE_DELAY_MS * attempt );
                        }
                        catch ( InterruptedException ie ) {
                            Thread.currentThread().interrupt();
                            throw new IOException( "Download interrupted", ie );
                        }
                    }
                }
            }
            throw lastException;
        }
    }

    /**
     * Downloads the file from the specified URL (as string) to the specified file.
     *
     * @param source              source URL (as string)
     * @param destination         destination file
     * @param responseContentType content type of response
     *
     * @throws IOException if unable to download or save file
     * @since 1.1
     */
    public static void downloadFileFromURL( String source, File destination, String responseContentType )
    throws IOException
    {
        downloadFileFromURL( new URL( source ), destination, responseContentType );
    }

    /**
     * Downloads the file from the specified URL to the specified file.
     *
     * @param source              source URL
     * @param destination         destination file
     * @param responseContentType content type of response
     *
     * @throws IOException if unable to download or save file
     * @since 1.1
     */
    public static void downloadFileFromURL( URL source, File destination, String responseContentType )
    throws IOException
    {
        URLConnection connection = source.openConnection();
        applyDefaults( connection );
        connection.setDoInput( true );
        connection.setRequestProperty( "Accept", responseContentType );
        File tempFile = new File( destination.getAbsolutePath() + ".tmp" );
        try ( InputStream is = connection.getInputStream() ) {
            FileUtils.copyInputStreamToFile( is, tempFile );
        }
        Files.move( tempFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING );
    }

    /**
     * Downloads the file from the specified URL (as string) and returns it as a string.
     *
     * @param source source URL (as string)
     *
     * @return file contents as string
     *
     * @throws IOException if unable to download file
     * @since 1.1
     */
    public static String downloadFileFromURL( String source ) throws IOException {
        return downloadFileFromURL( new URL( source ) );
    }

    /**
     * Downloads the file from the specified URL (as string) and returns it as a string.
     *
     * @param source              source URL (as string)
     * @param responseContentType content type of response
     *
     * @return file contents as string
     *
     * @throws IOException if unable to download or save file
     * @since 1.1
     */
    public static String downloadFileFromURL( String source, String responseContentType ) throws IOException
    {
        return downloadFileFromURL( new URL( source ), responseContentType );
    }

    /**
     * Downloads the file from the specified URL and returns it as a string.
     *
     * @param source source URL
     *
     * @return file contents as string
     *
     * @throws IOException if unable to download or save file
     * @since 1.1
     */
    public static String downloadFileFromURL( URL source ) throws IOException {
        URLConnection connection = source.openConnection();
        applyDefaults( connection );
        try ( InputStream is = connection.getInputStream() ) {
            return IOUtils.toString( is, StandardCharsets.UTF_8 );
        }
    }

    /**
     * Downloads the file from the specified URL and returns it as a string.
     *
     * @param source              source URL
     * @param responseContentType content type of response
     *
     * @return file contents as string
     *
     * @throws IOException if unable to download or save file
     * @since 1.1
     */
    public static String downloadFileFromURL( URL source, String responseContentType ) throws IOException
    {
        URLConnection connection = source.openConnection();
        applyDefaults( connection );
        connection.setDoInput( true );
        connection.setRequestProperty( "Accept", responseContentType );
        try ( InputStream is = connection.getInputStream() ) {
            return IOUtils.toString( is, StandardCharsets.UTF_8 );
        }
    }
}

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

import com.micatechnologies.minecraft.launcher.config.ConfigManager;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;

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
     * Per-path locks to prevent concurrent downloads of the same file. Using canonical path strings as keys ensures
     * that different File objects pointing to the same path share the same lock.
     */
    private static final ConcurrentHashMap< String, Object > PATH_LOCKS = new ConcurrentHashMap<>();

    /**
     * Returns a shared lock object for the given file path, creating one if needed.
     */
    private static Object getPathLock( File file ) {
        try {
            String key = file.getCanonicalPath();
            return PATH_LOCKS.computeIfAbsent( key, k -> new Object() );
        }
        catch ( IOException e ) {
            // Fallback to absolute path if canonical fails
            return PATH_LOCKS.computeIfAbsent( file.getAbsolutePath(), k -> new Object() );
        }
    }

    /**
     * User-Agent string identifying this launcher to remote servers.
     */
    private static final String USER_AGENT = "MicaMinecraftLauncher/" +
            ( LauncherConstants.LAUNCHER_APPLICATION_VERSION != null
                    ? LauncherConstants.LAUNCHER_APPLICATION_VERSION : "dev" );

    /**
     * Cached proxy instance built from config. Rebuilt when config changes.
     */
    private static volatile Proxy configuredProxy = null;
    private static volatile boolean proxyInitialized = false;

    /**
     * Returns the configured proxy, or {@link Proxy#NO_PROXY} if proxy is disabled.
     */
    private static Proxy getProxy() {
        if ( !proxyInitialized ) {
            reloadProxy();
        }
        return configuredProxy;
    }

    /**
     * Reloads proxy settings from config. Call after changing proxy settings.
     */
    public static void reloadProxy() {
        try {
            if ( ConfigManager.getProxyEnable() ) {
                String host = ConfigManager.getProxyHost();
                int port = ConfigManager.getProxyPort();
                String type = ConfigManager.getProxyType();
                if ( host != null && !host.isEmpty() ) {
                    Proxy.Type proxyType = "SOCKS".equalsIgnoreCase( type ) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
                    configuredProxy = new Proxy( proxyType, new InetSocketAddress( host, port ) );
                    Logger.logStd( "Proxy configured: " + proxyType + " " + host + ":" + port );
                }
                else {
                    configuredProxy = Proxy.NO_PROXY;
                }
            }
            else {
                configuredProxy = Proxy.NO_PROXY;
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Failed to configure proxy, using direct connection" );
            configuredProxy = Proxy.NO_PROXY;
        }
        proxyInitialized = true;
    }

    /**
     * Opens a URLConnection to the given URL, using the configured proxy if enabled.
     */
    private static URLConnection openConnection( URL url ) throws IOException {
        Proxy proxy = getProxy();
        return ( proxy == Proxy.NO_PROXY ) ? url.openConnection() : url.openConnection( proxy );
    }

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
     * Whether the launcher is currently in offline mode. When true, download methods that support offline fallback will
     * skip network access.
     */
    private static volatile boolean offlineMode = false;

    /**
     * Returns true if the launcher is currently in offline mode.
     *
     * @return true if offline
     *
     * @since 2.0
     */
    public static boolean isOffline()
    {
        return offlineMode;
    }

    /**
     * Sets the offline mode flag. When true, network-dependent operations should gracefully degrade.
     *
     * @param offline true to enable offline mode
     *
     * @since 2.0
     */
    public static void setOfflineMode( boolean offline )
    {
        offlineMode = offline;
    }

    /**
     * Checks network connectivity by attempting a quick HEAD request to a reliable host. Sets {@link #offlineMode}
     * accordingly and returns the result.
     *
     * @return true if the network is reachable
     *
     * @since 2.0
     */
    public static boolean checkNetworkAvailability()
    {
        try {
            URLConnection connection = new URL( "https://launchermeta.mojang.com/" ).openConnection();
            connection.setConnectTimeout( 5_000 );
            connection.setReadTimeout( 5_000 );
            connection.setRequestProperty( "User-Agent", USER_AGENT );
            try ( InputStream is = connection.getInputStream() ) {
                // Just need the connection to succeed
                is.read();
            }
            offlineMode = false;
            return true;
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Network connectivity check failed, entering offline mode" );
            offlineMode = true;
            return false;
        }
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
        synchronized ( getPathLock( destination ) ) {
            IOException lastException = null;
            for ( int attempt = 1; attempt <= MAX_RETRIES; attempt++ ) {
                try {
                    File tempFile = new File( destination.getAbsolutePath() + ".tmp" );
                    URLConnection connection = openConnection( source );
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
     * Buffer size for tracked downloads (8 KB).
     */
    private static final int DOWNLOAD_BUFFER_SIZE = 8192;

    /**
     * Downloads the file from the specified URL to the specified file, reporting byte-level progress to the given
     * {@link DownloadTracker}. Uses a manual stream copy with a buffer instead of
     * {@link FileUtils#copyInputStreamToFile} so that progress can be reported per chunk.
     *
     * @param source      source URL
     * @param destination destination file
     * @param tracker     the download tracker to report progress to, or null to skip tracking
     *
     * @throws IOException if unable to download or save file after all retry attempts
     * @since 2.0
     */
    public static void downloadFileFromURL( URL source, File destination, DownloadTracker tracker ) throws IOException {
        synchronized ( getPathLock( destination ) ) {
            IOException lastException = null;
            for ( int attempt = 1; attempt <= MAX_RETRIES; attempt++ ) {
                try {
                    File tempFile = new File( destination.getAbsolutePath() + ".tmp" );
                    URLConnection connection = openConnection( source );
                    applyDefaults( connection );
                    long contentLength = connection.getContentLengthLong();
                    if ( tracker != null ) {
                        tracker.registerDownload( contentLength );
                    }
                    try ( InputStream is = connection.getInputStream();
                          OutputStream os = new BufferedOutputStream( new FileOutputStream( tempFile ) ) ) {
                        byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
                        int bytesRead;
                        while ( ( bytesRead = is.read( buffer ) ) != -1 ) {
                            os.write( buffer, 0, bytesRead );
                            if ( tracker != null ) {
                                tracker.addBytes( bytesRead );
                            }
                        }
                    }
                    Files.move( tempFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING );
                    if ( tracker != null ) {
                        tracker.completeDownload();
                    }
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
     * Downloads the file from the specified URL (as string) to the specified file, with download tracking.
     *
     * @param source      source URL (as string)
     * @param destination destination file
     * @param tracker     the download tracker, or null
     *
     * @throws IOException if unable to download or save file
     * @since 2.0
     */
    public static void downloadFileFromURL( String source, File destination, DownloadTracker tracker )
    throws IOException
    {
        downloadFileFromURL( new URL( source ), destination, tracker );
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
        URLConnection connection = openConnection( source );
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
        URLConnection connection = openConnection( source );
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
        URLConnection connection = openConnection( source );
        applyDefaults( connection );
        connection.setDoInput( true );
        connection.setRequestProperty( "Accept", responseContentType );
        try ( InputStream is = connection.getInputStream() ) {
            return IOUtils.toString( is, StandardCharsets.UTF_8 );
        }
    }
}

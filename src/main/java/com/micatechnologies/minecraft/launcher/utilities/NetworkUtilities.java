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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
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
     * Optional listener invoked when a download enters the retry path. Set by
     * the launch flow before pre-launch starts (and cleared after) so a row
     * sub-text like "Retrying (1/3) X" surfaces what would otherwise be a
     * silent log line. Volatile rather than ThreadLocal because per-file
     * downloads happen on worker threads spawned from parallelStream pools
     * that don't inherit ThreadLocal state — the static reference is read
     * by every retry call regardless of which thread it's on.
     *
     * <p>Best-effort. Multiple concurrent downloads firing retries can step
     * on each other's messages, but the symptom is just "the user sees
     * whichever retry was most recent" which still beats invisible retries.</p>
     *
     * @since 2026.3
     */
    private static volatile java.util.function.Consumer< String > retryNoticeListener = null;

    /**
     * Installs a listener that fires when a download retry occurs. Set to
     * {@code null} to clear. See {@link #retryNoticeListener} for the
     * single-listener / cross-thread semantics.
     */
    public static void setRetryNoticeListener( java.util.function.Consumer< String > listener )
    {
        retryNoticeListener = listener;
    }

    /** Pulls the short filename out of a URL for a more readable retry message
     *  than the full URL would produce. {@code https://example/foo/bar.jar?x=1}
     *  becomes {@code bar.jar}. */
    private static String urlFileName( URL source )
    {
        if ( source == null ) return "";
        String path = source.getPath();
        if ( path == null || path.isEmpty() ) return source.getHost();
        int slash = path.lastIndexOf( '/' );
        return slash >= 0 && slash < path.length() - 1 ? path.substring( slash + 1 ) : path;
    }

    /** Fires the retry-notice listener if one's installed. Safe to call from
     *  any thread; null-checks the listener atomically via the volatile read. */
    private static void notifyRetry( URL source, int attempt, int maxRetries )
    {
        java.util.function.Consumer< String > l = retryNoticeListener;
        if ( l == null ) return;
        try {
            l.accept( LocalizationManager.format( "network.download.retrying", attempt, maxRetries,
                                                  urlFileName( source ) ) );
        }
        catch ( Throwable ignored ) { /* listener faults shouldn't poison the retry path */ }
    }

    /**
     * Per-path locks to prevent concurrent downloads of the same file. Using canonical path strings as keys ensures
     * that different File objects pointing to the same path share the same lock.
     */
    private static final ConcurrentHashMap< String, Object > PATH_LOCKS = new ConcurrentHashMap<>();

    /**
     * Returns a shared lock object for the given file path, creating one if needed.
     * Keyed by the normalized absolute path (case-folded on Windows) so different
     * File objects pointing at the same file share a lock — matching
     * {@code SynchronizedFileManager}'s keying — without the per-download
     * {@code getCanonicalPath()} filesystem syscall the old version paid.
     */
    private static Object getPathLock( File file ) {
        java.nio.file.Path normalized = file.toPath().toAbsolutePath().normalize();
        String key = File.separatorChar == '\\'
                     ? normalized.toString().toLowerCase( java.util.Locale.ROOT )
                     : normalized.toString();
        return PATH_LOCKS.computeIfAbsent( key, k -> new Object() );
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
                    Logger.logStd( LocalizationManager.format( "log.network.proxyConfigured", proxyType.toString(),
                                                               host, port ) );
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
            Logger.logWarningSilent( LocalizationManager.get( "log.network.proxyConfigFailed" ) );
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
        try ( java.net.Socket socket = new java.net.Socket() ) {
            socket.connect( new java.net.InetSocketAddress( "launchermeta.mojang.com", 443 ), 5_000 );
            offlineMode = false;
            return true;
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.get( "log.network.connectivityCheckFailed" ) );
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
                        Logger.logWarningSilent( LocalizationManager.format( "log.network.downloadAttemptFailed",
                                                                             attempt, source.toString(),
                                                                             RETRY_BASE_DELAY_MS * attempt ) );
                        notifyRetry( source, attempt + 1, MAX_RETRIES );
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
                            // Battery saver: when on battery + the user hasn't disabled throttling,
                            // sleep just enough per chunk to cap this stream at the configured rate.
                            // No-op on AC, on desktops, or when disabled.
                            PowerStateManager.maybeThrottle( bytesRead );
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
                        Logger.logWarningSilent( LocalizationManager.format( "log.network.downloadAttemptFailed",
                                                                             attempt, source.toString(),
                                                                             RETRY_BASE_DELAY_MS * attempt ) );
                        notifyRetry( source, attempt + 1, MAX_RETRIES );
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

    /**
     * Bounded download variant — reads at most {@code maxBytes} into a string, then
     * throws {@link IOException} if the response continues past that ceiling
     * Use this for any JSON endpoint whose response is fed
     * into Gson or otherwise into the application's parsing path: an unbounded
     * download from a compromised or hostile server can OOM the launcher with a
     * multi-gigabyte body.
     *
     * <p>Suggested limits used by callers today:
     * <ul>
     *   <li>{@code 256 * 1024} — announcements / GitHub release JSON (small bounded
     *       schemas).</li>
     *   <li>{@code 50 * 1024 * 1024} — modpack manifests (can be large for packs
     *       with thousands of mods).</li>
     * </ul>
     *
     * @param source   the URL to fetch
     * @param maxBytes maximum response body size in bytes; reading beyond this
     *                 raises {@link IOException}
     * @return the UTF-8 decoded body
     */
    public static String downloadFileFromURLBounded( URL source, long maxBytes ) throws IOException {
        // Bounded fetch implies "this body feeds straight into Gson" — make the
        // contextual security guarantees match the contextual risk. Disable
        // automatic redirects so an HTTPS→HTTP downgrade in a redirect chain
        // can't sneak a plaintext body into a flow the caller thinks is end-to-
        // end HTTPS. Then walk up to MAX_REDIRECTS hops manually, requiring each
        // hop to also be HTTPS.
        URL current = source;
        for ( int hop = 0; hop <= MAX_REDIRECTS; hop++ ) {
            if ( !"https".equalsIgnoreCase( current.getProtocol() ) ) {
                throw new IOException( "Refusing non-HTTPS URL on bounded fetch: " + current );
            }
            URLConnection connection = openConnection( current );
            applyDefaults( connection );
            if ( connection instanceof java.net.HttpURLConnection httpConn ) {
                httpConn.setInstanceFollowRedirects( false );
                int code = httpConn.getResponseCode();
                if ( code == java.net.HttpURLConnection.HTTP_MOVED_PERM
                        || code == java.net.HttpURLConnection.HTTP_MOVED_TEMP
                        || code == java.net.HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308 ) {
                    String location = httpConn.getHeaderField( "Location" );
                    httpConn.disconnect();
                    if ( location == null || location.isBlank() ) {
                        throw new IOException( "Redirect without Location header from " + current );
                    }
                    current = new URL( current, location );
                    continue;
                }
            }
            assertAcceptableJsonContentType( connection.getContentType(), current );
            try ( InputStream is = connection.getInputStream();
                  ByteArrayOutputStream out = new ByteArrayOutputStream() ) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ( ( read = is.read( buffer ) ) != -1 ) {
                    total += read;
                    if ( total > maxBytes ) {
                        throw new IOException(
                                "Response from " + source + " exceeded max-bytes cap (" + maxBytes + ")" );
                    }
                    out.write( buffer, 0, read );
                }
                return out.toString( StandardCharsets.UTF_8 );
            }
        }
        throw new IOException( "Too many redirects following " + source );
    }

    /** Cap on the number of HTTPS-only redirect hops a bounded fetch will follow.
     *  Generous enough for typical CDN bouncing, tight enough that a redirect loop
     *  doesn't waste resources. */
    private static final int MAX_REDIRECTS = 5;

    /** Convenience overload that takes the URL as a string. */
    public static String downloadFileFromURLBounded( String source, long maxBytes ) throws IOException {
        return downloadFileFromURLBounded( new URL( source ), maxBytes );
    }

    /**
     * Result of a {@link #downloadFileFromURLBoundedConditional} call. Carries the
     * decoded body plus the ETag and Last-Modified headers so the caller can persist
     * them alongside the cached body and pass them back on the next fetch to make
     * the request conditional.
     *
     * <p>When {@link #notModified()} is {@code true}, {@link #body()} is {@code null}
     * and the caller should reuse their previously-cached body instead.</p>
     *
     * @param body         the response body, or {@code null} on a 304 response
     * @param etag         the {@code ETag} response header, or {@code null} if absent
     * @param lastModified the {@code Last-Modified} response header, or {@code null} if absent
     * @param notModified  {@code true} iff the server returned 304 Not Modified
     *
     * @since 3.5
     */
    public record BoundedFetchResult( String body,
                                      String etag,
                                      String lastModified,
                                      boolean notModified )
    {
        /** Convenience: was this a 304? */
        public boolean isNotModified() { return notModified; }
    }

    /**
     * HTTPS-only bounded fetch with conditional-request support. Same body-size cap +
     * redirect-hop validation + Content-Type gate as {@link #downloadFileFromURLBounded},
     * but emits {@code If-None-Match} / {@code If-Modified-Since} when the caller
     * supplies stored validators from a previous fetch.
     *
     * <p>On a {@code 304 Not Modified} response, returns a {@link BoundedFetchResult}
     * with {@link BoundedFetchResult#notModified()} {@code true} and a {@code null}
     * body — the caller should reuse its persisted cache rather than re-parsing the
     * old bytes.</p>
     *
     * @param source           HTTPS URL to fetch
     * @param maxBytes         body-size cap; throws if exceeded
     * @param prevEtag         the ETag value persisted from the prior fetch, or null
     * @param prevLastModified the Last-Modified value persisted from the prior fetch, or null
     *
     * @return a result describing the body + freshness validators
     *
     * @since 3.5
     */
    public static BoundedFetchResult downloadFileFromURLBoundedConditional(
            URL source,
            long maxBytes,
            String prevEtag,
            String prevLastModified ) throws IOException
    {
        URL current = source;
        for ( int hop = 0; hop <= MAX_REDIRECTS; hop++ ) {
            if ( !"https".equalsIgnoreCase( current.getProtocol() ) ) {
                throw new IOException( "Refusing non-HTTPS URL on bounded fetch: " + current );
            }
            URLConnection connection = openConnection( current );
            applyDefaults( connection );
            if ( connection instanceof java.net.HttpURLConnection httpConn ) {
                httpConn.setInstanceFollowRedirects( false );
                if ( prevEtag != null && !prevEtag.isBlank() ) {
                    httpConn.setRequestProperty( "If-None-Match", prevEtag );
                }
                if ( prevLastModified != null && !prevLastModified.isBlank() ) {
                    httpConn.setRequestProperty( "If-Modified-Since", prevLastModified );
                }
                int code = httpConn.getResponseCode();
                if ( code == java.net.HttpURLConnection.HTTP_NOT_MODIFIED ) {
                    // 304: the cache the caller carries is still valid. Echo back the
                    // (possibly-updated) validators in case the server changed them.
                    String newEtag = httpConn.getHeaderField( "ETag" );
                    String newLastMod = httpConn.getHeaderField( "Last-Modified" );
                    httpConn.disconnect();
                    return new BoundedFetchResult( null,
                                                    newEtag != null ? newEtag : prevEtag,
                                                    newLastMod != null ? newLastMod : prevLastModified,
                                                    true );
                }
                if ( code == java.net.HttpURLConnection.HTTP_MOVED_PERM
                        || code == java.net.HttpURLConnection.HTTP_MOVED_TEMP
                        || code == java.net.HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308 ) {
                    String location = httpConn.getHeaderField( "Location" );
                    httpConn.disconnect();
                    if ( location == null || location.isBlank() ) {
                        throw new IOException( "Redirect without Location header from " + current );
                    }
                    current = new URL( current, location );
                    continue;
                }
            }
            assertAcceptableJsonContentType( connection.getContentType(), current );
            String etag = connection.getHeaderField( "ETag" );
            String lastMod = connection.getHeaderField( "Last-Modified" );
            try ( InputStream is = connection.getInputStream();
                  ByteArrayOutputStream out = new ByteArrayOutputStream() ) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ( ( read = is.read( buffer ) ) != -1 ) {
                    total += read;
                    if ( total > maxBytes ) {
                        throw new IOException(
                                "Response from " + source + " exceeded max-bytes cap (" + maxBytes + ")" );
                    }
                    out.write( buffer, 0, read );
                }
                return new BoundedFetchResult( out.toString( StandardCharsets.UTF_8 ),
                                                etag, lastMod, false );
            }
        }
        throw new IOException( "Too many redirects following " + source );
    }

    /**
     * Shared Content-Type gate for the bounded JSON fetch variants. The bounded
     * fetcher exists specifically for JSON consumption; reject responses whose
     * declared type contradicts that so a compromised host serving text/html
     * with a JSON-shaped body can't slip past Gson.
     *
     * <p>Accepted, in order of specificity:
     * <ul>
     *   <li>{@code application/json}, {@code text/json},
     *       {@code application/javascript} — explicit JSON declarations</li>
     *   <li>{@code text/plain}, {@code application/octet-stream} — what static
     *       blob hosts (Azure Blob, S3 with default config, GitHub raw, etc.)
     *       serve for unknown / custom extensions like {@code .mmcjson} when
     *       the host has no MIME mapping for it. The body IS JSON; the host
     *       just doesn't know to advertise it. Downstream Gson parse fails
     *       noisily if the body actually turns out to be non-JSON, and any
     *       files referenced from the manifest are SHA-256 verified before
     *       they're trusted, so accepting these two doesn't materially weaken
     *       the security posture — it just stops false positives on naive
     *       hosts.</li>
     * </ul>
     *
     * <p>Missing / blank Content-Type is allowed (some legitimate servers omit
     * it). Everything else throws.
     */
    private static void assertAcceptableJsonContentType( String contentType, URL from )
            throws IOException
    {
        if ( contentType == null || contentType.isBlank() ) return;
        String lower = contentType.toLowerCase( java.util.Locale.ROOT );
        if ( lower.startsWith( "application/json" )
                || lower.startsWith( "text/json" )
                || lower.startsWith( "application/javascript" )
                || lower.startsWith( "text/plain" )
                || lower.startsWith( "application/octet-stream" ) ) {
            return;
        }
        throw new IOException( "Unexpected Content-Type for bounded JSON fetch from "
                                       + from + ": " + contentType );
    }
}

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
import java.net.HttpURLConnection;
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

    /**
     * Optional listener for live per-file download progress. Set by the launch flow
     * (alongside {@link #retryNoticeListener}) so a large file's byte progress surfaces
     * as row sub-text — "Faithful-64x.zip — 45% · 12.3 / 27.1 MB · 2.4 MB/s" — instead of
     * a frozen "Downloading X..." line that just sits there while a big resource pack or
     * mod streams. Same volatile / cross-thread, best-effort, single-listener semantics as
     * {@link #retryNoticeListener}; cleared after launch.
     *
     * @since 2026.7
     */
    private static volatile java.util.function.Consumer< String > downloadProgressListener = null;

    /**
     * Installs a listener that fires (throttled) with a formatted live progress line for
     * the file currently downloading. Set to {@code null} to clear.
     *
     * @param listener the progress-line consumer, or {@code null} to clear
     */
    public static void setDownloadProgressListener( java.util.function.Consumer< String > listener )
    {
        downloadProgressListener = listener;
    }

    /** Minimum interval between download-progress listener fires (ms). Shared across all
     *  concurrent downloads via {@link #lastDownloadProgressFireMs} so a burst of parallel
     *  transfers can't collectively flood the UI thread. ~8 updates/sec is smooth enough. */
    private static final long DOWNLOAD_PROGRESS_MIN_INTERVAL_MS = 120;

    /** Wall-clock of the last download-progress fire; CAS-gated so only one of many
     *  concurrent download threads fires per interval. */
    private static final java.util.concurrent.atomic.AtomicLong lastDownloadProgressFireMs =
            new java.util.concurrent.atomic.AtomicLong( 0 );

    /**
     * Fires the download-progress listener (when installed) with a live line for the file
     * being received, throttled so concurrent downloads don't flood the UI. Safe to call
     * from any download thread.
     *
     * @param destination   the file being written (its name labels the line)
     * @param bytesSoFar     bytes received for this file so far
     * @param contentLength  the file's total size, or &le; 0 when the server didn't report it
     * @param tracker        the active tracker for the speed readout, or {@code null}
     */
    private static void notifyDownloadProgress( File destination, long bytesSoFar, long contentLength,
                                                DownloadTracker tracker )
    {
        java.util.function.Consumer< String > l = downloadProgressListener;
        if ( l == null ) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastDownloadProgressFireMs.get();
        if ( now - last < DOWNLOAD_PROGRESS_MIN_INTERVAL_MS ) {
            return;
        }
        // Only the thread that wins the CAS fires this interval — the rest skip, so N
        // concurrent downloads still produce ~one update per interval, not N.
        if ( !lastDownloadProgressFireMs.compareAndSet( last, now ) ) {
            return;
        }
        try {
            l.accept( formatDownloadProgress( destination, bytesSoFar, contentLength, tracker ) );
        }
        catch ( Throwable ignored ) {
            // A listener fault must never poison the download path.
        }
    }

    /**
     * Builds the live per-file progress line, e.g.
     * {@code "Faithful-64x.zip — 45% · 12.3 / 27.1 MB · 2.4 MB/s"}. Falls back to a plain
     * byte count when the server didn't send a Content-Length.
     */
    private static String formatDownloadProgress( File destination, long bytesSoFar, long contentLength,
                                                  DownloadTracker tracker )
    {
        String name = ( destination != null ) ? destination.getName() : "";
        StringBuilder sb = new StringBuilder();
        if ( !name.isEmpty() ) {
            sb.append( name ).append( " — " );
        }
        if ( contentLength > 0 ) {
            int pct = (int) Math.min( 100, ( bytesSoFar * 100 ) / contentLength );
            sb.append( pct ).append( "% · " )
              .append( formatBytes( bytesSoFar ) ).append( " / " ).append( formatBytes( contentLength ) );
        }
        else {
            sb.append( formatBytes( bytesSoFar ) );
        }
        if ( tracker != null ) {
            String speed = tracker.getFormattedSpeed();
            if ( speed != null && !speed.isEmpty() ) {
                sb.append( " · " ).append( speed );
            }
        }
        return sb.toString();
    }

    /** Human-readable byte size (e.g. {@code "27.1 MB"}, {@code "812 KB"}, {@code "945 B"}). */
    private static String formatBytes( long bytes )
    {
        if ( bytes < 1024 ) {
            return bytes + " B";
        }
        if ( bytes < 1024 * 1024 ) {
            return String.format( "%.0f KB", bytes / 1024.0 );
        }
        if ( bytes < 1024L * 1024 * 1024 ) {
            return String.format( "%.1f MB", bytes / ( 1024.0 * 1024 ) );
        }
        return String.format( "%.2f GB", bytes / ( 1024.0 * 1024 * 1024 ) );
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
     * Records evidence that the network is working: any successfully-completed real
     * request is strictly stronger proof of connectivity than the socket-connect
     * probe, so it clears a latched offline flag immediately. Without this, a
     * transiently-failed startup probe (Wi-Fi still associating, VPN mid-handshake)
     * stranded the session in offline mode — cache-only manifests, skipped
     * refreshes — even while actual downloads were succeeding, until the user
     * happened to trigger a manual re-probe or restarted the launcher.
     */
    private static void noteNetworkSuccess()
    {
        if ( offlineMode ) {
            offlineMode = false;
            Logger.logStd( LocalizationManager.get( "log.network.connectivityRestored" ) );
        }
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
     * Reliable, high-availability hosts probed by {@link #checkNetworkAvailability()},
     * in order. The first entry is the launcher's actual hard dependency (Mojang's
     * manifest host); the rest are independent global anchors (Cloudflare / Google /
     * Quad9 DNS-over-TLS endpoints) so a single host having a bad moment — Mojang
     * geo-throttling, a CDN edge hiccup, a captive-portal that blocks one IP — can't
     * by itself declare the whole launcher offline. {@code {host, port}} pairs.
     *
     * <p>History: this used to be a single socket-connect to
     * {@code launchermeta.mojang.com:443}. That one probe was the sole gate on
     * {@link #offlineMode}, so any transient miss against that single host latched
     * the launcher into offline-mode (cache-only manifests, skipped refreshes) for
     * the rest of the session even though the network and the modpack API were
     * perfectly reachable. Probing several independent hosts and only going offline
     * when ALL of them fail makes the signal reflect genuine connectivity loss
     * rather than one flaky endpoint.</p>
     */
    private static final String[][] CONNECTIVITY_PROBE_HOSTS = {
            { "launchermeta.mojang.com", "443" },
            { "1.1.1.1", "443" },          // Cloudflare DNS-over-TLS
            { "8.8.8.8", "443" },          // Google DNS-over-TLS
            { "9.9.9.9", "443" }           // Quad9 DNS-over-TLS
    };

    /**
     * Per-host connect timeout for the connectivity probe. Kept short because we try
     * several hosts and stop at the first success — in the common online case the
     * first host answers well under a second, and even a total outage resolves in
     * roughly {@code hosts * timeout} worst case.
     */
    private static final int CONNECTIVITY_PROBE_TIMEOUT_MS = 3_000;

    /**
     * Checks network connectivity by attempting a quick socket connect to each of a
     * small set of independent, high-availability hosts ({@link #CONNECTIVITY_PROBE_HOSTS}),
     * stopping at the first that answers. Sets {@link #offlineMode} accordingly and
     * returns the result.
     *
     * <p>Only declares the launcher offline when EVERY probe host fails — a single
     * flaky or throttled endpoint is no longer enough to latch offline-mode, which
     * previously stranded the session on cache-only manifests until a restart.</p>
     *
     * @return true if the network is reachable
     *
     * @since 2.0
     */
    public static boolean checkNetworkAvailability()
    {
        for ( String[] hostPort : CONNECTIVITY_PROBE_HOSTS ) {
            String host = hostPort[ 0 ];
            int port = Integer.parseInt( hostPort[ 1 ] );
            try ( java.net.Socket socket = new java.net.Socket() ) {
                socket.connect( new java.net.InetSocketAddress( host, port ), CONNECTIVITY_PROBE_TIMEOUT_MS );
                offlineMode = false;
                return true;
            }
            catch ( Exception e ) {
                // This host didn't answer — fall through and try the next anchor before
                // concluding anything. Only an all-hosts failure means offline.
            }
        }
        Logger.logWarningSilent( LocalizationManager.get( "log.network.connectivityCheckFailed" ) );
        offlineMode = true;
        return false;
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
     * <p>Delegates to the tracked variant with a {@code null} tracker so every file download —
     * tracked or not — goes through the same hardened core: bounded retries, the trickle-stall
     * watchdog, interrupt-responsive transfer loop, and atomic temp-file rename. The untracked
     * variant previously used a plain stream copy with no stall detection, which let a
     * throttled/half-dead connection (a few bytes every &lt;30&nbsp;s, never tripping the read
     * timeout) hang a launch indefinitely on a small file.</p>
     *
     * @param source      source URL
     * @param destination destination file
     *
     * @throws IOException if unable to download or save file after all retry attempts
     * @since 1.1
     */
    public static void downloadFileFromURL( URL source, File destination ) throws IOException {
        downloadFileFromURL( source, destination, ( DownloadTracker ) null );
    }

    /**
     * Buffer size for tracked downloads (8 KB).
     */
    private static final int DOWNLOAD_BUFFER_SIZE = 8192;

    /**
     * Stall-detection window for tracked downloads. The per-read {@code setReadTimeout} only
     * fires on a <em>fully silent</em> socket — a connection that trickles a few bytes every
     * &lt;30 s resets the timer forever and hangs the download thread indefinitely (observed as
     * a server-mode mod sync freezing mid-batch with no error). Every window we check the bytes
     * actually received; below the floor the attempt is aborted so the retry ladder opens a
     * fresh connection instead of riding a throttled/half-dead one.
     */
    private static final long STALL_WINDOW_MS = 60_000L;

    /**
     * Minimum bytes per stall window to count as live progress (~512 B/s average). Slow-but-real
     * connections (even sub-dial-up) clear this easily; only a genuinely wedged transfer trips it.
     */
    private static final long STALL_MIN_WINDOW_BYTES = 30_720L;

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
        downloadFileCore( source, destination, tracker, null );
    }

    /**
     * Shared download core behind every {@code downloadFileFromURL} file variant. Bounded
     * retries with exponential backoff, atomic temp-file rename, per-window trickle-stall
     * watchdog, live progress reporting, and an interrupt-responsive transfer loop so a
     * user-initiated cancel (thread interrupt) aborts an in-flight transfer at the next
     * chunk boundary instead of riding out the whole file (or hanging forever on a stalled
     * connection that never trips the read timeout).
     *
     * @param source            source URL
     * @param destination       destination file
     * @param tracker           the download tracker to report progress to, or null to skip tracking
     * @param acceptContentType value for the {@code Accept} request header, or null to omit it
     *
     * @throws IOException if unable to download or save file after all retry attempts, or if the
     *                     calling thread was interrupted mid-transfer
     */
    private static void downloadFileCore( URL source, File destination, DownloadTracker tracker,
                                          String acceptContentType ) throws IOException {
        synchronized ( getPathLock( destination ) ) {
            IOException lastException = null;
            // Register the file with the tracker exactly once for the whole retry sequence.
            // Re-registering per attempt (the old behaviour) double-counted the expected bytes
            // and leaked an activeDownloads increment on every failed try, which made the
            // aggregate byte total jump backward and the progress bar/ETA regress — reading to
            // the user as a stuck download.
            boolean registered = false;
            for ( int attempt = 1; attempt <= MAX_RETRIES; attempt++ ) {
                File tempFile = new File( destination.getAbsolutePath() + ".tmp" );
                URLConnection connection = null;
                long attemptBytes = 0;   // bytes this attempt reported to the tracker, rolled back on failure
                try {
                    connection = openConnection( source );
                    applyDefaults( connection );
                    if ( acceptContentType != null ) {
                        connection.setDoInput( true );
                        connection.setRequestProperty( "Accept", acceptContentType );
                    }
                    long contentLength = connection.getContentLengthLong();
                    if ( tracker != null && !registered ) {
                        tracker.registerDownload( contentLength );
                        registered = true;
                    }
                    try ( InputStream is = connection.getInputStream();
                          OutputStream os = new BufferedOutputStream( new FileOutputStream( tempFile ) ) ) {
                        byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
                        int bytesRead;
                        long stallWindowStartMs = System.currentTimeMillis();
                        long stallWindowBytes = 0;
                        while ( ( bytesRead = is.read( buffer ) ) != -1 ) {
                            // Cancel responsiveness: a launch cancel interrupts the download
                            // thread, but a socket read doesn't respond to interrupts — without
                            // this check a large or slow transfer keeps streaming long after the
                            // user clicked Cancel. Checking between chunks bounds the response
                            // time to one read (~read timeout worst case).
                            if ( Thread.currentThread().isInterrupted() ) {
                                throw new java.io.InterruptedIOException(
                                        "Download interrupted: " + source );
                            }
                            os.write( buffer, 0, bytesRead );
                            attemptBytes += bytesRead;
                            stallWindowBytes += bytesRead;
                            if ( tracker != null ) {
                                tracker.addBytes( bytesRead );
                            }
                            // Stall watchdog: a trickling connection (a few bytes every <30 s)
                            // never trips setReadTimeout, so without this check the download
                            // thread hangs indefinitely on a throttled/half-dead transfer.
                            // Abort the attempt so the retry ladder gets a fresh connection.
                            long nowMs = System.currentTimeMillis();
                            if ( nowMs - stallWindowStartMs >= STALL_WINDOW_MS ) {
                                if ( stallWindowBytes < STALL_MIN_WINDOW_BYTES ) {
                                    Logger.logWarningSilent( LocalizationManager.format(
                                            "log.network.downloadStalled", destination.getName(),
                                            stallWindowBytes, ( nowMs - stallWindowStartMs ) / 1000 ) );
                                    throw new IOException( "Download stalled (received " + stallWindowBytes
                                            + " bytes in " + ( ( nowMs - stallWindowStartMs ) / 1000 )
                                            + "s): " + source );
                                }
                                stallWindowStartMs = nowMs;
                                stallWindowBytes = 0;
                            }
                            // Surface live per-file byte progress so a large resource pack / mod
                            // shows a moving "name — 45% · 12/27 MB · speed" line instead of a
                            // frozen label. Throttled internally, so this is cheap per chunk.
                            notifyDownloadProgress( destination, attemptBytes, contentLength, tracker );
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
                    noteNetworkSuccess();
                    return;
                }
                catch ( IOException e ) {
                    lastException = e;
                    // Drop the partial download so a failed attempt can't be mistaken for a
                    // complete file on a later run and isn't left orphaned on disk.
                    tempFile.delete();
                    // Roll back the partial bytes this failed attempt fed the tracker so the retry
                    // re-counts them from scratch instead of double-counting into the total.
                    if ( tracker != null && attemptBytes > 0 ) {
                        tracker.addBytes( -attemptBytes );
                    }
                    // A cancel-driven interrupt must abort the whole retry ladder immediately —
                    // retrying a download the user just cancelled defeats the cancel.
                    if ( e instanceof java.io.InterruptedIOException ) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
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
                finally {
                    // Release the connection promptly. On the legacy HttpURLConnection stack a
                    // connection left undisconnected lingers until keep-alive timeout/GC, so a
                    // burst of failed concurrent downloads can exhaust the per-host pool and
                    // stall sibling downloads.
                    if ( connection instanceof HttpURLConnection httpConnection ) {
                        httpConnection.disconnect();
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
        downloadFileCore( source, destination, null, responseContentType );
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
        try {
            applyDefaults( connection );
            try ( InputStream is = connection.getInputStream() ) {
                String body = IOUtils.toString( is, StandardCharsets.UTF_8 );
                noteNetworkSuccess();
                return body;
            }
        }
        finally {
            // Release the connection promptly — on the legacy HttpURLConnection
            // stack an undisconnected connection lingers on the keep-alive pool
            // until timeout/GC, so a burst can exhaust the per-host pool.
            if ( connection instanceof HttpURLConnection httpConnection ) {
                httpConnection.disconnect();
            }
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
        try {
            applyDefaults( connection );
            connection.setDoInput( true );
            connection.setRequestProperty( "Accept", responseContentType );
            try ( InputStream is = connection.getInputStream() ) {
                String body = IOUtils.toString( is, StandardCharsets.UTF_8 );
                noteNetworkSuccess();
                return body;
            }
        }
        finally {
            // Release the connection promptly — on the legacy HttpURLConnection
            // stack an undisconnected connection lingers on the keep-alive pool
            // until timeout/GC, so a burst can exhaust the per-host pool.
            if ( connection instanceof HttpURLConnection httpConnection ) {
                httpConnection.disconnect();
            }
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
                noteNetworkSuccess();
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
                    noteNetworkSuccess();
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
                noteNetworkSuccess();
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

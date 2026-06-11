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

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import org.apache.commons.io.FileUtils;

/**
 * Class containing utility methods and other functionality that pertains to the executing system and/or operating
 * system.
 *
 * @author Mica Technologies
 * @version 1.1
 * @since 1.0
 */
public class SystemUtilities
{

    /**
     * Variable containing the client token for the current installation.
     *
     * @since 1.0
     */
    private static String clientToken = null;

    /**
     * @deprecated Use {@link ProcessUtilities#executeStringCommand(String, String)} instead.
     */
    @Deprecated
    public static void executeStringCommand( String command, String workingDirectory )
    throws IOException, InterruptedException
    {
        ProcessUtilities.executeStringCommand( command, workingDirectory );
    }

    /**
     * @deprecated Use {@link ProcessUtilities#launchCommand(String, String)} instead.
     */
    @Deprecated
    public static Process launchCommand( String command, String workingDirectory ) throws IOException
    {
        return ProcessUtilities.launchCommand( command, workingDirectory );
    }

    /**
     * @deprecated Use {@link ProcessUtilities#waitForProcess(Process)} instead.
     */
    @Deprecated
    public static void waitForProcess( Process process ) throws InterruptedException
    {
        ProcessUtilities.waitForProcess( process );
    }

    /** Per-entry decompressed-size ceiling for {@link #extractJarFile}. 64 MB is well above
     *  any legitimate native library or asset; raises a zip-bomb floor without blocking
     *  Mojang's largest natives. */
    private static final long MAX_EXTRACT_ENTRY_BYTES = 64L * 1024 * 1024;

    /** Cumulative decompressed-size ceiling for {@link #extractJarFile}. 256 MB lets us
     *  process the biggest legitimate library-bundle JARs without bowing to a malicious
     *  archive that streams gigabytes of zero-padded entries. */
    private static final long MAX_EXTRACT_TOTAL_BYTES = 256L * 1024 * 1024;

    /** Windows reserved device names. NTFS rejects creating files at these names, but the
     *  rejection happens late (after a partial path write) on some configurations, so we
     *  filter them ourselves to fail fast and to keep behaviour symmetrical with POSIX
     *  extract destinations. Compared case-insensitively against the base of each path
     *  segment (the part before any extension). */
    private static final Set< String > WINDOWS_RESERVED_NAMES = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9" );

    /**
     * Extracts the specified source JAR to the destination directory. Skips
     * {@code META-INF} entries (signatures aren't preserved across re-pack).
     *
     * <p>Hardened against archive-driven attacks:
     * <ul>
     *   <li><b>Zip-slip:</b> the resolved destination of every entry must reside
     *       under the base destination directory. Entry names containing
     *       {@code ..}, absolute paths, or path separators that escape the base
     *       are rejected with a {@link ModpackException}.</li>
     *   <li><b>NUL bytes:</b> any embedded {@code \0} in an entry name fails fast —
     *       these are typically a Windows-vs-Unix path-handling exploit.</li>
     *   <li><b>Windows reserved names:</b> entries named after device files
     *       (CON, PRN, NUL, COM1-9, LPT1-9) are skipped on every platform so
     *       behaviour is portable.</li>
     *   <li><b>Zip-bomb caps:</b> {@link #MAX_EXTRACT_ENTRY_BYTES} per entry and
     *       {@link #MAX_EXTRACT_TOTAL_BYTES} cumulative across the archive. The
     *       per-entry guard streams the count rather than trusting
     *       {@code JarEntry.getSize()}, which is attacker-controlled.</li>
     * </ul>
     *
     * @param source      extract from
     * @param destination extract to (treated as the containment root)
     */
    public static void extractJarFile( JarFile source, String destination ) throws ModpackException
    {
        final Path baseDir = Path.of( destination ).toAbsolutePath().normalize();
        long cumulativeBytes = 0L;

        Enumeration< JarEntry > jarFileFiles = source.entries();
        while ( jarFileFiles.hasMoreElements() ) {
            JarEntry jarFileFile = jarFileFiles.nextElement();
            String entryName = jarFileFile.getName();

            // Skip META-INF file(s)
            if ( entryName.contains( "META-INF" ) ) {
                continue;
            }

            // Reject NUL bytes outright.
            if ( entryName.indexOf( '\0' ) >= 0 ) {
                throw new ModpackException( "Refusing JAR entry containing NUL byte: " + entryName );
            }

            // Reject absolute paths in entry names. Some JAR producers emit them; nothing
            // we trust does. An absolute path would escape the base dir even after
            // normalization on platforms where Path.resolve(absolute) replaces the base.
            if ( entryName.startsWith( "/" ) || entryName.startsWith( "\\" )
                    || ( entryName.length() >= 2 && entryName.charAt( 1 ) == ':' ) ) {
                throw new ModpackException( "Refusing absolute JAR entry path: " + entryName );
            }

            // Resolve under base and verify containment. Path.normalize collapses ".."
            // segments first; startsWith confirms the resolved target is inside baseDir.
            Path target = baseDir.resolve( entryName ).normalize();
            if ( !target.startsWith( baseDir ) ) {
                throw new ModpackException( "Refusing JAR entry that escapes base dir: " + entryName );
            }

            // Reject Windows reserved device names on every platform so behaviour is
            // portable. Compare the basename (case-insensitive, extension-stripped).
            String basename = target.getFileName() == null ? "" : target.getFileName().toString();
            int dot = basename.lastIndexOf( '.' );
            String stem = dot >= 0 ? basename.substring( 0, dot ) : basename;
            if ( WINDOWS_RESERVED_NAMES.contains( stem.toLowerCase( Locale.ROOT ) ) ) {
                Logger.logWarningSilent( "Skipping reserved-name JAR entry: " + entryName );
                continue;
            }

            File extractedJarFileFile = SynchronizedFileManager.getSynchronizedFile( target.toString() );

            // Directory entries
            if ( jarFileFile.isDirectory() ) {
                //noinspection ResultOfMethodCallIgnored
                extractedJarFileFile.mkdirs();
                continue;
            }

            // Make sure the parent folders exist
            //noinspection ResultOfMethodCallIgnored
            extractedJarFileFile.getParentFile().mkdirs();

            // Stream content with a per-entry counter so we catch zip bombs even if
            // JarEntry.getSize() lies (uncompressed size is attacker-controlled metadata).
            //
            // Write to a sibling temp file + fsync + atomic-move, never directly to
            // the final path. The natives extraction overwrites lwjgl64.dll (and
            // friends) every launch; a partial write from an interrupted launch,
            // AV-scan interference, or a concurrent re-extract leaves a truncated
            // DLL on disk, and Windows LoadLibrary on a partial DLL returns
            // "A dynamic link library (DLL) initialization routine failed" with
            // process exit code 0xC0000005 (access violation). Atomic-move means
            // the destination either has the previous-good DLL or the new-complete
            // DLL — never an in-between state.
            long entryBytes = 0L;
            Path targetPath = extractedJarFileFile.toPath();
            Path tempPath = targetPath.resolveSibling(
                    targetPath.getFileName() + ".tmp-" + UUID.randomUUID() );
            try ( InputStream inputStream = source.getInputStream( jarFileFile );
                  FileOutputStream fileOutputStream = new FileOutputStream( tempPath.toFile() ) ) {
                byte[] buffer = new byte[ 8192 ];
                int bytesRead;
                while ( ( bytesRead = inputStream.read( buffer ) ) != -1 ) {
                    entryBytes += bytesRead;
                    if ( entryBytes > MAX_EXTRACT_ENTRY_BYTES ) {
                        throw new ModpackException(
                                "JAR entry exceeds per-entry size cap (" + MAX_EXTRACT_ENTRY_BYTES
                                        + " bytes): " + entryName );
                    }
                    cumulativeBytes += bytesRead;
                    if ( cumulativeBytes > MAX_EXTRACT_TOTAL_BYTES ) {
                        throw new ModpackException(
                                "JAR extraction exceeded total size cap (" + MAX_EXTRACT_TOTAL_BYTES
                                        + " bytes) at entry: " + entryName );
                    }
                    fileOutputStream.write( buffer, 0, bytesRead );
                }
                // Flush to disk before the atomic move so a power-loss / kernel
                // panic between the move and the next launch can't reveal an
                // empty file. FileChannel.force(true) syncs both content and
                // metadata.
                fileOutputStream.getFD().sync();
            }
            catch ( IOException e ) {
                // Best-effort cleanup of the temp file so we don't litter the
                // natives folder with .tmp-<uuid> files after a failed extract.
                try { Files.deleteIfExists( tempPath ); }
                catch ( IOException ignored ) { /* surface the original cause */ }
                throw new ModpackException( "Unable to read file from jar during extraction.", e );
            }

            // Atomic publish. On Windows, ATOMIC_MOVE + REPLACE_EXISTING works
            // within the same volume (which this always is — temp is a sibling
            // of the target). On filesystems that genuinely don't support
            // atomic moves we fall back to plain replace; the resulting window
            // is no worse than the previous direct-write behavior.
            try {
                Files.move( tempPath, targetPath,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING );
            }
            catch ( AtomicMoveNotSupportedException atomicEx ) {
                try {
                    Files.move( tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING );
                }
                catch ( IOException moveEx ) {
                    try { Files.deleteIfExists( tempPath ); }
                    catch ( IOException ignored ) { /* surface the original cause */ }
                    throw new ModpackException(
                            "Unable to publish extracted JAR entry: " + entryName, moveEx );
                }
            }
            catch ( IOException moveEx ) {
                try { Files.deleteIfExists( tempPath ); }
                catch ( IOException ignored ) { /* surface the original cause */ }
                throw new ModpackException(
                        "Unable to publish extracted JAR entry: " + entryName, moveEx );
            }
        }
    }

    /**
     * Executes the specified runnable task on a newly created DAEMON thread.
     *
     * <p>Daemon-by-default is deliberate: the launcher calls
     * {@code Platform.setImplicitExit(false)} in several code paths so the JVM
     * stays alive when the last JavaFX window closes (e.g. while a game is
     * running). A non-daemon background thread spawned via this helper would,
     * combined with that flag, keep the JVM alive indefinitely after the user
     * closes the launcher — orphaned stale process per IDE-force-stop or
     * abnormal exit.
     *
     * <p>Daemon threads are torn down automatically when the JVM exits, so
     * normal {@code System.exit()} still works and orphan processes can't
     * accumulate even if the in-flight task is mid-HTTP-read.
     *
     * <p>Most callers want background fire-and-forget behavior anyway — there
     * is no expectation that the work outlives the launcher. The few cases
     * that genuinely need a non-daemon (e.g. the actual game launch process)
     * spawn their own threads directly.
     *
     * @param runnable task
     *
     * @since 1.1
     */
    public static void spawnNewTask( Runnable runnable ) {
        try {
            backgroundExecutor().execute( runnable );
        }
        catch ( java.util.concurrent.RejectedExecutionException ree ) {
            // The pool was shut down (e.g. mid-cleanup, or a task submitted in the
            // narrow window between shutdownBackgroundExecutor() and the next
            // backgroundExecutor() recreation) and rejected the task. backgroundExecutor()
            // already recreates a shut-down pool on the next call, so this should be
            // vanishingly rare — but rather than silently drop the work, run it inline
            // as a last resort. Worst case is the caller's thread blocks for the task's
            // duration instead of fanning out.
            Logger.logWarningSilent( "Background executor rejected a task; running it inline as a fallback." );
            try {
                runnable.run();
            }
            catch ( Throwable t ) {
                Logger.logThrowable( t );
            }
        }
    }

    /**
     * Shared cached thread pool backing {@link #spawnNewTask}. A cached pool reuses
     * idle worker threads for the brief, fire-and-forget tasks the launcher fans out
     * across the GUI and background-fetch paths instead of constructing a fresh
     * {@code java.lang.Thread} per call. Idle threads are reaped after 60s, so steady-
     * state thread count drops back to ~0 between bursts; a burst of N concurrent
     * tasks (cold-start manifest fetch fanout, parallel image prefetch) still grows
     * the pool as needed up to JVM concurrency limits, but a long-tail of single-fire
     * Runnables no longer churns one OS thread apiece.
     *
     * <p>All workers are daemon threads named {@code mica-bg-N} so they don't keep the
     * JVM alive past launcher exit and are easy to find in a thread dump.</p>
     *
     * <p><b>Lazily (re)created.</b> The field is no longer {@code final} because the
     * launcher's {@code while(restartFlag)} restart loop runs {@code cleanupApp()} —
     * which calls {@link #shutdownBackgroundExecutor(long)} — between sessions. A
     * {@code final} pool stayed shut down forever after the first in-process restart,
     * so every {@code spawnNewTask} in the restarted session threw
     * {@code RejectedExecutionException} and silently disabled image caching, auth
     * renewal, Play actions, etc. {@link #backgroundExecutor()} now rebuilds the pool
     * on demand whenever it is {@code null} or already shut down.</p>
     */
    private static volatile java.util.concurrent.ExecutorService backgroundExecutor;

    /** Monotonic worker-thread sequence, kept outside the per-pool {@link java.util.concurrent.ThreadFactory}
     *  so names stay unique across pool generations (a restart that recreates the
     *  pool continues numbering rather than colliding {@code mica-bg-1} twice). */
    private static final java.util.concurrent.atomic.AtomicInteger BG_THREAD_SEQ =
            new java.util.concurrent.atomic.AtomicInteger( 0 );

    /**
     * Returns the shared background executor, lazily constructing it on first use and
     * reconstructing it if a prior {@link #shutdownBackgroundExecutor(long)} (e.g. from
     * a restart's {@code cleanupApp()}) left the previous pool shut down. Synchronized so
     * two threads racing on the first/post-restart call can't build two pools.
     */
    private static synchronized java.util.concurrent.ExecutorService backgroundExecutor() {
        if ( backgroundExecutor == null || backgroundExecutor.isShutdown() ) {
            backgroundExecutor = java.util.concurrent.Executors.newCachedThreadPool( r -> {
                Thread t = new Thread( r, "mica-bg-" + BG_THREAD_SEQ.incrementAndGet() );
                t.setDaemon( true );
                return t;
            } );
        }
        return backgroundExecutor;
    }

    /**
     * Shuts down the shared background-task executor with a bounded wait so any
     * in-flight tasks (a manifest fetch that's about to write its cache, a logger
     * flush) get a chance to land before the process tears down. Best-effort —
     * the timeout caps how long the launcher will wait, after which we move on
     * and let daemon-thread semantics clean up whatever is still running.
     *
     * <p>Safe to call on the restart path as well as on exit: the pool is recreated
     * on demand by {@link #backgroundExecutor()} the next time {@link #spawnNewTask}
     * runs, so a restarted session gets a fresh, live pool.</p>
     *
     * <p>Public; called from {@link com.micatechnologies.minecraft.launcher.LauncherCore}
     * on app shutdown.</p>
     *
     * @param awaitMillis milliseconds to wait for graceful completion
     */
    public static void shutdownBackgroundExecutor( long awaitMillis ) {
        java.util.concurrent.ExecutorService pool = backgroundExecutor;
        if ( pool == null ) {
            return;
        }
        pool.shutdown();
        try {
            if ( !pool.awaitTermination( awaitMillis,
                                         java.util.concurrent.TimeUnit.MILLISECONDS ) ) {
                Logger.logWarningSilent( "Background executor did not drain within "
                                                 + awaitMillis + "ms — proceeding with shutdown anyway." );
                pool.shutdownNow();
            }
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /**
     * Builds a file path using the specified file path parts.
     *
     * @param parts file path parts
     *
     * @return built file path
     *
     * @since 1.1
     */
    public static String buildFilePath( String... parts ) {
        StringBuilder stringBuilder = new StringBuilder();
        final var iterator = Arrays.stream( parts ).iterator();
        while ( iterator.hasNext() ) {
            stringBuilder.append( iterator.next() );
            if ( iterator.hasNext() ) {
                stringBuilder.append( File.separator );
            }
        }
        return stringBuilder.toString();
    }

    /**
     * @deprecated Use {@link VersionUtilities#compareVersionNumbers(String, String)} instead.
     */
    @Deprecated
    public static int compareVersionNumbers( String version1, String version2 )
    {
        return VersionUtilities.compareVersionNumbers( version1, version2 );
    }

    /**
     * Gets the client token that is applicable for the current installation.
     *
     * @return client token
     *
     * @since 1.0
     */
    public static String getClientToken() {
        if ( clientToken == null ) {
            Logger.logDebug( LocalizationManager.CLIENT_TOKEN_CHECKING_TEXT );

            // Attempt to read client token from saved file
            final File clientTokenFile = SynchronizedFileManager.getSynchronizedFile(
                    LocalPathManager.getClientTokenFilePath() );
            if ( clientTokenFile.isFile() ) {
                try {
                    clientToken = FileUtils.readFileToString( clientTokenFile, FileUtilities.persistenceCharset );
                }
                catch ( Exception e ) {
                    clientToken = null;
                    Logger.logError( LocalizationManager.UNABLE_READ_STORED_CLIENT_TOKEN_TEXT );
                    Logger.logThrowable( e );
                }
            }

            // Generate new client token if unable to load from saved file and save to file
            if ( clientToken == null ) {
                clientToken = UUID.randomUUID().toString();
                Logger.logStd( LocalizationManager.NEW_CLIENT_TOKEN_TEXT + " " + clientToken );
                try {
                    FileUtils.writeStringToFile( clientTokenFile, clientToken, FileUtilities.persistenceCharset );
                    Logger.logDebug( LocalizationManager.STORED_CLIENT_TOKEN_TEXT );
                }
                catch ( Exception e ) {
                    Logger.logError( LocalizationManager.UNABLE_SAVE_CLIENT_TOKEN_TEXT );
                    Logger.logThrowable( e );
                }
            }

            if ( clientToken != null ) {
                Logger.logDebug( LocalizationManager.LOADED_CLIENT_TOKEN_TEXT + " " + clientToken );
            }
        }

        return clientToken;
    }
}

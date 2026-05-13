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
import java.nio.file.Path;
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
            long entryBytes = 0L;
            try ( InputStream inputStream = source.getInputStream( jarFileFile );
                  FileOutputStream fileOutputStream = new FileOutputStream( extractedJarFileFile ) ) {
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
            }
            catch ( IOException e ) {
                throw new ModpackException( "Unable to read file from jar during extraction.", e );
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
        Thread t = new Thread( runnable );
        t.setDaemon( true );
        t.start();
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

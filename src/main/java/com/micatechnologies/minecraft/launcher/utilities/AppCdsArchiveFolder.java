/*
 * Copyright (c) 2026 Mica Technologies
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Makes sure the folder for the application class-data archive exists before the JVM needs it.
 *
 * <p>The packaged launcher runs with {@code -XX:SharedArchiveFile=<path>} and
 * {@code -XX:+AutoCreateSharedArchive}: when the archive is missing or stale, the JVM writes a
 * new one as it exits. If it cannot create that file, HotSpot (26.0.2) logs the error and then
 * seeks on the file descriptor it never got; on Windows the C runtime treats that as an invalid
 * parameter and kills the process with {@code 0xC0000409}. That is how every launcher exit
 * crashed while the archive sat in the read-only install folder under Program Files. The archive
 * now lives under the user's local app data, and the JVM does not create missing folders, so this
 * creates the folder at the start of every launch, before any path that can exit early (the
 * single-instance hand-off, the {@code --mcp} relay) gets the chance to.
 *
 * <p>The path arrives as the {@value #ARCHIVE_PROPERTY} system property, set from the same build
 * property as the JVM flag, so reading it costs nothing at startup. Runs before the locale is
 * resolved, so it reports to {@link StartupDiagnostics} in English, as everything that early does.
 *
 * @since 2026.10
 */
public final class AppCdsArchiveFolder
{
    /** System property carrying the archive path the JVM was given. */
    static final String ARCHIVE_PROPERTY = "mica.appcds.archive";

    /** Name of the archive the launcher used to keep in its install folder. */
    static final String LEGACY_ARCHIVE_NAME = "launcher.jsa";

    private AppCdsArchiveFolder() { /* static-only */ }

    /**
     * Creates the archive's folder if the JVM was given one, and removes a copy left in the
     * install folder by an earlier per-user install. Never throws.
     */
    public static void ensureExists()
    {
        Optional< Path > archive = archivePath( System.getProperty( ARCHIVE_PROPERTY ) );
        if ( archive.isEmpty() ) {
            return;
        }
        Path folder = archive.get().getParent();
        try {
            Files.createDirectories( folder );
            if ( !Files.isWritable( folder ) ) {
                StartupDiagnostics.record( "The class-data archive folder is not writable, so the JVM may "
                                           + "crash as it exits: " + folder );
            }
        }
        catch ( IOException | RuntimeException e ) {
            StartupDiagnostics.record( "Could not create the class-data archive folder " + folder
                                       + "; the JVM may crash as it exits", e );
        }
        removeLegacyArchive( archive.get() );
    }

    /**
     * Resolves the archive path from the system property's value.
     *
     * @param value the property's value, or {@code null} when it is not set
     *
     * @return the absolute archive path, or empty when there is none or it is not a usable path
     */
    static Optional< Path > archivePath( String value )
    {
        if ( value == null || value.isBlank() || value.contains( "$" ) ) {
            // A '$' means the packaged launcher did not expand one of its macros.
            return Optional.empty();
        }
        try {
            Path path = Path.of( value.strip() );
            return path.isAbsolute() && path.getParent() != null ? Optional.of( path ) : Optional.empty();
        }
        catch ( InvalidPathException e ) {
            return Optional.empty();
        }
    }

    /**
     * Where the archive used to be kept: {@code app\launcher.jsa} beside the packaged Windows
     * executable.
     *
     * @param appPath the packaged executable, from {@code jpackage.app-path}, or {@code null}
     * @param osName  the {@code os.name} system property
     *
     * @return the old archive's path, or empty off a packaged Windows install
     */
    static Optional< Path > legacyArchivePath( String appPath, String osName )
    {
        if ( appPath == null || appPath.isBlank() || osName == null
             || !osName.toLowerCase( Locale.ROOT ).startsWith( "windows" ) ) {
            return Optional.empty();
        }
        try {
            Path root = Path.of( appPath ).getParent();
            return root == null ? Optional.empty() : Optional.of( root.resolve( "app" ).resolve( LEGACY_ARCHIVE_NAME ) );
        }
        catch ( InvalidPathException e ) {
            return Optional.empty();
        }
    }

    /**
     * Deletes the old in-install archive, which a per-user install wrote and nothing reads any
     * more. Left alone it is 40 MB the uninstaller does not know about, and it keeps the install
     * folder from being removed.
     */
    private static void removeLegacyArchive( Path current )
    {
        Optional< Path > legacy = legacyArchivePath( System.getProperty( "jpackage.app-path" ),
                                                     System.getProperty( "os.name" ) );
        if ( legacy.isEmpty() || legacy.get().equals( current ) ) {
            return;
        }
        try {
            if ( Files.deleteIfExists( legacy.get() ) ) {
                StartupDiagnostics.record( "Removed the old class-data archive " + legacy.get() );
            }
        }
        catch ( IOException | RuntimeException ignored ) {
            // Read-only per-machine folder, or in use: harmless either way.
        }
    }
}

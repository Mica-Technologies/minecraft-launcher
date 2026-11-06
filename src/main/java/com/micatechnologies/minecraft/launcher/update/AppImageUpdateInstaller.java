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

package com.micatechnologies.minecraft.launcher.update;

import com.micatechnologies.minecraft.launcher.files.LocalPathManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs a staged update over the running AppImage, and keeps the previous one until the new
 * version has started (plan decision D19).
 *
 * <p>Linux lets a running AppImage be replaced: the running copy keeps reading its own mounted
 * image. So the launcher swaps the file itself and the native starter only rolls back. The swap
 * never leaves a moment without a launcher file: the previous file is kept as
 * {@code <file>.previous} (a hard link, or a copy where the file system has none), then the new
 * file is renamed over the old name in one step.
 *
 * <p>Each swap is recorded in {@value #APPLIED_MARKER} in the updates folder, as {@code key=value}
 * lines: {@code version} (the new one), {@code from}, {@code appimage} and {@code previous}
 * (absolute paths). The new version deletes the record and {@code .previous} once it shows a
 * window ({@link #confirmStarted(String)}). If it exits with an error before that, the starter
 * renames {@code .previous} back over {@code appimage}, deletes the record, and starts it.
 *
 * @since 2026.11
 */
public final class AppImageUpdateInstaller
{
    /** Name of the record of an installed but not yet started update, in the updates folder. */
    public static final String APPLIED_MARKER = "applied-update.properties";

    /** What {@link #install} recorded, as read back from {@value #APPLIED_MARKER}. */
    public record AppliedUpdate( String version, String from, Path appImage, Path previous )
    {
    }

    /** Set once this process has asked to confirm its own start. */
    private static final AtomicBoolean CONFIRM_REQUESTED = new AtomicBoolean();

    private final Path updatesFolder;
    private final UpdateStager stager;

    /**
     * An installer working with the given updates folder.
     *
     * @param updatesFolder where the staged update and the record live
     * @param stager        the stager that staged the update, to verify and clear it
     *
     * @since 2026.11
     */
    public AppImageUpdateInstaller( Path updatesFolder, UpdateStager stager )
    {
        this.updatesFolder = updatesFolder;
        this.stager = stager;
    }

    /**
     * The installer the launcher uses.
     *
     * @return the installer for the client-mode updates folder
     *
     * @since 2026.11
     */
    public static AppImageUpdateInstaller standard()
    {
        return new AppImageUpdateInstaller( Path.of( LocalPathManager.getLauncherUpdatesFolderPath() ),
                                            UpdateStager.standard() );
    }

    /**
     * Replaces {@code appImage} with the staged update, keeping the current file as
     * {@code <file>.previous}. The running launcher is unaffected; the new version runs from the
     * next start. On any failure the current file stays in place.
     *
     * @param staged         the staged update; must be for {@link InstallKind#LINUX_APPIMAGE}
     * @param appImage       the AppImage the user starts ({@code $APPIMAGE})
     * @param runningVersion the version running now, recorded for the rollback message
     *
     * @throws IOException when the update does not verify or a file operation fails
     * @since 2026.11
     */
    public void install( StagedUpdate staged, Path appImage, String runningVersion ) throws IOException
    {
        if ( staged.kind() != InstallKind.LINUX_APPIMAGE ) {
            throw new IOException( "Not an AppImage update: " + staged.kind() );
        }
        if ( !stager.verify( staged ) ) {
            throw new UpdateStager.ChecksumMismatchException( "The staged update no longer matches its checksum" );
        }
        Path target = appImage.toAbsolutePath().normalize();
        if ( !Files.isRegularFile( target ) || target.getParent() == null ) {
            throw new IOException( "Not an AppImage file: " + target );
        }
        String name = target.getFileName().toString();
        Path incoming = target.resolveSibling( name + ".new" );
        Path previous = target.resolveSibling( name + ".previous" );

        Files.copy( staged.file(), incoming, StandardCopyOption.REPLACE_EXISTING );
        try {
            makeExecutable( incoming );
            keepPrevious( target, previous );
            writeRecord( new AppliedUpdate( staged.version(), runningVersion, target, previous ) );
            Files.move( incoming, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
        }
        catch ( IOException | RuntimeException e ) {
            Files.deleteIfExists( incoming );
            Files.deleteIfExists( updatesFolder.resolve( APPLIED_MARKER ) );
            throw e;
        }
        stager.clear();
    }

    /**
     * Called once a version has shown a window: if it is the update {@link #install} recorded,
     * the update worked, so the previous AppImage and the record are deleted. Does nothing
     * otherwise, including when the version running is the previous one.
     *
     * @param runningVersion the version running now
     *
     * @return {@code true} when a recorded update was confirmed
     *
     * @since 2026.11
     */
    public boolean confirmStarted( String runningVersion )
    {
        Optional< AppliedUpdate > applied = applied();
        if ( applied.isEmpty() || !applied.get().version().equals( runningVersion ) ) {
            return false;
        }
        try {
            Files.deleteIfExists( applied.get().previous() );
            Files.deleteIfExists( updatesFolder.resolve( APPLIED_MARKER ) );
            return true;
        }
        catch ( IOException e ) {
            return false;
        }
    }

    /**
     * {@link #confirmStarted(String)} for the running launcher, once per process and off the
     * calling thread; does nothing outside an AppImage. Called whenever a launcher window shows.
     *
     * @param runningVersion the version running now
     *
     * @since 2026.11
     */
    public static void confirmStartedOnce( String runningVersion )
    {
        String appImage = System.getenv( "APPIMAGE" );
        if ( appImage == null || appImage.isBlank() || !CONFIRM_REQUESTED.compareAndSet( false, true ) ) {
            return;
        }
        Thread confirm = new Thread( () -> standard().confirmStarted( runningVersion ), "AppImage-Update-Confirm" );
        confirm.setDaemon( true );
        confirm.start();
    }

    /**
     * The installed but not yet confirmed update, if one is recorded.
     *
     * @return the record, or empty when there is none or it is incomplete
     *
     * @since 2026.11
     */
    public Optional< AppliedUpdate > applied()
    {
        Path marker = updatesFolder.resolve( APPLIED_MARKER );
        if ( !Files.isRegularFile( marker ) ) {
            return Optional.empty();
        }
        Map< String, String > values = new HashMap<>();
        try {
            for ( String line : Files.readAllLines( marker, StandardCharsets.UTF_8 ) ) {
                int eq = line.indexOf( '=' );
                if ( eq > 0 ) {
                    values.put( line.substring( 0, eq ), line.substring( eq + 1 ) );
                }
            }
            String version = values.get( "version" );
            String from = values.get( "from" );
            String appImage = values.get( "appimage" );
            String previous = values.get( "previous" );
            if ( version == null || from == null || appImage == null || previous == null ) {
                return Optional.empty();
            }
            return Optional.of( new AppliedUpdate( version, from, Path.of( appImage ), Path.of( previous ) ) );
        }
        catch ( IOException | RuntimeException e ) {
            return Optional.empty();
        }
    }

    /**
     * Keeps the current file under the previous name without ever removing the current one: a
     * hard link where the file system supports it (instant, no extra space), else a copy (exFAT,
     * common on SD cards, has no hard links).
     */
    private static void keepPrevious( Path current, Path previous ) throws IOException
    {
        Files.deleteIfExists( previous );
        try {
            Files.createLink( previous, current );
        }
        catch ( IOException | UnsupportedOperationException e ) {
            Files.copy( current, previous, StandardCopyOption.REPLACE_EXISTING );
        }
    }

    private static void makeExecutable( Path file ) throws IOException
    {
        try {
            Files.setPosixFilePermissions( file, PosixFilePermissions.fromString( "rwxr-xr-x" ) );
        }
        catch ( UnsupportedOperationException e ) {
            // Not a POSIX file system (exFAT mounts mark every file executable anyway).
            if ( !file.toFile().setExecutable( true, false ) && !Files.isExecutable( file ) ) {
                throw new IOException( "Cannot make " + file + " executable" );
            }
        }
    }

    private void writeRecord( AppliedUpdate applied ) throws IOException
    {
        String text = "version=" + oneLine( applied.version() ) + "\n"
                      + "from=" + oneLine( applied.from() ) + "\n"
                      + "appimage=" + oneLine( applied.appImage().toString() ) + "\n"
                      + "previous=" + oneLine( applied.previous().toString() ) + "\n";
        Files.createDirectories( updatesFolder );
        Path temp = updatesFolder.resolve( APPLIED_MARKER + ".tmp" );
        Files.writeString( temp, text, StandardCharsets.UTF_8 );
        Files.move( temp, updatesFolder.resolve( APPLIED_MARKER ), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE );
    }

    private static String oneLine( String value ) throws IOException
    {
        if ( value.indexOf( '\n' ) >= 0 || value.indexOf( '\r' ) >= 0 ) {
            throw new IOException( "Line break in an update record value" );
        }
        return value;
    }
}

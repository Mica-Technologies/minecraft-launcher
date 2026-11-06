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
import com.micatechnologies.minecraft.launcher.utilities.DownloadTracker;
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Downloads a launcher update into the updates folder, checks it against the SHA-256 GitHub
 * reported, and records it in a marker file for whatever installs it: the native starter on Linux,
 * a helper on Windows and macOS. At most one update is kept; staging another removes the first.
 *
 * <p>The marker, {@value #MARKER_NAME}, is plain {@code key=value} lines ({@code version},
 * {@code kind}, {@code file}, {@code sha256}) so the C starter can read it without a JSON parser.
 * {@code file} is a bare file name inside the updates folder, never a path, so nothing needs
 * escaping and the marker cannot point outside the folder.
 *
 * @since 2026.11
 */
public final class UpdateStager
{
    /** Name of the marker file in the updates folder. */
    public static final String MARKER_NAME = "staged-update.properties";

    /** Downloads a URL to a file, reporting progress; {@link NetworkUtilities} in production. */
    @FunctionalInterface
    public interface Downloader
    {
        /**
         * Downloads {@code url} to {@code destination}.
         *
         * @param url         the source
         * @param destination the file to write
         * @param tracker     progress sink, or {@code null}
         *
         * @throws IOException when the download fails or is interrupted
         */
        void download( String url, File destination, DownloadTracker tracker ) throws IOException;
    }

    /** Thrown when a download does not match the checksum the release gave for it. */
    public static final class ChecksumMismatchException extends IOException
    {
        /**
         * @param message what did not match
         */
        public ChecksumMismatchException( String message )
        {
            super( message );
        }
    }

    private final Path folder;
    private final Downloader downloader;

    /**
     * A stager working in the given folder.
     *
     * @param folder     the updates folder
     * @param downloader how files are fetched
     *
     * @since 2026.11
     */
    public UpdateStager( Path folder, Downloader downloader )
    {
        this.folder = folder;
        this.downloader = downloader;
    }

    /**
     * The stager the launcher uses: the client-mode updates folder and the shared downloader, with
     * its retries, stall watchdog and cancel-by-interrupt.
     *
     * @return the stager
     *
     * @since 2026.11
     */
    public static UpdateStager standard()
    {
        return new UpdateStager( Path.of( LocalPathManager.getLauncherUpdatesFolderPath() ),
                                 NetworkUtilities::downloadFileFromURL );
    }

    /**
     * Downloads and verifies the update, unless the same file is already staged and intact.
     * Interrupting the calling thread cancels the download.
     *
     * @param version the release's version
     * @param kind    the install kind the file is for
     * @param asset   the release file; must carry a SHA-256
     * @param tracker progress sink, or {@code null}
     *
     * @return the staged update
     *
     * @throws ChecksumMismatchException when the download does not match; nothing stays staged
     * @throws IOException               when the download or a file operation fails
     * @since 2026.11
     */
    public StagedUpdate stage( String version, InstallKind kind, ReleaseAsset asset, DownloadTracker tracker )
            throws IOException
    {
        if ( asset.sha256() == null ) {
            throw new IOException( "The release gives no checksum for " + asset.name() );
        }
        Path file = fileIn( asset.name() );
        Optional< StagedUpdate > existing = staged();
        if ( existing.isPresent() && existing.get().version().equals( version ) && existing.get().kind() == kind
             && existing.get().file().equals( file ) && existing.get().sha256().equals( asset.sha256() )
             && verify( existing.get() ) ) {
            return existing.get();
        }

        clear();
        Files.createDirectories( folder );
        downloader.download( asset.url(), file.toFile(), tracker );
        String actual = HashUtilities.getFileSHA256( file.toFile() );
        if ( actual == null || !actual.equalsIgnoreCase( asset.sha256() ) ) {
            Files.deleteIfExists( file );
            throw new ChecksumMismatchException(
                    asset.name() + ": expected SHA-256 " + asset.sha256() + ", downloaded " + actual );
        }

        StagedUpdate staged = new StagedUpdate( version, kind, file, asset.sha256().toLowerCase( Locale.ROOT ) );
        writeMarker( staged );
        return staged;
    }

    /**
     * The update waiting in the updates folder, if the marker is present, well formed and its file
     * exists. Does not re-hash the file; see {@link #verify(StagedUpdate)}.
     *
     * @return the staged update, or empty
     *
     * @since 2026.11
     */
    public Optional< StagedUpdate > staged()
    {
        Path marker = folder.resolve( MARKER_NAME );
        if ( !Files.isRegularFile( marker ) ) {
            return Optional.empty();
        }
        Map< String, String > values = new LinkedHashMap<>();
        try {
            List< String > lines = Files.readAllLines( marker, StandardCharsets.UTF_8 );
            for ( String line : lines ) {
                int eq = line.indexOf( '=' );
                if ( eq > 0 ) {
                    values.put( line.substring( 0, eq ), line.substring( eq + 1 ) );
                }
            }
        }
        catch ( IOException e ) {
            return Optional.empty();
        }
        String version = values.get( "version" );
        String kindName = values.get( "kind" );
        String fileName = values.get( "file" );
        String sha256 = values.get( "sha256" );
        if ( version == null || kindName == null || fileName == null || sha256 == null ) {
            return Optional.empty();
        }
        InstallKind kind;
        Path file;
        try {
            kind = InstallKind.valueOf( kindName );
            file = fileIn( fileName );
        }
        catch ( IllegalArgumentException | IOException e ) {
            return Optional.empty();
        }
        if ( !Files.isRegularFile( file ) ) {
            return Optional.empty();
        }
        return Optional.of( new StagedUpdate( version, kind, file, sha256 ) );
    }

    /**
     * Whether the staged file still hashes to its recorded SHA-256.
     *
     * @param staged the staged update
     *
     * @return {@code true} when it is intact
     *
     * @since 2026.11
     */
    public boolean verify( StagedUpdate staged )
    {
        String actual = HashUtilities.getFileSHA256( staged.file().toFile() );
        return actual != null && actual.equalsIgnoreCase( staged.sha256() );
    }

    /**
     * Removes the staged update from the updates folder: the marker first, so an interrupted clear
     * never leaves a marker pointing at a half-deleted file. The record of an already installed
     * update ({@link AppImageUpdateInstaller#APPLIED_MARKER}) stays.
     *
     * @throws IOException when a file cannot be removed
     * @since 2026.11
     */
    public void clear() throws IOException
    {
        if ( !Files.isDirectory( folder ) ) {
            return;
        }
        Files.deleteIfExists( folder.resolve( MARKER_NAME ) );
        try ( DirectoryStream< Path > entries = Files.newDirectoryStream( folder ) ) {
            for ( Path entry : entries ) {
                // The record of an installed update belongs to the installer and the starter.
                if ( Files.isRegularFile( entry )
                     && !entry.getFileName().toString().equals( AppImageUpdateInstaller.APPLIED_MARKER ) ) {
                    Files.deleteIfExists( entry );
                }
            }
        }
    }

    /**
     * Resolves a bare file name inside the updates folder.
     *
     * @throws IOException when the name is empty, a path, or names the marker itself
     */
    private Path fileIn( String name ) throws IOException
    {
        Path file;
        try {
            file = folder.resolve( name ).normalize();
        }
        catch ( InvalidPathException e ) {
            throw new IOException( "Not a usable file name: " + name, e );
        }
        if ( name.isBlank() || name.contains( "/" ) || name.contains( "\\" ) || !folder.normalize().equals(
                file.getParent() ) || name.equals( MARKER_NAME ) || name.contains( "\n" ) || name.contains( "\r" ) ) {
            throw new IOException( "Not a usable file name: " + name );
        }
        return file;
    }

    private void writeMarker( StagedUpdate staged ) throws IOException
    {
        String text = "version=" + oneLine( staged.version() ) + "\n"
                      + "kind=" + staged.kind().name() + "\n"
                      + "file=" + staged.file().getFileName() + "\n"
                      + "sha256=" + staged.sha256() + "\n";
        Path temp = folder.resolve( MARKER_NAME + ".tmp" );
        Files.writeString( temp, text, StandardCharsets.UTF_8 );
        Files.move( temp, folder.resolve( MARKER_NAME ), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE );
    }

    private static String oneLine( String value ) throws IOException
    {
        if ( value.contains( "\n" ) || value.contains( "\r" ) ) {
            throw new IOException( "A version must be one line" );
        }
        return value;
    }
}

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

import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateStagerTest
{
    private static final String NAME = "Mica-Minecraft-Launcher-2026.11.20-x86_64.AppImage";
    private static final byte[] CONTENT = "pretend AppImage".getBytes( StandardCharsets.UTF_8 );
    private static final String CONTENT_SHA = HashUtilities.sha256Hex( CONTENT );

    /** Serves fixed bytes for every URL and counts the downloads. */
    private static final class FakeDownloader implements UpdateStager.Downloader
    {
        final List< String > urls = new ArrayList<>();
        byte[] body = CONTENT;

        @Override
        public void download( String url, java.io.File destination,
                              com.micatechnologies.minecraft.launcher.utilities.DownloadTracker tracker )
                throws IOException
        {
            urls.add( url );
            Files.write( destination.toPath(), body );
        }
    }

    private static ReleaseAsset asset( String name, String sha256 )
    {
        return new ReleaseAsset( name, "https://example.invalid/" + name, CONTENT.length, sha256 );
    }

    @Test
    void stagesAVerifiedFileAndRecordsIt( @TempDir Path dir ) throws IOException
    {
        FakeDownloader downloader = new FakeDownloader();
        UpdateStager stager = new UpdateStager( dir.resolve( "updates" ), downloader );

        StagedUpdate staged = stager.stage( "2026.11.20", InstallKind.LINUX_APPIMAGE, asset( NAME, CONTENT_SHA ), null );

        assertEquals( dir.resolve( "updates" ).resolve( NAME ), staged.file() );
        assertEquals( List.of( "https://example.invalid/" + NAME ), downloader.urls );
        assertEquals( Optional.of( staged ), stager.staged(), "the marker reads back the same update" );
        assertTrue( stager.verify( staged ) );
        assertEquals( "version=2026.11.20\nkind=LINUX_APPIMAGE\nfile=" + NAME + "\nsha256=" + CONTENT_SHA + "\n",
                      Files.readString( dir.resolve( "updates" ).resolve( UpdateStager.MARKER_NAME ) ),
                      "the starter reads this format" );
    }

    @Test
    void aMismatchedDownloadIsDeletedAndNothingIsStaged( @TempDir Path dir )
    {
        FakeDownloader downloader = new FakeDownloader();
        downloader.body = "tampered".getBytes( StandardCharsets.UTF_8 );
        UpdateStager stager = new UpdateStager( dir, downloader );

        assertThrows( UpdateStager.ChecksumMismatchException.class,
                      () -> stager.stage( "2026.11.20", InstallKind.LINUX_APPIMAGE, asset( NAME, CONTENT_SHA ), null ) );
        assertFalse( Files.exists( dir.resolve( NAME ) ) );
        assertTrue( stager.staged().isEmpty() );
    }

    @Test
    void theSameIntactUpdateIsNotDownloadedTwice( @TempDir Path dir ) throws IOException
    {
        FakeDownloader downloader = new FakeDownloader();
        UpdateStager stager = new UpdateStager( dir, downloader );

        stager.stage( "2026.11.20", InstallKind.LINUX_APPIMAGE, asset( NAME, CONTENT_SHA ), null );
        stager.stage( "2026.11.20", InstallKind.LINUX_APPIMAGE, asset( NAME, CONTENT_SHA ), null );

        assertEquals( 1, downloader.urls.size() );
    }

    @Test
    void aDamagedStagedFileIsDownloadedAgain( @TempDir Path dir ) throws IOException
    {
        FakeDownloader downloader = new FakeDownloader();
        UpdateStager stager = new UpdateStager( dir, downloader );
        StagedUpdate first = stager.stage( "2026.11.20", InstallKind.LINUX_APPIMAGE, asset( NAME, CONTENT_SHA ), null );

        Files.writeString( first.file(), "truncated" );
        assertFalse( stager.verify( first ) );
        stager.stage( "2026.11.20", InstallKind.LINUX_APPIMAGE, asset( NAME, CONTENT_SHA ), null );

        assertEquals( 2, downloader.urls.size() );
        assertTrue( stager.verify( stager.staged().orElseThrow() ) );
    }

    @Test
    void stagingANewerUpdateRemovesTheOlderOne( @TempDir Path dir ) throws IOException
    {
        UpdateStager stager = new UpdateStager( dir, new FakeDownloader() );
        String older = "Mica-Minecraft-Launcher-2026.11.13-x86_64.AppImage";
        stager.stage( "2026.11.13", InstallKind.LINUX_APPIMAGE, asset( older, CONTENT_SHA ), null );

        stager.stage( "2026.11.20", InstallKind.LINUX_APPIMAGE, asset( NAME, CONTENT_SHA ), null );

        assertFalse( Files.exists( dir.resolve( older ) ) );
        assertEquals( "2026.11.20", stager.staged().orElseThrow().version() );
    }

    @Test
    void refusesFilesWithoutAChecksumOrWithAPathForAName( @TempDir Path dir )
    {
        FakeDownloader downloader = new FakeDownloader();
        UpdateStager stager = new UpdateStager( dir, downloader );

        assertThrows( IOException.class,
                      () -> stager.stage( "1", InstallKind.LINUX_APPIMAGE, asset( NAME, null ), null ) );
        for ( String bad : List.of( "../escape.AppImage", "sub/dir.AppImage", "..\\escape.msi", " ",
                                    UpdateStager.MARKER_NAME ) ) {
            assertThrows( IOException.class,
                          () -> stager.stage( "1", InstallKind.LINUX_APPIMAGE, asset( bad, CONTENT_SHA ), null ), bad );
        }
        assertTrue( downloader.urls.isEmpty() );
    }

    @Test
    void aMarkerThatIsIncompleteOrPointsElsewhereIsIgnored( @TempDir Path dir ) throws IOException
    {
        UpdateStager stager = new UpdateStager( dir, new FakeDownloader() );
        Files.write( dir.resolve( NAME ), CONTENT );
        Path marker = dir.resolve( UpdateStager.MARKER_NAME );

        Files.writeString( marker, "version=1\nkind=LINUX_APPIMAGE\nfile=" + NAME + "\n" );
        assertTrue( stager.staged().isEmpty(), "no sha256" );
        Files.writeString( marker, "version=1\nkind=SOMETHING\nfile=" + NAME + "\nsha256=" + CONTENT_SHA + "\n" );
        assertTrue( stager.staged().isEmpty(), "unknown kind" );
        Files.writeString( marker, "version=1\nkind=LINUX_APPIMAGE\nfile=../" + NAME + "\nsha256=" + CONTENT_SHA + "\n" );
        assertTrue( stager.staged().isEmpty(), "outside the folder" );
        Files.writeString( marker, "version=1\nkind=LINUX_APPIMAGE\nfile=gone.AppImage\nsha256=" + CONTENT_SHA + "\n" );
        assertTrue( stager.staged().isEmpty(), "file missing" );
        Files.writeString( marker, "version=1\nkind=LINUX_APPIMAGE\nfile=" + NAME + "\nsha256=" + CONTENT_SHA + "\n" );
        assertTrue( stager.staged().isPresent() );
    }

    @Test
    void clearRemovesTheUpdateAndItsMarker( @TempDir Path dir ) throws IOException
    {
        UpdateStager stager = new UpdateStager( dir, new FakeDownloader() );
        stager.stage( "2026.11.20", InstallKind.LINUX_APPIMAGE, asset( NAME, CONTENT_SHA ), null );

        stager.clear();

        assertTrue( stager.staged().isEmpty() );
        try ( var entries = Files.list( dir ) ) {
            assertEquals( 0, entries.count() );
        }
        new UpdateStager( dir.resolve( "never-created" ), new FakeDownloader() ).clear();
    }
}

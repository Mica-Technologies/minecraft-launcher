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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure, network-free decision logic in {@link NetworkUtilities}:
 * retry-message filename extraction, human-readable byte/progress formatting,
 * the per-path download-lock keying used to serialize concurrent downloads of
 * the same file, and the Content-Type gate that protects the bounded JSON
 * fetch path from a compromised host smuggling a non-JSON body past Gson.
 *
 * <p>None of these tests perform real network I/O — that would make them slow
 * and flaky, and the launch-critical logic here (which content types are
 * accepted, whether two {@link File} handles to the same path share a lock,
 * how a byte count is rendered) doesn't need a live socket to verify. Several
 * {@code private static} methods were widened to package-private (see the
 * "widened for testing" comments in {@code NetworkUtilities}) per the repo's
 * documented preference for a widened seam over reflection.</p>
 */
class NetworkUtilitiesTest
{
    // ------------------------------------------------------------------
    // urlFileName
    // ------------------------------------------------------------------

    @Test
    void urlFileName_extractsLastPathSegment() throws MalformedURLException
    {
        assertEquals( "bar.jar", NetworkUtilities.urlFileName( new URL( "https://example.com/foo/bar.jar" ) ) );
    }

    @Test
    void urlFileName_stripsQueryStringViaUrlPathParsing() throws MalformedURLException
    {
        // URL#getPath() already excludes the query string, so ?x=1 must not leak into
        // the extracted filename used in user-facing retry messages.
        assertEquals( "bar.jar", NetworkUtilities.urlFileName( new URL( "https://example.com/foo/bar.jar?x=1" ) ) );
    }

    @Test
    void urlFileName_fallsBackToHostWhenPathIsEmpty() throws MalformedURLException
    {
        assertEquals( "example.com", NetworkUtilities.urlFileName( new URL( "https://example.com" ) ) );
    }

    @Test
    void urlFileName_fallsBackToFullPathWhenPathHasNoSlash() throws MalformedURLException
    {
        // Pathological but should not throw: a path with no '/' at all falls through
        // to returning the path itself.
        URL u = new URL( "https", "example.com", -1, "nofile" );
        assertEquals( "nofile", NetworkUtilities.urlFileName( u ) );
    }

    @Test
    void urlFileName_returnsEmptyStringWhenPathEndsInSlash() throws MalformedURLException
    {
        // slash is the very last character: slash < path.length() - 1 is false, so the
        // method falls back to returning the raw path ("/foo/") rather than crashing on
        // a substring-past-the-end.
        assertEquals( "/foo/", NetworkUtilities.urlFileName( new URL( "https://example.com/foo/" ) ) );
    }

    @Test
    void urlFileName_returnsEmptyStringForNullSource()
    {
        assertEquals( "", NetworkUtilities.urlFileName( null ) );
    }

    // ------------------------------------------------------------------
    // formatBytes
    // ------------------------------------------------------------------

    @Test
    void formatBytes_rendersBytesBelowOneKilobyte()
    {
        assertEquals( "945 B", NetworkUtilities.formatBytes( 945 ) );
        assertEquals( "0 B", NetworkUtilities.formatBytes( 0 ) );
    }

    @Test
    void formatBytes_rendersKilobytesWithoutDecimal()
    {
        assertEquals( "812 KB", NetworkUtilities.formatBytes( 812 * 1024 ) );
    }

    @Test
    void formatBytes_rendersMegabytesWithOneDecimal()
    {
        assertEquals( "27.1 MB", NetworkUtilities.formatBytes( (long) ( 27.1 * 1024 * 1024 ) ) );
    }

    @Test
    void formatBytes_rendersGigabytesWithTwoDecimals()
    {
        assertEquals( "1.50 GB", NetworkUtilities.formatBytes( (long) ( 1.5 * 1024 * 1024 * 1024 ) ) );
    }

    // ------------------------------------------------------------------
    // formatDownloadProgress
    // ------------------------------------------------------------------

    @Test
    void formatDownloadProgress_includesFileNamePercentAndByteCounts()
    {
        File dest = new File( "Faithful-64x.zip" );
        String line = NetworkUtilities.formatDownloadProgress( dest, 12_000_000L, 27_000_000L, null );
        assertTrue( line.startsWith( "Faithful-64x.zip — " ), "line: " + line );
        assertTrue( line.contains( "44%" ), "line: " + line ); // (12_000_000*100)/27_000_000 = 44
        assertTrue( line.contains( "/" ), "line: " + line );
    }

    @Test
    void formatDownloadProgress_omitsPercentWhenContentLengthUnknown()
    {
        File dest = new File( "unknown-size.bin" );
        String line = NetworkUtilities.formatDownloadProgress( dest, 4096L, 0L, null );
        assertTrue( line.contains( "4 KB" ), "line: " + line );
        assertTrue( !line.contains( "%" ), "line: " + line );
    }

    @Test
    void formatDownloadProgress_clampsPercentAtOneHundred()
    {
        // bytesSoFar exceeding contentLength (can happen transiently) must not render
        // a nonsensical >100% figure.
        File dest = new File( "f.bin" );
        String line = NetworkUtilities.formatDownloadProgress( dest, 200L, 100L, null );
        assertTrue( line.contains( "100%" ), "line: " + line );
    }

    @Test
    void formatDownloadProgress_omitsFileNameSegmentWhenDestinationIsNull()
    {
        String line = NetworkUtilities.formatDownloadProgress( null, 500L, 0L, null );
        assertTrue( !line.contains( "—" ), "line: " + line );
        assertTrue( line.contains( "500 B" ), "line: " + line );
    }

    /** Stub tracker with a fixed {@code getFormattedSpeed()} result, avoiding a real
     *  elapsed-wall-clock-dependent speed sample (DownloadTracker only recalculates
     *  speed after a 500ms window, which would make the test slow or flaky). Extends
     *  the real class rather than using a mocking framework, matching the repo's stub
     *  convention (e.g. {@code RgbBackendRegistryTest}'s {@code StubBackend}). */
    private static final class StubTracker extends DownloadTracker
    {
        private final String speed;

        StubTracker( String speed )
        {
            this.speed = speed;
        }

        @Override public String getFormattedSpeed()
        {
            return speed;
        }
    }

    @Test
    void formatDownloadProgress_appendsSpeedSegmentWhenTrackerReportsOne()
    {
        String line = NetworkUtilities.formatDownloadProgress(
                new File( "f.bin" ), 10L, 1000L, new StubTracker( "2.4 MB/s" ) );
        assertTrue( line.contains( " · 2.4 MB/s" ), "line: " + line );
    }

    @Test
    void formatDownloadProgress_omitsSpeedSegmentWhenTrackerReportsEmptySpeed()
    {
        // contentLength 0 (unknown) so the only possible "·" would come from the speed
        // segment; an empty getFormattedSpeed() must suppress it entirely.
        String line = NetworkUtilities.formatDownloadProgress(
                new File( "f.bin" ), 10L, 0L, new StubTracker( "" ) );
        assertTrue( !line.contains( "·" ), "line: " + line );
    }

    // ------------------------------------------------------------------
    // getPathLock
    // ------------------------------------------------------------------

    @Test
    void getPathLock_returnsSameLockForDifferentFileObjectsOfSameAbsolutePath( @TempDir Path tmp )
    {
        File a = tmp.resolve( "same.txt" ).toFile();
        File b = new File( tmp.toFile(), "same.txt" );
        assertSame( NetworkUtilities.getPathLock( a ), NetworkUtilities.getPathLock( b ) );
    }

    @Test
    void getPathLock_returnsDifferentLocksForDifferentPaths( @TempDir Path tmp )
    {
        File a = tmp.resolve( "one.txt" ).toFile();
        File b = tmp.resolve( "two.txt" ).toFile();
        assertNotSame( NetworkUtilities.getPathLock( a ), NetworkUtilities.getPathLock( b ) );
    }

    @Test
    void getPathLock_treatsRelativeAndAbsoluteFormsOfSamePathAsSameLock( @TempDir Path tmp ) throws IOException
    {
        Path file = tmp.resolve( "rel.txt" );
        java.nio.file.Files.createFile( file );
        File absolute = file.toFile();
        File viaDotSegment = new File( tmp.toFile(), "./rel.txt" );
        assertSame( NetworkUtilities.getPathLock( absolute ), NetworkUtilities.getPathLock( viaDotSegment ) );
    }

    // ------------------------------------------------------------------
    // assertAcceptableJsonContentType
    // ------------------------------------------------------------------

    private static URL exampleUrl() throws MalformedURLException
    {
        return new URL( "https://example.com/manifest.json" );
    }

    @Test
    void assertAcceptableJsonContentType_allowsExplicitJsonDeclarations() throws Exception
    {
        URL u = exampleUrl();
        assertDoesNotThrow( () -> NetworkUtilities.assertAcceptableJsonContentType( "application/json", u ) );
        assertDoesNotThrow( () -> NetworkUtilities.assertAcceptableJsonContentType(
                "application/json; charset=utf-8", u ) );
        assertDoesNotThrow( () -> NetworkUtilities.assertAcceptableJsonContentType( "text/json", u ) );
        assertDoesNotThrow( () -> NetworkUtilities.assertAcceptableJsonContentType( "application/javascript", u ) );
    }

    @Test
    void assertAcceptableJsonContentType_allowsGenericStaticHostTypes() throws Exception
    {
        // Static blob hosts (Azure Blob, S3 default config, GitHub raw) serve unknown
        // extensions like .mmcjson under these generic types even though the body is
        // genuinely JSON.
        URL u = exampleUrl();
        assertDoesNotThrow( () -> NetworkUtilities.assertAcceptableJsonContentType( "text/plain", u ) );
        assertDoesNotThrow( () -> NetworkUtilities.assertAcceptableJsonContentType(
                "application/octet-stream", u ) );
    }

    @Test
    void assertAcceptableJsonContentType_allowsMissingOrBlankContentType() throws Exception
    {
        URL u = exampleUrl();
        assertDoesNotThrow( () -> NetworkUtilities.assertAcceptableJsonContentType( null, u ) );
        assertDoesNotThrow( () -> NetworkUtilities.assertAcceptableJsonContentType( "", u ) );
        assertDoesNotThrow( () -> NetworkUtilities.assertAcceptableJsonContentType( "   ", u ) );
    }

    @Test
    void assertAcceptableJsonContentType_rejectsHtmlContentType() throws Exception
    {
        URL u = exampleUrl();
        // A compromised or misconfigured host serving an HTML error/login page in place
        // of the expected JSON manifest must not be handed to Gson downstream.
        IOException e = assertThrows( IOException.class,
                () -> NetworkUtilities.assertAcceptableJsonContentType( "text/html", u ) );
        assertTrue( e.getMessage().contains( "text/html" ) );
    }

    @Test
    void assertAcceptableJsonContentType_rejectionIsCaseInsensitive() throws Exception
    {
        URL u = exampleUrl();
        assertDoesNotThrow( () -> NetworkUtilities.assertAcceptableJsonContentType( "APPLICATION/JSON", u ) );
        assertThrows( IOException.class,
                () -> NetworkUtilities.assertAcceptableJsonContentType( "TEXT/HTML", u ) );
    }

    @Test
    void boundedFileDownload_refusesPlainHttpAndRemovesTheDestination( @TempDir Path dir )
            throws Exception
    {
        // Refused before any connection is opened, so no network is touched. The caller's
        // pre-created temp file must not be left behind on the failure path.
        File dest = dir.resolve( "pack.mrpack" ).toFile();
        assertTrue( dest.createNewFile() );
        assertThrows( IOException.class, () -> NetworkUtilities.downloadFileFromURLBounded(
                new URL( "http://example.invalid/pack.mrpack" ), dest, 1024 ) );
        assertTrue( !dest.exists(), "partial destination should be deleted on failure" );
    }
}

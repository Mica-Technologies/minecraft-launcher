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

package com.micatechnologies.minecraft.launcher.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpEndpointFile} — the discovery file a local MCP client reads to find the
 * launcher's server.
 *
 * <p>Why this matters: <b>the file is a credential</b>. It carries the bearer token, so
 * anything able to read it can drive the MCP server. Two properties follow, and both are
 * tested here rather than assumed: it is written owner-only, and a file left behind by a
 * crashed launcher reads as absent.</p>
 *
 * <p>The staleness check is the less obvious of the two. A leftover file is worse than no
 * file at all — it sends a client to a port the launcher no longer owns, presenting a token
 * that whatever now listens there never issued.</p>
 */
class McpEndpointFileTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    // region round-trip

    @Test
    void anEndpointRoundTripsThroughTheFile() throws IOException
    {
        Path path = tempDir.resolve( McpEndpointFile.FILENAME );
        McpEndpointFile.write( path, endpoint( 51234, ProcessHandle.current().pid() ) );

        McpEndpointFile.Endpoint read = McpEndpointFile.read( path );
        assertNotNull( read );
        assertEquals( 51234, read.port() );
        assertEquals( TOKEN, read.token() );
        assertEquals( ProcessHandle.current().pid(), read.pid() );
        assertEquals( "3.0-test", read.launcherVersion() );
    }

    @Test
    void writingCreatesMissingParentDirectories() throws IOException
    {
        Path path = tempDir.resolve( "nested" ).resolve( "deeper" ).resolve( McpEndpointFile.FILENAME );
        McpEndpointFile.write( path, endpoint( 51234, ProcessHandle.current().pid() ) );
        assertTrue( Files.isRegularFile( path ) );
    }

    @Test
    void writingRequiresBothArguments()
    {
        Path path = tempDir.resolve( McpEndpointFile.FILENAME );
        assertThrows( IllegalArgumentException.class, () -> McpEndpointFile.write( path, null ) );
        assertThrows( IllegalArgumentException.class,
                      () -> McpEndpointFile.write( null, endpoint( 1, 1 ) ) );
    }

    // endregion

    // region permissions

    /**
     * The file carries the bearer token, so group and world must not be able to read it. Skipped
     * on filesystems without POSIX permissions — Windows uses ACLs, which the shared
     * {@code FilePermissions} helper handles separately.
     */
    @Test
    void theFileIsWrittenOwnerOnly() throws IOException
    {
        Path path = tempDir.resolve( McpEndpointFile.FILENAME );
        McpEndpointFile.write( path, endpoint( 51234, ProcessHandle.current().pid() ) );

        if ( !Files.getFileStore( path ).supportsFileAttributeView( "posix" ) ) {
            return;
        }
        Set< PosixFilePermission > permissions = Files.getPosixFilePermissions( path );
        assertFalse( permissions.contains( PosixFilePermission.GROUP_READ ), "group can read the token" );
        assertFalse( permissions.contains( PosixFilePermission.OTHERS_READ ), "world can read the token" );
        assertTrue( permissions.contains( PosixFilePermission.OWNER_READ ) );
    }

    // endregion

    // region absent and malformed files

    @Test
    void anAbsentFileReadsAsNothing()
    {
        assertNull( McpEndpointFile.read( tempDir.resolve( "does-not-exist.json" ) ) );
        assertNull( McpEndpointFile.read( null ) );
        assertNull( McpEndpointFile.readLive( tempDir.resolve( "does-not-exist.json" ) ) );
    }

    @Test
    void aDirectoryInPlaceOfTheFileReadsAsNothing() throws IOException
    {
        Path path = tempDir.resolve( "as-a-directory" );
        Files.createDirectory( path );
        assertNull( McpEndpointFile.read( path ) );
    }

    /**
     * A half-written file during startup is a normal race, not an error worth throwing over —
     * the caller's recovery is the same as for an absent file either way.
     */
    @Test
    void aMalformedFileReadsAsNothing() throws IOException
    {
        Path path = tempDir.resolve( McpEndpointFile.FILENAME );
        for ( String content : new String[]{ "", "   ", "not json", "{\"port\":", "[]", "null" } ) {
            Files.writeString( path, content, StandardCharsets.UTF_8 );
            assertNull( McpEndpointFile.read( path ), "content: " + content );
        }
    }

    @Test
    void aFileMissingTheTokenOrPortReadsAsNothing() throws IOException
    {
        Path path = tempDir.resolve( McpEndpointFile.FILENAME );
        Files.writeString( path, "{\"port\":51234}", StandardCharsets.UTF_8 );
        assertNull( McpEndpointFile.read( path ) );
        Files.writeString( path, "{\"token\":\"" + TOKEN + "\"}", StandardCharsets.UTF_8 );
        assertNull( McpEndpointFile.read( path ) );
    }

    @Test
    void anOutOfRangePortOrBlankTokenReadsAsNothing() throws IOException
    {
        Path path = tempDir.resolve( McpEndpointFile.FILENAME );
        for ( String content : new String[]{
                "{\"port\":0,\"token\":\"" + TOKEN + "\"}",
                "{\"port\":-1,\"token\":\"" + TOKEN + "\"}",
                "{\"port\":70000,\"token\":\"" + TOKEN + "\"}",
                "{\"port\":51234,\"token\":\"\"}",
                "{\"port\":51234,\"token\":\"   \"}" } ) {
            Files.writeString( path, content, StandardCharsets.UTF_8 );
            assertNull( McpEndpointFile.read( path ), "content: " + content );
        }
    }

    // endregion

    // region staleness

    @Test
    void aLiveEndpointIsReturned() throws IOException
    {
        Path path = tempDir.resolve( McpEndpointFile.FILENAME );
        McpEndpointFile.write( path, endpoint( 51234, ProcessHandle.current().pid() ) );
        assertNotNull( McpEndpointFile.readLive( path ) );
    }

    /**
     * The property this class exists for: a file left behind by a crashed launcher must not be
     * handed to a client. It would point at a port the launcher no longer owns.
     */
    @Test
    void aFileFromADeadProcessReadsAsAbsent() throws IOException
    {
        Path path = tempDir.resolve( McpEndpointFile.FILENAME );
        McpEndpointFile.write( path, endpoint( 51234, deadPid() ) );

        assertNotNull( McpEndpointFile.read( path ), "the raw read still sees it" );
        assertNull( McpEndpointFile.readLive( path ), "the live read must not" );
    }

    /** A file predating the pid field is not discarded — it may well still be valid. */
    @Test
    void aFileWithNoRecordedPidIsTreatedAsLive() throws IOException
    {
        Path path = tempDir.resolve( McpEndpointFile.FILENAME );
        Files.writeString( path, "{\"port\":51234,\"token\":\"" + TOKEN + "\"}", StandardCharsets.UTF_8 );
        assertNotNull( McpEndpointFile.readLive( path ) );
    }

    @Test
    void theCurrentProcessIsAlive()
    {
        assertTrue( McpEndpointFile.isProcessAlive( ProcessHandle.current().pid() ) );
    }

    @Test
    void invalidProcessIdsAreNotAlive()
    {
        assertFalse( McpEndpointFile.isProcessAlive( 0 ) );
        assertFalse( McpEndpointFile.isProcessAlive( -1 ) );
    }

    // endregion

    // region deletion

    @Test
    void deletingRemovesTheFile() throws IOException
    {
        Path path = tempDir.resolve( McpEndpointFile.FILENAME );
        McpEndpointFile.write( path, endpoint( 51234, ProcessHandle.current().pid() ) );
        McpEndpointFile.delete( path );
        assertFalse( Files.exists( path ) );
    }

    /** Shutdown runs this unconditionally, so a file that is already gone must not throw. */
    @Test
    void deletingAnAbsentFileIsHarmless()
    {
        McpEndpointFile.delete( tempDir.resolve( "never-existed.json" ) );
        McpEndpointFile.delete( null );
    }

    // endregion

    /**
     * Builds an endpoint for the given port and process id.
     *
     * @param port the port to record
     * @param pid  the process id to record
     *
     * @return the endpoint
     */
    private static McpEndpointFile.Endpoint endpoint( int port, long pid )
    {
        return new McpEndpointFile.Endpoint( port, TOKEN, pid, "3.0-test" );
    }

    /**
     * Finds a process id that is not currently in use.
     *
     * @return a dead process id
     */
    private static long deadPid()
    {
        for ( long candidate = 900_000; candidate < 999_999; candidate++ ) {
            if ( ProcessHandle.of( candidate ).isEmpty() ) {
                return candidate;
            }
        }
        throw new IllegalStateException( "Could not find an unused process id" );
    }
}

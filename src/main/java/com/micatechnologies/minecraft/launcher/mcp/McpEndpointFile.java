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

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.FilePermissions;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The discovery file that tells a local MCP client where the launcher's server is listening
 * and what bearer token to present.
 * <p>
 * <b>This file is a credential.</b> Anything that can read it can drive the MCP server, so it
 * is written with owner-only permissions through the same {@link FilePermissions} helper the
 * single-instance IPC token uses, and it is deleted on shutdown.
 * <p>
 * A file left behind by a crashed launcher is worse than no file: a client would present a
 * stale token to whatever now owns that port. {@link #readLive} therefore treats a file whose
 * recorded process is no longer running as absent.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpEndpointFile
{
    /** Filename of the endpoint descriptor, alongside the single-instance IPC token. */
    public static final String FILENAME = "mcp-endpoint.json";

    /**
     * Where the MCP server is listening and how to authenticate to it.
     *
     * @param port            the loopback TCP port
     * @param token           the per-launch bearer token
     * @param pid             the launcher process id, used to detect a stale file
     * @param launcherVersion the launcher version that wrote the file, for diagnostics
     *
     * @since 3.0
     */
    public record Endpoint( int port, String token, long pid, String launcherVersion )
    {
    }

    /**
     * Returns the default endpoint-file path.
     * <p>
     * Deliberately uses the game-mode-independent client config path, for the same reason
     * {@code SingleInstanceLock} does: a client discovering the endpoint has a different
     * working directory from the launcher, so a CWD-relative path would never agree between
     * them.
     *
     * @return the endpoint-file path
     *
     * @since 3.0
     */
    public static Path defaultPath()
    {
        return Path.of( LocalPathManager.getClientConfigFolderPath(), FILENAME );
    }

    /**
     * Writes the endpoint file with owner-only permissions.
     *
     * @param path     where to write
     * @param endpoint what to record
     *
     * @throws IOException              if the file cannot be written
     * @throws IllegalArgumentException if either argument is {@code null}
     * @since 3.0
     */
    public static void write( Path path, Endpoint endpoint ) throws IOException
    {
        if ( path == null || endpoint == null ) {
            throw new IllegalArgumentException( "A path and endpoint are required" );
        }

        JsonObject json = new JsonObject();
        json.addProperty( "port", endpoint.port() );
        json.addProperty( "token", endpoint.token() );
        json.addProperty( "pid", endpoint.pid() );
        json.addProperty( "launcherVersion", endpoint.launcherVersion() );

        Path parent = path.getParent();
        if ( parent != null ) {
            Files.createDirectories( parent );
        }
        Files.writeString( path, JSONUtilities.getGson().toJson( json ), StandardCharsets.UTF_8 );

        // Permissions are applied after the write rather than at creation because the helper
        // is shared with the IPC token path and is platform-aware (POSIX modes on Unix, ACLs
        // on Windows). The window between the two is why the file lives in a config directory
        // that is itself owner-only.
        FilePermissions.applyOwnerOnly( path );
    }

    /**
     * Reads the endpoint file without checking whether the launcher that wrote it is still
     * running.
     *
     * @param path where to read from
     *
     * @return the endpoint, or {@code null} when the file is absent, unreadable, or malformed
     *
     * @since 3.0
     */
    public static Endpoint read( Path path )
    {
        if ( path == null || !Files.isRegularFile( path ) ) {
            return null;
        }
        try {
            JsonObject json = JSONUtilities.getGson()
                    .fromJson( Files.readString( path, StandardCharsets.UTF_8 ), JsonObject.class );
            if ( json == null || !json.has( "port" ) || !json.has( "token" ) ) {
                return null;
            }
            int port = json.get( "port" ).getAsInt();
            String token = json.get( "token" ).getAsString();
            if ( port <= 0 || port > 65535 || token == null || token.isBlank() ) {
                return null;
            }
            long pid = json.has( "pid" ) ? json.get( "pid" ).getAsLong() : -1L;
            String version = json.has( "launcherVersion" ) ? json.get( "launcherVersion" ).getAsString() : "";
            return new Endpoint( port, token, pid, version );
        }
        catch ( Exception e ) {
            // A malformed or truncated file reads as absent rather than throwing: the caller's
            // recovery is identical either way, and a half-written file during startup is a
            // normal race rather than an error worth surfacing.
            return null;
        }
    }

    /**
     * Reads the endpoint file, treating one left behind by a process that is no longer running
     * as absent.
     * <p>
     * This is what callers discovering a running launcher should use. A stale file would
     * otherwise send a client to a port the launcher no longer owns, carrying a token that
     * whatever now listens there did not issue.
     *
     * @param path where to read from
     *
     * @return the endpoint, or {@code null} when it is absent, malformed, or stale
     *
     * @since 3.0
     */
    public static Endpoint readLive( Path path )
    {
        Endpoint endpoint = read( path );
        if ( endpoint == null ) {
            return null;
        }
        // A file with no recorded pid predates this check; treat it as live rather than
        // discarding an endpoint that may well be valid.
        if ( endpoint.pid() > 0 && !isProcessAlive( endpoint.pid() ) ) {
            return null;
        }
        return endpoint;
    }

    /**
     * Deletes the endpoint file, ignoring a file that is already gone.
     *
     * @param path the file to remove
     *
     * @since 3.0
     */
    public static void delete( Path path )
    {
        if ( path == null ) {
            return;
        }
        try {
            Files.deleteIfExists( path );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Could not remove the MCP endpoint file: " + path );
        }
    }

    /**
     * Reports whether a process id belongs to a process that is currently running.
     *
     * @param pid the process id to check
     *
     * @return {@code true} when a process with that id is alive
     *
     * @since 3.0
     */
    public static boolean isProcessAlive( long pid )
    {
        if ( pid <= 0 ) {
            return false;
        }
        return ProcessHandle.of( pid ).map( ProcessHandle::isAlive ).orElse( false );
    }

    /**
     * Not instantiable.
     */
    private McpEndpointFile()
    {
        throw new AssertionError( "McpEndpointFile is a utility class and must not be instantiated" );
    }
}

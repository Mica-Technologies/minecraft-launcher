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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates ready-to-paste configuration for connecting an MCP client to this launcher.
 * <p>
 * The snippets are generated rather than written out in the help text because the interesting
 * part is install-specific: where this launcher actually lives, and whether it is a native
 * executable or a JAR that needs {@code java -jar}. A user copying a generic example would have
 * to work that out themselves, and would get it wrong on macOS, where the launcher path
 * contains spaces.
 * <p>
 * <b>Every form uses the stdio relay, not the HTTP port.</b> {@code launcher --mcp} reads the
 * endpoint file itself, so the bearer token never has to be pasted into a client's config file
 * — which is both easier and materially safer, since that token would otherwise sit in
 * plaintext somewhere the user has to remember to update every launch. The raw HTTP details are
 * offered only for a client that cannot spawn a subprocess.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpClientSetup
{
    /** The server name suggested in every snippet. */
    public static final String SERVER_NAME = "mica-launcher";

    /**
     * Shown instead of a snippet when the launcher path resolves to the JVM rather than to an
     * installed launcher — the development-run case. A snippet built from that path would be
     * accepted by every client and then quietly do nothing.
     */
    static final String UNPACKAGED_NOTICE =
            "This launcher is running from source rather than an installed build, so there is no\n"
                    + "launcher command to give a client. Install the launcher and reopen this\n"
                    + "page, or connect over HTTP using the details under \"Raw HTTP\".";

    /** Clients this class can produce configuration for. */
    public enum Client
    {
        /** Claude Code — configured through its {@code claude mcp add} CLI. */
        CLAUDE_CODE,

        /** Cursor — configured through {@code ~/.cursor/mcp.json}. */
        CURSOR,

        /** Codex CLI — configured through {@code ~/.codex/config.toml}. */
        CODEX,

        /** Raw connection details, for a client that cannot spawn a subprocess. */
        HTTP
    }

    /**
     * Reports whether a launcher path is a JAR, and therefore has to be started through
     * {@code java -jar} rather than executed directly.
     *
     * <p>Deliberately not reusing {@code DesktopShortcutManager.isNativeExecutable}: that method
     * answers a broader question and misclassifies any path <em>ending</em> in "java" via
     * {@code endsWith}, a defect pinned by its own tests. The question here is narrower and has
     * an exact answer — does this path name a JAR — so it is asked directly.</p>
     *
     * @param launcherPath the resolved launcher path
     *
     * @return {@code true} when the path names a JAR
     *
     * @since 3.0
     */
    public static boolean isJar( String launcherPath )
    {
        return launcherPath != null
                && launcherPath.toLowerCase( java.util.Locale.ROOT ).endsWith( ".jar" );
    }

    /**
     * Reports whether a resolved path can actually start the launcher.
     *
     * <p>{@code DesktopShortcutManager.resolveLauncherPath()} falls back to the running JVM's
     * own executable when there is no packaged app image — which is what happens in a
     * development or IDE run. That path is real and exists, so nothing downstream complains,
     * but {@code .../bin/java --mcp} starts a bare JVM with no classpath and does nothing.
     * Emitting a snippet that silently fails is worse than saying it cannot be built, so this
     * is checked explicitly.</p>
     *
     * @param launcherPath the resolved path
     *
     * @return {@code true} when the path names a JAR or a native launcher executable
     *
     * @since 3.0
     */
    public static boolean isUsableLauncherPath( String launcherPath )
    {
        if ( launcherPath == null || launcherPath.isBlank() ) {
            return false;
        }
        if ( isJar( launcherPath ) ) {
            return true;
        }
        String name = launcherPath.replace( '\\', '/' );
        name = name.substring( name.lastIndexOf( '/' ) + 1 ).toLowerCase( java.util.Locale.ROOT );
        return !name.equals( "java" ) && !name.equals( "java.exe" ) && !name.equals( "javaw.exe" );
    }

    /**
     * Builds the command that starts the stdio relay.
     *
     * @param launcherPath       where the launcher lives
     * @param nativeExecutable   whether that path is a native executable rather than a JAR
     *
     * @return the command and its arguments, unquoted, ready to be rendered per format
     *
     * @since 3.0
     */
    public static List< String > relayCommand( String launcherPath, boolean nativeExecutable )
    {
        List< String > command = new ArrayList<>();
        if ( nativeExecutable ) {
            command.add( launcherPath );
        }
        else {
            command.add( "java" );
            command.add( "-jar" );
            command.add( launcherPath );
        }
        command.add( "--mcp" );
        return command;
    }

    /**
     * Builds a configuration snippet for one client.
     *
     * @param client           the client to configure
     * @param launcherPath     where the launcher lives
     * @param nativeExecutable whether that path is a native executable rather than a JAR
     * @param port             the loopback port the server is listening on, or {@code 0} when
     *                         it is not running; used only by {@link Client#HTTP}
     * @param endpointFilePath where the endpoint descriptor is written
     *
     * @return the snippet
     *
     * @since 3.0
     */
    public static String snippet( Client client, String launcherPath, boolean nativeExecutable,
                                  int port, String endpointFilePath )
    {
        if ( client != Client.HTTP && !isUsableLauncherPath( launcherPath ) ) {
            return UNPACKAGED_NOTICE;
        }
        List< String > command = relayCommand( launcherPath, nativeExecutable );
        return switch ( client ) {
            case CLAUDE_CODE -> claudeCode( command );
            case CURSOR -> cursor( command );
            case CODEX -> codex( command );
            case HTTP -> http( port, endpointFilePath );
        };
    }

    /**
     * Builds the {@code claude mcp add} command line.
     *
     * @param command the relay command
     *
     * @return the snippet
     */
    private static String claudeCode( List< String > command )
    {
        StringBuilder line = new StringBuilder( "claude mcp add " ).append( SERVER_NAME )
                                                                   .append( " --" );
        for ( String part : command ) {
            line.append( ' ' ).append( shellQuote( part ) );
        }
        return line.toString();
    }

    /**
     * Builds the Cursor {@code mcp.json} fragment.
     *
     * @param command the relay command
     *
     * @return the snippet
     */
    private static String cursor( List< String > command )
    {
        JsonArray args = new JsonArray();
        for ( int i = 1; i < command.size(); i++ ) {
            args.add( command.get( i ) );
        }
        JsonObject server = new JsonObject();
        server.addProperty( "command", command.get( 0 ) );
        server.add( "args", args );

        JsonObject servers = new JsonObject();
        servers.add( SERVER_NAME, server );
        JsonObject root = new JsonObject();
        root.add( "mcpServers", servers );

        return "// ~/.cursor/mcp.json\n" + JSONUtilities.getPrettyGson().toJson( root );
    }

    /**
     * Builds the Codex {@code config.toml} fragment.
     *
     * @param command the relay command
     *
     * @return the snippet
     */
    private static String codex( List< String > command )
    {
        StringBuilder args = new StringBuilder( "[" );
        for ( int i = 1; i < command.size(); i++ ) {
            if ( i > 1 ) {
                args.append( ", " );
            }
            args.append( tomlString( command.get( i ) ) );
        }
        args.append( ']' );

        return "# ~/.codex/config.toml\n"
                + "[mcp_servers." + SERVER_NAME + "]\n"
                + "command = " + tomlString( command.get( 0 ) ) + "\n"
                + "args = " + args + "\n";
    }

    /**
     * Builds the raw HTTP details.
     * <p>
     * Deliberately prints the endpoint file's <em>path</em> rather than the token it contains.
     * The token grants full access, it rotates every launch, and a Settings pane is one
     * screenshot away from a support thread.
     *
     * @param port             the bound port, or {@code 0} when not running
     * @param endpointFilePath where the endpoint descriptor is written
     *
     * @return the snippet
     */
    private static String http( int port, String endpointFilePath )
    {
        String endpoint = port > 0 ? "http://127.0.0.1:" + port + "/mcp"
                                   : "http://127.0.0.1:<port>/mcp  (server not running)";
        return "POST " + endpoint + "\n"
                + "Authorization: Bearer <token>\n"
                + "Content-Type: application/json\n"
                + "\n"
                + "The port and token are in:\n"
                + endpointFilePath + "\n"
                + "\n"
                + "Both change every time the launcher starts, so a client that reads them\n"
                + "once will stop working. Prefer the stdio command above, which re-reads\n"
                + "them for you.";
    }

    /**
     * Quotes an argument for a POSIX shell, using single quotes so nothing inside is expanded.
     * <p>
     * Needed because launcher paths routinely contain spaces —
     * {@code /Applications/Mica Minecraft Launcher.app/...} on macOS — and an unquoted snippet
     * would silently be parsed as several arguments.
     *
     * @param value the argument
     *
     * @return the quoted argument
     *
     * @since 3.0
     */
    static String shellQuote( String value )
    {
        if ( value == null || value.isEmpty() ) {
            return "''";
        }
        if ( value.matches( "[A-Za-z0-9_./:=+-]+" ) ) {
            return value;
        }
        // A single quote cannot appear inside a single-quoted string, so close, escape, reopen.
        return "'" + value.replace( "'", "'\\''" ) + "'";
    }

    /**
     * Renders a TOML basic string, escaping what TOML requires.
     *
     * @param value the value
     *
     * @return the quoted value
     *
     * @since 3.0
     */
    static String tomlString( String value )
    {
        String escaped = value == null ? "" : value.replace( "\\", "\\\\" ).replace( "\"", "\\\"" );
        return "\"" + escaped + "\"";
    }

    /**
     * Not instantiable.
     */
    private McpClientSetup()
    {
        throw new AssertionError( "McpClientSetup is a utility class and must not be instantiated" );
    }
}

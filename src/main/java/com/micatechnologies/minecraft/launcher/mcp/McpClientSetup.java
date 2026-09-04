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
 * <b>HTTP is the primary form.</b> The launcher already hosts a loopback HTTP server while it
 * is open, on a fixed port with a persisted token, so a client is configured once with a URL
 * and a bearer header and stays configured. That is the shape every MCP client understands and
 * the least work for the user.
 * <p>
 * The stdio relay ({@code launcher --mcp}) remains available for clients that can only spawn a
 * subprocess, and for those it has one genuine advantage: it reads the endpoint file itself, so
 * no token goes into the client's config at all. It is the fallback, not the recommendation.
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
                    + "launcher command to give a client. Use one of the HTTP options instead --\n"
                    + "they do not need a launcher path.";

    /** Clients this class can produce configuration for. */
    public enum Client
    {
        /** Claude Code over HTTP, through its {@code claude mcp add --transport http} CLI. */
        CLAUDE_CODE,

        /** Cursor over HTTP, through {@code ~/.cursor/mcp.json}. */
        CURSOR,

        /** Codex CLI over HTTP, through {@code ~/.codex/config.toml}. */
        CODEX,

        /** The raw URL and header, for adapting to any other client. */
        HTTP,

        /** The stdio relay, for a client that can only spawn a subprocess. */
        STDIO
    }

    /**
     * Builds the loopback endpoint URL.
     *
     * @param port the bound port
     *
     * @return the URL
     *
     * @since 3.0
     */
    public static String endpointUrl( int port )
    {
        return "http://127.0.0.1:" + port + "/mcp";
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
        return snippet( client, launcherPath, nativeExecutable, port, endpointFilePath, "<token>" );
    }

    /**
     * Builds a configuration snippet, embedding the real bearer token.
     *
     * @param client           the client to configure
     * @param launcherPath     where the launcher lives; used only by {@link Client#STDIO}
     * @param nativeExecutable whether that path is a native executable rather than a JAR
     * @param port             the loopback port the server is listening on
     * @param endpointFilePath where the endpoint descriptor is written
     * @param token            the bearer token to embed
     *
     * @return the snippet
     *
     * @since 3.0
     */
    public static String snippet( Client client, String launcherPath, boolean nativeExecutable,
                                  int port, String endpointFilePath, String token )
    {
        String bearer = token == null || token.isBlank() ? "<token>" : token;
        String url = endpointUrl( port );
        return switch ( client ) {
            case CLAUDE_CODE -> claudeCodeHttp( url, bearer );
            case CURSOR -> cursorHttp( url, bearer );
            case CODEX -> codexHttp( url, bearer );
            case HTTP -> rawHttp( url, bearer );
            case STDIO -> stdio( launcherPath, nativeExecutable, endpointFilePath );
        };
    }

    /**
     * Builds the {@code claude mcp add --transport http} command line.
     *
     * @param url    the endpoint URL
     * @param bearer the bearer token
     *
     * @return the snippet
     */
    private static String claudeCodeHttp( String url, String bearer )
    {
        return "claude mcp add --transport http " + SERVER_NAME + " " + url
                + " --header " + shellQuote( "Authorization: Bearer " + bearer );
    }

    /**
     * Builds the Cursor {@code mcp.json} fragment for an HTTP server.
     *
     * @param url    the endpoint URL
     * @param bearer the bearer token
     *
     * @return the snippet
     */
    private static String cursorHttp( String url, String bearer )
    {
        JsonObject headers = new JsonObject();
        headers.addProperty( "Authorization", "Bearer " + bearer );
        JsonObject server = new JsonObject();
        server.addProperty( "url", url );
        server.add( "headers", headers );

        JsonObject servers = new JsonObject();
        servers.add( SERVER_NAME, server );
        JsonObject root = new JsonObject();
        root.add( "mcpServers", servers );

        return "// ~/.cursor/mcp.json\n" + JSONUtilities.getPrettyGson().toJson( root );
    }

    /**
     * Builds the Codex {@code config.toml} fragment for an HTTP server.
     *
     * @param url    the endpoint URL
     * @param bearer the bearer token
     *
     * @return the snippet
     */
    private static String codexHttp( String url, String bearer )
    {
        return "# ~/.codex/config.toml\n"
                + "[mcp_servers." + SERVER_NAME + "]\n"
                + "url = " + tomlString( url ) + "\n"
                + "\n"
                + "[mcp_servers." + SERVER_NAME + ".http_headers]\n"
                + "Authorization = " + tomlString( "Bearer " + bearer ) + "\n";
    }

    /**
     * Builds the raw URL and header, for adapting to any client not listed.
     *
     * @param url    the endpoint URL
     * @param bearer the bearer token
     *
     * @return the snippet
     */
    private static String rawHttp( String url, String bearer )
    {
        return "URL     " + url + "\n"
                + "Header  Authorization: Bearer " + bearer + "\n"
                + "\n"
                + "Transport is Streamable HTTP (POST JSON-RPC). The launcher must be open.";
    }

    /**
     * Builds the stdio-relay configuration, for a client that cannot speak HTTP.
     *
     * @param launcherPath     where the launcher lives
     * @param nativeExecutable whether that path is a native executable rather than a JAR
     * @param endpointFilePath where the endpoint descriptor is written
     *
     * @return the snippet
     */
    private static String stdio( String launcherPath, boolean nativeExecutable,
                                 String endpointFilePath )
    {
        if ( !isUsableLauncherPath( launcherPath ) ) {
            return UNPACKAGED_NOTICE;
        }
        List< String > command = relayCommand( launcherPath, nativeExecutable );
        StringBuilder line = new StringBuilder();
        for ( String part : command ) {
            if ( line.length() > 0 ) {
                line.append( ' ' );
            }
            line.append( shellQuote( part ) );
        }
        return "For a client that can only spawn a subprocess, run this as the server command:\n"
                + line + "\n"
                + "\n"
                + "It relays stdio to the launcher and reads the port and token from\n"
                + endpointFilePath + " itself, so no token goes in the client's config.";
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

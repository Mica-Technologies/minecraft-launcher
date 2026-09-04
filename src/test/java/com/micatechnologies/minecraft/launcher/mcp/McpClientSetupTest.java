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
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpClientSetup} — the connection snippets shown in Settings.
 *
 * <p>These are generated rather than written into the help text because the interesting part
 * is install-specific, and the specific thing a generic example gets wrong is <b>quoting</b>.
 * The macOS launcher lives at a path containing spaces
 * ({@code /Applications/Mica Minecraft Launcher.app/...}), so an unquoted shell snippet is
 * silently parsed as several arguments and the user gets an error that has nothing to do with
 * MCP. Every format here is checked against a path with a space, and the JSON and TOML forms
 * are checked for being valid JSON and TOML rather than merely looking right.</p>
 *
 * <p>The other property worth pinning: the HTTP form prints the endpoint file's <em>path</em>,
 * never the token inside it. The token grants full access and rotates every launch, and a
 * Settings pane is one screenshot away from a support thread.</p>
 */
class McpClientSetupTest
{
    private static final String JAR = "/Users/someone/Apps/Mica Minecraft Launcher.jar";
    private static final String EXE = "/Applications/Mica Minecraft Launcher.app/Contents/MacOS/launcher";
    private static final String ENDPOINT = "/Users/someone/.MicaMinecraftLauncher/config/mcp-endpoint.json";

    // region the relay command

    @Test
    void aJarIsInvokedThroughJava()
    {
        assertEquals( List.of( "java", "-jar", JAR, "--mcp" ),
                      McpClientSetup.relayCommand( JAR, false ) );
    }

    @Test
    void aNativeExecutableIsInvokedDirectly()
    {
        assertEquals( List.of( EXE, "--mcp" ), McpClientSetup.relayCommand( EXE, true ) );
    }

    // endregion

    /**
     * The JAR check is asked directly rather than reusing
     * {@code DesktopShortcutManager.isNativeExecutable}, which answers a broader question and
     * misclassifies any path ending in "java" — a defect pinned by that class's own tests. The
     * cases below are exactly the ones that would go wrong if this delegated to it.
     */
    @Test
    void onlyAJarPathNeedsTheJavaLauncher()
    {
        assertTrue( McpClientSetup.isJar( "/x/launcher.jar" ) );
        assertTrue( McpClientSetup.isJar( "/x/Launcher.JAR" ) );
        assertFalse( McpClientSetup.isJar( EXE ) );
        assertFalse( McpClientSetup.isJar( "C:\\Mica\\launcher.exe" ) );
        assertFalse( McpClientSetup.isJar( null ) );
        assertFalse( McpClientSetup.isJar( "/opt/mica/java" ),
                     "a native launcher whose name ends in 'java' is not a JAR" );
    }

    // region Claude Code

    @Test
    void theClaudeCodeSnippetIsAnAddCommand()
    {
        String snippet = snippet( McpClientSetup.Client.CLAUDE_CODE, JAR, false );
        assertTrue( snippet.startsWith( "claude mcp add mica-launcher --" ), snippet );
        assertTrue( snippet.contains( "--mcp" ), snippet );
    }

    /**
     * The quoting case that matters. Unquoted, the shell splits the macOS path into three
     * arguments and the user gets a confusing error unrelated to MCP.
     */
    @Test
    void aPathWithSpacesIsShellQuoted()
    {
        String snippet = snippet( McpClientSetup.Client.CLAUDE_CODE, JAR, false );
        assertTrue( snippet.contains( "'" + JAR + "'" ), snippet );
    }

    /** Ordinary arguments are left bare, so the snippet stays readable. */
    @Test
    void argumentsNeedingNoQuotingAreLeftBare()
    {
        assertEquals( "java", McpClientSetup.shellQuote( "java" ) );
        assertEquals( "-jar", McpClientSetup.shellQuote( "-jar" ) );
        assertEquals( "--mcp", McpClientSetup.shellQuote( "--mcp" ) );
        assertEquals( "/usr/local/bin/launcher", McpClientSetup.shellQuote( "/usr/local/bin/launcher" ) );
    }

    /**
     * A single quote cannot appear inside a single-quoted shell string, so it has to be closed,
     * escaped and reopened. A path like {@code /Users/o'brien/...} is not hypothetical.
     */
    @Test
    void anApostropheInAPathIsEscapedForTheShell()
    {
        assertEquals( "'/Users/o'\\''brien/launcher.jar'",
                      McpClientSetup.shellQuote( "/Users/o'brien/launcher.jar" ) );
    }

    @Test
    void shellQuotingHandlesEveryAwkwardCharacter()
    {
        for ( String hostile : new String[]{ "a b", "a;b", "a$b", "a`b", "a\"b", "a|b", "a&b",
                                             "a\nb", "" } ) {
            String quoted = McpClientSetup.shellQuote( hostile );
            assertTrue( quoted.startsWith( "'" ) && quoted.endsWith( "'" ),
                        "should be quoted: " + hostile + " -> " + quoted );
        }
    }

    // endregion

    // region Cursor

    /** The snippet has to be valid JSON, not merely look like it. */
    @Test
    void theCursorSnippetIsValidJsonWithTheRightShape()
    {
        String snippet = snippet( McpClientSetup.Client.CURSOR, JAR, false );
        String json = snippet.substring( snippet.indexOf( '{' ) );
        JsonObject root = JSONUtilities.getGson().fromJson( json, JsonObject.class );

        JsonObject server = root.getAsJsonObject( "mcpServers" )
                .getAsJsonObject( McpClientSetup.SERVER_NAME );
        assertEquals( "java", server.get( "command" ).getAsString() );
        assertEquals( "-jar", server.getAsJsonArray( "args" ).get( 0 ).getAsString() );
        assertEquals( JAR, server.getAsJsonArray( "args" ).get( 1 ).getAsString(),
                      "the path is one array element, so it needs no quoting of its own" );
        assertEquals( "--mcp", server.getAsJsonArray( "args" ).get( 2 ).getAsString() );
    }

    @Test
    void theCursorSnippetNamesTheFileItGoesIn()
    {
        assertTrue( snippet( McpClientSetup.Client.CURSOR, JAR, false ).contains( ".cursor/mcp.json" ) );
    }

    @Test
    void aNativeExecutableIsTheCursorCommandItself()
    {
        String snippet = snippet( McpClientSetup.Client.CURSOR, EXE, true );
        JsonObject root = JSONUtilities.getGson()
                .fromJson( snippet.substring( snippet.indexOf( '{' ) ), JsonObject.class );
        JsonObject server = root.getAsJsonObject( "mcpServers" )
                .getAsJsonObject( McpClientSetup.SERVER_NAME );
        assertEquals( EXE, server.get( "command" ).getAsString() );
        assertEquals( 1, server.getAsJsonArray( "args" ).size() );
    }

    // endregion

    // region Codex

    @Test
    void theCodexSnippetIsATomlTable()
    {
        String snippet = snippet( McpClientSetup.Client.CODEX, JAR, false );
        assertTrue( snippet.contains( "[mcp_servers." + McpClientSetup.SERVER_NAME + "]" ), snippet );
        assertTrue( snippet.contains( "command = \"java\"" ), snippet );
        assertTrue( snippet.contains( "\"" + JAR + "\"" ), snippet );
        assertTrue( snippet.contains( "\"--mcp\"" ), snippet );
        assertTrue( snippet.contains( ".codex/config.toml" ), snippet );
    }

    /** TOML basic strings escape backslashes, which matters on Windows paths. */
    @Test
    void aWindowsPathIsEscapedForToml()
    {
        assertEquals( "\"C:\\\\Program Files\\\\Mica\\\\launcher.exe\"",
                      McpClientSetup.tomlString( "C:\\Program Files\\Mica\\launcher.exe" ) );
    }

    @Test
    void aQuoteInsideAPathIsEscapedForToml()
    {
        assertEquals( "\"say \\\"hi\\\"\"", McpClientSetup.tomlString( "say \"hi\"" ) );
    }

    // endregion

    // region raw HTTP

    /**
     * The property worth keeping: the endpoint file's path is shown, its contents are not. The
     * token grants full MCP access and rotates every launch.
     */
    @Test
    void theHttpSnippetNamesTheEndpointFileButNotTheToken()
    {
        String snippet = McpClientSetup.snippet( McpClientSetup.Client.HTTP, JAR, false, 51234,
                                                 ENDPOINT );
        assertTrue( snippet.contains( ENDPOINT ), snippet );
        assertTrue( snippet.contains( "<token>" ), "the token must be a placeholder: " + snippet );
        assertTrue( snippet.contains( "http://127.0.0.1:51234/mcp" ), snippet );
    }

    /** It also says why stdio is preferable, since the port and token both rotate. */
    @Test
    void theHttpSnippetWarnsThatThePortAndTokenRotate()
    {
        String snippet = McpClientSetup.snippet( McpClientSetup.Client.HTTP, JAR, false, 51234,
                                                 ENDPOINT );
        assertTrue( snippet.contains( "change every time" ), snippet );
    }

    @Test
    void aStoppedServerSaysSoRatherThanPrintingPortZero()
    {
        String snippet = McpClientSetup.snippet( McpClientSetup.Client.HTTP, JAR, false, 0, ENDPOINT );
        assertFalse( snippet.contains( ":0/mcp" ), snippet );
        assertTrue( snippet.contains( "not running" ), snippet );
    }

    // endregion

    // region a development run has no launcher command

    /**
     * Found by rendering the snippets on a real source checkout.
     * {@code DesktopShortcutManager.resolveLauncherPath()} falls back to the running JVM's own
     * executable when there is no packaged app image, so the snippet came out as
     * {@code .../bin/java --mcp} — which every client accepts and which then starts a bare JVM
     * with no classpath and does nothing. A snippet that silently fails is worse than none.
     */
    @Test
    void aJvmPathIsNotMistakenForALauncher()
    {
        for ( String jvm : new String[]{ "/Library/Java/JavaVirtualMachines/azul-26/Contents/Home/bin/java",
                                         "C:\\Program Files\\Java\\jdk-26\\bin\\java.exe",
                                         "C:\\Program Files\\Java\\jdk-26\\bin\\javaw.exe" } ) {
            assertFalse( McpClientSetup.isUsableLauncherPath( jvm ), jvm );
        }
    }

    @Test
    void realLauncherPathsAreUsable()
    {
        assertTrue( McpClientSetup.isUsableLauncherPath( JAR ) );
        assertTrue( McpClientSetup.isUsableLauncherPath( EXE ) );
        assertTrue( McpClientSetup.isUsableLauncherPath( "C:\\Mica\\launcher.exe" ) );
        assertFalse( McpClientSetup.isUsableLauncherPath( null ) );
        assertFalse( McpClientSetup.isUsableLauncherPath( "  " ) );
    }

    /** Every stdio client says so plainly rather than emitting a command that cannot work. */
    @Test
    void aDevelopmentRunExplainsItselfInsteadOfEmittingABadCommand()
    {
        String jvm = "/Library/Java/JavaVirtualMachines/azul-26/Contents/Home/bin/java";
        for ( McpClientSetup.Client client : new McpClientSetup.Client[]{
                McpClientSetup.Client.CLAUDE_CODE, McpClientSetup.Client.CURSOR,
                McpClientSetup.Client.CODEX } ) {
            String snippet = McpClientSetup.snippet( client, jvm, true, 51234, ENDPOINT );
            assertFalse( snippet.contains( "bin/java" ),
                         client + " emitted a JVM path as a launcher command: " + snippet );
            assertTrue( snippet.contains( "running from source" ), client + ": " + snippet );
        }
    }

    /** The HTTP details still work in a development run — they do not need a launcher path. */
    @Test
    void theHttpDetailsSurviveADevelopmentRun()
    {
        String snippet = McpClientSetup.snippet( McpClientSetup.Client.HTTP,
                                                 "/x/bin/java", true, 51234, ENDPOINT );
        assertTrue( snippet.contains( "http://127.0.0.1:51234/mcp" ), snippet );
        assertTrue( snippet.contains( ENDPOINT ), snippet );
    }

    // endregion

    // region every client

    /** No snippet may be empty, and none may contain an unsubstituted placeholder. */
    @Test
    void everyClientProducesAUsableSnippet()
    {
        for ( McpClientSetup.Client client : McpClientSetup.Client.values() ) {
            String snippet = McpClientSetup.snippet( client, JAR, false, 51234, ENDPOINT );
            assertFalse( snippet.isBlank(), client + " produced nothing" );
            assertFalse( snippet.contains( "null" ), client + " leaked a null: " + snippet );
        }
    }

    /** Every stdio form must actually reference the launcher and the relay flag. */
    @Test
    void everyStdioClientReferencesTheLauncherAndTheRelayFlag()
    {
        for ( McpClientSetup.Client client : new McpClientSetup.Client[]{
                McpClientSetup.Client.CLAUDE_CODE, McpClientSetup.Client.CURSOR,
                McpClientSetup.Client.CODEX } ) {
            String snippet = McpClientSetup.snippet( client, JAR, false, 51234, ENDPOINT );
            assertTrue( snippet.contains( JAR ), client + " omitted the launcher path" );
            assertTrue( snippet.contains( "--mcp" ), client + " omitted the relay flag" );
            assertFalse( snippet.contains( "51234" ),
                         client + " should use stdio, not the HTTP port" );
        }
    }

    // endregion

    private static String snippet( McpClientSetup.Client client, String path, boolean nativeExe )
    {
        return McpClientSetup.snippet( client, path, nativeExe, 51234, ENDPOINT );
    }
}

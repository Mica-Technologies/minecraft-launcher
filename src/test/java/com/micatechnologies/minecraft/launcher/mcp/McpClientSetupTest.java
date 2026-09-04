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
    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

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

    // region Claude Code (HTTP)

    @Test
    void theClaudeCodeSnippetAddsAnHttpTransport()
    {
        String snippet = http( McpClientSetup.Client.CLAUDE_CODE );
        assertTrue( snippet.startsWith( "claude mcp add --transport http mica-launcher " ), snippet );
        assertTrue( snippet.contains( "http://127.0.0.1:47824/mcp" ), snippet );
        assertTrue( snippet.contains( "Authorization: Bearer " + TOKEN ), snippet );
    }

    /**
     * The header carries a space, so it must be quoted or the shell splits it and
     * {@code --header} receives only "Authorization:".
     */
    @Test
    void theAuthorizationHeaderIsShellQuoted()
    {
        assertTrue( http( McpClientSetup.Client.CLAUDE_CODE )
                            .contains( "'Authorization: Bearer " + TOKEN + "'" ) );
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
    void anApostropheIsEscapedForTheShell()
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

    // region Cursor (HTTP)

    /** The snippet has to be valid JSON, not merely look like it. */
    @Test
    void theCursorSnippetIsValidJsonWithTheRightShape()
    {
        String snippet = http( McpClientSetup.Client.CURSOR );
        JsonObject root = JSONUtilities.getGson()
                .fromJson( snippet.substring( snippet.indexOf( '{' ) ), JsonObject.class );
        JsonObject server = root.getAsJsonObject( "mcpServers" )
                .getAsJsonObject( McpClientSetup.SERVER_NAME );

        assertEquals( "http://127.0.0.1:47824/mcp", server.get( "url" ).getAsString() );
        assertEquals( "Bearer " + TOKEN,
                      server.getAsJsonObject( "headers" ).get( "Authorization" ).getAsString() );
    }

    @Test
    void theCursorSnippetNamesTheFileItGoesIn()
    {
        assertTrue( http( McpClientSetup.Client.CURSOR ).contains( ".cursor/mcp.json" ) );
    }

    // endregion

    // region Codex (HTTP)

    @Test
    void theCodexSnippetIsATomlTableWithHeaders()
    {
        String snippet = http( McpClientSetup.Client.CODEX );
        assertTrue( snippet.contains( "[mcp_servers." + McpClientSetup.SERVER_NAME + "]" ), snippet );
        assertTrue( snippet.contains( "url = \"http://127.0.0.1:47824/mcp\"" ), snippet );
        assertTrue( snippet.contains( "Authorization = \"Bearer " + TOKEN + "\"" ), snippet );
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
    void aQuoteInsideAValueIsEscapedForToml()
    {
        assertEquals( "\"say \\\"hi\\\"\"", McpClientSetup.tomlString( "say \"hi\"" ) );
    }

    // endregion

    // region raw HTTP and stdio

    @Test
    void theRawFormGivesJustTheUrlAndHeader()
    {
        String snippet = http( McpClientSetup.Client.HTTP );
        assertTrue( snippet.contains( "http://127.0.0.1:47824/mcp" ), snippet );
        assertTrue( snippet.contains( "Authorization: Bearer " + TOKEN ), snippet );
    }

    /**
     * The stdio relay stays available for clients that cannot speak HTTP, and keeps its one
     * genuine advantage: it reads the token itself, so none goes into the client's config.
     */
    @Test
    void theStdioFormEmbedsNoTokenAtAll()
    {
        String snippet = McpClientSetup.snippet( McpClientSetup.Client.STDIO, JAR, false, 47824,
                                                 ENDPOINT, TOKEN );
        assertTrue( snippet.contains( "--mcp" ), snippet );
        assertTrue( snippet.contains( "'" + JAR + "'" ), "the path still needs quoting: " + snippet );
        assertFalse( snippet.contains( TOKEN ), "the relay reads the token itself: " + snippet );
        assertTrue( snippet.contains( ENDPOINT ), snippet );
    }

    /** A missing token renders as a placeholder rather than an empty header. */
    @Test
    void anUngeneratedTokenRendersAsAPlaceholder()
    {
        for ( McpClientSetup.Client client : new McpClientSetup.Client[]{
                McpClientSetup.Client.CLAUDE_CODE, McpClientSetup.Client.CURSOR,
                McpClientSetup.Client.CODEX, McpClientSetup.Client.HTTP } ) {
            String snippet = McpClientSetup.snippet( client, JAR, false, 47824, ENDPOINT, "" );
            // Compared after decoding: Gson HTML-escapes '<' to \u003c in the JSON form, so a
            // raw substring check would fail on Cursor while the value a client reads is right.
            assertTrue( decodeUnicode( snippet ).contains( "<token>" ), client + ": " + snippet );
        }
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
        String snippet = McpClientSetup.snippet( McpClientSetup.Client.STDIO, jvm, true, 47824,
                                                 ENDPOINT, TOKEN );
        assertFalse( snippet.contains( "bin/java" ),
                     "a JVM path must not be emitted as a launcher command: " + snippet );
        assertTrue( snippet.contains( "running from source" ), snippet );
    }

    /**
     * The HTTP forms need no launcher path at all, which is the other reason they are the
     * primary recommendation: they work identically from a source checkout and an installed
     * build.
     */
    @Test
    void theHttpFormsDoNotDependOnALauncherPath()
    {
        String jvm = "/Library/Java/JavaVirtualMachines/azul-26/Contents/Home/bin/java";
        for ( McpClientSetup.Client client : new McpClientSetup.Client[]{
                McpClientSetup.Client.CLAUDE_CODE, McpClientSetup.Client.CURSOR,
                McpClientSetup.Client.CODEX, McpClientSetup.Client.HTTP } ) {
            String snippet = McpClientSetup.snippet( client, jvm, true, 47824, ENDPOINT, TOKEN );
            assertFalse( snippet.contains( "running from source" ), client + ": " + snippet );
            assertTrue( snippet.contains( "47824" ), client + ": " + snippet );
        }
    }

    /** Only the stdio form names the endpoint file, because only it reads that file. */
    @Test
    void onlyTheStdioFormNamesTheEndpointFile()
    {
        assertTrue( McpClientSetup.snippet( McpClientSetup.Client.STDIO, JAR, false, 47824,
                                            ENDPOINT, TOKEN ).contains( ENDPOINT ) );
        assertFalse( http( McpClientSetup.Client.HTTP ).contains( ENDPOINT ) );
    }

    // endregion

    // region every client

    /** No snippet may be empty, and none may leak a null. */
    @Test
    void everyClientProducesAUsableSnippet()
    {
        for ( McpClientSetup.Client client : McpClientSetup.Client.values() ) {
            String snippet = McpClientSetup.snippet( client, JAR, false, 47824, ENDPOINT, TOKEN );
            assertFalse( snippet.isBlank(), client + " produced nothing" );
            assertFalse( snippet.contains( "null" ), client + " leaked a null: " + snippet );
        }
    }

    /**
     * Every HTTP form must carry both halves of the credential. A snippet with the URL but no
     * header, or the reverse, fails at connect time with an error the user cannot act on.
     */
    @Test
    void everyHttpFormCarriesBothTheUrlAndTheToken()
    {
        for ( McpClientSetup.Client client : new McpClientSetup.Client[]{
                McpClientSetup.Client.CLAUDE_CODE, McpClientSetup.Client.CURSOR,
                McpClientSetup.Client.CODEX, McpClientSetup.Client.HTTP } ) {
            String snippet = http( client );
            assertTrue( snippet.contains( "127.0.0.1:47824" ), client + " omitted the URL" );
            assertTrue( snippet.contains( TOKEN ), client + " omitted the token" );
            assertFalse( snippet.contains( "--mcp" ),
                         client + " should be HTTP, not the stdio relay" );
        }
    }

    /** The URL is built from whatever port the server actually bound. */
    @Test
    void theUrlFollowsTheBoundPort()
    {
        assertEquals( "http://127.0.0.1:47823/mcp", McpClientSetup.endpointUrl( 47823 ) );
        assertTrue( McpClientSetup.snippet( McpClientSetup.Client.HTTP, JAR, false, 51234,
                                            ENDPOINT, TOKEN ).contains( ":51234/mcp" ) );
    }

    // endregion

    /** Decodes \\uXXXX escapes, so assertions compare the value a client reads. */
    private static String decodeUnicode( String text )
    {
        StringBuilder out = new StringBuilder();
        for ( int i = 0; i < text.length(); i++ ) {
            if ( text.charAt( i ) == '\\' && i + 5 < text.length() && text.charAt( i + 1 ) == 'u' ) {
                try {
                    out.append( (char) Integer.parseInt( text.substring( i + 2, i + 6 ), 16 ) );
                    i += 5;
                    continue;
                }
                catch ( NumberFormatException ignored ) {
                    // Not an escape after all; fall through and copy the character.
                }
            }
            out.append( text.charAt( i ) );
        }
        return out.toString();
    }

    private static String http( McpClientSetup.Client client )
    {
        return McpClientSetup.snippet( client, JAR, false, 47824, ENDPOINT, TOKEN );
    }

    private static String snippet( McpClientSetup.Client client, String path, boolean nativeExe )
    {
        return McpClientSetup.snippet( client, path, nativeExe, 51234, ENDPOINT );
    }
}

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

package com.micatechnologies.minecraft.launcher.game.modpack.manifests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.RuntimeConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link GameLibraryManifest}'s client.json field-extraction seams —
 * {@code extractRequiredJavaMajorVersion}, {@code extractRequiredRuntimeComponent},
 * {@code extractVanillaMainClass}, {@code extractJvmArguments}, {@code extractGameArguments},
 * {@code extractLoggingConfig}, and {@code extractAssetIndexVersion} — all widened to
 * package-private and extracted from their {@code readToJsonObject()}-calling public
 * counterparts so they can be exercised with a hand-built manifest instead of a real,
 * network-backed {@link GameLibraryManifest} instance (which needs a fully constructed
 * {@code GameModPack}).
 *
 * <p>Why this matters: these fields decide the Java runtime a modpack launches under, whether
 * the modern (1.13+) or legacy argument format is used to build the JVM/game command line, the
 * main class that gets invoked, and the log4j config wired in for crash-report correlation. A
 * client.json is downloaded straight from Mojang's CDN via a URL resolved from the (also
 * externally hosted) version manifest — not attacker-controlled in the usual sense, but any
 * format drift (a field renamed, restructured, or omitted in an edge-case release) needs to
 * degrade to the documented fallback rather than throw or silently produce an empty command
 * line. That is exactly the behaviour pinned here.</p>
 */
class GameLibraryManifestParsingTest
{
    // =========================================================================
    //  extractRequiredJavaMajorVersion / extractRequiredRuntimeComponent
    // =========================================================================

    @Test
    void readsTheDeclaredJavaMajorVersion()
    {
        JsonObject javaVersion = new JsonObject();
        javaVersion.addProperty( "majorVersion", 17 );
        JsonObject manifest = new JsonObject();
        manifest.add( "javaVersion", javaVersion );

        assertEquals( 17, GameLibraryManifest.extractRequiredJavaMajorVersion( manifest ) );
    }

    @Test
    void javaMajorVersionFallsBackToDefaultWhenAbsent()
    {
        assertEquals( RuntimeConstants.DEFAULT_JAVA_MAJOR_VERSION,
                      GameLibraryManifest.extractRequiredJavaMajorVersion( new JsonObject() ) );
    }

    @Test
    void readsTheDeclaredRuntimeComponent()
    {
        JsonObject javaVersion = new JsonObject();
        javaVersion.addProperty( "component", "jre-legacy" );
        JsonObject manifest = new JsonObject();
        manifest.add( "javaVersion", javaVersion );

        assertEquals( "jre-legacy", GameLibraryManifest.extractRequiredRuntimeComponent( manifest ) );
    }

    @Test
    void runtimeComponentFallsBackToDefaultWhenAbsent()
    {
        assertEquals( RuntimeConstants.DEFAULT_RUNTIME_COMPONENT,
                      GameLibraryManifest.extractRequiredRuntimeComponent( new JsonObject() ) );
    }

    // =========================================================================
    //  extractVanillaMainClass
    // =========================================================================

    @Test
    void readsTheDeclaredMainClass()
    {
        JsonObject manifest = new JsonObject();
        manifest.addProperty( "mainClass", "net.minecraft.client.main.Main" );

        assertEquals( "net.minecraft.client.main.Main", GameLibraryManifest.extractVanillaMainClass( manifest ) );
    }

    @Test
    void mainClassIsNullWhenAbsent()
    {
        assertNull( GameLibraryManifest.extractVanillaMainClass( new JsonObject() ) );
    }

    // =========================================================================
    //  extractJvmArguments
    // =========================================================================

    @Test
    void flattensTheModernJvmArgumentsArray()
    {
        JsonArray jvmArgs = new JsonArray();
        jvmArgs.add( "-Xmx2G" );
        JsonObject arguments = new JsonObject();
        arguments.add( "jvm", jvmArgs );
        JsonObject manifest = new JsonObject();
        manifest.add( "arguments", arguments );

        assertEquals( "-Xmx2G", GameLibraryManifest.extractJvmArguments( manifest ) );
    }

    @Test
    void jvmArgumentsAreEmptyForALegacyManifestWithNoArgumentsBlock()
    {
        assertEquals( "", GameLibraryManifest.extractJvmArguments( new JsonObject() ) );
    }

    @Test
    void jvmArgumentsAreEmptyWhenArgumentsBlockHasNoJvmKey()
    {
        JsonObject manifest = new JsonObject();
        manifest.add( "arguments", new JsonObject() );

        assertEquals( "", GameLibraryManifest.extractJvmArguments( manifest ) );
    }

    // =========================================================================
    //  extractGameArguments
    // =========================================================================

    @Test
    void flattensTheModernGameArgumentsArray()
    {
        JsonArray gameArgs = new JsonArray();
        gameArgs.add( "--username" );
        gameArgs.add( "${auth_player_name}" );
        JsonObject arguments = new JsonObject();
        arguments.add( "game", gameArgs );
        JsonObject manifest = new JsonObject();
        manifest.add( "arguments", arguments );

        assertEquals( "--username ${auth_player_name}", GameLibraryManifest.extractGameArguments( manifest ) );
    }

    /**
     * Pre-1.13 manifests carry a single {@code minecraftArguments} string instead of the modern
     * {@code arguments.game} array; that legacy field must still surface verbatim rather than
     * being dropped once the modern-format branch was added.
     */
    @Test
    void fallsBackToLegacyMinecraftArgumentsString()
    {
        JsonObject manifest = new JsonObject();
        manifest.addProperty( "minecraftArguments", "--username ${auth_player_name} --version ${version_name}" );

        assertEquals( "--username ${auth_player_name} --version ${version_name}",
                      GameLibraryManifest.extractGameArguments( manifest ) );
    }

    @Test
    void gameArgumentsAreEmptyWhenNeitherFormIsPresent()
    {
        assertEquals( "", GameLibraryManifest.extractGameArguments( new JsonObject() ) );
    }

    /**
     * When both a modern {@code arguments.game} array and (implausibly) a legacy
     * {@code minecraftArguments} string are present, the modern array wins — the legacy fallback
     * is only ever consulted once the modern branch has definitively found nothing.
     */
    @Test
    void modernGameArgumentsTakePrecedenceOverLegacyFallback()
    {
        JsonArray gameArgs = new JsonArray();
        gameArgs.add( "--modernArg" );
        JsonObject arguments = new JsonObject();
        arguments.add( "game", gameArgs );
        JsonObject manifest = new JsonObject();
        manifest.add( "arguments", arguments );
        manifest.addProperty( "minecraftArguments", "--legacyArg" );

        assertEquals( "--modernArg", GameLibraryManifest.extractGameArguments( manifest ) );
    }

    // =========================================================================
    //  extractLoggingConfig
    // =========================================================================

    private static JsonObject loggingManifest( String argument, String url, String sha1, String id )
    {
        JsonObject file = new JsonObject();
        if ( url != null ) file.addProperty( "url", url );
        if ( sha1 != null ) file.addProperty( "sha1", sha1 );
        if ( id != null ) file.addProperty( "id", id );

        JsonObject clientLogging = new JsonObject();
        if ( argument != null ) clientLogging.addProperty( "argument", argument );
        clientLogging.add( "file", file );

        JsonObject logging = new JsonObject();
        logging.add( "client", clientLogging );

        JsonObject manifest = new JsonObject();
        manifest.add( "logging", logging );
        return manifest;
    }

    @Test
    void readsACompleteLoggingConfig()
    {
        JsonObject manifest = loggingManifest( "-Dlog4j.configurationFile=${path}",
                                               "https://example.test/log4j2.xml", "abc123", "client-1.12.xml" );

        String[] result = GameLibraryManifest.extractLoggingConfig( manifest );

        assertArrayEquals( new String[]{ "-Dlog4j.configurationFile=${path}",
                                         "https://example.test/log4j2.xml", "abc123", "client-1.12.xml" },
                            result );
    }

    @Test
    void loggingConfigIsNullWhenLoggingBlockAbsent()
    {
        assertNull( GameLibraryManifest.extractLoggingConfig( new JsonObject() ) );
    }

    @Test
    void loggingConfigIsNullWhenClientBlockAbsent()
    {
        JsonObject manifest = new JsonObject();
        manifest.add( "logging", new JsonObject() );

        assertNull( GameLibraryManifest.extractLoggingConfig( manifest ) );
    }

    /**
     * The argument template and the file URL are both required for the config to be usable —
     * an entry declaring only one (a malformed or truncated manifest) must not produce a
     * half-populated result that a caller could mistake for complete.
     */
    @Test
    void loggingConfigIsNullWhenArgumentIsMissingEvenIfFileUrlPresent()
    {
        JsonObject manifest = loggingManifest( null, "https://example.test/log4j2.xml", "abc123", "id" );

        assertNull( GameLibraryManifest.extractLoggingConfig( manifest ) );
    }

    @Test
    void loggingConfigIsNullWhenFileUrlIsMissingEvenIfArgumentPresent()
    {
        JsonObject manifest = loggingManifest( "-Dlog4j.configurationFile=${path}", null, null, null );

        assertNull( GameLibraryManifest.extractLoggingConfig( manifest ) );
    }

    // =========================================================================
    //  extractAssetIndexVersion
    // =========================================================================

    @Test
    void readsTheAssetIndexId() throws ModpackException
    {
        JsonObject assetIndex = new JsonObject();
        assetIndex.addProperty( "id", "1.20" );
        JsonObject manifest = new JsonObject();
        manifest.add( "assetIndex", assetIndex );

        assertEquals( "1.20", GameLibraryManifest.extractAssetIndexVersion( manifest ) );
    }

    @Test
    void throwsWhenAssetIndexBlockIsMissing()
    {
        // JsonHelper's required-field accessors throw unchecked IllegalArgumentException, not
        // ModpackException -- the checked declaration on extractAssetIndexVersion exists for
        // interface parity with its readToJsonObject()-calling caller, but nothing in this pure
        // extraction path actually throws it.
        assertThrows( IllegalArgumentException.class,
                       () -> GameLibraryManifest.extractAssetIndexVersion( new JsonObject() ) );
    }

    @Test
    void throwsWhenAssetIndexIdIsMissing()
    {
        JsonObject manifest = new JsonObject();
        manifest.add( "assetIndex", new JsonObject() );

        assertThrows( IllegalArgumentException.class,
                       () -> GameLibraryManifest.extractAssetIndexVersion( manifest ) );
    }
}

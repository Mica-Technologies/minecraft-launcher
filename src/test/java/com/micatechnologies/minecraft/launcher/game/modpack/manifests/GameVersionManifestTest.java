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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link GameVersionManifest#findLibraryManifestUrl(JsonArray, String)},
 * {@link GameVersionManifest#extractRequiredJavaMajorVersion(JsonObject)}, and
 * {@link GameVersionManifest#extractRequiredRuntimeComponent(JsonObject)} — the pure
 * JSON-scanning seams behind Mojang version resolution, extracted (widened to
 * package-private) from methods that otherwise require a network-downloaded manifest.
 *
 * <p>Why this matters: {@code findLibraryManifestUrl} is the very first step of resolving a
 * Minecraft version — it turns a version id string into the client.json URL that every
 * downstream library/native/argument decision is built from. Before this class had any tests,
 * a regression here (e.g. matching the wrong entry, or matching on a prefix instead of an exact
 * id) would silently point the launcher at the wrong Minecraft version, with no compile-time or
 * fast-fail signal — it would only surface as a mysterious wrong-version launch or a missing
 * asset far later in the pipeline. The two {@code extractRequired*} methods decide which Java
 * runtime a modpack launches under; getting them wrong means either a runtime download that
 * doesn't match what the game actually needs, or (for the default fallback) launching modern
 * versions under a JRE that predates records/sealed classes.</p>
 */
class GameVersionManifestTest
{
    private static JsonObject versionEntry( String id, String url )
    {
        JsonObject entry = new JsonObject();
        entry.addProperty( "id", id );
        entry.addProperty( "url", url );
        return entry;
    }

    private static JsonArray versionsOf( JsonObject... entries )
    {
        JsonArray array = new JsonArray();
        for ( JsonObject entry : entries ) {
            array.add( entry );
        }
        return array;
    }

    // =========================================================================
    //  findLibraryManifestUrl
    // =========================================================================

    @Test
    void findsTheUrlForTheRequestedVersionId() throws ModpackException
    {
        JsonArray versions = versionsOf( versionEntry( "1.20.1", "https://example.test/1.20.1.json" ),
                                         versionEntry( "1.19.2", "https://example.test/1.19.2.json" ) );

        assertEquals( "https://example.test/1.20.1.json",
                      GameVersionManifest.findLibraryManifestUrl( versions, "1.20.1" ) );
    }

    @Test
    void picksTheMatchingEntryRatherThanTheFirstOne() throws ModpackException
    {
        JsonArray versions = versionsOf( versionEntry( "1.19.2", "https://example.test/1.19.2.json" ),
                                         versionEntry( "1.20.1", "https://example.test/1.20.1.json" ),
                                         versionEntry( "1.20.4", "https://example.test/1.20.4.json" ) );

        assertEquals( "https://example.test/1.20.4.json",
                      GameVersionManifest.findLibraryManifestUrl( versions, "1.20.4" ) );
    }

    @Test
    void throwsWhenTheRequestedVersionIsNotPresent()
    {
        JsonArray versions = versionsOf( versionEntry( "1.20.1", "https://example.test/1.20.1.json" ) );

        assertThrows( ModpackException.class,
                       () -> GameVersionManifest.findLibraryManifestUrl( versions, "99.99.99" ) );
    }

    @Test
    void throwsOnAnEmptyVersionsArray()
    {
        assertThrows( ModpackException.class,
                       () -> GameVersionManifest.findLibraryManifestUrl( new JsonArray(), "1.20.1" ) );
    }

    /**
     * Version id matching is exact equality, not a prefix or substring match — "1.20" must not
     * satisfy a lookup for "1.20.1", otherwise a modpack requesting an unlisted point release
     * could silently resolve to an unrelated earlier version.
     */
    @Test
    void matchingIsExactNotAPrefixMatch()
    {
        JsonArray versions = versionsOf( versionEntry( "1.20", "https://example.test/1.20.json" ) );

        assertThrows( ModpackException.class,
                       () -> GameVersionManifest.findLibraryManifestUrl( versions, "1.20.1" ) );
    }

    // =========================================================================
    //  extractRequiredJavaMajorVersion
    // =========================================================================

    @Test
    void readsTheDeclaredJavaMajorVersion()
    {
        JsonObject javaVersion = new JsonObject();
        javaVersion.addProperty( "majorVersion", 21 );
        JsonObject clientJson = new JsonObject();
        clientJson.add( "javaVersion", javaVersion );

        assertEquals( 21, GameVersionManifest.extractRequiredJavaMajorVersion( clientJson ) );
    }

    @Test
    void fallsBackToTheDefaultWhenJavaVersionBlockIsAbsent()
    {
        assertEquals( RuntimeConstants.DEFAULT_JAVA_MAJOR_VERSION,
                      GameVersionManifest.extractRequiredJavaMajorVersion( new JsonObject() ) );
    }

    @Test
    void fallsBackToTheDefaultWhenMajorVersionFieldIsAbsent()
    {
        JsonObject clientJson = new JsonObject();
        clientJson.add( "javaVersion", new JsonObject() );

        assertEquals( RuntimeConstants.DEFAULT_JAVA_MAJOR_VERSION,
                      GameVersionManifest.extractRequiredJavaMajorVersion( clientJson ) );
    }

    // =========================================================================
    //  extractRequiredRuntimeComponent
    // =========================================================================

    @Test
    void readsTheDeclaredRuntimeComponent()
    {
        JsonObject javaVersion = new JsonObject();
        javaVersion.addProperty( "component", "java-runtime-gamma" );
        JsonObject clientJson = new JsonObject();
        clientJson.add( "javaVersion", javaVersion );

        assertEquals( "java-runtime-gamma", GameVersionManifest.extractRequiredRuntimeComponent( clientJson ) );
    }

    @Test
    void fallsBackToTheDefaultComponentWhenJavaVersionBlockIsAbsent()
    {
        assertEquals( RuntimeConstants.DEFAULT_RUNTIME_COMPONENT,
                      GameVersionManifest.extractRequiredRuntimeComponent( new JsonObject() ) );
    }

    @Test
    void fallsBackToTheDefaultComponentWhenComponentFieldIsAbsent()
    {
        JsonObject clientJson = new JsonObject();
        clientJson.add( "javaVersion", new JsonObject() );

        assertEquals( RuntimeConstants.DEFAULT_RUNTIME_COMPONENT,
                      GameVersionManifest.extractRequiredRuntimeComponent( clientJson ) );
    }
}

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
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.game.modpack.GameLibrary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GameLibraryManifest#extractLibraries(JsonObject)} — the scan that turns a
 * client.json's {@code libraries} array into the {@link GameLibrary} objects that actually get
 * downloaded and placed on the classpath (or extracted as natives).
 *
 * <p>Why this matters: this is the single most consequential piece of logic in the manifest
 * chain. Getting it wrong doesn't fail loudly — it silently omits a library the game needs (a
 * {@code NoClassDefFoundError} deep inside Minecraft/Forge startup), includes a library for the
 * wrong platform (wasted download, or worse, a native `.dll` on Linux that never loads), or drops
 * a native classifier so LWJGL throws {@code UnsatisfiedLinkError} at window-creation time. Method
 * was previously reachable only through a fully downloaded manifest file behind a real
 * {@code GameModPack}, so it had zero direct coverage despite being ~150 lines of nested
 * conditionals. Extracted (widened to package-private static) so it can be exercised with
 * hand-built manifests instead.</p>
 *
 * <p><b>Platform independence.</b> Like {@code ManifestRuleUtilitiesTest}, these tests ask
 * {@link ManifestRuleUtilities#getCurrentPlatformName()} for the running platform rather than
 * hard-coding one, so the suite behaves identically on all three CI platforms. The
 * windows/macOS/Linux native-classifier branches inside {@code extractLibraries} are each gated
 * by the real host OS ({@code SystemUtils.IS_OS_*}), so a single run only exercises the branch
 * for its own platform — exactly like the CI matrix exercises all three collectively.</p>
 */
class GameLibraryManifestExtractLibrariesTest
{
    private static final String CURRENT = ManifestRuleUtilities.getCurrentPlatformName();
    private static final String NOT_CURRENT = "windows".equals( CURRENT ) ? "linux" : "windows";

    // =========================================================================
    //  Helpers
    // =========================================================================

    private static JsonObject artifactObject( String path, String sha1, String url )
    {
        JsonObject artifact = new JsonObject();
        artifact.addProperty( "path", path );
        artifact.addProperty( "sha1", sha1 );
        artifact.addProperty( "url", url );
        return artifact;
    }

    private static JsonObject ruleObj( String action, String osName )
    {
        JsonObject r = new JsonObject();
        r.addProperty( "action", action );
        if ( osName != null ) {
            JsonObject os = new JsonObject();
            os.addProperty( "name", osName );
            r.add( "os", os );
        }
        return r;
    }

    private static JsonArray rulesOf( JsonObject... rules )
    {
        JsonArray a = new JsonArray();
        for ( JsonObject r : rules ) {
            a.add( r );
        }
        return a;
    }

    private static JsonObject libraryEntry( String name, JsonObject artifactObj, JsonArray rules,
                                            JsonObject classifiers )
    {
        JsonObject downloads = new JsonObject();
        if ( artifactObj != null ) {
            downloads.add( "artifact", artifactObj );
        }
        if ( classifiers != null ) {
            downloads.add( "classifiers", classifiers );
        }

        JsonObject lib = new JsonObject();
        lib.addProperty( "name", name );
        lib.add( "downloads", downloads );
        if ( rules != null ) {
            lib.add( "rules", rules );
        }
        return lib;
    }

    private static JsonObject manifestOf( JsonObject... libraries )
    {
        JsonArray libs = new JsonArray();
        for ( JsonObject l : libraries ) {
            libs.add( l );
        }
        JsonObject manifest = new JsonObject();
        manifest.add( "libraries", libs );
        return manifest;
    }

    // =========================================================================
    //  Basic inclusion / exclusion
    // =========================================================================

    @Test
    void manifestWithNoLibrariesYieldsAnEmptyList() throws ModpackException
    {
        ArrayList< GameLibrary > libs = GameLibraryManifest.extractLibraries( manifestOf() );
        assertTrue( libs.isEmpty() );
    }

    @Test
    void includesALibraryWithNoRulesForTheCurrentPlatform() throws ModpackException
    {
        JsonObject entry = libraryEntry( "com.example:lib:1.0",
                artifactObject( "com/example/lib/1.0/lib-1.0.jar", "abc123",
                                "https://example.test/lib.jar" ),
                null, null );

        ArrayList< GameLibrary > libs = GameLibraryManifest.extractLibraries( manifestOf( entry ) );

        assertEquals( 1, libs.size() );
        GameLibrary lib = libs.get( 0 );
        assertEquals( "com/example/lib/1.0/lib-1.0.jar", lib.getLocalFilePath() );
        assertFalse( lib.isNativeLib() );
        assertTrue( lib.getApplicableOSes().contains( CURRENT ) );
    }

    @Test
    void excludesALibraryWhoseRulesDisallowEveryPlatform() throws ModpackException
    {
        JsonObject entry = libraryEntry( "com.example:lib:1.0",
                artifactObject( "path", "sha1", "url" ),
                rulesOf( ruleObj( "disallow", null ) ), null );

        ArrayList< GameLibrary > libs = GameLibraryManifest.extractLibraries( manifestOf( entry ) );
        assertTrue( libs.isEmpty() );
    }

    @Test
    void excludesALibraryRestrictedToAnotherPlatform() throws ModpackException
    {
        JsonObject entry = libraryEntry( "com.example:lib:1.0",
                artifactObject( "path", "sha1", "url" ),
                rulesOf( ruleObj( "allow", NOT_CURRENT ) ), null );

        ArrayList< GameLibrary > libs = GameLibraryManifest.extractLibraries( manifestOf( entry ) );
        assertTrue( libs.isEmpty() );
    }

    @Test
    void includesALibraryExplicitlyAllowedForTheCurrentPlatform() throws ModpackException
    {
        JsonObject entry = libraryEntry( "com.example:lib:1.0",
                artifactObject( "path", "sha1", "url" ),
                rulesOf( ruleObj( "allow", CURRENT ) ), null );

        ArrayList< GameLibrary > libs = GameLibraryManifest.extractLibraries( manifestOf( entry ) );
        assertEquals( 1, libs.size() );
    }

    /**
     * An entry missing the required {@code downloads}/{@code name} shape is logged and skipped
     * rather than throwing — one malformed library entry must not abort resolution of every other
     * library in the manifest.
     */
    @Test
    void skipsAnEntryMissingTheRequiredDownloadsKeyWithoutThrowing() throws ModpackException
    {
        JsonObject malformed = new JsonObject();
        malformed.addProperty( "name", "com.example:lib:1.0" );
        // Deliberately no "downloads" key.

        ArrayList< GameLibrary > libs = GameLibraryManifest.extractLibraries( manifestOf( malformed ) );
        assertTrue( libs.isEmpty() );
    }

    @Test
    void multipleLibrariesAreAllResolvedIndependently() throws ModpackException
    {
        JsonObject first = libraryEntry( "com.example:a:1.0",
                artifactObject( "a.jar", "sha1a", "https://example.test/a.jar" ), null, null );
        JsonObject second = libraryEntry( "com.example:b:1.0",
                artifactObject( "b.jar", "sha1b", "https://example.test/b.jar" ), null, null );

        ArrayList< GameLibrary > libs = GameLibraryManifest.extractLibraries( manifestOf( first, second ) );

        assertEquals( 2, libs.size() );
        assertTrue( libs.stream().anyMatch( l -> "a.jar".equals( l.getLocalFilePath() ) ) );
        assertTrue( libs.stream().anyMatch( l -> "b.jar".equals( l.getLocalFilePath() ) ) );
    }

    // =========================================================================
    //  Native classifier resolution
    // =========================================================================

    @Test
    void resolvesAndIncludesTheNativeClassifierForTheCurrentPlatform() throws ModpackException
    {
        JsonObject nativeEntry = artifactObject( "natives/lib-natives.jar", "deadbeef",
                                                 "https://example.test/lib-natives.jar" );
        JsonObject classifiers = new JsonObject();
        classifiers.add( "natives-" + CURRENT, nativeEntry );

        JsonObject entry = libraryEntry( "com.example:lib:1.0",
                artifactObject( "lib.jar", "sha1val", "https://example.test/lib.jar" ),
                null, classifiers );

        ArrayList< GameLibrary > libs = GameLibraryManifest.extractLibraries( manifestOf( entry ) );

        // The main artifact plus its native classifier for this platform.
        assertEquals( 2, libs.size() );
        GameLibrary nativeLib = libs.stream().filter( GameLibrary::isNativeLib ).findFirst()
                                    .orElseThrow( () -> new AssertionError(
                                            "expected a native library entry to be included" ) );
        assertEquals( "natives/lib-natives.jar", nativeLib.getLocalFilePath() );
        assertEquals( List.of( CURRENT ), nativeLib.getApplicableOSes() );
    }

    /**
     * A library gated to another platform by its {@code rules} array must not contribute its
     * native classifier either — the native-resolution branches are gated on
     * {@code libraryAllowed}, not just on the presence of a matching classifier key.
     */
    @Test
    void excludesTheNativeClassifierWhenTheLibraryRulesDisallowTheCurrentPlatform() throws ModpackException
    {
        JsonObject nativeEntry = artifactObject( "natives/lib-natives.jar", "deadbeef",
                                                 "https://example.test/lib-natives.jar" );
        JsonObject classifiers = new JsonObject();
        classifiers.add( "natives-" + CURRENT, nativeEntry );

        JsonObject entry = libraryEntry( "com.example:lib:1.0",
                artifactObject( "lib.jar", "sha1val", "https://example.test/lib.jar" ),
                rulesOf( ruleObj( "allow", NOT_CURRENT ) ), classifiers );

        ArrayList< GameLibrary > libs = GameLibraryManifest.extractLibraries( manifestOf( entry ) );
        assertTrue( libs.isEmpty(), "neither the main artifact nor its native classifier should apply" );
    }

    /**
     * A {@code classifiers} object that does not carry an entry for the current platform's
     * resolved key must not synthesize a phantom native library.
     */
    @Test
    void doesNotIncludeANativeEntryWhenClassifiersLacksTheCurrentPlatformKey() throws ModpackException
    {
        JsonObject classifiers = new JsonObject();
        classifiers.add( "natives-" + NOT_CURRENT,
                         artifactObject( "natives/other.jar", "sha1", "https://example.test/other.jar" ) );

        JsonObject entry = libraryEntry( "com.example:lib:1.0",
                artifactObject( "lib.jar", "sha1val", "https://example.test/lib.jar" ),
                null, classifiers );

        ArrayList< GameLibrary > libs = GameLibraryManifest.extractLibraries( manifestOf( entry ) );

        assertEquals( 1, libs.size(), "only the main artifact should be present, no native entry" );
        assertFalse( libs.get( 0 ).isNativeLib() );
    }
}

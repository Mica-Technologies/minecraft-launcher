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

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GameLibraryManifest#resolveNativeClassifierKey(JsonObject, String)} —
 * the rule that picks which native binary a library ships for the current platform.
 *
 * <p>Why this matters: natives are the LWJGL and platform-glue binaries the game cannot
 * start without. Resolving the wrong classifier key means the download silently finds
 * nothing (or the wrong architecture), and the failure surfaces as an
 * {@code UnsatisfiedLinkError} at game startup rather than as a manifest problem. The rule
 * has two branches — an explicit {@code natives} mapping with an optional
 * <code>${arch}</code> placeholder, and a conventional fallback — and both are exercised
 * here.</p>
 */
class GameLibraryManifestClassifierTest
{
    /** Builds a library object carrying an explicit {@code natives} mapping. */
    private static JsonObject libraryWithNatives( String osKey, String value )
    {
        JsonObject natives = new JsonObject();
        natives.addProperty( osKey, value );
        JsonObject lib = new JsonObject();
        lib.add( "natives", natives );
        return lib;
    }

    /** The arch token the resolver substitutes for the current process. */
    private static String currentArchToken()
    {
        return System.getProperty( "os.arch", "" ).contains( "64" ) ? "64" : "32";
    }

    // =========================================================================
    //  Fallback branch
    // =========================================================================

    /**
     * Most modern libraries carry no {@code natives} mapping at all; the conventional
     * {@code natives-<os>} key is used instead.
     */
    @Test
    void fallsBackToConventionalKeyWhenNoNativesMappingPresent()
    {
        JsonObject lib = new JsonObject();
        assertEquals( "natives-windows",
                      GameLibraryManifest.resolveNativeClassifierKey( lib, "windows" ) );
        assertEquals( "natives-osx", GameLibraryManifest.resolveNativeClassifierKey( lib, "osx" ) );
        assertEquals( "natives-linux",
                      GameLibraryManifest.resolveNativeClassifierKey( lib, "linux" ) );
    }

    /**
     * A {@code natives} mapping that exists but has no entry for the requested OS also
     * falls back, rather than returning null or an entry meant for a different platform.
     */
    @Test
    void fallsBackWhenNativesMappingLacksTheRequestedOs()
    {
        JsonObject lib = libraryWithNatives( "windows", "natives-windows" );
        assertEquals( "natives-linux",
                      GameLibraryManifest.resolveNativeClassifierKey( lib, "linux" ) );
    }

    // =========================================================================
    //  Explicit mapping branch
    // =========================================================================

    @Test
    void usesExplicitNativesMappingWhenPresent()
    {
        JsonObject lib = libraryWithNatives( "osx", "natives-macos-custom" );
        assertEquals( "natives-macos-custom",
                      GameLibraryManifest.resolveNativeClassifierKey( lib, "osx" ) );
    }

    /**
     * Legacy LWJGL 2 manifests use <code>natives-windows-${arch}</code> to distinguish the
     * 32- and 64-bit builds. The placeholder must expand to the running process's
     * architecture, not the OS's — a 32-bit JVM on a 64-bit OS needs the 32-bit natives.
     */
    @Test
    void expandsArchPlaceholderToTheCurrentProcessArchitecture()
    {
        JsonObject lib = libraryWithNatives( "windows", "natives-windows-${arch}" );
        String resolved = GameLibraryManifest.resolveNativeClassifierKey( lib, "windows" );

        assertEquals( "natives-windows-" + currentArchToken(), resolved );
        assertTrue( resolved.indexOf( '$' ) < 0,
                    "an unexpanded placeholder would be looked up literally and never match" );
    }

    @Test
    void expandsArchPlaceholderAnywhereInTheValue()
    {
        JsonObject lib = libraryWithNatives( "linux", "${arch}-natives" );
        assertEquals( currentArchToken() + "-natives",
                      GameLibraryManifest.resolveNativeClassifierKey( lib, "linux" ) );
    }

    @Test
    void leavesValuesWithoutAPlaceholderUnchanged()
    {
        JsonObject lib = libraryWithNatives( "linux", "natives-linux" );
        assertEquals( "natives-linux",
                      GameLibraryManifest.resolveNativeClassifierKey( lib, "linux" ) );
    }

    /**
     * The arch token is only ever "64" or "32" — the resolver reduces the full
     * {@code os.arch} string (which may be {@code amd64}, {@code aarch64}, {@code x86}, …)
     * to that pair. Pinned so the reduction is not mistaken for a full arch name.
     */
    @Test
    void archTokenIsOnlyEverSixtyFourOrThirtyTwo()
    {
        JsonObject lib = libraryWithNatives( "windows", "${arch}" );
        String resolved = GameLibraryManifest.resolveNativeClassifierKey( lib, "windows" );
        assertTrue( "64".equals( resolved ) || "32".equals( resolved ),
                    "expected 64 or 32, got: " + resolved );
    }
}

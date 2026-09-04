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

package com.micatechnologies.minecraft.launcher.files;

import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RuntimeManager}'s Bell-SW Liberica JRE 8 resolution seams —
 * {@code resolveLibericaOsArch}, {@code buildLibericaApiUrl}, {@code buildLibericaExtractedFolderName},
 * and {@code resolveEffectiveJreFolder} — all extracted from {@code verifyLegacyJre} (Mojang's own
 * {@code jre-legacy} is 8u51, too old for Forge, so the launcher substitutes Bell-SW Liberica
 * 8u392 instead; see that method's javadoc).
 *
 * <p>Why this matters: {@code resolveLibericaOsArch} decides which Bell-SW binary gets
 * downloaded. Getting the OS token wrong downloads a build that can't even be extracted correctly
 * for this platform; getting the arch token wrong (x86 vs. arm) downloads a binary that fails to
 * execute at all on Apple Silicon / ARM Linux — precisely the failure mode Java 8 support for
 * Minecraft/Forge exists to avoid. {@code resolveEffectiveJreFolder} covers a real cross-platform
 * quirk: Liberica's macOS archives extract with a {@code .jre} suffix the Windows/Linux archives
 * don't have, and the exact same ternary chain was previously duplicated twice in
 * {@code verifyLegacyJre} with zero coverage.</p>
 */
class RuntimeManagerLibericaSeamsTest
{
    // =========================================================================
    //  resolveLibericaOsArch
    // =========================================================================

    @Test
    void resolvesToTheCorrectOsTokenForThisPlatform()
    {
        String[] osArch = RuntimeManager.resolveLibericaOsArch();

        if ( SystemUtils.IS_OS_WINDOWS ) {
            assertEquals( "windows", osArch[ 0 ] );
            assertEquals( "x86", osArch[ 1 ], "the Bell-SW API has no Windows ARM entry; x86 is correct here" );
        }
        else if ( SystemUtils.IS_OS_MAC ) {
            assertEquals( "macos", osArch[ 0 ] );
        }
        else {
            assertEquals( "linux", osArch[ 0 ] );
        }
    }

    @Test
    void archTokenMatchesTheRunningProcessArchitecture()
    {
        String[] osArch = RuntimeManager.resolveLibericaOsArch();
        boolean expectArm = System.getProperty( "os.arch", "" ).contains( "aarch64" ) && !SystemUtils.IS_OS_WINDOWS;

        assertEquals( expectArm ? "arm" : "x86", osArch[ 1 ] );
    }

    @Test
    void resolveLibericaOsArchAlwaysReturnsATwoElementArray()
    {
        assertEquals( 2, RuntimeManager.resolveLibericaOsArch().length );
    }

    // =========================================================================
    //  buildLibericaApiUrl
    // =========================================================================

    @Test
    void substitutesBothPlaceholdersIntoTheApiUrl()
    {
        String url = RuntimeManager.buildLibericaApiUrl( "linux", "arm" );

        assertTrue( url.contains( "os=linux" ), "expected os=linux in: " + url );
        assertTrue( url.contains( "arch=arm" ), "expected arch=arm in: " + url );
        assertTrue( url.startsWith( "https://api.bell-sw.com/" ) );
    }

    @Test
    void noPlaceholderTokensSurviveSubstitution()
    {
        String url = RuntimeManager.buildLibericaApiUrl( "windows", "x86" );
        assertTrue( url.indexOf( '{' ) < 0, "an unsubstituted placeholder would request a literal '{OS}': " + url );
    }

    @Test
    void requestsJre8u392SpecificallyRatherThanTheLatestRelease()
    {
        // Pinned deliberately: Mojang's own jre-legacy (8u51) is too old for Forge (needs
        // 8u121+ for sun.misc.ObjectInputFilter) -- the whole point of this API path is a known
        // -good pinned build, not "whatever Bell-SW currently publishes as latest".
        String url = RuntimeManager.buildLibericaApiUrl( "linux", "x86" );
        assertTrue( url.contains( "version-feature=8" ) );
        assertTrue( url.contains( "version-update=392" ) );
    }

    // =========================================================================
    //  buildLibericaExtractedFolderName
    // =========================================================================

    @Test
    void buildsTheExpectedFolderNameFormat()
    {
        assertEquals( "jre8u392", RuntimeManager.buildLibericaExtractedFolderName( "jre", 8, 392 ) );
    }

    @Test
    void differentUpdateVersionsProduceDifferentFolderNames()
    {
        assertEquals( "jre8u1", RuntimeManager.buildLibericaExtractedFolderName( "jre", 8, 1 ) );
        assertEquals( "jre8u2", RuntimeManager.buildLibericaExtractedFolderName( "jre", 8, 2 ) );
    }

    // =========================================================================
    //  resolveEffectiveJreFolder
    // =========================================================================

    @Test
    void prefersThePrimaryFolderWhenBothExist( @TempDir File dir ) throws IOException
    {
        File primary = new File( dir, "jre8u392" );
        File alt = new File( dir, "jre8u392.jre" );
        assertTrue( primary.mkdirs() );
        assertTrue( alt.mkdirs() );

        assertSame( primary, RuntimeManager.resolveEffectiveJreFolder( primary, alt ) );
    }

    @Test
    void fallsBackToTheAltFolderWhenOnlyItExists( @TempDir File dir )
    {
        // Simulates the macOS Liberica archive layout: only the ".jre"-suffixed folder exists.
        File primary = new File( dir, "jre8u392" );
        File alt = new File( dir, "jre8u392.jre" );
        assertTrue( alt.mkdirs() );

        assertSame( alt, RuntimeManager.resolveEffectiveJreFolder( primary, alt ) );
    }

    @Test
    void fallsBackToThePrimaryWhenNeitherExists( @TempDir File dir )
    {
        File primary = new File( dir, "jre8u392" );
        File alt = new File( dir, "jre8u392.jre" );

        assertSame( primary, RuntimeManager.resolveEffectiveJreFolder( primary, alt ),
                    "a caller about to extract fresh needs a definite target path, even if neither exists yet" );
    }
}

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

import com.micatechnologies.minecraft.launcher.consts.RuntimeConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RuntimeManager#majorVersionToComponent(int)} and the pure platform
 * helpers in {@link RuntimeConstants}.
 *
 * <p>Why this matters: this mapping decides which Java runtime the game is launched
 * under. A wrong answer does not fail at the mapping — it fails much later, either as a
 * download of the wrong runtime or as an
 * {@code UnsupportedClassVersionError} deep inside mod loading, which reads to the user
 * as "the modpack is broken". The fallback ladder for unknown versions is the part most
 * likely to drift as new Mojang runtime components appear, so its boundaries are pinned
 * here explicitly.</p>
 */
class RuntimeComponentMappingTest
{
    // =========================================================================
    //  Exact, known components
    // =========================================================================

    @Test
    void knownMajorVersionsMapToTheirMojangComponent()
    {
        assertEquals( "jre-legacy", RuntimeManager.majorVersionToComponent( 8 ) );
        assertEquals( "java-runtime-alpha", RuntimeManager.majorVersionToComponent( 16 ) );
        assertEquals( "java-runtime-gamma", RuntimeManager.majorVersionToComponent( 17 ) );
        assertEquals( "java-runtime-delta", RuntimeManager.majorVersionToComponent( 21 ) );
        assertEquals( "java-runtime-epsilon", RuntimeManager.majorVersionToComponent( 25 ) );
    }

    /**
     * Java 8 remains the single most common requirement across older modpacks, so the
     * default constant and the mapping for 8 must agree.
     */
    @Test
    void defaultJavaVersionAgreesWithTheDefaultComponent()
    {
        assertEquals( RuntimeConstants.DEFAULT_RUNTIME_COMPONENT,
                      RuntimeManager.majorVersionToComponent(
                              RuntimeConstants.DEFAULT_JAVA_MAJOR_VERSION ) );
    }

    // =========================================================================
    //  Fallback ladder for unknown versions
    // =========================================================================

    @Test
    void versionsBelowEightFallBackToLegacy()
    {
        assertEquals( "jre-legacy", RuntimeManager.majorVersionToComponent( 7 ) );
        assertEquals( "jre-legacy", RuntimeManager.majorVersionToComponent( 6 ) );
    }

    @Test
    void versionsBetweenNineAndSixteenUseAlpha()
    {
        assertEquals( "java-runtime-alpha", RuntimeManager.majorVersionToComponent( 9 ) );
        assertEquals( "java-runtime-alpha", RuntimeManager.majorVersionToComponent( 11 ) );
        assertEquals( "java-runtime-alpha", RuntimeManager.majorVersionToComponent( 15 ) );
    }

    @Test
    void versionsBetweenEighteenAndTwentyOneUseDelta()
    {
        assertEquals( "java-runtime-delta", RuntimeManager.majorVersionToComponent( 18 ) );
        assertEquals( "java-runtime-delta", RuntimeManager.majorVersionToComponent( 20 ) );
    }

    /**
     * Anything newer than the newest known component maps to that newest component rather
     * than failing. That is the right default — a modpack requiring a Java the launcher
     * has not been taught about should still get the closest available runtime — but it
     * means adding a new Mojang component requires updating this ladder, or new versions
     * silently land on the previous one.
     */
    @Test
    void versionsAboveTheNewestKnownComponentClampToIt()
    {
        assertEquals( "java-runtime-epsilon", RuntimeManager.majorVersionToComponent( 26 ) );
        assertEquals( "java-runtime-epsilon", RuntimeManager.majorVersionToComponent( 99 ) );
    }

    /**
     * Pins defensive behaviour for nonsensical input: a zero or negative major version
     * (a malformed or missing manifest field) resolves to the legacy runtime rather than
     * throwing, so a bad manifest degrades to a wrong-but-defined runtime instead of an
     * exception during launch.
     */
    @Test
    void nonPositiveVersionsResolveToLegacyRatherThanThrowing()
    {
        assertEquals( "jre-legacy", RuntimeManager.majorVersionToComponent( 0 ) );
        assertEquals( "jre-legacy", RuntimeManager.majorVersionToComponent( -1 ) );
    }

    // =========================================================================
    //  Platform helpers
    // =========================================================================

    @Test
    void mojangPlatformKeyIsResolvedForThisMachine()
    {
        String key = RuntimeConstants.getMojangPlatformKey();
        assertNotNull( key, "the runtime index cannot be consulted without a platform key" );
        assertFalse( key.isBlank() );
    }

    @Test
    void javaExecPathIsResolvedAndPointsAtAJavaBinary()
    {
        String path = RuntimeConstants.getJavaExecPathForOs();
        assertNotNull( path );
        assertFalse( path.isBlank() );
        assertTrue( path.contains( "java" ),
                    "the resolved executable path should reference a java binary, got: " + path );
    }
}

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

package com.micatechnologies.minecraft.launcher.game.modpack;

import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MavenArtifactPath} — the parser that turns a Maven coordinate from a
 * Forge or Mojang manifest into a relative filesystem path.
 *
 * <p>Why this matters on two counts. Correctness: every Forge library that ships as a
 * coordinate rather than a direct URL is located through this, so a parsing slip produces
 * a missing or wrong jar on the classpath and a crash inside mod loading, far from the
 * cause. Security: the coordinate comes from a downloaded manifest, and the parsed
 * components are concatenated into a path under the launcher's library directory — so
 * traversal characters in any component would let a hostile manifest write outside it.
 * The parser rejects those explicitly, and those rejections are what most of this class
 * pins down.</p>
 */
class MavenArtifactPathTest
{
    // =========================================================================
    //  Well-formed coordinates
    // =========================================================================

    @Test
    void parsesGroupArtifactVersion() throws ModpackException
    {
        MavenArtifactPath.Coord c = MavenArtifactPath.parseStrict( "net.minecraftforge:forge:1.20.1" );
        assertEquals( "net.minecraftforge", c.group() );
        assertEquals( "forge", c.artifact() );
        assertEquals( "1.20.1", c.version() );
        assertNull( c.classifier() );
        assertEquals( "jar", c.ext(), "extension defaults to jar" );
    }

    @Test
    void parsesOptionalClassifier() throws ModpackException
    {
        MavenArtifactPath.Coord c =
                MavenArtifactPath.parseStrict( "org.lwjgl:lwjgl:3.3.1:natives-windows" );
        assertEquals( "natives-windows", c.classifier() );
        assertEquals( "jar", c.ext() );
    }

    @Test
    void parsesExplicitExtension() throws ModpackException
    {
        MavenArtifactPath.Coord c = MavenArtifactPath.parseStrict( "de.oceanlabs.mcp:mcp_config:1.20.1@zip" );
        assertEquals( "zip", c.ext() );
        assertEquals( "mcp_config", c.artifact() );
    }

    @Test
    void parsesClassifierAndExtensionTogether() throws ModpackException
    {
        MavenArtifactPath.Coord c =
                MavenArtifactPath.parseStrict( "org.lwjgl:lwjgl:3.3.1:natives-linux@zip" );
        assertEquals( "natives-linux", c.classifier() );
        assertEquals( "zip", c.ext() );
    }

    /**
     * Forge's {@code install_profile.json} wraps some coordinates in square brackets. They
     * must parse identically to the unwrapped form.
     */
    @Test
    void stripsForgeStyleSquareBrackets() throws ModpackException
    {
        MavenArtifactPath.Coord bracketed =
                MavenArtifactPath.parseStrict( "[net.minecraftforge:forge:1.20.1]" );
        MavenArtifactPath.Coord plain =
                MavenArtifactPath.parseStrict( "net.minecraftforge:forge:1.20.1" );
        assertEquals( plain, bracketed );
    }

    // =========================================================================
    //  Relative path construction
    // =========================================================================

    @Test
    void buildsMavenLayoutRelativePath() throws ModpackException
    {
        String path = MavenArtifactPath.toRelativePathStrict( "net.minecraftforge:forge:1.20.1" );
        assertEquals( "net/minecraftforge/forge/1.20.1/forge-1.20.1.jar", path );
    }

    @Test
    void relativePathIncludesClassifierAndExtension() throws ModpackException
    {
        String path = MavenArtifactPath.toRelativePathStrict( "org.lwjgl:lwjgl:3.3.1:natives-windows@zip" );
        assertEquals( "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.zip", path );
    }

    @Test
    void groupDotsBecomePathSeparators() throws ModpackException
    {
        assertTrue( MavenArtifactPath.toRelativePathStrict( "a.b.c:d:1" ).startsWith( "a/b/c/" ) );
    }

    // =========================================================================
    //  Malformed input
    // =========================================================================

    @Test
    void nullCoordinateIsRejected()
    {
        assertThrows( ModpackException.class, () -> MavenArtifactPath.parseStrict( null ) );
    }

    @Test
    void coordinateWithTooFewComponentsIsRejected()
    {
        assertThrows( ModpackException.class,
                      () -> MavenArtifactPath.parseStrict( "net.minecraftforge:forge" ) );
        assertThrows( ModpackException.class, () -> MavenArtifactPath.parseStrict( "forge" ) );
        assertThrows( ModpackException.class, () -> MavenArtifactPath.parseStrict( "" ) );
    }

    // =========================================================================
    //  Path-traversal rejection — the security boundary
    // =========================================================================

    @Test
    void traversalInGroupIsRejected()
    {
        assertThrows( ModpackException.class,
                      () -> MavenArtifactPath.parseStrict( "..:artifact:1.0" ) );
        assertThrows( ModpackException.class,
                      () -> MavenArtifactPath.parseStrict( "a/../../etc:artifact:1.0" ) );
    }

    @Test
    void traversalInArtifactIsRejected()
    {
        assertThrows( ModpackException.class,
                      () -> MavenArtifactPath.parseStrict( "group:../evil:1.0" ) );
    }

    @Test
    void traversalInVersionIsRejected()
    {
        assertThrows( ModpackException.class,
                      () -> MavenArtifactPath.parseStrict( "group:artifact:../../1.0" ) );
    }

    @Test
    void traversalInClassifierIsRejected()
    {
        assertThrows( ModpackException.class,
                      () -> MavenArtifactPath.parseStrict( "group:artifact:1.0:../evil" ) );
    }

    @Test
    void traversalInExtensionIsRejected()
    {
        assertThrows( ModpackException.class,
                      () -> MavenArtifactPath.parseStrict( "group:artifact:1.0@../evil" ) );
    }

    /**
     * A literal separator in a component would inject an extra path segment even without a
     * {@code ..}, so both slash forms are rejected on every component regardless of the
     * host platform.
     */
    @Test
    void literalSlashesAreRejectedOnEveryComponent()
    {
        assertThrows( ModpackException.class,
                      () -> MavenArtifactPath.parseStrict( "group:art/ifact:1.0" ) );
        assertThrows( ModpackException.class,
                      () -> MavenArtifactPath.parseStrict( "group:artifact:1.0:nat\\\\ives" ) );
    }

    // =========================================================================
    //  Lenient variant
    // =========================================================================

    /**
     * {@code parseOrNull} is the lenient entry point: a structurally malformed coordinate
     * is skipped rather than aborting the whole resolution, matching historical Fabric
     * behaviour.
     */
    @Test
    void parseOrNullReturnsNullForStructurallyInvalidInput()
    {
        assertNull( MavenArtifactPath.parseOrNull( null ) );
        assertNull( MavenArtifactPath.parseOrNull( "too:few" ) );
        assertNull( MavenArtifactPath.parseOrNull( "" ) );
    }

    /**
     * Leniency deliberately stops at path traversal. A malformed coordinate is a benign
     * manifest typo and is skipped; a traversal sequence is an attack signature, and
     * silently skipping it would turn the lenient entry point into a way around the
     * security check that {@code parseStrict} enforces. The method's javadoc documents
     * this and declares the unchecked throw.
     *
     * <p>Pinned deliberately, because the asymmetry is surprising — the name promises a
     * null and this path throws instead. Anything calling it must be prepared for that.</p>
     */
    @Test
    void parseOrNullStillThrowsOnPathTraversalRatherThanSkippingIt()
    {
        assertThrows( RuntimeException.class,
                      () -> MavenArtifactPath.parseOrNull( "..:artifact:1.0" ) );
        assertThrows( RuntimeException.class,
                      () -> MavenArtifactPath.parseOrNull( "group:artifact:1.0:../evil" ) );
    }

    @Test
    void parseOrNullParsesValidCoordinates()
    {
        MavenArtifactPath.Coord c = MavenArtifactPath.parseOrNull( "group:artifact:1.0" );
        assertNotNull( c );
        assertEquals( "artifact", c.artifact() );
    }
}

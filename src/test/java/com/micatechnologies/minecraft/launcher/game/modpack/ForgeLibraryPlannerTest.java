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

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ForgeLibraryPlanner} — the decision that maps one Forge manifest entry onto
 * the jars the launcher must actually fetch and put on the classpath.
 *
 * <p>This is the logic {@code CLAUDE.md} points at when it calls Forge library resolution the
 * most complex thing in the codebase, and until now it had no coverage at all. It is worth
 * covering because its failure mode is quiet: a wrong decision produces a classpath that looks
 * entirely plausible and a game that will not start, with an error pointing at the mod loader
 * rather than at the launcher.</p>
 *
 * <p>Three Forge eras have to be told apart from the same manifest shape. <b>Modern</b> (1.13+)
 * embeds both {@code forge-<ver>.jar} and {@code forge-<ver>-universal.jar} under
 * {@code maven/}, and both must reach the classpath. <b>Legacy-with-path</b> has only the
 * universal jar there, so the base path must be substituted. <b>Legacy top-level</b> (1.7–1.12)
 * puts the universal jar at the installer's root while the manifest still advertises a Maven
 * URL that Forge's repository does not serve — following that URL 404s.</p>
 *
 * <p>The SHA-1 suppression is the subtle part. In both legacy paths the file that ends up being
 * used is not the file the manifest's hash describes, so keeping the hash would fail
 * verification against a perfectly good jar.</p>
 */
class ForgeLibraryPlannerTest
{
    private static final String FORGE_PREFIX = "net.minecraftforge:forge:";
    private static final String FORGE_COORD = "net.minecraftforge:forge:1.20.1-47.2.0";
    private static final String BASE_PATH = "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0.jar";
    private static final String UNIVERSAL_PATH =
            "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar";

    /** A bundled-artifact probe backed by a fixed set, standing in for the installer JAR. */
    private static Predicate< String > contains( String... names )
    {
        Set< String > present = Set.of( names );
        return present::contains;
    }

    private static final Predicate< String > NOTHING = name -> false;

    // region coordinate parsing

    @Test
    void aCoordinateYieldsItsFilenameStem()
    {
        assertEquals( "forge-1.20.1-47.2.0", ForgeLibraryPlanner.inferredRepoPath( FORGE_COORD ) );
        assertEquals( "asm-9.5", ForgeLibraryPlanner.inferredRepoPath( "org.ow2.asm:asm:9.5" ) );
    }

    /** A four-segment coordinate carries a classifier, which joins with a hyphen like the rest. */
    @Test
    void aClassifiedCoordinateKeepsItsClassifier()
    {
        assertEquals( "lwjgl-3.3.1-natives-macos",
                      ForgeLibraryPlanner.inferredRepoPath( "org.lwjgl:lwjgl:3.3.1:natives-macos" ) );
    }

    @Test
    void aNonCoordinateIsRejected()
    {
        assertThrows( IllegalArgumentException.class, () -> ForgeLibraryPlanner.inferredRepoPath( null ) );
        assertThrows( IllegalArgumentException.class,
                      () -> ForgeLibraryPlanner.inferredRepoPath( "no-colons-here" ) );
    }

    // endregion

    // region modern Forge — both jars present

    /**
     * The modern case. Both jars are bundled, the base one stays as the primary artifact, and
     * the universal one is added as a second classpath entry. Dropping either produces a
     * classpath missing half of Forge.
     */
    @Test
    void bothJarsBundledMeansTheUniversalOneIsAddedAlongside()
    {
        ForgeLibraryPlanner.Plan plan = ForgeLibraryPlanner.plan(
                FORGE_COORD, BASE_PATH, "forge-1.20.1-47.2.0", true, FORGE_PREFIX,
                contains( BASE_PATH, UNIVERSAL_PATH ), NOTHING );

        assertEquals( BASE_PATH, plan.repoPath(), "the base jar stays primary" );
        assertTrue( plan.addUniversalAsExtra() );
        assertEquals( UNIVERSAL_PATH, plan.universalRepoPath() );
        assertFalse( plan.suppressSha1(), "the base jar is still what the hash describes" );
        assertFalse( plan.legacyTopLevelUniversal() );
    }

    // endregion

    // region legacy Forge — universal only, under maven/

    /**
     * Only the universal jar is bundled, so it replaces the base path. The manifest's SHA-1
     * described the base jar, so keeping it would fail verification against a good file.
     */
    @Test
    void onlyTheUniversalJarBundledSubstitutesItAndDropsTheHash()
    {
        ForgeLibraryPlanner.Plan plan = ForgeLibraryPlanner.plan(
                FORGE_COORD, BASE_PATH, "forge-1.20.1-47.2.0", true, FORGE_PREFIX,
                contains( UNIVERSAL_PATH ), NOTHING );

        assertEquals( UNIVERSAL_PATH, plan.repoPath() );
        assertFalse( plan.addUniversalAsExtra(), "there is no second jar to add" );
        assertTrue( plan.suppressSha1() );
    }

    /** Neither bundled: nothing is substituted and the manifest's own URL and hash stand. */
    @Test
    void neitherJarBundledLeavesTheEntryUntouched()
    {
        ForgeLibraryPlanner.Plan plan = ForgeLibraryPlanner.plan(
                FORGE_COORD, BASE_PATH, "forge-1.20.1-47.2.0", true, FORGE_PREFIX,
                NOTHING, NOTHING );

        assertEquals( BASE_PATH, plan.repoPath() );
        assertFalse( plan.addUniversalAsExtra() );
        assertFalse( plan.suppressSha1() );
    }

    /**
     * An entry whose path already names the universal jar must not have {@code -universal}
     * appended again — that would look for {@code ...-universal-universal.jar}.
     */
    @Test
    void anEntryAlreadyNamingTheUniversalJarIsLeftAlone()
    {
        ForgeLibraryPlanner.Plan plan = ForgeLibraryPlanner.plan(
                FORGE_COORD, UNIVERSAL_PATH, "forge-1.20.1-47.2.0", true, FORGE_PREFIX,
                contains( UNIVERSAL_PATH ), NOTHING );

        assertEquals( UNIVERSAL_PATH, plan.repoPath() );
        assertFalse( plan.addUniversalAsExtra() );
        assertNull( plan.universalRepoPath() );
    }

    // endregion

    // region legacy Forge — universal at the installer's top level

    /**
     * The 1.7–1.12 case. The manifest gives no path and advertises a Maven URL that Forge's
     * repository does not serve, so the copy embedded at the installer's root has to be used.
     */
    @Test
    void aTopLevelUniversalJarIsDetectedAndTheHashDropped()
    {
        String legacyCoord = "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10";
        String stem = ForgeLibraryPlanner.inferredRepoPath( legacyCoord );

        ForgeLibraryPlanner.Plan plan = ForgeLibraryPlanner.plan(
                legacyCoord, stem, stem, false, FORGE_PREFIX,
                NOTHING, contains( stem + "-universal.jar" ) );

        assertTrue( plan.legacyTopLevelUniversal() );
        assertEquals( stem + "-universal.jar", plan.legacyTopLevelEntry() );
        assertTrue( plan.suppressSha1(), "the manifest carries no hash for the embedded jar" );
        assertFalse( plan.addUniversalAsExtra() );
    }

    @Test
    void noTopLevelJarMeansNoLegacySubstitution()
    {
        String stem = "forge-1.7.10-10.13.4.1614-1.7.10";
        ForgeLibraryPlanner.Plan plan = ForgeLibraryPlanner.plan(
                "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10", stem, stem, false,
                FORGE_PREFIX, NOTHING, NOTHING );

        assertFalse( plan.legacyTopLevelUniversal() );
        assertNull( plan.legacyTopLevelEntry() );
        assertFalse( plan.suppressSha1() );
    }

    // endregion

    // region the loader artifact vs everything else

    /**
     * The universal-jar logic applies only to Forge's own artifact. An ordinary library that
     * happened to have a universal jar bundled beside it must not be rewritten — that would
     * silently swap a dependency for a different file.
     */
    @Test
    void anOrdinaryLibraryIsNeverRewritten()
    {
        String asmPath = "org/ow2/asm/asm/9.5/asm-9.5.jar";
        ForgeLibraryPlanner.Plan plan = ForgeLibraryPlanner.plan(
                "org.ow2.asm:asm:9.5", asmPath, "asm-9.5", true, FORGE_PREFIX,
                contains( asmPath, "org/ow2/asm/asm/9.5/asm-9.5-universal.jar" ), NOTHING );

        assertEquals( asmPath, plan.repoPath() );
        assertFalse( plan.addUniversalAsExtra() );
        assertNull( plan.universalRepoPath() );
        assertFalse( plan.suppressSha1() );
    }

    /** Nor does the legacy top-level path apply to a non-loader artifact. */
    @Test
    void anOrdinaryLibraryIsNeverTreatedAsTheLegacyUniversalJar()
    {
        ForgeLibraryPlanner.Plan plan = ForgeLibraryPlanner.plan(
                "org.ow2.asm:asm:9.5", "asm-9.5", "asm-9.5", false, FORGE_PREFIX,
                NOTHING, contains( "asm-9.5-universal.jar" ) );
        assertFalse( plan.legacyTopLevelUniversal() );
    }

    /**
     * The prefix is supplied by the loader subclass — NeoForge uses a different coordinate —
     * so a plan built with one prefix must not fire for another loader's artifact.
     */
    @Test
    void theLoaderPrefixIsRespected()
    {
        String neoCoord = "net.neoforged:neoforge:20.4.100";
        String neoPath = "net/neoforged/neoforge/20.4.100/neoforge-20.4.100.jar";
        String neoUniversal = "net/neoforged/neoforge/20.4.100/neoforge-20.4.100-universal.jar";

        assertFalse( ForgeLibraryPlanner.plan( neoCoord, neoPath, "neoforge-20.4.100", true,
                                               FORGE_PREFIX, contains( neoPath, neoUniversal ),
                                               NOTHING ).addUniversalAsExtra(),
                     "the Forge prefix must not match a NeoForge artifact" );

        assertTrue( ForgeLibraryPlanner.plan( neoCoord, neoPath, "neoforge-20.4.100", true,
                                              "net.neoforged:neoforge:",
                                              contains( neoPath, neoUniversal ), NOTHING )
                            .addUniversalAsExtra(),
                    "with its own prefix it should" );
    }

    // endregion

    // region local paths

    @Test
    void anExplicitPathMapsStraightOntoTheLocalLayout()
    {
        assertEquals( "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0.jar",
                      ForgeLibraryPlanner.localPath( FORGE_COORD, BASE_PATH, true, "forge-1.20.1-47.2.0",
                                                     "/", ".jar" ) );
    }

    /**
     * The same entry on Windows. The separator is a parameter precisely so both layouts can be
     * checked from one machine — this code runs on three platforms and the cache layout has to
     * be right on all of them.
     */
    @Test
    void anExplicitPathUsesThePlatformSeparator()
    {
        assertEquals( "net\\minecraftforge\\forge\\1.20.1-47.2.0\\forge-1.20.1-47.2.0.jar",
                      ForgeLibraryPlanner.localPath( FORGE_COORD, BASE_PATH, true, "forge-1.20.1-47.2.0",
                                                     "\\", ".jar" ) );
    }

    /** Without an explicit path the layout is reconstructed from the coordinate. */
    @Test
    void aCoordinateOnlyEntryIsReconstructedIntoTheCacheLayout()
    {
        assertEquals( "org/ow2/asm/asm/9.5/asm-9.5.jar",
                      ForgeLibraryPlanner.localPath( "org.ow2.asm:asm:9.5", "asm-9.5", false,
                                                     "asm-9.5", "/", ".jar" ) );
    }

    @Test
    void aClassifiedCoordinateGetsEachSegmentAsADirectory()
    {
        assertEquals( "org/lwjgl/lwjgl/3.3.1/natives-macos/lwjgl-3.3.1-natives-macos.jar",
                      ForgeLibraryPlanner.localPath( "org.lwjgl:lwjgl:3.3.1:natives-macos",
                                                     "lwjgl-3.3.1-natives-macos", false,
                                                     "lwjgl-3.3.1-natives-macos", "/", ".jar" ) );
    }

    // endregion
}

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
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ModPackAuditLog}'s writer + {@link ModPackAuditLog#analyzeProblems}
 * round-trip against a real temp directory. The Problems detection — "re-downloaded in
 * the N most-recent consecutive launches" — is the value of the feature, so it's locked in
 * here: a streak that reaches the latest launch flags, a broken or stale streak doesn't.
 *
 * @author Mica Technologies
 */
class ModPackAuditLogTest
{
    /** Records a re-download of {@code file} under {@code launchId} with the given before/after hashes. */
    private static void redownload( String packRoot, int launchId, String file, String oldHash, String newHash )
    {
        LaunchPrepareContext.forLaunch( LaunchVerifyMode.FULL, packRoot, launchId )
                            .recordRedownload( packRoot + File.separator + file, oldHash, newHash, "EXPECTED", "sha1" );
    }

    @Test
    void flagsFileRedownloadedInTheLastThreeConsecutiveLaunches( @TempDir Path packDir )
    {
        String root = packDir.toString();
        redownload( root, 5, "mods/foo.jar", "AAA", "AAA" );
        redownload( root, 6, "mods/foo.jar", "AAA", "AAA" );
        redownload( root, 7, "mods/foo.jar", "AAA", "AAA" );

        List< ModPackAuditLog.Problem > problems = ModPackAuditLog.analyzeProblems( root, 7, 3 );
        assertEquals( 1, problems.size() );
        assertEquals( "mods/foo.jar", problems.get( 0 ).file() );
        // oldHash == newHash on every launch → re-fetching identical bytes.
        assertTrue( problems.get( 0 ).contentUnchanged() );
    }

    @Test
    void doesNotFlagWhenStreakIsTooShort( @TempDir Path packDir )
    {
        String root = packDir.toString();
        redownload( root, 6, "mods/foo.jar", "AAA", "AAA" );
        redownload( root, 7, "mods/foo.jar", "AAA", "AAA" );
        // Only 2 of the last 3 launches re-downloaded it.
        assertTrue( ModPackAuditLog.analyzeProblems( root, 7, 3 ).isEmpty() );
    }

    @Test
    void doesNotFlagWhenStreakIsStale( @TempDir Path packDir )
    {
        String root = packDir.toString();
        redownload( root, 5, "mods/foo.jar", "AAA", "AAA" );
        redownload( root, 6, "mods/foo.jar", "AAA", "AAA" );
        redownload( root, 7, "mods/foo.jar", "AAA", "AAA" );
        // Three healthy launches happened since (8, 9, 10 wrote nothing) — no longer a problem.
        assertTrue( ModPackAuditLog.analyzeProblems( root, 10, 3 ).isEmpty() );
    }

    @Test
    void marksContentChangedWhenHashesDiffer( @TempDir Path packDir )
    {
        String root = packDir.toString();
        redownload( root, 5, "mods/bar.jar", "AAA", "BBB" );
        redownload( root, 6, "mods/bar.jar", "BBB", "CCC" );
        redownload( root, 7, "mods/bar.jar", "CCC", "DDD" );

        List< ModPackAuditLog.Problem > problems = ModPackAuditLog.analyzeProblems( root, 7, 3 );
        assertEquals( 1, problems.size() );
        assertFalse( problems.get( 0 ).contentUnchanged() );
    }

    @Test
    void recordIsNoOpWithoutLaunchContext( @TempDir Path packDir )
    {
        String root = packDir.toString();
        // Outside a launch — should write nothing and not throw.
        LaunchPrepareContext.NONE.recordRedownload( root + "/mods/x.jar", "A", "A", "E", "sha1" );
        assertTrue( ModPackAuditLog.analyzeProblems( root, 7, 3 ).isEmpty() );
    }

    @Test
    void concurrentLaunchesOfTwoPacksEachRecordInTheirOwnLog( @TempDir Path dir )
    {
        // Two packs preparing at once used to share one static "current launch", so whichever
        // began last received both packs' entries. Each launch now carries its own target.
        String rootA = dir.resolve( "a" ).toString();
        String rootB = dir.resolve( "b" ).toString();
        LaunchPrepareContext launchA = LaunchPrepareContext.forLaunch( LaunchVerifyMode.FULL, rootA, 3 );
        LaunchPrepareContext launchB = LaunchPrepareContext.forLaunch( LaunchVerifyMode.FAST_PATH, rootB, 9 );
        new File( rootA ).mkdirs();
        new File( rootB ).mkdirs();

        launchA.recordRedownload( rootA + File.separator + "mods/a.jar", "A", "A", "E", "sha1" );
        launchB.recordRedownload( rootB + File.separator + "mods/b.jar", "B", "B", "E", "sha1" );

        List< ModPackAuditLog.Problem > inA = ModPackAuditLog.analyzeProblems( rootA, 3, 1 );
        List< ModPackAuditLog.Problem > inB = ModPackAuditLog.analyzeProblems( rootB, 9, 1 );
        assertEquals( 1, inA.size() );
        assertEquals( "mods/a.jar", inA.get( 0 ).file() );
        assertEquals( 1, inB.size() );
        assertEquals( "mods/b.jar", inB.get( 0 ).file() );
    }

    @Test
    void noLogYieldsNoProblems( @TempDir Path packDir )
    {
        assertTrue( ModPackAuditLog.analyzeProblems( packDir.toString(), 7, 3 ).isEmpty() );
    }
}

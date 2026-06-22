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

import org.junit.jupiter.api.AfterEach;
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
    @AfterEach
    void clearContext()
    {
        ModPackAuditLog.endLaunch();
    }

    /** Records a re-download of {@code file} under {@code launchId} with the given before/after hashes. */
    private static void redownload( String packRoot, int launchId, String file, String oldHash, String newHash )
    {
        ModPackAuditLog.beginLaunch( packRoot, launchId );
        ModPackAuditLog.recordRedownload( packRoot + File.separator + file, oldHash, newHash, "EXPECTED", "sha1" );
        ModPackAuditLog.endLaunch();
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
        // No beginLaunch — should write nothing and not throw.
        ModPackAuditLog.recordRedownload( root + "/mods/x.jar", "A", "A", "E", "sha1" );
        assertTrue( ModPackAuditLog.analyzeProblems( root, 7, 3 ).isEmpty() );
    }

    @Test
    void noLogYieldsNoProblems( @TempDir Path packDir )
    {
        assertTrue( ModPackAuditLog.analyzeProblems( packDir.toString(), 7, 3 ).isEmpty() );
    }
}

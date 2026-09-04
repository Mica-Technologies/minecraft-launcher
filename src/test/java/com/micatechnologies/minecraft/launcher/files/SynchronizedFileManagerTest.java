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
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link SynchronizedFileManager#getSynchronizedFile(String)} /
 * {@link SynchronizedFileManager#getSynchronizedFile(Path)} — the "one {@link File} instance per
 * underlying file" registry every download/verify/extract path in the launcher relies on for
 * {@code synchronized(file)} mutual exclusion.
 *
 * <p>Why this matters: the entire contract is "the same underlying file always yields the exact
 * same {@link File} object," so two threads racing to download/verify the same library, asset, or
 * runtime file actually serialize against each other. That contract silently breaks the moment two
 * different-looking path spellings for the same file (relative vs. absolute, a redundant
 * {@code ./}, or — on Windows — different letter case) produce different keys: two threads would
 * then synchronize on two distinct {@link File} objects and run concurrently against the same
 * bytes on disk, with no exception and no test failure to point at the cause. This class exists
 * to lock in the key-normalization behavior directly, rather than relying on a downstream
 * concurrency bug to surface it.</p>
 */
class SynchronizedFileManagerTest
{
    @Test
    void sameAbsolutePathReturnsTheSameInstance( @TempDir File dir )
    {
        File target = new File( dir, "same.txt" );

        File first = SynchronizedFileManager.getSynchronizedFile( target.getAbsolutePath() );
        File second = SynchronizedFileManager.getSynchronizedFile( target.getAbsolutePath() );

        assertSame( first, second, "the same absolute path must yield the same File instance" );
    }

    @Test
    void stringAndPathOverloadsShareTheSameRegistry( @TempDir File dir )
    {
        File target = new File( dir, "overload.txt" );

        File viaString = SynchronizedFileManager.getSynchronizedFile( target.getAbsolutePath() );
        File viaPath = SynchronizedFileManager.getSynchronizedFile( target.toPath() );

        assertSame( viaString, viaPath, "the String and Path overloads must resolve to one instance" );
    }

    /**
     * A relative path and its absolute-path equivalent (relative to the current working
     * directory) must collapse to the same key. This is the exact defect the class-level javadoc
     * warns about: before path normalization was added, {@code Path.of("foo")} and
     * {@code Path.of("/abs/.../foo")} produced different map keys for the same file.
     */
    @Test
    void relativeAndAbsoluteSpellingsOfTheSamePathCollapseToOneInstance()
    {
        String cwdRelativeName = "synchronized-file-manager-test-relative-" + System.nanoTime() + ".tmp";
        String absolutePath = new File( cwdRelativeName ).getAbsolutePath();

        File viaRelative = SynchronizedFileManager.getSynchronizedFile( cwdRelativeName );
        File viaAbsolute = SynchronizedFileManager.getSynchronizedFile( absolutePath );

        assertSame( viaRelative, viaAbsolute,
                    "a relative path and its resolved absolute equivalent must share one File instance" );
    }

    /**
     * A path containing a redundant {@code ./} segment must normalize to the same key as its
     * simplified form.
     */
    @Test
    void redundantPathSegmentsNormalizeToTheSameInstance( @TempDir File dir )
    {
        File target = new File( dir, "normalize.txt" );
        String withRedundantSegment = dir.getAbsolutePath() + File.separator + "." + File.separator +
                "normalize.txt";

        File direct = SynchronizedFileManager.getSynchronizedFile( target.getAbsolutePath() );
        File viaRedundant = SynchronizedFileManager.getSynchronizedFile( withRedundantSegment );

        assertSame( direct, viaRedundant );
    }

    /**
     * On Windows, the file system is case-insensitive, so two differently-cased spellings of the
     * same path must still collapse to one instance — otherwise two threads could synchronize on
     * different objects while writing the same on-disk file. This test only asserts the
     * case-folding behavior when actually running on Windows; on case-sensitive file systems
     * (macOS default, Linux), two different casings legitimately address different files, so the
     * instances are expected to differ there.
     */
    @Test
    void caseVariantPathsCollapseOnWindowsOnly( @TempDir File dir )
    {
        String lower = new File( dir, "casetest.txt" ).getAbsolutePath();
        String upper = new File( dir, "CASETEST.TXT" ).getAbsolutePath();

        File viaLower = SynchronizedFileManager.getSynchronizedFile( lower );
        File viaUpper = SynchronizedFileManager.getSynchronizedFile( upper );

        if ( SystemUtils.IS_OS_WINDOWS ) {
            assertSame( viaLower, viaUpper,
                        "Windows' case-insensitive file system must fold casing to one instance" );
        }
        else {
            org.junit.jupiter.api.Assertions.assertNotSame( viaLower, viaUpper,
                    "on a case-sensitive file system these are two distinct files" );
        }
    }
}

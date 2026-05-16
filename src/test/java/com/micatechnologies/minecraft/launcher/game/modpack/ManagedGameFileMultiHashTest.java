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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the multi-hash storage + strongest-first verification dispatch
 * added in the SECURITY 3.4 refactor. The single-hash constructor still
 * works (one slot populated, two sentinel) and is covered indirectly by
 * every legacy ManagedGameFile callsite; the new behaviour worth pinning
 * is:
 *
 * <ul>
 *   <li>The 5-arg constructor populates each slot the caller hands it
 *       and writes the {@code "-1"} sentinel for the rest.</li>
 *   <li>{@code verifyLocalFile} prefers SHA-256 over SHA-1 when both are
 *       set — a SHA-1-collision attack on a manifest that ALSO carries a
 *       genuine SHA-256 is rejected because the SHA-256 mismatch wins.</li>
 *   <li>Empty / blank / {@code null} hash inputs collapse to the {@code "-1"}
 *       sentinel so legacy manifests carrying {@code "sha256": ""} keep
 *       falling through to the next-strongest available hash.</li>
 * </ul>
 */
class ManagedGameFileMultiHashTest
{
    // SHA-1 / SHA-256 of the literal bytes "hello\n" — used as the fixed
    // "known good" file content for the verify tests so they don't have to
    // shell out to a hashing tool at runtime.
    private static final byte[] HELLO_BYTES   = "hello\n".getBytes();
    private static final String HELLO_SHA1    = "f572d396fae9206628714fb2ce00f72e94f2258f";
    private static final String HELLO_SHA256  = "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03";

    @Test
    void multiHashConstructorPopulatesEachProvidedSlot()
    {
        ManagedGameFile f = new ManagedGameFile( "https://e/x.jar", "x.jar",
                                                  "aaa", "bbb", "ccc" );
        assertEquals( "aaa", f.storedSha1() );
        assertEquals( "bbb", f.storedMd5() );
        assertEquals( "ccc", f.storedSha256() );
    }

    @Test
    void multiHashConstructorTreatsNullAndBlankAsSentinel()
    {
        ManagedGameFile f = new ManagedGameFile( "https://e/x.jar", "x.jar",
                                                  null, "  ", "" );
        assertEquals( "-1", f.storedSha1() );
        assertEquals( "-1", f.storedMd5() );
        assertEquals( "-1", f.storedSha256() );
    }

    @Test
    void singleHashConstructorStillRoutesToCorrectSlot()
    {
        ManagedGameFile s256 = new ManagedGameFile( "https://e/x.jar", "x.jar",
                                                     "ccc", ManagedGameFile.ManagedGameFileHashType.SHA256 );
        assertEquals( "ccc", s256.storedSha256() );
        assertEquals( "-1", s256.storedSha1() );
        assertEquals( "-1", s256.storedMd5() );

        ManagedGameFile s1 = new ManagedGameFile( "https://e/x.jar", "x.jar",
                                                   "aaa", ManagedGameFile.ManagedGameFileHashType.SHA1 );
        assertEquals( "aaa", s1.storedSha1() );
        assertEquals( "-1", s1.storedSha256() );
        assertEquals( "-1", s1.storedMd5() );
    }

    @Test
    void verifyPrefersSha256WhenBothShaPopulated( @TempDir Path tmp ) throws Exception
    {
        // File with SHA-256 = HELLO_SHA256, SHA-1 = HELLO_SHA1.
        Path file = tmp.resolve( "hello.txt" );
        Files.write( file, HELLO_BYTES );

        // Both hashes correct → verify true.
        ManagedGameFile both = newAt( file, HELLO_SHA1, HELLO_SHA256 );
        assertTrue( both.verifyLocalFileForTest(),
                "matching SHA-256 + matching SHA-1 should verify" );

        // SHA-256 wrong, SHA-1 correct → verify FALSE. The strongest-first
        // dispatch deliberately doesn't fall back to SHA-1 once a stronger
        // hash exists — that's the SHA-1-collision defense.
        ManagedGameFile wrongSha256 = newAt( file, HELLO_SHA1,
                                              "0000000000000000000000000000000000000000000000000000000000000000" );
        assertFalse( wrongSha256.verifyLocalFileForTest(),
                "wrong SHA-256 must reject even when SHA-1 is correct — no fallback" );

        // SHA-256 correct, SHA-1 wrong → verify TRUE. SHA-256 is the
        // primary check, SHA-1 is never even consulted on the strong path.
        ManagedGameFile wrongSha1 = newAt( file, "0000000000000000000000000000000000000000",
                                            HELLO_SHA256 );
        assertTrue( wrongSha1.verifyLocalFileForTest(),
                "matching SHA-256 should verify even when SHA-1 is stale" );
    }

    @Test
    void verifyFallsThroughToSha1WhenSha256Sentinel( @TempDir Path tmp ) throws Exception
    {
        Path file = tmp.resolve( "hello.txt" );
        Files.write( file, HELLO_BYTES );

        // Only SHA-1 populated (Mojang piston-meta v1, Forge installers).
        ManagedGameFile only1 = newAt( file, HELLO_SHA1, null );
        assertTrue( only1.verifyLocalFileForTest() );

        ManagedGameFile only1Wrong = newAt( file, "0000000000000000000000000000000000000000", null );
        assertFalse( only1Wrong.verifyLocalFileForTest() );
    }

    /** Builds a ManagedGameFile rooted at {@code file} with the given
     *  primary hashes (md5 unused). The local-path-prefix gymnastics here
     *  exists because verifyLocalFile checks the resolved path is contained
     *  under the prefix to defend against {@code "../../"} manifest abuse —
     *  the test rebinds the prefix to the temp dir's parent so the file's
     *  relative name resolves to itself. */
    private static ManagedGameFile newAt( Path file, String sha1, String sha256 )
    {
        ManagedGameFile f = new ManagedGameFile( "https://e/x.jar",
                                                  file.getFileName().toString(),
                                                  sha1, null, sha256 );
        f.setLocalPathPrefix( file.getParent().toString() + java.io.File.separator );
        return f;
    }
}

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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the post-download integrity gate, {@code matchesDeclaredHash}.
 * Unlike {@code verifyLocalFile}, the gate must hash freshly downloaded
 * bytes for real every time:
 *
 * <ul>
 *   <li>It dispatches strongest-first (SHA-256 → SHA-1 → MD5) just like
 *       the verify path, with no fallback past a populated stronger
 *       hash.</li>
 *   <li>It ignores the {@code FAST_PATH} launch verify mode — fast-path
 *       only skips re-hashing content already accepted by a prior FULL
 *       verify, never content that just arrived over the network.</li>
 *   <li>A file with no declared hash passes (nothing to verify
 *       against).</li>
 * </ul>
 */
class ManagedGameFilePostDownloadGateTest
{
    // SHA-1 / SHA-256 of the literal bytes "hello\n".
    private static final byte[] HELLO_BYTES  = "hello\n".getBytes();
    private static final String HELLO_SHA1   = "f572d396fae9206628714fb2ce00f72e94f2258f";
    private static final String HELLO_SHA256 = "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03";
    private static final String WRONG_SHA1   = "0000000000000000000000000000000000000000";
    private static final String WRONG_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000";

    @AfterEach
    void resetVerifyMode()
    {
        ManagedGameFile.setCurrentVerifyMode( LaunchVerifyMode.FULL );
    }

    @Test
    void gateAcceptsMatchingHashAndRejectsMismatch( @TempDir Path tmp ) throws Exception
    {
        File file = write( tmp );

        ManagedGameFile good = new ManagedGameFile( "https://e/x.jar", "x.jar",
                                                     HELLO_SHA1, null, null );
        assertTrue( good.matchesDeclaredHash( file ) );

        ManagedGameFile bad = new ManagedGameFile( "https://e/x.jar", "x.jar",
                                                    WRONG_SHA1, null, null );
        assertFalse( bad.matchesDeclaredHash( file ),
                "mismatched bytes must be rejected at the post-download gate" );
    }

    @Test
    void gateDispatchesStrongestFirstWithNoFallback( @TempDir Path tmp ) throws Exception
    {
        File file = write( tmp );

        // SHA-256 wrong, SHA-1 correct → reject; no fallback past the
        // strongest populated hash (SHA-1-collision defense).
        ManagedGameFile wrongStrong = new ManagedGameFile( "https://e/x.jar", "x.jar",
                                                            HELLO_SHA1, null, WRONG_SHA256 );
        assertFalse( wrongStrong.matchesDeclaredHash( file ) );

        // SHA-256 correct, SHA-1 stale → accept; SHA-1 never consulted.
        ManagedGameFile rightStrong = new ManagedGameFile( "https://e/x.jar", "x.jar",
                                                            WRONG_SHA1, null, HELLO_SHA256 );
        assertTrue( rightStrong.matchesDeclaredHash( file ) );
    }

    @Test
    void gateIgnoresFastPathVerifyMode( @TempDir Path tmp ) throws Exception
    {
        File file = write( tmp );

        ManagedGameFile bad = new ManagedGameFile( "https://e/x.jar", "x.jar",
                                                    WRONG_SHA1, null, null );

        ManagedGameFile.setCurrentVerifyMode( LaunchVerifyMode.FAST_PATH );
        assertFalse( bad.matchesDeclaredHash( file ),
                "FAST_PATH must not bypass the post-download acceptance gate" );
    }

    @Test
    void gatePassesWhenNoHashDeclared( @TempDir Path tmp ) throws Exception
    {
        File file = write( tmp );

        ManagedGameFile noHash = new ManagedGameFile( "https://e/x.jar", "x.jar" );
        assertTrue( noHash.matchesDeclaredHash( file ) );
    }

    private static File write( Path tmp ) throws Exception
    {
        Path file = tmp.resolve( "hello.txt" );
        Files.write( file, HELLO_BYTES );
        return file.toFile();
    }
}

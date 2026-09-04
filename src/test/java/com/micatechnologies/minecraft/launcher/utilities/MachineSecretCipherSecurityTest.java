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

package com.micatechnologies.minecraft.launcher.utilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Security-boundary tests for {@link MachineSecretCipher} — the AES-256-GCM
 * primitive that protects the Minecraft/Microsoft auth tokens
 * ({@code player.mica}, {@code cached_user.json}) and the CurseForge API key
 * at rest ({@code game/auth} + {@link com.micatechnologies.minecraft.launcher.config.AuthTokenStore}).
 *
 * <p>A regression here is high blast-radius. If tamper detection ever broke,
 * a corrupted or attacker-modified credential file would decrypt into
 * garbage bytes fed straight into the auth pipeline instead of failing
 * closed. If wrong-key handling ever broke, a config folder copied to
 * another machine (or restored from an unrelated install) could silently
 * "succeed" with corrupted plaintext instead of raising a clear failure the
 * caller can react to by re-prompting login.</p>
 *
 * <h3>Why the real cipher runs in a child JVM</h3>
 * <p>{@code MachineSecretCipher#deriveMachineKey} unconditionally mixes in a
 * per-install secret persisted at
 * {@code <launcher config folder>/machine-key.bin} (see its
 * {@code getOrCreateInstallSecret}) — there is no lower-level pure seam that
 * performs the AES-GCM operation without that disk round-trip. Under this
 * test JVM, {@code GameModeManager} never has its game mode set (nothing
 * here calls {@code inferGameMode()}), so {@code LocalPathManager} resolves
 * the launcher config folder to {@code <current working directory>/config} —
 * the server-mode fallback. Calling {@code encrypt}/{@code decrypt} directly
 * from this test process would therefore create a stray
 * {@code config/machine-key.bin} in whatever directory Maven happens to run
 * from, which is neither a JUnit {@code @TempDir} nor safely disposable.</p>
 *
 * <p>Every test below that needs the real cipher instead shells out to
 * {@link MachineSecretCipherSubprocessHarness} with the child process's
 * working directory pinned to a {@code @TempDir}, confining that incidental
 * write to the sandbox while still exercising the exact production
 * encrypt/decrypt code path end to end. Tests that fail before the cipher
 * ever reaches disk (null / blank / non-Base64 / too-short input) run
 * in-process with no subprocess at all, since {@code MachineSecretCipher}
 * short-circuits before deriving a key in those cases — see each method's
 * comment for why it's safe.</p>
 */
class MachineSecretCipherSecurityTest
{
    // ===================================================================
    // Malformed-input handling — these all return/throw before the cipher
    // ever touches the machine-key file, so they run directly, no subprocess.
    // ===================================================================

    @Test
    void encryptOfNullReturnsNull() throws Exception
    {
        assertNull( MachineSecretCipher.encrypt( null ) );
    }

    @Test
    void decryptOfNullReturnsNull() throws Exception
    {
        assertNull( MachineSecretCipher.decrypt( null ) );
    }

    @Test
    void decryptOfEmptyStringReturnsNull() throws Exception
    {
        assertNull( MachineSecretCipher.decrypt( "" ) );
    }

    @Test
    void decryptOfBlankStringReturnsNull() throws Exception
    {
        assertNull( MachineSecretCipher.decrypt( "    " ) );
    }

    @Test
    void encryptBytesOfNullReturnsNull() throws Exception
    {
        assertNull( MachineSecretCipher.encryptBytes( null ) );
    }

    @Test
    void decryptBytesOfNullReturnsNull() throws Exception
    {
        assertNull( MachineSecretCipher.decryptBytes( null ) );
    }

    @Test
    void decryptBytesOfEmptyArrayReturnsNull() throws Exception
    {
        assertNull( MachineSecretCipher.decryptBytes( new byte[ 0 ] ) );
    }

    @Test
    void decryptBytesOfTruncatedBlobReturnsNull() throws Exception
    {
        // One byte short of the 28-byte salt[16] + iv[12] floor. A caller
        // hitting this is a half-written or corrupted file, not a wrong key —
        // it must fail cleanly (null) rather than hand an under-sized buffer
        // to the cipher.
        assertNull( MachineSecretCipher.decryptBytes( new byte[ 27 ] ) );
    }

    @Test
    void decryptOfNonBase64GarbageFailsCleanly()
    {
        // Anything a user might paste into a config field, or a sync tool
        // might mangle, can land here. It must surface as a plain, catchable
        // failure at the Base64 layer — not an obscure exception from deep
        // inside a crypto provider. (Callers such as AuthTokenStore already
        // wrap this in try/catch(Throwable) for exactly this reason.)
        assertThrows( IllegalArgumentException.class,
                () -> MachineSecretCipher.decrypt( "!!!not-valid-base64!!!" ) );
    }

    // ===================================================================
    // Real-cipher tests — run the harness subprocess, cwd pinned to @TempDir.
    // ===================================================================

    @Test
    void roundTripSucceedsAndTamperIsDetectedAtEveryPosition( @TempDir Path tempDir ) throws Exception
    {
        String plaintext = "synthetic-test-token-" + System.nanoTime();
        List< String > lines = runHarness( tempDir, "fulltest", plaintext );

        assertTrue( containsLineStartingWith( lines, "ENCRYPT:OK" ),
                "encryption should succeed: " + lines );

        // 1. Encrypt/decrypt round-trip returns the exact original plaintext.
        assertTrue( containsLineStartingWith( lines, "BASELINE:OK:" + plaintext ),
                "round-trip decrypt should return the exact original plaintext: " + lines );

        // 2. Tamper detection: flipping a byte at the start (salt), middle
        //    (ciphertext), or end (GCM tag) of the envelope must fail
        //    decryption rather than silently return corrupted/valid-looking
        //    plaintext.
        assertTamperCausesFailure( lines, "TAMPER_START", plaintext );
        assertTamperCausesFailure( lines, "TAMPER_MIDDLE", plaintext );
        assertTamperCausesFailure( lines, "TAMPER_END", plaintext );

        // 4. Malformed-but-plausible input: random bytes the same length as
        //    a real envelope must not decrypt successfully.
        assertFalse( containsLineStartingWith( lines, "GARBAGE:OK" ),
                "random garbage the size of a real envelope must not decrypt successfully: " + lines );
        assertTrue( containsLineStartingWith( lines, "GARBAGE:THROW:" ),
                "random garbage long enough to look like a real envelope must fail via a thrown "
                        + "exception, not a silent null: " + lines );
    }

    @Test
    void wrongMachineKeyFailsCleanlyRatherThanReturningGarbage(
            @TempDir Path machineA, @TempDir Path machineB ) throws Exception
    {
        String plaintext = "synthetic-cross-install-token-" + System.nanoTime();

        List< String > encryptLines = runHarness( machineA, "encrypt", plaintext );
        String envelope = extractValue( encryptLines, "ENVELOPE:" );
        assertNotNull( envelope, "harness failed to produce an envelope: " + encryptLines );

        // Control: the SAME install (same machine-key.bin) can decrypt its
        // own envelope — proves the envelope itself is valid and any
        // cross-install failure below is really about the key, not a
        // harness bug.
        List< String > sameMachine = runHarness( machineA, "decrypt", envelope );
        assertTrue( containsLineStartingWith( sameMachine, "RESULT:OK:" + plaintext ),
                "sanity check failed: the producing install couldn't decrypt its own envelope: " + sameMachine );

        // A different install — fresh @TempDir, so a different randomly
        // generated per-install secret and therefore a different derived
        // key — must NOT be able to decrypt it, and must fail loudly rather
        // than returning corrupted / unrelated plaintext.
        List< String > crossMachine = runHarness( machineB, "decrypt", envelope );
        assertFalse( containsLineStartingWith( crossMachine, "RESULT:OK:" + plaintext ),
                "a different install must not be able to decrypt another install's envelope: " + crossMachine );
        assertTrue( containsLineStartingWith( crossMachine, "RESULT:THROW:" ),
                "cross-install decrypt should fail via a thrown exception (GCM auth tag check): " + crossMachine );
    }

    // ===================================================================
    // Harness plumbing
    // ===================================================================

    private static void assertTamperCausesFailure( List< String > lines, String label, String plaintext )
    {
        assertFalse( containsLineStartingWith( lines, label + ":OK:" + plaintext ),
                label + " must not decrypt back to the original plaintext: " + lines );
        assertTrue( containsLineStartingWith( lines, label + ":THROW:" ),
                label + " must fail via a thrown exception (GCM auth tag check), not a silent "
                        + "null/garbage return: " + lines );
    }

    private static boolean containsLineStartingWith( List< String > lines, String prefix )
    {
        return lines.stream().anyMatch( l -> l.startsWith( prefix ) );
    }

    private static String extractValue( List< String > lines, String prefix )
    {
        return lines.stream()
                .filter( l -> l.startsWith( prefix ) )
                .map( l -> l.substring( prefix.length() ) )
                .findFirst()
                .orElse( null );
    }

    /**
     * Runs {@link MachineSecretCipherSubprocessHarness} in a child JVM whose
     * working directory is {@code cwd}, reusing this test JVM's own java
     * executable and classpath. Returns stdout (with stderr merged in) as a
     * list of trimmed lines.
     */
    private static List< String > runHarness( Path cwd, String... harnessArgs ) throws Exception
    {
        String javaBin = System.getProperty( "java.home" ) + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty( "java.class.path" );

        List< String > command = new ArrayList<>();
        command.add( javaBin );
        command.add( "-cp" );
        command.add( classpath );
        command.add( MachineSecretCipherSubprocessHarness.class.getName() );
        command.addAll( List.of( harnessArgs ) );

        Process process = new ProcessBuilder( command )
                .directory( cwd.toFile() )
                .redirectErrorStream( true )
                .start();

        List< String > lines = new ArrayList<>();
        try ( BufferedReader reader = new BufferedReader(
                new InputStreamReader( process.getInputStream(), StandardCharsets.UTF_8 ) ) ) {
            String line;
            while ( ( line = reader.readLine() ) != null ) {
                lines.add( line );
            }
        }

        boolean finished = process.waitFor( 30, TimeUnit.SECONDS );
        if ( !finished ) {
            process.destroyForcibly();
            fail( "harness subprocess did not exit within 30s; output so far: " + lines );
        }
        return lines;
    }
}

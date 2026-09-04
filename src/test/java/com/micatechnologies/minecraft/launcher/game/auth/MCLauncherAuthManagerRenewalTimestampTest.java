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

package com.micatechnologies.minecraft.launcher.game.auth;

import com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipherSubprocessHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for {@code MCLauncherAuthManager#readRenewalTimestamp} — the parser
 * that decides how "recently signed in" the launcher believes the user to
 * be, by reading the on-disk renewal-timestamp file back.
 *
 * <p>This is the read half of a migration seam: pre-2026 installs left a
 * plain decimal epoch-millis string on disk, current installs write it
 * encrypted at rest via {@code MachineSecretCipher}. Get the fallback
 * ordering wrong and either every upgrading user's saved renewal time reads
 * as garbage (falls back to 0, forcing an unnecessary re-authentication on
 * their next launch) or, worse, a corrupted/foreign encrypted file gets
 * mistaken for a legacy plaintext number. The method always fails toward
 * {@code 0} ("unknown, so due"), never toward trusting a bad parse — these
 * tests pin that every malformed-input path actually lands on 0 rather than
 * throwing or returning a garbage value.</p>
 *
 * <p>Most cases here run in-process: every input below either fails at the
 * Base64-decode step or decodes to fewer than 28 bytes (the salt+IV floor
 * {@code MachineSecretCipher.decryptBytes} checks before ever deriving a
 * key), so {@code MachineSecretCipher.decrypt} never touches the
 * machine-key file on disk — see each test's inline comment. The one
 * exception, the successful-decrypt path, needs a real encrypted envelope
 * and therefore a real machine-key file; that one test shells out to two
 * subprocess harnesses sharing a {@code @TempDir} working directory. See
 * {@link MCLauncherAuthManagerRenewalTimestampHarness}'s javadoc.</p>
 */
class MCLauncherAuthManagerRenewalTimestampTest
{
    // ===================================================================
    // In-process — no input below reaches MachineSecretCipher's key
    // derivation, so none of these touch disk. See class javadoc.
    // ===================================================================

    @Test
    void nullRawReturnsZero()
    {
        assertEquals( 0L, MCLauncherAuthManager.readRenewalTimestamp( null ) );
    }

    @Test
    void blankRawReturnsZero()
    {
        assertEquals( 0L, MCLauncherAuthManager.readRenewalTimestamp( "   " ) );
    }

    /**
     * "not-a-number" contains '-', which is not in the standard (non-URL)
     * Base64 alphabet {@code MachineSecretCipher.decrypt} uses, so decoding
     * throws immediately — before any key derivation — and the method falls
     * back to the legacy plain-decimal parse, which also fails.
     */
    @Test
    void nonBase64NonNumericGarbageReturnsZero()
    {
        assertEquals( 0L, MCLauncherAuthManager.readRenewalTimestamp( "not-a-number" ) );
    }

    /**
     * A realistic 13-digit epoch-millis string is not a multiple-of-4-safe
     * Base64 length ("Last unit does not have enough valid bits"), so
     * decoding throws and the method falls back to parsing the raw decimal
     * string directly — the legacy pre-encryption on-disk format.
     */
    @Test
    void legacyPlainDecimalTimestampParsesDirectly()
    {
        assertEquals( 1_735_689_600_000L, MCLauncherAuthManager.readRenewalTimestamp( "1735689600000" ) );
    }

    @Test
    void legacyNegativeNumberParsesDirectlyRatherThanBeingTreatedAsAFailure()
    {
        // '-' is illegal Base64, so this also falls to the legacy parse.
        // Long.parseLong happily accepts a leading '-'; readRenewalTimestamp
        // does not separately validate sign, so the negative value comes
        // back verbatim rather than being coerced to the 0 "unknown" sentinel.
        assertEquals( -5L, MCLauncherAuthManager.readRenewalTimestamp( "-5" ) );
    }

    /**
     * "12345678901234" (14 digits) IS valid Base64 (length % 4 == 2 is
     * legal, no padding required) and decodes to 10 bytes — under the
     * 28-byte salt+IV floor, so {@code decryptBytes} returns {@code null}
     * without deriving a key. {@code readRenewalTimestamp} then falls back
     * to parsing the ORIGINAL raw string (not the decoded bytes), so the
     * full 14-digit value comes back, not something derived from the 10
     * decoded bytes.
     */
    @Test
    void base64ShapedNumberThatDecodesTooShortFallsBackToParsingTheOriginalString()
    {
        assertEquals( 12_345_678_901_234L,
                MCLauncherAuthManager.readRenewalTimestamp( "12345678901234" ) );
    }

    // ===================================================================
    // Subprocess — the successful-decrypt branch needs a real machine key.
    // ===================================================================

    /**
     * Full round trip through the production encrypt path (via
     * {@code MachineSecretCipherSubprocessHarness}) and then the production
     * decrypt path inside {@code readRenewalTimestamp} (via
     * {@link MCLauncherAuthManagerRenewalTimestampHarness}), both pinned to
     * the same {@code @TempDir} working directory so they share one
     * machine-key file. Confirms the primary "current, encrypted on-disk
     * format" branch actually round-trips, not just the legacy fallbacks
     * above.
     */
    @Test
    void encryptedTimestampRoundTripsThroughReadRenewalTimestamp( @TempDir Path tempDir ) throws Exception
    {
        String fakeEpochMillis = "1738368000000";

        List< String > encryptLines = runHarness( tempDir, MachineSecretCipherSubprocessHarness.class.getName(),
                "encrypt", fakeEpochMillis );
        String envelope = encryptLines.stream()
                .filter( l -> l.startsWith( "ENVELOPE:" ) )
                .map( l -> l.substring( "ENVELOPE:".length() ) )
                .findFirst()
                .orElse( null );
        assertNotNull( envelope, "harness failed to produce an envelope: " + encryptLines );

        List< String > readLines = runHarness( tempDir, MCLauncherAuthManagerRenewalTimestampHarness.class.getName(),
                "read", envelope );
        assertTrue( readLines.contains( "RESULT:" + fakeEpochMillis ),
                "decrypting the envelope through readRenewalTimestamp should yield the original value: "
                        + readLines );
    }

    // ===================================================================
    // Harness plumbing
    // ===================================================================

    private static List< String > runHarness( Path cwd, String mainClass, String... harnessArgs ) throws Exception
    {
        String javaBin = System.getProperty( "java.home" ) + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty( "java.class.path" );

        List< String > command = new ArrayList<>();
        command.add( javaBin );
        command.add( "-cp" );
        command.add( classpath );
        // Forward this test JVM's own JaCoCo agent (if present) so the
        // production code the child process actually executes is credited
        // to the coverage report instead of vanishing because it ran
        // uninstrumented. See ProfileArchiveTest#jacocoAgentArgOrNull.
        String jacocoAgentArg = jacocoAgentArgOrNull();
        if ( jacocoAgentArg != null ) {
            command.add( jacocoAgentArg );
        }
        command.add( mainClass );
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

    /**
     * Finds the {@code -javaagent} flag JaCoCo's {@code prepare-agent} goal
     * added to this JVM's own launch command, if any. Returns {@code null}
     * outside a JaCoCo-instrumented run, in which case the subprocess simply
     * runs uninstrumented.
     */
    private static String jacocoAgentArgOrNull()
    {
        for ( String arg : ManagementFactory.getRuntimeMXBean().getInputArguments() ) {
            if ( arg.startsWith( "-javaagent:" ) && arg.contains( "jacoco" ) ) {
                return arg;
            }
        }
        return null;
    }
}

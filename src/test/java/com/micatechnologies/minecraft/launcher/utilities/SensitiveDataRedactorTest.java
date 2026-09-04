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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SensitiveDataRedactor} — the launcher's only defense against
 * writing a live Minecraft session token into a log file.
 *
 * <p>This class guards a real security boundary. {@code redact} is applied to the
 * assembled game command line before it is logged
 * ({@code GameModPackLauncher}) and to every line rendered in the in-game console
 * ({@code MCLauncherGameConsoleGui}). The command line carries
 * {@code --accessToken <live token>}; if a pattern regresses, that token lands in a
 * plaintext log file that users routinely paste into Discord and GitHub issues when
 * asking for help. There is no second layer behind this one.</p>
 *
 * <p>The two methods have deliberately different contracts and both are covered here:
 * {@code redact} preserves the trailing UUID of a legacy session string (it is not a
 * credential on its own, and keeping it makes a redacted console line recognizable),
 * while {@code redactStrict} additionally strips account UUIDs for callers whose
 * contract forbids emitting any identifying account value at all.</p>
 */
class SensitiveDataRedactorTest
{
    /** A syntactically realistic (but entirely fabricated) Mojang-style JWT. */
    private static final String FAKE_TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.Zm9vYmFyYmF6cXV4";

    /** Fabricated account UUID, dashed form. */
    private static final String FAKE_UUID_DASHED = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    /** The same fabricated UUID with the dashes stripped — the form Mojang's launch
     *  arguments actually use. */
    private static final String FAKE_UUID_UNDASHED = "069a79f444e94726a5befca90e38aaf5";

    // =========================================================================
    //  redact() — null / empty / passthrough
    // =========================================================================

    @Test
    void redactPassesNullThrough()
    {
        assertNull( SensitiveDataRedactor.redact( null ) );
    }

    @Test
    void redactPassesEmptyStringThrough()
    {
        assertEquals( "", SensitiveDataRedactor.redact( "" ) );
    }

    @Test
    void redactLeavesOrdinaryLogLinesUntouched()
    {
        String line = "[12:04:11] [main/INFO]: Loaded 412 mods in 8.2s";
        assertEquals( line, SensitiveDataRedactor.redact( line ) );
    }

    // =========================================================================
    //  redact() — access token
    // =========================================================================

    @Test
    void redactStripsSpaceSeparatedAccessToken()
    {
        String out = SensitiveDataRedactor.redact( "--accessToken " + FAKE_TOKEN + " --version 1.20.1" );
        assertFalse( out.contains( FAKE_TOKEN ), "live token survived redaction" );
        assertTrue( out.contains( "--accessToken" ), "flag name should be preserved" );
        assertTrue( out.contains( "--version 1.20.1" ), "following args should be preserved" );
    }

    @Test
    void redactStripsEqualsSeparatedAccessToken()
    {
        String out = SensitiveDataRedactor.redact( "--accessToken=" + FAKE_TOKEN );
        assertFalse( out.contains( FAKE_TOKEN ), "live token survived redaction" );
        assertTrue( out.contains( "--accessToken" ), "flag name should be preserved" );
    }

    // =========================================================================
    //  redact() — client token
    // =========================================================================

    @Test
    void redactStripsClientTokenInBothSeparatorForms()
    {
        String spaced = SensitiveDataRedactor.redact( "--clientToken " + FAKE_TOKEN );
        String equals = SensitiveDataRedactor.redact( "--clientToken=" + FAKE_TOKEN );
        assertFalse( spaced.contains( FAKE_TOKEN ), "live token survived (space form)" );
        assertFalse( equals.contains( FAKE_TOKEN ), "live token survived (equals form)" );
    }

    // =========================================================================
    //  redact() — legacy session string
    // =========================================================================

    @Test
    void redactStripsLegacySessionTokenButKeepsTrailingUuid()
    {
        String out = SensitiveDataRedactor.redact(
                "--session token:" + FAKE_TOKEN + ":" + FAKE_UUID_UNDASHED );
        assertFalse( out.contains( FAKE_TOKEN ), "legacy session token survived redaction" );
        // Documented, deliberate behaviour: the trailing UUID is retained so the
        // redacted console line is still recognizable as a session string.
        assertTrue( out.contains( FAKE_UUID_UNDASHED ),
                    "redact() is specified to preserve the trailing UUID" );
    }

    // =========================================================================
    //  redact() — multiple secrets in one line
    // =========================================================================

    @Test
    void redactStripsEverySecretOnALineNotJustTheFirst()
    {
        String out = SensitiveDataRedactor.redact(
                "java --accessToken " + FAKE_TOKEN + " --clientToken " + FAKE_TOKEN + " -Xmx8G" );
        assertFalse( out.contains( FAKE_TOKEN ), "a secret survived on a multi-secret line" );
        assertTrue( out.contains( "-Xmx8G" ), "trailing args should be preserved" );
    }

    // =========================================================================
    //  redactStrict() — UUID handling
    // =========================================================================

    @Test
    void redactStrictPassesNullAndEmptyThrough()
    {
        assertNull( SensitiveDataRedactor.redactStrict( null ) );
        assertEquals( "", SensitiveDataRedactor.redactStrict( "" ) );
    }

    @Test
    void redactStrictStripsDashedUuid()
    {
        String out = SensitiveDataRedactor.redactStrict( "--uuid " + FAKE_UUID_DASHED );
        assertFalse( out.contains( FAKE_UUID_DASHED ), "dashed UUID survived strict redaction" );
    }

    @Test
    void redactStrictStripsUndashedUuid()
    {
        String out = SensitiveDataRedactor.redactStrict( "--uuid " + FAKE_UUID_UNDASHED );
        assertFalse( out.contains( FAKE_UUID_UNDASHED ), "undashed UUID survived strict redaction" );
    }

    @Test
    void redactStrictAlsoStripsTheLegacySessionTailThatRedactPreserves()
    {
        String input = "--session token:" + FAKE_TOKEN + ":" + FAKE_UUID_UNDASHED;
        assertTrue( SensitiveDataRedactor.redact( input ).contains( FAKE_UUID_UNDASHED ),
                    "precondition: redact() preserves the tail" );
        String strict = SensitiveDataRedactor.redactStrict( input );
        assertFalse( strict.contains( FAKE_TOKEN ), "token survived strict redaction" );
        assertFalse( strict.contains( FAKE_UUID_UNDASHED ),
                     "strict redaction must also remove the tail redact() keeps" );
    }

    @Test
    void redactStrictStillStripsTokensLikeRedactDoes()
    {
        String out = SensitiveDataRedactor.redactStrict( "--accessToken=" + FAKE_TOKEN );
        assertFalse( out.contains( FAKE_TOKEN ), "strict mode must be a superset of redact()" );
    }

    // =========================================================================
    //  redactStrict() — false-positive boundaries
    // =========================================================================

    @Test
    void redactStrictLeavesSha1HashesAlone()
    {
        // 40 hex chars. The undashed-UUID pattern is word-boundary anchored, so no
        // 32-char window inside a 40-char hex run can match. This matters because
        // library and asset SHA-1s appear constantly in launcher logs and redacting
        // them would make download-verification failures undiagnosable.
        String sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
        String out = SensitiveDataRedactor.redactStrict( "Verifying artifact " + sha1 );
        assertTrue( out.contains( sha1 ), "SHA-1 hash must not be redacted" );
    }

    @Test
    void redactStrictDoesNotTouchShortHexRuns()
    {
        String line = "exit code 0x1F, chunk a3f9c2";
        assertEquals( line, SensitiveDataRedactor.redactStrict( line ) );
    }

    @Test
    void redactStrictLeavesOrdinaryLogLinesUntouched()
    {
        String line = "[12:04:11] [main/INFO]: Loaded 412 mods in 8.2s";
        assertEquals( line, SensitiveDataRedactor.redactStrict( line ) );
    }

    /**
     * Documents a known and accepted false positive: a bare MD5 is 32 hex characters
     * and is therefore indistinguishable from an undashed UUID at this layer. Strict
     * mode redacts it. This is the intended trade — strict mode exists for callers
     * whose contract is "never emit an account identifier under any circumstances",
     * where over-redaction is the correct failure direction. Callers that need a
     * readable hash use {@code redact} instead.
     */
    @Test
    void redactStrictRedactsBareMd5AsAnAcceptedFalsePositive()
    {
        String md5 = "d41d8cd98f00b204e9800998ecf8427e";
        String out = SensitiveDataRedactor.redactStrict( "md5 " + md5 );
        assertFalse( out.contains( md5 ),
                     "documented behaviour: strict mode redacts 32-hex runs, MD5 included" );
    }

    // =========================================================================
    //  Realistic end-to-end shape
    // =========================================================================

    @Test
    void redactStrictScrubsARealisticLaunchCommandLine()
    {
        String cmd = "java -Xmx8G -cp forge.jar net.minecraft.client.main.Main"
                + " --username Steve"
                + " --uuid " + FAKE_UUID_UNDASHED
                + " --accessToken " + FAKE_TOKEN
                + " --userType msa --versionType release";
        String out = SensitiveDataRedactor.redactStrict( cmd );

        assertFalse( out.contains( FAKE_TOKEN ), "access token leaked" );
        assertFalse( out.contains( FAKE_UUID_UNDASHED ), "account UUID leaked" );
        // Username is explicitly NOT a credential and must survive — it is the one
        // piece of account context consumers are allowed to see.
        assertTrue( out.contains( "--username Steve" ), "username should be preserved" );
        assertTrue( out.contains( "-Xmx8G" ), "JVM args should be preserved" );
        assertTrue( out.contains( "--userType msa" ), "non-secret args should be preserved" );
    }
}

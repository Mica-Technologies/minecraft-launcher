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

import com.micatechnologies.minecraft.launcher.security.SupplementalScanner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the dual-hash ack matching semantics added so packs that update
 * their outer mods (e.g. City Super Mod) don't have to re-ack the same
 * embedded-artifact finding (e.g. shaded MaryTTS) on every release.
 *
 * <p>Matching rule:</p>
 * <ul>
 *   <li>{@code kind} is required and must match.</li>
 *   <li>At least one of {@code innerSha256} / {@code fileSha256} must be set
 *       — an ack with neither hash never matches.</li>
 *   <li>Each hash field, if set on the ack, must match the corresponding
 *       finding field. Both set = defense-in-depth (both must match).</li>
 *   <li>{@code locator}, if set on the ack, must match.</li>
 * </ul>
 */
class ScanAcknowledgementTest
{
    private static final String OUTER_SHA_A   = "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111";
    private static final String OUTER_SHA_B   = "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222";
    private static final String INNER_SHA_X   = "cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333";
    private static final String INNER_SHA_Y   = "dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444";
    private static final String LOCATOR_MARY  = "marytts/machinelearning/GMMTrainer.exe";

    /** Builds a Finding from the parts that matter for ack matching. */
    private static SupplementalScanner.Finding finding( String fileSha, String innerSha,
                                                         SupplementalScanner.Kind kind, String locator )
    {
        return new SupplementalScanner.Finding(
                SupplementalScanner.Severity.HIGH,
                kind,
                Path.of( "dummy.jar" ),
                fileSha,
                innerSha,
                locator,
                "test message" );
    }

    // =========================================================================================
    //  Inner-only ack semantics (the new "survives mod updates" path)
    // =========================================================================================

    @Test
    void innerOnlyAckMatchesEvenWhenOuterChanges()
    {
        // The whole reason this refactor exists: outer JAR repackaged → fileSha
        // changes, inner artifact byte-identical → innerSha stable. An ack
        // keyed on inner alone keeps matching.
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.innerSha256 = INNER_SHA_X;
        ack.kind = "EMBEDDED_EXECUTABLE";
        ack.locator = LOCATOR_MARY;
        ack.reason = "MaryTTS training binary shaded inside City Super Mod";

        SupplementalScanner.Finding originalRelease = finding(
                OUTER_SHA_A, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE,
                LOCATOR_MARY );
        SupplementalScanner.Finding nextRelease = finding(
                OUTER_SHA_B, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE,
                LOCATOR_MARY );

        assertTrue( ack.matches( originalRelease ),
                "Inner-only ack should match the original release" );
        assertTrue( ack.matches( nextRelease ),
                "Inner-only ack must keep matching when only the outer JAR repackages" );
    }

    @Test
    void innerOnlyAckRejectsWhenInnerChanges()
    {
        // If the actual offending element changed bytes, the ack stops
        // applying — the new content gets re-reviewed.
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.innerSha256 = INNER_SHA_X;
        ack.kind = "EMBEDDED_EXECUTABLE";

        SupplementalScanner.Finding patched = finding(
                OUTER_SHA_A, INNER_SHA_Y,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE,
                LOCATOR_MARY );

        assertFalse( ack.matches( patched ),
                "Inner-only ack must NOT match when the inner element changes" );
    }

    // =========================================================================================
    //  Outer-only ack semantics (existing back-compat path)
    // =========================================================================================

    @Test
    void outerOnlyAckMatchesSameOuterJar()
    {
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.fileSha256 = OUTER_SHA_A;
        ack.kind = "EMBEDDED_EXECUTABLE";

        SupplementalScanner.Finding f = finding(
                OUTER_SHA_A, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE,
                LOCATOR_MARY );

        assertTrue( ack.matches( f ) );
    }

    @Test
    void outerOnlyAckBreaksOnOuterChangeEvenWhenInnerUnchanged()
    {
        // This is the user-reported issue: outer JAR updated, inner unchanged,
        // the existing fileSha256-only ack stops applying and the finding
        // comes back. Test pins that behavior so a future "be lenient on
        // outer hash" refactor doesn't silently change it.
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.fileSha256 = OUTER_SHA_A;
        ack.kind = "EMBEDDED_EXECUTABLE";

        SupplementalScanner.Finding nextRelease = finding(
                OUTER_SHA_B, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE,
                LOCATOR_MARY );

        assertFalse( ack.matches( nextRelease ),
                "Outer-only ack must invalidate when the JAR is repackaged" );
    }

    // =========================================================================================
    //  Defense-in-depth (both set)
    // =========================================================================================

    @Test
    void bothHashesSetRequiresBothToMatch()
    {
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.fileSha256 = OUTER_SHA_A;
        ack.innerSha256 = INNER_SHA_X;
        ack.kind = "EMBEDDED_EXECUTABLE";

        // Both match → ack fires
        assertTrue( ack.matches( finding(
                OUTER_SHA_A, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, LOCATOR_MARY ) ) );

        // Only outer matches → ack doesn't fire (inner mutated = tampered)
        assertFalse( ack.matches( finding(
                OUTER_SHA_A, INNER_SHA_Y,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, LOCATOR_MARY ) ) );

        // Only inner matches → ack doesn't fire (outer repackaged outside
        // the trusted build, defense-in-depth treats this as suspicious)
        assertFalse( ack.matches( finding(
                OUTER_SHA_B, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, LOCATOR_MARY ) ) );
    }

    // =========================================================================================
    //  Edge cases
    // =========================================================================================

    @Test
    void neitherHashSetNeverMatches()
    {
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.kind = "EMBEDDED_EXECUTABLE";
        // No hash fields set → the ack is meaningless and must not silence
        // anything.
        assertFalse( ack.matches( finding(
                OUTER_SHA_A, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, LOCATOR_MARY ) ) );
    }

    @Test
    void missingKindNeverMatches()
    {
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.innerSha256 = INNER_SHA_X;
        // kind unset
        assertFalse( ack.matches( finding(
                OUTER_SHA_A, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, LOCATOR_MARY ) ) );
    }

    @Test
    void kindMismatchNeverMatches()
    {
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.innerSha256 = INNER_SHA_X;
        ack.kind = "IPV4_LITERAL";  // different rule

        assertFalse( ack.matches( finding(
                OUTER_SHA_A, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, LOCATOR_MARY ) ) );
    }

    @Test
    void hashComparisonIsCaseInsensitive()
    {
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.innerSha256 = INNER_SHA_X.toUpperCase();
        ack.kind = "EMBEDDED_EXECUTABLE";

        assertTrue( ack.matches( finding(
                OUTER_SHA_A, INNER_SHA_X.toLowerCase(),
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, LOCATOR_MARY ) ),
                "Hash comparison must tolerate case differences both directions" );
    }

    @Test
    void locatorWhenSetMustMatch()
    {
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.innerSha256 = INNER_SHA_X;
        ack.kind = "EMBEDDED_EXECUTABLE";
        ack.locator = LOCATOR_MARY;

        // Matching locator → fires.
        assertTrue( ack.matches( finding(
                OUTER_SHA_A, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, LOCATOR_MARY ) ) );

        // Different locator with otherwise-matching identity → doesn't fire.
        assertFalse( ack.matches( finding(
                OUTER_SHA_A, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, "other/Entry.exe" ) ) );
    }

    @Test
    void locatorWhenBlankMatchesAnyFindingOfSameKind()
    {
        // Wildcard-style ack: silence every EMBEDDED_EXECUTABLE with this
        // inner hash regardless of which path inside the JAR triggered it.
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.innerSha256 = INNER_SHA_X;
        ack.kind = "EMBEDDED_EXECUTABLE";
        ack.locator = "";   // blank — wildcard

        assertTrue( ack.matches( finding(
                OUTER_SHA_A, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, LOCATOR_MARY ) ) );
        assertTrue( ack.matches( finding(
                OUTER_SHA_A, INNER_SHA_X,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, "different/path.exe" ) ) );
    }

    @Test
    void nullFindingNeverMatches()
    {
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.innerSha256 = INNER_SHA_X;
        ack.kind = "EMBEDDED_EXECUTABLE";
        assertFalse( ack.matches( null ) );
    }

    @Test
    void blankFindingHashNeverSatisfiesNonBlankAck()
    {
        // If the launcher couldn't compute the inner hash (read failure mid-
        // scan), an ack that *requires* the inner hash should not silently
        // pass — we don't have evidence the inner element is the same.
        ScanAcknowledgement ack = new ScanAcknowledgement();
        ack.innerSha256 = INNER_SHA_X;
        ack.kind = "EMBEDDED_EXECUTABLE";

        SupplementalScanner.Finding noInner = finding(
                OUTER_SHA_A, null,
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, LOCATOR_MARY );
        assertFalse( ack.matches( noInner ) );

        SupplementalScanner.Finding emptyInner = finding(
                OUTER_SHA_A, "",
                SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, LOCATOR_MARY );
        assertFalse( ack.matches( emptyInner ) );
    }
}

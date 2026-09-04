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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MCLauncherAuthManager#isRenewalDue(long, long, long)} — the decision
 * that governs when the launcher re-authenticates against Microsoft.
 *
 * <p>Why this matters, in both directions. Renew too late and the user's session expires
 * mid-launch, which surfaces as an opaque authentication failure at the worst possible
 * moment. Renew too eagerly and every launch makes an unnecessary network round trip and
 * risks rate limiting. The decision itself is a one-line elapsed-time comparison, but it
 * was previously buried inside a method that also loaded an encrypted timestamp file,
 * mutated a static field, and logged — so it could not be tested at all.</p>
 *
 * <p>The extracted predicate takes the clock as a parameter, so these tests are
 * deterministic and do not sleep.</p>
 */
class MCLauncherAuthManagerRenewalTest
{
    /** Matches the production interval (4 hours) without depending on the private constant. */
    private static final long FOUR_HOURS_MS = 4L * 60L * 60L * 1000L;

    /** An arbitrary fixed "now" so tests never read the real clock. */
    private static final long NOW = 1_700_000_000_000L;

    // =========================================================================
    //  Normal ageing
    // =========================================================================

    @Test
    void freshTokenIsNotDue()
    {
        assertFalse( MCLauncherAuthManager.isRenewalDue( NOW, NOW, FOUR_HOURS_MS ) );
    }

    @Test
    void tokenWellInsideTheIntervalIsNotDue()
    {
        long oneHourAgo = NOW - ( 60L * 60L * 1000L );
        assertFalse( MCLauncherAuthManager.isRenewalDue( oneHourAgo, NOW, FOUR_HOURS_MS ) );
    }

    @Test
    void tokenOneMillisecondShortOfTheIntervalIsNotDue()
    {
        assertFalse( MCLauncherAuthManager.isRenewalDue( NOW - FOUR_HOURS_MS + 1, NOW, FOUR_HOURS_MS ) );
    }

    /**
     * The comparison is inclusive: exactly at the interval counts as due. Pinned because
     * flipping this boundary would silently shift every renewal by one full interval.
     */
    @Test
    void tokenExactlyAtTheIntervalIsDue()
    {
        assertTrue( MCLauncherAuthManager.isRenewalDue( NOW - FOUR_HOURS_MS, NOW, FOUR_HOURS_MS ) );
    }

    @Test
    void tokenPastTheIntervalIsDue()
    {
        assertTrue( MCLauncherAuthManager.isRenewalDue( NOW - ( FOUR_HOURS_MS * 3 ), NOW, FOUR_HOURS_MS ) );
    }

    // =========================================================================
    //  Unknown timestamp — fail toward re-authentication
    // =========================================================================

    /**
     * Zero is the sentinel for "never renewed, or the timestamp file was missing,
     * unreadable, or failed to decrypt". It must report due: the safe direction is
     * re-authenticating unnecessarily, not trusting a token of unknown age.
     */
    @Test
    void unknownTimestampIsAlwaysDue()
    {
        assertTrue( MCLauncherAuthManager.isRenewalDue( 0L, NOW, FOUR_HOURS_MS ) );
    }

    @Test
    void negativeTimestampIsTreatedAsUnknownAndIsDue()
    {
        assertTrue( MCLauncherAuthManager.isRenewalDue( -1L, NOW, FOUR_HOURS_MS ) );
    }

    // =========================================================================
    //  Clock skew
    // =========================================================================

    /**
     * A timestamp in the future — an NTP correction, or a config folder synced from a
     * machine whose clock ran ahead — yields a negative elapsed time and reports not due.
     * Pinned deliberately: the alternative interpretation (treat "impossible" as "renew")
     * would force a re-login on every launch until the local clock caught up, which for a
     * badly-skewed machine could be indefinitely.
     */
    @Test
    void futureTimestampReportsNotDueRatherThanForcingRepeatedLogins()
    {
        long oneDayAhead = NOW + ( 24L * 60L * 60L * 1000L );
        assertFalse( MCLauncherAuthManager.isRenewalDue( oneDayAhead, NOW, FOUR_HOURS_MS ),
                     "a future timestamp must not trigger a renewal storm" );
    }

    // =========================================================================
    //  Interval independence
    // =========================================================================

    @Test
    void honoursTheSuppliedIntervalRatherThanAHardCodedOne()
    {
        long tenMinutes = 10L * 60L * 1000L;
        assertTrue( MCLauncherAuthManager.isRenewalDue( NOW - tenMinutes, NOW, tenMinutes ) );
        assertFalse( MCLauncherAuthManager.isRenewalDue( NOW - tenMinutes, NOW, FOUR_HOURS_MS ) );
    }
}

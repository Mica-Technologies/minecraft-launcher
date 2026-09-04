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

import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link AuthUtilities#checkAuthResponse(MCLauncherAuthResult)} — the
 * single choke point every login flow (interactive Microsoft OAuth, cached
 * silent renewal) funnels through to decide whether authentication actually
 * succeeded. A wrong verdict here in either direction is user-visible: "false
 * success" would let the launcher proceed to a game launch with no valid
 * session, and "false failure" would block a legitimately authenticated user.
 *
 * <p><b>Correctness note (not a bug, but worth pinning):</b> {@code MCLauncherAuthResult}
 * is a record, and {@code checkAuthResponse} matches its five known error
 * constants by reference ({@code ==}), not {@code equals()}. A freshly
 * constructed {@code MCLauncherAuthResult} carrying a {@code null} user — same
 * field value as {@code ERROR_BAD_USERNAME_PASSWORD} — does <em>not</em> match
 * any error branch and is reported as success, because record equality was
 * never invoked; only the specific singleton instances declared on
 * {@code MCLauncherAuthResult} are recognized as errors. This only matters if
 * some future caller ever constructs a result with a {@code null} user without
 * going through one of the five constants — today, every real error path
 * returns one of those exact singletons, so the risk is latent rather than
 * live. Pinned below so a refactor that changes the comparison strategy (e.g.
 * to {@code equals()}) is a deliberate, visible decision rather than an
 * accidental behavior change.
 *
 * @since 3.0
 */
class AuthUtilitiesTest
{
    @Test
    void badUsernamePasswordIsNotSuccess()
    {
        assertFalse( AuthUtilities.checkAuthResponse( MCLauncherAuthResult.ERROR_BAD_USERNAME_PASSWORD ) );
    }

    @Test
    void loginExpiredIsNotSuccess()
    {
        assertFalse( AuthUtilities.checkAuthResponse( MCLauncherAuthResult.ERROR_LOGIN_EXPIRED ) );
    }

    @Test
    void noValueIsNotSuccess()
    {
        assertFalse( AuthUtilities.checkAuthResponse( MCLauncherAuthResult.ERROR_NO_VAL ) );
    }

    @Test
    void otherErrorIsNotSuccess()
    {
        assertFalse( AuthUtilities.checkAuthResponse( MCLauncherAuthResult.ERROR_OTHER ) );
    }

    @Test
    void notOwnedIsNotSuccess()
    {
        assertFalse( AuthUtilities.checkAuthResponse( MCLauncherAuthResult.ERROR_NOT_OWNED ) );
    }

    @Test
    void resultWithRealUserIsSuccess()
    {
        assertTrue( AuthUtilities.checkAuthResponse( new MCLauncherAuthResult( null ) ),
                    "any MCLauncherAuthResult other than the five error singletons is treated as success" );
    }

    @Test
    void freshInstanceWithNullUserIsTreatedAsSuccessDespiteMatchingAnErrorConstantsData()
    {
        // Pins the reference-equality quirk documented on the class: this instance's
        // only field (minecraftUser) is null, identical to ERROR_BAD_USERNAME_PASSWORD's,
        // but checkAuthResponse compares by identity, not by record equals().
        MCLauncherAuthResult freshNullUserResult = new MCLauncherAuthResult( null );
        assertTrue( AuthUtilities.checkAuthResponse( freshNullUserResult ) );
    }
}

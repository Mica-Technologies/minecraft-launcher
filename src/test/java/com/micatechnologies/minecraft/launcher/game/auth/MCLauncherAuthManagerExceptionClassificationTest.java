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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MCLauncherAuthManager}'s exception-message classifiers
 * ({@code checkIfExceptionIsNoValuePresent}, {@code checkIfExceptionIsNotBought},
 * {@code checkIfExceptionIsInvalidCredentials}) and the
 * {@code processAuthException} dispatcher that uses them to pick a
 * {@link MCLauncherAuthResult} sentinel.
 *
 * <p>These classifiers are the only thing standing between "the user's
 * Microsoft account doesn't own Minecraft" and "the user typed the wrong
 * password" and "something unrelated broke" — three completely different
 * messages and remediation paths in the sign-in UI, all triggered off a
 * substring match against whatever exception message the
 * {@code minecraft_authenticator} library happened to throw. Get the
 * substring wrong (case sensitivity, a version bump that reflows the
 * library's wording) and a user who genuinely never bought the game sees
 * "wrong password" instead — actionable-looking but wrong advice for their
 * actual problem. None of this needs the real auth library: the classifiers
 * only ever look at {@link Exception#getMessage()}, so plain
 * {@code new Exception("...")} instances exercise the exact same code path
 * as a real one would.</p>
 *
 * <p>{@code processAuthException} additionally decides a priority order
 * between the three signatures (not-bought is checked first, then
 * invalid-credentials, then no-value-present) for the rare case a message
 * could match more than one pattern; the ordering test below pins that
 * priority so a future reordering is a deliberate choice, not an accident.</p>
 */
class MCLauncherAuthManagerExceptionClassificationTest
{
    // ===================================================================
    // checkIfExceptionIsNotBought
    // ===================================================================

    @Test
    void notBoughtMatchesExactSubstring()
    {
        assertTrue( MCLauncherAuthManager.checkIfExceptionIsNotBought(
                new Exception( "This account does not have bought minecraft" ) ) );
    }

    @Test
    void notBoughtMatchIsCaseInsensitive()
    {
        assertTrue( MCLauncherAuthManager.checkIfExceptionIsNotBought(
                new Exception( "ACCOUNT DOES NOT HAVE BOUGHT THE GAME" ) ) );
    }

    @Test
    void notBoughtDoesNotMatchUnrelatedMessage()
    {
        assertFalse( MCLauncherAuthManager.checkIfExceptionIsNotBought(
                new Exception( "connection timed out" ) ) );
    }

    @Test
    void notBoughtWithNullMessageReturnsFalseRatherThanThrowing()
    {
        assertFalse( MCLauncherAuthManager.checkIfExceptionIsNotBought( new Exception( (String) null ) ) );
    }

    // ===================================================================
    // checkIfExceptionIsInvalidCredentials
    // ===================================================================

    @Test
    void invalidCredentialsMatchesExactSubstring()
    {
        assertTrue( MCLauncherAuthManager.checkIfExceptionIsInvalidCredentials(
                new Exception( "invalid credentials supplied" ) ) );
    }

    @Test
    void invalidCredentialsMatchIsCaseInsensitive()
    {
        assertTrue( MCLauncherAuthManager.checkIfExceptionIsInvalidCredentials(
                new Exception( "Invalid Credentials" ) ) );
    }

    @Test
    void invalidCredentialsDoesNotMatchUnrelatedMessage()
    {
        assertFalse( MCLauncherAuthManager.checkIfExceptionIsInvalidCredentials(
                new Exception( "no value present" ) ) );
    }

    @Test
    void invalidCredentialsWithNullMessageReturnsFalseRatherThanThrowing()
    {
        assertFalse( MCLauncherAuthManager.checkIfExceptionIsInvalidCredentials( new Exception( (String) null ) ) );
    }

    // ===================================================================
    // checkIfExceptionIsNoValuePresent
    // ===================================================================

    @Test
    void noValuePresentMatchesExactSubstring()
    {
        assertTrue( MCLauncherAuthManager.checkIfExceptionIsNoValuePresent(
                new Exception( "No value present" ) ) );
    }

    @Test
    void noValuePresentDoesNotMatchUnrelatedMessage()
    {
        assertFalse( MCLauncherAuthManager.checkIfExceptionIsNoValuePresent(
                new Exception( "invalid credentials" ) ) );
    }

    @Test
    void noValuePresentWithNullMessageReturnsFalseRatherThanThrowing()
    {
        assertFalse( MCLauncherAuthManager.checkIfExceptionIsNoValuePresent( new Exception( (String) null ) ) );
    }

    // ===================================================================
    // processAuthException — the dispatcher
    // ===================================================================

    @Test
    void processAuthExceptionMapsNotBoughtToNotOwnedResult()
    {
        assertSame( MCLauncherAuthResult.ERROR_NOT_OWNED,
                MCLauncherAuthManager.processAuthException( new Exception( "account does not have bought the game" ) ) );
    }

    @Test
    void processAuthExceptionMapsInvalidCredentialsToBadUsernamePasswordResult()
    {
        assertSame( MCLauncherAuthResult.ERROR_BAD_USERNAME_PASSWORD,
                MCLauncherAuthManager.processAuthException( new Exception( "invalid credentials" ) ) );
    }

    @Test
    void processAuthExceptionMapsNoValuePresentToNoValResult()
    {
        assertSame( MCLauncherAuthResult.ERROR_NO_VAL,
                MCLauncherAuthManager.processAuthException( new Exception( "no value present" ) ) );
    }

    @Test
    void processAuthExceptionMapsUnrecognizedMessageToOtherResult()
    {
        assertSame( MCLauncherAuthResult.ERROR_OTHER,
                MCLauncherAuthManager.processAuthException( new Exception( "the server closed the connection" ) ) );
    }

    @Test
    void processAuthExceptionMapsNullMessageToOtherResult()
    {
        assertSame( MCLauncherAuthResult.ERROR_OTHER,
                MCLauncherAuthManager.processAuthException( new Exception( (String) null ) ) );
    }

    /**
     * Pins the checked-first priority: a message matching both the
     * not-bought and invalid-credentials signatures resolves to
     * not-owned, since {@code processAuthException} checks
     * {@code checkIfExceptionIsNotBought} before the other two. A future
     * reordering of those checks would silently flip which error the user
     * sees for an ambiguous message.
     */
    @Test
    void processAuthExceptionPrefersNotBoughtOverInvalidCredentialsWhenBothMatch()
    {
        assertSame( MCLauncherAuthResult.ERROR_NOT_OWNED,
                MCLauncherAuthManager.processAuthException(
                        new Exception( "invalid credentials: account does not have bought the game" ) ) );
    }
}

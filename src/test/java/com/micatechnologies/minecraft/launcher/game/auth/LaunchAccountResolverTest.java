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

import com.micatechnologies.minecraft.launcher.game.auth.AccountManager.AccountInfo;
import com.micatechnologies.minecraft.launcher.game.auth.AccountManager.Status;
import com.micatechnologies.minecraft.launcher.game.auth.LaunchAccountResolver.Problem;
import com.micatechnologies.minecraft.launcher.game.auth.LaunchAccountResolver.Resolution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LaunchAccountResolver}: which account a launch uses, and when a launch is
 * blocked instead of silently playing on the wrong account.
 */
class LaunchAccountResolverTest
{
    private static final AccountInfo DEFAULT = new AccountInfo( "a", "Alex", Status.READY, true, true, 0 );
    private static final AccountInfo OTHER   = new AccountInfo( "b", "Blake", Status.READY, false, true, 0 );
    private static final AccountInfo EXPIRED = new AccountInfo( "c", "Casey", Status.NEEDS_SIGN_IN, false, true, 0 );

    @Test
    void noOverrideUsesTheDefault()
    {
        Resolution r = LaunchAccountResolver.resolve( null, List.of( OTHER, DEFAULT ) );
        assertTrue( r.ok() );
        assertEquals( "a", r.uuid() );
        assertFalse( r.fromOverride() );
    }

    @Test
    void aBlankOverrideIsNoOverride()
    {
        assertEquals( "a", LaunchAccountResolver.resolve( " ", List.of( DEFAULT ) ).uuid() );
    }

    @Test
    void anOverrideWinsOverTheDefault()
    {
        Resolution r = LaunchAccountResolver.resolve( "b", List.of( DEFAULT, OTHER ) );
        assertEquals( "b", r.uuid() );
        assertEquals( "Blake", r.accountName() );
        assertTrue( r.fromOverride() );
    }

    @Test
    void anOverrideForAnAccountThatIsGoneBlocksInsteadOfFallingBack()
    {
        Resolution r = LaunchAccountResolver.resolve( "z", List.of( DEFAULT, OTHER ) );
        assertFalse( r.ok() );
        assertNull( r.uuid(), "must not quietly play as the default" );
        assertEquals( Problem.OVERRIDE_MISSING, r.problem() );
    }

    @Test
    void anOverrideAccountThatNeedsSigningInBlocks()
    {
        Resolution r = LaunchAccountResolver.resolve( "c", List.of( DEFAULT, EXPIRED ) );
        assertEquals( Problem.NEEDS_SIGN_IN, r.problem() );
        assertEquals( "Casey", r.accountName() );
        assertTrue( r.fromOverride() );
    }

    @Test
    void aDefaultThatNeedsSigningInBlocks()
    {
        AccountInfo expiredDefault = new AccountInfo( "a", "Alex", Status.NEEDS_SIGN_IN, true, true, 0 );
        assertEquals( Problem.NEEDS_SIGN_IN, LaunchAccountResolver.resolve( null, List.of( expiredDefault ) ).problem() );
    }

    @Test
    void aRefreshingAccountCanStillLaunch()
    {
        AccountInfo refreshing = new AccountInfo( "a", "Alex", Status.REFRESHING, true, true, 0 );
        assertEquals( "a", LaunchAccountResolver.resolve( null, List.of( refreshing ) ).uuid() );
    }

    @Test
    void noAccountsAtAll()
    {
        assertEquals( Problem.NO_ACCOUNT, LaunchAccountResolver.resolve( null, List.of() ).problem() );
        assertEquals( Problem.NO_ACCOUNT, LaunchAccountResolver.resolve( null, List.of( OTHER ) ).problem(),
                      "accounts but no default" );
    }
}

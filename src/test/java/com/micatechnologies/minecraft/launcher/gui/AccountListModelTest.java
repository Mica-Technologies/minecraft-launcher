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


package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.game.auth.AccountManager;
import com.micatechnologies.minecraft.launcher.game.auth.AccountManager.AccountInfo;
import com.micatechnologies.minecraft.launcher.game.auth.AccountManager.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AccountListModel}: row order and which actions each account offers in the
 * header switcher and Settings → Account.
 */
class AccountListModelTest
{
    private static AccountInfo info( String uuid, String name, Status status, boolean isDefault, boolean remembered )
    {
        return new AccountInfo( uuid, name, status, isDefault, remembered, 0L );
    }

    @Test
    void theDefaultAccountComesFirstAndTheRestKeepTheirOrder()
    {
        List< AccountListModel.Row > rows = AccountListModel.rows( List.of(
                info( "b", "Bee", Status.READY, false, true ),
                info( "a", "Aye", Status.READY, true, true ),
                info( "c", "Cee", Status.READY, false, true ) ) );

        assertEquals( List.of( "a", "b", "c" ), rows.stream().map( AccountListModel.Row::uuid ).toList() );
    }

    @Test
    void theDefaultCannotBeMadeDefaultAgain()
    {
        AccountListModel.Row row = AccountListModel.rows( List.of( info( "a", "Aye", Status.READY, true, true ) ) ).get( 0 );
        assertTrue( row.isDefault() );
        assertFalse( row.canMakeDefault() );
        assertEquals( "account.status.default", row.statusKey() );
    }

    @Test
    void anotherReadyAccountCanBecomeTheDefault()
    {
        AccountListModel.Row row = AccountListModel.rows( List.of( info( "b", "Bee", Status.READY, false, true ) ) ).get( 0 );
        assertTrue( row.canMakeDefault() );
        assertFalse( row.needsSignIn() );
        assertEquals( "account.status.signedIn", row.statusKey() );
    }

    @Test
    void anAccountWithRejectedCredentialsOffersSignInAgainInstead()
    {
        AccountListModel.Row row = AccountListModel.rows( List.of( info( "b", "Bee", Status.NEEDS_SIGN_IN, false, true ) ) )
                                                   .get( 0 );
        assertTrue( row.needsSignIn() );
        assertFalse( row.canMakeDefault() );
        assertEquals( "account.status.needsSignIn", row.statusKey() );
    }

    @Test
    void aRefreshingAccountSaysSo()
    {
        assertEquals( "account.status.refreshing",
                      AccountListModel.rows( List.of( info( "a", "Aye", Status.REFRESHING, true, true ) ) )
                                      .get( 0 ).statusKey() );
    }

    @Test
    void sessionOnlyAccountsAreMarked()
    {
        assertTrue( AccountListModel.rows( List.of( info( "a", "Aye", Status.READY, true, false ) ) )
                                    .get( 0 ).sessionOnly() );
    }

    @Test
    void anUnknownNameFallsBackToTheUuid()
    {
        assertEquals( "abc", AccountListModel.rows( List.of( info( "abc", "", Status.READY, false, true ) ) )
                                             .get( 0 ).name() );
    }

    @Test
    void noAccountsMeansNoRows()
    {
        assertTrue( AccountListModel.rows( List.< AccountManager.AccountInfo >of() ).isEmpty() );
    }
}

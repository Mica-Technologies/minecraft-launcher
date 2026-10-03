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

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the signed-in accounts into the rows the header switcher and Settings → Account
 * show, and decides which actions each row offers. Pure, so it is tested without JavaFX.
 *
 * @since 2026.10
 */
final class AccountListModel
{
    /**
     * One account as a list row.
     *
     * @param uuid           the account's profile id, for avatars and actions
     * @param name           the display name, falling back to the uuid when unknown
     * @param statusKey      localization key of the status line
     * @param isDefault      whether launches use this account by default
     * @param canMakeDefault whether "Make default" applies
     * @param needsSignIn    whether the row offers "Sign in again" instead of switching
     * @param sessionOnly    whether the account is forgotten when the launcher quits
     *
     * @since 2026.10
     */
    record Row( String uuid, String name, String statusKey, boolean isDefault, boolean canMakeDefault,
                boolean needsSignIn, boolean sessionOnly ) { }

    private AccountListModel() { }

    /**
     * Builds the rows: the default account first, then the rest in the order given (most
     * recently used first, as {@link AccountManager#accounts()} returns them).
     *
     * @param accounts the signed-in accounts
     *
     * @return the rows; never {@code null}
     *
     * @since 2026.10
     */
    static List< Row > rows( List< AccountManager.AccountInfo > accounts )
    {
        List< Row > defaults = new ArrayList<>();
        List< Row > others = new ArrayList<>();
        for ( AccountManager.AccountInfo a : accounts ) {
            boolean needsSignIn = a.status() == AccountManager.Status.NEEDS_SIGN_IN;
            String statusKey = needsSignIn ? "account.status.needsSignIn"
                               : a.status() == AccountManager.Status.REFRESHING ? "account.status.refreshing"
                               : a.isDefault() ? "account.status.default"
                               : "account.status.signedIn";
            String name = a.displayName() == null || a.displayName().isBlank() ? a.uuid() : a.displayName();
            Row row = new Row( a.uuid(), name, statusKey, a.isDefault(), !a.isDefault() && !needsSignIn,
                               needsSignIn, !a.remembered() );
            ( a.isDefault() ? defaults : others ).add( row );
        }
        defaults.addAll( others );
        return defaults;
    }
}

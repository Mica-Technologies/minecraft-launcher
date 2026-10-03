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

import java.util.List;

/**
 * Decides which account a game launches with. Pure, so every case is unit-tested.
 *
 * <p>A pack with an account override launches with that account; every other pack uses the
 * default. When the overriding account is no longer signed in, the launch is <em>blocked</em>
 * with {@link Problem#OVERRIDE_MISSING} rather than quietly falling back to the default:
 * playing on the wrong account (wrong worlds on a server, wrong skin, wrong friends) is worse
 * than one extra click.</p>
 *
 * @since 2026.10
 */
public final class LaunchAccountResolver
{
    /**
     * Why a launch can't go ahead with an account.
     *
     * @since 2026.10
     */
    public enum Problem
    {
        /** No account is signed in at all. */
        NO_ACCOUNT,
        /** The pack's override names an account that is no longer signed in. */
        OVERRIDE_MISSING,
        /** The chosen account's saved sign-in was rejected; it has to sign in again. */
        NEEDS_SIGN_IN
    }

    /**
     * The outcome: an account to launch with, or the problem that blocks the launch.
     *
     * @param uuid        the account to launch with, or {@code null} when blocked
     * @param problem     why the launch is blocked, or {@code null} when it isn't
     * @param accountName the display name of the account concerned, when known (for messages)
     * @param fromOverride whether the account (or the problem) comes from the pack's override
     *
     * @since 2026.10
     */
    public record Resolution( String uuid, Problem problem, String accountName, boolean fromOverride )
    {
        /**
         * @return whether the launch can go ahead
         *
         * @since 2026.10
         */
        public boolean ok()
        {
            return problem == null;
        }
    }

    /**
     * Thrown when a launch is blocked by its account; carries the {@link Resolution} so the
     * caller can tell the user what to do.
     *
     * @since 2026.10
     */
    public static final class BlockedException extends Exception
    {
        private final transient Resolution resolution;

        /**
         * @param resolution the blocking resolution
         *
         * @since 2026.10
         */
        public BlockedException( Resolution resolution )
        {
            super( "launch blocked: " + resolution.problem() );
            this.resolution = resolution;
        }

        /**
         * @return why the launch is blocked
         *
         * @since 2026.10
         */
        public Resolution resolution()
        {
            return resolution;
        }
    }

    private LaunchAccountResolver() { }

    /**
     * Resolves the account for one launch.
     *
     * @param overrideUuid the pack's account override, or {@code null}/blank for none
     * @param accounts     the signed-in accounts (one of which may be the default)
     *
     * @return the account to use, or the problem
     *
     * @since 2026.10
     */
    public static Resolution resolve( String overrideUuid, List< AccountManager.AccountInfo > accounts )
    {
        boolean hasOverride = overrideUuid != null && !overrideUuid.isBlank();
        AccountManager.AccountInfo chosen = null;
        for ( AccountManager.AccountInfo a : accounts ) {
            if ( hasOverride ? a.uuid().equals( overrideUuid ) : a.isDefault() ) {
                chosen = a;
                break;
            }
        }
        if ( chosen == null ) {
            return hasOverride
                   ? new Resolution( null, Problem.OVERRIDE_MISSING, null, true )
                   : new Resolution( null, Problem.NO_ACCOUNT, null, false );
        }
        if ( chosen.status() == AccountManager.Status.NEEDS_SIGN_IN ) {
            return new Resolution( null, Problem.NEEDS_SIGN_IN, chosen.displayName(), hasOverride );
        }
        return new Resolution( chosen.uuid(), null, chosen.displayName(), hasOverride );
    }
}

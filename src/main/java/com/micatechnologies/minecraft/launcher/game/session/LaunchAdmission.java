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


package com.micatechnologies.minecraft.launcher.game.session;

import java.util.Collection;

/**
 * Whether a new launch may start alongside the games already active. Pure, for testing.
 *
 * <p>The rules (decision D1 of the multi-account plan): at most one active game per pack,
 * because a pack has a single install folder (saves, {@code options.txt}, mods, natives) and a
 * running game locks its jars on Windows; and at most one per account, because Minecraft signs
 * the older session out when an account logs in twice.</p>
 *
 * @since 2026.10
 */
public final class LaunchAdmission
{
    /**
     * The verdict.
     *
     * @since 2026.10
     */
    public enum Outcome
    {
        /** Go ahead. */
        OK,
        /** This pack is already launching or running. */
        PACK_ALREADY_RUNNING,
        /** This account is already playing another pack. */
        ACCOUNT_BUSY,
        /** Another game is active and the UI can only follow one at a time. */
        ANOTHER_GAME_RUNNING
    }

    /**
     * A verdict and the session it conflicts with, for the message.
     *
     * @param outcome     the verdict
     * @param conflicting the active session that blocks this launch, or {@code null}
     *
     * @since 2026.10
     */
    public record Decision( Outcome outcome, GameSession conflicting )
    {
        /**
         * @return whether the launch may start
         *
         * @since 2026.10
         */
        public boolean ok()
        {
            return outcome == Outcome.OK;
        }
    }

    private LaunchAdmission() { }

    /**
     * Checks a new launch against the active sessions.
     *
     * @param candidate the launch about to start
     * @param others    the other sessions; inactive ones are ignored
     * @param oneAtATime whether only one game may be active at all (while the UI can only show
     *                   one game's progress and console)
     *
     * @return the verdict
     *
     * @since 2026.10
     */
    public static Decision check( GameSession candidate, Collection< GameSession > others, boolean oneAtATime )
    {
        for ( GameSession s : others ) {
            if ( s == candidate || !s.phase().isActive() ) {
                continue;
            }
            if ( s.packKey() != null && s.packKey().equals( candidate.packKey() ) ) {
                return new Decision( Outcome.PACK_ALREADY_RUNNING, s );
            }
            if ( s.accountUuid() != null && s.accountUuid().equals( candidate.accountUuid() ) ) {
                return new Decision( Outcome.ACCOUNT_BUSY, s );
            }
        }
        if ( oneAtATime ) {
            for ( GameSession s : others ) {
                if ( s != candidate && s.phase().isActive() ) {
                    return new Decision( Outcome.ANOTHER_GAME_RUNNING, s );
                }
            }
        }
        return new Decision( Outcome.OK, null );
    }
}

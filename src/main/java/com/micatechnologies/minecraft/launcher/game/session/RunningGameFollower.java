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

import java.util.List;

/**
 * Keeps the launcher's "what are you playing" surfaces (keyboard RGB, Discord presence) on the
 * games that are actually running.
 *
 * <p>Those surfaces show one game. With several running, they follow the most recently started
 * one, and only fall back to the menu look and presence when the <em>last</em> game exits.
 * Before, every launch set them and every exit cleared them, so the first game to close wiped
 * the effect and presence of games still being played.</p>
 *
 * @since 2026.10
 */
public final class RunningGameFollower
{
    /**
     * Where the followed game is shown.
     *
     * @since 2026.10
     */
    public interface Sink
    {
        /**
         * Show this game.
         *
         * @param session the running game to show
         */
        void showGame( GameSession session );

        /** No game is running any more: show the menu state. */
        void showNoGame();
    }

    private final Sink           sink;
    private       GameSession    shown;

    /**
     * @param sink where to show the followed game
     *
     * @since 2026.10
     */
    public RunningGameFollower( Sink sink )
    {
        this.sink = sink;
    }

    /**
     * Picks the game to show: the running one that started last. Pure, for testing.
     *
     * @param sessions all sessions
     *
     * @return the game to show, or {@code null} when none is running
     *
     * @since 2026.10
     */
    static GameSession pick( List< GameSession > sessions )
    {
        GameSession best = null;
        for ( GameSession s : sessions ) {
            if ( s.phase() == GameSession.Phase.RUNNING && ( best == null || s.startedMs() >= best.startedMs() ) ) {
                best = s;
            }
        }
        return best;
    }

    /**
     * Re-evaluates after a change and updates the sink only when the followed game changed.
     *
     * @param sessions all sessions
     *
     * @since 2026.10
     */
    public synchronized void update( List< GameSession > sessions )
    {
        GameSession next = pick( sessions );
        if ( next == shown ) {
            return;
        }
        GameSession previous = shown;
        shown = next;
        if ( next != null ) {
            sink.showGame( next );
        }
        else if ( previous != null ) {
            sink.showNoGame();
        }
    }

    /**
     * Shows the followed game again even though it has not changed. Used after an in-process
     * launcher restart, which resets Discord presence and the keyboard while the game keeps
     * running. Does nothing when no game is running.
     *
     * @param sessions all sessions
     *
     * @since 2026.10
     */
    public synchronized void resync( List< GameSession > sessions )
    {
        shown = null;
        update( sessions );
    }
}

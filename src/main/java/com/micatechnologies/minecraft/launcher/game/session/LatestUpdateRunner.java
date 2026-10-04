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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Applies a stream of "show this state" updates in the order they were made, dropping any that a
 * newer update has already replaced.
 *
 * <p>Each update describes the whole state to show (a game, or no game), so only the newest one
 * matters. Updates run one at a time on a single thread, in submission order; an update that
 * finds a newer one queued behind it skips itself. Running them on an unordered pool instead let
 * a slow "show the game" land after a quick "no game", leaving the keyboard and Discord on a game
 * that had already exited.</p>
 *
 * @since 2026.10
 */
public final class LatestUpdateRunner
{
    private final Executor   executor;
    private final AtomicLong generation = new AtomicLong();

    /**
     * Creates a runner on an executor. The executor must run tasks one at a time, in order.
     *
     * @param executor a single-threaded, ordered executor
     *
     * @since 2026.10
     */
    public LatestUpdateRunner( Executor executor )
    {
        this.executor = executor;
    }

    /**
     * Creates a runner on its own daemon thread.
     *
     * @param threadName the thread's name
     *
     * @return the runner
     *
     * @since 2026.10
     */
    public static LatestUpdateRunner onDaemonThread( String threadName )
    {
        return new LatestUpdateRunner( Executors.newSingleThreadExecutor( r -> {
            Thread t = new Thread( r, threadName );
            t.setDaemon( true );
            return t;
        } ) );
    }

    /**
     * Queues an update. It runs after every earlier one, unless a later update has been submitted
     * by the time its turn comes.
     *
     * @param update the update
     *
     * @since 2026.10
     */
    public void submit( Runnable update )
    {
        long mine = generation.incrementAndGet();
        executor.execute( () -> {
            if ( generation.get() == mine ) {
                update.run();
            }
        } );
    }
}

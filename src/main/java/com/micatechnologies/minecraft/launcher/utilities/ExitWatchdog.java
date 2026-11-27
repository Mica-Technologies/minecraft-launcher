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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes sure that asking the launcher to quit ends the process. Once armed, a daemon thread waits
 * {@link #EXIT_DEADLINE_MS}; if the process is still alive then (a cleanup step or a shutdown
 * hook hung), it writes every thread's stack to {@code logs/exit-hang-<time>.log} and halts the
 * JVM.
 *
 * <p>A launcher that closes its windows but never exits is invisible on a desktop. Started from
 * Steam in the Steam Deck's Game Mode, it leaves a black screen: Steam counts the game as running
 * until the process ends.
 *
 * @since 2026.11
 */
public final class ExitWatchdog
{
    /** How long a quit may take before the process is halted. Cleanup's own bounded steps add up
     *  to well under this. */
    static final long EXIT_DEADLINE_MS = 20_000;

    private static final AtomicBoolean ARMED = new AtomicBoolean();

    private ExitWatchdog() { /* static-only */ }

    /**
     * Starts the countdown to a forced exit. Only the first call counts.
     *
     * @param status the exit status to halt with
     *
     * @since 2026.11
     */
    public static void arm( int status )
    {
        if ( !ARMED.compareAndSet( false, true ) ) {
            return;
        }
        Thread thread = new Thread( () -> {
            try {
                Thread.sleep( EXIT_DEADLINE_MS );
            }
            catch ( InterruptedException e ) {
                return;
            }
            FxWatchdog.writeDumpFile( "exit-hang-",
                                      "Launcher still running " + EXIT_DEADLINE_MS + " ms after it was asked to quit",
                                      "The launcher had not exited " + EXIT_DEADLINE_MS
                                      + " ms after it was asked to quit, so it was halted" );
            Runtime.getRuntime().halt( status );
        }, "Exit watchdog" );
        thread.setDaemon( true );
        thread.start();
    }
}

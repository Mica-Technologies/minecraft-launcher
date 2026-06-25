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

package com.micatechnologies.minecraft.launcher.tui;

import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A modpack game launched from the TUI: wraps the child {@link Process}, captures its piped
 * stdout/stderr into a bounded log ring buffer for the Logs view, and watches for exit (recording
 * the play session + invoking an on-exit callback). The TUI calls {@code GameModPack.startGame()}
 * directly — bypassing {@code LauncherCore.play()} — so launch-start / session-end recording is done
 * here rather than relying on the GUI play path.
 *
 * @since 2026.6
 */
public final class RunningGame
{
    /**
     * Cap on buffered log lines per game — keeps a long session from growing unbounded.
     *
     * @since 2026.6
     */
    private static final int MAX_LOG_LINES = 4000;

    /**
     * The modpack whose game process this instance wraps.
     *
     * @since 2026.6
     */
    private final GameModPack pack;

    /**
     * The launched child game process being monitored.
     *
     * @since 2026.6
     */
    private final Process     process;

    /**
     * Wall-clock timestamp (milliseconds since the epoch) captured at construction, used to compute
     * the elapsed play time and the recorded session duration.
     *
     * @since 2026.6
     */
    private final long        startMs;

    /**
     * Bounded log ring buffer holding the most recent captured stdout/stderr lines (oldest first).
     * Both reader threads (stdout + stderr) append under {@link #logLock}, and the GUI thread copies
     * it for display. An {@link ArrayDeque} (rather than {@code ConcurrentLinkedDeque}) is used so
     * {@code size()} is O(1) — a fast-spewing game log would otherwise pay an O(n) {@code size()}
     * traversal on every captured line.
     *
     * @since 2026.6
     */
    private final ArrayDeque< String > log = new ArrayDeque<>();

    /**
     * Monitor guarding all access to {@link #log}, shared between the two reader threads and the
     * snapshotting GUI thread.
     *
     * @since 2026.6
     */
    private final Object               logLock = new Object();

    /**
     * Creates a wrapper around an already-launched game process and stamps the session start time.
     * Note that this does not begin monitoring; call {@link #start(Runnable)} to record the launch
     * and spin up the reader and exit-watcher threads.
     *
     * @param pack    the modpack whose game process is being wrapped
     * @param process the launched child game process to monitor
     *
     * @since 2026.6
     */
    RunningGame( GameModPack pack, Process process )
    {
        this.pack = pack;
        this.process = process;
        this.startMs = System.currentTimeMillis();
    }

    /**
     * Records the launch, starts the stdout/stderr reader threads, and starts the exit watcher. The
     * launch-start recording is best-effort and never blocks the launch on failure. When the process
     * ends, the play session duration is recorded and {@code onExit} (if non-{@code null}) is invoked
     * on the watcher thread — off the FX/GUI thread.
     *
     * @param onExit a callback run once the process ends, or {@code null} for none
     *
     * @since 2026.6
     */
    void start( Runnable onExit )
    {
        try {
            pack.recordLaunchStart();
        }
        catch ( Throwable ignored ) {
            // best-effort playtime tracking; never block the launch
        }
        startReader( process.getInputStream() );
        startReader( process.getErrorStream() );

        Thread watcher = new Thread( () -> {
            try {
                process.waitFor();
            }
            catch ( InterruptedException ignored ) {
                Thread.currentThread().interrupt();
            }
            try {
                pack.recordSessionEnd( System.currentTimeMillis() - startMs );
            }
            catch ( Throwable ignored ) {
            }
            if ( onExit != null ) {
                onExit.run();
            }
        }, "tui-game-watch-" + pack.getPackSanitizedName() );
        watcher.setDaemon( true );
        watcher.start();
    }

    /**
     * Spawns a daemon thread that reads {@code in} line-by-line and appends each line to the bounded
     * {@link #log} ring buffer, evicting the oldest lines once {@link #MAX_LOG_LINES} is exceeded. The
     * thread terminates normally when the stream closes on process exit.
     *
     * @param in the process output stream (stdout or stderr) to capture
     *
     * @since 2026.6
     */
    private void startReader( InputStream in )
    {
        Thread t = new Thread( () -> {
            try ( BufferedReader br = new BufferedReader( new InputStreamReader( in, StandardCharsets.UTF_8 ) ) ) {
                String line;
                while ( ( line = br.readLine() ) != null ) {
                    synchronized ( logLock ) {
                        log.addLast( line );
                        while ( log.size() > MAX_LOG_LINES ) {
                            log.pollFirst();
                        }
                    }
                }
            }
            catch ( IOException ignored ) {
                // stream closed on process exit — normal
            }
        }, "tui-game-log-" + pack.getPackSanitizedName() );
        t.setDaemon( true );
        t.start();
    }

    /**
     * Returns the modpack whose game process this instance wraps.
     *
     * @return the wrapped modpack
     *
     * @since 2026.6
     */
    public GameModPack pack()
    {
        return pack;
    }

    /**
     * Reports whether the wrapped game process is still running.
     *
     * @return {@code true} if the process has not yet terminated
     *
     * @since 2026.6
     */
    public boolean isAlive()
    {
        return process.isAlive();
    }

    /**
     * Requests that the game process terminate. Termination is asynchronous; the exit watcher
     * started by {@link #start(Runnable)} records the session and refreshes the UI once the process
     * actually ends.
     *
     * @since 2026.6
     */
    public void stop()
    {
        process.destroy();
    }

    /**
     * Returns the elapsed wall-clock play time since this instance was constructed.
     *
     * @return the elapsed time in milliseconds
     *
     * @since 2026.6
     */
    public long elapsedMs()
    {
        return System.currentTimeMillis() - startMs;
    }

    /**
     * Returns a point-in-time copy of the captured log lines (oldest first). The returned list is a
     * fresh snapshot taken under {@link #logLock}, so the caller may iterate it freely while the
     * reader threads continue capturing.
     *
     * @return a new list containing the currently buffered log lines, oldest first
     *
     * @since 2026.6
     */
    public List< String > logSnapshot()
    {
        synchronized ( logLock ) {
            return new ArrayList<>( log );
        }
    }
}

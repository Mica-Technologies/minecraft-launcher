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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

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
    /** Cap on buffered log lines per game — keeps a long session from growing unbounded. */
    private static final int MAX_LOG_LINES = 4000;

    private final GameModPack pack;
    private final Process     process;
    private final long        startMs;
    private final ConcurrentLinkedDeque< String > log = new ConcurrentLinkedDeque<>();

    RunningGame( GameModPack pack, Process process )
    {
        this.pack = pack;
        this.process = process;
        this.startMs = System.currentTimeMillis();
    }

    /** Records the launch, starts the stdout/stderr reader threads, and starts the exit watcher.
     *  {@code onExit} runs (off the FX/GUI thread) once the process ends. */
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

    private void startReader( InputStream in )
    {
        Thread t = new Thread( () -> {
            try ( BufferedReader br = new BufferedReader( new InputStreamReader( in, StandardCharsets.UTF_8 ) ) ) {
                String line;
                while ( ( line = br.readLine() ) != null ) {
                    log.addLast( line );
                    while ( log.size() > MAX_LOG_LINES ) {
                        log.pollFirst();
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

    public GameModPack pack()
    {
        return pack;
    }

    public boolean isAlive()
    {
        return process.isAlive();
    }

    /** Requests the game process terminate. The exit watcher records the session + refreshes the UI. */
    public void stop()
    {
        process.destroy();
    }

    public long elapsedMs()
    {
        return System.currentTimeMillis() - startMs;
    }

    /** A point-in-time copy of the captured log lines (oldest first). */
    public List< String > logSnapshot()
    {
        return new ArrayList<>( log );
    }
}

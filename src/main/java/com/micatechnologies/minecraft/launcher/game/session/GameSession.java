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

import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One game launch, from the moment the user asks for it until the game exits: which pack,
 * which account, where it is in its life, its process, and its cancellation.
 *
 * <p>Several sessions can be live at once, one per pack and one per account (see
 * {@link LaunchAdmission}). Each owns its own cancellation, so cancelling one launch can
 * never cancel another, which the old single global "current launch" did.</p>
 *
 * <p>Thread-safe. Listeners run on whichever thread changed the session.</p>
 *
 * @since 2026.10
 */
public final class GameSession
{
    /**
     * Where a session is in its life.
     *
     * @since 2026.10
     */
    public enum Phase
    {
        /** Downloading, verifying and patching before the game starts. */
        PREPARING,
        /** The game process is running. */
        RUNNING,
        /** The game exited normally. */
        EXITED,
        /** The game exited with a non-zero code. */
        CRASHED,
        /** The user cancelled the launch before the game started. */
        CANCELLED,
        /** The launch failed before the game started. */
        FAILED;

        /**
         * @return whether a session in this phase holds its pack and account
         *
         * @since 2026.10
         */
        public boolean isActive()
        {
            return this == PREPARING || this == RUNNING;
        }
    }

    private static final AtomicLong IDS = new AtomicLong();

    private final long                   id = IDS.incrementAndGet();
    private final GameModPack            pack;
    private final String                 packKey;
    private final String                 packName;
    private final String                 accountUuid;
    private final String                 accountName;
    private final LongSupplier           clock;
    private final AtomicBoolean          cancelled = new AtomicBoolean( false );
    private final List< Consumer< GameSession > > listeners = new CopyOnWriteArrayList<>();
    private volatile Thread              worker;
    private volatile Phase               phase = Phase.PREPARING;
    private volatile Process             process;
    private volatile long                startedMs;
    private volatile long                endedMs;
    private volatile int                 exitCode;

    /**
     * Creates a session in {@link Phase#PREPARING}.
     *
     * @param pack        the pack being launched; may be {@code null} in tests
     * @param packKey     a stable identity for the pack (see {@code GameModPack#getSettingsKey()})
     * @param packName    the pack's display name
     * @param accountUuid the account it launches as, or {@code null} (server mode)
     * @param accountName that account's display name, or {@code null}
     * @param clock       epoch-millis clock
     *
     * @since 2026.10
     */
    public GameSession( GameModPack pack, String packKey, String packName, String accountUuid, String accountName,
                        LongSupplier clock )
    {
        this.pack = pack;
        this.packKey = packKey;
        this.packName = packName;
        this.accountUuid = accountUuid;
        this.accountName = accountName;
        this.clock = clock;
    }

    /** @return a process-unique id */
    public long id() { return id; }

    /** @return the pack, or {@code null} in tests */
    public GameModPack pack() { return pack; }

    /** @return the pack's stable identity */
    public String packKey() { return packKey; }

    /** @return the pack's display name */
    public String packName() { return packName; }

    /** @return the account uuid, or {@code null} for a server launch */
    public String accountUuid() { return accountUuid; }

    /** @return the account's display name, or {@code null} */
    public String accountName() { return accountName; }

    /** @return the current phase */
    public Phase phase() { return phase; }

    /** @return the game process once started, else {@code null} */
    public Process process() { return process; }

    /** @return the exit code once the game has exited */
    public int exitCode() { return exitCode; }

    /** @return when the game process started (epoch millis), or 0 before that */
    public long startedMs() { return startedMs; }

    /** @return when the session ended (epoch millis), or 0 while active */
    public long endedMs() { return endedMs; }

    /** @return whether {@link #cancel()} has been called */
    public boolean isCancelled() { return cancelled.get(); }

    /**
     * Binds the worker thread running this launch's preparation, so {@link #cancel()} can
     * interrupt its blocking downloads.
     *
     * @param worker the preparing thread
     *
     * @since 2026.10
     */
    public void bindWorker( Thread worker )
    {
        this.worker = worker;
    }

    /**
     * Cancels a launch that is still preparing: flags it and interrupts its worker. The
     * preparation notices at its next checkpoint. Has no effect once the game is running; use
     * {@link #stop(boolean)} for that. Idempotent.
     *
     * @since 2026.10
     */
    public void cancel()
    {
        if ( phase == Phase.PREPARING && cancelled.compareAndSet( false, true ) ) {
            Thread t = worker;
            if ( t != null ) {
                t.interrupt();
            }
        }
    }

    /**
     * Stops a running game.
     *
     * @param force {@code true} to kill it outright, {@code false} to ask it to exit
     *
     * @since 2026.10
     */
    public void stop( boolean force )
    {
        Process p = process;
        if ( p != null && p.isAlive() ) {
            if ( force ) {
                p.destroyForcibly();
            }
            else {
                p.destroy();
            }
        }
    }

    /**
     * Records the spawned game process and moves to {@link Phase#RUNNING}. When the process
     * exits the session moves to {@link Phase#EXITED} or {@link Phase#CRASHED} by itself.
     *
     * @param gameProcess the game process
     *
     * @since 2026.10
     */
    public void attachProcess( Process gameProcess )
    {
        if ( gameProcess == null || phase != Phase.PREPARING ) {
            return;
        }
        process = gameProcess;
        startedMs = clock.getAsLong();
        setPhase( Phase.RUNNING );
        gameProcess.onExit().thenAccept( p -> {
            int code;
            try {
                code = p.exitValue();
            }
            catch ( IllegalThreadStateException e ) {
                code = -1;
            }
            exitCode = code;
            endedMs = clock.getAsLong();
            setPhase( code == 0 ? Phase.EXITED : Phase.CRASHED );
        } );
    }

    /**
     * Ends a launch that never got its game running: {@link Phase#CANCELLED} when it was
     * cancelled, otherwise {@link Phase#FAILED}. No effect once running or already ended.
     *
     * @since 2026.10
     */
    public void endWithoutGame()
    {
        if ( phase == Phase.PREPARING ) {
            endedMs = clock.getAsLong();
            setPhase( cancelled.get() ? Phase.CANCELLED : Phase.FAILED );
        }
    }

    /**
     * Adds a listener for phase changes.
     *
     * @param listener receives this session after each change
     *
     * @since 2026.10
     */
    public void addListener( Consumer< GameSession > listener )
    {
        listeners.add( listener );
    }

    private void setPhase( Phase next )
    {
        phase = next;
        for ( Consumer< GameSession > l : listeners ) {
            try {
                l.accept( this );
            }
            catch ( RuntimeException ignored ) {
                // One failing listener must not keep the others from hearing about it.
            }
        }
    }
}

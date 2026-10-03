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

import com.micatechnologies.minecraft.launcher.gui.LogTrimPolicy;
import com.micatechnologies.minecraft.launcher.utilities.FilePermissions;
import com.micatechnologies.minecraft.launcher.utilities.SensitiveDataRedactor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything a game prints, captured whether or not anyone is looking.
 *
 * <p>Two reader threads drain the game's stdout and stderr; this has to happen for the whole
 * life of the game, because a pipe nobody reads fills within moments and the game then stalls
 * on its next log line. Every line is redacted (access tokens are on the JVM command line,
 * which some mods echo), kept in a bounded in-memory buffer for display, and written to the
 * session's log file. A flush thread hands lines to listeners in batches every
 * {@link #FLUSH_INTERVAL_MS} ms, so a chatty game doesn't flood the UI thread.</p>
 *
 * <p>This used to live inside the game console screen, so leaving that screen stopped the
 * capture. Owned by the {@link GameSession} now, it outlives any window.</p>
 *
 * @since 2026.10
 */
public final class GameLog
{
    /**
     * Receives captured lines.
     *
     * @since 2026.10
     */
    public interface Listener
    {
        /**
         * New lines, in order. Called on the flush thread.
         *
         * @param lines the redacted lines
         */
        void onLines( List< String > lines );

        /** The game's output has ended and the log file is closed. Called on the flush thread. */
        default void onClosed() { }
    }

    /** How often batched lines go to listeners and the log file is flushed. */
    static final long FLUSH_INTERVAL_MS = 150;

    /** A captured line and its position in the session. */
    private record Numbered( long index, String line ) { }

    /** What {@link #subscribe} returns: the text so far, after which the listener takes over. */
    public record Subscription( String snapshot, Runnable cancel ) { }

    private static final int TRIGGER_CHARS = 5_000_000;
    private static final int RETAIN_CHARS  = 4_000_000;

    private final Path                              file;
    private final int                               triggerChars;
    private final int                               retainChars;
    private final StringBuilder                     buffer  = new StringBuilder();
    private final ConcurrentLinkedQueue< Numbered > pending = new ConcurrentLinkedQueue<>();
    /** Lines appended so far; guarded by {@link #buffer}. Numbers lines for {@link #subscribe}. */
    private       long                              lineCount;
    private final List< Listener >                  listeners = new CopyOnWriteArrayList<>();
    private final Object                            writerLock = new Object();
    private final CountDownLatch                    closed = new CountDownLatch( 1 );
    private final AtomicInteger                     openReaders = new AtomicInteger();
    private       BufferedWriter                    writer;

    /**
     * Creates a log writing to the given file.
     *
     * @param file the session log file, or {@code null} to keep the log in memory only
     *
     * @since 2026.10
     */
    public GameLog( Path file )
    {
        this( file, TRIGGER_CHARS, RETAIN_CHARS );
    }

    /**
     * @param file         the session log file, or {@code null}
     * @param triggerChars buffer size that triggers dropping old text
     * @param retainChars  how much text to keep after dropping
     */
    GameLog( Path file, int triggerChars, int retainChars )
    {
        this.file = file;
        this.triggerChars = triggerChars;
        this.retainChars = retainChars;
    }

    /**
     * A new session log file name under {@code dir}: {@code game-<pack>-<timestamp>.log}, with
     * anything but letters, digits, dot, underscore and dash in the pack name replaced.
     * Pure, for testing.
     *
     * @param dir       the log folder
     * @param packName  the pack's display name
     * @param timestamp the session start, formatted
     *
     * @return the file path
     *
     * @since 2026.10
     */
    public static Path fileFor( Path dir, String packName, String timestamp )
    {
        String safe = ( packName == null ? "game" : packName ).replaceAll( "[^a-zA-Z0-9._-]", "_" );
        return dir.resolve( "game-" + safe + "-" + timestamp + ".log" );
    }

    /**
     * Starts capturing a process's output.
     *
     * @param process the game process; its output must be piped
     *
     * @since 2026.10
     */
    public void attach( Process process )
    {
        attach( process.getInputStream(), process.getErrorStream() );
    }

    /**
     * Starts capturing two output streams. Returns immediately; reading happens on daemon
     * threads until both streams end.
     *
     * @param out the standard output
     * @param err the standard error
     *
     * @since 2026.10
     */
    public void attach( InputStream out, InputStream err )
    {
        openWriter();
        openReaders.set( 2 );
        startDaemon( "game-log-out", () -> read( out ) );
        startDaemon( "game-log-err", () -> read( err ) );
        startDaemon( "game-log-flush", this::flushLoop );
    }

    /** @return the session log file, or {@code null} when kept in memory only */
    public Path file()
    {
        return file;
    }

    /**
     * @return the captured text still held in memory (the oldest lines of a very long session
     *         are only in the file)
     *
     * @since 2026.10
     */
    public String text()
    {
        synchronized ( buffer ) {
            return buffer.toString();
        }
    }

    /**
     * @return whether the output has ended and the file is closed
     *
     * @since 2026.10
     */
    public boolean isClosed()
    {
        return closed.getCount() == 0;
    }

    /**
     * Waits for the output to end.
     *
     * @param timeoutMs how long to wait
     *
     * @return whether it ended in time
     *
     * @throws InterruptedException if interrupted
     * @since 2026.10
     */
    public boolean awaitClosed( long timeoutMs ) throws InterruptedException
    {
        return closed.await( timeoutMs, TimeUnit.MILLISECONDS );
    }

    /**
     * @param listener receives batches of new lines
     *
     * @since 2026.10
     */
    public void addListener( Listener listener )
    {
        listeners.add( listener );
    }

    /**
     * @param listener a listener added with {@link #addListener}
     *
     * @since 2026.10
     */
    public void removeListener( Listener listener )
    {
        listeners.remove( listener );
    }

    /**
     * Subscribes a viewer that may arrive mid-game: returns the text captured so far, and from
     * then on passes the listener every later line, with none missed and none repeated.
     *
     * @param listener receives lines captured after the snapshot
     *
     * @return the snapshot, and how to unsubscribe
     *
     * @since 2026.10
     */
    public Subscription subscribe( Listener listener )
    {
        FromIndex wrapper;
        String snapshot;
        synchronized ( buffer ) {
            snapshot = buffer.toString();
            wrapper = new FromIndex( lineCount, listener );
            listeners.add( wrapper );
        }
        if ( isClosed() ) {
            listener.onClosed();
        }
        return new Subscription( snapshot, () -> listeners.remove( wrapper ) );
    }

    /** Passes on only lines numbered at or after the subscriber's snapshot. */
    private record FromIndex( long start, Listener target ) implements Listener
    {
        void deliver( List< Numbered > lines )
        {
            List< String > fresh = new ArrayList<>();
            for ( Numbered n : lines ) {
                if ( n.index() >= start ) {
                    fresh.add( n.line() );
                }
            }
            if ( !fresh.isEmpty() ) {
                target.onLines( fresh );
            }
        }

        @Override
        public void onLines( List< String > lines )
        {
            target.onLines( lines );
        }

        @Override
        public void onClosed()
        {
            target.onClosed();
        }
    }

    private void read( InputStream stream )
    {
        try ( BufferedReader reader = new BufferedReader( new InputStreamReader( stream, StandardCharsets.UTF_8 ) ) ) {
            String line;
            while ( ( line = reader.readLine() ) != null ) {
                String safe = SensitiveDataRedactor.redact( line );
                synchronized ( buffer ) {
                    buffer.append( safe ).append( '\n' );
                    int dropTo = LogTrimPolicy.fullLogDropOffset( buffer, triggerChars, retainChars );
                    if ( dropTo > 0 ) {
                        buffer.delete( 0, dropTo );
                    }
                    // Queued under the same lock as the append, so a subscriber's snapshot and
                    // the numbering agree on exactly which lines it already has.
                    pending.add( new Numbered( lineCount++, safe ) );
                }
                write( safe );
            }
        }
        catch ( IOException ignored ) {
            // The stream closes when the game exits.
        }
        finally {
            openReaders.decrementAndGet();
        }
    }

    private void flushLoop()
    {
        while ( openReaders.get() > 0 ) {
            try {
                Thread.sleep( FLUSH_INTERVAL_MS );
            }
            catch ( InterruptedException e ) {
                break;
            }
            deliver();
            flushWriter();
        }
        deliver();
        closeWriter();
        closed.countDown();
        for ( Listener l : listeners ) {
            try {
                l.onClosed();
            }
            catch ( RuntimeException ignored ) {
                // A failing listener must not stop the others.
            }
        }
    }

    private void deliver()
    {
        List< Numbered > batch = new ArrayList<>();
        Numbered line;
        while ( ( line = pending.poll() ) != null ) {
            batch.add( line );
        }
        if ( batch.isEmpty() ) {
            return;
        }
        List< Numbered > view = List.copyOf( batch );
        for ( Listener l : listeners ) {
            try {
                if ( l instanceof FromIndex from ) {
                    from.deliver( view );
                }
                else {
                    l.onLines( view.stream().map( Numbered::line ).toList() );
                }
            }
            catch ( RuntimeException ignored ) {
                // A failing listener must not stop the others.
            }
        }
    }

    private void openWriter()
    {
        if ( file == null ) {
            return;
        }
        try {
            Files.createDirectories( file.getParent() );
            synchronized ( writerLock ) {
                writer = Files.newBufferedWriter( file, StandardCharsets.UTF_8 );
            }
            // Game logs can carry user names and mod debug output; owner-only, best-effort.
            FilePermissions.applyOwnerOnly( file );
        }
        catch ( IOException e ) {
            synchronized ( writerLock ) {
                writer = null;
            }
        }
    }

    private void write( String line )
    {
        synchronized ( writerLock ) {
            if ( writer == null ) {
                return;
            }
            try {
                writer.write( line );
                writer.newLine();
            }
            catch ( IOException ignored ) {
                // A failed write must never back up into the game's pipe.
            }
        }
    }

    private void flushWriter()
    {
        synchronized ( writerLock ) {
            if ( writer != null ) {
                try {
                    writer.flush();
                }
                catch ( IOException ignored ) {
                    // Best effort; close() gets a final try.
                }
            }
        }
    }

    private void closeWriter()
    {
        synchronized ( writerLock ) {
            if ( writer != null ) {
                try {
                    writer.close();
                }
                catch ( IOException ignored ) {
                    // Nothing more to do.
                }
                writer = null;
            }
        }
    }

    private static void startDaemon( String name, Runnable body )
    {
        Thread t = new Thread( body, name );
        t.setDaemon( true );
        t.start();
    }
}

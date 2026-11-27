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

import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import javafx.application.Platform;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Notices when the JavaFX application thread stops answering, and writes a thread dump to the
 * log folder when it does.
 * <p>
 * A frozen window leaves no error in any log, and on a handheld or a machine without a terminal
 * there is no easy way to take a thread dump by hand. A daemon thread posts a tiny task to the FX
 * thread every {@link #PING_MS} ms; when none has run for {@link #STALL_MS} ms, it writes every
 * thread's stack (with the locks each holds and waits on, and any deadlock the JVM finds) to
 * {@code logs/ui-hang-<time>.log} and notes it in the startup log. One dump per stall: a stall
 * that clears and comes back gets another.
 * <p>
 * The single-instance hand-off also asks {@link #isUnresponsive()}, so a second launch can tell
 * the user the running launcher has stopped responding instead of handing its request to a
 * window that will never come forward.
 *
 * @author Mica Technologies
 * @since 2026.11
 */
public final class FxWatchdog
{
    /** How often the FX thread is pinged. */
    static final long PING_MS = 1_000;

    /** How long without an answer counts as a stall worth a thread dump. */
    static final long STALL_MS = 10_000;

    /** How long without an answer the hand-off treats the launcher as not responding. */
    static final long UNRESPONSIVE_MS = 5_000;

    /** Most dumps written in one session, so a launcher stuck for hours doesn't fill the disk. */
    static final int MAX_DUMPS = 5;

    /** How many watchdog ticks in a row have found the last ping still unanswered. Counted in
     *  ticks rather than measured with a clock, so a computer waking from sleep (the clock moved,
     *  nothing ran) doesn't read as a stall. */
    private static volatile int missedTicks = 0;

    /** Whether a ping is posted and hasn't run yet. */
    private static volatile boolean pingPending = false;

    /** The watchdog thread, once started. */
    private static Thread thread;

    private FxWatchdog() { /* static-only */ }

    /**
     * Starts watching the FX thread. Idempotent; call once the JavaFX toolkit is running.
     *
     * @since 2026.11
     */
    public static synchronized void start()
    {
        if ( thread != null ) {
            return;
        }
        thread = new Thread( FxWatchdog::run, "FX watchdog" );
        thread.setDaemon( true );
        thread.setPriority( Thread.MAX_PRIORITY );
        thread.start();
    }

    /**
     * Whether the FX thread has gone {@link #UNRESPONSIVE_MS} or more without answering. Always
     * {@code false} before {@link #start()}.
     *
     * @return {@code true} while the launcher's windows are frozen
     *
     * @since 2026.11
     */
    public static boolean isUnresponsive()
    {
        return stalledFor( missedTicks, UNRESPONSIVE_MS );
    }

    /**
     * Whether a run of unanswered ticks reaches a threshold. Pure, for tests.
     *
     * @param missed      consecutive ticks that found the ping unanswered
     * @param thresholdMs the threshold in milliseconds
     *
     * @return {@code true} when the unanswered time reaches the threshold
     */
    static boolean stalledFor( int missed, long thresholdMs )
    {
        return (long) missed * PING_MS >= thresholdMs;
    }

    private static void run()
    {
        boolean dumpedThisStall = false;
        int dumps = 0;
        while ( true ) {
            try {
                Thread.sleep( PING_MS );
            }
            catch ( InterruptedException e ) {
                return;
            }
            if ( pingPending ) {
                missedTicks++;
            }
            else {
                missedTicks = 0;
                pingPending = true;
                try {
                    Platform.runLater( () -> pingPending = false );
                }
                catch ( IllegalStateException toolkitGone ) {
                    // The toolkit has exited (launcher shutting down): nothing left to watch.
                    missedTicks = 0;
                    return;
                }
            }
            if ( !stalledFor( missedTicks, STALL_MS ) ) {
                dumpedThisStall = false;
            }
            else if ( !dumpedThisStall && dumps < MAX_DUMPS ) {
                dumpedThisStall = true;
                dumps++;
                writeDump();
            }
        }
    }

    private static void writeDump()
    {
        long stalledMs = (long) missedTicks * PING_MS;
        writeDumpFile( "ui-hang-", "FX application thread unanswered for " + stalledMs + " ms",
                       "The UI thread has not answered for " + stalledMs + " ms" );
    }

    /**
     * Writes a thread dump to {@code logs/<prefix><time>.log} in the launcher folder and notes it
     * in {@code startup.log}. Never throws.
     *
     * @param prefix  the file name's start, such as {@code ui-hang-}
     * @param heading the dump's first line
     * @param event   what happened, for the {@code startup.log} line
     *
     * @since 2026.11
     */
    static void writeDumpFile( String prefix, String heading, String event )
    {
        try {
            Path folder = Path.of( LocalPathConstants.CLIENT_MODE_LAUNCHER_FOLDER_PATH + LocalPathConstants.LOG_FOLDER );
            Files.createDirectories( folder );
            String stamp = LocalDateTime.now().format( DateTimeFormatter.ofPattern( "yyyyMMdd-HHmmss" ) );
            Path file = folder.resolve( prefix + stamp + ".log" );
            Files.writeString( file, threadDump( heading ), StandardCharsets.UTF_8 );
            StartupDiagnostics.record( event + "; thread dump in " + file.getFileName() );
        }
        catch ( Throwable t ) {
            StartupDiagnostics.record( event + ", and the thread dump could not be written", t );
        }
    }

    /**
     * Every live thread's stack with its locks, headed by any deadlock the JVM can see.
     *
     * @param stalledMs how long the FX thread has gone without answering
     *
     * @return the dump as text
     */
    static String threadDump( long stalledMs )
    {
        return threadDump( "FX application thread unanswered for " + stalledMs + " ms" );
    }

    /**
     * Every live thread's stack with its locks, headed by any deadlock the JVM can see.
     *
     * @param heading the dump's first line
     *
     * @return the dump as text
     */
    static String threadDump( String heading )
    {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        StringBuilder out = new StringBuilder();
        out.append( heading ).append( '\n' );
        out.append( "Java " ).append( System.getProperty( "java.version" ) ).append( ", " )
           .append( System.getProperty( "os.name" ) ).append( ' ' ).append( System.getProperty( "os.version" ) )
           .append( ' ' ).append( System.getProperty( "os.arch" ) ).append( "\n\n" );
        long[] deadlocked = bean.findDeadlockedThreads();
        if ( deadlocked != null && deadlocked.length > 0 ) {
            out.append( "DEADLOCK between " ).append( deadlocked.length ).append( " threads:\n" );
            for ( ThreadInfo info : bean.getThreadInfo( deadlocked, true, true ) ) {
                appendThread( out, info );
            }
            out.append( "\nAll threads:\n" );
        }
        ThreadInfo[] all = bean.dumpAllThreads( bean.isObjectMonitorUsageSupported(),
                                                bean.isSynchronizerUsageSupported() );
        // The FX thread first: it's the one that matters.
        for ( ThreadInfo info : all ) {
            if ( info != null && "JavaFX Application Thread".equals( info.getThreadName() ) ) {
                appendThread( out, info );
            }
        }
        for ( ThreadInfo info : all ) {
            if ( info != null && !"JavaFX Application Thread".equals( info.getThreadName() ) ) {
                appendThread( out, info );
            }
        }
        return out.toString();
    }

    private static void appendThread( StringBuilder out, ThreadInfo info )
    {
        if ( info == null ) {
            return;
        }
        // ThreadInfo.toString cuts stacks at 8 frames; write them whole.
        out.append( '"' ).append( info.getThreadName() ).append( "\" id=" ).append( info.getThreadId() )
           .append( ' ' ).append( info.getThreadState() );
        if ( info.getLockName() != null ) {
            out.append( " on " ).append( info.getLockName() );
        }
        if ( info.getLockOwnerName() != null ) {
            out.append( " owned by \"" ).append( info.getLockOwnerName() ).append( "\" id=" )
               .append( info.getLockOwnerId() );
        }
        out.append( '\n' );
        StackTraceElement[] stack = info.getStackTrace();
        java.lang.management.MonitorInfo[] monitors = info.getLockedMonitors();
        for ( int i = 0; i < stack.length; i++ ) {
            out.append( "\tat " ).append( stack[ i ] ).append( '\n' );
            for ( java.lang.management.MonitorInfo monitor : monitors ) {
                if ( monitor.getLockedStackDepth() == i ) {
                    out.append( "\t- locked " ).append( monitor ).append( '\n' );
                }
            }
        }
        for ( java.lang.management.LockInfo lock : info.getLockedSynchronizers() ) {
            out.append( "\t- holds " ).append( lock ).append( '\n' );
        }
        out.append( '\n' );
    }
}

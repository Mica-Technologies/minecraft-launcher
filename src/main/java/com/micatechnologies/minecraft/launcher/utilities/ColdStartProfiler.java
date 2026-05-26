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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cold-start phase profiler. Off by default — {@link #mark(String)} is a
 * single static-final check plus an early return when the
 * {@code MMCL_PROFILE_OUTPUT} env var (or {@code mmcl.profile.output}
 * system property) is unset.
 *
 * <p>When enabled:</p>
 * <ul>
 *   <li>{@link #mark(String)} records a phase waypoint with the
 *       nanosecond-precision offset from JVM class-load.</li>
 *   <li>{@link #writeAndMaybeExit()} appends one CSV row per phase to the
 *       output file (header written on first row). If
 *       {@code MMCL_EXIT_AFTER_PAINT=true}, also triggers an immediate
 *       JVM shutdown so a measurement wrapper can drive N back-to-back
 *       cold starts without manually closing the launcher.</li>
 * </ul>
 *
 * <p>Wrapper-driven by design: a PowerShell/Bash script sets the env
 * vars, launches the bundled exe, and the launcher self-terminates
 * after the main menu paints. CSV format is long (one row per phase)
 * so new waypoints can be added without invalidating existing data.</p>
 *
 * @author Mica Technologies
 * @since 2.0
 */
public final class ColdStartProfiler
{
    /**
     * Anchor time (nanos) at class load. {@code LauncherCore.main()} runs
     * within microseconds of class load on a cold JVM, so this is a
     * reasonable proxy for "the JVM is up and our code is starting."
     */
    private static final long ANCHOR_NS = System.nanoTime();

    /** Real epoch ms of JVM start, courtesy of the management bean. */
    private static final long JVM_START_EPOCH_MS =
            ManagementFactory.getRuntimeMXBean().getStartTime();

    /** Order-preserving map of phase name → ns offset from {@link #ANCHOR_NS}. */
    private static final Map< String, Long > MARKS = new LinkedHashMap<>();

    private static final boolean ENABLED;
    private static final boolean EXIT_AFTER_PAINT;
    private static final Path OUTPUT_PATH;

    private static final AtomicBoolean FLUSHED = new AtomicBoolean( false );

    static {
        String out = firstNonBlank( System.getenv( "MMCL_PROFILE_OUTPUT" ),
                                    System.getProperty( "mmcl.profile.output" ) );
        ENABLED = out != null;
        OUTPUT_PATH = ENABLED ? Path.of( out ) : null;

        String exit = firstNonBlank( System.getenv( "MMCL_EXIT_AFTER_PAINT" ),
                                     System.getProperty( "mmcl.profile.exit-after-paint" ) );
        EXIT_AFTER_PAINT = ENABLED &&
                ( "true".equalsIgnoreCase( exit ) || "1".equals( exit ) );
    }

    private ColdStartProfiler() { }

    /**
     * Records a cold-start phase waypoint. No-op (single static field check
     * + early return) when the profiler is off, so call sites can be left
     * in place permanently with negligible runtime cost.
     *
     * @param phaseName a short identifier for the waypoint
     *                  (e.g. {@code "locale_ready"}, {@code "main_menu_painted"})
     */
    public static void mark( String phaseName )
    {
        if ( !ENABLED ) {
            return;
        }
        long ns = System.nanoTime() - ANCHOR_NS;
        synchronized ( MARKS ) {
            MARKS.put( phaseName, ns );
        }
    }

    /** Returns whether the profiler is currently capturing data. Cheap. */
    public static boolean isEnabled()
    {
        return ENABLED;
    }

    /**
     * Writes the captured marks to the output CSV. If exit-after-paint mode
     * is enabled, also terminates the JVM after the write.
     *
     * <p>Safe to call multiple times — only the first call actually writes;
     * subsequent calls no-op so defensive invocations from other shutdown
     * paths are harmless.</p>
     */
    public static void writeAndMaybeExit()
    {
        if ( !ENABLED ) {
            return;
        }
        if ( !FLUSHED.compareAndSet( false, true ) ) {
            return;
        }
        try {
            flushCsv();
        }
        catch ( Throwable t ) {
            // Don't take down the launcher if profiling I/O fails — the worst
            // case is a missing CSV row that the wrapper will detect and re-run.
            //noinspection CallToPrintStackTrace
            t.printStackTrace();
        }
        if ( EXIT_AFTER_PAINT ) {
            // Defer one event-loop tick so the FX thread completes its current paint
            // before we yank the rug. The wrapper measures from process spawn to
            // process exit, so this ~100ms tail is part of the recorded number but
            // is constant across baseline/treatment and cancels out in the delta.
            Thread exiter = new Thread( () -> {
                try {
                    Thread.sleep( 100 );
                }
                catch ( InterruptedException ignored ) {
                }
                try {
                    javafx.application.Platform.exit();
                }
                catch ( Throwable ignored ) {
                }
                System.exit( 0 );
            }, "mmcl-profile-exit" );
            exiter.setDaemon( true );
            exiter.start();
        }
    }

    private static void flushCsv() throws IOException
    {
        List< Map.Entry< String, Long > > snapshot;
        synchronized ( MARKS ) {
            snapshot = new ArrayList<>( MARKS.entrySet() );
        }
        if ( snapshot.isEmpty() ) {
            return;
        }

        Path parent = OUTPUT_PATH.getParent();
        if ( parent != null ) {
            Files.createDirectories( parent );
        }
        boolean newFile = !Files.exists( OUTPUT_PATH ) || Files.size( OUTPUT_PATH ) == 0;

        String runId = String.valueOf( JVM_START_EPOCH_MS );
        String tsIso = Instant.now().toString();
        String jdk = escape( System.getProperty( "java.version", "" ) );
        String os = escape( System.getProperty( "os.name", "" ) );
        String arch = escape( System.getProperty( "os.arch", "" ) );
        boolean cds = detectCdsEnabled();

        StringBuilder out = new StringBuilder( 512 );
        if ( newFile ) {
            out.append( "ts_iso,run_id,jvm_start_epoch_ms,jdk_version,os,arch,"
                                + "cds_enabled,phase,offset_ms\n" );
        }
        for ( Map.Entry< String, Long > e : snapshot ) {
            out.append( tsIso ).append( ',' )
               .append( runId ).append( ',' )
               .append( JVM_START_EPOCH_MS ).append( ',' )
               .append( jdk ).append( ',' )
               .append( os ).append( ',' )
               .append( arch ).append( ',' )
               .append( cds ).append( ',' )
               .append( escape( e.getKey() ) ).append( ',' )
               .append( nsToMs( e.getValue() ) ).append( '\n' );
        }

        Files.writeString( OUTPUT_PATH, out.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND );
    }

    private static long nsToMs( long ns )
    {
        return ( ns + 500_000L ) / 1_000_000L;
    }

    private static boolean detectCdsEnabled()
    {
        try {
            for ( String a : ManagementFactory.getRuntimeMXBean().getInputArguments() ) {
                if ( a.contains( "SharedArchiveFile" ) || a.equalsIgnoreCase( "-Xshare:on" ) ) {
                    return true;
                }
            }
        }
        catch ( Throwable ignored ) {
        }
        return false;
    }

    private static String firstNonBlank( String... values )
    {
        for ( String v : values ) {
            if ( v != null && !v.isBlank() ) {
                return v;
            }
        }
        return null;
    }

    private static String escape( String s )
    {
        if ( s.indexOf( ',' ) < 0 && s.indexOf( '"' ) < 0 && s.indexOf( '\n' ) < 0 ) {
            return s;
        }
        return "\"" + s.replace( "\"", "\"\"" ) + "\"";
    }
}

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

package com.micatechnologies.minecraft.launcher.game.modpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-modpack download audit log. Records one JSON line per <em>re-download</em> event —
 * a file that already existed on disk, failed its declared-hash verification, and was
 * re-fetched — into {@code <packRoot>/.audit_log}. Each entry carries the launch number,
 * a timestamp, the file path, and the file's hash before and after the download (plus the
 * manifest's expected hash).
 *
 * <p>The point is diagnostics: a file whose {@code oldHash == newHash == expected} across
 * launch after launch is being re-downloaded for nothing — almost always a manifest hash
 * that doesn't match the bytes the server actually serves, so verification fails every
 * launch and the file is re-fetched indefinitely. {@link #analyzeProblems} surfaces exactly
 * that pattern (a file re-downloaded in N consecutive launches) for the detail modal.</p>
 *
 * <p>Only re-downloads of existing files are recorded, so a healthy launch (everything
 * verifies, nothing downloads) and a cold install (no prior file to compare) write nothing —
 * the cost is borne only by the misconfigured packs this is meant to catch.</p>
 *
 * <p>The current-launch context ({@link #beginLaunch}/{@link #endLaunch}) is held statically.
 * Same rationale as {@link ManagedGameFile}'s {@code currentVerifyMode}: the launcher
 * serializes launches at the {@code LauncherCore.play()} boundary, so concurrent launches
 * can't race it. The per-file writes <em>within</em> a launch run concurrently on the shared
 * download pool, so each append is serialized on the audit file's canonical monitor.</p>
 *
 * @author Mica Technologies
 * @since 3.7
 */
public final class ModPackAuditLog
{
    private ModPackAuditLog() { /* static-only */ }

    /** Audit log filename, in each pack's root folder. */
    static final String AUDIT_FILE = ".audit_log";

    /** Soft size ceiling; past this the log is trimmed to its most recent lines on the next write. */
    private static final long MAX_AUDIT_BYTES = 512L * 1024L;

    /** How many of the most recent lines survive a trim. */
    private static final int TRIM_RETAIN_LINES = 2000;

    /** Default "re-downloaded every launch" threshold for {@link #analyzeProblems(String, int)}. */
    public static final int DEFAULT_PROBLEM_THRESHOLD = 3;

    // Current-launch context. Set by the launch orchestrator around the download phase.
    private static volatile String currentPackRoot = null;
    private static volatile int    currentLaunchId = -1;

    /**
     * Marks the start of a launch's download phase. Subsequent {@link #recordRedownload}
     * calls are attributed to {@code launchId} and written under {@code packRoot}.
     *
     * @param packRoot the pack's root folder
     * @param launchId the launch number this run will be recorded under
     */
    public static void beginLaunch( String packRoot, int launchId )
    {
        currentPackRoot = packRoot;
        currentLaunchId = launchId;
    }

    /** Clears the current-launch context. Always call from the orchestrator's finally. */
    public static void endLaunch()
    {
        currentPackRoot = null;
        currentLaunchId = -1;
    }

    /** Whether a launch context is active (an audit entry would be recorded). */
    public static boolean isRecording()
    {
        return currentPackRoot != null;
    }

    /**
     * Records a re-download of an existing file. No-op when no launch context is active.
     * Never throws — diagnostics must not break a launch.
     *
     * @param fullLocalPath the file's full local path (relativized against the pack root)
     * @param oldHash       hash of the file before the download (may be null)
     * @param newHash       hash of the file after the download (may be null)
     * @param expectedHash  the manifest's declared hash (may be null)
     * @param algo          the hash algorithm used ({@code sha256}/{@code sha1}/{@code md5}, may be null)
     */
    public static void recordRedownload( String fullLocalPath, String oldHash, String newHash,
                                         String expectedHash, String algo )
    {
        String packRoot = currentPackRoot;
        int launchId = currentLaunchId;
        if ( packRoot == null || fullLocalPath == null ) {
            return;
        }
        try {
            JsonObject entry = new JsonObject();
            entry.addProperty( "ts", System.currentTimeMillis() );
            entry.addProperty( "launch", launchId );
            entry.addProperty( "file", relativize( packRoot, fullLocalPath ) );
            entry.addProperty( "oldHash", oldHash == null ? "" : oldHash );
            entry.addProperty( "newHash", newHash == null ? "" : newHash );
            entry.addProperty( "expected", expectedHash == null ? "" : expectedHash );
            entry.addProperty( "algo", algo == null ? "" : algo );
            // True when the bytes actually changed; false means we re-fetched identical content
            // (the strongest signal of a misconfigured manifest hash).
            entry.addProperty( "changed",
                               oldHash != null && newHash != null && !oldHash.equalsIgnoreCase( newHash ) );
            appendLine( packRoot, entry.toString() );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "Failed to write modpack audit log entry: " + t.getMessage() );
        }
    }

    private static void appendLine( String packRoot, String line ) throws IOException
    {
        Path auditPath = Path.of( packRoot, AUDIT_FILE );
        File auditFile = SynchronizedFileManager.getSynchronizedFile( auditPath.toString() );
        // Serialize concurrent per-file writes within a launch on the canonical monitor.
        synchronized ( auditFile ) {
            if ( auditFile.exists() && auditFile.length() > MAX_AUDIT_BYTES ) {
                trim( auditPath );
            }
            Files.writeString( auditPath, line + System.lineSeparator(), StandardCharsets.UTF_8,
                               StandardOpenOption.CREATE, StandardOpenOption.APPEND );
        }
    }

    /** Drops the oldest lines so the log stays bounded. Best-effort. */
    private static void trim( Path auditPath )
    {
        try {
            List< String > lines = Files.readAllLines( auditPath, StandardCharsets.UTF_8 );
            if ( lines.size() > TRIM_RETAIN_LINES ) {
                List< String > keep = new ArrayList<>( lines.subList( lines.size() - TRIM_RETAIN_LINES,
                                                                      lines.size() ) );
                Files.write( auditPath, keep, StandardCharsets.UTF_8,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING );
            }
        }
        catch ( IOException ignored ) {
            // A trim failure isn't worth failing the write over.
        }
    }

    private static String relativize( String packRoot, String fullLocalPath )
    {
        try {
            Path root = Path.of( packRoot ).toAbsolutePath().normalize();
            Path full = Path.of( fullLocalPath ).toAbsolutePath().normalize();
            if ( full.startsWith( root ) ) {
                return root.relativize( full ).toString().replace( '\\', '/' );
            }
        }
        catch ( Exception ignored ) {
            // Fall through to the raw path.
        }
        return fullLocalPath.replace( '\\', '/' );
    }

    // =========================================================================================
    //  Problems analysis
    // =========================================================================================

    /**
     * A file the audit log flags as re-downloading every launch.
     *
     * @param file                 the file path, relative to the pack root
     * @param consecutiveLaunches  how many consecutive most-recent launches re-downloaded it
     * @param contentUnchanged     true when every one of those re-downloads fetched identical
     *                             bytes — the hallmark of a manifest hash that doesn't match the
     *                             served file
     */
    public record Problem( String file, int consecutiveLaunches, boolean contentUnchanged ) { }

    /**
     * Reads the pack's audit log and returns the files that were re-downloaded in the
     * {@code threshold} most-recent consecutive launches (anchored at the pack's latest
     * launch number). An empty list means no such pattern — or no log at all.
     *
     * @param packRoot           the pack's root folder
     * @param mostRecentLaunchId the pack's current launch number (its latest completed launch)
     * @param threshold          how many consecutive launches define a problem
     *
     * @return the flagged files (possibly empty), never null
     */
    public static List< Problem > analyzeProblems( String packRoot, int mostRecentLaunchId, int threshold )
    {
        List< Problem > problems = new ArrayList<>();
        if ( packRoot == null || threshold <= 0 || mostRecentLaunchId < threshold ) {
            return problems;
        }
        Path auditPath = Path.of( packRoot, AUDIT_FILE );
        if ( !Files.exists( auditPath ) ) {
            return problems;
        }

        // file -> launch ids it was re-downloaded in, and the set of those that fetched
        // identical bytes (changed == false).
        Map< String, Set< Integer > > launchesByFile = new HashMap<>();
        Map< String, Set< Integer > > unchangedLaunchesByFile = new HashMap<>();
        try {
            for ( String line : Files.readAllLines( auditPath, StandardCharsets.UTF_8 ) ) {
                if ( line.isBlank() ) {
                    continue;
                }
                try {
                    JsonObject entry = JsonParser.parseString( line ).getAsJsonObject();
                    String file = entry.get( "file" ).getAsString();
                    int launch = entry.get( "launch" ).getAsInt();
                    launchesByFile.computeIfAbsent( file, k -> new TreeSet<>() ).add( launch );
                    if ( entry.has( "changed" ) && !entry.get( "changed" ).getAsBoolean() ) {
                        unchangedLaunchesByFile.computeIfAbsent( file, k -> new TreeSet<>() ).add( launch );
                    }
                }
                catch ( Exception ignoredLine ) {
                    // Skip a malformed line rather than abandoning the whole analysis.
                }
            }
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Failed to read modpack audit log: " + e.getMessage() );
            return problems;
        }

        // A file is a problem when it has a re-download in every one of the most-recent
        // `threshold` consecutive launches: {mostRecent, mostRecent-1, ... mostRecent-threshold+1}.
        for ( Map.Entry< String, Set< Integer > > e : launchesByFile.entrySet() ) {
            Set< Integer > launches = e.getValue();
            boolean allPresent = true;
            boolean allUnchanged = true;
            Set< Integer > unchanged = unchangedLaunchesByFile.getOrDefault( e.getKey(), Set.of() );
            for ( int i = 0; i < threshold; i++ ) {
                int wanted = mostRecentLaunchId - i;
                if ( !launches.contains( wanted ) ) {
                    allPresent = false;
                    break;
                }
                if ( !unchanged.contains( wanted ) ) {
                    allUnchanged = false;
                }
            }
            if ( allPresent ) {
                problems.add( new Problem( e.getKey(), threshold, allUnchanged ) );
            }
        }
        problems.sort( ( a, b ) -> a.file().compareToIgnoreCase( b.file() ) );
        return problems;
    }

    /** Convenience overload using {@link #DEFAULT_PROBLEM_THRESHOLD}. */
    public static List< Problem > analyzeProblems( String packRoot, int mostRecentLaunchId )
    {
        return analyzeProblems( packRoot, mostRecentLaunchId, DEFAULT_PROBLEM_THRESHOLD );
    }
}

/*
 * Copyright (c) 2021-2026 Mica Technologies
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

import com.micatechnologies.minecraft.launcher.files.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-pack local update log. Every time we observe a modpack manifest's {@code packVersion}
 * field change from its previously-seen value, we append an entry here recording the
 * before/after versions and the timestamp. The result is a chronological "this is how this
 * pack has evolved on our end" trail that the expanded modpack-detail view in the main
 * menu can render.
 *
 * <p>Two files are kept inside each pack's root folder:
 * <ul>
 *   <li>{@code .last_seen_remote_version} — a single line holding the manifest's
 *       {@code packVersion} as of the last time we saw it. Used to detect "the upstream
 *       version changed since our last fetch."</li>
 *   <li>{@code .update_log.txt} — append-only newline-delimited records. Each line is
 *       {@code <epochMs>|<oldVersion>|<newVersion>}, in chronological order (oldest
 *       first). Newest-first display is the renderer's job.</li>
 * </ul>
 *
 * <p>The first sighting of a pack (no {@code .last_seen_remote_version} file) does NOT
 * write a log entry — there's nothing to compare against, and the user already sees the
 * current version on the card. We just seed the tracking file so the next manifest fetch
 * has a baseline to compare against. This means installs predating this feature start
 * with an empty log and accumulate entries from there.
 *
 * <p>All operations are best-effort: I/O failures log a warning and otherwise no-op so
 * a broken filesystem never blocks the modpack list rendering.
 *
 * @since 3.4
 */
public final class ModPackUpdateLog
{
    /** Filename inside {@code getPackRootFolder()} for the last-seen-version tracker. */
    private static final String LAST_SEEN_VERSION_FILE = ".last_seen_remote_version";

    /** Filename inside {@code getPackRootFolder()} for the append-only log. */
    private static final String UPDATE_LOG_FILE = ".update_log.txt";

    /** Line separator inside the log file. {@code |} doesn't appear in any version
     *  string we've seen in practice (semver / Forge use {@code .} and {@code -}), so
     *  we use it as the field delimiter to avoid clashing with version characters. */
    private static final String LOG_FIELD_SEP = "|";

    /** Hard cap on entries we'll keep per pack. Anything older gets trimmed on the next
     *  append. Picked at 200 because the modal renders a scrollable list and 200 ≈ a
     *  decade of weekly releases — plenty for human-meaningful history without
     *  unbounded growth. */
    private static final int MAX_ENTRIES = 200;

    private ModPackUpdateLog() { /* static-only */ }

    /**
     * Observes the pack's current remote/manifest version against the last-seen value
     * on disk. If they differ, appends a log entry and updates the tracker. If no
     * tracker exists yet, seeds it without logging (fresh install or pre-feature pack).
     *
     * <p>Safe to call from any thread; serializes per-pack via the synchronized block
     * (cheap — bounded by the small number of installed packs).
     *
     * @param pack the pack to observe; no-op if null, vanilla, or has no version
     */
    public static void recordRemoteVersionSeen( GameModPack pack )
    {
        if ( pack == null ) return;
        // Vanilla "packs" — their packVersion is the MC version ID, which is genuinely
        // informative ("1.20.4 → 1.21") but not a manifest update in the sense we're
        // tracking here. Skip to avoid noisy log entries every time the user installs a
        // new vanilla version side-by-side.
        if ( pack.isVanillaVersion() ) return;

        String currentVersion = pack.getPackVersion();
        if ( currentVersion == null || currentVersion.isBlank() ) return;
        // "Error" is the sentinel packVersion that GameModPack.createFailedModPack uses
        // for manifests that failed to load. Recording it would produce a misleading
        // "1.2.3 → Error" entry on the first transient network hiccup.
        if ( "Error".equals( currentVersion ) ) return;

        Path root = Path.of( pack.getPackRootFolder() );
        Path trackerFile = root.resolve( LAST_SEEN_VERSION_FILE );
        Path logFile     = root.resolve( UPDATE_LOG_FILE );

        synchronized ( lockFor( pack ) ) {
            try {
                Files.createDirectories( root );
                String lastSeen = readLastSeenVersion( trackerFile );
                if ( lastSeen == null ) {
                    // First sighting — seed the tracker without logging.
                    writeLastSeenVersion( trackerFile, currentVersion );
                    return;
                }
                if ( lastSeen.equals( currentVersion ) ) {
                    return;
                }
                appendLogEntry( logFile, System.currentTimeMillis(), lastSeen, currentVersion );
                writeLastSeenVersion( trackerFile, currentVersion );
                trimLogIfNeeded( logFile );
            }
            catch ( Exception e ) {
                // Failure here must never break manifest fetching — just log and bail.
                Logger.logWarningSilent( "Unable to update modpack update-log for "
                                                 + pack.getPackName() + ": " + e.getMessage() );
            }
        }
    }

    /**
     * Reads the per-pack update log from disk. Returns entries ordered newest-first so
     * the UI can render them top-down without an extra sort.
     *
     * @param pack the pack whose log to read
     * @return list of entries, newest-first; empty list if no log exists yet
     */
    public static List< Entry > readEntries( GameModPack pack )
    {
        if ( pack == null ) return Collections.emptyList();
        Path logFile = Path.of( pack.getPackRootFolder() ).resolve( UPDATE_LOG_FILE );
        if ( !Files.exists( logFile ) ) return Collections.emptyList();

        List< Entry > entries = new ArrayList<>();
        try {
            List< String > lines = Files.readAllLines( logFile, StandardCharsets.UTF_8 );
            for ( String line : lines ) {
                Entry e = Entry.parse( line );
                if ( e != null ) entries.add( e );
            }
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Unable to read update log for " + pack.getPackName()
                                             + ": " + e.getMessage() );
            return Collections.emptyList();
        }
        Collections.reverse( entries );
        return entries;
    }

    /**
     * One row in the update log. Immutable, holds parsed fields for rendering.
     */
    public record Entry( long timestampMs, String oldVersion, String newVersion )
    {
        static Entry parse( String line )
        {
            if ( line == null ) return null;
            String trimmed = line.trim();
            if ( trimmed.isEmpty() ) return null;
            String[] parts = trimmed.split( "\\" + LOG_FIELD_SEP, 3 );
            if ( parts.length < 3 ) return null;
            try {
                long ts = Long.parseLong( parts[ 0 ] );
                return new Entry( ts, parts[ 1 ], parts[ 2 ] );
            }
            catch ( NumberFormatException ignored ) {
                return null;
            }
        }
    }

    // ---------------------------------------------------------------------------------

    private static String readLastSeenVersion( Path trackerFile )
    {
        if ( !Files.exists( trackerFile ) ) return null;
        try {
            String v = Files.readString( trackerFile, StandardCharsets.UTF_8 ).trim();
            return v.isEmpty() ? null : v;
        }
        catch ( IOException ignored ) {
            return null;
        }
    }

    private static void writeLastSeenVersion( Path trackerFile, String version ) throws IOException
    {
        Files.writeString( trackerFile, version, StandardCharsets.UTF_8 );
    }

    private static void appendLogEntry( Path logFile, long timestampMs, String oldVersion, String newVersion )
            throws IOException
    {
        String line = timestampMs + LOG_FIELD_SEP + sanitize( oldVersion ) + LOG_FIELD_SEP
                + sanitize( newVersion ) + System.lineSeparator();
        Files.writeString( logFile, line, StandardCharsets.UTF_8,
                           StandardOpenOption.CREATE, StandardOpenOption.APPEND );
    }

    /** Replaces field-separator characters with a visually-similar substitute so a
     *  rogue version string can't corrupt the parsing of subsequent fields. */
    private static String sanitize( String version )
    {
        if ( version == null ) return "";
        return version.replace( LOG_FIELD_SEP, "/" ).replace( "\n", " " ).replace( "\r", " " );
    }

    /** Caps the log at {@link #MAX_ENTRIES} entries by rewriting the file with the
     *  newest {@code MAX_ENTRIES} lines. Cheap because the file is small and trim only
     *  runs after an append. */
    private static void trimLogIfNeeded( Path logFile ) throws IOException
    {
        List< String > lines = Files.readAllLines( logFile, StandardCharsets.UTF_8 );
        if ( lines.size() <= MAX_ENTRIES ) return;
        List< String > trimmed = lines.subList( lines.size() - MAX_ENTRIES, lines.size() );
        Files.write( logFile, trimmed, StandardCharsets.UTF_8 );
    }

    /** Per-pack lock for serializing read/write on the same pack's log files. Using
     *  the pack's root path (interned) means two different packs can update in
     *  parallel — the manifest fetch is parallelStreamed in
     *  {@link GameModPackManager#fetchInstalledModPacks}. */
    private static Object lockFor( GameModPack pack )
    {
        return ( "modpack-update-log:" + pack.getPackRootFolder() ).intern();
    }

    /**
     * Formats a log entry timestamp for the UI. Local-time, day-resolution since
     * per-second precision isn't meaningful here (pack updates happen on the scale of
     * days/weeks, not seconds).
     *
     * @param entry the entry whose timestamp to format
     * @return e.g. "2026-05-11"
     */
    public static String formatTimestamp( Entry entry )
    {
        if ( entry == null ) return "";
        return java.time.LocalDate
                .ofInstant( java.time.Instant.ofEpochMilli( entry.timestampMs() ),
                            java.time.ZoneId.systemDefault() )
                .toString();
    }
}

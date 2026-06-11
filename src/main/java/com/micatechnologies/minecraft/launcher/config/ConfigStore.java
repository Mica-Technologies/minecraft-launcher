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

package com.micatechnologies.minecraft.launcher.config;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Persistence + state layer for {@link ConfigManager}. Owns the JSON
 * configObject, the read-from-disk path, and the debounced write
 * scheduler so the typed accessor methods on {@code ConfigManager}
 * can focus on per-key get/set logic without re-implementing the
 * load + persist plumbing.
 *
 * <p>This is the first step of the A6 domain split tracked in
 * {@code LAUNCHER_05_14_REVIEW_PLAN.md}. The eventual end state is
 * to split {@code ConfigManager} into per-domain managers (Runtime
 * / Auth / ModPack / RGB / Network / etc.) that each call into
 * {@code ConfigStore} for their backing state, but the current
 * {@code ConfigManager.set*}/{@code get*} APIs continue to work
 * unchanged during the migration.</p>
 *
 * <h3>Threading</h3>
 * <p>{@link #ensureLoaded()}, {@link #mutate(java.util.function.Consumer)},
 * {@link #flushNow()}, and the debounced write task are all synchronised on
 * the {@code ConfigStore} class monitor. The typed config setters in the
 * per-domain slices ({@code RuntimeConfig}, {@code AppConfig}, …) currently
 * mutate the {@link JsonObject} returned by {@link #ensureLoaded()} under their
 * OWN class monitors rather than {@code ConfigStore}'s, so they are not mutually
 * exclusive with the serializer. To keep that from corrupting / losing a write,
 * {@link #writeNow()} serializes a retrying {@code deepCopy} snapshot rather than
 * the live object. New mutation code should go through
 * {@link #mutate(java.util.function.Consumer)} (which holds the right monitor),
 * and the existing setters can migrate to it incrementally.</p>
 *
 * @since 2026.5
 */
public final class ConfigStore
{
    /** Delay between the most-recent {@link #scheduleWrite()} call and
     *  the actual disk flush. 500 ms is short enough that users don't
     *  notice "did my setting save?" lag, long enough to coalesce the
     *  typical Settings-save flurry into a single write. */
    private static final long WRITE_DEBOUNCE_MS = 500L;

    private static final ScheduledExecutorService WRITE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor( r -> {
                Thread t = new Thread( r, "mica-config-write" );
                t.setDaemon( true );
                return t;
            } );

    private static JsonObject json = null;
    private static ScheduledFuture< ? > pendingWrite = null;

    static {
        // JVM shutdown hook drains any pending flush so the last
        // setter call survives process exit even if it happened within
        // the debounce window.
        Runtime.getRuntime().addShutdownHook( new Thread( ConfigStore::flushNow,
                                                          "mica-config-shutdown-flush" ) );
    }

    private ConfigStore() { /* static-only */ }

    // ====================================================================
    // State access — used by ConfigManager's typed accessors
    // ====================================================================

    /** Lazily loads the config from disk on first call; subsequent
     *  calls are no-ops. Returns the live {@link JsonObject}. Callers that
     *  mutate it do so under their own per-slice class monitors today; that
     *  isn't mutually exclusive with the serializer, so {@link #writeNow()}
     *  snapshots defensively. Prefer {@link #mutate(java.util.function.Consumer)}
     *  for new writes. */
    public static synchronized JsonObject ensureLoaded() {
        if ( json == null ) {
            loadFromDisk();
        }
        return json;
    }

    /** Replace the entire backing JSON (used by import / reset paths).
     *  Caller is responsible for migrating the imported document via
     *  {@link ConfigManager#migrateConfigIfNeeded} when applicable. */
    public static synchronized void setJson( JsonObject obj ) {
        json = obj;
    }

    /** Snapshot reference for diagnostic / migration code. Returns
     *  null if the config hasn't been loaded yet — call
     *  {@link #ensureLoaded()} first if you need a guaranteed-loaded
     *  object. */
    public static synchronized JsonObject peek() {
        return json;
    }

    /**
     * Applies a mutation to the live config JSON under the {@code ConfigStore}
     * monitor. This is the lock-correct way to change config: writes serialize
     * the JSON under the same monitor, so a mutation routed through here can
     * never race the serializer. Most existing typed setters still mutate the
     * object returned by {@link #ensureLoaded()} under their own class monitors
     * instead — {@link #writeNow()} defends against that with a retrying deep-copy
     * snapshot — but new code should prefer this, and the setters can migrate to
     * it incrementally.
     *
     * @param mutation receives the live JSON; must not retain the reference
     *                 beyond the callback
     *
     * @since 2026.6
     */
    public static synchronized void mutate( java.util.function.Consumer< JsonObject > mutation ) {
        mutation.accept( ensureLoaded() );
    }

    /**
     * Returns a deep copy of the live config JSON for serialization, retrying the
     * copy if a setter mutating under a different monitor structurally modifies
     * the backing map mid-copy ({@link java.util.ConcurrentModificationException}).
     * Bounded so a pathological continuous-mutation storm can't spin forever — on
     * exhaustion the CME propagates to {@link #writeNow()}, whose next scheduled
     * write retries anyway. Must be called with the {@code ConfigStore} monitor
     * held.
     */
    private static JsonObject snapshotJson() {
        final int maxAttempts = 8;
        for ( int attempt = 1; ; attempt++ ) {
            try {
                return json.deepCopy();
            }
            catch ( java.util.ConcurrentModificationException cme ) {
                if ( attempt >= maxAttempts ) {
                    throw cme;
                }
                Thread.onSpinWait();
            }
        }
    }

    // ====================================================================
    // Persistence
    // ====================================================================

    /** Read the on-disk config into memory. Bootstraps a fresh default
     *  document when the file is missing or unreadable, then delegates
     *  to {@link ConfigManager#migrateConfigIfNeeded()} for any
     *  schema upgrades.
     *
     *  <p>Detects three corrupt-input shapes and treats them uniformly:
     *  (1) parse exception (truncated JSON), (2) parse returns null
     *  (zero-byte file — Gson's "no JSON to read" sentinel), (3) parse
     *  returns a non-object (file replaced with array / primitive).
     *  In each case the bad file is moved aside to
     *  {@code configuration.json.corrupt-&lt;timestamp&gt;} so the user
     *  can recover settings post-hoc, then the launcher boots fresh.</p> */
    private static synchronized void loadFromDisk() {
        String path = LocalPathManager.getLauncherConfigFolderPath()
                + ConfigConstants.CONFIG_FILE_NAME;
        File configFile = SynchronizedFileManager.getSynchronizedFile( path );

        boolean read = configFile.isFile();
        Throwable parseError = null;
        if ( read ) {
            try {
                json = FileUtilities.readAsJsonObject( configFile );
                // Gson.fromJson("", JsonObject.class) returns null rather than
                // throwing — a non-empty file that doesn't deserialise into a
                // JsonObject (replaced with an array, primitive, or zero bytes
                // by a half-finished write) lands here. Treat the same as a
                // parse exception so the corrupt-recovery path fires.
                if ( json == null ) {
                    read = false;
                }
                else {
                    // Diagnostic on load — mirrors the writeNow logging so
                    // we can correlate "what got written" with "what got
                    // read on next launch." Critical for triaging "modpacks
                    // are gone" reports.
                    int packCount = -1;
                    try {
                        if ( json.has( ConfigConstants.MOD_PACKS_INSTALLED_KEY )
                                && json.get( ConfigConstants.MOD_PACKS_INSTALLED_KEY ).isJsonArray() ) {
                            packCount = json.getAsJsonArray(
                                    ConfigConstants.MOD_PACKS_INSTALLED_KEY ).size();
                        }
                    }
                    catch ( Exception ignored ) { /* defensive — diagnostic only */ }
                    long size = configFile.length();
                    Logger.logDebug( "ConfigStore.loadFromDisk: read " + size + " bytes "
                                             + "(modpacks=" + packCount + ") from " + path );
                }
            }
            catch ( Exception e ) {
                parseError = e;
                read = false;
            }
        }

        if ( !read && configFile.isFile() ) {
            // Save the bad file aside before we overwrite it with defaults so
            // the user (or support) can dig through the broken JSON to recover
            // settings or diagnose the corruption.
            preserveCorruptConfigFile( configFile );
            Logger.logError( LocalizationManager.CONFIG_EXISTS_CORRUPT_RESET_ERROR_TEXT );
            if ( parseError != null ) {
                Logger.logThrowable( parseError );
            }
        }

        if ( !read ) {
            json = new JsonObject();
            // First-launch defaults come from ConfigManager — calling
            // through it keeps the default-population logic in one
            // place. The empty json above is what ConfigManager.set*
            // operate on; each defaulting setter triggers
            // scheduleWrite which lands a single coalesced write.
            json.addProperty( ConfigConstants.CONFIG_VERSION_KEY, ConfigConstants.CONFIG_VERSION );
            ConfigManager.populateFirstRunDefaults();
            Logger.logStd( LocalizationManager.CONFIG_RESET_SUCCESS_TEXT );
        }
        ConfigManager.migrateConfigIfNeeded();
    }

    /** Renames a corrupt config file aside with a timestamp suffix so the
     *  user can recover settings later. Silently best-effort — if the rename
     *  fails (file locked, permission denied), the launcher still resets and
     *  the bad bytes get overwritten on the next write. */
    private static void preserveCorruptConfigFile( File configFile )
    {
        try {
            java.nio.file.Path src = configFile.toPath();
            String timestamp = new java.text.SimpleDateFormat( "yyyyMMdd-HHmmss" )
                    .format( new java.util.Date() );
            java.nio.file.Path dst = src.resolveSibling(
                    configFile.getName() + ".corrupt-" + timestamp );
            java.nio.file.Files.move( src, dst,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING );
            Logger.logStd( "Preserved corrupt config at " + dst
                                   + " — settings can be manually recovered from it." );
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Could not preserve corrupt config file: " + e.getMessage() );
        }
    }

    /**
     * Schedules a debounced disk write. Idempotent — repeated calls
     * within the {@link #WRITE_DEBOUNCE_MS} window cancel the previous
     * schedule and queue a new one, so a burst of setters produces
     * exactly one disk write.
     */
    public static synchronized void scheduleWrite() {
        if ( pendingWrite != null && !pendingWrite.isDone() ) {
            pendingWrite.cancel( false );
        }
        pendingWrite = WRITE_SCHEDULER.schedule(
                ConfigStore::writeNow, WRITE_DEBOUNCE_MS, TimeUnit.MILLISECONDS );
    }

    /** Synchronously flushes any pending debounced write. Called from
     *  the JVM shutdown hook + exposed for code paths that need
     *  durability before returning (config import, reset). No-op when
     *  nothing is queued. */
    public static synchronized void flushNow() {
        ScheduledFuture< ? > pending = pendingWrite;
        pendingWrite = null;
        if ( pending != null && !pending.isDone() ) {
            pending.cancel( false );
            writeNow();
        }
    }

    /** Actual disk-write implementation — runs on the scheduled-writer
     *  thread under normal operation, or on the shutdown-hook /
     *  flushNow callers' threads when they bypass the schedule.
     *
     *  <p><b>Atomic write contract.</b> Writes go to a sibling temp file
     *  ({@code configuration.json.tmp}) first, get fsync-ed to durable
     *  storage, and then atomic-renamed over the target. The sequence
     *  guarantees that any abrupt process exit — JVM crash, taskkill /f,
     *  power loss — leaves either the previous content or the new
     *  content on disk, never a half-written truncation. The non-atomic
     *  {@code FileUtils.writeStringToFile} this replaced was the root
     *  cause of "launcher randomly forgot my modpack list" reports: any
     *  kill during the open-truncate-write-close window left a 0-byte
     *  or partial JSON file that {@link #loadFromDisk} couldn't parse
     *  on the next launch, triggering the corrupt-recovery reset.</p> */
    private static synchronized void writeNow() {
        if ( json == null ) {
            Logger.logError( LocalizationManager.CONFIG_NOT_LOADED_CANT_SAVE_ERROR_TEXT );
            return;
        }
        // The atomic write below goes through a FileChannel, which is an
        // InterruptibleChannel: if this thread's interrupt flag is set when a
        // blocking write()/force() runs, the channel throws
        // ClosedByInterruptException and closes mid-write, losing the config.
        // This bit the relaunch path — cleanupApp()'s executor shutdownNow()
        // interrupted the very thread doing the durability-critical flush, so the
        // new locale override never reached disk and the relaunched process came
        // back in the old language. Clear the interrupt flag for the duration of
        // the I/O (restoring it afterward so a genuine interrupt isn't swallowed),
        // and retry once if an interrupt still lands mid-write: persisting config
        // wins over promptly honoring a shutdown interrupt.
        boolean wasInterrupted = Thread.interrupted();
        try {
            for ( int attempt = 1; ; attempt++ ) {
                try {
                    writeNowOnce();
                    return;
                }
                catch ( java.nio.channels.ClosedByInterruptException ie ) {
                    wasInterrupted = true;
                    Thread.interrupted();  // clear the flag the interrupt re-set, then retry
                    if ( attempt >= 2 ) {
                        Logger.logError( LocalizationManager.CONFIG_SAVE_ERROR_TEXT );
                        Logger.logThrowable( ie );
                        return;
                    }
                }
            }
        }
        finally {
            if ( wasInterrupted ) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A single atomic-write attempt: serialize the in-memory JSON to a sibling
     *  temp file, fsync it, and atomic-rename it over the target. Lets
     *  {@link java.nio.channels.ClosedByInterruptException} propagate so
     *  {@link #writeNow()} can retry it; every other failure is logged and the
     *  temp file cleaned up. Must be called with the {@code ConfigStore} monitor
     *  held (it is — {@link #writeNow()} is {@code synchronized}). */
    private static void writeNowOnce() throws java.nio.channels.ClosedByInterruptException {
        String path = LocalPathManager.getLauncherConfigFolderPath()
                + ConfigConstants.CONFIG_FILE_NAME;
        File configFile = SynchronizedFileManager.getSynchronizedFile( path );
        java.nio.file.Path target = configFile.toPath();
        java.nio.file.Path tmp = target.resolveSibling( target.getFileName() + ".tmp" );

        // Snapshot the live config before serializing. The typed config setters
        // mutate this object under their OWN class monitors (RuntimeConfig.class,
        // AppConfig.class, ...), not ConfigStore's, so a concurrent setter can
        // structurally modify the backing map while objectToString iterates it —
        // a ConcurrentModificationException that writeNow would catch, dropping
        // the debounced write (lost update). Serializing a private deep copy makes
        // the write immune to concurrent mutation; snapshotJson retries the copy
        // itself if a setter races the deepCopy.
        JsonObject snapshot = snapshotJson();
        try {
            // Serialize the snapshot.
            String payload = com.micatechnologies.minecraft.launcher.utilities.JSONUtilities
                    .objectToString( snapshot );
            byte[] bytes = payload.getBytes( java.nio.charset.StandardCharsets.UTF_8 );

            // Diagnostic: log the byte count and pack list being written. Helps
            // diagnose "modpacks list mysteriously empty" reports by making
            // every write visible in the launcher log. Trimmed to keep the log
            // line readable — the full payload is on disk seconds later.
            int packCount = -1;
            try {
                if ( snapshot.has( ConfigConstants.MOD_PACKS_INSTALLED_KEY )
                        && snapshot.get( ConfigConstants.MOD_PACKS_INSTALLED_KEY ).isJsonArray() ) {
                    packCount = snapshot.getAsJsonArray( ConfigConstants.MOD_PACKS_INSTALLED_KEY ).size();
                }
            }
            catch ( Exception ignored ) { /* defensive — diagnostic only */ }
            Logger.logDebug( "ConfigStore.writeNow: writing " + bytes.length
                                     + " bytes (modpacks=" + packCount + ") to " + target );

            // Ensure the parent directory exists — the launcher creates it
            // at startup but a stale clean / fresh user-home wipe could
            // have removed it between then and now.
            java.nio.file.Path parent = target.getParent();
            if ( parent != null ) {
                java.nio.file.Files.createDirectories( parent );
            }

            // Write + fsync the temp file. fsync is what guarantees the
            // bytes are durable before the rename; without it Windows /
            // Linux are allowed to cache the write indefinitely and the
            // atomic rename could publish a still-empty inode if a crash
            // hits between write() and fsync().
            try ( java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                    tmp,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE ) ) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap( bytes );
                while ( buf.hasRemaining() ) {
                    ch.write( buf );
                }
                ch.force( true );  // fsync data + metadata
            }

            // Atomic rename. ATOMIC_MOVE on Windows works as long as
            // source + destination are on the same volume, which they
            // always are here (both inside the launcher config folder).
            // REPLACE_EXISTING is required because the target almost
            // always exists.
            try {
                java.nio.file.Files.move( tmp, target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING );
            }
            catch ( java.nio.file.AtomicMoveNotSupportedException atomicEx ) {
                // Some Windows filesystems (older SMB shares, FAT32 USB
                // sticks) refuse atomic moves. Fall back to a plain
                // replace + a follow-up fsync attempt. The window of
                // partial-file exposure is small but not zero — log so
                // operators can investigate the underlying FS if it
                // recurs.
                Logger.logWarningSilent( "Atomic config move not supported on this filesystem — "
                                                 + "falling back to non-atomic replace." );
                java.nio.file.Files.move( tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING );
            }
        }
        catch ( java.nio.channels.ClosedByInterruptException ie ) {
            // A set/landed interrupt closed the channel mid-write. Clean the temp
            // file and rethrow so writeNow() can clear the flag and retry rather
            // than treating this as a genuine save failure.
            try { java.nio.file.Files.deleteIfExists( tmp ); }
            catch ( Exception ignored ) { /* nothing else to do */ }
            throw ie;
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.CONFIG_SAVE_ERROR_TEXT );
            Logger.logThrowable( e );
            // Best-effort cleanup of the temp file so a failed write
            // doesn't leave .tmp debris that confuses the next launch.
            try { java.nio.file.Files.deleteIfExists( tmp ); }
            catch ( Exception ignored ) { /* nothing else to do */ }
        }
    }
}

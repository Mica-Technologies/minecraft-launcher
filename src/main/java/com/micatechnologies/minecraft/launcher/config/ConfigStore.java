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
 * <p>{@link #ensureLoaded()}, raw get/has/put, and {@link #flushNow()}
 * are all class-level synchronised so concurrent setters see a
 * consistent {@link JsonObject}. The debounced write task acquires
 * the same lock when it runs on the scheduler thread.</p>
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
     *  calls are no-ops. Returns the live {@link JsonObject} so
     *  ConfigManager's existing patterns continue to work — wrap
     *  reads + writes against this object in {@code synchronized}
     *  on the ConfigManager class (which it already does). */
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

    // ====================================================================
    // Persistence
    // ====================================================================

    /** Read the on-disk config into memory. Bootstraps a fresh default
     *  document when the file is missing or unreadable, then delegates
     *  to {@link ConfigManager#migrateConfigIfNeeded()} for any
     *  schema upgrades. */
    private static synchronized void loadFromDisk() {
        String path = LocalPathManager.getLauncherConfigFolderPath()
                + ConfigConstants.CONFIG_FILE_NAME;
        File configFile = SynchronizedFileManager.getSynchronizedFile( path );

        boolean read = configFile.isFile();
        if ( read ) {
            try {
                json = FileUtilities.readAsJsonObject( configFile );
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.CONFIG_EXISTS_CORRUPT_RESET_ERROR_TEXT );
                Logger.logThrowable( e );
                read = false;
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
     *  flushNow callers' threads when they bypass the schedule. */
    private static synchronized void writeNow() {
        if ( json == null ) {
            Logger.logError( LocalizationManager.CONFIG_NOT_LOADED_CANT_SAVE_ERROR_TEXT );
            return;
        }
        String path = LocalPathManager.getLauncherConfigFolderPath()
                + ConfigConstants.CONFIG_FILE_NAME;
        File configFile = SynchronizedFileManager.getSynchronizedFile( path );
        try {
            FileUtilities.writeFromJson( json, configFile );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.CONFIG_SAVE_ERROR_TEXT );
            Logger.logThrowable( e );
        }
    }
}

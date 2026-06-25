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

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.files.Logger;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Creates and prunes per-pack backups of user-editable state ({@code config/}
 * by default, optionally {@code saves/}) before a modpack update is applied.
 * The backup lives at {@code <packRoot>/.backups/<timestamp>.zip}; the
 * {@code .backups} folder is launcher-managed and visible to the user from
 * the OS file explorer if they ever need to recover by hand.
 *
 * <h3>Why ZIP, not a directory copy</h3>
 *
 * Save folders are easily several hundred MB to multiple GB once a player
 * has explored a world. A directory copy doubles the storage cost; ZIP
 * compresses the world's metadata + region files reasonably (~30-50% on
 * typical worlds) and bundles the backup into a single artifact that's
 * trivial to copy off-device.
 *
 * <h3>Pruning policy</h3>
 *
 * Both per-pack count and age caps apply, whichever prunes more aggressively:
 * <ul>
 *   <li>{@link ConfigManager#getMaxBackupsPerPack()} — default 3 — keep the
 *       N most recent backups, delete older ones.</li>
 *   <li>{@link ConfigManager#getMaxBackupAgeDays()} — default 14 — delete
 *       backups older than this many days regardless of count.</li>
 * </ul>
 *
 * <p>The defaults aim to keep backup disk use bounded: for a typical pack
 * with a 500 MB save + ~5 MB config, three backups is ~1.5 GB worst-case,
 * which most users have to spare. Users who update aggressively can tighten
 * either knob in Settings.</p>
 *
 * @since 2026.5
 */
public final class ModpackBackupManager
{
    private ModpackBackupManager() { /* static-only */ }

    /** Name of the subfolder where the launcher writes pack backups. */
    public static final String BACKUPS_DIR = ".backups";

    private static final SimpleDateFormat TS_FORMAT =
            new SimpleDateFormat( "yyyy-MM-dd--HH-mm-ss" );

    /** Default subfolders inside the pack root that get backed up. {@code saves}
     *  is opt-in via the include-saves config flag because it can be huge. */
    private static final String[] ALWAYS_BACKUP = { "config" };
    private static final String[] OPTIONAL_BACKUP = { "saves" };

    /**
     * Creates a backup of the pack's user-editable state. No-op when:
     * <ul>
     *   <li>The "auto-backup before update" setting is off, OR</li>
     *   <li>The pack doesn't yet have an install folder (fresh install — no
     *       prior state to preserve), OR</li>
     *   <li>None of the watched subfolders ({@code config/} and optionally
     *       {@code saves/}) exist.</li>
     * </ul>
     *
     * <p>Errors are logged but not propagated — a backup failure should
     * never block the in-progress update. The user can opt out in Settings
     * if backups become problematic.</p>
     *
     * @param pack the pack whose user-editable state should be archived; a
     *             {@code null} pack or one without an install folder is a no-op
     * @return the path to the created backup zip, or {@code null} when no
     *         backup was created
     */
    public static File backupBeforeUpdate( GameModPack pack )
    {
        if ( pack == null ) return null;
        if ( !ConfigManager.getAutoBackupBeforeUpdate() ) return null;
        String packRoot = pack.getPackRootFolder();
        if ( packRoot == null || packRoot.isBlank() ) return null;
        File rootDir = new File( packRoot );
        if ( !rootDir.isDirectory() ) return null;

        // Collect subdirs that actually exist + match the include policy.
        java.util.List< File > subdirs = new java.util.ArrayList<>();
        for ( String name : ALWAYS_BACKUP ) {
            File d = new File( rootDir, name );
            if ( d.isDirectory() ) subdirs.add( d );
        }
        if ( ConfigManager.getBackupIncludeSaves() ) {
            for ( String name : OPTIONAL_BACKUP ) {
                File d = new File( rootDir, name );
                if ( d.isDirectory() ) subdirs.add( d );
            }
        }
        if ( subdirs.isEmpty() ) return null;

        File backupsDir = new File( rootDir, BACKUPS_DIR );
        if ( !backupsDir.exists() && !backupsDir.mkdirs() ) {
            Logger.logWarningSilent( "Couldn't create backups dir at " + backupsDir.getAbsolutePath() );
            return null;
        }
        File zipFile = new File( backupsDir, TS_FORMAT.format( new Date() ) + ".zip" );

        try ( ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream( new FileOutputStream( zipFile ) ) ) ) {
            for ( File subdir : subdirs ) {
                addDirectoryToZip( zip, subdir, subdir.getName() + "/" );
            }
        }
        catch ( IOException ex ) {
            Logger.logWarningSilent( "Pack backup failed: " + ex.getMessage() );
            // Best-effort cleanup of the partial zip; if delete fails the
            // next backup attempt will overwrite it.
            //noinspection ResultOfMethodCallIgnored
            zipFile.delete();
            return null;
        }

        Logger.logStd( "Created backup before update: " + zipFile.getName()
                               + " (" + humanSize( zipFile.length() ) + ")" );
        pruneOldBackups( pack );
        return zipFile;
    }

    /**
     * Removes backups beyond the configured count/age caps. Called automatically
     * after every successful backup; also safe to call standalone if a user
     * tightens the cap in Settings and wants old backups cleaned up immediately.
     *
     * @param pack the pack whose {@code .backups} folder should be pruned; a
     *             {@code null} pack or one without an install folder is a no-op
     */
    public static void pruneOldBackups( GameModPack pack )
    {
        if ( pack == null || pack.getPackRootFolder() == null ) return;
        File backupsDir = new File( pack.getPackRootFolder(), BACKUPS_DIR );
        if ( !backupsDir.isDirectory() ) return;
        File[] backups = backupsDir.listFiles( f -> f.isFile() && f.getName().endsWith( ".zip" ) );
        if ( backups == null || backups.length == 0 ) return;

        Arrays.sort( backups, Comparator.comparingLong( File::lastModified ).reversed() );
        int maxCount = Math.max( 0, ConfigManager.getMaxBackupsPerPack() );
        int maxAgeDays = Math.max( 0, ConfigManager.getMaxBackupAgeDays() );
        long ageCutoffMs = maxAgeDays > 0
                ? System.currentTimeMillis() - ( maxAgeDays * 24L * 60 * 60 * 1000 )
                : 0L;

        int deleted = 0;
        for ( int i = 0; i < backups.length; i++ ) {
            File backup = backups[ i ];
            boolean overCount = ( maxCount > 0 && i >= maxCount );
            boolean tooOld = ( maxAgeDays > 0 && backup.lastModified() < ageCutoffMs );
            if ( overCount || tooOld ) {
                if ( backup.delete() ) {
                    deleted++;
                }
                else {
                    Logger.logWarningSilent( "Couldn't delete old backup: " + backup.getName() );
                }
            }
        }
        if ( deleted > 0 ) {
            Logger.logStd( "Pruned " + deleted + " old pack backup(s) for "
                                   + pack.getPackName() );
        }
    }

    // -----------------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------------

    /** Recursively adds the contents of {@code dir} to {@code zip} under the
     *  given relative path prefix. Symlinks aren't followed (best-effort —
     *  Files.isSymbolicLink check filters them). */
    private static void addDirectoryToZip( ZipOutputStream zip, File dir, String prefix )
            throws IOException
    {
        File[] entries = dir.listFiles();
        if ( entries == null ) return;
        for ( File entry : entries ) {
            Path p = entry.toPath();
            if ( Files.isSymbolicLink( p ) ) continue;
            String entryName = prefix + entry.getName();
            if ( entry.isDirectory() ) {
                ZipEntry zEntry = new ZipEntry( entryName + "/" );
                zip.putNextEntry( zEntry );
                zip.closeEntry();
                addDirectoryToZip( zip, entry, entryName + "/" );
            }
            else {
                ZipEntry zEntry = new ZipEntry( entryName );
                zEntry.setTime( entry.lastModified() );
                zip.putNextEntry( zEntry );
                Files.copy( p, zip );
                zip.closeEntry();
            }
        }
    }

    private static String humanSize( long bytes )
    {
        if ( bytes < 1024 ) return bytes + " B";
        if ( bytes < 1024 * 1024 ) return String.format( "%.1f KB", bytes / 1024.0 );
        if ( bytes < 1024L * 1024 * 1024 ) return String.format( "%.1f MB", bytes / 1024.0 / 1024.0 );
        return String.format( "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0 );
    }
}

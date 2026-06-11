/*
 * Copyright (c) 2021 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.files;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class that manages access to files on the file system in a manner that only allows for one instance of a File object
 * for each file. This allows for consistent synchronization when writing, reading and otherwise interacting with files
 * in a multithreaded environment.
 *
 * <p>The map is keyed by the <em>normalized absolute</em> path (case-folded on
 * Windows) rather than the raw {@link Path} handed in. That matters for the
 * "one File per file" contract callers rely on for {@code synchronized(file)}:
 * before this, {@code Path.of("foo")} and {@code Path.of("/abs/.../foo")} (and,
 * on Windows, case-variant spellings) produced different keys and therefore
 * <em>different</em> File instances for the same underlying file — defeating the
 * mutual exclusion entirely. Normalizing the key collapses them to one instance.
 * A {@link ConcurrentHashMap} + {@code computeIfAbsent} replaces the
 * {@code static synchronized} lookup so the per-asset hot path no longer
 * serializes every download thread through one class monitor.</p>
 *
 * @author Mica Technologies
 * @version 3.0
 * @since 1.1
 */
public class SynchronizedFileManager
{
    /** True when the platform path separator is {@code '\'} (Windows), whose
     *  file system is case-insensitive, so keys are case-folded there. */
    private static final boolean CASE_INSENSITIVE_FS = File.separatorChar == '\\';

    /**
     * Internal map used to store file objects of files that have previously been accessed,
     * keyed by the normalized absolute path string.
     *
     * @since 1.0
     */
    private static final ConcurrentHashMap< String, File > managedFiles = new ConcurrentHashMap<>();

    /**
     * Gets the single, synchronize-able file object for the specified string file path.
     *
     * @param filePath string file path
     *
     * @return single file object
     *
     * @since 1.0
     */
    public static File getSynchronizedFile( String filePath ) {
        return getSynchronizedFile( Path.of( filePath ) );
    }

    /**
     * Gets the single, synchronize-able file object for the specified {@link Path}.
     *
     * @param filePath string file path
     *
     * @return single file object
     *
     * @since 1.0
     */
    public static File getSynchronizedFile( Path filePath ) {
        Path normalized = filePath.toAbsolutePath().normalize();
        String key = CASE_INSENSITIVE_FS
                     ? normalized.toString().toLowerCase( Locale.ROOT )
                     : normalized.toString();
        return managedFiles.computeIfAbsent( key, k -> normalized.toFile() );
    }
}

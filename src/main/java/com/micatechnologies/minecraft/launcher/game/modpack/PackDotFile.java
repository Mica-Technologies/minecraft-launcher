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

import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Shared read/write helper for the small per-pack "dotfile" sidecars that live
 * in a pack's root folder ({@code .installed_version}, {@code .launch_history},
 * {@code .seen_news}). Each of these previously hand-rolled the same
 * {@code Path.of(packRoot, name)} → {@code synchronized(getSynchronizedFile(...))}
 * → exists/read or {@code mkdirs}/write boilerplate; centralizing it here keeps
 * the locking + directory-creation + UTF-8 discipline consistent across all of
 * them.
 *
 * <p>All operations acquire the canonical per-path monitor from
 * {@link SynchronizedFileManager}, so reads never observe a half-written file
 * from a concurrent write of the same sidecar. {@link #modify} additionally
 * holds that monitor across the entire read-compute-write, which is what
 * atomic read-modify-write sidecars (e.g. {@code .launch_history}) require.</p>
 *
 * @author Mica Technologies
 * @since 2026.6
 */
final class PackDotFile
{
    private PackDotFile() { /* static utility */ }

    /**
     * Reads a sidecar's full contents under its per-path monitor.
     *
     * @param packRoot the pack root folder path
     * @param name     the sidecar file name (e.g. {@code .installed_version})
     *
     * @return the file contents, or {@code null} if the file is absent or unreadable
     */
    static String read( String packRoot, String name )
    {
        Path file = Path.of( packRoot, name );
        synchronized ( SynchronizedFileManager.getSynchronizedFile( file ) ) {
            if ( !Files.exists( file ) ) {
                return null;
            }
            try {
                return Files.readString( file, StandardCharsets.UTF_8 );
            }
            catch ( IOException e ) {
                return null;
            }
        }
    }

    /**
     * Reads a sidecar as a list of lines under its per-path monitor.
     *
     * @param packRoot the pack root folder path
     * @param name     the sidecar file name
     *
     * @return the file's lines, or an empty list if the file is absent or unreadable
     */
    static List< String > readLines( String packRoot, String name )
    {
        Path file = Path.of( packRoot, name );
        synchronized ( SynchronizedFileManager.getSynchronizedFile( file ) ) {
            if ( !Files.exists( file ) ) {
                return Collections.emptyList();
            }
            try {
                return Files.readAllLines( file, StandardCharsets.UTF_8 );
            }
            catch ( IOException e ) {
                return Collections.emptyList();
            }
        }
    }

    /**
     * Writes a sidecar's contents under its per-path monitor, creating the pack
     * root directory first if needed.
     *
     * @param packRoot the pack root folder path
     * @param name     the sidecar file name
     * @param content  the content to write (UTF-8)
     *
     * @throws IOException if the directory or file could not be written
     */
    static void write( String packRoot, String name, String content ) throws IOException
    {
        Path file = Path.of( packRoot, name );
        synchronized ( SynchronizedFileManager.getSynchronizedFile( file ) ) {
            //noinspection ResultOfMethodCallIgnored
            file.getParent().toFile().mkdirs();
            Files.writeString( file, content, StandardCharsets.UTF_8 );
        }
    }

    /**
     * Performs an atomic read-modify-write on a sidecar: reads the current
     * contents (or {@code null} when absent), applies {@code transform}, and
     * writes the result — all while holding the per-path monitor, so a
     * concurrent {@code modify}/{@code read}/{@code write} of the same sidecar
     * can't interleave. The pack root directory is created if needed.
     *
     * <p>The transform runs under the monitor, so callers may safely update
     * in-memory caches keyed to this sidecar from within it.</p>
     *
     * @param packRoot  the pack root folder path
     * @param name      the sidecar file name
     * @param transform receives the current contents (or {@code null}) and
     *                  returns the new contents to write
     *
     * @throws IOException if the directory or file could not be written
     */
    static void modify( String packRoot, String name, UnaryOperator< String > transform ) throws IOException
    {
        Path file = Path.of( packRoot, name );
        synchronized ( SynchronizedFileManager.getSynchronizedFile( file ) ) {
            String current = null;
            if ( Files.exists( file ) ) {
                try {
                    current = Files.readString( file, StandardCharsets.UTF_8 );
                }
                catch ( IOException ignored ) {
                    current = null;
                }
            }
            String updated = transform.apply( current );
            //noinspection ResultOfMethodCallIgnored
            file.getParent().toFile().mkdirs();
            Files.writeString( file, updated, StandardCharsets.UTF_8 );
        }
    }
}

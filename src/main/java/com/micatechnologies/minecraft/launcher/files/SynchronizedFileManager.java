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
import java.util.HashMap;
import java.util.Map;

/**
 * Class that manages access to files on the file system in a manner that only allows for one instance of a File object
 * for each file. This allows for consistent synchronization when writing, reading and otherwise interacting with files
 * in a multithreaded environment.
 *
 * @author Mica Technologies
 * @version 2.0
 * @since 1.1
 */
public class SynchronizedFileManager
{
    /**
     * Internal map used to store file objects of files that have previously been accessed.
     *
     * @since 1.0
     */
    private static final Map< Path, File > managedFiles = new HashMap<>();

    /**
     * Gets the single, synchronize-able file object for the specified string file path.
     *
     * @param filePath string file path
     *
     * @return single file object
     *
     * @since 1.0
     */
    public static synchronized File getSynchronizedFile( String filePath ) {
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
    public static synchronized File getSynchronizedFile( Path filePath ) {
        File synchronizedFileObject;

        // Get existing file object if key (file path) is present
        if ( managedFiles.containsKey( filePath ) ) {
            synchronizedFileObject = managedFiles.get( filePath );
        }
        // Create file object and add to map if not present
        else {
            synchronizedFileObject = filePath.toFile();
            managedFiles.put( filePath, synchronizedFileObject );
        }

        return synchronizedFileObject;
    }
}

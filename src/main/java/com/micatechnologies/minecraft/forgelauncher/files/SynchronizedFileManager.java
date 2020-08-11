/*
 * Copyright (c) 2020 Mica Technologies
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

package com.micatechnologies.minecraft.forgelauncher.files;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Class that manages access to files on the file system in a manner that only allows for one instance of a File object
 * for each file. This allows for consistent synchronization when writing, reading and otherwise interacting with files
 * in a multi-threaded environment.
 *
 * @author Mica Technologies
 * @version 1.0
 * @creator hawka97
 * @editors hawka97
 * @since 1.1
 */
public class SynchronizedFileManager
{
    /**
     * Internal list used to store file objects of files that have previously been accessed.
     *
     * @since 1.0
     */
    private static final List< File > managedFiles = new ArrayList<>();

    /**
     * Gets the single file object for the specified file path
     *
     * @param filePath file path
     *
     * @return single file object
     *
     * @since 1.0
     */
    public static File getSynchronizedFile( String filePath ) {
        // Search for existing object for desired file path
        File desiredSynchronizedFile = null;
        for ( File managedFile : managedFiles ) {
            if ( managedFile.getAbsolutePath().equalsIgnoreCase( filePath ) ) {
                desiredSynchronizedFile = managedFile;
                break;
            }
        }

        // If an object does not exist for desired file path, create it
        if ( desiredSynchronizedFile == null ) {
            desiredSynchronizedFile = new File( filePath );
        }

        return desiredSynchronizedFile;
    }
}

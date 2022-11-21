/*
 * Copyright (c) 2022 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.files.hash;

import com.micatechnologies.minecraft.launcher.files.ManagedRemoteFile;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;

/**
 * Basic class used to store the value of a hash for a {@link ManagedRemoteFile} in an identifiable class (MD5 vs SHA1,
 * etc.).
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public abstract class FileChecksum
{
    /**
     * The hash value.
     *
     * @since 1.0
     */
    private final String value;

    /**
     * Constructor for an instance of {@link FileChecksum} with the specified hash.
     *
     * @param value hash value
     *
     * @since 1.0
     */
    public FileChecksum( String value ) {
        this.value = value;
    }

    /**
     * Gets the hash value.
     *
     * @return hash value
     *
     * @since 1.0
     */
    public String getValue() {
        return value;
    }

    /**
     * Verifies and returns a boolean value indicating if the specified {@link File} has a matching hash value.
     *
     * @param file {@link File} to verify hash against
     *
     * @return true if specified {@link File} hash matches
     *
     * @throws NoSuchAlgorithmException if unable to find hash algorithm
     * @throws IOException              if unable to access or read file
     */
    public abstract boolean verifyFile( File file ) throws NoSuchAlgorithmException, IOException;
}


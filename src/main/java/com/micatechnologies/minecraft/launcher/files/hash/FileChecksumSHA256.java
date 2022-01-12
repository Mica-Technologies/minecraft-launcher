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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Basic class used to store the value of a SHA-256 hash for a {@link ManagedRemoteFile}.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class FileChecksumSHA256 extends FileChecksum
{
    /**
     * Constructor for an instance of {@link FileChecksum} with the specified SHA-256 hash.
     *
     * @param value SHA-256 hash value
     *
     * @since 1.0
     */
    public FileChecksumSHA256( String value ) {
        super( value );
    }

    /**
     * Verifies and returns a boolean value indicating if the specified {@link File} has a matching SHA-256 hash value.
     *
     * @param file {@link File} to verify SHA-256 hash against
     *
     * @return true if specified {@link File} SHA-256 hash matches
     *
     * @throws NoSuchAlgorithmException if unable to find SHA-256 hash algorithm
     * @throws IOException              if unable to access or read file
     */
    @Override
    public boolean verifyFile( File file ) throws NoSuchAlgorithmException, IOException {
        // Get message digest algorithm
        MessageDigest instance = MessageDigest.getInstance( "SHA-256" );

        return FileChecksumCalculator.calculate( instance, file ).equalsIgnoreCase( getValue() );
    }
}

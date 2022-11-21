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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;

/**
 * Utility class for calculating the checksum of files using a specified {@link MessageDigest} algorithm.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class FileChecksumCalculator
{
    /**
     * Calculates and returns the checksum of the specified {@link File} using the specified {@link MessageDigest}
     * algorithm.
     *
     * @param digest {@link MessageDigest} algorithm
     * @param file   file to calculate checksum for
     *
     * @return checksum of the specified {@link File}
     *
     * @throws IOException if unable to read file
     * @since 1.0
     */
    public static String calculate( MessageDigest digest, File file ) throws IOException {
        // Get file input stream for reading file contents
        FileInputStream fileInputStream = new FileInputStream( file );

        // Create byte array for reading file data in chunks
        byte[] fileByteArray = new byte[ 1024 ];
        int fileByteCount;

        // Read entire file data and update digest
        while ( ( fileByteCount = fileInputStream.read( fileByteArray ) ) != -1 ) {
            digest.update( fileByteArray, 0, fileByteCount );
        }

        // Close file input stream
        fileInputStream.close();

        // Get bytes of hash
        byte[] hashBytes = digest.digest();
        return new BigInteger( 1, hashBytes ).toString( 16 );
    }
}

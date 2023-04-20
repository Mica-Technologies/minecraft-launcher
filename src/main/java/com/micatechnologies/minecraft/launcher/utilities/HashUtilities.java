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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.files.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Class that provides utility methods and functionality for file hashing with SHA-1 and SHA-256.
 *
 * @author Mica Technologies
 * @version 1.1
 * @since 2.0
 */
public class HashUtilities
{
    /**
     * Calculates and returns the checksum of the specified file, calculated using the specified message digest
     * algorithm.
     *
     * @param digest message digest algorithm
     * @param file   file to get checksum
     *
     * @return file checksum
     *
     * @throws IOException if unable to calculate file checksum
     * @since 1.0
     */
    private static BigInteger getFileChecksum( MessageDigest digest, File file ) throws IOException {
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
        return new BigInteger( 1, hashBytes );
    }

    /**
     * Gets the SHA-1 checksum of the specified file.
     *
     * @param file file to get checksum
     *
     * @return file SHA-1 checksum
     *
     * @since 1.0
     */
    public static String getFileSHA1( File file ) {
        String checksum = null;
        try {
            BigInteger checksumBigInteger = getFileChecksum( MessageDigest.getInstance( "SHA-1" ), file );
            checksum = String.format( "%040x", checksumBigInteger );
        }
        catch ( IOException e ) {
            Logger.logError( "Unable to calculate the SHA-1 sum of " + file.getAbsolutePath() + "!" );
            e.printStackTrace();
        }
        catch ( NoSuchAlgorithmException e ) {
            Logger.logError( "The SHA-1 algorithm is not available to calculate the checksum of " +
                                     file.getAbsolutePath() +
                                     "!" );
            e.printStackTrace();
        }
        return checksum;
    }

    /**
     * Gets the SHA-256 checksum of the specified file.
     *
     * @param file file to get checksum
     *
     * @return file SHA-256 checksum
     *
     * @since 1.0
     */
    public static String getFileSHA256( File file ) {
        String checksum = null;
        try {
            BigInteger checksumBigInteger = getFileChecksum( MessageDigest.getInstance( "SHA-256" ), file );
            checksum = String.format( "%064x", checksumBigInteger );
        }
        catch ( IOException e ) {
            Logger.logError( "Unable to calculate the SHA-256 sum of " + file.getAbsolutePath() + "!" );
            e.printStackTrace();
        }
        catch ( NoSuchAlgorithmException e ) {
            Logger.logError( "The SHA-256 algorithm is not available to calculate the checksum of " +
                                     file.getAbsolutePath() +
                                     "!" );
            e.printStackTrace();
        }
        return checksum;
    }

    /**
     * Gets the MD5 checksum of the specified file.
     *
     * @param file file to get checksum
     *
     * @return file MD5 checksum
     *
     * @since 1.1
     */
    public static String getFileMD5( File file ) {
        String checksum = null;
        try {
            BigInteger checksumBigInteger = getFileChecksum( MessageDigest.getInstance( "MD5" ), file );
            checksum = String.format( "%032x", checksumBigInteger );
        }
        catch ( IOException e ) {
            Logger.logError( "Unable to calculate the MD5 sum of " + file.getAbsolutePath() + "!" );
            e.printStackTrace();
        }
        catch ( NoSuchAlgorithmException e ) {
            Logger.logError(
                    "The MD5 algorithm is not available to calculate the checksum of " + file.getAbsolutePath() + "!" );
            e.printStackTrace();
        }
        return checksum;
    }

    /**
     * Gets the SHA-1 checksum of the specified file and returns true if it matches the specified checksum.
     *
     * @param file     file to compare checksum
     * @param validSha checksum to compare with
     *
     * @return true if checksums match
     *
     * @since 1.0
     */
    public static boolean verifySHA1( File file, String validSha ) {
        boolean matches = false;

        if ( file.isFile() ) {
            String fileSha = getFileSHA1( file );
            matches = fileSha.equalsIgnoreCase( validSha );
        }

        return matches;
    }

    /**
     * Gets the SHA-256 checksum of the specified file and returns true if it matches the specified checksum.
     *
     * @param file     file to compare checksum
     * @param validSha checksum to compare with
     *
     * @return true if checksums match
     *
     * @since 1.0
     */
    public static boolean verifySHA256( File file, String validSha ) {
        boolean matches = false;

        if ( file.isFile() ) {
            String fileSha = getFileSHA256( file );
            matches = fileSha.equalsIgnoreCase( validSha );
        }

        return matches;
    }

    /**
     * Gets the MD5 checksum of the specified file and returns true if it matches the specified checksum.
     *
     * @param file     file to compare checksum
     * @param validMd5 checksum to compare with
     *
     * @return true if checksums match
     *
     * @since 1.1
     */
    public static boolean verifyMD5( File file, String validMd5 ) {
        boolean matches = false;

        if ( file.isFile() ) {
            String fileMda5 = getFileMD5( file );
            matches = fileMda5.equalsIgnoreCase( validMd5 );
        }

        return matches;
    }
}

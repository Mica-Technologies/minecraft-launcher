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
        // 256 KB buffer rather than 8 KB: hashing dominates a FULL-verify launch
        // (multi-hundred-MB Forge installers, minecraft.jar, mod JARs), and an 8 KB
        // read meant ~12,800 syscalls per 100 MB. The larger buffer is typically
        // 2-4x faster on SSDs for one-line cost.
        byte[] fileByteArray = new byte[ 256 * 1024 ];
        int fileByteCount;

        try ( FileInputStream fileInputStream = new FileInputStream( file ) ) {
            while ( ( fileByteCount = fileInputStream.read( fileByteArray ) ) != -1 ) {
                digest.update( fileByteArray, 0, fileByteCount );
            }
        }

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
            Logger.logThrowable( e );
        }
        catch ( NoSuchAlgorithmException e ) {
            Logger.logError( "The SHA-1 algorithm is not available to calculate the checksum of " +
                                     file.getAbsolutePath() +
                                     "!" );
            Logger.logThrowable( e );
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
            Logger.logThrowable( e );
        }
        catch ( NoSuchAlgorithmException e ) {
            Logger.logError( "The SHA-256 algorithm is not available to calculate the checksum of " +
                                     file.getAbsolutePath() +
                                     "!" );
            Logger.logThrowable( e );
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
            Logger.logThrowable( e );
        }
        catch ( NoSuchAlgorithmException e ) {
            Logger.logError(
                    "The MD5 algorithm is not available to calculate the checksum of " + file.getAbsolutePath() + "!" );
            Logger.logThrowable( e );
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
        if ( file.isFile() ) {
            String fileSha = getFileSHA1( file );
            return fileSha != null && constantTimeHexEquals( fileSha, validSha );
        }
        return false;
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
        if ( file.isFile() ) {
            String fileSha = getFileSHA256( file );
            return fileSha != null && constantTimeHexEquals( fileSha, validSha );
        }
        return false;
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
        if ( file.isFile() ) {
            String fileMd5 = getFileMD5( file );
            return fileMd5 != null && constantTimeHexEquals( fileMd5, validMd5 );
        }
        return false;
    }

    /**
     * Case-insensitive constant-time comparison of two hex-encoded hash strings.
     * {@link String#equalsIgnoreCase} short-circuits on the first byte difference;
     * a remote attacker who can observe verification timing could in principle
     * recover a hash byte-by-byte. Replacing with an XOR-accumulator pattern keeps
     * the comparison time independent of where the strings diverge.
     *
     * <p>Hex hashes are 7-bit ASCII, so a UTF-8 byte conversion is safe and the
     * length check on the resulting byte arrays is equivalent to comparing the
     * original strings character-by-character.
     */
    private static boolean constantTimeHexEquals( String a, String b )
    {
        if ( a == null || b == null ) {
            return false;
        }
        if ( a.length() != b.length() ) {
            return false;
        }
        // Normalize case for hex semantics, then walk both strings in lock-step.
        // Working at the char level (rather than getBytes) avoids any String
        // intermediate that might short-circuit.
        int diff = 0;
        for ( int i = 0; i < a.length(); i++ ) {
            char ca = Character.toLowerCase( a.charAt( i ) );
            char cb = Character.toLowerCase( b.charAt( i ) );
            diff |= ca ^ cb;
        }
        return diff == 0;
    }
}

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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Image-format helpers for the modpack image cache.
 *
 * <p>The launcher renders pack logos and backgrounds via JavaFX's
 * {@code Image} class, which natively decodes only BMP, GIF, JPEG, and PNG.
 * Modrinth's CDN serves most project icons as WebP (their upload pipeline
 * transcodes for size), and pack manifests can carry arbitrary image URLs
 * for custom logos/backgrounds — anything else we drop on disk renders as
 * a blank rectangle.
 *
 * <p>This class bridges the gap. {@link #isJavaFxDecodable(File)} is a
 * cheap magic-byte sniff for the four formats JavaFX understands, and
 * {@link #ensureJavaFxDecodable(File)} reads anything else through
 * {@link javax.imageio.ImageIO} (which has the TwelveMonkeys WebP reader
 * plugin on the classpath, so WebP inputs work out of the box) and rewrites
 * the file in PNG. The transformation is in-place so downstream code that
 * computes a SHA-1 off the file sees the post-transcode bytes — important
 * for the content-addressed image cache, which keys files by their hash.
 *
 * @since 2026.3
 */
public final class ImageFormatUtilities
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ImageFormatUtilities() { /* static-only */ }

    /**
     * Returns {@code true} if {@code file}'s first bytes match one of the
     * image formats JavaFX's {@code Image} class natively decodes — PNG,
     * JPEG, GIF, or BMP. Anything else (WebP, AVIF, TIFF, HEIC, garbage)
     * returns {@code false}. Missing / unreadable files also return
     * {@code false} so callers can use this as a single-shot "is this
     * directly displayable" guard without separate existence checks.
     *
     * <p>Sniffs magic bytes rather than extension because filename
     * extensions lie at every layer of this pipeline — Modrinth's CDN
     * URLs use {@code .webp} but our content-addressed cache stores
     * everything under {@code .png}, so the on-disk extension tells you
     * what the cache wants to call it, not what it actually is.
     *
     * @param file the file to check
     * @return true if the file's format is JavaFX-decodable, false otherwise
     */
    public static boolean isJavaFxDecodable( File file )
    {
        if ( file == null || !file.isFile() || file.length() < 8 ) return false;
        byte[] head = readHead( file, 12 );
        if ( head == null ) return false;
        return matchesJavaFxNativeMagic( head );
    }

    /**
     * Ensures {@code file} is in a format {@link javafx.scene.image.Image}
     * can decode. If the file already passes {@link #isJavaFxDecodable},
     * this is a no-op. Otherwise the file is read through
     * {@link ImageIO#read(File)} (which picks up WebP via the TwelveMonkeys
     * plugin on the classpath) and rewritten in PNG, overwriting the
     * original bytes.
     *
     * <p>Returns {@code true} when {@code file} is JavaFX-decodable
     * afterwards — either it already was, or the transcode succeeded.
     * Returns {@code false} when the file's bytes can't be recognized by
     * any ImageIO reader (corrupt download, unsupported format), in which
     * case the file is left untouched so the caller can inspect / delete
     * it as needed.
     *
     * @param file the file to ensure is JavaFX-decodable
     * @return true if the file is JavaFX-decodable after ensuring, false otherwise
     */
    public static boolean ensureJavaFxDecodable( File file )
    {
        if ( file == null || !file.isFile() ) return false;
        if ( isJavaFxDecodable( file ) ) return true;

        // ImageIO.read returns null for any input no registered reader claims;
        // it does NOT throw IIOException on unknown formats. Treat null as
        // "not transcodable" — the caller will fall through to its
        // bundled-default logic.
        BufferedImage img;
        try {
            img = ImageIO.read( file );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.imageFormat.readFailed", file, e.getMessage() ) );
            return false;
        }
        if ( img == null ) return false;

        // Write to a sibling temp file, then atomically replace so a partial
        // write can't leave the destination in a torn state. PNG output is
        // chosen because it's lossless (preserves the source's alpha and
        // exact colors) and JavaFX's most-tested decoder format.
        File tempOut = new File( file.getParentFile(), file.getName() + ".transcode.tmp" );
        try {
            boolean ok = ImageIO.write( img, "PNG", tempOut );
            if ( !ok ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.imageFormat.writeFailed", file ) );
                return false;
            }
            Files.move( tempOut.toPath(), file.toPath(),
                                StandardCopyOption.REPLACE_EXISTING );
            return true;
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.imageFormat.transcodeFailed", file, e.getMessage() ) );
            return false;
        }
        finally {
            // Clean up the temp file if the atomic move didn't already consume it.
            if ( tempOut.exists() ) {
                //noinspection ResultOfMethodCallIgnored
                tempOut.delete();
            }
        }
    }

    // ===== internals =====

    /**
     * Reads the first {@code n} bytes from the specified file.
     *
     * @param file the file to read from
     * @param n the number of bytes to read
     * @return an array containing the first {@code n} bytes, or null if an I/O error occurs
     */
    private static byte[] readHead( File file, int n )
    {
        byte[] buf = new byte[n];
        try ( InputStream is = new FileInputStream( file ) ) {
            int read = 0;
            while ( read < n ) {
                int got = is.read( buf, read, n - read );
                if ( got < 0 ) return null;
                read += got;
            }
            return buf;
        }
        catch ( IOException e ) {
            return null;
        }
    }

    /**
     * Checks if the given byte array matches the magic bytes of any JavaFX-native image format.
     *
     * @param head the byte array to check
     * @return true if the byte array matches a JavaFX-native image format, false otherwise
     */
    private static boolean matchesJavaFxNativeMagic( byte[] head )
    {
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ( head[0] == (byte) 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47 ) return true;
        // JPEG: FF D8 FF
        if ( head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF ) return true;
        // GIF87a / GIF89a: "GIF8"
        if ( head[0] == 0x47 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x38 ) return true;
        // BMP: "BM"
        if ( head[0] == 0x42 && head[1] == 0x4D ) return true;
        return false;
    }
}

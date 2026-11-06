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

package com.micatechnologies.minecraft.launcher.gui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.util.Map;

/**
 * A QR code as a JavaFX image: dark modules on a white tile with the standard four-module quiet
 * zone, so a phone can scan it on the dark theme too. Used for {@code microsoft.com/link} on the
 * sign-in-with-a-code screen.
 *
 * @since 2026.11
 */
final class QrCodeImage
{
    /** Light border around the code, in modules; scanners need it. */
    static final int QUIET_ZONE = 4;

    private static final int DARK = 0xFF000000;
    private static final int LIGHT = 0xFFFFFFFF;

    private QrCodeImage() { /* static-only */ }

    /**
     * The code's modules, {@code true} for dark, without the quiet zone.
     *
     * @param text what the code holds
     *
     * @return a square grid, indexed {@code [row][column]}
     *
     * @throws WriterException when the text can't be encoded
     */
    static boolean[][] modules( String text ) throws WriterException
    {
        BitMatrix matrix = new QRCodeWriter().encode( text, BarcodeFormat.QR_CODE, 0, 0,
                                                      Map.of( EncodeHintType.MARGIN, 0,
                                                              EncodeHintType.ERROR_CORRECTION,
                                                              ErrorCorrectionLevel.M ) );
        boolean[][] grid = new boolean[ matrix.getHeight() ][ matrix.getWidth() ];
        for ( int y = 0; y < matrix.getHeight(); y++ ) {
            for ( int x = 0; x < matrix.getWidth(); x++ ) {
                grid[ y ][ x ] = matrix.get( x, y );
            }
        }
        return grid;
    }

    /**
     * Renders the code with whole-pixel modules, as close to the target size as fits.
     *
     * @param text     what the code holds
     * @param targetPx the wanted width and height
     *
     * @return the image, or {@code null} when the text can't be encoded
     */
    static Image render( String text, int targetPx )
    {
        boolean[][] grid;
        try {
            grid = modules( text );
        }
        catch ( WriterException | IllegalArgumentException e ) {
            return null;
        }
        int modules = grid.length + 2 * QUIET_ZONE;
        int scale = Math.max( 1, targetPx / modules );
        int size = modules * scale;
        WritableImage image = new WritableImage( size, size );
        PixelWriter pixels = image.getPixelWriter();
        for ( int y = 0; y < size; y++ ) {
            int row = y / scale - QUIET_ZONE;
            for ( int x = 0; x < size; x++ ) {
                int col = x / scale - QUIET_ZONE;
                boolean dark = row >= 0 && col >= 0 && row < grid.length && col < grid.length && grid[ row ][ col ];
                pixels.setArgb( x, y, dark ? DARK : LIGHT );
            }
        }
        return image;
    }
}

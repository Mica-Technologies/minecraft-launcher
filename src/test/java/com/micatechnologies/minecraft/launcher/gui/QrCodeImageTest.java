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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrCodeImageTest
{
    @Test
    void encodesTheLinkAsASquareCodeWithFinderPatterns() throws Exception
    {
        boolean[][] grid = QrCodeImage.modules( "https://www.microsoft.com/link" );

        assertEquals( grid.length, grid[ 0 ].length, "square" );
        assertEquals( 0, ( grid.length - 21 ) % 4, "a QR version size: 21 + 4n" );
        // The top-left finder pattern: a dark 7x7 ring, a light ring inside, a dark 3x3 centre.
        for ( int i = 0; i < 7; i++ ) {
            assertTrue( grid[ 0 ][ i ] );
            assertTrue( grid[ 6 ][ i ] );
            assertTrue( grid[ i ][ 0 ] );
        }
        assertFalse( grid[ 1 ][ 1 ] );
        assertTrue( grid[ 3 ][ 3 ] );
        assertTrue( grid[ 0 ][ grid.length - 1 ], "top-right finder" );
        assertTrue( grid[ grid.length - 1 ][ 0 ], "bottom-left finder" );
        assertFalse( grid[ grid.length - 1 ][ grid.length - 1 ], "no finder bottom-right" );
    }
}

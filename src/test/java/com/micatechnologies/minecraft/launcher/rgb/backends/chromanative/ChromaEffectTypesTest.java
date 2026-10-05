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

package com.micatechnologies.minecraft.launcher.rgb.backends.chromanative;

import com.sun.jna.Memory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Size checks for the param blocks handed to {@code RzChromaSDK64.dll}. The SDK reads as many
 * bytes as the effect type's struct holds, whatever we allocated, so an undersized block is an
 * out-of-bounds read inside the launcher's process. The mousepad CUSTOM2 block was once 4 bytes
 * against the 80 the SDK copies, and the launcher died with an access violation in the Razer
 * DLL a few minutes to a few hours into a session.
 */
class ChromaEffectTypesTest
{
    private static final int COLOR = 0x00FF00AA;

    @Test
    void mousepadCustom2ParamHoldsEveryLed()
    {
        // CUSTOM_EFFECT_TYPE2 { RZCOLOR Color[MAX_LEDS2]; }, MAX_LEDS2 = 20.
        assertEquals( 6, ChromaEffectTypes.MOUSEPAD_STATIC, "CUSTOM2 is the effect type the param is sized for" );
        Memory m = ChromaEffectTypes.buildMousepadCustom2Param( COLOR );
        assertEquals( 20 * 4, m.size() );
        for ( int i = 0; i < 20; i++ ) {
            assertEquals( COLOR, m.getInt( i * 4L ), "LED " + i );
        }
    }

    @Test
    void keyboardCustomParamIsTheFullGrid()
    {
        int[][] grid = new int[ 6 ][ 22 ];
        grid[ 5 ][ 21 ] = COLOR;
        Memory m = ChromaEffectTypes.buildKeyboardCustomParam( grid );
        assertEquals( 6 * 22 * 4, m.size() );
        assertEquals( COLOR, m.getInt( ( 5 * 22 + 21 ) * 4L ) );
    }

    @Test
    void mouseStaticParamIsLedIdThenColor()
    {
        Memory m = ChromaEffectTypes.buildMouseStaticParam( COLOR );
        assertEquals( 8, m.size() );
        assertEquals( 0, m.getInt( 0 ) );
        assertEquals( COLOR, m.getInt( 4 ) );
    }

    @Test
    void staticParamIsOneColor()
    {
        Memory m = ChromaEffectTypes.buildStaticParam( COLOR );
        assertEquals( 4, m.size() );
        assertEquals( COLOR, m.getInt( 0 ) );
    }
}

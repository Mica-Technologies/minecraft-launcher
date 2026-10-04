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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interface scale: config values snap to an offered scale, and popups get a scaled stylesheet
 * only away from 100 %.
 *
 * @since 2026.10
 */
class UiScaleTest
{
    @Test
    void snapsToTheNearestOfferedScale()
    {
        assertEquals( 100, UiScale.nearest( 100 ) );
        assertEquals( 50, UiScale.nearest( 10 ) );
        assertEquals( 150, UiScale.nearest( 400 ) );
        assertEquals( 125, UiScale.nearest( 120 ) );
        assertEquals( 75, UiScale.nearest( 80 ) );
    }

    @Test
    void noPopupStylesheetAtTheDesignedSize()
    {
        assertNull( UiScale.popupStylesheet( 1.0 ) );
    }

    @Test
    void popupStylesheetScalesTextAndPadding()
    {
        String uri = UiScale.popupStylesheet( 1.5 );
        assertTrue( uri.startsWith( "data:text/css;base64," ) );
        String css = new String( Base64.getDecoder().decode( uri.substring( "data:text/css;base64,".length() ) ),
                                 StandardCharsets.UTF_8 );
        assertTrue( css.contains( ".root .tooltip" ), css );
        assertTrue( css.contains( "-fx-font-size: 18.0px" ), "12 px tooltip text at 150 %: " + css );
        assertTrue( css.contains( "-fx-font-size: 21.0px" ), "14 px menu text at 150 %: " + css );
    }
}

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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link FilterOptionLabels}: every option id the Home and Browse dropdowns carry
 * must resolve to a real label. A missing key would show the raw key (e.g.
 * {@code filter.type.vanilla.beta}) in the menu, which no compile step catches.
 */
class FilterOptionLabelsTest
{
    /** The ids both screens use, by prefix. Keep in step with the TYPE_/STATUS_/SORT_ constants. */
    private static final Map< String, List< String > > IDS = Map.of(
            FilterOptionLabels.TYPE, List.of( "all", "modpacks", "vanilla", "vanilla.release", "vanilla.snapshot",
                                              "vanilla.beta", "vanilla.alpha", "forge", "neoforge", "fabric" ),
            FilterOptionLabels.STATUS, List.of( "all", "installed", "available" ),
            FilterOptionLabels.SORT, List.of( "default", "nameAz", "nameZa", "releaseDate", "lastPlayed",
                                              "mostPlayed", "recentUpdate" ) );

    @Test
    void everyOptionIdHasALabel()
    {
        IDS.forEach( ( prefix, ids ) -> {
            var converter = FilterOptionLabels.converter( prefix );
            for ( String id : ids ) {
                assertNotEquals( FilterOptionLabels.key( prefix, id ), converter.toString( id ),
                                 "missing label for " + prefix + id );
            }
        } );
    }

    @Test
    void aNullSelectionRendersEmpty()
    {
        assertEquals( "", FilterOptionLabels.converter( FilterOptionLabels.SORT ).toString( null ) );
        assertNull( FilterOptionLabels.converter( FilterOptionLabels.SORT ).fromString( "Default" ) );
    }
}

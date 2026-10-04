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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Running Games uptime clock zero-pads minutes and seconds ("1:03", not "1:3", which the old
 * game console showed).
 *
 * @since 2026.10
 */
class GameSessionPaneUptimeTest
{
    @Test
    void padsSecondsBelowTen()
    {
        assertEquals( LocalizationManager.format( "session.uptime", "1:03" ), GameSessionPane.formatDuration( 63_000 ) );
        assertEquals( LocalizationManager.format( "session.uptime", "0:07" ), GameSessionPane.formatDuration( 7_000 ) );
    }

    @Test
    void padsMinutesAndSecondsOnceThereAreHours()
    {
        assertEquals( LocalizationManager.format( "session.uptime", "1:05:09" ),
                      GameSessionPane.formatDuration( 3_600_000 + 5 * 60_000 + 9_000 ) );
    }

    @Test
    void keepsTwoDigitValuesAsTheyAre()
    {
        assertEquals( LocalizationManager.format( "session.uptime", "12:34" ),
                      GameSessionPane.formatDuration( 12 * 60_000 + 34_000 ) );
        assertEquals( LocalizationManager.format( "session.uptime", "0:00" ), GameSessionPane.formatDuration( 0 ) );
    }
}

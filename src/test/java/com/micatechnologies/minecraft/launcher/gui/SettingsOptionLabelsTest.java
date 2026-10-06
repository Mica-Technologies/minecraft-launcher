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

import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SettingsOptionLabels}: every theme and JVM preset the launcher offers must have
 * a label, and anything without one must show as it is rather than as a raw key.
 */
class SettingsOptionLabelsTest
{
    @Test
    void everyThemeHasALabel()
    {
        for ( String theme : ConfigConstants.ALLOWED_THEMES ) {
            String key = SettingsOptionLabels.THEME_KEYS.get( theme );
            assertTrue( key != null, "no label key for theme " + theme );
            assertNotEquals( key, SettingsOptionLabels.theme( theme ), "missing label for " + key );
        }
    }

    @Test
    void everyJvmPresetHasALabel()
    {
        var converter = SettingsOptionLabels.converter( SettingsOptionLabels.JVM_PRESET_KEYS );
        for ( String preset : ConfigConstants.JVM_PRESET_NAMES ) {
            String key = SettingsOptionLabels.JVM_PRESET_KEYS.get( preset );
            assertTrue( key != null, "no label key for preset " + preset );
            assertEquals( LocalizationManager.get( key ), converter.toString( preset ) );
            assertNotEquals( key, converter.toString( preset ), "missing label for " + key );
        }
    }

    @Test
    void itemsWithoutAKeyShowAsTheyAre()
    {
        // The JVM picker's trailing "Custom" entry is already localized text.
        String custom = LocalizationManager.get( "settings.jvmPreset.custom" );
        assertEquals( custom, SettingsOptionLabels.converter( SettingsOptionLabels.JVM_PRESET_KEYS )
                                                  .toString( custom ) );
        assertEquals( "Retro", SettingsOptionLabels.theme( "Retro" ) );
        assertEquals( "", SettingsOptionLabels.theme( null ) );
        assertNull( SettingsOptionLabels.converter( SettingsOptionLabels.THEME_KEYS ).fromString( "Dark" ) );
    }
}

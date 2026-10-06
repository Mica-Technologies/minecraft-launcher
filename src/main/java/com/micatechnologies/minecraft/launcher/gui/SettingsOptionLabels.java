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
import javafx.util.StringConverter;

import java.util.Map;

/**
 * Display labels for the theme and JVM-preset choices.
 *
 * <p>The saved config keeps the English identifiers ({@code "Native (Mica)"},
 * {@code "Low Memory"}), and the dropdowns and menus still carry them as their items; only the
 * text shown is looked up here. Changing the identifiers themselves would break every saved
 * config.</p>
 *
 * @since 2026.10
 */
public final class SettingsOptionLabels
{
    /** Label key for each theme identifier. */
    static final Map< String, String > THEME_KEYS = Map.of(
            ConfigConstants.THEME_AUTOMATIC, "settings.theme.option.automatic",
            ConfigConstants.THEME_DARK, "settings.theme.option.dark",
            ConfigConstants.THEME_LIGHT, "settings.theme.option.light",
            ConfigConstants.THEME_BLUE_GRAY, "settings.theme.option.blueGray",
            ConfigConstants.THEME_ORANGE_PURPLE, "settings.theme.option.orangePurple",
            ConfigConstants.THEME_CREEPER, "settings.theme.option.creeper",
            ConfigConstants.THEME_NATIVE, "settings.theme.option.native" );

    /** Label key for each JVM-preset identifier. */
    static final Map< String, String > JVM_PRESET_KEYS = Map.of(
            ConfigConstants.JVM_PRESET_PERFORMANCE, "settings.jvmPreset.option.performance",
            ConfigConstants.JVM_PRESET_LOW_MEMORY, "settings.jvmPreset.option.lowMemory",
            ConfigConstants.JVM_PRESET_DEBUG, "settings.jvmPreset.option.debug",
            ConfigConstants.JVM_PRESET_NONE, "settings.jvmPreset.option.none" );

    private SettingsOptionLabels() { }

    /**
     * The label for a theme identifier.
     *
     * @param theme the theme identifier
     *
     * @return its localized label, or the identifier itself when it isn't a known theme
     *
     * @since 2026.10
     */
    public static String theme( String theme )
    {
        return label( THEME_KEYS, theme );
    }

    /**
     * A converter that shows each dropdown item through {@code keys}. Items with no key (such as
     * the JVM picker's already-localized "Custom" entry) are shown as they are.
     *
     * @param keys {@link #THEME_KEYS} or {@link #JVM_PRESET_KEYS}
     *
     * @return the converter
     *
     * @since 2026.10
     */
    static StringConverter< String > converter( Map< String, String > keys )
    {
        return new StringConverter<>()
        {
            @Override
            public String toString( String id )
            {
                return label( keys, id );
            }

            @Override
            public String fromString( String label )
            {
                // The dropdowns aren't editable, so there is never typed text to parse.
                return null;
            }
        };
    }

    private static String label( Map< String, String > keys, String id )
    {
        if ( id == null ) {
            return "";
        }
        String key = keys.get( id );
        return key == null ? id : LocalizationManager.get( key );
    }
}

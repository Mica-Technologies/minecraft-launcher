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

package com.micatechnologies.minecraft.launcher.tui;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.graphics.Theme;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.utilities.OsThemeUtilities;

/**
 * Light / dark color scheme for the terminal UI, derived from the launcher's existing theme setting
 * so the CLI matches the user's chosen look: the explicit "Light" theme → light CLI; "Native" /
 * "Automatic" → follow the OS appearance; everything else (Dark + the colourful themes) → dark CLI.
 *
 * @since 2026.6
 */
final class TuiTheme
{
    private TuiTheme() { /* static-only */ }

    /** @return whether the CLI should render light-on-light (true) or light text on a dark bg (false). */
    static boolean isLight()
    {
        String theme = ConfigManager.getTheme();
        if ( ConfigConstants.THEME_LIGHT.equals( theme ) ) {
            return true;
        }
        if ( ConfigConstants.THEME_NATIVE.equals( theme ) || ConfigConstants.THEME_AUTOMATIC.equals( theme ) ) {
            return !OsThemeUtilities.isOsDark();
        }
        return false;   // Dark + colourful themes all map to a dark terminal.
    }

    /** Background fill color for the GUI's empty space (mostly hidden behind the full-screen window). */
    static TextColor background()
    {
        return isLight() ? TextColor.ANSI.WHITE : TextColor.ANSI.BLACK;
    }

    /** Builds the Lanterna theme (base / editable / selected / gui-background colors). */
    static Theme build()
    {
        if ( isLight() ) {
            return SimpleTheme.makeTheme(
                    true,
                    TextColor.ANSI.BLACK, TextColor.ANSI.WHITE,   // base fg / bg
                    TextColor.ANSI.BLACK, TextColor.ANSI.WHITE,   // editable fg / bg
                    TextColor.ANSI.WHITE, TextColor.ANSI.BLUE,    // selected fg / bg
                    TextColor.ANSI.WHITE );                       // gui background
        }
        return SimpleTheme.makeTheme(
                true,
                TextColor.ANSI.WHITE, TextColor.ANSI.BLACK,
                TextColor.ANSI.WHITE, TextColor.ANSI.BLACK,
                TextColor.ANSI.BLACK, TextColor.ANSI.WHITE,
                TextColor.ANSI.BLACK );
    }
}

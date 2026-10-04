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

import com.micatechnologies.minecraft.launcher.files.Logger;
import javafx.scene.text.Font;

import java.io.InputStream;

/**
 * Registers the fonts the launcher ships, so text renders the same on every OS instead of falling
 * back to each system's own UI font. Inter (SIL Open Font License, {@code fonts/inter/OFL.txt}):
 * Regular, Italic, SemiBold and Bold.
 *
 * <p>JavaFX only tells regular from bold within a family; Inter's SemiBold registers as its own
 * family, "Inter SemiBold". So {@code ui-base.css} asks for weight 600 by naming that family, and
 * weights other than regular, 600 and bold aren't used (TypeScaleTest).
 *
 * @since 2026.10
 */
public final class BundledFonts
{
    /** Faces under {@code /fonts/}: Inter for the UI, JetBrains Mono for game logs. JavaFX uses only
     *  the first family a CSS font list names, so the log's monospace font is bundled rather than
     *  left to whatever the system has. */
    private static final String[] FACES = { "inter/Inter-Regular", "inter/Inter-Italic", "inter/Inter-SemiBold",
                                            "inter/Inter-Bold", "jetbrains-mono/JetBrainsMono-Regular" };

    private static volatile boolean loaded;

    private BundledFonts()
    {
    }

    /**
     * Registers the bundled faces once; later calls return immediately. Call before the first
     * stylesheet is applied, so CSS that names the fonts finds them.
     */
    public static void ensureLoaded()
    {
        if ( loaded ) {
            return;
        }
        synchronized ( BundledFonts.class ) {
            if ( loaded ) {
                return;
            }
            for ( String face : FACES ) {
                try ( InputStream in = BundledFonts.class.getResourceAsStream( "/fonts/" + face + ".ttf" ) ) {
                    if ( in == null || Font.loadFont( in, 12 ) == null ) {
                        Logger.logWarningSilent( "Bundled font not loaded: " + face );
                    }
                }
                catch ( Exception e ) {
                    Logger.logWarningSilent( "Bundled font not loaded: " + face, e );
                }
            }
            loaded = true;
        }
    }
}

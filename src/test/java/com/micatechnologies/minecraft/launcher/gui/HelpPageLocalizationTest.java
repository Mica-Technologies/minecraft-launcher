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

import com.micatechnologies.minecraft.launcher.consts.localization.SupportedLocales;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers which help page the help window loads for the UI language, and that every translated
 * page has an English original (so a renamed topic can't leave orphaned translations).
 *
 * @since 2026.10
 */
class HelpPageLocalizationTest
{
    private static final String PAGE = "help/settings.html";

    @Test
    void prefersTheLanguageAndRegionFolder()
    {
        Set< String > present = Set.of( "help/pt_BR/settings.html", "help/pt/settings.html" );
        assertEquals( "help/pt_BR/settings.html",
                      MCLauncherHelpWindow.localizedPagePath( PAGE, Locale.of( "pt", "BR" ), present::contains ) );
    }

    @Test
    void fallsBackToTheLanguageFolderThenEnglish()
    {
        assertEquals( "help/de/settings.html",
                      MCLauncherHelpWindow.localizedPagePath( PAGE, Locale.GERMANY,
                                                              Set.of( "help/de/settings.html" )::contains ) );
        assertEquals( PAGE, MCLauncherHelpWindow.localizedPagePath( PAGE, Locale.JAPANESE, p -> false ) );
        assertEquals( PAGE, MCLauncherHelpWindow.localizedPagePath( PAGE, Locale.ROOT, p -> true ) );
    }

    @Test
    void everyTranslatedPageHasAnEnglishOriginal()
    {
        List< String > orphans = new ArrayList<>();
        for ( SupportedLocales.Entry entry : SupportedLocales.ENTRIES ) {
            String folder = "help/" + entry.tag().replace( '-', '_' ) + "/";
            for ( HelpTopic topic : HelpTopic.values() ) {
                String english = topic.getResourcePath();
                String translated = folder + english.substring( english.lastIndexOf( '/' ) + 1 );
                boolean hasTranslation = getClass().getClassLoader().getResource( translated ) != null;
                boolean hasEnglish = getClass().getClassLoader().getResource( english ) != null;
                if ( hasTranslation && !hasEnglish ) {
                    orphans.add( translated );
                }
            }
        }
        assertTrue( orphans.isEmpty(), "Translated help pages without an English original: " + orphans );
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for the Settings "Save &amp; Restart" button's
 * decision seam — the language-dropdown-index → override-tag mapping and the
 * "is a language change pending?" comparison that drives button visibility.
 *
 * <p>The dropdown's item 0 is the localized "Use OS Language" sentinel and item
 * {@code i > 0} is {@code SupportedLocales.ENTRIES[i - 1]}; the mapping works by
 * index so it never depends on the sentinel's translated label.</p>
 *
 * <p>No FX scene required: both methods under test are static and side-effect
 * free, so this runs in CI alongside the other seam tests. The interactive
 * show/hide behaviour is covered separately by the opt-in
 * {@code SettingsLanguageButtonFxTest} (TestFX).</p>
 */
class MCLauncherSettingsLanguageLogicTest
{
    /** Dropdown index of the OS-default sentinel. */
    private static final int OS_DEFAULT = 0;

    /** Dropdown index of the supported locale with the given tag. */
    private static int indexOf( String tag )
    {
        for ( int i = 0; i < SupportedLocales.ENTRIES.size(); i++ ) {
            if ( SupportedLocales.ENTRIES.get( i ).tag().equals( tag ) ) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException( tag );
    }

    @Test
    void overrideTagForEveryLocaleIndexResolvesToItsTag()
    {
        for ( int i = 0; i < SupportedLocales.ENTRIES.size(); i++ ) {
            SupportedLocales.Entry entry = SupportedLocales.ENTRIES.get( i );
            assertEquals( entry.tag(), MCLauncherSettingsGui.overrideTagForIndex( i + 1 ),
                          "index " + ( i + 1 ) + " should map to tag " + entry.tag() );
        }
    }

    @Test
    void overrideTagForOsDefaultOrOutOfRangeIsEmpty()
    {
        assertEquals( "", MCLauncherSettingsGui.overrideTagForIndex( OS_DEFAULT ) );
        assertEquals( "", MCLauncherSettingsGui.overrideTagForIndex( -1 ) );
        assertEquals( "", MCLauncherSettingsGui.overrideTagForIndex( SupportedLocales.ENTRIES.size() + 1 ) );
    }

    @Test
    void noChangeWhenSelectionMatchesSavedOverride()
    {
        // Saved French, French still selected → nothing pending.
        assertFalse( MCLauncherSettingsGui.isLanguageChangePending( indexOf( "fr" ), "fr" ) );
        // Saved OS-default (empty), OS-default still selected → nothing pending.
        assertFalse( MCLauncherSettingsGui.isLanguageChangePending( OS_DEFAULT, "" ) );
    }

    @Test
    void changeWhenSelectionDiffersFromSavedOverride()
    {
        // Saved OS-default, user picked French → pending.
        assertTrue( MCLauncherSettingsGui.isLanguageChangePending( indexOf( "fr" ), "" ) );
        // Saved French, user switched back to OS-default → pending.
        assertTrue( MCLauncherSettingsGui.isLanguageChangePending( OS_DEFAULT, "fr" ) );
        // Saved French, user picked German → pending.
        assertTrue( MCLauncherSettingsGui.isLanguageChangePending( indexOf( "de" ), "fr" ) );
    }

    @Test
    void savedOverrideComparisonIsCaseInsensitiveAndNullSafe()
    {
        // BCP-47 tags compare case-insensitively (config could carry "FR").
        assertFalse( MCLauncherSettingsGui.isLanguageChangePending( indexOf( "fr" ), "FR" ) );
        // A null saved override behaves like OS-default ("").
        assertFalse( MCLauncherSettingsGui.isLanguageChangePending( OS_DEFAULT, null ) );
        assertTrue( MCLauncherSettingsGui.isLanguageChangePending( indexOf( "fr" ), null ) );
    }
}

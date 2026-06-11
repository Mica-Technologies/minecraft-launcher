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
 * decision seam — the language-dropdown-label → override-tag mapping and the
 * "is a language change pending?" comparison that drives button visibility.
 *
 * <p>No FX scene required: both methods under test are static and side-effect
 * free, so this runs in CI alongside the other seam tests. The interactive
 * show/hide behaviour is covered separately by the opt-in
 * {@code SettingsLanguageButtonFxTest} (TestFX).</p>
 */
class MCLauncherSettingsLanguageLogicTest
{
    // The OS-default dropdown entry is a composite label ("Use OS Language
    // (detected: ...)"), never one of the SupportedLocales display names, so
    // any non-entry string stands in for it here.
    private static final String OS_DEFAULT_LABEL = SupportedLocales.OS_DEFAULT_LABEL_PREFIX + " (detected: English)";

    @Test
    void overrideTagForKnownDisplayResolvesToItsTag()
    {
        for ( SupportedLocales.Entry entry : SupportedLocales.ENTRIES ) {
            assertEquals( entry.tag(),
                    MCLauncherSettingsGui.overrideTagForDisplay( entry.displayName() ),
                    "display \"" + entry.displayName() + "\" should map to tag " + entry.tag() );
        }
    }

    @Test
    void overrideTagForOsDefaultOrUnknownOrNullIsEmpty()
    {
        assertEquals( "", MCLauncherSettingsGui.overrideTagForDisplay( OS_DEFAULT_LABEL ) );
        assertEquals( "", MCLauncherSettingsGui.overrideTagForDisplay( "Some Language We Don't Ship" ) );
        assertEquals( "", MCLauncherSettingsGui.overrideTagForDisplay( null ) );
    }

    @Test
    void noChangeWhenSelectionMatchesSavedOverride()
    {
        // Saved French, French still selected → nothing pending.
        assertFalse( MCLauncherSettingsGui.isLanguageChangePending( "Français", "fr" ) );
        // Saved OS-default (empty), OS-default still selected → nothing pending.
        assertFalse( MCLauncherSettingsGui.isLanguageChangePending( OS_DEFAULT_LABEL, "" ) );
    }

    @Test
    void changeWhenSelectionDiffersFromSavedOverride()
    {
        // Saved OS-default, user picked French → pending.
        assertTrue( MCLauncherSettingsGui.isLanguageChangePending( "Français", "" ) );
        // Saved French, user switched back to OS-default → pending.
        assertTrue( MCLauncherSettingsGui.isLanguageChangePending( OS_DEFAULT_LABEL, "fr" ) );
        // Saved French, user picked German → pending.
        assertTrue( MCLauncherSettingsGui.isLanguageChangePending( "Deutsch", "fr" ) );
    }

    @Test
    void savedOverrideComparisonIsCaseInsensitiveAndNullSafe()
    {
        // BCP-47 tags compare case-insensitively (config could carry "FR").
        assertFalse( MCLauncherSettingsGui.isLanguageChangePending( "Français", "FR" ) );
        // A null saved override behaves like OS-default ("").
        assertFalse( MCLauncherSettingsGui.isLanguageChangePending( OS_DEFAULT_LABEL, null ) );
        assertTrue( MCLauncherSettingsGui.isLanguageChangePending( "Français", null ) );
    }
}

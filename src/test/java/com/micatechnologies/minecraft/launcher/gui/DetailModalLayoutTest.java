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

import com.micatechnologies.minecraft.launcher.game.modpack.GameOptionalMod;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.micatechnologies.minecraft.launcher.gui.DetailModalLayout.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The detail window's layout decisions: which tabs each kind of pack gets, the Optional mods
 * summary counts, and the sticky tab bar's scroll arithmetic.
 *
 * @since 2026.11
 */
class DetailModalLayoutTest
{
    @Test
    void installedModpackGetsEveryTaskTabInOrder()
    {
        assertEquals( List.of( TAB_OVERVIEW, TAB_MODS, TAB_FILES, TAB_ACTIVITY, TAB_SETTINGS ),
                      tabsFor( false, false, true, true ) );
    }

    @Test
    void problemsFollowOverview()
    {
        assertEquals( List.of( TAB_OVERVIEW, TAB_PROBLEMS, TAB_MODS, TAB_FILES, TAB_ACTIVITY, TAB_SETTINGS ),
                      tabsFor( true, false, true, true ) );
    }

    @Test
    void vanillaInstallHasNoModsTab()
    {
        assertEquals( List.of( TAB_OVERVIEW, TAB_FILES, TAB_ACTIVITY, TAB_SETTINGS ),
                      tabsFor( false, true, true, true ) );
    }

    @Test
    void packWithoutFolderKeepsModsForItsOptionalModsAndSettings()
    {
        assertEquals( List.of( TAB_OVERVIEW, TAB_MODS, TAB_SETTINGS ), tabsFor( false, false, false, true ) );
    }

    @Test
    void packWithNeitherFolderNorSettingsKeyIsOverviewOnly()
    {
        assertEquals( List.of( TAB_OVERVIEW ), tabsFor( false, false, false, false ) );
        assertEquals( List.of( TAB_OVERVIEW ), tabsFor( false, true, false, false ) );
    }

    @Test
    void packWithFolderButNoSettingsKeyHasNoSettingsTab()
    {
        assertEquals( List.of( TAB_OVERVIEW, TAB_MODS, TAB_FILES, TAB_ACTIVITY ), tabsFor( false, false, true, false ) );
    }

    private static GameOptionalMod mod( String local, boolean client )
    {
        return new GameOptionalMod( local, "https://example.invalid/" + local, local, null, client, false, "",
                                    false, null, null );
    }

    @Test
    void countsOnlyClientSideModsOnceEach()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", true ), mod( "b.jar", true ),
                                                mod( "server-only.jar", false ), mod( "a.jar", true ) );
        OptionalCounts counts = optionalCounts( mods, Set.of( "a.jar", "server-only.jar" ) );
        assertEquals( 2, counts.available() );
        assertEquals( 1, counts.on() );
    }

    @Test
    void countsUseTheModIdentityKey()
    {
        GameOptionalMod upper = mod( "Shaders.JAR", true );
        OptionalCounts counts = optionalCounts( List.of( upper ), Set.of( upper.key() ) );
        assertEquals( new OptionalCounts( 1, 1 ), counts );
    }

    @Test
    void countsTolerateMissingInputs()
    {
        assertEquals( new OptionalCounts( 0, 0 ), optionalCounts( null, Set.of() ) );
        assertEquals( new OptionalCounts( 1, 0 ), optionalCounts( List.of( mod( "a.jar", true ) ), null ) );
    }

    @Test
    void tabBarPinsOnceScrolledToIt()
    {
        assertFalse( isPinned( 0, 300 ) );
        assertFalse( isPinned( 299, 300 ) );
        assertTrue( isPinned( 299.6, 300 ) );
        assertTrue( isPinned( 300, 300 ) );
        assertTrue( isPinned( 900, 300 ) );
        // A bar at the very top of the content (no hero laid out yet) is not "pinned".
        assertFalse( isPinned( 0, 0 ) );
    }

    @Test
    void pinnedBarFollowsTheScroll()
    {
        assertEquals( 0, pinTranslation( 120, 300 ) );
        assertEquals( 0, pinTranslation( 300, 300 ) );
        assertEquals( 250, pinTranslation( 550, 300 ) );
    }

    @Test
    void tabSwitchKeepsPinnedBarPinnedAtTheNewTabsTop()
    {
        assertEquals( 300, offsetAfterTabSwitch( 1200, 300 ) );
        assertEquals( 300, offsetAfterTabSwitch( 300, 300 ) );
    }

    @Test
    void tabSwitchLeavesAnUnpinnedPositionAlone()
    {
        assertEquals( 0, offsetAfterTabSwitch( 0, 300 ) );
        assertEquals( 180, offsetAfterTabSwitch( 180, 300 ) );
        assertEquals( 0, offsetAfterTabSwitch( -5, 300 ) );
    }
}

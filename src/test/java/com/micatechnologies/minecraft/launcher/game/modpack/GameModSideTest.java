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


package com.micatechnologies.minecraft.launcher.game.modpack;

import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Side gating for {@link GameMod}. The post-sync existence check relies on
 * {@link GameMod#isRequiredFor(GameMode)} to skip mods that were never downloaded for the
 * active side; if it disagreed with {@link GameMod#updateLocalFile(GameMode, LaunchPrepareContext)}, a client launch
 * of a pack with server-only mods would abort with those mods reported as "missing".
 */
class GameModSideTest
{
    private static GameMod mod( boolean clientReq, boolean serverReq )
    {
        return new GameMod( "test", "https://example.invalid/test.jar", null,
                            ManagedGameFile.ManagedGameFileHashType.SHA1, "test.jar", clientReq, serverReq );
    }

    @Test
    void serverOnlyModIsNotRequiredOnClient()
    {
        GameMod serverOnly = mod( false, true );
        assertFalse( serverOnly.isRequiredFor( GameMode.CLIENT ) );
        assertTrue( serverOnly.isRequiredFor( GameMode.SERVER ) );
    }

    @Test
    void clientOnlyModIsNotRequiredOnServer()
    {
        GameMod clientOnly = mod( true, false );
        assertTrue( clientOnly.isRequiredFor( GameMode.CLIENT ) );
        assertFalse( clientOnly.isRequiredFor( GameMode.SERVER ) );
    }

    @Test
    void sharedModIsRequiredOnBothSides()
    {
        GameMod shared = mod( true, true );
        assertTrue( shared.isRequiredFor( GameMode.CLIENT ) );
        assertTrue( shared.isRequiredFor( GameMode.SERVER ) );
    }

    @Test
    void skippedSideReportsSuccessWithoutDownloading() throws Exception
    {
        // updateLocalFile must be a successful no-op on the side the mod doesn't apply to;
        // the bogus URL would fail if it actually tried to fetch.
        assertTrue( mod( false, true ).updateLocalFile( GameMode.CLIENT, LaunchPrepareContext.NONE ) );
    }
}

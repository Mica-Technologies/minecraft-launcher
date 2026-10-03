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

import net.hycrafthd.minecraft_authenticator.login.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the two identity seams a per-pack account override depends on: the stable key a
 * pack's settings are stored under, and the sign-in arguments a launch hands Minecraft.
 */
class LaunchIdentityTest
{
    @Test
    void aManifestPackIsKeyedByItsManifestUrl()
    {
        assertEquals( "https://example.com/pack.json",
                      GameModPack.settingsKey( "https://example.com/pack.json", false, null ) );
    }

    @Test
    void aVanillaInstallIsKeyedByItsVersionNotItsLocalizedName()
    {
        assertEquals( "vanilla:1.20.4", GameModPack.settingsKey( null, true, "1.20.4" ) );
        assertEquals( "vanilla:1.20.4", GameModPack.settingsKey( "  ", true, "1.20.4" ) );
    }

    @Test
    void aPackWithNothingStableHasNoKey()
    {
        assertNull( GameModPack.settingsKey( null, false, null ) );
        assertNull( GameModPack.settingsKey( "", true, "" ) );
    }

    @Test
    void launchArgumentsComeFromTheChosenUser()
    {
        GameModPackLauncher.AuthArguments args = GameModPackLauncher.AuthArguments.of(
                new User( "uuid-b", "Blake", "token-b", "msa", "xuid", "client" ) );
        assertEquals( "Blake", args.playerName() );
        assertEquals( "uuid-b", args.uuid() );
        assertEquals( "token-b", args.accessToken() );
        assertEquals( "token:token-b:uuid-b", args.session() );
    }

    @Test
    void missingUserFieldsBecomeEmptyArgumentsNotTheWordNull()
    {
        GameModPackLauncher.AuthArguments args = GameModPackLauncher.AuthArguments.of(
                new User( null, null, null, null, null, null ) );
        assertEquals( "", args.playerName() );
        assertEquals( "token::", args.session() );
    }
}

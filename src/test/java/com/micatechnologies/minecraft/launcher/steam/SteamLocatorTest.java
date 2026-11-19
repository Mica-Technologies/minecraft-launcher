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

package com.micatechnologies.minecraft.launcher.steam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SteamLocatorTest
{
    /** A made-up loginusers.vdf in Steam's layout: two accounts, the second signed in last. */
    private static final String LOGIN_USERS = """
            "users"
            {
            \t"76561197960265829"
            \t{
            \t\t"AccountName"\t\t"first_account"
            \t\t"PersonaName"\t\t"First \\"Player\\""
            \t\t"MostRecent"\t\t"0"
            \t\t"Timestamp"\t\t"1700000000"
            \t}
            \t// a comment
            \t"76561197960265930"
            \t{
            \t\t"AccountName"\t\t"second_account"
            \t\t"PersonaName"\t\t"Second"
            \t\t"Timestamp"\t\t"1710000000"
            \t}
            }
            """;

    @Test
    void accountsComeFromUserdataWithNamesFromLoginUsers()
    {
        List< SteamLocator.Account > accounts = SteamLocator.accounts( List.of( 101L, 202L, 303L ), LOGIN_USERS );

        assertEquals( 3, accounts.size() );
        assertEquals( 202L, accounts.get( 0 ).accountId(), "the latest sign-in first" );
        assertEquals( "Second", accounts.get( 0 ).personaName() );
        assertEquals( "First \"Player\"", accounts.get( 1 ).personaName() );
        assertEquals( 303L, accounts.get( 2 ).accountId(), "known only from userdata" );
        assertEquals( "", accounts.get( 2 ).personaName() );
        assertEquals( 202L, SteamLocator.mostRecent( accounts ).orElseThrow().accountId() );
    }

    @Test
    void steamsMostRecentMarkWinsOverTheTimestamp()
    {
        String marked = LOGIN_USERS.replace( "\"MostRecent\"\t\t\"0\"", "\"MostRecent\"\t\t\"1\"" );

        assertEquals( 101L, SteamLocator.mostRecent( SteamLocator.accounts( List.of( 101L, 202L ), marked ) )
                                        .orElseThrow().accountId() );
    }

    @Test
    void withoutLoginUsersAccountsAreStillListed()
    {
        assertEquals( 1, SteamLocator.accounts( List.of( 101L ), "" ).size() );
        assertEquals( 1, SteamLocator.accounts( List.of( 101L ), "\"users\" { \"broken" ).size() );
        assertTrue( SteamLocator.mostRecent( List.of() ).isEmpty() );
    }

    @Test
    void readsAccountsFromAnInstall( @TempDir Path root ) throws IOException
    {
        Files.createDirectories( root.resolve( "userdata/101/config" ) );
        Files.createDirectories( root.resolve( "userdata/0" ) );
        Files.createDirectories( root.resolve( "userdata/ac" ) );
        Files.createDirectories( root.resolve( "config" ) );
        Files.writeString( root.resolve( "config/loginusers.vdf" ), LOGIN_USERS );

        List< SteamLocator.Account > accounts = SteamLocator.accounts( new SteamLocator.SteamInstall( root, false ) );

        assertEquals( 1, accounts.size(), "0 and non-numeric folders are not accounts" );
        assertEquals( "First \"Player\"", accounts.get( 0 ).personaName() );
        assertEquals( root.resolve( "userdata/101/config" ),
                      new SteamLocator.SteamInstall( root, false ).config( 101L ) );
    }

    @Test
    void candidateFoldersPerPlatform()
    {
        Path home = Path.of( "/home/player" );

        List< SteamLocator.SteamInstall > linux = SteamLocator.candidates( "Linux", home, Map.of(), null );
        assertEquals( home.resolve( ".steam/steam" ), linux.get( 0 ).root() );
        assertTrue( linux.stream().anyMatch( i -> i.flatpak()
                                                  && i.root().toString().contains( SteamLocator.FLATPAK_ID ) ) );

        Path mac = SteamLocator.candidates( "Mac OS X", home, Map.of(), null ).get( 0 ).root();
        assertEquals( home.resolve( "Library/Application Support/Steam" ), mac );

        List< SteamLocator.SteamInstall > windows = SteamLocator.candidates(
                "Windows 11", home, Map.of( "ProgramFiles(x86)", "C:\\Program Files (x86)" ), "D:\\Games\\Steam" );
        assertEquals( 2, windows.size() );
        assertEquals( "D:\\Games\\Steam", windows.get( 0 ).root().toString() );
    }

    @Test
    void parsesTheRegistrysSteamPath()
    {
        String output = "\r\nHKEY_CURRENT_USER\\Software\\Valve\\Steam\r\n"
                        + "    SteamPath    REG_SZ    c:/program files (x86)/steam\r\n\r\n";

        assertEquals( "c:\\program files (x86)\\steam", SteamLocator.parseRegSteamPath( output ) );
        assertNull( SteamLocator.parseRegSteamPath( "ERROR: The system was unable to find the specified key" ) );
    }

    @Test
    void textVdfHandlesUnquotedTokensAndEmptyInput()
    {
        Map< String, Object > parsed = SteamLocator.TextVdf.parse( "a b\n\"c\" { d \"e\" }" );

        assertEquals( "b", parsed.get( "a" ) );
        assertEquals( Map.of( "d", "e" ), parsed.get( "c" ) );
        assertTrue( SteamLocator.TextVdf.parse( null ).isEmpty() );
    }
}

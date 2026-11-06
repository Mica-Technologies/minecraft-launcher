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

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SteamShortcutsTest
{
    private static final String APPIMAGE = "/home/player/Applications/Mica-Minecraft-Launcher.AppImage";

    private static SteamShortcuts.Entry launcher()
    {
        return new SteamShortcuts.Entry( "Mica Minecraft Launcher", APPIMAGE, "/home/player", "",
                                         SteamShortcuts.launcherOptions() );
    }

    private static SteamShortcuts.Entry pack( String name )
    {
        return new SteamShortcuts.Entry( name, APPIMAGE, "/home/player", "",
                                         SteamShortcuts.packOptions( name, false ) );
    }

    /** A user's own shortcut, which must never change. */
    private static BinaryVdf.Node userShortcut()
    {
        return new BinaryVdf.Node().put( "appid", -5 ).put( "AppName", "Other Game" )
                                   .put( "Exe", "\"/usr/bin/other\"" ).put( "LaunchOptions", "--fullscreen" );
    }

    @Test
    void appIdsMatchSteamsFormula()
    {
        // Reference values from an independent CRC-32 (Python zlib) of quoted Exe + AppName.
        int id = SteamShortcuts.appId( "\"" + APPIMAGE + "\"", "Mica Minecraft Launcher" );
        assertEquals( -1_088_579_415, id );
        assertEquals( 3_206_387_881L, SteamShortcuts.gridId( id ) );
        assertEquals( "13771331087219294208", Long.toUnsignedString( SteamShortcuts.gameId( id ) ) );

        int windows = SteamShortcuts.appId(
                "\"C:\\Program Files\\Mica Minecraft Launcher\\Mica Minecraft Launcher.exe\"", "Skyward Isles" );
        assertEquals( 3_892_403_335L, SteamShortcuts.gridId( windows ) );
        assertEquals( "16717745026699886592", Long.toUnsignedString( SteamShortcuts.gameId( windows ) ) );
    }

    @Test
    void addsAnEntryWithEveryFieldSteamWrites()
    {
        BinaryVdf.Node root = SteamShortcuts.emptyRoot();

        int id = SteamShortcuts.upsert( root, launcher() );

        BinaryVdf.Node entry = root.getNode( "shortcuts" ).getNode( "0" );
        assertEquals( id, entry.getInt( "appid", 0 ) );
        assertEquals( "\"" + APPIMAGE + "\"", entry.getString( "Exe" ) );
        assertEquals( "\"/home/player\"", entry.getString( "StartDir" ) );
        assertEquals( "--steam", entry.getString( "LaunchOptions" ) );
        assertEquals( 1, entry.getInt( "AllowOverlay", 0 ) );
        assertNotNull( entry.getNode( "tags" ) );
        assertEquals( 17, entry.entries().size() );
    }

    @Test
    void updatingKeepsTheIdAndTheFieldsSteamOwns()
    {
        BinaryVdf.Node root = SteamShortcuts.emptyRoot();
        int id = SteamShortcuts.upsert( root, pack( "Skyward Isles" ) );
        BinaryVdf.Node entry = root.getNode( "shortcuts" ).getNode( "0" );
        entry.put( "LastPlayTime", 1_700_000_000 );

        int again = SteamShortcuts.upsert( root, new SteamShortcuts.Entry(
                "Skyward Isles", "/home/player/Moved.AppImage", "/home/player", "/icon.png",
                SteamShortcuts.packOptions( "Skyward Isles", false ) ) );

        assertEquals( id, again, "the AppImage moved but the entry keeps its ID, art and play time" );
        assertEquals( 1, root.getNode( "shortcuts" ).entries().size() );
        assertEquals( "\"/home/player/Moved.AppImage\"", entry.getString( "Exe" ) );
        assertEquals( "/icon.png", entry.getString( "icon" ) );
        assertEquals( 1_700_000_000, entry.getInt( "LastPlayTime", 0 ) );
    }

    @Test
    void theUsersOwnShortcutsAreLeftAlone() throws IOException
    {
        BinaryVdf.Node root = SteamShortcuts.emptyRoot();
        root.getNode( "shortcuts" ).put( "0", userShortcut() );
        byte[] before = BinaryVdf.write( root );

        SteamShortcuts.upsert( root, launcher() );
        SteamShortcuts.remove( root, SteamShortcuts.launcherOptions() );

        assertEquals( new String( before, java.nio.charset.StandardCharsets.ISO_8859_1 ),
                      new String( BinaryVdf.write( root ), java.nio.charset.StandardCharsets.ISO_8859_1 ) );
        assertEquals( 0, SteamShortcuts.remove( root, "--fullscreen" ), "only --steam entries are ours" );
    }

    @Test
    void aGapInTheNumberingNeverCostsAnEntry()
    {
        BinaryVdf.Node root = SteamShortcuts.emptyRoot();
        root.getNode( "shortcuts" ).put( "0", userShortcut() ).put( "2", userShortcut().put( "AppName", "Two" ) );

        SteamShortcuts.upsert( root, launcher() );

        BinaryVdf.Node shortcuts = root.getNode( "shortcuts" );
        assertEquals( 3, shortcuts.entries().size() );
        assertEquals( "Other Game", shortcuts.getNode( "0" ).getString( "AppName" ) );
        assertEquals( "Two", shortcuts.getNode( "1" ).getString( "AppName" ) );
        assertEquals( "Mica Minecraft Launcher", shortcuts.getNode( "2" ).getString( "AppName" ) );
    }

    @Test
    void removingRenumbersTheRest()
    {
        BinaryVdf.Node root = SteamShortcuts.emptyRoot();
        int alto = SteamShortcuts.upsert( root, pack( "Alto" ) );
        SteamShortcuts.upsert( root, pack( "Skyward Isles" ) );

        assertEquals( alto, SteamShortcuts.remove( root, SteamShortcuts.packOptions( "Alto", false ) ) );

        BinaryVdf.Node shortcuts = root.getNode( "shortcuts" );
        assertEquals( 1, shortcuts.entries().size() );
        assertEquals( "Skyward Isles", shortcuts.getNode( "0" ).getString( "AppName" ) );
        assertNull( SteamShortcuts.find( root, SteamShortcuts.packOptions( "Alto", false ) ) );
    }

    @Test
    void packNamesAreQuotedForTheirPlatform()
    {
        assertEquals( "--steam \"Skyward Isles\"", SteamShortcuts.packOptions( "Skyward Isles", false ) );
        assertEquals( "--steam \"A \\\"B\\\" \\$HOME \\`x\\` \\\\\"",
                      SteamShortcuts.packOptions( "A \"B\" $HOME `x` \\", false ), "shell" );
        assertEquals( "--steam \"A \\\"B\\\" $HOME\"", SteamShortcuts.packOptions( "A \"B\" $HOME", true ),
                      "Windows command line" );
        assertEquals( "--steam \"AB\"", SteamShortcuts.packOptions( "A\nB", false ), "line breaks dropped" );
    }
}

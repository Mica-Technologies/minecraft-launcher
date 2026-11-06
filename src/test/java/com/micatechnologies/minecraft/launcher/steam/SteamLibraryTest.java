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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The library operations against a made-up Steam folder, plus the artwork and process helpers. */
class SteamLibraryTest
{
    private static final Path PROGRAM = Path.of( "/home/player/Applications/Mica-Minecraft-Launcher.AppImage" );

    private static SteamLibrary.Target target( Path root )
    {
        return new SteamLibrary.Target( new SteamLocator.SteamInstall( root, false ),
                                        new SteamLocator.Account( 101L, "", 0, true ) );
    }

    private static BufferedImage image( int w, int h )
    {
        return new BufferedImage( w, h, BufferedImage.TYPE_INT_ARGB );
    }

    private static SteamLibrary.Request pack( String name )
    {
        return new SteamLibrary.Request( name, SteamShortcuts.packOptions( name, false ), image( 400, 200 ),
                                         image( 1280, 720 ) );
    }

    @Test
    void addsEntriesWithArtworkToAnAccountWithoutShortcutsYet( @TempDir Path root ) throws IOException
    {
        SteamLibrary.Target target = target( root );

        SteamLibrary.add( target, PROGRAM, List.of( pack( "Skyward Isles" ) ) );

        BinaryVdf.Node shortcut = BinaryVdf.read( Files.readAllBytes( target.shortcutsFile() ) )
                                           .getNode( "shortcuts" ).getNode( "0" );
        long grid = SteamShortcuts.gridId( shortcut.getInt( "appid", 0 ) );
        assertEquals( "\"" + PROGRAM + "\"", shortcut.getString( "Exe" ) );
        assertEquals( "\"" + PROGRAM.getParent() + "\"", shortcut.getString( "StartDir" ) );
        assertEquals( target.gridFolder().resolve( grid + "_icon.png" ).toString(), shortcut.getString( "icon" ) );
        for ( Path file : SteamArtwork.files( target.gridFolder(), grid ) ) {
            assertTrue( Files.exists( file ), file.getFileName().toString() );
        }
        assertEquals( 600, ImageIO.read( target.gridFolder().resolve( grid + "p.png" ).toFile() ).getWidth() );
        assertEquals( 620, ImageIO.read( target.gridFolder().resolve( grid + "_hero.png" ).toFile() ).getHeight() );
        assertEquals( SteamArtwork.LOGO_POSITION_JSON,
                      Files.readString( target.gridFolder().resolve( grid + ".json" ) ) );
        assertTrue( SteamLibrary.has( target, SteamShortcuts.packOptions( "Skyward Isles", false ) ) );
    }

    @Test
    void addingAgainUpdatesAndRemovingCleansUp( @TempDir Path root ) throws IOException
    {
        SteamLibrary.Target target = target( root );
        SteamLibrary.add( target, PROGRAM, List.of( pack( "Alto" ), SteamLibrary.launcherRequest() ) );
        SteamLibrary.add( target, PROGRAM, List.of( pack( "Alto" ) ) );

        BinaryVdf.Node shortcuts = BinaryVdf.read( Files.readAllBytes( target.shortcutsFile() ) )
                                            .getNode( "shortcuts" );
        assertEquals( 2, shortcuts.entries().size() );
        long grid = SteamShortcuts.gridId( shortcuts.getNode( "0" ).getInt( "appid", 0 ) );

        assertTrue( SteamLibrary.remove( target, SteamShortcuts.packOptions( "Alto", false ) ) );
        assertFalse( SteamLibrary.remove( target, SteamShortcuts.packOptions( "Alto", false ) ) );
        assertFalse( Files.exists( target.gridFolder().resolve( grid + "p.png" ) ) );
        assertTrue( SteamLibrary.has( target, SteamShortcuts.launcherOptions() ) );
        assertTrue( Files.exists( target.shortcutsFile().resolveSibling( "shortcuts.vdf.bak" ) ) );
    }

    @Test
    void aDamagedShortcutListIsNeverOverwritten( @TempDir Path root ) throws IOException
    {
        SteamLibrary.Target target = target( root );
        Files.createDirectories( target.shortcutsFile().getParent() );
        byte[] damaged = { 0x00, 's', 'h' };
        Files.write( target.shortcutsFile(), damaged );

        assertThrows( IOException.class, () -> SteamLibrary.add( target, PROGRAM, List.of( pack( "Alto" ) ) ) );
        assertArrayEquals( damaged, Files.readAllBytes( target.shortcutsFile() ) );
    }

    @Test
    void withoutALogoNoLogoFileIsWritten( @TempDir Path grid ) throws IOException
    {
        Path icon = SteamArtwork.write( grid, 42L, null, null );

        assertFalse( Files.exists( grid.resolve( "42_logo.png" ) ) );
        assertFalse( Files.exists( grid.resolve( "42.json" ) ) );
        assertEquals( 256, ImageIO.read( icon.toFile() ).getWidth() );
        assertEquals( 920, ImageIO.read( grid.resolve( "42.png" ).toFile() ).getWidth() );
    }

    @Test
    void fittingKeepsTheShape()
    {
        BufferedImage fitted = SteamArtwork.fit( image( 400, 100 ), 960, 540 );

        assertEquals( 960, fitted.getWidth() );
        assertEquals( 240, fitted.getHeight() );
    }

    @Test
    void recognisesTheSteamClientOnEveryPlatform()
    {
        assertTrue( SteamProcess.isSteamClient( "/home/player/.local/share/Steam/ubuntu12_32/steam" ) );
        assertTrue( SteamProcess.isSteamClient( "C:\\Program Files (x86)\\Steam\\steam.exe" ) );
        assertTrue( SteamProcess.isSteamClient( "/Applications/Steam.app/Contents/MacOS/steam_osx" ) );
        assertFalse( SteamProcess.isSteamClient( "/home/player/.local/share/Steam/ubuntu12_64/steamwebhelper" ) );
        assertFalse( SteamProcess.isSteamClient( "/usr/bin/steam-runtime-launcher-service" ) );
    }

    @Test
    void gameModeAlwaysCountsAsRunning()
    {
        assertTrue( SteamProcess.inGameMode( Map.of( "XDG_CURRENT_DESKTOP", "gamescope" ) ) );
        assertFalse( SteamProcess.inGameMode( Map.of( "XDG_CURRENT_DESKTOP", "KDE" ) ) );
        assertFalse( SteamProcess.inGameMode( Map.of() ) );
    }

    @Test
    void closesAndStartsSteamWithThePlatformsCommand()
    {
        SteamLocator.SteamInstall nativeSteam = new SteamLocator.SteamInstall( Path.of( "/home/player/.steam/steam" ),
                                                                               false );
        SteamLocator.SteamInstall flatpak = new SteamLocator.SteamInstall( Path.of( "/x" ), true );

        assertEquals( List.of( "steam", "-shutdown" ), SteamProcess.shutdownCommand( "Linux", nativeSteam ) );
        assertEquals( List.of( "flatpak", "run", SteamLocator.FLATPAK_ID, "-shutdown" ),
                      SteamProcess.shutdownCommand( "Linux", flatpak ) );
        assertEquals( List.of( "osascript", "-e", "quit app \"Steam\"" ),
                      SteamProcess.shutdownCommand( "Mac OS X", nativeSteam ) );
        assertEquals( "-shutdown", SteamProcess.shutdownCommand( "Windows 11", nativeSteam ).get( 1 ) );
        assertTrue( SteamProcess.shutdownCommand( "Windows 11", nativeSteam ).get( 0 ).endsWith( "steam.exe" ) );
        assertEquals( List.of( "steam" ), SteamProcess.startCommand( "Linux", nativeSteam ) );
        assertEquals( List.of( "open", "-a", "Steam" ), SteamProcess.startCommand( "Mac OS X", nativeSteam ) );
    }
}

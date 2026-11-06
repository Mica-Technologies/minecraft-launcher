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

import org.apache.commons.lang3.SystemUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tells whether Steam is running, and closes and starts it (plan decision D13). Steam reads its
 * shortcut list only when it starts and writes it back when it exits, so entries can only be
 * added while it is closed.
 *
 * <p>In the Steam Deck's Game Mode Steam is the whole interface and can't be closed from here:
 * there the user is asked to do it once from Desktop Mode.
 *
 * @since 2026.11
 */
public final class SteamProcess
{
    /** Environment the launcher's own runtimes set, which Steam must not inherit. */
    private static final List< String > LAUNCHER_ONLY_ENV = List.of( "APPIMAGE", "APPDIR", "ARGV0", "OWD",
                                                                      "MMCL_STARTER_PATH", "MMCL_STARTER_STATE" );

    private SteamProcess() { /* static-only */ }

    /**
     * Whether this is the Steam Deck's Game Mode (gamescope), where Steam can't be closed.
     *
     * @param env the environment
     *
     * @return {@code true} in Game Mode
     *
     * @since 2026.11
     */
    public static boolean inGameMode( Map< String, String > env )
    {
        return "gamescope".equalsIgnoreCase( env.getOrDefault( "XDG_CURRENT_DESKTOP", "" ) );
    }

    /**
     * Whether a Steam client process is running for this user.
     *
     * @return {@code true} when one is
     *
     * @since 2026.11
     */
    public static boolean isRunning()
    {
        if ( inGameMode( System.getenv() ) ) {
            return true;
        }
        return ProcessHandle.allProcesses()
                            .anyMatch( p -> p.info().command().map( SteamProcess::isSteamClient ).orElse( false ) );
    }

    /**
     * Whether a process's executable is the Steam client: {@code steam} (Linux, also the Flatpak's
     * inside its sandbox), {@code steam.exe} (Windows) or {@code steam_osx} (macOS). Helpers such
     * as {@code steamwebhelper} don't count: they exit with the client.
     *
     * @param command the executable's path
     *
     * @return {@code true} for the client
     *
     * @since 2026.11
     */
    static boolean isSteamClient( String command )
    {
        String name = command.replace( '\\', '/' );
        name = name.substring( name.lastIndexOf( '/' ) + 1 ).toLowerCase( Locale.ROOT );
        return name.equals( "steam" ) || name.equals( "steam.exe" ) || name.equals( "steam_osx" );
    }

    /**
     * The command that asks Steam to close itself cleanly (saving its files).
     *
     * @param osName  the {@code os.name} property
     * @param install the installation
     *
     * @return the command
     *
     * @since 2026.11
     */
    static List< String > shutdownCommand( String osName, SteamLocator.SteamInstall install )
    {
        String os = osName.toLowerCase( Locale.ROOT );
        if ( os.startsWith( "windows" ) ) {
            return List.of( install.root().resolve( "steam.exe" ).toString(), "-shutdown" );
        }
        if ( os.startsWith( "mac" ) ) {
            return List.of( "osascript", "-e", "quit app \"Steam\"" );
        }
        return install.flatpak() ? List.of( "flatpak", "run", SteamLocator.FLATPAK_ID, "-shutdown" )
                                 : List.of( "steam", "-shutdown" );
    }

    /**
     * The command that starts Steam again.
     *
     * @param osName  the {@code os.name} property
     * @param install the installation
     *
     * @return the command
     *
     * @since 2026.11
     */
    static List< String > startCommand( String osName, SteamLocator.SteamInstall install )
    {
        String os = osName.toLowerCase( Locale.ROOT );
        if ( os.startsWith( "windows" ) ) {
            return List.of( install.root().resolve( "steam.exe" ).toString() );
        }
        if ( os.startsWith( "mac" ) ) {
            return List.of( "open", "-a", "Steam" );
        }
        return install.flatpak() ? List.of( "flatpak", "run", SteamLocator.FLATPAK_ID ) : List.of( "steam" );
    }

    /**
     * Asks Steam to close and waits until it has.
     *
     * @param install   the installation
     * @param timeoutMs how long to wait
     *
     * @return {@code true} once no Steam client runs
     *
     * @throws InterruptedException when interrupted while waiting
     * @since 2026.11
     */
    public static boolean shutdown( SteamLocator.SteamInstall install, long timeoutMs ) throws InterruptedException
    {
        try {
            spawn( shutdownCommand( SystemUtils.OS_NAME, install ) );
        }
        catch ( IOException e ) {
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while ( System.currentTimeMillis() < deadline ) {
            if ( !isRunning() ) {
                // Steam writes its files on the way out; give the last write a moment.
                Thread.sleep( 1_000 );
                return true;
            }
            Thread.sleep( 500 );
        }
        return !isRunning();
    }

    /**
     * Starts Steam, without the launcher's own environment.
     *
     * @param install the installation
     *
     * @throws IOException when it can't be started
     * @since 2026.11
     */
    public static void start( SteamLocator.SteamInstall install ) throws IOException
    {
        spawn( startCommand( SystemUtils.OS_NAME, install ) );
    }

    private static void spawn( List< String > command ) throws IOException
    {
        ProcessBuilder builder = new ProcessBuilder( command );
        LAUNCHER_ONLY_ENV.forEach( builder.environment()::remove );
        builder.redirectOutput( ProcessBuilder.Redirect.DISCARD ).redirectError( ProcessBuilder.Redirect.DISCARD );
        builder.directory( new File( System.getProperty( "user.home" ) ) );
        builder.start();
    }
}

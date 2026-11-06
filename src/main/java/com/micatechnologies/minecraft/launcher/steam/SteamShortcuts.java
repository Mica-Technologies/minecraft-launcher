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

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * The non-Steam-game entries in a {@code shortcuts.vdf} tree: what the launcher adds, how Steam
 * derives their IDs, and how the launcher finds its own entries again.
 *
 * <p>An entry is the launcher's when its {@code LaunchOptions} start with
 * {@value LauncherConstants#PROGRAM_ARG_STEAM}; the whole {@code LaunchOptions} string identifies
 * which entry (the launcher, or one modpack). Every other entry is the user's and is never
 * changed.
 *
 * @since 2026.11
 */
public final class SteamShortcuts
{
    private SteamShortcuts() { /* static-only */ }

    /**
     * One entry the launcher wants in the Steam library.
     *
     * @param appName       the title Steam shows
     * @param exe           the program to run, unquoted
     * @param startDir      its working folder, unquoted
     * @param icon          an absolute path to an icon image, or {@code ""}
     * @param launchOptions the arguments, starting with {@value LauncherConstants#PROGRAM_ARG_STEAM}
     *
     * @since 2026.11
     */
    public record Entry( String appName, String exe, String startDir, String icon, String launchOptions )
    {
    }

    /**
     * The launch options for the launcher's own entry.
     *
     * @return {@code --steam}
     *
     * @since 2026.11
     */
    public static String launcherOptions()
    {
        return LauncherConstants.PROGRAM_ARG_STEAM;
    }

    /**
     * The launch options for a modpack's entry: {@code --steam "<name>"}, quoted for the platform
     * Steam runs it on (a shell on Linux and macOS, a Windows command line on Windows).
     *
     * @param packName the modpack's name
     * @param windows  whether Steam runs on Windows
     *
     * @return the options
     *
     * @since 2026.11
     */
    public static String packOptions( String packName, boolean windows )
    {
        StringBuilder quoted = new StringBuilder( "\"" );
        for ( char c : packName.toCharArray() ) {
            if ( c == '\n' || c == '\r' || c == 0 ) {
                continue;
            }
            boolean escape = windows ? c == '"' : c == '"' || c == '\\' || c == '$' || c == '`';
            if ( escape ) {
                quoted.append( '\\' );
            }
            quoted.append( c );
        }
        return LauncherConstants.PROGRAM_ARG_STEAM + " " + quoted.append( '"' );
    }

    /**
     * Steam's quoting of {@code Exe} and {@code StartDir}.
     *
     * @param path the unquoted path
     *
     * @return the path in double quotes
     *
     * @since 2026.11
     */
    public static String quoted( String path )
    {
        return "\"" + path + "\"";
    }

    /**
     * The 32-bit app ID Steam derives for a shortcut: CRC-32 of the stored (quoted) {@code Exe}
     * followed by {@code AppName}, with the top bit set. Stored as a signed integer in the file.
     *
     * @param storedExe the {@code Exe} value exactly as stored, quotes included
     * @param appName   the {@code AppName} value
     *
     * @return the ID, as the file stores it
     *
     * @since 2026.11
     */
    public static int appId( String storedExe, String appName )
    {
        CRC32 crc = new CRC32();
        crc.update( ( storedExe + appName ).getBytes( StandardCharsets.UTF_8 ) );
        return ( int ) ( crc.getValue() | 0x80000000L );
    }

    /**
     * The ID used in the artwork file names in {@code config/grid}: the app ID, unsigned.
     *
     * @param appId the ID as stored
     *
     * @return the unsigned value
     *
     * @since 2026.11
     */
    public static long gridId( int appId )
    {
        return appId & 0xFFFFFFFFL;
    }

    /**
     * The 64-bit ID {@code steam://rungameid/} takes for a shortcut.
     *
     * @param appId the ID as stored
     *
     * @return the game ID
     *
     * @since 2026.11
     */
    public static long gameId( int appId )
    {
        return ( gridId( appId ) << 32 ) | 0x02000000L;
    }

    /**
     * An empty {@code shortcuts.vdf} tree, for an account that has none yet.
     *
     * @return the root
     *
     * @since 2026.11
     */
    public static BinaryVdf.Node emptyRoot()
    {
        return new BinaryVdf.Node().put( "shortcuts", new BinaryVdf.Node() );
    }

    /**
     * Adds the entry, or updates the launcher's existing entry with the same launch options. An
     * existing entry keeps its app ID (and so its artwork and play time) and every field the
     * launcher does not set.
     *
     * @param root  the file's root
     * @param entry the entry
     *
     * @return the entry's app ID, as stored
     *
     * @since 2026.11
     */
    public static int upsert( BinaryVdf.Node root, Entry entry )
    {
        BinaryVdf.Node shortcuts = shortcuts( root );
        BinaryVdf.Node existing = find( shortcuts, entry.launchOptions() );
        String exe = quoted( entry.exe() );
        if ( existing != null ) {
            existing.put( "AppName", entry.appName() )
                    .put( "Exe", exe )
                    .put( "StartDir", quoted( entry.startDir() ) )
                    .put( "icon", entry.icon() );
            int appId = existing.getInt( "appid", 0 );
            if ( appId == 0 ) {
                appId = appId( exe, entry.appName() );
                existing.put( "appid", appId );
            }
            return appId;
        }
        int appId = appId( exe, entry.appName() );
        BinaryVdf.Node shortcut = new BinaryVdf.Node()
                .put( "appid", appId )
                .put( "AppName", entry.appName() )
                .put( "Exe", exe )
                .put( "StartDir", quoted( entry.startDir() ) )
                .put( "icon", entry.icon() )
                .put( "ShortcutPath", "" )
                .put( "LaunchOptions", entry.launchOptions() )
                .put( "IsHidden", 0 )
                .put( "AllowDesktopConfig", 1 )
                .put( "AllowOverlay", 1 )
                .put( "OpenVR", 0 )
                .put( "Devkit", 0 )
                .put( "DevkitGameID", "" )
                .put( "DevkitOverrideAppID", 0 )
                .put( "LastPlayTime", 0 )
                .put( "FlatpakAppID", "" )
                .put( "tags", new BinaryVdf.Node() );
        // Appended under a key no entry uses, then numbered: a file whose keys have gaps
        // ("0", "2") must not have an entry overwritten.
        shortcuts.put( "mmcl-new", shortcut );
        renumber( shortcuts );
        return appId;
    }

    /**
     * Removes the launcher's entry with these launch options.
     *
     * @param root          the file's root
     * @param launchOptions the entry's launch options
     *
     * @return the removed entry's app ID, or {@code 0} when there was none
     *
     * @since 2026.11
     */
    public static int remove( BinaryVdf.Node root, String launchOptions )
    {
        BinaryVdf.Node shortcuts = shortcuts( root );
        for ( BinaryVdf.Entry entry : shortcuts.entries() ) {
            if ( entry.value() instanceof BinaryVdf.Node shortcut && isOurs( shortcut, launchOptions ) ) {
                int appId = shortcut.getInt( "appid", 0 );
                shortcuts.remove( entry.key() );
                renumber( shortcuts );
                return appId;
            }
        }
        return 0;
    }

    /**
     * The launcher's entry with these launch options, if present.
     *
     * @param root          the file's root
     * @param launchOptions the entry's launch options
     *
     * @return the entry, or {@code null}
     *
     * @since 2026.11
     */
    public static BinaryVdf.Node find( BinaryVdf.Node root, String launchOptions )
    {
        BinaryVdf.Node shortcuts = root.getNode( "shortcuts" ) != null ? root.getNode( "shortcuts" ) : root;
        for ( BinaryVdf.Entry entry : shortcuts.entries() ) {
            if ( entry.value() instanceof BinaryVdf.Node shortcut && isOurs( shortcut, launchOptions ) ) {
                return shortcut;
            }
        }
        return null;
    }

    private static boolean isOurs( BinaryVdf.Node shortcut, String launchOptions )
    {
        String options = shortcut.getString( "LaunchOptions" );
        return options != null && options.startsWith( LauncherConstants.PROGRAM_ARG_STEAM )
               && options.equals( launchOptions );
    }

    private static BinaryVdf.Node shortcuts( BinaryVdf.Node root )
    {
        BinaryVdf.Node shortcuts = root.getNode( "shortcuts" );
        if ( shortcuts == null ) {
            shortcuts = new BinaryVdf.Node();
            root.put( "shortcuts", shortcuts );
        }
        return shortcuts;
    }

    /** Steam keys entries "0", "1", ... in order; keeps that true after an add or remove. */
    private static void renumber( BinaryVdf.Node shortcuts )
    {
        List< BinaryVdf.Value > values = new ArrayList<>();
        for ( BinaryVdf.Entry entry : shortcuts.entries() ) {
            values.add( entry.value() );
        }
        for ( BinaryVdf.Entry entry : new ArrayList<>( shortcuts.entries() ) ) {
            shortcuts.remove( entry.key() );
        }
        for ( int i = 0; i < values.size(); i++ ) {
            shortcuts.put( String.valueOf( i ), values.get( i ) );
        }
    }
}

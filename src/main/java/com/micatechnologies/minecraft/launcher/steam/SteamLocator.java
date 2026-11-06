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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Steam on this computer and the Steam accounts that have signed in to it.
 *
 * <p>Locations: Windows reads {@code SteamPath} from {@code HKCU\Software\Valve\Steam} (falling
 * back to {@code Program Files (x86)\Steam}); macOS uses {@code ~/Library/Application Support/Steam};
 * Linux and the Steam Deck use {@code ~/.steam/steam} (usually a link to
 * {@code ~/.local/share/Steam}), Debian's and Snap's folders, and the Flatpak's under
 * {@code ~/.var/app/com.valvesoftware.Steam}. A folder counts when it holds {@code userdata}.
 *
 * <p>Accounts are the numeric folders in {@code userdata} (32-bit account IDs; {@code 0} is
 * not one), with names and sign-in times from {@code config/loginusers.vdf} where it lists them.
 *
 * @since 2026.11
 */
public final class SteamLocator
{
    /** The Steam Flatpak's application ID. */
    public static final String FLATPAK_ID = "com.valvesoftware.Steam";

    /** SteamID64 of account ID 0; an account ID is a SteamID64 minus this. */
    private static final long STEAM_ID64_BASE = 76_561_197_960_265_728L;

    private static final Pattern REG_SZ = Pattern.compile( "SteamPath\\s+REG_(?:EXPAND_)?SZ\\s+(.+)" );

    private SteamLocator() { /* static-only */ }

    /**
     * A Steam installation.
     *
     * @param root    the folder holding {@code userdata}
     * @param flatpak whether it is the Steam Flatpak (its shortcuts run inside its sandbox)
     *
     * @since 2026.11
     */
    public record SteamInstall( Path root, boolean flatpak )
    {
        /**
         * The folder of one account's configuration.
         *
         * @param accountId the account
         *
         * @return {@code userdata/<id>/config}
         */
        public Path config( long accountId )
        {
            return root.resolve( "userdata" ).resolve( Long.toString( accountId ) ).resolve( "config" );
        }
    }

    /**
     * A Steam account that has used this Steam.
     *
     * @param accountId   the 32-bit account ID (the {@code userdata} folder's name)
     * @param personaName the display name, or {@code ""} when unknown
     * @param timestamp   the last sign-in time (Unix seconds), or {@code 0} when unknown
     * @param mostRecent  whether Steam marked it as the most recent
     *
     * @since 2026.11
     */
    public record Account( long accountId, String personaName, long timestamp, boolean mostRecent )
    {
    }

    /**
     * The Steam installations on this computer, without duplicates (links resolved).
     *
     * @return the installations; empty when Steam is not installed
     *
     * @since 2026.11
     */
    public static List< SteamInstall > find()
    {
        Path home = Path.of( System.getProperty( "user.home" ) );
        String windowsSteamPath = SystemUtils.IS_OS_WINDOWS ? readWindowsSteamPath() : null;
        List< SteamInstall > found = new ArrayList<>();
        Set< Path > seen = new LinkedHashSet<>();
        for ( SteamInstall candidate : candidates( SystemUtils.OS_NAME, home, System.getenv(), windowsSteamPath ) ) {
            if ( !Files.isDirectory( candidate.root().resolve( "userdata" ) ) ) {
                continue;
            }
            Path real;
            try {
                real = candidate.root().toRealPath();
            }
            catch ( IOException e ) {
                real = candidate.root().toAbsolutePath().normalize();
            }
            if ( seen.add( real ) ) {
                found.add( new SteamInstall( real, candidate.flatpak() ) );
            }
        }
        return found;
    }

    /**
     * Where Steam may be installed, most likely first. Pure, for tests.
     *
     * @param osName           the {@code os.name} property
     * @param home             the user's home folder
     * @param env              the environment
     * @param windowsSteamPath the registry's {@code SteamPath}, or {@code null}
     *
     * @return the candidate folders
     *
     * @since 2026.11
     */
    static List< SteamInstall > candidates( String osName, Path home, Map< String, String > env,
                                            String windowsSteamPath )
    {
        String os = osName == null ? "" : osName.toLowerCase( Locale.ROOT );
        List< SteamInstall > list = new ArrayList<>();
        if ( os.startsWith( "windows" ) ) {
            addIfValid( list, windowsSteamPath, false );
            String programFiles = env.get( "ProgramFiles(x86)" );
            addIfValid( list, programFiles == null ? null : programFiles + "\\Steam", false );
        }
        else if ( os.startsWith( "mac" ) ) {
            list.add( new SteamInstall( home.resolve( "Library/Application Support/Steam" ), false ) );
        }
        else {
            list.add( new SteamInstall( home.resolve( ".steam/steam" ), false ) );
            list.add( new SteamInstall( home.resolve( ".local/share/Steam" ), false ) );
            list.add( new SteamInstall( home.resolve( ".steam/debian-installation" ), false ) );
            list.add( new SteamInstall( home.resolve( "snap/steam/common/.local/share/Steam" ), false ) );
            Path flatpak = home.resolve( ".var/app" ).resolve( FLATPAK_ID );
            list.add( new SteamInstall( flatpak.resolve( ".local/share/Steam" ), true ) );
            list.add( new SteamInstall( flatpak.resolve( ".steam/steam" ), true ) );
            list.add( new SteamInstall( flatpak.resolve( "data/Steam" ), true ) );
        }
        return list;
    }

    private static void addIfValid( List< SteamInstall > list, String path, boolean flatpak )
    {
        if ( path == null || path.isBlank() ) {
            return;
        }
        try {
            list.add( new SteamInstall( Path.of( path.trim() ), flatpak ) );
        }
        catch ( InvalidPathException ignored ) {
            // Not a usable path: skip it.
        }
    }

    /**
     * The accounts of a Steam installation, the most recent first.
     *
     * @param install the installation
     *
     * @return the accounts; empty when none has signed in
     *
     * @since 2026.11
     */
    public static List< Account > accounts( SteamInstall install )
    {
        List< Long > ids = new ArrayList<>();
        try ( DirectoryStream< Path > folders = Files.newDirectoryStream( install.root().resolve( "userdata" ) ) ) {
            for ( Path folder : folders ) {
                String name = folder.getFileName().toString();
                if ( Files.isDirectory( folder ) && name.matches( "[0-9]{1,10}" ) && !"0".equals( name ) ) {
                    ids.add( Long.parseLong( name ) );
                }
            }
        }
        catch ( IOException e ) {
            return List.of();
        }
        String loginUsers = "";
        try {
            loginUsers = Files.readString( install.root().resolve( "config/loginusers.vdf" ), StandardCharsets.UTF_8 );
        }
        catch ( IOException ignored ) {
            // Accounts are still known from userdata, just without names.
        }
        return accounts( ids, loginUsers );
    }

    /**
     * Combines the {@code userdata} account IDs with what {@code loginusers.vdf} says. Pure, for
     * tests.
     *
     * @param accountIds the numeric {@code userdata} folders
     * @param loginUsers the text of {@code loginusers.vdf}, or {@code ""}
     *
     * @return the accounts, the most recent first
     *
     * @since 2026.11
     */
    static List< Account > accounts( List< Long > accountIds, String loginUsers )
    {
        Map< String, Object > users = TextVdf.parse( loginUsers ).get( "users" ) instanceof Map< ?, ? > map
                                      ? castMap( map ) : Map.of();
        List< Account > accounts = new ArrayList<>();
        for ( long id : accountIds ) {
            Map< String, Object > user = users.get( Long.toString( id + STEAM_ID64_BASE ) ) instanceof Map< ?, ? > map
                                         ? castMap( map ) : Map.of();
            accounts.add( new Account( id, string( user, "PersonaName" ), number( user, "Timestamp" ),
                                       "1".equals( string( user, "MostRecent" ) ) ) );
        }
        accounts.sort( Comparator.comparing( Account::mostRecent ).thenComparingLong( Account::timestamp )
                                 .reversed() );
        return accounts;
    }

    /**
     * The account to add entries for by default: the one Steam marked most recent, else the one
     * that signed in last.
     *
     * @param accounts the accounts
     *
     * @return that account, or empty when there are none
     *
     * @since 2026.11
     */
    public static Optional< Account > mostRecent( List< Account > accounts )
    {
        return accounts.isEmpty() ? Optional.empty() : Optional.of( accounts.get( 0 ) );
    }

    /**
     * The Windows registry's {@code SteamPath}, via {@code reg query}.
     *
     * @return the path, or {@code null}
     */
    private static String readWindowsSteamPath()
    {
        try {
            Process process = new ProcessBuilder( "reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath" )
                    .redirectErrorStream( true ).start();
            String output;
            try ( InputStream in = process.getInputStream() ) {
                output = new String( in.readAllBytes(), StandardCharsets.UTF_8 );
            }
            process.waitFor( 5, TimeUnit.SECONDS );
            return parseRegSteamPath( output );
        }
        catch ( IOException e ) {
            return null;
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * The {@code SteamPath} value in {@code reg query} output. Pure, for tests.
     *
     * @param output the command's output
     *
     * @return the path with Windows separators, or {@code null}
     *
     * @since 2026.11
     */
    static String parseRegSteamPath( String output )
    {
        for ( String line : output.split( "\\R" ) ) {
            Matcher m = REG_SZ.matcher( line.trim() );
            if ( m.matches() ) {
                return m.group( 1 ).trim().replace( '/', '\\' );
            }
        }
        return null;
    }

    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > castMap( Map< ?, ? > map )
    {
        return ( Map< String, Object > ) map;
    }

    private static String string( Map< String, Object > map, String key )
    {
        for ( Map.Entry< String, Object > e : map.entrySet() ) {
            if ( e.getKey().equalsIgnoreCase( key ) && e.getValue() instanceof String s ) {
                return s;
            }
        }
        return "";
    }

    private static long number( Map< String, Object > map, String key )
    {
        try {
            return Long.parseLong( string( map, key ) );
        }
        catch ( NumberFormatException e ) {
            return 0;
        }
    }

    /**
     * Steam's text KeyValues: {@code "key" "value"} pairs and {@code "key" { ... }} blocks, with
     * {@code //} comments and backslash escapes in strings. Enough for {@code loginusers.vdf}.
     */
    static final class TextVdf
    {
        private final String text;
        private int pos;

        private TextVdf( String text )
        {
            this.text = text;
        }

        /**
         * Parses the text; malformed input yields what was read up to the problem.
         *
         * @param text the file's text
         *
         * @return keys to strings or nested maps, in order
         */
        static Map< String, Object > parse( String text )
        {
            return new TextVdf( text == null ? "" : text ).block();
        }

        private Map< String, Object > block()
        {
            Map< String, Object > map = new LinkedHashMap<>();
            while ( true ) {
                skip();
                if ( pos >= text.length() || text.charAt( pos ) == '}' ) {
                    pos++;
                    return map;
                }
                String key = token();
                if ( key == null ) {
                    return map;
                }
                skip();
                if ( pos < text.length() && text.charAt( pos ) == '{' ) {
                    pos++;
                    map.put( key, block() );
                }
                else {
                    String value = token();
                    if ( value == null ) {
                        return map;
                    }
                    map.put( key, value );
                }
            }
        }

        private String token()
        {
            skip();
            if ( pos >= text.length() ) {
                return null;
            }
            StringBuilder out = new StringBuilder();
            if ( text.charAt( pos ) == '"' ) {
                pos++;
                while ( pos < text.length() && text.charAt( pos ) != '"' ) {
                    char c = text.charAt( pos++ );
                    if ( c == '\\' && pos < text.length() ) {
                        char next = text.charAt( pos++ );
                        out.append( switch ( next ) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            default -> next;
                        } );
                    }
                    else {
                        out.append( c );
                    }
                }
                pos++;
                return out.toString();
            }
            while ( pos < text.length() && !Character.isWhitespace( text.charAt( pos ) )
                    && "{}\"".indexOf( text.charAt( pos ) ) < 0 ) {
                out.append( text.charAt( pos++ ) );
            }
            return out.isEmpty() ? null : out.toString();
        }

        private void skip()
        {
            while ( pos < text.length() ) {
                char c = text.charAt( pos );
                if ( Character.isWhitespace( c ) ) {
                    pos++;
                }
                else if ( c == '/' && pos + 1 < text.length() && text.charAt( pos + 1 ) == '/' ) {
                    while ( pos < text.length() && text.charAt( pos ) != '\n' ) {
                        pos++;
                    }
                }
                else {
                    return;
                }
            }
        }
    }
}

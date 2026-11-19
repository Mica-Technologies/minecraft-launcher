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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryVdfTest
{
    /** Builds bytes the way Steam lays out shortcuts.vdf, independently of the writer under test. */
    private static final class Bytes
    {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Bytes map( String key )
        {
            out.write( 0x00 );
            return cstring( key );
        }

        Bytes string( String key, String value )
        {
            out.write( 0x01 );
            return cstring( key ).cstring( value );
        }

        Bytes integer( String key, int value )
        {
            out.write( 0x02 );
            cstring( key );
            out.write( value & 0xFF );
            out.write( ( value >>> 8 ) & 0xFF );
            out.write( ( value >>> 16 ) & 0xFF );
            out.write( ( value >>> 24 ) & 0xFF );
            return this;
        }

        Bytes end()
        {
            out.write( 0x08 );
            return this;
        }

        Bytes cstring( String text )
        {
            out.writeBytes( text.getBytes( StandardCharsets.UTF_8 ) );
            out.write( 0 );
            return this;
        }

        byte[] bytes()
        {
            return out.toByteArray();
        }
    }

    /** One shortcut as a current Steam client writes it (field order and casing included). */
    private static byte[] steamFile()
    {
        return new Bytes().map( "shortcuts" )
                          .map( "0" )
                          .integer( "appid", -1_234_567_890 )
                          .string( "AppName", "Some Game" )
                          .string( "Exe", "\"/home/player/Games/some-game\"" )
                          .string( "StartDir", "\"/home/player/Games/\"" )
                          .string( "icon", "" )
                          .string( "ShortcutPath", "" )
                          .string( "LaunchOptions", "" )
                          .integer( "IsHidden", 0 )
                          .integer( "AllowDesktopConfig", 1 )
                          .integer( "AllowOverlay", 1 )
                          .integer( "openvr", 0 )
                          .integer( "Devkit", 0 )
                          .string( "DevkitGameID", "" )
                          .integer( "DevkitOverrideAppID", 0 )
                          .integer( "LastPlayTime", 1_700_000_000 )
                          .string( "FlatpakAppID", "" )
                          .string( "sortas", "" )
                          .map( "tags" ).string( "0", "Favorite" ).end()
                          .end()
                          .end()
                          .end()
                          .bytes();
    }

    @Test
    void readsAShortcutAsSteamWritesIt() throws IOException
    {
        BinaryVdf.Node root = BinaryVdf.read( steamFile() );
        BinaryVdf.Node shortcut = root.getNode( "shortcuts" ).getNode( "0" );

        assertEquals( -1_234_567_890, shortcut.getInt( "AppId", 0 ), "keys match ignoring case" );
        assertEquals( "Some Game", shortcut.getString( "appname" ) );
        assertEquals( "\"/home/player/Games/some-game\"", shortcut.getString( "Exe" ) );
        assertEquals( 0, shortcut.getInt( "OpenVR", -1 ) );
        assertEquals( "Favorite", shortcut.getNode( "tags" ).getString( "0" ) );
        assertNull( shortcut.getString( "missing" ) );
        assertEquals( 7, shortcut.getInt( "AppName", 7 ), "wrong type falls back" );
    }

    @Test
    void writesBackTheExactBytesItRead() throws IOException
    {
        byte[] original = steamFile();

        assertArrayEquals( original, BinaryVdf.write( BinaryVdf.read( original ) ) );
    }

    @Test
    void putReplacesInPlaceKeepingCasingAndOrder() throws IOException
    {
        BinaryVdf.Node shortcut = BinaryVdf.read( steamFile() ).getNode( "shortcuts" ).getNode( "0" );

        shortcut.put( "OPENVR", 1 ).put( "NewField", "x" );

        assertEquals( "openvr", shortcut.entries().get( 10 ).key() );
        assertEquals( 1, shortcut.getInt( "openvr", 0 ) );
        assertEquals( "NewField", shortcut.entries().get( shortcut.entries().size() - 1 ).key() );
        assertTrue( shortcut.remove( "newfield" ) );
        assertFalse( shortcut.remove( "newfield" ) );
    }

    @Test
    void anEmptyShortcutsFileRoundTrips() throws IOException
    {
        byte[] empty = new Bytes().map( "shortcuts" ).end().end().bytes();

        BinaryVdf.Node root = BinaryVdf.read( empty );

        assertTrue( root.getNode( "shortcuts" ).entries().isEmpty() );
        assertArrayEquals( empty, BinaryVdf.write( root ) );
    }

    @Test
    void textIsUtf8()
    {
        BinaryVdf.Node root = new BinaryVdf.Node();
        root.put( "shortcuts", new BinaryVdf.Node().put( "0", new BinaryVdf.Node().put( "AppName", "Mïca ✓" ) ) );

        assertEquals( "Mïca ✓", assertDoesNotThrowRead( BinaryVdf.write( root ) ).getNode( "shortcuts" )
                                                                                .getNode( "0" )
                                                                                .getString( "AppName" ) );
    }

    @Test
    void rejectsDamagedData()
    {
        byte[] good = steamFile();
        byte[] truncated = java.util.Arrays.copyOf( good, good.length - 3 );
        byte[] trailing = java.util.Arrays.copyOf( good, good.length + 1 );
        byte[] prefix = new Bytes().map( "shortcuts" ).bytes();
        byte[] unknownType = java.util.Arrays.copyOf( prefix, prefix.length + 2 );
        unknownType[ unknownType.length - 2 ] = 0x07;

        assertThrows( IOException.class, () -> BinaryVdf.read( truncated ) );
        assertThrows( IOException.class, () -> BinaryVdf.read( trailing ) );
        assertThrows( IOException.class, () -> BinaryVdf.read( unknownType ) );
        assertThrows( IOException.class, () -> BinaryVdf.read( new byte[ 0 ] ) );
    }

    @Test
    void refusesToWriteANulInsideAString()
    {
        BinaryVdf.Node root = new BinaryVdf.Node().put( "shortcuts", "a\u0000b" );

        assertThrows( IllegalArgumentException.class, () -> BinaryVdf.write( root ) );
    }

    private static BinaryVdf.Node assertDoesNotThrowRead( byte[] data )
    {
        try {
            return BinaryVdf.read( data );
        }
        catch ( IOException e ) {
            throw new AssertionError( e );
        }
    }
}

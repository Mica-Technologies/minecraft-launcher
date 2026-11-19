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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Steam's binary KeyValues format, as used by {@code userdata/<account>/config/shortcuts.vdf}.
 *
 * <p>Each node is a type byte, a key ending in {@code 0x00}, and a value: {@code 0x00} a nested
 * map (its nodes, then {@code 0x08}), {@code 0x01} a UTF-8 string ending in {@code 0x00},
 * {@code 0x02} a little-endian signed 32-bit integer. A map's {@code 0x08} closes it; the file is
 * one root node (normally the map {@code shortcuts}) and a final {@code 0x08}.
 *
 * <p>A {@link Node} keeps its entries in order and finds keys ignoring case, because Steam itself
 * writes the same key with different casing ({@code appid} / {@code AppId}, {@code OpenVR} /
 * {@code openvr}). Entries this code doesn't know survive a read and write unchanged, so a newer
 * Steam's fields are never lost.
 *
 * @since 2026.11
 */
public final class BinaryVdf
{
    private static final byte TYPE_MAP = 0x00;
    private static final byte TYPE_STRING = 0x01;
    private static final byte TYPE_INT = 0x02;
    private static final byte TYPE_END = 0x08;

    private BinaryVdf() { /* static-only */ }

    /**
     * One value: a map, a string or an integer.
     *
     * @since 2026.11
     */
    public sealed interface Value permits Node, Text, Int
    {
    }

    /**
     * A string value.
     *
     * @param value the text
     *
     * @since 2026.11
     */
    public record Text( String value ) implements Value
    {
    }

    /**
     * A 32-bit integer value.
     *
     * @param value the number
     *
     * @since 2026.11
     */
    public record Int( int value ) implements Value
    {
    }

    /**
     * A map entry.
     *
     * @param key   the key, with the casing it was read or written with
     * @param value the value
     *
     * @since 2026.11
     */
    public record Entry( String key, Value value )
    {
    }

    /**
     * A map: ordered entries, keys found ignoring case.
     *
     * @since 2026.11
     */
    public static final class Node implements Value
    {
        private final List< Entry > entries = new ArrayList<>();

        /**
         * The entries, in order.
         *
         * @return a read-only view
         */
        public List< Entry > entries()
        {
            return Collections.unmodifiableList( entries );
        }

        /**
         * The value of a key, ignoring case.
         *
         * @param key the key
         *
         * @return the value, or {@code null}
         */
        public Value get( String key )
        {
            int index = indexOf( key );
            return index < 0 ? null : entries.get( index ).value();
        }

        /**
         * A string value, or {@code null} when absent or not a string.
         *
         * @param key the key
         *
         * @return the text
         */
        public String getString( String key )
        {
            return get( key ) instanceof Text text ? text.value() : null;
        }

        /**
         * An integer value.
         *
         * @param key      the key
         * @param fallback returned when absent or not an integer
         *
         * @return the number
         */
        public int getInt( String key, int fallback )
        {
            return get( key ) instanceof Int number ? number.value() : fallback;
        }

        /**
         * A nested map, or {@code null}.
         *
         * @param key the key
         *
         * @return the map
         */
        public Node getNode( String key )
        {
            return get( key ) instanceof Node node ? node : null;
        }

        /**
         * Sets a key: replaces the value in place (keeping the existing key's casing and position)
         * or appends a new entry.
         *
         * @param key   the key
         * @param value the value
         *
         * @return this map
         */
        public Node put( String key, Value value )
        {
            int index = indexOf( key );
            if ( index < 0 ) {
                entries.add( new Entry( key, value ) );
            }
            else {
                entries.set( index, new Entry( entries.get( index ).key(), value ) );
            }
            return this;
        }

        /** @return this map, after {@link #put} of a string */
        public Node put( String key, String value )
        {
            return put( key, new Text( value ) );
        }

        /** @return this map, after {@link #put} of an integer */
        public Node put( String key, int value )
        {
            return put( key, new Int( value ) );
        }

        /**
         * Removes a key, ignoring case.
         *
         * @param key the key
         *
         * @return whether it was there
         */
        public boolean remove( String key )
        {
            int index = indexOf( key );
            if ( index >= 0 ) {
                entries.remove( index );
            }
            return index >= 0;
        }

        private int indexOf( String key )
        {
            String wanted = key.toLowerCase( Locale.ROOT );
            for ( int i = 0; i < entries.size(); i++ ) {
                if ( entries.get( i ).key().toLowerCase( Locale.ROOT ).equals( wanted ) ) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * Reads a file's root node.
     *
     * @param data the file's bytes
     *
     * @return the root, holding the file's single top-level entry (normally {@code shortcuts})
     *
     * @throws IOException when the data is not valid binary VDF
     * @since 2026.11
     */
    public static Node read( byte[] data ) throws IOException
    {
        Reader reader = new Reader( data );
        Node root = reader.readMap();
        reader.expectEnd();
        return root;
    }

    /**
     * Writes a root node.
     *
     * @param root the root, as {@link #read} returns it
     *
     * @return the file's bytes
     *
     * @since 2026.11
     */
    public static byte[] write( Node root )
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeEntries( out, root );
        out.write( TYPE_END );
        return out.toByteArray();
    }

    private static void writeEntries( ByteArrayOutputStream out, Node node )
    {
        for ( Entry entry : node.entries ) {
            switch ( entry.value() ) {
                case Node child -> {
                    out.write( TYPE_MAP );
                    writeCString( out, entry.key() );
                    writeEntries( out, child );
                    out.write( TYPE_END );
                }
                case Text text -> {
                    out.write( TYPE_STRING );
                    writeCString( out, entry.key() );
                    writeCString( out, text.value() );
                }
                case Int number -> {
                    out.write( TYPE_INT );
                    writeCString( out, entry.key() );
                    int v = number.value();
                    out.write( v & 0xFF );
                    out.write( ( v >>> 8 ) & 0xFF );
                    out.write( ( v >>> 16 ) & 0xFF );
                    out.write( ( v >>> 24 ) & 0xFF );
                }
            }
        }
    }

    private static void writeCString( ByteArrayOutputStream out, String text )
    {
        byte[] bytes = text.getBytes( StandardCharsets.UTF_8 );
        for ( byte b : bytes ) {
            if ( b == 0 ) {
                throw new IllegalArgumentException( "A VDF string cannot contain a NUL character" );
            }
        }
        out.writeBytes( bytes );
        out.write( 0 );
    }

    /** A cursor over the bytes; every read checks bounds. */
    private static final class Reader
    {
        private final byte[] data;
        private int pos;

        Reader( byte[] data )
        {
            this.data = data;
        }

        /** Reads entries up to and including the closing {@code 0x08}. */
        Node readMap() throws IOException
        {
            Node node = new Node();
            while ( true ) {
                byte type = next();
                if ( type == TYPE_END ) {
                    return node;
                }
                String key = readCString();
                switch ( type ) {
                    case TYPE_MAP -> node.entries.add( new Entry( key, readMap() ) );
                    case TYPE_STRING -> node.entries.add( new Entry( key, new Text( readCString() ) ) );
                    case TYPE_INT -> node.entries.add( new Entry( key, new Int( readInt() ) ) );
                    default -> throw new IOException( "Unknown VDF type 0x" + Integer.toHexString( type & 0xFF )
                                                      + " at byte " + ( pos - 1 ) );
                }
            }
        }

        void expectEnd() throws IOException
        {
            if ( pos != data.length ) {
                throw new IOException( "Unexpected data after the VDF root at byte " + pos );
            }
        }

        private byte next() throws IOException
        {
            if ( pos >= data.length ) {
                throw new IOException( "The VDF data ends early" );
            }
            return data[ pos++ ];
        }

        private String readCString() throws IOException
        {
            int start = pos;
            while ( pos < data.length && data[ pos ] != 0 ) {
                pos++;
            }
            if ( pos >= data.length ) {
                throw new IOException( "An unterminated VDF string" );
            }
            String text = new String( data, start, pos - start, StandardCharsets.UTF_8 );
            pos++;
            return text;
        }

        private int readInt() throws IOException
        {
            if ( pos + 4 > data.length ) {
                throw new IOException( "The VDF data ends inside an integer" );
            }
            int v = ( data[ pos ] & 0xFF ) | ( data[ pos + 1 ] & 0xFF ) << 8 | ( data[ pos + 2 ] & 0xFF ) << 16
                    | ( data[ pos + 3 ] & 0xFF ) << 24;
            pos += 4;
            return v;
        }
    }
}

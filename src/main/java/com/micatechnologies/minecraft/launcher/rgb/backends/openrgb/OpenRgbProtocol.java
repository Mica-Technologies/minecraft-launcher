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

package com.micatechnologies.minecraft.launcher.rgb.backends.openrgb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire-protocol helpers for the OpenRGB SDK.
 *
 * <p>OpenRGB runs a local TCP server (default {@code localhost:6742})
 * that speaks a custom binary protocol — every packet is a 16-byte
 * header (magic "ORGB" + device index + packet ID + body size, all
 * little-endian) followed by a body specific to the packet type. This
 * class collects the constants, primitive read/write helpers, and the
 * controller-data parser into one place so {@link OpenRgbBackend} can
 * stay focused on lifecycle and frame rendering.</p>
 *
 * <p>Protocol reference:
 * <a href="https://openrgb-wiki.readthedocs.io/en/latest/Developer-Documentation/OpenRGB-SDK-Documentation/">
 * OpenRGB SDK documentation</a>. We negotiate at protocol version 3 — it's
 * the widely-deployed baseline and has all the fields we read (zones,
 * matrix layouts, LED names, custom-mode support).</p>
 *
 * @since 2026.5
 */
final class OpenRgbProtocol
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private OpenRgbProtocol() { /* static-only */ }

    /** Magic bytes that prefix every packet header. */
    static final byte[] MAGIC = "ORGB".getBytes( StandardCharsets.US_ASCII );

    /** Default OpenRGB server port. Configurable on the server side but
     *  almost nobody changes it. */
    static final int DEFAULT_PORT = 6742;

    /** Protocol version we ask the server to speak. Server downshifts to
     *  its own max if it doesn't support 3, which is fine — the field
     *  parser below tolerates either layout. */
    static final int CLIENT_PROTOCOL_VERSION = 3;

    /** OpenRGB device type for a keyboard. The other types (mouse,
     *  cooler, motherboard, …) are deliberately ignored by V1 — the
     *  effect engine produces keyboard-grid frames. */
    static final int DEVICE_TYPE_KEYBOARD = 5;

    // -------- Packet IDs --------

    /**
     * Packet ID for requesting the count of controllers.
     */
    static final int PKT_REQUEST_CONTROLLER_COUNT      = 0;

    /**
     * Packet ID for requesting controller data.
     */
    static final int PKT_REQUEST_CONTROLLER_DATA       = 1;

    /**
     * Packet ID for requesting the protocol version.
     */
    static final int PKT_REQUEST_PROTOCOL_VERSION      = 40;

    /**
     * Packet ID for setting the client name.
     */
    static final int PKT_SET_CLIENT_NAME               = 50;

    /**
     * Packet ID for updating LEDs on a controller.
     */
    static final int PKT_RGBCONTROLLER_UPDATELEDS      = 1050;

    /**
     * Packet ID for setting a custom mode on a controller.
     */
    static final int PKT_RGBCONTROLLER_SETCUSTOMMODE   = 1100;

    /** Header length: 4 magic + 4 device index + 4 packet ID + 4 size. */
    private static final int HEADER_BYTES = 16;

    // =========================================================================
    //  Packet I/O
    // =========================================================================

    /**
     * Writes one packet to the OpenRGB server. Header + body in a single
     * flush so the server reads them as one logical unit.
     *
     * @param out         The output stream to write the packet to.
     * @param deviceIndex The index of the device.
     * @param packetId    The ID of the packet.
     * @param body        The body of the packet.
     * @throws IOException If an I/O error occurs while writing the packet.
     */
    static void sendPacket( OutputStream out, int deviceIndex, int packetId, byte[] body )
            throws IOException
    {
        ByteBuffer header = ByteBuffer.allocate( HEADER_BYTES ).order( ByteOrder.LITTLE_ENDIAN );
        header.put( MAGIC );
        header.putInt( deviceIndex );
        header.putInt( packetId );
        header.putInt( body == null ? 0 : body.length );
        out.write( header.array() );
        if ( body != null && body.length > 0 ) {
            out.write( body );
        }
        out.flush();
    }

    /**
     * Reads one packet from the OpenRGB server. Validates the magic
     * and packet ID match expectations; on mismatch throws so the
     * caller can record a circuit-breaker failure instead of letting
     * a corrupted stream wedge the worker.
     *
     * @param in              The input stream to read the packet from.
     * @param expectedPacketId The expected packet ID.
     * @return A {@link Packet} object containing the parsed packet data.
     * @throws IOException If an I/O error occurs while reading the packet or if the magic or packet ID do not match expectations.
     */
    static Packet readPacket( DataInputStream in, int expectedPacketId ) throws IOException
    {
        byte[] header = in.readNBytes( HEADER_BYTES );
        if ( header.length != HEADER_BYTES ) {
            throw new IOException( "OpenRGB short read on packet header ("
                                           + header.length + "/" + HEADER_BYTES + ")" );
        }
        if ( header[0] != MAGIC[0] || header[1] != MAGIC[1]
                || header[2] != MAGIC[2] || header[3] != MAGIC[3] ) {
            throw new IOException( "OpenRGB packet magic mismatch — stream desynced" );
        }
        ByteBuffer h = ByteBuffer.wrap( header, 4, HEADER_BYTES - 4 )
                                   .order( ByteOrder.LITTLE_ENDIAN );
        int deviceIndex = h.getInt();
        int packetId = h.getInt();
        int bodySize = h.getInt();

        if ( expectedPacketId != -1 && packetId != expectedPacketId ) {
            // Drain the body so the stream stays aligned, then throw.
            // Without the drain the next read would land mid-body of
            // an ignored packet.
            if ( bodySize > 0 ) in.readNBytes( bodySize );
            throw new IOException( "OpenRGB packet ID mismatch: expected "
                                           + expectedPacketId + " got " + packetId );
        }

        byte[] body = bodySize > 0 ? in.readNBytes( bodySize ) : new byte[0];
        if ( body.length != bodySize ) {
            throw new IOException( "OpenRGB short read on packet body ("
                                           + body.length + "/" + bodySize + ")" );
        }
        return new Packet( deviceIndex, packetId, body );
    }

    /**
     * Represents a parsed OpenRGB packet.
     */
    record Packet( int deviceIndex, int packetId, byte[] body ) {}

    // =========================================================================
    //  Body builders
    // =========================================================================

    /**
     * Builds the body for the SET_CLIENT_NAME packet.
     *
     * @param clientName The name of the client to set.
     * @return A byte array containing the body of the packet.
     */
    static byte[] buildClientNameBody( String clientName )
    {
        byte[] nameBytes = clientName.getBytes( StandardCharsets.US_ASCII );
        byte[] body = new byte[ nameBytes.length + 1 ];
        System.arraycopy( nameBytes, 0, body, 0, nameBytes.length );
        body[ nameBytes.length ] = 0;
        return body;
    }

    /**
     * Builds the body for the REQUEST_CONTROLLER_DATA packet.
     *
     * @return A byte array containing the body of the packet.
     */
    static byte[] buildRequestControllerDataBody()
    {
        return ByteBuffer.allocate( 4 ).order( ByteOrder.LITTLE_ENDIAN )
                          .putInt( CLIENT_PROTOCOL_VERSION ).array();
    }

    /**
     * Builds the body for the UPDATELEDS packet.
     *
     * @param packedColors An array of packed colors to update.
     * @return A byte array containing the body of the packet.
     */
    static byte[] buildUpdateLedsBody( int[] packedColors )
    {
        int numColors = packedColors.length;
        // size + numColors + (4 bytes per LED)
        int bodySize = 4 + 2 + numColors * 4;
        ByteBuffer buf = ByteBuffer.allocate( bodySize ).order( ByteOrder.LITTLE_ENDIAN );
        buf.putInt( bodySize );                           // data_size
        buf.putShort( (short) numColors );                // num_colors
        for ( int packed : packedColors ) {
            // OpenRGB color layout per the wire format: R, G, B, padding.
            buf.put( (byte) ( ( packed >> 16 ) & 0xFF ) );
            buf.put( (byte) ( ( packed >> 8 ) & 0xFF ) );
            buf.put( (byte) ( packed & 0xFF ) );
            buf.put( (byte) 0 );
        }
        return buf.array();
    }

    // =========================================================================
    //  Controller-data parser
    // =========================================================================

    /**
     * Parsed slice of a {@code REQUEST_CONTROLLER_DATA} response.
     * Only the fields the backend actually consumes are surfaced; the
     * mode list, zone matrices, and initial-color block are walked past
     * and discarded so the offset trackers reach the LED-name section.
     */
    record ControllerData( int deviceType, String name, String description,
                           List< String > ledNames )
    {
        /**
         * Checks if the device is a keyboard.
         *
         * @return {@code true} if the device type is keyboard, {@code false} otherwise.
         */
        boolean isKeyboard() { return deviceType == DEVICE_TYPE_KEYBOARD; }

        /**
         * Gets the count of LEDs.
         *
         * @return The number of LEDs.
         */
        int ledCount() { return ledNames.size(); }
    }

    /**
     * Parses a controller-data body returned by REQUEST_CONTROLLER_DATA.
     * The OpenRGB wire format is a sequence of length-prefixed strings,
     * variable-length mode and zone records, and finally the LED name +
     * value table. This walker reads only what we need; everything else
     * is fast-forwarded by tracking the cursor against the documented
     * field widths.
     *
     * <p>Throws {@link IOException} on a truncated or malformed body — the
     * backend's circuit breaker then records a failure and demotes the
     * backend on repeated bad reads.</p>
     *
     * @param body The body of the controller data packet to parse.
     * @return A {@link ControllerData} object containing the parsed data.
     * @throws IOException If an I/O error occurs while parsing the controller data or if the data is truncated or malformed.
     */
    static ControllerData parseControllerData( byte[] body ) throws IOException
    {
        ByteBuffer buf = ByteBuffer.wrap( body ).order( ByteOrder.LITTLE_ENDIAN );

        try {
            // data_size (redundant), device_type
            buf.getInt();
            int deviceType = buf.getInt();
            String name = readString( buf );
            String description = readString( buf );
            // version, serial, location — read past, we don't need them.
            readString( buf ); // version (or vendor on later protocol versions)
            readString( buf ); // serial
            readString( buf ); // location

            // Modes block — variable per-mode shape. Skip mode-by-mode.
            int numModes = buf.getShort() & 0xFFFF;
            buf.getInt(); // active_mode
            for ( int i = 0; i < numModes; i++ ) {
                readString( buf );             // name
                buf.getInt();                  // value
                buf.getInt();                  // flags
                buf.getInt();                  // speed_min
                buf.getInt();                  // speed_max
                buf.getInt();                  // brightness_min (protocol >= 3)
                buf.getInt();                  // brightness_max (protocol >= 3)
                buf.getInt();                  // colors_min
                buf.getInt();                  // colors_max
                buf.getInt();                  // speed
                buf.getInt();                  // brightness (protocol >= 3)
                buf.getInt();                  // direction
                buf.getInt();                  // color_mode
                int modeNumColors = buf.getShort() & 0xFFFF;
                for ( int c = 0; c < modeNumColors; c++ ) {
                    buf.getInt();              // mode color
                }
            }

            // Zones — also variable per-zone shape (matrix). Skip.
            int numZones = buf.getShort() & 0xFFFF;
            for ( int i = 0; i < numZones; i++ ) {
                readString( buf );             // zone_name
                buf.getInt();                  // zone_type
                buf.getInt();                  // leds_min
                buf.getInt();                  // leds_max
                buf.getInt();                  // leds_count
                int matrixSize = buf.getShort() & 0xFFFF;
                if ( matrixSize > 0 ) {
                    // matrix_height + matrix_width + matrix_height*matrix_width entries
                    int matrixHeight = buf.getInt();
                    int matrixWidth = buf.getInt();
                    int entries = matrixHeight * matrixWidth;
                    for ( int e = 0; e < entries; e++ ) {
                        buf.getInt();
                    }
                }
            }

            // LED table — the part we actually want.
            int numLeds = buf.getShort() & 0xFFFF;
            List< String > ledNames = new ArrayList<>( numLeds );
            for ( int i = 0; i < numLeds; i++ ) {
                ledNames.add( readString( buf ) );
                buf.getInt();                  // led value (default color etc.)
            }

            return new ControllerData( deviceType, name, description, ledNames );
        }
        catch ( java.nio.BufferUnderflowException bue ) {
            throw new IOException( "OpenRGB controller data truncated or schema mismatch", bue );
        }
    }

    /**
     * Reads a length-prefixed string: uint16 length (INCLUDES trailing
     * null per OpenRGB's serialization), then {@code length} bytes. The
     * trailing null is stripped before returning.
     *
     * @param buf The buffer to read the string from.
     * @return A string containing the parsed data.
     */
    private static String readString( ByteBuffer buf )
    {
        int length = buf.getShort() & 0xFFFF;
        if ( length == 0 ) return "";
        byte[] bytes = new byte[ length ];
        buf.get( bytes );
        // Strip trailing null if present.
        int effective = ( bytes[ length - 1 ] == 0 ) ? length - 1 : length;
        return new String( bytes, 0, effective, StandardCharsets.UTF_8 );
    }
}

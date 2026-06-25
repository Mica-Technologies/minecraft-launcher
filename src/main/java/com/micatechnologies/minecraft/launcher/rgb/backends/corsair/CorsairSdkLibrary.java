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

package com.micatechnologies.minecraft.launcher.rgb.backends.corsair;

import com.sun.jna.Callback;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.apache.commons.lang3.SystemUtils;

import java.util.Arrays;
import java.util.List;

/**
 * JNA binding for Corsair's iCUE SDK v3 ({@code iCUESDK.x64_2017.dll}
 * on Windows 64-bit). Same shape and life as
 * {@link com.micatechnologies.minecraft.launcher.rgb.backends.chromanative.RzChromaSdkLibrary}
 * — a lazy-loaded interface with a {@link #INSTANCE} that's null when
 * iCUE isn't installed, plus a stub {@link #isLoadable()} probe the
 * backend uses before any other call.
 *
 * <h3>API surface used</h3>
 *
 * <p>The iCUE v3 SDK is callback-driven for connection (state-changed
 * handler) and polling-driven for everything else. The launcher needs
 * a small slice:</p>
 *
 * <ul>
 *   <li>{@link #CorsairConnect} — kick off connection to the iCUE
 *       service. State-changed callback fires on the SDK's worker
 *       thread when the session transitions; backend waits for
 *       {@code Connected}.</li>
 *   <li>{@link #CorsairDisconnect} — release the session.</li>
 *   <li>{@link #CorsairGetDevices} — enumerate every connected
 *       Corsair device (keyboard, mouse, cooler, RAM, fans, etc.).</li>
 *   <li>{@link #CorsairGetLedPositions} — fetch the LED IDs on a
 *       device. We only care about the IDs, not the positions — but
 *       this is the API that gives us a per-device LED ID list, and
 *       LED IDs are what {@code SetLedColors} keys on.</li>
 *   <li>{@link #CorsairSetLedColors} — apply colors to a device.
 *       Per-frame heavy hitter.</li>
 * </ul>
 *
 * <h3>String IDs (CorsairDeviceId)</h3>
 *
 * <p>The SDK identifies devices by a fixed {@code char[128]} buffer.
 * We treat it as opaque — copy the bytes out of a returned struct,
 * keep a reference, and copy back in when calling functions that
 * take it. Never need to inspect the contents.</p>
 *
 * @since 2026.5
 */
public interface CorsairSdkLibrary extends StdCallLibrary
{
    /** CorsairDeviceId is a fixed-size {@code char[128]} buffer. */
    int DEVICE_ID_LEN = 128;

    /** CorsairDeviceFilter: bit 0 in deviceTypeMask = "all devices". */
    int CDT_ALL = 0x7FFFFFFF;

    // CorsairError enum values (subset)
    /**
     * Success status code.
     */
    int CE_SUCCESS                = 0;
    /**
     * Not connected status code.
     */
    int CE_NOT_CONNECTED          = 1;
    /**
     * No control status code.
     */
    int CE_NO_CONTROL             = 2;
    /**
     * Incompatible protocol status code.
     */
    int CE_INCOMPATIBLE_PROTOCOL  = 3;
    /**
     * Invalid arguments status code.
     */
    int CE_INVALID_ARGUMENTS      = 4;
    /**
     * Invalid operation status code.
     */
    int CE_INVALID_OPERATION      = 5;
    /**
     * Device not found status code.
     */
    int CE_DEVICE_NOT_FOUND       = 6;
    /**
     * Not allowed status code.
     */
    int CE_NOT_ALLOWED            = 7;

    // CorsairSessionState enum values
    /**
     * Invalid session state.
     */
    int CSS_INVALID               = 0;
    /**
     * Closed session state.
     */
    int CSS_CLOSED                = 1;
    /**
     * Connecting session state.
     */
    int CSS_CONNECTING            = 2;
    /**
     * Connected session state.
     */
    int CSS_CONNECTED             = 3;
    /**
     * Subscribed session state.
     */
    int CSS_SUBSCRIBED            = 4;
    /**
     * Connection lost session state.
     */
    int CSS_CONNECTION_LOST       = 5;
    /**
     * Connection refused session state.
     */
    int CSS_CONNECTION_REFUSED    = 6;
    /**
     * Connection timeout session state.
     */
    int CSS_CONNECTION_TIMEOUT    = 7;

    /** Hard cap on device count and LEDs-per-device we'll enumerate.
     *  Plenty of headroom for a typical Corsair rig (keyboard + mouse
     *  + a few peripherals + RAM + fans). */
    /**
     * Maximum number of devices that can be enumerated.
     */
    int MAX_DEVICES = 64;
    /**
     * Maximum number of LEDs per device that can be enumerated.
     */
    int MAX_LEDS_PER_DEVICE = 512;

    /** Lazily loaded — null on non-Windows or when iCUE isn't installed. */
    /**
     * The lazily loaded instance of the Corsair SDK library.
     */
    CorsairSdkLibrary INSTANCE = loadOrNull();

    /** Callback signature for {@link #CorsairConnect}. Invoked on the
     *  SDK's worker thread when the session state changes. */
    /**
     * Interface for handling session state change events.
     */
    interface CorsairSessionStateChangedHandler extends Callback
    {
        /**
         * Handles the session state change event.
         *
         * @param context The context pointer provided during connection.
         * @param eventData The event data associated with the state change.
         */
        void invoke( Pointer context, Pointer eventData );
    }

    /** Begin connection to iCUE. State-change callback fires
     *  asynchronously; callers poll the state from there (or maintain
     *  a Java-side latch) to detect Connected / ConnectionRefused. */
    /**
     * Begins the connection to the iCUE service.
     *
     * @param onStateChanged The callback handler for session state changes.
     * @param context The context pointer to be passed to the callback.
     * @return An integer status code indicating the result of the operation.
     */
    int CorsairConnect( CorsairSessionStateChangedHandler onStateChanged, Pointer context );

    /** Release the session. Synchronous. */
    /**
     * Releases the iCUE session.
     *
     * @return An integer status code indicating the result of the operation.
     */
    int CorsairDisconnect();

    /** Enumerate every Corsair device matching {@code filter}.
     *  {@code devices} should be a {@link Structure#toArray} chunk of
     *  size {@code sizeMax}; on return {@code size} holds the real
     *  count. */
    /**
     * Enumerates all Corsair devices that match the given filter.
     *
     * @param filter The device filter to apply.
     * @param sizeMax The maximum number of devices to enumerate.
     * @param first The first device info structure in an array.
     * @param size A reference to store the actual number of devices enumerated.
     * @return An integer status code indicating the result of the operation.
     */
    int CorsairGetDevices( CorsairDeviceFilter.ByReference filter,
                            int sizeMax,
                            CorsairDeviceInfo first,
                            IntByReference size );

    /** Fetch the LED positions on a device. We only consume the
     *  {@code id} field of each position — the cx/cy coordinates
     *  describe the keyboard/strip layout for visual effects, which
     *  the launcher doesn't use yet. {@code deviceId} is a 128-byte
     *  buffer (passed as a Pointer). */
    /**
     * Fetches the LED positions on a specified device.
     *
     * @param deviceId The device ID as a pointer to a 128-byte buffer.
     * @param sizeMax The maximum number of LED positions to fetch.
     * @param first The first LED position structure in an array.
     * @param size A reference to store the actual number of LED positions fetched.
     * @return An integer status code indicating the result of the operation.
     */
    int CorsairGetLedPositions( Pointer deviceId,
                                  int sizeMax,
                                  CorsairLedPosition first,
                                  IntByReference size );

    /** Apply colors to one device's LEDs. {@code colors} is a contiguous
     *  array of {@link CorsairLedColor}; {@code size} is the number of
     *  entries. */
    /**
     * Applies colors to the LEDs of a specified device.
     *
     * @param deviceId The device ID as a pointer to a 128-byte buffer.
     * @param size The number of LED color entries.
     * @param first The first LED color structure in an array.
     * @return An integer status code indicating the result of the operation.
     */
    int CorsairSetLedColors( Pointer deviceId,
                              int size,
                              CorsairLedColor first );

    // ====================================================================
    // Structs
    // ====================================================================

    /**
     * Represents a device filter for enumerating Corsair devices.
     */
    @Structure.FieldOrder( { "deviceTypeMask" } )
    class CorsairDeviceFilter extends Structure
    {
        /**
         * The mask representing the type of devices to filter.
         */
        public int deviceTypeMask;

        /**
         * Constructs a new instance of {@link CorsairDeviceFilter}.
         */
        public CorsairDeviceFilter() {}

        /**
         * Constructs a new instance of {@link CorsairDeviceFilter} with the specified mask.
         *
         * @param mask The device type mask to set.
         */
        public CorsairDeviceFilter( int mask ) { this.deviceTypeMask = mask; }

        /**
         * Represents a reference to a {@link CorsairDeviceFilter}.
         */
        public static class ByReference extends CorsairDeviceFilter
                implements Structure.ByReference
        {
            /**
             * Constructs a new instance of {@link ByReference}.
             */
            public ByReference() {}

            /**
             * Constructs a new instance of {@link ByReference} with the specified mask.
             *
             * @param mask The device type mask to set.
             */
            public ByReference( int mask ) { super( mask ); }
        }
    }

    /**
     * Represents information about a Corsair device.
     */
    @Structure.FieldOrder( { "type", "id", "serial", "model", "ledCount", "channelCount" } )
    class CorsairDeviceInfo extends Structure
    {
        /**
         * The type of the device.
         */
        public int type;
        /**
         * The ID of the device as a 128-byte buffer.
         */
        public byte[] id = new byte[ DEVICE_ID_LEN ];
        /**
         * The serial number of the device as a 128-byte buffer.
         */
        public byte[] serial = new byte[ DEVICE_ID_LEN ];
        /**
         * The model name of the device as a 128-byte buffer.
         */
        public byte[] model = new byte[ DEVICE_ID_LEN ];
        /**
         * The number of LEDs on the device.
         */
        public int ledCount;
        /**
         * The number of channels on the device.
         */
        public int channelCount;
    }

    /**
     * Represents the position of a Corsair LED.
     */
    @Structure.FieldOrder( { "id", "cx", "cy" } )
    class CorsairLedPosition extends Structure
    {
        /**
         * The ID of the LED.
         */
        public int id;
        /**
         * The x-coordinate of the LED position.
         */
        public double cx;
        /**
         * The y-coordinate of the LED position.
         */
        public double cy;
    }

    /**
     * Represents the color of a Corsair LED.
     */
    @Structure.FieldOrder( { "id", "r", "g", "b", "a" } )
    class CorsairLedColor extends Structure
    {
        /**
         * The ID of the LED.
         */
        public int id;
        /**
         * The red component of the LED color.
         */
        public byte r;
        /**
         * The green component of the LED color.
         */
        public byte g;
        /**
         * The blue component of the LED color.
         */
        public byte b;
        /**
         * The alpha component of the LED color.
         */
        public byte a;
    }

    /**
     * Allocates an array of {@link CorsairLedColor} sharing one
     * Memory block, ready to pass to {@link #CorsairSetLedColors}.
     * JNA's auto-write happens when the first element's Pointer is
     * passed to a native function, propagating every entry.
     *
     * @param size The size of the array to allocate.
     * @return An array of {@link CorsairLedColor} structures.
     */
    static CorsairLedColor[] newLedColorArray( int size )
    {
        return (CorsairLedColor[]) new CorsairLedColor().toArray( size );
    }

    /**
     * Allocates an array of {@link CorsairLedPosition} sharing one
     * Memory block, ready to pass to other methods.
     * JNA's auto-write happens when the first element's Pointer is
     * passed to a native function, propagating every entry.
     *
     * @param size The size of the array to allocate.
     * @return An array of {@link CorsairLedPosition} structures.
     */
    static CorsairLedPosition[] newLedPositionArray( int size )
    {
        return (CorsairLedPosition[]) new CorsairLedPosition().toArray( size );
    }

    /**
     * Allocates an array of {@link CorsairDeviceInfo} sharing one
     * Memory block, ready to pass to other methods.
     * JNA's auto-write happens when the first element's Pointer is
     * passed to a native function, propagating every entry.
     *
     * @param size The size of the array to allocate.
     * @return An array of {@link CorsairDeviceInfo} structures.
     */
    static CorsairDeviceInfo[] newDeviceInfoArray( int size )
    {
        return (CorsairDeviceInfo[]) new CorsairDeviceInfo().toArray( size );
    }

    /**
     * Convenience: returns {@code true} iff the SDK DLL is loadable
     * on this host.
     *
     * @return A boolean indicating whether the SDK DLL is loadable.
     */
    static boolean isLoadable()
    {
        return INSTANCE != null;
    }

    /**
     * Try the canonical Corsair v3 SDK DLL names in order, return
     * the first that loads — or null if none do. The 2017 suffix is
     * the standard MSVC-redistributable target that ships with iCUE
     * 4.x; a "no suffix" name covers the unlikely case of a future
     * build dropping the suffix.
     *
     * @return An instance of {@link CorsairSdkLibrary} if loadable, otherwise null.
     */
    private static CorsairSdkLibrary loadOrNull()
    {
        if ( !SystemUtils.IS_OS_WINDOWS ) return null;
        List< String > candidates = Arrays.asList(
                "iCUESDK.x64_2019",
                "iCUESDK.x64_2017",
                "iCUESDK_x64",
                "iCUESDK"
        );
        for ( String name : candidates ) {
            try { return Native.load( name, CorsairSdkLibrary.class ); }
            catch ( Throwable ignored ) { /* try the next candidate */ }
        }
        return null;
    }
}

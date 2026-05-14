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
    int CE_SUCCESS                = 0;
    int CE_NOT_CONNECTED          = 1;
    int CE_NO_CONTROL             = 2;
    int CE_INCOMPATIBLE_PROTOCOL  = 3;
    int CE_INVALID_ARGUMENTS      = 4;
    int CE_INVALID_OPERATION      = 5;
    int CE_DEVICE_NOT_FOUND       = 6;
    int CE_NOT_ALLOWED            = 7;

    // CorsairSessionState enum values
    int CSS_INVALID               = 0;
    int CSS_CLOSED                = 1;
    int CSS_CONNECTING            = 2;
    int CSS_CONNECTED             = 3;
    int CSS_SUBSCRIBED            = 4;
    int CSS_CONNECTION_LOST       = 5;
    int CSS_CONNECTION_REFUSED    = 6;
    int CSS_CONNECTION_TIMEOUT    = 7;

    /** Hard cap on device count and LEDs-per-device we'll enumerate.
     *  Plenty of headroom for a typical Corsair rig (keyboard + mouse
     *  + a few peripherals + RAM + fans). */
    int MAX_DEVICES = 64;
    int MAX_LEDS_PER_DEVICE = 512;

    /** Lazily loaded — null on non-Windows or when iCUE isn't installed. */
    CorsairSdkLibrary INSTANCE = loadOrNull();

    /** Callback signature for {@link #CorsairConnect}. Invoked on the
     *  SDK's worker thread when the session state changes. */
    interface CorsairSessionStateChangedHandler extends Callback
    {
        void invoke( Pointer context, Pointer eventData );
    }

    /** Begin connection to iCUE. State-change callback fires
     *  asynchronously; callers poll the state from there (or maintain
     *  a Java-side latch) to detect Connected / ConnectionRefused. */
    int CorsairConnect( CorsairSessionStateChangedHandler onStateChanged, Pointer context );

    /** Release the session. Synchronous. */
    int CorsairDisconnect();

    /** Enumerate every Corsair device matching {@code filter}.
     *  {@code devices} should be a {@link Structure#toArray} chunk of
     *  size {@code sizeMax}; on return {@code size} holds the real
     *  count. */
    int CorsairGetDevices( CorsairDeviceFilter.ByReference filter,
                            int sizeMax,
                            CorsairDeviceInfo first,
                            IntByReference size );

    /** Fetch the LED positions on a device. We only consume the
     *  {@code id} field of each position — the cx/cy coordinates
     *  describe the keyboard/strip layout for visual effects, which
     *  the launcher doesn't use yet. {@code deviceId} is a 128-byte
     *  buffer (passed as a Pointer). */
    int CorsairGetLedPositions( Pointer deviceId,
                                  int sizeMax,
                                  CorsairLedPosition first,
                                  IntByReference size );

    /** Apply colors to one device's LEDs. {@code colors} is a contiguous
     *  array of {@link CorsairLedColor}; {@code size} is the number of
     *  entries. */
    int CorsairSetLedColors( Pointer deviceId,
                              int size,
                              CorsairLedColor first );

    // ====================================================================
    // Structs
    // ====================================================================

    @Structure.FieldOrder( { "deviceTypeMask" } )
    class CorsairDeviceFilter extends Structure
    {
        public int deviceTypeMask;

        public CorsairDeviceFilter() {}
        public CorsairDeviceFilter( int mask ) { this.deviceTypeMask = mask; }

        public static class ByReference extends CorsairDeviceFilter
                implements Structure.ByReference
        {
            public ByReference() {}
            public ByReference( int mask ) { super( mask ); }
        }
    }

    /** {@code CorsairDeviceInfo}: {@code type, id[128], serial[128],
     *  model[128], ledCount, channelCount}. We only read {@code id} and
     *  {@code model} (the latter for log lines); the rest of the fields
     *  exist so JNA marshals the struct at the right size + alignment. */
    @Structure.FieldOrder( { "type", "id", "serial", "model", "ledCount", "channelCount" } )
    class CorsairDeviceInfo extends Structure
    {
        public int type;
        public byte[] id = new byte[ DEVICE_ID_LEN ];
        public byte[] serial = new byte[ DEVICE_ID_LEN ];
        public byte[] model = new byte[ DEVICE_ID_LEN ];
        public int ledCount;
        public int channelCount;
    }

    /** {@code CorsairLedPosition}: {@code id (int), cx (double), cy (double)}.
     *  We only read {@code id}; cx/cy describe device geometry for visual
     *  effects, which the launcher's per-device "all LEDs same color"
     *  rendering doesn't use. */
    @Structure.FieldOrder( { "id", "cx", "cy" } )
    class CorsairLedPosition extends Structure
    {
        public int id;
        public double cx;
        public double cy;
    }

    /** {@code CorsairLedColor}: {@code id, r, g, b, a}. 8 bytes packed
     *  (the four channels are {@code unsigned char}, no padding needed). */
    @Structure.FieldOrder( { "id", "r", "g", "b", "a" } )
    class CorsairLedColor extends Structure
    {
        public int id;
        public byte r;
        public byte g;
        public byte b;
        public byte a;
    }

    /** Allocate an array of {@link CorsairLedColor} sharing one
     *  Memory block, ready to pass to {@link #CorsairSetLedColors}.
     *  JNA's auto-write happens when the first element's Pointer is
     *  passed to a native function, propagating every entry. */
    static CorsairLedColor[] newLedColorArray( int size )
    {
        return (CorsairLedColor[]) new CorsairLedColor().toArray( size );
    }

    /** Same idea for {@link CorsairLedPosition}. */
    static CorsairLedPosition[] newLedPositionArray( int size )
    {
        return (CorsairLedPosition[]) new CorsairLedPosition().toArray( size );
    }

    /** Same idea for {@link CorsairDeviceInfo}. */
    static CorsairDeviceInfo[] newDeviceInfoArray( int size )
    {
        return (CorsairDeviceInfo[]) new CorsairDeviceInfo().toArray( size );
    }

    /** Convenience: returns {@code true} iff the SDK DLL is loadable
     *  on this host. */
    static boolean isLoadable()
    {
        return INSTANCE != null;
    }

    /** Try the canonical Corsair v3 SDK DLL names in order, return
     *  the first that loads — or null if none do. The 2017 suffix is
     *  the standard MSVC-redistributable target that ships with iCUE
     *  4.x; a "no suffix" name covers the unlikely case of a future
     *  build dropping the suffix. */
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

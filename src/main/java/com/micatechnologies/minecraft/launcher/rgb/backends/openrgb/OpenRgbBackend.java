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

import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.rgb.KeyboardKey;
import com.micatechnologies.minecraft.launcher.rgb.RgbBackend;
import com.micatechnologies.minecraft.launcher.rgb.RgbColor;
import com.micatechnologies.minecraft.launcher.rgb.RgbFrame;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RGB backend that talks to the OpenRGB SDK server over its native
 * TCP protocol (localhost:6742). Pure-Java client — no JNA, no native
 * library, no vendor account required. Covers any peripheral OpenRGB
 * knows about, which is a substantial superset of the per-vendor SDKs
 * (Razer, Corsair, Logitech, ASUS, MSI, Gigabyte, …).
 *
 * <h3>Connection lifecycle</h3>
 *
 * <ol>
 *   <li>{@link #isAvailable()} attempts a TCP connect with a 500ms
 *       timeout. If the OpenRGB server isn't running, the connect fails
 *       fast and {@code isAvailable} returns false — controller routes
 *       to NoOp without further fuss.</li>
 *   <li>{@link #start()} opens a long-lived socket, sends our client
 *       name, enumerates devices, parses each device's data to find
 *       keyboards, and builds per-device LED-name → {@link KeyboardKey}
 *       maps so the per-key highlight effect can target W/A/S/D/E/etc.
 *       directly.</li>
 *   <li>{@link #renderFrame} packs a UPDATELEDS packet per connected
 *       keyboard. Devices with unknown LED names still get the
 *       background fill — graceful degradation.</li>
 *   <li>{@link #shutdown} closes the socket. OpenRGB's server keeps
 *       device state at "whatever the last UPDATELEDS told it" — we
 *       paint one final black frame on shutdown so the user's keyboard
 *       doesn't get stuck on whatever in-game effect was running when
 *       they quit the launcher.</li>
 * </ol>
 *
 * <h3>Per-key mapping</h3>
 *
 * <p>OpenRGB exposes each addressable LED as a string name. The naming
 * convention varies by keyboard vendor and OpenRGB version, but the
 * common patterns are {@code "Key: W"}, {@code "Key W"}, plain
 * {@code "W"}, and {@code "W Key"}. The matcher in
 * {@link #buildLedNameKeyMap} normalizes by uppercasing + stripping
 * "Key:"/"Key" tokens and matches against the canonical name of each
 * {@link KeyboardKey}. Unmapped keys silently fall back to the
 * background color when the effect references them.</p>
 *
 * @since 2026.5
 */
public final class OpenRgbBackend implements RgbBackend
{
    private static final String CLIENT_NAME = "Mica Minecraft Launcher";
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS    = 1_000;
    private static final int PROBE_TIMEOUT_MS   = 500;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;

    /** Every connected RGB device. Keyboards carry a per-key map for
     *  the in-game highlight effect; other devices (mice, mousemats,
     *  fans, GPU shrouds, motherboard zones, light strips, etc.) get
     *  {@code keyMap == null} and the background color across every LED.
     *  Drawing all device types is what makes the launcher's "test
     *  connection" actually light up a user's full RGB setup, not just
     *  the keyboard. */
    private final List< Device > devices = new ArrayList<>();

    @Override
    public String name() { return "OpenRGB"; }

    @Override
    public boolean isAvailable()
    {
        // Cheap probe: try a TCP connect with a tight timeout. The
        // OpenRGB server accepts connections immediately when running;
        // refusal means it's not installed / not running. We don't even
        // attempt the handshake here — that happens in start() and gets
        // routed to the circuit breaker if it goes wrong.
        try ( Socket probe = new Socket() ) {
            probe.connect( new InetSocketAddress( "localhost",
                                                    OpenRgbProtocol.DEFAULT_PORT ),
                            PROBE_TIMEOUT_MS );
            return true;
        }
        catch ( Throwable t ) {
            return false;
        }
    }

    @Override
    public void start() throws Exception
    {
        socket = new Socket();
        socket.connect( new InetSocketAddress( "localhost",
                                                 OpenRgbProtocol.DEFAULT_PORT ),
                         CONNECT_TIMEOUT_MS );
        socket.setSoTimeout( READ_TIMEOUT_MS );
        in = new DataInputStream( socket.getInputStream() );
        out = socket.getOutputStream();

        // (1) Set client name — required so the OpenRGB GUI shows
        // "Mica Minecraft Launcher" as the active client. Some
        // server-side mode logic also depends on a non-empty name.
        OpenRgbProtocol.sendPacket( out, 0,
                                     OpenRgbProtocol.PKT_SET_CLIENT_NAME,
                                     OpenRgbProtocol.buildClientNameBody( CLIENT_NAME ) );

        // (2) Get the controller count. The server responds with a
        // single uint32 LE.
        OpenRgbProtocol.sendPacket( out, 0,
                                     OpenRgbProtocol.PKT_REQUEST_CONTROLLER_COUNT,
                                     null );
        OpenRgbProtocol.Packet countPkt = OpenRgbProtocol.readPacket(
                in, OpenRgbProtocol.PKT_REQUEST_CONTROLLER_COUNT );
        if ( countPkt.body().length < 4 ) {
            throw new Exception( "OpenRGB controller-count response too short" );
        }
        int controllerCount = java.nio.ByteBuffer.wrap( countPkt.body() )
                .order( java.nio.ByteOrder.LITTLE_ENDIAN )
                .getInt();

        // (3) For each controller, fetch its data and register every
        // device — not just keyboards. Mice, mousemats, fan controllers,
        // GPU shrouds, motherboard zones, light strips, everything
        // OpenRGB exposes gets a {@link Device} entry. Keyboards
        // additionally get a per-key LED-name map; other devices fall
        // through to "paint all LEDs with the frame background".
        for ( int i = 0; i < controllerCount; i++ ) {
            OpenRgbProtocol.sendPacket( out, i,
                                         OpenRgbProtocol.PKT_REQUEST_CONTROLLER_DATA,
                                         OpenRgbProtocol.buildRequestControllerDataBody() );
            OpenRgbProtocol.Packet dataPkt = OpenRgbProtocol.readPacket(
                    in, OpenRgbProtocol.PKT_REQUEST_CONTROLLER_DATA );
            OpenRgbProtocol.ControllerData data;
            try {
                data = OpenRgbProtocol.parseControllerData( dataPkt.body() );
            }
            catch ( Throwable t ) {
                // A single device with a malformed blob shouldn't kill
                // the whole start. Log + skip; the user may still get
                // other devices working.
                Logger.logWarningSilent( "OpenRGB: failed to parse device "
                                                 + i + " data — skipping", t );
                continue;
            }
            if ( data.ledCount() <= 0 ) continue; // no addressable LEDs — nothing to drive

            // (4) Switch the device to "custom" mode so UPDATELEDS
            // actually takes effect. Without this the server may keep
            // running the device's last vendor mode (rainbow, breathing,
            // etc.) and ignore our color pushes. Best-effort — some
            // device types ignore SETCUSTOMMODE without an error reply.
            try {
                OpenRgbProtocol.sendPacket( out, i,
                                             OpenRgbProtocol.PKT_RGBCONTROLLER_SETCUSTOMMODE,
                                             null );
            }
            catch ( Throwable ignored ) { /* fall through — try renderFrame anyway */ }

            Map< KeyboardKey, Integer > keyMap = data.isKeyboard()
                    ? buildLedNameKeyMap( data.ledNames() )
                    : null;
            devices.add( new Device( i, data.deviceType(), data.ledCount(), keyMap, data.name() ) );
            Logger.logStd( "OpenRGB: registered " + deviceTypeName( data.deviceType() )
                                   + " #" + i + " — \"" + data.name() + "\" ("
                                   + data.ledCount() + " LEDs"
                                   + ( keyMap != null ? ", " + keyMap.size() + " mapped keys" : "" )
                                   + ")" );
        }

        if ( devices.isEmpty() ) {
            Logger.logStd( "OpenRGB: connected but no usable devices detected. "
                                   + "Effects will run as no-ops until a device appears." );
        }
    }

    @Override
    public void renderFrame( RgbFrame frame ) throws Exception
    {
        if ( devices.isEmpty() ) return; // nothing to drive

        int bgPacked = frame.background().packRgb();
        for ( Device dev : devices ) {
            int[] packed = new int[ dev.numLeds() ];
            for ( int i = 0; i < packed.length; i++ ) {
                packed[ i ] = bgPacked;
            }
            // Keyboards get per-key overrides; other devices stay on the
            // solid background fill. Mice / mousemats with multi-zone
            // backlight could one day get a finer mapping, but for V1
            // "paint everything matching the keyboard background" is the
            // right approximation — same color everywhere reads as a
            // single cohesive theme.
            if ( dev.keyMap() != null ) {
                for ( Map.Entry< KeyboardKey, RgbColor > e : frame.overrides().entrySet() ) {
                    Integer ledIdx = dev.keyMap().get( e.getKey() );
                    if ( ledIdx != null && ledIdx >= 0 && ledIdx < packed.length ) {
                        packed[ ledIdx ] = e.getValue().packRgb();
                    }
                }
            }
            OpenRgbProtocol.sendPacket( out, dev.deviceIndex(),
                                         OpenRgbProtocol.PKT_RGBCONTROLLER_UPDATELEDS,
                                         OpenRgbProtocol.buildUpdateLedsBody( packed ) );
        }
    }

    @Override
    public void shutdown()
    {
        // Paint one final black frame across every device so nothing
        // stays stuck on whatever colors the last effect was driving.
        // Best-effort — a broken socket here just means we close it
        // below without the final paint, which is fine. The zero-init
        // int[] is already "all black" — RGB packed value 0x000000.
        try {
            if ( out != null && !devices.isEmpty() ) {
                for ( Device dev : devices ) {
                    int[] packed = new int[ dev.numLeds() ];
                    OpenRgbProtocol.sendPacket( out, dev.deviceIndex(),
                                                 OpenRgbProtocol.PKT_RGBCONTROLLER_UPDATELEDS,
                                                 OpenRgbProtocol.buildUpdateLedsBody( packed ) );
                }
            }
        }
        catch ( Throwable ignored ) { /* best-effort */ }
        try { if ( socket != null ) socket.close(); } catch ( Throwable ignored ) { }
        socket = null;
        in = null;
        out = null;
        devices.clear();
    }

    // =========================================================================
    //  Per-device state
    // =========================================================================

    /** One row per device enumerated from the OpenRGB server.
     *  {@code keyMap} is non-null for keyboards (drives per-key
     *  overrides), null for everything else (mice / mousemats /
     *  motherboard zones / fans / strips — paint background across
     *  every LED). */
    private record Device( int deviceIndex, int deviceType, int numLeds,
                            Map< KeyboardKey, Integer > keyMap, String displayName ) {}

    /** Human label for an OpenRGB device-type code — used in the
     *  "registered ..." log line so the user can see what the launcher
     *  enumerated. The codes come straight from the OpenRGB
     *  server's RGBController.h enum; unknown values fall through to
     *  a generic "device" label. */
    private static String deviceTypeName( int type )
    {
        return switch ( type ) {
            case 0  -> "motherboard";
            case 1  -> "DRAM";
            case 2  -> "GPU";
            case 3  -> "cooler";
            case 4  -> "LED strip";
            case 5  -> "keyboard";
            case 6  -> "mouse";
            case 7  -> "mousemat";
            case 8  -> "headset";
            case 9  -> "headset stand";
            case 10 -> "gamepad";
            case 11 -> "light";
            case 12 -> "speaker";
            case 13 -> "virtual";
            default -> "device (type=" + type + ")";
        };
    }

    /**
     * Builds an LED-name → {@link KeyboardKey} mapping for one device.
     *
     * <p>OpenRGB's LED naming isn't standardized across vendors. The
     * common patterns observed in the wild are:</p>
     * <ul>
     *   <li>{@code "Key: W"} (Logitech, Corsair via OpenRGB)</li>
     *   <li>{@code "Key W"} (some MSI/ASUS)</li>
     *   <li>{@code "W"} (some Razer)</li>
     *   <li>{@code "W Key"} (less common)</li>
     * </ul>
     *
     * <p>We normalize by uppercasing, stripping the "KEY:" or "KEY"
     * tokens, and trimming, then compare against the canonical name of
     * each {@link KeyboardKey} (with a small synonym table for the keys
     * whose enum name doesn't match the typical OpenRGB string —
     * "LEFT_SHIFT" vs. "Left Shift" / "LSHIFT", "NUM_1" vs. "1", etc.).</p>
     *
     * <p>Unmatched names are silently dropped — the per-key override
     * for that LED just falls through to the background color in the
     * frame. This is the graceful-degradation path; an unfamiliar
     * keyboard still gets a single-color background paint.</p>
     */
    private static Map< KeyboardKey, Integer > buildLedNameKeyMap( List< String > ledNames )
    {
        Map< String, KeyboardKey > synonyms = new HashMap<>();
        for ( KeyboardKey k : KeyboardKey.values() ) {
            synonyms.put( k.name(), k );
        }
        // Hand-rolled synonyms for keys whose enum name doesn't match
        // OpenRGB's typical LED label. Order doesn't matter — we
        // normalize the LED name before lookup, and every entry maps to
        // exactly one KeyboardKey.
        synonyms.put( "LSHIFT", KeyboardKey.LEFT_SHIFT );
        synonyms.put( "LEFTSHIFT", KeyboardKey.LEFT_SHIFT );
        synonyms.put( "LEFT SHIFT", KeyboardKey.LEFT_SHIFT );
        synonyms.put( "LCTRL", KeyboardKey.LEFT_CTRL );
        synonyms.put( "LEFTCTRL", KeyboardKey.LEFT_CTRL );
        synonyms.put( "LEFT CTRL", KeyboardKey.LEFT_CTRL );
        synonyms.put( "LEFT CONTROL", KeyboardKey.LEFT_CTRL );
        synonyms.put( "ESC", KeyboardKey.ESCAPE );
        synonyms.put( "RETURN", KeyboardKey.ENTER );
        for ( int n = 1; n <= 9; n++ ) {
            synonyms.put( String.valueOf( n ), KeyboardKey.valueOf( "NUM_" + n ) );
        }

        Map< KeyboardKey, Integer > out = new HashMap<>();
        for ( int i = 0; i < ledNames.size(); i++ ) {
            String normalized = normalizeLedName( ledNames.get( i ) );
            if ( normalized.isEmpty() ) continue;
            KeyboardKey k = synonyms.get( normalized );
            if ( k == null ) continue;
            // First-match wins — some keyboards expose a key under
            // multiple LED slots (e.g. dual-zone backlight). Going
            // with the first one means our per-key highlight lands
            // somewhere on the physical key.
            out.putIfAbsent( k, i );
        }
        return out;
    }

    private static String normalizeLedName( String name )
    {
        if ( name == null ) return "";
        String s = name.toUpperCase( Locale.ROOT ).trim();
        // Strip leading "KEY:" or "KEY " tokens.
        if ( s.startsWith( "KEY:" ) ) s = s.substring( 4 ).trim();
        else if ( s.startsWith( "KEY " ) ) s = s.substring( 4 ).trim();
        // Strip trailing " KEY" token.
        if ( s.endsWith( " KEY" ) ) s = s.substring( 0, s.length() - 4 ).trim();
        return s;
    }
}

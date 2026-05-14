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

package com.micatechnologies.minecraft.launcher.rgb.backends.chroma;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.rgb.KeyboardKey;
import com.micatechnologies.minecraft.launcher.rgb.RgbBackend;
import com.micatechnologies.minecraft.launcher.rgb.RgbColor;
import com.micatechnologies.minecraft.launcher.rgb.RgbFrame;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * RGB backend that talks to the Razer Chroma SDK over its localhost REST
 * interface ({@code http://localhost:54235/razer/chromasdk}).
 *
 * <p>Razer Synapse exposes this REST endpoint whenever a Chroma-capable
 * device is connected and the SDK is enabled in Synapse. Posting an
 * application descriptor to it opens a session URI on a server-chosen
 * port; the launcher then PUTs keyboard color matrices to that session
 * and the SDK forwards them to the device. The session expires after
 * ~15 seconds of inactivity, so a heartbeat thread keeps it alive
 * during idle periods between effect renders.</p>
 *
 * <h3>Lifecycle</h3>
 *
 * <ol>
 *   <li>{@link #isAvailable()} probes localhost:54235 with a 500ms TCP
 *       connect. Failure means Synapse isn't running or the Chroma SDK
 *       service is disabled — controller routes to NoOp.</li>
 *   <li>{@link #start()} POSTs the application descriptor, parses the
 *       returned session URI, and spawns the heartbeat daemon thread.</li>
 *   <li>{@link #renderFrame} translates the {@link RgbFrame} into the
 *       6×22 {@code CHROMA_CUSTOM} effect payload via
 *       {@link ChromaKeyboardLayout} and PUTs it to {@code {uri}/keyboard}.</li>
 *   <li>{@link #shutdown} sends one final all-black frame so the user's
 *       keyboard doesn't get stuck on the last effect color, stops the
 *       heartbeat thread, and DELETEs the session URI so Synapse
 *       returns the device to its default lighting.</li>
 * </ol>
 *
 * @since 2026.5
 */
public final class ChromaRestBackend implements RgbBackend
{
    /** Razer Chroma SDK init endpoint. The session URI is server-chosen
     *  on a different port and read out of the init response. */
    private static final String INIT_ENDPOINT = "http://localhost:54235/razer/chromasdk";

    private static final int PROBE_TIMEOUT_MS = 500;
    private static final Duration HTTP_TIMEOUT = Duration.ofMillis( 1_000 );

    /** Heartbeat cadence. Razer's session expires after ~15s of
     *  inactivity, so 10s gives ample headroom while keeping the
     *  background HTTP traffic minimal. */
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    private HttpClient http;
    private String sessionUri;
    private Thread heartbeatThread;
    private volatile boolean heartbeatRunning;

    @Override
    public String name() { return "Razer Chroma"; }

    @Override
    public boolean isAvailable()
    {
        // The full POST init would be a more reliable probe, but it has
        // side effects (allocates a session URI server-side). A TCP
        // connect to the init port is enough to tell Synapse-with-SDK
        // from no-Synapse — Synapse only opens 54235 when the SDK is
        // enabled. If a different process is squatting on the port the
        // start() handshake will fail and the circuit breaker handles
        // the demotion.
        try ( Socket probe = new Socket() ) {
            probe.connect( new InetSocketAddress( "localhost", 54235 ), PROBE_TIMEOUT_MS );
            return true;
        }
        catch ( Throwable t ) {
            return false;
        }
    }

    @Override
    public void start() throws Exception
    {
        http = HttpClient.newBuilder()
                          .connectTimeout( HTTP_TIMEOUT )
                          .build();

        // Init payload describes the application to Synapse. The fields
        // appear in Synapse's connected-apps list; "device_supported":
        // ["keyboard"] tells the SDK to only allocate keyboard-relevant
        // session capacity (not strictly required but reduces overhead).
        String initBody = """
                {
                  "title": "Mica Minecraft Launcher",
                  "description": "Modpack-aware RGB lighting for Minecraft sessions.",
                  "author": {
                    "name": "Mica Technologies",
                    "contact": "https://github.com/Mica-Technologies/minecraft-launcher"
                  },
                  "device_supported": ["keyboard"],
                  "category": "application"
                }
                """;

        HttpRequest initReq = HttpRequest.newBuilder( URI.create( INIT_ENDPOINT ) )
                .timeout( HTTP_TIMEOUT )
                .header( "Content-Type", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( initBody ) )
                .build();

        HttpResponse< String > initResp = http.send( initReq,
                                                       HttpResponse.BodyHandlers.ofString() );
        if ( initResp.statusCode() != 200 ) {
            throw new IOException( "Chroma init returned HTTP " + initResp.statusCode()
                                           + ": " + initResp.body() );
        }
        JsonObject initJson = JSONUtilities.getGson().fromJson( initResp.body(), JsonObject.class );
        if ( initJson == null || !initJson.has( "uri" ) ) {
            throw new IOException( "Chroma init response missing 'uri': " + initResp.body() );
        }
        sessionUri = initJson.get( "uri" ).getAsString();
        Logger.logStd( "Razer Chroma: session opened at " + sessionUri );

        // Heartbeat — keeps the session alive during idle periods. The
        // thread is a daemon so JVM shutdown doesn't wait on it; the
        // shutdown() method also flips heartbeatRunning to false and
        // interrupts the thread for a clean teardown.
        heartbeatRunning = true;
        heartbeatThread = new Thread( this::heartbeatLoop, "mica-rgb-chroma-heartbeat" );
        heartbeatThread.setDaemon( true );
        heartbeatThread.start();
    }

    @Override
    public void renderFrame( RgbFrame frame ) throws Exception
    {
        if ( sessionUri == null ) return;

        // Build the 6×22 color grid from the frame. Start with the
        // background fill, then overlay each per-key override the
        // layout table knows how to address.
        int bgPacked = chromaPack( frame.background() );
        int[][] grid = new int[ ChromaKeyboardLayout.ROWS ][ ChromaKeyboardLayout.COLS ];
        for ( int r = 0; r < ChromaKeyboardLayout.ROWS; r++ ) {
            int[] row = grid[ r ];
            for ( int c = 0; c < ChromaKeyboardLayout.COLS; c++ ) {
                row[ c ] = bgPacked;
            }
        }
        for ( Map.Entry< KeyboardKey, RgbColor > e : frame.overrides().entrySet() ) {
            int[] coord = ChromaKeyboardLayout.coordOf( e.getKey() );
            if ( coord == null ) continue; // key not in our Chroma layout — fall through
            grid[ coord[0] ][ coord[1] ] = chromaPack( e.getValue() );
        }

        String body = buildKeyboardEffectBody( grid );
        HttpRequest req = HttpRequest.newBuilder( URI.create( sessionUri + "/keyboard" ) )
                .timeout( HTTP_TIMEOUT )
                .header( "Content-Type", "application/json" )
                .PUT( HttpRequest.BodyPublishers.ofString( body ) )
                .build();
        HttpResponse< Void > resp = http.send( req,
                                                 HttpResponse.BodyHandlers.discarding() );
        if ( resp.statusCode() != 200 ) {
            throw new IOException( "Chroma /keyboard returned HTTP " + resp.statusCode() );
        }
    }

    @Override
    public void shutdown()
    {
        // (1) Best-effort all-black frame so the keyboard doesn't stay
        // stuck on the last effect color.
        try {
            if ( sessionUri != null && http != null ) {
                renderFrame( RgbFrame.solid( RgbColor.BLACK ) );
            }
        }
        catch ( Throwable ignored ) { /* best-effort */ }

        // (2) Stop the heartbeat thread so it doesn't keep PUTing to a
        // session we're about to delete.
        heartbeatRunning = false;
        if ( heartbeatThread != null ) {
            heartbeatThread.interrupt();
            try { heartbeatThread.join( 1_000L ); }
            catch ( InterruptedException ie ) { Thread.currentThread().interrupt(); }
            heartbeatThread = null;
        }

        // (3) Delete the session URI. Synapse cleans up immediately on
        // DELETE; without this the session lingers for the 15s expiry
        // and the next launcher start can fail to allocate one if
        // we've hit Synapse's per-app session cap.
        if ( sessionUri != null && http != null ) {
            try {
                HttpRequest req = HttpRequest.newBuilder( URI.create( sessionUri ) )
                        .timeout( HTTP_TIMEOUT )
                        .DELETE()
                        .build();
                http.send( req, HttpResponse.BodyHandlers.discarding() );
            }
            catch ( Throwable ignored ) { /* best-effort */ }
        }
        sessionUri = null;
        http = null;
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /** Razer Chroma color packing: lowest byte = R, middle = G, top = B.
     *  Different from OpenRGB's wire layout, which stores R then G then
     *  B as separate bytes. */
    private static int chromaPack( RgbColor c )
    {
        return ( c.b() << 16 ) | ( c.g() << 8 ) | c.r();
    }

    /** Serializes a 6×22 grid as the CHROMA_CUSTOM effect payload.
     *  Hand-built rather than GSON-tree-walked because the grid is
     *  always exactly the same shape — a primitive int loop is cheaper
     *  than allocating ~132 JsonPrimitives every frame. */
    private static String buildKeyboardEffectBody( int[][] grid )
    {
        StringBuilder sb = new StringBuilder( 4_096 );
        sb.append( "{\"effect\":\"CHROMA_CUSTOM\",\"param\":[" );
        for ( int r = 0; r < ChromaKeyboardLayout.ROWS; r++ ) {
            if ( r > 0 ) sb.append( ',' );
            sb.append( '[' );
            for ( int c = 0; c < ChromaKeyboardLayout.COLS; c++ ) {
                if ( c > 0 ) sb.append( ',' );
                sb.append( grid[ r ][ c ] );
            }
            sb.append( ']' );
        }
        sb.append( "]}" );
        return sb.toString();
    }

    private void heartbeatLoop()
    {
        while ( heartbeatRunning ) {
            try {
                //noinspection BusyWait
                Thread.sleep( HEARTBEAT_INTERVAL_MS );
            }
            catch ( InterruptedException ie ) {
                Thread.currentThread().interrupt();
                break;
            }
            if ( !heartbeatRunning || sessionUri == null ) break;
            try {
                HttpRequest req = HttpRequest.newBuilder( URI.create( sessionUri + "/heartbeat" ) )
                        .timeout( HTTP_TIMEOUT )
                        .PUT( HttpRequest.BodyPublishers.noBody() )
                        .build();
                http.send( req, HttpResponse.BodyHandlers.discarding() );
            }
            catch ( Throwable t ) {
                // A failed heartbeat doesn't directly trip the main
                // backend's circuit breaker — the next renderFrame
                // will fail too and that one IS routed through the
                // breaker. Log silently here so the dev sees the
                // pattern in launcher.log without spamming the user.
                Logger.logWarningSilent( "Chroma heartbeat failed: "
                                                 + t.getClass().getSimpleName()
                                                 + " — " + t.getMessage() );
            }
        }
    }
}

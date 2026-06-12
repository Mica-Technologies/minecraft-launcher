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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
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

    /** First-time-success tracking per endpoint. Without this it's
     *  hard to tell from logs whether the Test Connection button
     *  actually reached the device endpoint or silently no-op'd —
     *  if every endpoint here gets a "first frame succeeded" line
     *  but the user's fans still don't light up, the gap is on the
     *  Synapse side (overlapping profile, game-mode override, etc.)
     *  rather than in our launcher → REST → SDK chain. */
    private final java.util.Set< String > endpointsSucceededOnce =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        // appear in Synapse's connected-apps list; "device_supported"
        // tells the SDK which device categories we want session capacity
        // for. We declare ALL Chroma device types so PC-case ARGB fans
        // routed through Chromalink, plus any connected mice / mousepads
        // / headsets / keypads, all light up alongside the keyboard.
        // Devices the user doesn't own are silently ignored by the SDK.
        String initBody = """
                {
                  "title": "Mica Minecraft Launcher",
                  "description": "Modpack-aware RGB lighting for Minecraft sessions.",
                  "author": {
                    "name": "Mica Technologies",
                    "contact": "https://github.com/Mica-Technologies/minecraft-launcher"
                  },
                  "device_supported": ["keyboard", "mouse", "mousepad", "headset", "keypad", "chromalink"],
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
        Logger.logStd( LocalizationManager.format( "log.rgb.chroma.sessionOpened", sessionUri ) );

        // Heartbeat — keeps the session alive during idle periods. The
        // thread is a daemon so JVM shutdown doesn't wait on it; the
        // shutdown() method also flips heartbeatRunning to false and
        // interrupts the thread for a clean teardown.
        heartbeatRunning = true;
        heartbeatThread = new Thread( this::heartbeatLoop, "mica-rgb-chroma-heartbeat" );
        heartbeatThread.setDaemon( true );
        heartbeatThread.start();
    }

    /** Non-keyboard Chroma endpoints we push the background color to.
     *  Each gets a CHROMA_STATIC effect so the entire device paints in
     *  the same color as the keyboard background — fans, mice, ARGB
     *  strips, headsets all react in sync with the launcher's effect.
     *  Per-zone targeting on these devices is a future feature; V1
     *  treats them as "follow the background." */
    private static final String[] NON_KEYBOARD_ENDPOINTS = {
            "/mouse", "/mousepad", "/headset", "/keypad", "/chromalink"
    };

    @Override
    public void renderFrame( RgbFrame frame ) throws Exception
    {
        if ( sessionUri == null ) return;

        int bgPacked = chromaPack( frame.background() );

        // (1) Keyboard — full 6×22 grid with per-key highlights.
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

        // Push to every Chroma endpoint INDEPENDENTLY. Per-endpoint
        // failures are tolerated and logged silently — most users won't
        // have every device type connected, and Synapse returns a 4xx
        // for "no device of this type" on those endpoints. Originally
        // this method threw on the first keyboard failure, which meant
        // a user with only Chromalink-connected ARGB fans (no Chroma
        // keyboard) saw every renderFrame abort before reaching
        // /chromalink, leaving the fans dark.
        //
        // The throw-if-all-failed semantic at the bottom preserves the
        // circuit breaker's behavior: if Synapse is fully down, every
        // endpoint fails, the breaker eventually trips. But a working
        // session with a partial device lineup keeps lighting the
        // devices that ARE present.
        int successes = 0;
        int attempts = 0;
        Throwable lastFailure = null;

        attempts++;
        try { sendKeyboardCustom( grid ); successes++; }
        catch ( Throwable t ) { lastFailure = t; logEndpointFailure( "/keyboard", t ); }

        for ( String endpoint : NON_KEYBOARD_ENDPOINTS ) {
            attempts++;
            try { sendStatic( endpoint, bgPacked ); successes++; }
            catch ( Throwable t ) { lastFailure = t; logEndpointFailure( endpoint, t ); }
        }

        if ( successes == 0 ) {
            // Every endpoint failed — this IS a real Chroma-down state,
            // so let the controller's circuit breaker see it. Wrap the
            // last underlying failure as the cause.
            throw new IOException( "Chroma renderFrame: all " + attempts
                                           + " endpoints failed", lastFailure );
        }
    }

    private static void logEndpointFailure( String endpoint, Throwable t )
    {
        Logger.logWarningSilent( LocalizationManager.format( "log.rgb.chroma.endpointPushFailed",
                                         endpoint, t.getClass().getSimpleName(), t.getMessage() ) );
    }

    /** Push the keyboard's CHROMA_CUSTOM matrix. Razer Chroma's REST
     *  API returns errors as a {@code "result"} field inside a 200 OK
     *  body rather than via HTTP status code — a plain
     *  {@code statusCode() != 200} check misses every SDK-level failure
     *  (session expired, unknown effect, device missing, etc.) and
     *  the launcher silently does nothing. Parse the body and throw
     *  on a non-zero result. */
    private void sendKeyboardCustom( int[][] grid ) throws Exception
    {
        sendEffectAndCheck( "/keyboard", buildKeyboardEffectBody( grid ) );
    }

    /** Push a single CHROMA_STATIC frame to a device endpoint. Used
     *  for mouse / mousepad / headset / keypad / chromalink — these
     *  devices either don't have a meaningful per-LED layout for our
     *  effects to target, or they're variable-shape (ARGB strips,
     *  fan controllers via Chromalink), so a solid color matched to
     *  the keyboard background is the right approximation for V1. */
    private void sendStatic( String endpoint, int packedColor ) throws Exception
    {
        String body = "{\"effect\":\"CHROMA_STATIC\",\"param\":{\"color\":" + packedColor + "}}";
        sendEffectAndCheck( endpoint, body );
    }

    /** Common PUT-and-parse-result wrapper used by every endpoint-push
     *  helper. Reads the response body, parses the standard Razer
     *  Chroma {@code result} field, and throws when either the HTTP
     *  status isn't 200 or the SDK reports a non-zero result code.
     *  This is the layer that catches issues like:
     *
     *  <ul>
     *    <li>{@code result: -1} — generic SDK failure (often "no
     *        device of this type connected").</li>
     *    <li>{@code result: -57} — RZRESULT_NOT_VALID_STATE; usually
     *        the session got dropped by Synapse.</li>
     *    <li>{@code result: -1073741275} — invalid parameter; usually
     *        a malformed effect payload or wrong grid dimensions.</li>
     *  </ul>
     *
     *  Surfacing these to the controller's circuit breaker is how
     *  failures bubble up to the Settings status chip and how the
     *  user finds out the session needs re-establishment. */
    private void sendEffectAndCheck( String endpoint, String body ) throws Exception
    {
        HttpRequest req = HttpRequest.newBuilder( URI.create( sessionUri + endpoint ) )
                .timeout( HTTP_TIMEOUT )
                .header( "Content-Type", "application/json" )
                .PUT( HttpRequest.BodyPublishers.ofString( body ) )
                .build();
        HttpResponse< String > resp = http.send( req,
                                                   HttpResponse.BodyHandlers.ofString() );
        if ( resp.statusCode() != 200 ) {
            throw new IOException( "Chroma " + endpoint + " returned HTTP "
                                           + resp.statusCode() + " body=" + resp.body() );
        }
        int result = parseResultField( resp.body() );
        if ( result != 0 ) {
            maybeLogResult126Hint( result );
            throw new IOException( "Chroma " + endpoint + " returned result=" + result
                                           + " (" + describeChromaResult( result )
                                           + ") body=" + resp.body() );
        }
        if ( endpointsSucceededOnce.add( endpoint ) ) {
            // One-shot: first time this endpoint accepted a frame for
            // the current session. Helps debug "no errors, no visible
            // change" cases — if every endpoint here gets a "first
            // frame succeeded" line but devices stay dark, the gap is
            // Synapse-side (active profile override, game mode, etc.)
            // rather than something the launcher can fix.
            Logger.logStd( LocalizationManager.format( "log.rgb.chroma.endpointFirstFrameSucceeded", endpoint ) );
        }
    }

    /** Translates a {@code result} code from a Chroma REST response into
     *  a short human-readable label so log lines self-explain. The Razer
     *  Chroma SDK forwards a mix of Windows system error codes and
     *  Razer-specific RZRESULT codes, none of which are particularly
     *  greppable as raw integers. Surfacing the meaning + likely-cause
     *  alongside the number saves a round-trip through Razer's docs. */
    private static String describeChromaResult( int result )
    {
        return switch ( result ) {
            case 0    -> "SUCCESS";
            case 1    -> "RZRESULT_FAILED";
            case 5    -> "ACCESS_DENIED — Synapse declined the request; check Chroma → "
                       + "Apps in Synapse and ensure SDK access is enabled";
            case 50   -> "NOT_SUPPORTED — effect type isn't valid for this device";
            case 87   -> "INVALID_PARAMETER — malformed payload (effect name, grid "
                       + "shape, or color format)";
            case 126  -> "MOD_NOT_FOUND — Synapse can't find the Chroma module for "
                       + "this device. Almost always means Chroma SDK access is "
                       + "disabled in Synapse, OR the user's installation is "
                       + "missing the per-device Chroma module";
            case 1168 -> "NOT_FOUND — device not registered with Synapse";
            case 1247 -> "ALREADY_INITIALIZED";
            case 4309 -> "RESOURCE_DISABLED";
            case 4319 -> "DEVICE_NOT_AVAILABLE — no device of this type connected";
            default   -> "unknown Chroma result code (see Razer SDK docs)";
        };
    }

    /** Whether the one-shot Synapse-config hint has been printed this
     *  session. We want to tell the user how to fix result=126 once,
     *  not 6 times a frame at 30fps. */
    private volatile boolean result126HintLogged = false;

    /** When result=126 lands the actionable fix is the same every time
     *  (toggle SDK access in Synapse). Print a single user-facing hint
     *  with the steps the first time the result code appears this
     *  session; the per-frame log lines from the caller still record
     *  every push that fails. */
    private void maybeLogResult126Hint( int result )
    {
        if ( result != 126 || result126HintLogged ) return;
        result126HintLogged = true;
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.rejecting" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.synapseIssue" ) );
        Logger.logStd( "" );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.background" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.cppSdk" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.restApi" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.deprecated1" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.deprecated2" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.deprecated3" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.deprecated4" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.deprecated5" ) );
        Logger.logStd( "" );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.worthChecking" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.checkSdkAccess" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.checkAppVisible" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.checkRestart" ) );
        Logger.logStd( "" );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.recommendedFix1" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.recommendedFix2" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.recommendedFix3" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.recommendedFix4" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.recommendedFix5" ) );
        Logger.logStd( LocalizationManager.get( "log.rgb.chroma.hint126.recommendedFix6" ) );
    }

    /** Pulls the {@code result} integer out of a Chroma REST response
     *  body. Returns {@code 0} (success) when the field is absent so a
     *  vendor change to the response format doesn't fail-closed
     *  unnecessarily — the HTTP status check above already gates the
     *  obvious failure path. Returns a sentinel value when the body
     *  is unparseable so the caller surfaces it. */
    private static int parseResultField( String body )
    {
        if ( body == null || body.isBlank() ) return 0;
        try {
            com.google.gson.JsonObject json = com.micatechnologies.minecraft.launcher.utilities
                    .JSONUtilities.getGson()
                    .fromJson( body, com.google.gson.JsonObject.class );
            if ( json == null || !json.has( "result" ) ) return 0;
            return json.get( "result" ).getAsInt();
        }
        catch ( Throwable t ) {
            // Malformed JSON in a 200 reply is itself unexpected — surface
            // it as a non-zero "unknown result" to the caller's circuit
            // breaker so the user sees something rather than silent dead-
            // air pushes.
            return -1;
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
        endpointsSucceededOnce.clear();
        result126HintLogged = false;
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
                Logger.logWarningSilent( LocalizationManager.format( "log.rgb.chroma.heartbeatFailed",
                                                 t.getClass().getSimpleName(), t.getMessage() ) );
            }
        }
    }
}

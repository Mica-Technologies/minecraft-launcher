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

package com.micatechnologies.minecraft.launcher.rgb.backends.chromanative;

import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.rgb.KeyboardKey;
import com.micatechnologies.minecraft.launcher.rgb.RgbBackend;
import com.micatechnologies.minecraft.launcher.rgb.RgbColor;
import com.micatechnologies.minecraft.launcher.rgb.RgbFrame;
import com.micatechnologies.minecraft.launcher.rgb.backends.chroma.ChromaKeyboardLayout;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RGB backend driving Razer hardware via the native {@code RzChromaSDK64.dll}
 * (Razer Chroma SDK 3 — the same path Fortnite, Overwatch, Diablo IV
 * etc. use). This is the fix for the {@code result=126} failures the
 * sibling {@link com.micatechnologies.minecraft.launcher.rgb.backends.chroma.ChromaRestBackend}
 * hits on recent Synapse builds: the REST API surface is effectively
 * deprecated, but the native DLL Razer ships with Synapse stays fully
 * supported and is the path their own games drive.
 *
 * <h3>Why this is a separate backend from {@code ChromaRestBackend}</h3>
 *
 * <p>The REST API and native DLL are two parallel Razer code paths
 * inside Synapse — different protocols, different SDK module loaders.
 * Keeping them as separate {@link RgbBackend} implementations lets a
 * user with a working REST setup (older Synapse, etc.) keep using it,
 * while users on current Synapse can pick this one without retiring
 * the fallback. Auto-probe order puts ChromaNative ahead of the REST
 * variant so most Razer users land on the working path automatically.</p>
 *
 * <h3>Lifecycle</h3>
 *
 * <ol>
 *   <li>{@link #isAvailable()} — try to load {@code RzChromaSDK64.dll}
 *       via JNA. Returns false instantly if Synapse / the Razer SDK
 *       isn't installed. No side effects.</li>
 *   <li>{@link #start()} — {@code InitSDK} with our {@link ChromaAppInfo}
 *       so the launcher shows up in Synapse's connected-apps list.</li>
 *   <li>{@link #renderFrame} — for each device family, build the
 *       appropriate effect (CHROMA_CUSTOM grid for keyboard, CHROMA_STATIC
 *       background fill for everything else), apply, then delete the
 *       effect ID. The "create, apply, delete" cycle is cheap (native
 *       call overhead only) and avoids leaking effect IDs across the
 *       session.</li>
 *   <li>{@link #shutdown} — paint a final black frame so devices don't
 *       stay stuck on the last effect's colors, then {@code UnInit}.</li>
 * </ol>
 *
 * <h3>Failure isolation</h3>
 *
 * <p>Per-device-family failures are logged silently and don't trip the
 * controller's circuit breaker — users with only a subset of Razer
 * devices (e.g. only ARGB fans on ChromaLink, no Chroma keyboard)
 * would otherwise see every {@code CreateKeyboardEffect} return a
 * non-zero result and burn through the breaker's failure budget. The
 * breaker only sees a failure when EVERY family fails.</p>
 *
 * @since 2026.5
 */
public final class ChromaNativeBackend implements RgbBackend
{
    private static final String APP_TITLE       = "Mica Minecraft Launcher";
    private static final String APP_DESCRIPTION = "Modpack-aware RGB lighting "
                                                  + "for Minecraft sessions.";
    private static final String APP_AUTHOR_NAME    = "Mica Technologies";
    private static final String APP_AUTHOR_CONTACT = "https://github.com/Mica-Technologies/minecraft-launcher";

    /** Memory for the APPINFOTYPE struct passed to InitSDK. Kept as a
     *  field for the duration of the session because JNA doesn't track
     *  ownership through Pointer arguments — letting the Memory get
     *  collected before Synapse is done reading the struct would risk
     *  use-after-free on Razer's side. */
    private Memory appInfoMemory;

    /** Initialized state. False before start, false after shutdown. */
    private volatile boolean initialized = false;

    /** One-shot success log per device family. Mirrors the REST backend's
     *  same flag so the user gets one "Razer Chroma (native): keyboard
     *  first frame succeeded" line per family per session — useful to
     *  confirm the launcher is actually reaching each device. */
    private final java.util.Set< String > familySucceededOnce = ConcurrentHashMap.newKeySet();

    @Override
    public String name() { return "Razer Chroma (Native)"; }

    @Override
    public boolean isAvailable()
    {
        return RzChromaSdkLibrary.isLoadable();
    }

    @Override
    public void start() throws Exception
    {
        if ( !RzChromaSdkLibrary.isLoadable() ) {
            throw new IllegalStateException( "RzChromaSDK64.dll not loadable — "
                                                     + "Synapse not installed?" );
        }

        // Declare every device family bit so the SDK accepts effect
        // pushes for whatever the user actually has connected. Unused
        // bits are harmless — Synapse silently no-ops effects for
        // missing devices.
        int supportedDevices = ChromaAppInfo.DEVICE_KEYBOARD
                | ChromaAppInfo.DEVICE_MOUSE
                | ChromaAppInfo.DEVICE_MOUSEPAD
                | ChromaAppInfo.DEVICE_HEADSET
                | ChromaAppInfo.DEVICE_KEYPAD
                | ChromaAppInfo.DEVICE_CHROMALINK;

        appInfoMemory = ChromaAppInfo.build( APP_TITLE, APP_DESCRIPTION,
                                              APP_AUTHOR_NAME, APP_AUTHOR_CONTACT,
                                              supportedDevices,
                                              ChromaAppInfo.CATEGORY_APPLICATION );

        int initResult = RzChromaSdkLibrary.INSTANCE.InitSDK( appInfoMemory );
        if ( initResult != 0 ) {
            // Failure on init — release the memory and surface the result
            // code to the controller's circuit breaker.
            appInfoMemory = null;
            throw new Exception( "Razer Chroma SDK InitSDK returned result="
                                         + initResult + " "
                                         + describeResult( initResult ) );
        }
        initialized = true;
        Logger.logStd( "Razer Chroma (Native): SDK initialized — appears in "
                               + "Synapse's connected-apps list as \""
                               + APP_TITLE + "\"." );
    }

    @Override
    public void renderFrame( RgbFrame frame ) throws Exception
    {
        if ( !initialized ) return;

        int bgPacked = chromaPack( frame.background() );

        // Build the keyboard 6×22 grid from the background fill +
        // per-key overrides, then apply each device-family effect
        // independently. successCount lets renderFrame stay tolerant
        // of partial-rig setups: if at least one device family
        // accepts the effect, we treat the frame as "delivered"
        // overall, even if the user is missing e.g. a Razer mouse.
        int[][] grid = buildKeyboardGrid( bgPacked, frame.overrides() );
        Memory keyboardParam = ChromaEffectTypes.buildKeyboardCustomParam( grid );
        Memory staticParam   = ChromaEffectTypes.buildStaticParam( bgPacked );
        Memory mouseParam    = ChromaEffectTypes.buildMouseStaticParam( bgPacked );

        int successes = 0;
        int attempts = 0;
        int lastFailure = 0;

        attempts++;
        if ( tryFamily( "keyboard",
                         family -> RzChromaSdkLibrary.INSTANCE.CreateKeyboardEffect(
                                 ChromaEffectTypes.KEYBOARD_CUSTOM,
                                 keyboardParam, family ) ) ) {
            successes++;
        }
        else { lastFailure = lastResult; }

        attempts++;
        if ( tryFamily( "mouse",
                         family -> RzChromaSdkLibrary.INSTANCE.CreateMouseEffect(
                                 ChromaEffectTypes.MOUSE_STATIC,
                                 mouseParam, family ) ) ) {
            successes++;
        }
        else { lastFailure = lastResult; }

        attempts++;
        if ( tryFamily( "mousepad",
                         family -> RzChromaSdkLibrary.INSTANCE.CreateMousepadEffect(
                                 ChromaEffectTypes.MOUSEPAD_STATIC,
                                 staticParam, family ) ) ) {
            successes++;
        }
        else { lastFailure = lastResult; }

        attempts++;
        if ( tryFamily( "headset",
                         family -> RzChromaSdkLibrary.INSTANCE.CreateHeadsetEffect(
                                 ChromaEffectTypes.HEADSET_STATIC,
                                 staticParam, family ) ) ) {
            successes++;
        }
        else { lastFailure = lastResult; }

        attempts++;
        if ( tryFamily( "keypad",
                         family -> RzChromaSdkLibrary.INSTANCE.CreateKeypadEffect(
                                 ChromaEffectTypes.KEYPAD_STATIC,
                                 staticParam, family ) ) ) {
            successes++;
        }
        else { lastFailure = lastResult; }

        attempts++;
        if ( tryFamily( "chromalink",
                         family -> RzChromaSdkLibrary.INSTANCE.CreateChromaLinkEffect(
                                 ChromaEffectTypes.CHROMALINK_STATIC,
                                 staticParam, family ) ) ) {
            successes++;
        }
        else { lastFailure = lastResult; }

        if ( successes == 0 ) {
            throw new java.io.IOException( "Razer Chroma (Native) renderFrame: "
                                                   + "all " + attempts + " device "
                                                   + "families failed, last result="
                                                   + lastFailure + " "
                                                   + describeResult( lastFailure ) );
        }
    }

    @Override
    public void shutdown()
    {
        if ( !initialized ) return;
        // Final black frame so devices don't stay stuck on the last
        // effect's colors when the user quits the launcher.
        try { renderFrame( RgbFrame.solid( RgbColor.BLACK ) ); }
        catch ( Throwable ignored ) { /* best-effort */ }
        try { RzChromaSdkLibrary.INSTANCE.UnInit(); }
        catch ( Throwable ignored ) { /* best-effort */ }
        initialized = false;
        appInfoMemory = null;
        familySucceededOnce.clear();
    }

    // =========================================================================
    //  Per-family create/apply/delete + result handling
    // =========================================================================

    /** Last family's result code, populated as a side effect of
     *  {@link #tryFamily}. Kept as a field rather than threading it
     *  through every call site since renderFrame loops sequentially.
     *  Not thread-safe — only the controller's single worker thread
     *  ever calls renderFrame. */
    private int lastResult;

    @FunctionalInterface
    private interface CreateEffectFn
    {
        int apply( Pointer effectIdOut );
    }

    /**
     * Runs the create / apply / delete dance for one device family,
     * logs failures silently per family, and records a one-shot
     * success log on the first successful frame for the family in
     * the current session.
     *
     * @return true on success (effect created AND applied), false on
     *         any failure
     */
    private boolean tryFamily( String familyName, CreateEffectFn createFn )
    {
        Memory effectId = new Memory( 16 ); // RZEFFECTID = GUID = 16 bytes
        int createResult = createFn.apply( effectId );
        if ( createResult != 0 ) {
            lastResult = createResult;
            logFamilyFailure( familyName, "Create", createResult );
            return false;
        }
        int setResult = RzChromaSdkLibrary.INSTANCE.SetEffect( effectId );
        // Always Delete to release the effect ID, even on SetEffect
        // failure — the create succeeded, so the SDK has resources
        // allocated for this ID that need to be reclaimed.
        try { RzChromaSdkLibrary.INSTANCE.DeleteEffect( effectId ); }
        catch ( Throwable ignored ) { /* best-effort cleanup */ }

        if ( setResult != 0 ) {
            lastResult = setResult;
            logFamilyFailure( familyName, "SetEffect", setResult );
            return false;
        }

        if ( familySucceededOnce.add( familyName ) ) {
            Logger.logStd( "Razer Chroma (Native): " + familyName
                                   + " first frame succeeded (result=0)" );
        }
        return true;
    }

    private static void logFamilyFailure( String familyName, String op, int result )
    {
        // logWarningSilent so a user without (say) a Razer keyboard
        // doesn't see "ERROR" toast spam every frame. The aggregate
        // "all families failed" path in renderFrame is what surfaces
        // to the user via the controller's circuit breaker.
        Logger.logWarningSilent( "Razer Chroma (Native) " + op + " for family "
                                         + familyName + " returned result=" + result
                                         + " (" + describeResult( result ) + ")" );
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /** Razer Chroma color packing: low byte = R, mid = G, high = B.
     *  Matches the REST backend's pack — Chroma's COLORREF format is
     *  the same regardless of SDK surface. */
    private static int chromaPack( RgbColor c )
    {
        return ( c.b() << 16 ) | ( c.g() << 8 ) | c.r();
    }

    /** Builds the 6×22 keyboard grid from a background color + per-key
     *  overrides. Re-uses {@link ChromaKeyboardLayout} from the REST
     *  backend's package — the keyboard grid layout is identical
     *  across both Chroma surfaces (it's a Razer-level concept, not
     *  REST-vs-native). */
    private static int[][] buildKeyboardGrid( int bgPacked,
                                                Map< KeyboardKey, RgbColor > overrides )
    {
        int rows = ChromaKeyboardLayout.ROWS;
        int cols = ChromaKeyboardLayout.COLS;
        int[][] grid = new int[ rows ][ cols ];
        for ( int r = 0; r < rows; r++ ) {
            for ( int c = 0; c < cols; c++ ) {
                grid[ r ][ c ] = bgPacked;
            }
        }
        for ( Map.Entry< KeyboardKey, RgbColor > e : overrides.entrySet() ) {
            int[] coord = ChromaKeyboardLayout.coordOf( e.getKey() );
            if ( coord == null ) continue;
            grid[ coord[0] ][ coord[1] ] = chromaPack( e.getValue() );
        }
        return grid;
    }

    /** Translates a Razer SDK result code into a short label. Covers the
     *  same RZRESULT codes the REST backend documents, plus the ones
     *  that show up exclusively from the native SDK (e.g. 1062
     *  SERVICE_NOT_ACTIVE — happens when the SDK was loaded but the
     *  Razer Chroma service got stopped after init). */
    static String describeResult( int result )
    {
        return switch ( result ) {
            case 0    -> "SUCCESS";
            case 5    -> "ACCESS_DENIED";
            case 50   -> "NOT_SUPPORTED — effect type not valid for this device";
            case 87   -> "INVALID_PARAMETER — malformed param or wrong effect type for family";
            case 126  -> "MOD_NOT_FOUND — per-device Chroma module not loaded";
            case 1062 -> "SERVICE_NOT_ACTIVE — Razer Chroma service stopped";
            case 1152 -> "SINGLE_INSTANCE_APP — another Chroma app holding the device";
            case 1167 -> "DEVICE_NOT_CONNECTED";
            case 1168 -> "NOT_FOUND";
            case 1247 -> "ALREADY_INITIALIZED";
            case 4309 -> "RESOURCE_DISABLED";
            case 4319 -> "DEVICE_NOT_AVAILABLE";
            case 5023 -> "NOT_VALID_STATE";
            default   -> "unknown RZRESULT (see Razer Chroma SDK headers)";
        };
    }
}

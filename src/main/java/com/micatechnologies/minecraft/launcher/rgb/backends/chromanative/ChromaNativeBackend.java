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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
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

    /** Per-family consecutive-failure counter. Once a family hits
     *  {@link #FAMILY_FAILURE_DROP_THRESHOLD} consecutive failures
     *  AND has never succeeded in this session, it's added to
     *  {@link #familyPermanentlyDropped} and renderFrame stops trying
     *  it — kills the spammy "result=87" log loop a user gets when
     *  e.g. they don't own a Razer mouse but the SDK still rejects
     *  the family-specific create with an invalid-parameter error.
     *  Any family that's succeeded at least once stays in the rotation
     *  (transient failures on a real device should still circuit-break,
     *  not be permanently dropped). */
    private final java.util.Map< String, Integer > familyFailureCount = new ConcurrentHashMap<>();

    /** Families we've given up on for this session — never retried, never
     *  logged. Cleared on {@link #shutdown()}. */
    private final java.util.Set< String > familyPermanentlyDropped = ConcurrentHashMap.newKeySet();

    /** Families whose name appears in {@link #familyPermanentlyDropped}
     *  but whose drop message we haven't logged yet. Used to emit a
     *  single "giving up on family X" line at drop time, after which
     *  the family contributes nothing to log volume. */
    private final java.util.Set< String > familyDropLoggedOnce = ConcurrentHashMap.newKeySet();

    /** Consecutive failure count at which a never-succeeded family is
     *  permanently dropped for the session. Picked low enough that a
     *  user without (say) a Razer mouse sees the warning ~5 times then
     *  silence, rather than thousands of identical lines over an hour
     *  of play. */
    private static final int FAMILY_FAILURE_DROP_THRESHOLD = 5;

    /**
     * {@inheritDoc}
     *
     * @return the fixed human-readable backend name
     *         {@code "Razer Chroma (Native)"} used in log lines and the
     *         RGB settings UI to distinguish this backend from the REST
     *         Chroma variant
     *
     * @since 2026.5
     */
    @Override
    public String name() { return "Razer Chroma (Native)"; }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation reports availability by attempting to load
     * {@code RzChromaSDK64.dll} via JNA (see
     * {@link RzChromaSdkLibrary#isLoadable()}). The probe is cheap and
     * side-effect free — it returns {@code false} instantly on non-Windows
     * hosts or when Synapse / the Razer Chroma SDK isn't installed.</p>
     *
     * @return {@code true} when the native Razer Chroma SDK DLL is
     *         loadable on this host, {@code false} otherwise
     *
     * @since 2026.5
     */
    @Override
    public boolean isAvailable()
    {
        return RzChromaSdkLibrary.isLoadable();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Builds the {@code APPINFOTYPE} struct describing the launcher
     * (so it appears in Synapse's connected-apps list), declares every
     * supported device-family bit, and calls {@code InitSDK}. On a
     * non-zero result the cached {@link #appInfoMemory} is released and
     * the result code is surfaced to the controller's circuit breaker as
     * an exception.</p>
     *
     * @throws IllegalStateException if {@code RzChromaSDK64.dll} cannot be
     *                               loaded (Synapse not installed)
     * @throws Exception             if {@code InitSDK} returns a non-zero
     *                               {@code RZRESULT} code; the message
     *                               carries the raw code and its
     *                               {@link #describeResult(int) label}
     *
     * @since 2026.5
     */
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
        Logger.logStd( LocalizationManager.format( "log.rgb.chroma.nativeSdkInitialized", APP_TITLE ) );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Translates the frame's background fill and per-key overrides
     * into the per-device-family effect structs, then runs the
     * create / apply / delete cycle for each family still in the active
     * rotation (keyboard, mouse, mousepad, headset, keypad, ChromaLink).
     * A no-op before {@link #start()} or after {@link #shutdown()}.</p>
     *
     * <p>The frame is treated as delivered if <em>any</em> family accepts
     * it, so users with a partial Razer rig (e.g. ChromaLink ARGB fans
     * but no Chroma keyboard) don't trip the controller's circuit
     * breaker. When every still-rotating family has been permanently
     * dropped the method returns cleanly (a "no hardware connected"
     * no-op) rather than reporting a failure.</p>
     *
     * @param frame the frame to paint; its {@link RgbFrame#background()}
     *              fills every LED and its {@link RgbFrame#overrides()}
     *              recolor individual keyboard keys
     *
     * @throws java.io.IOException if at least one family was attempted but
     *                             every attempt failed; the message
     *                             carries the attempt count and the last
     *                             {@code RZRESULT} code
     * @throws Exception           per the {@link RgbBackend} contract
     *
     * @since 2026.5
     */
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

        // Each family contributes to attempts only when it's still in
        // the active rotation. Permanently-dropped families (never-
        // succeeded + >= FAMILY_FAILURE_DROP_THRESHOLD strikes) don't
        // count — otherwise a user with only a keyboard would see
        // "all 6 families failed" on every render once the rest dropped.
        if ( !familyPermanentlyDropped.contains( "keyboard" ) ) {
            attempts++;
            if ( tryFamily( "keyboard",
                             family -> RzChromaSdkLibrary.INSTANCE.CreateKeyboardEffect(
                                     ChromaEffectTypes.KEYBOARD_CUSTOM,
                                     keyboardParam, family ) ) ) {
                successes++;
            }
            else { lastFailure = lastResult; }
        }

        if ( !familyPermanentlyDropped.contains( "mouse" ) ) {
            attempts++;
            if ( tryFamily( "mouse",
                             family -> RzChromaSdkLibrary.INSTANCE.CreateMouseEffect(
                                     ChromaEffectTypes.MOUSE_STATIC,
                                     mouseParam, family ) ) ) {
                successes++;
            }
            else { lastFailure = lastResult; }
        }

        if ( !familyPermanentlyDropped.contains( "mousepad" ) ) {
            attempts++;
            if ( tryFamily( "mousepad",
                             family -> RzChromaSdkLibrary.INSTANCE.CreateMousepadEffect(
                                     ChromaEffectTypes.MOUSEPAD_STATIC,
                                     staticParam, family ) ) ) {
                successes++;
            }
            else { lastFailure = lastResult; }
        }

        if ( !familyPermanentlyDropped.contains( "headset" ) ) {
            attempts++;
            if ( tryFamily( "headset",
                             family -> RzChromaSdkLibrary.INSTANCE.CreateHeadsetEffect(
                                     ChromaEffectTypes.HEADSET_STATIC,
                                     staticParam, family ) ) ) {
                successes++;
            }
            else { lastFailure = lastResult; }
        }

        if ( !familyPermanentlyDropped.contains( "keypad" ) ) {
            attempts++;
            if ( tryFamily( "keypad",
                             family -> RzChromaSdkLibrary.INSTANCE.CreateKeypadEffect(
                                     ChromaEffectTypes.KEYPAD_STATIC,
                                     staticParam, family ) ) ) {
                successes++;
            }
            else { lastFailure = lastResult; }
        }

        if ( !familyPermanentlyDropped.contains( "chromalink" ) ) {
            attempts++;
            if ( tryFamily( "chromalink",
                             family -> RzChromaSdkLibrary.INSTANCE.CreateChromaLinkEffect(
                                     ChromaEffectTypes.CHROMALINK_STATIC,
                                     staticParam, family ) ) ) {
                successes++;
            }
            else { lastFailure = lastResult; }
        }

        // No families left to try — every device family has either
        // succeeded historically (and is still rotating) or been
        // dropped. With attempts==0 we report a clean "no-op" frame
        // rather than a circuit-breaker failure; the user has no
        // Razer hardware connected and that's fine.
        if ( attempts == 0 ) return;

        if ( successes == 0 ) {
            throw new java.io.IOException( "Razer Chroma (Native) renderFrame: "
                                                   + "all " + attempts + " device "
                                                   + "families failed, last result="
                                                   + lastFailure + " "
                                                   + describeResult( lastFailure ) );
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Paints a final solid-black frame (best-effort) so devices don't
     * remain stuck on the last effect's colors after the launcher exits,
     * calls {@code UnInit} to tear down the SDK session, and clears all
     * per-family bookkeeping ({@link #familySucceededOnce},
     * {@link #familyFailureCount}, {@link #familyPermanentlyDropped},
     * {@link #familyDropLoggedOnce}). Safe to call when never started —
     * a no-op if {@link #initialized} is {@code false}. Both native calls
     * swallow any throwable so shutdown always completes.</p>
     *
     * @since 2026.5
     */
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
        familyFailureCount.clear();
        familyPermanentlyDropped.clear();
        familyDropLoggedOnce.clear();
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

    /**
     * Strategy for invoking one of the SDK's per-family
     * {@code CreateXxxEffect} functions. {@link #tryFamily} supplies the
     * out-parameter buffer and the lambda binds the concrete family call
     * (e.g. {@code CreateKeyboardEffect}) plus its effect type and param
     * struct.
     *
     * @since 2026.5
     */
    @FunctionalInterface
    private interface CreateEffectFn
    {
        /**
         * Invokes the bound {@code CreateXxxEffect} call.
         *
         * @param effectIdOut a 16-byte buffer that receives the newly
         *                    created effect's {@code RZEFFECTID} GUID
         *
         * @return the {@code RZRESULT} code; {@code 0} on success
         */
        int apply( Pointer effectIdOut );
    }

    /**
     * Runs the create / apply / delete dance for one device family,
     * logs failures silently per family, and records a one-shot
     * success log on the first successful frame for the family in
     * the current session.
     *
     * @param familyName the device-family key (e.g. {@code "keyboard"},
     *                   {@code "chromalink"}) used for failure tracking
     *                   and log lines
     * @param createFn   the family-specific {@code CreateXxxEffect}
     *                   invocation; receives the effect-ID out buffer and
     *                   returns the {@code RZRESULT} code
     *
     * @return true on success (effect created AND applied), false on
     *         any failure
     *
     * @since 2026.5
     */
    private boolean tryFamily( String familyName, CreateEffectFn createFn )
    {
        Memory effectId = new Memory( 16 ); // RZEFFECTID = GUID = 16 bytes
        int createResult = createFn.apply( effectId );
        if ( createResult != 0 ) {
            lastResult = createResult;
            recordFamilyFailure( familyName, "Create", createResult );
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
            recordFamilyFailure( familyName, "SetEffect", setResult );
            return false;
        }

        // Success — reset the consecutive-failure counter so a
        // previously-flaky family doesn't get dropped because of a
        // transient run from a few minutes ago.
        familyFailureCount.put( familyName, 0 );
        if ( familySucceededOnce.add( familyName ) ) {
            Logger.logStd( LocalizationManager.format( "log.rgb.chroma.nativeFirstFrameSucceeded", familyName ) );
        }
        return true;
    }

    /** Records one failure for the given family. Logs the per-frame
     *  warning ONLY while we're still considering the family — once we
     *  permanently drop it (never-succeeded + threshold strikes), a
     *  single "giving up on this family" line replaces the per-frame
     *  spam for the rest of the session. */
    private void recordFamilyFailure( String familyName, String op, int result )
    {
        int count = familyFailureCount.merge( familyName, 1, Integer::sum );

        // Drop check: only families that never succeeded in this
        // session get permanently dropped. A family that previously
        // worked but is now failing is a real device problem and
        // should keep circuit-breaking via the controller, not be
        // silenced here.
        if ( !familySucceededOnce.contains( familyName )
                && count >= FAMILY_FAILURE_DROP_THRESHOLD ) {
            if ( familyPermanentlyDropped.add( familyName )
                    && familyDropLoggedOnce.add( familyName ) ) {
                Logger.logStd( LocalizationManager.format( "log.rgb.chroma.nativeGivingUpOnFamily",
                                       familyName, FAMILY_FAILURE_DROP_THRESHOLD ) );
            }
            return; // suppress the per-frame log for the dropped family
        }

        // logWarningSilent so a user without (say) a Razer keyboard
        // doesn't see "ERROR" toast spam every frame.
        Logger.logWarningSilent( LocalizationManager.format( "log.rgb.chroma.nativeOpReturnedResult",
                                         op, familyName, result, describeResult( result ) ) );
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

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

package com.micatechnologies.minecraft.launcher.rgb.backends.asusaura;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.rgb.RgbBackend;
import com.micatechnologies.minecraft.launcher.rgb.RgbColor;
import com.micatechnologies.minecraft.launcher.rgb.RgbFrame;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * RGB backend driving ASUS hardware via the legacy
 * {@code AURA_SDK.dll}. Same shape as the Razer Chroma + Corsair iCUE
 * backends: lazy DLL load, per-family lifecycle, frame deduplication,
 * per-family failure backoff so a missing device category doesn't
 * poison the working ones.
 *
 * <h3>Device families covered</h3>
 *
 * <ul>
 *   <li>Motherboard ARGB (the most common Aura surface — most ASUS
 *       Aura users have at least this).</li>
 *   <li>GPU (ASUS ROG-branded cards).</li>
 *   <li>DRAM (ASUS Aura-compatible memory modules — narrow market).</li>
 *   <li>Claymore keyboard (the original ASUS Aura keyboard family).</li>
 * </ul>
 *
 * <p>Other ASUS device categories (newer mice, AIO coolers, fans
 * routed through ROG Armoury Crate) typically don't surface through
 * the legacy {@code AURA_SDK.dll} — those users should use OpenRGB
 * (already supported as a separate backend) which has broader ASUS
 * coverage via reverse-engineered drivers.</p>
 *
 * <h3>Color buffer format</h3>
 *
 * <p>Per the Aura SDK's documented signatures, each LED contributes
 * three bytes in {@code R, G, B} order. Total buffer size is
 * {@code 3 * ledCount}. Some community-reverse-engineered docs report
 * {@code BGR} for certain devices — if a user's hardware shows colors
 * swapped, that's the first place to look. We default to RGB because
 * that's what the ASUS-published SDK header documents.</p>
 *
 * <h3>Reliability notes</h3>
 *
 * <p>The Aura SDK is older than the Razer / Corsair SDKs we ship and
 * tends to be more fragile — controllers can transiently return zero
 * LEDs, {@code SetXColor} can fail silently on some firmware, and the
 * "software control" mode can revert if Aura Sync's service is
 * restarted. Per-family failure backoff drops families that fail
 * repeatedly without ever succeeding, mirroring
 * {@code ChromaNativeBackend}'s defense against missing-hardware
 * families.</p>
 *
 * @since 2026.5
 */
public final class AsusAuraBackend implements RgbBackend
{
    /** Consecutive failures at which a never-succeeded family is
     *  permanently dropped for the session. */
    private static final int FAMILY_FAILURE_DROP_THRESHOLD = 5;

    /**
     * One initialised Aura controller (one device family). Bundles the
     * opaque SDK controller pointer with the family's LED count, the
     * closures that apply a color / release the controller, and the
     * per-family failure-backoff bookkeeping.
     */
    private static final class Family
    {
        /** Human-readable family label (e.g. {@code "Motherboard"}) used in log lines. */
        final String name;
        /** Opaque Aura SDK controller pointer owned by this family. */
        final Pointer controller;
        /** Number of LEDs this device exposes; the color buffer is sized {@code 3 * ledCount}. */
        final int ledCount;
        /** Applies a packed RGB byte buffer to the device, returning the SDK status code. */
        final Function< byte[], Integer > applyColor;
        /** Restores default mode and releases the controller on shutdown. */
        final Runnable releaseHandler;

        /** Consecutive render failures since the last success; resets to zero on any success. */
        final AtomicInteger consecutiveFailures = new AtomicInteger( 0 );
        /** True once at least one color push has succeeded — protects against premature drop. */
        volatile boolean succeededOnce = false;
        /** True once the family has been permanently dropped from the render rotation. */
        volatile boolean droppedFromRotation = false;

        /**
         * Creates a family record.
         *
         * @param name           human-readable family label
         * @param controller     opaque Aura SDK controller pointer
         * @param ledCount       number of LEDs on the device
         * @param applyColor     closure that pushes a color buffer and returns the SDK status code
         * @param releaseHandler closure that releases the controller on shutdown
         */
        Family( String name, Pointer controller, int ledCount,
                Function< byte[], Integer > applyColor,
                Runnable releaseHandler )
        {
            this.name = name;
            this.controller = controller;
            this.ledCount = ledCount;
            this.applyColor = applyColor;
            this.releaseHandler = releaseHandler;
        }
    }

    /** Successfully-initialised device families, in probe order. */
    private final List< Family > families = new ArrayList<>();
    /** Whether {@link #start()} completed and at least one family is live. */
    private volatile boolean started = false;

    /** Frame dedup. The same color frame in / out is the static
     *  effect's common case; tracking the last packed RGB and skipping
     *  identical pushes keeps the SDK quiet. */
    private int lastSentPackedRgb = Integer.MIN_VALUE;

    /**
     * {@inheritDoc}
     *
     * @return the fixed display name {@code "ASUS Aura"}
     */
    @Override
    public String name() { return "ASUS Aura"; }

    /**
     * {@inheritDoc}
     *
     * <p>Maps directly to {@link AsusAuraSdkLibrary#isLoadable()} —
     * availability is purely whether {@code AURA_SDK.dll} can be loaded
     * on this host.</p>
     *
     * @return {@code true} if the Aura SDK DLL is loadable
     */
    @Override
    public boolean isAvailable()
    {
        return AsusAuraSdkLibrary.isLoadable();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Loads the Aura SDK and probes each device family
     * (motherboard, GPU, RAM, Claymore keyboard) independently, adding
     * a {@link Family} for every one that reports a non-zero LED count.
     * A family that fails to initialise is skipped without aborting the
     * others.</p>
     *
     * @throws IllegalStateException if the Aura SDK DLL is not loadable,
     *                               or if it loads but no device family
     *                               reports any LEDs
     */
    @Override
    public void start() throws Exception
    {
        if ( !AsusAuraSdkLibrary.isLoadable() ) {
            throw new IllegalStateException( "ASUS Aura SDK DLL (AURA_SDK.dll) not "
                                                     + "loadable — Aura Sync / older "
                                                     + "Armoury Crate not installed?" );
        }

        AsusAuraSdkLibrary sdk = AsusAuraSdkLibrary.INSTANCE;

        // Each family is initialised independently — one failing
        // family (e.g. "user has Aura GPU but no Aura motherboard")
        // doesn't stop the others.
        tryInit( "Motherboard",
                 sdk::CreateMbController, sdk::GetMbLedCount,
                 sdk::SetMbMode, sdk::SetMbColor, sdk::ReleaseMbController );
        tryInit( "GPU",
                 sdk::CreateGPUController, sdk::GetGPULedCount,
                 sdk::SetGPUMode, sdk::SetGPUColor, sdk::ReleaseGPUController );
        tryInit( "RAM",
                 sdk::CreateRAMController, sdk::GetRAMLedCount,
                 sdk::SetRAMMode, sdk::SetRAMColor, sdk::ReleaseRAMController );
        tryInit( "Claymore Keyboard",
                 sdk::CreateClaymoreKeyboard, sdk::GetClaymoreKeyboardLightCount,
                 sdk::SetClaymoreKeyboardMode, sdk::SetClaymoreKeyboardColor,
                 sdk::ReleaseClaymoreKeyboard );

        if ( families.isEmpty() ) {
            throw new IllegalStateException( "ASUS Aura: no compatible device families "
                                                     + "found. The Aura SDK loaded but every "
                                                     + "family probe returned zero LEDs. "
                                                     + "Try OpenRGB for broader ASUS coverage." );
        }
        started = true;
        Logger.logStd( LocalizationManager.format( "log.rgb.asusAura.familiesConnected",
                               families.size(), describeFamilies() ) );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pushes the frame's background color to every active family.
     * Identical consecutive frames are deduplicated (the common static-
     * effect case). Per-family failures are tolerated and tracked for
     * backoff; the call only fails if every active family failed.</p>
     *
     * @param frame the frame whose background color is applied to all families
     * @throws IllegalStateException if at least one family was attempted
     *                               and every attempt failed
     */
    @Override
    public void renderFrame( RgbFrame frame ) throws Exception
    {
        if ( !started || families.isEmpty() ) return;

        RgbColor bg = frame.background();
        int packed = ( bg.r() << 16 ) | ( bg.g() << 8 ) | bg.b();
        if ( packed == lastSentPackedRgb ) {
            return; // dedup
        }

        int successCount = 0;
        int attemptCount = 0;
        int lastError = 0;
        for ( Family fam : families ) {
            if ( fam.droppedFromRotation ) continue;
            attemptCount++;
            try {
                byte[] colorBuf = buildColorBuffer( fam.ledCount,
                                                     (byte) bg.r(), (byte) bg.g(), (byte) bg.b() );
                int rc = fam.applyColor.apply( colorBuf );
                if ( rc >= 0 ) {
                    // Aura's documented return is "0 = success" but
                    // some calls return > 0 (LED count). Both indicate
                    // the call wasn't an error.
                    fam.succeededOnce = true;
                    fam.consecutiveFailures.set( 0 );
                    successCount++;
                }
                else {
                    lastError = rc;
                    handleFamilyFailure( fam, rc );
                }
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.rgb.asusAura.setColorThrew", fam.name ), t );
                handleFamilyFailure( fam, -1 );
            }
        }

        if ( attemptCount > 0 && successCount == 0 ) {
            throw new IllegalStateException( "ASUS Aura: SetXColor failed on every "
                                                     + "active family (last error="
                                                     + lastError + ")" );
        }
        lastSentPackedRgb = packed;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs each family's release handler (restoring default mode and
     * releasing the controller), clears all state, and resets frame
     * deduplication so a subsequent {@link #start()} begins clean.
     * Release failures are logged but never propagated.</p>
     */
    @Override
    public void shutdown()
    {
        for ( Family fam : families ) {
            try { fam.releaseHandler.run(); }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.rgb.asusAura.releaseThrew", fam.name ), t );
            }
        }
        families.clear();
        started = false;
        lastSentPackedRgb = Integer.MIN_VALUE;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Initialise one device family. Pulls the controller pointer,
     * reads the LED count, sets software-control mode, and (on
     * success) appends a {@link Family} to {@link #families}.
     * Failures are silent — a user without (say) Aura RAM still wants
     * the rest of the backend up.
     *
     * @param label      human-readable family label used in log lines
     * @param createFn   {@code CreateXController} binding; populates the out-pointer
     * @param ledCountFn {@code GetXLedCount} binding; returns the device LED count
     * @param setModeFn  {@code SetXMode} binding; switches between software and default control
     * @param setColorFn {@code SetXColor} binding; pushes a color buffer
     * @param releaseFn  {@code ReleaseXController} binding; cleans up the controller
     */
    private void tryInit( String label,
                           java.util.function.Function< PointerByReference, Integer > createFn,
                           java.util.function.Function< Pointer, Integer > ledCountFn,
                           java.util.function.BiFunction< Pointer, Integer, Integer > setModeFn,
                           SetColorFn setColorFn,
                           java.util.function.Function< PointerByReference, Integer > releaseFn )
    {
        PointerByReference ref = new PointerByReference();
        int rc;
        try {
            rc = createFn.apply( ref );
        }
        catch ( Throwable t ) {
            Logger.logDebug( LocalizationManager.format( "log.rgb.asusAura.createControllerThrew", label ) );
            return;
        }
        if ( rc < 0 || ref.getValue() == null ) {
            Logger.logDebug( LocalizationManager.format( "log.rgb.asusAura.noController", label, rc ) );
            return;
        }
        Pointer ctrl = ref.getValue();
        int ledCount;
        try { ledCount = ledCountFn.apply( ctrl ); }
        catch ( Throwable t ) { ledCount = 0; }
        if ( ledCount <= 0 ) {
            Logger.logDebug( LocalizationManager.format( "log.rgb.asusAura.zeroLeds", label ) );
            try { releaseFn.apply( ref ); } catch ( Throwable ignored ) { }
            return;
        }

        // Switch to software control so our SetXColor calls actually
        // change the device's lighting. Best-effort — some controllers
        // ignore SetXMode and still respect SetXColor.
        try { setModeFn.apply( ctrl, AsusAuraSdkLibrary.MODE_SOFTWARE ); }
        catch ( Throwable ignored ) { /* tolerate — see comment above */ }

        final Pointer finalCtrl = ctrl;
        final int finalLedCount = ledCount;
        java.util.function.Function< byte[], Integer > applyColor =
                buf -> setColorFn.apply( finalCtrl, buf, buf.length );
        Runnable release = () -> {
            try {
                setModeFn.apply( finalCtrl, AsusAuraSdkLibrary.MODE_DEFAULT );
            }
            catch ( Throwable ignored ) { }
            try {
                releaseFn.apply( ref );
            }
            catch ( Throwable ignored ) { }
        };
        families.add( new Family( label, ctrl, ledCount, applyColor, release ) );
        Logger.logStd( LocalizationManager.format( "log.rgb.asusAura.familyConnected", label, ledCount ) );
    }

    /**
     * Build a {@code BYTE[3 * ledCount]} color buffer with the same
     * (r, g, b) triple repeated for every LED. RGB byte order per the
     * Aura SDK's documented signature; if a future build shows colors
     * swapped, flipping this to BGR is the first thing to try.
     *
     * @param ledCount number of LEDs on the device
     * @param r        red channel byte
     * @param g        green channel byte
     * @param b        blue channel byte
     * @return a freshly allocated {@code 3 * ledCount}-byte RGB buffer
     */
    private static byte[] buildColorBuffer( int ledCount, byte r, byte g, byte b )
    {
        byte[] buf = new byte[ 3 * ledCount ];
        for ( int i = 0; i < ledCount; i++ ) {
            buf[ i * 3     ] = r;
            buf[ i * 3 + 1 ] = g;
            buf[ i * 3 + 2 ] = b;
        }
        return buf;
    }

    private void handleFamilyFailure( Family fam, int rc )
    {
        int failures = fam.consecutiveFailures.incrementAndGet();
        if ( !fam.succeededOnce && failures >= FAMILY_FAILURE_DROP_THRESHOLD ) {
            fam.droppedFromRotation = true;
            Logger.logStd( LocalizationManager.format( "log.rgb.asusAura.givingUpOnFamily",
                                   fam.name, failures, rc ) );
        }
    }

    private String describeFamilies()
    {
        StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < families.size(); i++ ) {
            if ( i > 0 ) sb.append( ", " );
            Family f = families.get( i );
            sb.append( f.name ).append( " (" ).append( f.ledCount ).append( " LEDs)" );
        }
        return sb.toString();
    }

    /** Small functional shape that the Aura SDK's {@code SetXColor}
     *  signature {@code (Pointer, byte[], int)} doesn't fit into any
     *  built-in {@code java.util.function} type. */
    @FunctionalInterface
    private interface SetColorFn
    {
        Integer apply( Pointer ctrl, byte[] buf, int size );
    }
}

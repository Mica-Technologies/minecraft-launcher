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

    /** One initialised Aura controller (one device family). */
    private static final class Family
    {
        final String name;
        final Pointer controller;
        final int ledCount;
        final Function< byte[], Integer > applyColor;
        final Runnable releaseHandler;

        final AtomicInteger consecutiveFailures = new AtomicInteger( 0 );
        volatile boolean succeededOnce = false;
        volatile boolean droppedFromRotation = false;

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

    private final List< Family > families = new ArrayList<>();
    private volatile boolean started = false;

    /** Frame dedup. The same color frame in / out is the static
     *  effect's common case; tracking the last packed RGB and skipping
     *  identical pushes keeps the SDK quiet. */
    private int lastSentPackedRgb = Integer.MIN_VALUE;

    @Override
    public String name() { return "ASUS Aura"; }

    @Override
    public boolean isAvailable()
    {
        return AsusAuraSdkLibrary.isLoadable();
    }

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

    /** Initialise one device family. Pulls the controller pointer,
     *  reads the LED count, sets software-control mode, and (on
     *  success) appends a {@link Family} to {@link #families}.
     *  Failures are silent — a user without (say) Aura RAM still wants
     *  the rest of the backend up. */
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

    /** Build a {@code BYTE[3 * ledCount]} color buffer with the same
     *  (r, g, b) triple repeated for every LED. RGB byte order per the
     *  Aura SDK's documented signature; if a future build shows colors
     *  swapped, flipping this to BGR is the first thing to try. */
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

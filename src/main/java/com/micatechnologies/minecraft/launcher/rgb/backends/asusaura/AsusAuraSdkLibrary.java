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

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.apache.commons.lang3.SystemUtils;

import java.util.Arrays;
import java.util.List;

/**
 * JNA binding for ASUS's legacy {@code AURA_SDK.dll}. This is the
 * publicly-distributed Aura SDK that shipped with the original Aura
 * Sync software — flat C function exports, no COM, one "controller"
 * pointer per device family that the SDK manages opaquely.
 *
 * <h3>Caveats</h3>
 *
 * <p>ASUS's RGB SDK story has fragmented since the original Aura
 * Sync:</p>
 *
 * <ul>
 *   <li><b>AURA_SDK.dll</b> (this binding) — older, but the only one
 *       still freely distributed. Covers motherboard ARGB, GPUs,
 *       Aura-branded RAM, and some keyboards / mice.</li>
 *   <li><b>AuraServiceSDK</b> — newer Armoury Crate-era SDK. Gated
 *       behind a developer agreement; we don't ship binding for it.</li>
 *   <li><b>OpenRGB</b> — community alternative with reverse-engineered
 *       drivers; covers most ASUS hardware. Users who can run
 *       OpenRGB get broader coverage than this backend.</li>
 * </ul>
 *
 * <p>If {@code AURA_SDK.dll} isn't installed, {@link #isLoadable()}
 * returns false and the backend gracefully skips. Users with ASUS
 * hardware running Armoury Crate (not Aura Sync) will land in this
 * skip path — they should keep OpenRGB in their Auto-mode rotation.</p>
 *
 * <h3>API surface used</h3>
 *
 * <p>Per-family lifecycle is the same shape across device families
 * (Motherboard / GPU / RAM / Keyboard / Mouse):</p>
 *
 * <ol>
 *   <li>{@code CreateXController(out ptr)} — initialise the
 *       controller. Returns the LED count when nonzero (some headers
 *       document this as "0 = none, &gt;0 = led count"; others as
 *       "0 = success, error otherwise"). Treat &gt; 0 as success and
 *       use {@code GetXLightCount} for the real LED count.</li>
 *   <li>{@code SetXMode(ptr, mode)} — {@code mode = 1} hands software
 *       control to us; {@code mode = 0} returns control to Aura.</li>
 *   <li>{@code SetXColor(ptr, BYTE* buf, DWORD bytes)} — buffer is
 *       {@code 3 * ledCount} bytes in RGB order (verified for
 *       motherboard / GPU / RAM; keyboard varies by model).</li>
 *   <li>{@code GetXLightCount(ptr)} — number of LEDs on the device.</li>
 *   <li>{@code ReleaseXController(out ptr)} — clean up.</li>
 * </ol>
 *
 * @since 2026.5
 */
public interface AsusAuraSdkLibrary extends StdCallLibrary
{
    /** Software-control mode. {@code SetXMode(ptr, MODE_SOFTWARE)} is
     *  required before {@code SetXColor} will actually change anything
     *  on most devices — Aura otherwise overwrites our colors with its
     *  own effect at the next refresh. */
    int MODE_SOFTWARE = 1;

    /** Default / hardware mode. Returns control to whatever effect
     *  Aura was running before we took over. */
    int MODE_DEFAULT  = 0;

    /** Lazily loaded — null on non-Windows or when Aura SDK isn't installed. */
    AsusAuraSdkLibrary INSTANCE = loadOrNull();

    // ====================================================================
    // Motherboard
    // ====================================================================

    int CreateMbController( PointerByReference pCtrl );
    int ReleaseMbController( PointerByReference pCtrl );
    int GetMbLedCount( Pointer pCtrl );
    int SetMbColor( Pointer pCtrl, byte[] colorBuf, int bufSize );
    int SetMbMode( Pointer pCtrl, int mode );

    // ====================================================================
    // GPU
    // ====================================================================

    int CreateGPUController( PointerByReference pCtrl );
    int ReleaseGPUController( PointerByReference pCtrl );
    int GetGPULedCount( Pointer pCtrl );
    int SetGPUColor( Pointer pCtrl, byte[] colorBuf, int bufSize );
    int SetGPUMode( Pointer pCtrl, int mode );

    // ====================================================================
    // DRAM
    // ====================================================================

    int CreateRAMController( PointerByReference pCtrl );
    int ReleaseRAMController( PointerByReference pCtrl );
    int GetRAMLedCount( Pointer pCtrl );
    int SetRAMColor( Pointer pCtrl, byte[] colorBuf, int bufSize );
    int SetRAMMode( Pointer pCtrl, int mode );

    // ====================================================================
    // Claymore keyboard (the family that's the most widely-deployed
    // ASUS keyboard line covered by this SDK)
    // ====================================================================

    int CreateClaymoreKeyboard( PointerByReference pCtrl );
    int ReleaseClaymoreKeyboard( PointerByReference pCtrl );
    int GetClaymoreKeyboardLightCount( Pointer pCtrl );
    int SetClaymoreKeyboardColor( Pointer pCtrl, byte[] colorBuf, int bufSize );
    int SetClaymoreKeyboardMode( Pointer pCtrl, int mode );

    /** True iff {@code AURA_SDK.dll} is loadable on this host. The
     *  backend's {@code isAvailable()} maps directly to this. */
    static boolean isLoadable()
    {
        return INSTANCE != null;
    }

    /** Try common Aura SDK DLL names. The 64-bit DLL typically ships
     *  as just {@code AURA_SDK.dll}; some installer variants drop a
     *  {@code .x64} suffix. */
    private static AsusAuraSdkLibrary loadOrNull()
    {
        if ( !SystemUtils.IS_OS_WINDOWS ) return null;
        List< String > candidates = Arrays.asList(
                "AURA_SDK",
                "AuraSDK",
                "AURA_SDK.x64"
        );
        for ( String name : candidates ) {
            try { return Native.load( name, AsusAuraSdkLibrary.class ); }
            catch ( Throwable ignored ) { /* try the next candidate */ }
        }
        return null;
    }
}

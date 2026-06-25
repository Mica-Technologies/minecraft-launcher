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

    /**
     * Initialise the motherboard ARGB controller.
     *
     * @param pCtrl out-parameter receiving the opaque controller pointer
     * @return SDK status code; a non-negative value (some headers report
     *         the LED count) indicates the controller was created
     */
    int CreateMbController( PointerByReference pCtrl );

    /**
     * Release the motherboard ARGB controller and free its resources.
     *
     * @param pCtrl pointer reference to the controller to release
     * @return SDK status code
     */
    int ReleaseMbController( PointerByReference pCtrl );

    /**
     * Read the number of addressable LEDs on the motherboard controller.
     *
     * @param pCtrl the motherboard controller pointer
     * @return the LED count; the color buffer must be {@code 3 * count} bytes
     */
    int GetMbLedCount( Pointer pCtrl );

    /**
     * Push a packed RGB color buffer to the motherboard controller.
     *
     * @param pCtrl    the motherboard controller pointer
     * @param colorBuf {@code 3 * ledCount}-byte buffer in RGB order
     * @param bufSize  length of {@code colorBuf} in bytes
     * @return SDK status code; treat a non-negative value as success
     */
    int SetMbColor( Pointer pCtrl, byte[] colorBuf, int bufSize );

    /**
     * Set the motherboard controller's lighting mode.
     *
     * @param pCtrl the motherboard controller pointer
     * @param mode  {@link #MODE_SOFTWARE} to take software control or
     *              {@link #MODE_DEFAULT} to return control to Aura
     * @return SDK status code
     */
    int SetMbMode( Pointer pCtrl, int mode );

    // ====================================================================
    // GPU
    // ====================================================================

    /**
     * Initialise the GPU controller (ASUS ROG-branded cards).
     *
     * @param pCtrl out-parameter receiving the opaque controller pointer
     * @return SDK status code; a non-negative value indicates the
     *         controller was created
     */
    int CreateGPUController( PointerByReference pCtrl );

    /**
     * Release the GPU controller and free its resources.
     *
     * @param pCtrl pointer reference to the controller to release
     * @return SDK status code
     */
    int ReleaseGPUController( PointerByReference pCtrl );

    /**
     * Read the number of addressable LEDs on the GPU controller.
     *
     * @param pCtrl the GPU controller pointer
     * @return the LED count; the color buffer must be {@code 3 * count} bytes
     */
    int GetGPULedCount( Pointer pCtrl );

    /**
     * Push a packed RGB color buffer to the GPU controller.
     *
     * @param pCtrl    the GPU controller pointer
     * @param colorBuf {@code 3 * ledCount}-byte buffer in RGB order
     * @param bufSize  length of {@code colorBuf} in bytes
     * @return SDK status code; treat a non-negative value as success
     */
    int SetGPUColor( Pointer pCtrl, byte[] colorBuf, int bufSize );

    /**
     * Set the GPU controller's lighting mode.
     *
     * @param pCtrl the GPU controller pointer
     * @param mode  {@link #MODE_SOFTWARE} to take software control or
     *              {@link #MODE_DEFAULT} to return control to Aura
     * @return SDK status code
     */
    int SetGPUMode( Pointer pCtrl, int mode );

    // ====================================================================
    // DRAM
    // ====================================================================

    /**
     * Initialise the DRAM controller (Aura-compatible memory modules).
     *
     * @param pCtrl out-parameter receiving the opaque controller pointer
     * @return SDK status code; a non-negative value indicates the
     *         controller was created
     */
    int CreateRAMController( PointerByReference pCtrl );

    /**
     * Release the DRAM controller and free its resources.
     *
     * @param pCtrl pointer reference to the controller to release
     * @return SDK status code
     */
    int ReleaseRAMController( PointerByReference pCtrl );

    /**
     * Read the number of addressable LEDs across the DRAM modules.
     *
     * @param pCtrl the DRAM controller pointer
     * @return the LED count; the color buffer must be {@code 3 * count} bytes
     */
    int GetRAMLedCount( Pointer pCtrl );

    /**
     * Push a packed RGB color buffer to the DRAM controller.
     *
     * @param pCtrl    the DRAM controller pointer
     * @param colorBuf {@code 3 * ledCount}-byte buffer in RGB order
     * @param bufSize  length of {@code colorBuf} in bytes
     * @return SDK status code; treat a non-negative value as success
     */
    int SetRAMColor( Pointer pCtrl, byte[] colorBuf, int bufSize );

    /**
     * Set the DRAM controller's lighting mode.
     *
     * @param pCtrl the DRAM controller pointer
     * @param mode  {@link #MODE_SOFTWARE} to take software control or
     *              {@link #MODE_DEFAULT} to return control to Aura
     * @return SDK status code
     */
    int SetRAMMode( Pointer pCtrl, int mode );

    // ====================================================================
    // Claymore keyboard (the family that's the most widely-deployed
    // ASUS keyboard line covered by this SDK)
    // ====================================================================

    /**
     * Initialise the Claymore keyboard controller (the original ASUS
     * Aura keyboard family).
     *
     * @param pCtrl out-parameter receiving the opaque controller pointer
     * @return SDK status code; a non-negative value indicates the
     *         controller was created
     */
    int CreateClaymoreKeyboard( PointerByReference pCtrl );

    /**
     * Release the Claymore keyboard controller and free its resources.
     *
     * @param pCtrl pointer reference to the controller to release
     * @return SDK status code
     */
    int ReleaseClaymoreKeyboard( PointerByReference pCtrl );

    /**
     * Read the number of addressable lights on the Claymore keyboard.
     *
     * @param pCtrl the Claymore keyboard controller pointer
     * @return the light count; the color buffer must be {@code 3 * count} bytes
     */
    int GetClaymoreKeyboardLightCount( Pointer pCtrl );

    /**
     * Push a packed RGB color buffer to the Claymore keyboard.
     *
     * @param pCtrl    the Claymore keyboard controller pointer
     * @param colorBuf {@code 3 * lightCount}-byte buffer in RGB order
     * @param bufSize  length of {@code colorBuf} in bytes
     * @return SDK status code; treat a non-negative value as success
     */
    int SetClaymoreKeyboardColor( Pointer pCtrl, byte[] colorBuf, int bufSize );

    /**
     * Set the Claymore keyboard controller's lighting mode.
     *
     * @param pCtrl the Claymore keyboard controller pointer
     * @param mode  {@link #MODE_SOFTWARE} to take software control or
     *              {@link #MODE_DEFAULT} to return control to Aura
     * @return SDK status code
     */
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

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

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import org.apache.commons.lang3.SystemUtils;

/**
 * JNA binding for Razer's native Chroma SDK ({@code RzChromaSDK64.dll}
 * on Windows 64-bit; {@code RzChromaSDK.dll} on 32-bit).
 *
 * <p>This is the SDK Fortnite, Overwatch, Diablo IV, and similar Razer-
 * integrated games use. It's been around since Razer Synapse 2 and is
 * the path Razer themselves still maintain — the REST API at
 * {@code localhost:54235} that the launcher's other Chroma backend
 * uses has been effectively deprecated on recent Synapse releases,
 * which is why that backend hits {@code result=126} on every effect
 * push.</p>
 *
 * <h3>Function summary</h3>
 *
 * <p>The SDK exposes a small surface area:</p>
 *
 * <ul>
 *   <li>{@link #Init()} / {@link #InitSDK(Pointer)} — initialize. Loads
 *       per-device modules from Synapse's module directory. Returns 0
 *       on success.</li>
 *   <li>{@link #UnInit()} — clean up. Synapse returns devices to their
 *       last user-configured state after this.</li>
 *   <li>{@code CreateXxxEffect} per device family — build an effect for
 *       a device type and get an effect ID back. Each device family has
 *       its own enum of {@code EFFECT_TYPE} values and its own per-effect
 *       parameter struct.</li>
 *   <li>{@link #SetEffect(Pointer)} — apply the given effect ID to all
 *       currently-connected devices of the appropriate family.</li>
 *   <li>{@link #DeleteEffect(Pointer)} — free the effect ID's underlying
 *       resources. Called after SetEffect so we don't leak GUIDs over the
 *       session.</li>
 * </ul>
 *
 * <h3>Calling convention</h3>
 *
 * <p>{@link StdCallLibrary} because the SDK uses {@code __stdcall} on
 * 32-bit Windows. JNA ignores the convention on 64-bit (where everything
 * is fastcall), so either Library or StdCallLibrary works there; we use
 * StdCall for consistency with the launcher's other Win32 bindings
 * ({@code WindowsDpiAwareness}, {@code WindowChromeManager}).</p>
 *
 * <h3>Effect IDs as 16-byte GUIDs</h3>
 *
 * <p>{@code RZEFFECTID} is a GUID (16 bytes). We treat it as an opaque
 * blob — caller allocates a 16-byte {@link com.sun.jna.Memory} for the
 * out parameter, then passes the same pointer back to SetEffect /
 * DeleteEffect. Never need to inspect the GUID's contents.</p>
 *
 * @since 2026.5
 */
interface RzChromaSdkLibrary extends StdCallLibrary
{
    /** Lazy-initialized library handle. Null on non-Windows or when the
     *  SDK isn't installed; callers check before using. */
    RzChromaSdkLibrary INSTANCE = loadOrNull();

    /** Returns 0 ({@code RZRESULT_SUCCESS}) on success. */
    int Init();

    /** Like {@link #Init()} but registers the calling app with Synapse
     *  — the app's title / description shows up in Synapse's connected-
     *  apps list. {@code appInfoPtr} points to a packed {@code APPINFOTYPE}
     *  struct; see {@link ChromaAppInfo} for the layout. */
    int InitSDK( Pointer appInfoPtr );

    /** Returns 0 on success. After this call the SDK is shut down and
     *  cannot be reinitialized without re-loading the DLL. */
    int UnInit();

    /**
     * Build a keyboard effect.
     *
     * @param effect       enum value from {@code ChromaSDK::Keyboard::EFFECT_TYPE}
     * @param paramPtr     pointer to effect-specific param struct (e.g.
     *                     a 6×22 COLORREF array for CHROMA_CUSTOM, or a
     *                     single COLORREF for CHROMA_STATIC). May be
     *                     null for effects that take no params (NONE).
     * @param effectIdOut  16-byte buffer that receives the new effect's
     *                     GUID. Pass the same pointer to SetEffect /
     *                     DeleteEffect.
     * @return 0 on success.
     */
    int CreateKeyboardEffect( int effect, Pointer paramPtr, Pointer effectIdOut );

    /** Build a mouse effect. See {@link #CreateKeyboardEffect} for arg
     *  semantics — same shape, different per-device enum values. */
    int CreateMouseEffect( int effect, Pointer paramPtr, Pointer effectIdOut );

    /** Build a mousepad effect. */
    int CreateMousepadEffect( int effect, Pointer paramPtr, Pointer effectIdOut );

    /** Build a headset effect. */
    int CreateHeadsetEffect( int effect, Pointer paramPtr, Pointer effectIdOut );

    /** Build a keypad effect. */
    int CreateKeypadEffect( int effect, Pointer paramPtr, Pointer effectIdOut );

    /** Build a ChromaLink effect. ChromaLink is Razer's "third-party
     *  controller" bus — addressable RGB fan controllers, ARGB strip
     *  hubs, etc. This is the family the launcher's keyboardless-Razer-
     *  rig users (e.g. ARGB-fans-only) need to drive. */
    int CreateChromaLinkEffect( int effect, Pointer paramPtr, Pointer effectIdOut );

    /** Apply an already-created effect to its target devices. The SDK
     *  silently no-ops if no device of the effect's family is connected. */
    int SetEffect( Pointer effectId );

    /** Release the resources tied to an effect ID. Should be called
     *  after the effect's been applied via SetEffect, or when ditching
     *  an effect that was never set. */
    int DeleteEffect( Pointer effectId );

    /** Returns true when the SDK is actually loadable on this system.
     *  Used by the backend's isAvailable probe. */
    static boolean isLoadable()
    {
        return INSTANCE != null;
    }

    /** Resolves the SDK DLL, returning {@code null} on every non-Windows
     *  platform or when Synapse isn't installed (DLL not on PATH). */
    private static RzChromaSdkLibrary loadOrNull()
    {
        if ( !SystemUtils.IS_OS_WINDOWS ) return null;
        try {
            // The DLL is normally registered into the Windows DLL search
            // path by the Razer Chroma SDK installer (part of Synapse).
            // 32-bit JVMs would want RzChromaSDK.dll; the launcher ships
            // a 64-bit JVM so we always want the 64-bit DLL.
            return Native.load( "RzChromaSDK64", RzChromaSdkLibrary.class );
        }
        catch ( Throwable t ) {
            return null;
        }
    }
}

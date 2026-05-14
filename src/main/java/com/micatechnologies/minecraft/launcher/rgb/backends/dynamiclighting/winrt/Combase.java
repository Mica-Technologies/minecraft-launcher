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

package com.micatechnologies.minecraft.launcher.rgb.backends.dynamiclighting.winrt;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.apache.commons.lang3.SystemUtils;

/**
 * JNA binding for the WinRT activation surface in {@code combase.dll}.
 * Just the entry points we need to reach
 * {@code Windows.Devices.Lights.LampArray} and the helpers around it:
 * runtime init, HSTRING lifetime, and {@code RoGetActivationFactory}.
 *
 * <p>{@code combase.dll} is the Windows Runtime "base" DLL — it hosts
 * the activation table, HSTRING allocator, and the metadata-driven
 * QueryInterface plumbing. Ships in-box on every Windows 10/11 install,
 * so no install check is needed beyond {@link SystemUtils#IS_OS_WINDOWS}.</p>
 *
 * @since 2026.5
 */
public interface Combase extends StdCallLibrary
{
    /** Single-threaded apartment. We do NOT want this — WinDL's async
     *  operations need to dispatch their completion callbacks freely. */
    int RO_INIT_SINGLETHREADED = 0;

    /** Multi-threaded apartment. Required when we want to poll
     *  {@code IAsyncInfo::Status} from a worker thread without WinRT
     *  trying to pump messages on our behalf. */
    int RO_INIT_MULTITHREADED  = 1;

    /** Lazy-load. Null on non-Windows hosts; backend's {@code isAvailable}
     *  short-circuits before reaching anything that would NPE. */
    Combase INSTANCE = SystemUtils.IS_OS_WINDOWS ? loadOrNull() : null;

    private static Combase loadOrNull()
    {
        try { return Native.load( "combase", Combase.class ); }
        catch ( Throwable t ) { return null; }
    }

    /**
     * Initialize the Windows Runtime for the calling thread. Must be
     * called once per thread that touches WinRT objects. Subsequent
     * calls on the same thread are reference-counted and return
     * {@code S_FALSE} (1) — also acceptable.
     *
     * @param initType {@link #RO_INIT_MULTITHREADED} for our use case.
     * @return HRESULT — 0 ({@code S_OK}) or 1 ({@code S_FALSE} = already
     *         initialised) are both success. Negative values are errors.
     */
    int RoInitialize( int initType );

    /** Releases the WinRT runtime for the calling thread. Reference-
     *  counted against {@link #RoInitialize} calls — only the final
     *  matching {@code RoUninitialize} actually tears the apartment
     *  down. */
    void RoUninitialize();

    /**
     * Allocate a new HSTRING from a wide-char buffer. The caller owns
     * the resulting HSTRING and must release it with
     * {@link #WindowsDeleteString} when done.
     *
     * @param sourceString  wide-char buffer (UTF-16LE), e.g. a
     *                      {@link WString} or {@code wchar_t*} pointer.
     * @param length        length in WCHARs (NOT bytes).
     * @param string        out — receives the HSTRING handle.
     * @return HRESULT.
     */
    int WindowsCreateString( WString sourceString, int length, PointerByReference string );

    /** Release an HSTRING allocated by {@link #WindowsCreateString} or
     *  returned to us by a WinRT method. */
    int WindowsDeleteString( Pointer string );

    /**
     * Return the raw {@code wchar_t*} backing an HSTRING along with its
     * length. The pointer is owned by the HSTRING — do NOT free it or
     * write to it; just read until {@code length} WCHARs are consumed.
     *
     * @param string  HSTRING handle.
     * @param length  out — length in WCHARs. May be null.
     * @return pointer to the wide-char buffer, valid until
     *         {@link #WindowsDeleteString} releases the HSTRING.
     */
    Pointer WindowsGetStringRawBuffer( Pointer string, com.sun.jna.ptr.IntByReference length );

    /**
     * Get the activation factory for a runtime class. The
     * {@code activatableClassId} is the fully-qualified class name as
     * an HSTRING (e.g. "Windows.Devices.Lights.LampArray"); {@code iid}
     * is the IID of the statics interface you want
     * (e.g. {@code ILampArrayStatics}).
     *
     * @param activatableClassId  HSTRING of the runtime class name.
     * @param iid                 by-ref IID for the statics interface.
     * @param factory             out — receives the activation factory
     *                            pointer (cast to the requested interface).
     * @return HRESULT.
     */
    int RoGetActivationFactory( Pointer activatableClassId, Guid.IID.ByReference iid,
                                PointerByReference factory );

    // ====================================================================
    // Constants — HRESULT, IIDs, runtime class names
    // ====================================================================

    /** HRESULT success. */
    int S_OK = 0x00000000;

    /** HRESULT "already initialised on this thread" — also success. */
    int S_FALSE = 0x00000001;

    /**
     * Wrapper that ensures the WinRT runtime is loadable on this host.
     * Returns false on non-Windows or when {@code combase.dll} isn't
     * present (vanishingly unlikely on any supported Windows install,
     * but cheap to check).
     */
    static boolean isLoadable()
    {
        return INSTANCE != null;
    }

    /** Light wrapper that JNA's PointerType can use for HSTRING params.
     *  HSTRING is opaque — we never inspect its bits, so {@code Pointer}
     *  is enough. */
    final class Hstring extends PointerType
    {
        public Hstring() {}
        public Hstring( Pointer p ) { super( p ); }
    }
}

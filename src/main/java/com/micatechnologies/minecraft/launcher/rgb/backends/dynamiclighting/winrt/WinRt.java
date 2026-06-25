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

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.charset.StandardCharsets;

/**
 * Small WinRT-over-JNA toolkit: vtable-method dispatch, HSTRING
 * lifetime, IID parsing, IAsyncOperation polling. Everything the
 * {@code dynamiclighting} backend uses to talk to
 * {@code Windows.Devices.Lights} via {@code combase.dll}.
 *
 * <h3>How COM/WinRT calls work through JNA</h3>
 *
 * <p>A COM/WinRT interface pointer points to a struct whose first field
 * is a pointer to the v-table (an array of function pointers). Method
 * <i>N</i> on the interface is invoked by:</p>
 *
 * <ol>
 *   <li>Reading the v-table pointer at offset 0 of the interface
 *       pointer.</li>
 *   <li>Reading the function pointer at offset <i>N × ptrSize</i> from
 *       the v-table.</li>
 *   <li>Calling that function pointer with the interface pointer as
 *       the first argument (the {@code this} pointer).</li>
 * </ol>
 *
 * <p>Every method's first arg is the {@code this} pointer; the rest
 * match the interface's IDL declaration. All return HRESULT
 * (32-bit int), except {@code IUnknown::AddRef} and {@code Release},
 * which return ULONG (the new refcount). On x64 Windows the calling
 * convention is uniform — JNA's default {@link Function#getFunction}
 * is sufficient.</p>
 *
 * <h3>Vtable indices used by this backend</h3>
 *
 * <p>Confirmed against the SDK headers at
 * {@code C:/Program Files (x86)/Windows Kits/10/Include/.../winrt/}.
 * Index 0 = {@code IUnknown::QueryInterface}, 1 = {@code IUnknown::AddRef},
 * 2 = {@code IUnknown::Release}, 3-5 = {@code IInspectable} methods, 6+ =
 * interface-specific.</p>
 *
 * @since 2026.5
 */
public final class WinRt
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private WinRt() { /* static-only */ }

    // ====================================================================
    // Vtable indices (relative to start of the interface)
    // ====================================================================

    /** Index 0 of every COM/WinRT interface — {@code IUnknown::QueryInterface}. */
    public static final int IUNKNOWN_QUERY_INTERFACE       = 0;

    /** Index 1 of every COM/WinRT interface — {@code IUnknown::AddRef}. */
    public static final int IUNKNOWN_ADD_REF               = 1;

    /** Index 2 of every COM/WinRT interface — {@code IUnknown::Release}. */
    public static final int IUNKNOWN_RELEASE               = 2;

    /** Index 7 of {@code IAsyncInfo} — {@code get_Status}. */
    public static final int IASYNC_INFO_GET_STATUS         = 7;

    /** Index 8 of {@code IAsyncInfo} — {@code get_ErrorCode}. */
    public static final int IASYNC_INFO_GET_ERROR_CODE     = 8;

    /** Index 8 of {@code IAsyncOperation<T>} — {@code GetResults}. The
     *  layout is identical across every {@code IAsyncOperation<T>}
     *  instantiation (the type parameter only affects the IID, not the
     *  v-table shape). */
    public static final int IASYNC_OPERATION_GET_RESULTS   = 8;

    /** Index 6 of {@code IDeviceInformation} — {@code get_Id}. */
    public static final int IDEVICE_INFORMATION_GET_ID     = 6;

    /** Index 6 of {@code IVectorView<T>} — {@code GetAt(index, T**)}. */
    public static final int IVECTORVIEW_GET_AT             = 6;

    /** Index 7 of {@code IVectorView<T>} — {@code get_Size(uint32*)}. */
    public static final int IVECTORVIEW_GET_SIZE           = 7;

    /** Index 6 of {@code ILampArrayStatics} — {@code GetDeviceSelector(HSTRING*)}. */
    public static final int ILAMP_ARRAY_STATICS_GET_DEVICE_SELECTOR = 6;

    /** Index 7 of {@code ILampArrayStatics} — {@code FromIdAsync(HSTRING, IAsyncOperation**)}. */
    public static final int ILAMP_ARRAY_STATICS_FROM_ID_ASYNC       = 7;

    /** Index 10 of {@code IDeviceInformationStatics} —
     *  {@code FindAllAsyncAqsFilter(HSTRING, IAsyncOperation**)}. We use
     *  this rather than the bare {@code FindAllAsync} (index 8) so the
     *  enumeration is scoped to the LampArray selector — much faster
     *  than enumerating every PnP device on the host. */
    public static final int IDEV_INFO_STATICS_FIND_ALL_AQS           = 10;

    /** Index 23 of {@code ILampArray} — {@code SetColor(Color)}. The
     *  one method this whole backend exists to call. */
    public static final int ILAMP_ARRAY_SET_COLOR                    = 23;

    // ====================================================================
    // Async status codes (Windows.Foundation.AsyncStatus enum)
    // ====================================================================

    /** {@code AsyncStatus.Started} — the async operation is still running. */
    public static final int ASYNC_STARTED   = 0;

    /** {@code AsyncStatus.Completed} — the async operation finished successfully. */
    public static final int ASYNC_COMPLETED = 1;

    /** {@code AsyncStatus.Canceled} — the async operation was cancelled. */
    public static final int ASYNC_CANCELED  = 2;

    /** {@code AsyncStatus.Error} — the async operation faulted; inspect
     *  {@code IAsyncInfo::get_ErrorCode} for the HRESULT. */
    public static final int ASYNC_ERROR     = 3;

    // ====================================================================
    // IIDs
    // ====================================================================

    /** {@code IAsyncInfo} — base for every {@code IAsyncOperation<T>}.
     *  We QueryInterface to this so we can poll Status without needing
     *  to know T. */
    public static final Guid.IID IID_IASYNC_INFO =
            new Guid.IID( "{00000036-0000-0000-C000-000000000046}" );

    /** {@code Windows.Devices.Lights.ILampArrayStatics}. Verified
     *  against the Windows SDK 10.0.26100.0 header
     *  {@code windows.devices.lights.h}. */
    public static final Guid.IID IID_ILAMP_ARRAY_STATICS =
            new Guid.IID( "{7BB8C98D-5FC1-452D-BB1F-4AD410D398FF}" );

    /** {@code Windows.Devices.Enumeration.IDeviceInformationStatics}. */
    public static final Guid.IID IID_IDEV_INFO_STATICS =
            new Guid.IID( "{C17F100E-3A46-4A78-8013-769DC9B97390}" );

    // ====================================================================
    // Runtime class names (passed as HSTRINGs to RoGetActivationFactory)
    // ====================================================================

    /** Fully-qualified runtime class name for {@code LampArray}, passed
     *  as an HSTRING to {@code RoGetActivationFactory} to reach
     *  {@code ILampArrayStatics}. */
    public static final String CLASS_LAMP_ARRAY = "Windows.Devices.Lights.LampArray";

    /** Fully-qualified runtime class name for {@code DeviceInformation},
     *  passed as an HSTRING to {@code RoGetActivationFactory} to reach
     *  {@code IDeviceInformationStatics}. */
    public static final String CLASS_DEVICE_INFORMATION =
            "Windows.Devices.Enumeration.DeviceInformation";

    // ====================================================================
    // Vtable invocation
    // ====================================================================

    /**
     * Invoke method {@code index} on the COM/WinRT interface at
     * {@code iface}, passing {@code args} after the implicit
     * {@code this}-pointer first arg. The function is treated as
     * returning HRESULT (32-bit int).
     *
     * @param iface pointer to the COM/WinRT interface (the {@code this}
     *              pointer prepended as the first native argument).
     * @param index zero-based v-table slot of the method to invoke.
     * @param args  the remaining native arguments, in IDL order.
     * @return the HRESULT returned by the native method.
     */
    public static int invokeHr( Pointer iface, int index, Object... args )
    {
        Function fn = methodAt( iface, index );
        Object[] all = new Object[ args.length + 1 ];
        all[ 0 ] = iface;
        System.arraycopy( args, 0, all, 1, args.length );
        Object res = fn.invoke( Integer.class, all );
        return ( (Integer) res ).intValue();
    }

    /**
     * Invoke method {@code index} on {@code iface}, treating the return
     * as ULONG (used by {@code AddRef} / {@code Release}).
     *
     * @param iface pointer to the COM/WinRT interface (the {@code this}
     *              pointer prepended as the first native argument).
     * @param index zero-based v-table slot of the method to invoke.
     * @param args  the remaining native arguments, in IDL order.
     * @return the ULONG returned by the native method (typically the new
     *         reference count).
     */
    public static int invokeUlong( Pointer iface, int index, Object... args )
    {
        Function fn = methodAt( iface, index );
        Object[] all = new Object[ args.length + 1 ];
        all[ 0 ] = iface;
        System.arraycopy( args, 0, all, 1, args.length );
        Object res = fn.invoke( Integer.class, all );
        return ( (Integer) res ).intValue();
    }

    /**
     * Call {@code IUnknown::Release} on {@code iface}. No-op when null or
     * the null pointer. Best-effort — any thrown exception is swallowed so
     * this is safe to use on shutdown / cleanup paths.
     *
     * @param iface the interface pointer to release; ignored when null.
     */
    public static void release( Pointer iface )
    {
        if ( iface == null || Pointer.nativeValue( iface ) == 0L ) return;
        try { invokeUlong( iface, IUNKNOWN_RELEASE ); }
        catch ( Throwable ignored ) { /* shutdown path — must not throw */ }
    }

    /** Resolve method {@code index} on the v-table of {@code iface}.
     *  Reads the v-table pointer from offset 0 of the interface, then
     *  the function pointer at the right slot. */
    private static Function methodAt( Pointer iface, int index )
    {
        Pointer vtbl = iface.getPointer( 0 );
        Pointer fnPtr = vtbl.getPointer( (long) index * com.sun.jna.Native.POINTER_SIZE );
        return Function.getFunction( fnPtr );
    }

    // ====================================================================
    // HSTRING helpers
    // ====================================================================

    /**
     * Allocate an HSTRING from a Java string. Caller owns the returned
     * pointer and must release with
     * {@link Combase#WindowsDeleteString(Pointer)}.
     *
     * @param s the Java string to copy into a new HSTRING; a null value is
     *          treated as the empty string.
     * @return the newly allocated HSTRING handle, owned by the caller.
     * @throws WinRtException if {@code WindowsCreateString} fails.
     */
    public static Pointer createHstring( String s ) throws WinRtException
    {
        if ( s == null ) s = "";
        PointerByReference out = new PointerByReference();
        int hr = Combase.INSTANCE.WindowsCreateString( new WString( s ), s.length(), out );
        check( hr, "WindowsCreateString(\"" + s + "\")" );
        return out.getValue();
    }

    /**
     * Read an HSTRING back into a Java string. Does NOT release the
     * HSTRING — the caller manages its lifetime.
     *
     * @param hstr the HSTRING handle to read; null / null-pointer / empty
     *             handles yield the empty string.
     * @return the decoded Java string, or the empty string when the handle
     *         is null or carries no characters.
     */
    public static String readHstring( Pointer hstr )
    {
        if ( hstr == null || Pointer.nativeValue( hstr ) == 0L ) return "";
        IntByReference lenRef = new IntByReference();
        Pointer raw = Combase.INSTANCE.WindowsGetStringRawBuffer( hstr, lenRef );
        if ( raw == null ) return "";
        int chars = lenRef.getValue();
        if ( chars <= 0 ) return "";
        byte[] buf = raw.getByteArray( 0, chars * 2 );
        return new String( buf, StandardCharsets.UTF_16LE );
    }

    /** Release an HSTRING handle. Best-effort — exceptions swallowed. */
    public static void deleteHstring( Pointer hstr )
    {
        if ( hstr == null || Pointer.nativeValue( hstr ) == 0L ) return;
        try { Combase.INSTANCE.WindowsDeleteString( hstr ); }
        catch ( Throwable ignored ) { }
    }

    // ====================================================================
    // Color marshalling
    // ====================================================================

    /**
     * Pack a {@code Windows.UI.Color} struct into the 32-bit register
     * representation Win64 expects for by-value passing.
     *
     * <p>The native struct lays out as
     * {@code { BYTE A; BYTE R; BYTE G; BYTE B; }} starting at offset 0.
     * Loaded into a single 32-bit register on a little-endian host that
     * becomes {@code (B << 24) | (G << 16) | (R << 8) | A} — NOT the
     * intuitive ARGB packing. Easy to get wrong; centralised here.</p>
     */
    public static int packWinUiColor( int a, int r, int g, int b )
    {
        return ( ( b & 0xFF ) << 24 )
                | ( ( g & 0xFF ) << 16 )
                | ( ( r & 0xFF ) << 8 )
                |   ( a & 0xFF );
    }

    // ====================================================================
    // IAsyncOperation polling
    // ====================================================================

    /**
     * Block until the given {@code IAsyncOperation<T>} reports
     * Completed, or until {@code timeoutMs} elapses. Polls
     * {@code IAsyncInfo::Status} on a short sleep loop — simpler than
     * implementing an {@code IAsyncOperationCompletedHandler} callback
     * in Java (which would require generating a COM object with our own
     * v-table — non-trivial through JNA).
     *
     * <p>Returns the final {@code AsyncStatus} value. Anything other than
     * {@link #ASYNC_COMPLETED} means the async op failed, was cancelled,
     * or never finished within the timeout.</p>
     *
     * @param asyncOp   pointer to the {@code IAsyncOperation<T>}.
     * @param timeoutMs maximum wall time to wait.
     * @return final {@code AsyncStatus} ({@link #ASYNC_COMPLETED} on
     *         success), or {@link #ASYNC_STARTED} if we timed out.
     * @throws WinRtException if QueryInterface to {@code IAsyncInfo}
     *                        fails or get_Status fails.
     */
    public static int waitForAsync( Pointer asyncOp, long timeoutMs ) throws WinRtException
    {
        // Get IAsyncInfo from the operation so we can poll status.
        PointerByReference infoRef = new PointerByReference();
        int hr = invokeHr( asyncOp, IUNKNOWN_QUERY_INTERFACE,
                            asIidRef( IID_IASYNC_INFO ), infoRef );
        check( hr, "QueryInterface(IAsyncInfo)" );
        Pointer asyncInfo = infoRef.getValue();
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            IntByReference statusRef = new IntByReference();
            while ( true ) {
                hr = invokeHr( asyncInfo, IASYNC_INFO_GET_STATUS, statusRef );
                check( hr, "IAsyncInfo::get_Status" );
                int status = statusRef.getValue();
                if ( status != ASYNC_STARTED ) {
                    return status; // COMPLETED / CANCELED / ERROR
                }
                if ( System.currentTimeMillis() >= deadline ) {
                    return ASYNC_STARTED; // timed out
                }
                try { Thread.sleep( 25L ); }
                catch ( InterruptedException ie ) {
                    Thread.currentThread().interrupt();
                    return ASYNC_STARTED;
                }
            }
        }
        finally {
            release( asyncInfo );
        }
    }

    /** GUIDs are passed by-reference (REFIID) into QueryInterface. JNA's
     *  {@link Guid.IID} is a Structure subclass; calling
     *  {@code .getPointer()} after auto-write yields a stable pointer
     *  suitable as a REFIID arg. */
    public static Guid.IID.ByReference asIidRef( Guid.IID iid )
    {
        Guid.IID.ByReference ref = new Guid.IID.ByReference();
        ref.Data1 = iid.Data1;
        ref.Data2 = iid.Data2;
        ref.Data3 = iid.Data3;
        ref.Data4 = iid.Data4.clone();
        ref.write();
        return ref;
    }

    // ====================================================================
    // HRESULT handling
    // ====================================================================

    /** Throw if {@code hr} is a failure code (anything < 0). */
    public static void check( int hr, String op ) throws WinRtException
    {
        if ( hr < 0 ) {
            throw new WinRtException( op + " failed with HRESULT 0x"
                                            + Integer.toHexString( hr ), hr );
        }
    }

    /** Thin checked exception for native call failures so callers can
     *  catch one type and surface the HRESULT in the message. */
    public static final class WinRtException extends Exception
    {
        private final int hresult;

        /**
         * Constructs a new {@code WinRtException} with the specified detail message and HRESULT.
         *
         * @param message the detail message (which is saved for later retrieval by the {@link #getMessage()} method).
         * @param hresult the HRESULT value associated with this exception.
         */
        public WinRtException( String message, int hresult )
        {
            super( message );
            this.hresult = hresult;
        }

        /**
         * Returns the HRESULT value associated with this exception.
         *
         * @return the HRESULT value.
         */
        public int hresult() { return hresult; }
    }

    /** Allocate a small native buffer for an "out" Pointer slot. Caller
     *  reads the Pointer via {@code mem.getPointer(0)}. */
    public static Memory outPointerSlot()
    {
        Memory m = new Memory( com.sun.jna.Native.POINTER_SIZE );
        m.clear();
        return m;
    }
}

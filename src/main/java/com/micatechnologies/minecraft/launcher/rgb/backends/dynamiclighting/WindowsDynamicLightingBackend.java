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

package com.micatechnologies.minecraft.launcher.rgb.backends.dynamiclighting;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.rgb.RgbBackend;
import com.micatechnologies.minecraft.launcher.rgb.RgbColor;
import com.micatechnologies.minecraft.launcher.rgb.RgbFrame;
import com.micatechnologies.minecraft.launcher.rgb.backends.dynamiclighting.winrt.Combase;
import com.micatechnologies.minecraft.launcher.rgb.backends.dynamiclighting.winrt.WinRt;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.apache.commons.lang3.SystemUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * RGB backend driving Windows 11 Dynamic Lighting (the
 * {@code Windows.Devices.Lights.LampArray} WinRT API) through a JNA
 * bridge to {@code combase.dll}.
 *
 * <h3>Why JNA instead of a PowerShell helper</h3>
 *
 * <p>This was originally a PowerShell-subprocess backend. The handshake
 * to the PS subprocess kept timing out on real hardware regardless of
 * stdout-flush workarounds — verified on a fresh launcher run with a
 * Logitech G502 X registered as a LampArray device. Replacing the PS
 * round-trip with a direct WinRT-via-JNA call removes the subprocess
 * lifetime, child-pipe scheduling, and the 15-second handshake budget
 * from the picture entirely.</p>
 *
 * <p>The cost is ~600 lines of WinRT vtable-dispatch plumbing (see
 * {@link com.micatechnologies.minecraft.launcher.rgb.backends.dynamiclighting.winrt}).
 * Worth it because the launcher is the only meaningful consumer — no
 * native helper toolchain, no per-platform binary to ship.</p>
 *
 * <h3>Lifecycle</h3>
 *
 * <ol>
 *   <li>{@link #isAvailable()} — quick check: Windows + combase.dll
 *       loadable. No WinRT calls.</li>
 *   <li>{@link #start()} — initialise the WinRT MTA on the calling
 *       thread, fetch {@code ILampArrayStatics} and
 *       {@code IDeviceInformationStatics}, get the LampArray device
 *       selector, enumerate matching devices via
 *       {@code FindAllAsyncAqsFilter}, and for each device call
 *       {@code LampArray.FromIdAsync}. Each {@code ILampArray} pointer
 *       is added to {@link #lampArrays} for later use.</li>
 *   <li>{@link #renderFrame} — for every cached LampArray, call
 *       {@code SetColor} with the frame's background color. Frame
 *       deduplication skips the call entirely when the color hasn't
 *       changed. WinRT is initialised lazily on the calling thread
 *       (the mica-rgb worker is not the same thread that ran start()).</li>
 *   <li>{@link #shutdown} — Release every cached LampArray pointer,
 *       drop the cache. The WinRT apartment stays initialised until
 *       process exit — RoUninitialize is intentionally not called per
 *       thread because the standard guidance is "do not pair on a
 *       worker that may receive future jobs", and the launcher only
 *       leaves WinRT at JVM exit anyway.</li>
 * </ol>
 *
 * <h3>Threading</h3>
 *
 * <p>WinRT requires every thread that touches WinRT objects to first
 * call {@code RoInitialize}. {@code start()} initialises on whatever
 * thread it's called on; {@code renderFrame} uses a {@link ThreadLocal}
 * to lazily initialise the {@code mica-rgb} worker on its first frame.
 * MTA means the LampArray pointers created on the start thread are
 * legal to call from the worker thread.</p>
 *
 * <h3>Device coverage</h3>
 *
 * <p>Windows Dynamic Lighting only sees devices that ship HID Lighting
 * &amp; Illumination support (HID Usage Page 0x59) AND that the user
 * has enabled in Settings → Personalization → Dynamic Lighting. As of
 * mid-2026 that's a narrow set: some Logitech keyboards/mice, some
 * SteelSeries / ASUS / HP / Lenovo accessories, certain Microsoft
 * peripherals. Razer hardware does NOT participate in DL natively —
 * Razer users should keep Razer Chroma (Native) in the Auto-mode
 * rotation instead of relying on this backend for their Razer gear.</p>
 *
 * @since 2026.5
 */
public final class WindowsDynamicLightingBackend implements RgbBackend
{
    /** Maximum wait per async WinRT call (enumeration + per-device
     *  open). 5 s is generous — a healthy system completes in
     *  ~milliseconds. Anything longer than this is a wedged op that
     *  we want to bail on. */
    private static final long ASYNC_TIMEOUT_MS = 5_000L;

    /** Cached LampArray interface pointers, one per connected device.
     *  Held for the lifetime of the backend; released in
     *  {@link #shutdown}. */
    private final List< Pointer > lampArrays = new ArrayList<>();

    /** Per-thread "this thread has already called RoInitialize" flag.
     *  WinRT requires each thread that touches WinRT objects to init
     *  its own apartment association — the mica-rgb worker thread is
     *  not the same thread that ran {@link #start()}, so renderFrame
     *  must init itself on first call. */
    private static final ThreadLocal< Boolean > THREAD_WINRT_INITED =
            ThreadLocal.withInitial( () -> Boolean.FALSE );

    /** Frame dedup. The effect engine ticks at 30 fps even when the
     *  active effect is static; without dedup we'd push the same
     *  SetColor call to every LampArray 30 times per second. Tracking
     *  the last-sent packed Windows.UI.Color and skipping identical
     *  pushes keeps the worker thread idle when nothing is moving. */
    private int lastSentColor = Integer.MIN_VALUE;

    /** Whether {@link #start()} has completed successfully and the cached
     *  {@link #lampArrays} are ready to receive frames. Reset to
     *  {@code false} by {@link #shutdown}. Volatile because it is written
     *  on the start thread and read on the mica-rgb worker thread. */
    private volatile boolean started = false;

    /**
     * {@inheritDoc}
     *
     * @return the human-readable backend name {@code "Windows Dynamic Lighting"}.
     * @since 2026.5
     */
    @Override
    public String name() { return "Windows Dynamic Lighting"; }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation performs only a cheap, side-effect-free probe:
     * the host must be Windows and {@code combase.dll} must be loadable. No
     * WinRT activation or device enumeration happens here, so the call is
     * safe to invoke repeatedly from the controller's auto-probe.</p>
     *
     * @return {@code true} when running on Windows with a loadable
     *         {@code combase.dll}; {@code false} otherwise.
     * @since 2026.5
     */
    @Override
    public boolean isAvailable()
    {
        // Cheap probe: must be Windows AND combase.dll must load.
        // The latter is essentially universal on Windows 10/11, but
        // failing the check costs us nothing and the controller's
        // auto-probe expects isAvailable to be safe.
        return SystemUtils.IS_OS_WINDOWS && Combase.isLoadable();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Initialises the WinRT MTA on the calling thread, obtains the
     * {@code ILampArrayStatics} and {@code IDeviceInformationStatics}
     * activation factories, resolves the LampArray AQS device selector,
     * enumerates matching devices via {@code FindAllAsyncAqsFilter}, and
     * opens each device through {@code LampArray.FromIdAsync}. Every opened
     * {@code ILampArray} pointer is cached in {@link #lampArrays} for use
     * by {@link #renderFrame}. The transient per-start interfaces
     * (factories, selector HSTRING, async ops, collection) are always
     * released before returning.</p>
     *
     * <p>On any failure the partially-opened LampArrays are released so no
     * COM refcounts leak.</p>
     *
     * @throws IllegalStateException if the backend is not available on this
     *         host, if device enumeration does not complete within
     *         {@link #ASYNC_TIMEOUT_MS}, or if enumeration reports devices
     *         but none could be opened (typically because Dynamic Lighting
     *         is disabled in Windows Settings).
     * @throws Exception if any underlying WinRT call fails.
     * @since 2026.5
     */
    @Override
    public void start() throws Exception
    {
        if ( !isAvailable() ) {
            throw new IllegalStateException( "Windows Dynamic Lighting: not "
                                                     + "available on this host." );
        }

        ensureThreadWinRtInited();

        // Activation factories. Lifetimes are scoped to start() — released
        // before we return regardless of success/failure.
        Pointer lampStatics = null;
        Pointer devInfoStatics = null;
        Pointer selectorHstring = null;
        Pointer asyncFindOp = null;
        Pointer collection = null;

        try {
            // (1) Get ILampArrayStatics.
            lampStatics = getActivationFactory( WinRt.CLASS_LAMP_ARRAY,
                                                 WinRt.IID_ILAMP_ARRAY_STATICS );

            // (2) Ask LampArray for the AQS device selector string. This
            //     is the filter we'll pass to DeviceInformation.FindAll
            //     so the enumeration only returns LampArrays, not every
            //     PnP device on the box.
            PointerByReference selectorOut = new PointerByReference();
            int hr = WinRt.invokeHr( lampStatics, WinRt.ILAMP_ARRAY_STATICS_GET_DEVICE_SELECTOR,
                                      selectorOut );
            WinRt.check( hr, "ILampArrayStatics::GetDeviceSelector" );
            selectorHstring = selectorOut.getValue();
            String selectorStr = WinRt.readHstring( selectorHstring );
            Logger.logDebug( LocalizationManager.format( "log.rgb.dynamicLighting.deviceSelector", selectorStr ) );

            // (3) Get IDeviceInformationStatics + call FindAllAsyncAqsFilter
            //     with the LampArray selector. Returns IAsyncOperation
            //     <DeviceInformationCollection>.
            devInfoStatics = getActivationFactory( WinRt.CLASS_DEVICE_INFORMATION,
                                                    WinRt.IID_IDEV_INFO_STATICS );
            PointerByReference findOpOut = new PointerByReference();
            hr = WinRt.invokeHr( devInfoStatics, WinRt.IDEV_INFO_STATICS_FIND_ALL_AQS,
                                  selectorHstring, findOpOut );
            WinRt.check( hr, "IDeviceInformationStatics::FindAllAsyncAqsFilter" );
            asyncFindOp = findOpOut.getValue();

            // (4) Wait for the async enumeration to complete.
            int status = WinRt.waitForAsync( asyncFindOp, ASYNC_TIMEOUT_MS );
            if ( status != WinRt.ASYNC_COMPLETED ) {
                throw new IllegalStateException( "Windows Dynamic Lighting: "
                                                         + "device enumeration "
                                                         + asyncDescription( status )
                                                         + " (no devices opened)." );
            }

            // (5) Pull the DeviceInformationCollection — an IVectorView
            //     <DeviceInformation>. Vtable layout: GetAt(6), get_Size(7).
            PointerByReference collOut = new PointerByReference();
            hr = WinRt.invokeHr( asyncFindOp, WinRt.IASYNC_OPERATION_GET_RESULTS, collOut );
            WinRt.check( hr, "IAsyncOperation<DeviceInformationCollection>::GetResults" );
            collection = collOut.getValue();

            // (6) For each DeviceInformation, get its Id (path), then call
            //     LampArray.FromIdAsync to open a working LampArray ptr.
            IntByReference sizeRef = new IntByReference();
            hr = WinRt.invokeHr( collection, WinRt.IVECTORVIEW_GET_SIZE, sizeRef );
            WinRt.check( hr, "IVectorView<DeviceInformation>::get_Size" );
            int count = sizeRef.getValue();
            Logger.logStd( LocalizationManager.format( "log.rgb.dynamicLighting.devicesReported", count ) );

            for ( int i = 0; i < count; i++ ) {
                openLampArray( lampStatics, collection, i );
            }

            if ( lampArrays.isEmpty() ) {
                throw new IllegalStateException( "Windows Dynamic Lighting: "
                                                         + "enumeration reported "
                                                         + count + " device(s) but "
                                                         + "none opened — check that "
                                                         + "\"Use dynamic lighting on my "
                                                         + "devices\" is on in Windows "
                                                         + "Settings." );
            }

            started = true;
            Logger.logStd( LocalizationManager.format( "log.rgb.dynamicLighting.devicesReady", lampArrays.size() ) );
        }
        catch ( Throwable t ) {
            // Roll back any LampArrays we already opened so we don't
            // leak refcounts on a failed start.
            for ( Pointer lamp : lampArrays ) WinRt.release( lamp );
            lampArrays.clear();
            throw t;
        }
        finally {
            // Always release the per-start interfaces — they're not
            // needed for renderFrame.
            WinRt.release( collection );
            WinRt.release( asyncFindOp );
            WinRt.deleteHstring( selectorHstring );
            WinRt.release( devInfoStatics );
            WinRt.release( lampStatics );
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Packs the frame's background color into the byte order Win64
     * expects for a by-value {@code Windows.UI.Color} (see
     * {@link WinRt#packWinUiColor}) and pushes it to every cached
     * LampArray via {@code SetColor}. The call is skipped entirely when the
     * packed color matches the last one sent (frame deduplication). WinRT
     * is initialised lazily on the calling thread, since the mica-rgb
     * worker is not the thread that ran {@link #start()}. A device failing
     * does not abort the others — only when every device fails is an
     * exception raised.</p>
     *
     * @param frame the frame to render; its {@link RgbFrame#background()}
     *              color is applied to all lamps (alpha is forced to
     *              {@code 0xFF}).
     * @throws IllegalStateException if {@code SetColor} fails on every
     *         cached device.
     * @throws Exception if WinRT thread initialisation fails.
     * @since 2026.5
     */
    @Override
    public void renderFrame( RgbFrame frame ) throws Exception
    {
        if ( !started || lampArrays.isEmpty() ) return;

        ensureThreadWinRtInited();

        // Pack the background color into the byte order Win64 expects
        // when passing Windows.UI.Color by value (see WinRt#packWinUiColor
        // — it is NOT the obvious ARGB order). Alpha is always 0xFF; the
        // launcher's effect engine has no concept of translucent lamps.
        RgbColor bg = frame.background();
        int packed = WinRt.packWinUiColor( 0xFF, bg.r(), bg.g(), bg.b() );
        if ( packed == lastSentColor ) {
            return; // dedup — see field doc
        }

        // Fire SetColor at every cached LampArray. A device failing
        // doesn't take down the rest — the worst case is partial colour
        // coverage this frame; the next frame retries.
        int successCount = 0;
        int lastHr = Combase.S_OK;
        for ( Pointer lamp : lampArrays ) {
            try {
                int hr = WinRt.invokeHr( lamp, WinRt.ILAMP_ARRAY_SET_COLOR, packed );
                if ( hr >= 0 ) successCount++;
                else           lastHr = hr;
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.get( "log.rgb.dynamicLighting.setColorThrew" ), t );
            }
        }

        if ( successCount == 0 ) {
            throw new IllegalStateException( "Windows Dynamic Lighting: SetColor "
                                                     + "failed on every device "
                                                     + "(last HRESULT 0x"
                                                     + Integer.toHexString( lastHr ) + ")" );
        }
        lastSentColor = packed;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Releases every cached LampArray pointer and clears the cache.
     * COM/WinRT refcount decrements are thread-safe, so this is safe to
     * call from any thread. The WinRT apartment is deliberately left
     * initialised — {@code RoUninitialize} is not called per thread because
     * the worker may still hold other references and the launcher only
     * exits WinRT at process termination.</p>
     *
     * @since 2026.5
     */
    @Override
    public void shutdown()
    {
        started = false;
        // Release every LampArray pointer we hold. Doesn't matter what
        // thread we're on for Release — refcount decrement is thread-safe
        // in COM/WinRT.
        for ( Pointer lamp : lampArrays ) WinRt.release( lamp );
        lampArrays.clear();
        lastSentColor = Integer.MIN_VALUE;
        // Intentionally NOT calling RoUninitialize. Per-thread init is
        // refcounted; the worker thread may still hold references to
        // other objects, and tearing the MTA down here would risk a
        // CO_E_NOTINITIALIZED later. Process exit will clean up.
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    /** Get the activation factory for a runtime class as the given
     *  statics-interface IID. Caller owns the returned pointer and
     *  must release with {@link WinRt#release}. */
    private static Pointer getActivationFactory( String runtimeClass,
                                                  com.sun.jna.platform.win32.Guid.IID iid )
            throws Exception
    {
        Pointer classNameHstring = null;
        try {
            classNameHstring = WinRt.createHstring( runtimeClass );
            PointerByReference factoryOut = new PointerByReference();
            int hr = Combase.INSTANCE.RoGetActivationFactory( classNameHstring,
                                                                WinRt.asIidRef( iid ),
                                                                factoryOut );
            WinRt.check( hr, "RoGetActivationFactory(" + runtimeClass + ")" );
            return factoryOut.getValue();
        }
        finally {
            WinRt.deleteHstring( classNameHstring );
        }
    }

    /** Open the LampArray at {@code index} in the
     *  {@code DeviceInformationCollection} and cache it in
     *  {@link #lampArrays}. Failure of one device does not abort the
     *  loop — log + continue. */
    private void openLampArray( Pointer lampStatics, Pointer collection, int index )
    {
        Pointer devInfo = null;
        Pointer idHstring = null;
        Pointer fromIdOp = null;

        try {
            // Fetch the DeviceInformation at this index.
            PointerByReference devInfoOut = new PointerByReference();
            int hr = WinRt.invokeHr( collection, WinRt.IVECTORVIEW_GET_AT, index, devInfoOut );
            WinRt.check( hr, "IVectorView::GetAt(" + index + ")" );
            devInfo = devInfoOut.getValue();

            // Read its Id HSTRING (the device's PnP path — what
            // FromIdAsync needs).
            PointerByReference idOut = new PointerByReference();
            hr = WinRt.invokeHr( devInfo, WinRt.IDEVICE_INFORMATION_GET_ID, idOut );
            WinRt.check( hr, "IDeviceInformation::get_Id" );
            idHstring = idOut.getValue();
            String idStr = WinRt.readHstring( idHstring );

            // Open the LampArray asynchronously.
            PointerByReference opOut = new PointerByReference();
            hr = WinRt.invokeHr( lampStatics, WinRt.ILAMP_ARRAY_STATICS_FROM_ID_ASYNC,
                                  idHstring, opOut );
            WinRt.check( hr, "ILampArrayStatics::FromIdAsync" );
            fromIdOp = opOut.getValue();

            int status = WinRt.waitForAsync( fromIdOp, ASYNC_TIMEOUT_MS );
            if ( status != WinRt.ASYNC_COMPLETED ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.rgb.dynamicLighting.fromIdAsyncFailed",
                                                 asyncDescription( status ), idStr ) );
                return;
            }

            PointerByReference lampOut = new PointerByReference();
            hr = WinRt.invokeHr( fromIdOp, WinRt.IASYNC_OPERATION_GET_RESULTS, lampOut );
            WinRt.check( hr, "IAsyncOperation<LampArray>::GetResults" );
            Pointer lamp = lampOut.getValue();
            if ( lamp == null || Pointer.nativeValue( lamp ) == 0L ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.rgb.dynamicLighting.getResultsNull", idStr ) );
                return;
            }
            lampArrays.add( lamp );
            Logger.logStd( LocalizationManager.format( "log.rgb.dynamicLighting.openedLampArray", idStr ) );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.rgb.dynamicLighting.openLampArrayFailed", index ), t );
        }
        finally {
            WinRt.release( fromIdOp );
            WinRt.deleteHstring( idHstring );
            WinRt.release( devInfo );
        }
    }

    /** One-shot WinRT init for the calling thread. Idempotent — WinRT
     *  ref-counts RoInitialize internally, but we still guard with a
     *  ThreadLocal to avoid the unnecessary native round-trip. */
    private static void ensureThreadWinRtInited() throws Exception
    {
        if ( THREAD_WINRT_INITED.get() ) return;
        int hr = Combase.INSTANCE.RoInitialize( Combase.RO_INIT_MULTITHREADED );
        // S_OK = first init for this thread. S_FALSE = already inited
        // on a different MTA-compatible apartment. RPC_E_CHANGED_MODE
        // (0x80010106) = something else previously chose a different
        // apartment on this thread — fatal for us.
        if ( hr != Combase.S_OK && hr != Combase.S_FALSE ) {
            throw new IllegalStateException( "Windows Dynamic Lighting: "
                                                     + "RoInitialize returned HRESULT 0x"
                                                     + Integer.toHexString( hr )
                                                     + " — apartment conflict?" );
        }
        THREAD_WINRT_INITED.set( Boolean.TRUE );
    }

    private static String asyncDescription( int status )
    {
        return switch ( status ) {
            case WinRt.ASYNC_COMPLETED -> "completed";
            case WinRt.ASYNC_CANCELED  -> "was cancelled";
            case WinRt.ASYNC_ERROR     -> "reported an error";
            case WinRt.ASYNC_STARTED   -> "timed out (still running)";
            default -> "returned unknown status " + status;
        };
    }
}

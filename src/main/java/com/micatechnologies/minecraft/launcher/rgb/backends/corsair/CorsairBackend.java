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

package com.micatechnologies.minecraft.launcher.rgb.backends.corsair;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.rgb.RgbBackend;
import com.micatechnologies.minecraft.launcher.rgb.RgbColor;
import com.micatechnologies.minecraft.launcher.rgb.RgbFrame;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RGB backend driving Corsair hardware via the iCUE SDK v3
 * ({@code iCUESDK.x64_2017.dll} / {@code iCUESDK.x64_2019.dll}). Same
 * lifecycle shape as the Razer Chroma native backend — the SDK
 * abstracts every Corsair peripheral (keyboard, mouse, mousepad,
 * headset, RAM, fans, coolers) behind a unified LED-ID surface, so
 * the launcher doesn't need per-family branches like it does for
 * Chroma.
 *
 * <h3>Lifecycle</h3>
 *
 * <ol>
 *   <li>{@link #isAvailable()} — DLL loadable? Cheap probe. No
 *       subprocess, no driver poke.</li>
 *   <li>{@link #start()} — {@code CorsairConnect} kicks off the
 *       session asynchronously; backend waits on a CountDownLatch
 *       that the state-changed callback releases when the session
 *       reaches {@code Connected} (or fails with a refusal /
 *       timeout). After connect, enumerate devices and per-device
 *       LED IDs.</li>
 *   <li>{@link #renderFrame} — for every cached device, build a
 *       {@code CorsairLedColor[]} with the frame's background color
 *       across every known LED ID on that device, and push with
 *       {@code CorsairSetLedColors}. Frame deduplication skips the
 *       whole push when the color hasn't changed since last frame.</li>
 *   <li>{@link #shutdown} — {@code CorsairDisconnect}. The SDK
 *       restores devices to whatever profile iCUE was running before
 *       we connected.</li>
 * </ol>
 *
 * <h3>Per-device failure isolation</h3>
 *
 * <p>If a single device starts returning errors on
 * {@code SetLedColors}, we don't want it taking down the rest of the
 * rig. Devices that fail repeatedly without ever succeeding are
 * dropped from the active set the same way Razer's per-family
 * backoff handles missing-device families.</p>
 *
 * @since 2026.5
 */
public final class CorsairBackend implements RgbBackend
{
    /** How long {@link #start()} is willing to wait for the iCUE
     *  service to reach {@code Connected}. iCUE running already → ms.
     *  iCUE just launched / never launched → potentially seconds while
     *  the service spins up. 8 s is a comfortable cap; longer than
     *  this is "iCUE isn't running, don't block the bootstrap". */
    private static final long CONNECT_TIMEOUT_MS = 8_000L;

    /** Consecutive failures at which a never-succeeded device is
     *  permanently dropped from the active set. */
    private static final int DEVICE_FAILURE_DROP_THRESHOLD = 5;

    /** Per-device cached LED ID list. Lookup is hot path (every
     *  frame), so we hold the int[] + the deviceId byte buffer + a
     *  reusable LED-color Memory side-by-side.
     *
     *  @since 2026.5 */
    private static final class Device
    {
        /** 128-byte {@code CorsairDeviceId} buffer, detached from JNA's
         *  shared {@link Structure} Memory so it survives across frames. */
        final byte[] id;          // 128-byte CorsairDeviceId buffer
        /** LED IDs returned by {@code CorsairGetLedPositions}; the keys
         *  every per-frame {@code CorsairSetLedColors} push writes to. */
        final int[]  ledIds;       // LED IDs returned by GetLedPositions
        /** Human-readable device model string, used only for log lines. */
        final String model;        // for log lines only
        /** Count of consecutive {@code SetLedColors} failures since the
         *  last success; drives the never-succeeded drop check. */
        final AtomicInteger consecutiveFailures = new AtomicInteger( 0 );
        /** Set once the device has accepted at least one frame this
         *  session; keeps a previously-working device in the rotation
         *  even when it later starts failing. */
        volatile boolean succeededOnce = false;
        /** Set when the device has been permanently dropped from the
         *  active set for the remainder of the session. */
        volatile boolean droppedFromRotation = false;

        /**
         * Creates a cached device record.
         *
         * @param id     the 128-byte {@code CorsairDeviceId} buffer
         *               (a private copy detached from JNA's shared Memory)
         * @param ledIds the device's LED IDs to color each frame
         * @param model  the device model string for log lines
         */
        Device( byte[] id, int[] ledIds, String model )
        {
            this.id = id;
            this.ledIds = ledIds;
            this.model = model;
        }
    }

    /** Active set of enumerated Corsair devices, populated by
     *  {@link #enumerateDevices()} during {@link #start()} and cleared on
     *  {@link #shutdown()}. */
    private final List< Device > devices = new ArrayList<>();

    /** Connected state. {@code false} before {@link #start()} succeeds and
     *  after {@link #shutdown()}. */
    private volatile boolean connected = false;

    /** Frame dedup. The effect engine ticks at 30 fps; the same
     *  static color frame in / out is the common case (InGameEffect,
     *  SolidEffect). Tracking the last RGB triple and skipping
     *  identical pushes keeps the bus quiet. */
    private int lastSentPackedRgb = Integer.MIN_VALUE;

    /** Hard reference to the callback so the GC doesn't collect it
     *  while the native side still holds a function pointer. JNA
     *  doesn't track callback ownership across the boundary; without
     *  this field the callback's underlying trampoline could be freed
     *  out from under iCUE. */
    @SuppressWarnings( "FieldCanBeLocal" )
    private CorsairSdkLibrary.CorsairSessionStateChangedHandler stateHandler;

    /**
     * {@inheritDoc}
     *
     * @return the fixed human-readable backend name {@code "Corsair iCUE"}
     *         used in log lines and the RGB settings UI
     *
     * @since 2026.5
     */
    @Override
    public String name() { return "Corsair iCUE"; }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation reports availability by attempting to load
     * the iCUE SDK DLL via JNA (see {@link CorsairSdkLibrary#isLoadable()}).
     * The probe is cheap and side-effect free — it returns {@code false}
     * instantly on non-Windows hosts or when iCUE isn't installed.</p>
     *
     * @return {@code true} when the iCUE SDK DLL is loadable on this host,
     *         {@code false} otherwise
     *
     * @since 2026.5
     */
    @Override
    public boolean isAvailable()
    {
        return CorsairSdkLibrary.isLoadable();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calls {@code CorsairConnect}, then blocks on a
     * {@link CountDownLatch} released by the SDK's state-changed callback
     * until the session reaches {@code Connected} / {@code Subscribed} or a
     * terminal failure state, capped at {@link #CONNECT_TIMEOUT_MS}. Once
     * connected, enumerates every Corsair device and its LED IDs into
     * {@link #devices}.</p>
     *
     * @throws IllegalStateException if the iCUE SDK DLL cannot be loaded
     *                               (iCUE not installed); if
     *                               {@code CorsairConnect} returns an error;
     *                               if the session never reaches
     *                               {@code Connected} within the timeout; if
     *                               the session ends in a non-connected
     *                               terminal state; or if the connection
     *                               succeeds but no devices are found
     * @throws Exception             per the {@link RgbBackend} contract
     *
     * @since 2026.5
     */
    @Override
    public void start() throws Exception
    {
        if ( !CorsairSdkLibrary.isLoadable() ) {
            throw new IllegalStateException( "Corsair iCUE SDK DLL not loadable — "
                                                     + "iCUE not installed?" );
        }

        // CountDownLatch model: the state-changed callback fires on
        // an SDK worker thread when the session transitions. We wait
        // here for the latch to be released, with a timeout cap so a
        // wedged session can't block the launcher's bootstrap.
        CountDownLatch ready = new CountDownLatch( 1 );
        AtomicInteger terminalState = new AtomicInteger( CorsairSdkLibrary.CSS_INVALID );

        stateHandler = ( ctx, eventData ) -> {
            if ( eventData == null ) return;
            // CorsairSessionStateChanged starts with `int state`. Read
            // the first 4 bytes; anything after is detail we don't use.
            int state = eventData.getInt( 0 );
            switch ( state ) {
                case CorsairSdkLibrary.CSS_CONNECTED,
                     CorsairSdkLibrary.CSS_SUBSCRIBED -> {
                    terminalState.set( state );
                    ready.countDown();
                }
                case CorsairSdkLibrary.CSS_CONNECTION_REFUSED,
                     CorsairSdkLibrary.CSS_CONNECTION_TIMEOUT,
                     CorsairSdkLibrary.CSS_CONNECTION_LOST,
                     CorsairSdkLibrary.CSS_CLOSED -> {
                    terminalState.set( state );
                    ready.countDown();
                }
                default -> {
                    // CSS_CONNECTING — keep waiting.
                }
            }
        };

        int rc = CorsairSdkLibrary.INSTANCE.CorsairConnect( stateHandler, null );
        if ( rc != CorsairSdkLibrary.CE_SUCCESS ) {
            throw new IllegalStateException( "Corsair iCUE: CorsairConnect returned error "
                                                     + describeError( rc ) );
        }

        boolean signalled = ready.await( CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS );
        if ( !signalled ) {
            CorsairSdkLibrary.INSTANCE.CorsairDisconnect();
            throw new IllegalStateException( "Corsair iCUE: session never reached "
                                                     + "Connected within "
                                                     + CONNECT_TIMEOUT_MS + "ms — "
                                                     + "iCUE service might not be running." );
        }
        int finalState = terminalState.get();
        if ( finalState != CorsairSdkLibrary.CSS_CONNECTED
                && finalState != CorsairSdkLibrary.CSS_SUBSCRIBED ) {
            CorsairSdkLibrary.INSTANCE.CorsairDisconnect();
            throw new IllegalStateException( "Corsair iCUE: session ended in "
                                                     + describeSessionState( finalState ) );
        }
        connected = true;
        Logger.logStd( LocalizationManager.get( "log.rgb.corsair.sessionConnected" ) );

        enumerateDevices();
        if ( devices.isEmpty() ) {
            CorsairSdkLibrary.INSTANCE.CorsairDisconnect();
            connected = false;
            throw new IllegalStateException( "Corsair iCUE: connected but no devices "
                                                     + "found. Check iCUE recognises your "
                                                     + "hardware." );
        }
        Logger.logStd( LocalizationManager.format( "log.rgb.corsair.devicesReady", devices.size() ) );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pushes the frame's background color across every LED of every
     * device still in the active rotation via {@code CorsairSetLedColors}.
     * Identical consecutive frames are deduplicated (skipped entirely) by
     * comparing the packed RGB triple against {@link #lastSentPackedRgb}.
     * A no-op before {@link #start()} succeeds or after
     * {@link #shutdown()}.</p>
     *
     * <p>The frame is treated as delivered if <em>any</em> device accepts
     * it; a device that fails repeatedly without ever succeeding is
     * dropped from the rotation (see {@link #handleDeviceFailure}). Only
     * when every attempted device fails on the same frame is the failure
     * surfaced to the controller's circuit breaker.</p>
     *
     * @param frame the frame to paint; its {@link RgbFrame#background()}
     *              fills every LED on every device
     *
     * @throws IllegalStateException if at least one device was attempted
     *                               but {@code CorsairSetLedColors} failed
     *                               on every one; the message carries the
     *                               last iCUE error code
     * @throws Exception             per the {@link RgbBackend} contract
     *
     * @since 2026.5
     */
    @Override
    public void renderFrame( RgbFrame frame ) throws Exception
    {
        if ( !connected || devices.isEmpty() ) return;

        RgbColor bg = frame.background();
        int packed = ( bg.r() << 16 ) | ( bg.g() << 8 ) | bg.b();
        if ( packed == lastSentPackedRgb ) {
            return; // dedup
        }

        int successCount = 0;
        int attemptCount = 0;
        int lastErrorCode = CorsairSdkLibrary.CE_SUCCESS;
        for ( Device dev : devices ) {
            if ( dev.droppedFromRotation ) continue;
            attemptCount++;
            try {
                int rc = applyColorToDevice( dev, (byte) bg.r(), (byte) bg.g(), (byte) bg.b() );
                if ( rc == CorsairSdkLibrary.CE_SUCCESS ) {
                    dev.succeededOnce = true;
                    dev.consecutiveFailures.set( 0 );
                    successCount++;
                }
                else {
                    lastErrorCode = rc;
                    handleDeviceFailure( dev, rc );
                }
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.rgb.corsair.setLedColorsThrew", dev.model ), t );
                handleDeviceFailure( dev, CorsairSdkLibrary.CE_INVALID_OPERATION );
            }
        }

        if ( attemptCount > 0 && successCount == 0 ) {
            throw new IllegalStateException( "Corsair iCUE: SetLedColors failed on every "
                                                     + "device (last error="
                                                     + describeError( lastErrorCode ) + ")" );
        }
        lastSentPackedRgb = packed;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calls {@code CorsairDisconnect} (best-effort — any throwable is
     * swallowed so shutdown always completes), after which iCUE restores
     * devices to whatever profile was active before the launcher
     * connected. Clears the cached device set, resets the frame-dedup
     * marker, and drops the state-changed callback reference. Safe to call
     * when never started.</p>
     *
     * @since 2026.5
     */
    @Override
    public void shutdown()
    {
        if ( connected ) {
            try { CorsairSdkLibrary.INSTANCE.CorsairDisconnect(); }
            catch ( Throwable ignored ) { /* best-effort */ }
        }
        connected = false;
        devices.clear();
        lastSentPackedRgb = Integer.MIN_VALUE;
        stateHandler = null;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Enumerate connected Corsair devices and fetch each one's LED ID
     *  list, populating {@link #devices}. Devices that report zero LEDs are
     *  skipped; a failed {@code CorsairGetDevices} call logs silently and
     *  leaves {@link #devices} empty.
     *
     *  @since 2026.5 */
    private void enumerateDevices()
    {
        CorsairSdkLibrary.CorsairDeviceFilter.ByReference filter =
                new CorsairSdkLibrary.CorsairDeviceFilter.ByReference( CorsairSdkLibrary.CDT_ALL );

        CorsairSdkLibrary.CorsairDeviceInfo[] infoArr =
                CorsairSdkLibrary.newDeviceInfoArray( CorsairSdkLibrary.MAX_DEVICES );
        IntByReference sizeRef = new IntByReference();
        int rc = CorsairSdkLibrary.INSTANCE.CorsairGetDevices( filter,
                                                                CorsairSdkLibrary.MAX_DEVICES,
                                                                infoArr[ 0 ],
                                                                sizeRef );
        if ( rc != CorsairSdkLibrary.CE_SUCCESS ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.rgb.corsair.getDevicesError", describeError( rc ) ) );
            return;
        }
        // JNA's toArray() shares one Memory block — we MUST call read()
        // on each entry to sync the native bytes back into the Java
        // mirror after the SDK populated them.
        int count = sizeRef.getValue();
        for ( int i = 0; i < count && i < infoArr.length; i++ ) {
            infoArr[ i ].read();
            CorsairSdkLibrary.CorsairDeviceInfo info = infoArr[ i ];
            int[] ledIds = fetchLedIds( info.id );
            if ( ledIds.length == 0 ) continue;
            byte[] idCopy = info.id.clone(); // detach from shared Memory
            String model = trimTrailingNulls( info.model );
            devices.add( new Device( idCopy, ledIds, model ) );
            Logger.logStd( LocalizationManager.format( "log.rgb.corsair.deviceRegistered", model, ledIds.length ) );
        }
    }

    /** Fetch the LED IDs on one device. Returns an empty array on any
     *  failure — caller skips the device.
     *
     *  @param deviceIdBuffer the device's 128-byte {@code CorsairDeviceId}
     *                        buffer to query
     *
     *  @return the device's LED IDs, or an empty array if the query failed
     *
     *  @since 2026.5 */
    private int[] fetchLedIds( byte[] deviceIdBuffer )
    {
        Memory idMem = new Memory( CorsairSdkLibrary.DEVICE_ID_LEN );
        idMem.write( 0, deviceIdBuffer, 0, CorsairSdkLibrary.DEVICE_ID_LEN );

        CorsairSdkLibrary.CorsairLedPosition[] posArr =
                CorsairSdkLibrary.newLedPositionArray( CorsairSdkLibrary.MAX_LEDS_PER_DEVICE );
        IntByReference sizeRef = new IntByReference();
        int rc = CorsairSdkLibrary.INSTANCE.CorsairGetLedPositions(
                idMem, CorsairSdkLibrary.MAX_LEDS_PER_DEVICE, posArr[ 0 ], sizeRef );
        if ( rc != CorsairSdkLibrary.CE_SUCCESS ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.rgb.corsair.getLedPositionsError", describeError( rc ) ) );
            return new int[ 0 ];
        }
        int count = Math.min( sizeRef.getValue(), posArr.length );
        int[] ids = new int[ count ];
        for ( int i = 0; i < count; i++ ) {
            posArr[ i ].read();
            ids[ i ] = posArr[ i ].id;
        }
        return ids;
    }

    /** Apply (r, g, b) to every LED on {@code dev}. Returns the
     *  iCUE error code.
     *
     *  @param dev the device to recolor
     *  @param r   the red channel (0–255, as a signed byte)
     *  @param g   the green channel (0–255, as a signed byte)
     *  @param b   the blue channel (0–255, as a signed byte)
     *
     *  @return the {@code CorsairError} code from {@code CorsairSetLedColors};
     *          {@link CorsairSdkLibrary#CE_SUCCESS} on success
     *
     *  @since 2026.5 */
    private int applyColorToDevice( Device dev, byte r, byte g, byte b )
    {
        Memory idMem = new Memory( CorsairSdkLibrary.DEVICE_ID_LEN );
        idMem.write( 0, dev.id, 0, CorsairSdkLibrary.DEVICE_ID_LEN );

        // Build the contiguous LED-color array. JNA's toArray() backs
        // it with one Memory block; we write into each entry and call
        // write() to flush back to native.
        CorsairSdkLibrary.CorsairLedColor[] colors =
                CorsairSdkLibrary.newLedColorArray( dev.ledIds.length );
        for ( int i = 0; i < dev.ledIds.length; i++ ) {
            colors[ i ].id = dev.ledIds[ i ];
            colors[ i ].r = r;
            colors[ i ].g = g;
            colors[ i ].b = b;
            colors[ i ].a = (byte) 0xFF;
            colors[ i ].write();
        }
        return CorsairSdkLibrary.INSTANCE.CorsairSetLedColors(
                idMem, dev.ledIds.length, colors[ 0 ] );
    }

    /** Same per-family backoff pattern as ChromaNativeBackend: a
     *  device that's failed N times without ever succeeding is dropped
     *  for the session. Devices that succeeded at least once stay in
     *  the rotation (transient failures circuit-break through the
     *  controller, which is the right path for a real driver glitch). */
    private void handleDeviceFailure( Device dev, int errorCode )
    {
        int failures = dev.consecutiveFailures.incrementAndGet();
        if ( !dev.succeededOnce && failures >= DEVICE_FAILURE_DROP_THRESHOLD ) {
            dev.droppedFromRotation = true;
            Logger.logStd( LocalizationManager.format( "log.rgb.corsair.givingUpOnDevice",
                                   dev.model, failures, describeError( errorCode ) ) );
        }
    }

    /**
     * Trims trailing NUL padding from a fixed-size C string buffer and returns the
     * result as a Java string.
     *
     * @param buf the NUL-padded byte buffer
     *
     * @return the trimmed string
     */
    private static String trimTrailingNulls( byte[] buf )
    {
        int end = 0;
        while ( end < buf.length && buf[ end ] != 0 ) end++;
        return new String( buf, 0, end, java.nio.charset.StandardCharsets.UTF_8 );
    }

    /**
     * Returns a human-readable description for an iCUE SDK error code, for logging.
     *
     * @param code the SDK error code
     *
     * @return a human-readable error description
     */
    private static String describeError( int code )
    {
        return switch ( code ) {
            case CorsairSdkLibrary.CE_SUCCESS                -> "Success(0)";
            case CorsairSdkLibrary.CE_NOT_CONNECTED          -> "NotConnected(1)";
            case CorsairSdkLibrary.CE_NO_CONTROL             -> "NoControl(2)";
            case CorsairSdkLibrary.CE_INCOMPATIBLE_PROTOCOL  -> "IncompatibleProtocol(3)";
            case CorsairSdkLibrary.CE_INVALID_ARGUMENTS      -> "InvalidArguments(4)";
            case CorsairSdkLibrary.CE_INVALID_OPERATION      -> "InvalidOperation(5)";
            case CorsairSdkLibrary.CE_DEVICE_NOT_FOUND       -> "DeviceNotFound(6)";
            case CorsairSdkLibrary.CE_NOT_ALLOWED            -> "NotAllowed(7)";
            default                                          -> "Unknown(" + code + ")";
        };
    }

    /**
     * Returns a human-readable description for an iCUE session state, for logging.
     *
     * @param state the SDK session-state code
     *
     * @return a human-readable state description
     */
    private static String describeSessionState( int state )
    {
        return switch ( state ) {
            case CorsairSdkLibrary.CSS_INVALID            -> "Invalid";
            case CorsairSdkLibrary.CSS_CLOSED             -> "Closed";
            case CorsairSdkLibrary.CSS_CONNECTING         -> "Connecting";
            case CorsairSdkLibrary.CSS_CONNECTED          -> "Connected";
            case CorsairSdkLibrary.CSS_SUBSCRIBED         -> "Subscribed";
            case CorsairSdkLibrary.CSS_CONNECTION_LOST    -> "ConnectionLost";
            case CorsairSdkLibrary.CSS_CONNECTION_REFUSED -> "ConnectionRefused";
            case CorsairSdkLibrary.CSS_CONNECTION_TIMEOUT -> "ConnectionTimeout";
            default                                       -> "Unknown(" + state + ")";
        };
    }
}

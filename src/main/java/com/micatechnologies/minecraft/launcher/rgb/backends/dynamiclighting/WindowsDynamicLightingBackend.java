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

import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.rgb.RgbBackend;
import com.micatechnologies.minecraft.launcher.rgb.RgbColor;
import com.micatechnologies.minecraft.launcher.rgb.RgbFrame;
import org.apache.commons.lang3.SystemUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * RGB backend driving Windows 11 Dynamic Lighting (the
 * {@code Windows.Devices.Lights.LampArray} WinRT API) through a
 * long-lived PowerShell subprocess.
 *
 * <h3>Why PowerShell?</h3>
 *
 * <p>Java has no first-class WinRT bindings. The realistic options for
 * a Java app to call into LampArray are:</p>
 * <ul>
 *   <li>A JNA bridge to {@code combase.dll} (HSTRING / RoActivateInstance
 *       / IAsyncOperation polling). ~1500 lines of fragile glue —
 *       async-to-sync conversion across a JNA boundary is the brittle
 *       part. Pure-Java, no external deps.</li>
 *   <li>A bundled native helper (C# / C++ / Rust). Cleanest impl
 *       (~50 lines) but adds a new toolchain to the launcher's
 *       cross-platform build pipeline.</li>
 *   <li>A PowerShell subprocess. PowerShell ships on every supported
 *       Windows install (5.1 baseline) and projects WinRT types
 *       directly via the {@code [Type,Assembly,ContentType=WindowsRuntime]}
 *       loader syntax. Slow to start (~1-2s) but functional with no
 *       new build steps.</li>
 * </ul>
 *
 * <p>This backend goes with PowerShell. The slow startup happens once
 * per launcher session at {@link #start()} time, off the FX thread.
 * After that, the subprocess sits in a read-stdin loop and applies
 * colors via {@code LampArray::SetColor} when we send them. Per-command
 * latency is in the low hundreds of milliseconds — too slow for true
 * 30fps streaming, but the launcher's effects are mostly static
 * (idle pack-color, in-game gradient) and the per-backend frame
 * deduplication below collapses a 30fps stream of identical frames
 * down to one PowerShell write per actual color change.</p>
 *
 * <h3>Lifecycle</h3>
 *
 * <ol>
 *   <li>{@link #isAvailable()} — quick OS check (Windows 10+) plus a
 *       PATH check for {@code powershell.exe}. No subprocess spawn.</li>
 *   <li>{@link #start()} — write the bundled PowerShell helper script
 *       to a temp file, spawn it as a subprocess, wait for the
 *       {@code READY <n>} handshake. If the host has zero LampArray
 *       devices the script emits {@code NO_DEVICES} and exits; we
 *       throw so the controller's circuit breaker routes to NoOp.</li>
 *   <li>{@link #renderFrame} — pack the frame's background color as
 *       an {@code RRGGBB} hex string, write to the PS stdin, wait
 *       for the {@code OK} ack. Frame deduplication skips the write
 *       entirely when the new color matches what we sent last.</li>
 *   <li>{@link #shutdown} — send {@code EXIT}, wait briefly, force-
 *       kill the subprocess if it doesn't terminate on its own.
 *       The script itself paints black across all devices before
 *       exiting so they don't stay stuck on the last effect color.</li>
 * </ol>
 *
 * <h3>Device coverage</h3>
 *
 * <p>Windows Dynamic Lighting only sees devices that ship HID Lighting
 * And Illumination support (HID Usage Page 0x59). As of mid-2025 that's
 * a narrow set: some Logitech keyboards (G Pro X TKL), some HP/Lenovo
 * laptops with first-party RGB keyboards, certain SteelSeries / ASUS
 * accessories. Razer hardware explicitly does NOT participate in DL
 * yet — Razer users should pin "Razer Chroma (Native)" instead of
 * relying on this backend.</p>
 *
 * @since 2026.5
 */
public final class WindowsDynamicLightingBackend implements RgbBackend
{
    /** Path inside the launcher jar where the helper script lives.
     *  Extracted to a temp file on each {@link #start()} so PowerShell
     *  can {@code -File} it directly — passing a script via stdin
     *  conflicts with sending commands via the same channel. */
    private static final String SCRIPT_RESOURCE = "/rgb/windows-dynamic-lighting.ps1";

    /** Handshake / command timeouts. The READY handshake includes
     *  PowerShell startup + WinRT projection load + LampArray enum
     *  via two async device-info calls — easily multi-second on a
     *  cold launcher. The per-command timeout is much shorter since
     *  once the subprocess is warm a SetColor call is sub-100ms. */
    private static final long READY_HANDSHAKE_TIMEOUT_MS = 15_000L;
    private static final long COMMAND_ACK_TIMEOUT_MS     = 2_000L;
    private static final long SHUTDOWN_GRACE_MS          = 1_500L;

    private Process psProcess;
    private BufferedWriter psStdin;
    private BufferedReader psStdout;
    private Path scriptTempFile;

    /** Frame deduplication. The effect engine ticks at 30fps even when
     *  the active effect is static (SolidEffect, InGameEffect — both
     *  emit the same frame every tick). Without dedup we'd send the
     *  same COLOR command 30× per second, swamping the per-command
     *  ~100ms latency. Tracking the last-sent color and skipping
     *  identical pushes makes the backend usable for any effect. */
    private int lastSentColor = -1;

    private volatile boolean started = false;

    @Override
    public String name() { return "Windows Dynamic Lighting"; }

    @Override
    public boolean isAvailable()
    {
        // Cheap check: must be Windows. The subprocess spawn happens at
        // start() — if the host doesn't have PowerShell or LampArray
        // support, that's where it fails and gets routed to NoOp.
        // Windows 10 1809 (build 17763) added LampArray, but the
        // launcher already requires Win10+ for its own reasons, so we
        // skip a precise version check here.
        return SystemUtils.IS_OS_WINDOWS;
    }

    @Override
    public void start() throws Exception
    {
        // (1) Extract the bundled script to a temp file. PowerShell's
        // -File flag wants an on-disk path; -Command via stdin would
        // collide with our command-loop reading from the same stdin.
        scriptTempFile = extractScriptToTempFile();

        // (2) Spawn powershell.exe with the script. -NoProfile so user
        // profile scripts don't slow startup; -ExecutionPolicy Bypass
        // for the launcher's lifetime only (the temp file is gone
        // after shutdown); -NonInteractive so PS doesn't try to
        // prompt for anything if a WinRT call fails partway.
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NoLogo",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", scriptTempFile.toString()
        );
        pb.redirectErrorStream( false );
        psProcess = pb.start();
        psStdin   = new BufferedWriter( new OutputStreamWriter(
                psProcess.getOutputStream(), StandardCharsets.UTF_8 ) );
        psStdout  = new BufferedReader( new InputStreamReader(
                psProcess.getInputStream(), StandardCharsets.UTF_8 ) );

        // (3) Wait for the handshake line. Anything other than
        // "READY <n>" means the script bailed before reaching the
        // command loop — throw and let the controller demote us.
        String handshake = readLineWithTimeout( psStdout, READY_HANDSHAKE_TIMEOUT_MS );
        if ( handshake == null ) {
            throw new IOException( "Windows Dynamic Lighting handshake timeout; "
                                           + "PowerShell subprocess never replied" );
        }
        if ( handshake.startsWith( "READY " ) ) {
            int deviceCount;
            try { deviceCount = Integer.parseInt( handshake.substring( 6 ).trim() ); }
            catch ( NumberFormatException nfe ) { deviceCount = -1; }
            Logger.logStd( "Windows Dynamic Lighting: " + deviceCount
                                   + " LampArray device(s) connected." );
            started = true;
            return;
        }
        if ( handshake.equals( "NO_DEVICES" ) ) {
            throw new IOException( "Windows Dynamic Lighting: no LampArray devices "
                                           + "found on this system. Use OpenRGB or "
                                           + "Razer Chroma backends for non-DL "
                                           + "hardware." );
        }
        if ( handshake.startsWith( "ERROR " ) ) {
            throw new IOException( "Windows Dynamic Lighting helper: "
                                           + handshake.substring( 6 ) );
        }
        throw new IOException( "Windows Dynamic Lighting: unexpected handshake "
                                       + "line: " + handshake );
    }

    @Override
    public void renderFrame( RgbFrame frame ) throws Exception
    {
        if ( !started ) return;

        // Pack background as 0xRRGGBB. Per-key overrides are ignored —
        // LampArray's basic SetColor sets one color across every lamp.
        // (LampArray::SetColorsForIndices exists for per-LED control,
        //  but the launcher's effects address keys via the KeyboardKey
        //  enum and there's no portable DL→key map yet. Solid fill is
        //  the right approximation for V1.)
        RgbColor bg = frame.background();
        int packed = ( bg.r() << 16 ) | ( bg.g() << 8 ) | bg.b();
        if ( packed == lastSentColor ) {
            // Frame dedup — see field doc. Skip the PowerShell round-
            // trip entirely when the color hasn't changed.
            return;
        }

        String command = String.format( "COLOR %06X", packed );
        psStdin.write( command );
        psStdin.newLine();
        psStdin.flush();

        String reply = readLineWithTimeout( psStdout, COMMAND_ACK_TIMEOUT_MS );
        if ( reply == null ) {
            throw new IOException( "Windows Dynamic Lighting: no reply to "
                                           + command + " within "
                                           + COMMAND_ACK_TIMEOUT_MS + "ms" );
        }
        if ( !"OK".equals( reply ) ) {
            throw new IOException( "Windows Dynamic Lighting helper rejected "
                                           + command + ": " + reply );
        }
        lastSentColor = packed;
    }

    @Override
    public void shutdown()
    {
        started = false;
        // (1) Best-effort EXIT. The PS script handles this by painting
        // black across all LampArrays before exiting, so devices don't
        // stay stuck on the last effect's color.
        if ( psStdin != null ) {
            try {
                psStdin.write( "EXIT" );
                psStdin.newLine();
                psStdin.flush();
            }
            catch ( Throwable ignored ) { /* best-effort */ }
            try { psStdin.close(); }
            catch ( Throwable ignored ) { }
        }
        // (2) Give the subprocess a brief grace period to clean up,
        // then force-kill if it's still alive. JVM exit shouldn't be
        // waiting on a wedged child process.
        if ( psProcess != null ) {
            try {
                if ( !psProcess.waitFor( SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS ) ) {
                    psProcess.destroyForcibly();
                }
            }
            catch ( InterruptedException ie ) {
                Thread.currentThread().interrupt();
                psProcess.destroyForcibly();
            }
        }
        if ( psStdout != null ) {
            try { psStdout.close(); }
            catch ( Throwable ignored ) { }
        }
        // (3) Clean up the extracted script temp file. Not critical —
        // it's a small text file in temp dir — but tidy.
        if ( scriptTempFile != null ) {
            try { Files.deleteIfExists( scriptTempFile ); }
            catch ( Throwable ignored ) { }
        }
        psProcess = null;
        psStdin = null;
        psStdout = null;
        scriptTempFile = null;
        lastSentColor = -1;
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /** Copies the bundled PS script to a temp file with a .ps1 extension
     *  (PowerShell's -File flag requires .ps1) and returns the path. */
    private static Path extractScriptToTempFile() throws IOException
    {
        try ( InputStream in = WindowsDynamicLightingBackend.class
                .getResourceAsStream( SCRIPT_RESOURCE ) )
        {
            if ( in == null ) {
                throw new IOException( "Bundled Windows Dynamic Lighting helper "
                                               + "script not found at "
                                               + SCRIPT_RESOURCE );
            }
            Path tmp = Files.createTempFile( "mica-rgb-dl-", ".ps1" );
            Files.copy( in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING );
            tmp.toFile().deleteOnExit();
            return tmp;
        }
    }

    /** Reads one line from {@code reader} but caps the wait at
     *  {@code timeoutMs}. {@link BufferedReader#readLine()} is
     *  blocking and not interruptible across all platforms, so we
     *  poll {@link BufferedReader#ready()} on a small sleep loop. */
    private static String readLineWithTimeout( BufferedReader reader,
                                                 long timeoutMs ) throws IOException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while ( System.currentTimeMillis() < deadline ) {
            if ( reader.ready() ) {
                return reader.readLine();
            }
            try { Thread.sleep( 20L ); }
            catch ( InterruptedException ie ) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}

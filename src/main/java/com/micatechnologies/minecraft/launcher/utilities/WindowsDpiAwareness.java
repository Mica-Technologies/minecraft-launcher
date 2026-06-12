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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import org.apache.commons.lang3.SystemUtils;

/**
 * Opts the process into Per-Monitor DPI Awareness V2 on Windows before any window
 * or DPI API is touched. JavaFX's Glass backend internally sets Per-Monitor V1,
 * which scales the JavaFX-rendered client area per-monitor but leaves the OS-
 * managed non-client area (title bar, window border) sized at the system DPI —
 * so on a multi-monitor setup with mixed scaling (e.g. 100% main + 200% 4K), the
 * client area renders correctly on the 4K monitor but the title bar stays tiny.
 * V2 extends per-monitor scaling to the non-client area too.
 *
 * <p>Must be called as the very first thing in {@code main()} before JavaFX
 * initializes — once Glass calls {@code SetProcessDpiAwareness(PER_MONITOR_V1)}
 * the awareness is locked and a later upgrade attempt fails.
 *
 * @since 2026.2
 */
public final class WindowsDpiAwareness
{
    private WindowsDpiAwareness() { /* static-only */ }

    /** Minimal user32 binding for the Win10 1703+ context-handle API. */
    public interface User32Dpi extends StdCallLibrary
    {
        User32Dpi INSTANCE = SystemUtils.IS_OS_WINDOWS
                             ? Native.load( "user32", User32Dpi.class )
                             : null;

        boolean SetProcessDpiAwarenessContext( Pointer dpiContext );
    }

    /** Minimal shcore binding for the Win8.1 fallback. */
    public interface ShcoreDpi extends StdCallLibrary
    {
        ShcoreDpi INSTANCE = SystemUtils.IS_OS_WINDOWS
                             ? Native.load( "shcore", ShcoreDpi.class )
                             : null;

        int SetProcessDpiAwareness( int value );
    }

    /** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 — Win10 1703+. Sentinel HANDLE value. */
    private static final Pointer PER_MONITOR_V2 = new Pointer( -4L );
    /** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE — Win10 1607+. */
    private static final Pointer PER_MONITOR_V1 = new Pointer( -3L );
    /** PROCESS_PER_MONITOR_DPI_AWARE — shcore fallback for Win8.1 / Win10 pre-1607. */
    private static final int LEGACY_PER_MONITOR = 2;

    /**
     * Best-effort: request Per-Monitor DPI Awareness V2, falling back through V1
     * and finally the legacy shcore call. Each step is wrapped in a catch-all so
     * a missing entry point on older Windows builds, or an "already set" rejection
     * from the OS, never blocks startup.
     */
    public static void enablePerMonitorV2()
    {
        if ( !SystemUtils.IS_OS_WINDOWS ) {
            return;
        }
        try {
            if ( User32Dpi.INSTANCE.SetProcessDpiAwarenessContext( PER_MONITOR_V2 ) ) {
                return;
            }
            if ( User32Dpi.INSTANCE.SetProcessDpiAwarenessContext( PER_MONITOR_V1 ) ) {
                return;
            }
        }
        catch ( Throwable t ) {
            // SetProcessDpiAwarenessContext not exported (pre-Win10 1703) — fall through.
        }
        try {
            ShcoreDpi.INSTANCE.SetProcessDpiAwareness( LEGACY_PER_MONITOR );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.dpiAwareness.setupFailed", t.getMessage() ) );
        }
    }
}

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

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import org.apache.commons.lang3.SystemUtils;

/**
 * Lets another process take the foreground, for handing a click over to the launcher that is
 * already running. Windows only lets the process the user just started bring a window to the
 * front; a background process that calls {@code toFront()} merely flashes its taskbar button.
 * The second launch, which the user did just start, passes that right on before it forwards.
 *
 * @since 2026.10
 */
public final class WindowsForeground
{
    /** {@code ASFW_ANY}: any process may set the foreground window. */
    private static final int ASFW_ANY = -1;

    private WindowsForeground() { /* static-only */ }

    /** Minimal user32 binding. */
    interface User32Foreground extends StdCallLibrary
    {
        /**
         * Lets the given process, or any with {@code ASFW_ANY}, set the foreground window.
         *
         * @param processId the process id, or {@code ASFW_ANY}
         *
         * @return whether the call succeeded
         */
        boolean AllowSetForegroundWindow( int processId );
    }

    /**
     * Allows any process to take the foreground window, best-effort. No-op off Windows; a missing
     * entry point or a refusal (when this process isn't in the foreground itself) is ignored, as
     * the hand-off still works without it, just with a flashing taskbar button.
     */
    public static void allowAnyProcess()
    {
        if ( !SystemUtils.IS_OS_WINDOWS ) {
            return;
        }
        try {
            Native.load( "user32", User32Foreground.class ).AllowSetForegroundWindow( ASFW_ANY );
        }
        catch ( Throwable ignored ) {
            // Best-effort; see the method comment.
        }
    }
}

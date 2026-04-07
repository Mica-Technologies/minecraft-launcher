/*
 * Copyright (c) 2021 Mica Technologies
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

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * Provides single-instance enforcement for the launcher application using a localhost {@link ServerSocket}. When the
 * launcher starts, it attempts to bind a fixed port on the loopback address. If the port is already bound (another
 * instance is running), acquisition fails and the caller should notify the user and exit.
 * <p>
 * Using a {@link ServerSocket} instead of a file lock ensures the lock is automatically released by the OS when the
 * process terminates, regardless of how it exits (crash, kill, normal shutdown). This avoids stale lock files.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public class SingleInstanceLock
{
    /**
     * The server socket used to hold the single-instance lock. Kept open for the lifetime of the process.
     */
    private static ServerSocket lockSocket = null;

    /**
     * Attempts to acquire the single-instance lock by binding a {@link ServerSocket} on localhost. The port used depends
     * on whether the launcher is in development mode, allowing dev and release builds to run simultaneously.
     *
     * @return {@code true} if the lock was acquired (this is the only running instance), {@code false} if another
     *         instance already holds the lock
     *
     * @since 1.0
     */
    public static boolean tryAcquire()
    {
        int port = LauncherConstants.LAUNCHER_IS_DEV ?
                   LauncherConstants.SINGLE_INSTANCE_PORT_DEV :
                   LauncherConstants.SINGLE_INSTANCE_PORT;
        try {
            lockSocket = new ServerSocket( port, 1, InetAddress.getLoopbackAddress() );
            return true;
        }
        catch ( IOException e ) {
            // Port is already bound -- another instance is running
            return false;
        }
    }

    /**
     * Releases the single-instance lock by closing the server socket. This is normally handled automatically by the OS
     * on process exit, but can be called explicitly during shutdown if desired.
     *
     * @since 1.0
     */
    public static void release()
    {
        if ( lockSocket != null ) {
            try {
                lockSocket.close();
            }
            catch ( IOException ignored ) {
            }
            lockSocket = null;
        }
    }
}

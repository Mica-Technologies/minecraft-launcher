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

import com.micatechnologies.minecraft.launcher.files.Logger;
import io.github.palexdev.materialfx.controls.MFXProgressBar;

import java.awt.PopupMenu;
import java.awt.Taskbar;

/**
 * Wraps {@link java.awt.Taskbar} (Java 9+) to give macOS dock parity with the Windows
 * taskbar work in {@link TaskbarProgressManager}:
 *
 * <ul>
 *     <li>Dock badge / progress arc reflecting modpack-install progress.</li>
 *     <li>Right-click dock menu mirroring the system-tray menu.</li>
 *     <li>Bouncing dock icon on update-available / error attention requests.</li>
 * </ul>
 *
 * <p>Every method is a transparent no-op on platforms that don't support the underlying
 * {@code Taskbar.Feature}. {@code Taskbar.isTaskbarSupported()} returns true on macOS and
 * Linux; the specific features ({@code PROGRESS_VALUE}, {@code MENU},
 * {@code USER_ATTENTION}) vary by platform — in practice macOS supports all three, Linux
 * supports {@code PROGRESS_VALUE} only on KDE/Unity, Windows has no entries here at all
 * (Windows is covered by FXTaskbarProgressBar via {@link TaskbarProgressManager}).</p>
 *
 * <p>Lazily initializes on first method call so AWT toolkit init can happen after the
 * JavaFX stage is up. {@link #shutdown()} is idempotent and clears the menu / progress
 * so the dock doesn't carry state into the next launcher session.</p>
 */
public final class MacOsDockManager
{
    private static volatile Taskbar taskbar    = null;
    private static volatile boolean initialized = false;
    private static volatile boolean disabled    = false;

    private MacOsDockManager() { /* static-only */ }

    /** Lazily acquires the {@link Taskbar} instance on first call. Returns true if the
     *  taskbar is usable; subsequent calls are cheap boolean checks. */
    public static synchronized boolean ensureInitialized()
    {
        if ( initialized ) {
            return taskbar != null;
        }
        initialized = true;
        if ( !Taskbar.isTaskbarSupported() ) {
            disabled = true;
            return false;
        }
        try {
            taskbar = Taskbar.getTaskbar();
            return true;
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Unable to initialize macOS dock manager: " + e.getMessage() );
            disabled = true;
            taskbar = null;
            return false;
        }
    }

    /**
     * Pushes a 0..1 fraction (or {@link MFXProgressBar#INDETERMINATE_PROGRESS}) to the dock
     * badge via {@code setProgressValue(int 0..100)}. {@code java.awt.Taskbar} only exposes a
     * window-less progress *state* setter on Windows (covered separately by FXTaskbarProgressBar),
     * so on macOS / Linux we only push the numeric value. Indeterminate progress is rendered as
     * a static 50% — macOS's dock doesn't paint an indeterminate arc cleanly, and an empty
     * dock is worse than a half-filled one while a real download is in flight.
     */
    public static void setProgress( double fraction )
    {
        if ( disabled || !ensureInitialized() ) {
            return;
        }
        if ( !taskbar.isSupported( Taskbar.Feature.PROGRESS_VALUE ) ) {
            return;
        }
        try {
            int pct;
            if ( fraction == MFXProgressBar.INDETERMINATE_PROGRESS ) {
                pct = 50;
            }
            else {
                pct = ( int ) Math.round( Math.max( 0.0, Math.min( 1.0, fraction ) ) * 100.0 );
            }
            taskbar.setProgressValue( pct );
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Dock progress update failed: " + e.getMessage() );
        }
    }

    /** Clears the dock progress overlay. Call when leaving any screen that was driving progress. */
    public static void stop()
    {
        if ( disabled || !ensureInitialized() ) {
            return;
        }
        try {
            if ( taskbar.isSupported( Taskbar.Feature.PROGRESS_VALUE ) ) {
                taskbar.setProgressValue( -1 );
            }
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Dock progress clear failed: " + e.getMessage() );
        }
    }

    /** Bounce the dock icon to request user attention — used for the "update available"
     *  attention cue and other significant async events. {@code critical=true} makes the
     *  bounce persistent; {@code false} bounces once. */
    public static void requestAttention( boolean critical )
    {
        if ( disabled || !ensureInitialized() ) {
            return;
        }
        try {
            if ( taskbar.isSupported( Taskbar.Feature.USER_ATTENTION ) ) {
                taskbar.requestUserAttention( true, critical );
            }
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Dock requestUserAttention failed: " + e.getMessage() );
        }
    }

    /** macOS equivalent of Windows' {@code showFullErrorProgress()}. {@code java.awt.Taskbar}
     *  doesn't expose the per-window error state on macOS, so we approximate by filling the
     *  progress arc to 100% and combining with the dock bounce in
     *  {@link #requestAttention(boolean)} from the caller side. */
    public static void setError()
    {
        if ( disabled || !ensureInitialized() ) {
            return;
        }
        try {
            if ( taskbar.isSupported( Taskbar.Feature.PROGRESS_VALUE ) ) {
                taskbar.setProgressValue( 100 );
            }
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Dock setError failed: " + e.getMessage() );
        }
    }

    /**
     * Sets a short text badge on the dock icon — the red pill macOS uses for unread counts.
     * Drives the "update available" cue (a "1") alongside the dock bounce. Pass {@code null}
     * or blank to clear. No-op where {@code ICON_BADGE_TEXT} isn't supported (Windows/Linux).
     */
    public static void setBadge( String text )
    {
        if ( disabled || !ensureInitialized() ) {
            return;
        }
        try {
            if ( taskbar.isSupported( Taskbar.Feature.ICON_BADGE_TEXT ) ) {
                taskbar.setIconBadge( text );
            }
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Dock badge update failed: " + e.getMessage() );
        }
    }

    /** Clears the dock icon badge. */
    public static void clearBadge()
    {
        setBadge( null );
    }

    /** Installs the right-click dock menu. macOS only (Linux's {@code Taskbar.Feature.MENU}
     *  isn't supported by any DE in practice). Pass {@code null} to clear. */
    public static void installDockMenu( PopupMenu menu )
    {
        if ( disabled || !ensureInitialized() ) {
            return;
        }
        try {
            if ( taskbar.isSupported( Taskbar.Feature.MENU ) ) {
                taskbar.setMenu( menu );
            }
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Dock setMenu failed: " + e.getMessage() );
        }
    }

    /** Cleans up dock state at app exit. Idempotent. */
    public static synchronized void shutdown()
    {
        if ( taskbar == null ) {
            return;
        }
        stop();
        clearBadge();
        installDockMenu( null );
        taskbar = null;
        initialized = false;
        disabled = false;
    }
}

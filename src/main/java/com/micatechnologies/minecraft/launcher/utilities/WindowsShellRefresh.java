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
import com.sun.glass.ui.Window;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import javafx.stage.Stage;
import org.apache.commons.lang3.SystemUtils;

import java.util.List;

/**
 * Windows-specific shell-tickling helpers. Bundled in one class so the call sites stay
 * single-line and the Glass reflection + JNA plumbing is contained.
 *
 * <p>Currently exposes one operation: {@link #forceFrameRefresh(Stage)}, which fires a
 * {@code SetWindowPos(SWP_FRAMECHANGED)} against the launcher's HWND to prompt the Windows
 * shell to re-evaluate the window's per-monitor taskbar membership. The lighter-weight
 * "1-pixel position nudge" approach previously wired into
 * {@code MCLauncherGuiWindow.nudgeIfMonitorChanged} didn't reliably wake the shell up
 * across monitor boundaries — {@code SWP_FRAMECHANGED} triggers a {@code WM_NCCALCSIZE}
 * pass which is the canonical "this window's frame state may have changed, reconsider"
 * signal.</p>
 *
 * <p>HWND resolution uses the Glass {@code Window} API via the existing
 * {@code --add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED} export the
 * FXTaskbarProgressBar dep already requires. Matching is by title because Glass doesn't
 * expose a direct {@code Stage → Window} link; we additionally fall through to "the only
 * Glass window in this process" as a one-window-app shortcut.</p>
 */
public final class WindowsShellRefresh
{
    // SetWindowPos flag constants — duplicated here rather than depending on JNA's WinUser
    // (which doesn't surface all of them as Java constants).
    /**
     * Flag indicating that the window size should not be changed.
     */
    private static final int SWP_NOSIZE        = 0x0001;
    /**
     * Flag indicating that the window position should not be changed.
     */
    private static final int SWP_NOMOVE        = 0x0002;
    /**
     * Flag indicating that the window's Z-order should not be changed.
     */
    private static final int SWP_NOZORDER      = 0x0004;
    /**
     * Flag indicating that the window should not be activated.
     */
    private static final int SWP_NOACTIVATE    = 0x0010;
    /**
     * Flag indicating that the frame of the window has changed.
     */
    private static final int SWP_FRAMECHANGED  = 0x0020;

    /** Mask: refresh the frame without moving / resizing / restacking. Fallback when we
     *  can't read the window's current bounds. */
    /**
     * Refresh flags for no operation, which includes changing the frame but not moving or resizing.
     */
    private static final int REFRESH_FLAGS_NOOP =
            SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER;

    /** Mask: re-assert the current position + size with FRAMECHANGED set. Including the
     *  real position turns the call into a "real move" event from the shell's perspective —
     *  more attention-grabbing than the NOMOVE variant, which Win11's per-monitor taskbar
     *  appears to debounce. */
    /**
     * Refresh flags for moving, which includes changing the frame and reasserting the current position and size.
     */
    private static final int REFRESH_FLAGS_MOVE =
            SWP_FRAMECHANGED | SWP_NOZORDER | SWP_NOACTIVATE;

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private WindowsShellRefresh() { /* static-only */ }

    /**
     * Forces a non-client area recalc on the launcher's window so Windows reconsiders which
     * monitor's taskbar should host its icon. No-op outside Windows. No-op if the HWND
     * lookup fails (logged at warning level so failures are visible without spamming).
     *
     * <p>Safe to call from the FX thread — JNA's {@code SetWindowPos} doesn't block on the
     * UI message pump from a remote thread, and we're not changing visible state.</p>
     *
     * @param stage the JavaFX stage for which to refresh the frame
     */
    public static void forceFrameRefresh( Stage stage )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || stage == null ) {
            return;
        }
        long handle = resolveHwnd( stage );
        if ( handle == 0L ) {
            return;
        }
        try {
            HWND hwnd = new HWND( new Pointer( handle ) );

            // Read the window's current bounds so we can re-assert them with FRAMECHANGED
            // set. Including the actual position turns this into a real "move" event,
            // which Win11's per-monitor taskbar tracks more reliably than the no-op
            // SWP_NOMOVE | SWP_NOSIZE variant. Falls back to the no-op variant if
            // GetWindowRect fails for some reason.
            RECT bounds = new RECT();
            if ( User32.INSTANCE.GetWindowRect( hwnd, bounds ) ) {
                int x = bounds.left;
                int y = bounds.top;
                int w = bounds.right  - bounds.left;
                int h = bounds.bottom - bounds.top;
                User32.INSTANCE.SetWindowPos( hwnd, null, x, y, w, h, REFRESH_FLAGS_MOVE );
            }
            else {
                User32.INSTANCE.SetWindowPos( hwnd, null, 0, 0, 0, 0, REFRESH_FLAGS_NOOP );
            }
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.shellRefresh.setWindowPosFailed", e.getMessage() ) );
        }
    }

    /** Looks up the native window handle for {@code stage}. Returns 0 on failure; callers
     *  should treat that as "skip the operation, don't fail the caller's flow". */
    /**
     * Resolves the native window handle for the given JavaFX stage.
     *
     * @param stage the JavaFX stage to resolve the HWND for
     * @return the native window handle (HWND) or 0 if resolution fails
     */
    private static long resolveHwnd( Stage stage )
    {
        try {
            List< Window > windows = Window.getWindows();
            if ( windows.isEmpty() ) {
                return 0L;
            }
            // Preferred: title match. Stage titles get a "AppName | SceneName" shape from
            // MCLauncherGuiWindow.setScene so matching is straightforward.
            String wantedTitle = stage.getTitle();
            if ( wantedTitle != null ) {
                for ( Window w : windows ) {
                    if ( wantedTitle.equals( w.getTitle() ) ) {
                        return w.getNativeWindow();
                    }
                }
            }
            // Fallback: if there's exactly one Glass window in this process, that must be us.
            // (The launcher only has one Stage in its main lifecycle; help / about dialogs
            // are short-lived and wouldn't be the foreground window during a monitor drag.)
            if ( windows.size() == 1 ) {
                return windows.get( 0 ).getNativeWindow();
            }
            return 0L;
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.shellRefresh.resolveHwndFailed", e.getMessage() ) );
            return 0L;
        }
    }
}

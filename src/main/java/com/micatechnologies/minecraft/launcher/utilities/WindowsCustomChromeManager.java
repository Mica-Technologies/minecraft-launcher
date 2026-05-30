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
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.LONG_PTR;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser.WindowProc;
import com.sun.jna.win32.StdCallLibrary;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Control;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.apache.commons.lang3.SystemUtils;

/**
 * Windows "Window Controls Overlay" chrome: keeps the standard decorated ({@code UNIFIED})
 * window — so the OS draws the real minimize / maximize / close buttons and owns resize,
 * maximize, aero-snap and the Windows 11 snap-layouts flyout — but subclasses Glass's window
 * procedure to <b>remove the title bar</b> so the JavaFX content fills to the very top edge,
 * with the native controls floating over the top-right corner.
 *
 * <p>Always-on on Windows (no toggle): the decorated window is the launcher's normal window, so
 * this just extends content upward. The two message handlers:</p>
 * <ul>
 *   <li><b>WM_NCCALCSIZE</b> — reclaims the title-bar area so the client rect reaches the top
 *       (with a frame inset when maximized so content isn't clipped under the screen edge / taskbar).</li>
 *   <li><b>WM_NCHITTEST</b> — returns the resize borders; {@code HTMINBUTTON/HTMAXBUTTON/HTCLOSE}
 *       for the native caption-button rects (so the OS buttons + snap-layouts flyout keep working);
 *       and over the rest of the top strip, picks the JavaFX scene: a point over an interactive
 *       control returns {@code HTCLIENT} (so the launcher's own top-bar buttons stay clickable —
 *       the Windows analogue of the macOS drag-exclusion), empty space returns {@code HTCAPTION}
 *       so the OS drags / double-click-maximizes / shows the system menu.</li>
 * </ul>
 *
 * <p><b>Fallback.</b> Gated on Windows + 64-bit and wrapped so any failure leaves
 * {@link #isActive()} {@code false} and the standard window intact. The WndProc callback runs on
 * the Glass/JavaFX message-pump thread; every branch is inside one {@code try/catch(Throwable)}
 * returning a safe pass-through so an exception can never escape into Win32. The callback +
 * original-proc references are held in {@code static} fields for the JVM lifetime — a collected
 * callback would crash the process on the next message.</p>
 *
 * @author Mica Technologies
 * @since 3.5
 */
public final class WindowsCustomChromeManager
{
    // ---- Caption metrics (logical / 96-DPI units), scaled to physical for hit-testing. --------
    /** Title-bar / caption strip height — matches the standard Win11 caption. */
    static final int CAPTION_HEIGHT = 32;
    /** Width of one native caption button (minimize/maximize/close). */
    static final int BUTTON_WIDTH   = 46;
    /** Number of native caption buttons reserved at the top-right. */
    static final int BUTTON_COUNT   = 3;

    // ---- Win32 constants ---------------------------------------------------------------------
    private static final int GWL_STYLE    = -16;
    private static final int GWLP_WNDPROC = -4;
    private static final int WS_MAXIMIZE  = 0x01000000;

    private static final int SWP_NOMOVE       = 0x0002;
    private static final int SWP_NOSIZE       = 0x0001;
    private static final int SWP_NOZORDER     = 0x0004;
    private static final int SWP_NOACTIVATE   = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;

    private static final int WM_NCCALCSIZE = 0x0083;
    private static final int WM_NCHITTEST  = 0x0084;
    private static final int WM_DPICHANGED = 0x02E0;

    private static final int HTCLIENT      = 1;
    private static final int HTCAPTION     = 2;
    private static final int HTLEFT        = 10;
    private static final int HTRIGHT       = 11;
    private static final int HTTOP         = 12;
    private static final int HTTOPLEFT     = 13;
    private static final int HTTOPRIGHT    = 14;
    private static final int HTBOTTOM      = 15;
    private static final int HTBOTTOMLEFT  = 16;
    private static final int HTBOTTOMRIGHT = 17;
    private static final int HTMINBUTTON   = 8;
    private static final int HTMAXBUTTON   = 9;
    private static final int HTCLOSE       = 20;

    // ---- State (single window in this process) -----------------------------------------------
    private static volatile boolean active = false;
    private static volatile Stage   boundStage = null;
    private static volatile HWND    boundHwnd  = null;
    /** Retained for the JVM lifetime so its native trampoline isn't GC'd. */
    private static WindowProc subclassProc = null;
    private static LONG_PTR   originalProc = null;
    private static volatile int currentDpi = 96;

    private WindowsCustomChromeManager() { /* static-only */ }

    /** @return true once the WndProc subclass installed successfully. */
    public static boolean isActive() { return active; }

    /** Width (logical px) the native caption buttons occupy at the top-right — used by the
     *  Windows top-bar layout so the launcher's own controls clear them. */
    public static double reservedButtonsWidthLogical() { return (double) BUTTON_WIDTH * BUTTON_COUNT; }

    /**
     * Installs the chrome on the stage's native window. No-op off Windows. Safe to call before
     * the window is shown (defers to {@code WINDOW_SHOWN}). Idempotent.
     */
    public static void install( Stage stage )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || stage == null || active ) {
            return;
        }
        if ( Native.POINTER_SIZE != 8 ) {
            Logger.logWarningSilent( "WindowsCustomChrome: skipping — not a 64-bit JVM." );
            return;
        }
        if ( !stage.isShowing() ) {
            stage.addEventHandler( WindowEvent.WINDOW_SHOWN, new javafx.event.EventHandler<>()
            {
                @Override
                public void handle( WindowEvent event )
                {
                    stage.removeEventHandler( WindowEvent.WINDOW_SHOWN, this );
                    Platform.runLater( () -> install( stage ) );
                }
            } );
            return;
        }
        try {
            HWND hwnd = WindowChromeManager.resolveHwnd( stage );
            if ( hwnd == null ) {
                Logger.logWarningSilent( "WindowsCustomChrome: could not resolve HWND; standard title bar kept." );
                return;
            }
            User32Ex u = User32Ex.INSTANCE;
            subclassProc = WindowsCustomChromeManager::wndProc;
            originalProc = u.SetWindowLongPtrW( hwnd, GWLP_WNDPROC, subclassProc );
            if ( originalProc == null ) {
                Logger.logWarningSilent( "WindowsCustomChrome: SetWindowLongPtrW returned null; aborting." );
                subclassProc = null;
                return;
            }
            boundStage = stage;
            boundHwnd  = hwnd;
            currentDpi = queryDpi( hwnd );
            active = true;
            // Trigger a non-client recalc so the title bar is removed immediately.
            u.SetWindowPos( hwnd, null, 0, 0, 0, 0,
                            SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED );
            Logger.logStd( "WindowsCustomChrome: title bar inset active (DPI " + currentDpi + ")." );
        }
        catch ( Throwable t ) {
            active = false;
            Logger.logWarningSilent( "WindowsCustomChrome: install failed (" + t.getClass().getSimpleName()
                                             + ": " + t.getMessage() + "); standard title bar kept." );
        }
    }

    /** Restores the original window procedure. Defensive only — normal teardown is process exit. */
    public static void uninstall()
    {
        if ( !active || boundHwnd == null || originalProc == null ) {
            return;
        }
        try {
            User32Ex.INSTANCE.SetWindowLongPtrW( boundHwnd, GWLP_WNDPROC, originalProc );
            User32Ex.INSTANCE.SetWindowPos( boundHwnd, null, 0, 0, 0, 0,
                                            SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "WindowsCustomChrome: uninstall failed: " + t.getMessage() );
        }
        active = false;
    }

    // =========================================================================================
    //  Window procedure (runs on the Glass/JavaFX message-pump thread)
    // =========================================================================================

    private static LRESULT wndProc( HWND hwnd, int uMsg, WPARAM wParam, LPARAM lParam )
    {
        try {
            switch ( uMsg ) {
                case WM_NCCALCSIZE:
                    if ( wParam.intValue() != 0 ) {
                        return onNcCalcSize( hwnd, lParam );
                    }
                    break;
                case WM_NCHITTEST:
                    return onNcHitTest( hwnd, lParam );
                case WM_DPICHANGED:
                    currentDpi = ( wParam.intValue() >> 16 ) & 0xFFFF;
                    if ( currentDpi <= 0 ) {
                        currentDpi = 96;
                    }
                    break;
                default:
                    break;
            }
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "WindowsCustomChrome: WndProc msg 0x" + Integer.toHexString( uMsg )
                                             + " threw " + t.getClass().getSimpleName() );
        }
        return User32Ex.INSTANCE.CallWindowProcW( originalProc, hwnd, uMsg, wParam, lParam );
    }

    /** WM_NCCALCSIZE: reclaim the title bar so the client rect reaches the top. */
    private static LRESULT onNcCalcSize( HWND hwnd, LPARAM lParam )
    {
        // lParam -> NCCALCSIZE_PARAMS; rgrc[0] is the proposed client RECT (4 ints at offset 0).
        Pointer p = new Pointer( lParam.longValue() );
        if ( isMaximized( hwnd ) ) {
            int fx = frameThicknessX();
            int fy = frameThicknessY();
            p.setInt( 0, p.getInt( 0 ) + fx );    // left
            p.setInt( 4, p.getInt( 4 ) + fy );    // top
            p.setInt( 8, p.getInt( 8 ) - fx );    // right
            p.setInt( 12, p.getInt( 12 ) - fy );  // bottom
        }
        // Non-maximized: leave the rect = full window. Resize handled by our hit-test grab bands;
        // title bar is gone because we reserve no top inset.
        return new LRESULT( 0 );
    }

    /** WM_NCHITTEST: resize borders, native caption buttons, and interactive-aware drag strip. */
    private static LRESULT onNcHitTest( HWND hwnd, LPARAM lParam )
    {
        RECT wr = new RECT();
        if ( !User32Ex.INSTANCE.GetWindowRect( hwnd, wr ) ) {
            return new LRESULT( HTCLIENT );
        }
        long lp = lParam.longValue();
        int screenX = (short) ( lp & 0xFFFF );
        int screenY = (short) ( ( lp >> 16 ) & 0xFFFF );
        int relX = screenX - wr.left;
        int relY = screenY - wr.top;
        int width = wr.right - wr.left;
        int height = wr.bottom - wr.top;

        boolean maximized = isMaximized( hwnd );
        int capH = scale( CAPTION_HEIGHT );
        int nativeButtonsW = scale( BUTTON_WIDTH ) * BUTTON_COUNT;

        // 1. Native caption buttons (far top-right). Returning these codes keeps the OS-drawn
        //    min/max/close clickable and makes the Win11 snap-layouts flyout fire on HTMAXBUTTON.
        if ( relY >= 0 && relY < capH && relX >= width - nativeButtonsW && relX < width ) {
            int fromRight = width - relX;
            int btnW = scale( BUTTON_WIDTH );
            if ( fromRight <= btnW )     return new LRESULT( HTCLOSE );
            if ( fromRight <= btnW * 2 ) return new LRESULT( HTMAXBUTTON );
            return new LRESULT( HTMINBUTTON );
        }

        // 2. The launcher's own interactive controls in the caption strip (top-bar buttons, the
        //    title-bar help button) stay clickable — checked BEFORE the resize border so their top
        //    edge isn't a resize handle, matching how the native caption buttons behave. Mirrors
        //    the macOS isOnInteractiveNode drag exclusion.
        if ( relY >= 0 && relY < capH
                && isOverInteractiveControl( relX / dpiScale(), relY / dpiScale() ) ) {
            return new LRESULT( HTCLIENT );
        }

        // 3. Resize borders (none when maximized).
        if ( !maximized ) {
            int b = scale( 8 );
            boolean top = relY < b, bottom = relY >= height - b, left = relX < b, right = relX >= width - b;
            if ( top && left )     return new LRESULT( HTTOPLEFT );
            if ( top && right )    return new LRESULT( HTTOPRIGHT );
            if ( bottom && left )  return new LRESULT( HTBOTTOMLEFT );
            if ( bottom && right ) return new LRESULT( HTBOTTOMRIGHT );
            if ( top )    return new LRESULT( HTTOP );
            if ( bottom ) return new LRESULT( HTBOTTOM );
            if ( left )   return new LRESULT( HTLEFT );
            if ( right )  return new LRESULT( HTRIGHT );
        }

        // 4. Rest of the caption strip drags the window.
        if ( relY >= 0 && relY < capH ) {
            return new LRESULT( HTCAPTION );
        }

        // 5. Everything else is JavaFX content.
        return new LRESULT( HTCLIENT );
    }

    // ---- scene picking (FX thread) -----------------------------------------------------------

    /**
     * True if the given scene-space point (logical units) lies over a visible, interactive
     * JavaFX control. Runs the pick on the FX thread (the WndProc executes there); if for any
     * reason it isn't the FX thread, conservatively returns {@code false} (treat as drag) rather
     * than touch the scene graph off-thread.
     */
    private static boolean isOverInteractiveControl( double sceneX, double sceneY )
    {
        if ( !Platform.isFxApplicationThread() ) {
            return false;
        }
        Stage stage = boundStage;
        Scene scene = ( stage != null ) ? stage.getScene() : null;
        if ( scene == null || scene.getRoot() == null ) {
            return false;
        }
        Node hit = pick( scene.getRoot(), sceneX, sceneY );
        while ( hit != null ) {
            if ( isInteractive( hit ) ) {
                return true;
            }
            hit = hit.getParent();
        }
        return false;
    }

    /** Returns the topmost visible node containing the scene-space point, or null. */
    private static Node pick( Node node, double sceneX, double sceneY )
    {
        if ( node == null || !node.isVisible() || node.isMouseTransparent() ) {
            return null;
        }
        Bounds inScene = node.localToScene( node.getBoundsInLocal() );
        if ( inScene == null || !inScene.contains( sceneX, sceneY ) ) {
            return null;
        }
        if ( node instanceof Parent parent ) {
            var children = parent.getChildrenUnmodifiable();
            for ( int i = children.size() - 1; i >= 0; i-- ) {
                Node hit = pick( children.get( i ), sceneX, sceneY );
                if ( hit != null ) {
                    return hit;
                }
            }
        }
        return node;
    }

    /** Heuristic for "the user expects to click this, not drag the window." */
    private static boolean isInteractive( Node n )
    {
        if ( n instanceof Control || n instanceof ButtonBase ) {
            return true;
        }
        if ( n.getOnMouseClicked() != null || n.getOnMousePressed() != null ) {
            return true;
        }
        // The "?" help glyph and similar are plain nodes carrying a click handler / marker class.
        return n.getStyleClass().contains( "helpButton" )
                || n.getStyleClass().contains( "winCaptionBtn" );
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static boolean isMaximized( HWND hwnd )
    {
        try {
            return ( User32Ex.INSTANCE.GetWindowLongW( hwnd, GWL_STYLE ) & WS_MAXIMIZE ) != 0;
        }
        catch ( Throwable t ) {
            return false;
        }
    }

    private static int frameThicknessX()
    {
        try {
            return User32Ex.INSTANCE.GetSystemMetrics( 32 /* SM_CXFRAME */ )
                    + User32Ex.INSTANCE.GetSystemMetrics( 92 /* SM_CXPADDEDBORDER */ );
        }
        catch ( Throwable t ) {
            return scale( 8 );
        }
    }

    private static int frameThicknessY()
    {
        try {
            return User32Ex.INSTANCE.GetSystemMetrics( 33 /* SM_CYFRAME */ )
                    + User32Ex.INSTANCE.GetSystemMetrics( 92 /* SM_CXPADDEDBORDER */ );
        }
        catch ( Throwable t ) {
            return scale( 8 );
        }
    }

    private static float dpiScale() { return currentDpi / 96f; }

    private static int scale( int logical ) { return Math.round( logical * dpiScale() ); }

    private static int queryDpi( HWND hwnd )
    {
        try {
            int dpi = User32Ex.INSTANCE.GetDpiForWindow( hwnd );
            return dpi > 0 ? dpi : 96;
        }
        catch ( Throwable t ) {
            return 96;   // GetDpiForWindow needs Win10 1607+
        }
    }

    // =========================================================================================
    //  JNA bindings — loaded with NO function mapper so methods use the exact exported symbol
    //  names (the *W wide variants where they exist), avoiding the W32APIOptions mapper appending
    //  "W" to functions like SetWindowPos that have no wide variant.
    // =========================================================================================

    private interface User32Ex extends StdCallLibrary
    {
        User32Ex INSTANCE = Native.load( "user32", User32Ex.class );

        int      GetWindowLongW( HWND hWnd, int nIndex );
        LONG_PTR SetWindowLongPtrW( HWND hWnd, int nIndex, WindowProc proc );
        LONG_PTR SetWindowLongPtrW( HWND hWnd, int nIndex, LONG_PTR dwNewLong );
        LRESULT  CallWindowProcW( LONG_PTR lpPrevWndFunc, HWND hWnd, int msg, WPARAM wParam, LPARAM lParam );
        boolean  SetWindowPos( HWND hWnd, HWND hWndInsertAfter, int X, int Y, int cx, int cy, int uFlags );
        boolean  GetWindowRect( HWND hWnd, RECT rect );
        int      GetSystemMetrics( int nIndex );
        int      GetDpiForWindow( HWND hWnd );
    }
}

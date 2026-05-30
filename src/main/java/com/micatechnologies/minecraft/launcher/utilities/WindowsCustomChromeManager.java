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
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD.LONG_PTR;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser.WindowProc;
import com.sun.jna.win32.StdCallLibrary;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.apache.commons.lang3.SystemUtils;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Experimental Windows frameless title bar ("Window Controls Overlay"). When the user enables
 * the {@code windowsCustomChrome} flag, the stage is created {@link javafx.stage.StageStyle#UNDECORATED}
 * and this manager turns it back into a real, resizable, snappable window whose title bar is
 * removed so the JavaFX content fills to the top edge — while the min/max/close controls
 * (drawn in JavaFX by {@link WindowsTitleBarControls}) sit top-right and behave natively,
 * including the Windows 11 snap-layouts flyout on the maximize button.
 *
 * <p><b>How.</b> On the realized {@code HWND} we (1) re-add the native frame styles
 * ({@code WS_THICKFRAME | WS_CAPTION | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU}) so the OS
 * still owns resize / maximize / aero-snap, and (2) subclass the window procedure via
 * {@code SetWindowLongPtrW(GWLP_WNDPROC, ...)}. The subclass:</p>
 * <ul>
 *   <li><b>WM_NCCALCSIZE</b> — reclaims the title-bar area so the client rect reaches the top
 *       (with a frame inset when maximized so content isn't clipped under the screen edge / taskbar).</li>
 *   <li><b>WM_NCHITTEST</b> — returns {@code HTLEFT/RIGHT/TOP/BOTTOM}+corners for the resize
 *       borders, {@code HTMINBUTTON/HTMAXBUTTON/HTCLOSE} for the three caption-button cells
 *       (HTMAXBUTTON is what makes Win11 show the snap-layouts flyout), {@code HTCAPTION} for the
 *       drag strip, and {@code HTCLIENT} everywhere else so the JavaFX scene gets the event.</li>
 *   <li><b>WM_NCLBUTTONUP / WM_NC*MOUSE*</b> — performs the caption-button action and feeds a
 *       hover index back to the JavaFX side for themed hover visuals.</li>
 * </ul>
 *
 * <p><b>Fallback.</b> Everything is gated on Windows + the flag and wrapped so any failure leaves
 * {@link #isActive()} {@code false}; the caller then keeps the standard UNIFIED + Mica window
 * (see {@code MCLauncherGuiWindow}). The WndProc callback runs on the JavaFX/Glass message-pump
 * thread; every branch is inside one {@code try/catch(Throwable)} that returns a safe pass-through
 * so an exception can never escape into Win32, and all FX-touching actions hop through
 * {@link Platform#runLater}. The callback + original-proc references are held in {@code static}
 * fields for the JVM lifetime — a collected callback would crash the process on the next message.</p>
 *
 * @author Mica Technologies
 * @since 3.5
 */
public final class WindowsCustomChromeManager
{
    // ---- Shared LOGICAL metrics (JavaFX units). The FX controls (WindowsTitleBarControls) draw
    //      the buttons at exactly these sizes; the WndProc hit-tests the same region scaled to
    //      physical pixels by the window DPI. Keeping a single source of truth means no fragile
    //      runtime localToScreen handshake is needed for the geometry. -------------------------
    /** Caption strip height in JavaFX (logical) units — matches the standard Win11 title bar. */
    public static final int BASE_CAPTION_HEIGHT = 32;
    /** Width of each caption button in JavaFX (logical) units. */
    public static final int BASE_BUTTON_WIDTH   = 46;
    /** Number of caption buttons (minimize, maximize, close). */
    public static final int BUTTON_COUNT        = 3;

    // ---- Win32 constants ---------------------------------------------------------------------
    private static final int GWL_STYLE     = -16;
    private static final int GWLP_WNDPROC  = -4;

    private static final int WS_POPUP       = 0x80000000;
    private static final int WS_CAPTION     = 0x00C00000;
    private static final int WS_THICKFRAME  = 0x00040000;
    private static final int WS_MINIMIZEBOX = 0x00020000;
    private static final int WS_MAXIMIZEBOX = 0x00010000;
    private static final int WS_SYSMENU     = 0x00080000;
    private static final int WS_MAXIMIZE    = 0x01000000;

    private static final int SWP_NOMOVE     = 0x0002;
    private static final int SWP_NOSIZE     = 0x0001;
    private static final int SWP_NOZORDER   = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;

    private static final int WM_NCCALCSIZE   = 0x0083;
    private static final int WM_NCHITTEST    = 0x0084;
    private static final int WM_NCMOUSEMOVE  = 0x00A0;
    private static final int WM_NCLBUTTONDOWN = 0x00A1;
    private static final int WM_NCLBUTTONUP  = 0x00A2;
    private static final int WM_NCMOUSELEAVE = 0x02A2;
    private static final int WM_MOUSEMOVE    = 0x0200;
    private static final int WM_DPICHANGED   = 0x02E0;

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

    private static final int SM_CXFRAME        = 32;
    private static final int SM_CYFRAME        = 33;
    private static final int SM_CXPADDEDBORDER = 92;

    // ---- State (single window in this process) -----------------------------------------------
    private static volatile boolean active = false;
    private static volatile Stage   boundStage = null;
    private static volatile HWND    boundHwnd  = null;
    /** Retained for the JVM lifetime so its native trampoline isn't GC'd (would crash on next msg). */
    private static WindowProc subclassProc = null;
    private static LONG_PTR   originalProc = null;
    private static volatile int currentDpi = 96;
    private static volatile int lastHover  = -1;
    /** FX-side hook that paints the hovered caption button (index 0=min,1=max,2=close, -1=none). */
    private static volatile IntConsumer hoverListener = null;

    private WindowsCustomChromeManager() { /* static-only */ }

    /** @return true once the WndProc subclass installed successfully — callers gate the JavaFX
     *  control overlay + navbar shift on this so a failed install transparently falls back. */
    public static boolean isActive() { return active; }

    /** Width (JavaFX/logical units) reserved at the top-right for the caption buttons; used by the
     *  main-screen navbar shift so its trailing controls clear the buttons. */
    public static double reservedWidthLogical() { return (double) BASE_BUTTON_WIDTH * BUTTON_COUNT; }

    /** Sets the FX-side hover painter. Called from {@link WindowsTitleBarControls}. */
    public static void setHoverListener( IntConsumer listener ) { hoverListener = listener; }

    /**
     * Installs the custom chrome on the stage's native window. No-op unless on Windows; the caller
     * should only invoke this when the {@code windowsCustomChrome} flag is on and the stage was
     * created {@code UNDECORATED}. Safe to call before the window is shown — it defers to
     * {@code WINDOW_SHOWN} (the HWND doesn't exist until then). Idempotent.
     */
    public static void install( Stage stage )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || stage == null || active ) {
            return;
        }
        // SetWindowLongPtrW only round-trips a full pointer on 64-bit; the build targets x64.
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
                Logger.logWarningSilent( "WindowsCustomChrome: could not resolve HWND; staying with standard chrome." );
                return;
            }
            installOnHwnd( stage, hwnd );
        }
        catch ( Throwable t ) {
            active = false;
            Logger.logWarningSilent( "WindowsCustomChrome: install failed (" + t.getClass().getSimpleName()
                                             + ": " + t.getMessage() + "); falling back to standard chrome." );
        }
    }

    private static void installOnHwnd( Stage stage, HWND hwnd )
    {
        User32Ex u = User32Ex.INSTANCE;

        // 1. Re-add native frame styles so the OS still owns resize / maximize / aero-snap.
        //    Clear WS_POPUP (UNDECORATED leaves it set) and switch to an overlapped frame.
        int style = u.GetWindowLongW( hwnd, GWL_STYLE );
        int newStyle = ( style & ~WS_POPUP )
                | WS_CAPTION | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU;
        u.SetWindowLongW( hwnd, GWL_STYLE, newStyle );

        // 2. Subclass the window procedure (must be live before the SWP_FRAMECHANGED below so the
        //    first WM_NCCALCSIZE strips the title bar). Retain references for the JVM lifetime.
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

        // 3. Force a non-client recalc so the title bar is removed immediately.
        u.SetWindowPos( hwnd, null, 0, 0, 0, 0,
                        SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED );

        Logger.logStd( "WindowsCustomChrome: frameless title bar active (DPI " + currentDpi + ")." );
    }

    /**
     * Restores the original window procedure. Defensive only — the flag is restart-gated, so the
     * normal teardown is process exit (Windows reclaims the subclass). Not wired into the close
     * path to avoid touching the WndProc while messages may be in flight.
     */
    public static void uninstall( Stage stage )
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
                case WM_NCMOUSEMOVE:
                    updateHover( hitToButtonIndex( wParam.intValue() ) );
                    break;          // fall through to default for normal NC processing
                case WM_MOUSEMOVE:
                    updateHover( -1 );
                    break;
                case WM_NCMOUSELEAVE:
                    updateHover( -1 );
                    break;
                case WM_NCLBUTTONDOWN: {
                    int btn = hitToButtonIndex( wParam.intValue() );
                    if ( btn >= 0 ) {
                        return new LRESULT( 0 );   // swallow so DefWindowProc doesn't start a modal loop
                    }
                    break;
                }
                case WM_NCLBUTTONUP: {
                    int btn = hitToButtonIndex( wParam.intValue() );
                    if ( btn >= 0 ) {
                        performCaptionAction( btn );
                        return new LRESULT( 0 );
                    }
                    break;
                }
                case WM_DPICHANGED:
                    currentDpi = ( wParam.intValue() >> 16 ) & 0xFFFF;
                    if ( currentDpi <= 0 ) {
                        currentDpi = 96;
                    }
                    break;          // let the default proc move/resize to the suggested rect
                default:
                    break;
            }
        }
        catch ( Throwable t ) {
            // Never let an exception escape into Win32 — log once and pass through.
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
            // Maximized: the OS over-extends the frame by the border thickness on every side, which
            // would push content off-screen / under the taskbar. Inset by the frame size.
            int fx = frameThicknessX();
            int fy = frameThicknessY();
            p.setInt( 0, p.getInt( 0 ) + fx );    // left
            p.setInt( 4, p.getInt( 4 ) + fy );    // top
            p.setInt( 8, p.getInt( 8 ) - fx );    // right
            p.setInt( 12, p.getInt( 12 ) - fy );  // bottom
        }
        // Non-maximized: leave the rect = full window (client reaches every edge). Resize is handled
        // by our WM_NCHITTEST grab bands; the title bar is gone because we reserve no top inset.
        return new LRESULT( 0 );
    }

    /** WM_NCHITTEST: resize borders, caption buttons (native snap-layouts), drag strip. */
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
        int capH = scale( BASE_CAPTION_HEIGHT );
        int controlsW = scale( BASE_BUTTON_WIDTH ) * BUTTON_COUNT;

        // 1. Caption buttons (top-right). Checked first so the buttons stay fully hittable
        //    (and the Win11 snap-layouts flyout shows) even at the very top edge.
        if ( relY >= 0 && relY < capH && relX >= width - controlsW && relX < width ) {
            int fromRight = width - relX;                 // 1..controlsW
            int btnW = scale( BASE_BUTTON_WIDTH );
            if ( fromRight <= btnW )       return new LRESULT( HTCLOSE );
            if ( fromRight <= btnW * 2 )   return new LRESULT( HTMAXBUTTON );
            return new LRESULT( HTMINBUTTON );
        }

        // 2. Resize borders (none when maximized).
        if ( !maximized ) {
            int b = scale( 8 );                            // grab-band thickness
            boolean top = relY < b, bottom = relY >= height - b, left = relX < b, right = relX >= width - b;
            if ( top && left )    return new LRESULT( HTTOPLEFT );
            if ( top && right )   return new LRESULT( HTTOPRIGHT );
            if ( bottom && left ) return new LRESULT( HTBOTTOMLEFT );
            if ( bottom && right )return new LRESULT( HTBOTTOMRIGHT );
            if ( top )    return new LRESULT( HTTOP );
            if ( bottom ) return new LRESULT( HTBOTTOM );
            if ( left )   return new LRESULT( HTLEFT );
            if ( right )  return new LRESULT( HTRIGHT );
        }

        // 3. Drag strip (the rest of the caption row).
        if ( relY >= 0 && relY < capH ) {
            return new LRESULT( HTCAPTION );
        }

        // 4. Everything else is JavaFX content.
        return new LRESULT( HTCLIENT );
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static void performCaptionAction( int btn )
    {
        final Stage stage = boundStage;
        if ( stage == null ) {
            return;
        }
        Platform.runLater( () -> {
            try {
                switch ( btn ) {
                    case 0 -> stage.setIconified( true );
                    case 1 -> stage.setMaximized( !stage.isMaximized() );
                    // Fire WINDOW_CLOSE_REQUEST (what the native close button does) rather than
                    // stage.close() — the latter skips the per-screen onCloseRequest handlers that
                    // run the exit-confirmation / unsaved-changes flow and the app's cleanup.
                    case 2 -> stage.fireEvent(
                            new WindowEvent( stage, WindowEvent.WINDOW_CLOSE_REQUEST ) );
                    default -> { /* none */ }
                }
            }
            catch ( Throwable ignored ) { /* best-effort */ }
        } );
    }

    /** Maps a hit-test code to a caption-button index (0=min,1=max,2=close) or -1. */
    private static int hitToButtonIndex( int hit )
    {
        return switch ( hit ) {
            case HTMINBUTTON -> 0;
            case HTMAXBUTTON -> 1;
            case HTCLOSE     -> 2;
            default          -> -1;
        };
    }

    /** Pushes a hover-index change to the FX side (debounced to actual changes). */
    private static void updateHover( int index )
    {
        if ( index == lastHover ) {
            return;
        }
        lastHover = index;
        final IntConsumer l = hoverListener;
        if ( l != null ) {
            Platform.runLater( () -> {
                try { l.accept( index ); }
                catch ( Throwable ignored ) { /* visual only */ }
            } );
        }
    }

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
            return User32Ex.INSTANCE.GetSystemMetrics( SM_CXFRAME )
                    + User32Ex.INSTANCE.GetSystemMetrics( SM_CXPADDEDBORDER );
        }
        catch ( Throwable t ) {
            return scale( 8 );
        }
    }

    private static int frameThicknessY()
    {
        try {
            return User32Ex.INSTANCE.GetSystemMetrics( SM_CYFRAME )
                    + User32Ex.INSTANCE.GetSystemMetrics( SM_CXPADDEDBORDER );
        }
        catch ( Throwable t ) {
            return scale( 8 );
        }
    }

    /** Scales a logical (96-DPI) length to physical pixels for the window's current DPI. */
    private static int scale( int logical )
    {
        return Math.round( logical * currentDpi / 96f );
    }

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
    //  JNA bindings — loaded with NO function mapper, so methods use the exact exported symbol
    //  names (the *W wide variants where they exist). This avoids the W32APIOptions mapper
    //  appending "W" to functions like SetWindowPos that have no wide variant.
    // =========================================================================================

    private interface User32Ex extends StdCallLibrary
    {
        User32Ex INSTANCE = Native.load( "user32", User32Ex.class );

        int      GetWindowLongW( HWND hWnd, int nIndex );
        int      SetWindowLongW( HWND hWnd, int nIndex, int dwNewLong );
        LONG_PTR SetWindowLongPtrW( HWND hWnd, int nIndex, WindowProc proc );
        LONG_PTR SetWindowLongPtrW( HWND hWnd, int nIndex, LONG_PTR dwNewLong );
        LRESULT  CallWindowProcW( LONG_PTR lpPrevWndFunc, HWND hWnd, int msg, WPARAM wParam, LPARAM lParam );
        boolean  SetWindowPos( HWND hWnd, HWND hWndInsertAfter, int X, int Y, int cx, int cy, int uFlags );
        boolean  GetWindowRect( HWND hWnd, RECT rect );
        int      GetSystemMetrics( int nIndex );
        int      GetDpiForWindow( HWND hWnd );
    }

    /** Win32 MARGINS — kept for a future {@code DwmExtendFrameIntoClientArea} top-shadow pass. */
    @SuppressWarnings( "unused" )
    @Structure.FieldOrder( { "cxLeftWidth", "cxRightWidth", "cyTopHeight", "cyBottomHeight" } )
    public static class MARGINS extends Structure
    {
        public int cxLeftWidth;
        public int cxRightWidth;
        public int cyTopHeight;
        public int cyBottomHeight;
    }
}

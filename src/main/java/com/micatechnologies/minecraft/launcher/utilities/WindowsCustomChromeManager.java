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
 * Windows custom title-bar chrome: drops {@code WS_CAPTION} so the OS stops drawing its own
 * title-bar strip + min/max/close, and subclasses Glass's window procedure to <b>reclaim the
 * remaining frame</b> so the JavaFX content fills to the very top edge. The launcher draws its own
 * caption buttons in their place ({@link WindowsTitleBarControls}). The window stays maximizable
 * (WS_MAXIMIZEBOX) + resizable (WS_THICKFRAME), so aero-snap and the Windows 11 snap-layouts flyout
 * keep working via the hit-test below.
 *
 * <p>Always-on on Windows (no toggle). The style change + two message handlers:</p>
 * <ul>
 *   <li><b>WS_CAPTION removed</b> (in {@link #install}) — no native caption strip or buttons; only
 *       our themed JavaFX ones remain. Restored in {@link #uninstall}.</li>
 *   <li><b>WM_NCCALCSIZE</b> — reclaims the remaining frame so the client rect reaches the top
 *       (with a frame inset when maximized so content isn't clipped under the screen edge / taskbar).</li>
 *   <li><b>WM_NCHITTEST</b> — returns the resize borders; {@code HTMAXBUTTON} over the maximize
 *       slot only (so the Windows 11 snap-layouts flyout fires on hover and the OS owns
 *       maximize/restore); and over the rest of the top strip, picks the JavaFX scene: a point over
 *       an interactive control returns {@code HTCLIENT} — which includes our own JavaFX
 *       minimize/close caption buttons and the launcher's top-bar controls (the Windows analogue of
 *       the macOS drag-exclusion) — while empty space returns {@code HTCAPTION} so the OS drags /
 *       double-click-maximizes / shows the system menu.</li>
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
    /** Full title-bar strip height — matches the launcher's 52px navbar so the custom caption
     *  buttons fill it and the whole brand bar acts as the drag region. */
    static final int TITLE_BAR_HEIGHT = 52;
    /** Width of one caption button (minimize/maximize/close). */
    static final int BUTTON_WIDTH   = 46;
    /** Number of caption buttons reserved at the top-right (minimize / maximize / close). */
    static final int BUTTON_COUNT   = 3;

    // ---- Win32 constants ---------------------------------------------------------------------
    private static final int GWL_STYLE    = -16;
    private static final int GWLP_WNDPROC = -4;
    private static final int WS_MAXIMIZE     = 0x01000000;
    private static final int WS_CAPTION      = 0x00C00000;   // WS_BORDER | WS_DLGFRAME (title bar)
    private static final int WS_SYSMENU      = 0x00080000;
    private static final int WS_THICKFRAME   = 0x00040000;   // sizing border (resize)
    private static final int WS_MINIMIZEBOX  = 0x00020000;
    private static final int WS_MAXIMIZEBOX  = 0x00010000;

    private static final int TME_LEAVE        = 0x0002;
    private static final int TME_NONCLIENT    = 0x0010;

    private static final int SWP_NOMOVE       = 0x0002;
    private static final int SWP_NOSIZE       = 0x0001;
    private static final int SWP_NOZORDER     = 0x0004;
    private static final int SWP_NOACTIVATE   = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;

    private static final int WM_NCCALCSIZE   = 0x0083;
    private static final int WM_NCHITTEST    = 0x0084;
    private static final int WM_NCACTIVATE   = 0x0086;
    private static final int WM_NCMOUSEMOVE  = 0x00A0;
    private static final int WM_NCMOUSELEAVE = 0x02A2;
    private static final int WM_MOUSEMOVE    = 0x0200;
    private static final int WM_DPICHANGED    = 0x02E0;

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
    private static final int HTMAXBUTTON   = 9;

    // ---- State (single window in this process) -----------------------------------------------
    private static volatile boolean active = false;
    private static volatile Stage   boundStage = null;
    private static volatile HWND    boundHwnd  = null;
    /** Retained for the JVM lifetime so its native trampoline isn't GC'd. */
    private static WindowProc subclassProc = null;
    private static LONG_PTR   originalProc = null;
    /** The window's GWL_STYLE before we dropped WS_CAPTION, so uninstall() can restore it. */
    private static volatile int originalStyle = 0;
    /** Notified (on the FX thread) when the pointer enters/leaves the maximize button's
     *  HTMAXBUTTON rect, so the launcher can paint a hover the OS-owned button can't get from FX. */
    private static volatile java.util.function.Consumer< Boolean > maxHoverSink = null;
    /** Debounces the hover sink so it only fires on actual enter/leave transitions. */
    private static volatile boolean maxButtonHovered = false;
    private static volatile int currentDpi = 96;

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private WindowsCustomChromeManager() { /* static-only */ }

    /**
     * Checks if the custom chrome is currently active.
     *
     * @return {@code true} if the WndProc subclass installed successfully, otherwise {@code false}.
     */
    public static boolean isActive() { return active; }

    /**
     * Registers a sink notified (on the FX thread) on maximize-button hover enter/leave. The OS
     * owns that button (HTMAXBUTTON) so it never gets JavaFX hover events; this lets the launcher
     * paint a matching hover state itself.
     *
     * @param sink the consumer to be notified of maximize button hover events, or {@code null} to clear.
     */
    public static void setMaxButtonHoverSink( java.util.function.Consumer< Boolean > sink )
    {
        maxHoverSink = sink;
    }

    /**
     * Requests a one-shot WM_NCMOUSELEAVE so the maximize hover clears reliably when the pointer
     * leaves the non-client area (e.g. moves up into the snap-layouts flyout or off the window).
     *
     * @param hwnd the handle to the window.
     */
    private static void armNcLeaveTracking( HWND hwnd )
    {
        try {
            TRACKMOUSEEVENT tme = new TRACKMOUSEEVENT();
            tme.cbSize = tme.size();
            tme.dwFlags = TME_LEAVE | TME_NONCLIENT;
            tme.hwndTrack = hwnd;
            tme.dwHoverTime = 0;
            User32Ex.INSTANCE.TrackMouseEvent( tme );
        }
        catch ( Throwable t ) {
            // best-effort: WM_MOUSEMOVE in the client area still clears the hover
        }
    }

    /**
     * Fires the hover sink only on a real enter/leave transition.
     *
     * @param hovered {@code true} if the pointer has entered the maximize button, otherwise {@code false}.
     */
    private static void setMaxHover( boolean hovered )
    {
        if ( maxButtonHovered == hovered ) {
            return;
        }
        maxButtonHovered = hovered;
        java.util.function.Consumer< Boolean > sink = maxHoverSink;
        if ( sink != null ) {
            Platform.runLater( () -> sink.accept( hovered ) );
        }
    }

    /**
     * Gets the width (logical px) that the native caption buttons occupy at the top-right.
     *
     * @return the reserved width for the buttons in logical pixels.
     */
    public static double reservedButtonsWidthLogical() { return (double) BUTTON_WIDTH * BUTTON_COUNT; }

    /**
     * Installs the chrome on the stage's native window. No-op off Windows. Safe to call before
     * the window is shown (defers to {@code WINDOW_SHOWN}). Idempotent.
     *
     * @param stage the JavaFX stage to install the custom chrome on.
     */
    public static void install( Stage stage )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || stage == null ) {
            return;
        }
        // Already installed on THIS stage — genuine idempotent no-op.
        if ( active && boundStage == stage ) {
            return;
        }
        // Installed on a PRIOR stage. An in-process restart (e.g. Save & Restart for a
        // language change) tears down the old window and builds a brand-new Stage +
        // HWND, but this JVM — and therefore the static `active`/`boundHwnd` state —
        // survives. Without clearing it, the `active` guard would no-op and the new
        // window would keep its native WS_CAPTION title bar showing *above* our JavaFX
        // navbar (the double-title-bar bug). Detach from the old binding first
        // (uninstall() restores the prior window proc + style if that HWND somehow
        // still lives, and is a harmless no-op once it's destroyed), which also clears
        // `active`, then fall through to subclass the new window.
        if ( active && boundStage != stage ) {
            Logger.logStd( LocalizationManager.get( "log.customChrome.rebinding" ) );
            uninstall();
        }
        if ( Native.POINTER_SIZE != 8 ) {
            Logger.logWarningSilent( LocalizationManager.get( "log.customChrome.notSixtyFourBit" ) );
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
                Logger.logWarningSilent( LocalizationManager.get( "log.customChrome.resolveHwndFailed" ) );
                return;
            }
            User32Ex u = User32Ex.INSTANCE;
            subclassProc = WindowsCustomChromeManager::wndProc;
            originalProc = u.SetWindowLongPtrW( hwnd, GWLP_WNDPROC, subclassProc );
            if ( originalProc == null ) {
                Logger.logWarningSilent( LocalizationManager.get( "log.customChrome.setWndProcNull" ) );
                subclassProc = null;
                return;
            }
            boundStage = stage;
            boundHwnd  = hwnd;
            currentDpi = queryDpi( hwnd );
            active = true;
            // Drop WS_CAPTION so the OS stops painting its own title-bar strip + min/max/close
            // overlay — we draw our own caption buttons in JavaFX. Keep the window maximizable
            // (WS_MAXIMIZEBOX) so returning HTMAXBUTTON from the hit-test still pops the Windows 11
            // snap-layouts flyout, plus the resize border (WS_THICKFRAME), minimize, and system
            // menu. WM_NCCALCSIZE below still reclaims the thin remaining frame so content fills
            // to the top edge.
            try {
                originalStyle = u.GetWindowLongW( hwnd, GWL_STYLE );
                int custom = ( originalStyle & ~WS_CAPTION )
                        | WS_THICKFRAME | WS_MAXIMIZEBOX | WS_MINIMIZEBOX | WS_SYSMENU;
                u.SetWindowLongW( hwnd, GWL_STYLE, custom );
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.customChrome.dropCaptionFailed",
                                                                     t.getMessage() ) );
            }
            // Trigger a non-client recalc so the title bar is removed immediately.
            u.SetWindowPos( hwnd, null, 0, 0, 0, 0,
                            SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED );
            Logger.logStd( LocalizationManager.format( "log.customChrome.active", currentDpi ) );
        }
        catch ( Throwable t ) {
            active = false;
            Logger.logWarningSilent( LocalizationManager.format( "log.customChrome.installFailed",
                                                                 t.getClass().getSimpleName(), t.getMessage() ) );
        }
    }

    /**
     * Restores the original window procedure. Defensive only — normal teardown is process exit.
     */
    public static void uninstall()
    {
        if ( !active || boundHwnd == null || originalProc == null ) {
            return;
        }
        try {
            if ( originalStyle != 0 ) {
                User32Ex.INSTANCE.SetWindowLongW( boundHwnd, GWL_STYLE, originalStyle );
            }
            User32Ex.INSTANCE.SetWindowLongPtrW( boundHwnd, GWLP_WNDPROC, originalProc );
            User32Ex.INSTANCE.SetWindowPos( boundHwnd, null, 0, 0, 0, 0,
                                            SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.customChrome.uninstallFailed", t.getMessage() ) );
        }
        active = false;
    }

    // =========================================================================================
    //  Window procedure (runs on the Glass/JavaFX message-pump thread)
    // =========================================================================================

    /**
     * The window procedure that handles various Windows messages.
     *
     * @param hwnd   the handle to the window.
     * @param uMsg   the message identifier.
     * @param wParam the first message parameter.
     * @param lParam the second message parameter.
     * @return the result of the message processing.
     */
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
                case WM_NCACTIVATE:
                    // On focus change Windows repaints the non-client frame to reflect the
                    // active/inactive state, which briefly flashes the default title bar through our
                    // reclaimed caption when the launcher regains focus. Forwarding with lParam = -1
                    // updates the activation state WITHOUT repainting the non-client area — the
                    // standard custom-frame fix for that flash. (Return value is the default proc's,
                    // so activation/deactivation still proceeds normally.)
                    return User32Ex.INSTANCE.CallWindowProcW( originalProc, hwnd, uMsg, wParam,
                                                              new LPARAM( -1 ) );
                case WM_NCMOUSEMOVE:
                    // wParam is the hit-test code under the pointer; light our maximize button when
                    // it's over the HTMAXBUTTON slot. Falls through to the default proc so the OS
                    // still renders the snap-layouts flyout. Arm non-client leave tracking so we
                    // reliably get WM_NCMOUSELEAVE when the pointer exits (incl. up into the flyout).
                    setMaxHover( wParam.intValue() == HTMAXBUTTON );
                    armNcLeaveTracking( hwnd );
                    break;
                case WM_MOUSEMOVE:
                case WM_NCMOUSELEAVE:
                    // Pointer is in the client area (over our other buttons / content) or left the
                    // non-client area entirely — either way it's no longer on the maximize button.
                    setMaxHover( false );
                    break;
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
            Logger.logWarningSilent( LocalizationManager.format( "log.customChrome.wndProcThrew",
                                                                 Integer.toHexString( uMsg ),
                                                                 t.getClass().getSimpleName() ) );
        }
        return User32Ex.INSTANCE.CallWindowProcW( originalProc, hwnd, uMsg, wParam, lParam );
    }

    /**
     * Handles the WM_NCCALCSIZE message to reclaim the title bar so the client rect reaches the top.
     *
     * @param hwnd   the handle to the window.
     * @param lParam the second message parameter containing the NCCALCSIZE_PARAMS structure.
     * @return the result of the message processing.
     */
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

    /**
     * Handles the WM_NCHITTEST message to determine the resize borders, native caption buttons,
     * and interactive-aware drag strip.
     *
     * @param hwnd   the handle to the window.
     * @param lParam the second message parameter containing the mouse coordinates.
     * @return the result of the hit test.
     */
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
        int capH = scale( TITLE_BAR_HEIGHT );
        int nativeButtonsW = scale( BUTTON_WIDTH ) * BUTTON_COUNT;

        // When maximized, WM_NCCALCSIZE inset the client area by the frame thickness, so the JavaFX
        // content (and our caption buttons) start that far in from the window-rect edges. Shift to
        // content-relative coordinates so the button rects + the scene pick line up with where the
        // buttons actually render. Non-maximized: origin is (0,0), so cx/cy == relX/relY.
        int originX = maximized ? frameThicknessX() : 0;
        int originY = maximized ? frameThicknessY() : 0;
        int cx = relX - originX;
        int cy = relY - originY;
        int contentWidth = width - originX * 2;

        // 1. Maximize button (the middle of the three top-right slots) → HTMAXBUTTON, which is what
        //    makes the Windows 11 snap-layouts flyout appear on hover and lets the OS own
        //    maximize/restore. The minimize (left) and close (right) slots are deliberately NOT
        //    special-cased here: they fall through to the interactive-control check below, where our
        //    own JavaFX caption buttons claim HTCLIENT and handle the clicks (so they get themed
        //    hover + a red close). Only the maximize glyph needs the native flyout.
        if ( cy >= 0 && cy < capH && cx >= contentWidth - nativeButtonsW && cx < contentWidth ) {
            int fromRight = contentWidth - cx;
            int btnW = scale( BUTTON_WIDTH );
            if ( fromRight > btnW && fromRight <= btnW * 2 ) {
                return new LRESULT( HTMAXBUTTON );
            }
        }

        // 2. The launcher's own interactive controls in the caption strip (our minimize/close
        //    caption buttons, the help button, top-bar controls) stay clickable — checked BEFORE the
        //    resize border so their top edge isn't a resize handle, matching how the native caption
        //    buttons behave. Mirrors the macOS isOnInteractiveNode drag exclusion.
        if ( cy >= 0 && cy < capH
                && isOverInteractiveControl( cx / dpiScale(), cy / dpiScale() ) ) {
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
        if ( cy >= 0 && cy < capH ) {
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
     *
     * @param sceneX the x-coordinate in scene space.
     * @param sceneY the y-coordinate in scene space.
     * @return {@code true} if the point is over an interactive control, otherwise {@code false}.
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

    /**
     * Returns the topmost visible node containing the scene-space point, or null.
     *
     * @param node   the node to start the search from.
     * @param sceneX the x-coordinate in scene space.
     * @param sceneY the y-coordinate in scene space.
     * @return the topmost visible node containing the point, or null if none found.
     */
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

    /**
     * Heuristic for "the user expects to click this, not drag the window."
     *
     * @param n the node to check.
     * @return {@code true} if the node is interactive, otherwise {@code false}.
     */
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

    /**
     * Checks if the window is currently maximized.
     *
     * @param hwnd the handle to the window.
     * @return {@code true} if the window is maximized, otherwise {@code false}.
     */
    private static boolean isMaximized( HWND hwnd )
    {
        try {
            return ( User32Ex.INSTANCE.GetWindowLongW( hwnd, GWL_STYLE ) & WS_MAXIMIZE ) != 0;
        }
        catch ( Throwable t ) {
            return false;
        }
    }

    /**
     * Gets the frame thickness in the x-direction.
     *
     * @return the frame thickness in pixels.
     */
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

    /**
     * Gets the frame thickness in the y-direction.
     *
     * @return the frame thickness in pixels.
     */
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

    /**
     * Calculates the DPI scale factor.
     *
     * @return the DPI scale factor as a float.
     */
    private static float dpiScale() { return currentDpi / 96f; }

    /**
     * Scales a logical value to physical pixels based on the current DPI.
     *
     * @param logical the logical value to scale.
     * @return the scaled value in physical pixels.
     */
    private static int scale( int logical ) { return Math.round( logical * dpiScale() ); }

    /**
     * Queries the DPI for a given window.
     *
     * @param hwnd the handle to the window.
     * @return the DPI of the window, or 96 if an error occurs.
     */
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

    /**
     * Interface for accessing Windows API functions using JNA.
     */
    private interface User32Ex extends StdCallLibrary
    {
        User32Ex INSTANCE = Native.load( "user32", User32Ex.class );

        /**
         * Retrieves the specified window style bits.
         *
         * @param hWnd the handle to the window.
         * @param nIndex the index of the value to retrieve.
         * @return the window style bits.
         */
        int      GetWindowLongW( HWND hWnd, int nIndex );

        /**
         * Sets new extended window styles for the specified window.
         *
         * @param hWnd the handle to the window.
         * @param nIndex the index of the value to set.
         * @param dwNewLong the new style bits.
         * @return the previous style bits.
         */
        int      SetWindowLongW( HWND hWnd, int nIndex, int dwNewLong );

        /**
         * Sets a new window procedure for the specified window.
         *
         * @param hWnd the handle to the window.
         * @param nIndex the index of the value to set.
         * @param proc the new window procedure.
         * @return the previous window procedure.
         */
        LONG_PTR SetWindowLongPtrW( HWND hWnd, int nIndex, WindowProc proc );

        /**
         * Sets a new window procedure for the specified window using a pointer.
         *
         * @param hWnd the handle to the window.
         * @param nIndex the index of the value to set.
         * @param dwNewLong the new style bits as a pointer.
         * @return the previous style bits.
         */
        LONG_PTR SetWindowLongPtrW( HWND hWnd, int nIndex, LONG_PTR dwNewLong );

        /**
         * Calls the previous window procedure for the specified window.
         *
         * @param lpPrevWndFunc the address of the previous window procedure.
         * @param hWnd the handle to the window.
         * @param msg the message identifier.
         * @param wParam the first message parameter.
         * @param lParam the second message parameter.
         * @return the result of the message processing.
         */
        LRESULT  CallWindowProcW( LONG_PTR lpPrevWndFunc, HWND hWnd, int msg, WPARAM wParam, LPARAM lParam );

        /**
         * Changes the size, position, and Z order of a window.
         *
         * @param hWnd the handle to the window.
         * @param hWndInsertAfter the handle to the window that precedes the positioned window in the Z order.
         * @param X the new position of the left side of the window.
         * @param Y the new position of the top of the window.
         * @param cx the new width of the window.
         * @param cy the new height of the window.
         * @param uFlags the window sizing and positioning flags.
         * @return {@code true} if the function succeeds, otherwise {@code false}.
         */
        boolean  SetWindowPos( HWND hWnd, HWND hWndInsertAfter, int X, int Y, int cx, int cy, int uFlags );

        /**
         * Retrieves the dimensions of the bounding rectangle of the specified window.
         *
         * @param hWnd the handle to the window.
         * @param rect a pointer to a RECT structure that receives the screen coordinates of the upper-left and lower-right corners of the window.
         * @return {@code true} if the function succeeds, otherwise {@code false}.
         */
        boolean  GetWindowRect( HWND hWnd, RECT rect );

        /**
         * Retrieves various system metrics or configuration settings.
         *
         * @param nIndex the system metric or configuration setting to retrieve.
         * @return the value of the specified system metric or configuration setting.
         */
        int      GetSystemMetrics( int nIndex );

        /**
         * Retrieves the DPI associated with a window.
         *
         * @param hWnd the handle to the window.
         * @return the DPI of the window.
         */
        int      GetDpiForWindow( HWND hWnd );

        /**
         * Tracks mouse events for a specified window.
         *
         * @param lpEventTrack a pointer to a TRACKMOUSEEVENT structure that contains information about the tracking request.
         * @return {@code true} if the function succeeds, otherwise {@code false}.
         */
        boolean  TrackMouseEvent( TRACKMOUSEEVENT lpEventTrack );
    }

    /**
     * Win32 TRACKMOUSEEVENT — used to request a one-shot non-client mouse-leave notification.
     */
    @com.sun.jna.Structure.FieldOrder( { "cbSize", "dwFlags", "hwndTrack", "dwHoverTime" } )
    public static class TRACKMOUSEEVENT extends com.sun.jna.Structure
    {
        public int  cbSize;
        public int  dwFlags;
        public HWND hwndTrack;
        public int  dwHoverTime;
    }
}

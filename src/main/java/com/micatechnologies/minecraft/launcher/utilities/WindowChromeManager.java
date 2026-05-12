/*
 * Copyright (c) 2026 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 */

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.files.Logger;
import com.sun.glass.ui.Window;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import javafx.stage.Stage;
import org.apache.commons.lang3.SystemUtils;

import java.util.List;

/**
 * Windows native chrome integration. Wraps {@code dwmapi.DwmSetWindowAttribute} calls so the
 * launcher can flip the title-bar dark mode and request a Mica system backdrop without
 * shipping platform branches in every caller.
 *
 * <p>All public methods are no-ops on non-Windows platforms. On Windows they look up the
 * stage's native {@code HWND} via Glass {@code Window.getWindows()} (same path
 * {@link WindowsShellRefresh} uses) and forward to DWM. Failures are logged but never
 * propagated — chrome niceties shouldn't break the rest of the app.</p>
 *
 * <p>References:
 * <ul>
 *   <li>DWMWA_USE_IMMERSIVE_DARK_MODE (20): supported on Win10 20H1+ / Win11. Some Win10
 *       builds need the older attribute id 19 instead — we try 20 first, fall back to 19
 *       if the API call reports failure.</li>
 *   <li>DWMWA_SYSTEMBACKDROP_TYPE (38): Win11 22H2+. Values: 1=auto, 2=DWMSBT_MAINWINDOW
 *       (Mica), 3=DWMSBT_TRANSIENTWINDOW (Acrylic), 4=DWMSBT_TABBEDWINDOW (Mica Alt).</li>
 * </ul>
 *
 * @since 2025.2
 */
public final class WindowChromeManager
{
    /** DWMWA_USE_IMMERSIVE_DARK_MODE — modern (Win10 20H1+, Win11). */
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE        = 20;
    /** Legacy attribute id on early Win10 19H1/19H2 builds. */
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY = 19;
    /** DWMWA_BORDER_COLOR — Win11 22H2+. COLORREF or DWMWA_COLOR_DEFAULT (0xFFFFFFFF). */
    private static final int DWMWA_BORDER_COLOR                   = 34;
    /** DWMWA_CAPTION_COLOR — Win11 22H2+. COLORREF or DWMWA_COLOR_DEFAULT. */
    private static final int DWMWA_CAPTION_COLOR                  = 35;
    /** DWMWA_TEXT_COLOR — Win11 22H2+. COLORREF for the caption text. */
    private static final int DWMWA_TEXT_COLOR                     = 36;
    /** DWMWA_SYSTEMBACKDROP_TYPE — Win11 22H2+. */
    private static final int DWMWA_SYSTEMBACKDROP_TYPE            = 38;

    /** Sentinel value telling DWM to use the system default for that attribute. */
    public static final int COLOR_DEFAULT = 0xFFFFFFFF;

    /** DWMSBT_AUTO (let the system pick) / DWMSBT_NONE (no backdrop). */
    public static final int BACKDROP_NONE  = 1;
    /** DWMSBT_MAINWINDOW — Mica. */
    public static final int BACKDROP_MICA  = 2;
    /** DWMSBT_TRANSIENTWINDOW — Acrylic. */
    public static final int BACKDROP_ACRYLIC = 3;
    /** DWMSBT_TABBEDWINDOW — Mica Alt (tabbed look). */
    public static final int BACKDROP_MICA_ALT = 4;

    private WindowChromeManager() { /* static-only */ }

    /** JNA binding for the two {@code dwmapi.dll} entry points we care about. */
    public interface Dwmapi extends StdCallLibrary
    {
        Dwmapi INSTANCE = SystemUtils.IS_OS_WINDOWS
                          ? Native.load( "dwmapi", Dwmapi.class )
                          : null;

        int DwmSetWindowAttribute( HWND hwnd, int dwAttribute, IntByReference pvAttribute, int cbAttribute );
    }

    /**
     * Switches the title-bar appearance to match the app's selected theme. Cross-platform
     * entry point — on Windows this calls DWM's immersive-dark-mode attribute, on macOS
     * it routes to {@link com.micatechnologies.minecraft.launcher.utilities.MacOsVibrancyManager#applyTitleBarAppearance}
     * which sets the NSWindow's NSAppearance (DarkAqua / Aqua). Linux falls through as a
     * no-op since there's no system-wide title-bar API.
     *
     * @param stage    the Stage whose native window should be flipped
     * @param darkMode true to render the title bar in dark mode, false for light
     */
    public static void applyTitleBarDarkMode( Stage stage, boolean darkMode )
    {
        if ( stage == null ) {
            return;
        }
        if ( SystemUtils.IS_OS_MAC ) {
            com.micatechnologies.minecraft.launcher.utilities.MacOsVibrancyManager
                    .applyTitleBarAppearance( stage, darkMode );
            return;
        }
        if ( !SystemUtils.IS_OS_WINDOWS ) {
            return;
        }
        HWND hwnd = resolveHwnd( stage );
        if ( hwnd == null ) {
            return;
        }
        IntByReference value = new IntByReference( darkMode ? 1 : 0 );
        try {
            int result = Dwmapi.INSTANCE.DwmSetWindowAttribute(
                    hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, value, 4 );
            if ( result != 0 ) {
                // Old Win10 builds need the legacy attribute id. The "failure" return is
                // S_OK + nonzero or an HRESULT — either way the safest fallback is to retry
                // with the legacy id and ignore its result, since we've already failed the
                // primary path.
                Dwmapi.INSTANCE.DwmSetWindowAttribute(
                        hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY, value, 4 );
            }
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "DwmSetWindowAttribute (dark mode) failed: " + t.getMessage() );
        }
    }

    /**
     * Paints the title bar with a custom color. Encodes the JavaFX {@link javafx.scene.paint.Color}
     * to a Windows {@code COLORREF} (0x00BBGGRR) and forwards to DWM. Requires Win11 22H2+ —
     * older builds silently ignore. Pass {@code null} to revert to the system default.
     *
     * @param stage the Stage whose chrome to recolor
     * @param color the JavaFX color to apply, or {@code null} for system default
     */
    public static void applyCaptionColor( Stage stage, javafx.scene.paint.Color color )
    {
        setColorRef( stage, DWMWA_CAPTION_COLOR, color );
    }

    /**
     * Paints the window's outer border with a custom color. Same Win11 22H2+ requirement
     * as {@link #applyCaptionColor}. Useful for blending the border into the theme bg so
     * the window doesn't get a contrasting frame at the edges.
     *
     * @param stage the Stage whose border to recolor
     * @param color the JavaFX color to apply, or {@code null} for system default
     */
    public static void applyBorderColor( Stage stage, javafx.scene.paint.Color color )
    {
        setColorRef( stage, DWMWA_BORDER_COLOR, color );
    }

    /**
     * Sets the title-bar text color. Pair with {@link #applyCaptionColor} on themes whose
     * caption color contrasts poorly with the default (e.g. setting a light caption needs
     * a darker text color).
     */
    public static void applyCaptionTextColor( Stage stage, javafx.scene.paint.Color color )
    {
        setColorRef( stage, DWMWA_TEXT_COLOR, color );
    }

    /** Shared helper — encodes a JavaFX Color as a Windows COLORREF and forwards to DWM. */
    private static void setColorRef( Stage stage, int dwmAttribute, javafx.scene.paint.Color color )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || stage == null ) {
            return;
        }
        HWND hwnd = resolveHwnd( stage );
        if ( hwnd == null ) {
            return;
        }
        int colorRef = ( color == null )
                       ? COLOR_DEFAULT
                       : encodeColorRef( color );
        IntByReference value = new IntByReference( colorRef );
        try {
            Dwmapi.INSTANCE.DwmSetWindowAttribute( hwnd, dwmAttribute, value, 4 );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "DwmSetWindowAttribute (color " + dwmAttribute
                                             + ") failed: " + t.getMessage() );
        }
    }

    /** Encodes a JavaFX Color (sRGB) into Windows' COLORREF format: 0x00BBGGRR. */
    private static int encodeColorRef( javafx.scene.paint.Color color )
    {
        int r = (int) Math.round( color.getRed()   * 255.0 ) & 0xFF;
        int g = (int) Math.round( color.getGreen() * 255.0 ) & 0xFF;
        int b = (int) Math.round( color.getBlue()  * 255.0 ) & 0xFF;
        return ( b << 16 ) | ( g << 8 ) | r;
    }

    /**
     * Forces a full repaint of the window's client area + non-client area. Use after
     * changing DWM attributes (Mica, caption color, border color) that DWM doesn't
     * proactively invalidate — without this nudge the old pixels stay on screen until
     * the next ambient repaint event (focus, move, resize).
     *
     * <p>Uses {@code RedrawWindow(RDW_INVALIDATE | RDW_ERASE | RDW_FRAME |
     * RDW_ALLCHILDREN | RDW_UPDATENOW)} — the "throw out everything you've got and
     * redraw from scratch" combination. Safe to call from the FX thread.</p>
     */
    public static void forceFullRepaint( Stage stage )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || stage == null ) {
            return;
        }
        HWND hwnd = resolveHwnd( stage );
        if ( hwnd == null ) {
            return;
        }
        // RedrawWindow flags (per WinUser.h):
        //   RDW_INVALIDATE   0x0001 — mark the region invalid
        //   RDW_ERASE        0x0004 — issue WM_ERASEBKGND on next paint
        //   RDW_FRAME        0x0400 — include the non-client frame
        //   RDW_ALLCHILDREN  0x0080 — descend into child windows
        //   RDW_UPDATENOW    0x0100 — paint immediately, don't queue
        final int flags = 0x0001 | 0x0004 | 0x0400 | 0x0080 | 0x0100;
        try {
            com.sun.jna.platform.win32.User32.INSTANCE.RedrawWindow(
                    hwnd, null, null, new com.sun.jna.platform.win32.WinDef.DWORD( flags ) );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "RedrawWindow failed: " + t.getMessage() );
        }
    }

    /**
     * Requests an OS-native backdrop material (Mica / Acrylic / Mica Alt) behind the
     * window. Requires Windows 11 22H2 or newer; older builds silently ignore the call.
     * The scene fill should be transparent (or a translucent color) for the backdrop to
     * actually show through — see {@link javafx.scene.Scene#setFill(javafx.scene.paint.Paint)}.
     *
     * @param stage        the Stage to apply the backdrop to
     * @param backdropType one of {@link #BACKDROP_MICA}, {@link #BACKDROP_ACRYLIC},
     *                     {@link #BACKDROP_MICA_ALT}, or {@link #BACKDROP_NONE} to clear
     */
    public static void applyBackdrop( Stage stage, int backdropType )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || stage == null ) {
            return;
        }
        HWND hwnd = resolveHwnd( stage );
        if ( hwnd == null ) {
            return;
        }
        IntByReference value = new IntByReference( backdropType );
        try {
            Dwmapi.INSTANCE.DwmSetWindowAttribute(
                    hwnd, DWMWA_SYSTEMBACKDROP_TYPE, value, 4 );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "DwmSetWindowAttribute (backdrop) failed: " + t.getMessage() );
        }
    }

    /**
     * Translates a {@link Stage} to its native {@code HWND} via the Glass {@code Window}
     * registry. Title-match first (same approach {@link WindowsShellRefresh} uses), then
     * fall through to "the single Glass window in this process" when there's only one.
     */
    private static HWND resolveHwnd( Stage stage )
    {
        try {
            List< Window > windows = Window.getWindows();
            if ( windows.isEmpty() ) {
                return null;
            }
            String wantedTitle = stage.getTitle();
            if ( wantedTitle != null ) {
                for ( Window w : windows ) {
                    if ( wantedTitle.equals( w.getTitle() ) ) {
                        return new HWND( new Pointer( w.getNativeWindow() ) );
                    }
                }
            }
            if ( windows.size() == 1 ) {
                return new HWND( new Pointer( windows.get( 0 ).getNativeWindow() ) );
            }
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "Could not resolve HWND for stage: " + t.getMessage() );
        }
        return null;
    }
}

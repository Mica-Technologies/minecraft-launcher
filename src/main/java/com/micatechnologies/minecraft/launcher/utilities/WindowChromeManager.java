/*
 * Copyright (c) 2026 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 */

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.sun.glass.ui.Window;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.ptr.ByReference;
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
    /** Sentinel value telling DWM to draw <i>no</i> fill for that attribute — the caption / border
     *  then composites like the client area (so a Mica backdrop shows through it uniformly). */
    public static final int COLOR_NONE = 0xFFFFFFFE;

    /** DWMSBT_AUTO (let the system pick) / DWMSBT_NONE (no backdrop). */
    public static final int BACKDROP_NONE  = 1;
    /** DWMSBT_MAINWINDOW — Mica. */
    public static final int BACKDROP_MICA  = 2;
    /** DWMSBT_TRANSIENTWINDOW — Acrylic. */
    public static final int BACKDROP_ACRYLIC = 3;
    /** DWMSBT_TABBEDWINDOW — Mica Alt (tabbed look). */
    public static final int BACKDROP_MICA_ALT = 4;

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private WindowChromeManager() { /* static-only */ }

    /** JNA binding for the two {@code dwmapi.dll} entry points we care about. */
    public interface Dwmapi extends StdCallLibrary
    {
        Dwmapi INSTANCE = SystemUtils.IS_OS_WINDOWS
                          ? Native.load( "dwmapi", Dwmapi.class )
                          : null;

        /**
         * Sets a window attribute using the DWM API.
         *
         * @param hwnd          the handle to the window
         * @param dwAttribute   the attribute identifier
         * @param pvAttribute   the value of the attribute
         * @param cbAttribute   the size of the attribute value in bytes
         * @return              a result code indicating success or failure
         */
        int DwmSetWindowAttribute( HWND hwnd, int dwAttribute, IntByReference pvAttribute, int cbAttribute );

        /**
         * Extends the DWM-composited frame into the client area by the given {@link MARGINS}. Used by
         * the custom-chrome path: a window that has dropped {@code WS_CAPTION} and zeroed
         * {@code WM_NCCALCSIZE} otherwise loses DWM frame composition for its top edge, which Windows
         * then renders in the old "classic" non-client style (the white top line + classic caption
         * buttons). A 1px top margin re-arms composition so the modern frame, drop shadow, and the
         * Windows 11 snap-layouts flyout work, while the launcher's opaque content paints over it.
         *
         * @param hwnd      the handle to the window
         * @param pMarInset the margins (in physical px) to extend the frame by
         * @return an {@code HRESULT} (0 = {@code S_OK})
         */
        int DwmExtendFrameIntoClientArea( HWND hwnd, MARGINS pMarInset );

        /**
         * The DWM default window procedure. A custom-frame window calls this <i>first</i> from its
         * own window proc so DWM can render and hit-test the native caption affordances it still owns
         * — chiefly the Windows 11 maximize/snap-layouts button — that a fully app-drawn frame can't
         * reproduce. Returns {@code true} (and fills {@code plResult}) when DWM handled the message;
         * the caller then returns that result instead of forwarding to the original proc.
         *
         * @param hwnd     the handle to the window
         * @param msg      the message identifier
         * @param wParam   the first message parameter
         * @param lParam   the second message parameter
         * @param plResult receives the message result when DWM handled it
         * @return {@code true} if DWM handled the message, otherwise {@code false}
         */
        boolean DwmDefWindowProc( HWND hwnd, int msg, WPARAM wParam, LPARAM lParam, LRESULTByReference plResult );
    }

    /**
     * Win32 {@code MARGINS} — the per-edge inset (physical px) for {@code DwmExtendFrameIntoClientArea}.
     */
    @Structure.FieldOrder( { "cxLeftWidth", "cxRightWidth", "cyTopHeight", "cyBottomHeight" } )
    public static class MARGINS extends Structure
    {
        public int cxLeftWidth;
        public int cxRightWidth;
        public int cyTopHeight;
        public int cyBottomHeight;
    }

    /**
     * A by-reference {@code LRESULT} (pointer-sized) out-param for {@link Dwmapi#DwmDefWindowProc}.
     */
    public static class LRESULTByReference extends ByReference
    {
        /** Allocates the pointer-sized backing storage for the out-param. */
        public LRESULTByReference() { super( Native.POINTER_SIZE ); }

        /**
         * Reads the value DWM wrote.
         *
         * @return the {@code LRESULT} DWM stored, as a JNA {@link LRESULT}
         */
        public LRESULT getValue() { return new LRESULT( getPointer().getLong( 0 ) ); }
    }

    /**
     * Re-arms DWM frame composition for a custom-chrome (caption-stripped) window by extending the
     * frame 1px into the client area. Without this a window that dropped {@code WS_CAPTION} renders
     * its top edge in the legacy "classic" non-client style — the white top line and classic caption
     * buttons reported in issue #80. No-op off Windows / on a null handle; failures are logged, never
     * thrown (chrome niceties must not break the app).
     *
     * @param hwnd the native window handle to extend the frame on
     */
    public static void extendFrameForCustomChrome( HWND hwnd )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || hwnd == null ) {
            return;
        }
        try {
            MARGINS margins = new MARGINS();
            margins.cxLeftWidth = 0;
            margins.cxRightWidth = 0;
            margins.cyTopHeight = 1;   // 1px is enough to keep DWM composing the frame + snap button
            margins.cyBottomHeight = 0;
            Dwmapi.INSTANCE.DwmExtendFrameIntoClientArea( hwnd, margins );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.windowChrome.extendFrameFailed",
                                                                 t.getMessage() ) );
        }
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
            Logger.logWarningSilent( LocalizationManager.format( "log.windowChrome.darkModeFailed", t.getMessage() ) );
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
     *
     * @param stage the Stage whose caption text color to set
     * @param color the JavaFX color to apply, or {@code null} for system default
     */
    public static void applyCaptionTextColor( Stage stage, javafx.scene.paint.Color color )
    {
        setColorRef( stage, DWMWA_TEXT_COLOR, color );
    }

    /**
     * Clears DWM's caption fill ({@link #COLOR_NONE}) so the title-bar strip composites like the
     * client area instead of being painted a flat colour. Use on the native (Mica) theme, whose
     * client area is transparent — a solid caption colour there shows as a band against the
     * backdrop. Win11 22H2+; older builds silently ignore.
     *
     * @param stage the Stage whose caption fill to clear
     */
    public static void clearCaptionColor( Stage stage )
    {
        setColorRefRaw( stage, DWMWA_CAPTION_COLOR, COLOR_NONE );
    }

    /**
     * Clears DWM's window border fill ({@link #COLOR_NONE}). Pairs with {@link #clearCaptionColor}
     * on the native theme so neither the caption nor the frame paints a flat colour.
     *
     * @param stage the Stage whose border fill to clear
     */
    public static void clearBorderColor( Stage stage )
    {
        setColorRefRaw( stage, DWMWA_BORDER_COLOR, COLOR_NONE );
    }

    /**
     * Shared helper — encodes a JavaFX Color as a Windows COLORREF and forwards to DWM.
     *
     * @param stage the Stage whose color attribute to set
     * @param dwmAttribute the DWM attribute identifier
     * @param color the JavaFX color to apply, or {@code null} for system default
     */
    private static void setColorRef( Stage stage, int dwmAttribute, javafx.scene.paint.Color color )
    {
        setColorRefRaw( stage, dwmAttribute,
                        ( color == null ) ? COLOR_DEFAULT : encodeColorRef( color ) );
    }

    /**
     * Shared helper — forwards a raw DWM COLORREF / sentinel (e.g. {@link #COLOR_NONE}) to DWM.
     *
     * @param stage the Stage whose color attribute to set
     * @param dwmAttribute the DWM attribute identifier
     * @param colorRef the raw COLORREF value or sentinel
     */
    private static void setColorRefRaw( Stage stage, int dwmAttribute, int colorRef )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || stage == null ) {
            return;
        }
        HWND hwnd = resolveHwnd( stage );
        if ( hwnd == null ) {
            return;
        }
        IntByReference value = new IntByReference( colorRef );
        try {
            Dwmapi.INSTANCE.DwmSetWindowAttribute( hwnd, dwmAttribute, value, 4 );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.windowChrome.colorFailed",
                                                                 dwmAttribute, t.getMessage() ) );
        }
    }

    /**
     * Encodes a JavaFX Color (sRGB) into Windows' COLORREF format: 0x00BBGGRR.
     *
     * @param color the JavaFX color to encode
     * @return the encoded COLORREF value
     */
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
     *
     * @param stage the Stage to repaint
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
            Logger.logWarningSilent( LocalizationManager.format( "log.windowChrome.redrawFailed", t.getMessage() ) );
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
     * @return {@code true} if DWM accepted the attribute (S_OK), {@code false} off Windows, on a
     *         null / unresolved handle, or when DWM rejected it (Win10 / Win11 before 22H2)
     */
    public static boolean applyBackdrop( Stage stage, int backdropType )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || stage == null ) {
            return false;
        }
        HWND hwnd = resolveHwnd( stage );
        if ( hwnd == null ) {
            return false;
        }
        IntByReference value = new IntByReference( backdropType );
        try {
            // DwmSetWindowAttribute returns an HRESULT: S_OK (0) when DWM accepted the backdrop, or a
            // failure code (E_INVALIDARG) on Windows builds that don't know DWMWA_SYSTEMBACKDROP_TYPE
            // — Windows 10 and Windows 11 before 22H2. Callers use this to distinguish "backdrop
            // applied" from "silently rejected": a transparent scene over a rejected backdrop
            // composites as pure black rather than showing Mica.
            int hr = Dwmapi.INSTANCE.DwmSetWindowAttribute(
                    hwnd, DWMWA_SYSTEMBACKDROP_TYPE, value, 4 );
            return hr == 0;
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.windowChrome.backdropFailed", t.getMessage() ) );
            return false;
        }
    }

    /** Cached result of the Mica-backdrop capability probe: {@code null} until the first probe with a
     *  realized HWND, then the OS-level answer (stable for the process lifetime). */
    private static volatile Boolean micaBackdropSupported = null;

    /**
     * Reports whether DWM will actually <b>render</b> a Mica system backdrop for this stage's window.
     * Two independent conditions have to hold, and both fail as a pure-black window when a scene
     * relies on transparency to reveal Mica:
     * <ul>
     *   <li><b>OS support.</b> Mica needs the {@code DWMWA_SYSTEMBACKDROP_TYPE} attribute (Windows 11
     *       22H2 / build 22621+); on Windows 10 and earlier Windows 11 builds DWM rejects it. Probed
     *       via the {@link #applyBackdrop} HRESULT and cached (this never changes for the process).</li>
     *   <li><b>Transparency effects on.</b> Even where the attribute is <i>accepted</i> (S_OK), DWM
     *       paints no Mica when Windows "Transparency effects" is off — user preference, the
     *       "reduce transparency" accessibility setting, or battery saver. This is the common reason a
     *       Mica-capable machine still shows black. Re-checked every call (it can be toggled at
     *       runtime), so it is intentionally <i>not</i> folded into the cached OS-support probe.</li>
     * </ul>
     * Callers use a {@code false} result to fall back to a solid background instead of transparent.
     *
     * <p>Returns {@code false} <i>without caching</i> the OS probe when the native window isn't
     * realized yet (no HWND), so a later call after the stage is shown can still detect support.
     * Always {@code false} off Windows.</p>
     *
     * @param stage the Stage whose window to probe
     * @return {@code true} if DWM will paint the Mica backdrop, otherwise {@code false}
     */
    public static boolean supportsMicaBackdrop( Stage stage )
    {
        if ( !SystemUtils.IS_OS_WINDOWS ) {
            return false;
        }
        // Transparency effects gate first — cheap, and it can change at runtime so it's never cached.
        if ( !transparencyEffectsEnabled() ) {
            return false;
        }
        Boolean cached = micaBackdropSupported;
        if ( cached != null ) {
            return cached;
        }
        if ( stage == null || resolveHwnd( stage ) == null ) {
            return false;   // window not realized yet — don't cache, let a later call re-probe
        }
        boolean ok = applyBackdrop( stage, BACKDROP_MICA );
        micaBackdropSupported = ok;
        return ok;
    }

    /**
     * Reads Windows' "Transparency effects" toggle (Settings → Personalization → Colors). Mica /
     * Acrylic only render when it's on; with it off DWM still accepts the backdrop attribute but paints
     * nothing, so a transparent scene composites as black. The setting lives at
     * {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\EnableTransparency}
     * ({@code 1} = on). Battery saver and the "reduce transparency" accessibility option flip this same
     * value, so this one read covers all three. Defaults to {@code true} when the value is absent
     * (its Windows default) or unreadable — never suppress Mica on a bad read.
     *
     * @return {@code true} if transparency effects are enabled (or indeterminate), {@code false} if
     *         explicitly disabled
     */
    private static boolean transparencyEffectsEnabled()
    {
        try {
            final String key = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
            if ( Advapi32Util.registryValueExists( WinReg.HKEY_CURRENT_USER, key, "EnableTransparency" ) ) {
                return Advapi32Util.registryGetIntValue( WinReg.HKEY_CURRENT_USER, key, "EnableTransparency" ) != 0;
            }
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.windowChrome.transparencyReadFailed",
                                                                 t.getMessage() ) );
        }
        return true;   // absent / unreadable → Windows default is on
    }

    /**
     * Translates a {@link Stage} to its native {@code HWND} via the Glass {@code Window}
     * registry. Title-match first (same approach {@link WindowsShellRefresh} uses), then
     * fall through to "the single Glass window in this process" when there's only one.
     *
     * <p>Package-private so sibling Windows-chrome managers (e.g.
     * {@link WindowsCustomChromeManager}) can reuse the one HWND-resolution path rather
     * than duplicating it.</p>
     *
     * @param stage the Stage to resolve
     * @return the native HWND of the Stage, or null if resolution fails
     */
    static HWND resolveHwnd( Stage stage )
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
            Logger.logWarningSilent( LocalizationManager.format( "log.windowChrome.resolveHwndFailed", t.getMessage() ) );
        }
        return null;
    }
}

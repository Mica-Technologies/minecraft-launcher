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
import com.pixelduke.window.MacThemeWindowManager;
import com.pixelduke.window.ThemeWindowManager;
import com.pixelduke.window.ThemeWindowManagerFactory;
import com.pixelduke.window.WindowUtils;
import com.sun.jna.NativeLong;
import de.jangassen.jfa.foundation.Foundation;
import de.jangassen.jfa.foundation.ID;
import javafx.event.EventHandler;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.apache.commons.lang3.SystemUtils;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * macOS-only thin wrapper over PixelDuke's FXThemes that installs an
 * {@code NSVisualEffectView} backdrop on a JavaFX stage — real macOS frosted-glass
 * vibrancy compositing the desktop wallpaper through the window, the macOS analogue
 * to Windows 11 Mica.
 *
 * <p>An earlier attempt to drive {@code NSVisualEffectView} directly via JFA's
 * pure-Java objc_msgSend bridge (reverted in commit cf0352e) failed because JavaFX
 * 25's Glass/Metal compositor on macOS doesn't participate in the
 * {@code blendingMode = behindWindow} compositing path — the vfx had the right
 * material/state/frame but the wallpaper never composited through. FXThemes solves
 * this by shipping a precompiled {@code libFXThemes.dylib} that does the
 * {@code addSubview:positioned:NSWindowBelow} insertion from native Obj-C using
 * Glass's own NSWindow pointer (obtained via reflection on Window.getPeer()'s
 * raw handle), which Glass cooperates with where it didn't cooperate with our
 * Java-side equivalents.</p>
 *
 * <p>Every call is guarded by {@code SystemUtils.IS_OS_MAC}. The class only
 * class-loads the FXThemes API when its methods are invoked; on non-macOS hosts
 * the imports above are present but the {@link ThemeWindowManagerFactory} returns
 * a platform-specific (non-Mac) manager, and we never down-cast to
 * {@code MacThemeWindowManager}. The factory's native lib load is gated by its
 * own OS check internally.</p>
 *
 * <p>Stage lifecycle: FXThemes obtains the NSWindow pointer via
 * {@code Window.getPeer().getRawHandle()}, which returns 0 before
 * {@code Stage.show()} is called. If applied that early we install a one-shot
 * {@code WINDOW_SHOWN} handler that retries once the window is realized — the
 * common path on cold start, since {@code forceThemeChange()} runs from
 * {@code setScene} before the first show.</p>
 */
public final class MacOsVibrancyManager
{
    /** Lazily-initialized FXThemes manager. {@link ThemeWindowManagerFactory#create}
     *  triggers the native lib load on first call, which we defer until we know
     *  we're on macOS AND we have a real apply call to make. */
    private static volatile MacThemeWindowManager macManager;

    /** Tracks the dark/light setting currently applied to each stage's vibrancy.
     *  Re-apply with the same value short-circuits to a no-op rather than paying the
     *  JNI hop AND FXThemes' rebuild-the-NSVisualEffectView path (every apply call
     *  removes + recreates the vfx subview). forceThemeChange() fires on every scene
     *  transition, so the launcher would otherwise rebuild vfx 5+ times during cold
     *  start. WeakHashMap so closed stages don't pin us; Boolean value (not just
     *  presence) so we still rebuild on a dark↔light flip. */
    private static final Map< Stage, Boolean > currentDark = new WeakHashMap<>();

    private MacOsVibrancyManager() { /* static-only */ }

    /**
     * Installs the vibrant frosted-glass backdrop on {@code stage}'s NSWindow.
     * No-op on non-macOS, when called with a null stage, or when FXThemes fails to
     * initialize. Safe to call repeatedly — re-apply with the same dark/light setting
     * is a fast no-op, and re-apply with a flipped setting flips the NSAppearance.
     *
     * @param stage the JavaFX stage hosting the window to make vibrant
     * @param dark  true → {@code NSAppearanceNameVibrantDark}, false →
     *              {@code NSAppearanceNameVibrantLight}
     */
    public static void apply( Stage stage, boolean dark )
    {
        if ( !SystemUtils.IS_OS_MAC || stage == null ) {
            return;
        }
        if ( !stage.isShowing() ) {
            // NSWindow not realized yet — defer until WINDOW_SHOWN. forceThemeChange()
            // during initial setScene runs before stage.show(), so this is the cold-start
            // path.
            EventHandler< WindowEvent > onShown = new EventHandler< WindowEvent >()
            {
                @Override
                public void handle( WindowEvent event )
                {
                    stage.removeEventHandler( WindowEvent.WINDOW_SHOWN, this );
                    apply( stage, dark );
                }
            };
            stage.addEventHandler( WindowEvent.WINDOW_SHOWN, onShown );
            return;
        }
        // Fast-path: already vibrant with the same dark/light setting, nothing to do.
        // FXThemes' native side rebuilds the NSVisualEffectView on every setAppearance
        // call (remove-old + create-new), which churns the Metal compositor and can
        // contribute to visible artifacts on subsequent renders. Skipping the redundant
        // call avoids that.
        Boolean priorDark = currentDark.get( stage );
        if ( priorDark != null && priorDark == dark ) {
            return;
        }
        try {
            MacThemeWindowManager mgr = manager();
            if ( mgr == null ) {
                return;
            }
            // setDarkModeForWindowFrame uses NSAppearanceNameDarkAqua/Aqua (the modern
            // Big Sur+ standard window appearances) and installs the NSVisualEffectView
            // either way. We previously used setWindowFrameAppearance with
            // NSAppearanceNameVibrantDark/VibrantLight, but those Vibrant appearances are
            // intended for vibrancy-material views (sidebar, menu, popover) and don't
            // reliably theme a window frame on modern macOS — the title bar would stay
            // light even in vibrant-dark mode.
            mgr.setDarkModeForWindowFrame( stage, dark );

            // FXThemes' native code only calls [contentView setAppearance:...] which
            // doesn't propagate to the title bar — the title bar uses the *NSWindow's*
            // own appearance, not the content view's. Set it directly via JFA so the
            // title bar follows the chosen dark/light theme to match the macOS system.
            applyWindowAppearance( stage, dark );

            currentDark.put( stage, dark );
            Logger.logDebug( "MacOsVibrancy: applied " + ( dark ? "DarkAqua" : "Aqua" )
                                     + " (vibrancy + title-bar) to \"" + stage.getTitle() + "\"" );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "MacOsVibrancy: apply failed — "
                                             + t.getClass().getSimpleName() + ": "
                                             + t.getMessage() );
        }
    }

    /**
     * Flips the window's NSAppearance back to non-vibrant Aqua / DarkAqua, removing
     * the NSVisualEffectView. Called when switching the active theme away from Native
     * on macOS so the next theme's solid bg paints over an opaque-ish window again.
     * Idempotent; no-op if vibrancy was never applied.
     */
    public static void clear( Stage stage )
    {
        if ( !SystemUtils.IS_OS_MAC || stage == null ) {
            return;
        }
        if ( currentDark.remove( stage ) == null ) {
            return;
        }
        if ( !stage.isShowing() ) {
            return;
        }
        try {
            MacThemeWindowManager mgr = manager();
            if ( mgr == null ) {
                return;
            }
            // setDarkModeForWindowFrame routes through the same setAppearanceByName
            // bridge but with the non-vibrant appearance names; FXThemes' native code
            // removes any prior NSVisualEffectView when the appearance flips.
            mgr.setDarkModeForWindowFrame( stage, isOsDark() );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "MacOsVibrancy: clear failed — " + t.getMessage() );
        }
    }

    /** Finalizes the NSWindow for vibrancy + dark/light theming. Three things FXThemes
     *  doesn't do for us, all needed on the first vibrancy install so the very first
     *  frame composites correctly:
     *
     *  <ol>
     *    <li>[NSWindow setAppearance:] — FXThemes only writes appearance to
     *        contentView, not the window. The title bar's chrome (close/min/max
     *        buttons, text color, bg) comes from the NSWindow's own appearance, so
     *        without this the title bar stays Aqua-light even when the contentView
     *        is DarkAqua.</li>
     *    <li>[NSWindow setOpaque:NO] — required for the OS-level compositor to honor
     *        transparent pixels from the JFX layer; otherwise the window paints an
     *        opaque bg behind JFX and masks the NSVisualEffectView. FXThemes' native
     *        code doesn't set this, which is why on first launch the bg was
     *        rendering pure black until a theme-switch round-trip forced something
     *        else to toggle opacity.</li>
     *    <li>[NSWindow setBackgroundColor:clearColor] — pairs with setOpaque:NO so
     *        the window's bg fill is also transparent.</li>
     *  </ol>
     *
     *  Uses FXThemes' WindowUtils to grab the raw NSWindow pointer (reflection on
     *  Window.getPeer().getRawHandle()), then drives the AppKit calls via JFA's
     *  Foundation bridge. */
    private static void applyWindowAppearance( Stage stage, boolean dark )
    {
        try {
            NativeLong handle = WindowUtils.getNativeHandleOfStageAsNativeLong( stage );
            if ( handle == null || handle.longValue() == 0 ) {
                return;
            }
            ID nsWindow = new ID( handle.longValue() );

            String name = dark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua";
            ID appearance = Foundation.invoke( "NSAppearance", "appearanceNamed:",
                                               Foundation.nsString( name ) );
            if ( !Foundation.isNil( appearance ) ) {
                Foundation.invoke( nsWindow, "setAppearance:", appearance );
            }

            // The window-level transparency setup. Required for vibrancy to composite
            // through the JFX layer on the very first frame — without these, the
            // window's default opaque bg masks the NSVisualEffectView and the launcher
            // appears as solid black on launch until a theme-switch forces something
            // to re-toggle opacity.
            Foundation.invoke( nsWindow, "setOpaque:", 0 );
            ID clearColor = Foundation.invoke( "NSColor", "clearColor" );
            if ( !Foundation.isNil( clearColor ) ) {
                Foundation.invoke( nsWindow, "setBackgroundColor:", clearColor );
            }
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "MacOsVibrancy: NSWindow appearance set failed — "
                                             + t.getMessage() );
        }
    }

    /** Lazily resolves the FXThemes Mac manager. Returns null if the factory hands
     *  back something other than a Mac manager (we should never hit this since the
     *  caller already gated on {@code IS_OS_MAC}, but the factory's contract is
     *  loose enough that defending against it is cheap). */
    private static MacThemeWindowManager manager()
    {
        MacThemeWindowManager local = macManager;
        if ( local != null ) {
            return local;
        }
        synchronized ( MacOsVibrancyManager.class ) {
            if ( macManager == null ) {
                ThemeWindowManager twm = ThemeWindowManagerFactory.create();
                if ( twm instanceof MacThemeWindowManager ) {
                    macManager = ( MacThemeWindowManager ) twm;
                }
                else {
                    Logger.logWarningSilent(
                            "MacOsVibrancy: FXThemes returned non-Mac manager on macOS: "
                                    + ( twm == null ? "null" : twm.getClass().getName() ) );
                }
            }
            return macManager;
        }
    }

    /** Best-effort OS dark/light read for the clear() path's appearance choice.
     *  Reads JFX's own platform pref since we don't carry the OS-detector reference
     *  here. Falls back to dark on any failure — matches the launcher's default. */
    private static boolean isOsDark()
    {
        try {
            return com.jthemedetecor.OsThemeDetector.getDetector().isDark();
        }
        catch ( Throwable t ) {
            return true;
        }
    }
}

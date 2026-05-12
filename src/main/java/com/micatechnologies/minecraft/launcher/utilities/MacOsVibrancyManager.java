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
import de.jangassen.jfa.foundation.Foundation;
import de.jangassen.jfa.foundation.ID;
import javafx.event.EventHandler;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.apache.commons.lang3.SystemUtils;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * macOS-only NSVisualEffectView integration. Provides the closest analogue we can to
 * Windows 11's Mica backdrop on the Native theme: real wallpaper-tinted vibrancy behind
 * the JavaFX scene, via AppKit's {@code NSVisualEffectView} with
 * {@code blendingMode = behindWindow}.
 *
 * <p><b>Approach: contentView replacement.</b> We don't add the vibrancy view as a
 * subview of JavaFX's contentView — JavaFX uses frame-based layout (no auto-layout on
 * the contentView), so a child added with NSLayoutConstraints to its edges stays at
 * (0,0,0,0) because the constraints never resolve in a non-AL tree. Instead we swap
 * the window's contentView for a new NSVisualEffectView and re-parent JavaFX's old
 * NSView as a subview of it. {@code [NSWindow setContentView:]} automatically sizes
 * the new contentView to fill the window's content rect, so no NSRect struct passing
 * is needed (JFA 1.2.0 doesn't ship a typed CGRect/NSRect marshaller). The JFX view
 * keeps its prior frame and we set its {@code autoresizingMask} so it resizes with the
 * vibrancy contentView. Glass tracks its NSView by pointer, not by parent relationship,
 * so input events / pasteboard / fullscreen continue to work after the reparent.</p>
 *
 * <p>Driven via JFA's raw Foundation/objc_msgSend bridge — JFA 1.2.0 doesn't ship a
 * typed wrapper for NSVisualEffectView, so we send selectors directly. The class is
 * isolated specifically so it only class-loads when invoked: imports here pull in
 * {@link Foundation}, which immediately tries to dlopen the Cocoa frameworks via JNA
 * — fine on macOS, would obviously not be fine on Windows or Linux. Every public
 * method also guards with {@code SystemUtils.IS_OS_MAC} as a belt-and-suspenders.</p>
 *
 * <p>For the vibrancy to actually show through, the window must be
 * {@code opaque=NO} with {@code clearColor} background and the JavaFX scene's fill set
 * to {@code TRANSPARENT}. The caller handles the scene fill.</p>
 *
 * <p>Stage lifecycle: the underlying NSWindow doesn't exist until {@code Stage.show()}.
 * If {@link #apply(Stage, boolean)} is called before that — e.g. during the first
 * {@code forceThemeChange()} which fires before the initial show — we install a
 * one-shot {@code WINDOW_SHOWN} handler that retries once the window is realized.</p>
 *
 * <p>Re-apply on the same stage detects an existing wrap and just updates the vibrancy
 * properties / appearance in place, rather than re-wrapping (which would build a stack
 * of nested NSVisualEffectViews on every scene change).</p>
 */
public final class MacOsVibrancyManager
{
    /** {@code NSVisualEffectMaterialWindowBackground}. macOS 10.14+. The canonical
     *  "window background vibrancy" material — what AppKit uses for first-party apps'
     *  translucent windows (Finder sidebar background, Notes, etc.) when combined with
     *  blending mode {@code behindWindow}. Effectively the macOS equivalent of Windows
     *  11 Mica: a wallpaper-tinted layer that desaturates when the window is inactive.
     *
     *  <p>Note on the constant value: older docs scattered around the web cite 9 for
     *  the under-window-background material, but on modern macOS headers
     *  {@code NSVisualEffectMaterialWindowBackground} is 12 and
     *  {@code NSVisualEffectMaterialUnderWindowBackground} is 21. Value 9 maps to no
     *  recognized material on 10.14+ and silently renders as nothing — which is the
     *  bug we hit on the first attempt.</p> */
    private static final int MATERIAL_WINDOW_BG = 12;

    /** {@code NSVisualEffectBlendingModeBehindWindow}. Composites the desktop wallpaper
     *  through the window, the macOS equivalent of Windows 11 Mica. */
    private static final int BLENDING_BEHIND_WINDOW = 0;

    /** {@code NSVisualEffectStateActive}. Always render vibrancy at full strength,
     *  ignoring whether the window is the active app. The "FollowsWindow" state
     *  ({@code = 0}) was the original choice but evaluates to "inactive" when we apply
     *  before the launcher has taken focus — which is the cold-start path where we
     *  install the vibrancy from forceThemeChange. Active gives consistent visuals
     *  regardless of window focus at apply time. */
    private static final int STATE_ACTIVE = 1;

    /** {@code NSViewWidthSizable | NSViewHeightSizable}. autoresizingMask bits that
     *  make a subview resize to fill its superview in both dimensions. */
    private static final int AUTORESIZE_FILL = 2 | 16;

    /** Per-Stage state recorded by {@link #apply} so {@link #clear} can restore the
     *  window to its pre-vibrancy contentView, and so re-apply on the same stage knows
     *  not to re-wrap. WeakHashMap so a closed/discarded Stage doesn't pin us. */
    private static final Map< Stage, Applied > applied = new WeakHashMap<>();

    private MacOsVibrancyManager() { /* static-only */ }

    /**
     * Installs the NSVisualEffectView on {@code stage}'s NSWindow with a dark or light
     * appearance. No-op on non-macOS, when called with a null stage, or when the JFA
     * bridge fails. Safe to call repeatedly on the same stage — re-apply just updates
     * the vibrancy properties on the existing wrap.
     *
     * @param stage the JavaFX stage hosting the window to make vibrant
     * @param dark  true → NSAppearanceNameDarkAqua, false → NSAppearanceNameAqua
     */
    public static void apply( Stage stage, boolean dark )
    {
        if ( !SystemUtils.IS_OS_MAC || stage == null ) {
            return;
        }
        if ( !stage.isShowing() ) {
            // NSWindow doesn't exist yet — retry once it's realized. The first
            // forceThemeChange() during scene setup runs before stage.show(), so this
            // path is the common one on cold start.
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

        try {
            ID nsWindow = findNSWindowForStage( stage );
            if ( nsWindow == null || Foundation.isNil( nsWindow ) ) {
                Logger.logWarningSilent( "MacOsVibrancy: couldn't find NSWindow for stage \""
                                                 + stage.getTitle() + "\"" );
                return;
            }

            // Window-level transparency. Without these, the NSVisualEffectView paints into
            // an opaque window and the desktop never composites through.
            Foundation.invoke( nsWindow, "setOpaque:", 0 );
            ID clearColor = Foundation.invoke( "NSColor", "clearColor" );
            Foundation.invoke( nsWindow, "setBackgroundColor:", clearColor );

            // NSAppearance drives the vibrancy tint (and the title bar text color).
            // DarkAqua / Aqua match the OS dark/light convention the OsThemeDetector
            // gave us.
            String appearanceName = dark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua";
            ID appearance = Foundation.invoke( "NSAppearance", "appearanceNamed:",
                                               Foundation.nsString( appearanceName ) );
            if ( !Foundation.isNil( appearance ) ) {
                Foundation.invoke( nsWindow, "setAppearance:", appearance );
            }

            // If we've already wrapped this stage, just refresh the existing vfx's
            // properties — don't build another wrapper layer on top of the prior one.
            Applied prior = applied.get( stage );
            if ( prior != null && !Foundation.isNil( prior.vfx ) ) {
                configureVfx( prior.vfx );
                Logger.logDebug( "MacOsVibrancy: refreshed existing NSVisualEffectView (appearance="
                                         + appearanceName + ")" );
                return;
            }

            // Fresh wrap: NSVisualEffectView becomes the new contentView, JFX's old
            // contentView becomes its subview.
            ID jfxContent = Foundation.invoke( nsWindow, "contentView" );
            if ( jfxContent == null || Foundation.isNil( jfxContent ) ) {
                Logger.logWarningSilent( "MacOsVibrancy: NSWindow has no contentView yet" );
                return;
            }

            ID vfxClass = Foundation.getObjcClass( "NSVisualEffectView" );
            ID vfx = Foundation.invoke( Foundation.invoke( vfxClass, "alloc" ), "init" );
            configureVfx( vfx );

            // Capture jfxContent's current bounds as an NSValue BEFORE we change the
            // contentView. We'll use this NSValue via KVC to set vfx's frame, avoiding
            // the need to pass an NSRect struct-by-value through objc_msgSend (which
            // JFA 1.2.0 doesn't natively support — its Foundation.invoke marshalls
            // scalars and IDs, not by-value structs). NSValue is just an id, so KVC
            // round-trips the NSRect for us through Cocoa's runtime.
            ID boundsKey = Foundation.nsString( "bounds" );
            ID frameKey = Foundation.nsString( "frame" );
            ID priorBounds = Foundation.invoke( jfxContent, "valueForKey:", boundsKey );

            Foundation.invoke( nsWindow, "setContentView:", vfx );
            Foundation.invoke( vfx, "setAutoresizesSubviews:", 1 );

            // Belt-and-suspenders: explicitly size vfx to the prior content bounds.
            // [NSWindow setContentView:] is documented to auto-resize the new view, but
            // Glass's NSWindow subclass may not honor that. If vfx is sitting at 0x0,
            // the material has nowhere to render and the window reads as fully
            // transparent — exactly the symptom we keep hitting. KVC handles the NSRect
            // packing internally so we don't need a struct-passing path.
            if ( priorBounds != null && !Foundation.isNil( priorBounds ) ) {
                Foundation.invoke( vfx, "setValue:forKey:", priorBounds, frameKey );
            }

            // Re-parent jfxContent under vfx. Its frame still matches the old content
            // rect, which matches vfx's new size, so it fits without us touching it.
            Foundation.invoke( vfx, "addSubview:", jfxContent );
            Foundation.invoke( jfxContent, "setAutoresizingMask:", AUTORESIZE_FILL );

            // Make jfxContent's layer non-opaque so the vibrancy shows through it.
            // Glass requests a layer-backed view; we toggle opaque on the layer so the
            // transparent scene-fill pixels we render don't get composited over an
            // opaque layer background.
            Foundation.invoke( jfxContent, "setWantsLayer:", 1 );
            ID jfxLayer = Foundation.invoke( jfxContent, "layer" );
            if ( !Foundation.isNil( jfxLayer ) ) {
                Foundation.invoke( jfxLayer, "setOpaque:", 0 );
            }

            applied.put( stage, new Applied( jfxContent, vfx ) );

            // Diagnostic: confirm the swap stuck AND inspect the vfx's actual state
            // post-config. If Glass intercepted setContentView: and put the JFX view
            // back, contentView's class will be something Glass-owned. If our setters
            // didn't take (material=0, state=2, or frame=0x0), the readback shows it.
            ID nowContent = Foundation.invoke( nsWindow, "contentView" );
            Logger.logDebug( "MacOsVibrancy: applied NSVisualEffectView (appearance="
                                     + appearanceName + ", configured material="
                                     + MATERIAL_WINDOW_BG + ") to \"" + stage.getTitle()
                                     + "\"; contentView class=" + classNameOf( nowContent )
                                     + " vfx state: " + describeVfx( vfx ) );
        }
        catch ( Throwable t ) {
            // JFA can throw on missing classes / selectors (older macOS, future API removal).
            // The Native theme already has a graceful "no backdrop" fallback look in
            // ui-tokens-native.css — we just lose the vibrancy, not the launcher.
            Logger.logWarningSilent( "MacOsVibrancy: apply failed — " + t.getClass().getSimpleName()
                                             + ": " + t.getMessage() );
        }
    }

    /**
     * Restores the window to its pre-vibrancy contentView and resets opacity. Called when
     * switching the active theme away from Native on macOS so the next theme's solid bg
     * paints over an opaque window again. Idempotent.
     */
    public static void clear( Stage stage )
    {
        if ( !SystemUtils.IS_OS_MAC || stage == null ) {
            return;
        }
        try {
            Applied prior = applied.remove( stage );
            if ( prior == null ) {
                return;
            }
            ID nsWindow = findNSWindowForStage( stage );
            if ( nsWindow == null || Foundation.isNil( nsWindow ) ) {
                return;
            }

            // Put JFX's original NSView back as the window contentView. setContentView:
            // implicitly removes vfx from the window and re-parents jfxContent. vfx's
            // jfxContent subview reference is dropped at the same time so it's fine to
            // pass jfxContent directly.
            if ( !Foundation.isNil( prior.jfxContent ) ) {
                Foundation.invoke( nsWindow, "setContentView:", prior.jfxContent );
            }
            Foundation.invoke( nsWindow, "setOpaque:", 1 );
            // Don't reset backgroundColor — the applyTheme() inline rootPane bg will
            // paint over it momentarily, and there's no clean signal for what color
            // the window had before vibrancy took over.
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "MacOsVibrancy: clear failed — " + t.getMessage() );
        }
    }

    /** Sets NSVisualEffectView properties: material, blending mode, state, and
     *  layer-backing. Vibrancy materials only render through a backing CALayer; modern
     *  macOS auto-layer-backs NSVisualEffectView, but we set it explicitly because
     *  Glass-owned content trees have been observed not to inherit the auto-layer
     *  behavior in some JFX builds. */
    private static void configureVfx( ID vfx )
    {
        Foundation.invoke( vfx, "setWantsLayer:", 1 );
        Foundation.invoke( vfx, "setMaterial:", MATERIAL_WINDOW_BG );
        Foundation.invoke( vfx, "setBlendingMode:", BLENDING_BEHIND_WINDOW );
        Foundation.invoke( vfx, "setState:", STATE_ACTIVE );
    }

    /** Reads back the vfx's actual material / state / blending / frame / visibility from
     *  AppKit so we can verify our setters took and the view is attached + visible. Pulls
     *  the frame via KVC so we don't need to receive an NSRect by-value (NSValue's
     *  description renders the rect for free). */
    private static String describeVfx( ID vfx )
    {
        try {
            long mat = Foundation.invoke( vfx, "material" ).longValue();
            long blend = Foundation.invoke( vfx, "blendingMode" ).longValue();
            long state = Foundation.invoke( vfx, "state" ).longValue();
            boolean hidden = Foundation.invoke( vfx, "isHidden" ).booleanValue();

            ID frameVal = Foundation.invoke( vfx, "valueForKey:", Foundation.nsString( "frame" ) );
            String frameStr = ( frameVal == null || Foundation.isNil( frameVal ) ) ? "<nil>"
                    : Foundation.toStringViaUTF8( Foundation.invoke( frameVal, "description" ) );

            ID superview = Foundation.invoke( vfx, "superview" );
            String superCls = classNameOf( superview );

            ID window = Foundation.invoke( vfx, "window" );
            String windowCls = classNameOf( window );

            return "material=" + mat + " blending=" + blend + " state=" + state
                    + " hidden=" + hidden + " frame=" + frameStr
                    + " superview=" + superCls + " window=" + windowCls;
        }
        catch ( Throwable t ) {
            return "<describe failed: " + t.getMessage() + ">";
        }
    }

    /** Returns the NSObject's class name as a Java String. Used for diagnostics — when
     *  the vibrancy isn't visible we want to confirm whether the swap took (contentView
     *  is an NSVisualEffectView) or whether Glass undid it (contentView is back to
     *  something Glass-owned). */
    private static String classNameOf( ID obj )
    {
        if ( obj == null || Foundation.isNil( obj ) ) {
            return "<nil>";
        }
        try {
            ID cls = Foundation.invoke( obj, "class" );
            return Foundation.stringFromClass( cls );
        }
        catch ( Throwable t ) {
            return "<unknown>";
        }
    }

    /** Walks {@code [NSApp windows]} looking for an NSWindow whose title matches the
     *  Stage's. Works because each JavaFX Stage corresponds to one NSWindow with a
     *  title that's set by {@code stage.setTitle(...)} just before the theme apply.
     *  Title-based lookup avoids reaching into Glass internals (which require unstable
     *  --add-exports/--add-opens flags and break across JFX versions). */
    private static ID findNSWindowForStage( Stage stage )
    {
        String title = stage.getTitle();
        if ( title == null ) {
            return null;
        }
        ID nsApp = Foundation.invoke( "NSApplication", "sharedApplication" );
        ID windows = Foundation.invoke( nsApp, "windows" );
        long count = Foundation.invoke( windows, "count" ).longValue();
        for ( long i = 0; i < count; i++ ) {
            ID window = Foundation.invoke( windows, "objectAtIndex:", i );
            ID titleId = Foundation.invoke( window, "title" );
            if ( Foundation.isNil( titleId ) ) {
                continue;
            }
            String t = Foundation.toStringViaUTF8( titleId );
            if ( title.equals( t ) ) {
                return window;
            }
        }
        return null;
    }

    /** Per-stage record of the pre-vibrancy contentView (so {@link #clear} can put it
     *  back) and the vibrancy wrapper view (so re-apply on the same stage detects the
     *  existing wrap and refreshes properties instead of double-wrapping). */
    private static final class Applied
    {
        final ID jfxContent;
        final ID vfx;

        Applied( ID jfxContent, ID vfx )
        {
            this.jfxContent = jfxContent;
            this.vfx = vfx;
        }
    }
}

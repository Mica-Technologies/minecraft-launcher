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
import com.pixelduke.window.WindowUtils;
import com.sun.jna.Memory;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import de.jangassen.jfa.foundation.Foundation;
import de.jangassen.jfa.foundation.ID;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.apache.commons.lang3.SystemUtils;

import java.util.Set;

/**
 * macOS-only "hidden inset" title bar — the JavaFX analogue of Electron's
 * {@code titleBarStyle: 'hiddenInset'}. Makes the native title bar transparent and
 * lets the JavaFX content fill the full window height, leaving the standard traffic-light
 * close/minimize/zoom buttons floating over the top-left of the content.
 *
 * <p>The native side is three {@code NSWindow} flags, set on the live window pointer
 * (obtained the same way {@link MacOsVibrancyManager} does — FXThemes'
 * {@link WindowUtils#getNativeHandleOfStageAsNativeLong} reflects out Glass's NSWindow):
 * <ul>
 *   <li>{@code titlebarAppearsTransparent = YES} — the title-bar chrome (its background
 *       fill + separator) goes invisible; the title text and traffic lights remain.</li>
 *   <li>{@code styleMask |= NSWindowStyleMaskFullSizeContentView} — the content view is
 *       resized to span the title-bar region, so the JavaFX scene paints under the
 *       (now invisible) title bar instead of starting below it.</li>
 * </ul>
 * The window's <em>title text</em> is deliberately left visible — the launcher relies on
 * it (centered, over the navbar's empty middle) instead of the in-window screen-name
 * label, which {@link #hideRedundantBranding} strips on macOS so it doesn't collide with
 * the traffic lights.</p>
 *
 * <p>JFA marshalling: JNA maps a Java {@code Boolean} to the BOOL register value and a
 * Java {@code long} to {@code NSUInteger}, so {@code Foundation.invoke} carries the
 * boolean flag and the OR'd style mask through without any explicit type coercion. The
 * mask read-back ({@code invoke(window, "styleMask").longValue()}) preserves whatever
 * bits {@code StageStyle.UNIFIED} already set, and we only add the full-size bit.</p>
 *
 * <p>Every entry point is gated on {@code IS_OS_MAC} and is a no-op elsewhere. The
 * native call is deferred via a one-shot {@code WINDOW_SHOWN} handler when the stage
 * isn't realized yet (cold start sets the scene before {@code stage.show()}), mirroring
 * {@link MacOsVibrancyManager}'s pattern.</p>
 *
 * @author Mica Technologies
 * @since 2026.2
 */
public final class MacOsTitleBarManager
{
    /** {@code NSWindowStyleMaskFullSizeContentView} — {@code 1 << 15} per AppKit. OR'd
     *  into the window's existing style mask so the content view spans the title bar. */
    private static final long NS_WINDOW_STYLE_MASK_FULL_SIZE_CONTENT_VIEW = 1L << 15;

    /** {@code NSLayoutAttributeLeft} — places the title-bar accessory just right of the
     *  traffic lights, over the (hidden) brand-logo gap rather than the navbar buttons. */
    private static final long NS_LAYOUT_ATTRIBUTE_LEFT = 1L;

    private MacOsTitleBarManager() { /* static-only utility */ }

    /**
     * Applies the hidden-inset title bar to {@code stage}'s NSWindow. No-op on non-macOS
     * or a null stage. Idempotent — the style-mask OR and the transparency flag can be
     * re-set harmlessly, so this is safe to call on every show.
     *
     * @param stage the JavaFX stage whose native window should adopt the inset title bar
     *
     * @since 2026.2
     */
    public static void applyHiddenInset( Stage stage )
    {
        if ( !SystemUtils.IS_OS_MAC || stage == null ) {
            return;
        }
        if ( !stage.isShowing() ) {
            // NSWindow not realized yet — getNativeHandle returns 0 before show(). Defer to
            // WINDOW_SHOWN, then bounce through runLater so Cocoa finishes wiring the peer
            // before we touch its style mask (same ordering MacOsVibrancyManager relies on).
            EventHandler< WindowEvent > onShown = new EventHandler< WindowEvent >()
            {
                @Override
                public void handle( WindowEvent event )
                {
                    stage.removeEventHandler( WindowEvent.WINDOW_SHOWN, this );
                    Platform.runLater( () -> applyHiddenInset( stage ) );
                }
            };
            stage.addEventHandler( WindowEvent.WINDOW_SHOWN, onShown );
            return;
        }
        try {
            NativeLong handle = WindowUtils.getNativeHandleOfStageAsNativeLong( stage );
            if ( handle == null || handle.longValue() == 0 ) {
                return;
            }
            ID nsWindow = new ID( handle.longValue() );

            // titlebarAppearsTransparent = YES — chrome fill/separator vanish, traffic
            // lights + title text stay.
            Foundation.invoke( nsWindow, "setTitlebarAppearsTransparent:", true );

            // styleMask |= NSWindowStyleMaskFullSizeContentView — content view grows up
            // under the title bar. Read-modify-write so UNIFIED's existing bits survive.
            long styleMask = Foundation.invoke( nsWindow, "styleMask" ).longValue();
            Foundation.invoke( nsWindow, "setStyleMask:",
                               styleMask | NS_WINDOW_STYLE_MASK_FULL_SIZE_CONTENT_VIEW );

            Logger.logDebug( "MacOsTitleBar: hidden-inset applied to \"" + stage.getTitle() + "\"" );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "MacOsTitleBar: applyHiddenInset failed — "
                                             + t.getClass().getSimpleName() + ": " + t.getMessage() );
        }
    }

    /**
     * Grows {@code stage}'s native title bar to {@code bandHeightPx} so the traffic-light
     * close/minimize/zoom controls (and the window title) sit <em>vertically centered</em>
     * within the launcher's top navbar band instead of hugging its top edge. No-op off
     * macOS, on a null stage, or before the NSWindow peer is realized.
     *
     * <p><b>How.</b> Two steps. First, best-effort, attach a {@code bandHeightPx}-tall
     * {@code NSTitlebarAccessoryViewController} so AppKit grows the bar and re-centers the lights
     * itself — but JavaFX's Glass {@code NSWindow} has been observed not to honor titlebar
     * accessories (the bar stays standard height), so we don't rely on it. Second, do the actual
     * centering by repositioning the three standard window buttons: read each button's frame and
     * its superview's frame via {@code NSInvocation} (a struct return the generic
     * {@code Foundation.invoke} can't capture), then shift all three by one delta so their centers
     * sit {@code bandHeightPx/2} below the window's top edge. If the bar <em>did</em> grow, the
     * buttons are already centered and the delta is ~0.</p>
     *
     * <p><b>Why not an {@code NSToolbar}.</b> A unified toolbar grows the bar but lays its own
     * view across the whole band and eats the clicks there, forcing the interactive controls to be
     * native toolbar items — whose lazily built, autoreleased images did not survive a window
     * hide/show (returning from a game), leaving blank buttons. Moving the buttons leaves the
     * launcher's navbar controls as ordinary JavaFX nodes that can't go blank.</p>
     *
     * <p><b>Safety + idempotence.</b> The button move is skipped (and logged) when the buttons'
     * superview is shorter than the band, since moving a button outside its superview clips it and
     * kills its clicks. Any prior accessory is removed first, so re-applying on every
     * {@code WINDOW_SHOWN} (Glass can rebuild the NSWindow peer across hide/show) never compounds
     * anything; the button move re-reads live geometry each time, so it's self-correcting.</p>
     *
     * @param stage        the stage whose NSWindow traffic lights should be centered
     * @param bandHeightPx the navbar band height in points to center the lights within
     *
     * @since 2026.6
     */
    public static void applyCenteredTrafficLights( Stage stage, double bandHeightPx )
    {
        if ( !SystemUtils.IS_OS_MAC || stage == null || !stage.isShowing() ) {
            return;
        }
        try {
            NativeLong handle = WindowUtils.getNativeHandleOfStageAsNativeLong( stage );
            if ( handle == null || handle.longValue() == 0 ) {
                return;
            }
            ID nsWindow = new ID( handle.longValue() );

            // 1) Try the clean route: grow the title bar with an accessory view so AppKit
            //    re-centers the lights itself. JavaFX's Glass NSWindow has been observed NOT to
            //    honor this (the bar stays standard height), so it's best-effort — step 2 does
            //    the actual centering and is a no-op when the bar already grew. Drop any prior
            //    accessory first so re-applying on each show can't compound the height.
            removeOurTitlebarAccessories( nsWindow );
            ID view = Foundation.invoke( Foundation.invoke( "NSView", "alloc" ), "init" );
            Foundation.invoke( view, "setFrameSize:", 1.0, bandHeightPx );
            ID vc = Foundation.invoke(
                    Foundation.invoke( "NSTitlebarAccessoryViewController", "alloc" ), "init" );
            Foundation.invoke( vc, "setView:", view );
            Foundation.invoke( view, "autorelease" );
            Foundation.invoke( vc, "setLayoutAttribute:", NS_LAYOUT_ATTRIBUTE_LEFT );
            Foundation.invoke( nsWindow, "addTitlebarAccessoryViewController:", vc );
            Foundation.invoke( vc, "autorelease" );

            // 2) Reposition the traffic lights directly to the band's vertical center. The
            //    buttons live in a superview (the title-bar container); we read its frame +
            //    each button's frame via NSInvocation (struct returns the generic invoke can't
            //    capture), then shift all three by the same delta so their centers land
            //    bandHeightPx/2 below the window top. Cocoa coords are bottom-left-origin, so
            //    the window top edge is at y = superviewHeight and "down" means a smaller y.
            centerTrafficLightButtons( nsWindow, bandHeightPx );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "MacOsTitleBar: applyCenteredTrafficLights failed — "
                                             + t.getClass().getSimpleName() + ": " + t.getMessage() );
        }
    }

    /** Standard window button indices: close = 0, miniaturize = 1, zoom = 2. */
    private static final long[] TRAFFIC_LIGHT_BUTTONS = { 0L, 1L, 2L };

    /**
     * Shifts the three traffic-light buttons so their vertical centers sit {@code bandHeightPx/2}
     * below the window's top edge. Reads geometry via {@link #readRect}; skips (and logs) when the
     * buttons' superview is shorter than the band — moving a button outside its superview's bounds
     * would clip it and kill its clicks, which is worse than off-center lights.
     */
    private static void centerTrafficLightButtons( ID nsWindow, double bandHeightPx )
    {
        ID closeBtn = Foundation.invoke( nsWindow, "standardWindowButton:", TRAFFIC_LIGHT_BUTTONS[ 0 ] );
        if ( Foundation.isNil( closeBtn ) ) {
            Logger.logDebug( "MacOsTitleBar: no close button to center" );
            return;
        }
        ID superview = Foundation.invoke( closeBtn, "superview" );
        double[] svRect = readRect( superview, "frame" );
        double[] cRect = readRect( closeBtn, "frame" );
        if ( svRect == null || cRect == null ) {
            Logger.logDebug( "MacOsTitleBar: could not read traffic-light geometry" );
            return;
        }
        String svClass = Foundation.toStringViaUTF8( Foundation.invoke( superview, "className" ) );
        double svHeight = svRect[ 3 ];
        double btnHeight = cRect[ 3 ];
        Logger.logDebug( "MacOsTitleBar: lights superview=" + svClass + " h=" + svHeight
                                 + " closeBtn y=" + cRect[ 1 ] + " h=" + btnHeight );

        if ( svHeight < bandHeightPx ) {
            // Title-bar container is shorter than the band (the accessory didn't grow it). Moving
            // the buttons down would push them past the container's bottom edge and clip them.
            Logger.logDebug( "MacOsTitleBar: superview shorter than band (" + svHeight + " < "
                                     + bandHeightPx + "); leaving lights at default position" );
            return;
        }

        // Window top edge is at y = svHeight; target the band center bandHeightPx/2 below it.
        double targetCloseY = svHeight - ( bandHeightPx / 2.0 ) - ( btnHeight / 2.0 );
        double delta = targetCloseY - cRect[ 1 ];
        for ( long index : TRAFFIC_LIGHT_BUTTONS ) {
            ID btn = Foundation.invoke( nsWindow, "standardWindowButton:", index );
            if ( Foundation.isNil( btn ) ) {
                continue;
            }
            double[] r = readRect( btn, "frame" );
            if ( r == null ) {
                continue;
            }
            // setFrameOrigin: takes an NSPoint (two CGFloats) — same FP-register routing as
            // setFrameSize:, so two doubles marshal correctly with no struct.
            Foundation.invoke( btn, "setFrameOrigin:", r[ 0 ], r[ 1 ] + delta );
        }
        Logger.logDebug( "MacOsTitleBar: traffic lights re-centered (delta " + delta + "px)" );
    }

    /**
     * Reads the {@code NSRect} returned by a no-arg {@code selector} on {@code target} into a
     * {@code double[]{x, y, w, h}}. Uses {@code NSInvocation} so the struct return is captured
     * correctly on every architecture — {@code Foundation.invoke} only hands back the integer
     * return register, which can't hold an {@code NSRect}. Returns {@code null} on any failure.
     */
    private static double[] readRect( ID target, String selector )
    {
        try {
            if ( target == null || Foundation.isNil( target ) ) {
                return null;
            }
            Pointer sel = Foundation.createSelector( selector );
            ID sig = Foundation.invoke( target, "methodSignatureForSelector:", sel );
            if ( Foundation.isNil( sig ) ) {
                return null;
            }
            ID inv = Foundation.invoke( "NSInvocation", "invocationWithMethodSignature:", sig );
            if ( Foundation.isNil( inv ) ) {
                return null;
            }
            Foundation.invoke( inv, "setSelector:", sel );
            Foundation.invoke( inv, "setTarget:", target );
            Foundation.invoke( inv, "invoke" );
            Memory buf = new Memory( 32 ); // 4 × CGFloat (8 bytes each on 64-bit)
            Foundation.invoke( inv, "getReturnValue:", buf );
            return new double[] { buf.getDouble( 0 ), buf.getDouble( 8 ),
                                  buf.getDouble( 16 ), buf.getDouble( 24 ) };
        }
        catch ( Throwable t ) {
            return null;
        }
    }

    /** Removes every title-bar accessory view controller on {@code nsWindow}. We only ever
     *  add one (the height spacer), so clearing them all is safe and keeps re-apply idempotent. */
    private static void removeOurTitlebarAccessories( ID nsWindow )
    {
        for ( int guard = 0; guard < 16; guard++ ) {
            ID vcs = Foundation.invoke( nsWindow, "titlebarAccessoryViewControllers" );
            long count = Foundation.isNil( vcs ) ? 0 : Foundation.invoke( vcs, "count" ).longValue();
            if ( count <= 0 ) {
                return;
            }
            Foundation.invoke( nsWindow, "removeTitlebarAccessoryViewControllerAtIndex:", count - 1 );
        }
    }

    /**
     * On macOS, hides the in-window brand lockup (the {@code .navBrandLogo} logo and the
     * {@code .navBrand} screen-name label) in {@code root}'s navbar so it doesn't sit
     * under the floating traffic lights. The native title bar already shows the same
     * screen name, so nothing is lost. No-op on non-macOS or a null root, and harmless to
     * call repeatedly (it runs per scene as screens swap into the shared stage).
     *
     * @param root the scene root to scan for brand nodes
     *
     * @since 2026.2
     */
    public static void hideRedundantBranding( Parent root )
    {
        if ( !SystemUtils.IS_OS_MAC || root == null ) {
            return;
        }
        hideAll( root.lookupAll( ".navBrandLogo" ) );
        hideAll( root.lookupAll( ".navBrand" ) );
    }

    /** Collapses each node out of layout (visible + managed false) so the navbar's
     *  spacer reclaims the freed top-left corner for the traffic lights. */
    private static void hideAll( Set< Node > nodes )
    {
        for ( Node node : nodes ) {
            node.setVisible( false );
            node.setManaged( false );
        }
    }

    /**
     * On macOS, turns {@code root}'s top navbar into a window drag region. The full-size
     * content view that {@link #applyHiddenInset} installs means the JavaFX scene now
     * covers the whole top of the window, so the native title-bar drag no longer reaches
     * anything — the window can't be moved. This restores it the role Electron's
     * {@code -webkit-app-region: drag} plays.
     *
     * <p>On a primary-button press over empty navbar space we hand the drag to AppKit via
     * {@code [NSWindow performWindowDragWithEvent:]}, which runs its own native tracking
     * loop until mouse-up. That follows the cursor exactly with no lag — driving the move
     * from JavaFX {@code MOUSE_DRAGGED} + {@code Stage.setX/Y} instead makes the window
     * trail the pointer and drop the gesture on fast moves, because each reposition fights
     * the FX event pump. AppKit also gets us window snapping / Spaces edge behavior for
     * free.</p>
     *
     * <p>Presses that land on an interactive control (a button, a text field, or any node
     * carrying an {@code onMouseClicked} handler such as the refresh/help glyphs) do
     * <em>not</em> start a drag, so the controls keep working; a double-click on empty space
     * zooms, matching native title-bar behavior. No-op on non-macOS or a null arg. Safe to
     * call per scene — handlers are added to that scene's node, which is discarded with the
     * scene.</p>
     *
     * <p><b>Drag region.</b> On screens with a top navbar that bar is the drag region. Screens
     * <em>without</em> one (the login + launch-progress screens are centered cards with no
     * title-bar strip) would otherwise have nowhere to grab the window once the native title
     * bar is transparent + full-size-content — so we fall back to the whole scene root, letting
     * the user drag from any empty area. The interactive-node exemption still keeps the card's
     * own buttons / fields live, so only empty background starts a drag.</p>
     *
     * @param root  the scene root (its first {@code .navBar}, else the root itself, is the drag region)
     * @param stage the stage hosting the NSWindow to drag
     *
     * @since 2026.2
     */
    public static void installWindowDrag( Parent root, Stage stage )
    {
        if ( !SystemUtils.IS_OS_MAC || root == null || stage == null ) {
            return;
        }
        // lookup returns the first .navBar in document order — the top bar, not the
        // secondary search/filter bar some screens add below it. Screens with no navbar
        // (login, launch progress) fall back to the whole root so they're still draggable.
        final Node bar = root.lookup( ".navBar" );
        final Node dragRegion = ( bar != null ) ? bar : root;

        dragRegion.addEventHandler( MouseEvent.MOUSE_PRESSED, event -> {
            if ( event.getButton() != MouseButton.PRIMARY
                    || isOnInteractiveNode( event.getTarget(), dragRegion ) ) {
                return;
            }
            if ( event.getClickCount() == 2 ) {
                // Double-click empty space to zoom, matching the native title bar.
                stage.setMaximized( !stage.isMaximized() );
                return;
            }
            startNativeWindowDrag( stage );
        } );
    }

    /** Hands the in-flight mouse-down to AppKit's native window-drag tracking loop.
     *  {@code [NSApp currentEvent]} is the mouseDown NSEvent currently being dispatched
     *  (Glass delivers it synchronously to our FX handler, which on macOS runs on the
     *  AppKit main thread), exactly what {@code performWindowDragWithEvent:} expects. */
    private static void startNativeWindowDrag( Stage stage )
    {
        try {
            NativeLong handle = WindowUtils.getNativeHandleOfStageAsNativeLong( stage );
            if ( handle == null || handle.longValue() == 0 ) {
                return;
            }
            ID nsWindow = new ID( handle.longValue() );
            ID currentEvent = Foundation.invoke(
                    Foundation.invoke( "NSApplication", "sharedApplication" ), "currentEvent" );
            if ( Foundation.isNil( currentEvent ) ) {
                return;
            }
            Foundation.invoke( nsWindow, "performWindowDragWithEvent:", currentEvent );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "MacOsTitleBar: native window drag failed — "
                                             + t.getClass().getSimpleName() + ": " + t.getMessage() );
        }
    }

    /** Walks the event-target chain up to (but not including) {@code bar}, reporting
     *  whether the press landed on something that should keep its own click behavior —
     *  a button, a text input, or any node with an {@code onMouseClicked} handler (the
     *  navbar's clickable SVG glyphs / labels). Such presses must not start a drag. */
    private static boolean isOnInteractiveNode( EventTarget target, Node bar )
    {
        if ( !( target instanceof Node ) ) {
            return false;
        }
        Node node = ( Node ) target;
        while ( node != null && node != bar ) {
            if ( node instanceof ButtonBase
                    || node instanceof TextInputControl
                    || node.getOnMouseClicked() != null ) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }
}

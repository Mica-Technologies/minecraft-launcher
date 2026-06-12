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
import com.pixelduke.window.WindowUtils;
import com.sun.jna.NativeLong;
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

            Logger.logDebug( LocalizationManager.format( "log.macTitleBar.hiddenInsetApplied", stage.getTitle() ) );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.macTitleBar.applyHiddenInsetFailed",
                                             t.getClass().getSimpleName(), t.getMessage() ) );
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
            Logger.logWarningSilent( LocalizationManager.format( "log.macTitleBar.nativeWindowDragFailed",
                                             t.getClass().getSimpleName(), t.getMessage() ) );
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

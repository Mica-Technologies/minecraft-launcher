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

package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.steam.SteamDeck;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * Drag-to-scroll for a {@link ScrollPane} on a touch screen: put a finger on the page, slide it,
 * and the page follows; let go mid-slide and it carries on a little, easing out.
 *
 * <p>On Linux, JavaFX gets no touch events: the Steam Deck's screen (in Desktop Mode and under
 * gamescope alike) reaches it as a mouse, so a finger drag was a mouse drag that scrolled
 * nothing. This reads those drags. A press only becomes a scroll once it has moved
 * {@link #START_DISTANCE} pixels, mostly up or down, so taps still click. Once it has, the
 * pressed button is disarmed and the release and click are swallowed, so lifting the finger off
 * a card or button doesn't also press it. Drags that start on a scroll bar, slider, text field
 * or a nested scrollable (a list, a text area) are left to that control.
 *
 * <p>Only on a Steam Deck or under gamescope: elsewhere a mouse drag selects text or does
 * nothing, as people expect, and touch screens on Windows and macOS already scroll.
 *
 * @since 2026.11
 */
final class DragScroll
{
    /** How far, in layout pixels, a press must move before it scrolls rather than clicks. */
    static final double START_DISTANCE = 10;

    /** How long a flick carries on, in milliseconds: the finger's speed when it lifted, times this,
     *  is how much further the page glides. */
    static final double FLING_MS = 300;

    /** Fastest flick counted, in pixels per millisecond, so one wild flick can't jump the page. */
    static final double MAX_FLING_SPEED = 5;

    /** A finger that rested this long, in milliseconds, before lifting doesn't fling. */
    static final long FLING_IDLE_MS = 80;

    private static Boolean enabled;

    private DragScroll() { /* static-only */ }

    /** One pane's drag in progress. */
    private static final class Drag
    {
        boolean armed;
        boolean scrolling;
        boolean swallowClick;
        Node pressed;
        double startX;
        double startY;
        double startValue;
        double lastY;
        long lastNanos;
        /** Finger speed, in pixels per millisecond, smoothed over the last few moves. */
        double speed;
    }

    /**
     * Whether drag-to-scroll is on: on a Steam Deck, or under gamescope.
     *
     * @return {@code true} when it is
     */
    static synchronized boolean enabled()
    {
        if ( enabled == null ) {
            enabled = SteamDeck.isSteamDeck() || GamescopeWindows.active();
        }
        return enabled;
    }

    /**
     * Adds drag-to-scroll to a pane, where it is {@link #enabled()}. Call once per pane, from
     * {@link SmoothScroll#install(ScrollPane)}.
     *
     * @param pane the pane
     */
    static void install( ScrollPane pane )
    {
        if ( pane == null || !enabled() ) {
            return;
        }
        Drag drag = new Drag();

        pane.addEventFilter( MouseEvent.MOUSE_PRESSED, event -> {
            drag.armed = false;
            drag.scrolling = false;
            drag.swallowClick = false;
            if ( event.getButton() != MouseButton.PRIMARY || !( event.getTarget() instanceof Node target )
                 || leftToControl( target, pane ) ) {
                return;
            }
            Point2D at = pane.sceneToLocal( event.getSceneX(), event.getSceneY() );
            drag.armed = true;
            drag.pressed = target;
            drag.startX = at.getX();
            drag.startY = at.getY();
        } );

        pane.addEventFilter( MouseEvent.MOUSE_DRAGGED, event -> {
            if ( !drag.armed ) {
                return;
            }
            Point2D at = pane.sceneToLocal( event.getSceneX(), event.getSceneY() );
            if ( !drag.scrolling ) {
                if ( !startsScroll( at.getX() - drag.startX, at.getY() - drag.startY ) ) {
                    return;
                }
                if ( scrollable( pane ) <= 0 ) {
                    drag.armed = false;
                    return;
                }
                drag.scrolling = true;
                SmoothScroll.stopGlide( pane );
                disarmButtons( drag.pressed, pane );
                // Measure from here, so the page doesn't jump by the start distance.
                drag.startY = at.getY();
                drag.startValue = pane.getVvalue();
                drag.lastY = at.getY();
                drag.lastNanos = System.nanoTime();
                drag.speed = 0;
            }
            event.consume();
            double scrollable = scrollable( pane );
            if ( scrollable > 0 ) {
                pane.setVvalue( valueAfterDrag( drag.startValue, at.getY() - drag.startY, scrollable ) );
            }
            long now = System.nanoTime();
            double elapsedMs = ( now - drag.lastNanos ) / 1_000_000.0;
            if ( elapsedMs > 0 ) {
                drag.speed = 0.6 * ( ( at.getY() - drag.lastY ) / elapsedMs ) + 0.4 * drag.speed;
            }
            drag.lastY = at.getY();
            drag.lastNanos = now;
        } );

        pane.addEventFilter( MouseEvent.MOUSE_RELEASED, event -> {
            boolean wasScrolling = drag.scrolling;
            drag.armed = false;
            drag.scrolling = false;
            drag.pressed = null;
            if ( !wasScrolling ) {
                return;
            }
            event.consume();
            drag.swallowClick = true;
            long idleMs = ( System.nanoTime() - drag.lastNanos ) / 1_000_000;
            SmoothScroll.glideBy( pane, flingDistance( drag.speed, idleMs ) );
        } );

        pane.addEventFilter( MouseEvent.MOUSE_CLICKED, event -> {
            if ( drag.swallowClick ) {
                drag.swallowClick = false;
                event.consume();
            }
        } );
    }

    /**
     * Whether a press that has moved by ({@code dx}, {@code dy}) is a scroll: far enough, and more
     * up or down than sideways. Pure, for tests.
     *
     * @param dx sideways movement since the press, in layout pixels
     * @param dy vertical movement since the press, in layout pixels
     *
     * @return {@code true} when the drag scrolls
     */
    static boolean startsScroll( double dx, double dy )
    {
        return Math.abs( dy ) >= START_DISTANCE && Math.abs( dy ) > Math.abs( dx );
    }

    /**
     * The scroll value once the finger has moved {@code dy} pixels since the scroll started: the
     * page moves with the finger, so moving it up scrolls down. Pure, for tests.
     *
     * @param startValue the scroll value when the scroll started, 0 to 1
     * @param dy         vertical movement since then, in layout pixels; up is negative
     * @param scrollable how far the content can scroll, in layout pixels; more than 0
     *
     * @return the new scroll value, 0 to 1
     */
    static double valueAfterDrag( double startValue, double dy, double scrollable )
    {
        return Math.max( 0, Math.min( 1, startValue - dy / scrollable ) );
    }

    /**
     * How much further the page glides after the finger lifts. Pure, for tests.
     *
     * @param speed  the finger's speed when it lifted, in pixels per millisecond; up is negative
     * @param idleMs how long the finger rested before lifting
     *
     * @return the distance to scroll the content, in layout pixels; positive scrolls down
     */
    static double flingDistance( double speed, long idleMs )
    {
        if ( idleMs >= FLING_IDLE_MS ) {
            return 0;
        }
        double capped = Math.max( -MAX_FLING_SPEED, Math.min( MAX_FLING_SPEED, speed ) );
        return -capped * FLING_MS;
    }

    private static double scrollable( ScrollPane pane )
    {
        Node content = pane.getContent();
        return content == null ? 0 : content.getLayoutBounds().getHeight() - pane.getViewportBounds().getHeight();
    }

    /** Whether a press on {@code target} belongs to a control that drags on its own. */
    private static boolean leftToControl( Node target, ScrollPane pane )
    {
        if ( SmoothScroll.isInsideNestedScrollable( target, pane ) ) {
            return true;
        }
        for ( Node n = target; n != null && n != pane; n = n.getParent() ) {
            if ( n instanceof ScrollBar || n instanceof Slider || n instanceof TextInputControl
                 || n instanceof Spinner ) {
                return true;
            }
        }
        return false;
    }

    /** Disarms any button the scroll started on, so lifting the finger doesn't press it. */
    private static void disarmButtons( Node from, ScrollPane pane )
    {
        for ( Node n = from; n != null && n != pane; n = n.getParent() ) {
            if ( n instanceof ButtonBase button ) {
                button.disarm();
            }
        }
    }
}

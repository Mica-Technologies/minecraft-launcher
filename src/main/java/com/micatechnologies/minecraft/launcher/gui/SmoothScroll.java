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

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
import javafx.util.Duration;

/**
 * Applies a smooth (animated, eased) wheel-scroll behavior to a JavaFX
 * {@link ScrollPane}. JavaFX's default ScrollPane bumps the {@code vvalue}
 * in discrete steps on each wheel tick, which makes long lists feel rigid
 * and "snap-y" compared to native OS scroll surfaces (Edge, Win11 Settings,
 * macOS Finder, etc.). This helper instead intercepts the wheel event,
 * computes a clamped target {@code vvalue}, and animates the pane's
 * vvalue toward it via a {@link Timeline} with {@link Interpolator#EASE_OUT}
 * easing — the result is the inertia-y, continuous feel users expect from
 * modern apps.
 *
 * <p>Behavior notes:
 * <ul>
 *   <li>Successive scroll ticks <b>accumulate</b> into the same target rather
 *       than resetting it, so a fast flick produces a long smooth glide
 *       instead of stuttering frame by frame.</li>
 *   <li>Programmatic {@code setVvalue} calls (e.g. the main menu's
 *       scroll-into-view for a CLI-launched pack) still work — when nobody
 *       is currently animating, an external change updates the target so
 *       the next wheel event continues from where the pane actually is.</li>
 *   <li>If the content is shorter than the viewport (nothing to scroll), the
 *       wheel event is left for JavaFX's default handling. Avoids weird
 *       no-op animations when a screen has only a couple of cards.</li>
 *   <li>The helper installs itself as an {@code addEventFilter} on
 *       {@link ScrollEvent#SCROLL}, consumes the event, and triggers its
 *       Timeline. JavaFX's built-in wheel handler doesn't fire afterwards,
 *       so this fully replaces the default behavior.</li>
 * </ul>
 *
 * <p>Tuning constants are at the top of the class — adjust {@link #SCROLL_SPEED}
 * to change sensitivity and {@link #SCROLL_DURATION_MS} for the glide length.
 *
 * @since 3.4
 */
public final class SmoothScroll
{
    /**
     * Sensitivity multiplier: how far each unit of wheel delta should advance
     * the target vvalue. JavaFX's default wheel delta on Windows is typically
     * ±32 per tick; ~1.6× of that gives roughly 50 logical pixels of glide per
     * tick, comparable to native apps. Higher = faster scroll.
     */
    private static final double SCROLL_SPEED      = 1.6;

    /**
     * Animation duration toward the accumulated target. 220 ms is short enough
     * to feel responsive (no perceptible lag between flick and motion) and long
     * enough to read as a smooth glide rather than a teleport.
     */
    private static final double SCROLL_DURATION_MS = 220;

    private SmoothScroll() { /* static-only */ }

    /**
     * Installs the smooth-scroll behavior on the given {@link ScrollPane}.
     * Idempotent in the sense that installing twice doubles the sensitivity —
     * call only once per pane. Safe to call before the pane has content; the
     * scroll handler defers all content-size queries until each event fires.
     *
     * @param scrollPane the pane to apply smooth scrolling to
     */
    public static void install( ScrollPane scrollPane )
    {
        if ( scrollPane == null ) return;

        final double[] target = { scrollPane.getVvalue() };
        final Timeline[] anim = { null };

        // External vvalue changes (programmatic setVvalue, scrollbar drag, etc.):
        // when nothing is currently animating, sync the target to the new value
        // so the next wheel event continues from where the pane actually is. The
        // running-animation check is what prevents this listener from fighting
        // its own Timeline mid-tween.
        scrollPane.vvalueProperty().addListener( ( obs, oldV, newV ) -> {
            if ( anim[ 0 ] == null || anim[ 0 ].getStatus() != Animation.Status.RUNNING ) {
                target[ 0 ] = newV.doubleValue();
            }
        } );

        scrollPane.addEventFilter( ScrollEvent.SCROLL, event -> {
            Node content = scrollPane.getContent();
            if ( content == null ) return;

            double contentHeight  = content.getLayoutBounds().getHeight();
            double viewportHeight = scrollPane.getViewportBounds().getHeight();
            double scrollable     = contentHeight - viewportHeight;

            // Nothing to scroll: let JavaFX's default (no-op) wheel behavior
            // handle it. Animating in this branch produces visible jitter at
            // the clamp limits.
            if ( scrollable <= 0 ) return;

            event.consume();

            // Convert wheel delta into vvalue delta. Wheel-down (deltaY > 0)
            // means user wants to scroll content DOWN, which in JavaFX means
            // increasing vvalue. Wheel-up (deltaY < 0) decreases it.
            double deltaY = -event.getDeltaY() * SCROLL_SPEED;
            target[ 0 ] = clamp( target[ 0 ] + deltaY / scrollable, 0, 1 );

            if ( anim[ 0 ] != null ) {
                anim[ 0 ].stop();
            }
            anim[ 0 ] = new Timeline( new KeyFrame(
                    Duration.millis( SCROLL_DURATION_MS ),
                    new KeyValue( scrollPane.vvalueProperty(), target[ 0 ], Interpolator.EASE_OUT ) ) );
            anim[ 0 ].play();
        } );
    }

    private static double clamp( double v, double lo, double hi )
    {
        return Math.max( lo, Math.min( hi, v ) );
    }
}

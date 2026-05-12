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

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;

/**
 * Applies a smooth (animated, eased) wheel-scroll behavior to a JavaFX
 * {@link ScrollPane}. JavaFX's default ScrollPane bumps the {@code vvalue}
 * in discrete steps on each wheel tick, which feels rigid compared to native
 * OS scroll surfaces.
 *
 * <p>This helper replaces that with a "pull toward target" model driven by
 * an {@link AnimationTimer}:
 * <ol>
 *   <li>Wheel events accumulate their delta into a target {@code vvalue}
 *       (clamped to 0..1).</li>
 *   <li>An AnimationTimer ticks every render frame and advances the pane's
 *       actual vvalue toward the target by a fraction of the remaining
 *       distance — exponential approach, naturally eases into the target.</li>
 *   <li>When current ≈ target, the timer stops itself. Next wheel event
 *       restarts it.</li>
 * </ol>
 *
 * <p>Why AnimationTimer over Timeline:
 * <ul>
 *   <li><b>No restart jolts.</b> The previous Timeline implementation
 *       stopped + restarted on every wheel event, which was fine for
 *       discrete clicks but visibly jolted under a free-spinning mouse
 *       wheel firing 30+ events per second.</li>
 *   <li><b>Rapid events accumulate cleanly.</b> Each event just nudges
 *       the target; the timer is already running and continues chasing
 *       without breaking stride.</li>
 *   <li><b>External vvalue changes are easy to detect.</b> When the user
 *       drags the scrollbar (or selectModpack programmatically scrolls
 *       into view), the timer ISN'T running, so the vvalue listener
 *       resets the target — the next wheel event then continues from
 *       where the pane actually is.</li>
 * </ul>
 *
 * <p>Tuning constants up top — {@link #SCROLL_SPEED} controls sensitivity
 * (how much each unit of wheel delta moves the target), and
 * {@link #FOLLOW_RATE} controls how aggressively the actual vvalue chases
 * the target each frame (closer to 1.0 = snappier, closer to 0.0 = more
 * floaty).
 *
 * @since 3.4
 */
public final class SmoothScroll
{
    /**
     * Sensitivity multiplier: how far each unit of wheel delta should
     * advance the target vvalue. JavaFX's default wheel delta on Windows
     * is typically ±32 per click — this 2.6× multiplier puts the per-click
     * glide at ~80 logical pixels, comparable to Edge / Win11 Settings.
     * Free-wheel mode fires smaller deltas more often; the accumulation
     * + chase model handles both gracefully.
     */
    private static final double SCROLL_SPEED = 2.6;

    /**
     * Per-frame chase rate: at 60 fps, 0.25 means we cover 25% of the
     * remaining distance to the target every frame. After ~10 frames
     * (~167 ms) we're at 94% of the target, after 16 frames (~266 ms)
     * at 99%. Faster feels responsive on click-by-click input; slower
     * feels floatier on free-wheel. 0.25 is a deliberate middle ground.
     */
    private static final double FOLLOW_RATE = 0.25;

    /**
     * Snap threshold. When current is within this fraction of the target,
     * the timer assigns target exactly and stops. Prevents infinite tiny
     * fractional moves (Zeno's paradox style) and lets the timer
     * gracefully idle until the next wheel event.
     */
    private static final double SNAP_THRESHOLD = 0.0005;

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
        // Set to true while the animation is the one mutating vvalue, so the
        // value-change listener below ignores its own writes. Without this
        // flag, every frame the listener would reset the target to whatever
        // we just wrote, creating a feedback loop that stops the chase.
        final boolean[] internalUpdate = { false };

        final AnimationTimer ticker = new AnimationTimer()
        {
            @Override
            public void handle( long now )
            {
                double current = scrollPane.getVvalue();
                double diff = target[ 0 ] - current;
                if ( Math.abs( diff ) < SNAP_THRESHOLD ) {
                    internalUpdate[ 0 ] = true;
                    scrollPane.setVvalue( target[ 0 ] );
                    internalUpdate[ 0 ] = false;
                    stop();
                    return;
                }
                internalUpdate[ 0 ] = true;
                scrollPane.setVvalue( current + diff * FOLLOW_RATE );
                internalUpdate[ 0 ] = false;
            }
        };

        // External vvalue changes (drag scrollbar, programmatic setVvalue,
        // keyboard arrow keys): sync target to the new value so the chase
        // continues from where the user actually put the pane.
        scrollPane.vvalueProperty().addListener( ( obs, oldV, newV ) -> {
            if ( !internalUpdate[ 0 ] ) {
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

            // Idempotent — AnimationTimer.start() is a no-op if already
            // running. No restart jolts because the existing tick continues
            // chasing the freshly-updated target.
            ticker.start();
        } );
    }

    private static double clamp( double v, double lo, double hi )
    {
        return Math.max( lo, Math.min( hi, v ) );
    }
}

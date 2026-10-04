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
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.WeakInvalidationListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs an animation only while its node can actually be seen: the node and every parent are
 * visible, and the node sits in a scene whose window is showing.
 *
 * <p>Starting a timer when a node joins a scene and stopping it when it leaves is not enough. When
 * a window swaps scenes, the old scene drops off the stage but its nodes keep that scene, so the
 * timer would run for ever; the toolkit holds running timers strongly, so the whole old screen and
 * its controller would never be collected, and the 60 Hz pulse would never idle. A hidden node
 * (a spinner kept around with {@code visible="false"}) would tick for nothing too.
 *
 * <p>The gate watches the visibility and parent of the node and of each ancestor, the node's
 * scene, that scene's window and whether it is showing, and re-subscribes whenever the chain
 * changes. Everything outside the node is watched through one weak listener, which is also removed
 * explicitly whenever the chain changes, so a long-lived window never holds an old screen.
 *
 * @since 2026.10
 */
final class AnimationGate implements InvalidationListener
{
    private final Node node;
    private final Runnable start;
    private final Runnable stop;
    /** The one listener registered on everything watched; weak so nothing watched holds us. */
    private final WeakInvalidationListener weak = new WeakInvalidationListener( this );
    private final List< Observable > watched = new ArrayList<>();
    private boolean running;

    /**
     * Creates a gate. Callers normally use {@link #attach(Node, AnimationTimer)}.
     *
     * @param node  the animated node
     * @param start starts the animation; called only when it isn't running
     * @param stop  stops the animation; called only when it is running
     */
    AnimationGate( Node node, Runnable start, Runnable stop )
    {
        this.node = node;
        this.start = start;
        this.stop = stop;
        rewire();
    }

    /**
     * Gates a timer on its node being seen. The node must keep the returned gate (a field) so the
     * weak listeners stay alive as long as the node does.
     *
     * @param node  the animated node
     * @param timer its timer
     *
     * @return the gate
     */
    static AnimationGate attach( Node node, AnimationTimer timer )
    {
        return new AnimationGate( node, timer::start, timer::stop );
    }

    /** @return whether the animation is running */
    boolean isRunning()
    {
        return running;
    }

    @Override
    public void invalidated( Observable observable )
    {
        rewire();
    }

    /**
     * Re-subscribes to the current chain and starts or stops the animation to match. Reading every
     * watched value here also re-validates it, so each invalidation listener fires again on the
     * next change.
     */
    private void rewire()
    {
        for ( Observable o : watched ) {
            o.removeListener( weak );
        }
        watched.clear();

        boolean visible = true;
        for ( Node n = node; n != null; n = n.getParent() ) {
            watch( n.visibleProperty() );
            watch( n.parentProperty() );
            visible &= n.isVisible();
        }
        watch( node.sceneProperty() );
        boolean showing = false;
        Scene scene = node.getScene();
        if ( scene != null ) {
            watch( scene.windowProperty() );
            Window window = scene.getWindow();
            if ( window != null ) {
                watch( window.showingProperty() );
                showing = window.isShowing();
            }
        }

        boolean live = visible && showing;
        if ( live && !running ) {
            running = true;
            start.run();
        }
        else if ( !live && running ) {
            running = false;
            stop.run();
        }
    }

    private void watch( Observable observable )
    {
        observable.addListener( weak );
        watched.add( observable );
    }
}

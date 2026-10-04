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

import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that {@link AnimationGate} runs an animation only while its node is seen, and stops it
 * when the window swaps to another scene (the case that used to leak the old screen).
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class AnimationGateFxTest
{
    private Stage stage;

    @Start
    private void start( Stage stage )
    {
        this.stage = stage;
        stage.setScene( new Scene( new StackPane(), 200, 200 ) );
        stage.show();
    }

    @Test
    void runsOnlyWhileSeen( FxRobot robot )
    {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        robot.interact( () -> {
            Pane parent = new StackPane();
            Pane node = new Pane();
            parent.getChildren().add( node );
            AnimationGate gate = new AnimationGate( node, starts::incrementAndGet, stops::incrementAndGet );
            assertFalse( gate.isRunning(), "not in a scene" );

            Scene first = new Scene( parent, 200, 200 );
            stage.setScene( first );
            assertTrue( gate.isRunning(), "in a showing window" );

            parent.setVisible( false );
            assertFalse( gate.isRunning(), "a parent is hidden" );
            parent.setVisible( true );
            assertTrue( gate.isRunning() );

            // The old scene keeps its nodes, but it no longer has a window.
            stage.setScene( new Scene( new StackPane(), 200, 200 ) );
            assertFalse( gate.isRunning(), "scene swapped out" );

            stage.setScene( first );
            assertTrue( gate.isRunning() );
            stage.hide();
            assertFalse( gate.isRunning(), "window hidden" );

            // Moved under a new, visible parent in a hidden window: still stopped.
            Pane other = new StackPane();
            other.getChildren().add( node );
            parent.setVisible( false );
            assertFalse( gate.isRunning() );
        } );
        assertEquals( starts.get(), stops.get(), "every start was matched by a stop" );
    }
}

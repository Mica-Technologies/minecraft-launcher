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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Minimum-viable TestFX smoke test. Proves the harness is wired up
 * correctly: a Stage starts, the scene graph paints, and FxRobot can
 * drive the UI (lookup + fire input + read updated state).
 *
 * <p>This test does NOT pull in the actual launcher GUI controllers —
 * those have hard dependencies on ConfigManager / Logger / Platform
 * lifecycle state that aren't safe to spin up inside a test JVM yet.
 * Future GUI E2E tests should follow the same {@link Start} +
 * {@link FxRobot} pattern, building scenes from raw FXML or
 * controller no-arg constructors with the launcher-state singletons
 * stubbed out via test seams. See {@code @Start} below as the
 * template; the launcher's existing extracted-seam tests
 * ({@code LibraryViewModelTest}, {@code ScanAcknowledgementTest})
 * show the wider pattern for testable seams.</p>
 *
 * <p>Headless support: TestFX runs in interactive mode by default and
 * needs a display server. CI doesn't have one, so the entire class is
 * gated behind the {@code MMCL_RUN_TESTFX} env var — developers
 * running on a workstation set {@code MMCL_RUN_TESTFX=true} and the
 * tests fire; CI doesn't set it and the tests skip cleanly. To
 * eventually run these in CI too, add the {@code openjfx-monocle}
 * dependency and set system properties
 * {@code -Dtestfx.robot=glass -Dtestfx.headless=true -Dprism.order=sw}
 * via the Surefire {@code <systemPropertyVariables>} block.</p>
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class TestFxSmokeTest
{
    private Button incrementBtn;
    private Label counterLabel;
    private int clickCount;

    /** Called by TestFX before each test — analogous to JavaFX
     *  Application.start. Build the scene graph here. */
    @Start
    private void start( Stage stage )
    {
        clickCount = 0;

        counterLabel = new Label( "Clicks: 0" );
        counterLabel.setId( "counter-label" );

        incrementBtn = new Button( "Click me" );
        incrementBtn.setId( "increment-btn" );
        incrementBtn.setOnAction( e -> {
            clickCount++;
            counterLabel.setText( "Clicks: " + clickCount );
        } );

        VBox root = new VBox( 8, incrementBtn, counterLabel );
        stage.setScene( new Scene( root, 320, 120 ) );
        stage.show();
    }

    @Test
    void button_clicks_update_label( FxRobot robot )
    {
        // Class-level @EnabledIfEnvironmentVariable gates the whole
        // test on MMCL_RUN_TESTFX, so this body only runs when a
        // developer opted in. No additional skip check needed here.

        // Lookup by ID is the documented stable selector for tests —
        // text and CSS classes can change during a refactor, IDs are
        // explicit hooks.
        Button btn = robot.lookup( "#increment-btn" ).queryAs( Button.class );
        Label label = robot.lookup( "#counter-label" ).queryAs( Label.class );
        assertNotNull( btn );
        assertNotNull( label );

        robot.clickOn( "#increment-btn" );
        robot.clickOn( "#increment-btn" );
        robot.clickOn( "#increment-btn" );

        // Three click events → counter at 3. FxRobot's click events
        // are synchronous with the FX action handler, so we can assert
        // immediately after the last click.
        assertEquals( "Clicks: 3", label.getText() );
    }
}

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

import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
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
 * Locks in that a robot click on a switch drawn by {@link MaterialSwitchSkin} flips it exactly
 * once per click — every toggle in the launcher uses this skin, so a click that toggles zero or
 * two times leaves every switch looking dead.
 *
 * <p>Gated behind {@code MMCL_RUN_TESTFX=true} like the rest of the TestFX suite.</p>
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class MaterialSwitchSkinFxTest
{
    private MFXToggleButton withLabel;
    private MFXToggleButton noLabel;
    private final AtomicInteger changes = new AtomicInteger();

    @Start
    private void start( Stage stage )
    {
        withLabel = new MFXToggleButton( "Enable the thing" );
        withLabel.setSkin( new MaterialSwitchSkin( withLabel ) );
        withLabel.selectedProperty().addListener( ( o, a, b ) -> changes.incrementAndGet() );
        noLabel = new MFXToggleButton();
        noLabel.setSkin( new MaterialSwitchSkin( noLabel ) );

        VBox root = new VBox( 16, withLabel, noLabel );
        root.setStyle( "-fx-padding: 16;" );
        stage.setScene( new Scene( root, 360, 160 ) );
        stage.show();
    }

    @Test
    void clickFlipsTheSwitchOncePerClick( FxRobot robot )
    {
        assertFalse( withLabel.isSelected() );
        robot.clickOn( withLabel );
        assertTrue( withLabel.isSelected(), "one click should turn the switch on" );
        assertEquals( 1, changes.get(), "one click should change the state exactly once" );
        robot.clickOn( withLabel );
        assertFalse( withLabel.isSelected(), "a second click should turn it off again" );
        assertEquals( 2, changes.get() );
    }

    @Test
    void clickFlipsASwitchWithNoLabel( FxRobot robot )
    {
        robot.clickOn( noLabel );
        assertTrue( noLabel.isSelected(), "a switch with no label should still toggle on click" );
    }

    @Test
    void spaceFlipsTheFocusedSwitchOnce( FxRobot robot )
    {
        robot.interact( noLabel::requestFocus );
        robot.type( KeyCode.SPACE );
        assertTrue( noLabel.isSelected(), "Space should turn the focused switch on" );
        robot.type( KeyCode.SPACE );
        assertFalse( noLabel.isSelected(), "Space again should turn it off" );
    }
}

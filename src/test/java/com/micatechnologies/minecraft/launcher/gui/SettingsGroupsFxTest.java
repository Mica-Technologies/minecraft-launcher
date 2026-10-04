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

import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings row groups tag their visible rows by position and hide rows with nothing showing,
 * including groups inside a scroll pane that hasn't been skinned yet.
 *
 * @since 2026.10
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class SettingsGroupsFxTest
{
    @Test
    void tagsRowsAndFollowsVisibility( FxRobot robot )
    {
        Label a = new Label( "a" );
        Label b = new Label( "b" );
        Label c = new Label( "c" );
        VBox rowA = row( a );
        VBox rowB = row( b );
        VBox rowC = row( c );
        VBox group = new VBox( rowA, rowB, rowC );
        group.getStyleClass().add( "settingsGroup" );
        ScrollPane unskinned = new ScrollPane( new VBox( group ) );

        robot.interact( () -> SettingsGroups.install( new VBox( unskinned ) ) );
        assertTrue( rowA.getStyleClass().contains( "tileFirst" ) );
        assertTrue( rowB.getStyleClass().contains( "tileMiddle" ) );
        assertTrue( rowC.getStyleClass().contains( "tileLast" ) );

        robot.interact( () -> {
            c.setVisible( false );
            c.setManaged( false );
        } );
        assertFalse( rowC.isManaged(), "a row with nothing showing is hidden" );
        assertTrue( rowB.getStyleClass().contains( "tileLast" ), "the last visible row takes the bottom corners" );
    }

    private static VBox row( Label content )
    {
        VBox row = new VBox( content );
        row.getStyleClass().add( "settingsRow" );
        return row;
    }
}

/*
 * Copyright (c) 2021 Mica Technologies
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

import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

public class MCLauncherGuiWindow extends Application
{
    private Stage stage;

    @Override
    public void start( Stage stage ) throws Exception {
        // Initialize default scene/GUI
        MCLauncherProgressGui progressGui = new MCLauncherProgressGui( stage );

        // Save stage
        this.stage = stage;

        // Configure stage
        stage.setMinHeight( 400 );
        stage.setMinWidth( 400 );

        // Set scene
        setScene( progressGui );
        show();
    }

    void setScene( MCLauncherAbstractGui gui ) {
        GUIUtilities.JFXPlatformRun( () -> {
            // Change stage name
            stage.setTitle(
                    LauncherConstants.LAUNCHER_APPLICATION_NAME + GUIConstants.TITLE_SPLIT_CHAR + gui.getSceneName() );

            // Prepare scene environment
            gui.setup();
            gui.loadEnvironment();

            // Set scene
            stage.setScene( gui.scene );
        } );
    }

    public void show() {
        GUIUtilities.JFXPlatformRun( () -> stage.show() );
    }

    public Stage getStage() {
        return stage;
    }
}


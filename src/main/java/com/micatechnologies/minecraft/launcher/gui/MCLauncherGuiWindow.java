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

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.apache.commons.lang3.SystemUtils;

import java.io.InputStream;

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

        // Set resizable property
        stage.setResizable( ConfigManager.getResizableWindows() );

        // Set application icon
        try {
            InputStream iconStream = getClass().getClassLoader().getResourceAsStream( "micaforgelauncher.png" );
            Image icon = new Image( iconStream );
            stage.getIcons().add( icon );
        }
        catch ( Exception e ) {
            Logger.logError( "An error occurred while setting the application icon!" );
            Logger.logThrowable( e );
        }

        // Set scene
        show();
        setScene( progressGui );
        stage.centerOnScreen();
    }

    void setScene( MCLauncherAbstractGui gui ) {
        GUIUtilities.JFXPlatformRun( () -> {
            // Prepare scene environment
            gui.setup();
            gui.loadEnvironment();

            // Change stage name
            stage.setTitle(
                    LauncherConstants.LAUNCHER_APPLICATION_NAME + GUIConstants.TITLE_SPLIT_CHAR + gui.getSceneName() );

            // Set scene
            stage.setScene( gui.scene );

            // Style macOS Window
            if ( SystemUtils.IS_OS_MAC ) {
                styleMacWindow( gui );
            }
        } );
    }

    /**
     * Performs styling of the window that is specific to the macOS operating system.
     *
     * @since 2.0
     */
    private void styleMacWindow( MCLauncherAbstractGui gui ) {

        try {
            // Setup mac menu bar
        }
        catch ( Exception e ) {
            Logger.logDebug( "An error occurred while editing the macOS menu bar." );
            Logger.logThrowable( e );
        }
    }

    public void show() {
        GUIUtilities.JFXPlatformRun( () -> stage.show() );
    }

    public Stage getStage() {
        return stage;
    }
}


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

import com.jthemedetecor.OsThemeDetector;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.application.Application;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.image.Image;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

import java.io.InputStream;
import java.util.Objects;

public class MCLauncherGuiWindow extends Application
{

    private static final double                PREF_WIDTH  = 1000.0;
    private static final double                PREF_HEIGHT = 800.0;
    private static final double                MIN_WIDTH   = 750.0;
    private static final double                MIN_HEIGHT  = 600.0;
    private              Stage                 stage;
    private              MCLauncherAbstractGui gui;

    private OsThemeDetector detector = null;
    private java.util.function.Consumer< Boolean > themeListener = null;

    @Override
    public void start( Stage stage ) throws Exception {
        // Initialize default scene/GUI
        MCLauncherProgressGui progressGui = new MCLauncherProgressGui( stage, PREF_WIDTH, PREF_HEIGHT );

        // Save stage
        this.stage = stage;

        // Configure stage
        stage.setMinHeight( MIN_HEIGHT );
        stage.setMinWidth( MIN_WIDTH );

        // Set resizable property
        stage.setResizable( ConfigManager.getResizableWindows() );

        // Set application icon
        try ( InputStream iconStream = getClass().getClassLoader().getResourceAsStream( "micaminecraftlauncher.png" ) ) {
            if ( iconStream != null ) {
                Image icon = new Image( iconStream );
                stage.getIcons().add( icon );
            }
        }
        catch ( Exception e ) {
            Logger.logError( "An error occurred while setting the application icon!" );
            Logger.logThrowable( e );
        }

        // Setup theme detector
        try {
            detector = OsThemeDetector.getDetector();
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Unable to configure theme detector for dark/light mode!" );
            Logger.logThrowable( e );
        }

        // Set scene
        show();
        setScene( progressGui );
        stage.centerOnScreen();
    }

    void setScene( MCLauncherAbstractGui gui ) {
        // Cleanup previous GUI, if present
        if ( this.gui != null ) {
            this.gui.cleanup();
        }

        // Store new GUI and set it up
        this.gui = gui;
        GUIUtilities.JFXPlatformRun( () -> {
            // Prepare scene environment
            gui.setup();

            // Inject context-sensitive help button into top-right corner
            injectHelpButton( gui );

            // Change stage name
            stage.setTitle(
                    LauncherConstants.LAUNCHER_APPLICATION_NAME + GUIConstants.TITLE_SPLIT_CHAR + gui.getSceneName() );

            // Set correct first theme
            forceThemeChange();

            // Set scene
            stage.setScene( gui.scene );

            gui.afterShow();
        } );

        // Setup theme detector change listener (unregister previous to avoid accumulation)
        if ( detector != null ) {
            try {
                if ( themeListener != null ) {
                    detector.removeListener( themeListener );
                }
                themeListener = isDark -> forceThemeChange();
                detector.registerListener( themeListener );
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Unable to configure theme change listener for dark/light mode!" );
                Logger.logThrowable( e );
            }
        }
    }

    public void show() {
        GUIUtilities.JFXPlatformRun( () -> stage.show() );
    }

    public Stage getStage() {
        return stage;
    }

    public void forceThemeChange() {
        // Also refresh the help window theme if it's open
        MCLauncherHelpWindow.refreshTheme();

        switch ( ConfigManager.getTheme() ) {
            case ConfigConstants.THEME_AUTOMATIC:
                if ( detector != null ) {
                    if ( detector.isDark() ) {
                        // The OS switched to a dark theme
                        switchToDarkTheme();
                    }
                    else {
                        // The OS switched to a light theme
                        switchToLightTheme();
                    }
                }
                break;
            case ConfigConstants.THEME_LIGHT:
                switchToLightTheme();
                break;
            case ConfigConstants.THEME_DARK:
                switchToDarkTheme();
                break;
            case ConfigConstants.THEME_BLUE_GRAY:
                switchToBlueGrayTheme();
                break;
            case ConfigConstants.THEME_ORANGE_PURPLE:
                switchToOrangePurpleTheme();
                break;
        }
    }

    private void switchToLightTheme() {
        GUIUtilities.JFXPlatformRun( () -> {
            gui.rootPane.getStylesheets()
                        .remove(
                                Objects.requireNonNull( getClass().getClassLoader().getResource( "guiStyle-dark.css" ) )
                                       .toExternalForm() );
            gui.rootPane.getStylesheets()
                        .remove( Objects.requireNonNull(
                                                getClass().getClassLoader().getResource( "guiStyle-orangepurple.css" ) )
                                        .toExternalForm() );
            gui.rootPane.getStylesheets()
                        .remove( Objects.requireNonNull(
                                getClass().getClassLoader().getResource( "guiStyle-bluegray.css" ) ).toExternalForm() );
            gui.rootPane.getStylesheets()
                        .add( Objects.requireNonNull( getClass().getClassLoader().getResource( "guiStyle-light.css" ) )
                                     .toExternalForm() );
        } );
    }

    private void switchToDarkTheme() {
        GUIUtilities.JFXPlatformRun( () -> {
            gui.rootPane.getStylesheets()
                        .remove( Objects.requireNonNull(
                                getClass().getClassLoader().getResource( "guiStyle-light.css" ) ).toExternalForm() );
            gui.rootPane.getStylesheets()
                        .remove( Objects.requireNonNull(
                                                getClass().getClassLoader().getResource( "guiStyle-orangepurple.css" ) )
                                        .toExternalForm() );
            gui.rootPane.getStylesheets()
                        .remove( Objects.requireNonNull(
                                getClass().getClassLoader().getResource( "guiStyle-bluegray.css" ) ).toExternalForm() );
            gui.rootPane.getStylesheets()
                        .add( Objects.requireNonNull( getClass().getClassLoader().getResource( "guiStyle-dark.css" ) )
                                     .toExternalForm() );
        } );
    }

    private void switchToBlueGrayTheme() {
        GUIUtilities.JFXPlatformRun( () -> {
            gui.rootPane.getStylesheets()
                        .remove( Objects.requireNonNull(
                                getClass().getClassLoader().getResource( "guiStyle-light.css" ) ).toExternalForm() );
            gui.rootPane.getStylesheets()
                        .remove( Objects.requireNonNull(
                                                getClass().getClassLoader().getResource( "guiStyle-orangepurple.css" ) )
                                        .toExternalForm() );
            gui.rootPane.getStylesheets()
                        .remove(
                                Objects.requireNonNull( getClass().getClassLoader().getResource( "guiStyle-dark.css" ) )
                                       .toExternalForm() );
            gui.rootPane.getStylesheets()
                        .add( Objects.requireNonNull(
                                getClass().getClassLoader().getResource( "guiStyle-bluegray.css" ) ).toExternalForm() );
        } );
    }

    private void switchToOrangePurpleTheme() {
        GUIUtilities.JFXPlatformRun( () -> {
            gui.rootPane.getStylesheets()
                        .remove( Objects.requireNonNull(
                                getClass().getClassLoader().getResource( "guiStyle-light.css" ) ).toExternalForm() );
            gui.rootPane.getStylesheets()
                        .remove( Objects.requireNonNull(
                                getClass().getClassLoader().getResource( "guiStyle-bluegray.css" ) ).toExternalForm() );
            gui.rootPane.getStylesheets()
                        .remove(
                                Objects.requireNonNull( getClass().getClassLoader().getResource( "guiStyle-dark.css" ) )
                                       .toExternalForm() );
            gui.rootPane.getStylesheets()
                        .add( Objects.requireNonNull(
                                             getClass().getClassLoader().getResource( "guiStyle-orangepurple.css" ) )
                                     .toExternalForm() );
        } );
    }

    /**
     * Programmatically injects a "?" help button into the top-right corner of the screen's root pane. The button opens
     * the help window to the topic returned by the GUI's {@link MCLauncherAbstractGui#getHelpTopic()}.
     *
     * @param gui the current GUI screen
     */
    private void injectHelpButton( MCLauncherAbstractGui gui )
    {
        if ( gui.rootPane instanceof GridPane gridPane ) {
            MFXButton helpBtn = new MFXButton( "?" );
            helpBtn.getStyleClass().add( "helpButton" );
            helpBtn.setOnAction( e -> MCLauncherHelpWindow.show( gui.getHelpTopic() ) );

            // Add to column 0, row 0 aligned to top-right so it overlays in the corner
            int col = gridPane.getColumnConstraints().size() - 1;
            if ( col < 0 ) col = 0;
            gridPane.add( helpBtn, col, 0 );
            GridPane.setHalignment( helpBtn, HPos.RIGHT );
            GridPane.setValignment( helpBtn, VPos.TOP );
            GridPane.setMargin( helpBtn, new Insets( 8, 8, 0, 0 ) );
        }
    }

    /**
     * Cleans up the theme detector listener to prevent memory leaks. Should be called during application shutdown.
     *
     * @since 2.0
     */
    public void cleanup()
    {
        if ( detector != null && themeListener != null ) {
            try {
                detector.removeListener( themeListener );
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Unable to remove theme change listener during cleanup." );
            }
            themeListener = null;
        }
    }
}


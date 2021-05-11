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

import com.micatechnologies.jadapt.NSWindow;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import com.sun.glass.ui.Window;
import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.apache.commons.lang3.SystemUtils;
import org.rococoa.ID;
import org.rococoa.Rococoa;
import org.rococoa.cocoa.foundation.NSUInteger;

import java.io.InputStream;
import java.net.URL;
import java.util.Objects;

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
        setScene( progressGui );
        show();

        // Style macOS Window
        if ( SystemUtils.IS_OS_MAC ) {
            styleMacWindow();
        }
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
        } );
    }

    /**
     * Performs styling of the window that is specific to the macOS operating system.
     *
     * @since 2.0
     */
    private void styleMacWindow() {
        try {
            NSWindow thisWindow = getNSWindow();

            // Perform styling
            thisWindow.setTitlebarAppearsTransparent( true );
            thisWindow.setStyleMask(
                    new NSUInteger( thisWindow.styleMask().intValue() | NSWindow.StyleMaskFullSizeContentView ) );

            // Make window draggable
            stage.getScene()
                 .setOnMousePressed( pressEvent -> stage.getScene().setOnMouseDragged( dragEvent -> {
                     stage.setX( dragEvent.getScreenX() - pressEvent.getSceneX() );
                     stage.setY( dragEvent.getScreenY() - pressEvent.getSceneY() );
                 } ) );
        }
        catch ( Exception e ) {
            Logger.logDebug( "An error occurred while performing style modifications to an NSWindow wrapper." );
            Logger.logThrowable( e );
        }
    }

    /**
     * Gets the native macOS NSWindow interface class to allow for advanced macOS window styling.
     *
     * @return macOS native window
     *
     * @since 3.0
     */
    NSWindow getNSWindow() {
        // Load rococoa library
        URL url = this.getClass().getClassLoader().getResource( "lib/darwin/librococoa.dylib" );
        if ( url != null ) {
            System.load( url.toExternalForm() );
        }
        else {
            Logger.logDebug( "Unable to load rococoa library for macOS window styling!" );
        }

        // Wrap window as NSWindow and return
        return Rococoa.wrap( ID.fromLong( getWindowHandle() ), NSWindow.class );
    }

    /**
     * Gets the native window pointer.
     *
     * @return native window pointer
     *
     * @since 3.0
     */
    public long getWindowHandle() {
        // Attempt to compare windows and ensure correct one picked
        for ( Window w : Window.getWindows() ) {
            if ( Objects.equals( w.getTitle(), stage.getTitle() ) &&
                    w.getHeight() == stage.getHeight() &&
                    w.getWidth() == stage.getWidth() &&
                    w.getX() == stage.getX() &&
                    w.getY() == stage.getY() ) {
                return w.getNativeHandle();
            }
        }

        // If unable to find window, return window 0
        return Window.getWindows().get( 0 ).getNativeHandle();
    }

    public void show() {
        GUIUtilities.JFXPlatformRun( () -> stage.show() );
    }

    public Stage getStage() {
        return stage;
    }
}


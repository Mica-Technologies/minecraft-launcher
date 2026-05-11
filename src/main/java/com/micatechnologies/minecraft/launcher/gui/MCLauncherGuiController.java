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

import com.micatechnologies.minecraft.launcher.files.Logger;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class MCLauncherGuiController
{
    private static final AtomicBoolean       startSuccess = new AtomicBoolean( false );
    private static       MCLauncherGuiWindow guiWindow    = null;

    public static Stage getTopStageOrNull() {
        return startSuccess.get() ? guiWindow.getStage() : null;
    }

    public static void requestFocus() {
        Stage topStage = getTopStageOrNull();
        if ( topStage != null ) {
            GUIUtilities.JFXPlatformRun( () -> {
                // De-iconify (Windows minimize, macOS minimize-to-dock) before requestFocus
                // — without this, a user clicking the tray icon or a mmcl:// link with the
                // launcher minimized would get no visible response. show() + toFront() bring
                // the window above other apps; requestFocus() handles input focus.
                if ( topStage.isIconified() ) {
                    topStage.setIconified( false );
                }
                if ( !topStage.isShowing() ) {
                    topStage.show();
                }
                topStage.toFront();
                topStage.requestFocus();
            } );
        }
    }


    private static boolean startGui() {
        if ( guiWindow == null ) {
            GUIUtilities.JFXPlatformRun( () -> {
                try {
                    guiWindow = new MCLauncherGuiWindow();
                    Stage stage = new Stage();
                    // StageStyle.UNIFIED makes JavaFX create the HWND in a way Windows DWM
                    // can composite a Mica system backdrop through. Plain DECORATED keeps an
                    // opaque redirection bitmap so DwmSetWindowAttribute(SYSTEMBACKDROP_TYPE)
                    // is silently ignored. UNIFIED is supported on Windows + macOS; falls
                    // back to a normal decorated window on Linux. Visually it removes the
                    // title-bar / content separator line — since we color-match the caption
                    // to the theme bg via DWMWA_CAPTION_COLOR, the seam was already invisible
                    // on most themes, so adopting UNIFIED everywhere costs nothing.
                    stage.initStyle( javafx.stage.StageStyle.UNIFIED );
                    guiWindow.start( stage );
                    guiWindow.show();
                    startSuccess.set( true );
                }
                catch ( Exception e ) {
                    guiWindow = null;
                    Logger.logError( "An exception was encountered while starting the application GUI window." );
                    Logger.logThrowable( e );
                    startSuccess.set( false );
                }
            } );
        }
        return startSuccess.get();
    }

    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherMainGui goToMainGui() throws IOException {
        MCLauncherMainGui newMainGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newMainGui = new MCLauncherMainGui( guiWindow.getStage() );
            guiWindow.setScene( newMainGui );
            guiWindow.show();
        }
        else {
            Logger.logError( "The application main GUI could not be displayed due to the application GUI not being " +
                                     "started." );
        }
        return newMainGui;
    }

    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherSettingsGui goToSettingsGui() throws IOException {
        MCLauncherSettingsGui newSettingsGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newSettingsGui = new MCLauncherSettingsGui( guiWindow.getStage() );
            guiWindow.setScene( newSettingsGui );
            guiWindow.show();
        }
        else {
            Logger.logError( "The application settings GUI could not be displayed due to the application GUI not " +
                                     "being started." );
        }
        return newSettingsGui;
    }

    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherGameLibraryGui goToGameLibraryGui() throws IOException {
        MCLauncherGameLibraryGui newLibraryGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newLibraryGui = new MCLauncherGameLibraryGui( guiWindow.getStage() );
            guiWindow.setScene( newLibraryGui );
            guiWindow.show();
        }
        else {
            Logger.logError( "The game library GUI could not be displayed due to the application GUI not " +
                                     "being started." );
        }
        return newLibraryGui;
    }

    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherLoginGui goToLoginGui() throws IOException {
        MCLauncherLoginGui newLoginGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newLoginGui = new MCLauncherLoginGui( guiWindow.getStage() );
            guiWindow.setScene( newLoginGui );
            guiWindow.show();
        }
        else {
            Logger.logError(
                    "The login GUI could not be displayed due to the application GUI not " + "being started." );
        }
        return newLoginGui;
    }

    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherProgressGui goToProgressGui() throws IOException {
        MCLauncherProgressGui newProgressGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newProgressGui = new MCLauncherProgressGui( guiWindow.getStage() );
            guiWindow.setScene( newProgressGui );
            guiWindow.show();
        }
        else {
            Logger.logError(
                    "The progress GUI could not be displayed due to the application GUI not " + "being started." );
        }
        return newProgressGui;
    }

    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherGameConsoleGui goToGameConsoleGui() throws IOException {
        MCLauncherGameConsoleGui newGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newGui = new MCLauncherGameConsoleGui( guiWindow.getStage() );
            guiWindow.setScene( newGui );
            guiWindow.show();
        }
        else {
            Logger.logError( "The game console GUI could not be displayed." );
        }
        return newGui;
    }

    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherRuntimeGui goToRuntimeGui() throws IOException {
        MCLauncherRuntimeGui newRuntimeGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newRuntimeGui = new MCLauncherRuntimeGui( guiWindow.getStage() );
            guiWindow.setScene( newRuntimeGui );
            guiWindow.show();
        }
        else {
            Logger.logError(
                    "The runtime management GUI could not be displayed due to the application GUI not being started." );
        }
        return newRuntimeGui;
    }

    public static MCLauncherModPackEditorGui goToModPackEditorGui() throws IOException
    {
        MCLauncherModPackEditorGui newEditorGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newEditorGui = new MCLauncherModPackEditorGui( guiWindow.getStage() );
            guiWindow.setScene( newEditorGui );
            guiWindow.show();
        }
        else {
            Logger.logError(
                    "The modpack editor GUI could not be displayed due to the application GUI not being started." );
        }
        return newEditorGui;
    }

    public static void forceThemeRefresh() {
        if ( guiWindow != null ) {
            guiWindow.forceThemeChange();
        }
    }

    public static void exit() {
        if ( guiWindow != null ) {
            guiWindow.cleanup();
            if ( guiWindow.getStage() != null ) {
                Platform.setImplicitExit( false );
                if ( guiWindow.getStage().isShowing() ) {
                    GUIUtilities.JFXPlatformRun( () -> guiWindow.getStage().close() );
                }
            }
            guiWindow = null;
        }
    }

    /**
     * Returns a boolean indicating if the application in its current state should use graphical user interfaces.
     *
     * @return true if GUIs should be used
     *
     * @since 1.0
     */
    public static boolean shouldCreateGui() {
        return !GraphicsEnvironment.isHeadless();
    }
}
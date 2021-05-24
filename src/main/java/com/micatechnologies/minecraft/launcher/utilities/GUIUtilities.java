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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.auth.old.AuthManager;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class containing utilities for the GUI subsystem.
 *
 * @author Mica Technologies
 * @version 2.0
 * @since 1.0
 */
public class GUIUtilities
{
    /**
     * Show a question message prompt to user as a dialog using specified information.
     *
     * @param title       dialog title
     * @param headerText  dialog header
     * @param contentText dialog content/question text
     * @param button1     button 1 text
     * @param button2     button 2 text
     *
     * @return 0 for cancel, 1 for button 1, 2 for button 2
     *
     * @since 1.0
     */
    public static int showQuestionMessage( String title, String headerText, String contentText, String button1,
                                           String button2, Stage owner )
    {
        // Create a question dialog with the specified and created information/messages
        CountDownLatch waitForResponse = new CountDownLatch( 1 );
        AtomicInteger index = new AtomicInteger( 0 );
        JFXPlatformRun( () -> {
            Alert questionAlert = new Alert( Alert.AlertType.CONFIRMATION );
            questionAlert.setTitle( title );
            questionAlert.setHeaderText( headerText );
            questionAlert.setContentText( contentText );
            questionAlert.initStyle( StageStyle.UTILITY );
            questionAlert.initModality( Modality.WINDOW_MODAL );
            questionAlert.initOwner( owner );

            ButtonType btn1 = new ButtonType( button1 );
            ButtonType btn2 = new ButtonType( button2 );
            ButtonType btnC = new ButtonType( "Cancel", ButtonBar.ButtonData.CANCEL_CLOSE );

            questionAlert.getButtonTypes().setAll( btn1, btn2, btnC );

            // Show the created question dialog
            Optional< ButtonType > opt = questionAlert.showAndWait();
            if ( opt.isPresent() && opt.get() == btn1 ) {
                index.set( 1 );
            }
            else if ( opt.isPresent() && opt.get() == btn2 ) {
                index.set( 2 );
            }

            // Release code from waiting
            waitForResponse.countDown();
        } );

        // Wait for question to be acknowledged
        try {
            waitForResponse.await();
        }
        catch ( InterruptedException e ) {
            // Show error for unable to wait for error acknowledge
            JFXPlatformRun( () -> {
                Alert errorAlert = new Alert( Alert.AlertType.ERROR );
                errorAlert.setTitle( "Something's Wrong" );
                errorAlert.setHeaderText( "Application Error" );
                errorAlert.setContentText(
                        "A question message latch was interrupted before handling completed." + "\n" +
                                "Client Token: " + AuthManager.getClientToken() );
                errorAlert.initStyle( StageStyle.UTILITY );
                errorAlert.initModality( Modality.WINDOW_MODAL );
                errorAlert.initOwner( owner );

                // Show the created error
                errorAlert.showAndWait();
            } );
        }

        return index.get();
    }

    /**
     * Show an error message dialog to user with specified information.
     *
     * @param contentText dialog content/error text
     *
     * @since 1.0
     */
    public static void showErrorMessage( String contentText, Stage owner ) {
        // Create an error with the specified and created information/messages
        CountDownLatch waitForError = new CountDownLatch( 1 );
        JFXPlatformRun( () -> {
            Alert errorAlert = new Alert( Alert.AlertType.ERROR );
            errorAlert.setTitle( "Oops" );
            errorAlert.setHeaderText( "Error" );
            errorAlert.setContentText( contentText + "\nClient Token: " + AuthManager.getClientToken() );
            errorAlert.initModality( Modality.WINDOW_MODAL );
            errorAlert.initStyle( StageStyle.UTILITY );
            errorAlert.initOwner( owner );

            // Show the created error
            errorAlert.showAndWait();

            // Release code from waiting
            waitForError.countDown();
        } );

        // Wait for error to be acknowledged
        try {
            waitForError.await();
        }
        catch ( InterruptedException e ) {
            // Show error for unable to wait for error acknowledge
            JFXPlatformRun( () -> {
                Alert errorAlert = new Alert( Alert.AlertType.ERROR );
                errorAlert.setTitle( "Something's Wrong" );
                errorAlert.setHeaderText( "Application Error" );
                errorAlert.setContentText(
                        "An error message latch was interrupted before handling completed." + "\n" + "Client Token: " +
                                AuthManager.getClientToken() );
                errorAlert.initModality( Modality.WINDOW_MODAL );
                errorAlert.initStyle( StageStyle.UTILITY );
                errorAlert.initOwner( owner );

                // Show the created error
                errorAlert.showAndWait();
            } );
        }
    }

    /**
     * Show a warning message dialog to user with specified information.
     *
     * @param contentText dialog content/warning text
     *
     * @since 1.0
     */
    public static void showWarningMessage( String contentText, Stage owner ) {
        // Create a warning with the specified and created information/messages
        CountDownLatch waitForWarning = new CountDownLatch( 1 );
        JFXPlatformRun( () -> {
            Alert warningAlert = new Alert( Alert.AlertType.WARNING );
            warningAlert.setTitle( "Warning" );
            warningAlert.setHeaderText( "Warning" );
            warningAlert.setContentText( contentText );
            warningAlert.initModality( Modality.WINDOW_MODAL );
            warningAlert.initStyle( StageStyle.UTILITY );
            warningAlert.initOwner( owner );

            // Show the created error
            warningAlert.showAndWait();

            // Release code from waiting
            waitForWarning.countDown();
        } );

        // Wait for error to be acknowledged
        try {
            waitForWarning.await();
        }
        catch ( InterruptedException e ) {
            // Show error for unable to wait for error acknowledge
            JFXPlatformRun( () -> {
                Alert warningAlert = new Alert( Alert.AlertType.ERROR );
                warningAlert.setTitle( "Something's Wrong" );
                warningAlert.setHeaderText( "Application Error" );
                warningAlert.setContentText(
                        "A warning message latch was interrupted before handling completed." + "\n" + "Client Token: " +
                                AuthManager.getClientToken() );
                warningAlert.initModality( Modality.WINDOW_MODAL );
                warningAlert.initStyle( StageStyle.UTILITY );
                warningAlert.initOwner( owner );

                // Show the created error
                warningAlert.showAndWait();
            } );
        }
    }

    /**
     * Executes the specified runnable on the JavaFX thread and waits for its completion.
     *
     * @param r runnable task
     *
     * @since 1.1
     */
    public static void JFXPlatformRun( Runnable r ) {
        // If currently on JavaFX thread, run the runnable
        if ( Platform.isFxApplicationThread() ) {
            r.run();
        }
        else {
            // Create latch for identifying completion of runnable
            final CountDownLatch doneLatch = new CountDownLatch( 1 );

            // Schedule runnable for execution on JavaFX thread
            try {
                Platform.runLater( () -> {
                    try {
                        r.run();
                    }
                    finally {
                        doneLatch.countDown();
                    }
                } );
            }
            catch ( Exception e ) {
                Platform.startup( () -> {
                    try {
                        r.run();
                    }
                    finally {
                        doneLatch.countDown();
                    }
                } );
            }

            // Wait for completion of runnable on JavaFX thread
            try {
                doneLatch.await();
            }
            catch ( InterruptedException e ) {
                Logger.logError( "Unable to wait for a user interface task to complete!" );
            }
        }
    }
}

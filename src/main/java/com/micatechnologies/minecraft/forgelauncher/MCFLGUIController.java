package com.micatechnologies.minecraft.forgelauncher;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.Region;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class for managing and controlling GUI components and related functionality.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class MCFLGUIController {
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
    static int showQuestionMessage( String title, String headerText, String contentText, String button1, String button2, Stage owner ) {
        // Create a question dialog with the specified and created information/messages
        CountDownLatch waitForResponse = new CountDownLatch( 1 );
        AtomicInteger index = new AtomicInteger( 0 );
        Platform.runLater( () -> {
            Alert questionAlert = new Alert( Alert.AlertType.CONFIRMATION );
            questionAlert.setTitle( title );
            questionAlert.setHeaderText( headerText );
            questionAlert.setContentText( contentText );
            questionAlert.initOwner( owner );
            questionAlert.initModality( Modality.WINDOW_MODAL );

            ButtonType btn1 = new ButtonType( button1 );
            ButtonType btn2 = new ButtonType( button2 );
            ButtonType btnC = new ButtonType( "Cancel", ButtonBar.ButtonData.CANCEL_CLOSE );

            questionAlert.getButtonTypes().setAll( btn1, btn2, btnC );

            // Show the created question dialog
            Optional< ButtonType > opt = questionAlert.showAndWait();
            if ( opt.isPresent() && opt.get() == btn1 ) index.set( 1 );
            else if ( opt.isPresent() && opt.get() == btn2 ) index.set( 2 );

            // Release code from waiting
            waitForResponse.countDown();
        } );

        // Wait for question to be acknowledged
        try {
            waitForResponse.await();
        }
        catch ( InterruptedException e ) {
            // Show error for unable to wait for error acknowledge
            Platform.runLater( () -> {
                Alert errorAlert = new Alert( Alert.AlertType.ERROR );
                errorAlert.setTitle( "Something's Wrong" );
                errorAlert.setHeaderText( "Application Error" );
                errorAlert.setContentText( "A question message latch was interrupted before handling completed." + "\n" + "Client Token: " + MCFLApp.getClientToken() );
                errorAlert.initOwner( owner );
                errorAlert.initModality( Modality.WINDOW_MODAL );

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
     * @param errorID     error ID
     *
     * @since 1.0
     */
    static void showErrorMessage( String contentText, String errorID, Stage owner ) {
        // Create an error with the specified and created information/messages
        CountDownLatch waitForError = new CountDownLatch( 1 );
        try {
            Platform.runLater( () -> {
                Alert errorAlert = new Alert( Alert.AlertType.ERROR );
                errorAlert.setTitle( "Oops" );
                errorAlert.setHeaderText( "Error" );
                errorAlert.setContentText( contentText + "\nError Code: " + errorID + "\n" + "Client Token: " + MCFLApp.getClientToken() );
                errorAlert.initOwner( owner );
                errorAlert.initModality( Modality.WINDOW_MODAL );

                // Show the created error
                errorAlert.showAndWait();

                // Release code from waiting
                waitForError.countDown();
            } );
        }
        catch ( IllegalStateException e ) {
            Platform.startup( () -> {
                Alert errorAlert = new Alert( Alert.AlertType.ERROR );
                errorAlert.setTitle( "Oops" );
                errorAlert.setHeaderText( "Error" );
                errorAlert.setContentText( contentText + "\nError Code: " + errorID + "\n" + "Client Token: " + MCFLApp.getClientToken() );
                errorAlert.initOwner( owner );
                errorAlert.initModality( Modality.WINDOW_MODAL );

                // Show the created error
                errorAlert.showAndWait();

                // Release code from waiting
                waitForError.countDown();
            } );
        }

        // Wait for error to be acknowledged
        try {
            waitForError.await();
        }
        catch ( InterruptedException e ) {
            // Show error for unable to wait for error acknowledge
            Platform.runLater( () -> {
                Alert errorAlert = new Alert( Alert.AlertType.ERROR );
                errorAlert.setTitle( "Something's Wrong" );
                errorAlert.setHeaderText( "Application Error" );
                errorAlert.setContentText( "An error message latch was interrupted before handling completed." + "\n" + "Client Token: " + MCFLApp.getClientToken() );
                errorAlert.initOwner( owner );
                errorAlert.initModality( Modality.WINDOW_MODAL );

                // Show the created error
                errorAlert.showAndWait();
            } );
        }
    }
}

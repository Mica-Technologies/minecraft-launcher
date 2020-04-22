package com.micatechnologies.minecraft.forgelauncher.utilities;

import com.micatechnologies.minecraft.forgelauncher.MCFLApp;
import com.micatechnologies.minecraft.forgelauncher.gui.GenericGUI;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class GUIUtils {
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
    public static int showQuestionMessage( String title, String headerText, String contentText, String button1, String button2, Stage owner ) {
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
            JFXPlatformRun( () -> {
                Alert errorAlert = new Alert( Alert.AlertType.ERROR );
                errorAlert.setTitle( "Something's Wrong" );
                errorAlert.setHeaderText( "Application Error" );
                errorAlert.setContentText( "A question message latch was interrupted before handling completed." + "\n" + "Client Token: " + MCFLApp.getClientToken() );
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
            errorAlert.setContentText( contentText + "\nClient Token: " + MCFLApp.getClientToken() );
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
                errorAlert.setContentText( "An error message latch was interrupted before handling completed." + "\n" + "Client Token: " + MCFLApp.getClientToken() );
                errorAlert.initModality( Modality.WINDOW_MODAL );
                errorAlert.initStyle( StageStyle.UTILITY );
                errorAlert.initOwner( owner );

                // Show the created error
                errorAlert.showAndWait();
            } );
        }
    }

    public static void JFXPlatformRun( Runnable r ) {
        JFXPlatformRun( r, false );
    }

    public static void JFXPlatformRun( Runnable r, boolean wait ) {
        // Create latch for completion
        CountDownLatch countDownLatch = new CountDownLatch( 1 );

        Runnable runnable = () -> {
            r.run();
            countDownLatch.countDown();
        };

        try {
            Platform.runLater( runnable );
        }
        catch ( Exception e ) {
            Platform.startup( runnable );
        }

        try {
            if ( wait ) countDownLatch.await();
        }
        catch ( InterruptedException e ) {
            e.printStackTrace();
            Logger.logError( "Unable to wait for JavaFX platform runnable to finish!" );
        }
    }

    public static FXMLLoader buildFXMLLoader( String fxmlFileName, GenericGUI owner ) {
        FXMLLoader fxmlLoader = new FXMLLoader();
        fxmlLoader.setLocation( GUIUtils.class.getClassLoader().getResource( fxmlFileName ) );
        fxmlLoader.setController( owner );
        return fxmlLoader;
    }
}

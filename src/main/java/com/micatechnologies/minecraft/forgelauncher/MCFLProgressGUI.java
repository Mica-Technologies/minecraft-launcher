package com.micatechnologies.minecraft.forgelauncher;

import com.jfoenix.controls.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;


/**
 * GUI for launcher progress tracking. Shows progress bar and image
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class MCFLProgressGUI extends MCFLGenericGUI {

    /**
     * Upper line of text on GUI
     */
    @FXML
    public Label upperText;

    /**
     * Lower line of text on GUI
     */
    @FXML
    public Label lowerText;

    /**
     * Progress bar on GUI
     */
    @FXML
    public JFXProgressBar progressBar;

    /**
     * Image icon on GUI
     */
    @FXML
    public ImageView userIcon;


    /**
     * Initialize the upper text, lower text and progress bar of GUI.
     *
     * @since 1.0
     */
    @Override
    void create() {
        setProgress( JFXProgressBar.INDETERMINATE_PROGRESS );
        setLowerText( "Awaiting progress information..." );
        setUpperText( "Loading!" );
    }

    /**
     * Set the upper text string message
     *
     * @param text text to set
     *
     * @since 1.0
     */
    void setUpperText( String text ) {
        Platform.runLater( () -> upperText.setText( text ) );
    }

    /**
     * Set the lower text string message
     *
     * @param text text to set
     *
     * @since 1.0
     */
    void setLowerText( String text ) {
        Platform.runLater( () -> lowerText.setText( text ) );
    }

    /**
     * Set the progress bar amount
     *
     * @param progress amount of progress
     *
     * @since 1.0
     */
    void setProgress( double progress ) {
        Platform.runLater( () -> progressBar.setProgress( progress ) );
    }

    /**
     * Set the GUI icon image
     *
     * @param img image
     *
     * @since 1.0
     */
    void setIcon( Image img ) {
        userIcon.setImage( img );
    }

    /**
     * Create the FXMLLoader for showing the JavaFX stage
     *
     * @return created FXMLLoader
     *
     * @since 1.0
     */
    @Override
    FXMLLoader getFXMLLoader() {
        FXMLLoader fxmll = new FXMLLoader();
        fxmll.setLocation( getClass().getClassLoader().getResource( "LauncherProgressGUI.fxml" ) );
        fxmll.setController( this );
        return fxmll;
    }

    /**
     * Get the width and height of the JavaFX stage
     *
     * @return [width, height] of JavaFX stage
     *
     * @since 1.0
     */
    @Override
    int[] getSize() {
        return new int[]{ 500, 500 };
    }

    public static void main( String[] args ) {
        MCFLProgressGUI mpg = new MCFLProgressGUI();
        mpg.open();
    }
}

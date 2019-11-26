package com.micatechnologies.minecraft.forgelauncher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;

/**
 * Abstract GUI class for handling common functions of launcher GUIs.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public abstract class MCFLGenericGUI extends Application implements Initializable {

    /**
     * Current JavaFX stage (null if none).
     */
    private Stage currentStage = null;

    /**
     * Ready latch for GUI. Counts down to 0 when GUI is fully initialized and visible.
     */
    CountDownLatch readyLatch = new CountDownLatch( 1 );

    /**
     * Get the current JavaFX stage. Returns null if no JavaFX stage.
     *
     * @return current JavaFX stage
     *
     * @since 1.0
     */
    public Stage getCurrentStage() {
        try {
            readyLatch.await();
        }
        catch ( InterruptedException ignored ) {
        }
        return currentStage;
    }

    /**
     * Create and setup controls, elements, etc of the GUI.
     *
     * @since 1.0
     */
    abstract void create();

    /**
     * Create and return an FXMLLoader with the controller configured to {@link this}
     *
     * @return created FXMLLoader
     *
     * @since 1.0
     */
    abstract FXMLLoader getFXMLLoader();

    /**
     * Return GUI/stage size as [width, height]
     *
     * @return GUI/stage size
     *
     * @since 1.0
     */
    abstract int[] getSize();

    /**
     * Show the GUI if stage is set/ready
     *
     * @since 1.0
     */
    public void show() {
        // Run on JavaFX Application Thread
        Platform.runLater( () -> {
            // Verify stage exists
            if ( currentStage != null ) {
                // Verify stage/GUI is ready
                try {
                    if ( readyLatch.getCount() > 0 ) readyLatch.await();
                }
                catch ( InterruptedException ignored ) {
                }

                // Show stage/GUI
                currentStage.show();
            }
        } );
    }

    /**
     * Hide the GUI if stage is set/ready
     *
     * @since 1.0
     */
    public void hide() {
        // Run on JavaFX Application Thread
        Platform.runLater( () -> {
            // Verify stage exists
            if ( currentStage != null ) {
                // Verify stage/GUI is ready
                try {
                    if ( readyLatch.getCount() > 0 ) readyLatch.await();
                }
                catch ( InterruptedException ignored ) {
                }

                // Hide stage/GUI
                currentStage.hide();
            }
        } );
    }

    /**
     * Close the GUI if stage is set/ready
     *
     * @since 1.0
     */
    public void close() {
        // Run on JavaFX Application Thread
        Platform.runLater( () -> {
            // Verify stage exists
            if ( currentStage != null ) {
                // Verify stage/GUI is ready
                try {
                    if ( readyLatch.getCount() > 0 ) readyLatch.await();
                }
                catch ( InterruptedException ignored ) {
                }

                // Close stage/GUI
                currentStage.close();
            }
        } );
    }

    /**
     * JavaFX start method for main window setup
     *
     * @param primaryStage stage for showing window
     *
     * @throws Exception if unable to create/show window
     * @since 1.0
     */
    @Override
    public void start( Stage primaryStage ) throws Exception {
        // Configure scene and window
        primaryStage.setTitle( MCFLConstants.LAUNCHER_APPLICATION_NAME );
        primaryStage.setScene( new Scene( getFXMLLoader().load(), getSize()[ 0 ], getSize()[ 1 ] ) );
        primaryStage.initStyle( StageStyle.UNIFIED );
        currentStage = primaryStage;

        // Run specific window creation code
        create();

        // Mark window as ready
        readyLatch.countDown();

        // Show window
        show();
    }

    /**
     * JavaFX initialize method for main window setup. Ignored
     *
     * @param location  ignored
     * @param resources ignored
     *
     * @since 1.0
     */
    @Override
    public void initialize( URL location, ResourceBundle resources ) {

    }

    /**
     * Open the GUI for the first time. (Subsequent should use hide()/show())
     *
     * @since 1.0
     */
    public void open() {
        // Try to open GUI with platform startup (startup platform, platform cannot be started)
        try {
            Platform.startup( () -> {
                try {
                    start( new Stage() );
                }
                catch ( Exception e ) {
                    // Show error and allow JavaFX to close
                    MCFLLogger.error( "Unable to create application user interface.", 100, getCurrentStage());
                    Platform.setImplicitExit( true );
                }
            } );
        }
        // Open GUI with platform runLater (platform must be running, startup already called)
        catch ( IllegalStateException ignored ) {
            Platform.runLater( () -> {
                try {
                    start( new Stage() );
                }
                catch ( Exception e ) {
                    // Show error and allow JavaFX to close
                    MCFLLogger.error( "Unable to create application user interface.", 101,getCurrentStage() );
                    Platform.setImplicitExit( true );
                }
            } );
        }
    }

}

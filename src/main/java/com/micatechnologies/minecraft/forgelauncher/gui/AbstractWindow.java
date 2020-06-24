package com.micatechnologies.minecraft.forgelauncher.gui;

import com.micatechnologies.jadapt.NSWindow;
import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.utilities.GuiUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.LogUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.OnScreen;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.Pair;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import com.sun.glass.ui.Window;
import org.apache.commons.lang3.SystemUtils;
import org.rococoa.ID;
import org.rococoa.Rococoa;
import org.rococoa.cocoa.foundation.NSUInteger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * Abstract window class containing common functionality and features that should be implemented for all windows in the
 * application. This abstract window is the super class of all windows in the application.
 *
 * @author Mica Technologies
 * @version 3.0
 * @editors hawka97
 * @creator hawka97
 * @since 2.0
 */
public abstract class AbstractWindow extends Application
{
    /**
     * Current window JavaFX stage.
     *
     * @since 1.0
     */
    Stage currentJFXStage = null;

    /**
     * Latch that counts down to 0 when the window has been closed.
     *
     * @since 2.0
     */
    public final CountDownLatch closedLatch = new CountDownLatch( 1 );

    /**
     * Latch that counts down to 0 when the window is ready.
     *
     * @since 2.0
     */
    public final CountDownLatch readyLatch = new CountDownLatch( 1 );

    /**
     * Root pane of the window
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    Pane rootPane;

    /**
     * Center pane of the window
     *
     * @since 3.0
     */
    @FXML
    @OnScreen
    Pane centerPane;

    /**
     * Performs the setup and startup of the window and its stage.
     *
     * @param stage window stage
     *
     * @throws Exception if unable to initialize JavaFX stage
     * @since 1.0
     */
    @Override
    public void start( Stage stage ) throws Exception {
        // Create JavaFX scene and apply to stage
        stage.setScene( new Scene( GuiUtils.buildFXMLLoader( getFXMLResourcePath(), this ).load() ) );

        // Set window size
        stage.setMinWidth( getWindowSize()._1 );
        stage.setMinHeight( getWindowSize()._2 );
        stage.setWidth( getWindowSize()._1 );
        stage.setHeight( getWindowSize()._2 );
        stage.initStyle( StageStyle.UNIFIED );

        // Set window title and icon
        stage.setTitle( LauncherConstants.LAUNCHER_APPLICATION_NAME );
        InputStream iconStream = getClass().getClassLoader().getResourceAsStream( "micaforgelauncher.png" );
        if ( iconStream != null ) {
            stage.getIcons().add( new Image( iconStream ) );
        }

        // Set window closing handler
        stage.setOnCloseRequest( windowEvent -> close() );

        // Prepare GUI effects
        prepareGUIEffects();

        // Store stage
        currentJFXStage = stage;

        // Run window-specific creation code
        setupWindow();

        // Register window with controller
        GUIController.registerWindow( this );

        // Count down ready latch
        readyLatch.countDown();
    }

    /**
     * Prepares special or add-on effects that apply to all windows.
     *
     * @since 2.1
     */
    private void prepareGUIEffects() {
        /*
          NOTE: This method does not prepare any GUI effects
          because there are no desired GUI effects at this time.
         */
    }

    /**
     * Abstract method that must be implemented by the inheriting class and return the file path of the window's FXML
     * definition.
     *
     * @return window FXML resource path
     *
     * @since 2.0
     */
    abstract String getFXMLResourcePath();

    /**
     * Abstract method that must be implemented by the inheriting class and return the desired and initial size of the
     * window.
     *
     * @return window size as pair
     *
     * @since 1.1
     */
    abstract Pair< Integer, Integer > getWindowSize();

    /**
     * Abstract method that must be implemented by the inheriting class and include instance-specific window setup
     * instructions.
     *
     * @since 1.1
     */
    abstract void setupWindow();

    /**
     * Hides the window.
     *
     * @since 1.0
     */
    public void hide() {
        Platform.runLater( currentJFXStage::hide );
    }

    /**
     * Shows the window. This method also performs tasks related to synchronized window locations and window
     * configuration.
     *
     * @since 1.0
     */
    public void show() {
        // Perform setup if not done
        internPrepWindow();

        // Change window location
        Pair< Double, Double > customWindowLocation = GUIController.getCustomWindowLocation();
        if ( customWindowLocation._1 != -1 && customWindowLocation._2 != -1 ) {
            currentJFXStage.setX( customWindowLocation._1 );
            currentJFXStage.setY( customWindowLocation._2 );
        }

        // Set listener for window location changes
        ChangeListener< Number > windowMoveListener = ( observableValue, number, t1 ) -> GUIController
                .setCustomWindowLocations( currentJFXStage.getX(), currentJFXStage.getY() );

        currentJFXStage.xProperty().addListener( windowMoveListener );
        currentJFXStage.yProperty().addListener( windowMoveListener );

        // Show window
        GuiUtils.JFXPlatformRun( () -> {
            // Show stage
            currentJFXStage.show();

            // Style window for macOS
            if ( SystemUtils.IS_OS_MAC ) {
                styleMacWindow();
            }
        } );

        // Force window changes apply
        GUIController.refreshWindowConfiguration();

        // Wait for window ready
        try {
            readyLatch.await();
        }
        catch ( InterruptedException e ) {
            e.printStackTrace();
        }

    }

    /**
     * Gets the current JavaFX stage of this window.
     *
     * @return window stage
     */
    public Stage getCurrentJFXStage() {
        return currentJFXStage;
    }

    /**
     * Shows the window (same as standard show()) and blocks on the window closed latch. This method will block until
     * the window is closed.
     *
     * @throws InterruptedException if unable to wait for window to close
     * @since 2.2
     */
    public void showAndWait() throws InterruptedException {
        // Perform setup if not done
        internPrepWindow();

        // Show window
        GuiUtils.JFXPlatformRun(
                () -> {
                    // Show stage
                    currentJFXStage.show();

                    // Style window for macOS
                    if ( SystemUtils.IS_OS_MAC ) {
                        styleMacWindow();
                    }
                } );

        // Wait for window to close
        closedLatch.await();
    }

    /**
     * Closes the window.
     *
     * @since 2.0
     */
    public void close() {
        // Close window
        GuiUtils.JFXPlatformRun( currentJFXStage::close );

        // Unregister window from controller
        GUIController.unregisterWindow( this );

        // Countdown closed latch
        closedLatch.countDown();
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
        URL url = this.getClass().getClassLoader().getResource( "darwin/librococoa.dylib" );
        if ( url != null ) {
            System.load( url.getPath() );
        }
        else {
            LogUtils.logDebug( "Unable to load rococoa library for macOS window styling!" );
        }

        // Wrap window as NSWindow and return
        return Rococoa.wrap( ID.fromLong( getWindowHandle() ), NSWindow.class );
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
                    new NSUInteger(
                            thisWindow.styleMask().intValue() | NSWindow.StyleMaskFullSizeContentView ) );
        }
        catch ( Exception e ) {
            e.printStackTrace();
            LogUtils.logDebug(
                    "An error occurred while performing style modifications to an NSWindow wrapper." );
        }
    }

    /**
     * Internal method used to start the window and block until the window is ready.
     *
     * @since 2.0
     */
    private void internPrepWindow() {
        if ( readyLatch.getCount() > 0 ) {
            GuiUtils.JFXPlatformRun( () -> {
                try {
                    start( new Stage() );
                }
                catch ( Exception e ) {
                    LogUtils.logError( "An error occurred while creating a window!" );
                }
            } );
            try {
                readyLatch.await();
            }
            catch ( InterruptedException e ) {
                LogUtils.logError( "An error occurred while waiting for window creation!" );
            }
        }
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
            if ( Objects.equals( w.getTitle(), currentJFXStage.getTitle() )
                    && w.getHeight() == currentJFXStage.getHeight()
                    && w.getWidth() == currentJFXStage.getWidth()
                    && w.getX() == currentJFXStage.getX()
                    && w.getY() == currentJFXStage.getY() ) {
                return w.getNativeHandle();
            }
        }

        // If unable to find window, return window 0
        return Window.getWindows().get( 0 ).getNativeHandle();
    }
}
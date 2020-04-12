package com.micatechnologies.minecraft.forgelauncher.gui;

import com.micatechnologies.jadapt.NSWindow;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLGUIUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLLogUtil;
import com.micatechnologies.minecraft.forgelauncher.utilities.Pair;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import com.sun.glass.ui.Window;
import org.apache.commons.lang3.SystemUtils;
import org.rococoa.ID;
import org.rococoa.Rococoa;
import org.rococoa.cocoa.foundation.NSUInteger;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public abstract class FLGenericGUI extends Application {
    Stage currentJFXStage = null;
    public final CountDownLatch closedLatch = new CountDownLatch( 1 );
    private final CountDownLatch readyLatch = new CountDownLatch( 1 );

    @FXML
    Pane rootPane;

    @FXML
    Pane centerPane;

    @Override
    public void start( Stage stage ) throws Exception {
        // Create JavaFX scene and apply to stage
        FXMLLoader fxmlLoader =
                new FXMLLoader( getClass().getClassLoader().getResource( getFXMLResourcePath() ) );
        fxmlLoader.setController( this );
        stage.setScene( new Scene( fxmlLoader.load() ) );

        // Set window size
        stage.setMinWidth( getWindowSize().fst );
        stage.setMinHeight( getWindowSize().snd );
        stage.setWidth( getWindowSize().fst );
        stage.setHeight( getWindowSize().snd );
        stage.initStyle( StageStyle.UNIFIED );

        // Set window closing handler
        stage.setOnCloseRequest( windowEvent -> close() );

        // Apply frost effect to center pane

        // Store stage
        currentJFXStage = stage;

        // Run window-specific creation code
        setupWindow();

        // Register window with controller
        FLGUIController.registerWindow( this );

        // Count down ready latch
        readyLatch.countDown();
    }

    abstract String getFXMLResourcePath();

    abstract Pair< Integer, Integer > getWindowSize();

    abstract void setupWindow();

    public void hide() {
        Platform.runLater( currentJFXStage::hide );
    }

    public void show() {
        // Perform setup if not done
        internPrepWindow();

        // Show window
        FLGUIUtils.JFXPlatformRun(
                () -> {
                    // Show stage
                    currentJFXStage.show();

                    // Style window for macOS
                    if ( SystemUtils.IS_OS_MAC ) {
                        styleMacWindow();
                    }
                } );

        // Wait for window ready
        try {
            readyLatch.await();
        }
        catch ( InterruptedException e ) {
            e.printStackTrace();
        }
    }

    public Stage getCurrentJFXStage() {
        return currentJFXStage;
    }

    public void showAndWait() throws InterruptedException {
        // Perform setup if not done
        internPrepWindow();

        // Show window
        FLGUIUtils.JFXPlatformRun(
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

    public void close() {
        // Close window
        FLGUIUtils.JFXPlatformRun( currentJFXStage::close );

        // Unregister window from controller
        FLGUIController.unregisterWindow( this );

        // Countdown closed latch
        closedLatch.countDown();
    }

    private void styleMacWindow() {
        try {
            // Load rococoa library
            System.load( this.getClass().getResource( "darwin/librococoa.dylib" ).getPath() );

            // Wrap window as NSWindow
            NSWindow thisWindow = Rococoa.wrap( ID.fromLong( getWindowHandle() ), NSWindow.class );

            // Perform styling
            thisWindow.setTitlebarAppearsTransparent( true );
            thisWindow.setStyleMask(
                    new NSUInteger(
                            thisWindow.styleMask().intValue() | NSWindow.StyleMaskFullSizeContentView ) );
        }
        catch ( Exception e ) {
            FLLogUtil.debug(
                    "An error occurred while performing style modifications to an NSWindow wrapper." );
        }
    }

    private void internPrepWindow() {
        if ( readyLatch.getCount() > 0 ) {
            FLGUIUtils.JFXPlatformRun( () -> {
                try {
                    start( new Stage() );
                }
                catch ( Exception e ) {
                    FLLogUtil.error( "An error occurred while creating a window!", 3003, null );
                }
            } );
            try {
                readyLatch.await();
            }
            catch ( InterruptedException e ) {
                FLLogUtil.error( "An error occurred while waiting for window creation!", 3004, null );
            }
        }
    }

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
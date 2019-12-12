package com.micatechnologies.minecraft.forgelauncher;

import com.micatechnologies.jadapt.NSWindow;
import com.micatechnologies.minecraft.forgemodpacklib.MCModpackOSUtils;
import com.sun.glass.ui.Window;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.apache.commons.io.FileUtils;
import org.eclipse.swt.internal.cocoa.NSString;
import org.rococoa.ID;
import org.rococoa.Rococoa;
import org.rococoa.cocoa.foundation.NSUInteger;

import javax.swing.text.View;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
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
     * Finsihed latch for GUI. Counts down to 0 when GUI is closed.
     */
    public CountDownLatch closedLatch = new CountDownLatch( 1 );

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
    abstract void create( Stage stage );

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
     * Perform the functions necessary to enable light mode
     */
    abstract void enableLightMode();

    /**
     * Perform the functions necessary to enable dark mode
     */
    abstract void enableDarkMode();

    void doLightMode() {
        Platform.runLater( () -> {
            if ( currentStage != null ) {
                getCurrentStage().getScene().getStylesheets().add( getClass().getClassLoader().getResource( "LauncherLight.css" ).toExternalForm() );
                getCurrentStage().getScene().getStylesheets().remove( getClass().getClassLoader().getResource( "LauncherDark.css" ).toExternalForm() );
                enableLightMode();
            }
        } );
    }

    void doDarkMode() {
        Platform.runLater( () -> {
            if ( currentStage != null ) {
                currentStage.getScene().getStylesheets().remove( getClass().getClassLoader().getResource( "LauncherLight.css" ).toExternalForm() );
                currentStage.getScene().getStylesheets().add( getClass().getClassLoader().getResource( "LauncherDark.css" ).toExternalForm() );
                enableDarkMode();
            }
        } );
    }

    abstract Pane getRootPane();

    boolean styleThreadRun = true;

    void createUIStyleListenThread() {
        new Thread( () -> {
            //1 for light, 2 for dark
            int lastMode = 0;
            while ( styleThreadRun ) {
                if ( MCModpackOSUtils.isWindows() ) {
                    // TODO: Figure out how to detect windows (10) dark mode
                    /*if ( dark ) {
                        if ( lastMode == 1 ) enableDarkMode();
                        lastMode = 2;
                    }
                    else {
                        if ( lastMode == 2 ) doLightMode();
                        lastMode = 1;
                    }*/
                    return;
                }
                else if ( MCModpackOSUtils.isMac() ) {
                    try {
                        Process process = Runtime.getRuntime().exec( "defaults read -g AppleInterfaceStyle" );
                        BufferedReader bufferedReader = new BufferedReader( new InputStreamReader( process.getInputStream() ) );
                        String read = bufferedReader.readLine().toLowerCase();
                        if ( read.contains( "dark" ) ) {
                            if ( lastMode != 2 ) {
                                doDarkMode();
                                lastMode = 2;
                            }
                        }
                        else {
                            if ( lastMode != 1 ) {
                                doLightMode();
                                lastMode = 1;
                            }
                        }
                    }
                    // Light mode causes an exception
                    catch ( Exception ignored ) {
                        if ( lastMode != 1 ) {
                            doLightMode();
                            lastMode = 1;
                        }
                    }
                }

                // Check for window sizable config option
                Platform.runLater( () -> {
                    Stage currStage = getCurrentStage();
                    if ( currStage != null ) {
                        NSWindow nativeWindow = getNativeMacWindow();
                        if ( currStage.isShowing() && nativeWindow != null ) {
                            int styleMask = nativeWindow.styleMask().intValue();
                            if ( MCFLApp.getLauncherConfig().getResizableguis() )
                                styleMask |= NSWindow.StyleMaskResizable;
                            else styleMask &= ~NSWindow.StyleMaskResizable;
                            nativeWindow.setStyleMask( new NSUInteger( styleMask ) );
                        }
                        currStage.setResizable( MCFLApp.getLauncherConfig().getResizableguis() );
                    }
                } );

                doMacUnifiedTitleBar();

                // Check for light/dark mode again in 3s
                try {
                    Thread.sleep( 3000 );
                }
                catch ( InterruptedException ignored ) {

                }
            }
        } ).start();
    }

    private boolean isMacUnifiedWindowSet = false;

    private double xOffset;
    private double yOffset;

    private void doMacUnifiedTitleBar() {
        if ( MCModpackOSUtils.isMac() && !isMacUnifiedWindowSet ) {
            Platform.runLater( () -> {
                // Mac specific and client mode only
                if ( getNativeMacWindow() != null ) {
                    try {
                        NSWindow nsWindow = getNativeMacWindow();
                        if ( MCFLApp.getLauncherConfig().getResizableguis() )
                            nsWindow.setStyleMask( new NSUInteger( NSWindow.StyleMaskClosable | NSWindow.StyleMaskTitled | NSWindow.StyleMaskResizable | NSWindow.StyleMaskFullSizeContentView ) );
                        else
                            nsWindow.setStyleMask( new NSUInteger( NSWindow.StyleMaskClosable | NSWindow.StyleMaskTitled | NSWindow.StyleMaskFullSizeContentView ) );

                        nsWindow.setTitlebarAppearsTransparent( true );
                        nsWindow.setMovable( true );
                        nsWindow.setMovableByWindowBackground( true );

                        getRootPane().setOnMousePressed( event -> {
                            xOffset = event.getSceneX();
                            yOffset = event.getSceneY();
                        } );

                        getRootPane().setOnMouseDragged( event -> {
                            getCurrentStage().setX( event.getScreenX() - xOffset );
                            getCurrentStage().setY( event.getScreenY() - yOffset );
                        } );
                    }
                    catch ( Exception e ) {
                        e.printStackTrace();
                    }
                    isMacUnifiedWindowSet = true;
                }
            } );
        }
    }

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
                isMacUnifiedWindowSet = false;
                currentStage.show();
                currentStage.setOpacity( 1.0 );
                currentStage.toFront();
                currentStage.requestFocus();
                currentStage.setResizable( MCFLApp.getLauncherConfig().getResizableguis() );
                doMacUnifiedTitleBar();
            }
        } );
    }

    public NSWindow getNativeMacWindow() {
        if ( !MCModpackOSUtils.isMac() || !getCurrentStage().isShowing() ) return null;
        try {

            Window window = Window.getWindows().get( 0 );
            return Rococoa.wrap( ID.fromLong( window.getNativeWindow() ), NSWindow.class );
        }
        catch ( Exception e ) {
            e.printStackTrace();
            MCFLLogger.error( "Mac NSWindow class access error.", -100, null );
            return null;
        }
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
                currentStage.setFullScreen( false );
                if ( getNativeMacWindow() != null ) {
                    if ( ( getNativeMacWindow().styleMask().intValue() & NSWindow.StyleMaskFullScreen ) != 0 ) {
                        getNativeMacWindow().toggleFullScreen();
                    }
                }
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

                // Close style thread if running
                styleThreadRun = false;

                // Cleanup fullscreen
                currentStage.setFullScreen( false );
                if ( getNativeMacWindow() != null ) {
                    if ( ( getNativeMacWindow().styleMask().intValue() & NSWindow.StyleMaskFullScreen ) != 0 ) {
                        getNativeMacWindow().toggleFullScreen();
                    }
                }

                // Close stage/GUI
                closedLatch.countDown();
                currentStage.close();
                currentStage = null;
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
        primaryStage.setTitle( "" );
        primaryStage.setScene( new Scene( getFXMLLoader().load(), getSize()[ 0 ], getSize()[ 1 ] ) );
        if ( MCModpackOSUtils.isMac() ) primaryStage.initStyle( StageStyle.UNIFIED );
        primaryStage.setOnShown( event -> primaryStage.requestFocus() );
        primaryStage.setMinWidth( getSize()[ 0 ] );
        primaryStage.setMinHeight( getSize()[ 1 ] );
        primaryStage.setWidth( getSize()[ 0 ] );
        primaryStage.setHeight( getSize()[ 1 ] );
        primaryStage.setOpacity( 0.0 );
        currentStage = primaryStage;

        // Run specific window creation code
        create( currentStage );
        createUIStyleListenThread();

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
                    MCFLLogger.error( "Unable to create application user interface.", 100, getCurrentStage() );
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
                    MCFLLogger.error( "Unable to create application user interface.", 101, getCurrentStage() );
                    Platform.setImplicitExit( true );
                }
            } );
        }
    }

}

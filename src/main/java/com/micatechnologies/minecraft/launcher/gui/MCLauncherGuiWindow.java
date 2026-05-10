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

import com.jthemedetecor.OsThemeDetector;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.beans.value.ChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.geometry.VPos;
import javafx.scene.image.Image;
import javafx.scene.layout.GridPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.InputStream;
import java.util.Objects;

public class MCLauncherGuiWindow extends Application
{

    private static final double                PREF_WIDTH  = 1000.0;
    private static final double                PREF_HEIGHT = 800.0;
    private static final double                MIN_WIDTH   = 750.0;
    private static final double                MIN_HEIGHT  = 600.0;
    private              Stage                 stage;
    private              MCLauncherAbstractGui gui;

    private OsThemeDetector detector = null;
    private java.util.function.Consumer< Boolean > themeListener = null;

    /**
     * Last unmaximized window bounds. Tracked separately from the live stage because, while the stage is maximized,
     * its X/Y/width/height reflect the screen (not what we want to persist for "restore on next launch").
     */
    private double  lastNormalX      = Double.NaN;
    private double  lastNormalY      = Double.NaN;
    private double  lastNormalWidth  = Double.NaN;
    private double  lastNormalHeight = Double.NaN;
    private boolean normalBoundsSeen = false;

    /** Debounces bounds-change listeners so we don't write the config file on every pixel of a drag/resize. */
    private PauseTransition boundsSaveDebouncer = null;

    @Override
    public void start( Stage stage ) throws Exception {
        // Initialize default scene/GUI
        MCLauncherProgressGui progressGui = new MCLauncherProgressGui( stage, PREF_WIDTH, PREF_HEIGHT );

        // Save stage
        this.stage = stage;

        // Configure stage
        stage.setMinHeight( MIN_HEIGHT );
        stage.setMinWidth( MIN_WIDTH );

        // Set resizable property
        stage.setResizable( ConfigManager.getResizableWindows() );

        // Set application icon
        try ( InputStream iconStream = getClass().getClassLoader().getResourceAsStream( "micaminecraftlauncher.png" ) ) {
            if ( iconStream != null ) {
                Image icon = new Image( iconStream );
                stage.getIcons().add( icon );
            }
        }
        catch ( Exception e ) {
            Logger.logError( "An error occurred while setting the application icon!" );
            Logger.logThrowable( e );
        }

        // Setup theme detector
        try {
            detector = OsThemeDetector.getDetector();
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Unable to configure theme detector for dark/light mode!" );
            Logger.logThrowable( e );
        }

        // Restore saved window bounds (or fall back to centered default). Done before show() so the window
        // appears at its final position rather than flashing at the center first.
        boolean restored = restoreSavedBounds();

        // Set scene
        show();
        setScene( progressGui );
        if ( !restored ) {
            stage.centerOnScreen();
        }

        // Begin tracking bounds changes so we can persist them across launches.
        installBoundsPersistence();
    }

    /**
     * Reads previously saved window bounds from the config and applies them to the stage if they refer to a
     * currently-connected screen. Saved bounds with no overlapping screen (e.g. the user disconnected a monitor) are
     * discarded so the window doesn't open off-screen.
     *
     * @return true if saved bounds were applied, false if defaults should be used instead
     */
    private boolean restoreSavedBounds() {
        double savedX = ConfigManager.getWindowX();
        double savedY = ConfigManager.getWindowY();
        double savedWidth = ConfigManager.getWindowWidth();
        double savedHeight = ConfigManager.getWindowHeight();
        boolean savedMaximized = ConfigManager.getWindowMaximized();

        boolean haveBounds = !Double.isNaN( savedX ) && !Double.isNaN( savedY )
                && !Double.isNaN( savedWidth ) && !Double.isNaN( savedHeight )
                && savedWidth >= MIN_WIDTH && savedHeight >= MIN_HEIGHT;
        if ( !haveBounds ) {
            return false;
        }

        // Verify the saved rectangle still overlaps an attached screen before applying it.
        Rectangle2D savedRect = new Rectangle2D( savedX, savedY, savedWidth, savedHeight );
        if ( Screen.getScreensForRectangle( savedRect ).isEmpty() ) {
            Logger.logDebug( "Saved launcher window bounds are off-screen; using default position." );
            return false;
        }

        stage.setX( savedX );
        stage.setY( savedY );
        stage.setWidth( savedWidth );
        stage.setHeight( savedHeight );

        // Seed the "last normal bounds" tracker so an immediate maximize still has values to persist.
        lastNormalX = savedX;
        lastNormalY = savedY;
        lastNormalWidth = savedWidth;
        lastNormalHeight = savedHeight;
        normalBoundsSeen = true;

        if ( savedMaximized ) {
            stage.setMaximized( true );
        }
        return true;
    }

    /**
     * Wires listeners on the stage's bounds and maximized properties so changes get persisted (debounced) to the
     * config. Bounds saved are always the "last unmaximized" values, alongside a flag for the maximized state — that
     * way restoring a maximized window unmaximizes back to a sensible size and position.
     */
    private void installBoundsPersistence() {
        boundsSaveDebouncer = new PauseTransition( Duration.millis( 500 ) );
        boundsSaveDebouncer.setOnFinished( e -> persistBoundsNow() );

        ChangeListener< Number > onBoundsChanged = ( obs, oldVal, newVal ) -> {
            if ( !stage.isMaximized() && !stage.isIconified()
                    && stage.getWidth() > 0 && stage.getHeight() > 0 ) {
                lastNormalX = stage.getX();
                lastNormalY = stage.getY();
                lastNormalWidth = stage.getWidth();
                lastNormalHeight = stage.getHeight();
                normalBoundsSeen = true;
            }
            boundsSaveDebouncer.playFromStart();
        };
        ChangeListener< Boolean > onFlagChanged = ( obs, oldVal, newVal ) -> boundsSaveDebouncer.playFromStart();

        stage.xProperty().addListener( onBoundsChanged );
        stage.yProperty().addListener( onBoundsChanged );
        stage.widthProperty().addListener( onBoundsChanged );
        stage.heightProperty().addListener( onBoundsChanged );
        stage.maximizedProperty().addListener( onFlagChanged );
    }

    /**
     * Writes the current "last unmaximized" bounds and current maximized flag to the config. Called by the debouncer
     * after bounds settle, and synchronously during {@link #cleanup()} to flush any pending change at exit.
     */
    private void persistBoundsNow() {
        if ( stage == null || !normalBoundsSeen ) {
            return;
        }
        try {
            ConfigManager.setWindowBounds( lastNormalX, lastNormalY, lastNormalWidth, lastNormalHeight,
                                           stage.isMaximized() );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Unable to persist launcher window bounds." );
            Logger.logThrowable( e );
        }
    }

    void setScene( MCLauncherAbstractGui gui ) {
        // Cleanup previous GUI, if present
        if ( this.gui != null ) {
            this.gui.cleanup();
        }

        // Store new GUI and set it up
        this.gui = gui;
        GUIUtilities.JFXPlatformRun( () -> {
            // Prepare scene environment
            gui.setup();

            // Inject context-sensitive help button into top-right corner
            injectHelpButton( gui );

            // Change stage name
            stage.setTitle(
                    LauncherConstants.LAUNCHER_APPLICATION_NAME + GUIConstants.TITLE_SPLIT_CHAR + gui.getSceneName() );

            // Set correct first theme
            forceThemeChange();

            // Set scene
            stage.setScene( gui.scene );

            gui.afterShow();
        } );

        // Setup theme detector change listener (unregister previous to avoid accumulation)
        if ( detector != null ) {
            try {
                if ( themeListener != null ) {
                    detector.removeListener( themeListener );
                }
                themeListener = isDark -> forceThemeChange();
                detector.registerListener( themeListener );
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Unable to configure theme change listener for dark/light mode!" );
                Logger.logThrowable( e );
            }
        }
    }

    public void show() {
        GUIUtilities.JFXPlatformRun( () -> stage.show() );
    }

    public Stage getStage() {
        return stage;
    }

    public void forceThemeChange() {
        // Also refresh the help window theme if it's open
        MCLauncherHelpWindow.refreshTheme();

        switch ( ConfigManager.getTheme() ) {
            case ConfigConstants.THEME_AUTOMATIC:
                if ( detector != null ) {
                    if ( detector.isDark() ) {
                        // The OS switched to a dark theme
                        switchToDarkTheme();
                    }
                    else {
                        // The OS switched to a light theme
                        switchToLightTheme();
                    }
                }
                break;
            case ConfigConstants.THEME_LIGHT:
                switchToLightTheme();
                break;
            case ConfigConstants.THEME_DARK:
                switchToDarkTheme();
                break;
            case ConfigConstants.THEME_BLUE_GRAY:
                switchToBlueGrayTheme();
                break;
            case ConfigConstants.THEME_ORANGE_PURPLE:
                switchToOrangePurpleTheme();
                break;
        }
    }

    /** Resource paths for the four legacy per-theme stylesheets. Loaded first so the new
     *  base + token sheets layered on top win where they declare the same selectors. */
    private static final String LEGACY_DARK         = "guiStyle-dark.css";
    private static final String LEGACY_LIGHT        = "guiStyle-light.css";
    private static final String LEGACY_BLUE_GRAY    = "guiStyle-bluegray.css";
    private static final String LEGACY_ORANGE_PURPLE = "guiStyle-orangepurple.css";

    /** Path to the brand-new theme-agnostic base sheet (font stack + component shell). */
    private static final String UI_BASE_SHEET       = "ui/ui-base.css";

    /** Per-theme token sheets that define `-color-*` lookup variables consumed by ui-base.css. */
    private static final String UI_TOKENS_DARK         = "ui/ui-tokens-dark.css";
    private static final String UI_TOKENS_LIGHT        = "ui/ui-tokens-light.css";
    private static final String UI_TOKENS_BLUE_GRAY    = "ui/ui-tokens-bluegray.css";
    private static final String UI_TOKENS_ORANGE_PURPLE = "ui/ui-tokens-orangepurple.css";

    private void switchToLightTheme() {
        applyTheme( LEGACY_LIGHT, UI_TOKENS_LIGHT );
    }

    private void switchToDarkTheme() {
        applyTheme( LEGACY_DARK, UI_TOKENS_DARK );
    }

    private void switchToBlueGrayTheme() {
        applyTheme( LEGACY_BLUE_GRAY, UI_TOKENS_BLUE_GRAY );
    }

    private void switchToOrangePurpleTheme() {
        applyTheme( LEGACY_ORANGE_PURPLE, UI_TOKENS_ORANGE_PURPLE );
    }

    /**
     * Installs the chosen theme onto the active GUI's root pane. Layering, lowest precedence first:
     * <ol>
     *     <li>Legacy single-theme sheet (still defines selectors that the new system has not yet ported)</li>
     *     <li>{@link #UI_BASE_SHEET} (component shell built on lookup variables)</li>
     *     <li>The selected token sheet (defines the `-color-*` lookup palette)</li>
     * </ol>
     * Any previously-installed theme/token sheets are removed first so we never accumulate stylesheets across
     * theme switches or scene transitions.
     */
    private void applyTheme( String legacySheet, String tokenSheet ) {
        GUIUtilities.JFXPlatformRun( () -> {
            java.util.List< String > stylesheets = gui.rootPane.getStylesheets();

            // Drop every legacy theme sheet. Whichever is "current" gets re-added below.
            stylesheets.remove( cssUrl( LEGACY_DARK ) );
            stylesheets.remove( cssUrl( LEGACY_LIGHT ) );
            stylesheets.remove( cssUrl( LEGACY_BLUE_GRAY ) );
            stylesheets.remove( cssUrl( LEGACY_ORANGE_PURPLE ) );

            // Drop every token sheet. Whichever is "current" gets re-added below.
            stylesheets.remove( cssUrl( UI_TOKENS_DARK ) );
            stylesheets.remove( cssUrl( UI_TOKENS_LIGHT ) );
            stylesheets.remove( cssUrl( UI_TOKENS_BLUE_GRAY ) );
            stylesheets.remove( cssUrl( UI_TOKENS_ORANGE_PURPLE ) );

            // Drop the base sheet so we can re-install it in the correct order.
            stylesheets.remove( cssUrl( UI_BASE_SHEET ) );

            // Add in the layered order: legacy → base → tokens.
            stylesheets.add( cssUrl( legacySheet ) );
            stylesheets.add( cssUrl( UI_BASE_SHEET ) );
            stylesheets.add( cssUrl( tokenSheet ) );
        } );
    }

    /** Resolves a classpath CSS resource to its external URL form, throwing if missing. */
    private String cssUrl( String resourcePath ) {
        return Objects.requireNonNull( getClass().getClassLoader().getResource( resourcePath ),
                                       "Missing CSS resource: " + resourcePath ).toExternalForm();
    }

    /**
     * Programmatically injects a "?" help button into the top-right corner of the screen's root pane. The button opens
     * the help window to the topic returned by the GUI's {@link MCLauncherAbstractGui#getHelpTopic()}.
     *
     * @param gui the current GUI screen
     */
    private void injectHelpButton( MCLauncherAbstractGui gui )
    {
        if ( gui.rootPane instanceof GridPane gridPane ) {
            MFXButton helpBtn = new MFXButton( "?" );
            helpBtn.getStyleClass().add( "helpButton" );
            helpBtn.setOnAction( e -> MCLauncherHelpWindow.show( gui.getHelpTopic() ) );

            // Add to column 0, row 0 aligned to top-right so it overlays in the corner
            int col = gridPane.getColumnConstraints().size() - 1;
            if ( col < 0 ) col = 0;
            gridPane.add( helpBtn, col, 0 );
            GridPane.setHalignment( helpBtn, HPos.RIGHT );
            GridPane.setValignment( helpBtn, VPos.TOP );
            GridPane.setMargin( helpBtn, new Insets( 8, 8, 0, 0 ) );
        }
    }

    /**
     * Cleans up the theme detector listener to prevent memory leaks. Should be called during application shutdown.
     *
     * @since 2.0
     */
    public void cleanup()
    {
        // Flush any pending bounds change synchronously so it isn't lost if the user closes during the debounce window.
        if ( boundsSaveDebouncer != null ) {
            boundsSaveDebouncer.stop();
            boundsSaveDebouncer = null;
        }
        persistBoundsNow();

        if ( detector != null && themeListener != null ) {
            try {
                detector.removeListener( themeListener );
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Unable to remove theme change listener during cleanup." );
            }
            themeListener = null;
        }
    }
}


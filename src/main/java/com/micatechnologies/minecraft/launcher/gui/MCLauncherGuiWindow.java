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
import javafx.application.Platform;
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
import org.apache.commons.lang3.SystemUtils;

import java.io.InputStream;
import java.util.List;
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

    /**
     * Last screen the stage's center was observed on. Used to detect cross-monitor moves so we can nudge the window
     * position and force Windows' per-monitor taskbar to re-evaluate icon placement. {@code null} until the first
     * post-show position settle.
     */
    private Screen          lastKnownScreen          = null;
    private PauseTransition monitorChangeDebouncer   = null;

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

        // IMPORTANT: install the scene BEFORE showing the stage. Otherwise stage.show() paints the
        // OS-default (white) stage background for one frame before the scene is attached, creating a
        // jarring white flash on top of every theme.
        setScene( progressGui );
        show();
        if ( !restored ) {
            stage.centerOnScreen();
        }

        // Begin tracking bounds changes so we can persist them across launches.
        installBoundsPersistence();

        // Windows-only: nudge the window position when it crosses to a new monitor so the per-monitor
        // taskbar updates the icon's location. No-op on macOS / Linux.
        installMonitorChangeNudge();

        // macOS dock: install the same right-click action menu the system tray uses so dock users
        // can reach Play Last / Open Mods / Quit without focusing the window first. No-op on
        // Win / Linux (Taskbar.Feature.MENU is macOS-only in practice).
        com.micatechnologies.minecraft.launcher.utilities.MacOsDockManager.installDockMenu(
                com.micatechnologies.minecraft.launcher.utilities.LauncherActions.buildSharedMenu() );
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

    /**
     * Windows multi-monitor workaround. With Windows set to "show taskbar icons only on the monitor where the window
     * is open," the taskbar icon often fails to follow the window when it's dragged between displays — the per-monitor
     * shell sometimes misses the boundary crossing on JavaFX drag-end events. After the user's drag settles, we detect
     * whether the stage's center is on a different {@link Screen} than before and, if so, push a 1-px X nudge followed
     * by an immediate restore on the next pulse. The transient position change forces Windows to fire a fresh
     * {@code WM_WINDOWPOSCHANGED}, which prompts the shell to re-evaluate which monitor owns the window.
     *
     * <p>Gated on {@link SystemUtils#IS_OS_WINDOWS} — on macOS / Linux the listeners are never registered, so the
     * method is a true no-op there.</p>
     */
    private void installMonitorChangeNudge() {
        if ( !SystemUtils.IS_OS_WINDOWS || stage == null ) {
            return;
        }

        monitorChangeDebouncer = new PauseTransition( Duration.millis( 200 ) );
        monitorChangeDebouncer.setOnFinished( e -> nudgeIfMonitorChanged() );

        ChangeListener< Number > onPositionChanged = ( obs, oldVal, newVal ) -> {
            if ( stage.isShowing() && !stage.isIconified() ) {
                monitorChangeDebouncer.playFromStart();
            }
        };
        stage.xProperty().addListener( onPositionChanged );
        stage.yProperty().addListener( onPositionChanged );

        // Seed the initial screen reference so the *first* boundary crossing after launch registers as a change.
        // Width may still be 0 here if start() hasn't fully laid out yet; the helper falls back to primary in that case.
        lastKnownScreen = screenForStageCenter();
    }

    /** Resolves the {@link Screen} whose bounds contain the stage's geometric center, or the primary screen if the
     *  stage isn't sized yet / sits across boundaries. */
    private Screen screenForStageCenter() {
        if ( stage == null || stage.getWidth() <= 0 || stage.getHeight() <= 0 ) {
            return Screen.getPrimary();
        }
        double cx = stage.getX() + stage.getWidth() / 2.0;
        double cy = stage.getY() + stage.getHeight() / 2.0;
        // Use a 1x1 probe at the center point — getScreensForRectangle with the full window bounds returns multiple
        // screens whenever the window straddles a boundary, which makes the "which monitor owns this" check ambiguous.
        List< Screen > screens = Screen.getScreensForRectangle( cx, cy, 1, 1 );
        return screens.isEmpty() ? Screen.getPrimary() : screens.get( 0 );
    }

    /** Compares the current center-screen against {@link #lastKnownScreen}; on change, queues
     *  the position nudge AND fires a {@code SetWindowPos(SWP_FRAMECHANGED)} on the launcher's
     *  HWND. The position nudge alone proved unreliable across monitor boundaries on Win11
     *  — the shell often debounces small position deltas and skips the per-monitor taskbar
     *  re-evaluation. {@code SWP_FRAMECHANGED} is the canonical "this window's frame state
     *  may have changed" prod and triggers a {@code WM_NCCALCSIZE} pass that almost always
     *  wakes the shell up. Both run on monitor-cross so we get the union of their effects. */
    private void nudgeIfMonitorChanged() {
        if ( stage == null || !stage.isShowing() || stage.isIconified() ) {
            return;
        }

        Screen current = screenForStageCenter();
        Screen previous = lastKnownScreen;
        lastKnownScreen = current;

        if ( previous == null || current == null || previous.equals( current ) ) {
            return;
        }

        final double x = stage.getX();
        stage.setX( x + 1 );
        // Restore on the next pulse so the visible jump is at most one frame. Two separate setX() calls in the same
        // synchronous block coalesce into a single Glass position update and don't produce two WM_WINDOWPOSCHANGED
        // messages — runLater ensures the restore happens in a distinct animation pulse.
        Platform.runLater( () -> {
            stage.setX( x );
            // First attempt: immediately after the position settles. SetWindowPos with the
            // window's actual current bounds + SWP_FRAMECHANGED. Windows-only, no-op elsewhere.
            com.micatechnologies.minecraft.launcher.utilities.WindowsShellRefresh.forceFrameRefresh( stage );

            // Second attempt: ~200 ms later. The first attempt occasionally fires while
            // the Win11 shell is still mid-debounce on the drag-end events and gets
            // dropped; the delayed retry catches those cases. Two attempts together push
            // the icon-follow success rate from ~70% to consistently high in testing.
            PauseTransition retry = new PauseTransition( Duration.millis( 200 ) );
            retry.setOnFinished( ev ->
                com.micatechnologies.minecraft.launcher.utilities.WindowsShellRefresh.forceFrameRefresh( stage ) );
            retry.play();
        } );
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

            // macOS only: reparent the single system menu bar instance into this scene's root.
            // Win / Linux: no-op (the navbar covers the same actions in-window). The bar is
            // created lazily here on first use.
            SystemMenuBarManager.ensureCreated();
            SystemMenuBarManager.attachTo( gui.rootPane );

            // Change stage name
            stage.setTitle(
                    LauncherConstants.LAUNCHER_APPLICATION_NAME + GUIConstants.TITLE_SPLIT_CHAR + gui.getSceneName() );

            // Set correct first theme
            forceThemeChange();

            // Set scene
            stage.setScene( gui.scene );

            gui.afterShow();
        } );

        // Setup theme detector change listener (unregister previous to avoid accumulation).
        //
        // The listener runs on jthemedetecor's internal watcher thread, which restarts
        // itself mid-flight when Windows fires multiple registry-change events for a
        // single OS theme switch (Win11 fires updates for AppsUseLightTheme +
        // SystemUsesLightTheme + ColorPrevalence back-to-back). That restart interrupts
        // our previous listener-thread that was awaiting JFXPlatformRun's CountDownLatch,
        // which surfaces to the user as "Unable to wait for a user interface task to
        // complete". Two defenses:
        //
        //   1. Only re-apply the theme when the user actually has Automatic selected.
        //      For every other theme, the OS toggle is informational and should be
        //      ignored entirely.
        //   2. Dispatch the re-apply via Platform.runLater (fire-and-forget) instead of
        //      JFXPlatformRun's blocking wait, so an interrupted watcher thread can't
        //      leak through as a UI error.
        if ( detector != null ) {
            try {
                if ( themeListener != null ) {
                    detector.removeListener( themeListener );
                }
                themeListener = isDark -> {
                    // Only re-apply the theme when the user has picked one that follows
                    // the OS. Automatic flips dark/light root themes; Native (Mica)
                    // flips between its dark + light Mica-frost variants. Every other
                    // theme is explicit and ignores the OS toggle.
                    String current = ConfigManager.getTheme();
                    if ( !ConfigConstants.THEME_AUTOMATIC.equals( current )
                            && !ConfigConstants.THEME_NATIVE.equals( current ) ) {
                        return;
                    }
                    Platform.runLater( this::forceThemeChange );
                };
                detector.registerListener( themeListener );
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Unable to configure theme change listener for dark/light mode!" );
                Logger.logThrowable( e );
            }
        }
    }

    public void show() {
        GUIUtilities.JFXPlatformRun( () -> {
            boolean firstShow = !stage.isShowing();
            stage.show();
            // Re-apply DWM chrome attributes only on the *first* show. applyTheme() runs
            // before the HWND exists, so the very first paint needs a post-show retry —
            // but subsequent show() calls (each screen navigation re-calls show()) hit
            // an already-visible stage with a known HWND, and re-firing DWM there has
            // been observed to cause perceptible freezes on focus regain. Theme switches
            // call applyTheme() directly anyway, so we don't lose the chrome update.
            if ( firstShow ) {
                String tokenSheet = currentTokenSheet();
                boolean lightTheme = tokenSheet != null
                                  && ( tokenSheet.endsWith( "ui-tokens-light.css" )
                                    || tokenSheet.endsWith( "ui-tokens-native-light.css" ) );
                com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                        .applyTitleBarDarkMode( stage, !lightTheme );
                boolean isNative = tokenSheet != null
                                && ( tokenSheet.endsWith( "ui-tokens-native.css" )
                                  || tokenSheet.endsWith( "ui-tokens-native-light.css" ) );
                com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager.applyBackdrop(
                        stage,
                        isNative
                          ? com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager.BACKDROP_MICA
                          : com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager.BACKDROP_NONE );
                // Color-match chrome on first show too. applyTheme() ran before show() so
                // its caption-color call hit a not-yet-realized HWND; repeat here so the
                // very first paint already has the seamless title bar.
                javafx.scene.paint.Color chrome = javafx.scene.paint.Color.web( themeBgHex( tokenSheet ) );
                com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                        .applyCaptionColor( stage, chrome );
                com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                        .applyBorderColor( stage, chrome );

                // Force a non-client frame recalc so DWM repaints the title bar with
                // the chrome attributes we just set.
                com.micatechnologies.minecraft.launcher.utilities.WindowsShellRefresh
                        .forceFrameRefresh( stage );
                // SWP_FRAMECHANGED only touches the non-client area. The CLIENT area
                // (where the JavaFX scene paints) won't be invalidated by it, so on
                // Native theme the very first paint — which happened before Mica was
                // enabled — leaves a white client rectangle on screen until something
                // else triggers a repaint (focus, move, resize). RedrawWindow with
                // RDW_INVALIDATE | RDW_ERASE | RDW_FRAME flags forces both client and
                // non-client repaints synchronously, so Mica shows up on the very next
                // frame regardless of whether the first paint beat us to it.
                com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                        .forceFullRepaint( stage );
            }
        } );
    }

    /** Returns the currently-loaded ui-tokens-*.css path (one of {@link #UI_TOKENS_DARK} et al.)
     *  or null if no token sheet has been installed yet. Used to derive title-bar dark mode
     *  without re-reading the config in tight loops. */
    private String currentTokenSheet() {
        if ( gui == null || gui.rootPane == null ) return null;
        for ( String sheet : gui.rootPane.getStylesheets() ) {
            if ( sheet.endsWith( "ui-tokens-dark.css" ) )         return UI_TOKENS_DARK;
            if ( sheet.endsWith( "ui-tokens-light.css" ) )        return UI_TOKENS_LIGHT;
            if ( sheet.endsWith( "ui-tokens-bluegray.css" ) )     return UI_TOKENS_BLUE_GRAY;
            if ( sheet.endsWith( "ui-tokens-orangepurple.css" ) ) return UI_TOKENS_ORANGE_PURPLE;
            if ( sheet.endsWith( "ui-tokens-creeper.css" ) )      return UI_TOKENS_CREEPER;
            if ( sheet.endsWith( "ui-tokens-native.css" ) )       return UI_TOKENS_NATIVE;
            if ( sheet.endsWith( "ui-tokens-native-light.css" ) ) return UI_TOKENS_NATIVE_LIGHT;
        }
        return null;
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
            case ConfigConstants.THEME_CREEPER:
                switchToCreeperTheme();
                break;
            case ConfigConstants.THEME_NATIVE:
                switchToNativeTheme();
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
    private static final String UI_TOKENS_CREEPER       = "ui/ui-tokens-creeper.css";
    private static final String UI_TOKENS_NATIVE        = "ui/ui-tokens-native.css";
    private static final String UI_TOKENS_NATIVE_LIGHT  = "ui/ui-tokens-native-light.css";

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

    /** Creeper theme has no legacy CSS pair — falls back on the dark legacy sheet for any
     *  selectors not yet ported into ui-base.css, but uses creeper tokens for everything modern. */
    private void switchToCreeperTheme() {
        applyTheme( LEGACY_DARK, UI_TOKENS_CREEPER );
    }

    /** Native theme — translucent surface palette with the OS Mica backdrop showing
     *  through. Follows OS dark/light: picks the dark or light Native token sheet based
     *  on {@link OsThemeDetector#isDark()}. The legacy companion sheet matches too so
     *  selectors not yet ported into ui-base.css get the right palette. */
    private void switchToNativeTheme() {
        boolean osDark = detector == null || detector.isDark();
        if ( osDark ) {
            applyTheme( LEGACY_DARK, UI_TOKENS_NATIVE );
        }
        else {
            applyTheme( LEGACY_LIGHT, UI_TOKENS_NATIVE_LIGHT );
        }
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
            stylesheets.remove( cssUrl( UI_TOKENS_CREEPER ) );
            stylesheets.remove( cssUrl( UI_TOKENS_NATIVE ) );
            stylesheets.remove( cssUrl( UI_TOKENS_NATIVE_LIGHT ) );

            // Drop the base sheet so we can re-install it in the correct order.
            stylesheets.remove( cssUrl( UI_BASE_SHEET ) );

            // Add in the layered order: legacy → base → tokens.
            stylesheets.add( cssUrl( legacySheet ) );
            stylesheets.add( cssUrl( UI_BASE_SHEET ) );
            stylesheets.add( cssUrl( tokenSheet ) );

            // Belt-and-suspenders: paint the rootPane and scene fill with the theme's bg color
            // directly, so even if a CSS lookup somewhere in the chain fails to resolve, the
            // screen never shows OS-default white.
            //
            // Native theme is the exception: with StageStyle.UNIFIED on Windows, DWM can
            // composite a Mica backdrop through the JavaFX scene — but only if the scene
            // and rootPane both render transparent pixels. So for Native we clear the
            // inline rootPane bg (the ui-tokens-native.css `.rootPane { background: transparent }`
            // rule wins) and set scene fill to TRANSPARENT. For any other theme the inline
            // override paints the solid bg.
            String bg = themeBgHex( tokenSheet );
            boolean isNative = tokenSheet.endsWith( "ui-tokens-native.css" )
                            || tokenSheet.endsWith( "ui-tokens-native-light.css" );
            if ( isNative ) {
                // Force transparent via inline style — strongest override available so any
                // other stylesheet rule (legacy `.rootPane { background: #1C1B1F }`,
                // ui-base `.rootPane.hero-surface { background: -color-bg }`, etc.) loses
                // regardless of cascade order or selector specificity.
                gui.rootPane.setStyle( "-fx-background-color: transparent;" );
                if ( gui.scene != null ) {
                    gui.scene.setFill( javafx.scene.paint.Color.TRANSPARENT );
                }
            }
            else {
                gui.rootPane.setStyle( "-fx-background-color: " + bg + ";" );
                if ( gui.scene != null ) {
                    gui.scene.setFill( javafx.scene.paint.Color.web( bg ) );
                }
            }

            // Native theme: request the Mica backdrop via DWM. For non-Native themes,
            // clear any previously-set backdrop so the solid bg paints normally.
            //
            // On OS dark→light flips (and vice versa) we toggle backdrop NONE → MICA
            // before re-requesting MICA, since DWM otherwise caches the prior Mica
            // state and silently ignores the duplicate "use Mica" call — the new
            // OS-theme-tinted wallpaper composition never gets established and the
            // window paints with the prior tint or none at all.
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                    .applyBackdrop( stage,
                            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager.BACKDROP_NONE );
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                    .applyBackdrop( stage,
                            isNative
                              ? com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager.BACKDROP_MICA
                              : com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager.BACKDROP_NONE );

            // Flip the OS title bar to match the theme. Themes that paint a bright bg
            // (Light, Native light variant) need the light Windows chrome; everything
            // else reads dark and gets the immersive-dark chrome.
            boolean lightTheme = tokenSheet.endsWith( "ui-tokens-light.css" )
                              || tokenSheet.endsWith( "ui-tokens-native-light.css" );
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                    .applyTitleBarDarkMode( stage, !lightTheme );

            // Color-match the chrome to the theme bg so the title bar blends seamlessly
            // with the client area. Windows' default "dark mode" caption is near-black
            // (#000000) — fine in a pinch, but produces a hard horizontal seam against
            // any of our themed dark bgs. DWMWA_CAPTION_COLOR + DWMWA_BORDER_COLOR are
            // Win11 22H2+ attrs; older Windows silently ignore the calls and keep the
            // immersive-dark fallback we set above.
            javafx.scene.paint.Color chromeColor = javafx.scene.paint.Color.web( bg );
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                    .applyCaptionColor( stage, chromeColor );
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                    .applyBorderColor( stage, chromeColor );

            // Force a full client + non-client repaint after every theme change. Without
            // this, DWM accepts the new attributes but doesn't immediately invalidate
            // existing pixels — especially on OS-driven theme flips (dark→light Mica)
            // where the old wallpaper-tinted bitmap can persist until the next ambient
            // repaint event. RedrawWindow synchronously kicks WM_PAINT for both the
            // client area (JavaFX scene) and the non-client frame (title bar) so the
            // new theme + Mica backdrop show up immediately.
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                    .forceFullRepaint( stage );
        } );
    }

    /** Mirrors the {@code -color-bg} lookup defined in each ui-tokens-{theme}.css. Used as the inline
     *  fallback bg color so we never rely solely on lookup-variable resolution. */
    private static String themeBgHex( String tokenSheet ) {
        if ( tokenSheet == null ) return "#0C1017";
        if ( tokenSheet.endsWith( "ui-tokens-light.css" ) )         return "#FFFFFF";
        if ( tokenSheet.endsWith( "ui-tokens-bluegray.css" ) )      return "#121721";
        if ( tokenSheet.endsWith( "ui-tokens-orangepurple.css" ) )  return "#201221";
        if ( tokenSheet.endsWith( "ui-tokens-creeper.css" ) )       return "#0C130C";
        if ( tokenSheet.endsWith( "ui-tokens-native.css" ) )        return "#14181F";
        // Native light variant uses a near-white safety floor so the caption color
        // and the brief pre-Mica frame don't flash dark when running over light
        // wallpaper + dark OS chrome would normally not apply.
        if ( tokenSheet.endsWith( "ui-tokens-native-light.css" ) )  return "#F5F6FA";
        return "#0C1017";  // dark default
    }

    /** Resolves a classpath CSS resource to its external URL form, throwing if missing. */
    private String cssUrl( String resourcePath ) {
        return Objects.requireNonNull( getClass().getClassLoader().getResource( resourcePath ),
                                       "Missing CSS resource: " + resourcePath ).toExternalForm();
    }

    /**
     * Injects a "?" help button into the screen IF the FXML doesn't already supply one. Screens with a nav bar
     * (mainGUI) are expected to declare their own helpBtn directly in FXML — that is the canonical placement and
     * its action handler is wired by the screen's controller. This method handles the screens with no navbar
     * (login splash, progress) by floating a corner overlay help button.
     */
    private void injectHelpButton( MCLauncherAbstractGui gui )
    {
        // If the FXML already has a help button (any node with styleClass "helpButton"), don't inject another.
        if ( hasHelpButton( gui.rootPane ) ) {
            return;
        }

        MFXButton helpBtn = new MFXButton( "?" );
        helpBtn.getStyleClass().add( "helpButton" );
        helpBtn.setOnAction( e -> MCLauncherHelpWindow.show( gui.getHelpTopic() ) );

        // Anchor to top-right corner of a GridPane root.
        if ( gui.rootPane instanceof GridPane gridPane ) {
            int col = gridPane.getColumnConstraints().size() - 1;
            if ( col < 0 ) col = 0;
            gridPane.add( helpBtn, col, 0 );
            GridPane.setHalignment( helpBtn, HPos.RIGHT );
            GridPane.setValignment( helpBtn, VPos.TOP );
            GridPane.setMargin( helpBtn, new Insets( 8, 8, 0, 0 ) );
        }
    }

    /** True if any descendant of the given parent has styleClass "helpButton". */
    private boolean hasHelpButton( javafx.scene.Parent parent )
    {
        if ( parent == null ) return false;
        for ( javafx.scene.Node child : parent.getChildrenUnmodifiable() ) {
            if ( child.getStyleClass().contains( "helpButton" ) ) return true;
            if ( child instanceof javafx.scene.Parent nested && hasHelpButton( nested ) ) return true;
        }
        return false;
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

        if ( monitorChangeDebouncer != null ) {
            monitorChangeDebouncer.stop();
            monitorChangeDebouncer = null;
        }

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


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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton controller that owns the launcher's single {@link MCLauncherGuiWindow}
 * and drives all screen transitions. The {@code goTo*Gui()} family swaps the
 * window's scene to the requested screen (constructing it, lazily starting the
 * window if needed), while the {@code getTopStageOrNull} / {@code requestFocus} /
 * focus helpers give off-thread callers (notifications, tray, deep-link handlers)
 * a safe way to query and surface the window without reaching into
 * {@link MCLauncherGuiWindow} directly.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 1.0
 */
public class MCLauncherGuiController
{
    /** Whether {@link #startGui()} has successfully constructed the window; gates
     *  every accessor so off-thread callers never see a partially-initialized GUI. */
    private static final AtomicBoolean       startSuccess = new AtomicBoolean( false );
    /** The launcher's single GUI window, or {@code null} before start / after exit. */
    private static       MCLauncherGuiWindow guiWindow    = null;

    /**
     * Returns the launcher's top-level {@link Stage}, or {@code null} when the
     * GUI has not started (or has been torn down). Safe to call from any thread.
     *
     * @return the host stage, or {@code null} if the GUI isn't up
     *
     * @since 1.0
     */
    public static Stage getTopStageOrNull() {
        return startSuccess.get() && guiWindow != null ? guiWindow.getStage() : null;
    }

    /**
     * Best-effort check for whether the launcher window currently has OS focus — i.e. the user
     * is actively looking at it. Used to gate native toasts for events the user may have tabbed
     * away from (e.g. an install finishing). The reads are plain boolean property gets, safe
     * enough off the FX thread for a notification decision; returns false when the GUI isn't up
     * or the window is hidden / minimized.
     *
     * @return {@code true} if the launcher window is shown, non-iconified, and focused;
     *         {@code false} otherwise (including when the GUI is not up)
     *
     * @since 1.0
     */
    public static boolean isLauncherFocused() {
        Stage topStage = getTopStageOrNull();
        return topStage != null && topStage.isShowing() && !topStage.isIconified() && topStage.isFocused();
    }

    /**
     * Returns the launcher's currently-shown GUI controller (Main, Library, Settings,
     * Editor, etc.) or {@code null} if the GUI hasn't started yet. Lets external
     * callers make screen-aware decisions without reaching into {@link MCLauncherGuiWindow}
     * directly.
     *
     * @return the currently-displayed screen controller, or {@code null} if the GUI isn't up
     *
     * @since 1.0
     */
    public static MCLauncherAbstractGui getCurrentGuiOrNull() {
        return startSuccess.get() && guiWindow != null ? guiWindow.getCurrentGui() : null;
    }

    /**
     * Brings the launcher window to the foreground and gives it input focus,
     * de-iconifying and showing it first if needed. Used when the user activates
     * the tray icon or follows an {@code mmcl://} deep link with the window
     * minimized or hidden. No-op when the GUI isn't up; marshals onto the FX
     * thread internally so it's safe to call from any thread.
     *
     * @since 1.0
     */
    public static void requestFocus() {
        Stage topStage = getTopStageOrNull();
        if ( topStage != null ) {
            GUIUtilities.JFXPlatformRun( () -> {
                // De-iconify (Windows minimize, macOS minimize-to-dock) before requestFocus
                // — without this, a user clicking the tray icon or a mmcl:// link with the
                // launcher minimized would get no visible response. show() + toFront() bring
                // the window above other apps; requestFocus() handles input focus.
                if ( topStage.isIconified() ) {
                    topStage.setIconified( false );
                }
                if ( !topStage.isShowing() ) {
                    topStage.show();
                }
                topStage.toFront();
                topStage.requestFocus();
            } );
        }
    }


    /**
     * Idempotently constructs the launcher's single {@link MCLauncherGuiWindow}
     * + its host {@link Stage}, on the JavaFX thread. Returns true on success
     * (or when the window had already been started by a previous call), false
     * if construction failed.
     *
     * <p>Synchronized so the cold-start path can race two callers: the
     * background "FX prestart" thread (which overlaps Platform.startup +
     * window construction with the session thread's auth + pack-load work)
     * and the session thread's own startGui call from goToMainGui. Whichever
     * thread enters first does the construction; the second gets a fast
     * {@code guiWindow != null} return.</p>
     *
     * @return {@code true} if the window is (now or already) started successfully,
     *         {@code false} if construction failed
     *
     * @since 1.0
     */
    static synchronized boolean startGui() {
        if ( guiWindow == null ) {
            GUIUtilities.JFXPlatformRun( () -> {
                try {
                    guiWindow = new MCLauncherGuiWindow();
                    Stage stage = new Stage();
                    // StageStyle.UNIFIED makes JavaFX create the HWND in a way Windows DWM
                    // can composite a Mica system backdrop through. Plain DECORATED keeps an
                    // opaque redirection bitmap so DwmSetWindowAttribute(SYSTEMBACKDROP_TYPE)
                    // is silently ignored. UNIFIED is supported on Windows + macOS; falls
                    // back to a normal decorated window on Linux. Visually it removes the
                    // title-bar / content separator line — since we color-match the caption
                    // to the theme bg via DWMWA_CAPTION_COLOR, the seam was already invisible
                    // on most themes, so adopting UNIFIED everywhere costs nothing.
                    //
                    // On Windows the decorated UNIFIED window is also the base for the title-bar
                    // inset (WindowsCustomChromeManager, invoked post-show): it keeps the real OS
                    // min/max/close buttons while extending content to the top edge.
                    stage.initStyle( javafx.stage.StageStyle.UNIFIED );
                    // start() already shows the stage (with all the first-show chrome
                    // setup). A second show() here just no-ops against the now-visible
                    // stage — drop it to avoid the latent double-init trap if first-show
                    // logic is ever added.
                    guiWindow.start( stage );
                    startSuccess.set( true );
                }
                catch ( Exception e ) {
                    guiWindow = null;
                    Logger.logError( LocalizationManager.get( "log.controller.startGuiFailed" ) );
                    Logger.logThrowable( e );
                    startSuccess.set( false );
                }
            } );
        }
        return startSuccess.get();
    }

    /**
     * Triggers the GUI window construction in advance of the session thread
     * needing it. Same effect as {@link #startGui()}, but exposed publicly
     * so {@link com.micatechnologies.minecraft.launcher.LauncherSession}'s
     * FX-prestart thread can overlap the window-init work with its own
     * auth + pack-load critical path. Safe to call multiple times — the
     * underlying startGui is synchronized + idempotent.
     *
     * @return {@code true} if the window is (now or already) started successfully,
     *         {@code false} if construction failed
     *
     * @since 1.0
     */
    public static boolean prestartGui() {
        return startGui();
    }

    /**
     * Pre-constructed {@link MCLauncherMainGui} instance, populated by
     * {@link #prebuildMainGui()} from the FX-prestart thread. Picked up
     * (and cleared) by {@link #goToMainGui()} on the session thread's
     * first navigation to the main menu — avoids paying the FXML-load
     * cost on the critical path.
     *
     * <p>Accessed only under {@link #PREBUILD_LOCK} so a session thread
     * arriving mid-prebuild blocks on the monitor instead of racing into
     * its own redundant FXML load.</p>
     *
     * @since 1.0
     */
    private static MCLauncherMainGui prebuiltMainGui = null;

    /** Monitor guarding {@link #prebuiltMainGui} so prebuild + consume can't race —
     *  a session thread arriving mid-prebuild blocks on this lock until the in-flight
     *  construction completes instead of falling through to a redundant FXML load. */
    private static final Object PREBUILD_LOCK = new Object();

    /**
     * Pre-constructs the {@link MCLauncherMainGui} instance (FXML load + node
     * graph) so the session thread's first goToMainGui call can skip the
     * ~250 ms FXML parse.
     *
     * <p>Holds {@link #PREBUILD_LOCK} for the duration of the construction
     * so a racing {@link #consumePrebuiltMainGui()} caller (the session
     * thread arriving mid-prebuild) blocks until the instance is ready
     * rather than falling through and constructing a redundant copy on
     * its own. Pre-condition: {@link #startGui()} has been called and
     * succeeded so {@link #guiWindow} is non-null.</p>
     *
     * @since 1.0
     */
    public static void prebuildMainGui() {
        synchronized ( PREBUILD_LOCK ) {
            if ( prebuiltMainGui != null ) return;
            if ( !startSuccess.get() || guiWindow == null ) return;
            try {
                // Constructor loads the FXML on the FX thread via JFXPlatformRun;
                // the session thread can be doing anything during this. The
                // controller's setup() is NOT called here — that runs later, on
                // the FX thread, from MCLauncherGuiWindow.setScene, by which
                // point MCLauncherAuthManager.getLoggedInUser() (which setup()
                // dereferences for the player chip) has been populated by the
                // session-thread auth fast-path.
                prebuiltMainGui = new MCLauncherMainGui( guiWindow.getStage() );
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.controller.prebuildMainFailed",
                                                 t.getClass().getSimpleName() ) );
            }
        }
    }

    /**
     * Returns + clears the pre-built {@link MCLauncherMainGui} instance, or
     * {@code null} when no pre-build has been started or one failed.
     *
     * <p>Synchronizes on {@link #PREBUILD_LOCK} so a session thread
     * arriving mid-prebuild waits for the in-flight construction to
     * finish instead of returning null + redundantly constructing on its
     * own. Single-use: a subsequent call returns null.</p>
     *
     * @return the pre-built main-menu controller, or {@code null} if none was
     *         prepared (or it was already consumed)
     *
     * @since 1.0
     */
    private static MCLauncherMainGui consumePrebuiltMainGui() {
        synchronized ( PREBUILD_LOCK ) {
            MCLauncherMainGui pre = prebuiltMainGui;
            prebuiltMainGui = null;
            return pre;
        }
    }

    /**
     * Navigates to the main menu, reusing a {@link #prebuildMainGui() pre-built}
     * instance when one is ready (else constructing on the spot) and re-arming
     * the prebuild for the next visit. Starts the GUI window if needed.
     *
     * @return the shown main-menu controller, or {@code null} if the window
     *         could not be started
     *
     * @throws IOException if the screen's FXML could not be loaded
     * @since 1.0
     */
    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherMainGui goToMainGui() throws IOException {
        MCLauncherMainGui newMainGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            // Pick up the pre-built instance if the FX-prestart thread had
            // time to construct one in parallel with the session thread's
            // auth + pack-load work. Falls back to constructing on the spot
            // when prestart hasn't completed (slow disk, late goToMainGui
            // re-entry after a navigation away + back, etc.).
            newMainGui = consumePrebuiltMainGui();
            if ( newMainGui == null ) {
                newMainGui = new MCLauncherMainGui( guiWindow.getStage() );
            }
            guiWindow.setScene( newMainGui );
            guiWindow.show();
            // Re-arm the prebuild so a later return to the main menu
            // (Main -> Settings/Library/... -> Main) skips the ~250 ms FXML parse
            // again rather than only the first visit. The constructor just loads
            // the FXML scene graph (all wiring happens in setup() at setScene
            // time), so building a spare while one is shown is side-effect-free;
            // it's consumed on the next goToMainGui and cleared in exit().
            com.micatechnologies.minecraft.launcher.utilities.SystemUtilities.spawnNewTask(
                    MCLauncherGuiController::prebuildMainGui );
        }
        else {
            Logger.logError( LocalizationManager.get( "log.controller.mainNotDisplayed" ) );
        }
        return newMainGui;
    }

    /**
     * Navigates to the Settings screen, starting the GUI window if needed.
     *
     * @return the shown settings controller, or {@code null} if the window
     *         could not be started
     *
     * @throws IOException if the screen's FXML could not be loaded
     * @since 1.0
     */
    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherSettingsGui goToSettingsGui() throws IOException {
        MCLauncherSettingsGui newSettingsGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newSettingsGui = new MCLauncherSettingsGui( guiWindow.getStage() );
            guiWindow.setScene( newSettingsGui );
            guiWindow.show();
        }
        else {
            Logger.logError( LocalizationManager.get( "log.controller.settingsNotDisplayed" ) );
        }
        return newSettingsGui;
    }

    /**
     * Navigates to the Game Library screen, starting the GUI window if needed.
     *
     * @return the shown library controller, or {@code null} if the window
     *         could not be started
     *
     * @throws IOException if the screen's FXML could not be loaded
     * @since 1.0
     */
    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherGameLibraryGui goToGameLibraryGui() throws IOException {
        MCLauncherGameLibraryGui newLibraryGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newLibraryGui = new MCLauncherGameLibraryGui( guiWindow.getStage() );
            guiWindow.setScene( newLibraryGui );
            guiWindow.show();
        }
        else {
            Logger.logError( LocalizationManager.get( "log.controller.libraryNotDisplayed" ) );
        }
        return newLibraryGui;
    }

    /**
     * Navigates to the Microsoft sign-in screen, starting the GUI window if needed.
     *
     * @return the shown login controller, or {@code null} if the window
     *         could not be started
     *
     * @throws IOException if the screen's FXML could not be loaded
     * @since 1.0
     */
    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherLoginGui goToLoginGui() throws IOException {
        MCLauncherLoginGui newLoginGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newLoginGui = new MCLauncherLoginGui( guiWindow.getStage() );
            guiWindow.setScene( newLoginGui );
            guiWindow.show();
        }
        else {
            Logger.logError( LocalizationManager.get( "log.controller.loginNotDisplayed" ) );
        }
        return newLoginGui;
    }

    /**
     * Navigates to the legacy single-bar progress screen (used by sign-in and
     * other long-running flows), starting the GUI window if needed.
     *
     * @return the shown progress controller, or {@code null} if the window
     *         could not be started
     *
     * @throws IOException if the screen's FXML could not be loaded
     * @since 1.0
     */
    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherProgressGui goToProgressGui() throws IOException {
        MCLauncherProgressGui newProgressGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newProgressGui = new MCLauncherProgressGui( guiWindow.getStage() );
            guiWindow.setScene( newProgressGui );
            guiWindow.show();
        }
        else {
            Logger.logError( LocalizationManager.get( "log.controller.progressNotDisplayed" ) );
        }
        return newProgressGui;
    }

    /**
     * Transitions to the new step-list launch progress GUI. Used by the Play
     * path; other long-running flows (sign-in, etc.) still go through the
     * legacy {@link #goToProgressGui()} which renders a single bar.
     *
     * @return the shown launch-progress controller, or {@code null} if the window
     *         could not be started
     *
     * @throws IOException if the screen's FXML could not be loaded
     * @since 2026.3
     */
    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherLaunchProgressGui goToLaunchProgressGui() throws IOException {
        MCLauncherLaunchProgressGui newGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newGui = new MCLauncherLaunchProgressGui( guiWindow.getStage() );
            guiWindow.setScene( newGui );
            guiWindow.show();
        }
        else {
            Logger.logError( LocalizationManager.get( "log.controller.launchProgressNotDisplayed" ) );
        }
        return newGui;
    }

    /**
     * Navigates to the in-game console screen, starting the GUI window if needed.
     *
     * @return the shown console controller, or {@code null} if the window
     *         could not be started
     *
     * @throws IOException if the screen's FXML could not be loaded
     * @since 1.0
     */
    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherGameConsoleGui goToGameConsoleGui() throws IOException {
        MCLauncherGameConsoleGui newGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newGui = new MCLauncherGameConsoleGui( guiWindow.getStage() );
            guiWindow.setScene( newGui );
            guiWindow.show();
        }
        else {
            Logger.logError( LocalizationManager.get( "log.controller.consoleNotDisplayed" ) );
        }
        return newGui;
    }

    /**
     * Navigates to the Java runtime management screen, starting the GUI window
     * if needed.
     *
     * @return the shown runtime controller, or {@code null} if the window
     *         could not be started
     *
     * @throws IOException if the screen's FXML could not be loaded
     * @since 1.0
     */
    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherRuntimeGui goToRuntimeGui() throws IOException {
        MCLauncherRuntimeGui newRuntimeGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newRuntimeGui = new MCLauncherRuntimeGui( guiWindow.getStage() );
            guiWindow.setScene( newRuntimeGui );
            guiWindow.show();
        }
        else {
            Logger.logError( LocalizationManager.get( "log.controller.runtimeNotDisplayed" ) );
        }
        return newRuntimeGui;
    }

    /**
     * Opens the modpack editor with an empty document. Convenience for
     * {@link #goToModPackEditorGui(com.micatechnologies.minecraft.launcher.game.modpack.GameModPack)}
     * with {@code null}.
     *
     * @return the shown editor controller, or {@code null} if the window
     *         could not be started
     *
     * @throws IOException if the screen's FXML could not be loaded
     * @since 1.0
     */
    public static MCLauncherModPackEditorGui goToModPackEditorGui() throws IOException
    {
        return goToModPackEditorGui( null );
    }

    /**
     * Opens the modpack editor pre-loaded with {@code initialPack}'s manifest
     * source — used by the Library / main-menu Edit action so a click on an
     * installed pack lands the user on its fields populated rather than the
     * blank New-Modpack screen. Pass {@code null} to start with an empty
     * document (the toolbar New / Open / Open URL buttons still work either
     * way).
     *
     * @param initialPack the installed pack whose manifest source pre-populates the
     *                    editor fields, or {@code null} to open a blank document
     *
     * @return the shown editor controller, or {@code null} if the window
     *         could not be started
     *
     * @throws IOException if the screen's FXML could not be loaded
     * @since 1.0
     */
    public static MCLauncherModPackEditorGui goToModPackEditorGui(
            com.micatechnologies.minecraft.launcher.game.modpack.GameModPack initialPack )
            throws IOException
    {
        MCLauncherModPackEditorGui newEditorGui = null;
        boolean guiStarted = startGui();
        if ( guiStarted ) {
            newEditorGui = new MCLauncherModPackEditorGui( guiWindow.getStage() );
            // Stash the initial pack BEFORE setScene — setScene dispatches
            // gui.setup() on the FX thread, and setup is where the editor
            // reads + clears the field.
            if ( initialPack != null ) {
                newEditorGui.setInitialPack( initialPack );
            }
            guiWindow.setScene( newEditorGui );
            guiWindow.show();
        }
        else {
            Logger.logError( LocalizationManager.get( "log.controller.editorNotDisplayed" ) );
        }
        return newEditorGui;
    }

    /**
     * Forces the active window to re-apply the current theme (stylesheets +
     * native chrome). No-op when the GUI isn't up. Called after a theme change
     * in Settings.
     *
     * @since 1.0
     */
    public static void forceThemeRefresh() {
        if ( guiWindow != null ) {
            guiWindow.forceThemeChange();
        }
    }

    /**
     * Tears down the GUI window for a session end / restart: discards any
     * unconsumed pre-built main GUI (which holds this session's now-closing
     * {@link Stage}), cleans up and closes the window, and resets the
     * started-state flag in lockstep so off-thread callers don't dereference a
     * half-torn-down GUI.
     *
     * @since 1.0
     */
    public static void exit() {
        // Discard any unconsumed pre-built main GUI. It holds a reference to THIS
        // session's Stage (passed to its constructor in prebuildMainGui); if the
        // session restarts before goToMainGui consumes it, a restarted session's
        // prebuildMainGui() early-returns on the still-non-null field and
        // goToMainGui would then bind a controller wired to the previous session's
        // now-closed Stage (installing setOnCloseRequest on a dead stage and
        // retaining the old scene graph). Clearing it here under PREBUILD_LOCK
        // forces the next session to prebuild against its own live Stage.
        synchronized ( PREBUILD_LOCK ) {
            prebuiltMainGui = null;
        }
        if ( guiWindow != null ) {
            guiWindow.cleanup();
            if ( guiWindow.getStage() != null ) {
                Platform.setImplicitExit( false );
                if ( guiWindow.getStage().isShowing() ) {
                    GUIUtilities.JFXPlatformRun( () -> guiWindow.getStage().close() );
                }
            }
            guiWindow = null;
        }
        // Mark the GUI as torn down so off-thread callers (notification / tray /
        // Discord-RPC threads) don't dereference the now-null guiWindow during the
        // restart window before the next session repopulates it. Must be reset in
        // lockstep with nulling guiWindow above to avoid a startSuccess/guiWindow
        // desync NPE in getTopStageOrNull()/getCurrentGuiOrNull().
        startSuccess.set( false );
    }

    /**
     * Returns a boolean indicating if the application in its current state should use graphical user interfaces.
     *
     * @return true if GUIs should be used
     *
     * @since 1.0
     */
    public static boolean shouldCreateGui() {
        return !GraphicsEnvironment.isHeadless();
    }
}
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

import com.micatechnologies.minecraft.launcher.files.Logger;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class MCLauncherGuiController
{
    private static final AtomicBoolean       startSuccess = new AtomicBoolean( false );
    private static       MCLauncherGuiWindow guiWindow    = null;

    public static Stage getTopStageOrNull() {
        return startSuccess.get() ? guiWindow.getStage() : null;
    }

    /** Returns the launcher's currently-shown GUI controller (Main, Library, Settings,
     *  Editor, etc.) or {@code null} if the GUI hasn't started yet. Lets external
     *  callers make screen-aware decisions without reaching into {@link MCLauncherGuiWindow}
     *  directly. */
    public static MCLauncherAbstractGui getCurrentGuiOrNull() {
        return startSuccess.get() ? guiWindow.getCurrentGui() : null;
    }

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
                    stage.initStyle( javafx.stage.StageStyle.UNIFIED );
                    guiWindow.start( stage );
                    guiWindow.show();
                    startSuccess.set( true );
                }
                catch ( Exception e ) {
                    guiWindow = null;
                    Logger.logError( "An exception was encountered while starting the application GUI window." );
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
     * <p>Volatile so the session-thread read sees the prestart-thread
     * write without an explicit memory barrier. {@link AtomicBoolean}
     * tracks whether construction is in progress (to keep concurrent
     * pre-builds from racing each other).</p>
     */
    private static volatile MCLauncherMainGui prebuiltMainGui = null;
    private static final java.util.concurrent.atomic.AtomicBoolean prebuildInProgress =
            new java.util.concurrent.atomic.AtomicBoolean( false );

    /**
     * Pre-constructs the {@link MCLauncherMainGui} instance (FXML load + node
     * graph) so the session thread's first goToMainGui call can skip the
     * ~250 ms FXML parse. Idempotent + non-blocking on contention: a second
     * caller sees prebuildInProgress already set and returns immediately.
     *
     * <p>Pre-condition: {@link #startGui()} has been called and succeeded so
     * {@link #guiWindow} is non-null. The FX prestart thread chains this
     * after {@link #prestartGui()} returns.</p>
     */
    public static void prebuildMainGui() {
        if ( !startSuccess.get() || guiWindow == null ) return;
        if ( prebuiltMainGui != null ) return;
        if ( !prebuildInProgress.compareAndSet( false, true ) ) return;
        try {
            // Constructor loads the FXML on the FX thread via JFXPlatformRun;
            // the session thread can be doing anything during this. The
            // controller's setup() is NOT called here — that runs later, on
            // the FX thread, from MCLauncherGuiWindow.setScene, by which
            // point MCLauncherAuthManager.getLoggedInUser() (which setup()
            // dereferences for the player chip) has been populated by the
            // session-thread auth fast-path.
            MCLauncherMainGui instance = new MCLauncherMainGui( guiWindow.getStage() );
            prebuiltMainGui = instance;
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "Main GUI pre-build failed: "
                                             + t.getClass().getSimpleName() + " — "
                                             + "falling back to lazy construct in goToMainGui." );
        }
        finally {
            prebuildInProgress.set( false );
        }
    }

    /**
     * Returns + clears the pre-built {@link MCLauncherMainGui} instance, or
     * {@code null} when no pre-build is available. Single-use: a subsequent
     * call returns null until {@link #prebuildMainGui()} runs again.
     */
    private static MCLauncherMainGui consumePrebuiltMainGui() {
        MCLauncherMainGui pre = prebuiltMainGui;
        if ( pre != null ) {
            prebuiltMainGui = null;
        }
        return pre;
    }

    @SuppressWarnings( "UnusedReturnValue" )
    public static MCLauncherMainGui goToMainGui() throws IOException {
        com.micatechnologies.minecraft.launcher.utilities.ColdStartProfiler.mark( "main_gui_construct_start" );
        MCLauncherMainGui newMainGui = null;
        boolean guiStarted = startGui();
        com.micatechnologies.minecraft.launcher.utilities.ColdStartProfiler.mark( "main_gui_fx_started" );
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
            com.micatechnologies.minecraft.launcher.utilities.ColdStartProfiler.mark( "main_gui_instance_built" );
            guiWindow.setScene( newMainGui );
            com.micatechnologies.minecraft.launcher.utilities.ColdStartProfiler.mark( "main_gui_scene_set" );
            guiWindow.show();
            com.micatechnologies.minecraft.launcher.utilities.ColdStartProfiler.mark( "main_gui_show_called" );
        }
        else {
            Logger.logError( "The application main GUI could not be displayed due to the application GUI not being " +
                                     "started." );
        }
        return newMainGui;
    }

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
            Logger.logError( "The application settings GUI could not be displayed due to the application GUI not " +
                                     "being started." );
        }
        return newSettingsGui;
    }

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
            Logger.logError( "The game library GUI could not be displayed due to the application GUI not " +
                                     "being started." );
        }
        return newLibraryGui;
    }

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
            Logger.logError(
                    "The login GUI could not be displayed due to the application GUI not " + "being started." );
        }
        return newLoginGui;
    }

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
            Logger.logError(
                    "The progress GUI could not be displayed due to the application GUI not " + "being started." );
        }
        return newProgressGui;
    }

    /**
     * Transitions to the new step-list launch progress GUI. Used by the Play
     * path; other long-running flows (sign-in, etc.) still go through the
     * legacy {@link #goToProgressGui()} which renders a single bar.
     *
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
            Logger.logError(
                    "The launch progress GUI could not be displayed due to the application GUI not "
                            + "being started." );
        }
        return newGui;
    }

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
            Logger.logError( "The game console GUI could not be displayed." );
        }
        return newGui;
    }

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
            Logger.logError(
                    "The runtime management GUI could not be displayed due to the application GUI not being started." );
        }
        return newRuntimeGui;
    }

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
            Logger.logError(
                    "The modpack editor GUI could not be displayed due to the application GUI not being started." );
        }
        return newEditorGui;
    }

    public static void forceThemeRefresh() {
        if ( guiWindow != null ) {
            guiWindow.forceThemeChange();
        }
    }

    public static void exit() {
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
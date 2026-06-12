/*
 * Copyright (c) 2026 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.gui.GUIUtilities;
import com.nativejavafx.taskbar.TaskbarProgressbar;
import com.nativejavafx.taskbar.TaskbarProgressbarFactory;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import javafx.event.EventHandler;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * Process-wide owner of the native taskbar progress overlay.
 *
 * <p>The underlying {@code FXTaskbarProgressBar} factory creates a fresh
 * {@code ITaskbarList3} COM object plus a daemon-thread executor on every
 * {@code getTaskbarProgressbar(stage)} call, but the Windows taskbar holds the
 * progress state on the {@code HWND}, not on any one wrapper instance. When
 * multiple wrappers existed simultaneously (one in the progress GUI, another in
 * the main GUI, a third spun up by the update-check), their executor queues
 * interleaved on the same HWND, and the close/reopen race during scene
 * transitions left the taskbar bar stuck at the previous progress value.</p>
 *
 * <p>This manager keeps a single shared wrapper alive for the full launcher
 * session. Screens call {@link #attach(Stage)} once when they appear, then push
 * progress / stop / error states through these static methods. The wrapper is
 * only released at app exit via {@link #shutdown()}.</p>
 *
 * <p>Macros and Linux: {@code TaskbarProgressbar.isSupported()} returns false
 * and the factory hands back a {@code NullTaskbarProgressbar}. Every method
 * here becomes a transparent no-op on those platforms.</p>
 */
public final class TaskbarProgressManager
{
    private static volatile TaskbarProgressbar instance = null;
    private static volatile Stage              attachedStage = null;

    private TaskbarProgressManager() { /* static-only */ }

    /**
     * Lazily creates the shared wrapper for {@code stage} on the first call.
     * Subsequent calls with the same stage are no-ops; calls with a different
     * stage are ignored (the launcher only ever has one window stage, so a
     * second stage would indicate a bug rather than a re-attach intent).
     *
     * @return true if a usable wrapper exists after this call
     */
    public static boolean attach( Stage stage )
    {
        if ( stage == null ) return instance != null;

        if ( instance != null ) {
            return true;
        }

        // FXTaskbarProgressBar wraps an ITaskbarList3 COM object that must be touched only
        // from the JavaFX Application Thread, AND the underlying showCustomProgress call
        // checks stage.isShowing() — calling before the stage is on screen yields
        // "The given Stage is not showing". The screen lifecycle calls attach() during
        // afterShow() which runs after setScene() but before stage.show(), so the wrapper
        // must defer COM init until WINDOW_SHOWN fires.
        GUIUtilities.JFXPlatformRun( () -> {
            if ( instance != null ) {
                return;
            }
            if ( stage.isShowing() ) {
                createInstance( stage );
            }
            else {
                EventHandler< WindowEvent > onShown = new EventHandler< WindowEvent >() {
                    @Override
                    public void handle( WindowEvent event ) {
                        stage.removeEventHandler( WindowEvent.WINDOW_SHOWN, this );
                        createInstance( stage );
                    }
                };
                stage.addEventHandler( WindowEvent.WINDOW_SHOWN, onShown );
            }
        } );
        return instance != null;
    }

    /** Builds the FXTaskbarProgressBar wrapper. Caller must be on the FX thread and the
     *  stage must be showing (or about to be — the WINDOW_SHOWN handler fires after the
     *  native HWND is realized). */
    private static void createInstance( Stage stage )
    {
        try {
            instance = TaskbarProgressbarFactory.getTaskbarProgressbar( stage );
            attachedStage = stage;
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.taskbar.initFailed", e.getMessage() ) );
            instance = null;
            attachedStage = null;
        }
    }

    /**
     * Drives the OS-level progress overlay from a 0..1 fraction (or
     * {@link MFXProgressBar#INDETERMINATE_PROGRESS} for the indeterminate
     * marquee). Pass values already converted from the 0..100 percent scale.
     */
    public static void setProgress( double fraction )
    {
        // FXTaskbarProgressBar requires the FX thread; route every call there. Queue order
        // is preserved when multiple progress updates come from different workers.
        GUIUtilities.JFXPlatformRun( () -> {
            if ( instance != null ) {
                try {
                    if ( fraction == MFXProgressBar.INDETERMINATE_PROGRESS ) {
                        instance.showIndeterminateProgress();
                    }
                    else {
                        instance.showCustomProgress( fraction, TaskbarProgressbar.Type.NORMAL );
                    }
                }
                catch ( Exception | Error e ) {
                    Logger.logWarningSilent( LocalizationManager.format( "log.taskbar.updateFailed", e.getMessage() ) );
                }
            }
        } );
        // macOS / Linux path: java.awt.Taskbar. No-op where unsupported.
        MacOsDockManager.setProgress( fraction );
    }

    /**
     * Clears the taskbar overlay. Called by every GUI when it's about to be
     * replaced — guarantees the bar doesn't carry state across scene
     * transitions even if the next screen never touches the taskbar.
     */
    public static void stop()
    {
        GUIUtilities.JFXPlatformRun( () -> {
            if ( instance != null ) {
                try {
                    instance.stopProgress();
                }
                catch ( Exception | Error e ) {
                    Logger.logWarningSilent( LocalizationManager.format( "log.taskbar.stopFailed", e.getMessage() ) );
                }
            }
        } );
        MacOsDockManager.stop();
    }

    /**
     * Shows the red full-error overlay. Used as the "update available"
     * attention cue — the user can spot it from any window without the
     * launcher having focus.
     */
    public static void showFullError()
    {
        GUIUtilities.JFXPlatformRun( () -> {
            if ( instance != null ) {
                try {
                    instance.showFullErrorProgress();
                }
                catch ( Exception | Error e ) {
                    Logger.logWarningSilent( LocalizationManager.format( "log.taskbar.errorOverlayFailed", e.getMessage() ) );
                }
            }
        } );
        // macOS / Linux: red-tinted progress + bounce the dock icon to draw attention, and a
        // "1" badge so the update-available cue persists after the bounce settles. The badge
        // stays until the next launcher session, which is correct — the update remains
        // available until the user relaunches on the new version.
        MacOsDockManager.setError();
        MacOsDockManager.requestAttention( false );
        MacOsDockManager.setBadge( "1" );
    }

    /**
     * Releases the COM ref and shuts down the daemon executor. Call this
     * exactly once during app shutdown. Idempotent — repeated calls are
     * harmless no-ops.
     */
    public static void shutdown()
    {
        GUIUtilities.JFXPlatformRun( () -> {
            if ( instance != null ) {
                try {
                    instance.stopProgress();
                }
                catch ( Exception | Error ignored ) { /* best-effort clear */ }
                try {
                    instance.closeOperations();
                }
                catch ( Exception | Error ignored ) { /* best-effort release */ }
                instance = null;
                attachedStage = null;
            }
        } );
        MacOsDockManager.shutdown();
    }
}

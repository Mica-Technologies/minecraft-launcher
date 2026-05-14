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

package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Installs the launcher's cross-platform keyboard shortcuts on a {@link Scene}.
 *
 * <p>{@link SystemMenuBarManager} already exposes a few accelerators via the
 * macOS system menu bar, but those only work on macOS — on Windows and Linux
 * users get nothing. This class wires the same set as scene-level
 * {@link javafx.scene.input.KeyEvent#KEY_PRESSED} filters so every platform
 * gets navigation + action shortcuts.</p>
 *
 * <h3>Why an event filter, not setOnKeyPressed</h3>
 * <p>{@code setOnKeyPressed} on the scene gets clobbered by any controller
 * that wires its own scene-level key handler (e.g. {@link MCLauncherMainGui}
 * uses it for Enter + F5). Event filters compose — every {@code addEventFilter}
 * call layers on, so the global shortcuts coexist with screen-specific ones
 * regardless of order.</p>
 *
 * <h3>Cmd vs Ctrl</h3>
 * <p>{@link KeyEvent#isShortcutDown()} returns true for {@code Meta} on macOS
 * (i.e. {@code Cmd}) and {@code Ctrl} elsewhere — exactly the platform-aware
 * "primary modifier" semantics keyboard shortcuts want.</p>
 *
 * @since 2026.5
 */
public final class KeyboardShortcutManager
{
    private KeyboardShortcutManager() { /* static-only */ }

    /**
     * Installs the launcher's global navigation + help shortcuts on
     * {@code scene}. Safe to call multiple times — each call adds an event
     * filter, and since the shortcut handler consumes events it acts on,
     * downstream filters / handlers see the consumed event and skip. Should
     * be called from each GUI controller's {@code setup()} after the scene
     * is fully constructed.
     *
     * <p>Installed shortcuts:</p>
     * <ul>
     *   <li>{@code Cmd/Ctrl+,} — open Settings</li>
     *   <li>{@code Cmd/Ctrl+L} — open Browse (Library)</li>
     *   <li>{@code Cmd/Ctrl+E} — open Modpack Editor</li>
     *   <li>{@code Cmd/Ctrl+Shift+M} — return to Main menu (Cmd+M conflicts
     *       with the macOS Minimize Window system shortcut, so the launcher's
     *       home shortcut adds Shift)</li>
     *   <li>{@code Cmd/Ctrl+Shift+/} (i.e. {@code Cmd+?}) and {@code F1} —
     *       open the help window on the current screen's topic</li>
     * </ul>
     *
     * @param scene             the scene to wire shortcuts on; no-op when null
     * @param helpTopicSupplier supplies the current screen's
     *                          {@link HelpTopic} for the help shortcut;
     *                          may be null, in which case help defaults to
     *                          {@link HelpTopic#GETTING_STARTED}
     */
    public static void installGlobalShortcuts( Scene scene, Supplier< HelpTopic > helpTopicSupplier )
    {
        if ( scene == null ) return;
        scene.addEventFilter( KeyEvent.KEY_PRESSED, ev -> {
            // F1 / shortcut+? — context-sensitive help. Fires first because it
            // doesn't depend on the shortcut modifier (F1) or matches an
            // existing macOS menu-bar accelerator (Cmd+Shift+/).
            if ( ev.getCode() == KeyCode.F1
                    || ( ev.isShortcutDown() && ev.isShiftDown() && ev.getCode() == KeyCode.SLASH ) )
            {
                ev.consume();
                HelpTopic topic = helpTopicSupplier == null ? null : helpTopicSupplier.get();
                MCLauncherHelpWindow.show( topic == null ? HelpTopic.GETTING_STARTED : topic );
                return;
            }

            if ( !ev.isShortcutDown() ) return;
            // Skip the Cmd/Ctrl branch for events with Alt down — those are
            // typically text-input accelerators (Alt+Cmd+letter on macOS) that
            // would surprise users if intercepted as launcher shortcuts.
            if ( ev.isAltDown() ) return;

            switch ( ev.getCode() ) {
                case COMMA -> {
                    ev.consume();
                    navigate( MCLauncherGuiController::goToSettingsGui );
                }
                case L -> {
                    ev.consume();
                    navigate( MCLauncherGuiController::goToGameLibraryGui );
                }
                case E -> {
                    ev.consume();
                    navigate( () -> MCLauncherGuiController.goToModPackEditorGui() );
                }
                case M -> {
                    // Cmd+Shift+M only — bare Cmd+M is the macOS Minimize
                    // Window shortcut and we shouldn't override it.
                    if ( ev.isShiftDown() ) {
                        ev.consume();
                        navigate( MCLauncherGuiController::goToMainGui );
                    }
                }
                default -> { /* not a launcher shortcut */ }
            }
        } );
    }

    /** Wraps the navigation call with the off-FX-thread bounce + error logging
     *  that the rest of the launcher uses for screen transitions. */
    private static void navigate( IOThrowingRunnable target )
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                target.run();
            }
            catch ( IOException e ) {
                Logger.logError( "Keyboard shortcut navigation failed." );
                Logger.logThrowable( e );
            }
        } );
    }

    @FunctionalInterface
    private interface IOThrowingRunnable {
        void run() throws IOException;
    }
}

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

import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.game.session.GameSession;
import com.micatechnologies.minecraft.launcher.game.session.GameSessionRegistry;
import com.micatechnologies.minecraft.launcher.steam.SteamProcess;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A modpack started from its Steam library entry ({@code --steam "<pack>"}, plan decision D15):
 * the launcher starts the pack as soon as it is ready and quits when the game ends, so Steam,
 * which counts the game as running until every process it started has exited, returns to the
 * library with the game.
 *
 * <p>In the Steam Deck's Game Mode, gamescope shows a single window, preferring the one mapped
 * last; a launcher window shown after the game (Running Games refreshing, a notification) would
 * cover it. So there the launcher's windows are hidden while the game runs. If the launch fails
 * or the game crashes, they come back and the launcher stays open, so the error can be read.
 *
 * @since 2026.11
 */
public final class SteamPlaySession
{
    /** What to do when the pack's session changes. */
    enum Step
    {
        /** Nothing yet. */
        NONE,
        /** The game is up in Game Mode: hide the launcher's windows. */
        HIDE_WINDOWS,
        /** The game ended normally (or the launch was cancelled): quit, back to Steam. */
        QUIT,
        /** The launch failed or the game crashed: show the launcher and stop following. */
        SHOW_WINDOWS
    }

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private SteamPlaySession() { /* static-only */ }

    /**
     * What the session's phase means for a Steam start. Pure, for tests.
     *
     * @param phase             the pack's session phase
     * @param gameMode          whether this is the Deck's Game Mode
     * @param otherGamesRunning whether another game launched from this launcher still runs
     *
     * @return the step
     *
     * @since 2026.11
     */
    static Step decide( GameSession.Phase phase, boolean gameMode, boolean otherGamesRunning )
    {
        return switch ( phase ) {
            case PREPARING -> Step.NONE;
            case RUNNING -> gameMode ? Step.HIDE_WINDOWS : Step.NONE;
            case EXITED, CANCELLED -> otherGamesRunning ? Step.NONE : Step.QUIT;
            case CRASHED, FAILED -> Step.SHOW_WINDOWS;
        };
    }

    /**
     * Starts the pack named on a {@code --steam "<pack>"} command line, once, after the main
     * window is up. Does nothing for other starts. A pack that isn't installed leaves the
     * launcher open on its main screen.
     *
     * @param packName the pack named on the command line
     *
     * @since 2026.11
     */
    public static void startIfRequested( String packName )
    {
        if ( !LauncherCore.isSteamLaunch() || packName == null || packName.isBlank()
             || !STARTED.compareAndSet( false, true ) ) {
            return;
        }
        GameModPack pack = GameModPackManager.getInstalledModPackByName( packName );
        if ( pack == null ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.steam.packNotInstalled", packName ) );
            return;
        }
        boolean gameMode = SteamProcess.inGameMode( System.getenv() );
        String packKey = GameSession.keyFor( pack );
        GameSessionRegistry registry = GameSessionRegistry.get();
        AtomicBoolean done = new AtomicBoolean();
        Runnable follow = new Runnable()
        {
            @Override
            public void run()
            {
                if ( done.get() ) {
                    return;
                }
                GameSession session = latestFor( registry, packKey );
                if ( session == null ) {
                    return;
                }
                boolean others = registry.active().stream().anyMatch( s -> s != session );
                switch ( decide( session.phase(), gameMode, others ) ) {
                    case NONE -> { }
                    case HIDE_WINDOWS -> hideWindows();
                    case QUIT -> {
                        done.set( true );
                        registry.removeListener( this );
                        LauncherCore.closeApp();
                    }
                    case SHOW_WINDOWS -> {
                        done.set( true );
                        registry.removeListener( this );
                        showWindows( session );
                    }
                }
            }
        };
        registry.addListener( follow );
        SystemUtilities.spawnNewTask( () -> LauncherCore.play( pack ) );
    }

    private static GameSession latestFor( GameSessionRegistry registry, String packKey )
    {
        GameSession latest = null;
        for ( GameSession s : registry.sessions() ) {
            if ( s.packKey().equals( packKey ) && ( latest == null || s.id() > latest.id() ) ) {
                latest = s;
            }
        }
        return latest;
    }

    private static void hideWindows()
    {
        Platform.runLater( () -> {
            // Hiding every window must not end JavaFX: the launcher still quits or comes back.
            Platform.setImplicitExit( false );
            Stage main = MCLauncherGuiController.getTopStageOrNull();
            if ( main != null ) {
                main.hide();
            }
        } );
        RunningGamesWindow.hideUnlessPreparing();
    }

    private static void showWindows( GameSession session )
    {
        Platform.runLater( () -> {
            Stage main = MCLauncherGuiController.getTopStageOrNull();
            if ( main != null && !main.isShowing() ) {
                main.show();
            }
        } );
        RunningGamesWindow.showSession( session );
    }
}

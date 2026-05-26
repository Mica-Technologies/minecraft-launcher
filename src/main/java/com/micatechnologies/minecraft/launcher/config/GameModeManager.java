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

package com.micatechnologies.minecraft.launcher.config;

import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;

import java.awt.*;

/**
 * A class for managing the game mode of the launcher and inferring the game mode if one is not explicitly defined.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 1.1
 */
public class GameModeManager
{
    /**
     * Current application (launcher) game mode.
     *
     * @since 1.0
     */
    private static GameMode currentGameMode = null;

    /**
     * Infers the application (launcher) game mode and sets it as the current game mode.
     *
     * @since 1.0
     */
    public synchronized static void inferGameMode() {
        if ( GraphicsEnvironment.isHeadless() ) {
            currentGameMode = GameMode.SERVER;
        }
        else {
            currentGameMode = GameMode.CLIENT;
        }
        // Hardcoded English diagnostic. Localizing this would require
        // LocalizationManager.* references here, which would class-load
        // the bundle BEFORE LocaleBootstrap.apply runs (see Logger.java
        // for the full explanation). Game-mode log lines are
        // developer-facing diagnostics — translation has no value.
        Logger.logStd( "Game mode inferred and set to: " + currentGameMode.getStringName() );
    }

    /**
     * Gets the current application (launcher) game mode.
     *
     * @return current game mode
     *
     * @since 1.0
     */
    public synchronized static GameMode getCurrentGameMode() {
        return currentGameMode;
    }

    /**
     * Sets the application (launcher) game mode to the specified mode.
     *
     * @param gameMode game mode
     *
     * @since 1.0
     */
    public synchronized static void setCurrentGameMode( GameMode gameMode ) {
        currentGameMode = gameMode;
        // Hardcoded — see comment on inferGameMode above.
        Logger.logDebug( "Game mode being set to: " + currentGameMode.getStringName() + "." );
    }

    /**
     * Returns a boolean value indicating if the current game mode is client.
     *
     * @return true if client mode
     *
     * @since 1.0
     */
    public synchronized static boolean isClient() {
        return getCurrentGameMode() == GameMode.CLIENT;
    }

    /**
     * Returns a boolean value indicating if the current game mode is server.
     *
     * @return true if server mode
     *
     * @since 1.0
     */
    public synchronized static boolean isServer() {
        return getCurrentGameMode() == GameMode.SERVER;
    }
}

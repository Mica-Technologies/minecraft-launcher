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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;

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
        Logger.logStd( LocalizationManager.GAME_MODE_INFERRED_SET_TO_TEXT + " " + currentGameMode.getStringName() );
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
        Logger.logDebug(
                LocalizationManager.GAME_MODE_BEING_SET_TO_TEXT + " " + currentGameMode.getStringName() + "." );
    }

    /**
     * Returns a boolean value indicating if the current game mode is client.
     *
     * @return true if client mode
     *
     * @since 1.0
     */
    public static boolean isClient() {
        return getCurrentGameMode() == GameMode.CLIENT;
    }

    /**
     * Returns a boolean value indicating if the current game mode is server.
     *
     * @return true if server mode
     *
     * @since 1.0
     */
    public static boolean isServer() {
        return getCurrentGameMode() == GameMode.SERVER;
    }

    /**
     * Enum used to identify the supported game modes of the launcher, client and server.
     *
     * @author Mica Technologies
     * @version 1.0
     * @since 2.0
     */
    public enum GameMode
    {
        /**
         * Client game mode enum
         *
         * @since 1.0
         */
        CLIENT( "CLIENT" ),

        /**
         * Server game mode enum
         *
         * @since 1.0
         */
        SERVER( "SERVER" );

        /**
         * Stored string name value of the game mode. Value is returned by {@link #getStringName()}.
         *
         * @since 1.0
         */
        private final String gameModeString;

        /**
         * Constructor for a game mode Enum object with specified game mode string value
         *
         * @param gameModeString game mode string value
         *
         * @since 1.0
         */
        GameMode( String gameModeString ) {
            this.gameModeString = gameModeString;
        }

        /**
         * Gets the game mode string name value
         *
         * @return game mode string name
         *
         * @since 1.0
         */
        public String getStringName() {
            return gameModeString;
        }
    }

}

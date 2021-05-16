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

package com.micatechnologies.minecraft.launcher.files;

import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;

/**
 * Class that manages the local paths used by the launcher for storing configuration, mod pack installations, and Java
 * runtimes.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 1.1
 */
public class LocalPathManager
{
    /**
     * Gets the local path for storing launcher configuration, installation and runtime information.
     *
     * @return local launcher folder path
     *
     * @since 1.0
     */
    public static String getLauncherLocalPath() {
        return GameModeManager.isClient() ?
               LocalPathConstants.CLIENT_MODE_LAUNCHER_FOLDER_PATH :
               LocalPathConstants.SERVER_MODE_LAUNCHER_FOLDER_PATH;
    }

    /**
     * Gets the local path for storing launcher configuration information.
     *
     * @return launcher configuration folder path
     *
     * @since 1.0
     */
    public static String getLauncherConfigFolderPath() {
        return getLauncherLocalPath() + LocalPathConstants.CONFIG_FOLDER;
    }

    /**
     * Gets the local path for storing installed mod packs.
     *
     * @return launcher mod pack installation folder path
     *
     * @since 1.0
     */
    public static String getLauncherModpackFolderPath() {
        return getLauncherLocalPath() + LocalPathConstants.MODPACK_FOLDER;
    }

    /**
     * Gets the local path for storing launcher logs.
     *
     * @return launcher log folder path
     *
     * @since 1.0
     */
    public static String getLauncherLogFolderPath() {
        return getLauncherLocalPath() + LocalPathConstants.LOG_FOLDER;
    }

    /**
     * Gets the local path for storing game runtimes.
     *
     * @return launcher runtime folder path
     *
     * @since 1.0
     */
    public static String getLauncherRuntimeFolderPath() {
        return getLauncherLocalPath() + LocalPathConstants.RUNTIME_FOLDER;
    }

    /**
     * Gets the local path to the client token file.
     *
     * @return client token file path
     *
     * @since 1.0
     */
    public static String getClientTokenFilePath() {
        return getLauncherConfigFolderPath() + LocalPathConstants.CLIENT_TOKEN_FILE_NAME;
    }

    /**
     * Gets the local path to the remembered user account file.
     *
     * @return remembered user account file path
     *
     * @since 1.0
     */
    public static String getRememberedAccountFilePath() {
        return getLauncherConfigFolderPath() + LocalPathConstants.AUTH_ACCOUNT_REMEMBERED_FILE_NAME;
    }

    /**
     * Gets the local path to the launcher update information file.
     *
     * @return launcher update info file path
     *
     * @since 1.0
     */
    public static String getUpdateInfoFilePath() {
        return getLauncherConfigFolderPath() + LocalPathConstants.LAUNCHER_UPDATE_INFO_FILE_NAME;
    }

    /**
     * Gets the local path to the Minecraft version manifest.
     *
     * @return Minecraft version manifest file path
     *
     * @since 1.0
     */
    public static String getMinecraftVersionManifestFilePath() {
        return getLauncherConfigFolderPath() + LocalPathConstants.MINECRAFT_VERSION_MANIFEST_FILE_NAME;
    }
}

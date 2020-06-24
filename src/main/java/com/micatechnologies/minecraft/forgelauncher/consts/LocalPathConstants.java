/*
 * Copyright (c) 2020 Mica Technologies
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

package com.micatechnologies.minecraft.forgelauncher.consts;

import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.ClientAndServer;

import java.io.File;
import java.nio.file.Paths;

/**
 * Class of constants related to local file paths and storage for the application.
 *
 * @author Mica Technologies
 * @version 1.0
 * @editors hawka97
 * @creator hawka97
 * @since 2.0
 */
@ClientAndServer
public class LocalPathConstants
{
    /**
     * The absolute file path to the application local storage folder when the application is operating in client mode.
     *
     * @since 1.0
     */
    public static final String CLIENT_MODE_LAUNCHER_FOLDER_PATH =
            System.getProperty( "user.home" ) + File.pathSeparator + "." +
                    LauncherConstants.LAUNCHER_APPLICATION_NAME_TRIMMED;

    /**
     * The absolute file path to the application local storage folder when the application is operating in server mode.
     *
     * @since 1.0
     */
    public static final String SERVER_MODE_LAUNCHER_FOLDER_PATH = Paths.get( "" ).toAbsolutePath().toString();


    /**
     * Relative path to the application configuration folder within the application folder.
     *
     * @since 1.0
     */
    public static final String CONFIG_FOLDER = File.pathSeparator + "config";

    /**
     * Relative path to the installed modpacks folder within the application folder.
     *
     * @since 1.0
     */
    public static final String MODPACK_FOLDER = File.pathSeparator + "installs";

    /**
     * Relative path to the log folder within the application folder.
     *
     * @since 1.0
     */
    public static final String LOG_FOLDER = File.pathSeparator + "logs";

    /**
     * Relative path to the runtime install folder within the application folder.
     *
     * @since 1.0
     */
    public static final String RUNTIME_FOLDER = File.pathSeparator + "runtime";
}

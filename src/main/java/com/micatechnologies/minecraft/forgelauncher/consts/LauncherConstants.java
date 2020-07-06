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

/**
 * Class of constants/statics for use across package.
 *
 * @author Mica Technologies
 * @version 1.1
 * @creator hawka97
 * @editors hawka97
 * @since 1.0
 */
public class LauncherConstants
{
    /**
     * Launcher application name. Leave blank, this is auto-filled in.
     *
     * @since 1.0
     */
    public final static transient String LAUNCHER_APPLICATION_NAME = "Mica Forge Launcher";

    /**
     * Launcher application version. Leave blank, this is auto-filled in.
     *
     * @since 1.0
     */
    public final static transient String LAUNCHER_APPLICATION_VERSION = "2020.1";

    /**
     * Launcher application name without spaces.
     *
     * @since 1.0
     */
    public final static transient String LAUNCHER_APPLICATION_NAME_TRIMMED =
            LAUNCHER_APPLICATION_NAME.replaceAll( " ", "" );

    /**
     * Launcher update check URL. This URL is checked to see if the latest release is newer than the current release.
     *
     * @since 1.0.1
     */
    public final static String UPDATE_CHECK_REDIRECT_URL =
            "https://github.com/Mica-Technologies/Minecraft-Forge-Launcher/releases/latest";

    /**
     * Argument used to open application in forced client mode.
     *
     * @since 1.0
     */
    public static final String PROGRAM_ARG_CLIENT_MODE = "-c";

    /**
     * Argument used to open application in forced server mode.
     *
     * @since 1.0
     */
    public static final String PROGRAM_ARG_SERVER_MODE = "-s";

    /**
     * The minimum value allowed for the minimum RAM configuration in settings.
     *
     * @since 1.1
     */
    public static final double SETTINGS_MIN_RAM_MIN = 0.2;

    /**
     * The maximum value allowed for the minimum RAM configuration in settings.
     *
     * @since 1.1
     */
    public static final double SETTINGS_MIN_RAM_MAX = 32.0;

    /**
     * The maximum value allowed for the maximum RAM configuration in settings.
     *
     * @since 1.1
     */
    public static final double SETTINGS_MAX_RAM_MIN = 1.0;

    /**
     * The maximum value allowed for the maximum RAM configuration in settings.
     *
     * @since 1.1
     */
    public static final double SETTINGS_MAX_RAM_MAX = 64.0;
}

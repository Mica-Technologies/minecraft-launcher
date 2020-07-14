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

import com.google.gson.reflect.TypeToken;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.ClientAndServer;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Class containing constants that are used in the launcher configuration package.
 * <p>
 * NOTE: This class should NOT contain display strings that are visible to the end-user. All localizable strings MUST be
 * stored and retrieved using {@link com.micatechnologies.minecraft.forgelauncher.consts.localization.LocalizationManager}.
 *
 * @author Mica Technologies
 * @version 1.0
 * @editors hawka97
 * @creator hawka97
 * @since 2.0
 */
@ClientAndServer
public class ConfigConstants
{

    /**
     * The name of the configuration file when stored on disk.
     *
     * @since 1.0
     */
    public static final String CONFIG_FILE_NAME = File.separator + "configuration.json";

    /**
     * The default value for minimum RAM.
     *
     * @since 1.0
     */
    public static final long MIN_RAM_MEGABYTES_DEFAULT = 512;

    /**
     * The default value for maximum RAM.
     *
     * @since 1.0
     */
    public static final long MAX_RAM_MEGABYTES_DEFAULT = 2048;

    /**
     * The default value for debug logging being enabled.
     *
     * @since 1.0
     */
    public static final boolean LOG_DEBUG_ENABLE_DEFAULT = false;

    /**
     * The default value for resizable windows being enabled.
     *
     * @since 1.0
     */
    public static final boolean RESIZE_WINDOWS_ENABLE_DEFAULT = false;

    /**
     * The default value for the installed mod packs list.
     *
     * @since 1.0
     */
    public static final List< String > MOD_PACKS_INSTALLED_DEFAULT = new ArrayList<>();

    /**
     * Key for accessing the value of minimum RAM.
     *
     * @since 1.0
     */
    public static final String MIN_RAM_KEY = "minRAM";

    /**
     * Key for accessing the value of maximum RAM.
     *
     * @since 1.0
     */
    public static final String MAX_RAM_KEY = "maxRAM";

    /**
     * Key for accessing the debug logging enable value.
     *
     * @since 1.0
     */
    public static final String LOG_DEBUG_ENABLE_KEY = "debug";

    /**
     * Key for accessing the resizable windows enable value.
     *
     * @since 1.0
     */
    public static final String RESIZE_WINDOWS_ENABLE_KEY = "resizableWindows";

    /**
     * Key for accessing the list of installed mod packs.
     *
     * @since 1.0
     */
    public static final String MOD_PACKS_INSTALLED_KEY = "modpacks";

    /**
     * Type token for installed mod packs list.
     *
     * @since 1.0
     */
    public static final Type modPacksListType = new TypeToken< List< String > >()
    {
    }.getType();
}

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

package com.micatechnologies.minecraft.forgelauncher.consts.localization;

import java.util.ResourceBundle;

/**
 * Class for managing the application localization components and access to the display string resources manifest in
 * multiple languages.
 *
 * @author Mica Technologies
 * @version 1.0
 * @creator hawka97
 * @editors hawka97
 * @since 1.0
 */
public class LocalizationManager
{
    /**
     * The base name of the display strings local resource bundle collection.
     *
     * @since 1.0
     */
    private static final String localResourceBundleDisplayStringsBaseName = "DisplayStrings";

    /**
     * The resource bundle for the display strings collection in the current locale.
     *
     * @since 1.0
     */
    private static final ResourceBundle localResourceBundle =
            ResourceBundle.getBundle( localResourceBundleDisplayStringsBaseName );

    /**
     * The initial value of the upper label on the progress window when fetching mod pack information.
     *
     * @since 1.0
     */
    public static final String MODPACK_INSTALL_FETCH_UPPER_LABEL =
            localResourceBundle.getString( "MODPACK_INSTALL_FETCH_UPPER_LABEL" );

    /**
     * The initial value of the lower label on the progress window when fetching mod pack information.
     *
     * @since 1.0
     */
    public static final String MODPACK_INSTALL_FETCH_LOWER_LABEL =
            localResourceBundle.getString( "MODPACK_INSTALL_FETCH_LOWER_LABEL" );

    /**
     * The initial value of the upper label on the progress window when installing or updating the runtime.
     *
     * @since 1.0
     */
    public static final String RUNTIME_INSTALL_PROGRESS_UPPER_LABEL =
            localResourceBundle.getString( "RUNTIME_INSTALL_PROGRESS_UPPER_LABEL" );

    /**
     * The initial value of the lower label on the progress window when installing or updating the runtime.
     *
     * @since 1.0
     */
    public static final String RUNTIME_INSTALL_PROGRESS_LOWER_LABEL =
            localResourceBundle.getString( "RUNTIME_INSTALL_PROGRESS_LOWER_LABEL" );
}

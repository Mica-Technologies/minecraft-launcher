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

import java.util.HashMap;
import java.util.Map;

/**
 * Class containing constants that are used in the Forge mod loader class.
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
public class ForgeConstants
{
    /**
     * File name of the version manifest for Forge, found inside the root of the Forge .jar file.
     *
     * @since 1.0
     */
    public static final String FORGE_JAR_VERSION_FILE_NAME = "version.json";

    /**
     * Key for accessing the ID field in a Forge version manifest.
     *
     * @since 1.0
     */
    public static final String FORGE_VERSION_MANIFEST_ID_KEY = "id";

    /**
     * Key for accessing the inherits from field in a Forge version manifest.
     *
     * @since 1.0
     */
    public static final String FORGE_VERSION_MANIFEST_INHERITS_FROM_KEY = "inheritsFrom";

    /**
     * Key for accessing the Minecraft arguments field in a Forge version manifest.
     *
     * @since 1.0
     */
    public static final String FORGE_VERSION_MANIFEST_MINECRAFT_ARGS_KEY = "minecraftArguments";

    /**
     * Key for accessing the main class field in a Forge version manifest.
     *
     * @since 1.0
     */
    public static final String FORGE_VERSION_MANIFEST_MAIN_CLASS_KEY = "mainClass";

    /**
     * Key for accessing the libraries array in a Forge version manifest.
     *
     * @since 1.0
     */
    public static final String FORGE_VERSION_MANIFEST_LIBRARIES_KEY = "libraries";

    /**
     * Key for accessing the name of a library in a Forge version manifest.
     *
     * @since 1.0
     */
    public static final String FORGE_VERSION_MANIFEST_LIBRARY_NAME_KEY = "name";

    /**
     * Key for accessing the server requirement field of a library in a Forge version manifest.
     *
     * @since 1.0
     */
    public static final String FORGE_VERSION_MANIFEST_LIBRARY_SERVER_REQ_KEY = "serverreq";

    /**
     * Key for accessing the client requirement field of a library in a Forge version manifest.
     *
     * @since 1.0
     */
    public static final String FORGE_VERSION_MANIFEST_LIBRARY_CLIENT_REQ_KEY = "clientreq";
}

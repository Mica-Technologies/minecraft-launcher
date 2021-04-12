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

package com.micatechnologies.minecraft.launcher.consts;

/**
 * Class of constants used by mod pack and Minecraft manifest object classes.
 * <p>
 * NOTE: This class should NOT contain display strings that are visible to the end-user. All localizable strings MUST be
 * stored and retrieved using {@link com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager}.
 *
 * @author Mica Technologies
 * @version 1.0
 * @creator hawka97
 * @editors hawka97
 * @since 2.0
 */
public class ManifestConstants
{
    /**
     * Key for replacement of Minecraft asset folder name in full asset URL.
     *
     * @since 1.0
     */
    public static final String MINECRAFT_ASSET_SERVER_URL_FOLDER_KEY = "folder";

    /**
     * Key for replacement of Minecraft asset hash in full asset URL.
     *
     * @since 1.0
     */
    public static final String MINECRAFT_ASSET_SERVER_URL_HASH_KEY = "hash";

    /**
     * URL template for downloading Minecraft assets after populating the placeholder asset folder name and asset hash
     * values.
     *
     * @since 1.0
     */
    public static final String MINECRAFT_ASSET_SERVER_URL_TEMPLATE = "http://resources.download.minecraft.net/" +
            MINECRAFT_ASSET_SERVER_URL_FOLDER_KEY +
            "/" +
            MINECRAFT_ASSET_SERVER_URL_HASH_KEY;

    /**
     * File extension for JSON manifest files.
     *
     * @since 1.0
     */
    public static final String JSON_FILE_EXTENSION = ".json";

    /**
     * JSON key for accessing the asset objects in a Minecraft asset manifest.
     *
     * @since 1.0
     */
    public static final String MINECRAFT_ASSET_MANIFEST_OBJECTS_KEY = "objects";

    public static final String MINECRAFT_ASSET_MANIFEST_OBJECT_HASH_KEY = "hash";
    public static final String MINECRAFT_LIBRARY_MANIFEST_LIBRARIES_KEY="libraries";
    public static final String MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY = "downloads";
    public static final String MINECRAFT_LIBRARY_MANIFEST_LIBRARY_NAME_KEY = "name";
    public static final String MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_ARTIFACT_KEY = "artifact";
}

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

public class ManifestConstants
{
    public static final String MINECRAFT_ASSET_SERVER_URL_FOLDER_KEY = "{folder}";
    public static final String MINECRAFT_ASSET_SERVER_URL_HASH_KEY = "{hash}";
    public static final String MINECRAFT_ASSET_SERVER_URL_TEMPLATE =
            "http://resources.download.minecraft.net/" + MINECRAFT_ASSET_SERVER_URL_FOLDER_KEY + "/" +
                    MINECRAFT_ASSET_SERVER_URL_HASH_KEY;
    public static final String JSON_FILE_EXTENSION = ".json";
}

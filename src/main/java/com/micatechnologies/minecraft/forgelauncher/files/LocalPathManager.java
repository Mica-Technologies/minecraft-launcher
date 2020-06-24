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

package com.micatechnologies.minecraft.forgelauncher.files;

import com.micatechnologies.minecraft.forgelauncher.consts.LocalPathConstants;

public class LocalPathManager
{
    public static String getLauncherLocalPath() {
        return "TODO: GET APATH";
    }

    public static String getLauncherConfigFolderPath() {
        return getLauncherLocalPath() + LocalPathConstants.CONFIG_FOLDER;
    }

    public static String getLauncherModpackFolderPath() {
        return getLauncherLocalPath() + LocalPathConstants.MODPACK_FOLDER;
    }

    public static String getLauncherLogFolderPath() {
        return getLauncherLocalPath() + LocalPathConstants.LOG_FOLDER;
    }

    public static String getLauncherRuntimeFolderPath() {
        return getLauncherLocalPath() + LocalPathConstants.RUNTIME_FOLDER;
    }
}

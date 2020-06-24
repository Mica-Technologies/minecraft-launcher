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

/**
 * Class for managing the download and usage of JREs required for Minecraft.
 *
 * @author Mica Technologies
 * @editors hawka97
 * @creator hawka97
 * @version 1.0
 */
public class RuntimeManager
{
    /**
     * The path to the downloaded and verified JRE 8 installation. This value is <code>null</code> until populated by
     * verifying the JRE 8 with {@link #verifyJre8()}.
     *
     * @since 1.0
     */
    private static String jre8VerifiedPath = null;

    /**
     * Verifies the integrity of the local JRE 8 installation, and downloads or replaces files as necessary. This method
     * must be called before calling {@link #getJre8Path()}.
     *
     * @since 1.0
     */
    public static void verifyJre8() {

    }

    public static String getJre8Path() {
        if ( jre8VerifiedPath == null ) {
            verifyJre8();
        }
        return jre8VerifiedPath;
    }
}

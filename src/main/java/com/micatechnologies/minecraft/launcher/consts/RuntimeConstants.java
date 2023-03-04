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

package com.micatechnologies.minecraft.launcher.consts;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import org.apache.commons.lang3.SystemUtils;

import java.io.File;

/**
 * Constants related to and required by the runtime management subsystem in the application.
 * <p>
 * NOTE: This class should NOT contain display strings that are visible to the end-user. All localizable strings MUST be
 * stored and retrieved using {@link com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager}.
 *
 * @author Mica Technologies
 * @version 1.1
 * @since 2.0
 */
public class RuntimeConstants
{
    /**
     * URL for the API providing the latest JRE information for Windows OSes.
     *
     * @since 1.1
     */
    public static final String JRE_8_WIN_API_URL
            = "https://api.bell-sw.com/v1/liberica/releases?version-feature=8&version-modifier=latest&bitness=64&installation-type=archive&os=windows&arch=x86&bundle-type=jre";

    /**
     * URL for the API providing the latest JRE information for macOS OSes.
     *
     * @since 1.1
     */
    public static final String JRE_8_MAC_API_URL
            = "https://api.bell-sw.com/v1/liberica/releases?version-feature=8&version-modifier=latest&bitness=64&installation-type=archive&os=macos&arch=x86&bundle-type=jre";

    /**
     * URL for the API providing the latest JRE information for Linux OSes.
     *
     * @since 1.1
     */
    public static final String JRE_8_LNX_API_URL
            = "https://api.bell-sw.com/v1/liberica/releases?version-feature=8&version-modifier=latest&bitness=64&installation-type=archive&os=linux&arch=x86&bundle-type=jre";

    /**
     * The name of the file containing the latest JRE 8 API data.
     *
     * @since 1.1
     */
    public static final String JRE_8_API_DATA_FILE_NAME = "jre8.api.latest.json";


    /**
     * The file path to the Java executable in an extracted JRE 8 install for Windows.
     *
     * @since 1.0
     */
    public static final String JRE_8_WIN_JAVA_EXEC_PATH = "bin" + File.separator + "java.exe";

    /**
     * The file path to the Java executable in an extracted JRE 8 install for macOS.
     *
     * @since 1.0
     */
    public static final String JRE_8_MAC_JAVA_EXEC_PATH = "bin" + File.separator + "java";

    /**
     * The file path to the Java executable in an extracted JRE 8 install for Linux.
     *
     * @since 1.0
     */
    public static final String JRE_8_LNX_JAVA_EXEC_PATH = "bin" + File.separator + "java";
}

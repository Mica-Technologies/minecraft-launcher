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

import java.io.File;

/**
 * Constants related to and required by the runtime management subsystem in the application.
 * <p>
 * NOTE: This class should NOT contain display strings that are visible to the end-user. All localizable strings MUST be
 * stored and retrieved using {@link com.micatechnologies.minecraft.forgelauncher.consts.localization.LocalizationManager}.
 *
 * @author Mica Technologies
 * @version 1.0
 * @creator hawka97
 * @editors hawka97
 * @since 2.0
 */
public class RuntimeConstants
{
    /**
     * Download URL for version of JRE 8 that is compatible with Windows.
     *
     * @since 1.0
     */
    public static final String JRE_8_WIN_URL
            = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_windows_hotspot_8u232b09.zip";

    /**
     * Download URL for version of JRE 8 that is compatible with macOS.
     *
     * @since 1.0
     */
    public static final String JRE_8_MAC_URL
            = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_mac_hotspot_8u232b09.tar.gz";

    /**
     * Download URL for version of JRE 8 that is compatible with Linux.
     *
     * @since 1.0
     */
    public static final String JRE_8_LNX_URL
            = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_linux_hotspot_8u232b09.tar.gz";

    /**
     * Download URL for SHA-256 hash of JRE 8 that is compatible with Windows.
     *
     * @since 1.0
     */
    public static final String JRE_8_WIN_HASH_URL = JRE_8_WIN_URL + ".sha256.txt";

    /**
     * Download URL for SHA-256 hash of JRE 8 that is compatible with macOS.
     *
     * @since 1.0
     */
    public static final String JRE_8_MAC_HASH_URL = JRE_8_MAC_URL + ".sha256.txt";

    /**
     * Download URL for SHA-256 hash of JRE 8 that is compatible with Linux.
     *
     * @since 1.0
     */
    public static final String JRE_8_LNX_HASH_URL = JRE_8_LNX_URL + ".sha256.txt";

    /**
     * The name of the resulting JRE 8 folder on extraction from its archive.
     *
     * @since 1.0
     */
    public static final String JRE_8_EXTRACTED_FOLDER_NAME = "jdk8u232-b09-jre";

    /**
     * The file name of the downloaded JRE 8 archive file.
     *
     * @since 1.0
     */
    public static final String JRE_8_ARCHIVE_FILE_NAME = "jre.8";

    /**
     * The file name of the SHA-256 hash of the downloaded JRE 8 archive file.
     *
     * @since 1.0
     */
    public static final String JRE_8_HASH_FILE_NAME = "hash.jre.8";

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
    public static final String JRE_8_MAC_JAVA_EXEC_PATH = "Contents" +
            File.separator +
            "Home" +
            File.separator +
            "bin" +
            File.separator +
            "java";

    /**
     * The file path to the Java executable in an extracted JRE 8 install for Linux.
     *
     * @since 1.0
     */
    public static final String JRE_8_LNX_JAVA_EXEC_PATH = "bin" + File.separator + "java";

}

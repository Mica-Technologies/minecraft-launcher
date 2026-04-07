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

import java.io.File;

/**
 * Constants related to and required by the runtime management subsystem in the application. Uses Mojang's official
 * Java runtime distribution to ensure perfect Minecraft compatibility.
 *
 * @author Mica Technologies
 * @version 3.0
 * @since 2.0
 */
public class RuntimeConstants
{
    /**
     * The default Java runtime component to use when no version information is available from the manifest.
     *
     * @since 3.0
     */
    public static final String DEFAULT_RUNTIME_COMPONENT = "jre-legacy";

    /**
     * The default Java major version to use when no version information is available from the manifest.
     *
     * @since 2.0
     */
    public static final int DEFAULT_JAVA_MAJOR_VERSION = 8;

    /**
     * URL of the Mojang Java runtime index, listing all available runtime components per platform.
     *
     * @since 3.0
     */
    public static final String MOJANG_RUNTIME_INDEX_URL
            = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    /**
     * Cached file name for the Mojang runtime index.
     *
     * @since 3.0
     */
    public static final String MOJANG_RUNTIME_INDEX_FILE_NAME = "mojang-runtime-index.json";

    /**
     * Subfolder name template for per-component runtime storage.
     *
     * @since 3.0
     */
    public static final String RUNTIME_COMPONENT_SUBFOLDER_TEMPLATE = "{COMPONENT}";

    /**
     * File name for the cached component manifest within a runtime folder.
     *
     * @since 3.0
     */
    public static final String RUNTIME_MANIFEST_FILE_NAME = "runtime-manifest.json";

    /**
     * File name for storing the installed version name within a runtime folder.
     *
     * @since 3.0
     */
    public static final String RUNTIME_VERSION_FILE_NAME = ".version";

    /**
     * The file path to the Java executable within an extracted JRE for Windows.
     *
     * @since 1.0
     */
    public static final String WIN_JAVA_EXEC_PATH = "bin" + File.separator + "java.exe";

    /**
     * The file path to the Java executable within an extracted JRE for macOS.
     *
     * @since 1.0
     */
    public static final String MAC_JAVA_EXEC_PATH = "jre.bundle" + File.separator + "Contents" + File.separator +
            "Home" + File.separator + "bin" + File.separator + "java";

    /**
     * The file path to the Java executable within an extracted JRE for Linux.
     *
     * @since 1.0
     */
    public static final String LNX_JAVA_EXEC_PATH = "bin" + File.separator + "java";

    /**
     * Returns the Mojang platform key for the current OS and architecture.
     *
     * @return the Mojang platform key (e.g. "windows-x64", "mac-os-arm64", "linux")
     *
     * @since 3.0
     */
    public static String getMojangPlatformKey() {
        String osArch = System.getProperty( "os.arch", "" );
        boolean isArm = osArch.contains( "aarch64" ) || osArch.contains( "arm" );

        if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            return isArm ? "windows-arm64" : "windows-x64";
        }
        else if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
            return isArm ? "mac-os-arm64" : "mac-os";
        }
        else {
            // Linux
            return osArch.contains( "86" ) || osArch.contains( "32" ) ? "linux-i386" : "linux";
        }
    }

    /**
     * Returns the path to the Java executable within an extracted JRE for the current OS.
     *
     * @return the relative path to the java executable
     *
     * @since 2.0
     */
    public static String getJavaExecPathForOs() {
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            return WIN_JAVA_EXEC_PATH;
        }
        else if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
            return MAC_JAVA_EXEC_PATH;
        }
        return LNX_JAVA_EXEC_PATH;
    }
}

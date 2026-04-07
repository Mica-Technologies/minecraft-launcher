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

import com.micatechnologies.minecraft.launcher.LauncherCore;

import java.util.Map;

/**
 * Class of constants/statics for use across package.
 * <p>
 * NOTE: This class should NOT contain display strings that are visible to the end-user. All localizable strings MUST be
 * stored and retrieved using {@link com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager}.
 *
 * @author Mica Technologies
 * @version 1.1
 * @since 1.0
 */
public class LauncherConstants
{
    /**
     * Launcher boolean indicating if the application is in development mode. True when:
     * 1. Running from IDE (no implementation version in manifest), OR
     * 2. Built with the {@code -Pdev} Maven profile (manifest contains {@code Launcher-Environment: DEV})
     */
    public final static boolean LAUNCHER_IS_DEV = detectDevMode();

    /**
     * Launcher application name. Auto-filled from manifest, with DEV suffix when in dev mode.
     *
     * @since 1.0
     */
    public final static String LAUNCHER_APPLICATION_NAME = resolveAppName();

    /**
     * Launcher application version. Auto-filled from manifest, defaults to "0.0.1" in IDE dev mode.
     *
     * @since 1.0
     */
    public final static String LAUNCHER_APPLICATION_VERSION = LauncherCore.class.getPackage()
                                                                                .getImplementationVersion() != null ?
                                                              LauncherCore.class.getPackage()
                                                                                .getImplementationVersion() :
                                                              "0.0.1";

    /**
     * Launcher application name without spaces.
     *
     * @since 1.0
     */
    public final static String LAUNCHER_APPLICATION_NAME_TRIMMED = LAUNCHER_APPLICATION_NAME.replaceAll( " ", "" );

    private static boolean detectDevMode() {
        // No manifest version means running from IDE -- always dev mode
        if ( LauncherCore.class.getPackage().getImplementationVersion() == null ) {
            return true;
        }
        // Check for Launcher-Environment: DEV manifest entry (set by -Pdev Maven profile)
        try {
            java.util.jar.Manifest manifest = new java.util.jar.Manifest(
                    LauncherCore.class.getResourceAsStream( "/META-INF/MANIFEST.MF" ) );
            String env = manifest.getMainAttributes().getValue( "Launcher-Environment" );
            return "DEV".equalsIgnoreCase( env );
        }
        catch ( Exception e ) {
            return false;
        }
    }

    private static String resolveAppName() {
        String title = LauncherCore.class.getPackage().getImplementationTitle();
        if ( title != null ) {
            // If dev mode is active but the manifest title doesn't include DEV, append it
            if ( LAUNCHER_IS_DEV && !title.toUpperCase().contains( "DEV" ) ) {
                return title + " DEV";
            }
            return title;
        }
        return "Mica Minecraft Launcher DEV";
    }

    /**
     * Argument used to open application in forced client mode.
     *
     * @since 1.0
     */
    public static final String PROGRAM_ARG_CLIENT_MODE = "-c";

    /**
     * Argument used to open application in forced server mode.
     *
     * @since 1.0
     */
    public static final String PROGRAM_ARG_SERVER_MODE = "-s";

    /**
     * The minimum value allowed for the minimum RAM configuration in settings.
     *
     * @since 1.1
     */
    public static final double SETTINGS_MIN_RAM_MIN = 0.2;

    /**
     * The maximum value allowed for the minimum RAM configuration in settings.
     *
     * @since 1.1
     */
    public static final double SETTINGS_MIN_RAM_MAX = 32.0;

    /**
     * The maximum value allowed for the maximum RAM configuration in settings.
     *
     * @since 1.1
     */
    public static final double SETTINGS_MAX_RAM_MIN = 1.0;

    /**
     * The maximum value allowed for the maximum RAM configuration in settings.
     *
     * @since 1.1
     */
    public static final double SETTINGS_MAX_RAM_MAX = 64.0;

    /**
     * Exit code used when the launcher is closing cleanly/normally.
     *
     * @since 1.1
     */
    public static final int EXIT_STATUS_CODE_GOOD = 0;

    /**
     * Port used by the single-instance lock socket for release builds. Chosen to be high and unlikely to conflict.
     *
     * @since 2.0
     */
    public static final int SINGLE_INSTANCE_PORT = 47821;

    /**
     * Port used by the single-instance lock socket for development builds. Separate from release so both can run
     * simultaneously.
     *
     * @since 2.0
     */
    public static final int SINGLE_INSTANCE_PORT_DEV = 47822;

    /**
     * Map containing the JVM properties that must be applied at startup of each instance of the application.
     *
     * @since 1.1
     */
    public static final Map< String, String > JVM_PROPERTIES = Map.of( "prism.lcdtext", "false", "prism.text", "t2k",
                                                                       "prism.order", "sw","sun.net.http.allowRestrictedHeaders","true" );
}

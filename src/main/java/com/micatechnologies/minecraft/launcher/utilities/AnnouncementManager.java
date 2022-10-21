/*
 * Copyright (c) 2022 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.utilities;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Utility class for managing the check for and retrieval of announcements from the launcher repository.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1.1
 */
public class AnnouncementManager
{
    /**
     * The URL of the announcements JSON in the launcher repository.
     *
     * @since 1.0
     */
    private static final String ANNOUNCEMENT_URL
            = "https://raw.githubusercontent.com/Mica-Technologies/minecraft-launcher-modpacks/main/launcher-remote-config/announce.json";

    /**
     * The key used to access the JSON value for the announcement shown to the user on the login screen.
     *
     * @since 1.0
     */
    private static final String ANNOUNCEMENT_JSON_LOGIN_KEY = "login";

    /**
     * The key used to access the JSON value for the announcement shown to the user on the home screen.
     *
     * @since 1.0
     */
    private static final String ANNOUNCEMENT_JSON_HOME_KEY = "home";

    /**
     * The key used to access the JSON value for the announcement shown to the user on the configuration screen.
     *
     * @since 1.0
     */
    private static final String ANNOUNCEMENT_JSON_CONFIG_KEY = "config";

    /**
     * The key used to access the JSON value for the announcement shown to the user on the modpacks edit screen.
     *
     * @since 1.0
     */
    private static final String ANNOUNCEMENT_JSON_MODPACKS_EDIT_KEY = "modpacksedit";

    /**
     * The announcement shown to the user on the login screen.
     *
     * @since 1.0
     */
    private static String announcementLogin = "";

    /**
     * The announcement shown to the user on the home screen.
     *
     * @since 1.0
     */
    private static String announcementHome = "";

    /**
     * The announcement shown to the user on the configuration screen.
     *
     * @since 1.0
     */
    private static String announcementConfig = "";

    /**
     * The announcement shown to the user on the modpacks edit screen.
     *
     * @since 1.0
     */
    private static String announcementModpacksEdit = "";

    /**
     * Checks for new announcements from the launcher repository. If new announcements are found, they are retrieved and
     * stored in the appropriate variables.
     *
     * @since 1.0
     */
    public static void checkAnnouncements() {
        // Download announcements JSON from launcher repository
        JsonObject announcementJson = null;
        try {
            String manifestBody = IOUtils.toString( new URL( ANNOUNCEMENT_URL ), Charset.defaultCharset() );
            announcementJson = new Gson().fromJson( manifestBody, JsonObject.class );
        }
        catch ( Exception e ) {
            Logger.logError( "The launcher announcements could not be loaded." );
            Logger.logThrowable( e );
        }

        // Parse announcements JSON
        if ( announcementJson != null ) {
            // Check for login announcement
            if ( announcementJson.has( ANNOUNCEMENT_JSON_LOGIN_KEY ) ) {
                announcementLogin = announcementJson.get( ANNOUNCEMENT_JSON_LOGIN_KEY ).getAsString();
            }

            // Check for home announcement
            if ( announcementJson.has( ANNOUNCEMENT_JSON_HOME_KEY ) ) {
                announcementHome = announcementJson.get( ANNOUNCEMENT_JSON_HOME_KEY ).getAsString();
            }

            // Check for config announcement
            if ( announcementJson.has( ANNOUNCEMENT_JSON_CONFIG_KEY ) ) {
                announcementConfig = announcementJson.get( ANNOUNCEMENT_JSON_CONFIG_KEY ).getAsString();
            }

            // Check for modpacks edit announcement
            if ( announcementJson.has( ANNOUNCEMENT_JSON_MODPACKS_EDIT_KEY ) ) {
                announcementModpacksEdit = announcementJson.get( ANNOUNCEMENT_JSON_MODPACKS_EDIT_KEY ).getAsString();
            }
        }
    }

    /**
     * Gets the string announcement for the login screen.
     *
     * @return String announcement for the login screen.
     *
     * @since 1.0
     */
    public static String getAnnouncementLogin() {
        return announcementLogin;
    }

    /**
     * Gets the string announcement for the home screen.
     *
     * @return String announcement for the home screen.
     *
     * @since 1.0
     */
    public static String getAnnouncementHome() {
        return announcementHome;
    }

    /**
     * Gets the string announcement for the configuration screen.
     *
     * @return String announcement for the configuration screen.
     *
     * @since 1.0
     */
    public static String getAnnouncementConfig() {
        return announcementConfig;
    }

    /**
     * Gets the string announcement for the modpacks edit screen.
     *
     * @return String announcement for the modpacks edit screen.
     *
     * @since 1.0
     */
    public static String getAnnouncementModpacksEdit() {
        return announcementModpacksEdit;
    }
}

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

import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
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
 * @version 1.1
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
            = "https://micauseaststorage.blob.core.windows.net/mc-launcher-api/launcher-remote-config/announce.json";

    /** Hard cap on the announcement-JSON response size. The schema is a small fixed
     *  set of short strings; 256 KB is far above realistic legitimate sizes and bounds
     *  the OOM-via-pathological-body risk if the host is ever compromised. */
    private static final long ANNOUNCEMENT_MAX_BYTES = 256L * 1024;

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
     * The key used to access the boolean which controls whether gameplay is disabled based on the announcements.
     *
     * @since 1.1
     */
    private static final String DISABLE_GAMEPLAY_KEY = "disableGameplay";

    /**
     * The key used to access the boolean which controls whether modpack editing is disabled based on the
     * announcements.
     *
     * @since 1.1
     */
    private static final String DISABLE_MODPACKS_EDIT_KEY = "disableModpacksEdit";

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
     * The boolean which controls whether gameplay is disabled based on the announcements.
     *
     * @since 1.1
     */
    private static boolean disableGameplay = false;

    /**
     * The boolean which controls whether modpack editing is disabled based on the announcements.
     *
     * @since 1.1
     */
    private static boolean disableModpacksEdit = false;

    /**
     * In-flight (or completed) future for the background announcement fetch kicked off by
     * the launcher startup path. Lets the main menu attach a re-render hook that surfaces
     * the banner once the network call settles, instead of blocking cold-start on it.
     *
     * @since 3.5
     */
    private static volatile java.util.concurrent.CompletableFuture< Void > checkFuture = null;

    /**
     * Starts the announcement fetch in the background and returns the in-flight future.
     * Idempotent — repeated calls return the same future until the fetch completes, at
     * which point a fresh call starts a new fetch.
     *
     * @return the in-flight (or just-started) future
     *
     * @since 3.5
     */
    public static synchronized java.util.concurrent.CompletableFuture< Void > startCheckAsync() {
        java.util.concurrent.CompletableFuture< Void > existing = checkFuture;
        if ( existing != null && !existing.isDone() ) {
            return existing;
        }
        checkFuture = java.util.concurrent.CompletableFuture.runAsync(
                AnnouncementManager::checkAnnouncements );
        return checkFuture;
    }

    /**
     * Returns the in-flight (or last-completed) announcement-check future, or {@code null}
     * if no check has been started yet.
     *
     * @since 3.5
     */
    public static java.util.concurrent.CompletableFuture< Void > getCheckFuture() {
        return checkFuture;
    }

    /**
     * Checks for new announcements from the launcher repository. If new announcements are found, they are retrieved and
     * stored in the appropriate variables.
     *
     * @since 1.0
     */
    public static void checkAnnouncements() {
        // Download announcements JSON from launcher repository. Capped at 256 KB —
        // the schema is a fixed bounded set of short strings, and an unbounded body
        // from a compromised host would otherwise OOM the launcher on its way through
        // Gson.
        JsonObject announcementJson = null;
        try {
            String manifestBody = NetworkUtilities.downloadFileFromURLBounded(
                    ANNOUNCEMENT_URL, ANNOUNCEMENT_MAX_BYTES );
            announcementJson = JSONUtilities.getGson().fromJson( manifestBody, JsonObject.class );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.get( "log.announcement.loadFailed" ) );
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

            // Check for gameplay disable
            if ( announcementJson.has( DISABLE_GAMEPLAY_KEY ) ) {
                disableGameplay = announcementJson.get( DISABLE_GAMEPLAY_KEY ).getAsBoolean();
            }

            // Check for modpacks edit disable
            if ( announcementJson.has( DISABLE_MODPACKS_EDIT_KEY ) ) {
                disableModpacksEdit = announcementJson.get( DISABLE_MODPACKS_EDIT_KEY ).getAsBoolean();
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

    /**
     * Session-scoped dismissed-announcement registry. A {@link java.util.Set} of
     * announcement strings the user has dismissed via the banner's ✕ button —
     * deliberately in-memory only so dismissal resets on next launcher startup
     * (the user explicitly asked for "show up again on the next app launch"
     * behavior rather than persistent muting).
     *
     * <p>Keyed by the exact announcement text the user saw, so if the announcement
     * content changes mid-session the new text doesn't match a dismissed entry and
     * the banner reappears — which is the right call: a CHANGED announcement is
     * new information the user hasn't yet been shown.
     */
    private static final java.util.Set< String > dismissedAnnouncements =
            java.util.Collections.synchronizedSet( new java.util.HashSet<>() );

    /**
     * Marks the given announcement text as dismissed for the remainder of this
     * launcher session. Idempotent — re-dismissing the same text is a no-op.
     *
     * @param text the announcement string the user just dismissed
     *
     * @since 3.4
     */
    public static void dismissAnnouncementForSession( String text ) {
        if ( text != null && !text.isEmpty() ) {
            dismissedAnnouncements.add( text );
        }
    }

    /**
     * @param text the announcement string to test
     * @return true iff this exact announcement text has been dismissed in this session
     *
     * @since 3.4
     */
    public static boolean isAnnouncementDismissed( String text ) {
        return text != null && dismissedAnnouncements.contains( text );
    }

    /**
     * Gets the boolean which controls whether gameplay is disabled based on the announcements.
     *
     * @return Boolean which controls whether gameplay is disabled based on the announcements.
     *
     * @since 1.1
     */
    public static boolean getDisableGameplay() {
        return disableGameplay;
    }

    /**
     * Gets the boolean which controls whether modpack editing is disabled based on the announcements.
     *
     * @return Boolean which controls whether modpack editing is disabled based on the announcements.
     *
     * @since 1.1
     */
    public static boolean getDisableModpacksEdit() {
        return disableModpacksEdit;
    }
}

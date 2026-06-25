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

package com.micatechnologies.minecraft.launcher.utilities;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.UpdateCheckConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;

import java.io.File;
import java.io.IOException;

/**
 * Checks the launcher's GitHub releases API for the latest published release.
 * Results are memoized for the process lifetime after the first successful
 * fetch, so repeated queries don't re-hit the network.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 1.0
 */
public class UpdateCheckUtilities
{
    /** Cached latest release version tag; {@code null} until first fetched. */
    private static String latestReleaseVersion = null;
    /** Cached latest release page URL; {@code null} until first fetched. */
    private static String latestReleaseURL     = null;

    /**
     * Returns the latest released launcher version, fetching it from the GitHub
     * releases API on first call and returning the memoized value thereafter.
     *
     * @return the latest release version tag, or {@code null} if the API
     *         response was empty/malformed
     *
     * @throws IOException if the version information could not be downloaded
     * @since 1.0
     */
    public static String getLatestReleaseVersion() throws IOException {
        if ( latestReleaseVersion == null ) {
            fetchLatestInformation();
        }

        return latestReleaseVersion;
    }

    /**
     * Returns the URL of the latest release, fetching it from the GitHub
     * releases API on first call and returning the memoized value thereafter.
     *
     * @return the latest release page URL, or {@code null} if the API
     *         response was empty/malformed
     *
     * @throws IOException if the version information could not be downloaded
     * @since 1.0
     */
    public static String getLatestReleaseURL() throws IOException {
        if ( latestReleaseURL == null ) {
            fetchLatestInformation();
        }

        return latestReleaseURL;
    }

    /**
     * Downloads the latest-release JSON from the GitHub releases API and
     * populates {@link #latestReleaseVersion} / {@link #latestReleaseURL}. Logs
     * and leaves the fields untouched when the response is empty or missing the
     * expected keys.
     *
     * @throws IOException if the release information could not be downloaded
     * @since 1.0
     */
    private static void fetchLatestInformation() throws IOException {
        // Download latest version information from launcher GitHub releases API
        File latestVersionInfoFile = SynchronizedFileManager.getSynchronizedFile(
                LocalPathManager.getUpdateInfoFilePath() );
        NetworkUtilities.downloadFileFromURL( UpdateCheckConstants.UPDATE_CHECK_API_URL, latestVersionInfoFile,
                                              "application/vnd.github.v3+json" );

        // Extract latest version number and URL from JSON
        JsonObject releaseInfoObject = FileUtilities.readAsJsonObject( latestVersionInfoFile );
        if ( releaseInfoObject.size() > 0 &&
                releaseInfoObject.has( UpdateCheckConstants.UPDATE_CHECK_LATEST_VERSION_KEY ) &&
                releaseInfoObject.has( UpdateCheckConstants.UPDATE_CHECK_LATEST_URL_KEY ) ) {
            latestReleaseVersion = releaseInfoObject.get( UpdateCheckConstants.UPDATE_CHECK_LATEST_VERSION_KEY )
                                                    .getAsString();
            latestReleaseURL = releaseInfoObject.get( UpdateCheckConstants.UPDATE_CHECK_LATEST_URL_KEY ).getAsString();
        }
        else {
            Logger.logStd( LocalizationManager.get( "log.updateCheck.emptyApiResponse" ) );
        }
    }
}

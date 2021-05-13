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

package com.micatechnologies.minecraft.forgelauncher.utilities;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.forgelauncher.consts.UpdateCheckConstants;
import com.micatechnologies.minecraft.forgelauncher.files.LocalPathManager;
import com.micatechnologies.minecraft.forgelauncher.files.Logger;
import com.micatechnologies.minecraft.forgelauncher.files.SynchronizedFileManager;

import java.io.File;
import java.io.IOException;

public class UpdateCheckUtilities
{
    private static String latestReleaseVersion = null;
    private static String latestReleaseURL     = null;

    public static String getLatestReleaseVersion() throws IOException {
        if (latestReleaseVersion == null) fetchLatestInformation();

        return latestReleaseVersion;
    }

    public static String getLatestReleaseURL() throws IOException {
        if (latestReleaseURL == null) fetchLatestInformation();

        return latestReleaseURL;
    }

    private static void fetchLatestInformation() throws IOException {
        // Download latest version information from launcher releases API
        File latestVersionInfoFile = SynchronizedFileManager.getSynchronizedFile(
                LocalPathManager.getUpdateInfoFilePath() );
        NetworkUtilities.downloadFileFromURL( UpdateCheckConstants.UPDATE_CHECK_API_URL, latestVersionInfoFile );

        // Get latest version information file JSON contents
        JsonArray latestVersionInfoFileContents = FileUtilities.readAsJsonArray( latestVersionInfoFile );

        // Extract latest release from JSON
        if ( latestVersionInfoFileContents.size() > 0 ) {
            JsonObject latestReleaseInfoObject = latestVersionInfoFileContents.get( 0 ).getAsJsonObject();
            latestReleaseVersion = latestReleaseInfoObject.get( UpdateCheckConstants.UPDATE_CHECK_API_RELEASE_TAG_KEY )
                                                          .getAsString();
            latestReleaseURL = latestReleaseInfoObject.get( UpdateCheckConstants.UPDATE_CHECK_API_RELEASE_LINKS_KEY )
                                                      .getAsJsonObject()
                                                      .get( UpdateCheckConstants.UPDATE_CHECK_API_RELEASE_LINKS_SELF_KEY )
                                                      .getAsString();
        }
        else {
            Logger.logStd( "Unable to check for an available launcher update because the releases API " +
                                   "did not return a populated response." );
        }
    }
}
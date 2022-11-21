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

package com.micatechnologies.minecraft.launcher.utilities

import com.google.gson.JsonObject
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants
import com.micatechnologies.minecraft.launcher.files.{Logger, SynchronizedFileManager}

/**
 * A utility object for launcher update related constants and functions.
 *
 * @since 2023.1
 * @version 2.0
 * @author Mica Technologies
 */
object LauncherUpdateUtilities {

  /**
   * The API URL to check for the latest launcher update information.
   *
   * @since 2.0
   */
  private val UPDATE_CHECK_API_URL = "https://api.github.com/repos/Mica-Technologies/minecraft-launcher/releases/latest"

  /**
   * The key used to access the URL of the latest launcher update.
   *
   * @since 2.0
   */
  private val UPDATE_CHECK_LATEST_URL_KEY = "html_url"

  /**
   * The key used to access the version of the latest launcher update.
   *
   * @since 2.0
   */
  private val UPDATE_CHECK_LATEST_VERSION_KEY = "tag_name"

  /**
   * The latest available release version of the launcher.
   *
   * @since 1.0
   */
  private var latestReleaseVersion: String = _

  /**
   * The latest available release download URL of the launcher.
   *
   * @since 1.0
   */
  private var latestReleaseVersionURL: String = _

  /**
   * Gets the latest available release version of the launcher.
   *
   * @since 1.0
   * @return latest available release version of the launcher
   */
  def getLatestReleaseVersion: String = {
    if (latestReleaseVersion == null) {
      fetchLatestInformation();
    }
    latestReleaseVersion
  }

  /**
   * Gets the latest available release download URL of the launcher.
   *
   * @since 1.0
   * @return latest available release download URL of the launcher
   */
  def getLatestReleaseVersionURL: String = {
    if (latestReleaseVersionURL == null) {
      fetchLatestInformation();
    }
    latestReleaseVersionURL
  }

  /**
   * Fetches the latest available release version and download URL of the launcher.
   *
   * @since 1.0
   */
  private def fetchLatestInformation(): Unit = {
    // Download the latest version information from launcher GitHub releases API
    val latestVersionInfoFile = SynchronizedFileManager.getSynchronizedFile(LocalPathConstants
      .LAUNCHER_UPDATE_INFO_FILE_PATH)
    NetworkUtilities.downloadFileFromURL(UPDATE_CHECK_API_URL, latestVersionInfoFile, "application/vnd.github.v3+json")

    // Extract the latest version number and URL from JSON
    val releaseInfoObject: JsonObject = FileUtilities.readAsJsonObject(latestVersionInfoFile)
    if (releaseInfoObject.size > 0) {
      latestReleaseVersion = releaseInfoObject.get(UPDATE_CHECK_LATEST_VERSION_KEY).getAsString
      latestReleaseVersionURL = releaseInfoObject.get(UPDATE_CHECK_LATEST_URL_KEY).getAsString
    }
    else {
      Logger.logStd("Unable to check for an available launcher update because the releases API did not return a populated response.")
    }
  }
}

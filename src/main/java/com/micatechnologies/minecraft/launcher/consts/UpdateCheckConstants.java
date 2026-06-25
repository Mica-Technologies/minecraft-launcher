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

/**
 * Constants used by the launcher's self-update check, which queries the GitHub
 * Releases API to determine whether a newer launcher release than the running
 * one is available.
 * <p>
 * NOTE: This class should NOT contain display strings that are visible to the
 * end-user. All localizable strings MUST be stored and retrieved using
 * {@link com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager}.
 *
 * @author Mica Technologies
 * @since 1.0.1
 */
public class UpdateCheckConstants
{
    /**
     * Launcher update check URL. This URL is checked to see if the latest release is newer than the current release.
     *
     * @since 1.0.1
     */
    public final static String UPDATE_CHECK_API_URL = "https://api.github.com/repos/Mica-Technologies/minecraft" +
            "-launcher/releases/latest";

    /**
     * JSON key in the GitHub Releases API response identifying the human-facing
     * URL of the latest release, used to direct the user to the download page
     * when a newer launcher version is available.
     *
     * @since 1.0.1
     */
    public final static String UPDATE_CHECK_LATEST_URL_KEY = "html_url";

    /**
     * JSON key in the GitHub Releases API response identifying the tag name of
     * the latest release, compared against the running launcher's version to
     * decide whether an update is available.
     *
     * @since 1.0.1
     */
    public final static String UPDATE_CHECK_LATEST_VERSION_KEY    = "tag_name";
}

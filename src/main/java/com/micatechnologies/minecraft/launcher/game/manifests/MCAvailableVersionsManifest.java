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

package com.micatechnologies.minecraft.launcher.game.manifests;

import java.util.Date;
import java.util.List;

/**
 * The manifest of available Minecraft versions and their applicable information, including URL for the corresponding
 * package JSON.
 *
 * @author Mica Technologies
 * @version 1.0
 * @implNote Adapted from https://minecraft.fandom.com/wiki/Version_manifest.json
 * @since 2022.1
 */
public class MCAvailableVersionsManifest
{
    ///region: Instance Fields

    /**
     * The object containing information about the latest Minecraft release and snapshot versions.
     *
     * @since 1.0
     */
    private Latest latest;

    /**
     * The list containing an array of information about the available Minecraft versions.
     *
     * @since 1.0
     */
    private List< Version > versions;

    /**
     * Get the object containing information about the latest Minecraft release and snapshot versions.
     *
     * @return object containing information about the latest Minecraft release and snapshot versions
     *
     * @since 1.0
     */
    public Latest getLatest() {
        return latest;
    }

    /**
     * Get the list containing an array of information about the available Minecraft versions.
     *
     * @return the list containing an array of information about the available Minecraft versions
     *
     * @since 1.0
     */
    public List< Version > getVersions() {
        return versions;
    }

    /**
     * Class object for storing information about the latest Minecraft release and snapshot versions.
     *
     * @author Mica Technologies
     * @version 1.0
     * @implNote Adapted from https://minecraft.fandom.com/wiki/Version_manifest.json
     * @since 1.0
     */
    public static class Latest
    {
        /**
         * The latest supported Minecraft release version number.
         *
         * @since 1.0
         */
        private String release;

        /**
         * The latest supported Minecraft snapshot version number.
         *
         * @since 1.0
         */
        private String snapshot;

        /**
         * Get the latest supported Minecraft release version number.
         *
         * @return the latest supported Minecraft release version number
         *
         * @since 1.0
         */
        public String getRelease() {
            return release;
        }

        /**
         * Get the latest supported Minecraft snapshot version number.
         *
         * @return the latest supported Minecraft snapshot version number
         *
         * @since 1.0
         */
        public String getSnapshot() {
            return snapshot;
        }
    }

    /**
     * Class object for storing information about each available Minecraft version.
     *
     * @author Mica Technologies
     * @version 1.0
     * @implNote Adapted from https://minecraft.fandom.com/wiki/Version_manifest.json
     * @since 1.0
     */
    public static class Version
    {
        /**
         * The ID of the available Minecraft version.
         *
         * @since 1.0
         */
        private String id;

        /**
         * The type of the available Minecraft version (Release, Snapshot).
         *
         * @since 1.0
         */
        private String type;

        /**
         * The URL for the download manifest of the available Minecraft version.
         *
         * @since 1.0
         */
        private String url;

        /**
         * The last updated time of the available Minecraft version.
         *
         * @since 1.0
         */
        private Date time;

        /**
         * The release time of the available Minecraft version.
         *
         * @since 1.0
         */
        private Date releaseTime;

        /**
         * The SHA-1 hash of the available Minecraft version.
         *
         * @since 1.0
         */
        private String sha1;

        /**
         * The compliance level of the available Minecraft version. If 0, the launcher warns the user about this version
         * not being recent enough to support the latest player safety features. Its value is 1 otherwise.
         *
         * @since 1.0
         */
        private int complianceLevel;

        /**
         * Get the ID of the available Minecraft version.
         *
         * @return ID of the available Minecraft version
         *
         * @since 1.0
         */
        public String getId() {
            return id;
        }

        /**
         * Get the type of the available Minecraft version (Release, Snapshot).
         *
         * @return type of the available Minecraft version
         *
         * @since 1.0
         */
        public String getType() {
            return type;
        }

        /**
         * Get the URL for the download manifest of the available Minecraft version.
         *
         * @return URL for the download manifest of the available Minecraft version
         *
         * @since 1.0
         */
        public String getUrl() {
            return url;
        }

        /**
         * Get the last updated time of the available Minecraft version.
         *
         * @return the last updated time of the available Minecraft version
         *
         * @since 1.0
         */
        public Date getTime() {
            return time;
        }

        /**
         * Get the release time of the available Minecraft version.
         *
         * @return the release time of the available Minecraft version
         *
         * @since 1.0
         */
        public Date getReleaseTime() {
            return releaseTime;
        }

        /**
         * Get the SHA-1 hash of the available Minecraft version.
         *
         * @return the SHA-1 hash of the available Minecraft version
         *
         * @since 1.0
         */
        public String getSha1() {
            return sha1;
        }

        /**
         * Get the compliance level of the available Minecraft version. If 0, the launcher warns the user about this
         * version not being recent enough to support the latest player safety features. Its value is 1 otherwise.
         *
         * @return the compliance level of the available Minecraft version
         *
         * @since 1.0
         */
        public int getComplianceLevel() {
            return complianceLevel;
        }
    }

    ///endregion
}

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

import java.util.Map;

/**
 * The information manifest for a specific Minecraft version containing the asset download information for the game.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class MCAssetManifest
{
    /**
     * The map of asset objects from their file name to their corresponding hash and file size.
     *
     * @since 1.0
     */
    private Map< String, Asset > objects;

    /**
     * Gets the map of asset objects from their file name to their corresponding hash and file size.
     *
     * @return map of asset objects from their file name to their corresponding hash and file size.
     *
     * @since 1.0
     */
    public Map< String, Asset > getObjects() {
        return objects;
    }

    /**
     * Object containing an asset's hash information and file size.
     *
     * @author Mica Technologies
     * @version 1.0
     * @since 1.0
     */
    public static class Asset
    {
        /**
         * The hash of the asset.
         *
         * @since 1.0
         */
        private String hash;

        /**
         * The file size of the asset.
         *
         * @since 1.0
         */
        private int size;

        /**
         * Gets the hash of the asset.
         *
         * @return hash of the asset
         *
         * @since 1.0
         */
        public String getHash() {
            return hash;
        }

        /**
         * Gets the file size of the asset.
         *
         * @return file size of the asset
         *
         * @since 1.0
         */
        public int getSize() {
            return size;
        }
    }
}

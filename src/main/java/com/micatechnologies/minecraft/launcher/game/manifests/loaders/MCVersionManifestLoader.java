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

package com.micatechnologies.minecraft.launcher.game.manifests.loaders;

import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.manifests.MCAvailableVersionsManifest;
import com.micatechnologies.minecraft.launcher.game.manifests.MCVersionManifest;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Utility for downloading the {@link MCAvailableVersionsManifest} and associated {@link MCVersionManifest}s for each
 * available version.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class MCVersionManifestLoader
{
    /**
     * The URL for downloading the {@link MCAvailableVersionsManifest} of available Minecraft versions and their
     * applicable information, including URL for the corresponding package JSON.
     *
     * @since 1.0
     */
    private static final String MINECRAFT_VERSIONS_MANIFEST_URL
            = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    /**
     * A cached copy of the {@link MCAvailableVersionsManifest} of available Minecraft versions and their applicable
     * information, including URL for the corresponding package JSON.
     *
     * @since 1.0
     */
    private static MCAvailableVersionsManifest MCAvailableVersionsManifest = null;

    /**
     * Get the {@link MCAvailableVersionsManifest} of available Minecraft versions and their applicable information,
     * including URL for the corresponding package JSON.
     *
     * @return {@link MCAvailableVersionsManifest} of available Minecraft versions and their applicable information,
     *         including URL for the corresponding package JSON
     *
     * @throws IOException if unable to download the {@link MCAvailableVersionsManifest}
     * @since 1.0
     */
    public static MCAvailableVersionsManifest getVersionsManifest() throws IOException {
        // Get versions manifest
        if ( MCAvailableVersionsManifest == null ) {
            MCAvailableVersionsManifest = downloadVersionsManifest();
        }

        return MCAvailableVersionsManifest;
    }

    /**
     * Downloads the {@link MCVersionManifest} for the specified Minecraft version ID.
     *
     * @param id Minecraft version ID to download {@link MCVersionManifest} for
     *
     * @return {@link MCVersionManifest} for the specified Minecraft version ID
     *
     * @throws IOException if unable to download the {@link MCAvailableVersionsManifest} or applicable
     *                     {@link MCVersionManifest}
     * @since 1.0
     */
    public static MCVersionManifest downloadVersionManifest( String id ) throws IOException {
        // Get versions manifest
        if ( MCAvailableVersionsManifest == null ) {
            MCAvailableVersionsManifest = downloadVersionsManifest();
        }

        // Search for version in manifest
        MCAvailableVersionsManifest.Version availableDesiredVersion = null;
        List< MCAvailableVersionsManifest.Version > availableVersions = MCAvailableVersionsManifest.getVersions();
        for ( MCAvailableVersionsManifest.Version availableVersion : availableVersions ) {
            if ( availableVersion.getId().equalsIgnoreCase( id ) ) {
                availableDesiredVersion = availableVersion;
                break;
            }
        }

        // Download manifest for version (return null if version can't be found)
        MCVersionManifest availableDesiredVersionManifest = null;
        if ( availableDesiredVersion != null ) {
            File versionManifestFile = SynchronizedFileManager.getSynchronizedFile(
                    LocalPathConstants.MC_VERSION_MANIFESTS_FOLDER_PATH.resolve( id + ".json" ) );
            NetworkUtilities.downloadFileFromURL( availableDesiredVersion.getUrl(), versionManifestFile );
            availableDesiredVersionManifest = FileUtilities.readAsJsonObject( versionManifestFile,
                                                                              MCVersionManifest.class );
        }
        return availableDesiredVersionManifest;
    }

    /**
     * Downloads the {@link MCAvailableVersionsManifest} of available Minecraft versions and their applicable
     * information, including URL for the corresponding package JSON.
     *
     * @return {@link MCAvailableVersionsManifest} of available Minecraft versions and their applicable information
     *
     * @throws IOException if unable to download the {@link MCAvailableVersionsManifest}
     * @since 1.0
     */
    private static MCAvailableVersionsManifest downloadVersionsManifest() throws IOException {
        File versionsManifestFile = SynchronizedFileManager.getSynchronizedFile(
                LocalPathConstants.MC_VERSION_MANIFESTS_FOLDER_PATH.resolve( "versions.json" ) );
        NetworkUtilities.downloadFileFromURL( ModPackConstants.MINECRAFT_VERSION_MANIFEST_URL, versionsManifestFile );
        return FileUtilities.readAsJsonObject( versionsManifestFile, MCAvailableVersionsManifest.class );
    }
}

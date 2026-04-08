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

package com.micatechnologies.minecraft.launcher.game.modpack;

import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/**
 * Handles modpack environment setup: directory creation, image caching, and local path resolution. Extracted from
 * {@link GameModPack} to separate environment concerns from game launch logic.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
class GameModPackEnvironment
{
    private final GameModPackMetadata metadata;
    private volatile boolean          didCacheImages = false;

    GameModPackEnvironment( GameModPackMetadata metadata )
    {
        this.metadata = metadata;
    }

    /**
     * Creates all required directories for the modpack (bin, mods, config, natives, resourcepacks, shaderpacks).
     */
    void prepareEnvironment()
    {
        File binPath = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + "bin" );
        File modsPath = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + "mods" );
        File configPath = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + "config" );
        File nativePath = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + "bin" + File.separator + "natives" );
        File resPackPath = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + "resourcepacks" );
        File shaderPackPath = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + "shaderpacks" );

        //noinspection ResultOfMethodCallIgnored
        binPath.getParentFile().mkdirs();

        if ( !binPath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            binPath.mkdir();
        }
        if ( !modsPath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            modsPath.mkdir();
        }
        if ( !configPath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            configPath.mkdir();
        }
        if ( !nativePath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            nativePath.mkdir();
        }
        if ( !resPackPath.exists() && GameModeManager.isClient() ) {
            //noinspection ResultOfMethodCallIgnored
            resPackPath.mkdir();
        }
        if ( !shaderPackPath.exists() && GameModeManager.isClient() ) {
            //noinspection ResultOfMethodCallIgnored
            shaderPackPath.mkdir();
        }
    }

    /**
     * Returns the file path to the cached logo image, downloading it first if necessary.
     *
     * @return absolute path to the logo image file
     */
    synchronized String getPackLogoFilepath()
    {
        if ( !didCacheImages ) {
            cacheImages();
        }
        return getRawLogoFilePath();
    }

    /**
     * Returns the file path to the cached background image, downloading it first if necessary.
     *
     * @return absolute path to the background image file
     */
    synchronized String getPackBackgroundFilepath()
    {
        if ( !didCacheImages ) {
            cacheImages();
        }
        return getRawBackgroundFilePath();
    }

    /**
     * Downloads and caches both the logo and background images for this modpack.
     */
    synchronized void cacheImages()
    {
        try {
            fetchLatestModpackLogo();
            fetchLatestModpackBackground();
            didCacheImages = true;
        }
        catch ( Exception e ) {
            didCacheImages = false;
            Logger.logError( "Unable to download image assets for mod pack: " + metadata.getFriendlyName() );
            Logger.logThrowable( e );
        }
    }

    private String getRawLogoFilePath()
    {
        String filename;
        if ( metadata.packLogoSha1 != null ) {
            filename = metadata.packLogoSha1 + ".png";
        }
        else {
            filename = "logo_" + metadata.getPackSanitizedName() + ".png";
        }
        return LocalPathManager.getLauncherMetadataFolderPath() + File.separator + filename;
    }

    private String getRawBackgroundFilePath()
    {
        String filename;
        if ( metadata.packBackgroundSha1 != null ) {
            filename = metadata.packBackgroundSha1 + ".png";
        }
        else {
            filename = "background_" + metadata.getPackSanitizedName() + ".png";
        }
        return LocalPathManager.getLauncherMetadataFolderPath() + File.separator + filename;
    }

    private synchronized void fetchLatestModpackLogo() throws ModpackException
    {
        try {
            File syncFile = SynchronizedFileManager.getSynchronizedFile( getRawLogoFilePath() );
            boolean redownload = false;
            if ( metadata.packLogoSha1 == null ) {
                redownload = true;
            }
            else if ( !HashUtilities.verifySHA1( syncFile, metadata.packLogoSha1 ) ) {
                redownload = true;
            }
            if ( redownload ) {
                NetworkUtilities.downloadFileFromURL(
                        new URL( Objects.requireNonNullElse( metadata.packLogoURL,
                                                             ModPackConstants.MODPACK_DEFAULT_LOGO_URL ) ),
                        syncFile );
            }
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to download/fetch mod pack logo.", e );
        }
    }

    private synchronized void fetchLatestModpackBackground() throws ModpackException
    {
        try {
            File syncFile = SynchronizedFileManager.getSynchronizedFile( getRawBackgroundFilePath() );
            boolean redownload = false;
            if ( metadata.packBackgroundSha1 == null ) {
                redownload = true;
            }
            else if ( !HashUtilities.verifySHA1( syncFile, metadata.packBackgroundSha1 ) ) {
                redownload = true;
            }
            if ( redownload ) {
                NetworkUtilities.downloadFileFromURL( new URL(
                                                              Objects.requireNonNullElse( metadata.packBackgroundURL,
                                                                                          ModPackConstants.MODPACK_DEFAULT_BG_URL ) ),
                                                      syncFile );
            }
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to download/fetch mod pack background.", e );
        }
    }
}

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

import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class containing all GSON-deserialized metadata fields for a mod pack. This class holds the JSON schema fields
 * and their simple accessors. {@link GameModPack} extends this class and adds game lifecycle behavior (downloading,
 * launching, scanning, etc.).
 * <p>
 * GSON walks the class hierarchy when deserializing, so all fields declared here are populated automatically when
 * deserializing a {@link GameModPack} instance.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public abstract class GameModPackMetadata
{
    // region GSON-deserialized fields (must match JSON keys exactly)

    /**
     * Mod pack name. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packName;

    /**
     * Mod pack version. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packVersion;

    /**
     * Mod pack website URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packURL;

    /**
     * Mod pack unstable flag. Value read from manifest JSON.
     *
     * @since 1.2
     */
    @SuppressWarnings( "unused" )
    protected boolean packUnstable;

    /**
     * Mod pack custom Discord RPC flag. Value read from manifest JSON.
     *
     * @since 1.2
     */
    @SuppressWarnings( "unused" )
    protected boolean packCustomDiscordRpc;

    /**
     * Mod pack logo URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packLogoURL;

    /**
     * Mod pack logo SHA-1. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packLogoSha1;

    /**
     * Mod pack background URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packBackgroundURL;

    /**
     * Mod pack background SHA-1. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packBackgroundSha1;

    /**
     * Mod pack minimum RAM (GB). Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packMinRAMGB;

    /**
     * Mod pack Forge download URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packForgeURL;

    /**
     * Mod pack Forge download SHA-1 hash. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    protected String packForgeHash;

    /**
     * Mod pack scan exclusions (file or folder names, relative to mod pack root). Value read from manifest JSON.
     *
     * @since 1.3
     */
    @SuppressWarnings( "unused" )
    protected List< String > packScanExclusions;

    /**
     * List of mod pack Forge mods. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    protected List< GameMod > packMods;

    /**
     * List of mod pack Forge configs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    protected List< GameAsset > packConfigs;

    /**
     * List of mod pack resource packs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    protected List< ManagedGameFile > packResourcePacks;

    /**
     * List of mod pack shader packs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    protected List< ManagedGameFile > packShaderPacks;

    /**
     * List of initial files for mod pack. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    protected List< GameAsset > packInitialFiles;

    // endregion

    // region Simple accessors

    /**
     * Get the mod pack name.
     *
     * @return modpack name
     *
     * @since 1.0
     */
    public String getPackName()
    {
        return packName;
    }

    /**
     * Get the mod pack version.
     *
     * @return modpack version string
     *
     * @since 1.0
     */
    public String getPackVersion()
    {
        return packVersion;
    }

    /**
     * Get the mod pack website URL.
     *
     * @return modpack URL
     *
     * @since 1.0
     */
    public String getPackURL()
    {
        return packURL;
    }

    /**
     * Get the mod pack unstable flag.
     *
     * @return true if the modpack is marked as unstable (beta)
     *
     * @since 1.2
     */
    public boolean getPackUnstable()
    {
        return packUnstable;
    }

    /**
     * Get the mod pack custom Discord RPC flag.
     *
     * @return true if the modpack uses custom Discord RPC
     *
     * @since 1.2
     */
    public boolean getCustomDiscordRpc()
    {
        return packCustomDiscordRpc;
    }

    /**
     * Get the mod pack minimum RAM in gigabytes.
     *
     * @return minimum RAM in GB
     *
     * @since 1.0
     */
    public double getPackMinRAMGB()
    {
        return Double.parseDouble( packMinRAMGB );
    }

    /**
     * Get the mod pack scan exclusions.
     *
     * @return list of scan exclusion paths, never null
     *
     * @since 1.3
     */
    public List< String > getPackScanExclusions()
    {
        if ( packScanExclusions == null )
        {
            packScanExclusions = new ArrayList<>();
        }
        return packScanExclusions;
    }

    /**
     * Get the sanitized pack name (alphanumeric only, suitable for folder names).
     *
     * @return sanitized pack name
     *
     * @since 1.0
     */
    public String getPackSanitizedName()
    {
        return getPackName().replaceAll( "[^a-zA-Z0-9]", "" );
    }

    /**
     * Get the user-friendly display name (name + version).
     *
     * @return friendly name string, or null if pack name is null
     *
     * @since 1.0
     */
    public String getFriendlyName()
    {
        return getPackName() != null ?
               String.format( ModPackConstants.MODPACK_FRIENDLY_NAME_TEMPLATE, getPackName(), getPackVersion() ) :
               null;
    }

    // endregion

    // region Path helpers

    /**
     * Get the installation folder of this mod pack.
     *
     * @return installation folder path
     *
     * @since 1.0
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getPackRootFolder()
    {
        return LocalPathManager.getLauncherModpackFolderPath() + File.separator + getPackSanitizedName();
    }

    /**
     * Get the path to this mod pack's bin folder.
     *
     * @return bin folder path
     *
     * @since 1.0
     */
    public String getPackBinFolder()
    {
        return SystemUtilities.buildFilePath( getPackRootFolder(), LocalPathConstants.MOD_PACK_BIN_FOLDER_NAME );
    }

    // endregion

    /**
     * Returns a string representation of this mod pack (its friendly name).
     *
     * @return friendly name
     */
    @Override
    public String toString()
    {
        return getFriendlyName();
    }
}

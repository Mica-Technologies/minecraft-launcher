package com.micatechnologies.minecraft.forgelauncher.modpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.micatechnologies.minecraft.forgelauncher.exceptions.FLModpackException;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLSystemUtils;

/**
 * Class representing the Mojang Minecraft version manifest and providing functionality to download
 * a Minecraft version's library manifest.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
class MCVersionManifest extends MCRemoteFile {

    /**
     * Download URL of Minecraft version manifest
     */
    private static final String MINECRAFT_VERSION_MANIFEST_URL                = "https://launchermeta.mojang.com/mc/game/version_manifest.json";

    /**
     * Local path of Minecraft version manifest, relative to modpack root folder
     */
    private static final String MINECRAFT_VERSION_MANIFEST_LOCAL_MODPACK_PATH =
        "bin" + FLSystemUtils.getFileSeparator() + "minecraft-version.manifest";

    /**
     * Root folder of modpack
     */
    private final        String modpackRootFolder;

    /**
     * Create a Minecraft version manifest object for modpack at modpackRootFolder. Instances do not
     * require a local path prefix to be defined for download.
     *
     * @param modpackRootFolder root folder of modpack
     *
     * @since 1.0
     */
    MCVersionManifest( String modpackRootFolder ) {
        // Configure remote file
        super( MINECRAFT_VERSION_MANIFEST_URL,
               modpackRootFolder + FLSystemUtils.getFileSeparator()
                   + MINECRAFT_VERSION_MANIFEST_LOCAL_MODPACK_PATH );

        // Store modpack root folder
        this.modpackRootFolder = modpackRootFolder;
    }

    /**
     * Get the URL of the Minecraft library manifest for the specified Minecraft version.
     *
     * @param minecraftVersion minecraft version
     *
     * @return URL of Minecraft version's library manifest
     *
     * @throws FLModpackException if unable to get URL
     * @since 1.0
     */
    private String getMinecraftLibaryManifestURL( String minecraftVersion )
        throws FLModpackException {
        // Get versions from version manifest root object
        JsonArray minecraftVersions = readToJsonObject().getAsJsonArray( "versions" );

        // Loop through all versions in array
        for ( JsonElement version : minecraftVersions ) {
            // Check if version matches
            if ( version.getAsJsonObject().get( "id" ).getAsString().equals( minecraftVersion ) ) {
                return version.getAsJsonObject().get( "url" ).getAsString();
            }
        }

        // Throw exception if not found
        throw new FLModpackException(
            "Unable to find specified Minecraft version library manifest." );
    }

    /**
     * Get the Minecraft library manifest for the specified Minecraft version.
     *
     * @param minecraftVersion minecraft version
     *
     * @return Minecraft version's library manifest
     *
     * @throws FLModpackException if unable to get library manifest
     * @since 1.0
     */
    MCLibraryManifest getMinecraftLibraryManifest( String minecraftVersion )
        throws FLModpackException {
        return new MCLibraryManifest( getMinecraftLibaryManifestURL( minecraftVersion ),
                                      modpackRootFolder );
    }
}
package com.micatechnologies.minecraft.forgelauncher.modpack;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.forgelauncher.exceptions.FLModpackException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MCAssetManifest extends MCRemoteFile {

    private static final String MINECRAFT_ASSET_MANIFEST_LOCAL_MODPACK_FOLDER =
            "assets" + File.separator + "indexes";

    private final String modpackRootFolder;

    MCAssetManifest( String remote, String modpackRootFolder, String version ) {
        super( remote, modpackRootFolder + File.separator
                + MINECRAFT_ASSET_MANIFEST_LOCAL_MODPACK_FOLDER + File.separator
                + version + ".json" );
        this.modpackRootFolder = modpackRootFolder;
    }

    private ArrayList< MCRemoteFile > getAssets() throws FLModpackException {
        // Create list for assets
        ArrayList< MCRemoteFile > assets = new ArrayList<>();

        // Get objects list from assets manifest
        JsonObject objects = readToJsonObject().get( "objects" ).getAsJsonObject();

        // Add each asset to list
        for ( String assetName : objects.keySet() ) {
            JsonObject asset = objects.getAsJsonObject( assetName );
            String assetHash = asset.get( "hash" ).getAsString();
            String assetFolder = assetHash.substring( 0, 2 );
            String assetPath = modpackRootFolder + File.separator
                    + MCForgeModpackConsts.MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER + File.separator + "objects" + File.separator + assetFolder
                    + File.separator + assetHash;
            String assetURL =
                    "http://resources.download.minecraft.net/" + assetFolder + "/" + assetHash;
            assets.add( new MCRemoteFile( assetURL, assetPath, assetHash ) );
        }

        // Return list
        return assets;
    }

    void downloadAssets( final MCForgeModpackProgressProvider progressProvider )
    throws FLModpackException {
        // Update asset manifest first
        updateLocalFile();

        // Update each asset
        List< MCRemoteFile > assets = getAssets();
        for ( MCRemoteFile asset : assets ) {
            asset.updateLocalFile();

            // Update progress provider if present
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified asset " + asset.getFileName(),
                                                 ( 50.0 / ( double ) assets.size() ) );
            }
        }
    }
}

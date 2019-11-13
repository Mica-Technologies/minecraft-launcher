package com.micatechnologies.minecraft.forgelauncher;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import org.apache.commons.io.FileUtils;

/**
 * Launcher configuration object class. Serializable to and from JSON.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
class LauncherConfig {

    /**
     * (Serialized) Minimum amount of RAM allocated to launcher games.
     * <p>
     * Applies: Client, Server
     */
    int            minRAM;

    /**
     * (Serialized) Maximum amount of RAM allocated to launcher games.
     * <p>
     * Applies: Client, Server
     */
    int            maxRAM;

    /**
     * (Serialized) List of configured modpacks in launcher.
     * <p>
     * Applies: Client, Server
     */
    List< String > modpacks;

    /**
     * (Serialized) Boolean flag to enabled debug mode. Does not affect Forge or Minecraft.
     * <p>
     * Applies: Client, Server
     */
    boolean        debug;

    /**
     * (NOT Serialized) Default value for {@link LauncherConfig#minRAM}
     */
    final static transient int            defaultMinRAM   = 256;

    /**
     * (NOT Serialized) Default value for {@link LauncherConfig#maxRAM}
     */
    final static transient int            defaultMaxRAM   = 2048;

    /**
     * (NOT Serialized) Default value for {@link LauncherConfig#modpacks}
     */
    final static transient List< String > defaultModpacks = List.of(
        LauncherConstants.FILE_CONTENTS_DEFAULT_MODPACK_URL );

    /**
     * (NOT Serialized) Default value for {@link LauncherConfig#debug}
     */
    final static transient boolean        defaultDebug    = false;

    /**
     * Save the specified launcher configuration object to file
     * <p>
     * Applies: Client, Server
     *
     * @param toSave launcher configuration object
     */
    static void save( LauncherConfig toSave ) throws IOException {
        // Get config file
        File configFileFile = new File( LauncherConstants.PATH_LAUNCHER_CONFIG_FILE );

        // Verify file exists before writing
        if ( !configFileFile.exists() ) {
            try {
                configFileFile.createNewFile();
            }
            catch ( IOException e ) {
                System.err.println( "Unable to create file for saving config..." );
                e.printStackTrace();
            }
        }

        // Write to file
        FileUtils.writeStringToFile( configFileFile, new Gson().toJson( toSave ),
                                     Charset.defaultCharset() );
    }

    /**
     * Open the launcher configuration file and return it as object.
     * <p>
     * Applies: Client, Server
     *
     * @return launcher configuration object
     */
    static LauncherConfig open() {
        // Get config file
        File configFileFile = new File( LauncherConstants.PATH_LAUNCHER_CONFIG_FILE );

        // Read config file to JSON object
        try {
            return new Gson().fromJson( new FileReader( configFileFile ), LauncherConfig.class );
        }
        catch ( FileNotFoundException e ) {
            // Config file does not exist. Create new one.
            try {
                // Create any directories that may be missing
                configFileFile.getParentFile().mkdirs();

                // Create new file with default contents
                configFileFile.createNewFile();
                LauncherConfig newConf = new LauncherConfig();
                newConf.minRAM = LauncherConfig.defaultMinRAM;
                newConf.maxRAM = LauncherConfig.defaultMaxRAM;
                newConf.modpacks = LauncherConfig.defaultModpacks;
                newConf.debug = LauncherConfig.defaultDebug;
                save( newConf );

                // Return newly created config
                return new Gson().fromJson( new FileReader( configFileFile ),
                                            LauncherConfig.class );
            }
            // Config file does not exist and cannot be created. Show error.
            catch ( IOException ex ) {
                System.err.println(
                    "Unable to create missing launcher config file at " + configFileFile
                        .getAbsolutePath() );
                ex.printStackTrace();
            }
        }

        // Terminate program if config file was missing.
        System.exit( -1 );
        return null;
    }
}

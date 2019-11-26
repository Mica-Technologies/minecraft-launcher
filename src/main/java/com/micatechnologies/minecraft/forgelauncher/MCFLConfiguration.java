package com.micatechnologies.minecraft.forgelauncher;

import com.google.gson.Gson;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Class for storing and handling configuration. Can be read/written as JSON
 * to disk for persistent storage.
 *
 * @author Mica Technologies/HawkA97
 * @version 1.1
 */
public class MCFLConfiguration {
    //region: Constant Fields
    /**
     * File for persistent configuration storage
     */
    static final transient File DISK_FILE = new File( MCFLConstants.LAUNCHER_CLIENT_INSTALLATION_DIRECTORY + File.separator + "config.json" );

    /**
     * Array of options for minimum RAM configuration
     */
    static final transient double[] MIN_RAM_OPTIONS = { 0.25, 0.5, 0.75, 1.0 };

    /**
     * Array of options for maxmimum RAM configuration
     */
    static final transient double[] MAX_RAM_OPTIONS = { 2.0, 4.0, 6.0, 8.0, 12.0, 16.0, 20.0, 24.0, 28.0, 32.0, 36.0 };

    /**
     * Default option for minimum RAM configuration
     */
    static final transient double MIN_RAM_DEFAULT = MIN_RAM_OPTIONS[ 0 ];

    /**
     * Default option for maximum RAM configuration
     */
    static final transient double MAX_RAM_DEFAULT = MAX_RAM_OPTIONS[ 1 ];

    /**
     * Default modpack list
     */
    static final transient List< String > MODPACKS_DEFAULT = List.of( "https://cityofmcla.com/mcla_modpack_manifest.json" );

    /**
     * Default debug mode value
     */
    static final transient boolean DEBUG_DEFAULT = false;
    //endregion

    //region: Instance Fields
    /**
     * Configured amount of minimum RAM
     */
    private double minRAM;

    /**
     * Configured amount of maximum RAM
     */
    private double maxRAM;

    /**
     * Configured list of modpacks
     */
    private List< String > modpacks;

    /**
     * Configured debug mode boolean (true/false)
     */
    private boolean debug;
    //endregion

    //region: Get/Set Methods

    /**
     * Get the configured minimum amount of RAM
     *
     * @return minimum amount of RAM
     *
     * @since 1.1
     */
    double getMinRAM() {
        return minRAM;
    }

    /**
     * Get the configured maximum amount of RAM
     *
     * @return maximum amount of RAM
     *
     * @since 1.1
     */
    double getMaxRAM() {
        return maxRAM;
    }

    /**
     * Set the minimum amount of RAM
     *
     * @param minRAM minimum amount of RAM
     *
     * @since 1.1
     */
    void setMinRAM( double minRAM ) {
        this.minRAM = minRAM;
    }

    /**
     * Set the maximum amount of RAM
     *
     * @param maxRAM maximum amount of RAM
     *
     * @since 1.1
     */
    void setMaxRAM( double maxRAM ) {
        this.maxRAM = maxRAM;
    }

    /**
     * Get if the launcher is in debug mode
     *
     * @return debug mode boolean
     *
     * @since 1.1
     */
    boolean getDebug() {
        return debug;
    }

    /**
     * Set launcher debug mode
     *
     * @param debug debug mode boolean
     *
     * @since 1.1
     */
    void setDebug( boolean debug ) {
        this.debug = debug;
    }

    /**
     * Get the list of launcher modpacks
     *
     * @return launcher modpacks
     *
     * @since 1.1
     */
    List< String > getModpacks() {
        return modpacks;
    }
    //endregion

    //region: Functional Methods

    /**
     * Save the instance to disk
     *
     * @throws IOException if unable to write to disk
     * @since 1.1
     */
    void save() throws IOException {
        MCFLConfiguration.save( this );
    }

    /**
     * Save the specified instance to disk
     *
     * @param MCFLConfiguration specified instance
     *
     * @throws IOException if unable to write to disk
     * @since 1.0
     */
    static void save( MCFLConfiguration MCFLConfiguration ) throws IOException {
        // Verify local file exists
        if ( !DISK_FILE.exists() ) {
            DISK_FILE.createNewFile();
        }

        // Write configuration to file
        FileUtils.writeStringToFile( DISK_FILE, new Gson().toJson( MCFLConfiguration ), Charset.defaultCharset() );
    }

    /**
     * Attempt to read saved configuration from disk, and load default configuration and save if unable to.
     *
     * @return loaded configuration
     *
     * @throws IOException if unable to write default configuration to disk
     * @since 1.0
     */
    static MCFLConfiguration open() throws IOException {
        // Attempt to read from file
        try {
            return new Gson().fromJson( new FileReader( DISK_FILE ), MCFLConfiguration.class );
        }
        // Write default configuration and load if unable to read
        catch ( Exception e ) {
            MCFLConfiguration newConfig = new MCFLConfiguration();
            newConfig.debug = DEBUG_DEFAULT;
            newConfig.minRAM = MIN_RAM_DEFAULT;
            newConfig.maxRAM = MAX_RAM_DEFAULT;
            newConfig.modpacks = MODPACKS_DEFAULT;

            save( newConfig );
            return new Gson().fromJson( new FileReader( DISK_FILE ), MCFLConfiguration.class );
        }
    }
    //endregion
}

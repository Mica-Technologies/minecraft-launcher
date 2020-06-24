/*
 * Copyright (c) 2020 Mica Technologies
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

package com.micatechnologies.minecraft.forgelauncher.config;

import com.google.gson.Gson;
import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Class for storing and handling configuration. Can be read/written as JSON to disk for persistent storage.
 *
 * @author Mica Technologies
 * @version 2.0
 * @creator hawka97
 * @editors hawka97
 */
public class ConfigurationManager
{
    /**
     * File for persistent configuration storage
     */
    static final transient File DISK_FILE =
            new File( LauncherConstants.LAUNCHER_CLIENT_INSTALLATION_DIRECTORY + File.separator + "config.json" );

    /**
     * Array of options for minimum RAM configuration
     */
    public static final transient double[] MIN_RAM_OPTIONS = { 0.25, 0.5, 0.75, 1.0, 2.0 };

    /**
     * Array of options for maxmimum RAM configuration
     */
    public static final transient double[] MAX_RAM_OPTIONS =
            { 2.0, 4.0, 6.0, 8.0, 12.0, 16.0, 20.0, 24.0, 28.0, 32.0, 36.0 };

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
    static final transient List< String > MODPACKS_DEFAULT =
            List.of( "https://micatechnologies.com/alto-modpack/pack-manifest.json" );

    /**
     * Default debug mode value
     */
    static final transient boolean DEBUG_DEFAULT = false;

    /**
     * Default resizable guis value
     */
    static final transient boolean RESIZABLEGUIS_DEFAULT = false;

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

    /**
     * Configured resizable guis boolean (true/false)
     */
    private boolean resizableguis;

    /**
     * Get the configured minimum amount of RAM
     *
     * @return minimum amount of RAM
     *
     * @since 1.1
     */
    public double getMinRAM() {
        return minRAM;
    }

    /**
     * Get the configured maximum amount of RAM
     *
     * @return maximum amount of RAM
     *
     * @since 1.1
     */
    public double getMaxRAM() {
        return maxRAM;
    }

    /**
     * Set the minimum amount of RAM
     *
     * @param minRAM minimum amount of RAM
     *
     * @since 1.1
     */
    public void setMinRAM( double minRAM ) {
        this.minRAM = minRAM;
    }

    /**
     * Set the maximum amount of RAM
     *
     * @param maxRAM maximum amount of RAM
     *
     * @since 1.1
     */
    public void setMaxRAM( double maxRAM ) {
        this.maxRAM = maxRAM;
    }

    /**
     * Get if the launcher is in debug mode
     *
     * @return debug mode boolean
     *
     * @since 1.1
     */
    public boolean getDebug() {
        return debug;
    }

    /**
     * Set launcher debug mode
     *
     * @param debug debug mode boolean
     *
     * @since 1.1
     */
    public void setDebug( boolean debug ) {
        this.debug = debug;
    }

    /**
     * Get if the launcher allows resizable guis
     *
     * @return resizable guis boolean
     */
    public boolean getResizableguis() {
        return resizableguis;
    }

    /**
     * Set launcher resizable guis selection
     *
     * @param resizableguis resizable guis boolean
     */
    public void setResizableguis( boolean resizableguis ) {
        this.resizableguis = resizableguis;
    }

    /**
     * Get the list of launcher modpacks
     *
     * @return launcher modpacks
     *
     * @since 1.1
     */
    public List< String > getModpacks() {
        return modpacks;
    }

    /**
     * Save the instance to disk
     *
     * @throws IOException if unable to write to disk
     * @since 1.1
     */
    public void save() throws IOException {
        ConfigurationManager.save( this );
    }

    /**
     * Save the specified instance to disk
     *
     * @param LauncherConfiguration specified instance
     *
     * @throws IOException if unable to write to disk
     * @since 1.0
     */
    static void save( ConfigurationManager LauncherConfiguration ) throws IOException {
        // Verify local file exists
        if ( !DISK_FILE.exists() ) {
            DISK_FILE.getParentFile().mkdirs();
            DISK_FILE.createNewFile();
        }

        // Write configuration to file
        FileUtils.writeStringToFile( DISK_FILE, new Gson().toJson( LauncherConfiguration ), Charset.defaultCharset() );
    }

    /**
     * Attempt to read saved configuration from disk, and load default configuration and save if unable to.
     *
     * @return loaded configuration
     *
     * @throws IOException if unable to write default configuration to disk
     * @since 1.0
     */
    static ConfigurationManager open() throws IOException {
        // Attempt to read from file
        try {
            ConfigurationManager launcherConfiguration =
                    new Gson().fromJson( new FileReader( DISK_FILE ), ConfigurationManager.class );

            // Change Alto modpack URL to new if present
            final String oldMCLAURL = "https://cityofmcla.com/modpack/pack-manifest.json";
            final String newAltoURL = "https://micatechnologies.com/alto-modpack/pack-manifest.json";
            if ( launcherConfiguration.modpacks.contains( oldMCLAURL ) ) {
                launcherConfiguration.modpacks.remove( oldMCLAURL );
                launcherConfiguration.modpacks.add( newAltoURL );
            }

            return launcherConfiguration;
        }
        // Write default configuration and load if unable to read
        catch ( Exception e ) {
            ConfigurationManager newConfig = new ConfigurationManager();
            newConfig.debug = DEBUG_DEFAULT;
            newConfig.minRAM = MIN_RAM_DEFAULT;
            newConfig.maxRAM = MAX_RAM_DEFAULT;
            newConfig.modpacks = MODPACKS_DEFAULT;
            newConfig.resizableguis = RESIZABLEGUIS_DEFAULT;

            save( newConfig );
            return new Gson().fromJson( new FileReader( DISK_FILE ), ConfigurationManager.class );
        }
    }
}

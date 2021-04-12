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

package com.micatechnologies.minecraft.launcher.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;
import com.micatechnologies.minecraft.launcher.files.Logger;

import java.io.File;
import java.util.List;

/**
 * Class that manages the configuration and persistence of the configuration for the application.
 *
 * @author Mica Technologies
 * @version 3.0
 * @creator hawka97
 * @editors hawka97
 * @since 1.0
 */
public class ConfigManager
{
    /**
     * Configuration object. Must be loaded from disk and saved to disk on change.
     *
     * @see #readConfigurationFromDisk()
     * @see #writeConfigurationToDisk()
     * @since 3.0
     */
    private static JsonObject configObject = null;

    /**
     * Gets the configured minimum RAM for the Minecraft game.
     *
     * @return Minecraft starting/min RAM
     *
     * @since 1.0
     */
    public synchronized static long getMinRam() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Get and return value of min RAM
        return configObject.get( ConfigConstants.MIN_RAM_KEY ).getAsLong();
    }

    /**
     * Gets the configured minimum RAM (in GB) for the Minecraft game.
     *
     * @return Minecraft starting/min RAM (in GB)
     *
     * @since 1.0
     */
    public synchronized static double getMinRamInGb() {
        return getMinRam() / 1024.0;
    }

    /**
     * Sets the configured minimum RAM for the Minecraft game.
     *
     * @param minRam Minecraft starting/min RAM
     *
     * @since 1.0
     */
    public synchronized static void setMinRam( long minRam ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of min RAM
        configObject.addProperty( ConfigConstants.MIN_RAM_KEY, minRam );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Gets the configured maximum RAM for the Minecraft game.
     *
     * @return Minecraft maximum RAM
     *
     * @since 1.0
     */
    public synchronized static long getMaxRam() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Get and return value of min RAM
        return configObject.get( ConfigConstants.MAX_RAM_KEY ).getAsLong();
    }

    /**
     * Gets the configured maximum RAM (in GB) for the Minecraft game.
     *
     * @return Minecraft maximum RAM (in GB)
     *
     * @since 1.0
     */
    public synchronized static double getMaxRamInGb() {
        return getMaxRam() / 1024.0;
    }

    /**
     * Sets the configured maximum RAM for the Minecraft game.
     *
     * @param maxRam Minecraft maximum RAM
     *
     * @since 1.0
     */
    public synchronized static void setMaxRam( long maxRam ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of min RAM
        configObject.addProperty( ConfigConstants.MAX_RAM_KEY, maxRam );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Gets the configured state of debug logging for the application.
     *
     * @return true if debug logging enabled, otherwise false
     *
     * @since 2.0
     */
    public synchronized static boolean getDebugLogging() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Get and return value of min RAM
        return configObject.get( ConfigConstants.LOG_DEBUG_ENABLE_KEY ).getAsBoolean();
    }

    /**
     * Sets the configured state of debug logging for the application.
     *
     * @param debugLogging true to enable application debug logging, otherwise false
     *
     * @since 2.0
     */
    public synchronized static void setDebugLogging( boolean debugLogging ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of min RAM
        configObject.addProperty( ConfigConstants.LOG_DEBUG_ENABLE_KEY, debugLogging );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Gets the configured state of resizable windows for the application.
     *
     * @return true if resizable windows enabled, otherwise false
     *
     * @since 2.0
     */
    public synchronized static boolean getResizableWindows() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Get and return value of min RAM
        return configObject.get( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY ).getAsBoolean();
    }

    /**
     * Sets the configured state of resizable windows for the application.
     *
     * @param resizableWindows true to enable resizable windows, otherwise false
     *
     * @since 2.0
     */
    public synchronized static void setResizableWindows( boolean resizableWindows ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of min RAM
        configObject.addProperty( ConfigConstants.RESIZE_WINDOWS_ENABLE_KEY, resizableWindows );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Gets the configured custom JVM launch arguments for the application.
     *
     * @return custom JVM launch arguments
     *
     * @since 3.0
     */
    public synchronized static String getCustomJvmArgs() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Check for presence of field, and create default if does not exist
        if ( !configObject.has( ConfigConstants.JVM_ARGS_KEY ) ) {
            // Add property with default value
            configObject.addProperty( ConfigConstants.JVM_ARGS_KEY, ConfigConstants.JVM_ARGS_VALUE_DEFAULT );

            // Save configuration to disk
            writeConfigurationToDisk();
        }

        // Get and return value of custom JVM args
        return configObject.get( ConfigConstants.JVM_ARGS_KEY ).getAsString();
    }

    /**
     * Gets the configured list of installed mod packs by their manifest URLs.
     *
     * @return list of installed mod packs' manifest URLs
     *
     * @since 1.0
     */
    public synchronized static List< String > getInstalledModPacks() {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Get and return value of min RAM
        JsonArray installedModPacksArray = configObject.get( ConfigConstants.MOD_PACKS_INSTALLED_KEY ).getAsJsonArray();
        return new Gson().fromJson( installedModPacksArray, ConfigConstants.modPacksListType );
    }

    /**
     * Sets the configured list of installed mod packs' manifest URLs.
     *
     * @param installedModPacks list of installed mod packs' manifest URLs
     *
     * @since 1.0
     */
    public synchronized static void setInstalledModPacks( List< String > installedModPacks ) {
        // Read configuration from disk if not loaded
        if ( configObject == null ) {
            readConfigurationFromDisk();
        }

        // Set value of min RAM
        JsonArray installedModPacksArray = ( JsonArray ) new Gson().toJsonTree( installedModPacks,
                                                                                ConfigConstants.modPacksListType );
        configObject.add( ConfigConstants.MOD_PACKS_INSTALLED_KEY, installedModPacksArray );

        // Save configuration to disk
        writeConfigurationToDisk();
    }

    /**
     * Reads the application configuration from its file on persistent storage. In the event that a file does not exist,
     * or an error occurred with the file, a new default configuration file will be created.
     *
     * @since 1.0
     */
    private synchronized static void readConfigurationFromDisk() {
        // Get file path and file object for config file
        String configFilePath = LocalPathManager.getLauncherConfigFolderPath() + ConfigConstants.CONFIG_FILE_NAME;
        File configFile = SynchronizedFileManager.getSynchronizedFile( configFilePath );

        // Check if file exists (and is file), and attempt to read
        boolean read = configFile.isFile();
        if ( read ) {
            try {
                configObject = FileUtilities.readAsJsonObject( configFile );
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.CONFIG_EXISTS_CORRUPT_RESET_ERROR_TEXT );
                Logger.logThrowable( e );
                read = false;
            }
        }

        // If configuration not read or failed to read, use default configuration
        if ( !read ) {
            configObject = new JsonObject();
            setMinRam( ConfigConstants.MIN_RAM_MEGABYTES_DEFAULT );
            setMaxRam( ConfigConstants.MAX_RAM_MEGABYTES_DEFAULT );
            setDebugLogging( ConfigConstants.LOG_DEBUG_ENABLE_DEFAULT );
            setResizableWindows( ConfigConstants.RESIZE_WINDOWS_ENABLE_DEFAULT );
            setInstalledModPacks( ConfigConstants.MOD_PACKS_INSTALLED_DEFAULT );
            Logger.logStd( LocalizationManager.CONFIG_RESET_SUCCESS_TEXT );
        }
    }

    /**
     * Writes the application configuration to its file on persistent storage.
     *
     * @since 1.0
     */
    private synchronized static void writeConfigurationToDisk() {
        // Check if configuration is loaded, return if not
        if ( configObject == null ) {
            Logger.logError( LocalizationManager.CONFIG_NOT_LOADED_CANT_SAVE_ERROR_TEXT );
            return;
        }

        // Get file path and file object for config file
        String configFilePath = LocalPathManager.getLauncherConfigFolderPath() + ConfigConstants.CONFIG_FILE_NAME;
        File configFile = SynchronizedFileManager.getSynchronizedFile( configFilePath );

        try {
            FileUtilities.writeFromJson( configObject, configFile );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.CONFIG_SAVE_ERROR_TEXT );
            Logger.logThrowable( e );
        }
    }
}

package com.micatechnologies.minecraft.forgelauncher;

import com.google.gson.Gson;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Class representation of a launcher configuration object/file.
 *
 * @author Mica Technologies/HawkA97
 * @version 1.1
 */
public class Configuration {
    //region: Constant Fields
    static final transient File diskFile = new File(Constants.LAUNCHER_INSTALLATION_DIRECTORY + File.separator + "config.json");

    static final transient double[] minRAM_OPTIONS = {0.25, 0.5, 0.75, 1.0};
    static final transient double[] maxRAM_OPTIONS = {2.0, 4.0, 6.0, 8.0, 12.0, 16.0, 20.0, 24.0, 28.0, 32.0, 36.0};

    static final transient double minRAM_DEFAULT = minRAM_OPTIONS[0];
    static final transient double maxRAM_DEFAULT = maxRAM_OPTIONS[1];
    static final transient List<String> modpacks_DEFAULT = List.of("https://cityofmcla.com/mcla_modpack_manifest.json");
    static final transient boolean debug_DEFAULT = false;
    //endregion

    //region: Instance Fields
    private double minRAM;
    private double maxRAM;
    private List<String> modpacks;
    private boolean debug;
    //endregion

    //region: Get/Set Methods

    /**
     * Get the configured minimum amount of RAM
     *
     * @return minimum amount of RAM
     */
    double getMinRAM() {
        return minRAM;
    }

    /**
     * Get the configured maximum amount of RAM
     *
     * @return maximum amount of RAM
     */
    double getMaxRAM() {
        return maxRAM;
    }

    /**
     * Set the minimum amount of RAM
     *
     * @param minRAM minimum amount of RAM
     */
    void setMinRAM(double minRAM) {
        this.minRAM = minRAM;
    }

    /**
     * Set the maximum amount of RAM
     *
     * @param maxRAM maximum amount of RAM
     */
    void setMaxRAM(double maxRAM) {
        this.maxRAM = maxRAM;
    }

    /**
     * Get if the launcher is in debug mode
     *
     * @return debug mode boolean
     */
    boolean getDebug() {
        return debug;
    }

    /**
     * Set launcher debug mode
     *
     * @param debug debug mode boolean
     */
    void setDebug(boolean debug) {
        this.debug = debug;
    }
    //endregion

    //region: Read/Save Methods

    /**
     * Save the instance to disk
     *
     * @throws IOException if unable to write to disk
     */
    void save() throws IOException {
        Configuration.save(this);
    }

    /**
     * Save the specified instance to disk
     *
     * @param configuration specified instance
     * @throws IOException if unable to write to disk
     */
    static void save(Configuration configuration) throws IOException {
        // Verify local file exists
        if (!diskFile.exists()) {
            diskFile.createNewFile();
        }

        // Write configuration to file
        FileUtils.writeStringToFile(diskFile, new Gson().toJson(configuration), Charset.defaultCharset());
    }

    /**
     * Attempt to read saved configuration from disk, and load default configuration and save if unable to.
     *
     * @return loaded configuration
     * @throws IOException if unable to write default configuration to disk
     */
    static Configuration open() throws IOException {
        // Attempt to read from file
        try {
            return new Gson().fromJson(new FileReader(diskFile), Configuration.class);
        }
        // Write default configuration and load if unable to read
        catch (Exception e) {
            Configuration newConfig = new Configuration();
            newConfig.debug = debug_DEFAULT;
            newConfig.minRAM = minRAM_DEFAULT;
            newConfig.maxRAM = maxRAM_DEFAULT;
            newConfig.modpacks = modpacks_DEFAULT;

            save(newConfig);
            return new Gson().fromJson(new FileReader(diskFile), Configuration.class);
        }
    }
    //endregion
}

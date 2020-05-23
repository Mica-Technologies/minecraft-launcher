package com.micatechnologies.minecraft.forgelauncher.modpack;

/**
 * Class containing constants that support the mod pack functionality of the launcher.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class ModPackConstants {

    /**
     * URL of manifest containing the manifest URLs of installable mod packs.
     *
     * @since 1.0
     */
    public static final String AVAILABLE_PACKS_MANIFEST_URL = "https://minecraft.micatechnologies.com/files/packs/installable.json";

    /**
     * The key that identifies the list of installable mod pack manifest URLs in the available mod packs manifest.
     *
     * @since 1.0
     */
    public static final String AVAILABLE_PACKS_MANIFEST_LIST_KEY = "available";

    /**
     * Template for formatting mod pack friendly names. First string is the mod pack name and the second string is the mod pack version.
     *
     * @since 1.0
     */
    public static final String MODPACK_FRIENDLY_NAME_TEMPLATE = "%s: %s";

    /**
     * Garbage collector settings for the Minecraft game. Added to the command used to start Minecraft.
     *
     * @since 1.0
     */
    public static final String APP_GARBAGE_COLLECTOR_SETTINGS = "-XX:+UseG1GC -Dsun.rmi.dgc.server.gcInterval=2147483646 -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M ";
}

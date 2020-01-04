package com.micatechnologies.minecraft.forgemodpacklib;

/**
 * A class of statics/constants for the Forge Modpack Library
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class MCForgeModpackConsts {

    /**
     * Modpack install folder relative path to modpack manifest
     */
    static final        String                 MODPACK_MANIFEST_LOCAL_PATH              = "pack.manifest";

    /**
     * Modpack install folder relative path to Forge jar
     */
    static final        String                 MODPACK_FORGE_JAR_LOCAL_PATH             =
        "bin" + MCModpackOSUtils.getFileSeparator() + "modpack.jar";

    /**
     * Modpack install folder relative path to Forge jar
     */
    static final        String                 MODPACK_MINECRAFT_JAR_LOCAL_PATH         =
        "bin" + MCModpackOSUtils.getFileSeparator() + "minecraft.jar";

    /**
     * Modpack install folder relative path to mods folder
     */
    static final        String                 MODPACK_FORGE_MODS_LOCAL_FOLDER          = "mods";

    /**
     * Modpack install folder relative path to game libraries
     */
    static final        String                 MODPACK_FORGE_LIBS_LOCAL_FOLDER          = "libraries";

    /**
     * Modpack install folder relative path to configs folder
     */
    static final        String                 MODPACK_FORGE_CONFIGS_LOCAL_FOLDER       = "config";

    /**
     * Modpack install folder relative path to game natives
     */
    static final        String                 MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER   =
        "bin" + MCModpackOSUtils.getFileSeparator() + "natives";

    /**
     * Modpack install folder relative path to game natives
     */
    static final        String                 MODPACK_FORGE_RESOURCEPACKS_LOCAL_FOLDER = "resourcepacks";

    /**
     * Modpack install folder relative path to game natives
     */
    static final        String                 MODPACK_FORGE_SHADERPACKS_LOCAL_FOLDER   = "shaderpacks";

    static final        String                 MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER    = "assets";

    /**
     * Modpack install folder relative path to pack logo
     */
    static final        String                 MODPACK_LOGO_LOCAL_FILE                  =
        "bin" + MCModpackOSUtils.getFileSeparator() + "logo.png";

    /**
     * The Content-Type HTTP request parameter as JSON
     */
    static final        Pair< String, String > MANIFEST_CXN_CONTENT_TYPE                = new Pair<>(
        "Content-Type", "application/json" );

    /**
     * The charset HTTP request parameter as utf-8
     */
    static final        Pair< String, String > MANIFEST_CXN_CHARSET                     = new Pair<>(
        "charset", "utf-8" );

    /**
     * Constant for the Microsoft(r) Windows platform
     */
    static final        String                 PLATFORM_WINDOWS                         = "windows";

    /**
     * Constant for the Apple macOS(r) platform
     */
    static final        String                 PLATFORM_MACOS                           = "osx";

    /**
     * Constant for Linux(r) platforms
     */
    static final        String                 PLATFORM_UNIX                            = "linux";

    /**
     * Constant for Minecraft Client modpack mode
     */
    public static final int                    MINECRAFT_CLIENT_MODE                    = 0;

    /**
     * Constant for Minecraft Server modpack mode
     */
    public static final int                    MINECRAFT_SERVER_MODE                    = 1;

    public static final String MODPACK_DEFAULT_BG_URL = "https://wallpaperstock.net/minecraft-wallpapers_27410_1600x1200.jpg";
}

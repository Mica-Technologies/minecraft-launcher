package com.micatechnologies.minecraft.forgelauncher;

import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpackConsts;

import java.io.File;

class LauncherConstants {

    //region: Launcher Modes
    static final int LAUNCHER_CLIENT_MODE = MCForgeModpackConsts.MINECRAFT_CLIENT_MODE;

    static final int LAUNCHER_SERVER_MODE = MCForgeModpackConsts.MINECRAFT_SERVER_MODE;
    //endregion

    //region: Launcher Information
    static final String LAUNCHER_FULL_NAME = "Mica Minecraft Forge Launcher";

    static final String LAUNCHER_SHORT_NAME = "Mica Forge Launcher";
    //endregion

    //region: Remote File/Folder URLs
    static final String URL_JRE_WIN = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_windows_hotspot_8u232b09.zip";

    static final String URL_JRE_MAC = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_mac_hotspot_8u232b09.tar.gz";

    static final String URL_JRE_UNX = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_linux_hotspot_8u232b09.tar.gz";

    static final String URL_JRE_WIN_HASH = URL_JRE_WIN + ".sha256.txt";

    static final String URL_JRE_MAC_HASH = URL_JRE_MAC + ".sha256.txt";

    static final String URL_JRE_UNX_HASH = URL_JRE_UNX + ".sha256.txt";

    static final String URL_MINECRAFT_USER_ICONS = "https://minotar.net/armor/bust/user/70.png";
    //endregion

    //region: Local File/Folder Paths
    static final String PATH_ROOT = System.getProperty("user.home")
            + File.separator + "." + LAUNCHER_FULL_NAME.replace(" ", "") + File.separator;

    static final String PATH_CONFIG_FOLDER = PATH_ROOT + "config";

    static final String PATH_SAVED_USER_FILE =
            PATH_CONFIG_FOLDER + File.separator + "launcher.user.info";

    static final String PATH_LAUNCHER_CONFIG_FILE =
            PATH_CONFIG_FOLDER + File.separator + "launcher.config.json";

    static final String PATH_LAUNCHER_CLIENT_TOKEN =
            PATH_CONFIG_FOLDER + File.separator + "launcher.auth.tokn";

    static final String PATH_JRE_FOLDER = PATH_ROOT + "runtime";

    static final String PATH_JRE_EXTRACTED_FOLDER =
            PATH_JRE_FOLDER + File.separator + "jdk8u232-b09-jre";

    static final String PATH_JRE_WIN_EXEC =
            PATH_JRE_EXTRACTED_FOLDER + File.separator + "bin" + File.separator + "java.exe";

    static final String PATH_JRE_MAC_EXEC =
            PATH_JRE_EXTRACTED_FOLDER + File.separator + "Contents" + File.separator + "Home"
                    + File.separator + "bin" + File.separator + "java";

    static final String PATH_JRE_UNX_EXEC =
            PATH_JRE_EXTRACTED_FOLDER + File.separator + "bin" + File.separator + "java";

    static final String PATH_JRE_HASH =
            PATH_JRE_FOLDER + File.separator + "runtime.hash";

    static final String PATH_JRE_ARCHIVE =
            PATH_JRE_FOLDER + File.separator + "runtime.compressed";

    static final String PATH_MODPACKS_FOLDER = PATH_ROOT + File.separator + "modpacks";

    static final String PATH_MODPACKS_SANDBOX_FOLDER =
            PATH_MODPACKS_FOLDER + File.separator + "sandbox";
    //endregion

    //region: UI/Log Message Strings
    static final String STRING_STARTING_CLIENT_LAUNCHER = "Starting client launcher application";

    static final String STRING_STARTING_SERVER_LAUNCHER = "Starting server launcher application";
    //endregion

    //region: File Contents
    static final String FILE_CONTENTS_DEFAULT_MODPACK_URL = "https://cityofmcla.com/mcla_modpack_manifest.json";
    //endregion

    static final double[] MINIMUM_RAM_OPTIONS = {0.25,0.5,1.0};
    static final double[] MAXIMUM_RAM_OPTIONS = {2.0,4.0,6.0,8.0,10.0,12.0,16.0,18.0,20.0,24.0,28.0,32.0,36.0,40.0};
}

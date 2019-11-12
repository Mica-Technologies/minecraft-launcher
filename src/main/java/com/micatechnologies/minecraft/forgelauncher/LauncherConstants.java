package com.micatechnologies.minecraft.forgelauncher;

import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpackConsts;
import java.io.File;

public class LauncherConstants {

    public static final String LAUNCHER_JDK_WIN_URL               = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_windows_hotspot_8u232b09.zip";

    public static final String LAUNCHER_JDK_MAC_URL               = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_mac_hotspot_8u232b09.tar.gz";

    public static final String LAUNCHER_JDK_LINUX_URL             = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_linux_hotspot_8u232b09.tar.gz";

    public static final String LAUNCHER_JDK_HASH_POSTFIX          = ".sha256.txt";

    public static final String LAUNCHER_JDK_LOCAL_FOLDER_NAME     = "jdk8u232-b09-jre";

    public static final String LAUNCHER_JDK_WIN_LOCAL_JAVA_PATH   =
        "bin" + File.separator + "java.exe";

    public static final String LAUNCHER_JDK_LINUX_LOCAL_JAVA_PATH = "bin" + File.separator + "java";

    public static final String LAUNCHER_JDK_MAC_LOCAL_JAVA_PATH   =
        "Contents" + File.separator + "Home" + File.separator + "bin" + File.separator + "java";

    public static final String LAUNCHER_TITLE                     = "Mica Forge Launcher";

    public static final String LAUNCHER_FULL_NAME                 = "Mica Minecraft Forge Launcher";

    public static final int    LAUNCHER_CLIENT_MODE               = MCForgeModpackConsts.MINECRAFT_CLIENT_MODE;

    public static final int    LAUNCHER_SERVER_MODE               = MCForgeModpackConsts.MINECRAFT_SERVER_MODE;

    public static final String LAUNCHER_CONFIG_NAME               = "launcher.config.json";

    public static final String LAUNCHER_JDK_PATH                  = "jre";

    public static final String LAUNCHER_UUID_PATH                 = "client-auth.token";

    public static final String LAUNCHER_CLIENT_INSTALL_PATH       = System.getProperty(
        "user.home" ) + File.separator + "." + LAUNCHER_TITLE.replace( " ", "" ) + File.separator;

    public static final String LAUNCHER_SAVED_USER_FILE_PATH      =
        LAUNCHER_CLIENT_INSTALL_PATH + "client-auth.saved";

    public static final String LAUNCHER_CONFIG_DEFAULT_FILE       =
        "{  " + System.lineSeparator() + "   \"minRAM\":256," + System.lineSeparator()
            + "   \"maxRAM\":2048," + System.lineSeparator() + "   \"modpacks\":[  " + System
            .lineSeparator()
            + "      \"https://github.com/Mica-Technologies/Minecraft-Forge-Modpack-Lib/raw/master/full_modpack_json_example.json\""
            + System.lineSeparator() + "   ]" + System.lineSeparator() + "}";
}

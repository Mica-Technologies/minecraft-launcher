package com.micatechnologies.minecraft.forgelauncher;

import java.io.File;
import java.nio.file.Paths;

/**
 * Class of constants/statics for use across package.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class LauncherConstants
{
    /**
     * Launcher application name. Leave blank, this is auto-filled in.
     */
    public final static transient String LAUNCHER_APPLICATION_NAME = "Mica Forge Launcher";

    /**
     * Launcher application version. Leave blank, this is auto-filled in.
     */
    public final static transient String LAUNCHER_APPLICATION_VERSION = "2020.3";

    public final static transient String LAUNCHER_APPLICATION_NAME_TRIMMED = LAUNCHER_APPLICATION_NAME.replaceAll( " ", "" );

    /**
     * Launcher client installation directory
     */
    public final static transient String LAUNCHER_CLIENT_INSTALLATION_DIRECTORY =
            System.getProperty( "user.home" ) + File.separator + "." + LAUNCHER_APPLICATION_NAME_TRIMMED;

    /**
     * Launcher server installation directory
     */
    final static transient String LAUNCHER_SERVER_INSTALLATION_DIRECTORY = Paths.get( "" ).toAbsolutePath().toString();

    /**
     * Launcher client saved user location
     */
    public final static transient String LAUNCHER_CLIENT_SAVED_USER_FILE =
            LAUNCHER_CLIENT_INSTALLATION_DIRECTORY + File.separator + "launcher" + File.separator + "user.cache";

    /**
     * Launcher client saved token location
     */
    final static transient String LAUNCHER_CLIENT_TOKEN_FILE =
            LAUNCHER_CLIENT_INSTALLATION_DIRECTORY + File.separator + "launcher" + File.separator + "token.cache";

    final static transient String URL_JRE_WIN =
            "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_windows_hotspot_8u232b09.zip";

    final static transient String URL_JRE_MAC =
            "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_mac_hotspot_8u232b09.tar.gz";

    final static transient String URL_JRE_UNX =
            "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_linux_hotspot_8u232b09.tar.gz";

    final static transient String URL_JRE_WIN_HASH = URL_JRE_WIN + ".sha256.txt";

    final static transient String URL_JRE_MAC_HASH = URL_JRE_MAC + ".sha256.txt";

    final static transient String URL_JRE_UNX_HASH = URL_JRE_UNX + ".sha256.txt";

    final static transient String JRE_EXTRACTED_FOLDER_NAME = "jdk8u232-b09-jre";

    public final static String URL_MINECRAFT_USER_ICONS = "http://minotar.net/armor/bust/user/100.png";
    public final static String URL_MINECRAFT_NO_MODPACK_IMAGE =
            "https://cdn.pixabay.com/photo/2016/11/11/14/49/minecraft-1816996_960_720.png";

    public final static String UPDATE_CHECK_REDIRECT_URL =
            "https://github.com/Mica-Technologies/Minecraft-Forge-Launcher/releases/latest";

    public static final String PROGRAM_ARG_CLIENT_MODE = "-c";
    public static final String PROGRAM_ARG_SERVER_MODE = "-s";

    public static final String LOG_FOLDER_NAME = "logs";
}

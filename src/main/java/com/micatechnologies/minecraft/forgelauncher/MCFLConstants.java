package com.micatechnologies.minecraft.forgelauncher;

import java.io.File;
import java.nio.file.Paths;

/**
 * Class of constants/statics for use across package.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class MCFLConstants {
    /**
     * Launcher application name
     */
    final static transient String LAUNCHER_APPLICATION_NAME = "Mica Forge Launcher";

    /**
     * Launcher client installation directory
     */
    final static transient String LAUNCHER_CLIENT_INSTALLATION_DIRECTORY = System.getProperty( "user.home" ) + File.separator + "." + LAUNCHER_APPLICATION_NAME.replaceAll( " ", "" );

    /**
     * Launcher server installation directory
     */
    final static transient String LAUNCHER_SERVER_INSTALLATION_DIRECTORY = Paths.get( "" ).toAbsolutePath().toString();

    /**
     * Launcher client saved user location
     */
    final static transient String LAUNCHER_CLIENT_SAVED_USER_FILE = LAUNCHER_CLIENT_INSTALLATION_DIRECTORY + File.separator + "launcher" + File.separator + "user.cache";

    /**
     * Launcher client saved token location
     */
    final static transient String LAUNCHER_CLIENT_TOKEN_FILE = LAUNCHER_CLIENT_INSTALLATION_DIRECTORY + File.separator + "launcher" + File.separator + "token.cache";

    final static transient String URL_JRE_WIN = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_windows_hotspot_8u232b09.zip";

    final static transient String URL_JRE_MAC = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_mac_hotspot_8u232b09.tar.gz";

    final static transient String URL_JRE_UNX = "https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u232-b09/OpenJDK8U-jre_x64_linux_hotspot_8u232b09.tar.gz";

    final static transient String URL_JRE_WIN_HASH = URL_JRE_WIN + ".sha256.txt";

    final static transient String URL_JRE_MAC_HASH = URL_JRE_MAC + ".sha256.txt";

    final static transient String URL_JRE_UNX_HASH = URL_JRE_UNX + ".sha256.txt";

    final static transient String JRE_EXTRACTED_FOLDER_NAME = "jdk8u232-b09-jre";

    final static String URL_MINECRAFT_USER_ICONS = "http://minotar.net/armor/body/user/100.png";

    final static String GUI_ACCENT_COLOR = "#002952";
    final static String GUI_LIGHT_COLOR = "#cccccc";
    final static String GUI_DARK_COLOR = "#5b5b5b";
}

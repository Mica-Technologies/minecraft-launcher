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
    final static transient String LAUNCHER_CLIENT_INSTALLATION_DIRECTORY = System.getProperty( "user.home" ) + File.separator + LAUNCHER_APPLICATION_NAME.replaceAll( " ", "" );

    /**
     * Launcher server installation directory
     */
    final static transient String LAUNCHER_SERVER_INSTALLATION_DIRECTORY = Paths.get( "" ).toAbsolutePath().toString();
}

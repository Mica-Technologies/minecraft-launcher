package com.micatechnologies.minecraft.forgelauncher;

import java.io.File;

public class Constants {
    final static transient String LAUNCHER_APPLICATION_NAME = "Mica Forge Launcher";
    final static transient String LAUNCHER_INSTALLATION_DIRECTORY = System.getProperty("user.home") + File.separator + LAUNCHER_APPLICATION_NAME.replaceAll(" ", "");
}

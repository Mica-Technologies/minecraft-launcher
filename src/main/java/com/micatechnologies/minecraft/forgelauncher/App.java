package com.micatechnologies.minecraft.forgelauncher;

import com.micatechnologies.minecraft.authlib.MCAuthAccount;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpack;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpackConsts;

import java.util.ArrayList;
import java.util.List;

public class App {
    //region: Statics/Constants
    public static final int MODE_CLIENT = MCForgeModpackConsts.MINECRAFT_CLIENT_MODE;
    public static final int MODE_SERVER = MCForgeModpackConsts.MINECRAFT_SERVER_MODE;
    //endregion

    //region: App Configuration & Information
    private static int mode = MODE_CLIENT;
    private static String javaPath = "java";
    private static String clientToken = "";
    private static MCAuthAccount currentUser = null;
    private static MCFLConfiguration launcherConfig = null;
    private static List< MCForgeModpack > modpacks = new ArrayList<>();
    //endregion

    //region: Get/Set Methods
    public static int getMode() {
        return mode;
    }

    public static String getJavaPath() {
        return javaPath;
    }

    public static String getClientToken() {
        return clientToken;
    }

    public static MCAuthAccount getCurrentUser() {
        return currentUser;
    }

    public static MCFLConfiguration getLauncherConfig() {
        return launcherConfig;
    }
    //endregion
}

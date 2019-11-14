package com.micatechnologies.minecraft.forgelauncher;

/**
 * Simple class for managing logging in {@link LauncherCore}.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
class LauncherLogger {

    /**
     * Print/log the specified error text to the applicable outputs for launcher mode.
     *
     * @param msg error text
     * @since 1.0
     */
    static void doErrorLog(String msg) {
        System.err.println("[" + LauncherConstants.LAUNCHER_SHORT_NAME + "/ERR] " + msg);
    }

    /**
     * Print/log the specified text to the applicable outputs for launcher mode.
     *
     * @param msg message text
     * @since 1.0
     */
    static void doStandardLog(String msg) {
        System.out.println("[" + LauncherConstants.LAUNCHER_SHORT_NAME + "/STD] " + msg);
    }

    /**
     * Print/log the specified debug text to the applicable output for launcher mode.
     *
     * @param msg debug text
     * @since 1.0
     */
    static void doDebugLog(String msg) {
        if (LauncherCore.launcherConfig.debug) {
            System.out.println("[" + LauncherConstants.LAUNCHER_SHORT_NAME + "/DBG] " + msg);
        }
    }
}
